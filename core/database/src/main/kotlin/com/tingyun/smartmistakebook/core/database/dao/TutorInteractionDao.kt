package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorMoveCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorRevealCommand
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnResponseEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
internal abstract class TutorInteractionDao {
    @Query(
        """
        SELECT * FROM tutor_turn_response
        WHERE session_id = :sessionId
        ORDER BY cycle_ordinal ASC, turn_ordinal ASC
        """,
    )
    protected abstract fun observeEntities(sessionId: String): Flow<List<TutorTurnResponseEntity>>

    fun observe(sessionId: String): Flow<List<TutorTurnResponseRecord>> =
        observeEntities(sessionId).map { rows -> rows.map(TutorTurnResponseEntity::toRecord) }

    @Query(
        """
        SELECT * FROM tutor_turn_response
        WHERE session_id = :sessionId AND cycle_ordinal = :cycleOrdinal AND turn_ordinal = :turnOrdinal
        """,
    )
    protected abstract suspend fun readEntity(
        sessionId: String,
        cycleOrdinal: Int,
        turnOrdinal: Int,
    ): TutorTurnResponseEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(entity: TutorTurnResponseEntity): Long

    @Query(
        """
        UPDATE tutor_turn_response
        SET diagnostic_stem_markdown = :diagnosticStemMarkdown,
            selected_choice_id = :selectedChoiceId,
            selected_choice_markdown = :selectedChoiceMarkdown,
            selection_was_correct = :selectionWasCorrect,
            feedback_markdown = :feedbackMarkdown,
            choice_submitted_at_epoch_millis = :choiceSubmittedAtEpochMillis
        WHERE session_id = :sessionId
          AND cycle_ordinal = :cycleOrdinal
          AND turn_ordinal = :turnOrdinal
          AND question_document_id = :questionDocumentId
          AND revision_number = :revisionNumber
          AND diagnostic_stem_markdown IS NULL
          AND selected_choice_id IS NULL
          AND selected_choice_markdown IS NULL
          AND selection_was_correct IS NULL
          AND feedback_markdown IS NULL
          AND choice_submitted_at_epoch_millis IS NULL
        """,
    )
    protected abstract suspend fun updateChoicePayload(
        sessionId: String,
        questionDocumentId: String,
        revisionNumber: Int,
        cycleOrdinal: Int,
        turnOrdinal: Int,
        diagnosticStemMarkdown: String,
        selectedChoiceId: String,
        selectedChoiceMarkdown: String,
        selectionWasCorrect: Boolean,
        feedbackMarkdown: String,
        choiceSubmittedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_turn_response
        SET diagnostic_stem_markdown = NULL,
            selected_choice_id = NULL,
            selected_choice_markdown = NULL,
            selection_was_correct = NULL,
            feedback_markdown = NULL,
            choice_submitted_at_epoch_millis = NULL
        WHERE session_id = :sessionId
          AND cycle_ordinal = :cycleOrdinal
          AND turn_ordinal = :turnOrdinal
          AND question_document_id = :questionDocumentId
          AND revision_number = :revisionNumber
          AND diagnostic_stem_markdown = :diagnosticStemMarkdown
          AND selected_choice_id = :selectedChoiceId
          AND selected_choice_markdown = :selectedChoiceMarkdown
          AND selection_was_correct = :selectionWasCorrect
          AND feedback_markdown = :feedbackMarkdown
          AND choice_submitted_at_epoch_millis = :choiceSubmittedAtEpochMillis
        """,
    )
    protected abstract suspend fun clearExactChoicePayload(
        sessionId: String,
        questionDocumentId: String,
        revisionNumber: Int,
        cycleOrdinal: Int,
        turnOrdinal: Int,
        diagnosticStemMarkdown: String,
        selectedChoiceId: String,
        selectedChoiceMarkdown: String,
        selectionWasCorrect: Boolean,
        feedbackMarkdown: String,
        choiceSubmittedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_turn_response
        SET requested_move = :requestedMove,
            submitted_at_epoch_millis = CASE
                WHEN diagnostic_stem_markdown IS NULL
                    THEN MIN(submitted_at_epoch_millis, :occurredAtEpochMillis)
                ELSE submitted_at_epoch_millis
            END,
            updated_at_epoch_millis = MAX(updated_at_epoch_millis, :occurredAtEpochMillis)
        WHERE session_id = :sessionId
          AND cycle_ordinal = :cycleOrdinal
          AND turn_ordinal = :turnOrdinal
          AND requested_move IS NULL
        """,
    )
    protected abstract suspend fun updateMove(
        sessionId: String,
        cycleOrdinal: Int,
        turnOrdinal: Int,
        requestedMove: String,
        occurredAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_turn_response
        SET solution_revealed = 1,
            submitted_at_epoch_millis = CASE
                WHEN diagnostic_stem_markdown IS NULL
                    THEN MIN(submitted_at_epoch_millis, :occurredAtEpochMillis)
                ELSE submitted_at_epoch_millis
            END,
            updated_at_epoch_millis = MAX(updated_at_epoch_millis, :occurredAtEpochMillis)
        WHERE session_id = :sessionId
          AND cycle_ordinal = :cycleOrdinal
          AND turn_ordinal = :turnOrdinal
          AND solution_revealed = 0
        """,
    )
    protected abstract suspend fun markSolutionRevealed(
        sessionId: String,
        cycleOrdinal: Int,
        turnOrdinal: Int,
        occurredAtEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun recordChoice(command: PersistTutorChoiceCommand): TutorTurnResponseRecord {
        val candidate = command.toEntity()
        if (insert(candidate) != INSERT_CONFLICT) return candidate.toRecord()
        val existing = checkNotNull(
            readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal),
        )
        existing.requireSameQuestion(command.questionDocumentId, command.revisionNumber)
        if (existing.hasSameChoicePayload(candidate)) return existing.toRecord()
        if (existing.hasChoicePayload) {
            throw ImmutablePayloadConflictException(
                "tutor_turn_response",
                "${command.sessionId}:${command.cycleOrdinal}:${command.turnOrdinal}",
            )
        }
        updateChoicePayload(
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            diagnosticStemMarkdown = command.diagnosticStemMarkdown,
            selectedChoiceId = command.selectedChoiceId,
            selectedChoiceMarkdown = command.selectedChoiceMarkdown,
            selectionWasCorrect = command.selectionWasCorrect,
            feedbackMarkdown = command.feedbackMarkdown,
            choiceSubmittedAtEpochMillis = command.choiceSubmittedAtEpochMillis,
        )
        val stored = checkNotNull(
            readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal),
        )
        stored.requireSameQuestion(command.questionDocumentId, command.revisionNumber)
        if (!stored.hasSameChoicePayload(candidate)) {
            throw ImmutablePayloadConflictException(
                "tutor_turn_response",
                "${command.sessionId}:${command.cycleOrdinal}:${command.turnOrdinal}",
            )
        }
        return stored.toRecord()
    }

    @Transaction
    open suspend fun discardChoice(command: PersistTutorChoiceCommand): Boolean =
        clearExactChoicePayload(
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            diagnosticStemMarkdown = command.diagnosticStemMarkdown,
            selectedChoiceId = command.selectedChoiceId,
            selectedChoiceMarkdown = command.selectedChoiceMarkdown,
            selectionWasCorrect = command.selectionWasCorrect,
            feedbackMarkdown = command.feedbackMarkdown,
            choiceSubmittedAtEpochMillis = command.choiceSubmittedAtEpochMillis,
        ) == 1

    @Transaction
    open suspend fun recordMove(command: PersistTutorMoveCommand): TutorTurnResponseRecord {
        val candidate = command.toActionEntity()
        if (insert(candidate) != INSERT_CONFLICT) return candidate.toRecord()
        val existing = checkNotNull(
            readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal),
        )
        existing.requireSameQuestion(command.questionDocumentId, command.revisionNumber)
        if (existing.hasChoicePayload) {
            require(command.occurredAtEpochMillis >= existing.submittedAtEpochMillis)
        }
        existing.requestedMove?.let { storedMove ->
            if (storedMove != command.requestedMove) {
                throw ImmutablePayloadConflictException(
                    "tutor_requested_move",
                    "${command.sessionId}:${command.cycleOrdinal}:${command.turnOrdinal}",
                )
            }
            return existing.toRecord()
        }
        val updated = updateMove(
            sessionId = command.sessionId,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            requestedMove = command.requestedMove,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        if (updated != 1) {
            val raced = checkNotNull(
                readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal),
            )
            raced.requireSameQuestion(command.questionDocumentId, command.revisionNumber)
            if (raced.requestedMove == command.requestedMove) return raced.toRecord()
            throw ImmutablePayloadConflictException(
                "tutor_requested_move",
                "${command.sessionId}:${command.cycleOrdinal}:${command.turnOrdinal}",
            )
        }
        return checkNotNull(readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal)).toRecord()
    }

    @Transaction
    open suspend fun revealSolution(command: PersistTutorRevealCommand): TutorTurnResponseRecord {
        val candidate = command.toActionEntity()
        if (insert(candidate) != INSERT_CONFLICT) return candidate.toRecord()
        val existing = checkNotNull(
            readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal),
        )
        existing.requireSameQuestion(command.questionDocumentId, command.revisionNumber)
        if (existing.hasChoicePayload) {
            require(command.occurredAtEpochMillis >= existing.submittedAtEpochMillis)
        }
        if (existing.solutionRevealed) return existing.toRecord()
        val updated = markSolutionRevealed(
            sessionId = command.sessionId,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        if (updated != 1) {
            val raced = checkNotNull(
                readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal),
            )
            raced.requireSameQuestion(command.questionDocumentId, command.revisionNumber)
            check(raced.solutionRevealed) { "Tutor solution reveal was not persisted" }
            return raced.toRecord()
        }
        return checkNotNull(readEntity(command.sessionId, command.cycleOrdinal, command.turnOrdinal)).toRecord()
    }
}

private fun PersistTutorChoiceCommand.toEntity() = TutorTurnResponseEntity(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    diagnosticStemMarkdown = diagnosticStemMarkdown,
    selectedChoiceId = selectedChoiceId,
    selectedChoiceMarkdown = selectedChoiceMarkdown,
    selectionWasCorrect = selectionWasCorrect,
    feedbackMarkdown = feedbackMarkdown,
    requestedMove = null,
    solutionRevealed = false,
    choiceSubmittedAtEpochMillis = choiceSubmittedAtEpochMillis,
    submittedAtEpochMillis = choiceSubmittedAtEpochMillis,
    updatedAtEpochMillis = choiceSubmittedAtEpochMillis,
)

private fun PersistTutorMoveCommand.toActionEntity() = TutorTurnResponseEntity(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    diagnosticStemMarkdown = null,
    selectedChoiceId = null,
    selectedChoiceMarkdown = null,
    selectionWasCorrect = null,
    feedbackMarkdown = null,
    requestedMove = requestedMove,
    solutionRevealed = false,
    choiceSubmittedAtEpochMillis = null,
    submittedAtEpochMillis = occurredAtEpochMillis,
    updatedAtEpochMillis = occurredAtEpochMillis,
)

private fun PersistTutorRevealCommand.toActionEntity() = TutorTurnResponseEntity(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    diagnosticStemMarkdown = null,
    selectedChoiceId = null,
    selectedChoiceMarkdown = null,
    selectionWasCorrect = null,
    feedbackMarkdown = null,
    requestedMove = null,
    solutionRevealed = true,
    choiceSubmittedAtEpochMillis = null,
    submittedAtEpochMillis = occurredAtEpochMillis,
    updatedAtEpochMillis = occurredAtEpochMillis,
)

private fun TutorTurnResponseEntity.hasSameChoicePayload(other: TutorTurnResponseEntity): Boolean =
    sessionId == other.sessionId && questionDocumentId == other.questionDocumentId &&
        revisionNumber == other.revisionNumber && cycleOrdinal == other.cycleOrdinal &&
        turnOrdinal == other.turnOrdinal && diagnosticStemMarkdown == other.diagnosticStemMarkdown &&
        selectedChoiceId == other.selectedChoiceId && selectedChoiceMarkdown == other.selectedChoiceMarkdown &&
        selectionWasCorrect == other.selectionWasCorrect && feedbackMarkdown == other.feedbackMarkdown

private val TutorTurnResponseEntity.hasChoicePayload: Boolean
    get() = diagnosticStemMarkdown != null

private fun TutorTurnResponseEntity.requireSameQuestion(
    expectedQuestionDocumentId: String,
    expectedRevisionNumber: Int,
) {
    if (
        questionDocumentId != expectedQuestionDocumentId ||
        revisionNumber != expectedRevisionNumber
    ) {
        throw ImmutablePayloadConflictException(
            "tutor_turn_response",
            "$sessionId:$cycleOrdinal:$turnOrdinal",
        )
    }
}

internal fun TutorTurnResponseEntity.toRecord() = TutorTurnResponseRecord(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    diagnosticStemMarkdown = diagnosticStemMarkdown,
    selectedChoiceId = selectedChoiceId,
    selectedChoiceMarkdown = selectedChoiceMarkdown,
    selectionWasCorrect = selectionWasCorrect,
    feedbackMarkdown = feedbackMarkdown,
    requestedMove = requestedMove,
    solutionRevealed = solutionRevealed,
    choiceSubmittedAtEpochMillis = choiceSubmittedAtEpochMillis,
    submittedAtEpochMillis = submittedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private const val INSERT_CONFLICT = -1L
