package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.ConsumedLedgerEventReceipt
import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCasConflictException
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.KnowledgeNodeSuccessors
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome

/**
 * Drains the immutable learning ledger into the learner projection (spec §6):
 * incremental batches with CAS retries, and a bounded full replay when the
 * ledger demands one. Extracted from the study repository so the ledger/CAS
 * mechanics stay readable on their own; every database and projector access is
 * an explicit constructor dependency.
 */
internal class StudyProjectionDrainer(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val learningProjector: LearningProjector,
) {

    suspend fun drain(): PersistedLearnerSnapshot? {
        var consecutiveCasConflicts = 0
        // 合并重定向在**一次排空内是常量**，且增量投影与全量重放必须用同一份——
        // 两条路用不同的映射会算出不同的掌握度，而重放的职责正是复现增量的结果。
        val knowledgeNodeSuccessors = KnowledgeNodeSuccessors(database.readKnowledgeNodeSuccessors())
        repeat(MAX_PROJECTION_DRAIN_STEPS) {
            val current = database.readCurrentLearnerSnapshot(PROJECTION_NAME, learnerId)
            val batch = database.loadProjectionBatch(
                projectionName = PROJECTION_NAME,
                learnerId = learnerId,
                limit = PROJECTION_BATCH_SIZE,
            )
            val expectedCheckpoint = current?.snapshot?.checkpoint?.lastSequence ?: 0L
            if (batch.previousCheckpoint != expectedCheckpoint) {
                consecutiveCasConflicts++
                if (consecutiveCasConflicts >= MAX_CAS_RETRIES) {
                    throw ProjectionCasConflictException("Projection checkpoint changed during drain")
                }
                return@repeat
            }
            when (batch.stopReason) {
                ProjectionBatchStopReason.GAP,
                ProjectionBatchStopReason.CONFLICT,
                -> throw LearningLedgerIntegrityException(
                    batch.detail ?: "Learning ledger stopped at ${batch.blockedAtSequence}",
                )

                ProjectionBatchStopReason.FULL_REPLAY_REQUIRED -> {
                    try {
                        commitFullReplay(current, knowledgeNodeSuccessors)
                        consecutiveCasConflicts = 0
                    } catch (conflict: ProjectionCasConflictException) {
                        consecutiveCasConflicts++
                        if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                    }
                }

                ProjectionBatchStopReason.END_OF_LEDGER,
                ProjectionBatchStopReason.LIMIT_REACHED,
                -> {
                    val previous = current?.snapshot ?: LearnerSnapshot.empty(
                        learnerId = learnerId,
                        projectorVersion = LearningProjector.VERSION,
                    )
                    val requiresReplay = previous.checkpoint.projectorVersion != LearningProjector.VERSION ||
                        (
                            batch.events.isEmpty() &&
                                (
                                    previous.freshness != LearnerSnapshotFreshness.CURRENT ||
                                        previous.projectionStatus != ProjectionStatus.CURRENT
                                    )
                            )
                    if (requiresReplay) {
                        try {
                            commitFullReplay(current, knowledgeNodeSuccessors)
                            consecutiveCasConflicts = 0
                        } catch (conflict: ProjectionCasConflictException) {
                            consecutiveCasConflicts++
                            if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                        }
                    } else if (batch.events.isEmpty()) {
                        return current
                    } else {
                        val result = learningProjector.project(
                            previous = previous,
                            events = batch.events.map { it.event },
                            knownLedgerHeadSequence = batch.ledgerHeadSequence,
                            authoritativePresentationStates = batch.authoritativePresentationStates,
                            knowledgeNodeSuccessors = knowledgeNodeSuccessors,
                        )
                        check(
                            result.missingSequence == null &&
                            result.conflictedAttemptIds.isEmpty() &&
                                result.conflictedAnswerRevealOutcomeIds.isEmpty() &&
                                result.conflictedTutorAnswerExposureOutcomeIds.isEmpty() &&
                                result.deferredAttemptIds.isEmpty() &&
                                result.deferredAnswerRevealOutcomeIds.isEmpty() &&
                                result.deferredTutorAnswerExposureOutcomeIds.isEmpty(),
                        ) { "Projector rejected a database-validated incremental prefix" }
                        val commit = ProjectionCommit(
                            projectionName = PROJECTION_NAME,
                            learnerId = learnerId,
                            expectedPreviousCheckpoint = expectedCheckpoint,
                            expectedPreviousStateVersion = current?.stateVersion ?: 0L,
                            mode = ProjectionCommitMode.INCREMENTAL,
                            knownLedgerHeadSequence = batch.ledgerHeadSequence,
                            consumedLedgerEvents = batch.events.map { persisted ->
                                ConsumedLedgerEventReceipt(
                                    eventKind = persisted.outbox.eventKind,
                                    eventId = persisted.outbox.eventId,
                                    eventSequence = persisted.outbox.outboxSequence,
                                    canonicalFingerprint = persisted.canonicalFingerprint,
                                )
                            },
                            presentationProjectionStates = result.presentationProjectionStates,
                            snapshot = result.snapshot,
                        )
                        try {
                            database.commitProjection(commit)
                            consecutiveCasConflicts = 0
                        } catch (conflict: ProjectionCasConflictException) {
                            consecutiveCasConflicts++
                            if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                        }
                    }
                }
            }
        }
        throw ProjectionCasConflictException("Projection did not drain within the bounded work limit")
    }

    private suspend fun commitFullReplay(
        current: PersistedLearnerSnapshot?,
        knowledgeNodeSuccessors: KnowledgeNodeSuccessors,
    ): PersistedLearnerSnapshot {
        val ledger = database.loadLearningLedger(learnerId)
        if (ledger.status != LearningLedgerReadStatus.COMPLETE) {
            throw LearningLedgerIntegrityException(
                ledger.detail ?: "Full replay blocked at ${ledger.blockedAtSequence}",
            )
        }
        val result = learningProjector.replay(
            learnerId = learnerId,
            ledger = ledger.validPrefix.map { it.event },
            knowledgeNodeSuccessors = knowledgeNodeSuccessors,
        )
        val expectedCheckpoint = current?.snapshot?.checkpoint?.lastSequence ?: 0L
        val consumed = ledger.validPrefix
            .filter { it.event.eventSequence > expectedCheckpoint }
            .map { persisted -> persisted.toReceipt() }
        return database.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION_NAME,
                learnerId = learnerId,
                expectedPreviousCheckpoint = expectedCheckpoint,
                expectedPreviousStateVersion = current?.stateVersion ?: 0L,
                mode = ProjectionCommitMode.FULL_REPLAY,
                knownLedgerHeadSequence = result.snapshot.knownLedgerHeadSequence,
                consumedLedgerEvents = consumed,
                presentationProjectionStates = result.presentationProjectionStates,
                snapshot = result.snapshot,
            ),
        )
    }

    private fun com.tingyun.smartmistakebook.core.database.PersistedLearningLedgerEvent.toReceipt() =
        ConsumedLedgerEventReceipt(
            eventKind = when (event) {
                is Attempt -> EVENT_KIND_ATTEMPT
                is AttemptCorrection -> EVENT_KIND_CORRECTION
                is com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome -> EVENT_KIND_ANSWER_REVEAL
                is TutorAnswerExposureOutcome -> EVENT_KIND_TUTOR_ANSWER_EXPOSURE
                is ChatEvidenceSubmitted -> EVENT_KIND_CHAT_EVIDENCE
            },
            eventId = event.ledgerEventId,
            eventSequence = event.eventSequence,
            canonicalFingerprint = canonicalFingerprint,
        )

    private companion object {
        const val PROJECTION_NAME = "study-experience-v1"
        const val PROJECTION_BATCH_SIZE = 100
        const val MAX_CAS_RETRIES = 4
        const val MAX_PROJECTION_DRAIN_STEPS = 64
        const val EVENT_KIND_ATTEMPT = "ATTEMPT"
        const val EVENT_KIND_CORRECTION = "ATTEMPT_CORRECTION"
        const val EVENT_KIND_ANSWER_REVEAL = "ANSWER_REVEAL_OUTCOME"
        const val EVENT_KIND_TUTOR_ANSWER_EXPOSURE = "TUTOR_ANSWER_EXPOSURE_OUTCOME"
        const val EVENT_KIND_CHAT_EVIDENCE = "CHAT_EVIDENCE_SUBMITTED"
    }
}
