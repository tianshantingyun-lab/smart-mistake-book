package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.TutorRoundQuestionBindingPolicy
import com.tingyun.smartmistakebook.core.domain.TutorRoundQuestionRetriever
import com.tingyun.smartmistakebook.core.domain.previousBoundRoundQuestion
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorSendAction
import com.tingyun.smartmistakebook.core.domain.TutorSendPhase
import com.tingyun.smartmistakebook.core.domain.TutorSendState
import com.tingyun.smartmistakebook.core.domain.TutorTurnSendStateMachine
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.toContiguousTutorHistory
import com.tingyun.smartmistakebook.core.domain.toTutorConversationMemory
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.ActionType
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.AppFailureCode
import com.tingyun.smartmistakebook.core.model.Retryability
import com.tingyun.smartmistakebook.core.model.appFailure
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
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.MAX_TUTOR_MESSAGE_IMAGES
import com.tingyun.smartmistakebook.feature.tutor.MessageAttachmentDialog
import com.tingyun.smartmistakebook.feature.tutor.PendingMessageImage
import com.tingyun.smartmistakebook.feature.tutor.PendingMessageImagesRow
import com.tingyun.smartmistakebook.feature.tutor.tutorRespondImageIntakeError
import com.tingyun.smartmistakebook.feature.tutor.tutorRespondImageUnsupportedError
import java.io.File
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch



