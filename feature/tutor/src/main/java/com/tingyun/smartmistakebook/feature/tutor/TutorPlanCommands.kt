package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun tutorPlanExecuteCanStart(
    awaitingResponseAuthorization: Boolean,
    hasExecutableProvider: Boolean,
): Boolean = !awaitingResponseAuthorization && hasExecutableProvider

internal fun tutorPlanAutoStartApprovedAt(
    authorization: TutorAutoStartAuthorization?,
    cycleOrdinal: Int,
    priorConversationMemory: TutorConversationMemory?,
    priorCycleStudentMessages: List<String>,
    priorTurns: List<TutorTurnHistoryEntry>,
    sessionId: String,
    questionDocumentId: String,
    revisionNumber: Int,
    provider: ProviderCapabilitySnapshot,
    nowEpochMillis: Long,
): Long? = authorization
    ?.takeIf {
        cycleOrdinal == 1 &&
            priorConversationMemory == null &&
            priorCycleStudentMessages.isEmpty() &&
            priorTurns.isEmpty() &&
            it.matches(
                sessionId = sessionId,
                questionDocumentId = questionDocumentId,
                revisionNumber = revisionNumber,
                provider = provider,
                promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                nowEpochMillis = nowEpochMillis,
            )
    }
    ?.approvedAtEpochMillis

internal class TutorPlanCommands(
    private val scope: CoroutineScope,
    private val sink: TutorPlanSink,
) {
    fun executeTurn(
        cycleOrdinal: Int,
        priorConversationMemory: TutorConversationMemory?,
        priorCycleStudentMessages: List<String>,
        priorTurns: List<TutorTurnHistoryEntry>,
        oneShotAutoStartAuthorization: TutorAutoStartAuthorization? = null,
    ) {
        if (
            !tutorPlanExecuteCanStart(
                awaitingResponseAuthorization = sink.awaitingResponseAuthorization(),
                hasExecutableProvider = sink.executableProvider() != null,
            )
        ) {
            return
        }
        val provider = sink.executableProvider() ?: return
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
        val leaseApprovedAt = sink.lease()?.approvedAtFor(
            question = question,
            provider = provider,
            taskKind = ModelTaskKind.TUTOR_PLAN,
            nowEpochMillis = occurredAt,
        )
        val autoStartApprovedAt = tutorPlanAutoStartApprovedAt(
            authorization = oneShotAutoStartAuthorization,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
            sessionId = question.sessionId,
            questionDocumentId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
            provider = provider,
            nowEpochMillis = occurredAt,
        )
        val approvedAt = when (provider.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER ->
                tutorExternalPlanApprovedAt(leaseApprovedAt, autoStartApprovedAt) ?: run {
                    sink.clearLease()
                    sink.setPendingAction(
                        PendingTutorEgressAction.Plan(
                            cycleOrdinal = cycleOrdinal,
                            priorConversationMemory = priorConversationMemory,
                            priorCycleStudentMessages = priorCycleStudentMessages,
                            priorTurns = priorTurns,
                        ),
                    )
                    return
                }
            ModelExecutionLocation.LOCAL_NO_EGRESS,
            ModelExecutionLocation.UNAVAILABLE,
            -> occurredAt
        }
        val request = buildTutorPlanRequest(
            question = question,
            profile = sink.profile(),
            provider = provider,
            requestId = requestId,
            occurredAtEpochMillis = occurredAt,
            approvedAtEpochMillis = approvedAt,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
        )
        if (sink.pendingAction() is PendingTutorEgressAction.Plan) {
            sink.setPendingAction(null)
        }
        scope.launch { sink.modelTasks.execute(request).collect() }
    }
}

internal class TutorPlanSink(
    val awaitingResponseAuthorization: () -> Boolean,
    val executableProvider: () -> ProviderCapabilitySnapshot?,
    val question: () -> TutorQuestionContext,
    val profile: () -> StudyProfileOverview,
    val clock: () -> Long,
    val planTasks: () -> List<ModelTaskSnapshot>,
    val lease: () -> TutorCompositionEgressLease?,
    val pendingAction: () -> PendingTutorEgressAction?,
    val setPendingAction: (PendingTutorEgressAction?) -> Unit,
    val clearLease: () -> Unit,
    val modelTasks: ModelTaskRepository,
)
