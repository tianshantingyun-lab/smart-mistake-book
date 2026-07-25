package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer

@Composable
internal fun TutorTurnContent(
    output: TutorPlanOutput,
    modifier: Modifier = Modifier,
    response: TutorTurnResponse? = null,
    solutionRevealPreviewed: Boolean = false,
    interactionEnabled: Boolean = true,
    splitChoiceFeedback: Boolean = false,
    interactionBusy: Boolean = false,
    interactionError: String? = null,
    onSubmitChoice: (String) -> Unit = {},
    onRequestHint: (() -> Unit)? = null,
    onContinue: (TutorMoveType) -> Unit = {},
    onRevealSolution: () -> Unit = {},
    onRestartCycle: () -> Unit = {},
    solutionBottomModifier: Modifier = Modifier,
) {
    val plan = output.plan
    val item = plan.diagnosticItem
    // A pending click is process-local UI state. Persisting it without the in-flight coroutine can
    // restore a permanently disabled choice after activity recreation.
    var pendingChoiceId by remember(
        output.sessionId,
        output.questionDocumentId,
        output.draftRevisionNumber,
        output.cycleOrdinal,
        output.turnOrdinal,
    ) {
        mutableStateOf<String?>(null)
    }
    val choiceResponse = response?.takeIf(TutorTurnResponse::hasChoicePayload)
    val submitted = choiceResponse != null
    val selectedId = choiceResponse?.selectedChoiceId ?: pendingChoiceId
    val showSolution = response?.solutionRevealed == true || solutionRevealPreviewed
    val explanationOnlyAlternateVisible =
        response?.requestedMove == TutorMoveType.CHANGE_REPRESENTATION

    LaunchedEffect(pendingChoiceId, interactionError, submitted) {
        if (pendingChoiceId != null && (submitted || interactionError != null)) {
            pendingChoiceId = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("captured_tutor_model_ready"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SafeMarkdownText(plan.openingMarkdown, style = MaterialTheme.typography.bodyLarge)
        if (item == null) {
            plan.visualScene?.let { scene ->
                TutorVisualSceneRenderer(scene)
            }
        }
        item?.let { interaction ->
            SafeMarkdownText(interaction.stemMarkdown, style = MaterialTheme.typography.bodyLarge)
            interaction.promptMarkdown?.let {
                SafeMarkdownText(it, style = MaterialTheme.typography.bodyMedium)
            }
            interaction.choices.forEach { choice ->
                val isSelected = selectedId == choice.id
                val choicesEnabled = interactionEnabled &&
                    !submitted && !interactionBusy && pendingChoiceId == null
                val choiceStateDescription = when {
                    submitted && isSelected -> "已提交"
                    pendingChoiceId == choice.id -> "正在提交"
                    submitted -> "未选择"
                    !choicesEnabled -> "暂不可操作"
                    else -> "点击后立即提交"
                }
                OutlineActionChip(
                    text = choice.markdown,
                    onClick = {
                        if (!submitted && !interactionBusy && pendingChoiceId == null) {
                            pendingChoiceId = choice.id
                            onSubmitChoice(choice.id)
                        }
                    },
                    enabled = choicesEnabled,
                    selected = isSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = choiceStateDescription }
                        .testTag("captured_tutor_choice_${choice.id}"),
                    contentDescription = "选择并提交：${choice.markdown}",
                )
            }
            if (!submitted && onRequestHint != null) {
                TextButton(
                    onClick = onRequestHint,
                    enabled = interactionEnabled &&
                        !interactionBusy && pendingChoiceId == null,
                    colors = ButtonDefaults.textButtonColors(contentColor = JadeActive),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("captured_tutor_request_hint"),
                ) {
                    Text("我不确定，给我一点提示")
                }
            }
        }
        if (item == null) {
            TutorMoveButtons(
                moves = plan.suggestedMoves.filter { move ->
                    move.type == TutorMoveType.CHANGE_REPRESENTATION ||
                        move.type == TutorMoveType.REVEAL_SOLUTION
                },
                requestedMove = response?.requestedMove,
                showSolution = showSolution,
                alternateVisible = explanationOnlyAlternateVisible,
                interactionBusy = interactionBusy,
                interactionEnabled = interactionEnabled,
                canContinue = true,
                explanationOnly = true,
                onContinue = onContinue,
                onRevealSolution = onRevealSolution,
            )
            if (explanationOnlyAlternateVisible) {
                Text(
                    text = "另一种方法",
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
                SafeMarkdownText(
                    plan.alternateMethodMarkdown,
                    modifier = Modifier.testTag("captured_tutor_alternate_method"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (showSolution) {
                Text(
                    text = "完整讲解",
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
                SafeMarkdownText(
                    plan.solutionMarkdown,
                    modifier = Modifier.testTag("captured_tutor_solution"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .then(solutionBottomModifier),
                )
            }
        } else if (submitted && !splitChoiceFeedback) {
            val submittedResponse = requireNotNull(choiceResponse)
            Text(
                text = if (submittedResponse.selectionWasCorrect == true) {
                    "判断正确，继续把理由说完整"
                } else {
                    "这个选择暴露了一个关键分叉"
                },
                color = if (submittedResponse.selectionWasCorrect == true) JadeActive else ErrorWarm,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SafeMarkdownText(
                requireNotNull(submittedResponse.feedbackMarkdown),
                style = MaterialTheme.typography.bodyMedium,
            )
            plan.visualScene?.let { scene ->
                TutorVisualSceneRenderer(scene)
            }
            TutorMoveButtons(
                moves = plan.suggestedMoves,
                requestedMove = submittedResponse.requestedMove,
                showSolution = showSolution,
                alternateVisible = false,
                interactionBusy = interactionBusy,
                interactionEnabled = interactionEnabled,
                canContinue = output.turnOrdinal < TutorPlanInput.MAX_TURNS,
                explanationOnly = false,
                onContinue = onContinue,
                onRevealSolution = onRevealSolution,
            )
            if (showSolution) {
                Text(
                    text = "规范讲解",
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
                SafeMarkdownText(plan.solutionMarkdown, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "另一种表征",
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
                SafeMarkdownText(
                    plan.alternateMethodMarkdown,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .then(solutionBottomModifier),
                )
            }
            if (output.turnOrdinal == TutorPlanInput.MAX_TURNS) {
                PrimaryActionButton(
                    text = "继续讲这道题",
                    onClick = onRestartCycle,
                    enabled = interactionEnabled && !interactionBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("captured_tutor_restart_cycle"),
                    contentDescription = "保留本轮学习记忆并开始新的讲题轮次",
                )
            }
        }
        interactionError?.let { message ->
            Text(
                text = message,
                color = ErrorWarm,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("captured_tutor_interaction_error"),
            )
        }
    }
}

@Composable
internal fun TutorChoiceFeedbackContent(
    output: TutorPlanOutput,
    response: TutorTurnResponse,
    solutionRevealPreviewed: Boolean = false,
    interactionEnabled: Boolean,
    interactionBusy: Boolean = false,
    interactionError: String? = null,
    onContinue: (TutorMoveType) -> Unit = {},
    onRevealSolution: () -> Unit = {},
    onRestartCycle: () -> Unit = {},
    solutionBottomModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    require(response.hasChoicePayload)
    val plan = output.plan
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("captured_tutor_choice_feedback_${response.cycleOrdinal}_${response.turnOrdinal}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (response.selectionWasCorrect == true) {
                "判断正确，继续把理由说完整"
            } else {
                "这个选择暴露了一个关键分叉"
            },
            color = if (response.selectionWasCorrect == true) JadeActive else ErrorWarm,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        SafeMarkdownText(
            requireNotNull(response.feedbackMarkdown),
            style = MaterialTheme.typography.bodyMedium,
        )
        plan.visualScene?.let { scene -> TutorVisualSceneRenderer(scene) }
        TutorMoveButtons(
            moves = plan.suggestedMoves,
            requestedMove = response.requestedMove,
            showSolution = response.solutionRevealed || solutionRevealPreviewed,
            alternateVisible = false,
            interactionBusy = interactionBusy,
            interactionEnabled = interactionEnabled,
            canContinue = output.turnOrdinal < TutorPlanInput.MAX_TURNS,
            explanationOnly = false,
            onContinue = onContinue,
            onRevealSolution = onRevealSolution,
        )
        if (response.solutionRevealed || solutionRevealPreviewed) {
            Text(
                text = "规范讲解",
                color = InkSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
            SafeMarkdownText(plan.solutionMarkdown, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "另一种表征",
                color = InkSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
            SafeMarkdownText(
                plan.alternateMethodMarkdown,
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .then(solutionBottomModifier),
            )
        }
        if (output.turnOrdinal == TutorPlanInput.MAX_TURNS) {
            PrimaryActionButton(
                text = "继续讲这道题",
                onClick = onRestartCycle,
                enabled = interactionEnabled && !interactionBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("captured_tutor_restart_cycle"),
                contentDescription = "保留本轮学习记忆并开始新的讲题轮次",
            )
        }
        interactionError?.let { message ->
            Text(
                text = message,
                color = ErrorWarm,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("captured_tutor_interaction_error"),
            )
        }
    }
}

@Composable
private fun TutorMoveButtons(
    moves: List<TutorSuggestedMove>,
    requestedMove: TutorMoveType?,
    showSolution: Boolean,
    alternateVisible: Boolean,
    interactionBusy: Boolean,
    interactionEnabled: Boolean,
    canContinue: Boolean,
    explanationOnly: Boolean,
    onContinue: (TutorMoveType) -> Unit,
    onRevealSolution: () -> Unit,
) {
    val hasRevealMove = moves.any { it.type == TutorMoveType.REVEAL_SOLUTION }
    val visibleMoves = if (hasRevealMove) moves else moves.take(2)
    if (visibleMoves.isNotEmpty()) {
        Text(
            text = "接下来想怎么看？",
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    visibleMoves.forEach { move ->
        val isReveal = move.type == TutorMoveType.REVEAL_SOLUTION
        val enabled = interactionEnabled && !interactionBusy && if (isReveal) {
            !showSolution && (explanationOnly || requestedMove == null)
        } else {
            canContinue && requestedMove == null && !showSolution
        }
        val tag = when {
            explanationOnly && move.type == TutorMoveType.CHANGE_REPRESENTATION ->
                "captured_tutor_show_alternate"
            explanationOnly && isReveal -> "captured_tutor_reveal_without_choice"
            else -> "captured_tutor_move_${move.id}"
        }
        OutlineActionChip(
            text = move.label,
            onClick = {
                if (isReveal) onRevealSolution() else onContinue(move.type)
            },
            enabled = enabled,
            selected = (isReveal && showSolution) ||
                (move.type == TutorMoveType.CHANGE_REPRESENTATION && alternateVisible),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
        )
    }
    if (!hasRevealMove && !showSolution) {
        TextButton(
            onClick = onRevealSolution,
            enabled = interactionEnabled &&
                !interactionBusy && (explanationOnly || requestedMove == null),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(
                    if (explanationOnly) {
                        "captured_tutor_reveal_without_choice"
                    } else {
                        "captured_tutor_reveal_fallback"
                    },
                ),
        ) {
            Text("查看完整讲解")
        }
    }
}
