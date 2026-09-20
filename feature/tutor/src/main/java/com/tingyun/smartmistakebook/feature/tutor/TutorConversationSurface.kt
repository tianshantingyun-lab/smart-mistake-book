package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.ModelLiveKind
import com.tingyun.smartmistakebook.core.model.ModelLiveText
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import com.tingyun.smartmistakebook.core.ui.ThinkingCollapsibleCard
import com.tingyun.smartmistakebook.core.ui.TutorStreamingReply
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 在途一轮的实时文本：思考链 / 回答正文 / 工具进度，三条都来自 [ModelTaskRepository.observeLiveText]
 * 那条逐 token 通道。
 *
 * 消灭的失败：大厅与会话曾经各有一套"在途状态"——大厅接的是实时通道，会话读的是快照里那个
 * 旧的状态串（`streamingReplyBody`），于是同一屏里会话侧的正文最多只能一帧一帧地跳、思考链
 * 与工具调用根本不出现。现在两边共用这一个状态与同一条订阅，学生看到的是"长出来"的回答。
 */
internal data class TutorLiveTurn(
    val thinking: String? = null,
    val answer: String? = null,
    val toolNote: String? = null,
) {
    /** 思考卡正文：思考链与工具进度合在一起；两条都空时返回 null。 */
    val thinkingText: String?
        get() = listOfNotNull(thinking, toolNote)
            .filter(String::isNotBlank)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")

    /** 回答是否已经开始写：一开始写，思考卡就从"正在思考…"收起。 */
    val hasAnswer: Boolean
        get() = !answer.isNullOrBlank()

    companion object {
        val EMPTY = TutorLiveTurn()
    }
}

/**
 * 把一条实时文本折进在途状态：同一个请求上后到的通道覆盖前一条，没被覆盖的那条保留
 * （回答开始写之后，思考链仍然留在卡里，供学生回看）。
 */
internal fun TutorLiveTurn.withLive(live: ModelLiveText?): TutorLiveTurn = when (live?.kind) {
    ModelLiveKind.THINKING -> copy(thinking = live.text)
    ModelLiveKind.ANSWER -> copy(answer = live.text)
    ModelLiveKind.TOOL -> copy(toolNote = live.text)
    null -> this
}

/**
 * 唯一的一条实时流：网关把逐 token 文本发到这条通道上（不落库、不计事件上限），这里把它折成
 * [TutorLiveTurn] 交给界面。大厅与会话都从这里读，不再各自实现一遍。
 */
internal suspend fun ModelTaskRepository.observeTutorLiveTurn(
    requestId: String,
    onTurn: (TutorLiveTurn) -> Unit,
) {
    var turn = TutorLiveTurn.EMPTY
    observeLiveText(requestId).collect { live ->
        val next = turn.withLive(live)
        if (next != turn) {
            turn = next
            onTurn(next)
        }
    }
}

/**
 * 订阅"当前在途那一轮"的实时文本：请求标识一变就换订阅，不再在途时清空。
 *
 * 订阅放在组合里而不是某一条派发路径的协程里：会话有三条派发路径（新发送 / 失败重试 / 恢复
 * 未完成任务），只接其中一条就会出现"哪条路径忘了接"——那正是大厅此前自己踩过的坑
 * （正常发送路径从不读实时状态，只有"继续回复"看得到）。
 */
@Composable
internal fun rememberTutorLiveTurn(
    modelTasks: ModelTaskRepository,
    inFlightRequestId: String?,
): TutorLiveTurn {
    var turn by remember(inFlightRequestId) { mutableStateOf(TutorLiveTurn.EMPTY) }
    LaunchedEffect(modelTasks, inFlightRequestId) {
        val requestId = inFlightRequestId ?: return@LaunchedEffect
        modelTasks.observeTutorLiveTurn(requestId) { live -> turn = live }
    }
    return turn
}

