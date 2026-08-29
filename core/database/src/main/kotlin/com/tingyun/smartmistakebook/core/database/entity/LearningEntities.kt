package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Immutable presentation content. This is intentionally separate from accepted learning evidence. */
@Entity(
    tableName = "assessment_item_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["practice_unit_id"]),
        Index(value = ["problem_revision_id"]),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["tutor_content_snapshot_id", "item_revision"], unique = true),
    ],
)
internal data class AssessmentItemSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "assessment_item_snapshot_id")
    val assessmentItemSnapshotId: String,
    @ColumnInfo(name = "item_revision")
    val itemRevision: Int,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String?,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String?,
    @ColumnInfo(name = "tutor_content_snapshot_id")
    val tutorContentSnapshotId: String?,
    @ColumnInfo(name = "prompt_markdown")
    val promptMarkdown: String,
    @ColumnInfo(name = "options_snapshot")
    val optionsSnapshot: String,
    @ColumnInfo(name = "answer_spec_snapshot")
    val answerSpecSnapshot: String,
    @ColumnInfo(name = "verification_status")
    val verificationStatus: String,
    @ColumnInfo(name = "assessment_eligibility")
    val assessmentEligibility: String,
    @ColumnInfo(name = "scoring_mode")
    val scoringMode: String,
    @ColumnInfo(name = "learner_snapshot_version")
    val learnerSnapshotVersion: String,
    @ColumnInfo(name = "projection_checkpoint")
    val projectionCheckpoint: Long,
    @ColumnInfo(name = "hint_level_at_presentation")
    val hintLevelAtPresentation: Int,
    @ColumnInfo(name = "answer_reveal_state")
    val answerRevealState: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "assessment_event",
    foreignKeys = [
        ForeignKey(
            entity = AssessmentItemSnapshotEntity::class,
            parentColumns = ["assessment_item_snapshot_id"],
            childColumns = ["assessment_item_snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["assessment_item_snapshot_id"]),
        Index(value = ["assessment_item_snapshot_id", "event_sequence"], unique = true),
    ],
)
internal data class AssessmentEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "assessment_event_id")
    val assessmentEventId: String,
    @ColumnInfo(name = "assessment_item_snapshot_id")
    val assessmentItemSnapshotId: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "hint_level")
    val hintLevel: Int?,
    @ColumnInfo(name = "submitted_response")
    val submittedResponse: String?,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
)

/** Immutable, verified evidence snapshot accepted by learning-core-v2. */
@Entity(
    tableName = "assessment_evidence_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["assessment_item_id"]),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(
            value = [
                "snapshot_id",
                "practice_unit_id",
                "problem_revision_id",
                "taxonomy_version",
            ],
            unique = true,
        ),
    ],
)
internal data class AssessmentEvidenceSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    @ColumnInfo(name = "assessment_item_id")
    val assessmentItemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "answer_spec_id")
    val answerSpecId: String,
    @ColumnInfo(name = "item_family_id")
    val itemFamilyId: String,
    @ColumnInfo(name = "source_bundle_id")
    val sourceBundleId: String?,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val verification: String,
    @ColumnInfo(name = "calibration_support")
    val calibrationSupport: String,
    @ColumnInfo(name = "calibration_source_id")
    val calibrationSourceId: String,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String,
    @ColumnInfo(name = "calibration_valid_from_epoch_millis")
    val calibrationValidFromEpochMillis: Long,
    @ColumnInfo(name = "calibration_valid_until_epoch_millis")
    val calibrationValidUntilEpochMillis: Long,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long,
)

