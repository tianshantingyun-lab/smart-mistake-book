package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.canExposeSolutionFor
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.StreamingSafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

private data class TutorRespondExchangeKey(
    val sessionId: String,
    val revisionNumber: Int,
    val questionDocumentId: String,
    val responseOrdinal: Int,
    val studentMessage: String,
    val visibleTutorContextMarkdown: String?,
    val priorMessages: List<TutorChatHistoryEntry>,
    val requestedMove: TutorMoveType?,
    val explanationMode: TutorExplanationMode,
)

private fun TutorRespondInput.exchangeKey() = TutorRespondExchangeKey(
    sessionId = sessionId,
    revisionNumber = draftRevisionNumber,
    questionDocumentId = questionDocument.id,
    responseOrdinal = responseOrdinal,
    studentMessage = studentMessage,
    visibleTutorContextMarkdown = visibleTutorContextMarkdown,
    priorMessages = priorMessages,
    requestedMove = requestedMove,
    explanationMode = explanationMode,
)

private fun visibleTutorRespondTasks(tasks: List<ModelTaskSnapshot>): List<ModelTaskSnapshot> = tasks
    .filter { it.request.input is TutorRespondInput }
    .groupBy { (it.request.input as TutorRespondInput).exchangeKey() }
    .values
    .flatMap { attempts ->
        val latest = attempts.maxWith(
            compareBy<ModelTaskSnapshot>(ModelTaskSnapshot::createdAtEpochMillis)
                .thenBy(ModelTaskSnapshot::updatedAtEpochMillis)
                .thenBy { it.request.requestId },
        )
        if (latest.status == ModelTaskStatus.CANCELLED) emptyList() else attempts
    }

internal fun latestTutorRespondTasks(tasks: List<ModelTaskSnapshot>): List<ModelTaskSnapshot> =
    visibleTutorRespondTasks(tasks)
        .groupBy { (it.request.input as TutorRespondInput).exchangeKey() }
        .values
        .map { attempts ->
            attempts.maxWith(
                compareBy<ModelTaskSnapshot>(ModelTaskSnapshot::createdAtEpochMillis)
                    .thenBy(ModelTaskSnapshot::updatedAtEpochMillis)
                    .thenBy { it.request.requestId },
            )
        }
    .sortedWith(
        compareBy<ModelTaskSnapshot> {
            (it.request.input as TutorRespondInput).responseOrdinal
        }.thenBy(ModelTaskSnapshot::createdAtEpochMillis)
            .thenBy { it.request.requestId },
    )

internal fun durableVisibleTutorRespondTasks(
    tasks: List<ModelTaskSnapshot>,
): List<ModelTaskSnapshot> = visibleTutorRespondTasks(tasks)

internal fun ModelTaskSnapshot.canRetryTutorResponse(): Boolean =
    request.input is TutorRespondInput &&
        status == ModelTaskStatus.RETRYABLE_FAILURE &&
        attemptCount < 2 &&
        (
            provider?.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS ||
                request.requestId.tutorEnvelopeAttempt() < 1
            )

internal fun ModelTaskSnapshot.nextLocalTutorResponseRetryAttempt(): Int? {
    if (!canRetryTutorResponse()) return null
    if (provider?.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS) return null
    return (request.requestId.tutorEnvelopeAttempt() + 1).takeIf { it <= 1 }
}

private fun String.tutorEnvelopeAttempt(): Int =
    substringAfterLast(':').toIntOrNull() ?: 0

internal fun ModelTaskSnapshot.canRetryTutorResponseFor(
    explanationMode: TutorExplanationMode,
): Boolean {
    if (!canRetryTutorResponse()) return false
    val input = request.input as TutorRespondInput
    return input.explanationMode == tutorResponseModeFor(
        currentMode = explanationMode,
        studentMessage = input.studentMessage,
    )
}

internal fun tutorResponseModeFor(
    currentMode: TutorExplanationMode,
    studentMessage: String,
): TutorExplanationMode =
    if (
        currentMode == TutorExplanationMode.GUIDED &&
        studentMessage.trim() in DIRECT_TUTOR_RESPONSE_INTENTS
    ) {
        TutorExplanationMode.DIRECT
    } else {
        currentMode
    }

