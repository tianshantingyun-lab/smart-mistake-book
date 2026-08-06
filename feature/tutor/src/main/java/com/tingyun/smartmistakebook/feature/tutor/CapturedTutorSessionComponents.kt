package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText

@Composable
internal fun TutorStoredChoiceFeedback(
    response: TutorTurnResponse,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = JadeSoft.copy(alpha = 0.28f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "你选择了",
                color = InkSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
            SafeMarkdownText(
                markdown = requireNotNull(response.diagnosticStemMarkdown),
                style = MaterialTheme.typography.bodySmall,
            )
            SafeMarkdownText(
                markdown = requireNotNull(response.selectedChoiceMarkdown),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (response.selectionWasCorrect == true) {
                    "判断正确"
                } else {
                    "这里暴露了关键分叉"
                },
                color = if (response.selectionWasCorrect == true) JadeActive else ErrorWarm,
                style = MaterialTheme.typography.labelLarge,
            )
            SafeMarkdownText(
                markdown = requireNotNull(response.feedbackMarkdown),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun TutorDisclosureCard(
    provider: ProviderCapabilitySnapshot,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "开始讲这道题",
    actionText: String = "开始讲题",
    actionContentDescription: String = "开始讲解当前题",
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("captured_tutor_disclosure"),
        color = JadeSoft.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "会把当前题、少量同科学习记录，以及你在本题中发送的消息和已显示的讲解发给 ${provider.providerDisplayName}；需要还原题图关系时，只会再使用本题原图，不会发送其他题目。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryActionButton(
                text = actionText,
                onClick = onApprove,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("captured_tutor_start_model"),
                contentDescription = actionContentDescription,
            )
        }
    }
}

@Composable
internal fun TutorRespondDisclosureCard(
    provider: ProviderCapabilitySnapshot,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tutor_respond_disclosure"),
        color = JadeSoft.copy(alpha = 0.32f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "继续本题对话",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "会把当前题、少量同科学习记录、你发送的消息和已显示讲解发给 ${provider.providerDisplayName}；需要补充图解时，只会再使用本题原图，不会发送其他题目或完整学习记录。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            PrimaryActionButton(
                text = "继续对话",
                onClick = onApprove,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_respond_disclosure_approve"),
                contentDescription = "允许与当前模型继续本题对话",
            )
        }
    }
}

