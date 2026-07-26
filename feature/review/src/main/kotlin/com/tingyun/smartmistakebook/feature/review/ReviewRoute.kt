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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartColors

@Composable
fun ReviewRoute(
    overview: StudyReviewOverview,
    profile: StudyProfileOverview,
    onStartReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = reviewLandingState(overview)
    RootPageColumn(
        modifier = modifier.testTag("review_root"),
    ) {
        ReviewSummary(state)
        Spacer(Modifier.height(26.dp))
        SectionHeader(
            title = "今日进度",
            action = {
                Text(
                    text = state.progressText,
                    color = SmartColors.Ink,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("review_progress"),
                )
            },
        )
        Spacer(Modifier.height(30.dp))
        PrimaryActionButton(
            text = state.actionLabel,
            onClick = onStartReview,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag("review_start_button"),
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            enabled = state.actionEnabled,
        )
    }
}

internal data class ReviewLandingState(
    val scheduledCount: Int,
    val estimatedMinutes: Int,
    val completedCount: Int,
    val actionLabel: String,
    val actionEnabled: Boolean,
) {
    val progressText: String
        get() = "$completedCount / $scheduledCount"

}

internal fun reviewLandingState(overview: StudyReviewOverview): ReviewLandingState {
    val scheduledCount = overview.scheduledCount.coerceAtLeast(0)
    val completedCount = if (overview.completedToday) {
        scheduledCount
    } else {
        overview.currentOrdinal.coerceIn(0, scheduledCount)
    }
    val actionLabel = when {
        overview.completedToday -> "今日复习已完成"
        overview.activeSessionId != null -> "继续今日复习"
        scheduledCount > 0 -> "开始今日复习"
        else -> "今天没有待复习"
    }
    return ReviewLandingState(
        scheduledCount = scheduledCount,
        estimatedMinutes = (overview.estimatedSeconds.coerceAtLeast(0) + 59) / 60,
        completedCount = completedCount,
        actionLabel = actionLabel,
        actionEnabled = !overview.completedToday &&
            (overview.activeSessionId != null || scheduledCount > 0),
    )
}

@Composable
private fun ReviewSummary(state: ReviewLandingState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryValue(
            value = state.scheduledCount.toString(),
            label = "今日题量（道）",
            testTag = "review_scheduled_count",
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(48.dp)
                .background(SmartColors.Divider),
        )
        SummaryValue(
            value = state.estimatedMinutes.toString(),
            label = "预计时间（分钟）",
            testTag = "review_estimated_minutes",
        )
    }
}

@Composable
private fun SummaryValue(
    value: String,
    label: String,
    testTag: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = SmartColors.Ink,
            fontSize = 42.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(testTag),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = SmartColors.InkSecondary,
        )
    }
}
