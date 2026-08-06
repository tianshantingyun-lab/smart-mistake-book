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
        val selectedChoiceId: String? = null,
        val choiceSourceRequestId: String? = null,
        val conversationAuthorityFingerprint: String? = null,
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

internal fun PendingTutorEgressState.clearVisualRetryIfIdentityChanged(
    expectedRetry: PendingTutorEgressAction.RetryVisual,
    provider: ProviderCapabilitySnapshot?,
    semanticRequestId: String?,
): PendingTutorEgressState =
    if (
        action == expectedRetry &&
        (provider == null ||
            semanticRequestId == null ||
            !expectedRetry.matches(provider, semanticRequestId))
    ) {
        PendingTutorEgressState()
    } else {
        this
    }

internal fun PendingTutorEgressState.clearVisualRetryIfExecutionBlocked(
    expectedRetry: PendingTutorEgressAction.RetryVisual,
    executionAvailable: Boolean,
): PendingTutorEgressState =
    if (!executionAvailable && action == expectedRetry) {
        PendingTutorEgressState()
    } else {
        this
    }

internal fun PendingTutorEgressState.withoutVisualRetry(): PendingTutorEgressState =
    if (action is PendingTutorEgressAction.RetryVisual) {
        PendingTutorEgressState()
    } else {
        this
    }

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
private const val SELECTED_CHOICE_ID = "selected_choice_id"
private const val CHOICE_SOURCE_REQUEST_ID = "choice_source_request_id"
private const val CONVERSATION_AUTHORITY_FINGERPRINT = "conversation_authority_fingerprint"
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
    save = { state -> PendingTutorEgressStateCodec.encode(state).toBundle() },
    restore = { bundle -> PendingTutorEgressStateCodec.decode(bundle.toPrimitivePayload()) },
)

internal object PendingTutorEgressStateCodec {
    fun encode(state: PendingTutorEgressState): Map<String, Any?> = buildMap {
        when (val action = state.action) {
            null -> put(KIND, NONE)
            is PendingTutorEgressAction.Plan -> savePlan(action)
            is PendingTutorEgressAction.NewResponse -> {
                put(KIND, NEW_RESPONSE)
                put(MESSAGE, action.message)
                put(SELECTED_CHOICE_ID, action.selectedChoiceId)
                put(CHOICE_SOURCE_REQUEST_ID, action.choiceSourceRequestId)
                put(
                    CONVERSATION_AUTHORITY_FINGERPRINT,
                    action.conversationAuthorityFingerprint,
                )
                put(REQUESTED_MOVE, action.requestedMove?.name)
                put(CLEAR_DRAFT, action.clearDraftOnPersist)
            }
            is PendingTutorEgressAction.RetryResponse -> {
                put(KIND, RETRY_RESPONSE)
                put(REQUEST_ID, action.requestId)
            }
            is PendingTutorEgressAction.RetryVisual -> saveRetryVisual(action)
        }
    }

    fun decode(payload: Map<String, Any?>): PendingTutorEgressState =
        PendingTutorEgressState(payload.restoreAction())
}

private fun MutableMap<String, Any?>.savePlan(action: PendingTutorEgressAction.Plan) {
    put(KIND, PLAN)
    put(CYCLE, action.cycleOrdinal)
    put(MESSAGES, action.priorCycleStudentMessages.toList())
    put(TURN_COUNT, action.priorTurns.size)
    action.priorConversationMemory?.let { memory ->
        put(HAS_MEMORY, true)
        put(MEMORY_COMPLETED_CYCLES, memory.completedCycleCount)
        put(MEMORY_ANSWERED_TURNS, memory.answeredTurnCount)
        put(MEMORY_CORRECT_CHOICES, memory.correctChoiceCount)
        put(MEMORY_LAST_FEEDBACK, memory.lastFeedbackMarkdown)
        put(MEMORY_LAST_MOVE, memory.lastRequestedMove?.name)
        put(MEMORY_SOLUTION_REVEALED, memory.solutionWasRevealed)
    }
    action.priorTurns.forEachIndexed { index, turn ->
        put(turnKey(index, "ordinal"), turn.turnOrdinal)
        put(turnKey(index, "stem"), turn.diagnosticStemMarkdown)
        put(turnKey(index, "choice"), turn.selectedChoiceMarkdown)
        put(turnKey(index, "correct"), turn.selectionWasCorrect)
        put(turnKey(index, "feedback"), turn.feedbackMarkdown)
        put(turnKey(index, "move"), turn.requestedMove.name)
    }
}

private fun MutableMap<String, Any?>.saveRetryVisual(
    action: PendingTutorEgressAction.RetryVisual,
) {
    put(KIND, RETRY_VISUAL)
    put(SURFACE, action.anchor.surface.name)
    put(CYCLE, action.anchor.cycleOrdinal)
    put(TURN, action.anchor.turnOrdinal)
    action.anchor.responseOrdinal?.let { put(RESPONSE, it) }
    put(TASK_KIND, action.taskKind.name)
    put(REQUEST_ID, action.failedRequestId)
    put(SEMANTIC_REQUEST_ID, action.semanticRequestId)
    put(PROVIDER_ID, action.providerId)
    put(MODEL_ID, action.modelId)
    put(PROVIDER_CONFIGURATION_VERSION, action.providerConfigurationVersion)
}

