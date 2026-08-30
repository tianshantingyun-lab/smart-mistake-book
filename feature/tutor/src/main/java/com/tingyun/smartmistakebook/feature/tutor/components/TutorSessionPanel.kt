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
import com.tingyun.smartmistakebook.core.model.ActionType
import com.tingyun.smartmistakebook.core.model.AppFailure
import com.tingyun.smartmistakebook.core.model.AppFailureCode
import com.tingyun.smartmistakebook.core.model.Retryability
import com.tingyun.smartmistakebook.core.model.appFailure
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
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
internal fun TutorModelPanel(
    question: TutorQuestionContext,
    profile: StudyProfileOverview,
    modelTasks: ModelTaskRepository,
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = {
        emptyList()
    },
    interactions: TutorInteractionRepository,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    onLongTermWritesBlocked: () -> Unit = {},
    onRequestSave: () -> Unit = {},
    onRequestEnd: () -> Unit = {},
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenVisualOriginal: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
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
    var planRecoveryRequestInFlight by remember(question.sessionId) {
        mutableStateOf<String?>(null)
    }
    var pendingEgressState by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        stateSaver = pendingTutorEgressStateSaver,
    ) { mutableStateOf(PendingTutorEgressState()) }
    val responseActionAwaitingAuthorization =
        pendingEgressState.action.awaitsResponseAuthorization()
    var consumedAutoStartAuthorizationId by remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
    ) { mutableStateOf<String?>(null) }
    fun consumeAutoStartAuthorization(authorizationId: String) {
        if (consumedAutoStartAuthorizationId == authorizationId) return
        consumedAutoStartAuthorizationId = authorizationId
        onAutoStartAuthorizationConsumed(authorizationId)
    }

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
    val authorizationNow = clock()
    // The lease state must live for the whole session: keying it on the
    // provider fields re-created the MutableState when the provider loaded
    // (null -> configured), while planCommands (remembered on sessionId)
    // kept the stale first-composition delegate — grants landed in the new
    // state, reads saw the old one, and no externally-executed plan could
    // ever start (KD-1, docs/known-defects.md).
    val externalEgressLeaseState = remember(question.sessionId) {
        mutableStateOf<TutorCompositionEgressLease?>(null)
    }
    var externalEgressLease by externalEgressLeaseState
    LaunchedEffect(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        TUTOR_PROMPT_POLICY_VERSION,
        TUTOR_RESPOND_PROMPT_POLICY_VERSION,
        TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION,
        TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION,
    ) {
        // Provider identity or prompt policy changed: any previously granted
        // lease no longer matches the execution target, so drop it (the
        // pre-fix remember-keys did this implicitly by discarding the state).
        externalEgressLease = null
    }
    var forceResponseDisclosure by remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        currentProvider?.providerConfigurationVersion,
    ) { mutableStateOf(false) }
    fun grantExternalEgressLease(
        providerForExecution: ProviderCapabilitySnapshot,
        approvedAtEpochMillis: Long,
        taskKinds: Set<ModelTaskKind> = setOf(
            ModelTaskKind.TUTOR_PLAN,
            ModelTaskKind.TUTOR_RESPOND,
            ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ModelTaskKind.TUTOR_VISUAL_REVIEW,
        ),
    ) {
        if (providerForExecution.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            externalEgressLease = TutorCompositionEgressLease.grant(
                question = question,
                provider = providerForExecution,
                approvedAtEpochMillis = approvedAtEpochMillis,
                taskKinds = taskKinds,
            )
        }
    }
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
    val matchingAutoStartAuthorization = autoStartAuthorization
        ?.takeUnless { it.authorizationId == consumedAutoStartAuthorizationId }
        ?.takeIf { authorization ->
            executablePlanProvider?.let { providerForExecution ->
                authorization.matches(
                    sessionId = question.sessionId,
                    questionDocumentId = question.questionDocument.document.id,
                    revisionNumber = question.revisionNumber,
                    provider = providerForExecution,
                    promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                    nowEpochMillis = authorizationNow,
                )
            } == true
        }
    val planLeaseApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_PLAN,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val planFreshApprovalTask = currentProvider
        ?.takeIf { candidate ->
            candidate.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                candidate.supports(ModelTaskKind.TUTOR_PLAN)
        }
        ?.let { candidate ->
            observedTask?.takeIf { task ->
                task.requiresFreshTutorApproval(candidate) ||
                    (planLeaseApprovedAt == null &&
                        (task.status.isTutorExecutionPending() ||
                            task.status == ModelTaskStatus.RETRYABLE_FAILURE)) ||
                    (planLeaseApprovedAt == null &&
                        task.status == ModelTaskStatus.SUCCEEDED &&
                        task.output !is TutorPlanOutput)
            }
        }

    val planCommands = remember(question.sessionId) {
        TutorPlanCommands(
            scope = scope,
            sink = TutorPlanSink(
                awaitingResponseAuthorization = {
                    pendingEgressState.action.awaitsResponseAuthorization()
                },
                // Must read the backing state, not the composition-scoped
                // `executablePlanProvider` val: this lambda is captured once
                // by remember and would otherwise see the provider as it was
                // during the FIRST composition (null), silently killing every
                // auto-started turn (KD-1, docs/known-defects.md).
                executableProvider = {
                    provider?.takeIf { candidate ->
                        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                            candidate.supports(ModelTaskKind.TUTOR_PLAN)
                    }
                },
                question = { question },
                profile = { profile },
                clock = clock,
                planTasks = { tutorTasks },
                lease = { externalEgressLeaseState.value },
                pendingAction = { pendingEgressState.action },
                setPendingAction = { action ->
                    pendingEgressState = PendingTutorEgressState(action)
                },
                clearLease = { externalEgressLease = null },
                modelTasks = modelTasks,
            ),
        )
    }

    fun executeTurn(
        cycleOrdinal: Int,
        priorConversationMemory: TutorConversationMemory?,
        priorCycleStudentMessages: List<String>,
        priorTurns: List<TutorTurnHistoryEntry>,
        oneShotAutoStartAuthorization: TutorAutoStartAuthorization? = null,
    ) {
        planCommands.executeTurn(
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
            oneShotAutoStartAuthorization = oneShotAutoStartAuthorization,
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
        observedTask?.request?.requestId,
        executablePlanProvider?.providerId,
        executablePlanProvider?.modelId,
        executablePlanProvider?.providerConfigurationVersion,
        autoStartAuthorization?.authorizationId,
    ) {
        val authorization = autoStartAuthorization
            ?.takeUnless { it.authorizationId == consumedAutoStartAuthorizationId }
        if (observedTask != null) {
            authorization?.let { consumeAutoStartAuthorization(it.authorizationId) }
            return@LaunchedEffect
        }
        val providerForExecution = executablePlanProvider ?: run {
            if (currentProvider != null) {
                authorization?.let { consumeAutoStartAuthorization(it.authorizationId) }
            }
            return@LaunchedEffect
        }
        when (providerForExecution.executionLocation) {
            ModelExecutionLocation.LOCAL_NO_EGRESS -> {
                executeTurn(1, null, emptyList(), emptyList())
                authorization?.let { consumeAutoStartAuthorization(it.authorizationId) }
            }
            ModelExecutionLocation.EXTERNAL_PROVIDER -> if (authorization != null) {
                val authorizationMatches = authorization.matches(
                    sessionId = question.sessionId,
                    questionDocumentId = question.questionDocument.document.id,
                    revisionNumber = question.revisionNumber,
                    provider = providerForExecution,
                    promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                    nowEpochMillis = clock(),
                )
                if (!authorizationMatches) {
                    consumeAutoStartAuthorization(authorization.authorizationId)
                    return@LaunchedEffect
                }
                grantExternalEgressLease(
                    providerForExecution = providerForExecution,
                    approvedAtEpochMillis = authorization.approvedAtEpochMillis,
                    taskKinds = setOf(
                        ModelTaskKind.TUTOR_PLAN,
                        ModelTaskKind.TUTOR_VISUAL_GENERATE,
                        ModelTaskKind.TUTOR_VISUAL_REVIEW,
                    ),
                )
                executeTurn(
                    cycleOrdinal = 1,
                    priorConversationMemory = null,
                    priorCycleStudentMessages = emptyList(),
                    priorTurns = emptyList(),
                    oneShotAutoStartAuthorization = authorization,
                )
                consumeAutoStartAuthorization(authorization.authorizationId)
            }
            ModelExecutionLocation.UNAVAILABLE -> Unit
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

                    executablePlanProvider.executionLocation ==
                        ModelExecutionLocation.LOCAL_NO_EGRESS ||
                        planLeaseApprovedAt != null || matchingAutoStartAuthorization != null ->
                        TutorModelStatusCard(
                            title = "正在准备这道题",
                            detail = "正在整理讲解，请稍候。",
                        )

                    else -> TutorDisclosureCard(
                        provider = executablePlanProvider,
                        onApprove = {
                            grantExternalEgressLease(executablePlanProvider, clock())
                            executeTurn(1, null, emptyList(), emptyList())
                        },
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
    val responseFreshApprovalTask = currentProvider
        ?.takeIf { candidate ->
            candidate.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                candidate.supports(ModelTaskKind.TUTOR_RESPOND)
        }
        ?.let { candidate ->
            latestRespondTasks.lastOrNull()?.takeIf { task ->
                task.requiresFreshTutorApproval(candidate)
            }
        }
    val responseNeedsFreshAuthorization = responseFreshApprovalTask != null
    val responseLeaseApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val responseDisclosureRequired = currentProvider?.executionLocation ==
        ModelExecutionLocation.EXTERNAL_PROVIDER &&
        (forceResponseDisclosure || responseNeedsFreshAuthorization ||
            responseLeaseApprovedAt == null)
    val activeConversationApprovalAt = when (currentProvider?.executionLocation) {
        ModelExecutionLocation.EXTERNAL_PROVIDER ->
            responseLeaseApprovedAt.takeUnless { responseDisclosureRequired }
        ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
        ModelExecutionLocation.UNAVAILABLE,
        null,
        -> null
    }
    val respondAuthorized = respondSupported && activeConversationApprovalAt != null
    val chatSending = chatSubmitPending || latestRespondTasks.any { task ->
        currentProvider?.let(task::matchesTutorProvider) == true &&
            task.status.isTutorExecutionPending()
    }
    val visualWorkSeeds = remember(tutorTasks, tutorRespondTasks) {
        tutorVisualWorkSeeds(
            planTasks = tutorTasks,
            respondTasks = tutorRespondTasks,
        )
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
    val visualGenerateApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
    }
    val visualReviewApprovedAt = currentProvider?.let { candidate ->
        when (candidate.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = candidate,
                taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                nowEpochMillis = authorizationNow,
            )
            ModelExecutionLocation.LOCAL_NO_EGRESS -> authorizationNow
            ModelExecutionLocation.UNAVAILABLE -> null
        }
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
                generateApprovedAt = { visualGenerateApprovedAt },
                reviewApprovedAt = { visualReviewApprovedAt },
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
        visualGenerateApprovedAt,
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
        visualReviewApprovedAt,
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
                pendingAction = { pendingEgressState.action },
                setPendingAction = { action ->
                    pendingEgressState = PendingTutorEgressState(action)
                },
                setChatDraft = { chatDraft = it },
                currentPlanOutput = { currentPlanOutput },
                currentResponse = { currentResponse },
                currentInput = { currentInput },
                observedTask = { observedTask },
                tutorRespondTasks = { tutorRespondTasks },
                answerExposureKeys = { answerExposureKeys },
                chatSending = { chatSending },
                lease = { externalEgressLease },
                setForceResponseDisclosure = { forceResponseDisclosure = it },
                clearLease = { externalEgressLease = null },
                modelTasks = modelTasks,
            ),
        )
    }

    fun collectTutorRespondRequest(
        request: ModelTaskRequest,
        clearDraftOnPersist: Boolean,
        allowExternalEnvelopeForLocalRecovery: Boolean = false,
        clearPendingActionOnPersist: PendingTutorEgressAction? = null,
        isRetry: Boolean = false,
    ) {
        respondCommands.collect(
            request = request,
            clearDraftOnPersist = clearDraftOnPersist,
            allowExternalEnvelopeForLocalRecovery = allowExternalEnvelopeForLocalRecovery,
            clearPendingActionOnPersist = clearPendingActionOnPersist,
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
        respondAuthorized,
        responseFreshApprovalTask?.request?.requestId,
    ) {
        if (respondAuthorized && responseFreshApprovalTask == null) {
            recoverableRespondTask
                ?.takeUnless { it.request.requestId == locallyStartedRespondRequestId }
                ?.let { task -> modelTasks.execute(task.request).collect() }
        }
    }
    val pendingLocalRetryTask = (pendingEgressState.action as? PendingTutorEgressAction.RetryResponse)
        ?.let { pending ->
            latestRespondTasks.firstOrNull { it.request.requestId == pending.requestId }
        }
    LaunchedEffect(
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        currentProvider?.executionLocation,
        currentPlanOutput,
        pendingEgressState.action,
        pendingLocalRetryTask?.request?.requestId,
    ) {
        if (
            currentProvider?.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS ||
            !respondSupported || currentPlanOutput == null
        ) {
            return@LaunchedEffect
        }
        when (val pendingAction = pendingEgressState.action) {
            is PendingTutorEgressAction.NewResponse -> executeTutorResponse(
                message = pendingAction.message,
                requestedMove = pendingAction.requestedMove,
                clearDraftOnPersist = pendingAction.clearDraftOnPersist,
            )
            is PendingTutorEgressAction.RetryResponse ->
                pendingLocalRetryTask?.let(::retryTutorResponse)
            is PendingTutorEgressAction.Plan,
            null,
            -> Unit
        }
    }

    fun revealCurrentSolution(afterPreviewed: () -> Unit = {}) {
        if (interactionBusy || responseActionAwaitingAuthorization) return
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
        externalEgressLease?.planApprovedAtEpochMillis,
        responseActionAwaitingAuthorization,
    ) {
        if (
            currentHistory.isNotEmpty() &&
            currentHistory.size < TutorPlanInput.MAX_TURNS &&
            !nextTurnExists &&
            !responseActionAwaitingAuthorization
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
                awaitingResponseAuthorization = { responseActionAwaitingAuthorization },
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
        respondSupported && currentPlanOutput != null && respondAuthorized &&
        !responseActionAwaitingAuthorization
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
        autoScrollVersion = listOf(autoScrollVersion, respondAuthorized, chatStartError),
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
                        awaitingContinuation = planFreshApprovalTask?.request?.requestId ==
                            timelineItem.task.request.requestId,
                        interactionEnabled = isTail && isCurrentTurn &&
                            planFreshApprovalTask == null &&
                            !responseActionAwaitingAuthorization,
                        executionMatchesCurrentProvider = executionMatches,
                        splitChoiceFeedback = true,
                        interactionBusy = interactionBusy,
                        interactionError = interactionError.takeIf { isTail && isCurrentTurn },
                        onRetry = ::retryCurrentPlan,
                        onSubmitChoice = ::submitCurrentChoice,
                        onRequestHint = if (
                            respondSupported && respondAuthorized && !chatSending &&
                            !responseActionAwaitingAuthorization
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
                            interactionEnabled = isTail && isCurrentTurn &&
                                !responseActionAwaitingAuthorization,
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
                        (opensLocalSettings ||
                            (responseFreshApprovalTask == null && respondAuthorized))
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
                        awaitingContinuation = !respondAuthorized &&
                            timelineItem.task.status.isTutorExecutionPending(),
                        interactionEnabled = isTail && taskAllowsInteraction &&
                            executionMatches && respondAuthorized &&
                            !chatSending && !interactionBusy &&
                            !responseActionAwaitingAuthorization,
                        recoveryEnabled = recoveryEnabled &&
                            !responseActionAwaitingAuthorization,
                        executionMatchesCurrentProvider = executionMatches,
                        onRetry = { retryTutorResponse(timelineItem.task) },
                        onOpenModelSettings = onOpenModelSettings,
                        onOpenVisualOriginal = onOpenVisualOriginal,
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
        val pendingPlanAction = pendingEgressState.action as? PendingTutorEgressAction.Plan
        if (
            (planFreshApprovalTask != null || pendingPlanAction != null) &&
            executablePlanProvider?.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER
        ) {
            item("tutor_plan_recovery_disclosure") {
                TutorDisclosureCard(
                    provider = requireNotNull(executablePlanProvider),
                    title = "继续讲这道题",
                    actionText = "继续讲题",
                    actionContentDescription = "继续讲解当前题",
                    onApprove = {
                        if (planFreshApprovalTask != null && planRecoveryRequestInFlight != null) {
                            return@TutorDisclosureCard
                        }
                        val approvedAt = maxOf(
                            clock(),
                            (planFreshApprovalTask?.updatedAtEpochMillis ?: 0L) + 1,
                        )
                        val providerForRecovery = requireNotNull(executablePlanProvider)
                        grantExternalEgressLease(
                            providerForRecovery,
                            approvedAt,
                        )
                        if (planFreshApprovalTask != null) {
                            val canReuseExactRequest =
                                !planFreshApprovalTask.requiresFreshTutorApproval(
                                    providerForRecovery,
                                ) &&
                                    planFreshApprovalTask.coversCurrentTutorDisclosure(
                                        providerForRecovery,
                                        ModelTaskKind.TUTOR_PLAN,
                                    ) &&
                                    planFreshApprovalTask.request.egressManifest
                                        ?.isModelEgressApprovalFresh(approvedAt) == true
                            val request = if (canReuseExactRequest) {
                                planFreshApprovalTask.request
                            } else {
                                rebuildTutorRequestAfterApproval(
                                    failedTask = planFreshApprovalTask,
                                    provider = providerForRecovery,
                                    approvedAtEpochMillis = approvedAt,
                                )
                            }
                            planRecoveryRequestInFlight = request.requestId
                            scope.launch {
                                try {
                                    modelTasks.execute(request).collect()
                                } finally {
                                    planRecoveryRequestInFlight = null
                                }
                            }
                        } else if (pendingPlanAction != null) {
                            executeTurn(
                                cycleOrdinal = pendingPlanAction.cycleOrdinal,
                                priorConversationMemory =
                                pendingPlanAction.priorConversationMemory,
                                priorCycleStudentMessages =
                                pendingPlanAction.priorCycleStudentMessages,
                                priorTurns = pendingPlanAction.priorTurns,
                            )
                        }
                    },
                )
            }
        }
        if (
            respondSupported && currentPlanOutput != null && !respondAuthorized &&
            planFreshApprovalTask == null
        ) {
            item("tutor_respond_disclosure") {
                TutorRespondDisclosureCard(
                    provider = requireNotNull(currentProvider),
                    onApprove = {
                        val pendingResponseAction = pendingEgressState.action
                        val pendingRetryTask =
                            (pendingResponseAction as? PendingTutorEgressAction.RetryResponse)
                                ?.let { pending ->
                                    latestRespondTasks.firstOrNull {
                                        it.request.requestId == pending.requestId
                                    }
                                }
                        if (
                            pendingResponseAction is PendingTutorEgressAction.RetryResponse &&
                            pendingRetryTask == null
                        ) {
                            return@TutorRespondDisclosureCard
                        }
                        val approvedAt = clock()
                        grantExternalEgressLease(
                            requireNotNull(currentProvider),
                            approvedAt,
                            taskKinds = setOf(
                                ModelTaskKind.TUTOR_PLAN,
                                ModelTaskKind.TUTOR_RESPOND,
                                ModelTaskKind.TUTOR_VISUAL_GENERATE,
                                ModelTaskKind.TUTOR_VISUAL_REVIEW,
                            ),
                        )
                        forceResponseDisclosure = false
                        if (pendingResponseAction is PendingTutorEgressAction.NewResponse) {
                            executeTutorResponse(
                                message = pendingResponseAction.message,
                                requestedMove = pendingResponseAction.requestedMove,
                                clearDraftOnPersist =
                                pendingResponseAction.clearDraftOnPersist,
                            )
                            return@TutorRespondDisclosureCard
                        }
                        val taskToRecover = when (pendingResponseAction) {
                            is PendingTutorEgressAction.RetryResponse -> pendingRetryTask
                            else -> responseFreshApprovalTask ?: recoverableRespondTask ?: 
                                latestRespondTasks.lastOrNull(ModelTaskSnapshot::canRetryTutorResponse)
                        }
                        taskToRecover?.let { failedTask ->
                            val recoveryApprovedAt = maxOf(
                                approvedAt,
                                failedTask.updatedAtEpochMillis + 1,
                            )
                            val providerForRecovery = requireNotNull(currentProvider)
                            val canReuseExactRequest =
                                !failedTask.requiresFreshTutorApproval(providerForRecovery) &&
                                    failedTask.coversCurrentTutorDisclosure(
                                        providerForRecovery,
                                        ModelTaskKind.TUTOR_RESPOND,
                                    ) &&
                                    failedTask.request.egressManifest
                                        ?.isModelEgressApprovalFresh(recoveryApprovedAt) == true
                            collectTutorRespondRequest(
                                request = if (canReuseExactRequest) {
                                    failedTask.request
                                } else {
                                    rebuildTutorRequestAfterApproval(
                                        failedTask = failedTask,
                                        provider = providerForRecovery,
                                        approvedAtEpochMillis = recoveryApprovedAt,
                                    )
                                },
                                clearDraftOnPersist = false,
                                clearPendingActionOnPersist =
                                    pendingResponseAction as? PendingTutorEgressAction.RetryResponse,
                            )
                        }
                    },
                )
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
