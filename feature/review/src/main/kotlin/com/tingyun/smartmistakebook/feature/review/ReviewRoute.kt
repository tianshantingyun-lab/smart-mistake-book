package com.tingyun.smartmistakebook.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.domain.NewIntroductionPolicy
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartColors
import com.tingyun.smartmistakebook.core.ui.SubjectIcon
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReviewRoute(
    overview: StudyReviewOverview,
    profile: StudyProfileOverview,
    onStartReview: () -> Unit,
    onStartKnowledgeReview: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    RootPageColumn(
        modifier = modifier.testTag("review_root"),
    ) {
        ReviewGreeting()
        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "今日复习")
        Spacer(Modifier.height(24.dp))
        if (shouldShowReviewSummary(overview.scheduledCount, profile.hasLearningEvidence)) {
            ReviewSummary(overview)
        } else {
            Text(
                text = "暂无学习记录",
                color = SmartColors.InkSecondary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("review_no_learning_record"),
            )
        }
        Spacer(Modifier.height(30.dp))
        // 双复习入口（spec dual-review-entry §3.1）：知识点复习是今日错题的知识点排程伴侣，
        // 学生可自由选，建议先巩固概念再做错题。是否真有知识点队列由会话屏在进入时经
        // currentKnowledgeReviewPlan 决定（空则显示"今日无需复习"），首页不猜测、不重复排程。
        if (onStartKnowledgeReview != null && isKnowledgeReviewDay(overview)) {
            KnowledgeReviewEntryCard(
                onClick = onStartKnowledgeReview,
                modifier = Modifier.testTag("review_start_knowledge_review"),
            )
            Spacer(Modifier.height(12.dp))
        }
        PrimaryActionButton(
            text = when {
                overview.completedToday -> "今日复习已完成"
                overview.activeSessionId != null -> "继续今日复习"
                overview.scheduledCount > 0 -> "开始今日复习"
                else -> "暂无待复习题"
            },
            onClick = onStartReview,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag("review_start_button"),
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            enabled = overview.scheduledCount > 0 && !overview.completedToday,
        )
        // Intake backlog coverage promise (spec batch-intake §6 P3): questions
        // recorded but not yet introduced carry no learning pressure; the
        // surface shows them as a plan ("每天约 X 题新学，约 M 天覆盖") so a
        // large import reads as a schedule, not a wall.
        if (overview.intakeBacklogCount > 0) {
            Spacer(Modifier.height(12.dp))
            IntakeBacklogPreview(
                backlogCount = overview.intakeBacklogCount,
                medianEstimateSeconds = overview.intakeMedianEstimateSeconds,
                modifier = Modifier.testTag("review_intake_backlog_preview"),
            )
        }
        reviewContinuityText(overview)?.let { continuity ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = SmartColors.JadeSoft.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("review_continuity"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = SmartColors.JadeDark,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = continuity,
                    color = SmartColors.Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (profile.hasLearningEvidence && profile.weaknesses.isNotEmpty()) {
            PaperDivider(Modifier.padding(vertical = 26.dp))
            SectionHeader(title = "薄弱知识点")
            Spacer(Modifier.height(10.dp))
            profile.weaknesses.take(2).forEachIndexed { index, weakness ->
                WeakPointRow(
                    title = weakness.displayName,
                    detail = "独立作答把握：" +
                        com.tingyun.smartmistakebook.core.ui.masteryBandLabel(
                            weakness.conservativeMasteryScore,
                        ),
                    icon = if (index == 0) {
                        Icons.AutoMirrored.Outlined.ShowChart
                    } else {
                        Icons.Outlined.Bolt
                    },
                    testTag = "review_weakness_$index",
                )
            }
        }
    }
}

internal fun shouldShowReviewSummary(
    scheduledCount: Int,
    hasLearningEvidence: Boolean,
): Boolean = scheduledCount > 0 || hasLearningEvidence

/**
 * 知识点复习入口只在"今天确有复习计划"时展示（spec dual-review-entry §3.1）——知识点范围
 * 派生自今日错题队列，没有今日计划就没有可复习的知识点。已完成今天的不再建议。
 */
internal fun isKnowledgeReviewDay(overview: StudyReviewOverview): Boolean =
    overview.scheduledCount > 0 && !overview.completedToday

/** 知识点复习入口卡：与错题复习并列的第二个入口，建议先巩固概念再练题。 */
@Composable
private fun KnowledgeReviewEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SmartColors.JadeSoft.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(1.dp, SmartColors.Jade.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubjectIcon(
                icon = Icons.AutoMirrored.Outlined.ShowChart,
                contentDescription = "知识点复习",
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "先复习知识点",
                    style = MaterialTheme.typography.titleMedium,
                    color = SmartColors.Ink,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "巩固今天涉及的概念，再做错题",
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartColors.InkSecondary,
                )
            }
            Text(
                text = "开始",
                color = SmartColors.JadeDark,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

internal fun reviewContinuityText(overview: StudyReviewOverview): String? = when {
    overview.completionStreakDays <= 0 -> null
    overview.completedToday ->
        "今天已完成 · 连续复习 ${overview.completionStreakDays} 天"
    else ->
        "连续复习 ${overview.completionStreakDays} 天 · 完成今天的安排后自动记录"
}

@Composable
private fun ReviewGreeting() {
    val now = remember { Date() }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..10 -> "早上好，同学"
        in 11..13 -> "中午好，同学"
        in 14..17 -> "下午好，同学"
        else -> "晚上好，同学"
    }
    val dateLabel = remember(now) {
        SimpleDateFormat("M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE).format(now)
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = greeting,
            color = SmartColors.Ink,
            fontSize = 29.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.InkSecondary,
        )
    }
}

@Composable
private fun ReviewSummary(overview: StudyReviewOverview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryValue(
            value = overview.scheduledCount.toString(),
            label = if (overview.completedToday) "道今日已完成" else "道计划复习",
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(48.dp)
                .background(SmartColors.Divider),
        )
        SummaryValue(
            value = ((overview.estimatedSeconds + 59) / 60).toString(),
            label = "预计用时（分钟）",
        )
    }
}

