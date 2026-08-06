package com.tingyun.smartmistakebook.core.data.review

/**
 * Complete host-side REVIEW_PLANNING slot before it is wrapped by the atomic publication owner.
 *
 * The feature receives only these narrow capabilities. Student-owner leases, attempt facts,
 * mastery writers, stores, learner ids, and scheduling internals remain inside core:data.
 */
internal data class ProductionDailyReviewPorts(
    val planning: DailyReviewProductionCapability,
    val sessionActions: DailyReviewSessionActionPort,
    val pacingActions: DailyReviewPacingActionPort,
    val answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
    val assistanceActions: DailyReviewAssistanceActionPort,
    val pacingCommandFactory: DailyReviewPacingCommandFactory,
)