@Composable
internal fun TutorTaskContent(
    task: ModelTaskSnapshot,
    resolvedVisual: TutorVisualResolution = TutorVisualResolution.Hidden,
    visualPresentationMode: TutorVisualPresentationMode =
        TutorVisualPresentationMode.CURRENT_EXPANDED,
    visualOriginalAvailable: Boolean = false,
    response: TutorTurnResponse?,
    solutionRevealPreviewed: Boolean = false,
    awaitingContinuation: Boolean = false,
    interactionEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    splitChoiceFeedback: Boolean,
    interactionBusy: Boolean,
    interactionError: String?,
    onRetry: () -> Unit,
    onRetryVisual: () -> Unit = {},
    onVisualTargetHit: (TutorVisualHitProof) -> Unit = {},
    onSubmitChoice: (String) -> Unit,
    onDirectiveResponse: (TutorResponseMessage) -> Unit,
    onRequestHint: (() -> Unit)?,
    onContinue: (TutorMoveType) -> Unit,
    onRevealSolution: () -> Unit,
    onRestartCycle: () -> Unit,
    explanationMode: TutorExplanationMode,
    onOpenModelSettings: () -> Unit,
    onOpenVisualOriginal: () -> Unit = {},
    onReportVisualIncorrect: (String) -> Unit = {},
    solutionBottomModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val requiresModelSettings = task.failure?.code?.requiresModelSettings() == true
    when (task.status) {
        ModelTaskStatus.SUCCEEDED -> {
            val output = task.output as? TutorPlanOutput
            if (output == null) {
                TutorModelStatusCard(
                    title = "这次讲解暂时没准备好",
                    detail = "题目已经保存，可以再试一次。",
                    modifier = modifier,
                    actionLabel = "重新生成".takeIf {
                        interactionEnabled && executionMatchesCurrentProvider
                    },
                    onAction = onRetry,
                )
            } else {
                TutorTurnContent(
                    output = output,
                    ownerModelTaskRequestId = task.request.requestId,
                    modifier = modifier,
                    response = response,
                    solutionRevealPreviewed = solutionRevealPreviewed,
                    interactionEnabled = interactionEnabled,
                    splitChoiceFeedback = splitChoiceFeedback,
                    interactionBusy = interactionBusy,
                    interactionError = interactionError,
                    resolvedVisual = resolvedVisual,
                    visualPresentationMode = visualPresentationMode,
                    visualOriginalAvailable = visualOriginalAvailable,
                    onRetryVisual = onRetryVisual,
                    onOpenVisualOriginal = onOpenVisualOriginal,
                    onReportVisualIncorrect = onReportVisualIncorrect,
                    onVisualTargetHit = onVisualTargetHit,
                    onSubmitChoice = onSubmitChoice,
                    onDirectiveResponse = onDirectiveResponse,
                    onRequestHint = onRequestHint,
                    onContinue = onContinue,
                    onRevealSolution = onRevealSolution,
                    onRestartCycle = onRestartCycle,
                    explanationMode = explanationMode,
                    solutionBottomModifier = solutionBottomModifier,
                )
            }
        }
        ModelTaskStatus.PERMANENT_FAILURE,
        ModelTaskStatus.RETRYABLE_FAILURE,
        ModelTaskStatus.CANCELLED,
        -> TutorModelStatusCard(
            title = if (executionMatchesCurrentProvider) {
                "这次讲解暂时没完成"
            } else {
                "旧配置中的回复未完成"
            },
            detail = if (!executionMatchesCurrentProvider) {
                "之前的内容仍保留，可从当前配置继续这道题。"
            } else if (requiresModelSettings) {
                "模型设置需要更新，题目已经保存。"
            } else {
                "题目已经保存，可以再试一次。"
            },
            modifier = modifier,
            actionLabel = if (
                !interactionEnabled || !executionMatchesCurrentProvider ||
                (task.status != ModelTaskStatus.RETRYABLE_FAILURE && !requiresModelSettings)
            ) {
                null
            } else if (requiresModelSettings) {
                "检查模型设置"
            } else {
                "重新生成"
            },
            onAction = if (requiresModelSettings) {
                onOpenModelSettings
            } else {
                onRetry
            },
        )
        else -> if (executionMatchesCurrentProvider && awaitingContinuation) {
            TutorModelStatusCard(
                title = "讲解已暂停",
                detail = "点下面的“继续讲题”后接着完成。",
                modifier = modifier,
            )
        } else if (executionMatchesCurrentProvider) {
            TutorModelStatusCard(
                title = "正在准备这道题",
                detail = "正在整理讲解，请稍候。",
                modifier = modifier,
            )
        } else {
            TutorModelStatusCard(
                title = "旧配置中的回复未完成",
                detail = "之前的内容仍保留，可从当前配置继续这道题。",
                modifier = modifier,
            )
        }
    }
}

internal fun TutorTurnPlan.hasGuidedInteraction(): Boolean =
    when (interactionDirective) {
        is TutorInteractionDirective.Choices,
        is TutorInteractionDirective.FreeResponse,
        is TutorInteractionDirective.VisualTarget,
        -> true
        TutorInteractionDirective.Continue,
        -> false
        null -> diagnosticItem != null
    }

internal fun TutorInteractionDirective?.isEvidencePrompt(): Boolean = when (this) {
    is TutorInteractionDirective.Choices,
    is TutorInteractionDirective.FreeResponse,
    is TutorInteractionDirective.VisualTarget,
    -> true
    TutorInteractionDirective.Continue,
    null,
    -> false
}

internal fun TutorPlanOutput.isMasteryRelevantTo(input: TutorPlanInput): Boolean {
    return masteryTargetsAreRelevant(
        targetedEvidenceLabels = plan.targetedEvidenceLabels,
        teachingConstraints = input.teachingConstraints,
    )
}

internal fun String.isTutorHintRequest(): Boolean =
    this == GUIDED_HINT_MESSAGE || contains("提示", ignoreCase = true) ||
        contains("hint", ignoreCase = true)

@Composable
internal fun TutorModelStatusCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("captured_tutor_model_status"),
        color = JadeSoft.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = InkSecondary, style = MaterialTheme.typography.bodyMedium)
            actionLabel?.let { label ->
                OutlineActionChip(text = label, onClick = onAction)
            }
        }
    }
}

@Composable
internal fun TutorQuestionUnavailable(
    title: String,
    detail: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Ink, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = InkSecondary, style = MaterialTheme.typography.bodyMedium)
        OutlineActionChip(
            text = "重新读取",
            onClick = onRetry,
            modifier = Modifier.testTag("captured_tutor_retry"),
        )
    }
}

