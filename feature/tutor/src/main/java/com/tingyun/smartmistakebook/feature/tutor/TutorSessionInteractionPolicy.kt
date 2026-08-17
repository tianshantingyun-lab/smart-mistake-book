package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorSendAction
import com.tingyun.smartmistakebook.core.domain.TutorSendPhase
import com.tingyun.smartmistakebook.core.domain.TutorSendState
import com.tingyun.smartmistakebook.core.domain.TutorTurnSendStateMachine
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorMoveType

internal const val TUTOR_CHOICE_SAVE_ERROR = "这个选择暂时没有保存，请重试后再继续。"
internal const val TUTOR_MOVE_SAVE_ERROR = "下一种讲法没有启动，请再试一次。"

internal fun tutorChoiceSubmissionCanStart(
    hasPlanOutput: Boolean,
    hasDiagnosticItem: Boolean,
    hasEvaluation: Boolean,
    interactionBusy: Boolean,
): Boolean = hasPlanOutput && hasDiagnosticItem && hasEvaluation && !interactionBusy

internal fun tutorMoveCanStart(
    interactionBusy: Boolean,
    awaitingAuthorization: Boolean,
    hasExecutableProvider: Boolean,
): Boolean = !interactionBusy && !awaitingAuthorization && hasExecutableProvider

internal fun tutorRestartCanStart(
    awaitingAuthorization: Boolean,
    hasExecutableProvider: Boolean,
    hasConversationMemory: Boolean,
): Boolean = !awaitingAuthorization && hasExecutableProvider && hasConversationMemory

internal fun tutorPlanAttemptCount(
    matchingTaskCount: Int,
): Int = matchingTaskCount.coerceAtLeast(0)


internal fun tutorExternalPlanApprovedAt(
    leaseApprovedAtEpochMillis: Long?,
    autoStartApprovedAtEpochMillis: Long?,
): Long? = leaseApprovedAtEpochMillis ?: autoStartApprovedAtEpochMillis

internal const val TUTOR_RESPOND_IN_PROGRESS_TITLE = "这条消息还在处理中"
internal const val TUTOR_RESPOND_IN_PROGRESS_MESSAGE = "这条消息还在处理中，请稍后重试。"
internal const val TUTOR_RESPOND_LIMIT_TITLE = "发送次数已到上限"
internal const val TUTOR_RESPOND_LIMIT_MESSAGE = "这条消息的发送次数已到上限，请稍后再试。"
internal const val TUTOR_RESPOND_VALIDATION_TITLE = "消息格式需要调整"
internal const val TUTOR_RESPOND_VALIDATION_MESSAGE = "这条消息包含暂时无法发送的字符，请调整后再试。"
internal const val TUTOR_RESPOND_NETWORK_TITLE = "这条消息还没有发出"
internal const val TUTOR_RESPOND_NETWORK_MESSAGE = "这条消息还没有发出，请重试。"

internal fun tutorRespondProviderCanExecute(
    provider: ProviderCapabilitySnapshot?,
): Boolean = provider != null &&
    provider.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
    provider.supports(ModelTaskKind.TUTOR_RESPOND)

internal fun tutorRespondCollectCanStart(
    provider: ProviderCapabilitySnapshot?,
    requestHasEgressManifest: Boolean,
    allowExternalEnvelopeForLocalRecovery: Boolean,
    respondApprovedAtEpochMillis: Long?,
    chatSubmitPending: Boolean,
): Boolean {
    if (!tutorRespondProviderCanExecute(provider)) return false
    when (provider!!.executionLocation) {
        ModelExecutionLocation.LOCAL_NO_EGRESS ->
            if (requestHasEgressManifest && !allowExternalEnvelopeForLocalRecovery) return false
        ModelExecutionLocation.EXTERNAL_PROVIDER ->
            if (respondApprovedAtEpochMillis == null) return false
        ModelExecutionLocation.UNAVAILABLE -> return false
    }
    return !chatSubmitPending
}

internal sealed interface TutorRespondSendAdvance {
    data object InProgressBudgetExhausted : TutorRespondSendAdvance
    data object DispatchLimitReached : TutorRespondSendAdvance
    data class Ready(val nextState: TutorSendState) : TutorRespondSendAdvance
}

internal fun tutorRespondSendAdvance(
    sendState: TutorSendState,
    logicalOperationId: String,
    messageId: String,
    isRetry: Boolean,
): TutorRespondSendAdvance {
    val nextState = if (isRetry) {
        TutorTurnSendStateMachine.reduce(
            sendState,
            TutorSendAction.ConsentGranted(logicalOperationId, messageId),
        )
    } else {
        val persisted = TutorTurnSendStateMachine.reduce(
            sendState,
            TutorSendAction.StudentMessagePersisted(logicalOperationId, messageId),
        )
        if (persisted.phase == TutorSendPhase.PERMANENT_FAILURE) {
            return TutorRespondSendAdvance.InProgressBudgetExhausted
        }
        TutorTurnSendStateMachine.reduce(
            persisted,
            TutorSendAction.ConsentGranted(logicalOperationId, messageId),
        )
    }
    return if (nextState.phase == TutorSendPhase.DISPATCHING) {
        TutorRespondSendAdvance.Ready(nextState)
    } else {
        TutorRespondSendAdvance.DispatchLimitReached
    }
}

internal fun tutorRespondNewPendingAllowed(
    pending: PendingTutorEgressAction?,
    message: String,
    requestedMove: TutorMoveType?,
    clearDraftOnPersist: Boolean,
): Boolean = when (pending) {
    is PendingTutorEgressAction.Plan,
    is PendingTutorEgressAction.RetryResponse -> false
    is PendingTutorEgressAction.NewResponse ->
        pending.message == message &&
            pending.requestedMove == requestedMove &&
            pending.clearDraftOnPersist == clearDraftOnPersist
    null -> true
}

internal fun tutorRespondRetryPendingAllowed(
    pending: PendingTutorEgressAction?,
    requestId: String,
): Boolean = when (pending) {
    null -> true
    is PendingTutorEgressAction.RetryResponse -> pending.requestId == requestId
    is PendingTutorEgressAction.Plan,
    is PendingTutorEgressAction.NewResponse -> false
}

internal fun tutorRespondExecuteCanStart(
    pendingAllowed: Boolean,
    hasPlanOutput: Boolean,
    providerCanExecute: Boolean,
    messageBlank: Boolean,
    chatSending: Boolean,
): Boolean = pendingAllowed && hasPlanOutput && providerCanExecute && !messageBlank && !chatSending

internal fun tutorRespondExternalApprovedAt(
    location: ModelExecutionLocation,
    leaseApprovedAtEpochMillis: Long?,
    occurredAtEpochMillis: Long,
): Long? = when (location) {
    ModelExecutionLocation.EXTERNAL_PROVIDER -> leaseApprovedAtEpochMillis
    ModelExecutionLocation.LOCAL_NO_EGRESS -> occurredAtEpochMillis
    ModelExecutionLocation.UNAVAILABLE -> null
}
