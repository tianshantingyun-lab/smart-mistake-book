package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorSendAction
import com.tingyun.smartmistakebook.core.domain.TutorSendPhase
import com.tingyun.smartmistakebook.core.domain.TutorSendState
import com.tingyun.smartmistakebook.core.domain.TutorTurnSendStateMachine
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot

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
    hasExecutableProvider: Boolean,
): Boolean = !interactionBusy && hasExecutableProvider

internal fun tutorRestartCanStart(
    hasExecutableProvider: Boolean,
    hasConversationMemory: Boolean,
): Boolean = hasExecutableProvider && hasConversationMemory

internal fun tutorPlanAttemptCount(
    matchingTaskCount: Int,
): Int = matchingTaskCount.coerceAtLeast(0)

/**
 * The single live agent gate for a tutor send surface. Under global consent a send
 * dispatches immediately when the configured external provider supports the kind and
 * (for image-bearing visual kinds) accepts images; otherwise it fails closed at the UI.
 * PLAN/RESPOND are image-optional, so a structured-only provider still runs them.
 */
internal fun tutorAgentChatEnabled(
    provider: ProviderCapabilitySnapshot?,
    consentEnabled: Boolean,
    kind: ModelTaskKind,
): Boolean {
    val candidate = provider ?: return false
    if (candidate.executionLocation != ModelExecutionLocation.EXTERNAL_PROVIDER) return false
    if (!consentEnabled) return false
    if (!candidate.supports(kind)) return false
    val kindNeedsImage = kind == ModelTaskKind.TUTOR_VISUAL_GENERATE ||
        kind == ModelTaskKind.TUTOR_VISUAL_REVIEW
    return !kindNeedsImage || candidate.supportsImageInput
}

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
    consentEnabled: Boolean,
    requestHasEgressManifest: Boolean,
    allowExternalEnvelopeForLocalRecovery: Boolean,
    chatSubmitPending: Boolean,
): Boolean {
    if (!tutorRespondProviderCanExecute(provider)) return false
    when (provider!!.executionLocation) {
        ModelExecutionLocation.LOCAL_NO_EGRESS ->
            if (requestHasEgressManifest && !allowExternalEnvelopeForLocalRecovery) return false
        // 全局同意开启才允许外发；关闭时 UI 已隐藏输入框，这里同样 fail closed。
        ModelExecutionLocation.EXTERNAL_PROVIDER ->
            if (!consentEnabled) return false
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

internal fun tutorRespondExecuteCanStart(
    hasPlanOutput: Boolean,
    providerCanExecute: Boolean,
    messageBlank: Boolean,
    chatSending: Boolean,
): Boolean = hasPlanOutput && providerCanExecute && !messageBlank && !chatSending
