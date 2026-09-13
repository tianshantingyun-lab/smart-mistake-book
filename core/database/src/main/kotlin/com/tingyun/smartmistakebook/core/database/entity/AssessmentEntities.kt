package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * `assessment_*` 家族的表实体（审计 R-01：从 `LearningEntities.kt` 拆出，同包 ⇒ 零 import 改动）。
 */
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
