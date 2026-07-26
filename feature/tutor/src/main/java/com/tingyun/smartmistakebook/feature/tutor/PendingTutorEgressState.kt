package com.tingyun.smartmistakebook.feature.tutor

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface

internal sealed interface PendingTutorEgressAction {
    data class Plan(
        val cycleOrdinal: Int,
        val priorConversationMemory: TutorConversationMemory?,
        val priorCycleStudentMessages: List<String>,
        val priorTurns: List<TutorTurnHistoryEntry>,
    ) : PendingTutorEgressAction

    data class NewResponse(
        val message: String,
        val requestedMove: TutorMoveType?,
        val clearDraftOnPersist: Boolean,
    ) : PendingTutorEgressAction

    data class RetryResponse(
        val requestId: String,
    ) : PendingTutorEgressAction

    data class RetryVisual(
        val anchor: TutorVisualTurnAnchor,
        val taskKind: ModelTaskKind,
        val failedRequestId: String?,
        val semanticRequestId: String,
        val providerId: String,
        val modelId: String,
        val providerConfigurationVersion: String,
        val approvedAtEpochMillis: Long? = null,
    ) : PendingTutorEgressAction {
        init {
            require(
                taskKind == ModelTaskKind.TUTOR_VISUAL_GENERATE ||
                    taskKind == ModelTaskKind.TUTOR_VISUAL_REVIEW,
            )
            require(failedRequestId == null || failedRequestId.isNotBlank())
            require(semanticRequestId.isNotBlank())
            require(providerId.isNotBlank() && modelId.isNotBlank())
            require(providerConfigurationVersion.isNotBlank())
            require(approvedAtEpochMillis == null || approvedAtEpochMillis >= 0)
        }

        fun matches(
            provider: ProviderCapabilitySnapshot,
            currentSemanticRequestId: String,
        ): Boolean =
            semanticRequestId == currentSemanticRequestId &&
                providerId == provider.providerId &&
                modelId == provider.modelId &&
                providerConfigurationVersion == provider.providerConfigurationVersion

        fun restoredWithoutAuthorization(): RetryVisual =
            copy(approvedAtEpochMillis = null)

        fun approvedAtFor(
            provider: ProviderCapabilitySnapshot,
            currentSemanticRequestId: String,
        ): Long? = approvedAtEpochMillis?.takeIf {
            matches(provider, currentSemanticRequestId)
        }

        fun matchesFailedTask(task: ModelTaskSnapshot?): Boolean =
            if (failedRequestId == null) {
                task == null
            } else {
                task?.request?.requestId == failedRequestId &&
                    task.request.input.kind == taskKind
            }
    }
}

internal data class PendingTutorEgressState(
    val action: PendingTutorEgressAction? = null,
)

internal fun PendingTutorEgressAction?.awaitsResponseAuthorization(): Boolean =
    this is PendingTutorEgressAction.NewResponse ||
        this is PendingTutorEgressAction.RetryResponse

private const val KIND = "kind"
private const val NONE = "none"
private const val PLAN = "plan"
private const val NEW_RESPONSE = "new_response"
private const val RETRY_RESPONSE = "retry_response"
private const val RETRY_VISUAL = "retry_visual"
private const val CYCLE = "cycle"
private const val MESSAGES = "messages"
private const val TURN_COUNT = "turn_count"
private const val HAS_MEMORY = "has_memory"
private const val MEMORY_COMPLETED_CYCLES = "memory_completed_cycles"
private const val MEMORY_ANSWERED_TURNS = "memory_answered_turns"
private const val MEMORY_CORRECT_CHOICES = "memory_correct_choices"
private const val MEMORY_LAST_FEEDBACK = "memory_last_feedback"
private const val MEMORY_LAST_MOVE = "memory_last_move"
private const val MEMORY_SOLUTION_REVEALED = "memory_solution_revealed"
private const val MESSAGE = "message"
private const val REQUESTED_MOVE = "requested_move"
private const val CLEAR_DRAFT = "clear_draft"
private const val REQUEST_ID = "request_id"
private const val SURFACE = "surface"
private const val TURN = "turn"
private const val RESPONSE = "response"
private const val TASK_KIND = "task_kind"
private const val SEMANTIC_REQUEST_ID = "semantic_request_id"
private const val PROVIDER_ID = "provider_id"
private const val MODEL_ID = "model_id"
private const val PROVIDER_CONFIGURATION_VERSION = "provider_configuration_version"

