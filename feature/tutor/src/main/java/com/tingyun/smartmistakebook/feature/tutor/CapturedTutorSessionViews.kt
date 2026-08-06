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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCancellationReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorGuidancePolicy
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceOutcome
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceRequest
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.toTutorConversationMemory
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.isModelEgressApprovalFresh
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun CapturedTutorSessionContent(
    state: CapturedTutorSessionUiState,
    saveInProgress: Boolean,
    saveError: String?,
    endInProgress: Boolean,
    endError: String?,
    onSave: (ConfirmedTutorSession) -> Unit,
    onRequestEnd: () -> Unit,
    onRetryLoad: () -> Unit,
    repository: CaptureWorkflowRepository,
    modelTasks: ScopedModelTaskPort,
    sessionHost: TutorCurrentSessionHostPort,
    profile: StudyProfileOverview?,
    catalogEntries: List<StudyCatalogEntry>,
    learningWritesAllowed: Boolean,
    visualIntent: TutorCurrentSessionVisualIntent,
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    onCameraAttachment: () -> Unit,
    onGalleryAttachment: () -> Unit,
    onLibraryAttachment: () -> Unit,
    onBack: () -> Unit,
    autoStartAuthorization: TutorAutoStartAuthorization?,
    onAutoStartAuthorizationConsumed: (String) -> Unit,
    explanationMode: TutorExplanationMode,
    explanationModeVersion: Long,
    onExplanationModeChange: (TutorExplanationMode) -> Unit,
    masteryContextRepository: TutorMasteryContextRepository?,
    questionKnowledgeNodes: List<KnowledgeNodeRef>,
    fallbackKnowledgeNodes: List<KnowledgeNodeRef>,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is CapturedTutorSessionUiState.Ready -> HostReadyCapturedSession(
            session = state.session,
            saveInProgress = saveInProgress,
            saveError = saveError,
            endInProgress = endInProgress,
            endError = endError,
            onSave = onSave,
            onRequestEnd = onRequestEnd,
            visualOriginalAvailable = true,
            visualSourceAssetsReader = {
                repository.readTutorVisualSourceAssets(state.session.sessionId)
            },
            modelTasks = modelTasks,
            sessionHost = sessionHost,
            learningWritesAllowed = learningWritesAllowed,
            visualIntent = visualIntent,
            onOpenModelSettings = onOpenModelSettings,
            onBack = onBack,
            autoStartAuthorization = autoStartAuthorization,
            onAutoStartAuthorizationConsumed = onAutoStartAuthorizationConsumed,
            explanationMode = explanationMode,
            onExplanationModeChange = onExplanationModeChange,
            masteryContextRepository = masteryContextRepository,
            questionKnowledgeNodes = questionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
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
                    CapturedTutorSessionUiState.Loading -> LoadingTutorQuestion()
                    CapturedTutorSessionUiState.Missing -> TutorQuestionUnavailable(
                        title = "没有找到这次讲题",
                        detail = "这次题面没有保存完整，可返回拍题入口重新上传。",
                        onRetry = onRetryLoad,
                    )
                    CapturedTutorSessionUiState.Unavailable -> TutorQuestionUnavailable(
                        title = "暂时无法打开这次讲题",
                        detail = "题面没有完整载入，本机记录仍会保留。请稍后重试。",
                        onRetry = onRetryLoad,
                    )
                    is CapturedTutorSessionUiState.Ready -> Unit
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
internal fun HostReadyCapturedSession(
    session: ConfirmedTutorSession,
    saveInProgress: Boolean,
    saveError: String?,
    endInProgress: Boolean,
    endError: String?,
    onSave: (ConfirmedTutorSession) -> Unit,
    onRequestEnd: () -> Unit,
    visualOriginalAvailable: Boolean,
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope>,
    modelTasks: ScopedModelTaskPort,
    sessionHost: TutorCurrentSessionHostPort,
    learningWritesAllowed: Boolean,
    visualIntent: TutorCurrentSessionVisualIntent,
    onOpenModelSettings: () -> Unit,
    onBack: () -> Unit,
    autoStartAuthorization: TutorAutoStartAuthorization?,
    onAutoStartAuthorizationConsumed: (String) -> Unit,
    explanationMode: TutorExplanationMode,
    onExplanationModeChange: (TutorExplanationMode) -> Unit,
    masteryContextRepository: TutorMasteryContextRepository?,
    questionKnowledgeNodes: List<KnowledgeNodeRef>,
    fallbackKnowledgeNodes: List<KnowledgeNodeRef>,
    modifier: Modifier = Modifier,
) {
    var sourceExpanded by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    val question = remember(session, questionKnowledgeNodes, fallbackKnowledgeNodes) {
        session.toTutorQuestionContext().copy(
            questionKnowledgeNodes = questionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
        )
    }
    TutorCurrentSessionHostPanel(
        question = question,
        modelTasks = modelTasks,
        sessionHost = sessionHost,
        masteryContextRepository = masteryContextRepository,
        learningWritesAllowed = learningWritesAllowed,
        explanationMode = explanationMode,
        visualIntent = visualIntent,
        onExplanationModeChange = onExplanationModeChange,
        onOpenModelSettings = onOpenModelSettings,
        visualSourceAssetsReader = visualSourceAssetsReader,
        visualOriginalAvailable = visualOriginalAvailable,
        onOpenVisualOriginal = { sourceExpanded = true },
        autoStartAuthorization = autoStartAuthorization,
        onAutoStartAuthorizationConsumed = onAutoStartAuthorizationConsumed,
        conversationEnabled = !session.isEndedWithoutSave,
        headerContent = {
            TutorPageHeader(onBack)
            PaperDivider()
        },
        leadingContent = {
            SectionHeader(
                title = session.title,
                modifier = Modifier.padding(top = 10.dp),
                action = if (session.disposition == TutorSessionDisposition.ENDED_WITHOUT_SAVE) {
                    null
                } else {
                    {
                        OutlineActionChip(
                            text = tutorSessionSaveLabel(
                                session.isSaved,
                                saveInProgress,
                                saveError != null,
                            ),
                            onClick = { onSave(session) },
                            enabled = session.disposition == TutorSessionDisposition.ACTIVE &&
                                !saveInProgress && !endInProgress,
                            icon = Icons.Outlined.LibraryAddCheck,
                            contentDescription = if (session.isSaved) {
                                "本题已存入错题本"
                            } else {
                                "将本题存入错题本"
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
            if (visualOriginalAvailable) {
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
            }
            if (session.isEndedWithoutSave) EndedTutorSessionNotice()
        },
        trailingContent = {
            saveError?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.testTag("captured_tutor_save_error"),
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.disposition == TutorSessionDisposition.ACTIVE) {
                OutlineActionChip(
                    text = when {
                        endInProgress -> "正在结束"
                        endError != null -> "重试结束且不保存"
                        else -> "结束且不保存"
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
                    text = message,
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
internal fun ReadyCapturedSession(
    session: ConfirmedTutorSession,
    saveInProgress: Boolean,
    saveError: String?,
    endInProgress: Boolean = false,
    endError: String? = null,
    onSave: (ConfirmedTutorSession) -> Unit,
    onRequestEnd: () -> Unit = {},
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = {
        emptyList()
    },
    visualOriginalAvailable: Boolean = false,
    modelTasks: ScopedModelTaskPort,
    interactions: TutorInteractionRepository,
    learningMemory: TutorLearningMemoryRepository? = null,
    cleanupScope: CoroutineScope? = null,
    profile: StudyProfileOverview? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    longTermWritesBlocked: Boolean = false,
    learningWritePermissionVersion: Long = 0L,
    onLongTermWritesBlocked: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = onOpenMistakeNotebook,
    onBack: () -> Unit = {},
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    masteryContextRepository: TutorMasteryContextRepository? = null,
    questionKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var sourceExpanded by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    TutorModelPanel(
        question = session.toTutorQuestionContext().copy(
            questionKnowledgeNodes = questionKnowledgeNodes,
            fallbackKnowledgeNodes = fallbackKnowledgeNodes,
        ),
        profile = profile,
        modelTasks = modelTasks,
        masteryContextRepository = masteryContextRepository,
        visualSourceAssetsReader = visualSourceAssetsReader,
        visualOriginalAvailable = visualOriginalAvailable,
        interactions = interactions,
        capturedSession = session,
        learningMemory = learningMemory,
        cleanupScope = cleanupScope,
        catalogEntries = catalogEntries,
        allowLongTermLearningWrites = !longTermWritesBlocked,
        learningWritePermissionVersion = learningWritePermissionVersion,
        onLongTermWritesBlocked = onLongTermWritesBlocked,
        onRequestSave = { onSave(session) },
        onRequestEnd = onRequestEnd,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onCameraAttachment = onCameraAttachment,
        onGalleryAttachment = onGalleryAttachment,
        onLibraryAttachment = onLibraryAttachment,
        onOpenVisualOriginal = { sourceExpanded = true },
        onOpenModelSettings = onOpenModelSettings,
        autoStartAuthorization = autoStartAuthorization,
        onAutoStartAuthorizationConsumed = onAutoStartAuthorizationConsumed,
        explanationMode = explanationMode,
        explanationModeVersion = explanationModeVersion,
        onExplanationModeChange = onExplanationModeChange,
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
                    text = message,
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
                    text = message,
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
internal fun EndedTutorSessionNotice() {
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

