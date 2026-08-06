package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef

internal fun addReviewReason(
    encodedReasons: String?,
    reasonCode: String,
): String {
    val reasons = encodedReasons?.let(::decodeOrderedStrings)?.toSet().orEmpty()
    if (reasonCode in reasons || reasons.size >= MAX_STORED_REVIEW_REASONS) {
        return encodeCanonicalSet(reasons)
    }
    return encodeCanonicalSet(reasons + reasonCode)
}

internal fun StudentReviewCandidateEntity.toLearningEvidenceRef(): LearningEvidenceRef? {
    val fields =
        listOf(
            sourceEvidenceEventKind,
            sourceEvidenceEventId,
            sourceEvidenceSequence,
            sourceEvidenceCanonicalFingerprint,
        )
    check(fields.all { it == null } || fields.all { it != null }) {
        "Corrupt review candidate: partial learning-evidence reference"
    }
    return sourceEvidenceEventId?.let { eventId ->
        LearningEvidenceRef(
            learnerId = learnerId,
            eventKind = checkNotNull(sourceEvidenceEventKind),
            eventId = eventId,
            eventSequence = checkNotNull(sourceEvidenceSequence),
            eventCanonicalFingerprint = checkNotNull(sourceEvidenceCanonicalFingerprint),
        )
    }
}

internal fun StudentReviewSessionEntity.hasCoherentActiveReviewOwnership(
    expectedLearnerId: String,
): Boolean =
    learnerId == expectedLearnerId &&
        activeLearnerId == expectedLearnerId &&
        state == StudentReviewSessionState.ACTIVE.name &&
        sessionVersion > 0 &&
        currentQueueItemId != null &&
        currentPresentationId != null &&
        completedAtEpochMillis == null

internal fun reviewPresentationId(
    sessionId: String,
    item: StudentReviewQueueItemEntity,
): String =
    "presentation-" +
        CanonicalSha256("student-review-presentation-v1")
            .field("sessionId", sessionId)
            .field("learnerId", item.learnerId)
            .field("planId", item.planId)
            .field("queueItemId", item.queueItemId)
            .field("practiceUnitId", item.practiceUnitId)
            .field("basisRevisionId", item.basisRevisionId)
            .finish()
            .take(40)

internal fun transitionUnreadyAuditReasonCode(
    receipt: StudentReviewTransitionReceiptEntity,
    item: StudentReviewQueueItemEntity,
    reason: StudentReviewQueueRemovalReason,
): String =
    "transition-unready:${reason.name}:" +
        CanonicalSha256("student-review-transition-unready-v1")
            .field("transitionCanonicalFingerprint", receipt.transitionCanonicalFingerprint)
            .field("queueItemId", item.queueItemId)
            .field("practiceUnitId", item.practiceUnitId)
            .field("basisRevisionId", item.basisRevisionId)
            .field("scheduledOrder", item.scheduledOrder)
            .field("reason", reason.name)
            .finish()
/**
 * Learner-local rolling estimate used only for future daily time budgeting.
 *
 * One unusual attempt cannot replace the prior estimate: the observation is first bounded to
 * one-quarter through four times the baseline, then contributes 25%. The result remains within
 * the same persistence contract as every review candidate.
 */
internal fun rollingReviewDurationSeconds(
    baselineSeconds: Int,
    observedDurationMillis: Long?,
): Int {
    require(baselineSeconds in 1..MAX_ESTIMATED_DURATION_SECONDS) {
        "Review duration baseline is outside the supported range"
    }
    if (observedDurationMillis == null) return baselineSeconds
    require(observedDurationMillis in 0..MAX_REVIEW_PACING_ELAPSED_DURATION_MILLIS) {
        "Observed review duration is outside the supported range"
    }
    val rawObservedSeconds =
        ((observedDurationMillis + 999L) / 1_000L)
            .coerceIn(1L, MAX_ESTIMATED_DURATION_SECONDS.toLong())
    val observedSeconds =
        rawObservedSeconds.coerceIn(
            minimumValue =
                maxOf(
                    1L,
                    baselineSeconds.toLong() / REVIEW_DURATION_OUTLIER_RATIO,
                ),
            maximumValue =
                minOf(
                    MAX_ESTIMATED_DURATION_SECONDS.toLong(),
                    baselineSeconds.toLong() * REVIEW_DURATION_OUTLIER_RATIO,
                ),
        )
    return (
        (
            baselineSeconds.toLong() * REVIEW_DURATION_BASELINE_WEIGHT +
                observedSeconds +
                REVIEW_DURATION_ROUNDING_OFFSET
        ) / REVIEW_DURATION_TOTAL_WEIGHT
    ).toInt()
        .coerceIn(1, MAX_ESTIMATED_DURATION_SECONDS)
}

internal const val REVIEW_RESPONSE_REASON = "verified-review-response"
internal const val REVIEW_DONE_REASON = "review-done"
internal const val REVIEW_STUCK_REASON = "review-stuck"
internal const val REVIEW_REVEALED_REASON = "review-answer-revealed"
internal const val MAX_REVIEW_PACING_ELAPSED_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
internal const val REVIEW_DURATION_BASELINE_WEIGHT = 3L
internal const val REVIEW_DURATION_TOTAL_WEIGHT = 4L
internal const val REVIEW_DURATION_ROUNDING_OFFSET = 2L
internal const val REVIEW_DURATION_OUTLIER_RATIO = 4L
