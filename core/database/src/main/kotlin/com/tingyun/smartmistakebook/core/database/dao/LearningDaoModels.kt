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


internal const val EVENT_KIND_ATTEMPT = "ATTEMPT"
internal const val EVENT_KIND_CORRECTION = "ATTEMPT_CORRECTION"
internal const val EVENT_KIND_ANSWER_REVEAL = "ANSWER_REVEAL_OUTCOME"
internal const val EVENT_KIND_CHAT_EVIDENCE = "CHAT_EVIDENCE_SUBMITTED"
internal const val SQLITE_PRESENTATION_ID_BATCH_SIZE = 900

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

internal data class PresentationAuthorityTransition(
    val previous: PresentationProjectionStateEntity?,
    val next: PresentationProjectionStateEntity,
)

internal fun PresentationProjectionStateEntity.toModel(asOfLedgerSequence: Long) = PresentationProjectionState(
    presentationId = presentationId,
    asOfLedgerSequence = asOfLedgerSequence,
    memoryProjectionApplied = memoryProjected,
    answerRevealSequence = terminalEventSequence,
)
