package com.tingyun.smartmistakebook.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.tingyun.smartmistakebook.core.model.StreamingMarkdownAssembler
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        suspend fun parseWork(characterCount: Int): Pair<Long, String> {
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
            val work = parser.parsedCharacterCount +
                planner.appliedCharacterCount +
                visible.length
            return work to visible
        }

        val (workAt2k, visibleAt2k) = parseWork(2_048)
        val (workAt4k, visibleAt4k) = parseWork(4_096)

        assertEquals(2_048, visibleAt2k.length)
        assertEquals(4_096, visibleAt4k.length)
        org.junit.Assert.assertTrue(
            "doubling input must keep UI parse work near-linear: " +
                "2k=$workAt2k, 4k=$workAt4k",
            workAt4k * 10 <= workAt2k * 22,
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
}

private class RecordingDispatcher : CoroutineDispatcher() {
    var dispatchCount: Int = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount += 1
        block.run()
    }
}
