package com.tingyun.smartmistakebook.core.domain

/**
 * Detects split-import jobs that were created but never promoted to READY.
 *
 * `create` writes the job row and its questions in a single transaction, and
 * both producers then flip the status to READY in a *second* write whose result
 * they ignore — `BatchSplitRecognizer` for batch pages and the capture workflow
 * for single-page splits. A crash or throw between those two writes leaves the
 * job in PREPARING, which is a trap for the student rather than a neutral state:
 *
 * - [SplitImportJobSummary.readyForReview] is true for PREPARING, so the review
 *   screen shows the job;
 * - the store's `updateSelection` and `markQuestionConfirmed` both require READY,
 *   so that screen cannot operate on it.
 *
 * The only exit left is discarding the whole job, which throws away the model's
 * work. Because the questions committed together with the job row, a PREPARING
 * job always has its questions, so promoting it merely completes the sequence
 * that was interrupted — it is never a guess about missing data.
 *
 * The grace window exists so a recovery pass cannot race a writer that is
 * legitimately between the two writes right now.
 */
object SplitImportRecovery {

    /** How long a job may sit in PREPARING before recovery treats it as stuck. */
    const val STUCK_PREPARING_GRACE_MILLIS: Long = 5L * 60L * 1_000L

    fun isStuckPreparing(
        status: String,
        createdAtEpochMillis: Long,
        nowEpochMillis: Long,
        graceMillis: Long = STUCK_PREPARING_GRACE_MILLIS,
    ): Boolean {
        require(graceMillis >= 0) { "Split recovery grace must not be negative" }
        if (status != SplitImportStatus.PREPARING) return false
        // A job stamped in the future is a clock anomaly, not a stuck job; the
        // negative age keeps it out, so a writer running right now is not cut short.
        return nowEpochMillis - createdAtEpochMillis >= graceMillis
    }
}
