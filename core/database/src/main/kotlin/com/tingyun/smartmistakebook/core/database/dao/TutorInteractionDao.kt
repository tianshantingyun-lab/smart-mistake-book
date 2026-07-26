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
import com.tingyun.smartmistakebook.core.database.PersistTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.TutorVisualTargetEvidenceRecord
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnResponseEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorVisualTargetEvidenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEntity
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
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
        SELECT * FROM tutor_visual_target_evidence
        WHERE session_id = :sessionId
        ORDER BY cycle_ordinal ASC, turn_ordinal ASC, response_ordinal ASC,
            submitted_at_epoch_millis ASC
        """,
    )
    protected abstract fun observeVisualEvidenceEntities(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidenceEntity>>

    fun observeVisualEvidence(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidenceRecord>> =
        observeVisualEvidenceEntities(sessionId).map { rows ->
            rows.map(TutorVisualTargetEvidenceEntity::toRecord)
        }

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
        SELECT * FROM tutor_visual_target_evidence
        WHERE model_task_request_id = :modelTaskRequestId
        """,
    )
    protected abstract suspend fun readVisualEvidenceEntity(
        modelTaskRequestId: String,
    ): TutorVisualTargetEvidenceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertVisualEvidence(
        entity: TutorVisualTargetEvidenceEntity,
    ): Long

    @Query("SELECT * FROM model_task WHERE request_id = :requestId LIMIT 1")
    protected abstract suspend fun findModelTask(requestId: String): ModelTaskEntity?

    @Query(
        """
        DELETE FROM tutor_visual_target_evidence
        WHERE model_task_request_id = :modelTaskRequestId
          AND session_id = :sessionId
          AND question_document_id = :questionDocumentId
          AND revision_number = :revisionNumber
          AND cycle_ordinal = :cycleOrdinal
          AND turn_ordinal = :turnOrdinal
          AND surface_kind = :surfaceKind
          AND (
              response_ordinal = :responseOrdinal OR
              response_ordinal IS NULL AND :responseOrdinal IS NULL
          )
          AND selected_target_id = :selectedTargetId
          AND selection_was_correct = :selectionWasCorrect
          AND submitted_at_epoch_millis = :submittedAtEpochMillis
        """,
    )
    protected abstract suspend fun deleteExactVisualEvidence(
        modelTaskRequestId: String,
        sessionId: String,
        questionDocumentId: String,
        revisionNumber: Int,
        cycleOrdinal: Int,
        turnOrdinal: Int,
        surfaceKind: String,
        responseOrdinal: Int?,
        selectedTargetId: String,
        selectionWasCorrect: Boolean,
        submittedAtEpochMillis: Long,
    ): Int

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
    open suspend fun recordVisualTargetEvidence(
        command: PersistTutorVisualTargetEvidenceCommand,
    ): TutorVisualTargetEvidenceRecord {
        val expectedTargetId = validateVisualTargetEvidence(command)
        val candidate = command.toVisualEvidenceEntity(
            selectionWasCorrect = command.selectedTargetId == expectedTargetId,
        )
        if (insertVisualEvidence(candidate) != INSERT_CONFLICT) return candidate.toRecord()
        val existing = checkNotNull(readVisualEvidenceEntity(command.modelTaskRequestId))
        if (!existing.hasSameVisualEvidencePayload(candidate)) {
            throw ImmutablePayloadConflictException(
                "tutor_visual_target_evidence",
                command.modelTaskRequestId,
            )
        }
        return existing.toRecord()
    }

    @Transaction
    open suspend fun discardVisualTargetEvidence(
        command: PersistTutorVisualTargetEvidenceCommand,
    ): Boolean {
        val selectionWasCorrect =
            command.selectedTargetId == validateVisualTargetEvidence(command)
        val deleted = deleteExactVisualEvidence(
            modelTaskRequestId = command.modelTaskRequestId,
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            surfaceKind = command.surfaceKind,
            responseOrdinal = command.responseOrdinal,
            selectedTargetId = command.selectedTargetId,
            selectionWasCorrect = selectionWasCorrect,
            submittedAtEpochMillis = command.submittedAtEpochMillis,
        )
        if (deleted == 1) return true
        val existing = readVisualEvidenceEntity(command.modelTaskRequestId) ?: return false
        return existing.hasSameVisualEvidencePayload(
            command.toVisualEvidenceEntity(selectionWasCorrect),
        ) &&
            existing.submittedAtEpochMillis != command.submittedAtEpochMillis
    }

    private suspend fun validateVisualTargetEvidence(
        command: PersistTutorVisualTargetEvidenceCommand,
    ): String {
        val task = findModelTask(command.modelTaskRequestId)
            ?: throw ImmutablePayloadConflictException(
                "tutor_visual_target_evidence_model_task",
                command.modelTaskRequestId,
            )
        val request = ModelTaskCodec.decodeRequest(task.requestSnapshot)
        val output = task.outputSnapshot?.let(ModelTaskCodec::decodeOutput)
        val commonIdentityMatches =
            task.status == ModelTaskStatus.SUCCEEDED.name &&
                request.requestId == command.modelTaskRequestId &&
                command.submittedAtEpochMillis >= task.updatedAtEpochMillis
        val expectedTargetId = when (command.surfaceKind) {
            "PLAN" -> {
                val input = request.input as? TutorPlanInput
                val planOutput = output as? TutorPlanOutput
                val directive = planOutput?.plan?.interactionDirective as?
                    TutorInteractionDirective.VisualTarget
                directive?.targetId?.takeIf {
                    input != null && command.responseOrdinal == null &&
                    input.sessionId == command.sessionId &&
                    input.questionDocument.id == command.questionDocumentId &&
                    input.draftRevisionNumber == command.revisionNumber &&
                    input.cycleOrdinal == command.cycleOrdinal &&
                    input.turnOrdinal == command.turnOrdinal
                }
            }
            "FOLLOW_UP" -> {
                val input = request.input as? TutorRespondInput
                val respondOutput = output as? TutorRespondOutput
                val directive = respondOutput?.interactionDirective as?
                    TutorInteractionDirective.VisualTarget
                directive?.targetId?.takeIf {
                    input != null &&
                    input.sessionId == command.sessionId &&
                    input.questionDocument.id == command.questionDocumentId &&
                    input.draftRevisionNumber == command.revisionNumber &&
                    input.cycleOrdinal == command.cycleOrdinal &&
                    input.turnOrdinal == command.turnOrdinal &&
                    input.responseOrdinal == command.responseOrdinal
                }
            }
            else -> null
        }
        if (!commonIdentityMatches || expectedTargetId == null) {
            throw ImmutablePayloadConflictException(
                "tutor_visual_target_evidence_surface",
                command.modelTaskRequestId,
            )
        }
        return expectedTargetId
    }

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