private val DIRECT_TUTOR_RESPONSE_INTENTS = setOf("直接讲", "不要问", "别提问")

private fun ModelTaskSnapshot.requiresTutorModelSettings(): Boolean {
    val code = failure?.code ?: return false
    return code.requiresModelSettings()
}

internal enum class TutorActiveFailureAction {
    RETRY,
    MODEL_SETTINGS,
}

internal data class TutorActiveFailurePresentation(
    val detail: String,
    val action: TutorActiveFailureAction?,
)

internal fun tutorActiveFailurePresentation(
    message: TutorActiveStreamMessage,
    durableTask: ModelTaskSnapshot?,
    currentMode: TutorExplanationMode?,
): TutorActiveFailurePresentation? {
    if (message.phase != TutorActiveStreamPhase.FAILED) return null
    val matchingTask = durableTask?.takeIf { task ->
        task.request.requestId == message.identity?.requestId &&
            task.status in setOf(
                ModelTaskStatus.PERMANENT_FAILURE,
                ModelTaskStatus.RETRYABLE_FAILURE,
            )
    }
    val action = when {
        matchingTask?.requiresTutorModelSettings() == true ->
            TutorActiveFailureAction.MODEL_SETTINGS

        matchingTask?.request?.input is TutorRespondInput &&
            currentMode != null &&
            !message.retryConsumed &&
            matchingTask.canRetryTutorResponseFor(currentMode) ->
            TutorActiveFailureAction.RETRY

        matchingTask?.request?.input !is TutorRespondInput &&
            matchingTask?.status == ModelTaskStatus.RETRYABLE_FAILURE &&
            !message.retryConsumed &&
            matchingTask.attemptCount < 2 ->
            TutorActiveFailureAction.RETRY

        matchingTask == null &&
            TutorActiveStreamRecovery.RETRY in message.recoveryActions ->
            TutorActiveFailureAction.RETRY

        else -> null
    }
    val detail = matchingTask?.failure?.message
        ?: message.failureDetail
        ?: if (message.snapshot == null) {
            "这次回复没有完成。"
        } else {
            "这次回复没有完成，已保留上面的内容。"
        }
    return TutorActiveFailurePresentation(detail = detail, action = action)
}

internal fun tutorChatHistory(
    tasks: List<ModelTaskSnapshot>,
    answerExposureKeys: Set<TutorAnswerExposureKey>,
): List<TutorChatHistoryEntry> {
    val succeeded = latestTutorRespondTasks(tasks).mapNotNull { task ->
        val input = task.request.input as TutorRespondInput
        val output = task.output as? TutorRespondOutput
        if (task.status != ModelTaskStatus.SUCCEEDED || output == null) return@mapNotNull null
        val answerWasExposed = output.canExposeSolutionFor(input) &&
            task.toRespondAnswerExposureKey() in answerExposureKeys
        TutorChatHistoryEntry(
            studentMessage = input.studentMessage,
            assistantMarkdown = if (output.solutionRevealed && !answerWasExposed) {
                HIDDEN_TUTOR_ANSWER_CONTEXT
            } else {
                output.messageMarkdown
            },
        )
    }
    var totalChars = 0
    return buildList {
        for (message in succeeded.asReversed()) {
            val messageChars = message.studentMessage.length + message.assistantMarkdown.length
            if (
                size >= TutorRespondInput.MAX_PRIOR_MESSAGES ||
                totalChars + messageChars > TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS
            ) {
                break
            }
            add(message)
            totalChars += messageChars
        }
    }.asReversed()
}

private const val HIDDEN_TUTOR_ANSWER_CONTEXT =
    "上一条讲解包含完整答案，但学生还没有完整看到；不要假设学生已经读过答案。"
private const val UNAUTHORIZED_TUTOR_ANSWER_MESSAGE =
    "我先不直接展开完整答案。你可以继续问当前步骤，或者点“看完整讲解”。"
private val LOCAL_REVEAL_SOLUTION_MOVE = TutorSuggestedMove(
    id = "local-reveal-solution",
    label = "看完整讲解",
    type = TutorMoveType.REVEAL_SOLUTION,
)

