package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.AttemptAdvanceProofRecord
import com.tingyun.smartmistakebook.core.database.AttemptCorrectionRecord
import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteCommand
import com.tingyun.smartmistakebook.core.database.AttemptIdempotencyConflictException
import com.tingyun.smartmistakebook.core.database.AttemptPersistenceRecord
import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.ConsumedLedgerEventReceipt
import com.tingyun.smartmistakebook.core.database.DatabaseContractValidator
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LearningLedgerRead
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.PersistedAttemptP0
import com.tingyun.smartmistakebook.core.database.PersistedAnswerRevealP0
import com.tingyun.smartmistakebook.core.database.PersistedCorrectionP0
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.PersistedLearningLedgerEvent
import com.tingyun.smartmistakebook.core.database.PersistedIncrementalLearningEvent
import com.tingyun.smartmistakebook.core.database.ProjectionBatch
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCasConflictException
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.database.ProjectionOutboxRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.model.AppliedAttemptRecord
import com.tingyun.smartmistakebook.core.model.AppliedAnswerRevealRecord
import com.tingyun.smartmistakebook.core.model.AppliedCorrectionRecord
import com.tingyun.smartmistakebook.core.model.AppliedTutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import com.tingyun.smartmistakebook.core.database.entity.AppliedAttemptRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedAnswerRevealRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedCorrectionRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AppliedTutorAnswerExposureRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AnswerRevealOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentAnswerRevealEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEvidenceAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEvidenceSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentPresentationEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptCorrectionEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AttemptSubmissionEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorAnswerExposureOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.IndependentCorrectObservationEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerKnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearnerProjectionSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionConsumptionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.PresentationProjectionStateEntity
import kotlinx.coroutines.flow.Flow


