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
import com.tingyun.smartmistakebook.core.model.AppliedLearningObservationRecord
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
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
import com.tingyun.smartmistakebook.core.database.entity.AppliedLearningObservationRecordEntity
import com.tingyun.smartmistakebook.core.database.entity.AttributedLearningObservationEventEntity
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
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionConsumptionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.PresentationProjectionStateEntity
import kotlinx.coroutines.flow.Flow

internal const val EVENT_KIND_ATTEMPT = "ATTEMPT"
internal const val EVENT_KIND_CORRECTION = "ATTEMPT_CORRECTION"
internal const val EVENT_KIND_ANSWER_REVEAL = "ANSWER_REVEAL_OUTCOME"
private const val SQLITE_PRESENTATION_ID_BATCH_SIZE = 900

internal data class AttemptPersistenceRow(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "submission_id")
    val submissionId: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    @ColumnInfo(name = "attempt_event_count")
    val attemptEventCount: Int,
    @ColumnInfo(name = "outbox_count")
    val outboxCount: Int,
)

internal data class AttemptAdvanceProofRow(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "submission_id")
    val submissionId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "outbox_id")
    val outboxId: String,
)

internal data class AttemptTransactionResult(
    val created: Boolean,
    val submissionId: String,
    val attempt: Attempt,
    val canonicalFingerprint: String,
    val outbox: ProjectionOutboxEntity,
)

internal data class CorrectionTransactionResult(
    val created: Boolean,
    val submissionId: String,
    val correction: AttemptCorrection,
    val canonicalFingerprint: String,
    val outbox: ProjectionOutboxEntity,
)

internal data class AnswerRevealTransactionResult(
    val created: Boolean,
    val assessmentEventId: String,
    val outcome: AnswerRevealOutcome,
    val canonicalFingerprint: String,
    val outbox: ProjectionOutboxEntity,
)

private data class PresentationAuthorityTransition(
    val previous: PresentationProjectionStateEntity?,
    val next: PresentationProjectionStateEntity,
)

private fun PresentationProjectionStateEntity.toModel(asOfLedgerSequence: Long) = PresentationProjectionState(
    presentationId = presentationId,
    asOfLedgerSequence = asOfLedgerSequence,
    memoryProjectionApplied = memoryProjected,
    answerRevealSequence = terminalEventSequence,
)

@Dao
internal abstract class ImmutableLearningFactDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAssessmentItem(item: AssessmentItemSnapshotEntity): Long

    @Query("SELECT * FROM assessment_item_snapshot WHERE assessment_item_snapshot_id = :id LIMIT 1")
    abstract suspend fun findAssessmentItem(id: String): AssessmentItemSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAssessmentEvent(event: AssessmentEventEntity): Long

    @Query("SELECT * FROM assessment_event WHERE assessment_event_id = :id LIMIT 1")
    abstract suspend fun findAssessmentEvent(id: String): AssessmentEventEntity?

    @Transaction
    open suspend fun saveAssessmentItem(item: AssessmentItemSnapshotEntity): Boolean {
        if (insertAssessmentItem(item) != -1L) return true
        if (findAssessmentItem(item.assessmentItemSnapshotId) == item) return false
        throw ImmutablePayloadConflictException("assessment_item_snapshot", item.assessmentItemSnapshotId)
    }

    @Transaction
    open suspend fun saveAssessmentEvent(event: AssessmentEventEntity): Boolean {
        if (insertAssessmentEvent(event) != -1L) return true
        if (findAssessmentEvent(event.assessmentEventId) == event) return false
        throw ImmutablePayloadConflictException("assessment_event", event.assessmentEventId)
    }
}

@Dao
internal abstract class LearningDao {
    @Query(
        "SELECT COALESCE(MAX(last_allocated_sequence), 0) " +
            "FROM learning_sequence WHERE learner_id = :learnerId",
    )
    abstract fun observeLedgerHead(learnerId: String): Flow<Long>

    @Query(
        """
        SELECT
            submission.learner_id,
            submission.submission_id,
            submission.payload_fingerprint,
            COUNT(DISTINCT event.attempt_id) AS attempt_event_count,
            COUNT(DISTINCT outbox.outbox_id) AS outbox_count
        FROM attempt_submission AS submission
        LEFT JOIN attempt_event AS event ON event.submission_id = submission.submission_id
        LEFT JOIN projection_outbox AS outbox
          ON outbox.event_kind = 'ATTEMPT' AND outbox.event_id = event.attempt_id
        WHERE submission.submission_id = :submissionId
        GROUP BY submission.learner_id, submission.submission_id, submission.payload_fingerprint
        """,
    )
    protected abstract suspend fun findAttemptPersistenceRow(
        submissionId: String,
    ): AttemptPersistenceRow?

