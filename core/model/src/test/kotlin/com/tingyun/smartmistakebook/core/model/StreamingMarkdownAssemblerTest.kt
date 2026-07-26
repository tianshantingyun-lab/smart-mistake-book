package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownAssemblerTest {
    @Test
    fun blankLineCommitsTheFinishedParagraphAndKeepsTheActiveParagraphProvisional() {
        val assembler = StreamingMarkdownAssembler()

        assertEquals(
            TutorMarkdownSnapshot("", "第一段"),
            assembler.append("第一段"),
        )
        assertEquals(
            TutorMarkdownSnapshot("第一段。\n\n", "第二段"),
            assembler.append("。\n\n第二段"),
        )
    }

    @Test
    fun unfinishedInlineAndDisplayFormulaContentIsNotVisible() {
        val inline = StreamingMarkdownAssembler()
        assertEquals(
            TutorMarkdownSnapshot("", "由 "),
            inline.append("由 \$x +"),
        )
        assertEquals(
            TutorMarkdownSnapshot("", "由 \$x + y\$ 可得"),
            inline.append(" y\$ 可得"),
        )

        val display = StreamingMarkdownAssembler()
        assertEquals(
            TutorMarkdownSnapshot("前文。\n\n", ""),
            display.append("前文。\n\n\$\$x +"),
        )
        assertEquals(
            TutorMarkdownSnapshot("前文。\n\n\$\$x + y\$\$\n\n", ""),
            display.append(" y\$\$\n\n"),
        )
    }

    @Test
    fun unfinishedFenceIsHeldUntilItsClosingLineIsComplete() {
        val assembler = StreamingMarkdownAssembler()

        assertEquals(
            TutorMarkdownSnapshot("说明：\n\n", ""),
            assembler.append("说明：\n\n```text\n\$不是公式"),
        )
        assertEquals(
            TutorMarkdownSnapshot("说明：\n\n", ""),
            assembler.append("\n```"),
        )
        assertEquals(
            TutorMarkdownSnapshot("说明：\n\n```text\n\$不是公式\n```\n\n", ""),
            assembler.append("\n\n"),
        )
    }

    @Test
    fun tableIsHeldUntilAFollowingBlockEstablishesItsBoundary() {
        val assembler = StreamingMarkdownAssembler()

        assertEquals(
            TutorMarkdownSnapshot.EMPTY,
            assembler.append("| 项 | 值 |\n"),
        )
        assertEquals(
            TutorMarkdownSnapshot.EMPTY,
            assembler.append("|---|---|\n| a | 1 |\n"),
        )
        assertEquals(
            TutorMarkdownSnapshot(
                stableMarkdown = "| 项 | 值 |\n|---|---|\n| a | 1 |\n\n",
                provisionalMarkdown = "下一段",
            ),
            assembler.append("\n下一段"),
        )
    }

    @Test
    fun arbitraryChunkingProducesTheSameAcceptedSnapshot() {
        val markdown = """
            第一段。

            由 ${'$'}x + y${'$'} 得到结果。

            | 项 | 值 |
            |---|---|
            | a | 1 |

            ```text
            完成
            ```
        """.trimIndent()

        val whole = StreamingMarkdownAssembler().run {
            append(markdown)
            complete()
        }
        val characterByCharacter = StreamingMarkdownAssembler().run {
            markdown.forEach { append(it.toString()) }
            complete()
        }

        assertEquals(whole, characterByCharacter)
        assertEquals(
            StreamingMarkdownCompletion.Accepted(
                TutorMarkdownSnapshot(markdown, ""),
            ),
            whole,
        )
    }

    @Test
    fun completionWithAnUnclosedDisplayFormulaOrTableKeepsOnlyPreviouslyStableContent() {
        val cases = listOf(
            "已稳定。\n\n\$\$x + y",
            "已稳定。\n\n| 表头 | 数值 |\n",
        )

        cases.forEach { markdown ->
            val assembler = StreamingMarkdownAssembler()
            assembler.append(markdown)

            assertEquals(
                StreamingMarkdownCompletion.Rejected(
                    TutorMarkdownSnapshot("已稳定。\n\n", ""),
                ),
                assembler.complete(),
            )
        }
    }

    @Test
    fun aStructurallyCompleteTableAtEndOfStreamIsAccepted() {
        val markdown = "| 项 | 值 |\n|---|---|\n| a | 1 |"
        val assembler = StreamingMarkdownAssembler()

        assertEquals(TutorMarkdownSnapshot.EMPTY, assembler.append(markdown))
        assertEquals(
            StreamingMarkdownCompletion.Accepted(
                TutorMarkdownSnapshot(markdown, ""),
            ),
            assembler.complete(),
        )
    }

    @Test
    fun ordinaryPipeTextWithOrWithoutTrailingNewlineIsAcceptedAtEndOfStream() {
        listOf(
            "条件 A | B",
            "条件 A | B\n",
        ).forEach { markdown ->
            val assembler = StreamingMarkdownAssembler()

            assertEquals(TutorMarkdownSnapshot.EMPTY, assembler.append(markdown))
            assertEquals(
                StreamingMarkdownCompletion.Accepted(
                    TutorMarkdownSnapshot(markdown, ""),
                ),
                assembler.complete(),
            )
        }
    }

    @Test
    fun incompleteInlineMarkupAndFenceAreWithheldThenLiteralizedAtEndOfStream() {
        val cases = listOf(
            Triple(
                "价格是 \$5",
                TutorMarkdownSnapshot("", "价格是 "),
                "价格是 ＄5",
            ),
            Triple(
                "末尾单反引号`",
                TutorMarkdownSnapshot("", "末尾单反引号"),
                "末尾单反引号｀",
            ),
            Triple(
                "已稳定。\n\n```text\n<b>未关闭</b>",
                TutorMarkdownSnapshot("已稳定。\n\n", ""),
                "已稳定。\n\n｀｀｀text\n＜b＞未关闭＜/b＞",
            ),
        )

        cases.forEach { (markdown, streamingSnapshot, completedMarkdown) ->
            val assembler = StreamingMarkdownAssembler()

            assertEquals(streamingSnapshot, assembler.append(markdown))
            val completion = StreamingMarkdownCompletion.Accepted(
                TutorMarkdownSnapshot(completedMarkdown, ""),
            )
            assertEquals(completion, assembler.complete())
            assertFalse(
                SafeInlineMarkdown.requiresPlainTextFallback(
                    completion.snapshot.visibleMarkdown,
                ),
            )
        }
    }

    @Test
    fun activeMarkupIsLiteralizedBeforeItCanEnterASnapshot() {
        val assembler = StreamingMarkdownAssembler()
        val raw = "<b>重点</b> data:text/plain ![图](https://example.test/a.png)\n\n"

        val snapshot = assembler.append(raw)

        assertFalse(SafeInlineMarkdown.requiresPlainTextFallback(snapshot.visibleMarkdown))
        assertFalse(snapshot.visibleMarkdown.contains("<b>"))
        assertFalse(snapshot.visibleMarkdown.contains("data:"))
        assertFalse(snapshot.visibleMarkdown.contains("![图](https://"))
        assertTrue(snapshot.visibleMarkdown.contains("重点"))
    }
}
