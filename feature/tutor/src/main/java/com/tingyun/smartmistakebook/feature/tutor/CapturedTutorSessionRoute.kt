package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.toContiguousTutorHistory
import com.tingyun.smartmistakebook.core.domain.toTutorConversationMemory
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.AppFailureCode
import com.tingyun.smartmistakebook.core.model.isModelEgressApprovalFresh
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.StructuredContentRenderer
import com.tingyun.smartmistakebook.core.ui.studentSubjectLabel
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun CapturedTutorSessionRoute(
    sessionId: String,
    repository: CaptureWorkflowRepository,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    conversations: TutorConversationRepository,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onBack: () -> Unit,
    onEndedWithoutSave: () -> Unit = onBack,
    /** Global "model agent" consent; when OFF external plan/respond/visual egress fails closed. */
    agentConsentEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showEndConfirmation by rememberSaveable(sessionId) { mutableStateOf(false) }
    val viewModel: TutorSessionViewModel = viewModel(
        key = "tutor-session-$sessionId",
        factory = TutorSessionViewModelFactory(
            repository = repository,
            conversations = conversations,
            sessionId = sessionId,
        ),
    )
    val state by viewModel.uiState.collectAsState()
    val saveInProgress by viewModel.saveInProgress.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val endInProgress by viewModel.endInProgress.collectAsState()
    val endError by viewModel.endError.collectAsState()
    val longTermWritesBlocked by viewModel.longTermWritesBlocked.collectAsState()
    val pendingEnd by viewModel.pendingEnd.collectAsState()

    LaunchedEffect(pendingEnd) {
        if (pendingEnd) {
            onEndedWithoutSave()
            viewModel.onEndConsumed()
        }
    }

    CapturedTutorSessionContent(
        state = state,
        saveInProgress = saveInProgress,
        saveError = saveError,
        endInProgress = endInProgress,
        endError = endError,
        onSave = viewModel::save,
        onRequestEnd = { showEndConfirmation = true },
        onRetryLoad = viewModel::reload,
        repository = repository,
        modelTasks = modelTasks,
        interactions = interactions,
        profile = profile,
        catalogEntries = catalogEntries,
        longTermWritesBlocked = longTermWritesBlocked,
        onLongTermWritesBlocked = viewModel::markLongTermWritesBlocked,
        onOpenModelSettings = onOpenModelSettings,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onBack = onBack,
        agentConsentEnabled = agentConsentEnabled,
        modifier = modifier,
    )


    if (showEndConfirmation) {
        AlertDialog(
            onDismissRequest = { showEndConfirmation = false },
            title = { Text("结束这次临时讲题？") },
            text = {
                Text("结束后不会加入错题本；题面和原图仍安全保留在本机。返回只表示稍后继续，不会结束。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val session = (state as? TutorSessionUiState.Ready)?.session
                        showEndConfirmation = false
                        if (session != null) viewModel.endWithoutSaving(session)
                    },
                    modifier = Modifier.testTag("captured_tutor_end_confirm"),
                ) {
                    Text("结束且不保存", color = ErrorWarm)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEndConfirmation = false },
                    modifier = Modifier.testTag("captured_tutor_end_cancel"),
                ) {
                    Text("继续讲题", color = JadeActive)
                }
            },
            containerColor = com.tingyun.smartmistakebook.core.ui.Paper,
        )
    }
}

