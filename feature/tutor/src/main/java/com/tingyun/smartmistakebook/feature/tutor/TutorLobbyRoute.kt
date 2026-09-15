package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImage
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand
import com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand
import com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.MAX_TUTOR_MESSAGE_IMAGES
import com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorMessage
import com.tingyun.smartmistakebook.core.domain.TutorMessageRole
import com.tingyun.smartmistakebook.core.domain.TutorMessageStatus
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.ActionType
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.AppFailureCode
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.Retryability
import com.tingyun.smartmistakebook.core.model.appFailure
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageLazyColumn
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

/** 输入框附件预览：本地 uri + 学生选择来源（用于发送前的顺序保持）。 */
internal data class PendingLobbyImage(
    val localUri: String,
)

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
    imageIntake: LobbyMessageImageIntake? = null,
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
    val persistedTasks by remember(modelTasks, activeConversationId) {
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
    var sendError by remember { mutableStateOf<AppFailure?>(null) }
    // 发送是本组合内的瞬时行为，不应跨进程/重建保留——残留 true 会永久锁死输入框。
    var sendInFlight by remember { mutableStateOf(false) }
    // 最近一次发送失败的消息文本：可重试失败的"重试"按钮据此重发。
    var lastFailedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var resumingTaskId by remember { mutableStateOf<String?>(null) }
    var draftPersistJob by remember { mutableStateOf<Job?>(null) }
    // 会话任务可能因离开页面被取消而停在非终态（协程已死、DB 无终态）：
    // 这样的任务必须给出"继续回复"的恢复出口，否则输入框永久禁用。
    val stalledTask = conversationTasks.lastOrNull { task ->
        task.status in setOf(
            ModelTaskStatus.WAITING_FOR_MODEL,
            ModelTaskStatus.QUEUED,
            ModelTaskStatus.RUNNING,
            ModelTaskStatus.STREAMING,
        )
    }
    val hasActiveTask = stalledTask != null || sendInFlight || resumingTaskId != null

    // 消息附图（学生裁定：加号打开"拍照/相册"二选一，一次最多 9 张）。
    val context = LocalContext.current
    var pendingImages by remember { mutableStateOf<List<PendingLobbyImage>>(emptyList()) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var pendingCameraImageUri by remember { mutableStateOf<String?>(null) }
    val lobbyImageEnabled = imageIntake != null

    fun remainingImageSlots(): Int = MAX_TUTOR_MESSAGE_IMAGES - pendingImages.size

    fun addPendingImages(uris: List<String>) {
        if (uris.isEmpty()) return
        pendingImages = (pendingImages + uris.map { PendingLobbyImage(it) })
            .take(MAX_TUTOR_MESSAGE_IMAGES)
    }

    val lobbyCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingCameraImageUri
        pendingCameraImageUri = null
        if (saved && uri != null) {
            addPendingImages(listOf(uri))
        }
    }
    val lobbyGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_TUTOR_MESSAGE_IMAGES),
    ) { selected ->
        addPendingImages(selected.take(remainingImageSlots()).map { it.toString() })
    }

    fun launchLobbyCamera() {
        val directory = File(context.cacheDir, "captured_images").apply {
            if (!isDirectory) mkdirs()
        }
        val file = File(directory, "lobby-${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.capture.fileprovider",
            file,
        )
        pendingCameraImageUri = uri.toString()
        lobbyCameraLauncher.launch(uri)
    }

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

    fun resumeStalledTask() {
        val task = stalledTask ?: return
        val conversationId = activeConversationId
        if (conversationId.isBlank() || resumingTaskId != null || sendInFlight) return
        resumingTaskId = task.request.requestId
        sendError = null
        scope.launch {
            try {
                var terminalHandled = false
                val replyTo = conversationMessages.lastOrNull {
                    it.role == TutorMessageRole.STUDENT
                }
                val assistantOrdinal =
                    (conversationSnapshot?.conversation?.lastTurnOrdinal ?: 0) + 1
                modelTasks.execute(task.request).collect { current ->
                    if (terminalHandled) return@collect
                    val output = current.output as? TutorLobbyOutput
                    when {
                        current.status == ModelTaskStatus.SUCCEEDED && output != null -> {
                            terminalHandled = true
                            conversations.appendAssistantMessage(
                                AppendTutorAssistantMessageCommand(
                                    conversationId = conversationId,
                                    messageId = "tutor-message:${UUID.randomUUID()}",
                                    ordinal = assistantOrdinal,
                                    replyToMessageId = replyTo?.messageId,
                                    bodyMarkdown = output.messageMarkdown,
                                    logicalOperationId = task.request.requestId,
                                    status = TutorMessageStatus.SUCCEEDED,
                                    createdAtEpochMillis = current.updatedAtEpochMillis,
                                    completedAtEpochMillis = current.updatedAtEpochMillis,
                                    errorCode = null,
                                ),
                            )
                        }
                        current.status in setOf(
                            ModelTaskStatus.RETRYABLE_FAILURE,
                            ModelTaskStatus.PERMANENT_FAILURE,
                            ModelTaskStatus.CANCELLED,
                        ) -> {
                            terminalHandled = true
                            conversations.appendAssistantMessage(
                                AppendTutorAssistantMessageCommand(
                                    conversationId = conversationId,
                                    messageId = "tutor-message:${UUID.randomUUID()}",
                                    ordinal = assistantOrdinal,
                                    replyToMessageId = replyTo?.messageId,
                                    bodyMarkdown = "这次回复没有准备好，你的消息已经保留。",
                                    logicalOperationId = task.request.requestId,
                                    status = TutorMessageStatus.FAILED,
                                    createdAtEpochMillis = current.updatedAtEpochMillis,
                                    completedAtEpochMillis = current.updatedAtEpochMillis,
                                    errorCode = current.failure?.code?.name,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("TutorLobby", "Failed to resume stalled reply", e)
                sendError = appFailure(
                    code = AppFailureCode.NETWORK_UNAVAILABLE,
                    title = "暂时没有恢复成功",
                    message = "这条回复还没有完成，可以再试一次。",
                    dataPreserved = true,
                    retryability = Retryability.RETRYABLE,
                    primaryAction = ActionType.RETRY,
                )
            } finally {
                resumingTaskId = null
            }
        }
    }

    fun startMessage(message: String, approvedAtEpochMillis: Long) {
        val currentProvider = provider
        if (currentProvider == null && !providerLoadFailed) {
            // 首帧：模型能力还没读回来，别把它误报成"当前模型不能处理对话"。
            sendError = appFailure(
                code = AppFailureCode.TUTOR_PROVIDER_UNAVAILABLE,
                title = "正在读取模型配置",
                message = "模型配置还在读取中，稍等片刻再发送。",
                dataPreserved = true,
                retryability = Retryability.RETRYABLE,
                primaryAction = ActionType.RETRY,
            )
            return
        }
        if (currentProvider == null || !currentProvider.supports(ModelTaskKind.TUTOR_LOBBY)) {
            sendError = appFailure(
                code = if (providerLoadFailed) {
                    AppFailureCode.PROVIDER_NOT_CONFIGURED
                } else {
                    AppFailureCode.PROVIDER_CAPABILITY_MISMATCH
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
                dataPreserved = true,
                primaryAction = ActionType.OPEN_SETTINGS,
            )
            return
        }
        if (sendInFlight || resumingTaskId != null) return
        val sentImages = pendingImages
        if (sentImages.isNotEmpty() && currentProvider.supportsImageInput != true) {
            sendError = appFailure(
                code = AppFailureCode.PROVIDER_CAPABILITY_MISMATCH,
                title = "当前模型不支持看图",
                message = "去掉图片或更换支持图片的模型后再发送。",
                dataPreserved = true,
                primaryAction = ActionType.OPEN_SETTINGS,
            )
            return
        }
        sendInFlight = true
        if (message.isNotBlank()) lastFailedMessage = message
        val occurredAt = System.currentTimeMillis()
        val currentConversationId = activeConversationId

        scope.launch {
            try {
                // 附图与拍照/讲题同口径：学生选择图片并点发送本身就是本次知情，
                // 不再弹确认卡（配置模型 = 唯一条件）。
                val intake = imageIntake
                draft = ""
                sendError = null
                // 发送时登记：图片字节进私有资产库并拿到 egress 证明所需的哈希/尺寸。
                val imageAssets = if (sentImages.isNotEmpty() && intake != null) {
                    sentImages.map { pending ->
                        intake.registerImage(pending.localUri, System.currentTimeMillis())
                    }
                } else {
                    emptyList()
                }
                // 纯图消息给一句可读的兜底文本（消息体不能为空）。
                val effectiveMessage = message.ifBlank { "请帮我看看这些图片。" }
                // 活跃会话可能已被用户从历史页删除：先确认存在，不存在则静默
                // 开新会话，消息照常发出——而不是向外键冲突抛错、谎称已保留。
                val freshSnapshot = currentConversationId.takeIf { it.isNotBlank() }?.let {
                    runCatching {
                        conversations.observeConversation(it).first()
                    }.getOrNull()
                }
                val conversationId: String
                val lastTurnOrdinal: Int
                val freshMessages: List<TutorMessage>
                if (freshSnapshot != null) {
                    conversationId = currentConversationId
                    lastTurnOrdinal = freshSnapshot.conversation.lastTurnOrdinal
                    freshMessages = freshSnapshot.messages
                } else {
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
                    conversationId = created.conversationId
                    lastTurnOrdinal = created.lastTurnOrdinal
                    freshMessages = emptyList()
                    activeConversationId = conversationId
                }
                scope.launch {
                    conversations.clearDraft(
                        ClearTutorConversationDraftCommand(
                            conversationId = conversationId,
                            occurredAtEpochMillis = occurredAt,
                        ),
                    )
                }
                val studentOrdinal = lastTurnOrdinal + 1
                val assistantOrdinal = studentOrdinal + 1
                val logicalTurnOrdinal = (lastTurnOrdinal / 2) + 1
                val messageId = "tutor-message:${UUID.randomUUID()}"
                val logicalOperationId = "tutor-lobby-op:${UUID.randomUUID()}"
                val studentMessage = conversations.appendStudentMessage(
                    AppendTutorStudentMessageCommand(
                        conversationId = conversationId,
                        messageId = messageId,
                        ordinal = studentOrdinal,
                        bodyMarkdown = effectiveMessage,
                        logicalOperationId = logicalOperationId,
                        createdAtEpochMillis = occurredAt,
                        sourceImageAssetIds = imageAssets.map { it.assetId },
                    ),
                )
                pendingImages = emptyList()
                lastFailedMessage = null
                val request = buildTutorLobbyRequest(
                    provider = currentProvider,
                    conversationId = conversationId,
                    messageOrdinal = logicalTurnOrdinal,
                    studentMessage = effectiveMessage,
                    priorMessages = freshMessages.toLobbyHistory(),
                    occurredAtEpochMillis = occurredAt,
                    approvedAtEpochMillis = approvedAtEpochMillis,
                    imageAssets = imageAssets,
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
                sendError = appFailure(
                    code = AppFailureCode.NETWORK_UNAVAILABLE,
                    title = "这条消息已经保留",
                    message = "这条消息已经保留，但暂时没有发出去。",
                    dataPreserved = true,
                    retryability = Retryability.RETRYABLE,
                    primaryAction = ActionType.RETRY,
                )
            } finally {
                sendInFlight = false
            }
        }
    }

    fun submitDraft() {
        val message = draft.trim()
        val hasImages = pendingImages.isNotEmpty()
        if ((message.isBlank() && !hasImages) || hasActiveTask || sendInFlight) return
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
                    imageIntake = imageIntake,
                    onOpenCapabilitySettings = onOpenCapabilitySettings,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (stalledTask != null && resumingTaskId == null && !sendInFlight) {
                item(key = "lobby-stalled") {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "上一条回复没有完成",
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlineActionChip(
                            text = "继续回复",
                            onClick = ::resumeStalledTask,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .testTag("tutor_lobby_resume"),
                        )
                    }
                }
            }
            if (resumingTaskId != null) {
                item(key = "lobby-resuming") {
                    TutorPrompt(
                        text = "正在回复…",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
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
                    if (message.primaryAction?.actionType == ActionType.RETRY) {
                        OutlineActionChip(
                            text = "重试",
                            onClick = {
                                lastFailedMessage?.let { text ->
                                    startMessage(text, System.currentTimeMillis())
                                }
                            },
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .testTag("tutor_lobby_retry_send"),
                        )
                    } else {
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
                onOpenAttachMenu = if (lobbyImageEnabled) {
                    { attachMenuOpen = true }
                } else {
                    null
                },
                attachmentPreview = if (pendingImages.isNotEmpty()) {
                    {
                        PendingImagesRow(
                            images = pendingImages,
                            onRemove = { index ->
                                pendingImages = pendingImages.filterIndexed { i, _ -> i != index }
                            },
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .widthIn(max = SmartDimens.MaximumContentWidth)
                    .padding(
                        horizontal = SmartDimens.ContentHorizontalPadding,
                        vertical = 8.dp,
                    ),
            )
        }
    }
    if (attachMenuOpen) {
        AlertDialog(
            onDismissRequest = { attachMenuOpen = false },
            title = { Text("添加图片") },
            text = { Text("拍一张新照片，或从相册选择（最多 ${MAX_TUTOR_MESSAGE_IMAGES} 张）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        attachMenuOpen = false
                        launchLobbyCamera()
                    },
                    modifier = Modifier.testTag("lobby_attach_camera"),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Text("拍照", modifier = Modifier.padding(start = 6.dp))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        attachMenuOpen = false
                        lobbyGalleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.testTag("lobby_attach_gallery"),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text("从相册选择", modifier = Modifier.padding(start = 6.dp))
                }
            },
        )
    }
}

/** 气泡内的消息图片行：从规范资产解析本地 uri 后内嵌渲染（微信式）。 */
@Composable
private fun LobbyMessageImagesRow(
    assetIds: List<String>,
    imageIntake: LobbyMessageImageIntake,
) {
    val uris by produceState<Map<String, String?>>(emptyMap(), assetIds, imageIntake) {
        val resolved = buildMap {
            for (assetId in assetIds) {
                put(assetId, imageIntake.resolveImageUri(assetId))
            }
        }
        value = resolved
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        assetIds.forEachIndexed { index, assetId ->
            val uri = uris[assetId]
            if (uri != null) {
                BoundedLocalImage(
                    imageUri = uri,
                    contentDescription = "消息图片 ${index + 1}",
                    expanded = false,
                    collapsedMaxHeight = 140.dp,
                    modifier = Modifier
                        .size(width = 120.dp, height = 120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .testTag("lobby_message_image_$index"),
                )
            } else {
                Text(
                    text = "图片暂时打不开",
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 输入框上方的附件预览行（微信式）：缩略图 + 删除角标。 */
@Composable
private fun PendingImagesRow(
    images: List<PendingLobbyImage>,
    onRemove: (Int) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SmartDimens.ContentHorizontalPadding, vertical = 4.dp)
            .testTag("lobby_pending_images"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(images.size) { index ->
            Box {
                BoundedLocalImage(
                    imageUri = images[index].localUri,
                    contentDescription = "待发送图片 ${index + 1}",
                    expanded = false,
                    collapsedMaxHeight = 72.dp,
                    modifier = Modifier
                        .size(width = 72.dp, height = 72.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Surface(
                    color = Paper,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clickable { onRemove(index) }
                        .testTag("lobby_pending_image_remove_$index"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "移除这张图片",
                        tint = InkSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorLobbyMessageItem(
    message: TutorMessage,
    imageIntake: LobbyMessageImageIntake? = null,
    onOpenCapabilitySettings: () -> Unit = {},
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
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    if (message.sourceImageAssetIds.isNotEmpty() && imageIntake != null) {
                        LobbyMessageImagesRow(
                            assetIds = message.sourceImageAssetIds,
                            imageIntake = imageIntake,
                        )
                    }
                    Text(
                        text = message.bodyMarkdown,
                        modifier = Modifier.padding(
                            top = if (message.sourceImageAssetIds.isEmpty()) 0.dp else 8.dp,
                        ),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.bodyMarkdown,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                lobbyFailureReasonText(message.errorCode)?.let { reason ->
                    Text(
                        text = reason,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("tutor_lobby_failure_reason"),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val failureCode = message.errorCode
                    ?.let { raw -> runCatching { ModelFailureCode.valueOf(raw) }.getOrNull() }
                if (failureCode?.requiresModelSettings() == true) {
                    OutlineActionChip(
                        text = "检查模型设置",
                        onClick = onOpenCapabilitySettings,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("tutor_lobby_failure_open_model_settings"),
                    )
                }
            }
        }
        else -> TutorPrompt(
            text = message.bodyMarkdown,
            modifier = modifier.testTag("tutor_lobby_task_progress"),
        )
    }
}

/**
 * Lobby 回复失败的补充说明（纯函数，便于单测）：把内部失败码翻译成学生能
 * 行动的一句话；无需额外说明的失败返回 null（通用文案已足够）。
 */
internal fun lobbyFailureReasonText(errorCode: String?): String? = when (errorCode) {
    "AUTHENTICATION_FAILED" -> "API Key 可能已失效，检查后重新发送。"
    "PROVIDER_NOT_CONFIGURED", "MODEL_NOT_CONFIGURED" -> "当前还没有可用的模型配置。"
    "PROVIDER_CAPABILITY_MISSING" -> "当前模型不支持这项对话能力。"
    "TIMEOUT" -> "模型响应超时，可以再发一次。"
    "NETWORK_UNAVAILABLE" -> "网络暂时不可用，可以稍后再试。"
    "SERVICE_UNAVAILABLE" -> "模型服务暂时不可用，可以稍后再试。"
    "RATE_LIMITED" -> "请求太频繁，稍等片刻再发。"
    "INVALID_RESPONSE" -> "模型这次返回的内容无法使用，可以再发一次。"
    else -> null
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
