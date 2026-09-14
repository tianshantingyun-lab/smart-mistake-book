package com.tingyun.smartmistakebook.feature.capture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.MAX_CAPTURE_USER_HINT_CHARS
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel


@Composable
fun CaptureScreen(
    entryOrigin: CaptureEntryOrigin,
    repository: CaptureWorkflowRepository,
    modelTasks: ModelTaskRepository,
    onOpenModelSettings: () -> Unit,
    onTutorSessionReady: (sessionId: String) -> Unit,
    onLibraryEntryReady: (String) -> Unit,
    onSplitReady: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resumeDraftId: String? = null,
    // This build may reach a model provider at all; the global "model agent" consent toggle
    // that used to gate it was removed on 2026-09-13. Whether a round actually runs is
    // decided per-provider by captureModelReady below.
    modelEgressAllowed: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val workflowViewModel: CaptureViewModel = viewModel(
        key = "capture-workflow-${resumeDraftId ?: entryOrigin.name}",
        factory = CaptureViewModelFactory(
            repository = repository,
            modelTasks = modelTasks,
            resumeDraftId = resumeDraftId,
        ),
    )
    val workflowUiState by workflowViewModel.uiState.collectAsStateWithLifecycle()
    val workspaceWriter = remember(repository) {
        CaptureWorkspaceWriter { request -> repository.saveDraftWorkspace(request) }
    }
    val initialCachePrune = remember { CompletableDeferred<Unit>() }
    val state = rememberCaptureScreenState(resumeDraftId, entryOrigin)
    val modelExecutionCoordinator = remember(modelTasks, coroutineScope) {
        CaptureModelTaskCoordinator(
            modelTasks = modelTasks,
            scope = coroutineScope,
        )
    }

    val activeEntryOrigin = runCatching {
        CaptureEntryOrigin.valueOf(state.activeEntryOriginName)
    }.getOrDefault(entryOrigin)
    val resumeLoadState = runCatching {
        CaptureResumeLoadState.valueOf(state.resumeLoadStateName)
    }.getOrDefault(CaptureResumeLoadState.SOURCE_UNAVAILABLE)
    val acquisitionPurpose = runCatching {
        CaptureAcquisitionPurpose.valueOf(state.acquisitionPurposeName)
    }.getOrDefault(CaptureAcquisitionPurpose.NEW_CAPTURE)

    val realParseOutput = (state.parseSnapshot?.output as? CaptureParseOutput)
        ?.takeIf { state.parseSnapshot?.provider?.isDemo == false }
    val structuredCandidate = realParseOutput?.capturedDocument
    val visibleStructuredDocument = state.workspaceState?.workingDocument ?: structuredCandidate
    val structuredProjection = visibleStructuredDocument?.let {
        QuestionDocumentMarkdownProjection.project(it.document).trim()
    }.orEmpty()
    val assessmentDecision = (state.assessmentSnapshot?.output as? CaptureAssessmentOutput)
        ?.assessment?.decision
    val assessmentBlocksEntry = assessmentDecision != null &&
        assessmentDecision != CaptureAssessmentDecision.PASS
    val captureModelReady = modelEgressAllowed &&
        state.providerCapabilities?.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        state.providerCapabilities?.supportsImageInput == true
    val candidateKind = if (structuredCandidate != null) {
        CaptureCandidateKind.MODEL_STRUCTURED
    } else if (state.recognitionStateName == CaptureRecognitionState.CANDIDATE_AVAILABLE.name) {
        CaptureCandidateKind.LOCAL_TRANSITIONAL
    } else {
        CaptureCandidateKind.NONE
    }
    val correctedStructuredCandidate =
        state.workspaceState?.editorMode == CaptureDraftEditorMode.STRUCTURED_DOCUMENT &&
            state.workspaceState?.userEditedBlockIds?.isNotEmpty() == true
    val finalConfirmationPending = state.workspaceState?.finalConfirmationRequest != null
    val candidateUsable = captureCandidateIsUsable(visibleStructuredDocument) && (
        structuredCandidate != null ||
            correctedStructuredCandidate ||
            finalConfirmationPending
        ) && (!assessmentBlocksEntry || finalConfirmationPending)
    // strictOffline（本构建不连接模型）：没有模型解析输出可等，草稿就绪即可
    // 用本地识别题面进入手动整理；题面校验通过就直接允许入库，不再等模型。
    val manualEntryMode = !modelEgressAllowed
    val manualWorkspaceReady = manualEntryMode &&
        state.draftId != null &&
        state.workspaceState != null
    val manualCommitReady = manualEntryMode &&
        captureCandidateIsUsable(state.workspaceState?.workingDocument)
    val entryGateOpen = candidateUsable || manualCommitReady

    val draftState = remember { CaptureDraftStateCommands(state) }

    fun retryAssessmentProcessing() {
        draftState.retryAssessment()
    }

    fun retryParseProcessing() {
        draftState.retryParse()
    }

    fun resetForNextCapture() {
        draftState.resetForNextCapture(workflowViewModel::reset)
    }

    fun applyWorkspace(restored: CaptureWorkspaceLocalSnapshot) {
        draftState.applyWorkspace(restored)
    }

    fun updateWorkspace(transform: (CaptureWorkspaceUiState) -> CaptureWorkspaceUiState) {
        draftState.updateWorkspace(transform)
    }

    fun applyDraftSummary(
        draft: CaptureDraftSummary,
        requestId: String,
        occurredAtEpochMillis: Long,
    ) {
        draftState.applyDraftSummary(draft, requestId, occurredAtEpochMillis)
    }

    val workspaceCommands = remember(workspaceWriter) {
        CaptureWorkspaceCommands(workspaceWriter, coroutineScope, state)
    }
    suspend fun saveWorkspaceNow(
        state: CaptureWorkspaceUiState,
        occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ) = workspaceCommands.saveNow(state, occurredAtEpochMillis)

    suspend fun flushWorkspaceNow(): Boolean = workspaceCommands.flushNow()

    fun afterWorkspaceFlush(action: () -> Unit) {
        workspaceCommands.afterFlush(action)
    }

    fun requestBackWithFlush() {
        workspaceCommands.requestBack(onBack)
    }

    val sourceImport = remember(workspaceCommands, activeEntryOrigin, entryGateOpen) {
        CaptureSourceImportCommands(
            scope = coroutineScope,
            state = state,
            workspaceCommands = workspaceCommands,
            entryGateOpen = { entryGateOpen },
            onDeleteOwnedUri = { uri -> deleteOwnedCaptureAsync(context, uri) },
            onConfirmWorkspace = { identity ->
                workflowViewModel.confirm(identity, activeEntryOrigin)
            },
            onAcceptSource = { uri, source, purpose, requestId, occurredAt, expectedPageCount ->
                workflowViewModel.acceptSource(
                    uri = uri,
                    source = source,
                    origin = activeEntryOrigin,
                    purpose = purpose,
                    requestId = requestId,
                    occurredAtEpochMillis = occurredAt,
                    expectedPageCount = expectedPageCount,
                )
            },
        )
    }

    fun persistAdditionalPage(localUri: String, source: CaptureInputSource) {
        sourceImport.persistAdditionalPage(localUri, source)
    }

    val latestPendingCameraUri by rememberUpdatedState(state.pendingCameraUri)
    val latestReceivedImageUri by rememberUpdatedState(state.receivedImageUri)
    val latestReplacementCandidateUri by rememberUpdatedState(state.replacementCandidateUri)
    val latestPendingAppendOwnedUri by rememberUpdatedState(state.pendingAppendOwnedUri)

    val returnedImages = remember {
        CaptureReturnedImageCommands(
            state = state,
            draftState = draftState,
            sourceImport = sourceImport,
            onDeleteOwnedUri = { uri -> deleteOwnedCaptureAsync(context, uri) },
        )
    }

    fun applyReturnedImagePlan(
        plan: CaptureReturnedImagePlan,
        source: CaptureInputSource,
        purpose: CaptureAcquisitionPurpose,
    ) {
        returnedImages.apply(plan, source, purpose)
    }


    val acquisitionLaunchers = rememberCaptureAcquisitionLaunchers(
        context = context,
        scope = coroutineScope,
        pendingCameraUri = state.pendingCameraUri,
        receivedImageUri = state.receivedImageUri,
        replacementCandidateUri = state.replacementCandidateUri,
        acquisitionPurpose = acquisitionPurpose,
        onPendingCameraUriChange = { state.pendingCameraUri = it },
        onPhotoImportInProgressChange = { state.photoImportInProgress = it },
        applyReturnedImagePlan = { plan, source, purpose ->
            returnedImages.apply(plan, source, purpose)
        },
    )
    val acquisition = remember(acquisitionLaunchers) {
        CaptureAcquisitionCommands(
            context = context,
            scope = coroutineScope,
            launchers = acquisitionLaunchers,
            state = state,
            workspaceCommands = workspaceCommands,
            returnedImages = returnedImages,
            onWaitForCachePrune = { initialCachePrune.await() },
        )
    }
    fun launchCamera(
        purpose: CaptureAcquisitionPurpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        workspaceAlreadyFlushed: Boolean = false,
    ) {
        acquisition.launchCamera(purpose, workspaceAlreadyFlushed)
    }
    fun launchPhotoPicker(
        purpose: CaptureAcquisitionPurpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        workspaceAlreadyFlushed: Boolean = false,
    ) {
        acquisition.launchPhotoPicker(purpose, workspaceAlreadyFlushed)
    }
    fun requestRetake() {
        acquisition.requestRetake(hasDraft = state.draftId != null)
    }

    fun persistSourceAndStartCorrection() {
        sourceImport.persistNewSource()
    }

    fun replaceDraftWithCandidate() {
        sourceImport.replaceDraftWithCandidate()
    }

    fun keepCurrentDraftAfterReplacementFailure() {
        sourceImport.keepCurrentDraftAfterReplacementFailure()
    }

    fun commitCorrection() {
        sourceImport.commitCorrection()
    }

    val workflowEvents = remember {
        CaptureWorkflowEventCommands(
            state = state,
            draftState = draftState,
            onDeleteOwnedUri = { uri -> deleteOwnedCaptureAsync(context, uri) },
            onConsumeDraftImported = { workflowViewModel.consumeDraftImported(it) },
            onConsumeTutorSession = { workflowViewModel.onTutorSessionConsumed(it) },
            onTutorSessionReady = onTutorSessionReady,
        )
    }

    LaunchedEffect(state.receivedImageUri, state.draftId) {
        if (shouldAutoPersistCapture(state.receivedImageUri, state.draftId, state.workflowInProgress)) {
            persistSourceAndStartCorrection()
        }
    }

    LaunchedEffect(workflowUiState.importedDraft?.requestId) {
        val event = workflowUiState.importedDraft ?: return@LaunchedEffect
        workflowEvents.applyImported(event)
    }

    LaunchedEffect(
        workflowUiState.workflow.phase,
        workflowUiState.workflow.savedEntryId,
    ) {
        workflowEvents.applySaved(
            phase = workflowUiState.workflow.phase,
            origin = activeEntryOrigin,
            currentRevisionNumber = state.draftRevisionNumber,
            savedEntryId = workflowUiState.workflow.savedEntryId,
        )
    }

    LaunchedEffect(workflowUiState.confirmedTutorSession?.sessionId) {
        val session = workflowUiState.confirmedTutorSession ?: return@LaunchedEffect
        workflowEvents.consumeTutorSession(session)
    }

    LaunchedEffect(
        workflowUiState.workflow.phase,
        workflowUiState.workflow.latestRequestId,
    ) {
        workflowEvents.applyFailed(
            phase = workflowUiState.workflow.phase,
            failureCode = workflowUiState.workflow.failureCode,
        )
    }

    LaunchedEffect(state.replacementCandidateUri) {
        if (state.replacementCandidateUri != null && state.replacementError == null) {
            replaceDraftWithCandidate()
        }
    }

    val resumeCommands = remember {
        CaptureResumeCommands(
            context = context,
            repository = repository,
            state = state,
            draftState = draftState,
            onCompleteCachePrune = { initialCachePrune.complete(Unit) },
            onRedirectTutor = onTutorSessionReady,
        )
    }

    LaunchedEffect(Unit) {
        resumeCommands.pruneAndRecoverOwnedUris()
    }

    LaunchedEffect(resumeDraftId) {
        val requestedDraftId = resumeDraftId ?: return@LaunchedEffect
        resumeCommands.loadResume(requestedDraftId)
    }

    // Runtime-state rehydration for the fresh capture routes: sourcePages are
    // deliberately outside the Saver, and only the resume route re-derives
    // them. After a rotation or a detour to model settings, an in-progress
    // draft would otherwise be stuck on "正在准备这道题" with no way forward.
    LaunchedEffect(
        resumeDraftId,
        state.draftId,
        state.committedEntryId,
        state.sourcePages.size,
    ) {
        if (resumeDraftId != null) return@LaunchedEffect
        val currentDraftId = state.draftId ?: return@LaunchedEffect
        if (state.committedEntryId != null) return@LaunchedEffect
        if (state.sourcePages.isNotEmpty()) return@LaunchedEffect
        if (state.workflowInProgress) return@LaunchedEffect
        resumeCommands.loadResume(currentDraftId)
        when (state.resumeLoadStateName) {
            CaptureResumeLoadState.MISSING.name -> {
                state.draftId = null
                state.receivedImageUri = null
                state.captureError = CAPTURE_RESUMED_DRAFT_MISSING
            }
            CaptureResumeLoadState.SOURCE_UNAVAILABLE.name -> {
                state.captureError = CAPTURE_RESUMED_DRAFT_UNAVAILABLE
            }
        }
    }

    LaunchedEffect(state.draftId, state.workspaceHydratedDraftId) {
        val currentDraftId = state.draftId ?: return@LaunchedEffect
        resumeCommands.hydrateWorkspace(currentDraftId)
    }

    LaunchedEffect(state.workspaceHydratedDraftId, state.workspaceChangeVersion) {
        if (
            state.workspaceState == null ||
            state.workspaceHydratedDraftId == null ||
            state.workspaceState?.finalConfirmationRequest != null
        ) {
            return@LaunchedEffect
        }
        delay(CAPTURE_WORKSPACE_DEBOUNCE_MILLIS)
        if (state.workspaceState?.finalConfirmationRequest != null) return@LaunchedEffect
        flushWorkspaceNow()
    }

    DisposableEffect(lifecycleOwner, modelTasks) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> lifecycleOwner.lifecycleScope.launch {
                    state.providerCapabilities = runCatching { modelTasks.capabilities() }.getOrNull()
                }
                Lifecycle.Event.ON_STOP -> if (state.workspaceState != null) {
                    lifecycleOwner.lifecycleScope.launch {
                        withContext(NonCancellable) { flushWorkspaceNow() }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (state.workspaceState != null) {
                lifecycleOwner.lifecycleScope.launch {
                    withContext(NonCancellable) { flushWorkspaceNow() }
                }
            }
        }
    }

    BackHandler(enabled = state.workspaceState != null && state.committedEntryId == null) {
        afterWorkspaceFlush(onBack)
    }

    val modelTaskCommands = remember(modelExecutionCoordinator) {
        CaptureModelTaskCommands(
            repository = repository,
            modelTasks = modelTasks,
            coordinator = modelExecutionCoordinator,
            state = state,
            draftState = draftState,
            onSplitReady = onSplitReady,
            structuredProjection = { structuredProjection },
            buildAssessmentRequest = {
                requestId, currentDraftId, sourceAssetId, width, height, occurredAt, egressAllowed ->
                captureAssessmentRequest(
                    requestId = requestId,
                    draftId = currentDraftId,
                    sourceAssetId = sourceAssetId,
                    origin = activeEntryOrigin.toAssessmentOrigin(),
                    imageWidth = width,
                    imageHeight = height,
                    occurredAtEpochMillis = occurredAt,
                    agentConsentGranted = egressAllowed,
                    userHint = state.userHint,
                )
            },
            buildParseRequest = {
                requestId, currentDraftId, basisRevision, pages, assessmentIds, occurredAt, egressAllowed ->
                captureParseRequest(
                    requestId = requestId,
                    draftId = currentDraftId,
                    origin = activeEntryOrigin.toAssessmentOrigin(),
                    basisRevisionNumber = basisRevision,
                    sourcePages = pages,
                    assessmentRequestIds = assessmentIds,
                    occurredAtEpochMillis = occurredAt,
                    agentConsentGranted = egressAllowed,
                )
            },
        )
    }
    LaunchedEffect(state.assessmentRequestId) {
        val requestId = state.assessmentRequestId ?: return@LaunchedEffect
        modelTaskCommands.observeAssessment(requestId)
    }

    LaunchedEffect(
        state.assessmentSnapshot?.stateVersion,
        state.splitRetryNonce,
        state.draftId,
        state.draftRevisionNumber,
        state.sourcePages,
    ) {
        when (
            captureSplitDecision(
                snapshot = state.assessmentSnapshot,
                draftId = state.draftId,
                revisionNumber = state.draftRevisionNumber,
                sourcePages = state.sourcePages,
            )
        ) {
            // 一页多题需要用户拍板拆分方式（自动/手动框选），不抢先执行。
            is CaptureSplitDecision.Run -> state.splitPendingChoice = true
            else -> modelTaskCommands.maybeSplit()
        }
    }

    var manualSplitDialogOpen by remember { mutableStateOf(false) }
    if (manualSplitDialogOpen) {
        val selectedPage = state.sourcePages.getOrNull(state.selectedSourcePageIndex)
        RegionBoxSelectorDialog(
            imageUri = selectedPage?.imageUri ?: state.receivedImageUri.orEmpty(),
            pageWidth = selectedPage?.width ?: 0,
            pageHeight = selectedPage?.height ?: 0,
            onConfirm = { regions ->
                manualSplitDialogOpen = false
                coroutineScope.launch { modelTaskCommands.manualSplit(regions) }
            },
            onDismiss = { manualSplitDialogOpen = false },
        )
    }

    LaunchedEffect(modelTasks) {
        state.providerCapabilities = runCatching { modelTasks.capabilities() }.getOrNull()
    }

    LaunchedEffect(
        state.assessmentRequestId,
        state.assessmentSourceAssetId,
        state.assessmentOccurredAtEpochMillis,
        state.assessmentRetryNonce,
        state.providerCapabilities,
        modelEgressAllowed,
    ) {
        modelTaskCommands.dispatchAssessment(
            provider = state.providerCapabilities,
            requestId = state.assessmentRequestId,
            sourceAssetId = state.assessmentSourceAssetId,
            draftId = state.draftId,
            occurredAt = state.assessmentOccurredAtEpochMillis,
            // The gate value doubles as the request envelope's attested grant.
            egressAllowed = modelEgressAllowed,
        )
    }

    LaunchedEffect(state.parseRequestId) {
        val requestId = state.parseRequestId ?: return@LaunchedEffect
        modelTaskCommands.observeParse(requestId)
    }

    LaunchedEffect(
        state.parseRequestId,
        state.sourcePageAssessmentSnapshots.map { it?.stateVersion },
        state.sourcePages,
        state.parseRetryNonce,
        state.providerCapabilities,
        modelEgressAllowed,
    ) {
        modelTaskCommands.dispatchParse(
            provider = state.providerCapabilities,
            requestId = state.parseRequestId,
            draftId = state.draftId,
            basisRevision = state.draftRevisionNumber,
            egressAllowed = modelEgressAllowed,
        )
    }

    LaunchedEffect(state.parseSnapshot?.stateVersion) {
        modelTaskCommands.adoptParseOutput(realParseOutput)
    }

    DisposableEffect(context) {
        onDispose {
            if (context.findActivity()?.isChangingConfigurations != true) {
                val ownedUris = buildList {
                    latestPendingCameraUri?.let(::add)
                    latestReceivedImageUri?.let(::add)
                    latestReplacementCandidateUri?.let(::add)
                    latestPendingAppendOwnedUri?.let(::add)
                }
                captureCleanupScope.launch {
                    ownedUris.forEach {
                        revokeCaptureGrant(context, it)
                        deleteOwnedCapture(context, it)
                    }
                }
            }
        }
    }

    if (
        resumeDraftId != null &&
        resumeLoadState != CaptureResumeLoadState.READY
    ) {
        RootPageColumn(modifier = modifier.testTag("capture_resume_gate")) {
            CaptureTopBar(title = "继续处理题目", onBack = onBack)
            LocalModeLine(
                text = "原图和已生成题面保存在本机；完成前不会加入错题本",
                icon = Icons.Outlined.Lock,
                contentDescription = "待处理题目保存状态",
            )
            SectionHeader(
                title = "恢复题目",
                modifier = Modifier.padding(top = 18.dp),
            )
            CaptureResumeStateCard(
                state = resumeLoadState,
                onBack = onBack,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        return
    }

    RootPageColumn(modifier = modifier.testTag("capture_screen")) {
        CaptureTopBar(
            title = when (activeEntryOrigin) {
                CaptureEntryOrigin.TUTOR -> "拍题讲解"
                CaptureEntryOrigin.LIBRARY -> "录入错题"
            },
            onBack = ::requestBackWithFlush,
        )
        LocalModeLine(
            text = capturePrivacyLine(
                snapshot = state.assessmentSnapshot,
                parseSnapshot = state.parseSnapshot,
                sourcePersisted = state.draftId != null,
            ),
            icon = Icons.Outlined.Lock,
            contentDescription = "拍题隐私状态",
        )

        Text(
            text = when (activeEntryOrigin) {
                CaptureEntryOrigin.TUTOR -> "题面准备好后直接进入讲题；是否加入错题本由你决定"
                CaptureEntryOrigin.LIBRARY -> "题面准备好后直接存入错题本，不会自动开始讲解"
            },
            color = InkMuted,
            style = MaterialTheme.typography.bodySmall,
        )

        SectionHeader(
            title = when {
                state.committedEntryId != null -> "已存入错题本"
                state.draftId != null -> "整理题目"
                state.receivedImageUri != null -> "图片已安全接收"
                else -> "拍下完整题目"
            },
            modifier = Modifier.padding(top = 18.dp),
        )

        if (state.committedEntryId != null && activeEntryOrigin == CaptureEntryOrigin.LIBRARY) {
            CaptureCommittedCard(
                onView = { onLibraryEntryReady(state.committedEntryId.orEmpty()) },
                onCaptureAnother = {
                    resetForNextCapture()
                    state.receivedImageUri = null
                    state.receivedInputSource = null
                    launchCamera()
                },
                modifier = Modifier.padding(top = 14.dp),
            )
        } else if (state.draftId != null) {
            if (state.sourcePages.isNotEmpty()) {
                CaptureSourcePageBar(
                    pages = state.sourcePages,
                    selectedPageIndex = state.selectedSourcePageIndex,
                    enabled = !state.workflowInProgress && state.sourcePages.size < MAX_CAPTURE_SOURCE_PAGES,
                    onSelectPage = {
                        state.selectedSourcePageIndex = it
                    },
                    onAddByCamera = {
                        launchCamera(CaptureAcquisitionPurpose.APPEND_DRAFT)
                    },
                    onAddFromPhotos = {
                        launchPhotoPicker(CaptureAcquisitionPurpose.APPEND_DRAFT)
                    },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            (state.sourcePages.getOrNull(state.selectedSourcePageIndex)?.imageUri ?: state.receivedImageUri)
                ?.let { imageUri ->
                CaptureSourceImagePreview(
                    imageUri = imageUri,
                    onStateChange = {},
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (!candidateUsable && !manualWorkspaceReady) {
                if (!captureModelReady) {
                    CaptureModelSetupBlock(
                        onOpenSettings = { afterWorkspaceFlush(onOpenModelSettings) },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                } else {
                    CaptureUserHintField(
                        hint = state.userHint,
                        evaluatedHint = (state.assessmentSnapshot?.request?.input
                            as? CaptureAssessmentInput)?.userHint,
                        evaluatedTerminal = state.assessmentSnapshot?.status?.isTerminal == true,
                        onHintChange = { state.userHint = it },
                        onReassess = ::retryAssessmentProcessing,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    CaptureModelTaskCard(
                        snapshot = state.assessmentSnapshot,
                        parseSnapshot = state.parseSnapshot,
                        splitInProgress = state.workflowInProgress &&
                            assessmentDecision == CaptureAssessmentDecision.SPLIT,
                        splitPendingChoice = state.splitPendingChoice,
                        splitError = state.splitError,
                        onAutoSplit = {
                            state.splitPendingChoice = false
                            coroutineScope.launch { modelTaskCommands.maybeSplit() }
                        },
                        onManualSplit = { manualSplitDialogOpen = true },
                        onRetrySplit = {
                            state.splitPendingChoice = false
                            state.splitRetryNonce += 1
                            coroutineScope.launch { modelTaskCommands.maybeSplit() }
                        },
                        onRetry = ::retryAssessmentProcessing,
                        onRetryParse = ::retryParseProcessing,
                        onRetake = ::requestRetake,
                        onAddPage = {
                            launchCamera(CaptureAcquisitionPurpose.APPEND_DRAFT)
                        },
                        onOpenModelSettings = { afterWorkspaceFlush(onOpenModelSettings) },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            visibleStructuredDocument
                ?.takeIf { candidateUsable }
                ?.let { candidate ->
                CaptureDocumentPreviewCard(
                    capturedDocument = candidate,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (candidateUsable || manualWorkspaceReady) {
                if (manualEntryMode) {
                    Text(
                        text = "当前版本不连接模型：照片已用本地识别生成题面，请检查后手动整理录入。",
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .testTag("capture_manual_entry_notice"),
                        color = InkMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CaptureCorrectionForm(
                subject = state.workspaceState?.subject ?: state.selectedSubject,
                title = state.workspaceState?.title ?: state.correctedTitle,
                transcription = state.workspaceState?.transcription ?: state.correctedTranscription,
                writingLayer = runCatching { CaptureWritingLayer.valueOf(state.writingLayerName) }
                    .getOrDefault(CaptureWritingLayer.UNKNOWN),
                writingLayerResolved = state.workspaceState?.hasResolvedWritingLayers() == true,
                recognitionState = runCatching {
                    CaptureRecognitionState.valueOf(state.recognitionStateName)
                }.getOrDefault(CaptureRecognitionState.NOT_ATTEMPTED),
                recognitionConfidence = structuredCandidate?.blockEvidence
                    ?.mapNotNull { it.confidence }
                    ?.takeIf { it.isNotEmpty() }
                    ?.average() ?: state.recognitionConfidence,
                recognitionBlockCount = structuredCandidate?.document?.blocks?.size
                    ?: state.recognitionBlockCount,
                candidateKind = candidateKind,
                candidateUsable = candidateUsable || manualWorkspaceReady,
                manualEntry = manualEntryMode,
                entryGateOpen = entryGateOpen,
                entryGateMessage = if (assessmentBlocksEntry) "请先补拍完整的一道题。" else "",
                structuredProjectionEdited = state.workspaceState?.userEditedFields?.isNotEmpty() == true,
                transcriptionEditable = state.workspaceState?.canEditAsOneTextField() != false,
                completionIntent = when (activeEntryOrigin) {
                    CaptureEntryOrigin.TUTOR -> CaptureCompletionIntent.START_TUTORING
                    CaptureEntryOrigin.LIBRARY -> CaptureCompletionIntent.SAVE_TO_LIBRARY
                },
                isSaving = state.workflowInProgress || state.workspaceSaving || state.workspaceState == null,
                isRetryLocked = state.commitOutcomeUnknown || finalConfirmationPending,
                onSubjectChange = {
                    state.selectedSubject = it
                    updateWorkspace { state -> state.editSubject(it) }
                },
                onTitleChange = {
                    state.correctedTitle = it
                    state.titleEditedByUser = true
                    updateWorkspace { state -> state.editTitle(it) }
                },
                onTranscriptionChange = {
                    state.correctedTranscription = it
                    state.transcriptionEditedByUser = true
                    updateWorkspace { state -> state.editSingleParagraph(it) }
                },
                onWritingLayerChange = {
                    state.writingLayerName = it.name
                    updateWorkspace { state -> state.editWritingLayer(it) }
                },
                onCommit = ::commitCorrection,
                modifier = Modifier.padding(top = 12.dp),
                structuredEditorState = state.workspaceState?.takeUnless {
                    it.canEditAsOneTextField()
                },
                onStructuredBlockChange = { updatedBlock ->
                    state.transcriptionEditedByUser = true
                    updateWorkspace { it.editBlock(updatedBlock) }
                },
            )
            }
        } else if (state.receivedImageUri == null) {
            val disclosedProvider = state.providerCapabilities
            val disclosedEntryOrigin = activeEntryOrigin
            CaptureActions(
                provider = disclosedProvider,
                entryOrigin = disclosedEntryOrigin,
                onTakePicture = {
                    if (!state.cameraLaunchInProgress && !state.photoImportInProgress && !state.workflowInProgress) {
                        launchCamera()
                    }
                },
                onPickPhoto = {
                    if (!state.cameraLaunchInProgress && !state.photoImportInProgress && !state.workflowInProgress) {
                        launchPhotoPicker()
                    }
                },
                modifier = Modifier.padding(top = 14.dp),
            )
            CaptureGuidance(Modifier.padding(top = 20.dp))
        } else {
            AwaitingCorrectionCard(
                onRetake = { launchCamera() },
                onPickAnother = { launchPhotoPicker() },
                onStartCorrection = ::persistSourceAndStartCorrection,
                isImporting = state.workflowInProgress,
                modifier = Modifier.padding(top = 14.dp),
            )
        }


        if (state.replacementCandidateUri != null) {
            ReplacementStatusCard(
                isReplacing = state.workflowInProgress && state.replacementError == null,
                error = state.replacementError,
                onRetry = ::replaceDraftWithCandidate,
                onKeepCurrent = ::keepCurrentDraftAfterReplacementFailure,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (
            workflowUiState.workflow.phase ==
            com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase.FAILED &&
            workflowUiState.workflow.canRetry &&
            workflowUiState.userError != null &&
            state.captureError == null
        ) {
            CaptureError(
                message = workflowUiState.userError?.message.orEmpty(),
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlineActionChip(
                text = "重试上一步",
                onClick = workflowViewModel::retryFailedWorkflow,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .testTag("capture_workflow_retry"),
            )
        }
        if (state.captureError != null) {
            CaptureError(
                message = state.captureError.orEmpty(),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (state.workspaceSaveError != null) {
            WorkspaceSaveErrorCard(
                message = state.workspaceSaveError.orEmpty(),
                saving = state.workspaceSaving,
                onRetry = { coroutineScope.launch { flushWorkspaceNow() } },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

    }


}

/**
 * 评估阶段的可选指向说明：默认收起不打扰"拍完即走"；填写后随图交给模型界定录入范围。
 * 说明相对已评估内容有变化且评估已出终态时，给出"按说明重新评估"。
 */
@Composable
private fun CaptureUserHintField(
    hint: String,
    evaluatedHint: String?,
    evaluatedTerminal: Boolean,
    onHintChange: (String) -> Unit,
    onReassess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier) {
        if (!expanded) {
            OutlineActionChip(
                text = "补充说明（可选）",
                onClick = { expanded = true },
                modifier = Modifier.testTag("capture_user_hint_toggle"),
            )
            return@Column
        }
        OutlinedTextField(
            value = hint,
            onValueChange = { updated -> if (updated.length <= MAX_CAPTURE_USER_HINT_CHARS) onHintChange(updated) },
            placeholder = { Text("例如：只要第 2、3 题；只录左半页的题") },
            supportingText = { Text("简短说明要录入的范围，随图一起交给模型，可不填。") },
            singleLine = false,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("capture_user_hint_field"),
        )
        if (evaluatedTerminal && evaluatedHint != hint.trim().takeIf { it.isNotEmpty() }) {
            OutlineActionChip(
                text = "按说明重新评估",
                onClick = onReassess,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("capture_user_hint_reassess"),
            )
        }
    }
}
