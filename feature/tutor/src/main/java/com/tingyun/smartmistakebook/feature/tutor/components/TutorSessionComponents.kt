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
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
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
    resolvedVisualScene: TutorVisualDocumentScene? = null,
    response: TutorTurnResponse?,
    solutionRevealPreviewed: Boolean = false,
    awaitingContinuation: Boolean = false,
    interactionEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    splitChoiceFeedback: Boolean,
    interactionBusy: Boolean,
    interactionError: String?,
    onRetry: () -> Unit,
    onSubmitChoice: (String) -> Unit,
    onRequestHint: (() -> Unit)?,
    onContinue: (TutorMoveType) -> Unit,
    onRevealSolution: () -> Unit,
    onRestartCycle: () -> Unit,
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
                    modifier = modifier,
                    response = response,
                    solutionRevealPreviewed = solutionRevealPreviewed,
                    interactionEnabled = interactionEnabled,
                    splitChoiceFeedback = splitChoiceFeedback,
                    interactionBusy = interactionBusy,
                    interactionError = interactionError,
                    resolvedVisualScene = resolvedVisualScene,
                    onOpenVisualOriginal = onOpenVisualOriginal,
                    onReportVisualIncorrect = onReportVisualIncorrect,
                    onSubmitChoice = onSubmitChoice,
                    onRequestHint = onRequestHint,
                    onContinue = onContinue,
                    onRevealSolution = onRevealSolution,
                    onRestartCycle = onRestartCycle,
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