@Entity(
    tableName = "assessment_evidence_attribution",
    primaryKeys = ["snapshot_id", "binding_id"],
    foreignKeys = [
        ForeignKey(
            entity = AssessmentEvidenceSnapshotEntity::class,
            parentColumns = [
                "snapshot_id",
                "practice_unit_id",
                "problem_revision_id",
                "taxonomy_version",
            ],
            childColumns = [
                "snapshot_id",
                "practice_unit_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PracticeUnitKnowledgeBindingEntity::class,
            parentColumns = [
                "binding_id",
                "practice_unit_id",
                "knowledge_node_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
            childColumns = [
                "binding_id",
                "practice_unit_id",
                "knowledge_node_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["snapshot_id"]),
        Index(value = ["knowledge_node_id"]),
        Index(
            value = [
                "snapshot_id",
                "practice_unit_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
        ),
        Index(
            value = [
                "binding_id",
                "practice_unit_id",
                "knowledge_node_id",
                "basis_revision_id",
                "taxonomy_version",
            ],
        ),
    ],
)
internal data class AssessmentEvidenceAttributionEntity(
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    @ColumnInfo(name = "binding_id")
    val bindingId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    val weight: Double,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    val role: String,
    val certainty: String,
)

@Entity(
    tableName = "attempt_submission",
    indices = [
        Index(value = ["learner_id"]),
        Index(value = ["payload_fingerprint"]),
    ],
)
internal data class AttemptSubmissionEntity(
    @PrimaryKey
    @ColumnInfo(name = "submission_id")
    val submissionId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
)

@Entity(
    tableName = "assessment_presentation",
    primaryKeys = ["learner_id", "presentation_id"],
    foreignKeys = [
        ForeignKey(
            entity = AssessmentEvidenceSnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["assessment_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["assessment_snapshot_id"]),
        Index(value = ["learner_id", "terminal"]),
    ],
)
internal data class AssessmentPresentationEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "assessment_snapshot_id")
    val assessmentSnapshotId: String,
    @ColumnInfo(name = "last_response_ordinal")
    val lastResponseOrdinal: Int,
    val terminal: Boolean,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "attempt_event",
    foreignKeys = [
        ForeignKey(
            entity = AttemptSubmissionEntity::class,
            parentColumns = ["submission_id"],
            childColumns = ["submission_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AssessmentEvidenceSnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["assessment_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["submission_id"], unique = true),
        Index(value = ["assessment_snapshot_id"]),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "attempt_id"], unique = true),
        Index(value = ["learner_id", "submission_id", "attempt_id"], unique = true),
        Index(value = ["learner_id", "presentation_id", "response_ordinal"], unique = true),
    ],
)
internal data class AttemptEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "submission_id")
    val submissionId: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "response_ordinal")
    val responseOrdinal: Int,
    @ColumnInfo(name = "assessment_snapshot_id")
    val assessmentSnapshotId: String,
    @ColumnInfo(name = "submitted_choice_id")
    val submittedChoiceId: String?,
    @ColumnInfo(name = "submitted_choice_markdown")
    val submittedChoiceMarkdown: String?,
    @ColumnInfo(name = "response_submitted_at_epoch_millis")
    val responseSubmittedAtEpochMillis: Long?,
    @ColumnInfo(name = "evidence_direction")
    val evidenceDirection: String,
    @ColumnInfo(name = "evidence_weight")
    val evidenceWeight: Double,
    @ColumnInfo(name = "evidence_reason")
    val evidenceReason: String,
    @ColumnInfo(name = "problem_memory_outcome")
    val problemMemoryOutcome: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int,
    @ColumnInfo(name = "study_day_epoch_day")
    val studyDayEpochDay: Long,
    @ColumnInfo(name = "study_day_time_zone_id")
    val studyDayTimeZoneId: String,
    @ColumnInfo(name = "study_day_utc_offset_minutes")
    val studyDayUtcOffsetMinutes: Int,
    /** Assistance level recorded before the response (spec 3.2, wiring A3). */
    @ColumnInfo(name = "hint_count", defaultValue = "0")
    val hintCount: Int = 0,
    @ColumnInfo(name = "revealed_before_answer", defaultValue = "0")
    val revealedBeforeAnswer: Int = 0,
    /** Error-type channel columns (spec 7); populated from stage C onward. */
    @ColumnInfo(name = "error_type")
    val errorType: String? = null,
    @ColumnInfo(name = "error_type_confidence")
    val errorTypeConfidence: Double? = null,
    @ColumnInfo(name = "low_confidence_correct", defaultValue = "0")
    val lowConfidenceCorrect: Int = 0,
)

@Entity(
    tableName = "attempt_correction",
    foreignKeys = [
        ForeignKey(
            entity = AttemptEventEntity::class,
            parentColumns = ["learner_id", "submission_id", "attempt_id"],
            childColumns = ["learner_id", "submission_id", "attempt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["attempt_id"]),
        Index(value = ["learner_id", "submission_id", "attempt_id"]),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "correction_id"], unique = true),
    ],
)
internal data class AttemptCorrectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "correction_id")
    val correctionId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "submission_id")
    val submissionId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "replacement_evidence_direction")
    val replacementEvidenceDirection: String,
    @ColumnInfo(name = "replacement_evidence_weight")
    val replacementEvidenceWeight: Double,
    @ColumnInfo(name = "replacement_evidence_reason")
    val replacementEvidenceReason: String,
    @ColumnInfo(name = "replacement_memory_outcome")
    val replacementMemoryOutcome: String,
    @ColumnInfo(name = "reason_markdown")
    val reasonMarkdown: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
)

