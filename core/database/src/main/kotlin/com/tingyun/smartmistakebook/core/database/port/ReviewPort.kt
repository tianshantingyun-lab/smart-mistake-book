package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.ReviewPlanBundle
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for review planning operations.
 */
interface ReviewReadPort {
    fun observeReviewPlan(reviewPlanId: String): Flow<ReviewPlanBundle?>
    fun observeReviewPlanForSession(sessionId: String): Flow<ReviewPlanBundle?>
    fun observeActiveReviewPlan(learnerId: String): Flow<ReviewPlanBundle?>
    fun observeCurrentReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanBundle?>

    fun observeCompletedReviewLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>>
}
