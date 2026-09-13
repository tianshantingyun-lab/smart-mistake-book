package com.tingyun.smartmistakebook.feature.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartColors
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel

/**
 * 无工件错题的复习入口（第2条：复习作答与评级不再由学生自评）。
 *
 * 这道题没有机判选项，学生在这里唯一的动作是**去讲题判定**：先把题按自己的思路做一遍，
 * 讲题里模型出检查题（选择题或开放式追问），对错由本地核对、模型给语义判词，判定完成后
 * 自动落成这次复习的 attempt 并进入下一题。
 *
 * 没有模型时不给假通道：如实说明"需要配置模型才能判定"，学生可以返回——该复习项保持
 * 到期，不会被伪造的记忆更新或跳过记录带过。
 *
 * **这是 2026-09-14 知情裁定的取舍，不是遗漏**：另两个候选（零权重"本次先跳过"、计划期排除）
 * 的代价见 `docs/scenario-registry.md` 的 ReviewSession 行。已知后果——生产线上的题都是
 * 无工件题，所以没有可用模型时**整条复习队列都不推进**（卡在第 1 题），等于该构建没有复习功能。
 * 改这条行为前先读那份登记：跳过必须零权重、不动记忆、该题保持到期，否则就是伪造证据。
 */
@Composable
fun CapturedReviewSessionScreen(
    onBack: () -> Unit,
    entry: StudyCatalogEntry,
    queuePosition: Int,
    queueSize: Int,
    tutorJudgedAvailable: Boolean,
    onOpenTutorJudge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeQueueSize = queueSize.coerceAtLeast(1)
    val safeQueuePosition = queuePosition.coerceIn(1, safeQueueSize)
    val problemScrollState = rememberScrollState()
    RootPageColumn(modifier = modifier.testTag("captured_review_session_root")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("captured_review_back"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回今日复习")
            }
            Text(
                text = "今日复习",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = SmartColors.Ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = entry.subject.studentSubjectLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = SmartColors.InkSecondary,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { safeQueuePosition.toFloat() / safeQueueSize.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp),
            color = SmartColors.Jade,
            trackColor = SmartColors.Track,
        )
        Spacer(Modifier.height(12.dp))
        LocalModeLine(text = "第 $safeQueuePosition / $safeQueueSize 题 · 复做你保存的原题")
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader(title = entry.title)
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(problemScrollState),
        ) {
            SafeMarkdownText(
                markdown = entry.problemMarkdown,
                color = SmartColors.Ink,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 19.sp,
                    lineHeight = 30.sp,
                ),
            )
        }
        Spacer(Modifier.height(24.dp))
        if (tutorJudgedAvailable) {
            Text(
                text = "先按自己的思路把它做一遍，然后去讲题：模型会出检查题，对错由本机核对，" +
                    "判定完成后自动记入复习并进入下一题。",
                modifier = Modifier.testTag("captured_review_tutor_judged_hint"),
                style = MaterialTheme.typography.bodyMedium,
                color = SmartColors.InkSecondary,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryActionButton(
                text = "去讲题判定",
                onClick = onOpenTutorJudge,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("captured_review_tutor_judged_button"),
                contentDescription = "去讲题里完成这道题的检查",
            )
        } else {
            Text(
                text = "这道题需要模型出检查题才能判定对错，当前还没有可用的模型服务。" +
                    "先配置好模型再回来复习；这道题会一直留在今日复习里。",
                modifier = Modifier.testTag("captured_review_tutor_unavailable_notice"),
                style = MaterialTheme.typography.bodyMedium,
                color = SmartColors.InkSecondary,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
