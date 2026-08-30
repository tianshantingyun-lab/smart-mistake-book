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
import com.tingyun.smartmistakebook.core.database.entity.ReviewLogEntity
import com.tingyun.smartmistakebook.core.database.entity.LlmTeachingAdvisoryEntity
import kotlinx.coroutines.flow.Flow


/** Projection row for one collected review-log sample (spec 3.1). */
data class ReviewLogSampleProjection(
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "reviewed_at_utc")
    val reviewedAtUtc: Long,
    val rating: Int,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "time_bucket")
    val timeBucket: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "evidence_weight")
    val evidenceWeight: Double,
    @ColumnInfo(name = "scroll_up_count")
    val scrollUpCount: Int,
    @ColumnInfo(name = "edit_count")
    val editCount: Int,
    @ColumnInfo(name = "interruption_count")
    val interruptionCount: Int,
    @ColumnInfo(name = "away_millis")
    val awayMillis: Long,
    @ColumnInfo(name = "planned_reason")
    val plannedReason: String?,
    @ColumnInfo(name = "delta_t_days")
    val deltaTDays: Double?,
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewLog(entries: List<ReviewLogEntity>)

    /** Idempotent review-log collection (UNIQUE learner+source). */
    open suspend fun recordReviewLog(entries: List<ReviewLogEntity>) {
        insertReviewLog(entries)
    }

    @Query(
        "SELECT card_id AS practice_unit_id, reviewed_at_utc, rating, duration_ms, time_bucket, " +
            "source_kind, evidence_weight, scroll_up_count, edit_count, interruption_count, " +
            "away_millis, planned_reason, delta_t_days FROM review_log " +
            "WHERE learner_id = :learnerId " +
            "ORDER BY reviewed_at_utc ASC, review_log_id ASC LIMIT :limit",
    )
    abstract suspend fun readReviewLogSamples(learnerId: String, limit: Int): List<ReviewLogSampleProjection>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTeachingAdvisories(entries: List<LlmTeachingAdvisoryEntity>)

    /** Idempotent advisory collection (UNIQUE learner+source+kind). */
    open suspend fun recordTeachingAdvisories(entries: List<LlmTeachingAdvisoryEntity>) {
        insertTeachingAdvisories(entries)
    }

    @Query(
        "SELECT * FROM llm_teaching_advisory WHERE learner_id = :learnerId " +
            "AND (:practiceUnitId IS NULL OR practice_unit_id = :practiceUnitId) " +
            "ORDER BY created_at_epoch_millis DESC LIMIT 50",
    )
    abstract fun observeTeachingAdvisories(
        learnerId: String,
        practiceUnitId: String?,
    ): Flow<List<LlmTeachingAdvisoryEntity>>

    @Query(
        "SELECT MAX(reviewed_at_utc) FROM review_log " +
            "WHERE learner_id = :learnerId AND card_id = :practiceUnitId " +
            "AND source_kind = :sourceKind AND scheduling_eligible = 1",
    )
    abstract suspend fun readLastReviewLogAt(
        learnerId: String,
        practiceUnitId: String,
        sourceKind: String,
    ): Long?

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