internal fun tutorSessionSaveLabel(
    isSaved: Boolean,
    saveInProgress: Boolean,
    saveFailed: Boolean,
): String = when {
    isSaved -> "已存入"
    saveInProgress -> "保存中"
    saveFailed -> "重试保存"
    else -> "存入错题本"
}

internal fun tutorSessionStatusLine(isSaved: Boolean): String = if (isSaved) {
    "已存入错题本"
} else {
    "临时题目 · 讲完后再决定是否存入"
}

internal fun tutorSessionStatusLine(session: ConfirmedTutorSession): String =
    tutorSessionStatusLine(session.disposition)

internal fun tutorSessionStatusLine(disposition: TutorSessionDisposition): String = when (
    disposition
) {
    TutorSessionDisposition.ACTIVE -> "临时题目 · 讲完后再决定是否存入"
    TutorSessionDisposition.SAVED -> "已存入错题本"
    TutorSessionDisposition.ENDED_WITHOUT_SAVE -> "本次讲题已结束 · 未存入错题本"
}

@Composable
internal fun CapturedTutorTimelinePlanItem(
    task: ModelTaskSnapshot,
    response: TutorTurnResponse?,
    isTail: Boolean,
    currentCycleOrdinal: Int,
    currentTurnOrdinal: Int,
    currentProvider: ProviderCapabilitySnapshot?,
    resolvedVisualStates: Map<TutorVisualTurnAnchor, TutorVisualResolution>,
    visualOriginalAvailable: Boolean,
    planSolutionPreviewKeys: Set<PlanSolutionPreviewKey>,
    planFreshApprovalTask: ModelTaskSnapshot?,
    pendingInteractionBlocked: Boolean,
    responseActionAwaitingAuthorization: Boolean,
    interactionBusy: Boolean,
    interactionError: String?,
    explanationMode: TutorExplanationMode,
    hintsUsed: Int,
    maxHints: Int,
    respondSupported: Boolean,
    respondAuthorized: Boolean,
    chatSending: Boolean,
    onRetry: () -> Unit,
    onRequestVisualRetry: (TutorVisualTurnAnchor) -> Unit,
    onVisualTargetHit: (TutorVisualHitProof) -> Unit,
    onSubmitChoice: (String) -> Unit,
    onDirectiveResponse: (TutorResponseMessage) -> Unit,
    onContinue: (TutorMoveType) -> Unit,
    onRevealSolution: () -> Unit,
    onRestartCycle: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenVisualOriginal: () -> Unit,
    onReportVisualIncorrect: (String) -> Unit,
    solutionBottomModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val taskInput = task.request.input as TutorPlanInput
    val planOutput = task.output as? TutorPlanOutput
    val isCurrentTurn = tutorIsCurrentTurn(
        taskCycleOrdinal = taskInput.cycleOrdinal,
        taskTurnOrdinal = taskInput.turnOrdinal,
        currentCycleOrdinal = currentCycleOrdinal,
        currentTurnOrdinal = currentTurnOrdinal,
    )
    val executionMatches = tutorPlanExecutionMatches(currentProvider, task)
    val visualAnchor = planOutput?.let { output ->
        TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = output.cycleOrdinal,
            turnOrdinal = output.turnOrdinal,
        )
    }
    val resolvedVisual = tutorResolvedVisual(visualAnchor, resolvedVisualStates)
    val solutionRevealPreviewed = task.toPlanSolutionPreviewKey()
        ?.let { it in planSolutionPreviewKeys } == true
    val awaitingContinuation = tutorAwaitingContinuation(
        awaitingRequestId = planFreshApprovalTask?.request?.requestId,
        taskRequestId = task.request.requestId,
    )
    val interactionEnabled = tutorPlanInteractionEnabled(
        isTail = isTail,
        isCurrentTurn = isCurrentTurn,
        hasFreshApproval = planFreshApprovalTask != null,
        pendingInteractionBlocked = pendingInteractionBlocked,
        responseActionAwaitingAuthorization = responseActionAwaitingAuthorization,
    )
    val onRequestHint = if (tutorHintRequestEnabled(
        guidedMode = explanationMode == TutorExplanationMode.GUIDED,
        hintsUsed = hintsUsed,
        maxHints = maxHints,
        respondSupported = respondSupported,
        respondAuthorized = respondAuthorized,
        chatSending = chatSending,
        pendingInteractionBlocked = pendingInteractionBlocked,
        responseActionAwaitingAuthorization = responseActionAwaitingAuthorization,
    )) {
        { onDirectiveResponse(TutorResponseMessage.freeResponse(GUIDED_HINT_MESSAGE)) }
    } else {
        null
    }
    TutorTaskContent(
        task = task,
        resolvedVisual = resolvedVisual,
        visualPresentationMode = tutorPlanVisualPresentationMode(isCurrentTurn),
        visualOriginalAvailable = visualOriginalAvailable,
        response = response,
        solutionRevealPreviewed = solutionRevealPreviewed,
        awaitingContinuation = awaitingContinuation,
        interactionEnabled = interactionEnabled,
        executionMatchesCurrentProvider = executionMatches,
        splitChoiceFeedback = true,
        interactionBusy = interactionBusy,
        interactionError = interactionError.takeIf { isTail && isCurrentTurn },
        onRetry = onRetry,
        onRetryVisual = { visualAnchor?.let(onRequestVisualRetry) },
        onVisualTargetHit = onVisualTargetHit,
        onSubmitChoice = onSubmitChoice,
        onDirectiveResponse = onDirectiveResponse,
        onRequestHint = onRequestHint,
        onContinue = onContinue,
        onRevealSolution = onRevealSolution,
        onRestartCycle = onRestartCycle,
        explanationMode = explanationMode,
        onOpenModelSettings = onOpenModelSettings,
        onOpenVisualOriginal = onOpenVisualOriginal,
        onReportVisualIncorrect = onReportVisualIncorrect,
        solutionBottomModifier = solutionBottomModifier,
        modifier = modifier,
    )
}

