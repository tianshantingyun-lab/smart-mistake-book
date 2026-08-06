package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContext
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
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
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.JadeActive
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TutorModelPanel(
    question: TutorQuestionContext,
    profile: StudyProfileOverview?,
    modelTasks: ScopedModelTaskPort,
    masteryContextRepository: TutorMasteryContextRepository? = null,
    visualSourceAssetsReader: suspend () -> List<TutorVisualSourceAssetScope> = {
        emptyList()
    },
    visualOriginalAvailable: Boolean = false,
    interactions: TutorInteractionRepository,
    capturedSession: ConfirmedTutorSession? = null,
    learningMemory: TutorLearningMemoryRepository? = null,
    cleanupScope: CoroutineScope? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    allowLongTermLearningWrites: Boolean = true,
    learningWritePermissionVersion: Long = 0L,
    onLongTermWritesBlocked: () -> Unit = {},
    onRequestSave: () -> Unit = {},
    onRequestEnd: () -> Unit = {},
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = onOpenMistakeNotebook,
    onOpenVisualOriginal: () -> Unit = {},
    onOpenModelSettings: () -> Unit,
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    conversationEnabled: Boolean = true,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
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
    val questionLifecycleIdentity = remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument,
    ) {
        question.toCapturedTutorQuestionLifecycleIdentity()
    }
    var providerAuthority by remember(questionLifecycleIdentity) {
        mutableStateOf(TutorProviderAuthorityState())
    }
    val provider = providerAuthority.provider
    val providerLoadFailed = providerAuthority.loadFailed
    val scope = rememberCoroutineScope()
    val masteryContextRequest = remember(
        question.subject,
        question.questionKnowledgeNodes,
        question.fallbackKnowledgeNodes,
    ) {
        question.masteryContextRequestOrNull()
    }
    var masteryContextSnapshot by remember(
        questionLifecycleIdentity,
        question.subject,
        masteryContextRequest,
    ) {
        mutableStateOf(TutorMasteryContextReadSnapshot())
    }
    val masteryContextReadCoordinator = remember(
        scope,
        questionLifecycleIdentity,
        question.subject,
        masteryContextRepository,
        masteryContextRequest,
    ) {
        TutorMasteryContextReadCoordinator(scope) { snapshot ->
            masteryContextSnapshot = snapshot
        }
    }
    DisposableEffect(masteryContextReadCoordinator) {
        onDispose(masteryContextReadCoordinator::close)
    }
    val masteryRecoveryReader = remember(
        questionLifecycleIdentity,
        question.subject,
        masteryContextRepository,
        masteryContextRequest,
    ) {
        TutorMasteryRecoveryReader(question.toTutorMasteryRecoveryReadKey())
    }
    val applicationContext = LocalContext.current.applicationContext
    val localRecoveryPresentationStore = remember(applicationContext) {
        AndroidTutorLocalRecoveryPresentationStore(applicationContext)
    }
    val localRecoveryPresentationScopeKey = remember(questionLifecycleIdentity) {
        localRecoveryPresentationScopeKey(
            sessionId = questionLifecycleIdentity.sessionId,
            revisionNumber = questionLifecycleIdentity.revisionNumber,
            questionDocumentFingerprint = questionLifecycleIdentity.questionDocumentFingerprint,
        )
    }
    var localRecoveryPresentationLookupKey by rememberSaveable(
        questionLifecycleIdentity,
    ) {
        mutableStateOf<String?>(null)
    }
    var localRecoveryPresentationCredentials by remember(questionLifecycleIdentity) {
        mutableStateOf<List<TutorLocalRecoveryPresentationCredential>>(emptyList())
    }
    LaunchedEffect(
        localRecoveryPresentationStore,
        localRecoveryPresentationScopeKey,
        localRecoveryPresentationLookupKey,
    ) {
        localRecoveryPresentationCredentials = runCatching {
            localRecoveryPresentationStore.read(localRecoveryPresentationScopeKey)
        }.getOrDefault(emptyList())
    }
    val recoveryAuthorityCoordinator = remember(
        questionLifecycleIdentity,
    ) {
        TutorRecoveryAuthorityCoordinator()
    }
    DisposableEffect(masteryRecoveryReader, recoveryAuthorityCoordinator) {
        onDispose {
            masteryRecoveryReader.close()
            recoveryAuthorityCoordinator.close()
        }
    }
    LaunchedEffect(
        masteryContextRepository,
        masteryContextRequest,
        masteryContextReadCoordinator,
    ) {
        masteryContextReadCoordinator.refresh(
            repository = masteryContextRepository,
            request = masteryContextRequest,
        )
    }
    val masteryContextReady =
        masteryContextRepository == null ||
            masteryContextRequest == null ||
            (
                masteryContextSnapshot.repository === masteryContextRepository &&
                masteryContextSnapshot.request == masteryContextRequest &&
                    !masteryContextSnapshot.loading
                )
    val masteryContext = masteryContextSnapshot.context.takeIf {
        masteryContextRepository != null &&
            masteryContextRequest != null &&
            masteryContextReady &&
            masteryContextSnapshot.repository === masteryContextRepository &&
            masteryContextSnapshot.request == masteryContextRequest
    } ?: TutorMasteryContext.EMPTY
    val activeStreamOwner = remember(
        questionLifecycleIdentity,
        modelTasks,
        scope,
    ) {
        TutorActiveStreamOwner(
            scope = scope,
            initialMode = explanationMode,
            cancelDurableRequest = modelTasks::cancel,
        )
    }
    DisposableEffect(activeStreamOwner) {
        onDispose(activeStreamOwner::close)
    }
    val activeStreamState by activeStreamOwner.state.collectAsState()
    val activeMessage = activeStreamState.active
    val latestActiveMessage by rememberUpdatedState(activeMessage)
    val lifecycleOwner = LocalLifecycleOwner.current
    val persistedTasks by remember(question.sessionId, modelTasks) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_PLAN)
    }.collectAsState(initial = emptyList())
    val persistedRespondTasks by remember(question.sessionId, modelTasks) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_RESPOND)
    }.collectAsState(initial = emptyList())
    val persistedVisualGenerationTasks by remember(question.sessionId, modelTasks) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_GENERATE)
    }.collectAsState(initial = emptyList())
    val persistedVisualReviewTasks by remember(question.sessionId, modelTasks) {
        modelTasks.observeBySubject(question.sessionId, ModelTaskKind.TUTOR_VISUAL_REVIEW)
    }.collectAsState(initial = emptyList())
    val visualWorkIdentity = remember(
        questionLifecycleIdentity,
    ) {
        CapturedTutorVisualWorkIdentity(
            sessionId = questionLifecycleIdentity.sessionId,
            revisionNumber = questionLifecycleIdentity.revisionNumber,
            questionDocumentFingerprint =
                questionLifecycleIdentity.questionDocumentFingerprint,
        )
    }
    val latestVisualWorkIdentity = rememberUpdatedState(visualWorkIdentity)
    var visualSourceAssets by remember(visualWorkIdentity) {
        mutableStateOf<List<TutorVisualSourceAssetScope>>(emptyList())
    }
    var visualSourceLoadFinished by remember(visualWorkIdentity) {
        mutableStateOf(false)
    }
    var visualSourceLoadFailed by remember(visualWorkIdentity) {
        mutableStateOf(false)
    }
    var visualExecutionFailures by remember(visualWorkIdentity) {
        mutableStateOf(emptySet<TutorVisualExecutionKey>())
    }
    LaunchedEffect(visualWorkIdentity) {
        val loadIdentity = visualWorkIdentity
        visualSourceLoadFinished = false
        try {
            val sourceAssets = visualSourceAssetsReader()
                .sortedBy(TutorVisualSourceAssetScope::pageIndex)
            if (latestVisualWorkIdentity.value != loadIdentity) return@LaunchedEffect
            visualSourceAssets = sourceAssets
            visualSourceLoadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (latestVisualWorkIdentity.value != loadIdentity) return@LaunchedEffect
            visualSourceAssets = emptyList()
            visualSourceLoadFailed = true
        } finally {
            if (latestVisualWorkIdentity.value == loadIdentity) {
                visualSourceLoadFinished = true
            }
        }
    }
    LaunchedEffect(
        visualWorkIdentity,
        visualSourceAssets.map { source -> source.assetId to source.sha256 },
    ) {
        visualExecutionFailures = emptySet()
    }
    var localRecoveryAdmissionVersion by remember(
        questionLifecycleIdentity,
    ) {
        mutableLongStateOf(0L)
    }
    val allPersistedTutorTasks = persistedTasks + persistedRespondTasks
    val visiblePersistedPlanTasks = remember(
        persistedTasks,
        allPersistedTutorTasks,
        localRecoveryPresentationCredentials,
        localRecoveryAdmissionVersion,
        question,
        visualWorkIdentity.questionDocumentFingerprint,
        explanationMode,
        explanationModeVersion,
        learningWritePermissionVersion,
        providerAuthority,
        activeStreamState,
    ) {
        persistedTasks.filter { task ->
            task.hasCurrentLocalRecoveryAdmission(
                allTasks = allPersistedTutorTasks,
                question = question,
                questionDocumentFingerprint = visualWorkIdentity.questionDocumentFingerprint,
                presentationCredentials = localRecoveryPresentationCredentials,
            )
        }
    }
    val unsupersededPersistedRespondTasks = remember(
        persistedRespondTasks,
        activeStreamState.supersededRequestIds,
    ) {
        persistedRespondTasks.filterNot { task ->
            task.request.requestId in activeStreamState.supersededRequestIds
        }
    }
    val visiblePersistedRespondTasks = remember(
        unsupersededPersistedRespondTasks,
        allPersistedTutorTasks,
        localRecoveryPresentationCredentials,
        localRecoveryAdmissionVersion,
        question,
        visualWorkIdentity.questionDocumentFingerprint,
        explanationMode,
        explanationModeVersion,
        learningWritePermissionVersion,
        providerAuthority,
        activeStreamState,
    ) {
        unsupersededPersistedRespondTasks.filter { task ->
            task.hasCurrentLocalRecoveryAdmission(
                allTasks = allPersistedTutorTasks,
                question = question,
                questionDocumentFingerprint = visualWorkIdentity.questionDocumentFingerprint,
                presentationCredentials = localRecoveryPresentationCredentials,
            )
        }
    }
    val modelBlocksLongTermWrites = visiblePersistedRespondTasks.blocksTutorLongTermWrites()
    val learningWritesAllowed =
        allowLongTermLearningWrites && !modelBlocksLongTermWrites
    LaunchedEffect(modelBlocksLongTermWrites) {
        if (modelBlocksLongTermWrites) onLongTermWritesBlocked()
    }
    val persistedResponses by remember(question.sessionId, interactions) {
        interactions.observe(question.sessionId)
    }.collectAsState(initial = emptyList())
    val persistedVisualTargetEvidence by remember(question.sessionId, interactions) {
        interactions.observeVisualTargetEvidence(question.sessionId)
    }.collectAsState(initial = emptyList())
    var interactionBusy by remember(questionLifecycleIdentity) { mutableStateOf(false) }
    var interactionError by remember(questionLifecycleIdentity) { mutableStateOf<String?>(null) }
    var pendingEvidenceJob by remember(questionLifecycleIdentity) { mutableStateOf<Job?>(null) }
    var chatDraft by rememberSaveable(
        questionLifecycleIdentity,
    ) { mutableStateOf("") }
    var chatStartError by rememberSaveable(questionLifecycleIdentity) {
        mutableStateOf<String?>(null)
    }
    var reportedVisualSceneIds by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visualWorkIdentity.questionDocumentFingerprint,
    ) { mutableStateOf(emptyList<String>()) }
    var planRecoveryRequestInFlight by remember(questionLifecycleIdentity) {
        mutableStateOf<String?>(null)
    }
    var localPlanAutoResumeKey by remember(questionLifecycleIdentity) {
        mutableStateOf<String?>(null)
    }
    var pendingEgressState by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visualWorkIdentity.questionDocumentFingerprint,
        stateSaver = pendingTutorEgressStateSaver,
    ) { mutableStateOf(PendingTutorEgressState()) }
    val pendingVisualRetry =
        pendingEgressState.action as? PendingTutorEgressAction.RetryVisual
    val latestPendingEgressForCapabilityRefresh = rememberUpdatedState(pendingEgressState)
    var draftToClearOnDurableStart by rememberSaveable(
        questionLifecycleIdentity,
    ) { mutableStateOf<String?>(null) }
    var actionToClearOnDurableStartState by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visualWorkIdentity.questionDocumentFingerprint,
        stateSaver = pendingTutorEgressStateSaver,
    ) { mutableStateOf(PendingTutorEgressState()) }
    var durableStartCleanupRequestId by rememberSaveable(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visualWorkIdentity.questionDocumentFingerprint,
    ) { mutableStateOf<String?>(null) }
    val responseActionAwaitingAuthorization =
        pendingEgressState.action.awaitsResponseAuthorization()
    var consumedAutoStartAuthorizationId by remember(
        questionLifecycleIdentity,
    ) { mutableStateOf<String?>(null) }

    fun consumeAutoStartAuthorization(authorizationId: String) {
        if (consumedAutoStartAuthorizationId == authorizationId) return
        consumedAutoStartAuthorizationId = authorizationId
        onAutoStartAuthorizationConsumed(authorizationId)
    }

    val providerAuthorityRefreshCoordinator = remember(
        questionLifecycleIdentity,
        modelTasks,
    ) {
        TutorProviderAuthorityRefreshCoordinator(
            loadCapabilities = modelTasks::capabilities,
            currentAuthority = { providerAuthority },
            updateAuthority = { updatedAuthority ->
                if (updatedAuthority != providerAuthority) {
                    recoveryAuthorityCoordinator.revoke()
                    providerAuthority = updatedAuthority
                }
            },
            currentPendingEgress = { latestPendingEgressForCapabilityRefresh.value },
            updatePendingEgress = { pendingEgressState = it },
        )
    }

    LaunchedEffect(questionLifecycleIdentity, providerAuthorityRefreshCoordinator) {
        providerAuthorityRefreshCoordinator.refresh()
    }
    DisposableEffect(
        lifecycleOwner,
        questionLifecycleIdentity,
        modelTasks,
        providerAuthorityRefreshCoordinator,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    scope.launch {
                        providerAuthorityRefreshCoordinator.refresh()
                    }
                }
                Lifecycle.Event.ON_STOP -> recoveryAuthorityCoordinator.revoke()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentProvider = provider
    LaunchedEffect(
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
    ) {
        visualExecutionFailures = emptySet()
    }
    val authorizationNow = clock()
    var externalEgressLease by remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visualWorkIdentity.questionDocumentFingerprint,
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        TUTOR_PROMPT_POLICY_VERSION,
        TUTOR_RESPOND_PROMPT_POLICY_VERSION,
        TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION,
        TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION,
    ) { mutableStateOf<TutorCompositionEgressLease?>(null) }
    var forceResponseDisclosure by remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visualWorkIdentity.questionDocumentFingerprint,
        currentProvider?.providerConfigurationVersion,
    ) { mutableStateOf(false) }
    fun grantExternalEgressLease(
        providerForExecution: ProviderCapabilitySnapshot,
        approvedAtEpochMillis: Long,
    ) {
        if (providerForExecution.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            externalEgressLease = TutorCompositionEgressLease.grant(
                question = question,
                provider = providerForExecution,
                approvedAtEpochMillis = approvedAtEpochMillis,
            )
        }
    }
    val conversationProjection = remember(
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        visiblePersistedPlanTasks,
        visiblePersistedRespondTasks,
        persistedResponses,
    ) {
        buildTutorConversationProjection(
            question = question,
            planTasks = visiblePersistedPlanTasks,
            respondTasks = visiblePersistedRespondTasks,
            responses = persistedResponses,
        )
    }
    val tutorTasks = conversationProjection.planTasks
    val tutorResponses = conversationProjection.responses
    val tutorRespondTasks = conversationProjection.respondTasks
    val timeline = conversationProjection.timeline
    // Deliberately process-only: an answer is not durably unlocked until its exact bottom is visible.
    var planSolutionPreviewKeys by remember(
        questionLifecycleIdentity,
    ) { mutableStateOf(emptySet<PlanSolutionPreviewKey>()) }
    val solutionExposureTracker = rememberTutorSolutionExposureTracker(
        question = question,
        timeline = timeline,
        responses = tutorResponses,
        previewKeys = planSolutionPreviewKeys,
        longTermWritesBlocked = !learningWritesAllowed,
        interactions = interactions,
        clock = clock,
    )
    val answerExposureKeys = solutionExposureTracker.answerExposureKeys
    val activeRespondTask = activeMessage?.identity?.requestId?.let { requestId ->
        tutorRespondTasks.firstOrNull { task -> task.request.requestId == requestId }
    }
    val activeRespondInput = activeRespondTask?.request?.input as? TutorRespondInput
    val transientPreviewExposureKey = transientDirectPreviewExposureKey(
        exposureKey = activeRespondTask?.toRespondAnswerExposureKey(),
        explanationMode = activeRespondInput?.explanationMode,
        activeMessage = activeMessage,
    )
    LaunchedEffect(solutionExposureTracker, transientPreviewExposureKey) {
        transientPreviewExposureKey?.let(solutionExposureTracker::markTransientAnswerExposure)
    }
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
                task.isRebuildableTutorRequest() &&
                    task.request.matchesTutorRuntimeAuthority(
                        explanationMode = explanationMode,
                        modeVersion = explanationModeVersion,
                        learningWritePermissionVersion = learningWritePermissionVersion,
                    ) && (
                    task.requiresFreshTutorApproval(candidate) ||
                        (planLeaseApprovedAt == null &&
                            (task.status.isTutorExecutionPending() ||
                                task.status == ModelTaskStatus.RETRYABLE_FAILURE)) ||
                        (planLeaseApprovedAt == null &&
                            task.status == ModelTaskStatus.SUCCEEDED &&
                            task.output !is TutorPlanOutput)
                    )
            }
        }

    fun executeTurn(
        cycleOrdinal: Int,
        priorConversationMemory: TutorConversationMemory?,
        priorCycleStudentMessages: List<String>,
        priorTurns: List<TutorTurnHistoryEntry>,
        oneShotAutoStartAuthorization: TutorAutoStartAuthorization? = null,
    ) {
        recoveryAuthorityCoordinator.revoke()
        if (!masteryContextReady) return
        if (pendingEgressState.action.awaitsResponseAuthorization()) return
        val providerForExecution = executablePlanProvider ?: return
        val attempt = tutorTasks.count { task ->
            val input = task.request.input as? TutorPlanInput
            input?.cycleOrdinal == cycleOrdinal &&
                input.priorConversationMemory == priorConversationMemory &&
                input.priorCycleStudentMessages == priorCycleStudentMessages &&
                input.priorTurns == priorTurns
        }
        val requestId = tutorPlanRequestId(
            question = question,
            masteryContext = masteryContext,
            provider = providerForExecution,
            attempt = attempt,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
            explanationMode = explanationMode,
            modeVersion = explanationModeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion,
        )
        val occurredAt = clock()
        val approvedAt = when (providerForExecution.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = providerForExecution,
                taskKind = ModelTaskKind.TUTOR_PLAN,
                nowEpochMillis = occurredAt,
            ) ?: oneShotAutoStartAuthorization
                ?.takeIf {
                    cycleOrdinal == 1 &&
                        priorConversationMemory == null &&
                        priorCycleStudentMessages.isEmpty() &&
                        priorTurns.isEmpty() &&
                        it.matches(
                            sessionId = question.sessionId,
                            questionDocumentId = question.questionDocument.document.id,
                            revisionNumber = question.revisionNumber,
                            provider = providerForExecution,
                            promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                            nowEpochMillis = occurredAt,
                        )
                }
                ?.approvedAtEpochMillis ?: run {
                externalEgressLease = null
                pendingEgressState = PendingTutorEgressState(
                    PendingTutorEgressAction.Plan(
                        cycleOrdinal = cycleOrdinal,
                        priorConversationMemory = priorConversationMemory,
                        priorCycleStudentMessages = priorCycleStudentMessages,
                        priorTurns = priorTurns,
                    ),
                )
                return
            }
            ModelExecutionLocation.LOCAL_NO_EGRESS,
            ModelExecutionLocation.UNAVAILABLE,
            -> occurredAt
        }
        val request = buildTutorPlanRequest(
            question = question,
            masteryContext = masteryContext,
            provider = providerForExecution,
            requestId = requestId,
            occurredAtEpochMillis = occurredAt,
            approvedAtEpochMillis = approvedAt,
            cycleOrdinal = cycleOrdinal,
            priorConversationMemory = priorConversationMemory,
            priorCycleStudentMessages = priorCycleStudentMessages,
            priorTurns = priorTurns,
            explanationMode = explanationMode,
            modeVersion = explanationModeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion,
            allowLongTermLearningWrites = learningWritesAllowed,
        )
        if (pendingEgressState.action is PendingTutorEgressAction.Plan) {
            pendingEgressState = PendingTutorEgressState()
        }
        scope.launch { modelTasks.execute(request).collect() }
    }

    val admittedLocalPlanSourceRequestId = remember(
        localRecoveryPresentationCredentials,
    ) {
        localRecoveryPresentationCredentials.lastOrNull { credential ->
            credential.sourceTaskKind == ModelTaskKind.TUTOR_PLAN
        }?.sourceRequestId
    }
    val localPlanRecoverySource = observedTask
        ?.takeUnless { task -> task.request.isLocalTutorRecoveryRequest() }
        ?: persistedTasks.firstOrNull { task ->
            task.request.requestId == admittedLocalPlanSourceRequestId
        }
    val recoverableLocalPlanTask = localPlanRecoverySource?.takeIf { task ->
        task.isRebuildableTutorRequest() &&
            task.request.matchesTutorRuntimeAuthority(
                explanationMode = explanationMode,
                modeVersion = explanationModeVersion,
                learningWritePermissionVersion = learningWritePermissionVersion,
            ) &&
            currentProvider?.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS &&
            task.request.egressManifest == null &&
            task.status.isTutorExecutionPending()
    }
    LaunchedEffect(
        observedTask?.request?.requestId,
        executablePlanProvider?.providerId,
        executablePlanProvider?.modelId,
        executablePlanProvider?.providerConfigurationVersion,
        autoStartAuthorization?.authorizationId,
        masteryContextReady,
        masteryContext,
    ) {
        if (!masteryContextReady) return@LaunchedEffect
        val authorization = autoStartAuthorization
            ?.takeUnless { it.authorizationId == consumedAutoStartAuthorizationId }
        if (observedTask != null) {
            localPlanAutoResumeKey = null
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
                val resumeKey = "local:" +
                    "${question.sessionId}:${question.revisionNumber}:" +
                    question.questionDocument.document.id
                if (recoverableLocalPlanTask != null) {
                    localPlanAutoResumeKey = resumeKey
                    return@LaunchedEffect
                }
                if (localPlanAutoResumeKey != resumeKey) {
                    localPlanAutoResumeKey = resumeKey
                    executeTurn(1, null, emptyList(), emptyList())
                }
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
    val exactVisualTargetEvidence = persistedVisualTargetEvidence.filter { evidence ->
        evidence.sessionId == question.sessionId &&
            evidence.questionDocumentId == question.questionDocument.document.id &&
            evidence.revisionNumber == question.revisionNumber
    }
    val currentHistory = tutorContiguousHistory(
        planTasks = currentCycleTasks,
        respondTasks = tutorRespondTasks,
        responses = currentCycleResponses,
        visualTargetEvidence = exactVisualTargetEvidence,
    )
    val nextTurnExists = currentCycleTasks.any { task ->
        (task.request.input as? TutorPlanInput)?.turnOrdinal == currentHistory.size + 1
    }
    val currentPlanOutput = observedTask.output as? TutorPlanOutput
    val responseConversationAuthorityFingerprint =
        capturedTutorConversationAuthorityFingerprint(
            planRequestId = observedTask.request.requestId,
            respondRequestIds = tutorRespondTasks.map { it.request.requestId },
        )
    val guidanceProblem = remember(
        question.questionDocument.document.id,
        question.revisionNumber,
    ) {
        TutorProblemScope(
            problemId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
        )
    }
    fun evidenceCancellation(requestId: String) = tutorEvidenceCancellation(
        question = question,
        requestId = requestId,
        clock = clock,
    )
    val guidanceEvents = remember(
        currentCycleTasks,
        currentCycleResponses,
        tutorRespondTasks,
        persistedVisualTargetEvidence,
    ) {
        buildTutorGuidanceEvents(
            questionDocumentId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
            currentCycleTasks = currentCycleTasks,
            currentCycleResponses = currentCycleResponses,
            tutorRespondTasks = tutorRespondTasks,
            visualTargetEvidence = persistedVisualTargetEvidence,
        )
    }
    val replayedGuidanceTransition = remember(
        guidanceProblem,
        guidanceEvents,
    ) {
        replayTutorGuidanceTransition(
            problem = guidanceProblem,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = guidanceEvents,
        )
    }
    val replayedCancellationRequestId =
        replayedGuidanceTransition.cancelEvidenceRequestId
            ?: replayedGuidanceTransition.state.pendingEvidenceRequestId
    var locallyCancelledEvidenceRequestIds by remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
        interactions,
    ) {
        mutableStateOf(emptySet<String>())
    }
    var cancellationPendingEvidenceRequestIds by remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
        interactions,
    ) {
        mutableStateOf(emptySet<String>())
    }
    var replayedPendingIsCancelled by remember(
        replayedCancellationRequestId,
        interactions,
    ) {
        mutableStateOf<Boolean?>(
            false.takeIf { replayedCancellationRequestId == null },
        )
    }
    LaunchedEffect(replayedCancellationRequestId, interactions) {
        val requestId = replayedCancellationRequestId
        replayedPendingIsCancelled = if (requestId == null) {
            false
        } else {
            runCatching {
                interactions.isEvidenceCancelled(evidenceCancellation(requestId))
            }.getOrNull()
        }
    }
    val replayedCancellationConfirmed = when {
        replayedCancellationRequestId in locallyCancelledEvidenceRequestIds -> true
        else -> replayedPendingIsCancelled
    }
    val guidanceResolution = remember(
        replayedGuidanceTransition,
        explanationMode,
        answerExposureKeys,
        replayedCancellationConfirmed,
    ) {
        resolveTutorGuidanceMode(
            replay = replayedGuidanceTransition,
            requestedMode = explanationMode,
            answerWasExposed = answerExposureKeys.isNotEmpty(),
            cancellationConfirmed = replayedCancellationConfirmed,
        )
    }
    val guidanceState = guidanceResolution.state
    val effectiveExplanationMode = guidanceState.mode
    val learningWriteAuthority = TutorLearningWriteAuthority(
        allowed = learningWritesAllowed,
        permissionVersion = learningWritePermissionVersion,
    )
    val latestLearningWriteAuthority = rememberUpdatedState(learningWriteAuthority)
    val latestExplanationModeVersion = rememberUpdatedState(explanationModeVersion)
    val choiceLearningMemoryIdentity = remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
        observedTask.request.requestId,
        currentInput.cycleOrdinal,
        currentInput.turnOrdinal,
        explanationModeVersion,
        learningWritePermissionVersion,
    ) {
        CapturedTutorChoiceRuntimeIdentity(
            sessionId = question.sessionId,
            questionDocumentId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
            planRequestId = observedTask.request.requestId,
            cycleOrdinal = currentInput.cycleOrdinal,
            turnOrdinal = currentInput.turnOrdinal,
            modeVersion = explanationModeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion,
        )
    }
    val latestChoiceLearningMemoryIdentity =
        rememberUpdatedState(choiceLearningMemoryIdentity)
    val choiceSubmissionGate = remember(
        question.sessionId,
        question.questionDocument.document.id,
        question.revisionNumber,
    ) {
        CapturedTutorChoiceSubmissionGate()
    }
    DisposableEffect(choiceSubmissionGate) {
        onDispose {
            choiceSubmissionGate.close()
        }
    }
    val pendingInteractionBlocked =
        guidanceResolution.blockPendingInteraction ||
            (
                replayedCancellationRequestId in cancellationPendingEvidenceRequestIds &&
                    replayedCancellationConfirmed != true
                )

    fun cancellationIsConfirmed(requestId: String): Boolean =
        tutorCancellationIsConfirmed(
            requestId = requestId,
            locallyCancelledEvidenceRequestIds = locallyCancelledEvidenceRequestIds,
            replayedCancellationRequestId = replayedCancellationRequestId,
            replayedPendingIsCancelled = replayedPendingIsCancelled,
        )

    fun pendingInteractionIsCurrentlyBlocked(): Boolean =
        tutorPendingInteractionIsCurrentlyBlocked(
            guidanceResolution = guidanceResolution,
            cancellationPendingEvidenceRequestIds = cancellationPendingEvidenceRequestIds,
            replayedCancellationRequestId = replayedCancellationRequestId,
            cancellationIsConfirmed = ::cancellationIsConfirmed,
        )

    fun beginEvidenceCancellation(requestId: String) {
        choiceSubmissionGate.invalidate(requestId)
        cancellationPendingEvidenceRequestIds =
            cancellationPendingEvidenceRequestIds + requestId
        pendingEvidenceJob?.cancel()
        pendingEvidenceJob = null
        interactionBusy = false
    }

    suspend fun persistEvidenceCancellation(requestId: String) {
        beginEvidenceCancellation(requestId)
        if (!cancellationIsConfirmed(requestId)) {
            interactions.cancelEvidence(evidenceCancellation(requestId))
            locallyCancelledEvidenceRequestIds =
                locallyCancelledEvidenceRequestIds + requestId
        }
        cancellationPendingEvidenceRequestIds =
            cancellationPendingEvidenceRequestIds - requestId
        if (requestId == replayedCancellationRequestId) {
            replayedPendingIsCancelled = true
        }
        interactionError = null
    }

    LaunchedEffect(
        learningWriteAuthority.allowed,
        learningWriteAuthority.permissionVersion,
        guidanceState.pendingEvidenceRequestId,
    ) {
        if (!learningWriteAuthority.allowed) {
            guidanceState.pendingEvidenceRequestId
                ?.takeUnless(::cancellationIsConfirmed)
                ?.let { requestId ->
                    runCatching { persistEvidenceCancellation(requestId) }
                }
        }
    }

    LaunchedEffect(
        guidanceResolution.cancelEvidenceRequestId,
        replayedCancellationConfirmed,
    ) {
        val requestId = guidanceResolution.cancelEvidenceRequestId
            ?: return@LaunchedEffect
        if (!cancellationIsConfirmed(requestId)) {
            runCatching {
                persistEvidenceCancellation(requestId)
            }.onFailure {
                interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
            }
        }
    }
    LaunchedEffect(
        activeStreamOwner,
        effectiveExplanationMode,
    ) {
        activeStreamOwner.updateMode(effectiveExplanationMode)
    }
    val requestExplanationModeChange: (TutorExplanationMode) -> Unit = { mode ->
        recoveryAuthorityCoordinator.revoke()
        if (mode == TutorExplanationMode.DIRECT) {
            val transition = TutorGuidancePolicy.transitionMode(
                guidanceState,
                TutorExplanationMode.DIRECT,
            )
            transition.cancelEvidenceRequestId?.let(::beginEvidenceCancellation)
            scope.launch {
                runCatching {
                    transition.cancelEvidenceRequestId?.let {
                        persistEvidenceCancellation(it)
                    }
                    onExplanationModeChange(mode)
                }.onFailure {
                    interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
                }
            }
        } else {
            onExplanationModeChange(mode)
        }
    }
    val respondSupported = currentProvider?.let { candidate ->
        candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
            candidate.supports(ModelTaskKind.TUTOR_RESPOND)
    } == true
    val latestRespondTasks = conversationProjection.latestRespondTasks
    val latestRecoveryExplanationMode = rememberUpdatedState(explanationMode)
    val latestRecoveryEffectiveMode = rememberUpdatedState(effectiveExplanationMode)
    val latestRecoveryLearningWriteAuthority =
        rememberUpdatedState(learningWriteAuthority)
    val latestRecoveryTurn = rememberUpdatedState(
        TutorTurnKey(currentInput.cycleOrdinal, currentInput.turnOrdinal),
    )
    val latestRecoveryProviderAuthority = rememberUpdatedState(providerAuthority)
    val latestRecoveryPlanTasks = rememberUpdatedState(persistedTasks)
    val latestRecoveryRespondTasks = rememberUpdatedState(persistedRespondTasks)

    fun currentRecoveryTask(
        operation: TutorRecoveryOperation,
    ): ModelTaskSnapshot? {
        val tasks = when (operation.taskKind) {
            ModelTaskKind.TUTOR_PLAN -> latestRecoveryPlanTasks.value
            ModelTaskKind.TUTOR_RESPOND -> latestRecoveryRespondTasks.value
            else -> return null
        }
        return tasks.firstOrNull { candidate ->
            candidate.request == operation.sourceRequest
        }
    }

    fun currentRecoveryOperation(
        operation: TutorRecoveryOperation,
    ): TutorRecoveryOperation? {
        val task = currentRecoveryTask(operation) ?: return null
        return TutorRecoveryOperation(
            taskKind = operation.taskKind,
            sourceRequest = task.request,
            taskStateVersion = task.stateVersion,
        )
    }

    fun currentRecoveryRuntimeAuthority(
        operation: TutorRecoveryOperation,
        requireExactOperationGeneration: Boolean = true,
    ): TutorRecoveryRuntimeAuthority? {
        val currentTask = currentRecoveryTask(operation) ?: return null
        val currentOperation = currentRecoveryOperation(operation) ?: return null
        if (requireExactOperationGeneration && currentOperation != operation) return null
        val currentAuthority = latestRecoveryProviderAuthority.value
        val currentProviderForRecovery = currentAuthority.provider
            ?.takeIf { candidate ->
                candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                    candidate.supports(operation.taskKind)
            }
            ?: return null
        val currentTurn = latestRecoveryTurn.value
        val writeAuthority = latestRecoveryLearningWriteAuthority.value
        val currentRequestedMode = latestRecoveryExplanationMode.value
        val currentEffectiveMode = latestRecoveryEffectiveMode.value
        val sourceMatchesRuntime = tutorRecoverySourceMatchesRuntime(
            input = currentTask.request.input,
            question = question,
            currentTurn = currentTurn,
            effectiveMode = currentEffectiveMode,
            modeVersion = latestExplanationModeVersion.value,
            writeAuthority = writeAuthority,
        )
        if (!sourceMatchesRuntime) return null
        val approvedAt = when (currentProviderForRecovery.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = currentProviderForRecovery,
                taskKind = operation.taskKind,
                nowEpochMillis = clock(),
            ) ?: return null
            ModelExecutionLocation.LOCAL_NO_EGRESS -> 0L
            ModelExecutionLocation.UNAVAILABLE -> return null
        }
        val conversationGeneration = activeStreamOwner.currentConversationGeneration()
        if (
            !activeStreamOwner.recoveryAuthorityIsOpen(
                expectedConversationGeneration = conversationGeneration,
                expectedMode = currentEffectiveMode,
            )
        ) {
            return null
        }
        return TutorRecoveryRuntimeAuthority(
            question = question.toTutorMasteryRecoveryReadKey(),
            questionDocumentFingerprint = visualWorkIdentity.questionDocumentFingerprint,
            explanationMode = currentRequestedMode,
            effectiveExplanationMode = currentEffectiveMode,
            modeVersion = latestExplanationModeVersion.value,
            learningWritePermissionVersion = writeAuthority.permissionVersion,
            learningWritesAllowed = writeAuthority.allowed,
            cycleOrdinal = currentTurn.cycleOrdinal,
            turnOrdinal = currentTurn.turnOrdinal,
            conversationGeneration = conversationGeneration,
            activeOwnerEpoch = activeStreamOwner.currentOwnerEpoch(),
            providerId = currentProviderForRecovery.providerId,
            modelId = currentProviderForRecovery.modelId,
            providerConfigurationVersion =
                currentProviderForRecovery.providerConfigurationVersion,
            providerAuthorityGeneration = currentAuthority.refreshGeneration,
            executionLocation = currentProviderForRecovery.executionLocation,
            taskKind = operation.taskKind,
            approvedAtEpochMillis = approvedAt,
        )
    }

    fun beginRecovery(
        task: ModelTaskSnapshot,
        taskKind: ModelTaskKind,
    ): TutorRecoveryAuthorityToken? {
        if (task.request.input.kind != taskKind) return null
        val operation = TutorRecoveryOperation(
            taskKind = taskKind,
            sourceRequest = task.request,
            taskStateVersion = task.stateVersion,
        )
        val runtime = currentRecoveryRuntimeAuthority(operation) ?: return null
        return recoveryAuthorityCoordinator.tryIssue(runtime, operation)
    }

    fun recoveryIsCurrent(token: TutorRecoveryAuthorityToken): Boolean =
        recoveryAuthorityCoordinator.isCurrent(
            token = token,
            runtime = currentRecoveryRuntimeAuthority(token.operation),
            operation = currentRecoveryOperation(token.operation),
        )

    fun recoveryResultIsCurrent(token: TutorRecoveryAuthorityToken): Boolean =
        recoveryAuthorityCoordinator.isCurrent(
            token = token,
            runtime = currentRecoveryRuntimeAuthority(token.operation),
            operation = currentRecoveryOperation(token.operation),
        )

    fun admitLocalRecovery(
        request: ModelTaskRequest,
        token: TutorRecoveryAuthorityToken,
    ): Boolean {
        val admitted = recoveryAuthorityCoordinator.admitLocalRequest(request, token)
        if (admitted) localRecoveryAdmissionVersion += 1
        return admitted
    }

    fun revokeLocalRecoveryAdmission(
        requestId: String,
        token: TutorRecoveryAuthorityToken,
    ) {
        if (recoveryAuthorityCoordinator.revokeLocalRequest(requestId, token)) {
            localRecoveryAdmissionVersion += 1
        }
    }

    suspend fun acceptLocalRecovery(
        requestId: String,
        token: TutorRecoveryAuthorityToken,
    ): Boolean {
        val operation = currentRecoveryOperation(token.operation) ?: return false
        val runtime = currentRecoveryRuntimeAuthority(token.operation) ?: return false
        val credential = recoveryAuthorityCoordinator.acceptCompletion(
            token = token,
            runtime = runtime,
            operation = operation,
            recoveredRequestId = requestId,
        )
        if (credential == null) {
            return false
        }
        val persisted = runCatching {
            localRecoveryPresentationStore.persist(
                scopeKey = localRecoveryPresentationScopeKey,
                credential = credential,
            )
        }.getOrDefault(false)
        if (!persisted) return false
        localRecoveryPresentationLookupKey = requestId
        localRecoveryPresentationCredentials = runCatching {
            localRecoveryPresentationStore.read(localRecoveryPresentationScopeKey)
        }.getOrElse { listOf(credential) }
        localRecoveryAdmissionVersion += 1
        return true
    }

    LaunchedEffect(
        recoverableLocalPlanTask?.request?.requestId,
        recoverableLocalPlanTask?.stateVersion,
        providerAuthority,
        visualWorkIdentity.questionDocumentFingerprint,
        explanationMode,
        effectiveExplanationMode,
        explanationModeVersion,
        learningWritePermissionVersion,
        learningWritesAllowed,
        currentInput.cycleOrdinal,
        currentInput.turnOrdinal,
        activeStreamState,
        masteryContext,
    ) {
        val task = recoverableLocalPlanTask ?: return@LaunchedEffect
        val providerForRecovery = currentProvider
            ?.takeIf { it.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS }
            ?: return@LaunchedEffect
        val recoveryAuthority = beginRecovery(
            task = task,
            taskKind = ModelTaskKind.TUTOR_PLAN,
        ) ?: return@LaunchedEffect
        if (!recoveryIsCurrent(recoveryAuthority)) return@LaunchedEffect
        val runtime = recoveryAuthority.runtime
        val request = rebuildLocalTutorRequestForRecoveryOrNull(
            failedTask = task,
            provider = providerForRecovery,
            question = question,
            masteryContext = masteryContext,
            explanationMode = runtime.effectiveExplanationMode,
            modeVersion = runtime.modeVersion,
            learningWritePermissionVersion = runtime.learningWritePermissionVersion,
            allowLongTermLearningWrites = runtime.learningWritesAllowed,
            cycleOrdinal = runtime.cycleOrdinal,
            turnOrdinal = runtime.turnOrdinal,
            recoveryAuthority = recoveryAuthority.toLocalRecoveryRequestAuthority(),
        ) ?: return@LaunchedEffect
        if (!recoveryIsCurrent(recoveryAuthority)) return@LaunchedEffect
        if (!admitLocalRecovery(request, recoveryAuthority)) return@LaunchedEffect
        if (!recoveryIsCurrent(recoveryAuthority)) {
            revokeLocalRecoveryAdmission(request.requestId, recoveryAuthority)
            return@LaunchedEffect
        }
        localPlanAutoResumeKey = "local:" +
            "${question.sessionId}:${question.revisionNumber}:" +
            question.questionDocument.document.id
        try {
            modelTasks.execute(request).collect { snapshot ->
                if (!recoveryResultIsCurrent(recoveryAuthority)) {
                    revokeLocalRecoveryAdmission(request.requestId, recoveryAuthority)
                    modelTasks.cancel(request.requestId)
                    throw CancellationException("Tutor recovery authority was revoked")
                }
                if (
                    snapshot.status == ModelTaskStatus.SUCCEEDED &&
                    snapshot.output is TutorPlanOutput
                ) {
                    acceptLocalRecovery(request.requestId, recoveryAuthority)
                }
            }
        } catch (cancelled: CancellationException) {
            revokeLocalRecoveryAdmission(request.requestId, recoveryAuthority)
            runCatching { modelTasks.cancel(request.requestId) }
            throw cancelled
        }
    }

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
    val chatSending = activeMessage?.activityVisible == true || latestRespondTasks.any { task ->
        currentProvider?.let(task::matchesTutorProvider) == true &&
            task.status.isTutorExecutionPending()
    }
    val activeDurableRespondTask = activeMessage?.identity?.requestId?.let { requestId ->
        latestRespondTasks.firstOrNull { it.request.requestId == requestId }
    }
    LaunchedEffect(
        activeMessage?.identity?.requestId,
        activeMessage?.phase,
        activeDurableRespondTask?.status,
    ) {
        if (activeDurableRespondTask?.status == ModelTaskStatus.SUCCEEDED) {
            activeStreamOwner.acknowledgeDurableSuccess(
                activeDurableRespondTask.request.requestId,
            )
        }
    }
    val visualWorkSeeds = remember(visualWorkIdentity, tutorTasks, tutorRespondTasks) {
        tutorVisualWorkSeeds(
            planTasks = tutorTasks,
            respondTasks = tutorRespondTasks,
        )
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
    val autoVisualAnchors = remember(visualWorkSeeds) {
        visualWorkSeeds.takeLast(MAX_AUTO_VISUAL_WORK_ITEMS)
            .mapTo(hashSetOf(), TutorVisualWorkSeed::anchor)
    }
    val resolvedVisualStates = remember(
        visualWorkIdentity,
        visualWorkSeeds,
        persistedVisualGenerationTasks,
        persistedVisualReviewTasks,
        visualSourceAssets,
        visualSourceLoadFinished,
        visualSourceLoadFailed,
        currentProvider?.providerId,
        currentProvider?.modelId,
        currentProvider?.providerConfigurationVersion,
        currentProvider?.executionLocation,
        providerLoadFailed,
        question.sessionId,
        question.revisionNumber,
        question.questionDocument.document.id,
        reportedVisualSceneIds,
        autoVisualAnchors,
        visualExecutionFailures,
    ) {
        visualWorkSeeds.associate { seed ->
            val providerForVisual = currentProvider
            val expectedGenerateRequestId = providerForVisual?.takeIf {
                visualSourceAssets.isNotEmpty() &&
                    it.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                    it.supports(ModelTaskKind.TUTOR_VISUAL_GENERATE)
            }?.let { provider ->
                tutorVisualGenerateRequestId(
                    question = question,
                    provider = provider,
                    sourceAssets = visualSourceAssets,
                    anchor = seed.anchor,
                    focusMarkdown = seed.request.focusMarkdown,
                    explanationMarkdown = seed.explanationMarkdown,
                )
            }
            val resolution = when {
                !visualSourceLoadFinished -> TutorVisualResolution.Preparing
                visualSourceLoadFailed || visualSourceAssets.isEmpty() ->
                    TutorVisualResolution.Fallback(
                        TutorVisualFallbackReason.SOURCE_UNAVAILABLE,
                    )
                providerForVisual == null ->
                    requireNotNull(
                        tutorVisualProviderLoadingResolution(
                            provider = null,
                            providerLoadFailed = providerLoadFailed,
                        ),
                    )
                providerForVisual.executionLocation == ModelExecutionLocation.UNAVAILABLE ||
                    !providerForVisual.supports(ModelTaskKind.TUTOR_VISUAL_GENERATE) ->
                    TutorVisualResolution.Fallback(
                        TutorVisualFallbackReason.PROVIDER_UNAVAILABLE,
                    )
                else -> {
                    resolveTutorVisual(
                        anchor = seed.anchor,
                        question = question,
                        generationTasks = persistedVisualGenerationTasks,
                        reviewTasks = persistedVisualReviewTasks,
                        expectedGenerationRequestId = requireNotNull(expectedGenerateRequestId),
                        reviewProvider = providerForVisual,
                    )
                }
            }
            val reviewCandidate = when (resolution) {
                is TutorVisualResolution.Reviewing -> resolution
                is TutorVisualResolution.Fallback -> resolution.reviewCandidate
                else -> null
            }
            val expectedReviewRequestId = if (
                providerForVisual != null &&
                reviewCandidate != null
            ) {
                tutorVisualReviewRequestId(
                    generationRequestId =
                        reviewCandidate.generationTask.request.requestId,
                    provider = providerForVisual,
                    generated = reviewCandidate.output,
                    reviewReasonCodes = reviewCandidate.reasonCodes,
                )
            } else {
                null
            }
            val executionFailure = visualExecutionFailures.failureFor(
                anchor = seed.anchor,
                generationRequestId = expectedGenerateRequestId,
                reviewRequestId = expectedReviewRequestId,
            )
            val presented = when {
                resolution is TutorVisualResolution.Ready &&
                    resolution.scene.sceneId in reportedVisualSceneIds ->
                    TutorVisualResolution.Fallback(TutorVisualFallbackReason.REPORTED)
                executionFailure != null && resolution !is TutorVisualResolution.Ready ->
                    TutorVisualResolution.Fallback(
                        reason = TutorVisualFallbackReason.NOT_STARTED,
                        canRetry = true,
                        reviewCandidate = reviewCandidate,
                        retryTaskKind = executionFailure.taskKind,
                        retrySemanticRequestId = executionFailure.semanticRequestId,
                    )
                resolution == TutorVisualResolution.Preparing &&
                    seed.anchor !in autoVisualAnchors ->
                    TutorVisualResolution.Fallback(
                        reason = TutorVisualFallbackReason.NOT_STARTED,
                        canRetry = expectedGenerateRequestId != null,
                        retryTaskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                        retrySemanticRequestId = expectedGenerateRequestId,
                    )
                else -> resolution
            }
            seed.anchor to presented
        }
    }
    fun requestVisualRetry(anchor: TutorVisualTurnAnchor) {
        val fallback = resolvedVisualStates[anchor] as? TutorVisualResolution.Fallback
            ?: return
        if (!fallback.canRetry || pendingEgressState.action != null) return
        val failedTask = fallback.failedTask
        val taskKind = fallback.retryTaskKind ?: return
        val semanticRequestId = fallback.retrySemanticRequestId ?: return
        val providerForRetry = currentProvider?.takeIf { provider ->
            provider.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                provider.supports(taskKind)
        } ?: return
        if (
            taskKind != ModelTaskKind.TUTOR_VISUAL_GENERATE &&
            taskKind != ModelTaskKind.TUTOR_VISUAL_REVIEW
        ) {
            return
        }
        pendingEgressState = PendingTutorEgressState(
            PendingTutorEgressAction.RetryVisual(
                anchor = anchor,
                taskKind = taskKind,
                failedRequestId = failedTask?.request?.requestId,
                semanticRequestId = semanticRequestId,
                providerId = providerForRetry.providerId,
                modelId = providerForRetry.modelId,
                providerConfigurationVersion =
                    providerForRetry.providerConfigurationVersion,
            ),
        )
        visualExecutionFailures = visualExecutionFailures.filterNotTo(mutableSetOf()) { failure ->
            failure.anchor == anchor &&
                failure.taskKind == taskKind &&
                failure.semanticRequestId == semanticRequestId
        }
    }

    fun openVisualReadOnly(requestId: String) {
        val browse = TutorGuidancePolicy.evaluate(
            guidanceState,
            TutorGuidanceRequest.visualBrowse(requestId, guidanceProblem),
        )
        if (browse.outcome == TutorGuidanceOutcome.READ_ONLY) onOpenVisualOriginal()
    }
    fun reportVisualIncorrect(sceneId: String) {
        if (sceneId !in reportedVisualSceneIds) {
            reportedVisualSceneIds = reportedVisualSceneIds + sceneId
            openVisualReadOnly("visual-report:$sceneId")
        }
    }

    fun clearPendingVisualRetry(retry: PendingTutorEgressAction.RetryVisual) {
        if (pendingEgressState.action == retry) {
            pendingEgressState = PendingTutorEgressState()
        }
    }

    fun rejectStalePendingVisualRetry(
        retry: PendingTutorEgressAction.RetryVisual,
        providerForRetry: ProviderCapabilitySnapshot?,
        semanticRequestId: String?,
    ): Boolean {
        val identityChanged = providerForRetry == null ||
            semanticRequestId == null ||
            !retry.matches(providerForRetry, semanticRequestId)
        if (identityChanged) {
            pendingEgressState = pendingEgressState.clearVisualRetryIfIdentityChanged(
                expectedRetry = retry,
                provider = providerForRetry,
                semanticRequestId = semanticRequestId,
            )
        }
        return identityChanged
    }

    suspend fun executeVisualRequest(
        anchor: TutorVisualTurnAnchor,
        request: ModelTaskRequest,
    ): Boolean {
        val executionIdentity = visualWorkIdentity
        val executionKey = TutorVisualExecutionKey(
            anchor = anchor,
            taskKind = request.input.kind,
            semanticRequestId = request.requestId.substringBefore(":retry:"),
            questionDocumentFingerprint = executionIdentity.questionDocumentFingerprint,
        )
        val outcome = collectTutorVisualExecution(modelTasks.execute(request))
        if (latestVisualWorkIdentity.value != executionIdentity) return false
        visualExecutionFailures = visualExecutionFailures.afterExecution(
            key = executionKey,
            outcome = outcome,
        )
        return outcome == TutorVisualExecutionOutcome.COMPLETED
    }

    val latestVisualWorkSeeds = rememberUpdatedState(visualWorkSeeds)
    val latestVisualSourceAssets = rememberUpdatedState(visualSourceAssets)
    val latestVisualProvider = rememberUpdatedState(currentProvider)
    val latestVisualGenerateApproval = rememberUpdatedState(visualGenerateApprovedAt)
    val latestVisualReviewApproval = rememberUpdatedState(visualReviewApprovedAt)
    val latestVisualGenerationTasks = rememberUpdatedState(persistedVisualGenerationTasks)
    val latestVisualReviewTasks = rememberUpdatedState(persistedVisualReviewTasks)
    val latestAutoVisualAnchors = rememberUpdatedState(autoVisualAnchors)
    val latestPendingVisualRetry = rememberUpdatedState(pendingVisualRetry)
    val latestVisualExecutionFailures = rememberUpdatedState(visualExecutionFailures)

    LaunchedEffect(
        visualWorkIdentity,
        modelTasks,
    ) {
        val anchorScheduler = TutorVisualAnchorScheduler(this)
        snapshotFlow {
            TutorVisualGenerationExecutionState(
                identity = latestVisualWorkIdentity.value,
                workSeeds = latestVisualWorkSeeds.value,
                sourceAssets = latestVisualSourceAssets.value,
                provider = latestVisualProvider.value,
                approvedAtEpochMillis = latestVisualGenerateApproval.value,
                generationTasks = latestVisualGenerationTasks.value,
                autoAnchors = latestAutoVisualAnchors.value,
                pendingRetry = latestPendingVisualRetry.value,
                executionFailures = latestVisualExecutionFailures.value,
            )
        }.collect { state ->
            val generationRetry = state.pendingRetry
                ?.takeIf { retry ->
                    retry.taskKind == ModelTaskKind.TUTOR_VISUAL_GENERATE
                }
            val providerForVisual = applyTutorVisualSchedulingBoundary(
                taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                provider = state.provider,
                sourceAssets = state.sourceAssets,
                pendingRetry = generationRetry,
                pendingEgressState = pendingEgressState,
                updatePendingEgress = { pendingEgressState = it },
                cancelScheduledWork = { anchorScheduler.cancelExcept(emptySet()) },
            )
            if (providerForVisual == null) {
                return@collect
            }
            val workSeeds = state.workSeeds.filter { seed ->
                seed.anchor in state.autoAnchors || seed.anchor == generationRetry?.anchor
            }
            val workItems = workSeeds.map { seed ->
                val semanticRequestId = tutorVisualGenerateRequestId(
                    question = question,
                    provider = providerForVisual,
                    sourceAssets = state.sourceAssets,
                    anchor = seed.anchor,
                    focusMarkdown = seed.request.focusMarkdown,
                    explanationMarkdown = seed.explanationMarkdown,
                )
                seed to TutorVisualExecutionKey(
                    anchor = seed.anchor,
                    taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
                    semanticRequestId = semanticRequestId,
                    questionDocumentFingerprint = state.identity.questionDocumentFingerprint,
                )
            }
            generationRetry?.let { retry ->
                val currentRetryKey = workItems
                    .firstOrNull { (seed, _) -> seed.anchor == retry.anchor }
                    ?.second
                if (
                    rejectStalePendingVisualRetry(
                        retry = retry,
                        providerForRetry = providerForVisual,
                        semanticRequestId = currentRetryKey?.semanticRequestId,
                    )
                ) {
                    anchorScheduler.cancelExcept(emptySet())
                    return@collect
                }
            }
            anchorScheduler.cancelExcept(
                workItems.mapTo(hashSetOf()) { (_, schedulerKey) -> schedulerKey },
            )
            workItems.forEach { (seed, schedulerKey) ->
                anchorScheduler.launch(schedulerKey) {
                    run seedExecution@ {
                            val pendingRetryForSeed = generationRetry
                                ?.takeIf { retry -> retry.anchor == seed.anchor }
                            if (
                                pendingRetryForSeed != null &&
                                rejectStalePendingVisualRetry(
                                    retry = pendingRetryForSeed,
                                    providerForRetry = providerForVisual,
                                    semanticRequestId = schedulerKey.semanticRequestId,
                                )
                            ) {
                                return@seedExecution
                            }
                            val approvedAt = when {
                                pendingRetryForSeed != null &&
                                    providerForVisual.executionLocation ==
                                    ModelExecutionLocation.EXTERNAL_PROVIDER ->
                                    pendingRetryForSeed.approvedAtFor(
                                        providerForVisual,
                                        schedulerKey.semanticRequestId,
                                    ) ?: return@seedExecution
                                else -> state.approvedAtEpochMillis ?: return@seedExecution
                            }
                            val failedTask =
                                pendingRetryForSeed?.failedRequestId?.let { failedRequestId ->
                                    state.generationTasks.firstOrNull { task ->
                                        task.request.requestId == failedRequestId
                                    }
                                }
                            val occurredAt = if (
                                providerForVisual.executionLocation ==
                                ModelExecutionLocation.EXTERNAL_PROVIDER
                            ) {
                                approvedAt
                            } else {
                                maxOf(
                                    clock(),
                                    failedTask?.updatedAtEpochMillis?.plus(1) ?: 0L,
                                )
                            }
                            val request = runCatching {
                                buildTutorVisualGenerateRequest(
                                    question = question,
                                    provider = providerForVisual,
                                    sourceAssets = state.sourceAssets,
                                    anchor = seed.anchor,
                                    focusMarkdown = seed.request.focusMarkdown,
                                    explanationMarkdown = seed.explanationMarkdown,
                                    occurredAtEpochMillis = occurredAt,
                                    approvedAtEpochMillis = approvedAt,
                                )
                            }.getOrNull() ?: return@seedExecution
                            if (
                                pendingRetryForSeed != null &&
                                !pendingRetryForSeed.matches(providerForVisual, request.requestId)
                            ) {
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            if (request.requestId != schedulerKey.semanticRequestId) {
                                pendingRetryForSeed?.let(::clearPendingVisualRetry)
                                return@seedExecution
                            }
                            if (
                                pendingRetryForSeed == null &&
                                schedulerKey in state.executionFailures
                            ) {
                                return@seedExecution
                            }
                            val existing = latestTutorVisualTask(state.generationTasks, request)
                            if (pendingRetryForSeed != null) {
                                val retryRequest = when {
                                    pendingRetryForSeed.failedRequestId == null &&
                                        existing == null -> request
                                    failedTask == null ||
                                        existing?.request?.requestId !=
                                        failedTask.request.requestId ||
                                        failedTask.request.input.kind !=
                                        ModelTaskKind.TUTOR_VISUAL_GENERATE ||
                                        !failedTask.matchesTutorProvider(providerForVisual) ||
                                        !failedTask.canRetryVisualTask() -> null
                                    else -> freshTutorVisualRetryRequest(request, failedTask)
                                }
                                if (retryRequest == null) {
                                    clearPendingVisualRetry(pendingRetryForSeed)
                                    return@seedExecution
                                }
                                executeVisualRequest(seed.anchor, retryRequest)
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            when {
                                existing == null ->
                                    executeVisualRequest(seed.anchor, request)
                                existing.status.isTutorExecutionPending() &&
                                    existing.coversCurrentTutorDisclosure(
                                        providerForVisual,
                                        ModelTaskKind.TUTOR_VISUAL_GENERATE,
                                    ) -> executeVisualRequest(seed.anchor, existing.request)
                            }
                    }
                }
            }
        }
    }

    LaunchedEffect(
        visualWorkIdentity,
        modelTasks,
    ) {
        val anchorScheduler = TutorVisualAnchorScheduler(this)
        snapshotFlow {
            TutorVisualReviewExecutionState(
                identity = latestVisualWorkIdentity.value,
                workSeeds = latestVisualWorkSeeds.value,
                sourceAssets = latestVisualSourceAssets.value,
                provider = latestVisualProvider.value,
                approvedAtEpochMillis = latestVisualReviewApproval.value,
                generationTasks = latestVisualGenerationTasks.value,
                reviewTasks = latestVisualReviewTasks.value,
                autoAnchors = latestAutoVisualAnchors.value,
                pendingRetry = latestPendingVisualRetry.value,
                executionFailures = latestVisualExecutionFailures.value,
            )
        }.collect { state ->
            val reviewRetry = state.pendingRetry
                ?.takeIf { retry ->
                    retry.taskKind == ModelTaskKind.TUTOR_VISUAL_REVIEW
                }
            val providerForReview = applyTutorVisualSchedulingBoundary(
                taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                provider = state.provider,
                sourceAssets = state.sourceAssets,
                pendingRetry = reviewRetry,
                pendingEgressState = pendingEgressState,
                updatePendingEgress = { pendingEgressState = it },
                cancelScheduledWork = { anchorScheduler.cancelExcept(emptySet()) },
            )
            if (providerForReview == null) {
                return@collect
            }
            val workSeeds = state.workSeeds.filter { seed ->
                seed.anchor in state.autoAnchors || seed.anchor == reviewRetry?.anchor
            }
            val workItems = workSeeds.mapNotNull { seed ->
                val pendingRetryForSeed = reviewRetry
                    ?.takeIf { retry -> retry.anchor == seed.anchor }
                val resolution = resolveTutorVisual(
                    question = question,
                    anchor = seed.anchor,
                    generationTasks = state.generationTasks,
                    reviewTasks = state.reviewTasks,
                    expectedGenerationRequestId = tutorVisualGenerateRequestId(
                        question = question,
                        provider = providerForReview,
                        sourceAssets = state.sourceAssets,
                        anchor = seed.anchor,
                        focusMarkdown = seed.request.focusMarkdown,
                        explanationMarkdown = seed.explanationMarkdown,
                    ),
                    reviewProvider = providerForReview,
                )
                val candidate = when (resolution) {
                    is TutorVisualResolution.Reviewing -> resolution
                    is TutorVisualResolution.Fallback -> resolution.reviewCandidate
                    else -> null
                } ?: return@mapNotNull null
                if (
                    resolution is TutorVisualResolution.Fallback &&
                    pendingRetryForSeed == null
                ) {
                    return@mapNotNull null
                }
                val semanticRequestId = tutorVisualReviewRequestId(
                    generationRequestId = candidate.generationTask.request.requestId,
                    provider = providerForReview,
                    generated = candidate.output,
                    reviewReasonCodes = candidate.reasonCodes,
                )
                TutorVisualReviewWorkItem(
                    seed = seed,
                    candidate = candidate,
                    executionKey = TutorVisualExecutionKey(
                        anchor = seed.anchor,
                        taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
                        semanticRequestId = semanticRequestId,
                        questionDocumentFingerprint = state.identity.questionDocumentFingerprint,
                    ),
                )
            }
            reviewRetry?.let { retry ->
                val currentRetryKey = workItems
                    .firstOrNull { workItem -> workItem.seed.anchor == retry.anchor }
                    ?.executionKey
                if (
                    rejectStalePendingVisualRetry(
                        retry = retry,
                        providerForRetry = providerForReview,
                        semanticRequestId = currentRetryKey?.semanticRequestId,
                    )
                ) {
                    anchorScheduler.cancelExcept(emptySet())
                    return@collect
                }
            }
            anchorScheduler.cancelExcept(
                workItems.mapTo(hashSetOf(), TutorVisualReviewWorkItem::executionKey),
            )
            workItems.forEach { workItem ->
                val seed = workItem.seed
                anchorScheduler.launch(workItem.executionKey) {
                    run seedExecution@ {
                            val pendingRetryForSeed = reviewRetry
                                ?.takeIf { retry -> retry.anchor == seed.anchor }
                            if (
                                pendingRetryForSeed != null &&
                                rejectStalePendingVisualRetry(
                                    retry = pendingRetryForSeed,
                                    providerForRetry = providerForReview,
                                    semanticRequestId =
                                        workItem.executionKey.semanticRequestId,
                                )
                            ) {
                                return@seedExecution
                            }
                            val reviewCandidate = workItem.candidate
                            val approvedAt = when {
                                pendingRetryForSeed != null &&
                                    providerForReview.executionLocation ==
                                    ModelExecutionLocation.EXTERNAL_PROVIDER ->
                                    pendingRetryForSeed.approvedAtFor(
                                        providerForReview,
                                        workItem.executionKey.semanticRequestId,
                                    ) ?: return@seedExecution
                                else -> state.approvedAtEpochMillis ?: return@seedExecution
                            }
                            val failedTask =
                                pendingRetryForSeed?.failedRequestId?.let { failedRequestId ->
                                    state.reviewTasks.firstOrNull { task ->
                                        task.request.requestId == failedRequestId
                                    }
                                }
                            val occurredAt = if (
                                providerForReview.executionLocation ==
                                ModelExecutionLocation.EXTERNAL_PROVIDER
                            ) {
                                approvedAt
                            } else {
                                maxOf(
                                    clock(),
                                    failedTask?.updatedAtEpochMillis?.plus(1) ?: 0L,
                                )
                            }
                            val request = runCatching {
                                buildTutorVisualReviewRequest(
                                    question = question,
                                    provider = providerForReview,
                                    sourceAssets = state.sourceAssets,
                                    generationRequest = reviewCandidate.generationTask.request,
                                    generated = reviewCandidate.output,
                                    reviewReasonCodes = reviewCandidate.reasonCodes,
                                    occurredAtEpochMillis = occurredAt,
                                    approvedAtEpochMillis = approvedAt,
                                )
                            }.getOrNull() ?: return@seedExecution
                            if (
                                pendingRetryForSeed != null &&
                                !pendingRetryForSeed.matches(providerForReview, request.requestId)
                            ) {
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            if (request.requestId != workItem.executionKey.semanticRequestId) {
                                pendingRetryForSeed?.let(::clearPendingVisualRetry)
                                return@seedExecution
                            }
                            if (
                                pendingRetryForSeed == null &&
                                workItem.executionKey in state.executionFailures
                            ) {
                                return@seedExecution
                            }
                            val existing = latestTutorVisualTask(state.reviewTasks, request)
                            if (pendingRetryForSeed != null) {
                                val retryRequest = when {
                                    pendingRetryForSeed.failedRequestId == null &&
                                        existing == null -> request
                                    failedTask == null ||
                                        existing?.request?.requestId !=
                                        failedTask.request.requestId ||
                                        failedTask.request.input.kind !=
                                        ModelTaskKind.TUTOR_VISUAL_REVIEW ||
                                        !failedTask.matchesTutorProvider(providerForReview) ||
                                        !failedTask.canRetryVisualTask() -> null
                                    else -> freshTutorVisualRetryRequest(request, failedTask)
                                }
                                if (retryRequest == null) {
                                    clearPendingVisualRetry(pendingRetryForSeed)
                                    return@seedExecution
                                }
                                executeVisualRequest(seed.anchor, retryRequest)
                                clearPendingVisualRetry(pendingRetryForSeed)
                                return@seedExecution
                            }
                            when {
                                existing == null ->
                                    executeVisualRequest(seed.anchor, request)
                                existing.status.isTutorExecutionPending() &&
                                    existing.coversCurrentTutorDisclosure(
                                        providerForReview,
                                        ModelTaskKind.TUTOR_VISUAL_REVIEW,
                                    ) -> executeVisualRequest(seed.anchor, existing.request)
                            }
                    }
                }
            }
        }
    }

    fun startTutorRespondStream(
        studentMessage: String,
        startsNewTurn: Boolean,
        clearDraftOnPersist: Boolean,
        allowExternalEnvelopeForLocalRecovery: Boolean = false,
        clearPendingActionOnPersist: PendingTutorEgressAction? = null,
        retryConsumed: Boolean = false,
        executionAuthorized: () -> Boolean = { true },
        resultAuthorized: () -> Boolean = executionAuthorized,
        onAuthorizedCompletion: suspend (String) -> Boolean = { true },
        prepare: suspend () -> ModelTaskRequest,
    ): Boolean {
        if (!executionAuthorized()) return false
        val providerForExecution = currentProvider ?: return false
        if (
            providerForExecution.executionLocation == ModelExecutionLocation.UNAVAILABLE ||
            !providerForExecution.supports(ModelTaskKind.TUTOR_RESPOND)
        ) {
            return false
        }
        if (
            providerForExecution.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
            externalEgressLease?.approvedAtFor(
                question = question,
                provider = providerForExecution,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
                nowEpochMillis = clock(),
            ) == null
        ) {
            return false
        }
        if (activeMessage?.activityVisible == true) return false
        chatStartError = null
        if (clearDraftOnPersist) {
            draftToClearOnDurableStart = chatDraft
            durableStartCleanupRequestId = null
        }
        clearPendingActionOnPersist?.let { action ->
            actionToClearOnDurableStartState = PendingTutorEgressState(
                action.takeIf { pendingEgressState.action == it },
            )
            durableStartCleanupRequestId = null
        }
        activeStreamOwner.submit(
            studentMessage = studentMessage,
            startsNewTurn = startsNewTurn,
            retryConsumed = retryConsumed,
            executionAuthorized = executionAuthorized,
            resultAuthorized = resultAuthorized,
            onAuthorizedCompletion = onAuthorizedCompletion,
        ) {
            val request = prepare()
            withContext(Dispatchers.Main.immediate) {
                if (
                    draftToClearOnDurableStart != null ||
                    actionToClearOnDurableStartState.action != null
                ) {
                    durableStartCleanupRequestId = request.requestId
                }
            }
            val input = request.input as? TutorRespondInput
                ?: error("Tutor response stream requires TutorRespondInput")
            require(input.studentMessage == studentMessage) {
                "Tutor stream message must match its prepared request"
            }
            require(
                providerForExecution.executionLocation !=
                    ModelExecutionLocation.LOCAL_NO_EGRESS ||
                    request.egressManifest == null ||
                    allowExternalEnvelopeForLocalRecovery,
            ) {
                "Local Tutor recovery cannot silently reuse an external envelope"
            }
            TutorPreparedStream(request.requestId) { identity ->
                modelTasks.executeTutorStream(request, identity)
            }
        }
        return true
    }

    fun clearSubmittedStateAfterDurableStart() {
        draftToClearOnDurableStart?.let { submittedDraft ->
            if (chatDraft == submittedDraft) chatDraft = ""
        }
        actionToClearOnDurableStartState.action?.let { submittedAction ->
            if (pendingEgressState.action == submittedAction) {
                pendingEgressState = PendingTutorEgressState()
            }
        }
        draftToClearOnDurableStart = null
        actionToClearOnDurableStartState = PendingTutorEgressState()
        durableStartCleanupRequestId = null
    }

    LaunchedEffect(
        activeMessage?.identity?.requestId,
        activeMessage?.durablyStarted,
    ) {
        if (activeMessage?.durablyStarted != true) return@LaunchedEffect
        clearSubmittedStateAfterDurableStart()
    }

    val durableCleanupTask = durableStartCleanupRequestId?.let { requestId ->
        persistedRespondTasks.firstOrNull { it.request.requestId == requestId }
    }
    LaunchedEffect(
        durableStartCleanupRequestId,
        durableCleanupTask?.stateVersion,
    ) {
        if (durableCleanupTask != null) clearSubmittedStateAfterDurableStart()
    }

    fun collectTutorRespondRequest(
        request: ModelTaskRequest,
        clearDraftOnPersist: Boolean,
        allowExternalEnvelopeForLocalRecovery: Boolean = false,
        clearPendingActionOnPersist: PendingTutorEgressAction? = null,
        retryConsumed: Boolean = false,
        executionAuthorized: () -> Boolean = { true },
        resultAuthorized: () -> Boolean = executionAuthorized,
        onAuthorizedCompletion: suspend (String) -> Boolean = { true },
    ) {
        if (!executionAuthorized()) return
        val input = request.input as? TutorRespondInput ?: return
        if (
            !request.matchesTutorRuntimeAuthority(
                modeVersion = explanationModeVersion,
                learningWritePermissionVersion = learningWritePermissionVersion,
            )
        ) {
            return
        }
        startTutorRespondStream(
            studentMessage = input.studentMessage,
            startsNewTurn = false,
            clearDraftOnPersist = clearDraftOnPersist,
            allowExternalEnvelopeForLocalRecovery = allowExternalEnvelopeForLocalRecovery,
            clearPendingActionOnPersist = clearPendingActionOnPersist,
            retryConsumed = retryConsumed,
            executionAuthorized = executionAuthorized,
            resultAuthorized = resultAuthorized,
            onAuthorizedCompletion = onAuthorizedCompletion,
            prepare = { request },
        )
    }

    fun executeTutorResponse(
        response: TutorResponseMessage,
        requestedMove: TutorMoveType? = null,
        clearDraftOnPersist: Boolean = false,
    ) {
        recoveryAuthorityCoordinator.revoke()
        if (!masteryContextReady) return
        val exactMessage = response.messageMarkdown
        val selectedChoiceId = response.selectedChoiceId
        val visibleChoice = TutorVisibleChoice.resolve(timeline, response)
        if (selectedChoiceId != null && visibleChoice == null) {
            interactionError = "这项互动已经过期，请根据当前提示重新选择。"
            return
        }
        if (
            exactMessage.isBlank() ||
            chatSending ||
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked()
        ) {
            return
        }
        if (exactMessage.isTutorHintRequest()) {
            val hint = TutorGuidancePolicy.evaluate(
                guidanceState,
                TutorGuidanceRequest.hint(
                    "hint:${question.sessionId}:${currentInput.cycleOrdinal}:${currentInput.turnOrdinal}",
                    guidanceProblem,
                ),
            )
            if (hint.outcome == TutorGuidanceOutcome.DIRECT_EXPLANATION) {
                val transition = TutorGuidancePolicy.transitionMode(
                    guidanceState,
                    TutorExplanationMode.DIRECT,
                )
                val previewKey = observedTask.toPlanSolutionPreviewKey()
                transition.cancelEvidenceRequestId?.let(::beginEvidenceCancellation)
                scope.launch {
                    runCatching {
                        transition.cancelEvidenceRequestId?.let {
                            persistEvidenceCancellation(it)
                        }
                        previewKey?.let { key ->
                            planSolutionPreviewKeys = planSolutionPreviewKeys + key
                        }
                    }.onFailure {
                        interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
                    }
                }
                return
            }
        }
        val requestTransition = tutorResponseModeTransitionFor(
            state = guidanceState,
            studentMessage = exactMessage,
        )
        val requestMode = requestTransition.state.mode
        requestTransition.cancelEvidenceRequestId
            ?.takeUnless(::cancellationIsConfirmed)
            ?.let { requestId ->
                beginEvidenceCancellation(requestId)
                scope.launch {
                    runCatching {
                        continueTutorResponseAfterEvidenceCancellation(
                            cancelEvidenceRequestId = requestId,
                            cancellationConfirmed = false,
                            cancelEvidence = ::persistEvidenceCancellation,
                            continueResponse = {
                                executeTutorResponse(
                                    response = response,
                                    requestedMove = requestedMove,
                                    clearDraftOnPersist = clearDraftOnPersist,
                                )
                            },
                        )
                    }.onFailure {
                        interactionError = "旧互动暂时没有安全关闭，请重试后再继续。"
                    }
                }
                return
            }
        val pendingResponseAction = pendingEgressState.action
            as? PendingTutorEgressAction.NewResponse
        when (val pendingAction = pendingEgressState.action) {
            is PendingTutorEgressAction.Plan,
            is PendingTutorEgressAction.RetryResponse,
            is PendingTutorEgressAction.RetryVisual,
            -> return
            is PendingTutorEgressAction.NewResponse -> if (
                pendingAction.message != exactMessage ||
                pendingAction.selectedChoiceId != selectedChoiceId ||
                pendingAction.choiceSourceRequestId != response.choiceSourceRequestId ||
                pendingAction.requestedMove != requestedMove ||
                pendingAction.clearDraftOnPersist != clearDraftOnPersist ||
                pendingAction.conversationAuthorityFingerprint !=
                responseConversationAuthorityFingerprint
            ) {
                pendingEgressState = PendingTutorEgressState()
                interactionError = "互动内容已更新，请重新发送。"
                return
            }
            null -> Unit
        }
        val visiblePlan = currentPlanOutput ?: return
        val providerForExecution = currentProvider?.takeIf { candidate ->
            candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                candidate.supports(ModelTaskKind.TUTOR_RESPOND)
        } ?: return
        val responseCycleOrdinal = currentInput.cycleOrdinal
        val responseTurnOrdinal = currentInput.turnOrdinal
        val approvalNow = clock()
        val externalApprovedAt = when (providerForExecution.executionLocation) {
            ModelExecutionLocation.EXTERNAL_PROVIDER -> externalEgressLease?.approvedAtFor(
                question = question,
                provider = providerForExecution,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
                nowEpochMillis = approvalNow,
            ) ?: run {
                forceResponseDisclosure = true
                externalEgressLease = null
                pendingEgressState = PendingTutorEgressState(
                    PendingTutorEgressAction.NewResponse(
                        message = exactMessage,
                        selectedChoiceId = selectedChoiceId,
                        choiceSourceRequestId = response.choiceSourceRequestId,
                        conversationAuthorityFingerprint =
                        responseConversationAuthorityFingerprint,
                        requestedMove = requestedMove,
                        clearDraftOnPersist = clearDraftOnPersist,
                    ),
                )
                return
            }
            ModelExecutionLocation.LOCAL_NO_EGRESS -> null
            ModelExecutionLocation.UNAVAILABLE -> return
        }
        val tasksForRequest = tutorRespondTasks
        val exposureKeysForRequest = answerExposureKeys
        val currentResponseForRequest = currentResponse
        val answerWasExposed = exposureKeysForRequest.isNotEmpty()
        val startResponse = {
            startTutorRespondStream(
                studentMessage = exactMessage,
                startsNewTurn = true,
                clearDraftOnPersist = clearDraftOnPersist,
                clearPendingActionOnPersist = pendingResponseAction,
            ) {
                val lastResponseOrdinal = tasksForRequest.maxOfOrNull { task ->
                    (task.request.input as? TutorRespondInput)?.responseOrdinal ?: 0
                } ?: 0
                val responseOrdinal = lastResponseOrdinal + 1
                val priorMessages: List<TutorChatHistoryEntry> = tutorChatHistory(
                    tasksForRequest,
                    answerExposureKeys = exposureKeysForRequest,
                )
                val visibleContext = visibleTutorContextMarkdown(
                    visiblePlan,
                    currentResponseForRequest,
                    answerWasExposed = answerWasExposed,
                )
                val attempt = tasksForRequest.count { task ->
                    (task.request.input as? TutorRespondInput)?.responseOrdinal == responseOrdinal
                }
                val requestId = tutorRespondRequestId(
                    question = question,
                    masteryContext = masteryContext,
                    provider = providerForExecution,
                    responseOrdinal = responseOrdinal,
                    cycleOrdinal = responseCycleOrdinal,
                    turnOrdinal = responseTurnOrdinal,
                    studentMessage = exactMessage,
                    selectedChoiceId = visibleChoice?.id,
                    visibleTutorContextMarkdown = visibleContext,
                    priorMessages = priorMessages,
                    requestedMove = requestedMove,
                    explanationMode = requestMode,
                    modeVersion = explanationModeVersion,
                    learningWritePermissionVersion = learningWritePermissionVersion,
                    attempt = attempt,
                )
                val occurredAt = maxOf(
                    clock(),
                    tasksForRequest.maxOfOrNull { it.createdAtEpochMillis + 1 } ?: 0L,
                )
                buildTutorRespondRequest(
                    question = question,
                    masteryContext = masteryContext,
                    provider = providerForExecution,
                    requestId = requestId,
                    occurredAtEpochMillis = occurredAt,
                    approvedAtEpochMillis = externalApprovedAt ?: occurredAt,
                    responseOrdinal = responseOrdinal,
                    cycleOrdinal = responseCycleOrdinal,
                    turnOrdinal = responseTurnOrdinal,
                    studentMessage = exactMessage,
                    selectedChoice = visibleChoice,
                    visibleTutorContextMarkdown = visibleContext,
                    priorMessages = priorMessages,
                    requestedMove = requestedMove,
                    explanationMode = requestMode,
                    modeVersion = explanationModeVersion,
                    learningWritePermissionVersion = learningWritePermissionVersion,
                    allowLongTermLearningWrites = learningWritesAllowed,
                )
            }
        }
        startResponse()
    }

    fun executeTutorResponse(
        message: String,
        requestedMove: TutorMoveType? = null,
        clearDraftOnPersist: Boolean = false,
    ) = executeTutorResponse(
        response = TutorResponseMessage.freeResponse(message),
        requestedMove = requestedMove,
        clearDraftOnPersist = clearDraftOnPersist,
    )

    fun pendingTutorResponseMessage(
        pending: PendingTutorEgressAction.NewResponse,
    ): TutorResponseMessage? = tutorPendingTutorResponseMessage(timeline, pending)

    fun retryTutorResponse(task: ModelTaskSnapshot) {
        if (!task.isRebuildableTutorRequest()) return
        if (
            !task.request.matchesTutorRuntimeAuthority(
                modeVersion = explanationModeVersion,
                learningWritePermissionVersion = learningWritePermissionVersion,
            )
        ) {
            return
        }
        if (!task.canRetryTutorResponse()) return
        val exactPendingRetry = (pendingEgressState.action as? PendingTutorEgressAction.RetryResponse)
            ?.takeIf { it.requestId == task.request.requestId }
        when (val pendingAction = pendingEgressState.action) {
            null -> Unit
            is PendingTutorEgressAction.RetryResponse -> if (
                pendingAction.requestId != task.request.requestId
            ) {
                return
            }
            is PendingTutorEgressAction.Plan,
            is PendingTutorEgressAction.NewResponse,
            is PendingTutorEgressAction.RetryVisual,
            -> return
        }
        val providerForExecution = currentProvider?.takeIf { candidate ->
            candidate.executionLocation != ModelExecutionLocation.UNAVAILABLE &&
                candidate.supports(ModelTaskKind.TUTOR_RESPOND)
        } ?: return
        if (providerForExecution.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            val approvedAt = externalEgressLease?.approvedAtFor(
                question = question,
                provider = providerForExecution,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
                nowEpochMillis = clock(),
            )
            if (approvedAt == null) {
                externalEgressLease = null
                forceResponseDisclosure = true
                pendingEgressState = PendingTutorEgressState(
                    PendingTutorEgressAction.RetryResponse(task.request.requestId),
                )
                return
            }
            val recoveryAuthority = beginRecovery(
                task = task,
                taskKind = ModelTaskKind.TUTOR_RESPOND,
            ) ?: return
            scope.launch {
                val request = rebuildTutorRequestAfterFreshMasteryApprovalOrNull(
                    failedTask = task,
                    provider = providerForExecution,
                    approvedAtEpochMillis = maxOf(approvedAt, task.updatedAtEpochMillis + 1),
                    question = question,
                    masteryContextRepository = masteryContextRepository,
                    recoveryReader = masteryRecoveryReader,
                    recoveryIsAuthorized = { recoveryIsCurrent(recoveryAuthority) },
                ) ?: return@launch
                if (!recoveryIsCurrent(recoveryAuthority)) return@launch
                if (latestActiveMessage?.activityVisible == true) return@launch
                collectTutorRespondRequest(
                    request = request,
                    clearDraftOnPersist = false,
                    allowExternalEnvelopeForLocalRecovery = exactPendingRetry != null,
                    clearPendingActionOnPersist = exactPendingRetry,
                    retryConsumed = true,
                    executionAuthorized = { recoveryIsCurrent(recoveryAuthority) },
                    resultAuthorized = { recoveryResultIsCurrent(recoveryAuthority) },
                )
            }
            return
        }
        val localRecoveryAuthority = beginRecovery(
            task = task,
            taskKind = ModelTaskKind.TUTOR_RESPOND,
        ) ?: return
        if (!recoveryIsCurrent(localRecoveryAuthority)) return
        val localRecoveryRuntime = localRecoveryAuthority.runtime
        val request = when (providerForExecution.executionLocation) {
            ModelExecutionLocation.LOCAL_NO_EGRESS -> if (
                task.request.egressManifest == null && task.matchesTutorProvider(providerForExecution)
            ) {
                val nextAttempt = task.nextLocalTutorResponseRetryAttempt() ?: return
                val recoveredRequest = rebuildLocalTutorRequestForRecoveryOrNull(
                    failedTask = task,
                    provider = providerForExecution,
                    question = question,
                    masteryContext = masteryContext,
                    explanationMode = localRecoveryRuntime.effectiveExplanationMode,
                    modeVersion = localRecoveryRuntime.modeVersion,
                    learningWritePermissionVersion =
                        localRecoveryRuntime.learningWritePermissionVersion,
                    allowLongTermLearningWrites = localRecoveryRuntime.learningWritesAllowed,
                    cycleOrdinal = localRecoveryRuntime.cycleOrdinal,
                    turnOrdinal = localRecoveryRuntime.turnOrdinal,
                    recoveryAuthority =
                        localRecoveryAuthority.toLocalRecoveryRequestAuthority(),
                ) ?: return
                recoveredRequest.copy(
                    requestId = "${recoveredRequest.requestId}:retry:$nextAttempt",
                    occurredAtEpochMillis = maxOf(clock(), task.updatedAtEpochMillis + 1),
                )
            } else if (exactPendingRetry != null) {
                val currentAttempt =
                    task.request.requestId.substringAfterLast(':').toIntOrNull() ?: 0
                if (currentAttempt >= 1) return
                val recoveredRequest = rebuildLocalTutorRequestForRecoveryOrNull(
                    failedTask = task,
                    provider = providerForExecution,
                    question = question,
                    masteryContext = masteryContext,
                    explanationMode = localRecoveryRuntime.effectiveExplanationMode,
                    modeVersion = localRecoveryRuntime.modeVersion,
                    learningWritePermissionVersion =
                        localRecoveryRuntime.learningWritePermissionVersion,
                    allowLongTermLearningWrites = localRecoveryRuntime.learningWritesAllowed,
                    cycleOrdinal = localRecoveryRuntime.cycleOrdinal,
                    turnOrdinal = localRecoveryRuntime.turnOrdinal,
                    recoveryAuthority =
                        localRecoveryAuthority.toLocalRecoveryRequestAuthority(),
                ) ?: return
                recoveredRequest.copy(
                    requestId = "${recoveredRequest.requestId}:retry:1",
                    occurredAtEpochMillis = maxOf(clock(), task.updatedAtEpochMillis + 1),
                    egressManifest = null,
                )
            } else {
                return
            }
            ModelExecutionLocation.EXTERNAL_PROVIDER -> error("External retry returned before here")
            ModelExecutionLocation.UNAVAILABLE -> return
        }
        if (!recoveryIsCurrent(localRecoveryAuthority)) return
        if (activeMessage?.activityVisible == true) return
        if (!admitLocalRecovery(request, localRecoveryAuthority)) return
        if (!recoveryIsCurrent(localRecoveryAuthority)) {
            revokeLocalRecoveryAdmission(request.requestId, localRecoveryAuthority)
            return
        }
        collectTutorRespondRequest(
            request = request,
            clearDraftOnPersist = false,
            allowExternalEnvelopeForLocalRecovery = exactPendingRetry != null,
            clearPendingActionOnPersist = exactPendingRetry,
            retryConsumed = true,
            executionAuthorized = { recoveryIsCurrent(localRecoveryAuthority) },
            resultAuthorized = { recoveryResultIsCurrent(localRecoveryAuthority) },
            onAuthorizedCompletion = { requestId ->
                acceptLocalRecovery(requestId, localRecoveryAuthority)
            },
        )
    }

    val recoverableRespondTask = latestRespondTasks.lastOrNull { task ->
        task.isRebuildableTutorRequest() &&
            !task.request.isLocalTutorRecoveryRequest() &&
            task.request.matchesTutorRuntimeAuthority(
                modeVersion = explanationModeVersion,
                learningWritePermissionVersion = learningWritePermissionVersion,
            ) &&
            currentProvider?.let(task::matchesTutorProvider) == true &&
            task.isPendingTutorRespondFor(effectiveExplanationMode)
    }
    LaunchedEffect(
        recoverableRespondTask?.request?.requestId,
        respondAuthorized,
        responseFreshApprovalTask?.request?.requestId,
    ) {
        if (respondAuthorized && responseFreshApprovalTask == null) {
            recoverableRespondTask
                ?.takeUnless {
                    it.request.requestId == activeMessage?.identity?.requestId
                }
                ?.let { task ->
                    val providerForRecovery = currentProvider ?: return@let
                    val recoveryAuthority = beginRecovery(
                        task = task,
                        taskKind = ModelTaskKind.TUTOR_RESPOND,
                    ) ?: return@let
                    if (!recoveryIsCurrent(recoveryAuthority)) return@let
                    val recoveryRuntime = recoveryAuthority.runtime
                    val request = when (providerForRecovery.executionLocation) {
                        ModelExecutionLocation.EXTERNAL_PROVIDER -> {
                            val approvedAt = externalEgressLease?.approvedAtFor(
                                question = question,
                                provider = providerForRecovery,
                                taskKind = ModelTaskKind.TUTOR_RESPOND,
                                nowEpochMillis = clock(),
                            ) ?: return@let
                            rebuildTutorRequestAfterFreshMasteryApprovalOrNull(
                                failedTask = task,
                                provider = providerForRecovery,
                                approvedAtEpochMillis = maxOf(
                                    approvedAt,
                                    task.updatedAtEpochMillis + 1,
                                ),
                                question = question,
                                masteryContextRepository = masteryContextRepository,
                                recoveryReader = masteryRecoveryReader,
                                recoveryIsAuthorized = {
                                    recoveryIsCurrent(recoveryAuthority)
                                },
                            ) ?: return@let
                        }
                        ModelExecutionLocation.LOCAL_NO_EGRESS ->
                            rebuildLocalTutorRequestForRecoveryOrNull(
                                failedTask = task,
                                provider = providerForRecovery,
                                question = question,
                                masteryContext = masteryContext,
                                explanationMode = recoveryRuntime.effectiveExplanationMode,
                                modeVersion = recoveryRuntime.modeVersion,
                                learningWritePermissionVersion =
                                    recoveryRuntime.learningWritePermissionVersion,
                                allowLongTermLearningWrites = recoveryRuntime.learningWritesAllowed,
                                cycleOrdinal = recoveryRuntime.cycleOrdinal,
                                turnOrdinal = recoveryRuntime.turnOrdinal,
                                recoveryAuthority =
                                    recoveryAuthority.toLocalRecoveryRequestAuthority(),
                            ) ?: return@let
                        ModelExecutionLocation.UNAVAILABLE -> return@let
                    }
                    if (!recoveryIsCurrent(recoveryAuthority)) return@let
                    if (providerForRecovery.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS) {
                        if (!admitLocalRecovery(request, recoveryAuthority)) return@let
                    }
                    if (!recoveryIsCurrent(recoveryAuthority)) {
                        revokeLocalRecoveryAdmission(request.requestId, recoveryAuthority)
                        return@let
                    }
                    collectTutorRespondRequest(
                        request = request,
                        clearDraftOnPersist = false,
                        retryConsumed = task.status == ModelTaskStatus.RETRYABLE_FAILURE,
                        executionAuthorized = { recoveryIsCurrent(recoveryAuthority) },
                        resultAuthorized = { recoveryResultIsCurrent(recoveryAuthority) },
                        onAuthorizedCompletion = { requestId ->
                            acceptLocalRecovery(requestId, recoveryAuthority)
                        },
                    )
                }
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
            is PendingTutorEgressAction.NewResponse -> {
                val response = pendingTutorResponseMessage(pendingAction)
                if (response == null) {
                    pendingEgressState = PendingTutorEgressState()
                    interactionError = "互动内容已更新，请根据当前提示重新选择。"
                } else {
                    executeTutorResponse(
                        response = response,
                        requestedMove = pendingAction.requestedMove,
                        clearDraftOnPersist = pendingAction.clearDraftOnPersist,
                    )
                }
            }
            is PendingTutorEgressAction.RetryResponse ->
                pendingLocalRetryTask?.let(::retryTutorResponse)
            is PendingTutorEgressAction.Plan,
            is PendingTutorEgressAction.RetryVisual,
            null,
            -> Unit
        }
    }

    fun revealCurrentSolution(afterPreviewed: () -> Unit = {}) {
        if (
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        if (currentResponse?.solutionRevealed != true) {
            val previewKey = observedTask.toPlanSolutionPreviewKey() ?: return
            planSolutionPreviewKeys = planSolutionPreviewKeys + previewKey
        }
        interactionError = null
        afterPreviewed()
    }
    LaunchedEffect(
        effectiveExplanationMode,
        observedTask.request.requestId,
    ) {
        if (effectiveExplanationMode == TutorExplanationMode.DIRECT) {
            observedTask.toPlanSolutionPreviewKey()?.let { key ->
                planSolutionPreviewKeys = planSolutionPreviewKeys + key
            }
        }
    }
    LaunchedEffect(
        question.sessionId,
        currentCycle,
        currentHistory,
        currentInput.priorConversationMemory,
        currentInput.priorCycleStudentMessages,
        nextTurnExists,
        executablePlanProvider?.providerConfigurationVersion,
        externalEgressLease?.approvedAtEpochMillis,
        responseActionAwaitingAuthorization,
        explanationMode,
    ) {
        if (
            effectiveExplanationMode == TutorExplanationMode.GUIDED &&
            currentHistory.isNotEmpty() &&
            guidanceState.questionsAsked < TutorGuidancePolicy.MAX_QUESTIONS &&
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

    fun submitCurrentChoice(choiceId: String) {
        val output = observedTask.output as? TutorPlanOutput
        val item = output?.plan?.diagnosticItem
        val evaluation = item?.evaluateChoice(choiceId)
        val evidence = guidanceState.authorizeEvidence(observedTask.request.requestId)
        if (
            effectiveExplanationMode != TutorExplanationMode.GUIDED ||
            !evidence.mayWriteLearningEvidence ||
            output == null || item == null || evaluation == null || interactionBusy ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        val exactIdentity = choiceLearningMemoryIdentity
        val command = RecordTutorChoiceCommand(
            sessionId = question.sessionId,
            questionDocumentId = question.questionDocument.document.id,
            revisionNumber = question.revisionNumber,
            cycleOrdinal = currentInput.cycleOrdinal,
            turnOrdinal = currentInput.turnOrdinal,
            diagnosticStemMarkdown = item.stemMarkdown,
            selectedChoiceId = evaluation.choice.id,
            selectedChoiceMarkdown = evaluation.choice.markdown,
            selectionWasCorrect = evaluation.isCorrect,
            feedbackMarkdown = requireNotNull(evaluation.choice.feedbackMarkdown),
            occurredAtEpochMillis = clock(),
            evidenceRequestId = observedTask.request.requestId,
        )
        interactionError = null
        interactionBusy = true
        pendingEvidenceJob = scope.launch {
            try {
                check(
                    choiceSubmissionGate.allows(
                        requestId = exactIdentity.planRequestId,
                        identityStillCurrent =
                            latestChoiceLearningMemoryIdentity.value == exactIdentity,
                    ) &&
                        latestLearningWriteAuthority.value.allows(
                            requestPermissionVersion =
                                exactIdentity.learningWritePermissionVersion,
                            requestModeVersion = exactIdentity.modeVersion,
                            currentModeVersion = latestExplanationModeVersion.value,
                        ) &&
                        !cancellationIsConfirmed(exactIdentity.planRequestId),
                ) { "Tutor choice authority changed before persistence" }
                // Production interactions persist the exact choice and perform the trusted
                // owner-side learning finalization as one ordered repository operation.
                interactions.recordChoice(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                interactionError = "这个选择暂时没有保存，请重试后再继续。"
            } finally {
                interactionBusy = false
                pendingEvidenceJob = null
            }
        }
    }

    fun submitVisualTargetEvidence(
        requestId: String,
        anchor: TutorVisualTurnAnchor,
        directive: TutorInteractionDirective.VisualTarget,
        hitProof: TutorVisualHitProof,
        inlineScene: TutorVisualScene? = null,
    ) {
        val ready = inlineScene?.let { scene ->
            inlineTutorVisualResolution(scene, requestId) as? TutorVisualResolution.Ready
        } ?: (resolvedVisualStates[anchor] as? TutorVisualResolution.Ready)
            ?: return
        val readyScene = ready.scene
        val expectedPresentation = ready.hitPresentation(requestId) ?: return
        if (
            hitProof.presentation != expectedPresentation ||
            !canSubmitTutorVisualTarget(
                mode = effectiveExplanationMode,
                pendingEvidenceRequestId = guidanceState.pendingEvidenceRequestId,
                requestId = requestId,
                visualReady = true,
                expectedTargetId = directive.targetId,
                hitTargetId = hitProof.selectedTargetId,
                sceneReported = readyScene.sceneId in reportedVisualSceneIds,
            ) ||
            !guidanceState.authorizeEvidence(requestId)
                .mayWriteLearningEvidence ||
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        interactionError = null
        interactionBusy = true
        pendingEvidenceJob = scope.launch {
            try {
                interactions.recordVisualTargetEvidence(
                    RecordTutorVisualTargetEvidenceCommand(
                        sessionId = question.sessionId,
                        questionDocumentId = question.questionDocument.document.id,
                        revisionNumber = question.revisionNumber,
                        anchor = anchor,
                        modelTaskRequestId = requestId,
                        hitProof = hitProof,
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                interactionError = "这个选择暂时没有保存，请重试后再继续。"
            } finally {
                interactionBusy = false
                pendingEvidenceJob = null
            }
        }
    }

    fun submitCurrentVisualTarget(hitProof: TutorVisualHitProof) {
        val output = observedTask.output as? TutorPlanOutput ?: return
        val directive = output.plan.interactionDirective as?
            TutorInteractionDirective.VisualTarget ?: return
        submitVisualTargetEvidence(
            requestId = observedTask.request.requestId,
            anchor = TutorVisualTurnAnchor(
                surface = TutorVisualTurnSurface.PLAN,
                cycleOrdinal = output.cycleOrdinal,
                turnOrdinal = output.turnOrdinal,
            ),
            directive = directive,
            hitProof = hitProof,
            inlineScene = output.plan.visualScene,
        )
    }

    fun continueCurrentTurn(requestedMove: TutorMoveType) {
        if (
            effectiveExplanationMode != TutorExplanationMode.GUIDED ||
            interactionBusy ||
            pendingInteractionIsCurrentlyBlocked() ||
            responseActionAwaitingAuthorization
        ) {
            return
        }
        if (executablePlanProvider == null) {
            onOpenModelSettings()
            return
        }
        interactionBusy = true
        scope.launch {
            interactionError = null
            try {
                val movedResponse = interactions.recordMove(
                    RecordTutorMoveCommand(
                        sessionId = question.sessionId,
                        questionDocumentId = question.questionDocument.document.id,
                        revisionNumber = question.revisionNumber,
                        cycleOrdinal = currentInput.cycleOrdinal,
                        turnOrdinal = currentInput.turnOrdinal,
                        requestedMove = requestedMove,
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                val nextHistory = tutorContiguousHistory(
                    planTasks = currentCycleTasks,
                    respondTasks = tutorRespondTasks,
                    responses = currentCycleResponses
                        .filterNot { it.turnOrdinal == movedResponse.turnOrdinal }
                        .plus(movedResponse),
                    visualTargetEvidence = exactVisualTargetEvidence,
                )
                if (nextHistory.any { it.turnOrdinal == movedResponse.turnOrdinal }) {
                    val nextState = replayTutorGuidance(
                        problem = guidanceProblem,
                        requestedMode = explanationMode,
                        answerWasExposed = answerExposureKeys.isNotEmpty(),
                        events = if (movedResponse.hasChoicePayload) {
                            guidanceEvents + TutorGuidanceEvent.Evidence(
                                requestId = observedTask.request.requestId,
                                selectionWasCorrect =
                                    movedResponse.selectionWasCorrect == true,
                            )
                        } else {
                            guidanceEvents
                        },
                    )
                    if (nextState.mode == TutorExplanationMode.GUIDED) {
                        executeTurn(
                            currentInput.cycleOrdinal,
                            currentInput.priorConversationMemory,
                            currentInput.priorCycleStudentMessages,
                            nextHistory,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                interactionError = "下一种讲法没有启动，请再试一次。"
            } finally {
                interactionBusy = false
            }
        }
    }

    fun restartCurrentCycle() {
        if (responseActionAwaitingAuthorization) return
        if (executablePlanProvider == null) {
            onOpenModelSettings()
        } else {
            tutorResponses.toTutorConversationMemory(
                answerExposureKeys = answerExposureKeys,
                visualTargetEvidence = exactVisualTargetEvidence,
            )?.let { memory ->
                executeTurn(
                    currentCycle + 1,
                    memory,
                    priorCycleStudentMessages(tutorRespondTasks),
                    emptyList(),
                )
            }
        }
    }

    val conversationListState = rememberLazyListState()
    @Composable
    fun solutionBottomModifier(stableId: String): Modifier {
        val anchorToken = remember(stableId) { Any() }
        DisposableEffect(solutionExposureTracker, stableId, anchorToken) {
            onDispose {
                solutionExposureTracker.removeSolutionBottomBounds(stableId, anchorToken)
            }
        }
        return Modifier
            .testTag("tutor_solution_bottom_$stableId")
            .onGloballyPositioned { coordinates ->
                solutionExposureTracker.updateSolutionBottomBounds(
                    stableId = stableId,
                    token = anchorToken,
                    bounds = coordinates.boundsInWindow(clipBounds = false),
                )
            }
    }
    val activeRequestId = activeMessage?.identity?.requestId
    val activeReplyExists = tutorActiveReplyExists(timeline, activeRequestId)
    val tailId = tutorTailId(timeline, activeMessage)
    val autoScrollVersion = tutorConversationAutoScrollVersion(timeline)
    val visualInsertionToken = tutorVisualInsertionToken(resolvedVisualStates)
    val composerContent: (@Composable () -> Unit)? = if (
        respondSupported && currentPlanOutput != null && respondAuthorized &&
        !responseActionAwaitingAuthorization
    ) {
        {
            TutorChatComposer(
                value = chatDraft,
                enabled = !chatSending && !interactionBusy && !pendingInteractionBlocked,
                sending = chatSending,
                explanationMode = explanationMode,
                onExplanationModeChange = requestExplanationModeChange,
                onCameraAttachment = onCameraAttachment,
                onGalleryAttachment = onGalleryAttachment,
                onLibraryAttachment = onLibraryAttachment,
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
                    text = message,
                    color = ErrorWarm,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag("tutor_chat_start_error"),
                )
            }
        }
    } else if (currentPlanOutput != null) {
        {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TutorGuidanceModeControl(
                    mode = explanationMode,
                    onModeChange = requestExplanationModeChange,
                )
            }
        }
    } else {
        null
    }
    val pendingPlanAction = pendingEgressState.action as? PendingTutorEgressAction.Plan
    val showActiveReply = tutorShowActiveReply(activeMessage, activeReplyExists)
    val showPlanRecoveryDisclosure = tutorShowPlanRecoveryDisclosure(
        hasPlanFreshApproval = planFreshApprovalTask != null,
        hasPendingPlanAction = pendingPlanAction != null,
        executableProviderIsExternal =
            executablePlanProvider?.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER,
    )
    val showVisualRetryDisclosure = tutorShowVisualRetryDisclosure(
        hasPendingVisualRetry = pendingVisualRetry != null,
        currentProviderIsExternal =
            currentProvider?.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER,
    )
    val pendingResponseRetryIsRebuildable =
        pendingResponseRetryIsRebuildable(
            pendingAction = pendingEgressState.action,
            latestRespondTasks = latestRespondTasks,
        )
    val showRespondDisclosure = tutorShowRespondDisclosure(
        respondSupported = respondSupported,
        hasCurrentPlan = currentPlanOutput != null,
        respondAuthorized = respondAuthorized,
        hasPlanFreshApproval = planFreshApprovalTask != null,
        hasPendingVisualRetry = pendingVisualRetry != null,
        pendingResponseRetryIsRebuildable = pendingResponseRetryIsRebuildable,
    )
    val showChatStartError = tutorShowChatStartError(
        composerAvailable = composerContent != null,
        chatStartError = chatStartError,
    )
    val expectedConversationItemCount =
        tutorExpectedConversationItemCount(
            timelineSize = timeline.size,
            showActiveReply = showActiveReply,
            showPlanRecoveryDisclosure = showPlanRecoveryDisclosure,
            showVisualRetryDisclosure = showVisualRetryDisclosure,
            showRespondDisclosure = showRespondDisclosure,
            showChatStartError = showChatStartError,
        )

    TutorConversationFrame(
        header = headerContent,
        autoScrollVersion = listOf(
            autoScrollVersion,
            respondAuthorized,
            chatStartError,
            activeMessage?.renderVersion,
        ),
        expectedItemCount = expectedConversationItemCount,
        forceFollowToken = activeMessage?.turnVersion,
        blockAutoFollowToken = listOf(
            solutionExposureTracker.blockAutoFollowToken,
            visualInsertionToken,
        ),
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
        items(timeline, key = TutorConversationTimelineItem::stableId) { timelineItem ->
            val isTail = timelineItem.stableId == tailId
            when (timelineItem) {
                is TutorConversationTimelineItem.Plan -> {
                    CapturedTutorTimelinePlanItem(
                        task = timelineItem.task,
                        response = conversationProjection.responsesByTurn[
                            TutorTurnKey(
                                (timelineItem.task.request.input as TutorPlanInput).cycleOrdinal,
                                (timelineItem.task.request.input as TutorPlanInput).turnOrdinal,
                            )
                        ],
                        isTail = isTail,
                        currentCycleOrdinal = currentInput.cycleOrdinal,
                        currentTurnOrdinal = currentInput.turnOrdinal,
                        currentProvider = currentProvider,
                        resolvedVisualStates = resolvedVisualStates,
                        visualOriginalAvailable = visualOriginalAvailable,
                        planSolutionPreviewKeys = planSolutionPreviewKeys,
                        planFreshApprovalTask = planFreshApprovalTask,
                        pendingInteractionBlocked = pendingInteractionBlocked,
                        responseActionAwaitingAuthorization =
                            responseActionAwaitingAuthorization,
                        interactionBusy = interactionBusy || pendingInteractionBlocked,
                        interactionError = interactionError,
                        explanationMode = effectiveExplanationMode,
                        hintsUsed = guidanceState.hintsUsed,
                        maxHints = TutorGuidancePolicy.MAX_HINTS,
                        respondSupported = respondSupported,
                        respondAuthorized = respondAuthorized,
                        chatSending = chatSending,
                        onRetry = ::retryCurrentPlan,
                        onRequestVisualRetry = ::requestVisualRetry,
                        onVisualTargetHit = ::submitCurrentVisualTarget,
                        onSubmitChoice = ::submitCurrentChoice,
                        onDirectiveResponse = { response ->
                            executeTutorResponse(response)
                        },
                        onContinue = ::continueCurrentTurn,
                        onRevealSolution = { revealCurrentSolution() },
                        onRestartCycle = ::restartCurrentCycle,
                        onOpenModelSettings = onOpenModelSettings,
                        onOpenVisualOriginal = {
                            val browse = TutorGuidancePolicy.evaluate(
                                guidanceState,
                                TutorGuidanceRequest.visualBrowse(
                                    "visual-open:${timelineItem.task.request.requestId}",
                                    guidanceProblem,
                                ),
                            )
                            if (browse.outcome == TutorGuidanceOutcome.READ_ONLY) {
                                onOpenVisualOriginal()
                            }
                        },
                        onReportVisualIncorrect = ::reportVisualIncorrect,
                        solutionBottomModifier = solutionBottomModifier(timelineItem.stableId),
                    )
                }
                is TutorConversationTimelineItem.ChoiceFeedback -> {
                    CapturedTutorTimelineChoiceFeedbackItem(
                        planTask = timelineItem.planTask,
                        response = timelineItem.response,
                        isTail = isTail,
                        isCurrentTurn = tutorIsCurrentTurn(
                            taskCycleOrdinal = timelineItem.response.cycleOrdinal,
                            taskTurnOrdinal = timelineItem.response.turnOrdinal,
                            currentCycleOrdinal = currentInput.cycleOrdinal,
                            currentTurnOrdinal = currentInput.turnOrdinal,
                        ),
                        planSolutionPreviewKeys = planSolutionPreviewKeys,
                        explanationMode = effectiveExplanationMode,
                        pendingInteractionBlocked = pendingInteractionBlocked,
                        responseActionAwaitingAuthorization =
                            responseActionAwaitingAuthorization,
                        interactionBusy = interactionBusy,
                        interactionError = interactionError,
                        onContinue = ::continueCurrentTurn,
                        onRevealSolution = { revealCurrentSolution() },
                        onRestartCycle = ::restartCurrentCycle,
                        solutionBottomModifier = solutionBottomModifier(timelineItem.stableId),
                    )
                }
                is TutorConversationTimelineItem.Reply -> {
                    CapturedTutorTimelineReplyItem(
                        task = timelineItem.task,
                        isTail = isTail,
                        currentProvider = currentProvider,
                        activeMessage = activeMessage,
                        resolvedVisualStates = resolvedVisualStates,
                        visualOriginalAvailable = visualOriginalAvailable,
                        respondAuthorized = respondAuthorized,
                        chatSending = chatSending,
                        interactionBusy = interactionBusy,
                        pendingInteractionBlocked = pendingInteractionBlocked,
                        responseActionAwaitingAuthorization =
                            responseActionAwaitingAuthorization,
                        responseFreshApprovalTask = responseFreshApprovalTask,
                        explanationMode = effectiveExplanationMode,
                        catalogEntries = catalogEntries,
                        profile = profile,
                        onRetry = { retryTutorResponse(timelineItem.task) },
                        onRequestVisualRetry = ::requestVisualRetry,
                        onSubmitVisualTargetEvidence = {
                            requestId, anchor, directive, hitProof, inlineScene ->
                            submitVisualTargetEvidence(
                                requestId = requestId,
                                anchor = anchor,
                                directive = directive,
                                hitProof = hitProof,
                                inlineScene = inlineScene,
                            )
                        },
                        onOpenModelSettings = onOpenModelSettings,
                        onOpenVisualOriginal = {
                            openVisualReadOnly(
                                "visual-reply:${timelineItem.task.request.requestId}",
                            )
                        },
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
                        onDirectiveResponse = ::executeTutorResponse,
                        onRequestSave = onRequestSave,
                        onRequestEnd = onRequestEnd,
                        onOpenMistakeNotebook = onOpenMistakeNotebook,
                        onOpenProfile = onOpenProfile,
                        solutionBottomModifier = solutionBottomModifier(timelineItem.stableId),
                    )
                }
            }
        }
        if (showActiveReply) {
            val activeReply = checkNotNull(activeMessage)
            item("tutor_active_reply_${activeReply.turnVersion}") {
                TutorActiveChatExchange(
                    message = activeReply,
                    onRetry = { activeStreamOwner.retry() },
                )
            }
        }
        if (showPlanRecoveryDisclosure) {
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
                        grantExternalEgressLease(providerForRecovery, approvedAt)
                        if (planFreshApprovalTask != null) {
                            val exactPlanTask = planFreshApprovalTask
                            val recoveryAuthority = beginRecovery(
                                task = exactPlanTask,
                                taskKind = ModelTaskKind.TUTOR_PLAN,
                            ) ?: return@TutorDisclosureCard
                            scope.launch {
                                val request =
                                    rebuildTutorRequestAfterFreshMasteryApprovalOrNull(
                                        failedTask = exactPlanTask,
                                        provider = providerForRecovery,
                                        approvedAtEpochMillis = approvedAt,
                                        question = question,
                                        masteryContextRepository = masteryContextRepository,
                                        recoveryReader = masteryRecoveryReader,
                                        recoveryIsAuthorized = {
                                            recoveryIsCurrent(recoveryAuthority)
                                        },
                                    ) ?: return@launch
                                if (!recoveryIsCurrent(recoveryAuthority)) return@launch
                                planRecoveryRequestInFlight = request.requestId
                                try {
                                    if (!recoveryIsCurrent(recoveryAuthority)) return@launch
                                    modelTasks.execute(request).collect {
                                        if (!recoveryResultIsCurrent(recoveryAuthority)) {
                                            throw CancellationException(
                                                "Tutor plan recovery authority was revoked",
                                            )
                                        }
                                    }
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
        if (showVisualRetryDisclosure) {
            item("tutor_visual_retry_disclosure") {
                TutorDisclosureCard(
                    provider = requireNotNull(currentProvider),
                    title = "重试图解",
                    actionText = "允许并重试",
                    actionContentDescription = "允许发送当前题目图片并重试图解",
                    onApprove = {
                        val retry = pendingEgressState.action
                            as? PendingTutorEgressAction.RetryVisual
                            ?: return@TutorDisclosureCard
                        if (retry.approvedAtEpochMillis != null) {
                            return@TutorDisclosureCard
                        }
                        val providerForRetry = requireNotNull(currentProvider)
                        val fallback = resolvedVisualStates[retry.anchor]
                            as? TutorVisualResolution.Fallback
                        val failedTask = fallback?.failedTask
                        val retryIsCurrent = fallback?.canRetry == true &&
                            fallback.retrySemanticRequestId?.let { semanticRequestId ->
                                retry.matches(providerForRetry, semanticRequestId)
                            } == true &&
                            retry.matchesFailedTask(failedTask) &&
                            (failedTask == null ||
                                failedTask.matchesTutorProvider(providerForRetry)) &&
                            providerForRetry.supports(retry.taskKind)
                        if (!retryIsCurrent) {
                            clearPendingVisualRetry(retry)
                            return@TutorDisclosureCard
                        }
                        val approvedAt = maxOf(
                            clock(),
                            failedTask?.updatedAtEpochMillis?.plus(1) ?: 0L,
                        )
                        grantExternalEgressLease(providerForRetry, approvedAt)
                        if (pendingEgressState.action == retry) {
                            pendingEgressState = PendingTutorEgressState(
                                retry.copy(approvedAtEpochMillis = approvedAt),
                            )
                        }
                    },
                )
            }
        }
        if (showRespondDisclosure) {
            item("tutor_respond_disclosure") {
                TutorRespondDisclosureCard(
                    provider = requireNotNull(currentProvider),
                    onApprove = {
                        val pendingResponseAction = pendingEgressState.action
                        val pendingRetryTask =
                            (pendingResponseAction as? PendingTutorEgressAction.RetryResponse)
                                ?.let { pending ->
                                    latestRespondTasks.firstOrNull {
                                        it.request.requestId == pending.requestId &&
                                            it.isRebuildableTutorRequest() &&
                                            it.canRetryTutorResponseFor(effectiveExplanationMode)
                                    }
                                }
                        if (
                            pendingResponseAction is PendingTutorEgressAction.RetryResponse &&
                            pendingRetryTask == null
                        ) {
                            return@TutorRespondDisclosureCard
                        }
                        val approvedAt = clock()
                        grantExternalEgressLease(requireNotNull(currentProvider), approvedAt)
                        forceResponseDisclosure = false
                        if (pendingResponseAction is PendingTutorEgressAction.NewResponse) {
                            val response = pendingTutorResponseMessage(pendingResponseAction)
                            if (response == null) {
                                pendingEgressState = PendingTutorEgressState()
                                interactionError = "互动内容已更新，请根据当前提示重新选择。"
                                return@TutorRespondDisclosureCard
                            }
                            run {
                                executeTutorResponse(
                                    response = response,
                                    requestedMove = pendingResponseAction.requestedMove,
                                    clearDraftOnPersist =
                                    pendingResponseAction.clearDraftOnPersist,
                                )
                            }
                            return@TutorRespondDisclosureCard
                        }
                        val taskToRecover = when (pendingResponseAction) {
                            is PendingTutorEgressAction.RetryResponse -> pendingRetryTask
                            else -> responseFreshApprovalTask ?: recoverableRespondTask ?:
                                latestRespondTasks.lastOrNull { task ->
                                    task.isRebuildableTutorRequest() &&
                                        task.canRetryTutorResponseFor(effectiveExplanationMode)
                                }
                        }
                        taskToRecover?.let { failedTask ->
                            val recoveryApprovedAt = maxOf(
                                approvedAt,
                                failedTask.updatedAtEpochMillis + 1,
                            )
                            val providerForRecovery = requireNotNull(currentProvider)
                            val recoveryAuthority = beginRecovery(
                                task = failedTask,
                                taskKind = ModelTaskKind.TUTOR_RESPOND,
                            ) ?: return@let
                            scope.launch {
                                val request =
                                    rebuildTutorRequestAfterFreshMasteryApprovalOrNull(
                                        failedTask = failedTask,
                                        provider = providerForRecovery,
                                        approvedAtEpochMillis = recoveryApprovedAt,
                                        question = question,
                                        masteryContextRepository = masteryContextRepository,
                                        recoveryReader = masteryRecoveryReader,
                                        recoveryIsAuthorized = {
                                            recoveryIsCurrent(recoveryAuthority)
                                        },
                                    ) ?: return@launch
                                if (!recoveryIsCurrent(recoveryAuthority)) return@launch
                                collectTutorRespondRequest(
                                    request = request,
                                    clearDraftOnPersist = false,
                                    clearPendingActionOnPersist =
                                        pendingResponseAction as?
                                            PendingTutorEgressAction.RetryResponse,
                                    retryConsumed =
                                        failedTask.status == ModelTaskStatus.RETRYABLE_FAILURE,
                                    executionAuthorized = {
                                        recoveryIsCurrent(recoveryAuthority)
                                    },
                                    resultAuthorized = {
                                        recoveryResultIsCurrent(recoveryAuthority)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        if (showChatStartError) {
            item("tutor_chat_start_error") {
                Text(
                    text = requireNotNull(chatStartError),
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