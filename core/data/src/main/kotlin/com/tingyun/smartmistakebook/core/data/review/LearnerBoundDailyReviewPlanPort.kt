package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlan
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlanningRequest
import com.tingyun.smartmistakebook.core.domain.ReviewExamTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DailyReviewPlanningResult(
    val plan: ThreeAuthorityReviewPlan,
    val candidateProfile: ReviewCandidateProfileSummary,
)

/**
 * Learner-bound daily review planning surface.
 *
 * The learner identity and both authority capabilities are fixed by the owning runtime. Callers
 * may choose a bounded daily duration, but cannot select another learner or access either store.
 */
interface LearnerBoundDailyReviewPlanPort {
    suspend fun planDailyReview(
        localDayEpochDay: Long,
        timeZoneId: String,
        planningAtEpochMillis: Long,
        timeBudgetSeconds: Int = DEFAULT_TIME_BUDGET_SECONDS,
        maxItemCount: Int = ThreeAuthorityReviewPlanningRequest.DEFAULT_MAX_ITEM_COUNT,
        examTarget: ReviewExamTarget? = null,
    ): DailyReviewPlanningResult

    companion object {
        const val DEFAULT_TIME_BUDGET_SECONDS = 15 * 60
        const val MIN_TIME_BUDGET_SECONDS = 1
        const val MAX_TIME_BUDGET_SECONDS = 60 * 60
    }
}

internal class BoundLearnerDailyReviewPlanPort(
    private val learnerId: String,
    private val planAndStore:
        suspend (ThreeAuthorityDailyReviewRequest) -> ThreeAuthorityDailyReviewPlanningResult,
) : LearnerBoundDailyReviewPlanPort {
    private val planningMutex = Mutex()

    constructor(
        learnerId: String,
        coordinator: ThreeAuthorityReviewPlanCoordinator,
    ) : this(
        learnerId = learnerId,
        planAndStore = coordinator::planAndStoreWithProfile,
    )

    init {
        learnerId.requireBoundedDailyReviewValue("Review learner id")
    }

    override suspend fun planDailyReview(
        localDayEpochDay: Long,
        timeZoneId: String,
        planningAtEpochMillis: Long,
        timeBudgetSeconds: Int,
        maxItemCount: Int,
        examTarget: ReviewExamTarget?,
    ): DailyReviewPlanningResult =
        planningMutex.withLock {
            currentCoroutineContext().ensureActive()
            require(
                timeBudgetSeconds >=
                    LearnerBoundDailyReviewPlanPort.MIN_TIME_BUDGET_SECONDS &&
                    timeBudgetSeconds <=
                    LearnerBoundDailyReviewPlanPort.MAX_TIME_BUDGET_SECONDS,
            ) {
                "Daily review time budget is outside the supported range"
            }
            require(maxItemCount in 1..ThreeAuthorityReviewPlanningRequest.MAX_ITEM_COUNT) {
                "Daily review item-count budget is outside the supported range"
            }
            val request =
                ThreeAuthorityDailyReviewRequest(
                    learnerId = learnerId,
                    localDayEpochDay = localDayEpochDay,
                    timeZoneId = timeZoneId,
                    timeBudgetSeconds = timeBudgetSeconds,
                    maxItemCount = maxItemCount,
                    examTarget = examTarget,
                    planningAtEpochMillis = planningAtEpochMillis,
                )
            val result = planAndStore(request)
            val plan = result.plan
            check(plan.learnerId == learnerId) {
                "Daily review coordinator returned another learner's plan"
            }
            check(plan.localDayEpochDay == localDayEpochDay) {
                "Daily review coordinator changed the requested local day"
            }
            check(plan.timeZoneId == timeZoneId) {
                "Daily review coordinator changed the requested time zone"
            }
            check(plan.timeBudgetSeconds == timeBudgetSeconds) {
                "Daily review coordinator changed the requested time budget"
            }
            check(plan.maxItemCount == maxItemCount) {
                "Daily review coordinator changed the requested item-count budget"
            }
            check(plan.estimatedDurationSeconds <= timeBudgetSeconds) {
                "Daily review coordinator exceeded the requested time budget"
            }
            DailyReviewPlanningResult(
                plan = plan,
                candidateProfile = result.candidateProfile,
            )
        }
}

internal const val RUNTIME_DAILY_REVIEW_CANDIDATE_LIMIT = 500

private fun String.requireBoundedDailyReviewValue(label: String) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= 256 &&
            none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value of at most 256 characters"
    }
}
