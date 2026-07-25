package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "tutor_turn_response",
    primaryKeys = ["session_id", "cycle_ordinal", "turn_ordinal"],
    indices = [
        Index(value = ["question_document_id", "revision_number"]),
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