@Entity(
    tableName = "assessment_answer_reveal_event",
    foreignKeys = [
        ForeignKey(
            entity = AssessmentEvidenceSnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["assessment_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["outcome_id"], unique = true),
        Index(value = ["assessment_snapshot_id"]),
        Index(value = ["learner_id", "presentation_id"], unique = true),
    ],
)
internal data class AssessmentAnswerRevealEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "assessment_event_id")
    val assessmentEventId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "outcome_id")
    val outcomeId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "assessment_snapshot_id")
    val assessmentSnapshotId: String,
    @ColumnInfo(name = "content_markdown")
    val contentMarkdown: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "study_day_epoch_day")
    val studyDayEpochDay: Long,
    @ColumnInfo(name = "study_day_time_zone_id")
    val studyDayTimeZoneId: String,
    @ColumnInfo(name = "study_day_utc_offset_minutes")
    val studyDayUtcOffsetMinutes: Int,
)

@Entity(
    tableName = "answer_reveal_outcome",
    foreignKeys = [
        ForeignKey(
            entity = AssessmentAnswerRevealEventEntity::class,
            parentColumns = ["assessment_event_id"],
            childColumns = ["assessment_event_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AssessmentEvidenceSnapshotEntity::class,
            parentColumns = ["snapshot_id"],
            childColumns = ["assessment_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["assessment_event_id"], unique = true),
        Index(value = ["assessment_snapshot_id"]),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "outcome_id"], unique = true),
        Index(value = ["learner_id", "presentation_id"], unique = true),
    ],
)
internal data class AnswerRevealOutcomeEntity(
    @PrimaryKey
    @ColumnInfo(name = "outcome_id")
    val outcomeId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "assessment_event_id")
    val assessmentEventId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "assessment_snapshot_id")
    val assessmentSnapshotId: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "study_day_epoch_day")
    val studyDayEpochDay: Long,
    @ColumnInfo(name = "study_day_time_zone_id")
    val studyDayTimeZoneId: String,
    @ColumnInfo(name = "study_day_utc_offset_minutes")
    val studyDayUtcOffsetMinutes: Int,
)

@Entity(tableName = "learning_sequence")
internal data class LearningSequenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "last_allocated_sequence")
    val lastAllocatedSequence: Long,
)

@Entity(
    tableName = "projection_outbox",
    indices = [
        Index(value = ["learner_id", "outbox_sequence"], unique = true),
        Index(value = ["event_kind", "event_id"], unique = true),
        Index(value = ["learner_id", "status", "outbox_sequence"]),
    ],
)
internal data class ProjectionOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "outbox_id")
    val outboxId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "outbox_sequence")
    val outboxSequence: Long,
    @ColumnInfo(name = "event_kind")
    val eventKind: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    val status: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "projection_consumption",
    primaryKeys = ["projection_name", "learner_id", "outbox_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProjectionOutboxEntity::class,
            parentColumns = ["outbox_id"],
            childColumns = ["outbox_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["outbox_id"]),
        Index(
            value = ["projection_name", "learner_id", "outbox_sequence"],
            unique = true,
        ),
    ],
)
internal data class ProjectionConsumptionEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "outbox_id")
    val outboxId: String,
    @ColumnInfo(name = "outbox_sequence")
    val outboxSequence: Long,
    @ColumnInfo(name = "projector_version")
    val projectorVersion: String,
    @ColumnInfo(name = "consumed_at_epoch_millis")
    val consumedAtEpochMillis: Long,
)

/** Legacy denormalized projection retained for mistake-list fixture previews. */
@Entity(
    tableName = "problem_memory_state",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["next_review_at_epoch_millis"])],
)
internal data class ProblemMemoryStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "stability_days")
    val stabilityDays: Double,
    val difficulty: Double,
    @ColumnInfo(name = "last_reviewed_at_epoch_millis")
    val lastReviewedAtEpochMillis: Long?,
    @ColumnInfo(name = "next_review_at_epoch_millis")
    val nextReviewAtEpochMillis: Long,
    @ColumnInfo(name = "review_count")
    val reviewCount: Int,
    @ColumnInfo(name = "lapse_count")
    val lapseCount: Int,
    val retrievability: Double,
    @ColumnInfo(name = "projection_checkpoint")
    val projectionCheckpoint: Long,
    @ColumnInfo(name = "projector_version")
    val projectorVersion: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/** Legacy denormalized projection retained for fixture compatibility. */
