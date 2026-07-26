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
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun TutorLobbyRoute(
    onCapture: () -> Unit,
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    modelTasks: ModelTaskRepository,
    catalogEntries: List<StudyCatalogEntry>,
    profile: StudyProfileOverview,
    explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val persistedTasks by remember(modelTasks) {
        modelTasks.observeRecentBySubject(
            TUTOR_LOBBY_CONVERSATION_ID,
            ModelTaskKind.TUTOR_LOBBY,
            MAX_PERSISTED_TASKS,
        )
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
    var draft by rememberSaveable { mutableStateOf("") }
    var pendingDisclosureMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var sendError by rememberSaveable { mutableStateOf<String?>(null) }
    val hasActiveTask = conversationTasks.any { task ->
        task.status in setOf(
            ModelTaskStatus.WAITING_FOR_MODEL,
            ModelTaskStatus.QUEUED,
            ModelTaskStatus.RUNNING,
            ModelTaskStatus.STREAMING,
        )
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
            sendError = if (providerLoadFailed) {
                "暂时读不到模型配置，请检查后再试。"
            } else {
                "当前模型还不能处理对话，请先完成模型配置和能力测试。"
            }
            return
        }
        val occurredAt = System.currentTimeMillis()
        val nextOrdinal = conversationTasks
            .mapNotNull { task -> (task.request.input as? TutorLobbyInput)?.messageOrdinal }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        val request = buildTutorLobbyRequest(
            provider = currentProvider,
            messageOrdinal = nextOrdinal,
            studentMessage = message,
            priorMessages = conversationTasks.toLobbyHistory(),
            occurredAtEpochMillis = occurredAt,
            approvedAtEpochMillis = approvedAtEpochMillis,
        )
        pendingDisclosureMessage = null
        draft = ""
        sendError = null
        scope.launch {
            try {
                modelTasks.execute(request).collect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sendError = "这条消息已经保留，但暂时没有发出去。"
            }
        }
    }

    fun submitDraft() {
        val message = draft.trim()
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
                TutorTopBar(onOpenCapabilitySettings = onOpenCapabilitySettings)
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
                    catalogEntries = catalogEntries,
                    profile = profile,
                    onOpenMistakeNotebook = onOpenMistakeNotebook,
                    onOpenProfile = onOpenProfile,
                    onOpenCapabilitySettings = onOpenCapabilitySettings,
                    modifier = Modifier.padding(top = 12.dp),
                )
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
                onCapture = onCapture,
                onGallery = onCapture,
                onChooseExisting = onChooseExisting,
                onSend = ::submitDraft,
                explanationMode = explanationMode,
                onExplanationModeChange = onExplanationModeChange,
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
            assistantMarkdown = output.messageMarkdown,
        ).takeIf { task.status == ModelTaskStatus.SUCCEEDED }
    }.takeLast(MAX_CONTEXT_MESSAGES)

private const val MAX_VISIBLE_MESSAGES = 20
private const val MAX_CONTEXT_MESSAGES = 8
private const val MAX_PERSISTED_TASKS = 64
