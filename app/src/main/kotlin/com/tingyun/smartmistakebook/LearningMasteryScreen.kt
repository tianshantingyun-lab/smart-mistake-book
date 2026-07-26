package com.tingyun.smartmistakebook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.studentLabel

@Composable
internal fun LearningMasteryScreen(
    overview: StudyProfileOverview,
    onBack: () -> Unit,
    nowEpochMillis: Long = System.currentTimeMillis(),
) {
    val summaries = (overview.weaknesses + overview.strengths)
        .distinctBy(StudyKnowledgeSummary::knowledgeNodeId)
    RootPageColumn(modifier = Modifier.testTag("learning_mastery_screen")) {
        SecondaryHeader(title = "学习掌握", onBack = onBack)
        if (summaries.isEmpty()) {
            LearningMasteryEmptyState()
            return@RootPageColumn
        }

        SectionHeader("分科掌握")
        summaries
            .groupBy(StudyKnowledgeSummary::subject)
            .toList()
            .sortedBy { (subject, _) -> subject.masteryOrder() }
            .forEach { (subject, subjectSummaries) ->
                SubjectMasterySection(
                    subject = subject,
                    summaries = subjectSummaries,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        RecentLearningChanges(
            summaries = summaries,
            nowEpochMillis = nowEpochMillis,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

@Composable
private fun LearningMasteryEmptyState() {
    Text(
        text = "还没有学习记录",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .background(JadeSoft.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .testTag("learning_mastery_empty"),
        color = Ink,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SubjectMasterySection(
    subject: SubjectKind,
    summaries: List<StudyKnowledgeSummary>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JadeSoft.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .testTag("learning_mastery_subject:${subject.name}"),
    ) {
        Text(
            text = subject.studentLabel(),
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        summaries
            .groupBy { summary -> summary.topicPath.firstOrNull() ?: "其他" }
            .toList()
            .sortedBy { it.first }
            .forEachIndexed { index, (topic, topicSummaries) ->
                if (index > 0) PaperDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    text = topic,
                    modifier = Modifier.padding(top = if (index == 0) 12.dp else 0.dp),
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                topicSummaries
                    .sortedWith(
                        compareBy<StudyKnowledgeSummary> { it.status.displayOrder() }
                            .thenBy(StudyKnowledgeSummary::displayName),
                    )
                    .forEach { summary ->
                        KnowledgeMasteryRow(summary, Modifier.padding(top = 9.dp))
                    }
            }
    }
}

@Composable
private fun KnowledgeMasteryRow(
    summary: StudyKnowledgeSummary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${summary.displayName}，${summary.status.studentLabel()}"
            }
            .testTag("learning_mastery_point:${summary.knowledgeNodeId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary.displayName,
            modifier = Modifier.weight(1f),
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = summary.status.studentLabel(),
            color = if (summary.status == MasteryStatus.MASTERED) JadeActive else InkSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RecentLearningChanges(
    summaries: List<StudyKnowledgeSummary>,
    nowEpochMillis: Long,
    modifier: Modifier = Modifier,
) {
    val recent = summaries
        .mapNotNull { summary ->
            summary.latestActivityAtEpochMillis()?.let { occurredAt -> summary to occurredAt }
        }
        .sortedByDescending { (_, occurredAt) -> occurredAt }
        .take(MAX_RECENT_CHANGES)
    if (recent.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("learning_mastery_recent"),
    ) {
        SectionHeader("最近变化")
        recent.forEachIndexed { index, (summary, occurredAt) ->
            if (index > 0) PaperDivider(Modifier.padding(vertical = 10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) 10.dp else 0.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "${summary.subject.studentLabel()}，${summary.displayName}，" +
                            "${summary.status.studentLabel()}，" +
                            recentActivityLabel(occurredAt, nowEpochMillis)
                    }
                    .testTag("learning_mastery_recent:${summary.knowledgeNodeId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.displayName,
                        color = Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${summary.subject.studentLabel()} · ${summary.status.studentLabel()}",
                        modifier = Modifier.padding(top = 2.dp),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = recentActivityLabel(occurredAt, nowEpochMillis),
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun MasteryStatus.displayOrder(): Int = when (this) {
    MasteryStatus.CONFLICTED -> 0
    MasteryStatus.LEARNING -> 1
    MasteryStatus.STALE -> 2
    MasteryStatus.UNKNOWN -> 3
    MasteryStatus.MASTERED -> 4
}

private fun SubjectKind.masteryOrder(): Int =
    if (this == SubjectKind.GENERAL) Int.MAX_VALUE else ordinal

private fun StudyKnowledgeSummary.latestActivityAtEpochMillis(): Long? =
    listOfNotNull(lastEvidenceAtEpochMillis, lastIndependentErrorAtEpochMillis).maxOrNull()

internal fun recentActivityLabel(
    occurredAtEpochMillis: Long,
    nowEpochMillis: Long,
): String {
    val elapsedDays = (nowEpochMillis - occurredAtEpochMillis)
        .coerceAtLeast(0) / DAY_MILLIS
    return when (elapsedDays) {
        0L -> "今天"
        1L -> "昨天"
        else -> "${elapsedDays}天前"
    }
}

private const val MAX_RECENT_CHANGES = 4
private const val DAY_MILLIS = 86_400_000L
