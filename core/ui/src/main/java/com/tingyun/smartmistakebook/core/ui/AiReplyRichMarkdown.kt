package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.markdown.MarkdownParseOptions
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.RichText
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.SetupMaterial3RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import com.tingyun.smartmistakebook.core.model.SafeInlineMarkdown
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits

/**
 * 终稿块级渲染的 fail-closed 判定：只有不含任何 HTML/active scheme/远程图片/裸 URL 的标记，
 * 才值得交给 richtext 完整 commonmark；否则回退到受限 SafeMarkdownText。
 * 裸 URL 判定复用数据层 `SafeInlineMarkdown.BARE_URL`——单一真源，随数据层扩展自动生效，避免两处正则漂移。
 * 纯函数，便于单元测试锁定。
 */
internal fun shouldUseRichTextMarkdown(markdown: String): Boolean {
    if (markdown.isBlank()) return false
    if (markdown.length > StructuredContentLimits.MAX_TEXT_CHARS) return false
    if (SafeInlineMarkdown.BARE_URL.containsMatchIn(markdown)) return false
    return !SafeInlineMarkdown.requiresPlainTextFallback(markdown)
}

/**
 * 终稿块级渲染器：以 richtext 完整 commonmark 渲染模型讲解正文。终端渲染，只用于已
 * SUCCEEDED 的完整讲解；流式仍走 SafeMarkdownText。
 *
 * 安全（fail-closed）：数据层先经 requireTutorSceneText 白名单（禁 HTML/链接/URL/图片）。
 * 渲染前再兜底：检测到 HTML/active scheme/远程图片即回退到受限 SafeMarkdownText；并关掉
 * 自动链接、把链接点击设为 no-op，避免任何导航/外发。
 *
 * 行内样式与流式/回退共用 [TutorMarkdownTokens]——bold/italic/code span 经 [RichTextStringStyle]
 * 注入，使"中途→完成"不跳变。
 */
@Composable
fun AiReplyRichMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    if (!shouldUseRichTextMarkdown(markdown)) {
        SafeMarkdownText(
            markdown = markdown,
            style = TutorMarkdownTokens.body,
            modifier = modifier,
        )
        return
    }

    SetupMaterial3RichText {
        RichText(
            modifier = modifier,
            style = RichTextStyle(
                stringStyle = RichTextStringStyle(
                    boldStyle = TutorMarkdownTokens.strong,
                    italicStyle = TutorMarkdownTokens.emphasis,
                    codeStyle = TutorMarkdownTokens.code,
                ),
                codeBlockStyle = CodeBlockStyle(
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    wordWrap = true,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                        ),
                ),
            ),
        ) {
            Markdown(
                markdown,
                MarkdownParseOptions(autolink = false),
                { /* fail-closed：no-op，链接惰性 */ },
            )
        }
    }
}