@Composable
private fun SummaryValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = SmartColors.Ink,
            fontSize = 42.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.InkSecondary,
        )
    }
}

@Composable
private fun WeakPointRow(
    title: String,
    detail: String,
    icon: ImageVector,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubjectIcon(
            icon = icon,
            contentDescription = "$title 知识点",
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = SmartColors.Ink,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = SmartColors.InkSecondary,
            )
        }
    }
}


/**
 * Intake-backlog coverage preview (spec batch-intake §6 P3): renders the
 * backlog as a schedule — "每天约 X 题新学，约 M 天覆盖" — computed with the
 * same NewIntroductionPolicy.preview the scheduler uses, so the promise
 * matches what the daily introduction actually does. A median reference
 * budget of 15 minutes of new material per day keeps the estimate stable
 * without threading the full time-budget preference into this surface.
 */
@Composable
private fun IntakeBacklogPreview(
    backlogCount: Int,
    medianEstimateSeconds: Int,
    modifier: Modifier = Modifier,
) {
    val estimateSeconds = if (medianEstimateSeconds > 0) medianEstimateSeconds else 60
    val preview = remember(backlogCount, estimateSeconds) {
        NewIntroductionPolicy.preview(
            backlogCount = backlogCount,
            typicalItemSeconds = estimateSeconds,
            timeBudgetSeconds = DEFAULT_INTAKE_DAILY_BUDGET_SECONDS,
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = SmartColors.JadeSoft.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (preview.daysToCover <= 0) {
                "还有 $backlogCount 道新题待引入"
            } else {
                "还有 $backlogCount 道新题待学：每天约 ${preview.perDayItems} 题，约 ${preview.daysToCover} 天覆盖"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.Ink,
        )
    }
}

/** Reference daily budget (seconds) the intake preview is computed from. */
private const val DEFAULT_INTAKE_DAILY_BUDGET_SECONDS = 900