@Composable
internal fun CapturedTutorTimelineChoiceFeedbackItem(
    planTask: ModelTaskSnapshot?,
    response: TutorTurnResponse,
    isTail: Boolean,
    isCurrentTurn: Boolean,
    planSolutionPreviewKeys: Set<PlanSolutionPreviewKey>,
    explanationMode: TutorExplanationMode,
    pendingInteractionBlocked: Boolean,
    responseActionAwaitingAuthorization: Boolean,
    interactionBusy: Boolean,
    interactionError: String?,
    onContinue: (TutorMoveType) -> Unit,
    onRevealSolution: () -> Unit,
    onRestartCycle: () -> Unit,
    solutionBottomModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val output = planTask?.output as? TutorPlanOutput
    if (output != null) {
        TutorChoiceFeedbackContent(
            output = output,
            response = response,
            solutionRevealPreviewed = planTask.toPlanSolutionPreviewKey()
                ?.let { it in planSolutionPreviewKeys } == true,
            showDirectExplanation = isCurrentTurn &&
                explanationMode == TutorExplanationMode.DIRECT,
            interactionEnabled = tutorPlanInteractionEnabled(
                isTail = isTail,
                isCurrentTurn = isCurrentTurn,
                hasFreshApproval = false,
                pendingInteractionBlocked = pendingInteractionBlocked,
                responseActionAwaitingAuthorization = responseActionAwaitingAuthorization,
            ),
            interactionBusy = interactionBusy || pendingInteractionBlocked,
            interactionError = interactionError.takeIf { isTail && isCurrentTurn },
            onContinue = onContinue,
            onRevealSolution = onRevealSolution,
            onRestartCycle = onRestartCycle,
            solutionBottomModifier = solutionBottomModifier,
            modifier = modifier,
        )
    } else {
        TutorStoredChoiceFeedback(response, modifier)
    }
}

