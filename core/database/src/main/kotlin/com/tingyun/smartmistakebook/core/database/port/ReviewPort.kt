package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceCommand
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceResult
import com.tingyun.smartmistakebook.core.database.ReviewSessionRecord
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

/**
 * Port for review plan/session writes and deprecated P0 inspection.
 */
interface ReviewWritePort {
    suspend fun saveReviewPlan(bundle: ReviewPlanBundle)

    suspend fun saveReviewSession(session: ReviewSessionRecord)

    /**
     * Replays an already committed review transition. It must never create a new transition.
     * New answers must enter through [recordReviewAttempt], which creates the attempt and advances
     * its queue item in one write transaction.
     */
    @Deprecated("New review transitions must use recordReviewAttempt")
    suspend fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
    ): ReviewSessionAdvanceResult
}