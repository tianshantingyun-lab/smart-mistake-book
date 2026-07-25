package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "tutor_session_problem_anchor",
    foreignKeys = [
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["learner_id"]),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["problem_revision_id"]),
    ],
)
internal data class TutorSessionProblemAnchorEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "anchor_source")
    val anchorSource: String,
    @ColumnInfo(name = "anchored_at_epoch_millis")
    val anchoredAtEpochMillis: Long,
)

@Entity(
    tableName = "tutor_answer_exposure",
    foreignKeys = [
        ForeignKey(
            entity = ModelTaskEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["model_task_request_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["learner_id"]),
        Index(value = ["session_id"]),
        Index(value = ["session_id", "cycle_ordinal", "turn_ordinal"]),
        Index(value = ["model_task_request_id"], unique = true),
    ],
)
internal data class TutorAnswerExposureEntity(
    @PrimaryKey
    @ColumnInfo(name = "exposure_id")
    val exposureId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "question_revision_number")
    val questionRevisionNumber: Int,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "surface_kind")
    val surfaceKind: String,
    @ColumnInfo(name = "model_task_request_id")
    val modelTaskRequestId: String?,
    @ColumnInfo(name = "response_ordinal")
    val responseOrdinal: Int?,
    @ColumnInfo(name = "exposed_at_epoch_millis")
    val exposedAtEpochMillis: Long,
)

@Entity(
    tableName = "tutor_answer_exposure_outcome",
    foreignKeys = [
        ForeignKey(
            entity = TutorAnswerExposureEntity::class,
            parentColumns = ["exposure_id"],
            childColumns = ["exposure_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorSessionProblemAnchorEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id", "problem_revision_id"],
            childColumns = ["practice_unit_id", "problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["exposure_id"], unique = true),
        Index(value = ["session_id"]),
        Index(value = ["practice_unit_id", "problem_revision_id"]),
        Index(value = ["learner_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "outcome_id"], unique = true),
    ],
)
internal data class TutorAnswerExposureOutcomeEntity(
    @PrimaryKey
    @ColumnInfo(name = "outcome_id")
    val outcomeId: String,
    @ColumnInfo(name = "exposure_id")
    val exposureId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "question_revision_number")
    val questionRevisionNumber: Int,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
)

@Entity(
    tableName = "applied_tutor_answer_exposure_record",
    primaryKeys = ["projection_name", "learner_id", "outcome_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProjectionSnapshotEntity::class,
            parentColumns = ["projection_name", "learner_id"],
            childColumns = ["projection_name", "learner_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TutorAnswerExposureOutcomeEntity::class,
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
internal data class AppliedTutorAnswerExposureRecordEntity(
    @ColumnInfo(name = "projection_name")
    val projectionName: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "outcome_id")
    val outcomeId: String,
    @ColumnInfo(name = "exposure_id")
    val exposureId: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
)
