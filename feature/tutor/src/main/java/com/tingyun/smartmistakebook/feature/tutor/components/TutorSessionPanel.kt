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
    onLongTermWritesBlocked: () -> Unit = {},
    onRequestSave: () -> Unit = {},
    onRequestEnd: () -> Unit = {},
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenVisualOriginal: () -> Unit = {},
    attachedImageResolver: (suspend (AttachedImage) -> String?)? = null,
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
    ) {
        respondCommands.execute(
            message = message,
            requestedMove = requestedMove,
            clearDraftOnPersist = clearDraftOnPersist,
        )
    }

    fun retryTutorResponse(task: ModelTaskSnapshot) {
        respondCommands.retry(task)
    }


    val recoverableRespondTask = latestRespondTasks.lastOrNull { task ->
        currentProvider?.let(task::matchesTutorProvider) == true &&
            task.status.isTutorExecutionPending()
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
                onSend = {
                    executeTutorResponse(
                        message = chatDraft,
                        clearDraftOnPersist = true,
                    )
                },
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
        }
    } else {
        null
    }

    TutorConversationFrame(
        header = headerContent,
        autoScrollVersion = listOf(autoScrollVersion, respondAgentAuthorized, chatStartError),
        forceFollowToken = locallyStartedRespondRequestId,
        blockAutoFollowToken = solutionExposureTracker.blockAutoFollowToken,
        modifier = modifier,
        listState = conversationListState,
        listViewportModifier = Modifier.onGloballyPositioned { coordinates ->
            solutionExposureTracker.updateViewportBounds(
                coordinates.boundsInWindow(clipBounds = false),
            )
        },
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