private fun Map<String, Any?>.restoreAction(): PendingTutorEgressAction? = runCatching {
    when (optionalString(KIND)) {
        NONE -> null
        PLAN -> restorePlan()
        NEW_RESPONSE -> restoreNewResponse()
        RETRY_RESPONSE -> PendingTutorEgressAction.RetryResponse(
            requestId = requiredString(REQUEST_ID).also { require(it.isNotBlank()) },
        )
        RETRY_VISUAL -> restoreRetryVisual()
        else -> error("Unknown pending tutor action")
    }
}.getOrNull()

private fun Map<String, Any?>.restoreRetryVisual(): PendingTutorEgressAction.RetryVisual {
    return PendingTutorEgressAction.RetryVisual(
        anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.valueOf(requiredString(SURFACE)),
            cycleOrdinal = requiredInt(CYCLE),
            turnOrdinal = requiredInt(TURN),
            responseOrdinal = optionalInt(RESPONSE),
        ),
        taskKind = ModelTaskKind.valueOf(requiredString(TASK_KIND)),
        failedRequestId = optionalString(REQUEST_ID),
        semanticRequestId = requiredString(SEMANTIC_REQUEST_ID),
        providerId = requiredString(PROVIDER_ID),
        modelId = requiredString(MODEL_ID),
        providerConfigurationVersion = requiredString(PROVIDER_CONFIGURATION_VERSION),
    ).restoredWithoutAuthorization()
}

private fun Map<String, Any?>.restoreNewResponse(): PendingTutorEgressAction.NewResponse {
    val message = requiredString(MESSAGE)
    require(message.isNotBlank())
    return PendingTutorEgressAction.NewResponse(
        message = message,
        selectedChoiceId = optionalString(SELECTED_CHOICE_ID),
        choiceSourceRequestId = optionalString(CHOICE_SOURCE_REQUEST_ID),
        conversationAuthorityFingerprint =
        optionalString(CONVERSATION_AUTHORITY_FINGERPRINT),
        requestedMove = optionalString(REQUESTED_MOVE)?.let(TutorMoveType::valueOf),
        clearDraftOnPersist = optionalBoolean(CLEAR_DRAFT) ?: false,
    )
}

private fun Map<String, Any?>.restorePlan(): PendingTutorEgressAction.Plan {
    val cycleOrdinal = requiredInt(CYCLE)
    require(cycleOrdinal > 0)
    val priorMessages = requiredStringList(MESSAGES)
    require(priorMessages.size <= TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGES)
    val turnCount = requiredInt(TURN_COUNT)
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

private fun Map<String, Any?>.restoreMemory(): TutorConversationMemory? {
    if (optionalBoolean(HAS_MEMORY) != true) return null
    return TutorConversationMemory(
        completedCycleCount = requiredInt(MEMORY_COMPLETED_CYCLES),
        answeredTurnCount = requiredInt(MEMORY_ANSWERED_TURNS),
        correctChoiceCount = requiredInt(MEMORY_CORRECT_CHOICES),
        lastFeedbackMarkdown = optionalString(MEMORY_LAST_FEEDBACK),
        lastRequestedMove = optionalString(MEMORY_LAST_MOVE)?.let(TutorMoveType::valueOf),
        solutionWasRevealed = optionalBoolean(MEMORY_SOLUTION_REVEALED) ?: false,
    )
}

private fun Map<String, Any?>.restoreTurn(index: Int): TutorTurnHistoryEntry {
    return TutorTurnHistoryEntry(
        turnOrdinal = requiredInt(turnKey(index, "ordinal")),
        diagnosticStemMarkdown = requiredString(turnKey(index, "stem")),
        selectedChoiceMarkdown = requiredString(turnKey(index, "choice")),
        selectionWasCorrect = requireNotNull(optionalBoolean(turnKey(index, "correct"))),
        feedbackMarkdown = requiredString(turnKey(index, "feedback")),
        requestedMove = TutorMoveType.valueOf(requiredString(turnKey(index, "move"))),
    )
}

private fun Map<String, Any?>.requiredString(key: String): String =
    requireNotNull(optionalString(key))

private fun Map<String, Any?>.optionalString(key: String): String? {
    val value = this[key]
    require(value == null || value is String)
    return value
}

private fun Map<String, Any?>.requiredInt(key: String): Int =
    requireNotNull(optionalInt(key))

private fun Map<String, Any?>.optionalInt(key: String): Int? {
    val value = this[key] ?: return null
    require(value is Int)
    return value
}

private fun Map<String, Any?>.optionalBoolean(key: String): Boolean? {
    val value = this[key] ?: return null
    require(value is Boolean)
    return value
}

private fun Map<String, Any?>.requiredStringList(key: String): List<String> {
    val value = this[key]
    require(value is List<*> && value.all { it is String })
    return value.filterIsInstance<String>()
}

private fun Map<String, Any?>.toBundle(): Bundle = Bundle().apply {
    this@toBundle.forEach { (key, value) ->
        when (value) {
            null -> putString(key, null)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            is List<*> -> {
                require(value.all { it is String })
                putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
            }
            else -> error("Unsupported pending tutor payload value")
        }
    }
}

@Suppress("DEPRECATION")
private fun Bundle.toPrimitivePayload(): Map<String, Any?> =
    keySet().associateWith(::get)
