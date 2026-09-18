package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.ReadableMathText

/**
 * 讲题正文的一段：普通 Markdown，或一段独立公式（`$$…$$`）。
 * 独立公式交给既有的数学引擎渲染；行内公式不切段（否则会打断 Markdown 列表/段落），
 * 由 [splitTutorProseSegments] 先转成可读数学文本再原样留在 Markdown 里。
 */
internal sealed interface TutorProseSegment {
    data class Markdown(val text: String) : TutorProseSegment
    data class DisplayMath(val latex: String) : TutorProseSegment
}

/**
 * 按独立公式 `$$…$$` 切段，并把行内公式 `$…$` 转成可读数学（[ReadableMathText.formula]，
 * 解析失败原样透传，与题干渲染同一套约定）。
 *
 * 行内公式只认同一行内闭合的 `$…$`（不跨行、非空），`\$` 视为转义；这样普通文本里的
 * 单个 `$` 不会吞掉后面整段讲解。
 */
internal fun splitTutorProseSegments(markdown: String): List<TutorProseSegment> {
    if (markdown.isEmpty()) return emptyList()
    val segments = ArrayList<TutorProseSegment>()
    val text = StringBuilder()
    var index = 0
    fun flushText() {
        if (text.isNotEmpty()) {
            segments.add(TutorProseSegment.Markdown(text.toString()))
            text.setLength(0)
        }
    }
    while (index < markdown.length) {
        if (markdown.startsWith("$$", index)) {
            val end = markdown.indexOf("$$", index + 2)
            if (end > index + 2) {
                flushText()
                segments.add(TutorProseSegment.DisplayMath(markdown.substring(index + 2, end)))
                index = end + 2
                continue
            }
        }
        if (markdown[index] == '$' && (index == 0 || markdown[index - 1] != '\\')) {
            val end = markdown.indexOf('$', index + 1)
            val inner = if (end > index + 1) markdown.substring(index + 1, end) else null
            if (inner != null && '\n' !in inner && inner.isNotBlank()) {
                text.append(ReadableMathText.formula(inner))
                index = end + 1
                continue
            }
        }
        text.append(markdown[index])
        index += 1
    }
    flushText()
    return segments
}

/**
 * 讲题正文渲染：Markdown 走既有安全渲染（含 fail-closed 回退），独立公式走数学引擎
 * （解析失败回退为可读文本），两段之间的排版由本组件统一。
 */
@Composable
fun TutorReplyMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val segments = splitTutorProseSegments(markdown)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is TutorProseSegment.Markdown -> AiReplyRichMarkdown(
                    markdown = segment.text,
                    modifier = Modifier.fillMaxWidth(),
                )
                is TutorProseSegment.DisplayMath -> MathFormulaBox(
                    formula = segment.latex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tutor_reply_formula")
                        .padding(vertical = 2.dp),
                    color = Ink,
                    fallbackText = ReadableMathText.formula(segment.latex),
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                )
            }
        }
    }
}
