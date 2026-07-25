package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import kotlinx.coroutines.flow.Flow

data class TutorTurnResponse(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val diagnosticStemMarkdown: String?,
    val selectedChoiceId: String?,
    val selectedChoiceMarkdown: String?,
    val selectionWasCorrect: Boolean?,
    val feedbackMarkdown: String?,
    val requestedMove: TutorMoveType? = null,
    val solutionRevealed: Boolean = false,
    val submittedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val choiceSubmittedAtEpochMillis: Long? =
        submittedAtEpochMillis.takeIf { diagnosticStemMarkdown != null },
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        val choicePayload = listOf(
            diagnosticStemMarkdown,
            selectedChoiceId,
            selectedChoiceMarkdown,
            selectionWasCorrect,
            feedbackMarkdown,
        )
        require(choicePayload.all { it == null } || choicePayload.none { it == null }) {
            "Tutor choice fields must be either all present or all absent"
        }
        if (hasChoicePayload) {
            require(!diagnosticStemMarkdown.isNullOrBlank())
            require(!selectedChoiceId.isNullOrBlank() && !selectedChoiceMarkdown.isNullOrBlank())
            require(!feedbackMarkdown.isNullOrBlank())
            require(choiceSubmittedAtEpochMillis != null && choiceSubmittedAtEpochMillis >= 0)
        } else {
            require(choiceSubmittedAtEpochMillis == null)
            require(requestedMove != null || solutionRevealed) {
                "A tutor action-only response must persist a move or solution reveal"
            }
        }
        require(requestedMove != TutorMoveType.REVEAL_SOLUTION)
        require(submittedAtEpochMillis >= 0 && updatedAtEpochMillis >= submittedAtEpochMillis)
    }

    val hasChoicePayload: Boolean
        get() = diagnosticStemMarkdown != null
}

enum class TutorAnswerExposureSurfaceKind {
    PLAN_SOLUTION,
    RESPOND_REPLY,
}

/** Exact identity of one durably recorded, bottom-visible tutor answer surface. */
data class TutorAnswerExposureKey(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val surfaceKind: TutorAnswerExposureSurfaceKind,
    val modelTaskRequestId: String,
    val responseOrdinal: Int? = null,
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(modelTaskRequestId.isNotBlank())
        require(
            surfaceKind == TutorAnswerExposureSurfaceKind.PLAN_SOLUTION &&
                responseOrdinal == null ||
                surfaceKind == TutorAnswerExposureSurfaceKind.RESPOND_REPLY &&
                responseOrdinal != null && responseOrdinal > 0,
        ) { "Tutor answer exposure surface identity is inconsistent" }
    }
}

fun TutorTurnResponse.hasExposedPlanSolution(
    answerExposureKeys: Set<TutorAnswerExposureKey>,
): Boolean = solutionRevealed && answerExposureKeys.any { key ->
    key.surfaceKind == TutorAnswerExposureSurfaceKind.PLAN_SOLUTION &&
        key.sessionId == sessionId && key.questionDocumentId == questionDocumentId &&
        key.revisionNumber == revisionNumber && key.cycleOrdinal == cycleOrdinal &&
        key.turnOrdinal == turnOrdinal
}

private fun TutorAnswerExposureKey.matchesTutorConversation(
    response: TutorTurnResponse,
): Boolean = sessionId == response.sessionId &&
    questionDocumentId == response.questionDocumentId &&
    revisionNumber == response.revisionNumber

data class RecordTutorChoiceCommand(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val diagnosticStemMarkdown: String,
    val selectedChoiceId: String,
    val selectedChoiceMarkdown: String,
    val selectionWasCorrect: Boolean,
    val feedbackMarkdown: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(diagnosticStemMarkdown.isNotBlank())
        require(selectedChoiceId.isNotBlank() && selectedChoiceMarkdown.isNotBlank())
        require(feedbackMarkdown.isNotBlank() && occurredAtEpochMillis >= 0)
    }
}

data class RecordTutorMoveCommand(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val requestedMove: TutorMoveType,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(requestedMove != TutorMoveType.REVEAL_SOLUTION)
        require(occurredAtEpochMillis >= 0)
    }
}

data class RevealTutorSolutionCommand(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(occurredAtEpochMillis >= 0)
    }
}

data class RecordTutorSolutionExposureCommand(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val surfaceKind: TutorAnswerExposureSurfaceKind,
    val modelTaskRequestId: String,
    val responseOrdinal: Int? = null,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && questionDocumentId.isNotBlank())
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0)
        require(modelTaskRequestId.isNotBlank())
        require(
            surfaceKind == TutorAnswerExposureSurfaceKind.PLAN_SOLUTION &&
                responseOrdinal == null ||
                surfaceKind == TutorAnswerExposureSurfaceKind.RESPOND_REPLY &&
                responseOrdinal != null && responseOrdinal > 0,
        ) { "Tutor answer exposure surface identity is inconsistent" }
        require(occurredAtEpochMillis >= 0)
    }
}

