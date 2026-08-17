package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.AppErrorCode
import com.tingyun.smartmistakebook.core.model.RecoveryAction
import com.tingyun.smartmistakebook.core.model.UserRecoverableError
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.userRecoverableError
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
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageLazyColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun TutorLobbyRoute(
    onCapture: () -> Unit,
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    conversations: TutorConversationRepository,
    modelTasks: ModelTaskRepository,
    catalogEntries: List<StudyCatalogEntry>,
    profile: StudyProfileOverview,
    initialConversationId: String? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var activeConversationId by rememberSaveable {
        mutableStateOf(initialConversationId.orEmpty())
    }
    val conversationSnapshot by remember(activeConversationId) {
        if (activeConversationId.isBlank()) {
            flowOf(null)
        } else {
            conversations.observeConversation(activeConversationId)
        }
    }.collectAsState(initial = null)
    val conversationMessages = conversationSnapshot?.messages.orEmpty()
    val persistedTasks by remember(modelTasks) {
        if (activeConversationId.isBlank()) {
            flowOf(emptyList())
        } else {
            modelTasks.observeRecentBySubject(
                activeConversationId,
                ModelTaskKind.TUTOR_LOBBY,
                MAX_PERSISTED_TASKS,
            )
        }
    }.collectAsState(initial = emptyList())
    val conversationTasks = remember(persistedTasks) {
        persistedTasks
            .mapNotNull { task ->
                (task.request.input as? TutorLobbyInput)?.let { input -> input.messageOrdinal to task }
            }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (_, attempts) -> attempts.maxByOrNull(ModelTaskSnapshot::updatedAtEpochMillis) }
            .sortedBy { task -> (task.request.input as TutorLobbyInput).messageOrdinal }
    }
    val visibleTasks = remember(conversationTasks) { conversationTasks.takeLast(MAX_VISIBLE_MESSAGES) }
    var provider by remember { mutableStateOf<ProviderCapabilitySnapshot?>(null) }
    var providerLoadFailed by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(activeConversationId) { mutableStateOf("") }
    var sendError by remember { mutableStateOf<UserRecoverableError?>(null) }
    var sendInFlight by rememberSaveable { mutableStateOf(false) }
    var draftPersistJob by remember { mutableStateOf<Job?>(null) }
    val hasActiveTask = conversationTasks.any { task ->
        task.status in setOf(
            ModelTaskStatus.WAITING_FOR_MODEL,
            ModelTaskStatus.QUEUED,
            ModelTaskStatus.RUNNING,
            ModelTaskStatus.STREAMING,
        )
    } || sendInFlight

    LaunchedEffect(conversations) {
        if (activeConversationId.isNotBlank()) return@LaunchedEffect
        conversations.observeRecent(MAX_RECENT_CONVERSATIONS).first { recent ->
            recent.firstOrNull { conversation ->
                conversation.anchorKind == TutorConversationAnchorKind.TEXT_ONLY &&
                    conversation.status.name !in setOf("COMPLETED", "ARCHIVED")
            }?.let { conversation ->
                activeConversationId = conversation.conversationId
                return@first true
            } ?: false
        }
    }

    LaunchedEffect(activeConversationId) {
        if (activeConversationId.isBlank()) return@LaunchedEffect
        draft = conversations.observeConversation(activeConversationId)
            .first()
            ?.conversation
            ?.studentDraft
            .orEmpty()
    }

    LaunchedEffect(modelTasks) {
        try {
            provider = modelTasks.capabilities()
            providerLoadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            providerLoadFailed = true
        }
    }

    fun startMessage(message: String, approvedAtEpochMillis: Long) {
        val currentProvider = provider
        if (currentProvider == null || !currentProvider.supports(ModelTaskKind.TUTOR_LOBBY)) {
            sendError = userRecoverableError(
                code = if (providerLoadFailed) {
                    AppErrorCode.PROVIDER_NOT_CONFIGURED
                } else {
                    AppErrorCode.PROVIDER_CAPABILITY_MISMATCH
                },
                title = if (providerLoadFailed) {
                    "暂时读不到模型配置"
                } else {
                    "当前模型还不能处理对话"
                },
                message = if (providerLoadFailed) {
                    "暂时读不到模型配置，请检查后再试。"
                } else {
                    "当前模型还不能处理对话，请先完成模型配置和能力测试。"
                },
                dataSafe = true,
                primaryAction = RecoveryAction.OPEN_SETTINGS,
            )
            return
        }
        if (sendInFlight) return
        sendInFlight = true
        val occurredAt = System.currentTimeMillis()
        val currentConversationId = activeConversationId
        if (currentConversationId.isNotBlank()) {
            scope.launch {
                conversations.clearDraft(
                    ClearTutorConversationDraftCommand(
                        conversationId = currentConversationId,
                        occurredAtEpochMillis = occurredAt,
                    ),
                )
            }
        }
        val studentOrdinal = (conversationSnapshot?.conversation?.lastTurnOrdinal ?: 0) + 1
        val assistantOrdinal = studentOrdinal + 1
        val logicalTurnOrdinal = ((conversationSnapshot?.conversation?.lastTurnOrdinal ?: 0) / 2) + 1

        draft = ""
        sendError = null

        scope.launch {
            try {
                val conversationId = if (currentConversationId.isBlank()) {
                    val created = conversations.createConversation(
                        CreateTutorConversationCommand(
                            conversationId = "tutor-conv:${UUID.randomUUID()}",
                            anchorKind = TutorConversationAnchorKind.TEXT_ONLY,
                            anchorId = null,
                            anchorRevisionId = null,
                            title = null,
                            createdAtEpochMillis = occurredAt,
                        ),
                    )
                    activeConversationId = created.conversationId
                    created.conversationId
                } else {
                    currentConversationId
                }
                val messageId = "tutor-message:${UUID.randomUUID()}"
                val logicalOperationId = "tutor-lobby-op:${UUID.randomUUID()}"
                val studentMessage = conversations.appendStudentMessage(
                    AppendTutorStudentMessageCommand(
                        conversationId = conversationId,
                        messageId = messageId,
                        ordinal = studentOrdinal,
                        bodyMarkdown = message,
                        logicalOperationId = logicalOperationId,
                        createdAtEpochMillis = occurredAt,
                    ),
                )
                val request = buildTutorLobbyRequest(
                    provider = currentProvider,
                    conversationId = conversationId,
                    messageOrdinal = logicalTurnOrdinal,
                    studentMessage = message,
                    priorMessages = conversationMessages.toLobbyHistory(),
                    occurredAtEpochMillis = occurredAt,
                    approvedAtEpochMillis = approvedAtEpochMillis,
                )

                var terminalHandled = false
                modelTasks.execute(request).collect { task ->
                    if (terminalHandled) return@collect
                    val output = task.output as? TutorLobbyOutput
                    if (task.status == ModelTaskStatus.SUCCEEDED && output != null) {
                        terminalHandled = true
                        conversations.appendAssistantMessage(
                            AppendTutorAssistantMessageCommand(
                                conversationId = conversationId,
                                messageId = "tutor-message:${UUID.randomUUID()}",
                                ordinal = assistantOrdinal,
                                replyToMessageId = studentMessage.messageId,
                                bodyMarkdown = output.messageMarkdown,
                                logicalOperationId = logicalOperationId,
                                status = TutorMessageStatus.SUCCEEDED,
                                createdAtEpochMillis = occurredAt,
                                completedAtEpochMillis = task.updatedAtEpochMillis,
                                errorCode = null,
                            ),
                        )
                    } else if (
                        task.status == ModelTaskStatus.RETRYABLE_FAILURE ||
                        task.status == ModelTaskStatus.PERMANENT_FAILURE ||
                        task.status == ModelTaskStatus.CANCELLED
                    ) {
                        terminalHandled = true
                        conversations.appendAssistantMessage(
                            AppendTutorAssistantMessageCommand(
                                conversationId = conversationId,
                                messageId = "tutor-message:${UUID.randomUUID()}",
                                ordinal = assistantOrdinal,
                                replyToMessageId = studentMessage.messageId,
                                bodyMarkdown = "这次回复没有准备好，你的消息已经保留。",
                                logicalOperationId = logicalOperationId,
                                status = TutorMessageStatus.FAILED,
                                createdAtEpochMillis = occurredAt,
                                completedAtEpochMillis = task.updatedAtEpochMillis,
                                errorCode = task.failure?.code?.name,
                            ),
                        )
                    }
                }
                if (!terminalHandled) {
                    conversations.appendAssistantMessage(
                        AppendTutorAssistantMessageCommand(
                            conversationId = conversationId,
                            messageId = "tutor-message:${UUID.randomUUID()}",
                            ordinal = assistantOrdinal,
                            replyToMessageId = studentMessage.messageId,
                            bodyMarkdown = "这条消息已经保留，暂时没有收到讲解。",
                            logicalOperationId = logicalOperationId,
                            status = TutorMessageStatus.FAILED,
                            createdAtEpochMillis = occurredAt,
                            completedAtEpochMillis = System.currentTimeMillis(),
                            errorCode = "UNKNOWN",
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("TutorLobby", "Failed to send message", e)
                sendError = userRecoverableError(
                    code = AppErrorCode.NETWORK_UNAVAILABLE,
                    title = "这条消息已经保留",
                    message = "这条消息已经保留，但暂时没有发出去。",
                    dataSafe = true,
                    primaryAction = RecoveryAction.RETRY,
                )
            } finally {
                sendInFlight = false
            }
        }
    }

    fun submitDraft() {
        val message = draft.trim()
        if (message.isBlank() || hasActiveTask || sendInFlight) return
        startMessage(message, System.currentTimeMillis())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .imePadding()
            .testTag("tutor_lobby"),
    ) {
        RootPageLazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("tutor_screen"),
        ) {
            item(key = "lobby-header") {
                TutorTopBar(
                    onOpenCapabilitySettings = onOpenCapabilitySettings,
                    onOpenHistory = onOpenHistory,
                )
                PaperDivider(Modifier.padding(top = 8.dp, bottom = 14.dp))
            }
            if (conversationMessages.isEmpty() && visibleTasks.isEmpty()) {
                item(key = "lobby-intro") {
                    TutorPrompt(
                        text = "把题目、推导或困惑发来。你也可以直接拍题，或从错题本选一道题。",
                        modifier = Modifier.testTag("tutor_empty_state"),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlineActionChip(
                            text = "拍题讲解",
                            onClick = onCapture,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tutor_capture_shortcut"),
                            contentDescription = "拍照或选择题目图片开始讲解",
                        )
                        OutlineActionChip(
                            text = "从错题本选择",
                            onClick = onChooseExisting,
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tutor_choose_existing_button"),
                        )
                    }
                }
            }
            items(
                items = conversationMessages.takeLast(MAX_VISIBLE_MESSAGES),
                key = { it.messageId },
            ) { message ->
                TutorLobbyMessageItem(
                    message = message,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (sendInFlight) {
                item(key = "lobby-sending") {
                    TutorPrompt(
                        text = "正在发送…",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            sendError?.let { message ->
                item(key = "lobby-send-error") {
                    Text(
                        text = message.message,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .testTag("tutor_lobby_send_error"),
                        color = ErrorWarm,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlineActionChip(
                        text = "检查模型设置",
                        onClick = onOpenCapabilitySettings,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("tutor_lobby_open_model_settings"),
                    )
                }
            }
        }
        PaperDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Paper),
            contentAlignment = Alignment.TopCenter,
        ) {
            TutorComposer(
                value = draft,
                onValueChange = { value ->
                    val next = value.take(TutorLobbyInput.MAX_STUDENT_MESSAGE_CHARS)
                    draft = next
                    val conversationId = activeConversationId
                    if (conversationId.isNotBlank()) {
                        draftPersistJob?.cancel()
                        draftPersistJob = scope.launch {
                            val occurredAt = System.currentTimeMillis()
                            if (next.isBlank()) {
                                conversations.clearDraft(
                                    ClearTutorConversationDraftCommand(
                                        conversationId = conversationId,
                                        occurredAtEpochMillis = occurredAt,
                                    ),
                                )
                            } else {
                                conversations.saveDraft(
                                    SaveTutorConversationDraftCommand(
                                        conversationId = conversationId,
                                        draft = next,
                                        occurredAtEpochMillis = occurredAt,
                                    ),
                                )
                            }
                        }
                    }
                },
                onCapture = onCapture,
                onSend = ::submitDraft,
                placeholder = "输入题目、困惑，或说你现在想做什么",
                enabled = !hasActiveTask && !sendInFlight,
                modifier = Modifier
                    .widthIn(max = SmartDimens.MaximumContentWidth)
                    .padding(
                        horizontal = SmartDimens.ContentHorizontalPadding,
                        vertical = 8.dp,
                    ),
            )
        }
    }
}

@Composable
private fun TutorLobbyTask(
    task: ModelTaskSnapshot,
    catalogEntries: List<StudyCatalogEntry>,
    profile: StudyProfileOverview,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val input = task.request.input as? TutorLobbyInput ?: return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 用户消息气泡：图片+文字
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = JadeSoft.copy(alpha = 0.62f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .testTag("tutor_lobby_student_message"),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(
                        text = input.studentMessage,
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        val output = task.output as? TutorLobbyOutput
        if (task.status == ModelTaskStatus.SUCCEEDED && output != null) {
            TutorPrompt(
                text = output.messageMarkdown,
                modifier = Modifier.testTag("tutor_lobby_assistant_message"),
            )
            TutorLocalIntentPanel(
                decision = output.intentDecision,
                studentMessage = input.studentMessage,
                catalogEntries = catalogEntries,
                profile = profile,
                onOpenMistakeNotebook = onOpenMistakeNotebook,
                onOpenProfile = onOpenProfile,
            )
        } else if (
            task.status == ModelTaskStatus.RETRYABLE_FAILURE ||
            task.status == ModelTaskStatus.PERMANENT_FAILURE
        ) {
            Surface(
                color = ErrorWarm.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ErrorWarm.copy(alpha = 0.36f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_lobby_task_failure"),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = task.failure?.message ?: "这次回复暂时没有准备好。",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (task.failure?.code?.requiresModelSettings() == true) {
                        OutlineActionChip(
                            text = "检查模型设置",
                            onClick = onOpenCapabilitySettings,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        } else {
            TutorPrompt(
                text = task.userMessage.ifBlank { "正在理解你的消息…" },
                modifier = Modifier.testTag("tutor_lobby_task_progress"),
            )
        }
    }
}

@Composable
private fun TutorLobbyMessageItem(
    message: TutorMessage,
    modifier: Modifier = Modifier,
) {
    if (message.role == TutorMessageRole.STUDENT) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                color = JadeSoft.copy(alpha = 0.62f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .testTag("tutor_lobby_student_message"),
            ) {
                Text(
                    text = message.bodyMarkdown,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    when (message.status) {
        TutorMessageStatus.SUCCEEDED -> TutorPrompt(
            text = message.bodyMarkdown,
            modifier = modifier.testTag("tutor_lobby_assistant_message"),
        )
        TutorMessageStatus.FAILED, TutorMessageStatus.CANCELLED -> Surface(
            color = ErrorWarm.copy(alpha = 0.08f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ErrorWarm.copy(alpha = 0.36f)),
            modifier = modifier
                .fillMaxWidth()
                .testTag("tutor_lobby_task_failure"),
        ) {
            Text(
                text = message.bodyMarkdown,
                modifier = Modifier.padding(12.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        else -> TutorPrompt(
            text = message.bodyMarkdown,
            modifier = modifier.testTag("tutor_lobby_task_progress"),
        )
    }
}

@Composable
private fun TutorLobbyDisclosureCard(
    providerName: String,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tutor_lobby_disclosure"),
        color = JadeSoft.copy(alpha = 0.4f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "发送这条消息",
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "会把这条消息和最近几轮对话发给 $providerName；不包含题图、错题内容或学习记录。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            PrimaryActionButton(
                text = "同意并发送",
                onClick = onApprove,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_lobby_disclosure_approve"),
                contentDescription = "允许向当前模型发送这条消息和最近对话",
            )
        }
    }
}

private fun List<TutorMessage>.toLobbyHistory(): List<TutorChatHistoryEntry> {
    val entries = mutableListOf<TutorChatHistoryEntry>()
    var pendingStudent: TutorMessage? = null
    sortedBy { it.ordinal }.forEach { message ->
        when (message.role) {
            TutorMessageRole.STUDENT -> pendingStudent = message
            TutorMessageRole.ASSISTANT -> {
                pendingStudent?.let { student ->
                    if (message.status == TutorMessageStatus.SUCCEEDED) {
                        entries += TutorChatHistoryEntry(
                            studentMessage = student.bodyMarkdown,
                            assistantMarkdown = message.bodyMarkdown,
                        )
                    }
                }
                pendingStudent = null
            }
            TutorMessageRole.LOCAL_EVENT -> Unit
        }
    }
    return entries.takeLast(MAX_CONTEXT_MESSAGES)
}

private const val MAX_VISIBLE_MESSAGES = 20
private const val MAX_CONTEXT_MESSAGES = 8
private const val MAX_PERSISTED_TASKS = 64
private const val MAX_RECENT_CONVERSATIONS = 20
