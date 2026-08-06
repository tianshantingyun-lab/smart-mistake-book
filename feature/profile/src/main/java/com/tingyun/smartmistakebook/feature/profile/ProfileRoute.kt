package com.tingyun.smartmistakebook.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.studentLabel

@Suppress("UNUSED_PARAMETER")
@Composable
fun ProfileRoute(
    overview: StudyProfileOverview,
    review: StudyReviewOverview,
    capabilities: AppCapabilitySnapshot,
    onOpenCapability: () -> Unit,
    onOpenLearningMastery: () -> Unit,
    onOpenDataPrivacy: () -> Unit,
    onOpenReminder: () -> Unit,
    onOpenStorage: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long = System.currentTimeMillis(),
) {
    val subjectSummaries = profileSubjectSummaries(overview)
    val recentChanges = profileRecentChanges(overview)
    val weaknesses = profileWeaknessSummaries(overview)
    RootPageColumn(
        modifier = modifier.testTag("profile_screen"),
        contentPadding = PaddingValues(
            start = 26.dp,
            top = 0.dp,
            end = 26.dp,
            bottom = 12.dp,
        ),
    ) {
        SubjectMasterySection(
            summaries = subjectSummaries,
            onOpenLearningMastery = onOpenLearningMastery,
        )
        if (shouldShowProfileRecentChanges(recentChanges, review.completionStreakDays)) {
            PaperDivider(Modifier.padding(vertical = 12.dp))
            RecentChangesSection(
                changes = recentChanges,
                completionStreakDays = review.completionStreakDays,
                nowEpochMillis = nowEpochMillis,
            )
        }
        if (weaknesses.isNotEmpty()) {
            PaperDivider(Modifier.padding(vertical = 12.dp))
            WeaknessSummarySection(weaknesses)
        }
        PaperDivider(Modifier.padding(vertical = 12.dp))
        SettingsSection(
            onOpenCapability = onOpenCapability,
            onOpenDataPrivacy = onOpenDataPrivacy,
            onOpenReminder = onOpenReminder,
            onOpenStorage = onOpenStorage,
        )
    }
}

internal data class ProfileSubjectSummary(
    val subject: SubjectKind,
    val status: MasteryStatus,
)

internal data class ProfileRecentChange(
    val summary: StudyKnowledgeSummary,
    val occurredAtEpochMillis: Long,
)

internal fun profileSubjectSummaries(
    overview: StudyProfileOverview,
): List<ProfileSubjectSummary> = uniqueProfileSummaries(overview)
    .groupBy(StudyKnowledgeSummary::subject)
    .map { (subject, summaries) ->
        ProfileSubjectSummary(
            subject = subject,
            status = summaries.minBy(::profileStatusPriority).status,
        )
    }
    .sortedBy { group ->
        if (group.subject == SubjectKind.GENERAL) Int.MAX_VALUE else group.subject.ordinal
    }

internal fun profileWeaknessSummaries(
    overview: StudyProfileOverview,
): List<StudyKnowledgeSummary> = overview.weaknesses
    .distinctBy(StudyKnowledgeSummary::knowledgeNodeId)
    .take(MAX_WEAKNESS_SUMMARIES)

internal fun profileRecentChanges(
    overview: StudyProfileOverview,
): List<ProfileRecentChange> = uniqueProfileSummaries(overview)
    .mapNotNull { summary ->
        summary.latestActivityAtEpochMillis()?.let { occurredAt ->
            ProfileRecentChange(summary, occurredAt)
        }
    }
    .sortedByDescending(ProfileRecentChange::occurredAtEpochMillis)
    .take(MAX_RECENT_CHANGES)

internal fun shouldShowProfileRecentChanges(
    changes: List<ProfileRecentChange>,
    completionStreakDays: Int,
): Boolean = changes.isNotEmpty() || completionStreakDays > 0

internal fun profileRecentActivityLabel(
    occurredAtEpochMillis: Long,
    nowEpochMillis: Long,
): String {
    val elapsedDays = (nowEpochMillis - occurredAtEpochMillis)
        .coerceAtLeast(0L) / DAY_MILLIS
    return when (elapsedDays) {
        0L -> "今天"
        1L -> "昨天"
        else -> "${elapsedDays}天前"
    }
}

private fun uniqueProfileSummaries(
    overview: StudyProfileOverview,
): List<StudyKnowledgeSummary> = (overview.weaknesses + overview.strengths)
    .distinctBy(StudyKnowledgeSummary::knowledgeNodeId)

