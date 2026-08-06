package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "tutor_turn_response",
    primaryKeys = ["session_id", "cycle_ordinal", "turn_ordinal"],
    indices = [
        Index(value = ["question_document_id", "revision_number"]),
        Index(
            value = [
                "evidence_request_id",
                "session_id",
                "cycle_ordinal",
                "turn_ordinal",
            ],
        ),
    ],
)
internal data class TutorTurnResponseEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "diagnostic_stem_markdown")
    val diagnosticStemMarkdown: String?,
    @ColumnInfo(name = "selected_choice_id")
    val selectedChoiceId: String?,
    @ColumnInfo(name = "selected_choice_markdown")
    val selectedChoiceMarkdown: String?,
    @ColumnInfo(name = "selection_was_correct")
    val selectionWasCorrect: Boolean?,
    @ColumnInfo(name = "feedback_markdown")
    val feedbackMarkdown: String?,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    @ColumnInfo(name = "requested_move")
    val requestedMove: String?,
    @ColumnInfo(name = "solution_revealed")
    val solutionRevealed: Boolean,
    @ColumnInfo(name = "choice_submitted_at_epoch_millis")
    val choiceSubmittedAtEpochMillis: Long?,
    @ColumnInfo(name = "submitted_at_epoch_millis")
    val submittedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "tutor_visual_target_evidence",
    foreignKeys = [
        ForeignKey(
            entity = ModelTaskEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["model_task_request_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["session_id", "cycle_ordinal", "turn_ordinal"]),
        Index(value = ["hit_proof_id"], unique = true),
    ],
)
internal data class TutorVisualTargetEvidenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "model_task_request_id")
    val modelTaskRequestId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "surface_kind")
    val surfaceKind: String,
    @ColumnInfo(name = "response_ordinal")
    val responseOrdinal: Int?,
    @ColumnInfo(name = "scene_source_kind")
    val sceneSourceKind: String,
    @ColumnInfo(name = "scene_task_request_id")
    val sceneTaskRequestId: String,
    @ColumnInfo(name = "scene_id")
    val sceneId: String,
    @ColumnInfo(name = "scene_fingerprint")
    val sceneFingerprint: String,
    @ColumnInfo(name = "hit_proof_id")
    val hitProofId: String,
    @ColumnInfo(name = "panel_id")
    val panelId: String,
    @ColumnInfo(name = "frame_fingerprint")
    val frameFingerprint: String,
    @ColumnInfo(name = "step_index")
    val stepIndex: Int,
    @ColumnInfo(name = "selected_target_id")
    val selectedTargetId: String,
    @ColumnInfo(name = "selection_was_correct")
    val selectionWasCorrect: Boolean,
    @ColumnInfo(name = "submitted_at_epoch_millis")
    val submittedAtEpochMillis: Long,
)

@Entity(
    tableName = "tutor_evidence_cancellation",
    primaryKeys = [
        "learner_id",
        "session_id",
        "question_document_id",
        "revision_number",
        "evidence_request_id",
    ],
    indices = [
        Index(value = ["session_id", "question_document_id", "revision_number"]),
        Index(
            value = [
                "learner_id",
                "evidence_request_id",
                "session_id",
                "question_document_id",
                "revision_number",
            ],
        ),
    ],
)
internal data class TutorEvidenceCancellationEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String,
    @ColumnInfo(name = "cancelled_at_epoch_millis")
    val cancelledAtEpochMillis: Long,
)
