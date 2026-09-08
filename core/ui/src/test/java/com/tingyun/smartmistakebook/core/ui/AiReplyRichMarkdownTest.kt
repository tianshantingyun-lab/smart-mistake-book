package com.tingyun.smartmistakebook.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyRichMarkdownTest {
    @Test
    fun richTextAllowedForWhitelistedBlockMarkdown() {
        assertTrue(shouldUseRichTextMarkdown("# 先看符号\n\n- 步骤一\n- 步骤二"))
        assertTrue(shouldUseRichTextMarkdown("**重点** 与 `代码`"))
        assertTrue(shouldUseRichTextMarkdown("> 引用一句讲解"))
    }

    @Test
    fun richTextFallsBackForRawHtml() {
        assertFalse(shouldUseRichTextMarkdown("答案 <div>不是这样</div>"))
        assertFalse(shouldUseRichTextMarkdown("<script>run()</script>"))
    }

    @Test
    fun richTextFallsBackForActiveSchemeAndRemoteImage() {
        assertFalse(shouldUseRichTextMarkdown("点我 javascript:alert(1)"))
        assertFalse(shouldUseRichTextMarkdown("![图](https://evil.com/x.png)"))
        assertFalse(shouldUseRichTextMarkdown("详见 https://example.com"))
    }

    @Test
    fun richTextFallsBackForBlank() {
        assertFalse(shouldUseRichTextMarkdown(""))
        assertFalse(shouldUseRichTextMarkdown("   "))
    }
}