@Composable
private fun CapturedTutorSessionContent(
    state: TutorSessionUiState,
    saveInProgress: Boolean,
    saveError: AppFailure?,
    endInProgress: Boolean,
    endError: AppFailure?,
    onSave: (ConfirmedTutorSession) -> Unit,
    onRequestEnd: () -> Unit,
    onRetryLoad: () -> Unit,
    repository: CaptureWorkflowRepository,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry>,
    longTermWritesBlocked: Boolean,
    onLongTermWritesBlocked: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    onBack: () -> Unit,
    /** Global "model agent" consent; when OFF external plan/respond/visual egress fails closed. */
    agentConsentEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is TutorSessionUiState.Ready -> ReadyCapturedSession(
                session = state.session,
                saveInProgress = saveInProgress,
                saveError = saveError,
                endInProgress = endInProgress,
                endError = endError,
                onSave = onSave,
                onRequestEnd = onRequestEnd,
                visualSourceAssetsReader = {
                    repository.readTutorVisualSourceAssets(state.session.sessionId)
                },
                modelTasks = modelTasks,
                interactions = interactions,
                profile = profile,
                catalogEntries = catalogEntries,
                longTermWritesBlocked = longTermWritesBlocked,
                onLongTermWritesBlocked = onLongTermWritesBlocked,
                onOpenModelSettings = onOpenModelSettings,
                onOpenMistakeNotebook = onOpenMistakeNotebook,
                onOpenProfile = onOpenProfile,
                onBack = onBack,
                agentConsentEnabled = agentConsentEnabled,
                modifier = modifier.testTag("captured_tutor_session_screen"),
            )

        else -> TutorConversationFrame(
            header = {
                TutorPageHeader(onBack)
                PaperDivider()
            },
            autoScrollVersion = state,
            modifier = modifier.testTag("captured_tutor_session_screen"),
        ) {
            item("captured_tutor_non_ready") {
                when (state) {
                    TutorSessionUiState.Loading -> LoadingTutorQuestion()
                    TutorSessionUiState.Missing -> TutorQuestionUnavailable(
                        title = "没有找到这次讲题",
                        detail = "这次题面没有保存完整，可返回拍题入口重新上传。",
                        onRetry = onRetryLoad,
                    )
                    TutorSessionUiState.Unavailable -> TutorQuestionUnavailable(
                        title = "暂时无法打开这次讲题",
                        detail = "题面没有完整载入，本机记录仍会保留。请稍后重试。",
                        onRetry = onRetryLoad,
                    )
                    is TutorSessionUiState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
internal fun TutorPageHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .testTag("captured_tutor_back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Ink,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "讲题",
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
internal fun LoadingTutorQuestion() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
            .testTag("captured_tutor_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = JadeActive,
        )
        Text("正在打开这道题…", color = InkSecondary)
    }
}

@Composable
internal fun ReadyCapturedSession(
    session: ConfirmedTutorSession,
    saveInProgress: Boolean,
    saveError: AppFailure?,
    endInProgress: Boolean = false,
    endError: AppFailure? = null,
    onSave: (ConfirmedTutorSession) -> Unit,
    onRequestEnd: () -> Unit = {},
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = {
        emptyList()
    },
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    longTermWritesBlocked: Boolean = false,
    onLongTermWritesBlocked: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onBack: () -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    /** Global "model agent" consent; when OFF external plan/respond/visual egress fails closed. */
    agentConsentEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var sourceExpanded by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    TutorModelPanel(
        question = session.toTutorQuestionContext(),
        profile = profile,
        modelTasks = modelTasks,
        visualSourceAssetsReader = visualSourceAssetsReader,
        interactions = interactions,
        catalogEntries = catalogEntries,
        onLongTermWritesBlocked = onLongTermWritesBlocked,
        onRequestSave = { onSave(session) },
        onRequestEnd = onRequestEnd,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onOpenVisualOriginal = { sourceExpanded = true },
        onOpenModelSettings = onOpenModelSettings,
        consentEnabled = agentConsentEnabled,
        clock = clock,
        conversationEnabled = !session.isEndedWithoutSave,
        headerContent = {
            TutorPageHeader(onBack)
            PaperDivider()
        },
        leadingContent = {
            LocalModeLine(text = tutorSessionStatusLine(session))
            SectionHeader(
                title = session.title,
                modifier = Modifier.padding(top = 10.dp),
                action = if (session.disposition == TutorSessionDisposition.ENDED_WITHOUT_SAVE) {
                    null
                } else {
                    {
                        OutlineActionChip(
                            text = if (longTermWritesBlocked) {
                                "本次不记录"
                            } else {
                                tutorSessionSaveLabel(
                                    session.isSaved,
                                    saveInProgress,
                                    saveError != null,
                                )
                            },
                            onClick = { onSave(session) },
                            enabled = session.disposition == TutorSessionDisposition.ACTIVE &&
                                !saveInProgress && !endInProgress && !longTermWritesBlocked,
                            icon = Icons.Outlined.LibraryAddCheck,
                            contentDescription = when {
                                longTermWritesBlocked -> "本次不会存入错题本"
                                session.isSaved -> "本题已存入错题本"
                                else -> "将本题存入错题本"
                            },
                            modifier = Modifier.testTag("captured_tutor_save"),
                        )
                    }
                },
            )
            Text(
                text = session.subject.studentSubjectLabel(),
                modifier = Modifier.padding(top = 4.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            StructuredContentRenderer(
                document = session.questionDocument.document,
                choicesEnabled = false,
            )
            OutlineActionChip(
                text = if (sourceExpanded) "收起原图" else "查看原图",
                onClick = { sourceExpanded = !sourceExpanded },
                icon = Icons.Outlined.Image,
                contentDescription = if (sourceExpanded) "收起拍题原图" else "查看拍题原图",
                modifier = Modifier
                    .padding(top = 14.dp)
                    .testTag("captured_tutor_source_toggle"),
            )
            if (sourceExpanded) {
                BoundedLocalImage(
                    imageUri = session.sourceImageUri,
                    contentDescription = "拍题原图",
                    expanded = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag("captured_tutor_source_image"),
                )
            }
            if (session.isEndedWithoutSave) EndedTutorSessionNotice()
        },
        trailingContent = {
            saveError?.let { message ->
                Text(
                    text = message.message,
                    modifier = Modifier.testTag("captured_tutor_save_error"),
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.disposition == TutorSessionDisposition.ACTIVE) {
                OutlineActionChip(
                    text = if (endInProgress) {
                        "正在结束"
                    } else if (endError != null) {
                        "重试结束且不保存"
                    } else {
                        "结束且不保存"
                    },
                    onClick = onRequestEnd,
                    enabled = !saveInProgress && !endInProgress,
                    icon = Icons.Outlined.DeleteOutline,
                    contentDescription = "结束本次临时讲题且不存入错题本",
                    modifier = Modifier.testTag("captured_tutor_end_without_save"),
                )
            }
            endError?.let { message ->
                Text(
                    text = message.message,
                    modifier = Modifier.testTag("captured_tutor_end_error"),
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
        },
        modifier = modifier,
    )
}

@Composable
private fun EndedTutorSessionNotice() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        color = JadeSoft.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "本次讲题已结束 · 未存入错题本",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "题面和原图仍保存在本机，但不会生成错题或继续调用模型。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
