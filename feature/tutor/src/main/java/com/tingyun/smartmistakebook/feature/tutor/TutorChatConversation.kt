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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
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
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class TutorRespondExchangeKey(
    val sessionId: String,
    val revisionNumber: Int,
    val questionDocumentId: String,
    val responseOrdinal: Int,
    val studentMessage: String,
    val visibleTutorContextMarkdown: String?,
    val priorMessages: List<TutorChatHistoryEntry>,
    val requestedMove: TutorMoveType?,
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
)

internal fun latestTutorRespondTasks(tasks: List<ModelTaskSnapshot>): List<ModelTaskSnapshot> = tasks
    .filter { it.request.input is TutorRespondInput }
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

internal fun ModelTaskSnapshot.canRetryTutorResponse(): Boolean =
    request.input is TutorRespondInput && status == ModelTaskStatus.RETRYABLE_FAILURE

private fun ModelTaskSnapshot.requiresTutorModelSettings(): Boolean {
    val code = failure?.code ?: return false
    return code.requiresModelSettings()
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
    resolvedVisualScene: TutorVisualDocumentScene? = null,
    awaitingContinuation: Boolean = false,
    interactionEnabled: Boolean,
    recoveryEnabled: Boolean,
    executionMatchesCurrentProvider: Boolean,
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onMove: (TutorSuggestedMove) -> Unit,
    onRevealSolution: (TutorSuggestedMove) -> Unit,
    localIntentContent: @Composable (TutorRespondInput, TutorRespondOutput) -> Unit = { _, _ -> },
    onOpenVisualOriginal: () -> Unit = {},
    onReportVisualIncorrect: (String) -> Unit = {},
    assistantBottomModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val input = task.request.input as TutorRespondInput
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TutorStudentMessageBubble(
            message = input.studentMessage,
            modifier = Modifier.testTag("tutor_chat_user_${input.responseOrdinal}"),
        )
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
            localIntentContent = localIntentContent,
            resolvedVisualScene = resolvedVisualScene,
            onOpenVisualOriginal = onOpenVisualOriginal,
            onReportVisualIncorrect = onReportVisualIncorrect,
            assistantBottomModifier = assistantBottomModifier,
        )
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
    localIntentContent: @Composable (TutorRespondInput, TutorRespondOutput) -> Unit,
    resolvedVisualScene: TutorVisualDocumentScene?,
    onOpenVisualOriginal: () -> Unit,
    onReportVisualIncorrect: (String) -> Unit,
    assistantBottomModifier: Modifier,
) {
    val input = task.request.input as TutorRespondInput
    val output = task.output as? TutorRespondOutput
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
                            )
                            resolvedVisualScene?.let { scene ->
                                TutorVisualSceneRenderer(
                                    scene = scene,
                                    onOpenOriginal = onOpenVisualOriginal,
                                    onReportIncorrect = {
                                        onReportVisualIncorrect(scene.sceneId)
                                    },
                                )
                            }
                            localIntentContent(input, output)
                            output.visualScene?.let { TutorVisualSceneRenderer(it) }
                            if (output.solutionRevealed) {
                                Box(
                                    modifier = Modifier
                                        .size(1.dp)
                                        .testTag("tutor_chat_assistant_bottom_${input.responseOrdinal}")
                                        .then(assistantBottomModifier),
                                )
                            }
                            if (showActions) {
                                output.suggestedMoves.forEach { move ->
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
                            showActions && task.canRetryTutorResponse() -> "重试"
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
    forceFollowToken: Any? = null,
    blockAutoFollowToken: Any? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    listViewportModifier: Modifier = Modifier,
    composer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    var initialTailPositioned by remember(listState) { mutableStateOf(false) }
    var followsTail by remember(listState) { mutableStateOf(true) }
    var handledForceToken by remember(listState) { mutableStateOf<Any?>(null) }
    var handledBlockToken by remember(listState) { mutableStateOf<Any?>(null) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            val atEnd = layout.totalItemsCount == 0 || lastVisible == layout.totalItemsCount - 1
            listState.isScrollInProgress to atEnd
        }.collect { (scrolling, atEnd) ->
            if (scrolling) {
                followsTail = atEnd
            } else if (atEnd) {
                followsTail = true
            }
        }
    }
    LaunchedEffect(autoScrollVersion, forceFollowToken, blockAutoFollowToken, listState) {
        val forceFollow = forceFollowToken != null && forceFollowToken != handledForceToken
        val blockAutoFollow = initialTailPositioned &&
            blockAutoFollowToken != null &&
            blockAutoFollowToken != handledBlockToken
        val shouldFollow = !initialTailPositioned ||
            !blockAutoFollow && (followsTail || forceFollow)
        withFrameNanos { }
        val itemCount = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        if (shouldFollow) {
            listState.scrollToItem(itemCount - 1)
            followsTail = true
        } else if (blockAutoFollow) {
            followsTail = false
        }
        initialTailPositioned = true
        if (forceFollowToken != null) handledForceToken = forceFollowToken
        if (blockAutoFollowToken != null) handledBlockToken = blockAutoFollowToken
    }

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
                Surface(color = Paper, shadowElevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
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
        if (!followsTail && initialTailPositioned) {
            FloatingActionButton(
                onClick = {
                    followsTail = true
                    forceFollowToken?.let { handledForceToken = it }
                    val itemCount = listState.layoutInfo.totalItemsCount
                    if (itemCount > 0) {
                        kotlinx.coroutines.MainScope().launch {
                            listState.scrollToItem(itemCount - 1)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp)
                    .testTag("tutor_scroll_to_bottom"),
                containerColor = JadeActive,
                contentColor = Paper,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowDown,
                    contentDescription = "回到最新",
                )
            }
        }
    }
}

@Composable
internal fun TutorChatComposer(
    value: String,
    enabled: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { changed ->
            onValueChange(changed.take(TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS))
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag("tutor_chat_composer"),
        enabled = enabled,
        placeholder = { Text("问这道题，或说出你卡住的步骤") },
        minLines = 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(
            onSend = { if (enabled && value.isNotBlank() && !sending) onSend() },
        ),
        trailingIcon = {
            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank() && !sending,
                modifier = Modifier.testTag("tutor_chat_send"),
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
        supportingText = if (value.length >= 1_000) {
            { Text("${value.length}/${TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS}") }
        } else {
            null
        },
        shape = RoundedCornerShape(14.dp),
    )
}
