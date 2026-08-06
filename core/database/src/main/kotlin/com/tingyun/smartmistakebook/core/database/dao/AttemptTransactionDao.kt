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
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceFactProofFingerprint
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
import com.tingyun.smartmistakebook.core.model.AdmittedLearningObservationEvent
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
import com.tingyun.smartmistakebook.core.model.LearningObservationLedgerEvent
import com.tingyun.smartmistakebook.core.model.LearningObservationProjectionAdmission
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
import com.tingyun.smartmistakebook.core.database.entity.LearningEventIdentityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAdmissionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactProofEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionConsumptionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.PresentationProjectionStateEntity
import kotlinx.coroutines.flow.Flow

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
    protected abstract suspend fun insertEventIdentity(
        identity: LearningEventIdentityEntity,
    ): Long

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
        claimEventIdentity(command.attemptId, EVENT_KIND_ATTEMPT)
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
        claimEventIdentity(command.correctionId, EVENT_KIND_CORRECTION)
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
        val outcomeId = DatabaseContractValidator.answerRevealOutcomeId(event.assessmentEventId)
        claimEventIdentity(outcomeId, EVENT_KIND_ANSWER_REVEAL)
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

    private suspend fun claimEventIdentity(eventId: String, eventKind: String) {
        claimLearningEventIdentity(
            eventId = eventId,
            eventKind = eventKind,
            insert = ::insertEventIdentity,
        )
    }
}
