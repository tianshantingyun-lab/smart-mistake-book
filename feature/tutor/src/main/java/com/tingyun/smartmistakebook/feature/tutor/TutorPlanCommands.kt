package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
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
 * A plan turn may start when the single agent gate allows dispatch to the provider right now.
 * That gate is [tutorAgentChatEnabled] (location + consent + capability); it owns the decision
 * so the panel and the command layer cannot drift. Local providers never egress, so they need
 * no consent.
 */
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
        if (!tutorAgentChatEnabled(provider, sink.consentEnabled(), ModelTaskKind.TUTOR_PLAN)) return
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
