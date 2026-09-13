package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.LogDurationModel
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmissionResult
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import kotlinx.coroutines.flow.first

/**
 * Subjective rating writes for an active review session (spec §2.7): the
 * cooldown-only observation path, the scheduling write with its review-log
 * record, and the L1 duration sample. Extracted from the study repository so
 * the two rating outcomes stay side by side and auditable.
 */
internal class StudyRatingSubmissionService(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val durationModel: LogDurationModel,
    private val reviewLogSink: ReviewLogSink,
    private val writeContext: StudyWriteContext,
    private val submissionPreparer: StudySubmissionPreparer,
    private val learnerSnapshot: suspend () -> LearnerSnapshot,
) {

    suspend fun submit(
        sessionId: String,
        expectedStateVersion: Long,
        submission: StudyReviewRatingSubmission,
    ): StudyReviewRatingSubmissionResult {
        require(sessionId.isNotBlank()) { "Review session id must not be blank" }
        require(expectedStateVersion >= 0) { "Expected review-session version must not be negative" }
        val reviewPlan = requireNotNull(
            database.observeReviewPlanForSession(sessionId).first(),
        ) { "No persisted review plan owns session $sessionId" }
        val activeSession = (reviewPlan.activeSession ?: reviewPlan.latestSession)?.takeIf {
            it.reviewSessionId == sessionId
        } ?: error("Review session $sessionId does not belong to its persisted plan")
        val orderedQueue = reviewPlan.queue.sortedBy { it.ordinal }
        val queueItem = requireNotNull(
            orderedQueue.singleOrNull { it.ordinal.toLong() == expectedStateVersion },
        ) { "Expected review-session version does not identify one planned queue item" }
        require(queueItem.practiceUnitId == submission.practiceUnitId) {
            "Review rating belongs to another planned practice unit"
        }
        val currentMistakes = database.observeMistakes().first()
        val mistake = requireNotNull(
            currentMistakes.singleOrNull { it.practiceUnitId == submission.practiceUnitId },
        ) { "The planned saved question is no longer active" }

        val priorMemory = learnerSnapshot().problemMemoryStates
            ?.get(submission.practiceUnitId)
        val cooldownActive = writeContext.isWithinCooldown(
            practiceUnitId = submission.practiceUnitId,
            sourceKind = ReviewLogSink.SOURCE_KIND_SELF_REPORT,
            cooldownMillis = SUBJECTIVE_COOLDOWN_MILLIS,
            atEpochMillis = submission.occurredAtEpochMillis,
        )

        if (cooldownActive) {
            // Spec §2.7: repeated subjective reports inside the cooldown stay
            // observation-only - they land in review_log, never in the
            // scheduling ledger, and the session keeps its current position.
            val evidence = submissionPreparer.ratingEvidenceFor(submission.rating)
            reviewLogSink.record(
                practiceUnitId = submission.practiceUnitId,
                evidence = evidence,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = writeContext.studyDayAt(submission.occurredAtEpochMillis),
                sourceKind = ReviewLogSink.SOURCE_KIND_SELF_REPORT,
                sourceId = writeContext.stableId("rating", submission.requestId),
                previousReviewedAtEpochMillis = priorMemory?.lastReviewedAtEpochMillis,
                schedulingEligible = false,
                scrollUpCount = submission.scrollUpCount,
                interruptionCount = submission.interruptionCount,
                awayMillis = submission.awayMillis,
            )
            val progress = activeSession.toProgress(orderedQueue.size)
            return StudyReviewRatingSubmissionResult(
                attemptId = writeContext.stableId("rating", submission.requestId),
                created = false,
                rating = submission.rating,
                evidenceReason = evidence.reason,
                progress = progress,
                nextPracticeUnitId = orderedQueue
                    .getOrNull(progress.currentOrdinal)
                    ?.practiceUnitId,
                evidenceSuppressedByCooldown = true,
            )
        }

        val prepared = submissionPreparer.prepareRatingSubmission(submission, mistake)
        database.saveAssessmentEvidenceSnapshot(prepared.evidenceSnapshot)
        val writeResult = database.recordReviewAttempt(
            ReviewAttemptWriteCommand(
                attempt = prepared.command,
                sessionId = sessionId,
                expectedStateVersion = expectedStateVersion,
                reviewQueueItemId = queueItem.reviewQueueItemId,
                practiceUnitId = queueItem.practiceUnitId,
            ),
        )
        if (writeResult.attempt.created) {
            reviewLogSink.record(
                practiceUnitId = submission.practiceUnitId,
                evidence = prepared.command.evidence,
                occurredAtEpochMillis = submission.occurredAtEpochMillis,
                durationSeconds = submission.durationSeconds,
                studyDay = prepared.command.studyDay,
                sourceKind = ReviewLogSink.SOURCE_KIND_SELF_REPORT,
                sourceId = writeResult.attempt.attempt.attemptId,
                previousReviewedAtEpochMillis = priorMemory?.lastReviewedAtEpochMillis,
                scrollUpCount = submission.scrollUpCount,
                interruptionCount = submission.interruptionCount,
                awayMillis = submission.awayMillis,
            )
        }
        // L1 rollout: feed the observed duration (subject already loaded above).
        durationModel.record(
            learnerId = learnerId,
            subjectId = mistake.subject,
            itemType = null,
            difficulty = 5.0, // unused dimension; kept for API stability
            durationSeconds = submission.durationSeconds.toDouble().coerceAtLeast(1.0),
        )
        val progress = writeResult.advance.session.toProgress(orderedQueue.size)
        return StudyReviewRatingSubmissionResult(
            attemptId = writeResult.attempt.attempt.attemptId,
            created = writeResult.attempt.created,
            rating = submission.rating,
            evidenceReason = writeResult.attempt.attempt.evidence.reason,
            progress = progress,
            nextPracticeUnitId = orderedQueue
                .getOrNull(progress.currentOrdinal)
                ?.practiceUnitId,
        )
    }


    private companion object {
        /** Spec 2.7 cooldowns: subjective reports 6h, visual interactions 1h. */
        const val SUBJECTIVE_COOLDOWN_MILLIS = 6L * 60 * 60 * 1000
    }
}
