package com.tingyun.smartmistakebook.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.tingyun.smartmistakebook.core.model.StreamingMarkdownAssembler
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMarkdownParserTest {
    @Test
    fun `safe markdown parsing uses the supplied background dispatcher`() = runBlocking {
        val dispatcher = RecordingDispatcher()

        val result = parseSafeMarkdown("**重点** 和 `x`", dispatcher)

        assertEquals(1, dispatcher.dispatchCount)
        assertEquals("重点 和 x", result.text)
    }

    @Test
    fun `unrelated previous parse is hidden while replacement is parsing`() {
        val parsed = SafeMarkdownParseResult(
            source = "上一条答案",
            contentIdentity = "old-turn",
            annotated = AnnotatedString("上一条答案"),
        )

        val visible = safeMarkdownWhileParsing(
            displayText = "上一条答案的补充",
            contentIdentity = "new-turn",
            parsed = parsed,
        )

        assertEquals("", visible.text)
    }

    @Test
    fun `safe parsed prefix remains visible while stream grows`() {
        val parsed = SafeMarkdownParseResult(
            source = "先看已知条件",
            contentIdentity = "same-turn",
            annotated = AnnotatedString("先看已知条件"),
        )

        val visible = safeMarkdownWhileParsing(
            displayText = "先看已知条件，再列方程",
            contentIdentity = "same-turn",
            parsed = parsed,
        )

        assertEquals("先看已知条件", visible.text)
    }

    @Test
    fun `provisional rollback falls back to the parsed stable markdown`() {
        val parsedStable = SafeMarkdownParseResult(
            source = "先看已知条件。\n\n",
            contentIdentity = "same-turn",
            annotated = AnnotatedString("先看已知条件。\n\n"),
        )
        val parsedVisible = SafeMarkdownParseResult(
            source = "先看已知条件。\n\n未完成的公式",
            contentIdentity = "same-turn",
            annotated = AnnotatedString("先看已知条件。\n\n未完成的公式"),
        )

        val visible = streamingMarkdownWhileParsing(
            stableText = "先看已知条件。\n\n",
            provisionalText = "改为列方程",
            contentIdentity = "same-turn",
            parsedStable = parsedStable,
            parsedVisible = parsedVisible,
        )

        assertEquals("先看已知条件。\n\n", visible.text)
    }

    @Test
    fun `streaming markdown never reuses a parse from another response`() {
        val parsedStable = SafeMarkdownParseResult(
            source = "相同前缀",
            contentIdentity = "old-turn",
            annotated = AnnotatedString("相同前缀"),
        )
        val parsedVisible = SafeMarkdownParseResult(
            source = "相同前缀和后缀",
            contentIdentity = "old-turn",
            annotated = AnnotatedString("相同前缀和后缀"),
        )

        val visible = streamingMarkdownWhileParsing(
            stableText = "相同前缀",
            provisionalText = "和后缀",
            contentIdentity = "new-turn",
            parsedStable = parsedStable,
            parsedVisible = parsedVisible,
        )

        assertEquals("", visible.text)
    }

    @Test
    fun `promoting parsed provisional markdown to stable keeps one complete copy visible`() {
        val parsedStable = SafeMarkdownParseResult(
            source = "第一段。",
            contentIdentity = "same-turn",
            annotated = AnnotatedString("第一段。"),
        )
        val parsedVisible = SafeMarkdownParseResult(
            source = "第一段。第二段。",
            contentIdentity = "same-turn",
            annotated = AnnotatedString("第一段。第二段。"),
        )

        val visible = streamingMarkdownWhileParsing(
            stableText = "第一段。第二段。",
            provisionalText = "",
            contentIdentity = "same-turn",
            parsedStable = parsedStable,
            parsedVisible = parsedVisible,
        )

        assertEquals("第一段。第二段。", visible.text)
    }

    @Test
    fun `streaming parser retains parsed chunks when preview input doubles`() = runBlocking {
        suspend fun parseWork(characterCount: Int): Triple<Long, Long, String> {
            var nowNanos = 0L
            val assembler = StreamingMarkdownAssembler(clockNanos = { nowNanos })
            val parser = IncrementalSafeMarkdownParser()
            val planner = IncrementalTextPatchPlanner()
            val carrier = StringBuilder()
            repeat(characterCount) {
                nowNanos += 64_000_000L
                val patch = planner.plan(
                    parser.parse(
                        snapshot = assembler.append("a"),
                        contentIdentity = "same-turn",
                    ),
                )
                carrier.setLength(carrier.length - patch.deleteSuffixCharacterCount)
                patch.appendedChunks.forEach { carrier.append(it.text) }
            }
            val visible = carrier.toString()
            val cacheWork = parser.cacheMaintenanceWorkCount
            val work = parser.parsedCharacterCount +
                cacheWork +
                planner.appliedCharacterCount +
                visible.length
            return Triple(work, cacheWork, visible)
        }

        val (workAt2k, cacheWorkAt2k, visibleAt2k) = parseWork(2_048)
        val (workAt4k, cacheWorkAt4k, visibleAt4k) = parseWork(4_096)

        assertEquals(2_048, visibleAt2k.length)
        assertEquals(4_096, visibleAt4k.length)
        org.junit.Assert.assertTrue(cacheWorkAt2k >= 2_048)
        org.junit.Assert.assertTrue(cacheWorkAt4k >= 4_096)
        org.junit.Assert.assertTrue(
            "doubling input must keep UI parse work near-linear: " +
                "2k=$workAt2k (cache=$cacheWorkAt2k), " +
                "4k=$workAt4k (cache=$cacheWorkAt4k)",
            workAt4k * 10 <= workAt2k * 22,
        )
    }

    @Test
    fun `stale same identity parses do not publish cache entries`() = runBlocking {
        var nowNanos = 0L
        val assembler = StreamingMarkdownAssembler(clockNanos = { nowNanos })
        val parser = IncrementalSafeMarkdownParser()
        val requests = List(32) {
            nowNanos += 64_000_000L
            parser.prepare(
                snapshot = assembler.append("a"),
                contentIdentity = "same-turn",
            )
        }

        val finalResult = requests.fold(
            StreamingMarkdownRenderState.empty("same-turn"),
        ) { _, request -> parser.parse(request) }

        assertEquals("a".repeat(32), finalResult.materialize().text)
        assertEquals(1L, parser.cacheMaintenanceWorkCount)
    }

    @Test
    fun `published cache state makes its rollback entry visible atomically`() = runBlocking {
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val bParsed = CountDownLatch(1)
        val bCompleted = CountDownLatch(1)
        val blockedKey = AtomicReference<StreamingMarkdownParseKey?>()
        val parser = IncrementalSafeMarkdownParser(
            dispatcher = Dispatchers.Default,
            chunkParser = { markdown ->
                if (markdown == "B") bParsed.countDown()
                AnnotatedString(markdown)
            },
            afterStatePublished = { key ->
                if (key == blockedKey.get()) {
                    publishEntered.countDown()
                    check(releasePublish.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val assembler = StreamingMarkdownAssembler()
        val identity = "same-turn"
        parser.parse(assembler.append("稳定\n\n"), identity)

        val snapshotA = assembler.append("A")
        val requestA = parser.prepare(snapshotA, identity)
        blockedKey.set(requestA.key)
        val parseA = async(Dispatchers.Default) {
            parser.parse(requestA)
        }
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            "稳定\n\nA",
            parser.visibleWhileParsing(snapshotA, identity).materialize().text,
        )

        val snapshotB = assembler.append("B")
        val requestB = parser.prepare(snapshotB, identity)
        val parseB = async(Dispatchers.Default) {
            try {
                parser.parse(requestB)
            } finally {
                bCompleted.countDown()
            }
        }
        assertTrue(bParsed.await(5, TimeUnit.SECONDS))

        val bCompletedBeforeRelease: Boolean
        val rollbackBeforeRelease: StreamingMarkdownRenderState
        try {
            bCompletedBeforeRelease = bCompleted.await(1, TimeUnit.SECONDS)
            rollbackBeforeRelease = parser.visibleWhileParsing(snapshotA, identity)
        } finally {
            releasePublish.countDown()
        }
        parseA.await()
        parseB.await()

        assertEquals("稳定\n\nA", rollbackBeforeRelease.materialize().text)
        assertFalse(
            "B must not publish through A's incomplete publication",
            bCompletedBeforeRelease,
        )
        assertEquals(
            "稳定\n\nA",
            parser.visibleWhileParsing(snapshotA, identity).materialize().text,
        )
        assertEquals(2L, parser.cacheMaintenanceWorkCount)
    }

    @Test
    fun `cache hit reasserts an entry removed by a losing staged publisher`() = runBlocking {
        val entriesStaged = CountDownLatch(1)
        val releaseStagedEntries = CountDownLatch(1)
        val bParsed = CountDownLatch(1)
        val blockedKey = AtomicReference<StreamingMarkdownParseKey?>()
        val parser = IncrementalSafeMarkdownParser(
            dispatcher = Dispatchers.Default,
            chunkParser = { markdown ->
                if (markdown == "\\") bParsed.countDown()
                AnnotatedString(markdown)
            },
            afterCacheEntriesStaged = { key ->
                if (key == blockedKey.get()) {
                    entriesStaged.countDown()
                    check(releaseStagedEntries.await(5, TimeUnit.SECONDS))
                }
            },
        )
        var nowNanos = 0L
        val assembler = StreamingMarkdownAssembler(clockNanos = { nowNanos })
        val identity = "same-turn"
        parser.parse(assembler.append("稳定\n\n"), identity)

        nowNanos += 64_000_000L
        val snapshotA = assembler.append("A")
        val requestA = parser.prepare(snapshotA, identity)
        blockedKey.set(requestA.key)
        val parseA = async(Dispatchers.Default) {
            parser.parse(requestA)
        }
        assertTrue(entriesStaged.await(5, TimeUnit.SECONDS))

        val snapshotB = assembler.append("\\")
        assertSame(snapshotA.provisionalContent, snapshotB.provisionalContent)
        val parseB = async(Dispatchers.Default) {
            parser.parse(parser.prepare(snapshotB, identity))
        }
        assertTrue(bParsed.await(5, TimeUnit.SECONDS))

        releaseStagedEntries.countDown()
        parseA.await()
        parseB.await()

        val snapshotC = assembler.append("x")
        parser.parse(snapshotC, identity)
        assertEquals(
            "稳定\n\nA",
            parser.visibleWhileParsing(snapshotA, identity).materialize().text,
        )
    }

    @Test
    fun `streaming parser preserves inline semantics across provider chunks`() = runBlocking {
        var nowNanos = 0L
        val assembler = StreamingMarkdownAssembler(clockNanos = { nowNanos })
        val parser = IncrementalSafeMarkdownParser()
        var visible = StreamingMarkdownRenderState.empty("same-turn")

        "*重点*".forEach { character ->
            nowNanos += 64_000_000L
            visible = parser.parse(
                snapshot = assembler.append(character.toString()),
                contentIdentity = "same-turn",
            )
        }

        val materialized = visible.materialize()
        assertEquals("重点", materialized.text)
        assertTrue(
            materialized.spanStyles.any { range ->
                range.start == 0 &&
                    range.end == materialized.length &&
                    range.item.fontStyle == FontStyle.Italic
            },
        )
    }

    @Test
    fun `incremental carrier replaces a rolled back tail without rebuilding its prefix`() =
        runBlocking {
            var nowNanos = 0L
            val assembler = StreamingMarkdownAssembler(clockNanos = { nowNanos })
            val parser = IncrementalSafeMarkdownParser()
            val planner = IncrementalTextPatchPlanner()
            val carrier = StringBuilder()
            var sawRollback = false

            repeat(4) {
                nowNanos += 64_000_000L
                val state = parser.parse(
                    snapshot = assembler.append("\\"),
                    contentIdentity = "same-turn",
                )
                val patch = planner.plan(state)
                sawRollback = sawRollback || patch.deleteSuffixCharacterCount > 0
                carrier.setLength(carrier.length - patch.deleteSuffixCharacterCount)
                patch.appendedChunks.forEach { carrier.append(it.text) }
                assertEquals(state.materialize().text, carrier.toString())
            }

            assertTrue(sawRollback)
            assertTrue(planner.appliedCharacterCount <= 10)
        }

    @Test
    fun `stale provisional result yields stable fallback before current parse completes`() {
        val identity = "same-turn"
        val oldSnapshot = TutorMarkdownSnapshot("稳定", "旧尾")
        val currentSnapshot = TutorMarkdownSnapshot("稳定", "新尾")
        val oldKey = StreamingMarkdownParseKey(
            stableContent = oldSnapshot.stableContent,
            provisionalContent = oldSnapshot.provisionalContent,
            provisionalTail = oldSnapshot.provisionalTail,
            contentIdentity = identity,
        )
        val currentKey = StreamingMarkdownParseKey(
            stableContent = oldSnapshot.stableContent,
            provisionalContent = currentSnapshot.provisionalContent,
            provisionalTail = currentSnapshot.provisionalTail,
            contentIdentity = identity,
        )
        val oldWhole = StreamingMarkdownRenderState(
            stable = ParsedMarkdownChunkChain.EMPTY.append(AnnotatedString("稳定")),
            provisional = ParsedMarkdownChunkChain.EMPTY.append(AnnotatedString("旧尾")),
            provisionalTail = AnnotatedString(""),
            contentIdentity = identity,
        )
        val stableFallback = StreamingMarkdownRenderState(
            stable = oldWhole.stable,
            provisional = ParsedMarkdownChunkChain.EMPTY,
            provisionalTail = AnnotatedString(""),
            contentIdentity = identity,
        )
        val currentWhole = StreamingMarkdownRenderState(
            stable = stableFallback.stable,
            provisional = ParsedMarkdownChunkChain.EMPTY.append(AnnotatedString("新尾")),
            provisionalTail = AnnotatedString(""),
            contentIdentity = identity,
        )

        assertSame(
            stableFallback,
            streamingMarkdownWhileParsing(
                currentKey = currentKey,
                fallback = stableFallback,
                parsed = StreamingMarkdownParseResult(oldKey, oldWhole),
            ),
        )
        assertSame(
            currentWhole,
            streamingMarkdownWhileParsing(
                currentKey = currentKey,
                fallback = stableFallback,
                parsed = StreamingMarkdownParseResult(currentKey, currentWhole),
            ),
        )
    }

    @Test
    fun `new identity fallback returns before superseded parse is released`() = runBlocking {
        val parseEntered = CountDownLatch(1)
        val releaseParse = CountDownLatch(1)
        val parser = IncrementalSafeMarkdownParser(
            dispatcher = Dispatchers.Default,
            chunkParser = { markdown ->
                if (markdown == "旧尾") {
                    parseEntered.countDown()
                    check(releaseParse.await(5, TimeUnit.SECONDS))
                }
                AnnotatedString(markdown)
            },
        )
        val oldParse = async(Dispatchers.Default) {
            parser.parse(
                snapshot = TutorMarkdownSnapshot("", "旧尾"),
                contentIdentity = "old-turn",
            )
        }
        assertTrue(parseEntered.await(5, TimeUnit.SECONDS))

        val currentSnapshot = TutorMarkdownSnapshot("新稳定", "")
        val readStarted = CountDownLatch(1)
        val readCompleted = CountDownLatch(1)
        val readResult = AtomicReference<StreamingMarkdownRenderState?>()
        val readFailure = AtomicReference<Throwable?>()
        val reader = thread(name = "new-identity-fallback") {
            readStarted.countDown()
            try {
                readResult.set(
                    parser.visibleWhileParsing(
                        snapshot = currentSnapshot,
                        contentIdentity = "new-turn",
                    ),
                )
            } catch (throwable: Throwable) {
                readFailure.set(throwable)
            } finally {
                readCompleted.countDown()
            }
        }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))

        val returnedBeforeRelease = try {
            readCompleted.await(1, TimeUnit.SECONDS)
        } finally {
            releaseParse.countDown()
            reader.join(5_000)
        }
        oldParse.await()
        readFailure.get()?.let { throw it }
        val initialFallback = checkNotNull(readResult.get())

        assertTrue(
            "new identity fallback must not wait for a superseded parse",
            returnedBeforeRelease,
        )
        assertEquals("new-turn", initialFallback.contentIdentity)
        assertEquals("", initialFallback.materialize().text)
        assertEquals(
            "",
            parser.visibleWhileParsing(currentSnapshot, "new-turn").materialize().text,
        )
    }

    @Test
    fun `same identity rollback returns stable prefix before old parse is released`() =
        runBlocking {
            val parseEntered = CountDownLatch(1)
            val releaseParse = CountDownLatch(1)
            val parser = IncrementalSafeMarkdownParser(
                dispatcher = Dispatchers.Default,
                chunkParser = { markdown ->
                    if (markdown == "A") {
                        parseEntered.countDown()
                        check(releaseParse.await(5, TimeUnit.SECONDS))
                    }
                    AnnotatedString(markdown)
                },
            )
            val assembler = StreamingMarkdownAssembler()
            val identity = "same-turn"
            parser.parse(assembler.append("稳定\n\n"), identity)
            val oldSnapshot = assembler.append("A")
            val oldParse = async(Dispatchers.Default) {
                parser.parse(oldSnapshot, identity)
            }
            assertTrue(parseEntered.await(5, TimeUnit.SECONDS))

            val rollbackSnapshot = assembler.append(" | B")
            val readStarted = CountDownLatch(1)
            val readCompleted = CountDownLatch(1)
            val readResult = AtomicReference<StreamingMarkdownRenderState?>()
            val readFailure = AtomicReference<Throwable?>()
            val reader = thread(name = "same-identity-rollback") {
                readStarted.countDown()
                try {
                    readResult.set(parser.visibleWhileParsing(rollbackSnapshot, identity))
                } catch (throwable: Throwable) {
                    readFailure.set(throwable)
                } finally {
                    readCompleted.countDown()
                }
            }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))

            val returnedBeforeRelease = try {
                readCompleted.await(1, TimeUnit.SECONDS)
            } finally {
                releaseParse.countDown()
                reader.join(5_000)
            }
            oldParse.await()
            readFailure.get()?.let { throw it }
            val rollbackFallback = checkNotNull(readResult.get())

            assertTrue(
                "same identity rollback must not wait for the old parse",
                returnedBeforeRelease,
            )
            assertEquals("稳定\n\n", rollbackFallback.materialize().text)
            assertEquals(
                "稳定\n\n",
                parser.visibleWhileParsing(rollbackSnapshot, identity).materialize().text,
            )
        }
}

private class RecordingDispatcher : CoroutineDispatcher() {
    var dispatchCount: Int = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount += 1
        block.run()
    }
}