/**
 * Recovers the most recent contiguous suffix of exact student messages from persisted requests.
 * Model output and task success are intentionally irrelevant: an omitted or failed reply cannot
 * erase what the student actually said.
 */
internal fun priorCycleStudentMessages(tasks: List<ModelTaskSnapshot>): List<String> {
    val latestByOrdinal = latestTutorRespondTasks(tasks)
        .groupBy { task -> (task.request.input as TutorRespondInput).responseOrdinal }
        .values
        .map { sameOrdinal ->
            sameOrdinal.maxWith(
                compareBy<ModelTaskSnapshot>(ModelTaskSnapshot::createdAtEpochMillis)
                    .thenBy(ModelTaskSnapshot::updatedAtEpochMillis)
                    .thenBy { task -> task.request.requestId },
            )
        }
        .sortedBy { task -> (task.request.input as TutorRespondInput).responseOrdinal }
    var expectedOrdinal = (latestByOrdinal.lastOrNull()?.request?.input as? TutorRespondInput)
        ?.responseOrdinal
        ?: return emptyList()
    var retainedChars = 0
    return buildList {
        for (task in latestByOrdinal.asReversed()) {
            val input = task.request.input as TutorRespondInput
            if (input.responseOrdinal != expectedOrdinal) break
            val message = input.studentMessage
            if (
                size >= TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGES ||
                retainedChars + message.length >
                TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGE_CHARS
            ) {
                break
            }
            add(message)
            retainedChars += message.length
            expectedOrdinal -= 1
        }
    }.asReversed()
}

@Composable
internal fun TutorChatExchange(
    task: ModelTaskSnapshot,
    activeMessage: TutorActiveStreamMessage? = null,
    resolvedVisual: TutorVisualResolution = TutorVisualResolution.Hidden,
    visualPresentationMode: TutorVisualPresentationMode =
        TutorVisualPresentationMode.CURRENT_EXPANDED,
    visualOriginalAvailable: Boolean = false,
    awaitingContinuation: Boolean = false,
    interactionEnabled: Boolean,
    recoveryEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onMove: (TutorSuggestedMove) -> Unit,
    onRevealSolution: (TutorSuggestedMove) -> Unit,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    onDirectiveResponse: (String) -> Unit = {},
    onRetryVisual: () -> Unit = {},
    onVisualTargetHit: (TutorVisualHitProof) -> Unit = {},
    localIntentContent: @Composable (TutorRespondInput, TutorRespondOutput) -> Unit = { _, _ -> },
    onOpenVisualOriginal: () -> Unit = {},
    onReportVisualIncorrect: (String) -> Unit = {},
    assistantBottomModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val input = task.request.input as TutorRespondInput
    val output = task.output as? TutorRespondOutput
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TutorStudentMessageBubble(
            message = input.studentMessage,
            modifier = Modifier.testTag("tutor_chat_user_${input.responseOrdinal}"),
        )
        if (activeMessage != null) {
            TutorActiveAssistantReply(
                message = activeMessage,
                durableTask = task,
                currentMode = explanationMode,
                onRetry = onRetry,
                onOpenModelSettings = onOpenModelSettings,
            )
        } else {
            TutorAssistantReplyBubble(
                task = task,
                awaitingContinuation = awaitingContinuation,
                showActions = interactionEnabled,
                recoveryEnabled = recoveryEnabled,
                executionMatchesCurrentProvider = executionMatchesCurrentProvider,
                onRetry = onRetry,
                onOpenModelSettings = onOpenModelSettings,
                onMove = onMove,
                onRevealSolution = onRevealSolution,
                explanationMode = explanationMode,
                onDirectiveResponse = onDirectiveResponse,
                localIntentContent = localIntentContent,
                visualTargetReady = isTutorVisualTargetReady(
                    state = resolvedVisual,
                    inlineScene = output?.visualScene,
                ),
                assistantBottomModifier = assistantBottomModifier,
            )
            val visualTargetHitHandler = onVisualTargetHit.takeIf {
                explanationMode == TutorExplanationMode.GUIDED &&
                    output?.interactionDirective is TutorInteractionDirective.VisualTarget
            }
            TutorVisualPresentation(
                state = resolvedVisual,
                mode = visualPresentationMode,
                originalAvailable = visualOriginalAvailable,
                onRetry = onRetryVisual,
                onOpenOriginal = onOpenVisualOriginal,
                onReportIncorrect = onReportVisualIncorrect,
                ownerModelTaskRequestId = task.request.requestId,
                onTargetHit = visualTargetHitHandler,
            )
            output?.visualScene?.let { scene ->
                TutorVisualPresentation(
                    state = inlineTutorVisualResolution(scene, task.request.requestId),
                    mode = visualPresentationMode,
                    originalAvailable = visualOriginalAvailable,
                    onRetry = {},
                    onOpenOriginal = onOpenVisualOriginal,
                    onReportIncorrect = onReportVisualIncorrect,
                    ownerModelTaskRequestId = task.request.requestId,
                    onTargetHit = visualTargetHitHandler,
                )
            }
        }
    }
}