@Composable
internal fun CapturedTutorTimelineReplyItem(
    task: ModelTaskSnapshot,
    isTail: Boolean,
    currentProvider: ProviderCapabilitySnapshot?,
    activeMessage: TutorActiveStreamMessage?,
    resolvedVisualStates: Map<TutorVisualTurnAnchor, TutorVisualResolution>,
    visualOriginalAvailable: Boolean,
    respondAuthorized: Boolean,
    chatSending: Boolean,
    interactionBusy: Boolean,
    pendingInteractionBlocked: Boolean,
    responseActionAwaitingAuthorization: Boolean,
    responseFreshApprovalTask: ModelTaskSnapshot?,
    explanationMode: TutorExplanationMode,
    catalogEntries: List<StudyCatalogEntry>,
    profile: StudyProfileOverview?,
    onRetry: () -> Unit,
    onRequestVisualRetry: (TutorVisualTurnAnchor) -> Unit,
    onSubmitVisualTargetEvidence: (
        String,
        TutorVisualTurnAnchor,
        TutorInteractionDirective.VisualTarget,
        TutorVisualHitProof,
        TutorVisualScene?,
    ) -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenVisualOriginal: () -> Unit,
    onReportVisualIncorrect: (String) -> Unit,
    onMove: (TutorSuggestedMove) -> Unit,
    onRevealSolution: (TutorSuggestedMove) -> Unit,
    onDirectiveResponse: (TutorResponseMessage) -> Unit,
    onRequestSave: () -> Unit,
    onRequestEnd: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    solutionBottomModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val executionMatches = tutorPlanExecutionMatches(currentProvider, task)
    val taskAllowsInteraction =
        task.status == ModelTaskStatus.SUCCEEDED ||
            (
                task.isRebuildableTutorRequest() &&
                    task.canRetryTutorResponse()
                )
    val opensLocalSettings =
        task.failure?.code?.requiresModelSettings() == true
    val recoveryEnabled = isTail && executionMatches &&
        task.isRebuildableTutorRequest() &&
        !chatSending && !interactionBusy &&
        (opensLocalSettings ||
            (responseFreshApprovalTask == null && respondAuthorized))
    val input = task.request.input as TutorRespondInput
    val output = task.output as? TutorRespondOutput
    val resolvedVisual = input.let {
        resolvedVisualStates[
            TutorVisualTurnAnchor(
                surface = TutorVisualTurnSurface.FOLLOW_UP,
                cycleOrdinal = it.cycleOrdinal,
                turnOrdinal = it.turnOrdinal,
                responseOrdinal = it.responseOrdinal,
            )
        ]
    } ?: TutorVisualResolution.Hidden
    val interactionEnabled = isTail && taskAllowsInteraction &&
        executionMatches && respondAuthorized &&
        !chatSending && !interactionBusy &&
        !pendingInteractionBlocked &&
        !responseActionAwaitingAuthorization
    TutorChatExchange(
        task = task,
        activeMessage = activeMessage?.takeIf {
            it.identity?.requestId == task.request.requestId
        },
        resolvedVisual = resolvedVisual,
        visualPresentationMode = tutorVisualPresentationMode(isCurrent = isTail),
        visualOriginalAvailable = visualOriginalAvailable,
        awaitingContinuation = !respondAuthorized &&
            task.status.isTutorExecutionPending(),
        interactionEnabled = interactionEnabled,
        recoveryEnabled = recoveryEnabled && !responseActionAwaitingAuthorization,
        executionMatchesCurrentProvider = executionMatches,
        onRetry = onRetry,
        onOpenModelSettings = onOpenModelSettings,
        onMove = onMove,
        onRevealSolution = onRevealSolution,
        explanationMode = explanationMode,
        onDirectiveResponse = onDirectiveResponse,
        onRetryVisual = {
            input.let { source ->
                onRequestVisualRetry(
                    TutorVisualTurnAnchor(
                        surface = TutorVisualTurnSurface.FOLLOW_UP,
                        cycleOrdinal = source.cycleOrdinal,
                        turnOrdinal = source.turnOrdinal,
                        responseOrdinal = source.responseOrdinal,
                    ),
                )
            }
        },
        onVisualTargetHit = { hitProof ->
            output?.let { source ->
                (source.interactionDirective as? TutorInteractionDirective.VisualTarget)
                    ?.let { directive ->
                        onSubmitVisualTargetEvidence(
                            task.request.requestId,
                            TutorVisualTurnAnchor(
                                surface = TutorVisualTurnSurface.FOLLOW_UP,
                                cycleOrdinal = source.cycleOrdinal,
                                turnOrdinal = source.turnOrdinal,
                                responseOrdinal = source.responseOrdinal,
                            ),
                            directive,
                            hitProof,
                            source.visualScene,
                        )
                    }
            }
        },
        localIntentContent = { source, sourceOutput ->
            TutorLocalIntentPanel(
                output = sourceOutput,
                studentMessage = source.studentMessage,
                catalogEntries = catalogEntries,
                profile = profile,
                onRequestSave = onRequestSave,
                onRequestEnd = onRequestEnd,
                onOpenMistakeNotebook = onOpenMistakeNotebook,
                onOpenProfile = onOpenProfile,
            )
        },
        onOpenVisualOriginal = onOpenVisualOriginal,
        onReportVisualIncorrect = onReportVisualIncorrect,
        assistantBottomModifier = solutionBottomModifier,
        modifier = modifier,
    )
}

internal const val MAX_AUTO_VISUAL_WORK_ITEMS = 8
internal const val GUIDED_HINT_MESSAGE = "我不确定，请给我一点提示"
