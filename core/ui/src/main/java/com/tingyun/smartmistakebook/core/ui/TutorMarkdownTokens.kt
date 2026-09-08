package com.tingyun.smartmistakebook.core.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 讲题正文的行内排版令牌——流式(SafeMarkdownText)与终稿 richtext 共用同一族 span 样式，
 * 使"中途→完成"的行内强/斜/码/公式不跳变。
 *
 * - `SafeMarkdownText`(streaming/回退) 经 [StructuredContentRenderer] 直接应用这些 SpanStyle；
 * - richtext 经 [AiReplyRichMarkdown] 以 [RichTextStringStyle] 注入同样的 bold/italic/code span。
 *
 * 数据层已保证正文不含 URL/HTML/图片，故这里不再单独做安全判定。
 */
object TutorMarkdownTokens {
    /** 行内正文基线。 */
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.6.sp,
    )

    /** 行内 `**加粗**`：加粗字重 + JadeSoft 半透明底。 */
    val strong: SpanStyle = SpanStyle(
        fontWeight = FontWeight.ExtraBold,
        background = JadeSoft.copy(alpha = 0.25f),
    )

    /** 行内 `*强调*`：斜体。 */
    val emphasis: SpanStyle = SpanStyle(fontStyle = FontStyle.Italic)

    /** 行内 `` `代码` ``：mono + JadeSoft 底。 */
    val code: SpanStyle = SpanStyle(
        background = JadeSoft,
        fontFamily = FontFamily.Monospace,
    )

    /** 行内 `$公式$`：serif + 中等字重，公式更清晰。 */
    val formula: SpanStyle = SpanStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
    )
}
