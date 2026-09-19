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
import com.tingyun.smartmistakebook.core.domain.TutorHistoryBudget
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
import com.tingyun.smartmistakebook.core.ui.ThinkingCollapsibleCard
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
    // 正在生成时的实时状态文本：网关把上游思考链节流转成进度消息，学生等待时就能看到它在想什么。
    var liveReplyStatus by remember { mutableStateOf<String?>(null) }
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
    var pendingImages by remember { mutableStateOf<List<PendingMessageImage>>(emptyList()) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var pendingCameraImageUri by remember { mutableStateOf<String?>(null) }
    val lobbyImageEnabled = imageIntake != null

    fun remainingImageSlots(): Int = MAX_TUTOR_MESSAGE_IMAGES - pendingImages.size

    fun addPendingImages(uris: List<String>) {
        if (uris.isEmpty()) return
        pendingImages = (pendingImages + uris.map { PendingMessageImage(it) })
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
                    // 生成中的实时状态（思考链与工具调用节流后的当前文本）→ 展开的思考卡实时显示。
                    liveReplyStatus = current.userMessage.takeIf {
                        it.isNotBlank() && current.status in LIVE_REPLY_STATUSES
                    }
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
                                    thinkingMarkdown = output.thinkingMarkdown,
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
                // 资产库按内容寻址，同一张照片被选两次会拿到同一个 assetId，而请求契约
                // 要求 assetId 互不重复；这里按 assetId 去重（保留首次出现的位置），
                // 而不是让重复选择把整条消息顶成发送失败。
                val imageAssets = if (sentImages.isNotEmpty() && intake != null) {
                    sentImages
                        .map { pending ->
                            intake.registerImage(pending.localUri, System.currentTimeMillis())
                        }
                        .distinctBy { it.assetId }
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
                val request = try {
                    buildTutorLobbyRequest(
                        provider = currentProvider,
                        conversationId = conversationId,
                        messageOrdinal = logicalTurnOrdinal,
                        studentMessage = effectiveMessage,
                        priorMessages = freshMessages.toLobbyHistory(),
                        occurredAtEpochMillis = occurredAt,
                        approvedAtEpochMillis = approvedAtEpochMillis,
                        imageAssets = imageAssets,
                    )
                } catch (_: IllegalArgumentException) {
                    // 契约违规不是网络问题：重试会逐字重放同一个非法请求，所以如实说
                    // 是格式问题，并且不给重试按钮（给了也必然再失败一次）。
                    sendError = appFailure(
                        code = AppFailureCode.VALIDATION_FAILED,
                        title = "这条消息暂时发不出去",
                        message = "这条消息的格式需要调整，改一下再发送。",
                        dataPreserved = true,
                    )
                    return@launch
                }

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
                                thinkingMarkdown = output.thinkingMarkdown,
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
                // 生成结束：清掉实时文本，避免下一次发送在首个状态到达前闪出上一条的残留。
                liveReplyStatus = null
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
            if (resumingTaskId != null || sendInFlight) {
                item(key = "lobby-resuming") {
                    Column(Modifier.padding(top = 10.dp)) {
                        // 生成中：思考/工具调用自动展开、实时流式；回答落地后由消息自带的折叠卡接管。
                        ThinkingCollapsibleCard(
                            thinkingMarkdown = liveReplyStatus,
                            thinking = true,
                        )
                        if (liveReplyStatus == null) {
                            TutorPrompt(
                                text = if (resumingTaskId != null) "正在回复…" else "正在发送…",
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
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
                        PendingMessageImagesRow(
                            images = pendingImages,
                            onRemove = { index ->
                                pendingImages = pendingImages.filterIndexed { i, _ -> i != index }
                            },
                            testTagPrefix = "lobby",
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
        MessageAttachmentDialog(
            onDismiss = { attachMenuOpen = false },
            onLaunchCamera = {
                attachMenuOpen = false
                launchLobbyCamera()
            },
            onLaunchGallery = {
                attachMenuOpen = false
                lobbyGalleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            testTagPrefix = "lobby",
        )
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
                        MessageImagesRow(
                            assetIds = message.sourceImageAssetIds,
                            imageIntake = imageIntake,
                            testTagPrefix = "lobby",
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
        TutorMessageStatus.SUCCEEDED -> Column(modifier = modifier) {
            // 思考轨迹先于正文出现、默认折叠，不抢正文；模型没给思考时这块不渲染。
            ThinkingCollapsibleCard(
                thinkingMarkdown = message.thinkingMarkdown,
                thinking = false,
            )
            TutorPrompt(
                text = message.bodyMarkdown,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_lobby_assistant_message"),
            )
        }
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

/**
 * Bounded prior history for a lobby turn. Exposed to tests because this trim is
 * the only thing standing between a long conversation and a `require` throw
 * inside [TutorLobbyInput]: passing an over-budget list straight through made
 * every later send fail permanently (reported, wrongly, as a network error).
 */
internal fun List<TutorMessage>.toLobbyHistory(): List<TutorChatHistoryEntry> {
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
    return TutorHistoryBudget.bounded(entries)
}

private const val MAX_VISIBLE_MESSAGES = 20
private const val MAX_PERSISTED_TASKS = 64
private const val MAX_RECENT_CONVERSATIONS = 20

/** 生成中的任务状态：这些状态下的任务消息（思考链/工具调用节流）值得实时展示。 */
private val LIVE_REPLY_STATUSES = setOf(
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
)
