package com.tingyun.smartmistakebook.feature.review
import com.tingyun.smartmistakebook.core.ui.Divider
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Jade
import com.tingyun.smartmistakebook.core.ui.Track

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartColors

internal sealed interface ReviewLandingState {
    data object Loading : ReviewLandingState

    data class Content(
        val scheduledCount: Int,
        val estimatedMinutes: Int,
        val completedCount: Int,
        val actionLabel: String,
        val actionEnabled: Boolean,
        val actionInProgress: Boolean = false,
    ) : ReviewLandingState {
        init {
            require(scheduledCount >= 0) { "Review count must not be negative" }
            require(estimatedMinutes >= 0) { "Review duration must not be negative" }
            require(completedCount in 0..scheduledCount) {
                "Review progress must stay inside today's plan"
            }
        }

        val progressText: String
            get() = "$completedCount / $scheduledCount"
        val scheduledCountDescription: String
            get() = "今日题量，$scheduledCount 道"
        val estimatedMinutesDescription: String
            get() = "预计时间，$estimatedMinutes 分钟"
        val progressDescription: String
            get() = "今日进度，$progressText"
    }

    data object Unavailable : ReviewLandingState
}

internal fun reviewLandingState(
    home: ReviewHomeState,
    actionInProgress: Boolean = false,
): ReviewLandingState =
    when (home) {
        is ReviewHomeState.Unavailable -> ReviewLandingState.Unavailable
        is ReviewHomeState.Ready -> {
            val plan = home.plan
            val completed = plan.completedItemCount + plan.skippedItemCount
            val hasActiveSession =
                home.session?.status ==
                    com.tingyun.smartmistakebook.core.data.review.ReviewHomeSessionStatus.ACTIVE
            val actionLabel =
                when {
                    actionInProgress -> "正在打开"
                    plan.isComplete -> "今日完成"
                    hasActiveSession -> "继续复习"
                    plan.scheduledItemCount > 0 -> "开始复习"
                    else -> "今日无复习"
                }
            ReviewLandingState.Content(
                scheduledCount = plan.scheduledItemCount,
                estimatedMinutes = (plan.remainingEstimatedSeconds + 59) / 60,
                completedCount = completed,
                actionLabel = actionLabel,
                actionEnabled =
                    !actionInProgress &&
                        !plan.isComplete &&
                        (hasActiveSession || plan.remainingItemCount > 0),
                actionInProgress = actionInProgress,
            )
        }
    }

@Composable
internal fun ReviewHomeScreen(
    state: ReviewLandingState,
    onPrimaryAction: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RootPageColumn(
        modifier = modifier.testTag("review_root"),
    ) {
        when (state) {
            ReviewLandingState.Loading -> ReviewLoading()
            ReviewLandingState.Unavailable ->
                ReviewUnavailable(
                    onRetry = onRetry,
                )
            is ReviewLandingState.Content ->
                ReviewContent(
                    state = state,
                    onPrimaryAction = onPrimaryAction,
                )
        }
    }
}

@Composable
private fun ReviewContent(
    state: ReviewLandingState.Content,
    onPrimaryAction: () -> Unit,
) {
    ReviewSummary(state)
    Spacer(Modifier.height(28.dp))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("review_progress")
                .clearAndSetSemantics {
                    contentDescription = state.progressDescription
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "进度",
            style = MaterialTheme.typography.bodyLarge,
            color = InkSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = state.progressText,
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(28.dp))
    PrimaryActionButton(
        text = state.actionLabel,
        onClick = onPrimaryAction,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("review_start_button"),
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        enabled = state.actionEnabled,
    )
}

@Composable
private fun ReviewLoading() {
    Spacer(Modifier.height(20.dp))
    LinearProgressIndicator(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .testTag("review_loading")
                .clearAndSetSemantics {
                    contentDescription = "正在准备今日复习"
                },
        color = Jade,
        trackColor = Track,
    )
}

@Composable
private fun ReviewUnavailable(
    onRetry: () -> Unit,
) {
    Text(
        text = "暂时无法使用",
        modifier = Modifier.testTag("review_unavailable"),
        style = MaterialTheme.typography.titleMedium,
        color = Ink,
    )
    Spacer(Modifier.height(20.dp))
    PrimaryActionButton(
        text = "重试",
        onClick = onRetry,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("review_retry"),
    )
}

@Composable
private fun ReviewSummary(
    state: ReviewLandingState.Content,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("review_summary"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryValue(
            value = state.scheduledCount.toString(),
            label = "题量",
            contentDescription = state.scheduledCountDescription,
            testTag = "review_scheduled_count",
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier
                    .width(1.dp)
                    .height(48.dp)
                    .background(Divider),
        )
        SummaryValue(
            value = state.estimatedMinutes.toString(),
            label = "分钟",
            contentDescription = state.estimatedMinutesDescription,
            testTag = "review_estimated_minutes",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryValue(
    value: String,
    label: String,
    contentDescription: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .testTag(testTag)
                .clearAndSetSemantics {
                    this.contentDescription = contentDescription
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            color = Ink,
            fontSize = 42.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