fun RecordTutorSolutionExposureCommand.toTutorAnswerExposureKey() = TutorAnswerExposureKey(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    surfaceKind = surfaceKind,
    modelTaskRequestId = modelTaskRequestId,
    responseOrdinal = responseOrdinal,
)

data class TutorSessionProblemAnchor(
    val sessionId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val anchoredAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank())
        require(problemRevisionId.isNotBlank() && practiceUnitId.isNotBlank())
        require(anchoredAtEpochMillis >= 0)
    }
}

/** Durable local writer for student-authored tutor choices and direct teaching actions. */
interface TutorInteractionRepository {
    fun observe(sessionId: String): Flow<List<TutorTurnResponse>>

    suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse

    suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse

    suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse

    suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand)

    /** Missing support and read failures must remain fail-closed at the caller. */
    suspend fun hasAnswerExposure(key: TutorAnswerExposureKey): Boolean = false

    /**
     * Returns only exact, durably recorded exposure identities.
     *
     * Implementations backed by a database should override this to avoid one query per timeline item.
     */
    suspend fun findRecordedAnswerExposures(
        keys: Set<TutorAnswerExposureKey>,
    ): Set<TutorAnswerExposureKey> = keys.filterTo(mutableSetOf()) { key ->
        hasAnswerExposure(key)
    }

    suspend fun anchorSession(anchor: TutorSessionProblemAnchor) = Unit
}

fun List<TutorTurnResponse>.toContiguousTutorHistory(): List<TutorTurnHistoryEntry> = buildList {
    this@toContiguousTutorHistory
        .asSequence()
        .filter(TutorTurnResponse::hasChoicePayload)
        .sortedBy(TutorTurnResponse::turnOrdinal)
        .forEach { response ->
            val move = response.requestedMove ?: return@forEach
            if (response.turnOrdinal != size + 1) return@buildList
            add(
                TutorTurnHistoryEntry(
                    turnOrdinal = response.turnOrdinal,
                    diagnosticStemMarkdown = requireNotNull(response.diagnosticStemMarkdown),
                    selectedChoiceMarkdown = requireNotNull(response.selectedChoiceMarkdown),
                    selectionWasCorrect = requireNotNull(response.selectionWasCorrect),
                    feedbackMarkdown = requireNotNull(response.feedbackMarkdown),
                    requestedMove = move,
                ),
            )
        }
}

fun List<TutorTurnResponse>.toTutorConversationMemory(
    answerExposureKeys: Set<TutorAnswerExposureKey>,
): TutorConversationMemory? {
    val ordered = sortedWith(
        compareBy(TutorTurnResponse::cycleOrdinal).thenBy(TutorTurnResponse::turnOrdinal),
    )
    val exposedRespondReplies = answerExposureKeys.filter { key ->
        key.surfaceKind == TutorAnswerExposureSurfaceKind.RESPOND_REPLY &&
            (ordered.isEmpty() || ordered.any { response ->
                key.matchesTutorConversation(response)
            })
    }
    val semanticResponses = ordered.filter { response ->
        val answerWasExposed = response.hasExposedPlanSolution(answerExposureKeys)
        response.hasChoicePayload ||
            response.requestedMove != null ||
            answerWasExposed
    }
    if (semanticResponses.isEmpty() && exposedRespondReplies.isEmpty()) return null
    val choiceResponses = semanticResponses.filter(TutorTurnResponse::hasChoicePayload)
    val latestChoice = choiceResponses.lastOrNull()
    return TutorConversationMemory(
        completedCycleCount = maxOf(
            semanticResponses.lastOrNull()?.cycleOrdinal ?: 0,
            exposedRespondReplies.maxOfOrNull(TutorAnswerExposureKey::cycleOrdinal) ?: 0,
        ),
        answeredTurnCount = choiceResponses.size,
        correctChoiceCount = choiceResponses.count { it.selectionWasCorrect == true },
        lastFeedbackMarkdown = latestChoice?.feedbackMarkdown,
        lastRequestedMove = semanticResponses.asReversed()
            .firstNotNullOfOrNull(TutorTurnResponse::requestedMove),
        solutionWasRevealed = exposedRespondReplies.isNotEmpty() ||
            semanticResponses.any { response ->
                response.hasExposedPlanSolution(answerExposureKeys)
            },
    )
}
