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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorLobbyConversation
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorLobbyVisualRequest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TutorLobbyRoute(
    onCapture: () -> Unit,
    onGallery: () -> Unit = onCapture,
    onCaptureWithVisualIntent: (TutorCurrentSessionVisualIntent) -> Unit = { onCapture() },
    onGalleryWithVisualIntent: (TutorCurrentSessionVisualIntent) -> Unit = { onGallery() },
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    modelTasks: ScopedModelTaskPort,
    conversationLobby: TutorConversationLobbyPort,
    catalogEntries: List<StudyCatalogEntry>,
    explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(conversationLobby) {
        TutorLobbyConversationController(conversationLobby)
    }
    var conversationState by remember(controller) {
        mutableStateOf<TutorLobbyConversationState>(TutorLobbyConversationState.Loading)
    }
    var observedModeName by rememberSaveable { mutableStateOf(explanationMode.name) }
    var modeVersion by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(explanationMode) {
        if (observedModeName != explanationMode.name) {
            observedModeName = explanationMode.name
            modeVersion += 1
        }
    }
    LaunchedEffect(controller) {
        conversationState = try {
            TutorLobbyConversationState.Ready(controller.loadInitialConversation())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TutorLobbyConversationState.Failed
        }
    }
    when (val state = conversationState) {
        TutorLobbyConversationState.Loading -> TutorLobbyConversationStatus(
            message = "正在打开对话…",
            modifier = modifier,
        )

        TutorLobbyConversationState.Failed -> TutorLobbyConversationStatus(
            message = "暂时无法打开对话，请重试。",
            onRetry = {
                scope.launch {
                    conversationState = try {
                        TutorLobbyConversationState.Ready(controller.loadInitialConversation())
                    } catch (_: Exception) {
                        TutorLobbyConversationState.Failed
                    }
                }
            },
            modifier = modifier,
        )

        is TutorLobbyConversationState.Ready -> TutorLobbyConversationRoute(
            onCapture = onCapture,
            onGallery = onGallery,
            onCaptureWithVisualIntent = onCaptureWithVisualIntent,
            onGalleryWithVisualIntent = onGalleryWithVisualIntent,
            onChooseExisting = onChooseExisting,
            onOpenCapabilitySettings = onOpenCapabilitySettings,
            onOpenMistakeNotebook = onOpenMistakeNotebook,
            onOpenProfile = onOpenProfile,
            modelTasks = modelTasks,
            controller = controller,
            conversation = state.conversation,
            catalogEntries = catalogEntries,
            explanationMode = explanationMode,
            modeVersion = modeVersion,
            onExplanationModeChange = onExplanationModeChange,
            onStartNewConversation = {
                val currentConversation = state.conversation
                scope.launch {
                    conversationState = TutorLobbyConversationState.Loading
                    conversationState = try {
                        TutorLobbyConversationState.Ready(
                            controller.startNewConversation(currentConversation),
                        )
                    } catch (_: Exception) {
                        TutorLobbyConversationState.Failed
                    }
                }
            },
            modifier = modifier,
        )
    }
}

private sealed interface TutorLobbyConversationState {
    data object Loading : TutorLobbyConversationState
    data object Failed : TutorLobbyConversationState
    data class Ready(val conversation: TutorLobbyConversation) : TutorLobbyConversationState
}