@Entity(
    tableName = "knowledge_mastery_state",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mastery_probability"])],
)
internal data class KnowledgeMasteryStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "mastery_probability")
    val masteryProbability: Double,
    @ColumnInfo(name = "independent_correct_count")
    val independentCorrectCount: Int,
    @ColumnInfo(name = "assisted_correct_count")
    val assistedCorrectCount: Int,
    @ColumnInfo(name = "incorrect_count")
    val incorrectCount: Int,
    @ColumnInfo(name = "evidence_weight_total")
    val evidenceWeightTotal: Double,
    @ColumnInfo(name = "last_evidence_at_epoch_millis")
    val lastEvidenceAtEpochMillis: Long?,
    @ColumnInfo(name = "projection_checkpoint")
    val projectionCheckpoint: Long,
    @ColumnInfo(name = "projector_version")
    val projectorVersion: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "learner_projection_snapshot",
    primaryKeys = ["projection_name", "learner_id"],
    indices = [Index(value = ["learner_id", "checkpoint_sequence"])],
)
internal data class LearnerProjectionSnapshotEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "checkpoint_sequence")
    val checkpointSequence: Long,
    @ColumnInfo(name = "known_ledger_head_sequence")
    val knownLedgerHeadSequence: Long,
    @ColumnInfo(name = "projector_version")
    val projectorVersion: String,
    @ColumnInfo(name = "projected_at_epoch_millis")
    val projectedAtEpochMillis: Long,
    @ColumnInfo(name = "generated_at_epoch_millis")
    val generatedAtEpochMillis: Long,
    @ColumnInfo(name = "correction_watermark_epoch_millis")
    val correctionWatermarkEpochMillis: Long?,
    val freshness: String,
    @ColumnInfo(name = "projection_status")
    val projectionStatus: String,
)

@Entity(
    tableName = "learner_problem_memory_state",
    primaryKeys = ["projection_name", "learner_id", "practice_unit_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id"]),
        Index(value = ["practice_unit_id"]),
    ],
)
internal data class LearnerProblemMemoryStateEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "stability_days")
    val stabilityDays: Double,
    val difficulty: Double,
    @ColumnInfo(name = "last_reviewed_at_epoch_millis")
    val lastReviewedAtEpochMillis: Long,
    @ColumnInfo(name = "next_review_at_epoch_millis")
    val nextReviewAtEpochMillis: Long,
    @ColumnInfo(name = "independent_correct_count")
    val independentCorrectCount: Int,
    @ColumnInfo(name = "assisted_correct_count")
    val assistedCorrectCount: Int,
    @ColumnInfo(name = "lapse_count")
    val lapseCount: Int,
    @ColumnInfo(name = "answer_reveal_count")
    val answerRevealCount: Int,
    @ColumnInfo(name = "last_lapse_at_epoch_millis")
    val lastLapseAtEpochMillis: Long?,
    @ColumnInfo(name = "clock_anomaly_count")
    val clockAnomalyCount: Int,
    @ColumnInfo(name = "last_clock_anomaly_at_epoch_millis")
    val lastClockAnomalyAtEpochMillis: Long?,
    @ColumnInfo(name = "last_attempt_id")
    val lastAttemptId: String,
    @ColumnInfo(name = "projector_version")
    val projectorVersion: String,
    @ColumnInfo(name = "checkpoint_sequence")
    val checkpointSequence: Long,
    @ColumnInfo(name = "last_evidence_reason")
    val lastEvidenceReason: String? = null,
    @ColumnInfo(name = "last_evidence_direction")
    val lastEvidenceDirection: String? = null,
    @ColumnInfo(name = "consecutive_cross_day_success", defaultValue = "0")
    val consecutiveCrossDaySuccess: Int = 0,
    @ColumnInfo(name = "consecutive_cross_day_again", defaultValue = "0")
    val consecutiveCrossDayAgain: Int = 0,
)

