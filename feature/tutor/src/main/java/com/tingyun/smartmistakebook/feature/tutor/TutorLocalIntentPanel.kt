package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorAuthorizedCapability
import com.tingyun.smartmistakebook.core.domain.TutorIntentAuthorityPolicy
import com.tingyun.smartmistakebook.core.domain.TutorLocalReadProjection
import com.tingyun.smartmistakebook.core.domain.TutorMistakeLookupItem
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel

@Composable
internal fun TutorLocalIntentPanel(
    output: TutorRespondOutput,
    studentMessage: String,
    catalogEntries: List<StudyCatalogEntry>,
    profile: StudyProfileOverview,
    onRequestSave: () -> Unit,
    onRequestEnd: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    TutorLocalIntentPanel(
        decision = output.intentDecision,
        studentMessage = studentMessage,
        catalogEntries = catalogEntries,
        profile = profile,
        onRequestSave = onRequestSave,
        onRequestEnd = onRequestEnd,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
    )
}

@Composable
internal fun TutorLocalIntentPanel(
    decision: TutorIntentDecision,
    studentMessage: String,
    catalogEntries: List<StudyCatalogEntry>,
    profile: StudyProfileOverview,
    onRequestSave: () -> Unit = {},
    onRequestEnd: () -> Unit = {},
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val authorization = remember(decision, studentMessage) {
        TutorIntentAuthorityPolicy.authorize(decision, studentMessage)
    }
    if (authorization.capabilities.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor_local_intent_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (TutorAuthorizedCapability.BLOCK_LONG_TERM_WRITES_FOR_SESSION in authorization.capabilities) {
            LocalModeLine(text = "这次对话不会写入长期学习记录")
        }
        if (TutorAuthorizedCapability.READ_MISTAKE_NOTEBOOK in authorization.capabilities) {
            val items = remember(catalogEntries, decision, authorization) {
                TutorLocalReadProjection.mistakes(
                    catalog = catalogEntries,
                    decision = decision,
                    authorization = authorization,
                )
            }
            TutorMistakeLookupPanel(items, onOpenMistakeNotebook)
        }
        if (TutorAuthorizedCapability.READ_LEARNING_PROGRESS in authorization.capabilities) {
            val progress = remember(profile, authorization) {
                TutorLocalReadProjection.learningProgress(profile, authorization)
            }
            Surface(
                color = JadeSoft.copy(alpha = 0.48f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = if (progress.recordedAttemptCount == 0) {
                            "还没有形成学习记录"
                        } else {
                            "已记录 ${progress.recordedAttemptCount} 次学习反馈"
                        },
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    progress.needsAttention.take(3).forEach { item ->
                        Text(
                            text = "需要再看看 · ${item.displayName}",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    progress.goingWell.take(2).forEach { item ->
                        Text(
                            text = "比较稳 · ${item.displayName}",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlineActionChip(
                        text = "查看学习数据",
                        onClick = onOpenProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tutor_open_profile"),
                    )
                }
            }
        }
        if (TutorAuthorizedCapability.REQUEST_SAVE_CONFIRMATION in authorization.capabilities) {
            OutlineActionChip(
                text = "确认加入错题本",
                onClick = onRequestSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_intent_confirm_save"),
            )
        }
        if (
            TutorAuthorizedCapability.REQUEST_END_WITHOUT_SAVE_CONFIRMATION in
            authorization.capabilities
        ) {
            OutlineActionChip(
                text = "确认结束且不保存",
                onClick = onRequestEnd,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_intent_confirm_end"),
            )
        }
    }
}

@Composable
private fun TutorMistakeLookupPanel(
    items: List<TutorMistakeLookupItem>,
    onOpenMistakeNotebook: () -> Unit,
) {
    Surface(
        color = JadeSoft.copy(alpha = 0.48f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = if (items.isEmpty()) "没有找到相符的错题" else "找到 ${items.size} 道相符错题",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
            )
            items.take(3).forEach { item ->
                val labels = (item.chapterLabels + item.knowledgeLabels)
                    .distinct()
                    .take(2)
                    .joinToString(" · ")
                Text(
                    text = buildString {
                        append(item.subject.studentSubjectLabel()).append(" · ").append(item.title)
                        if (labels.isNotBlank()) append("\n").append(labels)
                    },
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlineActionChip(
                text = "打开错题本",
                onClick = onOpenMistakeNotebook,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_open_mistake_notebook"),
            )
        }
    }
}