private fun turnKey(index: Int, field: String) = "turn_${index}_$field"

internal val pendingTutorEgressStateSaver = Saver<PendingTutorEgressState, Bundle>(
    save = { state ->
        Bundle().apply {
            when (val action = state.action) {
                null -> putString(KIND, NONE)
                is PendingTutorEgressAction.Plan -> savePlan(action)
                is PendingTutorEgressAction.NewResponse -> {
                    putString(KIND, NEW_RESPONSE)
                    putString(MESSAGE, action.message)
                    putString(REQUESTED_MOVE, action.requestedMove?.name)
                    putBoolean(CLEAR_DRAFT, action.clearDraftOnPersist)
                }
                is PendingTutorEgressAction.RetryResponse -> {
                    putString(KIND, RETRY_RESPONSE)
                    putString(REQUEST_ID, action.requestId)
                }
                is PendingTutorEgressAction.RetryVisual -> saveRetryVisual(action)
            }
        }
    },
    restore = { bundle -> PendingTutorEgressState(bundle.restoreAction()) },
)

private fun Bundle.savePlan(action: PendingTutorEgressAction.Plan) {
    putString(KIND, PLAN)
    putInt(CYCLE, action.cycleOrdinal)
    putStringArrayList(MESSAGES, ArrayList(action.priorCycleStudentMessages))
    putInt(TURN_COUNT, action.priorTurns.size)
    action.priorConversationMemory?.let { memory ->
        putBoolean(HAS_MEMORY, true)
        putInt(MEMORY_COMPLETED_CYCLES, memory.completedCycleCount)
        putInt(MEMORY_ANSWERED_TURNS, memory.answeredTurnCount)
        putInt(MEMORY_CORRECT_CHOICES, memory.correctChoiceCount)
        putString(MEMORY_LAST_FEEDBACK, memory.lastFeedbackMarkdown)
        putString(MEMORY_LAST_MOVE, memory.lastRequestedMove?.name)
        putBoolean(MEMORY_SOLUTION_REVEALED, memory.solutionWasRevealed)
    }
    action.priorTurns.forEachIndexed { index, turn ->
        putInt(turnKey(index, "ordinal"), turn.turnOrdinal)
        putString(turnKey(index, "stem"), turn.diagnosticStemMarkdown)
        putString(turnKey(index, "choice"), turn.selectedChoiceMarkdown)
        putBoolean(turnKey(index, "correct"), turn.selectionWasCorrect)
        putString(turnKey(index, "feedback"), turn.feedbackMarkdown)
        putString(turnKey(index, "move"), turn.requestedMove.name)
    }
}

private fun Bundle.saveRetryVisual(action: PendingTutorEgressAction.RetryVisual) {
    putString(KIND, RETRY_VISUAL)
    putString(SURFACE, action.anchor.surface.name)
    putInt(CYCLE, action.anchor.cycleOrdinal)
    putInt(TURN, action.anchor.turnOrdinal)
    action.anchor.responseOrdinal?.let { putInt(RESPONSE, it) }
    putString(TASK_KIND, action.taskKind.name)
    putString(REQUEST_ID, action.failedRequestId)
    putString(SEMANTIC_REQUEST_ID, action.semanticRequestId)
    putString(PROVIDER_ID, action.providerId)
    putString(MODEL_ID, action.modelId)
    putString(PROVIDER_CONFIGURATION_VERSION, action.providerConfigurationVersion)
}

private fun Bundle.restoreAction(): PendingTutorEgressAction? = runCatching {
    when (getString(KIND)) {
        NONE -> null
        PLAN -> restorePlan()
        NEW_RESPONSE -> restoreNewResponse()
        RETRY_RESPONSE -> PendingTutorEgressAction.RetryResponse(
            requestId = requireNotNull(getString(REQUEST_ID)).also { require(it.isNotBlank()) },
        )
        RETRY_VISUAL -> restoreRetryVisual()
        else -> error("Unknown pending tutor action")
    }
}.getOrNull()