@Entity(
    tableName = "learner_knowledge_mastery_state",
    primaryKeys = ["projection_name", "learner_id", "knowledge_node_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id"]),
        Index(value = ["knowledge_node_id"]),
    ],
)
internal data class LearnerKnowledgeMasteryStateEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "probability_independent_correct")
    val masteryScore: Double,
    @ColumnInfo(name = "lower_bound_independent_correct")
    val conservativeMasteryScore: Double,
    @ColumnInfo(name = "evidence_mass")
    val evidenceMass: Double,
    @ColumnInfo(name = "last_independent_error_at_epoch_millis")
    val lastIndependentErrorAtEpochMillis: Long?,
    @ColumnInfo(name = "last_independent_error_sequence")
    val lastIndependentErrorSequence: Long?,
    val status: String,
    @ColumnInfo(name = "calibration_support")
    val calibrationSupport: String,
    @ColumnInfo(name = "projector_version")
    val projectorVersion: String,
    @ColumnInfo(name = "checkpoint_sequence")
    val checkpointSequence: Long,
    @ColumnInfo(name = "last_evidence_at_epoch_millis")
    val lastEvidenceAtEpochMillis: Long?,
    @ColumnInfo(name = "conflict_since_sequence")
    val conflictSinceSequence: Long?,
    @ColumnInfo(name = "last_evidence_reason")
    val lastEvidenceReason: String? = null,
    @ColumnInfo(name = "last_evidence_direction")
    val lastEvidenceDirection: String? = null,
)

@Entity(
    tableName = "independent_correct_observation",
    primaryKeys = ["projection_name", "learner_id", "knowledge_node_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerKnowledgeMasteryStateEntity::class,
            parentColumns = ["projection_name", "learner_id", "knowledge_node_id"],
            childColumns = ["projection_name", "learner_id", "knowledge_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id", "knowledge_node_id"]),
        Index(value = ["projection_name", "learner_id", "event_sequence"]),
    ],
)
internal data class IndependentCorrectObservationEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    val ordinal: Int,
    @ColumnInfo(name = "item_family_id")
    val itemFamilyId: String,
    @ColumnInfo(name = "study_day_epoch_day")
    val studyDayEpochDay: Long,
    @ColumnInfo(name = "is_study_day_trusted")
    val isStudyDayTrusted: Boolean,
    @ColumnInfo(name = "time_trust", defaultValue = "TRUSTED")
    val timeTrust: String = "TRUSTED",
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "binding_id")
    val bindingId: String,
    @ColumnInfo(name = "evidence_weight")
    val evidenceWeight: Double,
    @ColumnInfo(name = "calibration_support")
    val calibrationSupport: String,
    @ColumnInfo(name = "calibration_source_id")
    val calibrationSourceId: String,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String,
    @ColumnInfo(name = "calibration_valid_from_epoch_millis")
    val calibrationValidFromEpochMillis: Long,
    @ColumnInfo(name = "calibration_valid_until_epoch_millis")
    val calibrationValidUntilEpochMillis: Long,
)

@Entity(
    tableName = "applied_attempt_record",
    primaryKeys = ["projection_name", "learner_id", "attempt_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttemptEventEntity::class,
            parentColumns = ["learner_id", "attempt_id"],
            childColumns = ["learner_id", "attempt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id"]),
        Index(value = ["learner_id", "attempt_id"]),
        Index(value = ["projection_name", "learner_id", "event_sequence"], unique = true),
    ],
)
internal data class AppliedAttemptRecordEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "response_ordinal")
    val responseOrdinal: Int,
)

@Entity(
    tableName = "applied_correction_record",
    primaryKeys = ["projection_name", "learner_id", "correction_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttemptCorrectionEntity::class,
            parentColumns = ["learner_id", "correction_id"],
            childColumns = ["learner_id", "correction_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id"]),
        Index(value = ["learner_id", "correction_id"]),
        Index(value = ["projection_name", "learner_id", "event_sequence"], unique = true),
    ],
)
internal data class AppliedCorrectionRecordEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "correction_id")
    val correctionId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
)

@Entity(
    tableName = "applied_answer_reveal_record",
    primaryKeys = ["projection_name", "learner_id", "outcome_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AnswerRevealOutcomeEntity::class,
            parentColumns = ["learner_id", "outcome_id"],
            childColumns = ["learner_id", "outcome_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id"]),
        Index(value = ["learner_id", "outcome_id"]),
        Index(value = ["projection_name", "learner_id", "event_sequence"], unique = true),
    ],
)
internal data class AppliedAnswerRevealRecordEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "outcome_id")
    val outcomeId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
)

/**
 * Unbounded projector authority for one presentation. Applied-event records are a bounded audit
 * window and must never be used to decide whether an older presentation already projected memory.
 */