/** 生成中的任务状态：这些状态下任务快照自带的状态文本值得显示（还没有逐 token 文本时）。 */
internal val TUTOR_LIVE_TASK_STATUSES = setOf(
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
)

/**
 * 生成中的快照 → 思考卡的兜底状态行。
 *
 * 这是原 `ModelTaskSnapshot.streamingReplyBody()` 的位置。它此前只认 STREAMING，且在大厅与会话
 * 两处被当成两个不同的东西：大厅当"模型正在做什么"的状态行，会话当**回答正文**。合并成一条
 * 实时流之后，正文一律来自 [ModelTaskRepository.observeLiveText] 的 ANSWER 通道，快照只提供这
 * 一行兜底文本——所以状态集取两处的并集（大厅原本就覆盖 WAITING/QUEUED/RUNNING）。
 */
internal fun ModelTaskSnapshot.tutorLiveStatusText(): String? {
    if (status !in TUTOR_LIVE_TASK_STATUSES) return null
    return when (request.input) {
        is TutorRespondInput, is TutorLobbyInput -> userMessage.takeIf { it.isNotBlank() }
        else -> null
    }
}

/**
 * 在途状态：思考卡（思考链 + 工具进度，自动展开、逐 token 增长；回答一开始写就收起）+
 * 逐 token 的回答正文（[TutorStreamingReply]）+ 什么都还没到时的一句占位。
 *
 * 大厅与会话共用这一个实现；两处各自的 testTag 由调用方传入，被测试按住的标签继续存在。
 */
@Composable
internal fun TutorLiveTurnBlock(
    turn: TutorLiveTurn,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    placeholder: String = TUTOR_LIVE_PLACEHOLDER,
    answerTestTag: String = "tutor_streaming_reply",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val thinkingText = turn.thinkingText ?: statusText
        ThinkingCollapsibleCard(
            thinkingMarkdown = thinkingText,
            thinking = !turn.hasAnswer,
        )
        turn.answer?.takeIf(String::isNotBlank)?.let { answer ->
            TutorStreamingReply(
                markdown = answer,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .testTag(answerTestTag),
            )
        }
        if (thinkingText == null && !turn.hasAnswer) {
            TutorPrompt(
                text = placeholder,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

internal const val TUTOR_LIVE_PLACEHOLDER = "正在回复…"

/**
 * 失败卡（唯一实现）：一句正文 + 可选的原因 + 至多两个出口（检查模型设置 / 重新发送 / 重试）。
 *
 * 出口的文案与 testTag 由调用方给：大厅有「检查模型设置 / 重新发送」，会话有「检查模型设置 /
 * 重试」，这些标签被测试按住，不能因为合并而改名。
 */
@Composable
internal fun TutorTurnFailureCard(
    detail: String,
    modifier: Modifier = Modifier,
    reason: String? = null,
    reasonTestTag: String? = null,
    primaryActionLabel: String? = null,
    primaryActionTestTag: String = "tutor_failure_action",
    onPrimaryAction: () -> Unit = {},
    secondaryActionLabel: String? = null,
    secondaryActionTestTag: String = "tutor_failure_secondary_action",
    onSecondaryAction: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ErrorWarm.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ErrorWarm.copy(alpha = 0.36f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = detail,
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            reason?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .then(
                            if (reasonTestTag != null) {
                                Modifier.testTag(reasonTestTag)
                            } else {
                                Modifier
                            },
                        ),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            primaryActionLabel?.let { label ->
                OutlineActionChip(
                    text = label,
                    onClick = onPrimaryAction,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag(primaryActionTestTag),
                )
            }
            secondaryActionLabel?.let { label ->
                OutlineActionChip(
                    text = label,
                    onClick = onSecondaryAction,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag(secondaryActionTestTag),
                )
            }
        }
    }
}

/**
 * 讲题页面的唯一屏幕组件：标题栏 + 列表（自动跟随最新）+ 在途状态 + 输入区。
 *
 * 消灭的失败：大厅此前是一个自己拼的 `Column`（`RootPageLazyColumn` + 分隔线 + 另一个输入组件），
 * 会话是这一套 `TutorConversationFrame`——同一个页面的两种状态长得不一样，而且大厅没有"跟随最新"
 * 这条逻辑：回答到达后画面停在旧位置（真机截图确认）。两处现在都是这一个组件。
 *
 * @param liveTurn 当前在途的一轮；null 表示没有在途（不渲染在途区）。
 * @param liveStatusText 还没有逐 token 文本时的兜底状态行（来自任务快照）。
 * @param liveAnswerTestTag 逐 token 回答正文的标签：大厅 / 会话各有一个被测试按住的标签。
 */
@Composable
internal fun TutorConversationFrame(
    header: @Composable () -> Unit,
    autoScrollVersion: Any?,
    forceFollowToken: Any? = null,
    blockAutoFollowToken: Any? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    listViewportModifier: Modifier = Modifier,
    liveTurn: TutorLiveTurn? = null,
    liveStatusText: String? = null,
    livePlaceholder: String = TUTOR_LIVE_PLACEHOLDER,
    liveAnswerTestTag: String = "tutor_streaming_reply",
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

    val coroutineScope = rememberCoroutineScope()

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
            ) {
                content()
                if (liveTurn != null) {
                    item("tutor_live_turn") {
                        TutorLiveTurnBlock(
                            turn = liveTurn,
                            statusText = liveStatusText,
                            placeholder = livePlaceholder,
                            answerTestTag = liveAnswerTestTag,
                        )
                    }
                }
            }
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
            ScrollToBottomButton(
                onClick = {
                    followsTail = true
                    forceFollowToken?.let { handledForceToken = it }
                    coroutineScope.launch {
                        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp)
                    .testTag("tutor_scroll_to_bottom"),
            )
        }
    }
}