@Dao
internal abstract class ProjectionTransactionDao {
    @Query(
        """
        SELECT * FROM learner_projection_snapshot
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findHeader(
        projectionName: String,
        learnerId: String,
    ): LearnerProjectionSnapshotEntity?

    @Query(
        """
        SELECT * FROM projection_outbox
        WHERE learner_id = :learnerId AND outbox_sequence > :afterSequence
        ORDER BY outbox_sequence ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun findOutboxAfter(
        learnerId: String,
        afterSequence: Long,
        limit: Int,
    ): List<ProjectionOutboxEntity>

    @Query(
        """
        SELECT * FROM projection_outbox
        WHERE learner_id = :learnerId
          AND outbox_sequence > :afterSequence
          AND outbox_sequence <= :throughSequence
        ORDER BY outbox_sequence ASC
        """,
    )
    protected abstract suspend fun findOutboxRange(
        learnerId: String,
        afterSequence: Long,
        throughSequence: Long,
    ): List<ProjectionOutboxEntity>

    @Query(
        """
        SELECT * FROM projection_outbox
        WHERE learner_id = :learnerId
        ORDER BY outbox_sequence ASC
        """,
    )
    protected abstract suspend fun findAllOutbox(learnerId: String): List<ProjectionOutboxEntity>

    @Query("SELECT last_allocated_sequence FROM learning_sequence WHERE learner_id = :learnerId")
    protected abstract suspend fun lastAllocatedSequence(learnerId: String): Long?

    @Query("SELECT * FROM attempt_event WHERE attempt_id = :attemptId LIMIT 1")
    protected abstract suspend fun findAttempt(attemptId: String): AttemptEventEntity?

    @Query("SELECT * FROM attempt_correction WHERE correction_id = :correctionId LIMIT 1")
    protected abstract suspend fun findCorrection(correctionId: String): AttemptCorrectionEntity?

    @Query("SELECT * FROM answer_reveal_outcome WHERE outcome_id = :outcomeId LIMIT 1")
    protected abstract suspend fun findProjectionAnswerReveal(
        outcomeId: String,
    ): AnswerRevealOutcomeEntity?

    @Query("SELECT * FROM tutor_answer_exposure_outcome WHERE outcome_id = :outcomeId LIMIT 1")
    protected abstract suspend fun findProjectionTutorExposure(
        outcomeId: String,
    ): TutorAnswerExposureOutcomeEntity?

    @Query("SELECT * FROM assessment_evidence_snapshot WHERE snapshot_id = :snapshotId LIMIT 1")
    protected abstract suspend fun findEvidenceSnapshot(
        snapshotId: String,
    ): AssessmentEvidenceSnapshotEntity?

    @Query(
        """
        SELECT * FROM assessment_evidence_attribution
        WHERE snapshot_id = :snapshotId
        ORDER BY binding_id ASC
        """,
    )
    protected abstract suspend fun findAttributions(
        snapshotId: String,
    ): List<AssessmentEvidenceAttributionEntity>

    @Query(
        """
        SELECT * FROM learner_problem_memory_state
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY practice_unit_id ASC
        """,
    )
    protected abstract suspend fun findMemoryStates(
        projectionName: String,
        learnerId: String,
    ): List<LearnerProblemMemoryStateEntity>

    @Query(
        """
        SELECT * FROM learner_knowledge_mastery_state
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY knowledge_node_id ASC
        """,
    )
    protected abstract suspend fun findMasteryStates(
        projectionName: String,
        learnerId: String,
    ): List<LearnerKnowledgeMasteryStateEntity>

    @Query(
        """
        SELECT * FROM independent_correct_observation
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY knowledge_node_id ASC, ordinal ASC
        """,
    )
    protected abstract suspend fun findObservations(
        projectionName: String,
        learnerId: String,
    ): List<IndependentCorrectObservationEntity>

    @Query(
        """
        SELECT * FROM applied_attempt_record
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY event_sequence ASC
        """,
    )
    protected abstract suspend fun findAppliedAttempts(
        projectionName: String,
        learnerId: String,
    ): List<AppliedAttemptRecordEntity>

    @Query(
        """
        SELECT * FROM applied_correction_record
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY event_sequence ASC
        """,
    )
    protected abstract suspend fun findAppliedCorrections(
        projectionName: String,
        learnerId: String,
    ): List<AppliedCorrectionRecordEntity>

    @Query(
        """
        SELECT * FROM applied_answer_reveal_record
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY event_sequence ASC
        """,
    )
    protected abstract suspend fun findAppliedAnswerReveals(
        projectionName: String,
        learnerId: String,
    ): List<AppliedAnswerRevealRecordEntity>

    @Query(
        """
        SELECT * FROM applied_tutor_answer_exposure_record
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY event_sequence ASC
        """,
    )
    protected abstract suspend fun findAppliedTutorAnswerExposures(
        projectionName: String,
        learnerId: String,
    ): List<AppliedTutorAnswerExposureRecordEntity>

    @Query(
        """
        SELECT * FROM presentation_projection_state
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY presentation_id ASC
        """,
    )
    protected abstract suspend fun findPresentationProjectionStates(
        projectionName: String,
        learnerId: String,
    ): List<PresentationProjectionStateEntity>

    @Query(
        """
        SELECT * FROM presentation_projection_state
        WHERE projection_name = :projectionName
          AND learner_id = :learnerId
          AND presentation_id IN (:presentationIds)
        ORDER BY presentation_id ASC
        """,
    )
    protected abstract suspend fun findPresentationProjectionStatesForIds(
        projectionName: String,
        learnerId: String,
        presentationIds: List<String>,
    ): List<PresentationProjectionStateEntity>

    @Query(
        """
        SELECT * FROM presentation_projection_state
        WHERE projection_name = :projectionName
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findPresentationProjectionState(
        projectionName: String,
        learnerId: String,
        presentationId: String,
    ): PresentationProjectionStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertHeader(header: LearnerProjectionSnapshotEntity)

    @Query(
        """
        UPDATE learner_projection_snapshot
        SET state_version = :newStateVersion,
            checkpoint_sequence = :checkpointSequence,
            known_ledger_head_sequence = :knownLedgerHeadSequence,
            projector_version = :projectorVersion,
            projected_at_epoch_millis = :projectedAtEpochMillis,
            generated_at_epoch_millis = :generatedAtEpochMillis,
            correction_watermark_epoch_millis = :correctionWatermarkEpochMillis,
            freshness = :freshness,
            projection_status = :projectionStatus
        WHERE projection_name = :projectionName
          AND learner_id = :learnerId
          AND checkpoint_sequence = :expectedCheckpoint
          AND state_version = :expectedStateVersion
        """,
    )
    protected abstract suspend fun compareAndSetHeader(
        projectionName: String,
        learnerId: String,
        expectedCheckpoint: Long,
        expectedStateVersion: Long,
        newStateVersion: Long,
        checkpointSequence: Long,
        knownLedgerHeadSequence: Long,
        projectorVersion: String,
        projectedAtEpochMillis: Long,
        generatedAtEpochMillis: Long,
        correctionWatermarkEpochMillis: Long?,
        freshness: String,
        projectionStatus: String,
    ): Int

    @Query(
        "DELETE FROM learner_problem_memory_state WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteMemoryStates(projectionName: String, learnerId: String)

    @Query(
        "DELETE FROM independent_correct_observation WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteObservations(projectionName: String, learnerId: String)

    @Query(
        "DELETE FROM learner_knowledge_mastery_state WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteMasteryStates(projectionName: String, learnerId: String)

    @Query(
        "DELETE FROM applied_attempt_record WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteAppliedAttempts(projectionName: String, learnerId: String)

    @Query(
        "DELETE FROM applied_correction_record WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteAppliedCorrections(projectionName: String, learnerId: String)

    @Query(
        "DELETE FROM applied_answer_reveal_record WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteAppliedAnswerReveals(projectionName: String, learnerId: String)

    @Query(
        "DELETE FROM applied_tutor_answer_exposure_record WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteAppliedTutorAnswerExposures(
        projectionName: String,
        learnerId: String,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMemoryStates(states: List<LearnerProblemMemoryStateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMasteryStates(states: List<LearnerKnowledgeMasteryStateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertObservations(
        observations: List<IndependentCorrectObservationEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAppliedAttempts(records: List<AppliedAttemptRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAppliedCorrections(records: List<AppliedCorrectionRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAppliedAnswerReveals(
        records: List<AppliedAnswerRevealRecordEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAppliedTutorAnswerExposures(
        records: List<AppliedTutorAnswerExposureRecordEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertConsumptions(
        consumptions: List<ProjectionConsumptionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPresentationProjectionState(
        state: PresentationProjectionStateEntity,
    )

    @Query(
        """
        UPDATE presentation_projection_state
        SET terminal_outcome_id = :terminalOutcomeId,
            terminal_outcome = :terminalOutcome,
            terminal_event_sequence = :terminalEventSequence,
            memory_projected = :memoryProjected,
            memory_projection_sequence = :memoryProjectionSequence,
            last_response_ordinal = :lastResponseOrdinal,
            state_version = :nextStateVersion
        WHERE projection_name = :projectionName
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND state_version = :expectedStateVersion
        """,
    )
    protected abstract suspend fun compareAndSetPresentationProjectionState(
        projectionName: String,
        learnerId: String,
        presentationId: String,
        expectedStateVersion: Long,
        terminalOutcomeId: String?,
        terminalOutcome: String?,
        terminalEventSequence: Long?,
        memoryProjected: Boolean,
        memoryProjectionSequence: Long?,
        lastResponseOrdinal: Int,
        nextStateVersion: Long,
    ): Int

    @Transaction
    open suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        limit: Int,
    ): ProjectionBatch {
        DatabaseContractValidator.validateProjectionRequest(projectionName, learnerId, limit)
        val checkpoint = findHeader(projectionName, learnerId)?.checkpointSequence ?: 0L
        val ledgerHead = lastAllocatedSequence(learnerId) ?: 0L
        val rows = findOutboxAfter(learnerId, checkpoint, limit + 1)
        val events = mutableListOf<PersistedIncrementalLearningEvent>()
        var expected = checkpoint + 1
        for (row in rows) {
            if (row.outboxSequence != expected) {
                return batchStop(
                    projectionName,
                    learnerId,
                    checkpoint,
                    ledgerHead,
                    events,
                    ProjectionBatchStopReason.GAP,
                    expected,
                    "Expected sequence $expected but found ${row.outboxSequence}",
                )
            }
            if (row.eventKind == EVENT_KIND_CORRECTION) {
                return batchStop(
                    projectionName,
                    learnerId,
                    checkpoint,
                    ledgerHead,
                    events,
                    ProjectionBatchStopReason.FULL_REPLAY_REQUIRED,
                    expected,
                    "Correction ${row.eventId} requires a full ledger replay",
                )
            }
            val persisted = when (row.eventKind) {
                EVENT_KIND_ATTEMPT -> readAttempt(row)?.let { attempt ->
                    PersistedIncrementalLearningEvent(
                        event = attempt.attempt,
                        canonicalFingerprint = attempt.canonicalFingerprint,
                        outbox = attempt.outbox,
                    )
                }
                EVENT_KIND_ANSWER_REVEAL -> readAnswerReveal(row)?.let { reveal ->
                    PersistedIncrementalLearningEvent(
                        event = reveal.outcome,
                        canonicalFingerprint = reveal.canonicalFingerprint,
                        outbox = reveal.outbox,
                    )
                }
                EVENT_KIND_TUTOR_ANSWER_EXPOSURE -> readTutorAnswerExposure(row)?.let { outcome ->
                    PersistedIncrementalLearningEvent(
                        event = outcome,
                        canonicalFingerprint = row.canonicalFingerprint,
                        outbox = row.toRecord(),
                    )
                }
                else -> null
            } ?: return batchStop(
                projectionName,
                learnerId,
                checkpoint,
                ledgerHead,
                events,
                ProjectionBatchStopReason.CONFLICT,
                expected,
                "Ledger event ${row.eventId} is missing or differs from its canonical outbox",
            )
            if (events.size == limit) {
                return batchStop(
                    projectionName,
                    learnerId,
                    checkpoint,
                    ledgerHead,
                    events,
                    ProjectionBatchStopReason.LIMIT_REACHED,
                    expected,
                    null,
                )
            }
            events += persisted
            expected++
        }
        val consumedThrough = expected - 1
        return if (ledgerHead > consumedThrough) {
            batchStop(
                projectionName,
                learnerId,
                checkpoint,
                ledgerHead,
                events,
                ProjectionBatchStopReason.GAP,
                expected,
                "Sequence $expected was allocated but has no immutable ledger event",
            )
        } else {
            batchStop(
                projectionName,
                learnerId,
                checkpoint,
                ledgerHead,
                events,
                ProjectionBatchStopReason.END_OF_LEDGER,
                null,
                null,
            )
        }
    }

    @Transaction
    open suspend fun loadLearningLedger(learnerId: String): LearningLedgerRead {
        DatabaseContractValidator.validateLedgerRequest(learnerId)
        val prefix = mutableListOf<PersistedLearningLedgerEvent>()
        var expected = 1L
        for (row in findAllOutbox(learnerId)) {
            if (row.outboxSequence != expected) {
                return LearningLedgerRead(
                    learnerId = learnerId,
                    validPrefix = prefix,
                    status = LearningLedgerReadStatus.GAP,
                    blockedAtSequence = expected,
                    detail = "Expected sequence $expected but found ${row.outboxSequence}",
                )
            }
            val event = when (row.eventKind) {
                EVENT_KIND_ATTEMPT -> readAttempt(row)?.let { it.attempt }
                EVENT_KIND_ANSWER_REVEAL -> readAnswerReveal(row)?.let { it.outcome }
                EVENT_KIND_TUTOR_ANSWER_EXPOSURE -> readTutorAnswerExposure(row)
                EVENT_KIND_CORRECTION -> readCorrection(row)?.let { it.correction }
                else -> null
            }
            if (event == null) {
                return LearningLedgerRead(
                    learnerId = learnerId,
                    validPrefix = prefix,
                    status = LearningLedgerReadStatus.CONFLICT,
                    blockedAtSequence = expected,
                    detail = "Ledger event ${row.eventId} is missing or conflicts with its outbox",
                )
            }
            prefix += PersistedLearningLedgerEvent(event, row.canonicalFingerprint)
            expected++
        }
        val allocated = lastAllocatedSequence(learnerId) ?: 0L
        return if (allocated == expected - 1) {
            LearningLedgerRead(learnerId, prefix, LearningLedgerReadStatus.COMPLETE)
        } else {
            LearningLedgerRead(
                learnerId = learnerId,
                validPrefix = prefix,
                status = LearningLedgerReadStatus.GAP,
                blockedAtSequence = expected,
                detail = "Sequence $expected was allocated but has no immutable ledger event",
            )
        }
    }

    @Transaction
    open suspend fun readCurrentSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot? {
        DatabaseContractValidator.validateProjectionRequest(projectionName, learnerId, 1)
        val header = findHeader(projectionName, learnerId) ?: return null
        return header.toPersistedSnapshot(
            memoryStates = findMemoryStates(projectionName, learnerId),
            masteryStates = findMasteryStates(projectionName, learnerId),
            observations = findObservations(projectionName, learnerId),
            appliedAttempts = findAppliedAttempts(projectionName, learnerId),
            appliedCorrections = findAppliedCorrections(projectionName, learnerId),
            appliedAnswerReveals = findAppliedAnswerReveals(projectionName, learnerId),
            appliedTutorAnswerExposures = findAppliedTutorAnswerExposures(projectionName, learnerId),
        )
    }

    @Transaction
    open suspend fun commitProjection(commit: ProjectionCommit): PersistedLearnerSnapshot {
        DatabaseContractValidator.validateProjectionCommit(commit)
        val existing = findHeader(commit.projectionName, commit.learnerId)
        if (existing == null) {
            if (commit.expectedPreviousCheckpoint != 0L || commit.expectedPreviousStateVersion != 0L) {
                throw ProjectionCasConflictException("Projection snapshot does not exist at the expected state")
            }
        } else if (existing.checkpointSequence != commit.expectedPreviousCheckpoint ||
            existing.stateVersion != commit.expectedPreviousStateVersion
        ) {
            throw ProjectionCasConflictException("Projection checkpoint/state version CAS failed")
        }
        val actualLedgerHead = lastAllocatedSequence(commit.learnerId) ?: 0L
        if (commit.knownLedgerHeadSequence > actualLedgerHead ||
            (existing != null && commit.knownLedgerHeadSequence < existing.knownLedgerHeadSequence)
        ) {
            throw ProjectionCasConflictException("Known ledger head is stale or ahead of storage")
        }
        if (commit.snapshot.projectionStatus == ProjectionStatus.CURRENT &&
            commit.snapshot.checkpoint.lastSequence != actualLedgerHead
        ) {
            throw ProjectionCasConflictException("A CURRENT snapshot must include the current ledger head")
        }

        val targetCheckpoint = commit.snapshot.checkpoint.lastSequence
        val rows = findOutboxRange(
            learnerId = commit.learnerId,
            afterSequence = commit.expectedPreviousCheckpoint,
            throughSequence = targetCheckpoint,
        )
        val expectedRows = commit.consumedLedgerEvents
        if (rows.size != expectedRows.size) {
            throw ProjectionCasConflictException("Committed ledger receipts do not cover the pending prefix")
        }
        rows.zip(expectedRows).forEach { (row, receipt) ->
            if (row.eventKind != receipt.eventKind ||
                row.eventId != receipt.eventId ||
                row.outboxSequence != receipt.eventSequence ||
                row.canonicalFingerprint != receipt.canonicalFingerprint ||
                !row.hasValidCanonicalEvent()
            ) {
                throw ProjectionCasConflictException(
                    "Ledger receipt differs from sequence ${receipt.eventSequence}",
                )
            }
        }
        if (commit.mode == ProjectionCommitMode.INCREMENTAL &&
            rows.any {
                it.eventKind != EVENT_KIND_ATTEMPT && it.eventKind != EVENT_KIND_ANSWER_REVEAL
                    && it.eventKind != EVENT_KIND_TUTOR_ANSWER_EXPOSURE
            }
        ) {
            throw ProjectionCasConflictException("Incremental commit cannot cross a correction")
        }
        verifyAppliedEventWindows(commit.snapshot, commit.learnerId)

        val storedSnapshot = if (actualLedgerHead == commit.snapshot.knownLedgerHeadSequence) {
            commit.snapshot
        } else {
            commit.snapshot.copy(
                knownLedgerHeadSequence = actualLedgerHead,
                freshness = LearnerSnapshotFreshness.STALE,
            )
        }
        val newStateVersion = commit.expectedPreviousStateVersion + 1
        val header = storedSnapshot.toEntity(
            projectionName = commit.projectionName,
            stateVersion = newStateVersion,
            knownLedgerHeadSequence = actualLedgerHead,
        )
        if (existing == null) {
            insertHeader(header)
        } else if (compareAndSetHeader(
                projectionName = commit.projectionName,
                learnerId = commit.learnerId,
                expectedCheckpoint = commit.expectedPreviousCheckpoint,
                expectedStateVersion = commit.expectedPreviousStateVersion,
                newStateVersion = newStateVersion,
                checkpointSequence = header.checkpointSequence,
                knownLedgerHeadSequence = header.knownLedgerHeadSequence,
                projectorVersion = header.projectorVersion,
                projectedAtEpochMillis = header.projectedAtEpochMillis,
                generatedAtEpochMillis = header.generatedAtEpochMillis,
                correctionWatermarkEpochMillis = header.correctionWatermarkEpochMillis,
                freshness = header.freshness,
                projectionStatus = header.projectionStatus,
            ) != 1
        ) {
            throw ProjectionCasConflictException("Projection checkpoint/state version CAS failed")
        }

        applyPresentationAuthorityTransitions(
            projectionName = commit.projectionName,
            learnerId = commit.learnerId,
            rows = rows,
        )
        verifyCommittedPresentationStates(commit, rows)

        deleteObservations(commit.projectionName, commit.learnerId)
        deleteMasteryStates(commit.projectionName, commit.learnerId)
        deleteMemoryStates(commit.projectionName, commit.learnerId)
        deleteAppliedAttempts(commit.projectionName, commit.learnerId)
        deleteAppliedCorrections(commit.projectionName, commit.learnerId)
        deleteAppliedAnswerReveals(commit.projectionName, commit.learnerId)
        deleteAppliedTutorAnswerExposures(commit.projectionName, commit.learnerId)
        storedSnapshot.toMemoryEntities(commit.projectionName).insertWhenNotEmpty(::insertMemoryStates)
        val mastery = storedSnapshot.toMasteryEntities(commit.projectionName)
        mastery.first.insertWhenNotEmpty(::insertMasteryStates)
        mastery.second.insertWhenNotEmpty(::insertObservations)
        storedSnapshot.toAppliedEntities(commit.projectionName)
            .insertWhenNotEmpty(::insertAppliedAttempts)
        storedSnapshot.toAppliedCorrectionEntities(commit.projectionName)
            .insertWhenNotEmpty(::insertAppliedCorrections)
        storedSnapshot.toAppliedAnswerRevealEntities(commit.projectionName)
            .insertWhenNotEmpty(::insertAppliedAnswerReveals)
        storedSnapshot.toAppliedTutorAnswerExposureEntities(commit.projectionName)
            .insertWhenNotEmpty(::insertAppliedTutorAnswerExposures)
        rows.map { row ->
            ProjectionConsumptionEntity(
                projectionName = commit.projectionName,
                learnerId = commit.learnerId,
                outboxId = row.outboxId,
                outboxSequence = row.outboxSequence,
                projectorVersion = commit.snapshot.checkpoint.projectorVersion,
                consumedAtEpochMillis = commit.snapshot.checkpoint.projectedAtEpochMillis,
            )
        }.insertWhenNotEmpty(::insertConsumptions)

        return PersistedLearnerSnapshot(
            projectionName = commit.projectionName,
            stateVersion = newStateVersion,
            knownLedgerHeadSequence = actualLedgerHead,
            snapshot = storedSnapshot,
        )
    }

    private suspend fun applyPresentationAuthorityTransitions(
        projectionName: String,
        learnerId: String,
        rows: List<ProjectionOutboxEntity>,
    ) {
        rows.forEach { row ->
            val previous = when (row.eventKind) {
                EVENT_KIND_ATTEMPT -> {
                    val attempt = findAttempt(row.eventId)
                        ?: throw ProjectionCasConflictException(
                            "Attempt ${row.eventId} disappeared during authority projection",
                        )
                    transitionPresentationAuthorityForAttempt(
                        projectionName = projectionName,
                        learnerId = learnerId,
                        attempt = attempt,
                    )
                }
                EVENT_KIND_ANSWER_REVEAL -> {
                    val reveal = findProjectionAnswerReveal(row.eventId)
                        ?: throw ProjectionCasConflictException(
                            "Answer reveal ${row.eventId} disappeared during authority projection",
                        )
                    transitionPresentationAuthorityForReveal(
                        projectionName = projectionName,
                        learnerId = learnerId,
                        reveal = reveal,
                    )
                }
                EVENT_KIND_CORRECTION -> null
                EVENT_KIND_TUTOR_ANSWER_EXPOSURE -> null
                else -> throw ProjectionCasConflictException("Unknown presentation authority event kind")
            }
            previous?.let { persistPresentationProjectionState(it) }
        }
    }

    private suspend fun transitionPresentationAuthorityForAttempt(
        projectionName: String,
        learnerId: String,
        attempt: AttemptEventEntity,
    ): PresentationAuthorityTransition {
        if (attempt.learnerId != learnerId) {
            throw ProjectionCasConflictException("Attempt learner differs from authority learner")
        }
        val current = findPresentationProjectionState(
            projectionName = projectionName,
            learnerId = learnerId,
            presentationId = attempt.presentationId,
        )
        val expectedOrdinal = (current?.lastResponseOrdinal ?: 0) + 1
        if (attempt.responseOrdinal != expectedOrdinal) {
            throw ProjectionCasConflictException(
                "Presentation ${attempt.presentationId} expected response ordinal $expectedOrdinal " +
                    "but found ${attempt.responseOrdinal}",
            )
        }
        current?.terminalEventSequence?.let { terminalSequence ->
            if (terminalSequence >= attempt.eventSequence) {
                throw ProjectionCasConflictException(
                    "Presentation terminal sequence must precede its later response",
                )
            }
        }
        val next = PresentationProjectionStateEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            presentationId = attempt.presentationId,
            terminalOutcomeId = current?.terminalOutcomeId,
            terminalOutcome = current?.terminalOutcome,
            terminalEventSequence = current?.terminalEventSequence,
            memoryProjected = true,
            memoryProjectionSequence = if (current?.terminalEventSequence == null) {
                attempt.eventSequence
            } else {
                current.memoryProjectionSequence
            },
            lastResponseOrdinal = attempt.responseOrdinal,
            stateVersion = (current?.stateVersion ?: 0) + 1,
        )
        return PresentationAuthorityTransition(current, next)
    }

    private suspend fun transitionPresentationAuthorityForReveal(
        projectionName: String,
        learnerId: String,
        reveal: AnswerRevealOutcomeEntity,
    ): PresentationAuthorityTransition {
        if (reveal.learnerId != learnerId) {
            throw ProjectionCasConflictException("Answer-reveal learner differs from authority learner")
        }
        val current = findPresentationProjectionState(
            projectionName = projectionName,
            learnerId = learnerId,
            presentationId = reveal.presentationId,
        )
        if (current?.terminalEventSequence != null) {
            throw ProjectionCasConflictException(
                "Presentation ${reveal.presentationId} already has a projected terminal outcome",
            )
        }
        val next = PresentationProjectionStateEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            presentationId = reveal.presentationId,
            terminalOutcomeId = reveal.outcomeId,
            terminalOutcome = ProblemMemoryOutcome.ANSWER_REVEALED.name,
            terminalEventSequence = reveal.eventSequence,
            memoryProjected = true,
            memoryProjectionSequence = current?.memoryProjectionSequence ?: reveal.eventSequence,
            lastResponseOrdinal = current?.lastResponseOrdinal ?: 0,
            stateVersion = (current?.stateVersion ?: 0) + 1,
        )
        return PresentationAuthorityTransition(current, next)
    }

    private suspend fun persistPresentationProjectionState(
        transition: PresentationAuthorityTransition,
    ) {
        val previous = transition.previous
        val next = transition.next
        check(next.memoryProjected == (next.memoryProjectionSequence != null)) {
            "Presentation memory authority is internally inconsistent"
        }
        check(
            listOf(next.terminalOutcomeId, next.terminalOutcome, next.terminalEventSequence)
                .all { it == null } ||
                listOf(next.terminalOutcomeId, next.terminalOutcome, next.terminalEventSequence)
                    .all { it != null },
        ) { "Presentation terminal authority is internally inconsistent" }
        if (previous == null) {
            insertPresentationProjectionState(next)
            return
        }
        if (compareAndSetPresentationProjectionState(
                projectionName = next.projectionName,
                learnerId = next.learnerId,
                presentationId = next.presentationId,
                expectedStateVersion = previous.stateVersion,
                terminalOutcomeId = next.terminalOutcomeId,
                terminalOutcome = next.terminalOutcome,
                terminalEventSequence = next.terminalEventSequence,
                memoryProjected = next.memoryProjected,
                memoryProjectionSequence = next.memoryProjectionSequence,
                lastResponseOrdinal = next.lastResponseOrdinal,
                nextStateVersion = next.stateVersion,
            ) != 1
        ) {
            throw ProjectionCasConflictException(
                "Presentation ${next.presentationId} authority state-version CAS failed",
            )
        }
    }

    private suspend fun verifyCommittedPresentationStates(
        commit: ProjectionCommit,
        rows: List<ProjectionOutboxEntity>,
    ) {
        val expected = when (commit.mode) {
            ProjectionCommitMode.INCREMENTAL -> {
                val presentationIds = rows.mapNotNullTo(linkedSetOf()) { row ->
                    when (row.eventKind) {
                        EVENT_KIND_ATTEMPT -> findAttempt(row.eventId)?.presentationId
                        EVENT_KIND_ANSWER_REVEAL ->
                            findProjectionAnswerReveal(row.eventId)?.presentationId
                        EVENT_KIND_CORRECTION -> null
                        EVENT_KIND_TUTOR_ANSWER_EXPOSURE -> null
                        else -> null
                    }
                }
                if (presentationIds.isEmpty()) {
                    emptyMap()
                } else {
                    findPresentationProjectionStatesInBatches(
                        projectionName = commit.projectionName,
                        learnerId = commit.learnerId,
                        presentationIds = presentationIds.toList(),
                    ).associate { state ->
                        state.presentationId to state.toModel(commit.snapshot.checkpoint.lastSequence)
                    }
                }
            }

            ProjectionCommitMode.FULL_REPLAY -> findPresentationProjectionStates(
                projectionName = commit.projectionName,
                learnerId = commit.learnerId,
            ).associate { state ->
                state.presentationId to state.toModel(commit.snapshot.checkpoint.lastSequence)
            }
        }
        if (commit.presentationProjectionStates != expected) {
            throw ProjectionCasConflictException(
                "Projected presentation authority differs from the immutable ledger",
            )
        }
    }

    private suspend fun verifyAppliedEventWindows(snapshot: LearnerSnapshot, learnerId: String) {
        snapshot.appliedAttemptRecords.values.forEach { applied ->
            val attempt = findAttempt(applied.attemptId)
                ?: throw ProjectionCasConflictException(
                    "Applied attempt ${applied.attemptId} is not in the immutable ledger",
                )
            if (attempt.learnerId != learnerId ||
                attempt.eventSequence != applied.eventSequence ||
                attempt.canonicalFingerprint != applied.canonicalFingerprint ||
                readAttempt(attempt.toOutbox()) == null
            ) {
                throw ProjectionCasConflictException(
                    "Applied attempt ${applied.attemptId} differs from the immutable ledger",
                )
            }
        }
        snapshot.appliedCorrectionRecords.values.forEach { applied ->
            val correction = findCorrection(applied.correctionId)
                ?: throw ProjectionCasConflictException(
                    "Applied correction ${applied.correctionId} is not in the immutable ledger",
                )
            if (correction.learnerId != learnerId ||
                correction.attemptId != applied.attemptId ||
                correction.eventSequence != applied.eventSequence ||
                correction.canonicalFingerprint != applied.canonicalFingerprint ||
                readCorrection(correction.toOutbox()) == null
            ) {
                throw ProjectionCasConflictException(
                    "Applied correction ${applied.correctionId} differs from the immutable ledger",
                )
            }
        }
        snapshot.appliedAnswerRevealRecords.values.forEach { applied ->
            val reveal = findProjectionAnswerReveal(applied.outcomeId)
                ?: throw ProjectionCasConflictException(
                    "Applied answer reveal ${applied.outcomeId} is not in the immutable ledger",
                )
            if (reveal.learnerId != learnerId ||
                reveal.presentationId != applied.presentationId ||
                reveal.eventSequence != applied.eventSequence ||
                reveal.canonicalFingerprint != applied.canonicalFingerprint ||
                readAnswerReveal(reveal.toOutbox()) == null
            ) {
                throw ProjectionCasConflictException(
                    "Applied answer reveal ${applied.outcomeId} differs from the immutable ledger",
                )
            }
        }
        snapshot.appliedTutorAnswerExposureRecords.values.forEach { applied ->
            val exposure = findProjectionTutorExposure(applied.outcomeId)
                ?: throw ProjectionCasConflictException(
                    "Applied tutor answer exposure ${applied.outcomeId} is not in the immutable ledger",
                )
            if (exposure.learnerId != learnerId ||
                exposure.exposureId != applied.exposureId ||
                exposure.eventSequence != applied.eventSequence ||
                exposure.canonicalFingerprint != applied.canonicalFingerprint ||
                readTutorAnswerExposure(exposure.toOutbox()) == null
            ) {
                throw ProjectionCasConflictException(
                    "Applied tutor answer exposure ${applied.outcomeId} differs from the immutable ledger",
                )
            }
        }
    }

    private suspend fun ProjectionOutboxEntity.hasValidCanonicalEvent(): Boolean = when (eventKind) {
        EVENT_KIND_ATTEMPT -> readAttempt(this) != null
        EVENT_KIND_ANSWER_REVEAL -> readAnswerReveal(this) != null
        EVENT_KIND_TUTOR_ANSWER_EXPOSURE -> readTutorAnswerExposure(this) != null
        EVENT_KIND_CORRECTION -> readCorrection(this) != null
        else -> false
    }

    private suspend fun readAttempt(row: ProjectionOutboxEntity): PersistedAttemptP0? {
        val entity = findAttempt(row.eventId) ?: return null
        if (entity.learnerId != row.learnerId ||
            entity.eventSequence != row.outboxSequence ||
            entity.canonicalFingerprint != row.canonicalFingerprint
        ) return null
        val snapshot = findEvidenceSnapshot(entity.assessmentSnapshotId) ?: return null
        val attempt = runCatching {
            entity.toModel(snapshot.toModel(findAttributions(snapshot.snapshotId)))
        }.getOrNull() ?: return null
        if (LearningLedgerFingerprint.attempt(attempt) != row.canonicalFingerprint) return null
        return PersistedAttemptP0(
            learnerId = entity.learnerId,
            submissionId = entity.submissionId,
            attempt = attempt,
            canonicalFingerprint = row.canonicalFingerprint,
            outbox = row.toRecord(),
        )
    }

    private suspend fun readCorrection(row: ProjectionOutboxEntity): PersistedCorrectionP0? {
        val entity = findCorrection(row.eventId) ?: return null
        if (entity.learnerId != row.learnerId ||
            entity.eventSequence != row.outboxSequence ||
            entity.canonicalFingerprint != row.canonicalFingerprint
        ) return null
        val correction = runCatching(entity::toModel).getOrNull() ?: return null
        if (LearningLedgerFingerprint.correction(correction) != row.canonicalFingerprint) return null
        return PersistedCorrectionP0(
            learnerId = entity.learnerId,
            submissionId = entity.submissionId,
            correction = correction,
            canonicalFingerprint = row.canonicalFingerprint,
            outbox = row.toRecord(),
        )
    }

    private suspend fun readAnswerReveal(row: ProjectionOutboxEntity): PersistedAnswerRevealP0? {
        val entity = findProjectionAnswerReveal(row.eventId) ?: return null
        if (entity.learnerId != row.learnerId ||
            entity.eventSequence != row.outboxSequence ||
            entity.canonicalFingerprint != row.canonicalFingerprint
        ) return null
        val snapshot = findEvidenceSnapshot(entity.assessmentSnapshotId) ?: return null
        val outcome = runCatching {
            entity.toModel(snapshot.toModel(findAttributions(snapshot.snapshotId)))
        }.getOrNull() ?: return null
        if (LearningLedgerFingerprint.answerReveal(outcome) != row.canonicalFingerprint) return null
        return PersistedAnswerRevealP0(
            learnerId = entity.learnerId,
            assessmentEventId = entity.assessmentEventId,
            outcome = outcome,
            canonicalFingerprint = row.canonicalFingerprint,
            outbox = row.toRecord(),
        )
    }

    private suspend fun readTutorAnswerExposure(
        row: ProjectionOutboxEntity,
    ): TutorAnswerExposureOutcome? {
        val entity = findProjectionTutorExposure(row.eventId) ?: return null
        if (entity.learnerId != row.learnerId ||
            entity.eventSequence != row.outboxSequence ||
            entity.canonicalFingerprint != row.canonicalFingerprint
        ) return null
        val outcome = runCatching(entity::toModel).getOrNull() ?: return null
        if (LearningLedgerFingerprint.tutorAnswerExposure(outcome) != row.canonicalFingerprint) return null
        return outcome
    }

    private suspend fun batchStop(
        projectionName: String,
        learnerId: String,
        checkpoint: Long,
        ledgerHead: Long,
        events: List<PersistedIncrementalLearningEvent>,
        reason: ProjectionBatchStopReason,
        blockedAt: Long?,
        detail: String?,
    ): ProjectionBatch {
        val presentationIds = events.mapNotNullTo(linkedSetOf()) { persisted ->
            when (val event = persisted.event) {
                is Attempt -> event.presentationId
                is AnswerRevealOutcome -> event.presentationId
                is TutorAnswerExposureOutcome -> null
            }
        }
        val persistedStates = if (presentationIds.isEmpty()) {
            emptyMap()
        } else {
            findPresentationProjectionStatesInBatches(
                projectionName = projectionName,
                learnerId = learnerId,
                presentationIds = presentationIds.toList(),
            ).associateBy(PresentationProjectionStateEntity::presentationId)
        }
        val authoritativeStates = presentationIds.associateWith { presentationId ->
            persistedStates[presentationId]?.toModel(checkpoint)
                ?: PresentationProjectionState(
                    presentationId = presentationId,
                    asOfLedgerSequence = checkpoint,
                    memoryProjectionApplied = false,
                )
        }
        return ProjectionBatch(
            projectionName = projectionName,
            learnerId = learnerId,
            previousCheckpoint = checkpoint,
            ledgerHeadSequence = ledgerHead,
            events = events,
            authoritativePresentationStates = authoritativeStates,
            stopReason = reason,
            blockedAtSequence = blockedAt,
            detail = detail,
        )
    }

    private suspend fun findPresentationProjectionStatesInBatches(
        projectionName: String,
        learnerId: String,
        presentationIds: List<String>,
    ): List<PresentationProjectionStateEntity> = presentationIds
        .chunked(SQLITE_PRESENTATION_ID_BATCH_SIZE)
        .flatMap { batch ->
            findPresentationProjectionStatesForIds(
                projectionName = projectionName,
                learnerId = learnerId,
                presentationIds = batch,
            )
        }

    private suspend fun <T> List<T>.insertWhenNotEmpty(insert: suspend (List<T>) -> Unit) {
        if (isNotEmpty()) insert(this)
    }
}