@Composable
private fun TutorLobbyConversationStatus(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    RootPageLazyColumn(modifier = modifier.testTag("tutor_lobby")) {
        item {
            TutorTopBar(onOpenCapabilitySettings = {})
            PaperDivider(Modifier.padding(top = 8.dp, bottom = 14.dp))
            TutorPrompt(text = message)
            onRetry?.let { retry ->
                OutlineActionChip(
                    text = "重试",
                    onClick = retry,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TutorLobbyConversationRoute(
    onCapture: () -> Unit,
    onGallery: () -> Unit = onCapture,
    onCaptureWithVisualIntent: (TutorCurrentSessionVisualIntent) -> Unit,
    onGalleryWithVisualIntent: (TutorCurrentSessionVisualIntent) -> Unit,
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    modelTasks: ScopedModelTaskPort,
    controller: TutorLobbyConversationController,
    conversation: TutorLobbyConversation,
    catalogEntries: List<StudyCatalogEntry>,
    explanationMode: TutorExplanationMode,
    modeVersion: Long,
    onExplanationModeChange: (TutorExplanationMode) -> Unit,
    onStartNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var committedConversation by remember(conversation) {
        mutableStateOf(conversation)
    }
    val activeStreamOwner = remember(modelTasks, scope, conversation.conversationId) {
        TutorActiveStreamOwner(
            scope = scope,
            initialMode = explanationMode,
            cancelDurableRequest = modelTasks::cancel,
        )
    }
    DisposableEffect(activeStreamOwner) {
        onDispose(activeStreamOwner::close)
    }
    LaunchedEffect(activeStreamOwner, explanationMode) {
        activeStreamOwner.updateMode(explanationMode)
    }
    val activeStreamState by activeStreamOwner.state.collectAsState()
    val activeMessage = activeStreamState.active
    val persistedTasks by remember(modelTasks, conversation.conversationId) {
        modelTasks.observeRecentBySubject(
            conversation.conversationId,
            ModelTaskKind.TUTOR_LOBBY,
            MAX_PERSISTED_TASKS,
        )
    }.collectAsState(initial = emptyList())
    val conversationTasks = remember(
        persistedTasks,
        activeStreamState.supersededRequestIds,
        conversation.conversationId,
    ) {
        latestTutorLobbyConversationTasks(
            persistedTasks.filterNot { task ->
                task.request.requestId in activeStreamState.supersededRequestIds
            },
            conversation.conversationId,
        )
    }
    var consumedVisualSourceRequestId by rememberSaveable(conversation.conversationId) {
        mutableStateOf<String?>(null)
    }
    val latestVisualSourceTask = conversationTasks.lastOrNull()?.takeIf { task ->
        task.tutorLobbyVisualPresentation() == TutorLobbyVisualPresentation.SourceRequired
    }
    val visibleTasks = remember(conversationTasks) { conversationTasks.takeLast(MAX_VISIBLE_MESSAGES) }
    var provider by remember(conversation.conversationId) { mutableStateOf<ProviderCapabilitySnapshot?>(null) }
    var providerLoadFailed by rememberSaveable(conversation.conversationId) { mutableStateOf(false) }
    var draft by rememberSaveable(conversation.conversationId) { mutableStateOf("") }
    var pendingDisclosureMessage by rememberSaveable(conversation.conversationId) { mutableStateOf<String?>(null) }
    var sendError by rememberSaveable(conversation.conversationId) { mutableStateOf<String?>(null) }
    var draftToClearOnDurableStart by rememberSaveable(conversation.conversationId) { mutableStateOf<String?>(null) }
    var disclosureToClearOnDurableStart by rememberSaveable(conversation.conversationId) { mutableStateOf<String?>(null) }
    val hasActiveTask = activeMessage?.activityVisible == true || conversationTasks.any { task ->
        task.status in setOf(
            ModelTaskStatus.WAITING_FOR_MODEL,
            ModelTaskStatus.QUEUED,
            ModelTaskStatus.RUNNING,
            ModelTaskStatus.STREAMING,
        )
    }
    val activeRequestId = activeMessage?.identity?.requestId
    val durableActiveTask = activeRequestId?.let { requestId ->
        conversationTasks.firstOrNull { it.request.requestId == requestId }
    }
    LaunchedEffect(
        activeRequestId,
        activeMessage?.phase,
        durableActiveTask?.status,
    ) {
        if (durableActiveTask?.status == ModelTaskStatus.SUCCEEDED) {
            activeStreamOwner.acknowledgeDurableSuccess(durableActiveTask.request.requestId)
        }
    }
    LaunchedEffect(
        activeRequestId,
        activeMessage?.durablyStarted,
    ) {
        if (activeMessage?.durablyStarted != true) return@LaunchedEffect
        draftToClearOnDurableStart?.let { submittedDraft ->
            if (draft == submittedDraft) draft = ""
        }
        disclosureToClearOnDurableStart?.let { submittedMessage ->
            if (pendingDisclosureMessage == submittedMessage) {
                pendingDisclosureMessage = null
            }
        }
        draftToClearOnDurableStart = null
        disclosureToClearOnDurableStart = null
    }

    LaunchedEffect(modelTasks, conversation.conversationId) {
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
            sendError = if (providerLoadFailed) {
                "暂时读不到模型配置，请检查后再试。"
            } else {
                "当前模型还不能处理对话，请先完成模型配置和能力测试。"
            }
            return
        }
        val tasksForRequest = conversationTasks
        val conversationForRequest = committedConversation
        val modeForTurn = explanationMode
        val modeVersionForTurn = modeVersion
        draftToClearOnDurableStart = draft
        disclosureToClearOnDurableStart =
            pendingDisclosureMessage?.takeIf { it == message }
        sendError = null
        activeStreamOwner.submit(studentMessage = message) {
            val occurredAt = System.currentTimeMillis()
            val allocated = controller.allocateTurn(
                conversation = conversationForRequest,
                message = message,
                mode = modeForTurn,
                modeVersion = modeVersionForTurn,
            )
            if (committedConversation == conversationForRequest) {
                committedConversation = allocated.conversation
            }
            val request = buildTutorLobbyRequest(
                provider = currentProvider,
                messageOrdinal = allocated.turnOrdinal,
                studentMessage = message,
                priorMessages = tasksForRequest.toLobbyHistory(),
                occurredAtEpochMillis = occurredAt,
                approvedAtEpochMillis = approvedAtEpochMillis,
                conversationId = allocated.conversation.conversationId,
                explanationMode = modeForTurn,
                modeVersion = modeVersionForTurn,
                explicitVisualRequest = VisualIntent.detect(message)?.toLobbyRequest(),
            )
            TutorPreparedStream(request.requestId) { identity ->
                modelTasks.executeTutorStream(request, identity)
            }
        }
    }

    fun retryTask(task: ModelTaskSnapshot) {
        val input = task.request.input as? TutorLobbyInput ?: return
        if (!task.canRetryTutorLobby()) return
        val taskProvider = task.provider ?: provider ?: return
        val request = if (
            taskProvider.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS
        ) {
            val nextAttempt = task.nextTutorLobbyRetryAttempt() ?: return
            val occurredAt = maxOf(
                System.currentTimeMillis(),
                task.updatedAtEpochMillis + 1,
            )
            buildTutorLobbyRequest(
                provider = taskProvider,
                messageOrdinal = input.messageOrdinal,
                studentMessage = input.studentMessage,
                priorMessages = input.priorMessages,
                occurredAtEpochMillis = occurredAt,
                attempt = nextAttempt,
                conversationId = input.conversationId,
                explanationMode = input.explanationMode,
                modeVersion = input.modeVersion,
                explicitVisualRequest = input.explicitVisualRequest,
                choiceInteractionAuthorized = input.choiceInteractionAuthorized,
                allowedVisualTargetIds = input.allowedVisualTargetIds,
            )
        } else {
            task.request
        }
        activeStreamOwner.submit(
            studentMessage = input.studentMessage,
            startsNewTurn = false,
            retryConsumed = true,
        ) {
            TutorPreparedStream(request.requestId) { identity ->
                modelTasks.executeTutorStream(request, identity)
            }
        }
    }

    fun resumeTask(task: ModelTaskSnapshot) {
        val input = task.request.input as? TutorLobbyInput ?: return
        if (!task.canResumeTutorLobby()) return
        activeStreamOwner.submit(
            studentMessage = input.studentMessage,
            startsNewTurn = false,
        ) {
            TutorPreparedStream(task.request.requestId) { identity ->
                modelTasks.executeTutorStream(task.request, identity)
            }
        }
    }

    val recoverablePendingTask = latestPendingTutorLobbyTask(conversationTasks)
    LaunchedEffect(
        recoverablePendingTask?.request?.requestId,
        activeMessage == null,
    ) {
        if (activeMessage == null) {
            recoverablePendingTask?.let(::resumeTask)
        }
    }

    fun submitMessage(message: String) {
        if (message.isBlank() || hasActiveTask) return
        val currentProvider = provider
        if (currentProvider == null || !currentProvider.supports(ModelTaskKind.TUTOR_LOBBY)) {
            startMessage(message, System.currentTimeMillis())
            return
        }
        if (currentProvider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            pendingDisclosureMessage = message
            sendError = null
        } else {
            startMessage(message, System.currentTimeMillis())
        }
    }

    fun submitDraft() {
        submitMessage(draft.trim())
    }

    fun launchComposerCapture(
        callback: (TutorCurrentSessionVisualIntent) -> Unit,
    ) {
        val sourceTask = latestVisualSourceTask.takeIf { draft.isBlank() }
        val sourceRequest = (sourceTask?.request?.input as? TutorLobbyInput)?.explicitVisualRequest
        val intent = tutorCaptureVisualIntent(
            studentDraft = draft,
            explicitVisualRequest = sourceRequest,
            visualSourceRequestId = sourceTask?.request?.requestId,
            consumedVisualSourceRequestId = consumedVisualSourceRequestId,
        )
        if (sourceTask != null && intent == TutorCurrentSessionVisualIntent.USER_EXPLICIT) {
            consumedVisualSourceRequestId = sourceTask.request.requestId
        }
        callback(intent)
    }

    val listState = rememberLazyListState()
    val activeItemVisible = activeMessage != null &&
        visibleTasks.none { it.request.requestId == activeRequestId }
    val expectedItemCount =
        1 +
            (if (visibleTasks.isEmpty()) 1 else 0) +
            visibleTasks.size +
            (if (activeItemVisible) 1 else 0) +
            (if (pendingDisclosureMessage != null) 1 else 0) +
            (if (sendError != null) 1 else 0)
    TutorConversationAnchorEffect(
        autoScrollVersion = listOf(
            visibleTasks.map { listOf(it.request.requestId, it.stateVersion, it.status) },
            activeMessage?.renderVersion,
        ),
        expectedItemCount = expectedItemCount,
        forceFollowToken = activeMessage?.turnVersion,
        listState = listState,
    )
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
            listState = listState,
        ) {
            item(key = "lobby-header") {
                TutorTopBar(onOpenCapabilitySettings = onOpenCapabilitySettings)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlineActionChip(
                        text = "新对话",
                        onClick = onStartNewConversation,
                        modifier = Modifier.testTag("tutor_lobby_new_conversation"),
                    )
                }
                PaperDivider(Modifier.padding(top = 8.dp, bottom = 14.dp))
            }
            if (visibleTasks.isEmpty()) {
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
                items = visibleTasks,
                key = { task -> task.request.requestId },
            ) { task ->
                TutorLobbyTask(
                    task = task,
                    canRetry = task.canRetryTutorLobby(),
                    activeMessage = activeMessage?.takeIf {
                        it.identity?.requestId == task.request.requestId
                    },
                    catalogEntries = catalogEntries,
                    onOpenMistakeNotebook = onOpenMistakeNotebook,
                    onOpenProfile = onOpenProfile,
                    onOpenCapabilitySettings = onOpenCapabilitySettings,
                    onCapture = onCaptureWithVisualIntent,
                    onGuidedResponse = { response -> submitMessage(response) },
                    currentMode = explanationMode,
                    currentModeVersion = modeVersion,
                    onRetry = { retryTask(task) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (activeItemVisible) {
                item(key = "lobby-active-${activeMessage.turnVersion}") {
                    TutorActiveChatExchange(
                        message = activeMessage,
                        onRetry = activeStreamOwner::retry,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            pendingDisclosureMessage?.let { message ->
                item(key = "lobby-disclosure") {
                    TutorLobbyDisclosureCard(
                        providerName = provider?.providerDisplayName.orEmpty(),
                        onApprove = { startMessage(message, System.currentTimeMillis()) },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            sendError?.let { message ->
                item(key = "lobby-send-error") {
                    Text(
                        text = message,
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
                    draft = value.take(TutorLobbyInput.MAX_STUDENT_MESSAGE_CHARS)
                    if (pendingDisclosureMessage != null) pendingDisclosureMessage = null
                },
                onCapture = { launchComposerCapture(onCaptureWithVisualIntent) },
                onGallery = { launchComposerCapture(onGalleryWithVisualIntent) },
                onChooseExisting = onChooseExisting,
                onSend = ::submitDraft,
                explanationMode = explanationMode,
                onExplanationModeChange = { mode ->
                    activeStreamOwner.updateMode(mode)
                    onExplanationModeChange(mode)
                },
                placeholder = "输入题目、困惑，或说你现在想做什么",
                enabled = !hasActiveTask,
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
    canRetry: Boolean,
    activeMessage: TutorActiveStreamMessage?,
    catalogEntries: List<StudyCatalogEntry>,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onCapture: (TutorCurrentSessionVisualIntent) -> Unit,
    onGuidedResponse: (String) -> Unit,
    currentMode: TutorExplanationMode,
    currentModeVersion: Long,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val input = task.request.input as? TutorLobbyInput ?: return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = JadeSoft.copy(alpha = 0.62f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .testTag("tutor_lobby_student_message"),
            ) {
                Text(
                    text = input.studentMessage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val output = task.output as? TutorLobbyOutput
        if (activeMessage != null) {
            TutorActiveAssistantReply(
                message = activeMessage,
                durableTask = task,
                onRetry = onRetry,
                onOpenModelSettings = onOpenCapabilitySettings,
            )
        } else if (task.status == ModelTaskStatus.SUCCEEDED && output != null) {
            TutorPrompt(
                text = output.messageMarkdown,
                modifier = Modifier.testTag("tutor_lobby_assistant_message"),
            )
            TutorLocalIntentPanel(
                decision = output.intentDecision,
                studentMessage = input.studentMessage,
                catalogEntries = catalogEntries,
                onOpenMistakeNotebook = onOpenMistakeNotebook,
                onOpenProfile = onOpenProfile,
            )
            visibleTutorLobbyDirective(
                input = input,
                output = output,
                currentMode = currentMode,
                currentModeVersion = currentModeVersion,
            )?.let { directive ->
                TutorLobbyInteraction(
                    directive = directive,
                    onResponse = onGuidedResponse,
                )
            }
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
                    } else if (canRetry) {
                        OutlineActionChip(
                            text = "重试",
                            onClick = onRetry,
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
        TutorLobbyVisualStatus(
            presentation = task.tutorLobbyVisualPresentation(),
            onCapture = {
                onCapture(
                    tutorCaptureVisualIntent(
                        explicitVisualRequest = input.explicitVisualRequest,
                    ),
                )
            },
        )
    }
}

internal fun tutorCaptureVisualIntent(
    studentDraft: String = "",
    explicitVisualRequest: TutorLobbyVisualRequest? = null,
    visualSourceRequestId: String? = null,
    consumedVisualSourceRequestId: String? = null,
): TutorCurrentSessionVisualIntent =
    if (
        VisualIntent.detect(studentDraft) != null ||
        explicitVisualRequest != null &&
        (visualSourceRequestId == null || visualSourceRequestId != consumedVisualSourceRequestId)
    ) {
        TutorCurrentSessionVisualIntent.USER_EXPLICIT
    } else {
        TutorCurrentSessionVisualIntent.NONE
    }

@Composable
private fun TutorLobbyInteraction(
    directive: TutorInteractionDirective,
    onResponse: (String) -> Unit,
) {
    when (directive) {
        TutorInteractionDirective.Continue -> Unit
        is TutorInteractionDirective.FreeResponse -> SafeMarkdownText(
            directive.promptMarkdown,
            modifier = Modifier.testTag("tutor_lobby_free_response"),
            style = MaterialTheme.typography.bodyMedium,
        )
        is TutorInteractionDirective.Choices -> {
            SafeMarkdownText(
                directive.promptMarkdown,
                modifier = Modifier.testTag("tutor_lobby_choices"),
                style = MaterialTheme.typography.bodyMedium,
            )
            directive.choices.forEach { choice ->
                OutlineActionChip(
                    text = choice.labelMarkdown,
                    onClick = { onResponse(choice.labelMarkdown) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tutor_lobby_choice_${choice.id}"),
                )
            }
        }
        is TutorInteractionDirective.VisualTarget -> SafeMarkdownText(
            directive.promptMarkdown,
            modifier = Modifier.testTag("tutor_lobby_visual_target"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TutorLobbyVisualStatus(
    presentation: TutorLobbyVisualPresentation,
    onCapture: () -> Unit,
) {
    when (presentation) {
        TutorLobbyVisualPresentation.Hidden -> Unit
        TutorLobbyVisualPresentation.Preparing -> Surface(
            color = JadeSoft.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tutor_lobby_visual_preparing"),
        ) {
            Text(
                text = "正在准备图解…",
                modifier = Modifier.padding(12.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TutorLobbyVisualPresentation.SourceRequired -> Surface(
            color = JadeSoft.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tutor_lobby_visual_fallback"),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "发题图后可以准确生成",
                    modifier = Modifier.weight(1f),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlineActionChip(text = "拍题", onClick = onCapture)
            }
        }
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

private fun List<ModelTaskSnapshot>.toLobbyHistory(): List<TutorChatHistoryEntry> =
    mapNotNull { task ->
        val input = task.request.input as? TutorLobbyInput ?: return@mapNotNull null
        val output = task.output as? TutorLobbyOutput ?: return@mapNotNull null
        TutorChatHistoryEntry(
            studentMessage = input.studentMessage,
            assistantMarkdown = output.lobbyHistoryMarkdown(),
        ).takeIf { task.status == ModelTaskStatus.SUCCEEDED }
    }.takeLast(MAX_CONTEXT_MESSAGES)

private fun TutorLobbyOutput.lobbyHistoryMarkdown(): String = when (val directive = interactionDirective) {
    null,
    TutorInteractionDirective.Continue,
    -> messageMarkdown
    is TutorInteractionDirective.FreeResponse -> directive.promptMarkdown
    is TutorInteractionDirective.Choices -> directive.promptMarkdown
    is TutorInteractionDirective.VisualTarget -> directive.promptMarkdown
}

private const val MAX_VISIBLE_MESSAGES = 20
private const val MAX_CONTEXT_MESSAGES = 8
private const val MAX_PERSISTED_TASKS = 64
