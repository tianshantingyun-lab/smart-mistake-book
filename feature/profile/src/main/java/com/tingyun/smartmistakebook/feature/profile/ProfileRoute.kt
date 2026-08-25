package com.tingyun.smartmistakebook.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode
import com.tingyun.smartmistakebook.core.ui.Divider
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SubjectIcon
import com.tingyun.smartmistakebook.core.ui.Track

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
) {
    RootPageColumn(
        modifier = modifier.testTag("profile_screen"),
        contentPadding = PaddingValues(
            start = 26.dp,
            top = 0.dp,
            end = 26.dp,
            bottom = 12.dp,
        ),
    ) {
        Text(
            text = "我的",
            color = Ink,
            style = MaterialTheme.typography.headlineLarge,
        )
        if (overview.hasLearningEvidence) {
            WeeklyLearningSection(overview, review, Modifier.padding(top = 8.dp))
            PaperDivider(Modifier.padding(top = 8.dp))
        }
        if (overview.weaknesses.isNotEmpty()) {
            WeaknessSection(overview.weaknesses, Modifier.padding(top = 8.dp))
            PaperDivider(Modifier.padding(top = 8.dp))
        }
        LearningMasteryEntry(
            overview = overview,
            onClick = onOpenLearningMastery,
            modifier = Modifier.padding(top = 8.dp),
        )
        PaperDivider(Modifier.padding(top = 8.dp))
        SettingsSection(
            capabilities = capabilities,
            onOpenCapability = onOpenCapability,
            onOpenDataPrivacy = onOpenDataPrivacy,
            onOpenReminder = onOpenReminder,
            onOpenStorage = onOpenStorage,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LearningMasteryEntry(
    overview: StudyProfileOverview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "学习掌握")
        SettingsRow(
            title = "查看全部知识点",
            subtitle = learningMasterySubtitle(overview),
            icon = Icons.Outlined.AccountTree,
            onClick = onClick,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("profile_learning_mastery"),
        )
    }
}

@Composable
private fun WeeklyLearningSection(
    overview: StudyProfileOverview,
    review: StudyReviewOverview,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("profile_weekly_learning")) {
        SectionHeader(title = "当前学习情况")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WeeklyStat(
                label = "学习次数",
                value = overview.recordedAttemptCount.toString(),
                unit = "次",
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            WeeklyStat(
                label = "已掌握",
                value = overview.newlyMasteredCount.toString(),
                unit = "个知识点",
                modifier = Modifier.weight(1.18f),
            )
            StatDivider()
            WeeklyStat(
                label = "连续复习",
                value = review.completionStreakDays.toString(),
                unit = "天",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(JadeSoft.copy(alpha = 0.48f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ShowChart,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = JadeActive,
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    text = profileLearningStatus(overview, review),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

internal fun profileLearningStatus(
    overview: StudyProfileOverview,
    review: StudyReviewOverview,
): String = when {
    !overview.hasLearningEvidence -> "暂无学习记录 · 完成一次复习后开始记录"
    !overview.projectionIsCurrent -> "学习记录已保存 · 正在整理"
    review.completedToday -> "今天的复习已完成 · 已自动记录"
    else -> "已根据最近 ${overview.recordedAttemptCount} 次作答更新学习情况"
}

@Composable
private fun WeeklyStat(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label，$value$unit"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = value,
                color = JadeActive,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = unit,
                modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(48.dp)
            .background(Divider),
    )
}

@Composable
private fun WeaknessSection(
    weaknesses: List<StudyKnowledgeSummary>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("profile_weaknesses")) {
        SectionHeader(title = "当前薄弱点")
        weaknesses.forEachIndexed { index, weakness ->
            if (index > 0) PaperDivider(Modifier.padding(start = 64.dp))
            WeaknessRow(
                title = weakness.displayName,
                detail = "${weakness.status.displayLabel()} · 根据多次独立作答估计",
                mastery = weakness.conservativeMasteryScore.toFloat(),
                icon = if (index % 2 == 0) Icons.Outlined.Functions else Icons.Outlined.Bolt,
                modifier = if (index == 0) Modifier.padding(top = 10.dp) else Modifier,
            )
        }
    }
}

private fun MasteryStatus.displayLabel(): String = when (this) {
    MasteryStatus.UNKNOWN -> "暂无学习记录"
    MasteryStatus.LEARNING -> "正在学习"
    MasteryStatus.MASTERED -> "已掌握"
    MasteryStatus.CONFLICTED -> "表现不稳定"
    MasteryStatus.STALE -> "建议复习"
}

@Composable
private fun WeaknessRow(
    title: String,
    detail: String,
    mastery: Float,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    // Qualitative bands only: precise probabilities require a
    // calibrated model (audit section 6.4), aligned with ReviewRoute.
    val masteryLabel =
        com.tingyun.smartmistakebook.core.ui.masteryBandLabel(mastery.toDouble())
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title，$detail，掌握$masteryLabel"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubjectIcon(
            icon = icon,
            contentDescription = "$title 图标",
            modifier = Modifier.size(44.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "掌握$masteryLabel",
                    color = JadeActive,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = detail,
                color = InkMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { mastery },
                modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .height(5.dp),
                color = JadeActive,
                trackColor = Track,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    capabilities: AppCapabilitySnapshot,
    onOpenCapability: () -> Unit,
    onOpenDataPrivacy: () -> Unit,
    onOpenReminder: () -> Unit,
    onOpenStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capabilitySubtitle = tutorCapabilitySubtitle(capabilities)
    val privacySubtitle = when (capabilities.networkMode) {
        NetworkMode.STRICT_OFFLINE -> "当前版本未启用联网智能服务"
        NetworkMode.LOCAL_FIRST -> "学习记录留在本机 · 智能任务才联网"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "设置")
        SettingsRow(
            title = "大模型设置",
            subtitle = capabilitySubtitle,
            icon = Icons.Outlined.Memory,
            onClick = onOpenCapability,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("profile_capability_setting"),
        )
        PaperDivider(Modifier.padding(start = 52.dp))
        SettingsRow(
            title = "数据与隐私",
            subtitle = privacySubtitle,
            icon = Icons.Outlined.Shield,
            onClick = onOpenDataPrivacy,
            modifier = Modifier.testTag("profile_privacy_setting"),
        )
        PaperDivider(Modifier.padding(start = 52.dp))
        SettingsRow(
            title = "提醒与复习偏好",
            subtitle = "每日提醒时间与通知权限",
            icon = Icons.Outlined.NotificationsNone,
            onClick = onOpenReminder,
            modifier = Modifier.testTag("profile_reminder_setting"),
        )
        PaperDivider(Modifier.padding(start = 52.dp))
        SettingsRow(
            title = "存储与导出",
            subtitle = "管理本机原图与 PDF 导出",
            icon = Icons.Outlined.Backup,
            onClick = onOpenStorage,
            modifier = Modifier.testTag("profile_storage_setting"),
        )
    }
}

internal fun learningMasterySubtitle(overview: StudyProfileOverview): String {
    val summaries = overview.weaknesses + overview.strengths
    if (summaries.isEmpty()) return "做过题后，这里会自动形成你的学习情况"
    val subjectCount = summaries.map(StudyKnowledgeSummary::subject)
        .filterNot { it == com.tingyun.smartmistakebook.core.model.SubjectKind.GENERAL }
        .distinct()
        .size
    val subjectText = if (subjectCount > 0) "$subjectCount 科 · " else ""
    return "$subjectText${summaries.size} 个知识点有学习记录"
}

internal fun tutorCapabilitySubtitle(capabilities: AppCapabilitySnapshot): String =
    when {
        !capabilities.tutorTeachingEnabled ||
            capabilities.networkMode == NetworkMode.STRICT_OFFLINE ->
            "讲题功能暂不可用 · 查看当前能力"
        capabilities.remoteModelAvailable ->
            if (capabilities.remoteModelImageInputVerified) {
                "图片读取与智能讲题已就绪"
            } else {
                "智能讲题已就绪 · 图片读取暂不可用"
            }
        capabilities.remoteModelCapabilitiesTested ->
            "当前模型暂不兼容 · 查看测试结果"
        capabilities.remoteModelConfigured ->
            "模型配置已保存 · 请完成能力测试"
        else ->
            "配置大模型 API · 仅发送当次已说明的内容"
    }

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title，$subtitle"
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = InkSecondary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                text = title,
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = InkMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = InkSecondary,
        )
    }
}
