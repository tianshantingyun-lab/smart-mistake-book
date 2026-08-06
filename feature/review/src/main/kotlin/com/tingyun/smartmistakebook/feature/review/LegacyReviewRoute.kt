package com.tingyun.smartmistakebook.feature.review

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyReviewOverview

/**
 * Temporary source-compatible route for the unpublished legacy app graph.
 *
 * New production wiring must call [DailyReviewRoute]. This overload disappears when the app
 * publisher can provide every narrow review capability.
 */
@Deprecated(
    message = "Use DailyReviewRoute with the three-authority review capabilities",
)
@Composable
fun ReviewRoute(
    overview: StudyReviewOverview,
    @Suppress("UNUSED_PARAMETER") profile: StudyProfileOverview,
    onStartReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReviewHomeScreen(
        state = reviewLandingState(overview),
        onPrimaryAction = onStartReview,
        onRetry = onStartReview,
        modifier = modifier,
    )
}

internal fun reviewLandingState(
    overview: StudyReviewOverview,
): ReviewLandingState.Content {
    val scheduledCount = overview.scheduledCount.coerceAtLeast(0)
    val completedCount =
        if (overview.completedToday) {
            scheduledCount
        } else {
            overview.currentOrdinal.coerceIn(0, scheduledCount)
        }
    val actionLabel =
        when {
            overview.completedToday -> "今日完成"
            overview.activeSessionId != null -> "继续复习"
            scheduledCount > 0 -> "开始复习"
            else -> "今日无复习"
        }
    return ReviewLandingState.Content(
        scheduledCount = scheduledCount,
        estimatedMinutes = (overview.estimatedSeconds.coerceAtLeast(0) + 59) / 60,
        completedCount = completedCount,
        actionLabel = actionLabel,
        actionEnabled =
            !overview.completedToday &&
                (overview.activeSessionId != null || scheduledCount > 0),
    )
}
