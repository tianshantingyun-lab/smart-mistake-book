package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A plan turn may start when the provider can execute the kind AND, for an external
 * provider, global agent consent is ON. Local-only providers never egress, so they need
 * no consent. A configured external provider with consent OFF fails closed at the UI
 * (blocked-settings card), not here.
 */
internal fun tutorPlanExecuteCanStart(
    provider: ProviderCapabilitySnapshot?,
    consentEnabled: Boolean,
): Boolean {
    if (
        provider == null ||
        provider.executionLocation == ModelExecutionLocation.UNAVAILABLE ||
        !provider.supports(ModelTaskKind.TUTOR_PLAN)
    ) {
        return false
    }
    return provider.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS ||
        consentEnabled
}

internal class TutorPlanCommands(
    private val scope: CoroutineScope,
    private val sink: TutorPlanSink,
) {
    fun executeTurn(
        cycleOrdinal: Int,
        priorConversationMemory: TutorConversationMemory?,
        priorCycleStudentMessages: List<String>,
        priorTurns: List<TutorTurnHistoryEntry>,
    ) {
        val provider = sink.provider() ?: return
        if (!tutorPlanExecuteCanStart(provider, sink.consentEnabled())) return
        val question = sink.question()
        val attempt = tutorPlanAttemptCount(
            sink.planTasks().count { task ->
                val input = task.request.input as? TutorPlanInput
                input?.cycleOrdinal == cycleOrdinal &&
                    input.priorConversationMemory == priorConversationMemory &&
                    input.priorCycleStudentMessages == priorCycleStudentMessages &&
                    input.priorTurns == priorTurns
            },
        )
        val requestId = tutorPlanRequestId(
            question = question,
            provider = provider,
            attempt = attempt,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
        )
        val occurredAt = sink.clock()
        val request = buildTutorPlanRequest(
            question = question,
            profile = sink.profile(),
            provider = provider,
            requestId = requestId,
            occurredAtEpochMillis = occurredAt,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
        )
        scope.launch { sink.modelTasks.execute(request).collect() }
    }
}

internal class TutorPlanSink(
    val provider: () -> ProviderCapabilitySnapshot?,
    val consentEnabled: () -> Boolean,
    val question: () -> TutorQuestionContext,
    val profile: () -> StudyProfileOverview,
    val clock: () -> Long,
    val planTasks: () -> List<ModelTaskSnapshot>,
    val modelTasks: ModelTaskRepository,
)
