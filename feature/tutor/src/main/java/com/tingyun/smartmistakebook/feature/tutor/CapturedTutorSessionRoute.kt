package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorAttachedQuestionReader
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorKnowledgeContextLoader
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.toContiguousTutorHistory
import com.tingyun.smartmistakebook.core.domain.toTutorConversationMemory
import android.util.Log
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCode
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
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
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.LocalImageLoadState
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
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
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
    /** 学生消息附图的资产读取器；null 时会话页不提供附图入口。 */
    imageIntake: LobbyMessageImageIntake? = null,
    /** 加号菜单「从错题库选择」选中后的题面读取器；null 时该菜单项不出现。 */
    attachedQuestionReader: TutorAttachedQuestionReader? = null,
    onOpenModelSettings: () -> Unit,
    /** 讲题历史入口；与大厅、错题讲题共用同一个页面标题栏，所以三个入口都有它。 */
    onOpenHistory: (() -> Unit)? = null,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onBack: () -> Unit,
    onEndedWithoutSave: () -> Unit = onBack,
    /** 拍照讲题知识注入（D5/审计 R2 断链一）；null 时维持零注入旧行为。 */
    knowledgeContextLoader: TutorKnowledgeContextLoader? = null,
    teachingReferenceRepository: TutorTeachingReferenceRepository? = null,
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
        conversations = conversations,
        modelTasks = modelTasks,
        interactions = interactions,
        profile = profile,
        catalogEntries = catalogEntries,
        longTermWritesBlocked = longTermWritesBlocked,
        attachedImageResolver = attachedImageResolver,
        imageIntake = imageIntake,
        attachedQuestionReader = attachedQuestionReader,
        onLongTermWritesBlocked = viewModel::markLongTermWritesBlocked,
        onOpenModelSettings = onOpenModelSettings,
        onOpenHistory = onOpenHistory,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onBack = onBack,
        knowledgeContextLoader = knowledgeContextLoader,
        teachingReferenceRepository = teachingReferenceRepository,
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
    /** 学生文字落库用；缺省 null 时该界面不落库（门控按空语料 fail-closed）。 */
    conversations: TutorConversationRepository? = null,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry>,
    longTermWritesBlocked: Boolean,
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
    imageIntake: LobbyMessageImageIntake? = null,
    /** 加号菜单「从错题库选择」选中后的题面读取器；null 时该菜单项不出现。 */
    attachedQuestionReader: TutorAttachedQuestionReader? = null,
    onLongTermWritesBlocked: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onBack: () -> Unit,
    knowledgeContextLoader: TutorKnowledgeContextLoader? = null,
    teachingReferenceRepository: TutorTeachingReferenceRepository? = null,
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
                attachedImageResolver = attachedImageResolver,
                imageIntake = imageIntake,
                attachedQuestionReader = attachedQuestionReader,
                modelTasks = modelTasks,
                interactions = interactions,
                conversations = conversations,
                profile = profile,
                catalogEntries = catalogEntries,
                longTermWritesBlocked = longTermWritesBlocked,
                onLongTermWritesBlocked = onLongTermWritesBlocked,
                onOpenModelSettings = onOpenModelSettings,
                onOpenHistory = onOpenHistory,
                onOpenMistakeNotebook = onOpenMistakeNotebook,
                onOpenProfile = onOpenProfile,
                onBack = onBack,
                knowledgeContextLoader = knowledgeContextLoader,
                teachingReferenceRepository = teachingReferenceRepository,
                modifier = modifier.testTag("captured_tutor_session_screen"),
            )

        else -> TutorConversationFrame(
            header = {
                TutorPageHeader(
                    onOpenCapabilitySettings = onOpenModelSettings,
                    onOpenHistory = onOpenHistory,
                    onBack = onBack,
                )
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
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
    imageIntake: LobbyMessageImageIntake? = null,
    /** 加号菜单「从错题库选择」选中后的题面读取器；null 时该菜单项不出现。 */
    attachedQuestionReader: TutorAttachedQuestionReader? = null,
    modelTasks: ModelTaskRepository,
    interactions: TutorInteractionRepository,
    /** 学生文字落库用；缺省 null 时该界面不落库（门控按空语料 fail-closed）。 */
    conversations: TutorConversationRepository? = null,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    longTermWritesBlocked: Boolean = false,
    onLongTermWritesBlocked: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onBack: () -> Unit = {},
    /**
     * 拍照讲题的知识注入（ADR 0001 / D5、审计 R2 断链一）：两段式检索出当前题候选节点
     * （未确认绑定 → RETRIEVAL_CANDIDATE 角色），材料走既有 referencesFor / 20k 预算
     * 选择器。null 时维持旧行为（零注入）——正常接线由 app 层提供。
     */
    knowledgeContextLoader: TutorKnowledgeContextLoader? = null,
    teachingReferenceRepository: TutorTeachingReferenceRepository? = null,
    clock: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
) {
    var sourceExpanded by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    // 模板照抄 SavedMistakeTutorRoute 的 produceState 取数，但 catch{emptyList()} 的
    // 静默降级改成 Log.w + 输入里的 loadFailed 标志（prompt 披露"教学材料未加载"）；
    // 检索零命中是合法空注入（loadFailed 保持 false，维持现状语义）。
    val knowledgeContext by produceState<CapturedTutorKnowledgeContext>(
        initialValue = CapturedTutorKnowledgeContext(
            preDisclosures = emptyList(),
            candidateNodeIds = emptyList(),
            reviewedTeachingReferences = emptyList(),
            loadFailed = false,
        ),
        key1 = session,
        key2 = knowledgeContextLoader,
        key3 = teachingReferenceRepository,
    ) {
        val loader = knowledgeContextLoader
        val references = teachingReferenceRepository
        if (loader == null || references == null) return@produceState
        val questionText = QuestionDocumentMarkdownProjection.project(
            session.questionDocument.document,
        )
        try {
            val pre = loader.knowledgePreDisclosure(
                subject = session.subject,
                confirmedBindingNodeIds = emptyList(),
                questionText = questionText,
            )
            var refsFailed = false
            val refs = if (pre.loadFailed || pre.candidateNodeIds.isEmpty()) {
                emptyList()
            } else {
                try {
                    references.referencesFor(
                        subject = session.subject,
                        knowledgeNodeIds = pre.candidateNodeIds.toSet(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w("CapturedTutorSession", "teaching references load failed: $failure")
                    refsFailed = true
                    emptyList()
                }
            }
            value = CapturedTutorKnowledgeContext(
                preDisclosures = pre.preDisclosures,
                candidateNodeIds = pre.candidateNodeIds,
                reviewedTeachingReferences = refs,
                loadFailed = pre.loadFailed || refsFailed,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w("CapturedTutorSession", "knowledge context load failed: $failure")
            value = CapturedTutorKnowledgeContext(
                preDisclosures = emptyList(),
                candidateNodeIds = emptyList(),
                reviewedTeachingReferences = emptyList(),
                loadFailed = true,
            )
        }
    }
    TutorModelPanel(
        question = session.toTutorQuestionContext().copy(
            relatedKnowledgeNodeIds = knowledgeContext.candidateNodeIds.toSet(),
            reviewedTeachingReferences = knowledgeContext.reviewedTeachingReferences,
            knowledgeCodes = knowledgeContext.preDisclosures,
            teachingReferencesLoadFailed = knowledgeContext.loadFailed,
        ),
        profile = profile,
        modelTasks = modelTasks,
        visualSourceAssetsReader = visualSourceAssetsReader,
        attachedImageResolver = attachedImageResolver,
        imageIntake = imageIntake,
        attachedQuestionReader = attachedQuestionReader,
        interactions = interactions,
        conversations = conversations,
        catalogEntries = catalogEntries,
        onLongTermWritesBlocked = onLongTermWritesBlocked,
        onRequestSave = { onSave(session) },
        onRequestEnd = onRequestEnd,
        onOpenMistakeNotebook = onOpenMistakeNotebook,
        onOpenProfile = onOpenProfile,
        onOpenVisualOriginal = { sourceExpanded = true },
        onOpenModelSettings = onOpenModelSettings,
        clock = clock,
        conversationEnabled = !session.isEndedWithoutSave,
        // 同一个页面标题栏：拍照会话与大堂、错题讲题长得一模一样。
        headerContent = {
            TutorPageHeader(
                onOpenCapabilitySettings = onOpenModelSettings,
                onOpenHistory = onOpenHistory,
                onBack = onBack,
            )
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
            InlineSourceImage(
                imageUri = session.sourceImageUri,
                onClick = { sourceExpanded = true },
                modifier = Modifier.padding(top = 14.dp),
            )
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
    if (sourceExpanded) {
        SourceImageFullscreenDialog(
            imageUri = session.sourceImageUri,
            onDismiss = { sourceExpanded = false },
        )
    }
}

/** 题面下方的常驻原图缩略图：点开全屏对照；原图读不到时给占位而不是静默空白。 */
@Composable
private fun InlineSourceImage(
    imageUri: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loadState by remember { mutableStateOf(LocalImageLoadState.LOADING) }
    Column(modifier = modifier) {
        BoundedLocalImage(
            imageUri = imageUri,
            contentDescription = "拍题原图，点开看大图",
            expanded = false,
            onLoadStateChange = { loadState = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .testTag("captured_tutor_source_image"),
        )
        if (loadState == LocalImageLoadState.UNAVAILABLE) {
            Text(
                text = "原图暂时打不开",
                color = InkMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourceImageFullscreenDialog(
    imageUri: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var loadState by remember { mutableStateOf(LocalImageLoadState.LOADING) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        ) {
            BoundedLocalImage(
                imageUri = imageUri,
                contentDescription = "拍题原图",
                expanded = true,
                onLoadStateChange = { loadState = it },
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag("captured_tutor_source_image_full"),
            )
            if (loadState == LocalImageLoadState.UNAVAILABLE) {
                Text(
                    text = "原图暂时打不开",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
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

/** 拍照会话的知识注入取数结果（两段式检索候选 + 材料 + 失败标志）。 */
private data class CapturedTutorKnowledgeContext(
    val preDisclosures: List<TutorKnowledgeCode>,
    val candidateNodeIds: List<String>,
    val reviewedTeachingReferences: List<TutorTeachingReference>,
    val loadFailed: Boolean,
)
