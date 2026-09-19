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
import androidx.compose.runtime.saveable.listSaver
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
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
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
import com.tingyun.smartmistakebook.core.model.recoverableByResending
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
    // 待发附件跨重建保留：选好图之后切走或进程被回收再回来，图不该消失（发送成功才清空）。
    var pendingImages by rememberSaveable(
        stateSaver = listSaver<List<PendingMessageImage>, String>(
            save = { images -> images.map(PendingMessageImage::localUri) },
            restore = { uris -> uris.map(::PendingMessageImage) },
        ),
    ) { mutableStateOf(emptyList()) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    // 相机结果要回填到发起拍照时约定的目标 URI，跨重建也必须还在，否则拍完的照片无处安放。
    var pendingCameraImageUri by rememberSaveable { mutableStateOf<String?>(null) }
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

    /**
     * 派发一轮大厅对话并消费它的状态流：终态落一条助手消息，进行中的状态写进实时文本。
     *
     * 新发送、失败重发、继续未完成任务三条入口共用这一处。它们此前各自实现过一次，而两份
     * 实现已经漂移——正常发送路径从不读 `task.userMessage`，所以学生发出消息后只看到一句
     * 永远不变的"正在发送…"，只有"继续回复"路径看得到实时状态。实时流只接一处，就不会再
     * 出现"哪条路径忘了接"。
     */
    suspend fun dispatchLobbyTurn(
        request: ModelTaskRequest,
        conversationId: String,
        replyToMessageId: String?,
        assistantOrdinal: Int,
        logicalOperationId: String,
    ) {
        var terminalHandled = false
        modelTasks.execute(request).collect { task ->
            if (terminalHandled) return@collect
            liveReplyStatus = task.userMessage.takeIf {
                it.isNotBlank() && task.status in LIVE_REPLY_STATUSES
            }
            val output = task.output as? TutorLobbyOutput
            when {
                task.status == ModelTaskStatus.SUCCEEDED && output != null -> {
                    terminalHandled = true
                    conversations.appendAssistantMessage(
                        AppendTutorAssistantMessageCommand(
                            conversationId = conversationId,
                            messageId = "tutor-message:${UUID.randomUUID()}",
                            ordinal = assistantOrdinal,
                            replyToMessageId = replyToMessageId,
                            bodyMarkdown = output.messageMarkdown,
                            thinkingMarkdown = output.thinkingMarkdown,
                            logicalOperationId = logicalOperationId,
                            status = TutorMessageStatus.SUCCEEDED,
                            createdAtEpochMillis = request.occurredAtEpochMillis,
                            completedAtEpochMillis = task.updatedAtEpochMillis,
                            errorCode = null,
                        ),
                    )
                }

                task.status in TERMINAL_FAILURE_STATUSES -> {
                    terminalHandled = true
                    conversations.appendAssistantMessage(
                        AppendTutorAssistantMessageCommand(
                            conversationId = conversationId,
                            messageId = "tutor-message:${UUID.randomUUID()}",
                            ordinal = assistantOrdinal,
                            replyToMessageId = replyToMessageId,
                            bodyMarkdown = TUTOR_LOBBY_FAILED_REPLY_BODY,
                            logicalOperationId = logicalOperationId,
                            status = TutorMessageStatus.FAILED,
                            createdAtEpochMillis = request.occurredAtEpochMillis,
                            completedAtEpochMillis = task.updatedAtEpochMillis,
                            errorCode = task.failure?.code?.name,
                        ),
                    )
                }
            }
        }
        if (!terminalHandled) {
            conversations.appendAssistantMessage(
                AppendTutorAssistantMessageCommand(
                    conversationId = conversationId,
                    messageId = "tutor-message:${UUID.randomUUID()}",
                    ordinal = assistantOrdinal,
                    replyToMessageId = replyToMessageId,
                    bodyMarkdown = TUTOR_LOBBY_INTERRUPTED_REPLY_BODY,
                    logicalOperationId = logicalOperationId,
                    status = TutorMessageStatus.FAILED,
                    createdAtEpochMillis = request.occurredAtEpochMillis,
                    completedAtEpochMillis = System.currentTimeMillis(),
                    errorCode = "UNKNOWN",
                ),
            )
        }
    }

    fun resumeStalledTask() {
        val task = stalledTask ?: return
        val conversationId = activeConversationId
        if (conversationId.isBlank() || resumingTaskId != null || sendInFlight) return
        resumingTaskId = task.request.requestId
        sendError = null
        val replyTo = conversationMessages.lastOrNull {
            it.role == TutorMessageRole.STUDENT
        }
        val assistantOrdinal =
            (conversationSnapshot?.conversation?.lastTurnOrdinal ?: 0) + 1
        scope.launch {
            try {
                dispatchLobbyTurn(
                    request = task.request,
                    conversationId = conversationId,
                    replyToMessageId = replyTo?.messageId,
                    assistantOrdinal = assistantOrdinal,
                    logicalOperationId = task.request.requestId,
                )
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

    /**
     * 发送入口的时间戳只取一次：同一个时刻既作"学生做出发送决定"的授权时刻，也作请求发生时刻。
     *
     * `ModelEgressManifest.requireAuthorizes` 要求 `approvedAtEpochMillis >= occurredAtEpochMillis`。
     * 这两个值此前各自读一次时钟，于是"两次读秒是否落在同一毫秒"决定了发送成败：毫秒一错开，
     * 请求就在任何网络动作之前被本地拒掉（stage=PREPARING、attempt=0），学生看到的是
     * "刚发出去一秒就说没准备好"。一次用户动作只产生一个时刻，用结构保证不等式成立，
     * 而不是放宽这条校验。
     */
    fun startMessage(message: String, decidedAtEpochMillis: Long = System.currentTimeMillis()) {
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
        // 与"发送决定"同刻：不再二次读时钟，见 startMessage 的说明。
        val occurredAt = decidedAtEpochMillis
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
                        approvedAtEpochMillis = decidedAtEpochMillis,
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

                dispatchLobbyTurn(
                    request = request,
                    conversationId = conversationId,
                    replyToMessageId = studentMessage.messageId,
                    assistantOrdinal = assistantOrdinal,
                    logicalOperationId = logicalOperationId,
                )
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

    /**
     * 原样重发一条已经失败的消息：同一句原文、同一批附图，以新的 attempt 重新签发授权。
     *
     * 与"新发送"只有两点不同。一是不再追加学生消息——原文已经在对话里，重发不该让它出现
     * 两次。二是 attempt 递增：同一逻辑轮的每次派发必须是不同的请求标识，否则仓库按任务
     * 标识直接返回上一次的终态，"重新发送"会变成一次静默的空操作。
     */
    fun resendMessage(studentMessage: TutorMessage) {
        val currentProvider = provider
        if (currentProvider == null || !currentProvider.supports(ModelTaskKind.TUTOR_LOBBY)) return
        if (sendInFlight || resumingTaskId != null) return
        val conversationId = activeConversationId
        if (conversationId.isBlank() || studentMessage.conversationId != conversationId) return
        sendInFlight = true
        sendError = null
        // 同一次用户动作取一次时钟：授权时刻与请求时刻必须同刻（见 startMessage 的说明）。
        val decidedAtEpochMillis = System.currentTimeMillis()
        scope.launch {
            try {
                val snapshot = runCatching {
                    conversations.observeConversation(conversationId).first()
                }.getOrNull() ?: return@launch
                // 逻辑轮次按"这是第几条学生消息"数，而不是按 ordinal 推算：一旦某次派发
                // 异常导致助手行缺失，ordinal 的奇偶就会错位，进而把两轮映射成同一个请求标识。
                val logicalTurnOrdinal = snapshot.messages.count {
                    it.role == TutorMessageRole.STUDENT
                }
                val attempt = conversationTasks.count { task ->
                    (task.request.input as? TutorLobbyInput)?.messageOrdinal == logicalTurnOrdinal
                }
                // 附图从资产库读回元数据：图被清理或被改动时按纯文字重发，
                // 而不是让整条消息发不出去。
                val imageAssets = studentMessage.sourceImageAssetIds
                    .mapNotNull { assetId -> imageIntake?.describeImage(assetId) }
                val request = try {
                    buildTutorLobbyRequest(
                        provider = currentProvider,
                        conversationId = conversationId,
                        messageOrdinal = logicalTurnOrdinal,
                        studentMessage = studentMessage.bodyMarkdown,
                        priorMessages = snapshot.messages.toLobbyHistory(),
                        occurredAtEpochMillis = decidedAtEpochMillis,
                        approvedAtEpochMillis = decidedAtEpochMillis,
                        attempt = attempt,
                        imageAssets = imageAssets,
                    )
                } catch (_: IllegalArgumentException) {
                    sendError = appFailure(
                        code = AppFailureCode.VALIDATION_FAILED,
                        title = "这条消息暂时发不出去",
                        message = "这条消息的格式需要调整，改一下再发送。",
                        dataPreserved = true,
                    )
                    return@launch
                }
                dispatchLobbyTurn(
                    request = request,
                    conversationId = conversationId,
                    replyToMessageId = studentMessage.messageId,
                    assistantOrdinal = snapshot.conversation.lastTurnOrdinal + 1,
                    logicalOperationId = studentMessage.logicalOperationId ?: request.requestId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("TutorLobby", "Failed to resend message", e)
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
                liveReplyStatus = null
            }
        }
    }

    fun submitDraft() {
        val message = draft.trim()
        val hasImages = pendingImages.isNotEmpty()
        if ((message.isBlank() && !hasImages) || hasActiveTask || sendInFlight) return
        startMessage(message)
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
                    resendTarget = message.resendTargetOrNull(conversationMessages),
                    onResend = ::resendMessage,
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
                                    startMessage(text)
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
    /** 可原样重发时，这里是要重发的那条学生消息；否则为 null（不渲染重发按钮）。 */
    resendTarget: TutorMessage? = null,
    onResend: (TutorMessage) -> Unit = {},
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
                val failureCode = message.errorCode
                    ?.let { raw -> runCatching { ModelFailureCode.valueOf(raw) }.getOrNull() }
                lobbyFailureReasonText(failureCode)?.let { reason ->
                    Text(
                        text = reason,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("tutor_lobby_failure_reason"),
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (failureCode?.requiresModelSettings() == true) {
                    OutlineActionChip(
                        text = "检查模型设置",
                        onClick = onOpenCapabilitySettings,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("tutor_lobby_failure_open_model_settings"),
                    )
                }
                // 原样再发一次：同一句原文、同一批附图，以新的 attempt 重新签发授权。
                resendTarget?.let { studentMessage ->
                    OutlineActionChip(
                        text = "重新发送",
                        onClick = { onResend(studentMessage) },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("tutor_lobby_resend"),
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
 * Lobby 回复失败的补充说明（纯函数，便于单测）：把内部失败码翻译成学生能行动的一句话。
 *
 * 逐条穷举而不是 `else -> null` 兜底：真实发生过的那次失败（`EGRESS_AUTHORIZATION_INVALID`）
 * 此前正好落在兜底分支里，于是失败卡只有一句"没有准备好"，学生既不知道原因也看不到出口。
 * 穷举还让以后新增的失败码**必须**在这里表态，而不是静默退回通用文案。
 */
internal fun lobbyFailureReasonText(code: ModelFailureCode?): String? = when (code) {
    null -> null
    ModelFailureCode.MODEL_NOT_CONFIGURED -> "当前还没有可用的模型配置，去设置里配好再发。"
    ModelFailureCode.AUTHENTICATION_FAILED -> "API Key 可能已失效，检查后重新发送。"
    ModelFailureCode.PROVIDER_CAPABILITY_MISSING ->
        "当前模型不支持这项对话能力，换一个模型或重新做一次能力测试。"
    ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED -> "这次发送还没有得到授权，重新发送即可。"
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID ->
        "这次发送的授权已经失效（例如隔了很久才重试），重新发送会重新授权。"
    ModelFailureCode.NETWORK_UNAVAILABLE -> "网络暂时不可用，可以稍后再试。"
    ModelFailureCode.SERVICE_UNAVAILABLE -> "模型服务暂时不可用，可以稍后再试。"
    ModelFailureCode.TIMEOUT -> "模型响应超时，可以再发一次。"
    ModelFailureCode.RATE_LIMITED -> "请求太频繁，稍等片刻再发。"
    ModelFailureCode.INVALID_RESPONSE -> "模型这次返回的内容无法使用，可以再发一次。"
    ModelFailureCode.PROVIDER_REJECTED_INPUT ->
        "上游拒绝了这次请求；带图时通常是图太多或太大，去掉一张或换小一点的图再发。"
    ModelFailureCode.UNKNOWN -> null
}

/**
 * 这条失败的回复还能不能"原样再发一次"；能则返回要重发的那条学生消息。
 *
 * 只在三个条件同时成立时给按钮：失败码属于重发有意义的那些码、这条失败仍是会话最后一条
 * 消息（更早的失败重发会让对话顺序错乱）、以及能找到它回复的那条学生消息（重发要带上
 * 原消息原文与它的附图）。消灭的失败：失败后界面上没有任何出口，学生只能把话重打一遍
 * ——实测记录里就是这么发生的：失败之后手动补发了"3"和"第三题"两条新消息。
 */
internal fun TutorMessage.resendTargetOrNull(
    conversationMessages: List<TutorMessage>,
): TutorMessage? {
    if (status != TutorMessageStatus.FAILED) return null
    if (conversationMessages.lastOrNull()?.messageId != messageId) return null
    val code = errorCode?.let { raw ->
        runCatching { ModelFailureCode.valueOf(raw) }.getOrNull()
    }
    if (code?.recoverableByResending() != true) return null
    val repliedTo = replyToMessageId ?: return null
    return conversationMessages.firstOrNull { candidate ->
        candidate.messageId == repliedTo && candidate.role == TutorMessageRole.STUDENT
    }
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

/** 终态失败：这三种状态下这一轮不会再有输出，必须落一条学生看得见的交代。 */
private val TERMINAL_FAILURE_STATUSES = setOf(
    ModelTaskStatus.RETRYABLE_FAILURE,
    ModelTaskStatus.PERMANENT_FAILURE,
    ModelTaskStatus.CANCELLED,
)

/**
 * 失败卡正文只说"消息还在"这句确定的话；**为什么失败、下一步怎么走**由失败卡按
 * `errorCode` 逐条给出（[lobbyFailureReasonText]）。此前只有这一句通用文案，而唯一
 * 真正发生的失败码（授权过期）不在原因表的任何一支里，学生看到的就是"没有准备好"。
 */
private const val TUTOR_LOBBY_FAILED_REPLY_BODY = "这次回复没有准备好，你的消息已经保留。"
private const val TUTOR_LOBBY_INTERRUPTED_REPLY_BODY = "这条消息已经保留，暂时没有收到讲解。"

/** 生成中的任务状态：这些状态下的任务消息（思考链/工具调用节流）值得实时展示。 */
private val LIVE_REPLY_STATUSES = setOf(
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
)