@Entity(
    tableName = "presentation_projection_state",
    primaryKeys = ["projection_name", "learner_id", "presentation_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssessmentPresentationEntity::class,
            parentColumns = ["learner_id", "presentation_id"],
            childColumns = ["learner_id", "presentation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["projection_name", "learner_id"]),
        Index(value = ["learner_id", "presentation_id"]),
        Index(value = ["projection_name", "learner_id", "terminal_event_sequence"]),
        Index(value = ["projection_name", "learner_id", "memory_projection_sequence"]),
    ],
)
internal data class PresentationProjectionStateEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "terminal_outcome_id")
    val terminalOutcomeId: String?,
    @ColumnInfo(name = "terminal_outcome")
    val terminalOutcome: String?,
    @ColumnInfo(name = "terminal_event_sequence")
    val terminalEventSequence: Long?,
    @ColumnInfo(name = "memory_projected")
    val memoryProjected: Boolean,
    @ColumnInfo(name = "memory_projection_sequence")
    val memoryProjectionSequence: Long?,
    @ColumnInfo(name = "last_response_ordinal")
    val lastResponseOrdinal: Int,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
)

/**
 * Raw collected review evidence (spec mastery-scheduling 3.1). Collection is
 * decoupled from scheduling: every graded interaction lands here exactly
 * once per (learner, source), while scheduling state is owned by the
 * projection. review_log feeds the evaluation harness and the local
 * parameter optimizer, never the projector.
 */
@Entity(
    tableName = "review_log",
    indices = [
        Index(
            value = ["learner_id", "source_id"],
            unique = true,
        ),
        Index(value = ["learner_id", "card_id"]),
        Index(value = ["learner_id", "reviewed_at_utc"]),
    ],
)
internal data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "review_log_id")
    val reviewLogId: Long = 0,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "card_id")
    val cardId: String,
    /** FSRS rating 1..4 (Again/Hard/Good/Easy) after the evidence mapping. */
    val rating: Int,
    @ColumnInfo(name = "delta_t_days")
    val deltaTDays: Double,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "reviewed_at_utc")
    val reviewedAtUtc: Long,
    /** ATTEMPT / SELF_REPORT / VISUAL / TUTOR_EXPOSURE. */
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    /** Idempotency key (attempt id / outcome id / exposure outcome id). */
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "evidence_weight")
    val evidenceWeight: Double,
    /** False for observation-only rows (cooldown-suppressed, spec 2.7). */
    @ColumnInfo(name = "scheduling_eligible", defaultValue = "1")
    val schedulingEligible: Boolean = true,
    /** MORNING/NOON/AFTERNOON/EVENING/NIGHT at review time (spec 2.12). */
    @ColumnInfo(name = "time_bucket")
    val timeBucket: String,
    /** Silent interaction signals (spec 2.14), collected without UI prompts. */
    @ColumnInfo(name = "scroll_up_count", defaultValue = "0")
    val scrollUpCount: Int = 0,
    @ColumnInfo(name = "edit_count", defaultValue = "0")
    val editCount: Int = 0,
    @ColumnInfo(name = "interruption_count", defaultValue = "0")
    val interruptionCount: Int = 0,
    /** Cumulative time away from the app during the attempt (spec 2.14). */
    @ColumnInfo(name = "away_millis", defaultValue = "0")
    val awayMillis: Long = 0,
    /** Planner reason snapshot carried onto the attempt (spec 6 calibration). */
    @ColumnInfo(name = "planned_reason")
    val plannedReason: String? = null,
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,
)

/**
 * LLM-authored teaching advisory (three-store closed loop): the model owns
 * these rows inside the mastery database. Projection-owned state
 * (learner_*_state) is never touched - full replay only ever rebuilds the
 * projector's rows, so model judgment and learning evidence stay separate
 * authorities. 审阅口径：讲题重点 (TEACHING_FOCUS) 与 误区 (MISCONCEPTION)。
 */
@Entity(
    tableName = "llm_teaching_advisory",
    indices = [
        Index(
            value = ["learner_id", "source_id", "advisory_kind"],
            unique = true,
        ),
        Index(value = ["learner_id", "knowledge_node_id"]),
        Index(value = ["learner_id", "practice_unit_id"]),
    ],
)
internal data class LlmTeachingAdvisoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "advisory_id")
    val advisoryId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String?,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String?,
    /** TEACHING_FOCUS / MISCONCEPTION. */
    @ColumnInfo(name = "advisory_kind")
    val advisoryKind: String,
    @ColumnInfo(name = "payload_markdown")
    val payloadMarkdown: String,
    @ColumnInfo(name = "confidence")
    val confidence: Double?,
    /** Model task/session identifier for provenance and dedup. */
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)