private fun StudyKnowledgeSummary.latestActivityAtEpochMillis(): Long? =
    listOfNotNull(lastEvidenceAtEpochMillis, lastIndependentErrorAtEpochMillis).maxOrNull()

@Composable
private fun SubjectMasterySection(
    summaries: List<ProfileSubjectSummary>,
    onOpenLearningMastery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_subject_mastery"),
    ) {
        SectionHeader(title = "分科掌握")
        if (summaries.isEmpty()) {
            Text(
                text = "还没有学习记录",
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("profile_learning_empty"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            summaries.forEach { summary ->
                SubjectStatusRow(summary)
            }
        }
        ProfileActionRow(
            title = "查看学习掌握",
            onClick = onOpenLearningMastery,
            modifier = Modifier.testTag("profile_learning_mastery"),
        )
    }
}

@Composable
private fun SubjectStatusRow(summary: ProfileSubjectSummary) {
    val statusText = summary.status.studentLabel()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${summary.subject.studentLabel()}，$statusText"
            }
            .testTag("profile_subject_${summary.subject.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary.subject.studentLabel(),
            modifier = Modifier.weight(1f),
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = statusText,
            color = JadeActive,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun profileStatusPriority(summary: StudyKnowledgeSummary): Int =
    when (summary.status) {
        MasteryStatus.CONFLICTED -> 0
        MasteryStatus.STALE -> 1
        MasteryStatus.LEARNING -> 2
        MasteryStatus.UNKNOWN -> 3
        MasteryStatus.MASTERED -> 4
    }

@Composable
private fun RecentChangesSection(
    changes: List<ProfileRecentChange>,
    completionStreakDays: Int,
    nowEpochMillis: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_recent_changes"),
    ) {
        SectionHeader(title = "最近变化")
        changes.forEach { change ->
            val summary = change.summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "${summary.displayName}，${summary.status.studentLabel()}，" +
                            profileRecentActivityLabel(
                                change.occurredAtEpochMillis,
                                nowEpochMillis,
                            )
                    }
                    .testTag("profile_recent_${summary.knowledgeNodeId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = summary.displayName,
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${summary.subject.studentLabel()} · ${summary.status.studentLabel()}",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = profileRecentActivityLabel(
                        change.occurredAtEpochMillis,
                        nowEpochMillis,
                    ),
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (completionStreakDays > 0) {
            Text(
                text = "连续复习 $completionStreakDays 天",
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics {
                        contentDescription = "连续复习，${completionStreakDays}天"
                    }
                    .padding(top = 12.dp)
                    .testTag("profile_review_streak"),
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun WeaknessSummarySection(weaknesses: List<StudyKnowledgeSummary>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_weaknesses"),
    ) {
        SectionHeader(title = "薄弱摘要")
        weaknesses.forEach { weakness ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "${weakness.displayName}，${weakness.status.studentLabel()}"
                    }
                    .testTag("profile_weakness_${weakness.knowledgeNodeId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = weakness.displayName,
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = weakness.status.studentLabel(),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun SettingsSection(
    onOpenCapability: () -> Unit,
    onOpenDataPrivacy: () -> Unit,
    onOpenReminder: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_settings"),
    ) {
        SectionHeader(title = "设置")
        ProfileActionRow(
            title = "模型与 API",
            onClick = onOpenCapability,
            icon = Icons.Outlined.Memory,
            modifier = Modifier.testTag("profile_capability_setting"),
        )
        ProfileActionRow(
            title = "数据与隐私",
            onClick = onOpenDataPrivacy,
            icon = Icons.Outlined.Shield,
            modifier = Modifier.testTag("profile_privacy_setting"),
        )
        ProfileActionRow(
            title = "提醒",
            onClick = onOpenReminder,
            icon = Icons.Outlined.NotificationsNone,
            modifier = Modifier.testTag("profile_reminder_setting"),
        )
        ProfileActionRow(
            title = "存储与导出",
            onClick = onOpenStorage,
            icon = Icons.Outlined.Backup,
            modifier = Modifier.testTag("profile_storage_setting"),
        )
    }
}

@Composable
private fun ProfileActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = title
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = InkSecondary,
            )
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (icon == null) 0.dp else 14.dp),
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = InkSecondary,
        )
    }
}

private const val MAX_WEAKNESS_SUMMARIES = 2
private const val MAX_RECENT_CHANGES = 3
private const val DAY_MILLIS = 86_400_000L