@Composable
internal fun TutorActiveChatExchange(
    message: TutorActiveStreamMessage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TutorStudentMessageBubble(message.studentMessage)
        TutorActiveAssistantReply(message = message, onRetry = onRetry)
    }
}

@Composable
private fun TutorStudentMessageBubble(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            color = JadeSoft.copy(alpha = 0.72f),
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun TutorActiveAssistantReply(
    message: TutorActiveStreamMessage,
    onRetry: () -> Unit,
    durableTask: ModelTaskSnapshot? = null,
    currentMode: TutorExplanationMode? = null,
    onOpenModelSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot = message.snapshot
    val failure = tutorActiveFailurePresentation(
        message = message,
        durableTask = durableTask,
        currentMode = currentMode,
    )
    if (message.activityVisible && snapshot?.isEmpty != false && !message.showPlaceholder) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .testTag("tutor_stream_activity"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = JadeActive,
                strokeWidth = 2.dp,
            )
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(0.94f),
        color = Paper,
        shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (snapshot != null && !snapshot.isEmpty) {
                StreamingSafeMarkdownText(
                    snapshot = snapshot,
                    style = MaterialTheme.typography.bodyMedium,
                    contentIdentity = message.identity ?: listOf(
                        message.ownerVersion,
                        message.turnVersion,
                        message.modeVersion,
                    ),
                )
            } else if (message.showPlaceholder) {
                Row(
                    modifier = Modifier.testTag("tutor_stream_placeholder"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = JadeActive,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "正在回复…",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (failure != null) {
                TutorReplyFailure(
                    detail = failure.detail,
                    actionLabel = when (failure.action) {
                        TutorActiveFailureAction.RETRY -> "重试"
                        TutorActiveFailureAction.MODEL_SETTINGS -> "检查模型设置"
                        null -> null
                    },
                    onAction = when (failure.action) {
                        TutorActiveFailureAction.MODEL_SETTINGS -> onOpenModelSettings
                        else -> onRetry
                    },
                )
            }
        }
    }
}

@Composable
private fun TutorAssistantReplyBubble(
    task: ModelTaskSnapshot,
    awaitingContinuation: Boolean,
    showActions: Boolean,
    recoveryEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onMove: (TutorSuggestedMove) -> Unit,
    onRevealSolution: (TutorSuggestedMove) -> Unit,
    explanationMode: TutorExplanationMode,
    onDirectiveResponse: (String) -> Unit,
    localIntentContent: @Composable (TutorRespondInput, TutorRespondOutput) -> Unit,
    visualTargetReady: Boolean,
    assistantBottomModifier: Modifier,
) {
    val input = task.request.input as TutorRespondInput
    val output = task.output as? TutorRespondOutput
    val assistantContentIdentity = listOf(
        task.request.requestId,
        input.responseOrdinal,
        output?.messageMarkdown,
    )
    var assistantContentReady by remember(assistantContentIdentity) {
        mutableStateOf(false)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .testTag("tutor_chat_assistant_${input.responseOrdinal}"),
        color = Paper,
        shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Box {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (task.status) {
                    ModelTaskStatus.SUCCEEDED -> {
                        if (output == null) {
                            TutorReplyFailure()
                        } else if (
                            output.solutionRevealed &&
                            !output.canExposeSolutionFor(input)
                        ) {
                            SafeMarkdownText(
                                markdown = UNAUTHORIZED_TUTOR_ANSWER_MESSAGE,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (showActions) {
                                OutlineActionChip(
                                    text = LOCAL_REVEAL_SOLUTION_MOVE.label,
                                    onClick = {
                                        onRevealSolution(LOCAL_REVEAL_SOLUTION_MOVE)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("tutor_chat_local_reveal_solution"),
                                )
                            }
                        } else {
                            SafeMarkdownText(
                                markdown = output.messageMarkdown,
                                style = MaterialTheme.typography.bodyMedium,
                                contentIdentity = assistantContentIdentity,
                                onContentReady = { assistantContentReady = true },
                            )
                            localIntentContent(input, output)
                            visibleTutorInteractionDirective(
                                explanationMode,
                                output.interactionDirective,
                            )?.let { directive ->
                                TutorInteractionDirectiveContent(
                                    directive = directive,
                                    enabled = showActions,
                                    onResponse = onDirectiveResponse,
                                    visualTargetReady = visualTargetReady,
                                )
                            }
                            if (output.solutionRevealed && assistantContentReady) {
                                Box(
                                    modifier = Modifier
                                        .size(1.dp)
                                        .testTag("tutor_chat_assistant_bottom_${input.responseOrdinal}")
                                        .then(assistantBottomModifier),
                                )
                            }
                            if (showActions) {
                                output.suggestedMoves
                                    .take(MAX_MODEL_RELEVANT_FOLLOW_UPS)
                                    .forEach { move ->
                                    OutlineActionChip(
                                        text = move.label,
                                        onClick = {
                                            if (move.type == TutorMoveType.REVEAL_SOLUTION) {
                                                onRevealSolution(move)
                                            } else {
                                                onMove(move)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("tutor_chat_move_${move.id}"),
                                    )
                                }
                            }
                        }
                    }

                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                    -> {
                        val settingsRequired = task.requiresTutorModelSettings()
                        val actionLabel = when {
                            recoveryEnabled && executionMatchesCurrentProvider && settingsRequired ->
                                "检查模型设置"
                            showActions && task.canRetryTutorResponseFor(explanationMode) -> "重试"
                            else -> null
                        }
                        TutorReplyFailure(
                            detail = when {
                                !executionMatchesCurrentProvider -> "旧配置中的回复没有完成。"
                                settingsRequired -> "模型设置需要更新，题目已经保存。"
                                else -> "这次回复没有完成。"
                            },
                            actionLabel = actionLabel,
                            onAction = if (settingsRequired) onOpenModelSettings else onRetry,
                            actionTestTag = if (settingsRequired) {
                                "tutor_chat_model_settings"
                            } else {
                                "tutor_chat_retry"
                            },
                        )
                    }

                    else -> if (executionMatchesCurrentProvider && awaitingContinuation) {
                        Text(
                            "回复已暂停，点下方“继续对话”后接着完成。",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("tutor_chat_reply_paused"),
                        )
                    } else if (executionMatchesCurrentProvider) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .testTag("tutor_chat_reply_progress"),
                                color = JadeActive,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "正在回复…",
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Text(
                            "旧配置中的回复未完成",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("tutor_chat_legacy_incomplete"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorReplyFailure(
    detail: String = "这次回复没有完成。",
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionTestTag: String = "tutor_chat_retry",
) {
    Text(detail, color = ErrorWarm, style = MaterialTheme.typography.bodyMedium)
    if (actionLabel != null) {
        OutlineActionChip(
            text = actionLabel,
            onClick = onAction,
            modifier = Modifier.testTag(actionTestTag),
        )
    }
}

@Composable
internal fun TutorConversationFrame(
    header: @Composable () -> Unit,
    autoScrollVersion: Any?,
    expectedItemCount: Int? = null,
    forceFollowToken: Any? = null,
    blockAutoFollowToken: Any? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    listViewportModifier: Modifier = Modifier,
    composer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    TutorConversationAnchorEffect(
        autoScrollVersion = autoScrollVersion,
        expectedItemCount = expectedItemCount,
        forceFollowToken = forceFollowToken,
        blockAutoFollowToken = blockAutoFollowToken,
        listState = listState,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = SmartDimens.MaximumContentWidth)
                .fillMaxSize()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = SmartDimens.ContentHorizontalPadding,
                    top = 8.dp,
                    end = SmartDimens.ContentHorizontalPadding,
                ),
            ) {
                header()
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("tutor_conversation_list")
                    .then(listViewportModifier),
                state = listState,
                contentPadding = PaddingValues(
                    start = SmartDimens.ContentHorizontalPadding,
                    top = 12.dp,
                    end = SmartDimens.ContentHorizontalPadding,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
            composer?.let {
                PaperDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Paper)
                        .padding(
                            horizontal = SmartDimens.ContentHorizontalPadding,
                            vertical = 8.dp,
                        ),
                ) {
                    it()
                }
            }
        }
    }
}

@Composable
internal fun TutorConversationAnchorEffect(
    autoScrollVersion: Any?,
    expectedItemCount: Int? = null,
    forceFollowToken: Any? = null,
    blockAutoFollowToken: Any? = null,
    listState: LazyListState,
) {
    var initialTailPositioned by remember(listState) { mutableStateOf(false) }
    var followsTail by remember(listState) { mutableStateOf(true) }
    var handledForceToken by remember(listState) { mutableStateOf<Any?>(null) }
    var handledBlockToken by remember(listState) { mutableStateOf<Any?>(null) }
    val nearBottomThresholdPx = with(LocalDensity.current) { 72.dp.roundToPx() }

    LaunchedEffect(listState, nearBottomThresholdPx) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            val nearBottom = isTutorConversationNearBottom(
                totalItemsCount = layout.totalItemsCount,
                lastVisibleItemIndex = lastVisible?.index,
                lastVisibleItemBottomPx = lastVisible?.let { it.offset + it.size },
                viewportEndPx = layout.viewportEndOffset,
                thresholdPx = nearBottomThresholdPx,
            )
            listState.isScrollInProgress to nearBottom
        }.collect { (scrolling, nearBottom) ->
            if (scrolling) {
                followsTail = nearBottom
            } else if (nearBottom) {
                followsTail = true
            }
        }
    }
    LaunchedEffect(listState) {
        var previousTailLayout: TutorConversationTailLayout? = null
        snapshotFlow {
            if (!followsTail || listState.isScrollInProgress) {
                null
            } else {
                val layout = listState.layoutInfo
                val itemCount = layout.totalItemsCount
                TutorConversationTailLayout(
                    totalItemsCount = itemCount,
                    tailItemSizePx = layout.visibleItemsInfo
                        .lastOrNull { it.index == itemCount - 1 }
                        ?.size,
                )
            }
        }.collectLatest { current ->
            if (current == null) {
                previousTailLayout = null
                return@collectLatest
            }
            val previous = previousTailLayout
            previousTailLayout = current
            if (
                shouldFollowTutorConversationLayoutGrowth(
                    followsTail = followsTail,
                    previous = previous,
                    current = current,
                )
            ) {
                listState.scrollToTutorConversationTail(current.totalItemsCount)
            }
        }
    }
    LaunchedEffect(
        autoScrollVersion,
        expectedItemCount,
        forceFollowToken,
        blockAutoFollowToken,
        listState,
    ) {
        val forceFollow = forceFollowToken != null && forceFollowToken != handledForceToken
        val blockAutoFollow = initialTailPositioned &&
            blockAutoFollowToken != null &&
            blockAutoFollowToken != handledBlockToken
        val mutation = when {
            forceFollow -> TutorConversationMutation.STUDENT_SEND
            blockAutoFollow -> TutorConversationMutation.VISUAL_INSERTION
            else -> TutorConversationMutation.ACTIVE_REPLY_GROWTH
        }
        val shouldFollow = !initialTailPositioned || shouldFollowTutorConversationTail(
            wasNearBottom = followsTail,
            mutation = mutation,
        )
        if (blockAutoFollow) {
            followsTail = false
        }
        withFrameNanos { }
        val itemCount = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { count ->
                count > 0 && (expectedItemCount == null || count == expectedItemCount)
            }
        if (shouldFollow) {
            listState.scrollToTutorConversationTail(itemCount)
            followsTail = true
        }
        initialTailPositioned = true
        if (forceFollowToken != null) handledForceToken = forceFollowToken
        if (blockAutoFollowToken != null) handledBlockToken = blockAutoFollowToken
    }
}

internal data class TutorConversationTailLayout(
    val totalItemsCount: Int,
    val tailItemSizePx: Int?,
)

internal fun shouldFollowTutorConversationLayoutGrowth(
    followsTail: Boolean,
    previous: TutorConversationTailLayout?,
    current: TutorConversationTailLayout,
): Boolean {
    if (!followsTail || previous == null) return false
    val previousSize = previous.tailItemSizePx ?: return false
    val currentSize = current.tailItemSizePx ?: return false
    return current.totalItemsCount == previous.totalItemsCount &&
        current.totalItemsCount > 0 &&
        currentSize > previousSize
}

private suspend fun LazyListState.scrollToTutorConversationTail(itemCount: Int) {
    val lastIndex = itemCount - 1
    scrollToItem(lastIndex)
    withFrameNanos { }
    val layout = layoutInfo
    val lastItem = layout.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
    val tailOffset = (lastItem.size - viewportHeight).coerceAtLeast(0)
    if (tailOffset > 0) {
        scrollToItem(lastIndex, tailOffset)
    }
}

@Composable
internal fun TutorChatComposer(
    value: String,
    enabled: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { changed ->
                onValueChange(changed.take(TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(SmartDimens.ComposerHeight)
                .testTag("tutor_chat_composer"),
            enabled = enabled,
            placeholder = { Text("问这道题，或说出你卡住的步骤") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { if (enabled && value.isNotBlank() && !sending) onSend() },
            ),
            trailingIcon = {
                IconButton(
                    onClick = onSend,
                    enabled = enabled && value.isNotBlank() && !sending,
                    modifier = Modifier
                        .size(SmartDimens.MinimumTouchTarget)
                        .testTag("tutor_chat_send"),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = JadeActive,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "发送这条消息",
                            tint = JadeActive,
                        )
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
        )
        if (value.length >= 1_000) {
            Text(
                text = "${value.length}/${TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS}",
                modifier = Modifier
                    .padding(top = 4.dp, end = 12.dp)
                    .testTag("tutor_chat_character_count"),
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCameraAttachment,
                enabled = enabled,
                modifier = Modifier.testTag("tutor_chat_camera"),
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = "拍摄新题")
            }
            IconButton(
                onClick = onGalleryAttachment,
                enabled = enabled,
                modifier = Modifier.testTag("tutor_chat_gallery"),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = "从相册选择")
            }
            IconButton(
                onClick = onLibraryAttachment,
                enabled = enabled,
                modifier = Modifier.testTag("tutor_chat_library"),
            ) {
                Icon(Icons.Outlined.AutoStories, contentDescription = "从错题本选择")
            }
            TutorGuidanceModeControl(
                mode = explanationMode,
                onModeChange = onExplanationModeChange,
            )
        }
    }
}

@Composable
internal fun TutorGuidanceModeControl(
    mode: TutorExplanationMode,
    onModeChange: (TutorExplanationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val guided = mode == TutorExplanationMode.GUIDED
    TextButton(
        onClick = {
            onModeChange(
                if (guided) TutorExplanationMode.DIRECT else TutorExplanationMode.GUIDED,
            )
        },
        modifier = modifier
            .semantics {
                role = Role.Switch
                selected = guided
            }
            .testTag("tutor_guidance_toggle"),
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (guided) JadeActive else InkSecondary,
        )
        Text(
            text = "引导",
            modifier = Modifier.padding(start = 6.dp),
            color = if (guided) JadeActive else InkSecondary,
        )
    }
}
