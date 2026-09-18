package com.tingyun.smartmistakebook.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBlocksTest {
    @Test
    fun blankLinesCloseBlocksAndTheGrowingTailStaysSeparate() {
        val blocks = splitStreamingBlocks("第一段讲完了。\n\n第二段还在继")

        assertEquals(listOf("第一段讲完了。"), blocks.completed)
        assertEquals("第二段还在继", blocks.tail)
    }

    @Test
    fun aTrailingBlankLineLeavesNothingInTheTail() {
        val blocks = splitStreamingBlocks("第一段。\n\n第二段。\n\n")

        assertEquals(listOf("第一段。", "第二段。"), blocks.completed)
        assertEquals("", blocks.tail)
    }

    @Test
    fun displayMathKeepsItsBlankLinesInsideOneBlock() {
        val blocks = splitStreamingBlocks("配方如下。\n\n$$\nx^{2}-5x+6=0\n$$\n\n下一段开始")

        assertEquals(listOf("配方如下。", "$$\nx^{2}-5x+6=0\n$$"), blocks.completed)
        assertEquals("下一段开始", blocks.tail)
    }

    @Test
    fun anOpenDisplayMathBlockStaysInTheTailUntillItCloses() {
        val blocks = splitStreamingBlocks("先看这一步。\n\n$$\nx^{2}")

        assertEquals(listOf("先看这一步。"), blocks.completed)
        assertEquals("$$\nx^{2}", blocks.tail)
    }

    @Test
    fun codeFenceBlankLinesDoNotSplitTheBlock() {
        val blocks = splitStreamingBlocks("示例：\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n结束语")

        assertEquals(listOf("示例：", "```kotlin\nval a = 1\n\nval b = 2\n```"), blocks.completed)
        assertEquals("结束语", blocks.tail)
    }

    @Test
    fun anUnterminatedCodeFenceStaysInTheTail() {
        val blocks = splitStreamingBlocks("示例：\n\n```kotlin\nval a = 1")

        assertEquals(listOf("示例："), blocks.completed)
        assertEquals("```kotlin\nval a = 1", blocks.tail)
    }

    @Test
    fun unterminatedInlineMarkdownStaysInTheTailForTolerantRendering() {
        val blocks = splitStreamingBlocks("结论是 **还没写完\n\n下一段")

        // The unfinished bold run belongs to the tail; rendering it as tolerant text cannot flicker.
        assertEquals(listOf("结论是 **还没写完"), blocks.completed)
        assertEquals("下一段", blocks.tail)
    }

    @Test
    fun blankMarkdownProducesNoBlocks() {
        val blocks = splitStreamingBlocks("   \n\n")

        assertTrue(blocks.completed.isEmpty())
        assertEquals("", blocks.tail)
    }
}