private fun PersistTutorVisualTargetEvidenceCommand.toVisualEvidenceEntity(
    selectionWasCorrect: Boolean,
) =
    TutorVisualTargetEvidenceEntity(
        modelTaskRequestId = modelTaskRequestId,
        sessionId = sessionId,
        questionDocumentId = questionDocumentId,
        revisionNumber = revisionNumber,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        surfaceKind = surfaceKind,
        responseOrdinal = responseOrdinal,
        selectedTargetId = selectedTargetId,
        selectionWasCorrect = selectionWasCorrect,
        submittedAtEpochMillis = submittedAtEpochMillis,
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

private fun TutorVisualTargetEvidenceEntity.hasSameVisualEvidencePayload(
    other: TutorVisualTargetEvidenceEntity,
): Boolean =
    modelTaskRequestId == other.modelTaskRequestId &&
        sessionId == other.sessionId &&
        questionDocumentId == other.questionDocumentId &&
        revisionNumber == other.revisionNumber &&
        cycleOrdinal == other.cycleOrdinal &&
        turnOrdinal == other.turnOrdinal &&
        surfaceKind == other.surfaceKind &&
        responseOrdinal == other.responseOrdinal &&
        selectedTargetId == other.selectedTargetId &&
        selectionWasCorrect == other.selectionWasCorrect

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

internal fun TutorVisualTargetEvidenceEntity.toRecord() = TutorVisualTargetEvidenceRecord(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    surfaceKind = surfaceKind,
    modelTaskRequestId = modelTaskRequestId,
    responseOrdinal = responseOrdinal,
    selectedTargetId = selectedTargetId,
    selectionWasCorrect = selectionWasCorrect,
    submittedAtEpochMillis = submittedAtEpochMillis,
)

private const val INSERT_CONFLICT = -1L
