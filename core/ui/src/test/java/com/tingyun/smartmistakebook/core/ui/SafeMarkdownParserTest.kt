package com.tingyun.smartmistakebook.core.ui

import androidx.compose.ui.text.AnnotatedString
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}

private class RecordingDispatcher : CoroutineDispatcher() {
    var dispatchCount: Int = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount += 1
        block.run()
    }
}