@Composable
private fun ScrollToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = JadeActive,
        contentColor = Paper,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "回到最新",
        )
    }
}

/**
 * 讲题页面的唯一输入区。大厅与会话共用：同一个输入框、同一个发送按钮、同一套附图入口。
 *
 * 消灭的失败：两处此前是两个组件——大厅那个在纯图消息（正文为空、只带了图）时发送键是灰的
 * （`value.isNotBlank()` 才可点），而两边的发送入口本来就都允许纯图发送；同一个页面里"能不能
 * 发出去"取决于学生从哪个状态进来。
 */
@Composable
internal fun TutorChatComposer(
    value: String,
    enabled: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "问这道题，或说出你卡住的步骤",
    /** 非 null 时显示左侧“+”按钮并调用它打开添加菜单（拍照 / 相册 / 从错题库选择）。 */
    onOpenAttachMenu: (() -> Unit)? = null,
    /** 输入框上方的附件预览行（微信式，可选）。 */
    attachmentPreview: (@Composable () -> Unit)? = null,
    /** 已选好待发送的附件数：纯图消息也能发出（正文由调用方补一句兜底文本）。 */
    attachmentCount: Int = 0,
) {
    val canSend = enabled && !sending && (value.isNotBlank() || attachmentCount > 0)
    Column(modifier = modifier.fillMaxWidth()) {
        attachmentPreview?.invoke()
        OutlinedTextField(
            value = value,
            onValueChange = { changed ->
                onValueChange(changed.take(TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS))
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tutor_chat_composer"),
            enabled = enabled,
            placeholder = { Text(placeholder) },
            minLines = 1,
            maxLines = 4,
            leadingIcon = onOpenAttachMenu?.let { openAttachMenu ->
                {
                    IconButton(
                        onClick = openAttachMenu,
                        enabled = enabled,
                        modifier = Modifier.testTag("tutor_chat_attach"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "添加图片",
                            tint = JadeActive,
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { if (canSend) onSend() },
            ),
            trailingIcon = {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
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
}