private fun Bundle.restoreRetryVisual(): PendingTutorEgressAction.RetryVisual {
    require(containsKey(CYCLE) && containsKey(TURN))
    return PendingTutorEgressAction.RetryVisual(
        anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.valueOf(requireNotNull(getString(SURFACE))),
            cycleOrdinal = getInt(CYCLE),
            turnOrdinal = getInt(TURN),
            responseOrdinal = getInt(RESPONSE).takeIf { containsKey(RESPONSE) },
        ),
        taskKind = ModelTaskKind.valueOf(requireNotNull(getString(TASK_KIND))),
        failedRequestId = getString(REQUEST_ID),
        semanticRequestId = requireNotNull(getString(SEMANTIC_REQUEST_ID)),
        providerId = requireNotNull(getString(PROVIDER_ID)),
        modelId = requireNotNull(getString(MODEL_ID)),
        providerConfigurationVersion =
            requireNotNull(getString(PROVIDER_CONFIGURATION_VERSION)),
    ).restoredWithoutAuthorization()
}

private fun Bundle.restoreNewResponse(): PendingTutorEgressAction.NewResponse {
    val message = requireNotNull(getString(MESSAGE))
    require(message.isNotBlank())
    return PendingTutorEgressAction.NewResponse(
        message = message,
        requestedMove = getString(REQUESTED_MOVE)?.let(TutorMoveType::valueOf),
        clearDraftOnPersist = getBoolean(CLEAR_DRAFT),
    )
}

private fun Bundle.restorePlan(): PendingTutorEgressAction.Plan {
    require(containsKey(CYCLE) && containsKey(MESSAGES) && containsKey(TURN_COUNT))
    val cycleOrdinal = getInt(CYCLE)
    require(cycleOrdinal > 0)
    val priorMessages = getStringArrayList(MESSAGES)?.toList().orEmpty()
    require(priorMessages.size <= TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGES)
    val turnCount = getInt(TURN_COUNT)
    require(turnCount in 0 until TutorPlanInput.MAX_TURNS)
    val priorMemory = restoreMemory()
    val priorTurns = (0 until turnCount).map(::restoreTurn)
    return PendingTutorEgressAction.Plan(
        cycleOrdinal = cycleOrdinal,
        priorConversationMemory = priorMemory,
        priorCycleStudentMessages = priorMessages,
        priorTurns = priorTurns,
    )
}

private fun Bundle.restoreMemory(): TutorConversationMemory? {
    if (!getBoolean(HAS_MEMORY)) return null
    require(
        containsKey(MEMORY_COMPLETED_CYCLES) &&
            containsKey(MEMORY_ANSWERED_TURNS) &&
            containsKey(MEMORY_CORRECT_CHOICES),
    )
    return TutorConversationMemory(
        completedCycleCount = getInt(MEMORY_COMPLETED_CYCLES),
        answeredTurnCount = getInt(MEMORY_ANSWERED_TURNS),
        correctChoiceCount = getInt(MEMORY_CORRECT_CHOICES),
        lastFeedbackMarkdown = getString(MEMORY_LAST_FEEDBACK),
        lastRequestedMove = getString(MEMORY_LAST_MOVE)?.let(TutorMoveType::valueOf),
        solutionWasRevealed = getBoolean(MEMORY_SOLUTION_REVEALED),
    )
}

private fun Bundle.restoreTurn(index: Int): TutorTurnHistoryEntry {
    require(containsKey(turnKey(index, "ordinal")) && containsKey(turnKey(index, "correct")))
    return TutorTurnHistoryEntry(
        turnOrdinal = getInt(turnKey(index, "ordinal")),
        diagnosticStemMarkdown = requireNotNull(getString(turnKey(index, "stem"))),
        selectedChoiceMarkdown = requireNotNull(getString(turnKey(index, "choice"))),
        selectionWasCorrect = getBoolean(turnKey(index, "correct")),
        feedbackMarkdown = requireNotNull(getString(turnKey(index, "feedback"))),
        requestedMove = TutorMoveType.valueOf(requireNotNull(getString(turnKey(index, "move")))),
    )
}
