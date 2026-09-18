package com.tingyun.smartmistakebook.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 思考链折叠卡：AI 气泡内正文上方，默认折叠（学生先看讲解，不被推理过程剧透）。
 * 教育语境 = “过程透明”，灰阶（InkMuted/InkSecondary）与正文（Ink）主次分明，不抢讲解。
 */

/** 折叠卡的显示判定（纯函数，便于单测）：有思考文本，或正处于思考中占位态。 */
internal fun shouldShowThinkingCard(thinkingMarkdown: String?, thinking: Boolean): Boolean =
    thinking || !thinkingMarkdown.isNullOrBlank()

@Composable
fun ThinkingCollapsibleCard(
    thinkingMarkdown: String?,
    modifier: Modifier = Modifier,
    thinking: Boolean = false,
    color: Color = InkSecondary,
) {
    if (!shouldShowThinkingCard(thinkingMarkdown, thinking)) return

    // 生成中自动展开：学生实时看到思考与工具调用的流式过程；答案落地后自动收起成一组。
    var expanded by rememberSaveable { mutableStateOf(thinking) }
    LaunchedEffect(thinking) {
        expanded = thinking
    }
    val thinkingBody = thinkingMarkdown?.takeIf { it.isNotBlank() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            if (thinking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = color,
                )
            }
            Text(
                text = if (thinking) {
                    "正在思考…"
                } else if (expanded) {
                    "已展示思考过程"
                } else {
                    "已思考"
                },
                color = color,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded && thinkingBody != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SafeMarkdownText(
                markdown = thinkingBody.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, end = 2.dp),
            )
        }
    }
}