@Composable
internal fun TutorModelPanel(
    question: TutorQuestionContext,
    profile: StudyProfileOverview,
    modelTasks: ModelTaskRepository,
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = {
        emptyList()
    },
    interactions: TutorInteractionRepository,
    /**
     * 学生会话的对话仓库：学生每一轮文字都要落进 `tutor_message`，供写侧门控
     * 逐字核对模型引文（见 `TutorRespondCommands.recordStudentTurnIfNeeded`）。
     * null 时该界面不落库，门控按空语料 fail-closed。
     */
    conversations: TutorConversationRepository? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    /**
     * 本轮候选菜单的本地文本检索源。null 时只组上一轮绑定的题这一条来源——
     * 没有检索就等于菜单里只有身份确定的题，本轮多半判成无题轮，不会误绑。
     */
    roundQuestionRetriever: TutorRoundQuestionRetriever? = null,
    onLongTermWritesBlocked: () -> Unit = {},
    onRequestSave: () -> Unit = {},
    onRequestEnd: () -> Unit = {},
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenVisualOriginal: () -> Unit = {},
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
    /**
     * 学生消息附图的读取器：既用于把选中的图片登记成规范资产，也用于在气泡里回显。
     * null 时会话页不提供附图入口（例如没有可用的资产库）。
     */
    imageIntake: LobbyMessageImageIntake? = null,
    onOpenModelSettings: () -> Unit,
    conversationEnabled: Boolean = true,
    headerContent: @Composable () -> Unit = {},
    leadingContent: @Composable ColumnScope.() -> Unit = {},
    trailingContent: @Composable ColumnScope.() -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
) {
    if (!conversationEnabled) {
        TutorConversationFrame(
            header = headerContent,
            autoScrollVersion = "${question.sessionId}:${question.revisionNumber}:ended",
            modifier = modifier,
        ) {
            item("tutor_question_context") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = leadingContent,
                )
            }
            item("tutor_session_footer") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = trailingContent,
                )
            }
        }
        return
    }
    var provider by remember(question.sessionId) { mutableStateOf<ProviderCapabilitySnapshot?>(null) }
    var providerLoadFailed by remember(question.sessionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sessionContext = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val persistedTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_PLAN)
    }.collectAsState(initial = emptyList())
    val persistedRespondTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_RESPOND)
    }.collectAsState(initial = emptyList())
    val persistedVisualGenerationTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_GENERATE)
    }.collectAsState(initial = emptyList())
    val persistedVisualReviewTasks by remember(question.sessionId) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_REVIEW)
    }.collectAsState(initial = emptyList())
    var visualSourceAssets by remember(question.sessionId, question.revisionNumber) {
        mutableStateOf<List<TutorVisualSourceAssetScope>>(emptyList())
    }
    LaunchedEffect(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) {
        visualSourceAssets = runCatching { visualSourceAssetsReader() }
            .getOrDefault(emptyList())
            .sortedBy(TutorVisualSourceAssetScope::pageIndex)
    }
    val longTermWritesBlocked = persistedRespondTasks.blocksTutorLongTermWrites()
    LaunchedEffect(longTermWritesBlocked) {
        if (longTermWritesBlocked) onLongTermWritesBlocked()
    }
    val persistedResponses by remember(question.sessionId, interactions) {
        interactions.observe(question.sessionId)
    }.collectAsState(initial = emptyList())
    var interactionBusy by remember(question.sessionId) { mutableStateOf(false) }
    var interactionError by remember(question.sessionId) { mutableStateOf<String?>(null) }
    var chatDraft by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf("") }
    var chatSubmitPending by remember(question.sessionId) { mutableStateOf(false) }
    var tutorSendState by remember(question.sessionId) { mutableStateOf(TutorSendState()) }
    var locallyStartedRespondRequestId by remember(question.sessionId) {
        mutableStateOf<String?>(null)
    }
    var chatStartError by remember(question.sessionId) {
        mutableStateOf<AppFailure?>(null)
    }
    // 待发送的附图：与大厅同一套（选择 → 预览 → 发送时登记成规范资产）。
    var pendingImages by remember(question.sessionId) {
        mutableStateOf<List<PendingMessageImage>>(emptyList())
    }
    var attachMenuOpen by remember(question.sessionId) { mutableStateOf(false) }
    var pendingCameraImageUri by remember(question.sessionId) { mutableStateOf<String?>(null) }
    val sessionImageEnabled = imageIntake != null
    var reportedVisualSceneIds by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(question.sessionId) {
        try {
            provider = modelTasks.capabilities()
            providerLoadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            providerLoadFailed = true
        }
    }
    DisposableEffect(lifecycleOwner, question.sessionId, modelTasks) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    try {
                        provider = modelTasks.capabilities()
                        providerLoadFailed = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        providerLoadFailed = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentProvider = provider
    val conversationProjection = remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        persistedTasks,
        persistedRespondTasks,
        persistedResponses,
    ) {
        buildTutorConversationProjection(
            question = question,
            planTasks = persistedTasks,
            respondTasks = persistedRespondTasks,
            responses = persistedResponses,
        )
    }
    val tutorTasks = conversationProjection.planTasks
    val tutorResponses = conversationProjection.responses
    val tutorRespondTasks = conversationProjection.respondTasks
    val timeline = conversationProjection.timeline
    // Deliberately process-only: an answer is not durably unlocked until its exact bottom is visible.
    var planSolutionPreviewKeys by remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
    ) { mutableStateOf(emptySet<PlanSolutionPreviewKey>()) }
    val solutionExposureTracker = rememberTutorSolutionExposureTracker(
        question = question,
        timeline = timeline,
        responses = tutorResponses,
        previewKeys = planSolutionPreviewKeys,
        longTermWritesBlocked = longTermWritesBlocked,
        interactions = interactions,
        clock = clock,
    )
    val answerExposureKeys = solutionExposureTracker.answerExposureKeys
    val currentCycle = conversationProjection.currentCycle
    val currentCycleTasks = conversationProjection.currentCyclePlanTasks
    val currentCycleResponses = conversationProjection.currentCycleResponses
    val observedTask = conversationProjection.observedPlanTask
    val executablePlanProvider = currentProvider?.takeIf { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(ModelTaskKind.TUTOR_PLAN)
    }

    val planCommands = remember(question.sessionId) {
        TutorPlanCommands(
            scope = scope,
            sink = TutorPlanSink(
                // Must read the backing state, not the composition-scoped
                // `executablePlanProvider` val: this lambda is captured once
                // by remember and would otherwise see the provider as it was
                // during the FIRST composition (null), silently killing every
                // auto-started turn (KD-1, docs/known-defects.md).
                provider = {
                    provider?.takeIf { candidate ->
                        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                            candidate.supports(ModelTaskKind.TUTOR_PLAN)
                    }
                },
                question = { question },
                profile = { profile },
                clock = clock,
                planTasks = { tutorTasks },
                modelTasks = modelTasks,
            ),
        )
    }

    fun executeTurn(
        cycleOrdinal: Int,
        priorConversationMemory: TutorConversationMemory?,
        priorCycleStudentMessages: List<String>,
        priorTurns: List<TutorTurnHistoryEntry>,
    ) {
        planCommands.executeTurn(
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
        )
    }


    val recoverableLocalPlanTask = observedTask?.takeIf { task ->
        currentProvider?.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS &&
            task.request.egressManifest == null &&
            task.status.isTutorExecutionPending()
    }
    LaunchedEffect(
        recoverableLocalPlanTask?.request?.requestId,
        recoverableLocalPlanTask?.stateVersion,
    ) {
        recoverableLocalPlanTask?.let { task -> modelTasks.execute(task.request).collect() }
    }
    LaunchedEffect(
        executablePlanProvider?.providerId,
        observedTask,
    ) {
        if (
            observedTask == null &&
            executablePlanProvider != null &&
            tutorAgentChatEnabled(executablePlanProvider, ModelTaskKind.TUTOR_PLAN)
        ) {
            executeTurn(1, null, emptyList(), emptyList())
        }
    }

    if (observedTask == null) {
        TutorConversationFrame(
            header = headerContent,
            autoScrollVersion = listOf(
                question.sessionId,
                question.revisionNumber,
                currentProvider?.providerConfigurationVersion,
                providerLoadFailed,
            ),
            modifier = modifier,
        ) {
            item("tutor_question_context") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = leadingContent,
                )
            }
            item("tutor_model_entry") {
                when {
                    currentProvider == null -> TutorModelStatusCard(
                        title = if (providerLoadFailed) "暂时没准备好" else "正在准备这道题",
                        detail = if (providerLoadFailed) {
                            "题目已经保存，检查设置后可以继续。"
                        } else {
                            "请稍候。"
                        },
                        actionLabel = if (providerLoadFailed) "检查设置" else null,
                        onAction = onOpenModelSettings,
                    )

                    executablePlanProvider == null -> TutorModelStatusCard(
                        title = "需要先连接大模型",
                        detail = "题目已经保存，配置完成后可以从这里继续。",
                        actionLabel = "去设置",
                        onAction = onOpenModelSettings,
                    )

                    else -> TutorModelStatusCard(
                        title = "正在准备这道题",
                        detail = "正在整理讲解，请稍候。",
                    )
                }
            }
            item("tutor_session_footer") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = trailingContent,
                )
            }
        }
        return
    }

    val currentInput = observedTask.request.input as TutorPlanInput
    val currentResponse = conversationProjection.responsesByTurn[
        TutorTurnKey(currentInput.cycleOrdinal, currentInput.turnOrdinal)
    ]
    val currentHistory = currentCycleResponses.toContiguousTutorHistory()
    val nextTurnExists = currentCycleTasks.any { task ->
        (task.request.input as? TutorPlanInput)?.turnOrdinal == currentHistory.size + 1
    }
    val currentPlanOutput = observedTask.output as? TutorPlanOutput
    val respondSupported = currentProvider?.let { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(ModelTaskKind.TUTOR_RESPOND)
    } == true
    val latestRespondTasks = conversationProjection.latestRespondTasks
    val respondAgentAuthorized =
        tutorAgentChatEnabled(currentProvider, ModelTaskKind.TUTOR_RESPOND)
    val chatSending = chatSubmitPending || latestRespondTasks.any { task ->
        currentProvider?.let(task::matchesTutorProvider) == true &&
            task.status.isTutorExecutionPending()
    }
    // 在途的一轮：正常派发、失败重试、恢复未完成任务三条路径落在同一个请求标识上，
    // 所以实时流只订阅这一个（订阅写在组合里，不会漏掉任何一条派发路径）。
    val recoverableRespondTask = latestRespondTasks.lastOrNull { task ->
        currentProvider?.let(task::matchesTutorProvider) == true &&
            task.status.isTutorExecutionPending()
    }
    val liveTurn = rememberTutorLiveTurn(modelTasks, recoverableRespondTask?.request?.requestId)
    val visualWorkSeeds = remember(tutorTasks, tutorRespondTasks) {
        // 2D/3D 结构化场景已隔离：不再生成视觉任务。改 TutorVisualIsolation.STRUCTURED_SCENE_ISOLATED 恢复。
        if (TutorVisualIsolation.STRUCTURED_SCENE_ISOLATED) {
            emptyList()
        } else {
            tutorVisualWorkSeeds(
                planTasks = tutorTasks,
                respondTasks = tutorRespondTasks,
            )
        }
    }
    val visualWorkPlan = remember(visualWorkSeeds) {
        planTutorVisualWork(visualWorkSeeds)
    }
    var visualGenerateBuildFailures by remember(question.sessionId) {
        mutableStateOf<Set<TutorVisualTurnAnchor>>(emptySet())
    }
    var visualReviewBuildFailures by remember(question.sessionId) {
        mutableStateOf<Set<TutorVisualTurnAnchor>>(emptySet())
    }
    val resolvedVisualScenes = remember(
        visualWorkSeeds,
        persistedVisualGenerationTasks,
        persistedVisualReviewTasks,
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        reportedVisualSceneIds,
    ) {
        visualWorkSeeds.mapNotNull { seed ->
            (resolveTutorVisual(
                anchor = seed.anchor,
                question = question,
                generationTasks = persistedVisualGenerationTasks,
                reviewTasks = persistedVisualReviewTasks,
            ) as? TutorVisualResolution.Ready)?.let { ready ->
                ready.scene
                    .takeUnless { scene -> scene.sceneId in reportedVisualSceneIds }
                    ?.let { scene -> seed.anchor to scene }
            }
        }.toMap()
    }
    val visualItemNotices = remember(
        visualWorkPlan,
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visualGenerateBuildFailures,
        visualReviewBuildFailures,
        persistedVisualGenerationTasks,
        persistedVisualReviewTasks,
    ) {
        tutorVisualItemNotices(
            selectedSeeds = visualWorkPlan.selectedSeeds,
            question = question,
            requestBuildFailedAnchors = visualGenerateBuildFailures + visualReviewBuildFailures,
            generationTasks = persistedVisualGenerationTasks,
            reviewTasks = persistedVisualReviewTasks,
        ).associateBy(TutorVisualItemNotice::anchor)
    }
    val visualOverflowMessage = remember(visualWorkPlan) {
        tutorVisualOverflowMessage(visualWorkPlan.overflowCount)
    }
    fun reportVisualIncorrect(sceneId: String) {
        if (sceneId !in reportedVisualSceneIds) {
            reportedVisualSceneIds = reportedVisualSceneIds + sceneId
            onOpenVisualOriginal()
        }
    }

    val visualWork = remember(question.sessionId) {
        TutorVisualWorkCommands(
            sink = TutorVisualWorkSink(
                currentProvider = { currentProvider },
                question = { question },
                clock = clock,
                sourceAssets = { visualSourceAssets },
                selectedSeeds = { visualWorkPlan.selectedSeeds },
                generationTasks = { persistedVisualGenerationTasks },
                reviewTasks = { persistedVisualReviewTasks },
                setGenerateBuildFailures = { visualGenerateBuildFailures = it },
                setReviewBuildFailures = { visualReviewBuildFailures = it },
                modelTasks = modelTasks,
            ),
        )
    }

    LaunchedEffect(
        visualWorkSeeds,
        visualSourceAssets,
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        persistedVisualGenerationTasks,
    ) {
        visualWork.dispatchGenerate()
    }

    LaunchedEffect(
        visualWorkSeeds,
        visualSourceAssets,
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        persistedVisualGenerationTasks,
        persistedVisualReviewTasks,
    ) {
        visualWork.dispatchReview()
    }

    /**
     * 本轮候选菜单（派发前组好）：上一轮绑定的题 + 本地文本检索前 N 条。
     *
     * 第三条来源"本轮学生显式添加的题"在会话页暂时恒为空：P1-b 之后会话页的加号里仍然只有
     * 拍照/相册，换题（"从错题库选择"选中的题落在**当前会话**而不是新开一个会话）属于 P3；
     * 在这之前"显式添加"在这条路径上不存在，不能凭空造一条。菜单的组装 API 已经带上了这个
     * 位置（`assembleCandidates(explicitlyAdded = …)`），P3 接上即可。
     *
     * 检索是 suspend 的，所以在草稿变化时预先算好放进状态：`execute` 是点击即发的非 suspend
     * 路径，按下时现算会引入一次可见等待——或者更糟，按钮先亮后发。
     */
    val previousBoundQuestion = remember(persistedRespondTasks, question.sessionId) {
        previousBoundRoundQuestion(persistedRespondTasks)
    }
    var retrievedCandidates by remember(question.sessionId) {
        mutableStateOf(emptyList<RelatedProblemCandidate>())
    }
    LaunchedEffect(
        chatDraft,
        question.sessionId,
        question.revisionNumber,
        catalogEntries,
        roundQuestionRetriever,
    ) {
        val retriever = roundQuestionRetriever
        if (retriever == null || chatDraft.isBlank()) {
            retrievedCandidates = emptyList()
            return@LaunchedEffect
        }
        retrievedCandidates = runCatching {
            retriever.retrieve(
                catalog = catalogEntries,
                studentMessage = chatDraft,
                excluded = listOfNotNull(previousBoundQuestion),
                limit = TutorRoundQuestionBindingPolicy.MAX_CANDIDATES,
            )
        }.getOrDefault(emptyList())
    }
    val boundQuestionCandidates = remember(previousBoundQuestion, retrievedCandidates) {
        TutorRoundQuestionBindingPolicy.assembleCandidates(
            explicitlyAdded = emptyList(),
            previouslyBound = listOfNotNull(previousBoundQuestion),
            retrieved = retrievedCandidates,
        )
    }

    val respondCommands = remember(question.sessionId) {
        TutorRespondCommands(
            scope = scope,
            sink = TutorRespondSink(
                currentProvider = { currentProvider },
                question = { question },
                profile = { profile },
                clock = clock,
                chatSubmitPending = { chatSubmitPending },
                setChatSubmitPending = { chatSubmitPending = it },
                tutorSendState = { tutorSendState },
                setTutorSendState = { tutorSendState = it },
                setChatStartError = { chatStartError = it },
                setLocallyStartedRespondRequestId = { locallyStartedRespondRequestId = it },
                setChatDraft = { chatDraft = it },
                currentPlanOutput = { currentPlanOutput },
                currentResponse = { currentResponse },
                currentInput = { currentInput },
                observedTask = { observedTask },
                tutorRespondTasks = { tutorRespondTasks },
                // 与上面一行是两个口径：上面是"这道题聊过什么"，这里是"这个会话走到第几轮"。
                // 轮次号必须按会话分配，否则同一会话换题会撞 model_task/tutor_message 的唯一槽。
                sessionRespondTasks = { persistedRespondTasks },
                answerExposureKeys = { answerExposureKeys },
                chatSending = { chatSending },
                modelTasks = modelTasks,
                conversations = conversations,
            ),
        )
    }

    fun collectTutorRespondRequest(
        request: ModelTaskRequest,
        clearDraftOnPersist: Boolean,
        allowExternalEnvelopeForLocalRecovery: Boolean = false,
        isRetry: Boolean = false,
    ) {
        respondCommands.collect(
            request = request,
            clearDraftOnPersist = clearDraftOnPersist,
            allowExternalEnvelopeForLocalRecovery = allowExternalEnvelopeForLocalRecovery,
            isRetry = isRetry,
        )
    }

    fun executeTutorResponse(
        message: String,
        requestedMove: TutorMoveType? = null,
        clearDraftOnPersist: Boolean = false,
        studentImageAssetIds: List<String> = emptyList(),
    ) {
        respondCommands.execute(
            message = message,
            requestedMove = requestedMove,
            clearDraftOnPersist = clearDraftOnPersist,
            studentImageAssetIds = studentImageAssetIds,
            boundQuestionCandidates = boundQuestionCandidates,
        )
    }

    /**
     * 发一条学生会话消息，带上已选好的附图。
     *
     * 附图在**发送时**才登记成规范资产（选中时只是本地 uri），这与大厅同口径：登记要读图、
     * 解码、算哈希，不该在选图那一刻阻塞界面；而一旦登记失败就如实说图片读不出来，
     * 不把消息发出去——否则模型会收到一条没有图的"看图"请求。
     */
    fun submitTutorResponse(message: String) {
        val selected = pendingImages
        val intake = imageIntake
        if (selected.isEmpty() || intake == null) {
            executeTutorResponse(message, clearDraftOnPersist = true)
            return
        }
        if (provider?.supportsImageInput != true) {
            chatStartError = tutorRespondImageUnsupportedError()
            return
        }
        scope.launch {
            val assetIds = runCatching {
                selected
                    .map { pending ->
                        intake.registerImage(pending.localUri, System.currentTimeMillis())
                    }
                    // 资产库按内容寻址：同一张照片被选两次会得到同一个 assetId，而请求契约
                    // 要求互不重复，所以按 assetId 去重而不是让重复选择顶掉整条消息。
                    .distinctBy { image -> image.assetId }
                    .map { image -> image.assetId }
            }.getOrElse { failure ->
                android.util.Log.w("TutorSession", "Attached image could not be registered", failure)
                chatStartError = tutorRespondImageIntakeError()
                return@launch
            }
            pendingImages = emptyList()
            executeTutorResponse(
                message = message,
                clearDraftOnPersist = true,
                studentImageAssetIds = assetIds,
            )
        }
    }

    val sessionCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingCameraImageUri
        pendingCameraImageUri = null
        if (saved && uri != null) {
            pendingImages = (pendingImages + PendingMessageImage(uri))
                .take(MAX_TUTOR_MESSAGE_IMAGES)
        }
    }
    val sessionGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_TUTOR_MESSAGE_IMAGES),
    ) { selectedUris ->
        val room = MAX_TUTOR_MESSAGE_IMAGES - pendingImages.size
        pendingImages = (pendingImages + selectedUris.take(room).map { uri ->
            PendingMessageImage(uri.toString())
        }).take(MAX_TUTOR_MESSAGE_IMAGES)
    }

    fun launchSessionCamera() {
        val directory = File(sessionContext.cacheDir, "captured_images").apply {
            if (!isDirectory) mkdirs()
        }
        val file = File(directory, "session-${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            sessionContext,
            "${sessionContext.packageName}.capture.fileprovider",
            file,
        )
        pendingCameraImageUri = uri.toString()
        sessionCameraLauncher.launch(uri)
    }

    fun retryTutorResponse(task: ModelTaskSnapshot) {
        respondCommands.retry(task)
    }


    LaunchedEffect(
        recoverableRespondTask?.request?.requestId,
        respondAgentAuthorized,
    ) {
        if (respondAgentAuthorized) {
            recoverableRespondTask
                ?.takeUnless { it.request.requestId == locallyStartedRespondRequestId }
                ?.let { task -> modelTasks.execute(task.request).collect() }
        }
    }

    fun revealCurrentSolution(afterPreviewed: () -> Unit = {}) {
        if (interactionBusy) return
        if (currentResponse?.solutionRevealed != true) {
            val previewKey = observedTask.toPlanSolutionPreviewKey() ?: return
            planSolutionPreviewKeys = planSolutionPreviewKeys + previewKey
        }
        interactionError = null
        afterPreviewed()
    }
    LaunchedEffect(
        question.sessionId,
        currentCycle,
        currentHistory,
        currentInput.priorConversationMemory,
        currentInput.priorCycleStudentMessages,
        nextTurnExists,
        executablePlanProvider?.providerConfigurationVersion,
    ) {
        if (
            currentHistory.isNotEmpty() &&
            currentHistory.size < TutorPlanInput.MAX_TURNS &&
            !nextTurnExists
        ) {
            executeTurn(
                currentInput.cycleOrdinal,
                currentInput.priorConversationMemory,
                currentInput.priorCycleStudentMessages,
                currentHistory,
            )
        }
    }
    fun retryCurrentPlan() {
        if (executablePlanProvider == null) {
            onOpenModelSettings()
        } else {
            executeTurn(
                currentInput.cycleOrdinal,
                currentInput.priorConversationMemory,
                currentInput.priorCycleStudentMessages,
                currentInput.priorTurns,
            )
        }
    }

    val interactionCommands = remember(question.sessionId) {
        TutorInteractionCommands(
            scope = scope,
            sink = TutorInteractionSink(
                currentPlanOutput = { currentPlanOutput },
                currentInput = { currentInput },
                question = { question },
                clock = clock,
                interactionBusy = { interactionBusy },
                setInteractionBusy = { interactionBusy = it },
                setInteractionError = { interactionError = it },
                hasExecutableProvider = { executablePlanProvider != null },
                openModelSettings = onOpenModelSettings,
                currentCycleResponses = { currentCycleResponses },
                tutorResponses = { tutorResponses },
                tutorRespondTasks = { tutorRespondTasks },
                answerExposureKeys = { answerExposureKeys },
                currentCycle = { currentCycle },
                executeTurn = { cycle, memory, messages, turns ->
                    executeTurn(cycle, memory, messages, turns)
                },
                interactions = interactions,
            ),
        )
    }

    fun submitCurrentChoice(choiceId: String) {
        interactionCommands.submitChoice(choiceId)
    }

    fun continueCurrentTurn(requestedMove: TutorMoveType) {
        interactionCommands.continueTurn(requestedMove)
    }

    fun restartCurrentCycle() {
        interactionCommands.restartCycle()
    }

    val conversationListState = rememberLazyListState()
    fun solutionBottomModifier(stableId: String): Modifier = Modifier
        .testTag("tutor_solution_bottom_$stableId")
        .onGloballyPositioned { coordinates ->
            solutionExposureTracker.updateSolutionBottomBounds(
                stableId = stableId,
                bounds = coordinates.boundsInWindow(clipBounds = false),
            )
        }
    val tailId = timeline.lastOrNull()?.stableId
    val autoScrollVersion = timeline.map { timelineItem ->
        when (timelineItem) {
            is TutorConversationTimelineItem.Plan -> listOf(
                timelineItem.stableId,
                timelineItem.task.stateVersion,
                timelineItem.task.status,
            )
            is TutorConversationTimelineItem.ChoiceFeedback -> listOf(
                timelineItem.stableId,
                timelineItem.response.updatedAtEpochMillis,
                timelineItem.response.requestedMove,
                timelineItem.response.solutionRevealed,
            )
            is TutorConversationTimelineItem.Reply -> listOf(
                timelineItem.stableId,
                timelineItem.task.stateVersion,
                timelineItem.task.status,
            )
        }
    }
    val composerContent: (@Composable () -> Unit)? = if (
        respondSupported && currentPlanOutput != null && respondAgentAuthorized
    ) {
        {
            TutorChatComposer(
                value = chatDraft,
                enabled = !chatSending && !interactionBusy,
                sending = chatSending,
                onValueChange = {
                    chatDraft = it
                    chatStartError = null
                },
                onSend = { submitTutorResponse(chatDraft) },
                onOpenAttachMenu = if (sessionImageEnabled) {
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
                            testTagPrefix = "session",
                        )
                    }
                } else {
                    null
                },
                attachmentCount = pendingImages.size,
            )
            chatStartError?.let { message ->
                Text(
                    text = message.message,
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag("tutor_chat_start_error"),
                )
            }
            if (attachMenuOpen) {
                MessageAttachmentDialog(
                    onDismiss = { attachMenuOpen = false },
                    onLaunchCamera = {
                        attachMenuOpen = false
                        launchSessionCamera()
                    },
                    onLaunchGallery = {
                        attachMenuOpen = false
                        sessionGalleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    testTagPrefix = "session",
                )
            }
        }
    } else {
        null
    }

    TutorConversationFrame(
        header = headerContent,
        autoScrollVersion = listOf(
            autoScrollVersion,
            respondAgentAuthorized,
            chatStartError,
            liveTurn,
        ),
        forceFollowToken = locallyStartedRespondRequestId,
        blockAutoFollowToken = solutionExposureTracker.blockAutoFollowToken,
        modifier = modifier,
        listState = conversationListState,
        listViewportModifier = Modifier.onGloballyPositioned { coordinates ->
            solutionExposureTracker.updateViewportBounds(
                coordinates.boundsInWindow(clipBounds = false),
            )
        },
        // 同一套在途区（思考卡 / 逐 token 回答 / 工具进度），与大厅共用一条实时流。
        liveTurn = liveTurn.takeIf { recoverableRespondTask != null },
        liveStatusText = recoverableRespondTask?.tutorLiveStatusText(),
        liveAnswerTestTag = "tutor_chat_reply_streaming",
        composer = composerContent,
    ) {
        item("tutor_question_context") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = leadingContent,
            )
        }
        visualOverflowMessage?.let { message ->
            item("tutor_visual_work_limit") {
                TutorPrompt(
                    text = message,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .testTag("tutor_visual_work_limit"),
                )
            }
        }
        items(timeline, key = TutorConversationTimelineItem::stableId) { timelineItem ->
            val isTail = timelineItem.stableId == tailId
            when (timelineItem) {
                is TutorConversationTimelineItem.Plan -> {
                    val taskInput = timelineItem.task.request.input as TutorPlanInput
                    val response = conversationProjection.responsesByTurn[
                        TutorTurnKey(taskInput.cycleOrdinal, taskInput.turnOrdinal)
                    ]
                    val isCurrentTurn = taskInput.cycleOrdinal == currentInput.cycleOrdinal &&
                        taskInput.turnOrdinal == currentInput.turnOrdinal
                    val executionMatches = currentProvider?.let(
                        timelineItem.task::matchesTutorProvider,
                    ) == true
                    val planOutput = timelineItem.task.output as? TutorPlanOutput
                    val planVisualAnchor = planOutput?.let { output ->
                        TutorVisualTurnAnchor(
                            surface = TutorVisualTurnSurface.PLAN,
                            cycleOrdinal = output.cycleOrdinal,
                            turnOrdinal = output.turnOrdinal,
                        )
                    }
                    val resolvedVisualScene = planVisualAnchor?.let(resolvedVisualScenes::get)
                    val planVisualNotice = planVisualAnchor?.let(visualItemNotices::get)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TutorTaskContent(
                        task = timelineItem.task,
                        resolvedVisualScene = resolvedVisualScene,
                        response = response,
                        solutionRevealPreviewed = timelineItem.task.toPlanSolutionPreviewKey()
                            ?.let { it in planSolutionPreviewKeys } == true,
                        awaitingContinuation = false,
                        interactionEnabled = isTail && isCurrentTurn,
                        executionMatchesCurrentProvider = executionMatches,
                        splitChoiceFeedback = true,
                        interactionBusy = interactionBusy,
                        interactionError = interactionError.takeIf { isTail && isCurrentTurn },
                        onRetry = ::retryCurrentPlan,
                        onSubmitChoice = ::submitCurrentChoice,
                        onRequestHint = if (
                            respondSupported && respondAgentAuthorized && !chatSending
                        ) {
                            {
                                executeTutorResponse(
                                    message = "我不确定，请给我一点提示",
                                )
                            }
                        } else {
                            null
                        },
                        onContinue = ::continueCurrentTurn,
                        onRevealSolution = { revealCurrentSolution() },
                        onRestartCycle = ::restartCurrentCycle,
                        onOpenModelSettings = onOpenModelSettings,
                        onOpenVisualOriginal = onOpenVisualOriginal,
                        onReportVisualIncorrect = ::reportVisualIncorrect,
                        solutionBottomModifier = solutionBottomModifier(timelineItem.stableId),
                    )
                    planVisualNotice?.let { notice ->
                        TutorPrompt(
                            text = notice.studentMessage(),
                            modifier = Modifier.testTag("tutor_visual_item_error"),
                        )
                    }
                    }
                }

                is TutorConversationTimelineItem.ChoiceFeedback -> {
                    val response = timelineItem.response
                    val output = timelineItem.planTask?.output as? TutorPlanOutput
                    val isCurrentTurn = response.cycleOrdinal == currentInput.cycleOrdinal &&
                        response.turnOrdinal == currentInput.turnOrdinal
                    if (output != null) {
                        TutorChoiceFeedbackContent(
                            output = output,
                            response = response,
                            solutionRevealPreviewed = timelineItem.planTask
                                .toPlanSolutionPreviewKey()
                                ?.let { it in planSolutionPreviewKeys } == true,
                            interactionEnabled = isTail && isCurrentTurn,
                            interactionBusy = interactionBusy,
                            interactionError = interactionError.takeIf { isTail && isCurrentTurn },
                            onContinue = ::continueCurrentTurn,
                            onRevealSolution = { revealCurrentSolution() },
                            onRestartCycle = ::restartCurrentCycle,
                            solutionBottomModifier = solutionBottomModifier(timelineItem.stableId),
                        )
                    } else {
                        TutorStoredChoiceFeedback(response)
                    }
                }

                is TutorConversationTimelineItem.Reply -> {
                    val executionMatches = currentProvider?.let(
                        timelineItem.task::matchesTutorProvider,
                    ) == true
                    val taskAllowsInteraction = timelineItem.task.status ==
                        ModelTaskStatus.SUCCEEDED || timelineItem.task.canRetryTutorResponse()
                    val opensLocalSettings =
                        timelineItem.task.failure?.code?.requiresModelSettings() == true
                    val recoveryEnabled = isTail && executionMatches &&
                        !chatSending && !interactionBusy &&
                        (opensLocalSettings || respondAgentAuthorized)
                    val replyVisualAnchor = (timelineItem.task.request.input as? TutorRespondInput)?.let { input ->
                        TutorVisualTurnAnchor(
                            surface = TutorVisualTurnSurface.FOLLOW_UP,
                            cycleOrdinal = input.cycleOrdinal,
                            turnOrdinal = input.turnOrdinal,
                            responseOrdinal = input.responseOrdinal,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TutorChatExchange(
                        task = timelineItem.task,
                        resolvedVisualScene = replyVisualAnchor?.let(resolvedVisualScenes::get),
                        awaitingContinuation = !respondAgentAuthorized &&
                            timelineItem.task.status.isTutorExecutionPending(),
                        interactionEnabled = isTail && taskAllowsInteraction &&
                            executionMatches && respondAgentAuthorized &&
                            !chatSending && !interactionBusy,
                        recoveryEnabled = recoveryEnabled,
                        executionMatchesCurrentProvider = executionMatches,
                        onRetry = { retryTutorResponse(timelineItem.task) },
                        onOpenModelSettings = onOpenModelSettings,
                        onOpenVisualOriginal = onOpenVisualOriginal,
                        attachedImageResolver = attachedImageResolver,
                        studentImageIntake = imageIntake,
                        onReportVisualIncorrect = ::reportVisualIncorrect,
                        onMove = { move ->
                            executeTutorResponse(
                                message = move.label,
                                requestedMove = move.type,
                            )
                        },
                        onRevealSolution = { move ->
                            revealCurrentSolution {
                                executeTutorResponse(
                                    message = move.label,
                                    requestedMove = TutorMoveType.REVEAL_SOLUTION,
                                )
                            }
                        },
                        localIntentContent = { input, output ->
                            TutorLocalIntentPanel(
                                output = output,
                                studentMessage = input.studentMessage,
                                catalogEntries = catalogEntries,
                                profile = profile,
                                onRequestSave = onRequestSave,
                                onRequestEnd = onRequestEnd,
                                onOpenMistakeNotebook = onOpenMistakeNotebook,
                                onOpenProfile = onOpenProfile,
                            )
                        },
                        assistantBottomModifier = solutionBottomModifier(timelineItem.stableId),
                    )
                    replyVisualAnchor?.let(visualItemNotices::get)?.let { notice ->
                        TutorPrompt(
                            text = notice.studentMessage(),
                            modifier = Modifier.testTag("tutor_visual_item_error"),
                        )
                    }
                    }
                }
            }
        }
        if (composerContent == null && chatStartError != null) {
            item("tutor_chat_start_error") {
                Text(
                    text = requireNotNull(chatStartError).message,
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("tutor_chat_start_error"),
                )
            }
        }
        item("tutor_session_footer") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = trailingContent,
            )
        }
    }
}
