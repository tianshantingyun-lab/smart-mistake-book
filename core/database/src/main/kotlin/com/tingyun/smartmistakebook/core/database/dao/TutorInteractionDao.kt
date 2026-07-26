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
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
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
        var visualRequest: TutorVisualGenerationRequest? = null
        var inlineScene: TutorVisualDocumentScene? = null
        val expectedTargetId = when (command.surfaceKind) {
            "PLAN" -> {
                val input = request.input as? TutorPlanInput
                val planOutput = output as? TutorPlanOutput
                visualRequest = planOutput?.plan?.visualRequest
                inlineScene = planOutput?.plan?.visualScene as? TutorVisualDocumentScene
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
                visualRequest = respondOutput?.visualRequest
                inlineScene = respondOutput?.visualScene as? TutorVisualDocumentScene
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
        val scene = when (command.sceneSourceKind) {
            "INLINE" -> inlineScene?.takeIf {
                command.sceneTaskRequestId == command.modelTaskRequestId
            }
            "GENERATED" -> validateGeneratedVisualScene(
                command = command,
                expectedFocusMarkdown = visualRequest?.focusMarkdown,
            )
            else -> null
        }
        val selectedElement = scene?.elements?.firstOrNull { element ->
            element.elementId == command.selectedTargetId
        }
        if (
            scene == null ||
            scene.sceneId != command.sceneId ||
            TutorVisualSceneFingerprint.of(scene) != command.sceneFingerprint ||
            scene.steps.getOrNull(command.stepIndex) == null ||
            scene.elements.none { element -> element.elementId == expectedTargetId } ||
            selectedElement?.panelId != command.panelId
        ) {
            throw ImmutablePayloadConflictException(
                "tutor_visual_target_evidence_scene",
                command.modelTaskRequestId,
            )
        }
        return expectedTargetId
    }

    private suspend fun validateGeneratedVisualScene(
        command: PersistTutorVisualTargetEvidenceCommand,
        expectedFocusMarkdown: String?,
    ): TutorVisualDocumentScene? {
        val focusMarkdown = expectedFocusMarkdown ?: return null
        val sceneTask = findModelTask(command.sceneTaskRequestId) ?: return null
        if (
            sceneTask.status != ModelTaskStatus.SUCCEEDED.name ||
            sceneTask.updatedAtEpochMillis > command.submittedAtEpochMillis
        ) {
            return null
        }
        val request = ModelTaskCodec.decodeRequest(sceneTask.requestSnapshot)
        if (request.requestId != command.sceneTaskRequestId) return null
        val output = sceneTask.outputSnapshot?.let(ModelTaskCodec::decodeOutput) ?: return null
        return when (val input = request.input) {
            is TutorVisualGenerateInput -> {
                val generated = output as? TutorVisualGenerateOutput ?: return null
                generated.scene.takeIf {
                    input.matchesVisualEvidence(command, focusMarkdown) &&
                        generated.matchesVisualEvidence(command) &&
                        generated.decision == TutorVisualGenerationDecision.GENERATED &&
                        generated.confidence >= MIN_GENERATION_READY_CONFIDENCE
                }
            }
            is TutorVisualReviewInput -> {
                val reviewed = output as? TutorVisualReviewOutput ?: return null
                val reviewedScene = when (reviewed.decision) {
                    TutorVisualReviewDecision.APPROVED -> input.candidateScene
                    TutorVisualReviewDecision.REPAIRED -> reviewed.scene
                    TutorVisualReviewDecision.REJECTED -> null
                }
                reviewedScene.takeIf {
                    input.matchesVisualEvidence(command, focusMarkdown) &&
                        reviewed.matchesVisualEvidence(command) &&
                        reviewed.confidence >= MIN_REVIEW_READY_CONFIDENCE
                }
            }
            else -> null
        }
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
        sceneSourceKind = sceneSourceKind,
        sceneTaskRequestId = sceneTaskRequestId,
        sceneId = sceneId,
        sceneFingerprint = sceneFingerprint,
        hitProofId = hitProofId,
        panelId = panelId,
        frameFingerprint = frameFingerprint,
        stepIndex = stepIndex,
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
        sceneSourceKind == other.sceneSourceKind &&
        sceneTaskRequestId == other.sceneTaskRequestId &&
        sceneId == other.sceneId &&
        sceneFingerprint == other.sceneFingerprint &&
        hitProofId == other.hitProofId &&
        panelId == other.panelId &&
        frameFingerprint == other.frameFingerprint &&
        stepIndex == other.stepIndex &&
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
    sceneSourceKind = sceneSourceKind,
    sceneTaskRequestId = sceneTaskRequestId,
    sceneId = sceneId,
    sceneFingerprint = sceneFingerprint,
    hitProofId = hitProofId,
    panelId = panelId,
    frameFingerprint = frameFingerprint,
    stepIndex = stepIndex,
    selectedTargetId = selectedTargetId,
    selectionWasCorrect = selectionWasCorrect,
    submittedAtEpochMillis = submittedAtEpochMillis,
)

private fun TutorVisualGenerateInput.matchesVisualEvidence(
    command: PersistTutorVisualTargetEvidenceCommand,
    expectedFocusMarkdown: String,
): Boolean =
    sessionId == command.sessionId &&
        draftRevisionNumber == command.revisionNumber &&
        questionDocument.id == command.questionDocumentId &&
        anchor.matchesVisualEvidence(command) &&
        focusMarkdown == expectedFocusMarkdown

private fun TutorVisualReviewInput.matchesVisualEvidence(
    command: PersistTutorVisualTargetEvidenceCommand,
    expectedFocusMarkdown: String,
): Boolean =
    sessionId == command.sessionId &&
        draftRevisionNumber == command.revisionNumber &&
        questionDocument.id == command.questionDocumentId &&
        anchor.matchesVisualEvidence(command) &&
        focusMarkdown == expectedFocusMarkdown

private fun TutorVisualGenerateOutput.matchesVisualEvidence(
    command: PersistTutorVisualTargetEvidenceCommand,
): Boolean =
    sessionId == command.sessionId &&
        draftRevisionNumber == command.revisionNumber &&
        questionDocumentId == command.questionDocumentId &&
        anchor.matchesVisualEvidence(command)

private fun TutorVisualReviewOutput.matchesVisualEvidence(
    command: PersistTutorVisualTargetEvidenceCommand,
): Boolean =
    sessionId == command.sessionId &&
        draftRevisionNumber == command.revisionNumber &&
        questionDocumentId == command.questionDocumentId &&
        anchor.matchesVisualEvidence(command)

private fun com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor.matchesVisualEvidence(
    command: PersistTutorVisualTargetEvidenceCommand,
): Boolean =
    surface.name == command.surfaceKind &&
        cycleOrdinal == command.cycleOrdinal &&
        turnOrdinal == command.turnOrdinal &&
        responseOrdinal == command.responseOrdinal

private const val INSERT_CONFLICT = -1L
private const val MIN_GENERATION_READY_CONFIDENCE = 0.90
private const val MIN_REVIEW_READY_CONFIDENCE = 0.75