    @Query(
        """
        SELECT
            attempt.learner_id,
            attempt.attempt_id,
            attempt.submission_id,
            attempt.presentation_id,
            snapshot.practice_unit_id,
            attempt.occurred_at_epoch_millis,
            attempt.event_sequence,
            attempt.canonical_fingerprint,
            outbox.outbox_id
        FROM attempt_event AS attempt
        JOIN assessment_evidence_snapshot AS snapshot
          ON snapshot.snapshot_id = attempt.assessment_snapshot_id
        JOIN projection_outbox AS outbox
          ON outbox.event_kind = 'ATTEMPT'
         AND outbox.event_id = attempt.attempt_id
         AND outbox.learner_id = attempt.learner_id
         AND outbox.outbox_sequence = attempt.event_sequence
         AND outbox.canonical_fingerprint = attempt.canonical_fingerprint
        WHERE attempt.attempt_id = :attemptId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAttemptAdvanceProofRow(
        attemptId: String,
    ): AttemptAdvanceProofRow?

    @Query("SELECT * FROM attempt_event WHERE attempt_id = :attemptId LIMIT 1")
    protected abstract suspend fun findAttemptEntity(attemptId: String): AttemptEventEntity?

    @Query("SELECT * FROM attempt_correction WHERE correction_id = :correctionId LIMIT 1")
    protected abstract suspend fun findCorrectionEntity(correctionId: String): AttemptCorrectionEntity?

    @Query("SELECT * FROM answer_reveal_outcome WHERE outcome_id = :outcomeId LIMIT 1")
    protected abstract suspend fun findAnswerRevealEntity(outcomeId: String): AnswerRevealOutcomeEntity?

    @Query("SELECT * FROM assessment_evidence_snapshot WHERE snapshot_id = :snapshotId LIMIT 1")
    protected abstract suspend fun findEvidenceSnapshotEntity(
        snapshotId: String,
    ): AssessmentEvidenceSnapshotEntity?

    @Query(
        """
        SELECT * FROM assessment_evidence_attribution
        WHERE snapshot_id = :snapshotId
        ORDER BY binding_id ASC
        """,
    )
    protected abstract suspend fun findAttributionEntities(
        snapshotId: String,
    ): List<AssessmentEvidenceAttributionEntity>

    @Query(
        """
        SELECT * FROM projection_outbox
        WHERE event_kind = :eventKind AND event_id = :eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findEventOutboxEntity(
        eventKind: String,
        eventId: String,
    ): ProjectionOutboxEntity?

    @Transaction
    open suspend fun findAttemptPersistence(submissionId: String): AttemptPersistenceRecord? =
        findAttemptPersistenceRow(submissionId)?.let { row ->
            AttemptPersistenceRecord(
                learnerId = row.learnerId,
                submissionId = row.submissionId,
                payloadFingerprint = row.payloadFingerprint,
                attemptEventCount = row.attemptEventCount,
                outboxCount = row.outboxCount,
            )
        }

    @Transaction
    open suspend fun findAttemptAdvanceProof(attemptId: String): AttemptAdvanceProofRecord? {
        val row = findAttemptAdvanceProofRow(attemptId) ?: return null
        val persisted = try {
            readAttemptVerified(attemptId)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } ?: return null
        val attempt = persisted.attempt
        if (persisted.learnerId != row.learnerId ||
            persisted.submissionId != row.submissionId ||
            attempt.attemptId != row.attemptId ||
            attempt.presentationId != row.presentationId ||
            attempt.assessmentSnapshot.practiceUnitId != row.practiceUnitId ||
            attempt.occurredAtEpochMillis != row.occurredAtEpochMillis ||
            attempt.eventSequence != row.eventSequence ||
            persisted.canonicalFingerprint != row.canonicalFingerprint ||
            persisted.outbox.outboxId != row.outboxId ||
            persisted.outbox.learnerId != row.learnerId ||
            persisted.outbox.outboxSequence != row.eventSequence
        ) {
            return null
        }
        return AttemptAdvanceProofRecord(
            learnerId = row.learnerId,
            attemptId = row.attemptId,
            submissionId = row.submissionId,
            presentationId = row.presentationId,
            practiceUnitId = row.practiceUnitId,
            occurredAtEpochMillis = row.occurredAtEpochMillis,
        )
    }

    @Transaction
    open suspend fun readAttempt(attemptId: String): PersistedAttemptP0? =
        readAttemptVerified(attemptId)

    private suspend fun readAttemptVerified(attemptId: String): PersistedAttemptP0? {
        val entity = findAttemptEntity(attemptId) ?: return null
        val attempt = readAttemptModel(entity) ?: return null
        val outbox = findEventOutboxEntity(EVENT_KIND_ATTEMPT, attemptId) ?: return null
        DatabaseContractValidator.verifyCanonicalFingerprint(attempt, entity.canonicalFingerprint)
        check(outbox.canonicalFingerprint == entity.canonicalFingerprint)
        return PersistedAttemptP0(
            learnerId = entity.learnerId,
            submissionId = entity.submissionId,
            attempt = attempt,
            canonicalFingerprint = entity.canonicalFingerprint,
            outbox = outbox.toRecord(),
        )
    }

    @Transaction
    open suspend fun readCorrection(correctionId: String): PersistedCorrectionP0? {
        val entity = findCorrectionEntity(correctionId) ?: return null
        val correction = entity.toModel()
        val outbox = findEventOutboxEntity(EVENT_KIND_CORRECTION, correctionId) ?: return null
        DatabaseContractValidator.verifyCanonicalFingerprint(correction, entity.canonicalFingerprint)
        check(outbox.canonicalFingerprint == entity.canonicalFingerprint)
        return PersistedCorrectionP0(
            learnerId = entity.learnerId,
            submissionId = entity.submissionId,
            correction = correction,
            canonicalFingerprint = entity.canonicalFingerprint,
            outbox = outbox.toRecord(),
        )
    }

    @Transaction
    open suspend fun readAnswerReveal(outcomeId: String): PersistedAnswerRevealP0? {
        val entity = findAnswerRevealEntity(outcomeId) ?: return null
        val snapshot = findEvidenceSnapshotEntity(entity.assessmentSnapshotId) ?: return null
        val outcome = entity.toModel(
            snapshot.toModel(findAttributionEntities(snapshot.snapshotId)),
        )
        val outbox = findEventOutboxEntity(EVENT_KIND_ANSWER_REVEAL, outcomeId) ?: return null
        DatabaseContractValidator.verifyCanonicalFingerprint(outcome, entity.canonicalFingerprint)
        check(outbox.canonicalFingerprint == entity.canonicalFingerprint)
        return PersistedAnswerRevealP0(
            learnerId = entity.learnerId,
            assessmentEventId = entity.assessmentEventId,
            outcome = outcome,
            canonicalFingerprint = entity.canonicalFingerprint,
            outbox = outbox.toRecord(),
        )
    }

    private suspend fun readAttemptModel(entity: AttemptEventEntity): Attempt? {
        val snapshot = findEvidenceSnapshotEntity(entity.assessmentSnapshotId) ?: return null
        return entity.toModel(snapshot.toModel(findAttributionEntities(snapshot.snapshotId)))
    }
}

@Dao
internal abstract class AttemptTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvidenceSnapshot(
        snapshot: AssessmentEvidenceSnapshotEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAttributions(
        attributions: List<AssessmentEvidenceAttributionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSubmission(submission: AttemptSubmissionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAttemptEvent(event: AttemptEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCorrection(correction: AttemptCorrectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPresentation(
        presentation: AssessmentPresentationEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAnswerRevealEvent(
        event: AssessmentAnswerRevealEventEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAnswerRevealOutcome(outcome: AnswerRevealOutcomeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutbox(outbox: ProjectionOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun initializeSequence(sequence: LearningSequenceEntity): Long

    @Query(
        """
        UPDATE learning_sequence
        SET last_allocated_sequence = :next
        WHERE learner_id = :learnerId AND last_allocated_sequence = :expected
        """,
    )
    protected abstract suspend fun compareAndSetSequence(
        learnerId: String,
        expected: Long,
        next: Long,
    ): Int

    @Query("SELECT last_allocated_sequence FROM learning_sequence WHERE learner_id = :learnerId")
    protected abstract suspend fun lastAllocatedSequence(learnerId: String): Long?

    @Query("SELECT * FROM attempt_submission WHERE submission_id = :submissionId LIMIT 1")
    protected abstract suspend fun findSubmission(submissionId: String): AttemptSubmissionEntity?

    @Query("SELECT * FROM attempt_event WHERE submission_id = :submissionId LIMIT 1")
    protected abstract suspend fun findAttemptBySubmission(submissionId: String): AttemptEventEntity?

    @Query("SELECT * FROM attempt_event WHERE attempt_id = :attemptId LIMIT 1")
    protected abstract suspend fun findAttempt(attemptId: String): AttemptEventEntity?

    @Query("SELECT * FROM attempt_correction WHERE correction_id = :correctionId LIMIT 1")
    protected abstract suspend fun findCorrection(correctionId: String): AttemptCorrectionEntity?

    @Query("SELECT * FROM answer_reveal_outcome WHERE outcome_id = :outcomeId LIMIT 1")
    protected abstract suspend fun findAnswerReveal(outcomeId: String): AnswerRevealOutcomeEntity?

    @Query(
        """
        SELECT * FROM assessment_presentation
        WHERE learner_id = :learnerId AND presentation_id = :presentationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findPresentation(
        learnerId: String,
        presentationId: String,
    ): AssessmentPresentationEntity?

    @Query(
        """
        UPDATE assessment_presentation
        SET last_response_ordinal = :nextOrdinal,
            state_version = state_version + 1,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND presentation_id = :presentationId
          AND assessment_snapshot_id = :assessmentSnapshotId
          AND last_response_ordinal = :expectedOrdinal
          AND state_version = :expectedVersion
        """,
    )
    protected abstract suspend fun compareAndSetResponseOrdinal(
        learnerId: String,
        presentationId: String,
        assessmentSnapshotId: String,
        expectedOrdinal: Int,
        expectedVersion: Long,
        nextOrdinal: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE assessment_presentation
        SET terminal = 1,
            state_version = state_version + 1,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND presentation_id = :presentationId
          AND assessment_snapshot_id = :assessmentSnapshotId
          AND state_version = :expectedVersion
          AND terminal = 0
        """,
    )
    protected abstract suspend fun compareAndSetTerminal(
        learnerId: String,
        presentationId: String,
        assessmentSnapshotId: String,
        expectedVersion: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("SELECT * FROM assessment_answer_reveal_event WHERE assessment_event_id = :eventId LIMIT 1")
    protected abstract suspend fun findAnswerRevealEvent(
        eventId: String,
    ): AssessmentAnswerRevealEventEntity?

    @Query(
        """
        SELECT * FROM assessment_answer_reveal_event
        WHERE learner_id = :learnerId AND presentation_id = :presentationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAnswerRevealEventForPresentation(
        learnerId: String,
        presentationId: String,
    ): AssessmentAnswerRevealEventEntity?

    @Query("SELECT * FROM answer_reveal_outcome WHERE outcome_id = :outcomeId LIMIT 1")
    protected abstract suspend fun findAnswerRevealOutcome(
        outcomeId: String,
    ): AnswerRevealOutcomeEntity?

    @Query(
        """
        SELECT * FROM answer_reveal_outcome
        WHERE learner_id = :learnerId AND presentation_id = :presentationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAnswerRevealOutcomeForPresentation(
        learnerId: String,
        presentationId: String,
    ): AnswerRevealOutcomeEntity?

    @Query(
        """
        SELECT event.*
        FROM assessment_answer_reveal_event AS event
        LEFT JOIN answer_reveal_outcome AS outcome
          ON outcome.assessment_event_id = event.assessment_event_id
        WHERE event.learner_id = :learnerId
          AND outcome.outcome_id IS NULL
        ORDER BY event.occurred_at_epoch_millis ASC, event.assessment_event_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun findOrphanAnswerRevealEvents(
        learnerId: String,
        limit: Int,
    ): List<AssessmentAnswerRevealEventEntity>

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

    @Query("SELECT * FROM practice_unit_knowledge_binding WHERE binding_id = :bindingId LIMIT 1")
    protected abstract suspend fun findBinding(bindingId: String): PracticeUnitKnowledgeBindingEntity?

    @Query(
        """
        SELECT * FROM projection_outbox
        WHERE event_kind = :eventKind AND event_id = :eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findEventOutbox(
        eventKind: String,
        eventId: String,
    ): ProjectionOutboxEntity?

    @Transaction
    open suspend fun saveAssessmentEvidenceSnapshot(snapshot: AssessmentEvidenceSnapshot): Boolean {
        DatabaseContractValidator.validateAssessmentEvidenceSnapshot(snapshot)
        return saveEvidenceSnapshot(snapshot)
    }

    @Transaction
    open suspend fun recordAttempt(command: AttemptWriteCommand): AttemptTransactionResult {
        DatabaseContractValidator.validateAttempt(command)
        findSubmission(command.submissionId)?.let { existing ->
            return readAttemptReplay(existing, command)
        }
        val snapshot = readEvidenceSnapshotModel(command.assessmentSnapshotId)
        val presentation = loadOrCreatePresentation(
            learnerId = command.learnerId,
            presentationId = command.presentationId,
            assessmentSnapshotId = command.assessmentSnapshotId,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        val authoritativeCommand = canonicalizeAttemptForPresentation(command, presentation)
        if (presentation.lastResponseOrdinal == Int.MAX_VALUE) {
            throw ImmutablePayloadConflictException("assessment_presentation", command.presentationId)
        }
        val responseOrdinal = presentation.lastResponseOrdinal + 1
        DatabaseContractValidator.constructAttempt(
            command = authoritativeCommand,
            assessmentSnapshot = snapshot,
            responseOrdinal = responseOrdinal,
            eventSequence = 1,
        )
        if (compareAndSetResponseOrdinal(
                learnerId = command.learnerId,
                presentationId = command.presentationId,
                assessmentSnapshotId = command.assessmentSnapshotId,
                expectedOrdinal = presentation.lastResponseOrdinal,
                expectedVersion = presentation.stateVersion,
                nextOrdinal = responseOrdinal,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("assessment_presentation", command.presentationId)
        }
        val sequence = allocateSequence(command.learnerId)
        val attempt = DatabaseContractValidator.constructAttempt(
            command = authoritativeCommand,
            assessmentSnapshot = snapshot,
            responseOrdinal = responseOrdinal,
            eventSequence = sequence,
        )
        val fingerprint = LearningLedgerFingerprint.attempt(attempt)
        val submission = AttemptSubmissionEntity(
            submissionId = command.submissionId,
            learnerId = command.learnerId,
            payloadFingerprint = fingerprint,
        )
        val event = attempt.toEntity(command.learnerId, command.submissionId, fingerprint)
        val outbox = event.toOutbox()
        insertSubmission(submission)
        insertAttemptEvent(event)
        insertOutbox(outbox)
        return AttemptTransactionResult(
            created = true,
            submissionId = command.submissionId,
            attempt = attempt,
            canonicalFingerprint = fingerprint,
            outbox = outbox,
        )
    }

    @Transaction
    open suspend fun recordAnswerReveal(
        command: AnswerRevealWriteCommand,
    ): AnswerRevealTransactionResult {
        DatabaseContractValidator.validateAnswerReveal(command)
        val event = command.toAnswerRevealEventEntity()
        val inserted = insertAnswerRevealEvent(event) != -1L
        if (!inserted && findAnswerRevealEvent(command.assessmentEventId) != event) {
            throw ImmutablePayloadConflictException(
                "assessment_answer_reveal_event",
                command.assessmentEventId,
            )
        }
        val outcomeId = DatabaseContractValidator.answerRevealOutcomeId(command.assessmentEventId)
        findAnswerRevealOutcome(outcomeId)?.let { existing ->
            return readAnswerRevealReplay(existing, event)
        }
        return materializeAnswerReveal(event, allowAlreadyTerminal = !inserted)
    }

    /**
     * Migration-only bridge for databases created by a build that committed the assessment event
     * before answer-reveal outcomes joined the learning ledger. Normal writes use recordAnswerReveal.
     */
    @Transaction
    open suspend fun importLegacyAnswerRevealAssessmentEvent(
        command: AnswerRevealWriteCommand,
    ): Boolean {
        DatabaseContractValidator.validateAnswerReveal(command)
        readEvidenceSnapshotModel(command.assessmentSnapshotId)
        val presentation = loadOrCreatePresentation(
            learnerId = command.learnerId,
            presentationId = command.presentationId,
            assessmentSnapshotId = command.assessmentSnapshotId,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        val event = command.toAnswerRevealEventEntity()
        val inserted = insertAnswerRevealEvent(event) != -1L
        if (!inserted && findAnswerRevealEvent(command.assessmentEventId) != event) {
            throw ImmutablePayloadConflictException(
                "assessment_answer_reveal_event",
                command.assessmentEventId,
            )
        }
        if (!presentation.terminal && compareAndSetTerminal(
                learnerId = command.learnerId,
                presentationId = command.presentationId,
                assessmentSnapshotId = command.assessmentSnapshotId,
                expectedVersion = presentation.stateVersion,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("assessment_presentation", command.presentationId)
        }
        return inserted
    }

    @Transaction
    open suspend fun reconcileAnswerRevealOutcomes(
        learnerId: String,
        limit: Int,
    ): List<AnswerRevealTransactionResult> {
        DatabaseContractValidator.validateLedgerRequest(learnerId)
        require(limit in 1..1_000) { "limit must be between 1 and 1000" }
        return findOrphanAnswerRevealEvents(learnerId, limit).map { event ->
            materializeAnswerReveal(event, allowAlreadyTerminal = true)
        }
    }

    @Transaction
    open suspend fun appendCorrection(
        command: AttemptCorrectionRecord,
    ): CorrectionTransactionResult {
        DatabaseContractValidator.validateCorrection(command)
        val targetEntity = findAttempt(command.attemptId)
            ?: throw ImmutablePayloadConflictException("correction_target", command.attemptId)
        if (targetEntity.learnerId != command.learnerId ||
            targetEntity.submissionId != command.submissionId
        ) {
            throw ImmutablePayloadConflictException("correction_target", command.attemptId)
        }
        val target = readAttemptModel(targetEntity)
        val authoritativeCommand = canonicalizeCorrectionForPresentation(command, target)
        findCorrection(command.correctionId)?.let { existing ->
            return readCorrectionReplay(existing, authoritativeCommand)
        }
        DatabaseContractValidator.validateCorrectionAgainstAttempt(authoritativeCommand, target)
        val sequence = allocateSequence(command.learnerId)
        val correction = DatabaseContractValidator.constructCorrection(authoritativeCommand, sequence)
        val fingerprint = LearningLedgerFingerprint.correction(correction)
        val entity = correction.toEntity(
            learnerId = command.learnerId,
            submissionId = command.submissionId,
            canonicalFingerprint = fingerprint,
        )
        val outbox = entity.toOutbox()
        insertCorrection(entity)
        insertOutbox(outbox)
        return CorrectionTransactionResult(
            created = true,
            submissionId = command.submissionId,
            correction = correction,
            canonicalFingerprint = fingerprint,
            outbox = outbox,
        )
    }

    private suspend fun materializeAnswerReveal(
        event: AssessmentAnswerRevealEventEntity,
        allowAlreadyTerminal: Boolean,
    ): AnswerRevealTransactionResult {
        val snapshot = readEvidenceSnapshotModel(event.assessmentSnapshotId)
        val presentation = loadOrCreatePresentation(
            learnerId = event.learnerId,
            presentationId = event.presentationId,
            assessmentSnapshotId = event.assessmentSnapshotId,
            occurredAtEpochMillis = event.occurredAtEpochMillis,
        )
        if (presentation.terminal && !allowAlreadyTerminal) {
            throw ImmutablePayloadConflictException("terminal_presentation", event.presentationId)
        }
        if (!presentation.terminal && compareAndSetTerminal(
                learnerId = event.learnerId,
                presentationId = event.presentationId,
                assessmentSnapshotId = event.assessmentSnapshotId,
                expectedVersion = presentation.stateVersion,
                updatedAtEpochMillis = event.occurredAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("assessment_presentation", event.presentationId)
        }
        val sequence = allocateSequence(event.learnerId)
        val outcome = event.toModel(snapshot, sequence)
        val fingerprint = LearningLedgerFingerprint.answerReveal(outcome)
        val entity = outcome.toEntity(event, fingerprint)
        val outbox = entity.toOutbox()
        insertAnswerRevealOutcome(entity)
        insertOutbox(outbox)
        return AnswerRevealTransactionResult(
            created = true,
            assessmentEventId = event.assessmentEventId,
            outcome = outcome,
            canonicalFingerprint = fingerprint,
            outbox = outbox,
        )
    }

    private suspend fun readAnswerRevealReplay(
        existing: AnswerRevealOutcomeEntity,
        event: AssessmentAnswerRevealEventEntity,
    ): AnswerRevealTransactionResult {
        if (existing.learnerId != event.learnerId ||
            existing.assessmentEventId != event.assessmentEventId ||
            existing.presentationId != event.presentationId ||
            existing.assessmentSnapshotId != event.assessmentSnapshotId ||
            existing.occurredAtEpochMillis != event.occurredAtEpochMillis ||
            existing.studyDayEpochDay != event.studyDayEpochDay ||
            existing.studyDayTimeZoneId != event.studyDayTimeZoneId ||
            existing.studyDayUtcOffsetMinutes != event.studyDayUtcOffsetMinutes
        ) {
            throw ImmutablePayloadConflictException("answer_reveal_outcome", existing.outcomeId)
        }
        val outcome = existing.toModel(readEvidenceSnapshotModel(existing.assessmentSnapshotId))
        if (LearningLedgerFingerprint.answerReveal(outcome) != existing.canonicalFingerprint) {
            throw ImmutablePayloadConflictException("answer_reveal_outcome", existing.outcomeId)
        }
        val outbox = findEventOutbox(EVENT_KIND_ANSWER_REVEAL, existing.outcomeId)
            ?: throw ImmutablePayloadConflictException("answer_reveal_outcome", existing.outcomeId)
        if (outbox != existing.toOutbox()) {
            throw ImmutablePayloadConflictException("answer_reveal_outcome", existing.outcomeId)
        }
        return AnswerRevealTransactionResult(
            created = false,
            assessmentEventId = event.assessmentEventId,
            outcome = outcome,
            canonicalFingerprint = existing.canonicalFingerprint,
            outbox = outbox,
        )
    }

    private suspend fun readAttemptReplay(
        existingSubmission: AttemptSubmissionEntity,
        command: AttemptWriteCommand,
    ): AttemptTransactionResult {
        val existingEvent = findAttemptBySubmission(command.submissionId)
            ?: throw AttemptIdempotencyConflictException(command.submissionId)
        val existingAttempt = readAttemptModel(existingEvent)
        if (existingAttempt.submittedResponse != command.submittedResponse) {
            throw AttemptIdempotencyConflictException(command.submissionId)
        }
        val authoritativeCommand = canonicalizeAttemptForReplay(command, existingAttempt)
        val expected = DatabaseContractValidator.constructAttempt(
            command = authoritativeCommand,
            assessmentSnapshot = existingAttempt.assessmentSnapshot,
            responseOrdinal = existingAttempt.responseOrdinal,
            eventSequence = existingEvent.eventSequence,
        )
        if (existingSubmission.learnerId != command.learnerId ||
            existingAttempt.assessmentSnapshot.snapshotId != command.assessmentSnapshotId ||
            existingAttempt != expected ||
            existingSubmission.payloadFingerprint != existingEvent.canonicalFingerprint ||
            LearningLedgerFingerprint.attempt(existingAttempt) != existingEvent.canonicalFingerprint
        ) {
            throw AttemptIdempotencyConflictException(command.submissionId)
        }
        val outbox = findEventOutbox(EVENT_KIND_ATTEMPT, existingEvent.attemptId)
            ?: throw AttemptIdempotencyConflictException(command.submissionId)
        if (outbox != existingEvent.toOutbox()) {
            throw AttemptIdempotencyConflictException(command.submissionId)
        }
        return AttemptTransactionResult(
            created = false,
            submissionId = command.submissionId,
            attempt = existingAttempt,
            canonicalFingerprint = existingEvent.canonicalFingerprint,
            outbox = outbox,
        )
    }

    private suspend fun canonicalizeAttemptForReplay(
        command: AttemptWriteCommand,
        existingAttempt: Attempt,
    ): AttemptWriteCommand {
        val reveal = findAnswerRevealOutcomeForPresentation(
            learnerId = command.learnerId,
            presentationId = existingAttempt.presentationId,
        ) ?: return command
        return if (reveal.eventSequence < existingAttempt.eventSequence) {
            command.canonicalizedAfterReveal()
        } else {
            command
        }
    }

    private suspend fun readCorrectionReplay(
        existing: AttemptCorrectionEntity,
        command: AttemptCorrectionRecord,
    ): CorrectionTransactionResult {
        val expected = DatabaseContractValidator.constructCorrection(command, existing.eventSequence)
        val persisted = existing.toModel()
        if (existing.learnerId != command.learnerId ||
            existing.submissionId != command.submissionId ||
            persisted != expected ||
            LearningLedgerFingerprint.correction(persisted) != existing.canonicalFingerprint
        ) {
            throw ImmutablePayloadConflictException("attempt_correction", command.correctionId)
        }
        val target = findAttempt(command.attemptId)
            ?: throw ImmutablePayloadConflictException("correction_target", command.attemptId)
        if (target.learnerId != command.learnerId || target.submissionId != command.submissionId) {
            throw ImmutablePayloadConflictException("correction_target", command.attemptId)
        }
        DatabaseContractValidator.validateCorrectionAgainstAttempt(command, readAttemptModel(target))
        val outbox = findEventOutbox(EVENT_KIND_CORRECTION, existing.correctionId)
            ?: throw ImmutablePayloadConflictException("attempt_correction", command.correctionId)
        if (outbox != existing.toOutbox()) {
            throw ImmutablePayloadConflictException("attempt_correction", command.correctionId)
        }
        return CorrectionTransactionResult(
            created = false,
            submissionId = command.submissionId,
            correction = persisted,
            canonicalFingerprint = existing.canonicalFingerprint,
            outbox = outbox,
        )
    }

    private suspend fun saveEvidenceSnapshot(snapshot: AssessmentEvidenceSnapshot): Boolean {
        val entity = snapshot.toEntity()
        val attributions = snapshot.toAttributionEntities()
        val inserted = insertEvidenceSnapshot(entity) != -1L
        if (!inserted) {
            val existing = findEvidenceSnapshot(snapshot.snapshotId)
            if (existing != entity || findAttributions(snapshot.snapshotId) != attributions) {
                throw ImmutablePayloadConflictException("assessment_evidence_snapshot", snapshot.snapshotId)
            }
            return false
        }
        attributions.forEach { attribution ->
            val binding = findBinding(attribution.bindingId)
            if (binding == null ||
                binding.practiceUnitId != attribution.practiceUnitId ||
                binding.knowledgeNodeId != attribution.knowledgeNodeId ||
                binding.basisRevisionId != attribution.basisRevisionId ||
                binding.taxonomyVersion != attribution.taxonomyVersion
            ) {
                throw ImmutablePayloadConflictException("knowledge_binding", attribution.bindingId)
            }
        }
        insertAttributions(attributions)
        return true
    }

    private suspend fun readEvidenceSnapshotModel(snapshotId: String): AssessmentEvidenceSnapshot {
        val snapshot = findEvidenceSnapshot(snapshotId)
            ?: throw ImmutablePayloadConflictException("assessment_evidence_snapshot", snapshotId)
        return snapshot.toModel(findAttributions(snapshotId))
    }

    private suspend fun loadOrCreatePresentation(
        learnerId: String,
        presentationId: String,
        assessmentSnapshotId: String,
        occurredAtEpochMillis: Long,
    ): AssessmentPresentationEntity {
        val requested = AssessmentPresentationEntity(
            learnerId = learnerId,
            presentationId = presentationId,
            assessmentSnapshotId = assessmentSnapshotId,
            lastResponseOrdinal = 0,
            terminal = false,
            stateVersion = 0,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )
        if (insertPresentation(requested) != -1L) return requested
        val existing = findPresentation(learnerId, presentationId)
            ?: throw ImmutablePayloadConflictException("assessment_presentation", presentationId)
        if (existing.assessmentSnapshotId != assessmentSnapshotId) {
            throw ImmutablePayloadConflictException("assessment_presentation", presentationId)
        }
        return existing
    }

    private suspend fun canonicalizeAttemptForPresentation(
        command: AttemptWriteCommand,
        presentation: AssessmentPresentationEntity,
    ): AttemptWriteCommand {
        if (!presentation.terminal) return command
        val revealEvent = findAnswerRevealEventForPresentation(
            learnerId = command.learnerId,
            presentationId = command.presentationId,
        ) ?: throw ImmutablePayloadConflictException(
            "terminal_presentation_reveal",
            command.presentationId,
        )
        val outcomeId = DatabaseContractValidator.answerRevealOutcomeId(revealEvent.assessmentEventId)
        val revealOutcome = findAnswerRevealOutcome(outcomeId)?.toModel(
            readEvidenceSnapshotModel(revealEvent.assessmentSnapshotId),
        ) ?: materializeAnswerReveal(
            event = revealEvent,
            allowAlreadyTerminal = true,
        ).outcome
        check(revealOutcome.eventSequence > 0) {
            "A terminal presentation must have an authoritative reveal sequence"
        }

        return command.canonicalizedAfterReveal()
    }

    private suspend fun canonicalizeCorrectionForPresentation(
        command: AttemptCorrectionRecord,
        target: Attempt,
    ): AttemptCorrectionRecord {
        val reveal = findAnswerRevealOutcomeForPresentation(
            learnerId = command.learnerId,
            presentationId = target.presentationId,
        ) ?: return command
        if (reveal.eventSequence >= target.eventSequence) return command

        return when (command.replacementEvidence.direction) {
            LearningEvidenceDirection.POSITIVE,
            LearningEvidenceDirection.NONE,
            -> command.copy(
                replacementEvidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NONE,
                    weight = 0.0,
                    reason = LearningEvidenceReason.ANSWER_REVEALED,
                ),
                replacementMemoryOutcome = ProblemMemoryOutcome.ANSWER_REVEALED,
            )

            LearningEvidenceDirection.NEGATIVE -> command.copy(
                replacementEvidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = 0.6,
                    reason = LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
                ),
                replacementMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )
        }
    }

    private fun AttemptWriteCommand.canonicalizedAfterReveal(): AttemptWriteCommand =
        when (evidence.direction) {
            LearningEvidenceDirection.POSITIVE,
            LearningEvidenceDirection.NONE,
            -> copy(
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NONE,
                    weight = 0.0,
                    reason = LearningEvidenceReason.ANSWER_REVEALED,
                ),
                problemMemoryOutcome = ProblemMemoryOutcome.ANSWER_REVEALED,
            )

            LearningEvidenceDirection.NEGATIVE -> copy(
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = 0.6,
                    reason = LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
                ),
                problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            )
        }

    private suspend fun readAttemptModel(entity: AttemptEventEntity): Attempt {
        val snapshot = findEvidenceSnapshot(entity.assessmentSnapshotId)
            ?: throw ImmutablePayloadConflictException(
                "assessment_evidence_snapshot",
                entity.assessmentSnapshotId,
            )
        return entity.toModel(snapshot.toModel(findAttributions(snapshot.snapshotId)))
    }

    private suspend fun allocateSequence(learnerId: String): Long {
        initializeSequence(LearningSequenceEntity(learnerId, 0))
        val current = checkNotNull(lastAllocatedSequence(learnerId))
        check(current < Long.MAX_VALUE) { "Learning sequence exhausted for $learnerId" }
        val next = current + 1
        check(compareAndSetSequence(learnerId, current, next) == 1) {
            "Learning sequence CAS failed inside a serialized Room transaction"
        }
        return next
    }
}

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

    @Query(
        "SELECT * FROM attributed_learning_observation_event WHERE event_id = :eventId LIMIT 1",
    )
    protected abstract suspend fun findProjectionLearningObservation(
        eventId: String,
    ): AttributedLearningObservationEventEntity?

    @Query(
        """
        SELECT * FROM learning_observation_event_attribution
        WHERE event_id = :eventId
        ORDER BY ordinal ASC
        """,
    )
    protected abstract suspend fun findProjectionLearningObservationAttributions(
        eventId: String,
    ): List<LearningObservationEventAttributionEntity>

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
        SELECT * FROM applied_learning_observation_record
        WHERE projection_name = :projectionName AND learner_id = :learnerId
        ORDER BY event_sequence ASC
        """,
    )
    protected abstract suspend fun findAppliedLearningObservations(
        projectionName: String,
        learnerId: String,
    ): List<AppliedLearningObservationRecordEntity>

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

    @Query(
        "DELETE FROM applied_learning_observation_record WHERE projection_name = :projectionName AND learner_id = :learnerId",
    )
    protected abstract suspend fun deleteAppliedLearningObservations(
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
    protected abstract suspend fun insertAppliedLearningObservations(
        records: List<AppliedLearningObservationRecordEntity>,
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
                EVENT_KIND_LEARNING_OBSERVATION -> readLearningObservation(row)?.let { event ->
                    PersistedIncrementalLearningEvent(
                        event = event,
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
                EVENT_KIND_LEARNING_OBSERVATION -> readLearningObservation(row)
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
            appliedLearningObservations = findAppliedLearningObservations(projectionName, learnerId),
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
                    && it.eventKind != EVENT_KIND_LEARNING_OBSERVATION
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
        deleteAppliedLearningObservations(commit.projectionName, commit.learnerId)
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
        storedSnapshot.toAppliedLearningObservationEntities(commit.projectionName)
            .insertWhenNotEmpty(::insertAppliedLearningObservations)
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
                EVENT_KIND_LEARNING_OBSERVATION -> null
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
                        EVENT_KIND_LEARNING_OBSERVATION -> null
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
        snapshot.appliedLearningObservationRecords.values.forEach { applied ->
            val observation = findProjectionLearningObservation(applied.observationEventId)
                ?: throw ProjectionCasConflictException(
                    "Applied learning observation ${applied.observationEventId} is not in the immutable ledger",
                )
            if (observation.learnerId != learnerId ||
                observation.eventSequence != applied.eventSequence ||
                observation.canonicalFingerprint != applied.canonicalFingerprint ||
                readLearningObservation(observation.toOutbox()) == null
            ) {
                throw ProjectionCasConflictException(
                    "Applied learning observation ${applied.observationEventId} differs from the immutable ledger",
                )
            }
        }
    }

    private suspend fun ProjectionOutboxEntity.hasValidCanonicalEvent(): Boolean = when (eventKind) {
        EVENT_KIND_ATTEMPT -> readAttempt(this) != null
        EVENT_KIND_ANSWER_REVEAL -> readAnswerReveal(this) != null
        EVENT_KIND_TUTOR_ANSWER_EXPOSURE -> readTutorAnswerExposure(this) != null
        EVENT_KIND_LEARNING_OBSERVATION -> readLearningObservation(this) != null
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

    private suspend fun readLearningObservation(
        row: ProjectionOutboxEntity,
    ): AttributedLearningObservationEvent? {
        val entity = findProjectionLearningObservation(row.eventId) ?: return null
        if (entity.learnerId != row.learnerId ||
            entity.eventSequence != row.outboxSequence ||
            entity.canonicalFingerprint != row.canonicalFingerprint
        ) return null
        val event = runCatching {
            entity.toModel(findProjectionLearningObservationAttributions(entity.eventId))
        }.getOrNull() ?: return null
        if (LearningLedgerFingerprint.learningObservation(event) != row.canonicalFingerprint) {
            return null
        }
        return event
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
                is AttributedLearningObservationEvent -> null
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

private fun AssessmentEvidenceSnapshot.toEntity() = AssessmentEvidenceSnapshotEntity(
    snapshotId = snapshotId,
    assessmentItemId = assessmentItemId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    answerSpecId = answerSpecId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    taxonomyVersion = taxonomyVersion,
    verification = verification.name,
    calibrationSupport = calibration.support.name,
    calibrationSourceId = calibration.sourceId,
    calibrationVersion = calibration.version,
    calibrationValidFromEpochMillis = calibration.validFromEpochMillis,
    calibrationValidUntilEpochMillis = calibration.validUntilEpochMillis,
    capturedAtEpochMillis = capturedAtEpochMillis,
)

private fun AssessmentEvidenceSnapshot.toAttributionEntities() = attributions
    .sortedBy(KnowledgeEvidenceAttribution::bindingId)
    .map { attribution ->
        AssessmentEvidenceAttributionEntity(
            snapshotId = snapshotId,
            bindingId = attribution.bindingId,
            practiceUnitId = practiceUnitId,
            knowledgeNodeId = attribution.knowledgeNodeId,
            weight = attribution.weight,
            basisRevisionId = attribution.basisRevisionId,
            taxonomyVersion = attribution.taxonomyVersion,
            role = attribution.role.name,
            certainty = attribution.certainty.name,
        )
    }

private fun AssessmentEvidenceSnapshotEntity.toModel(
    attributions: List<AssessmentEvidenceAttributionEntity>,
) = AssessmentEvidenceSnapshot(
    snapshotId = snapshotId,
    assessmentItemId = assessmentItemId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    answerSpecId = answerSpecId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    taxonomyVersion = taxonomyVersion,
    verification = AssessmentSnapshotVerification.valueOf(verification),
    calibration = CalibrationSnapshot(
        support = CalibrationSupport.valueOf(calibrationSupport),
        sourceId = calibrationSourceId,
        version = calibrationVersion,
        validFromEpochMillis = calibrationValidFromEpochMillis,
        validUntilEpochMillis = calibrationValidUntilEpochMillis,
    ),
    attributions = attributions.map { attribution ->
        KnowledgeEvidenceAttribution(
            bindingId = attribution.bindingId,
            knowledgeNodeId = attribution.knowledgeNodeId,
            weight = attribution.weight,
            basisRevisionId = attribution.basisRevisionId,
            taxonomyVersion = attribution.taxonomyVersion,
            role = EvidenceAttributionRole.valueOf(attribution.role),
            certainty = EvidenceAttributionCertainty.valueOf(attribution.certainty),
        )
    },
    capturedAtEpochMillis = capturedAtEpochMillis,
)

private fun Attempt.toEntity(
    learnerId: String,
    submissionId: String,
    canonicalFingerprint: String,
): AttemptEventEntity {
    val response = submittedResponse as? AttemptSubmittedResponse.Choice
    return AttemptEventEntity(
        attemptId = attemptId,
        learnerId = learnerId,
        submissionId = submissionId,
        eventSequence = eventSequence,
        canonicalFingerprint = canonicalFingerprint,
        presentationId = presentationId,
        responseOrdinal = responseOrdinal,
        assessmentSnapshotId = assessmentSnapshot.snapshotId,
        submittedChoiceId = response?.choiceId,
        submittedChoiceMarkdown = response?.choiceMarkdown,
        responseSubmittedAtEpochMillis = response?.submittedAtEpochMillis,
        evidenceDirection = evidence.direction.name,
        evidenceWeight = evidence.weight,
        evidenceReason = evidence.reason.name,
        problemMemoryOutcome = problemMemoryOutcome.name,
        occurredAtEpochMillis = occurredAtEpochMillis,
        durationSeconds = durationSeconds,
        studyDayEpochDay = studyDay.epochDay,
        studyDayTimeZoneId = studyDay.timeZoneId,
        studyDayUtcOffsetMinutes = studyDay.utcOffsetMinutes,
    )
}

private fun AttemptEventEntity.toModel(snapshot: AssessmentEvidenceSnapshot): Attempt {
    val choiceId = submittedChoiceId
    val choiceMarkdown = submittedChoiceMarkdown
    val submittedAtEpochMillis = responseSubmittedAtEpochMillis
    val submittedResponse = when {
        choiceId == null &&
            choiceMarkdown == null &&
            submittedAtEpochMillis == null -> AttemptSubmittedResponse.LegacyUnavailable

        choiceId != null &&
            choiceMarkdown != null &&
            submittedAtEpochMillis != null -> AttemptSubmittedResponse.Choice(
            choiceId = choiceId,
            choiceMarkdown = choiceMarkdown,
            submittedAtEpochMillis = submittedAtEpochMillis,
        )

        else -> throw LearningLedgerIntegrityException(
            "Attempt $attemptId has a partially persisted submitted response",
        )
    }
    return Attempt(
        attemptId = attemptId,
        presentationId = presentationId,
        responseOrdinal = responseOrdinal,
        assessmentSnapshot = snapshot,
        evidence = LearningEvidence(
            direction = LearningEvidenceDirection.valueOf(evidenceDirection),
            weight = evidenceWeight,
            reason = LearningEvidenceReason.valueOf(evidenceReason),
        ),
        problemMemoryOutcome = ProblemMemoryOutcome.valueOf(problemMemoryOutcome),
        occurredAtEpochMillis = occurredAtEpochMillis,
        durationSeconds = durationSeconds,
        studyDay = StudyDayContext(
            epochDay = studyDayEpochDay,
            timeZoneId = studyDayTimeZoneId,
            utcOffsetMinutes = studyDayUtcOffsetMinutes,
        ),
        eventSequence = eventSequence,
        submittedResponse = submittedResponse,
    )
}

private fun AttemptCorrection.toEntity(
    learnerId: String,
    submissionId: String,
    canonicalFingerprint: String,
) = AttemptCorrectionEntity(
    correctionId = correctionId,
    learnerId = learnerId,
    submissionId = submissionId,
    attemptId = attemptId,
    eventSequence = eventSequence,
    canonicalFingerprint = canonicalFingerprint,
    replacementEvidenceDirection = replacementEvidence.direction.name,
    replacementEvidenceWeight = replacementEvidence.weight,
    replacementEvidenceReason = replacementEvidence.reason.name,
    replacementMemoryOutcome = replacementMemoryOutcome.name,
    reasonMarkdown = reasonMarkdown,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun AttemptCorrectionEntity.toModel() = AttemptCorrection(
    correctionId = correctionId,
    attemptId = attemptId,
    replacementEvidence = LearningEvidence(
        direction = LearningEvidenceDirection.valueOf(replacementEvidenceDirection),
        weight = replacementEvidenceWeight,
        reason = LearningEvidenceReason.valueOf(replacementEvidenceReason),
    ),
    replacementMemoryOutcome = ProblemMemoryOutcome.valueOf(replacementMemoryOutcome),
    reasonMarkdown = reasonMarkdown,
    occurredAtEpochMillis = occurredAtEpochMillis,
    eventSequence = eventSequence,
)

private fun AnswerRevealWriteCommand.toAnswerRevealEventEntity() =
    AssessmentAnswerRevealEventEntity(
        assessmentEventId = assessmentEventId,
        learnerId = learnerId,
        outcomeId = DatabaseContractValidator.answerRevealOutcomeId(assessmentEventId),
        presentationId = presentationId,
        assessmentSnapshotId = assessmentSnapshotId,
        contentMarkdown = contentMarkdown,
        occurredAtEpochMillis = occurredAtEpochMillis,
        studyDayEpochDay = studyDay.epochDay,
        studyDayTimeZoneId = studyDay.timeZoneId,
        studyDayUtcOffsetMinutes = studyDay.utcOffsetMinutes,
    )

private fun AssessmentAnswerRevealEventEntity.toModel(
    snapshot: AssessmentEvidenceSnapshot,
    eventSequence: Long,
) = AnswerRevealOutcome(
    outcomeId = outcomeId,
    presentationId = presentationId,
    assessmentSnapshot = snapshot,
    occurredAtEpochMillis = occurredAtEpochMillis,
    studyDay = StudyDayContext(
        epochDay = studyDayEpochDay,
        timeZoneId = studyDayTimeZoneId,
        utcOffsetMinutes = studyDayUtcOffsetMinutes,
    ),
    eventSequence = eventSequence,
)

private fun AnswerRevealOutcome.toEntity(
    source: AssessmentAnswerRevealEventEntity,
    canonicalFingerprint: String,
) = AnswerRevealOutcomeEntity(
    outcomeId = outcomeId,
    learnerId = source.learnerId,
    assessmentEventId = source.assessmentEventId,
    presentationId = presentationId,
    assessmentSnapshotId = assessmentSnapshot.snapshotId,
    eventSequence = eventSequence,
    canonicalFingerprint = canonicalFingerprint,
    occurredAtEpochMillis = occurredAtEpochMillis,
    studyDayEpochDay = studyDay.epochDay,
    studyDayTimeZoneId = studyDay.timeZoneId,
    studyDayUtcOffsetMinutes = studyDay.utcOffsetMinutes,
)

private fun AnswerRevealOutcomeEntity.toModel(snapshot: AssessmentEvidenceSnapshot) =
    AnswerRevealOutcome(
        outcomeId = outcomeId,
        presentationId = presentationId,
        assessmentSnapshot = snapshot,
        occurredAtEpochMillis = occurredAtEpochMillis,
        studyDay = StudyDayContext(
            epochDay = studyDayEpochDay,
            timeZoneId = studyDayTimeZoneId,
            utcOffsetMinutes = studyDayUtcOffsetMinutes,
        ),
        eventSequence = eventSequence,
    )

private fun AttemptEventEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_ATTEMPT:$attemptId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_ATTEMPT,
    eventId = attemptId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = occurredAtEpochMillis,
)

private fun AttemptCorrectionEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_CORRECTION:$correctionId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_CORRECTION,
    eventId = correctionId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = occurredAtEpochMillis,
)

private fun AnswerRevealOutcomeEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_ANSWER_REVEAL:$outcomeId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_ANSWER_REVEAL,
    eventId = outcomeId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = occurredAtEpochMillis,
)

internal fun ProjectionOutboxEntity.toRecord() = ProjectionOutboxRecord(
    outboxId = outboxId,
    learnerId = learnerId,
    outboxSequence = outboxSequence,
    eventKind = eventKind,
    eventId = eventId,
    canonicalFingerprint = canonicalFingerprint,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun LearnerSnapshot.toEntity(
    projectionName: String,
    stateVersion: Long,
    knownLedgerHeadSequence: Long,
) = LearnerProjectionSnapshotEntity(
    projectionName = projectionName,
    learnerId = learnerId,
    stateVersion = stateVersion,
    checkpointSequence = checkpoint.lastSequence,
    knownLedgerHeadSequence = knownLedgerHeadSequence,
    projectorVersion = checkpoint.projectorVersion,
    projectedAtEpochMillis = checkpoint.projectedAtEpochMillis,
    generatedAtEpochMillis = generatedAtEpochMillis,
    correctionWatermarkEpochMillis = correctionWatermarkEpochMillis,
    freshness = freshness.name,
    projectionStatus = projectionStatus.name,
)

private fun LearnerSnapshot.toMemoryEntities(projectionName: String) = problemMemoryStates.values
    .sortedBy(ProblemMemoryState::practiceUnitId)
    .map { state ->
        LearnerProblemMemoryStateEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            practiceUnitId = state.practiceUnitId,
            stabilityDays = state.stabilityDays,
            difficulty = state.difficulty,
            lastReviewedAtEpochMillis = state.lastReviewedAtEpochMillis,
            nextReviewAtEpochMillis = state.nextReviewAtEpochMillis,
            independentCorrectCount = state.independentCorrectCount,
            assistedCorrectCount = state.assistedCorrectCount,
            lapseCount = state.lapseCount,
            answerRevealCount = state.answerRevealCount,
            lastLapseAtEpochMillis = state.lastLapseAtEpochMillis,
            clockAnomalyCount = state.clockAnomalyCount,
            lastClockAnomalyAtEpochMillis = state.lastClockAnomalyAtEpochMillis,
            lastAttemptId = state.lastAttemptId,
            projectorVersion = state.projectorVersion,
            checkpointSequence = state.checkpointSequence,
        )
    }

private fun LearnerSnapshot.toMasteryEntities(
    projectionName: String,
): Pair<List<LearnerKnowledgeMasteryStateEntity>, List<IndependentCorrectObservationEntity>> {
    val states = mutableListOf<LearnerKnowledgeMasteryStateEntity>()
    val observations = mutableListOf<IndependentCorrectObservationEntity>()
    knowledgeMasteryStates.values.sortedBy(KnowledgeMasteryState::knowledgeNodeId).forEach { state ->
        states += LearnerKnowledgeMasteryStateEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            knowledgeNodeId = state.knowledgeNodeId,
            probabilityIndependentCorrect = state.probabilityIndependentCorrect,
            lowerBoundIndependentCorrect = state.lowerBoundIndependentCorrect,
            evidenceMass = state.evidenceMass,
            lastIndependentErrorAtEpochMillis = state.lastIndependentErrorAtEpochMillis,
            lastIndependentErrorSequence = state.lastIndependentErrorSequence,
            status = state.status.name,
            calibrationSupport = state.calibrationSupport.name,
            projectorVersion = state.projectorVersion,
            checkpointSequence = state.checkpointSequence,
            lastEvidenceAtEpochMillis = state.lastEvidenceAtEpochMillis,
            conflictSinceSequence = state.conflictSinceSequence,
        )
        state.independentCorrectObservations.forEachIndexed { ordinal, observation ->
            observations += IndependentCorrectObservationEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                knowledgeNodeId = state.knowledgeNodeId,
                ordinal = ordinal,
                itemFamilyId = observation.itemFamilyId,
                studyDayEpochDay = observation.studyDayEpochDay,
                isStudyDayTrusted = observation.isStudyDayTrusted,
                occurredAtEpochMillis = observation.occurredAtEpochMillis,
                eventSequence = observation.eventSequence,
                bindingId = observation.bindingId,
                evidenceWeight = observation.evidenceWeight,
                calibrationSupport = observation.calibration.support.name,
                calibrationSourceId = observation.calibration.sourceId,
                calibrationVersion = observation.calibration.version,
                calibrationValidFromEpochMillis = observation.calibration.validFromEpochMillis,
                calibrationValidUntilEpochMillis = observation.calibration.validUntilEpochMillis,
            )
        }
    }
    return states to observations
}

private fun LearnerSnapshot.toAppliedEntities(projectionName: String) = appliedAttemptRecords.values
    .sortedBy(AppliedAttemptRecord::eventSequence)
    .map { record ->
        AppliedAttemptRecordEntity(
            projectionName = projectionName,
            learnerId = learnerId,
            attemptId = record.attemptId,
            canonicalFingerprint = record.canonicalFingerprint,
            eventSequence = record.eventSequence,
            presentationId = record.presentationId,
            responseOrdinal = record.responseOrdinal,
        )
    }

private fun LearnerSnapshot.toAppliedCorrectionEntities(projectionName: String) =
    appliedCorrectionRecords.values
        .sortedBy(AppliedCorrectionRecord::eventSequence)
        .map { record ->
            AppliedCorrectionRecordEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                correctionId = record.correctionId,
                attemptId = record.attemptId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        }

private fun LearnerSnapshot.toAppliedAnswerRevealEntities(projectionName: String) =
    appliedAnswerRevealRecords.values
        .sortedBy(AppliedAnswerRevealRecord::eventSequence)
        .map { record ->
            AppliedAnswerRevealRecordEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                outcomeId = record.outcomeId,
                presentationId = record.presentationId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        }

private fun LearnerSnapshot.toAppliedTutorAnswerExposureEntities(projectionName: String) =
    appliedTutorAnswerExposureRecords.values
        .sortedBy(AppliedTutorAnswerExposureRecord::eventSequence)
        .map { record ->
            AppliedTutorAnswerExposureRecordEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                outcomeId = record.outcomeId,
                exposureId = record.exposureId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        }

private fun LearnerSnapshot.toAppliedLearningObservationEntities(projectionName: String) =
    appliedLearningObservationRecords.values
        .sortedBy(AppliedLearningObservationRecord::eventSequence)
        .map { record ->
            AppliedLearningObservationRecordEntity(
                projectionName = projectionName,
                learnerId = learnerId,
                eventId = record.observationEventId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        }

private fun LearnerProjectionSnapshotEntity.toPersistedSnapshot(
    memoryStates: List<LearnerProblemMemoryStateEntity>,
    masteryStates: List<LearnerKnowledgeMasteryStateEntity>,
    observations: List<IndependentCorrectObservationEntity>,
    appliedAttempts: List<AppliedAttemptRecordEntity>,
    appliedCorrections: List<AppliedCorrectionRecordEntity>,
    appliedAnswerReveals: List<AppliedAnswerRevealRecordEntity>,
    appliedTutorAnswerExposures: List<AppliedTutorAnswerExposureRecordEntity>,
    appliedLearningObservations: List<AppliedLearningObservationRecordEntity>,
): PersistedLearnerSnapshot {
    val observationsByKnowledge = observations.groupBy { it.knowledgeNodeId }
    val snapshot = LearnerSnapshot(
        learnerId = learnerId,
        problemMemoryStates = memoryStates.associate { state ->
            state.practiceUnitId to ProblemMemoryState(
                practiceUnitId = state.practiceUnitId,
                stabilityDays = state.stabilityDays,
                difficulty = state.difficulty,
                lastReviewedAtEpochMillis = state.lastReviewedAtEpochMillis,
                nextReviewAtEpochMillis = state.nextReviewAtEpochMillis,
                independentCorrectCount = state.independentCorrectCount,
                assistedCorrectCount = state.assistedCorrectCount,
                lapseCount = state.lapseCount,
                answerRevealCount = state.answerRevealCount,
                lastLapseAtEpochMillis = state.lastLapseAtEpochMillis,
                clockAnomalyCount = state.clockAnomalyCount,
                lastClockAnomalyAtEpochMillis = state.lastClockAnomalyAtEpochMillis,
                lastAttemptId = state.lastAttemptId,
                projectorVersion = state.projectorVersion,
                checkpointSequence = state.checkpointSequence,
            )
        },
        knowledgeMasteryStates = masteryStates.associate { state ->
            state.knowledgeNodeId to KnowledgeMasteryState(
                knowledgeNodeId = state.knowledgeNodeId,
                probabilityIndependentCorrect = state.probabilityIndependentCorrect,
                lowerBoundIndependentCorrect = state.lowerBoundIndependentCorrect,
                evidenceMass = state.evidenceMass,
                independentCorrectObservations = observationsByKnowledge[state.knowledgeNodeId]
                    .orEmpty()
                    .sortedBy { it.ordinal }
                    .map { observation ->
                        IndependentCorrectObservation(
                            itemFamilyId = observation.itemFamilyId,
                            studyDayEpochDay = observation.studyDayEpochDay,
                            isStudyDayTrusted = observation.isStudyDayTrusted,
                            occurredAtEpochMillis = observation.occurredAtEpochMillis,
                            eventSequence = observation.eventSequence,
                            bindingId = observation.bindingId,
                            evidenceWeight = observation.evidenceWeight,
                            calibration = CalibrationSnapshot(
                                support = CalibrationSupport.valueOf(observation.calibrationSupport),
                                sourceId = observation.calibrationSourceId,
                                version = observation.calibrationVersion,
                                validFromEpochMillis = observation.calibrationValidFromEpochMillis,
                                validUntilEpochMillis = observation.calibrationValidUntilEpochMillis,
                            ),
                        )
                    },
                lastIndependentErrorAtEpochMillis = state.lastIndependentErrorAtEpochMillis,
                lastIndependentErrorSequence = state.lastIndependentErrorSequence,
                status = MasteryStatus.valueOf(state.status),
                calibrationSupport = CalibrationSupport.valueOf(state.calibrationSupport),
                projectorVersion = state.projectorVersion,
                checkpointSequence = state.checkpointSequence,
                lastEvidenceAtEpochMillis = state.lastEvidenceAtEpochMillis,
                conflictSinceSequence = state.conflictSinceSequence,
            )
        },
        checkpoint = ProjectionCheckpoint(
            lastSequence = checkpointSequence,
            projectorVersion = projectorVersion,
            projectedAtEpochMillis = projectedAtEpochMillis,
        ),
        knownLedgerHeadSequence = knownLedgerHeadSequence,
        generatedAtEpochMillis = generatedAtEpochMillis,
        correctionWatermarkEpochMillis = correctionWatermarkEpochMillis,
        freshness = LearnerSnapshotFreshness.valueOf(freshness),
        projectionStatus = ProjectionStatus.valueOf(projectionStatus),
        appliedAttemptRecords = appliedAttempts.associate { record ->
            record.attemptId to AppliedAttemptRecord(
                attemptId = record.attemptId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
                presentationId = record.presentationId,
                responseOrdinal = record.responseOrdinal,
            )
        },
        appliedCorrectionRecords = appliedCorrections.associate { record ->
            record.correctionId to AppliedCorrectionRecord(
                correctionId = record.correctionId,
                attemptId = record.attemptId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        },
        appliedAnswerRevealRecords = appliedAnswerReveals.associate { record ->
            record.outcomeId to AppliedAnswerRevealRecord(
                outcomeId = record.outcomeId,
                presentationId = record.presentationId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        },
        appliedTutorAnswerExposureRecords = appliedTutorAnswerExposures.associate { record ->
            record.outcomeId to AppliedTutorAnswerExposureRecord(
                outcomeId = record.outcomeId,
                exposureId = record.exposureId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        },
        appliedLearningObservationRecords = appliedLearningObservations.associate { record ->
            record.eventId to AppliedLearningObservationRecord(
                observationEventId = record.eventId,
                canonicalFingerprint = record.canonicalFingerprint,
                eventSequence = record.eventSequence,
            )
        },
    )
    return PersistedLearnerSnapshot(
        projectionName = projectionName,
        stateVersion = stateVersion,
        knownLedgerHeadSequence = knownLedgerHeadSequence,
        snapshot = snapshot,
    )
}
