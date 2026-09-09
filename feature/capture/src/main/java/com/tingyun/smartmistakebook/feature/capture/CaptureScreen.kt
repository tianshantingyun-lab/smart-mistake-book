package com.tingyun.smartmistakebook.feature.capture

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalModeLine
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    onSplitReady: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resumeDraftId: String? = null,
    agentConsentGranted: Boolean = false,
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
    val captureModelConsentGranted = agentConsentGranted &&
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
    val entryGateOpen = candidateUsable

    val draftState = remember { CaptureDraftStateCommands(state) }

    fun retryAssessmentProcessing() {
        draftState.retryAssessment()
    }

    fun retryParseProcessing() {
        draftState.retryParse()
    }

    fun resetDraftState() {
        draftState.resetDraft()
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
            applyReturnedImagePlan(plan, source, purpose)
        },
    )
    val acquisition = remember(acquisitionLaunchers) {
        CaptureAcquisitionCommands(
            context = context,
            scope = coroutineScope,
            launchers = acquisitionLaunchers,
            sink = CaptureAcquisitionSink(
                hasWorkspace = { state.workspaceState != null },
                cameraLaunchInProgress = { state.cameraLaunchInProgress },
                photoImportInProgress = { state.photoImportInProgress },
                workflowInProgress = { state.workflowInProgress },
                setPurpose = { state.acquisitionPurposeName = it.name },
                applyReplacementPrep = { prep ->
                    if (prep.requestId != null) {
                        state.replacementRequestId = prep.requestId
                        state.replacementOccurredAtEpochMillis = prep.occurredAtEpochMillis
                    }
                    if (prep.clearReplacementError) {
                        state.replacementError = null
                    }
                },
                setCameraLaunchInProgress = { state.cameraLaunchInProgress = it },
                setPhotoImportInProgress = { state.photoImportInProgress = it },
                setPendingCameraUri = { state.pendingCameraUri = it },
                clearCaptureError = { state.captureError = null },
                applyReturnedImagePlan = { plan, source, purpose ->
                    applyReturnedImagePlan(plan, source, purpose)
                },
                afterWorkspaceFlush = ::afterWorkspaceFlush,
                waitForCachePrune = { initialCachePrune.await() },
            ),
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
            sink = CaptureResumeSink(
                pendingCameraUri = { state.pendingCameraUri },
                receivedImageUri = { state.receivedImageUri },
                replacementCandidateUri = { state.replacementCandidateUri },
                pendingAppendOwnedUri = { state.pendingAppendOwnedUri },
                draftId = { state.draftId },
                workspaceHydratedDraftId = { state.workspaceHydratedDraftId },
                applyOwnedUriRecovery = { recovery ->
                    if (recovery.clearPendingCamera) state.pendingCameraUri = null
                    if (recovery.clearReceived) {
                        state.receivedImageUri = null
                        state.receivedInputSource = null
                    }
                    if (recovery.clearReplacement) {
                        state.replacementCandidateUri = null
                        state.replacementInputSourceName = null
                        state.replacementRequestId = null
                        state.replacementOccurredAtEpochMillis = null
                    }
                    if (recovery.clearPendingAppend) state.pendingAppendOwnedUri = null
                    recovery.error?.let { state.captureError = it }
                },
                completeCachePrune = { initialCachePrune.complete(Unit) },
                setResumeState = { state.resumeLoadStateName = it.name },
                redirectTutor = { onTutorSessionReady(it) },
                applyResumeDraft = { applied ->
                    state.activeEntryOriginName = applied.originName
                    state.receivedImageUri = applied.receivedImageUri
                    state.receivedInputSource = null
                    state.importRequestId = null
                    state.importOccurredAtEpochMillis = applied.importOccurredAtEpochMillis
                    state.commitOutcomeUnknown = false
                    state.draftId = applied.draftId
                    state.draftRevisionNumber = applied.draftRevisionNumber
                    state.canonicalSha256 = applied.canonicalSha256
                    state.sourcePages = applied.sourcePages
                    state.sourcePageAssessmentSnapshots = applied.sourcePageAssessmentSnapshots
                    state.selectedSourcePageIndex = 0
                    state.committedEntryId = null
                    state.selectedSubject = applied.selectedSubject
                    state.correctedTitle = applied.correctedTitle
                    state.titleEditedByUser = false
                    state.correctedTranscription = applied.correctedTranscription
                    state.writingLayerName = applied.writingLayerName
                    state.recognitionStateName = applied.recognitionStateName
                    state.recognitionConfidence = applied.recognitionConfidence
                    state.recognitionBlockCount = applied.recognitionBlockCount
                    state.assessmentSnapshot = applied.tasks.assessmentSnapshot
                    state.assessmentRequestId = applied.tasks.assessmentRequestId
                    state.assessmentSourceAssetId = applied.tasks.assessmentSourceAssetId
                    state.assessmentOccurredAtEpochMillis = applied.tasks.assessmentOccurredAtEpochMillis
                    state.assessmentRetryNonce = 0
                    state.parseSnapshot = applied.tasks.parseSnapshot
                    state.parseRequestId = applied.tasks.parseRequestId
                    state.parseRetryNonce = 0
                    state.transcriptionEditedByUser = false
                    state.captureError = null
                    applyWorkspace(applied.workspace)
                },
                applyWorkspace = { applyWorkspace(it) },
            ),
        )
    }

    LaunchedEffect(Unit) {
        resumeCommands.pruneAndRecoverOwnedUris()
    }

    LaunchedEffect(resumeDraftId) {
        val requestedDraftId = resumeDraftId ?: return@LaunchedEffect
        resumeCommands.loadResume(requestedDraftId)
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
            sink = CaptureModelTaskSink(
                sourcePages = { state.sourcePages },
                pageAssessmentSnapshots = { state.sourcePageAssessmentSnapshots },
                assessmentSnapshot = { state.assessmentSnapshot },
                parseSnapshot = { state.parseSnapshot },
                draftId = { state.draftId },
                revisionNumber = { state.draftRevisionNumber },
                workspace = { state.workspaceState },
                pendingAssessmentRecoveryRequest = { state.pendingAssessmentRecoveryRequest },
                pendingParseRecoveryRequest = { state.pendingParseRecoveryRequest },
                transcriptionEditedByUser = { state.transcriptionEditedByUser },
                titleEditedByUser = { state.titleEditedByUser },
                structuredProjection = { structuredProjection },
                setAssessmentSnapshot = { state.assessmentSnapshot = it },
                setParseSnapshot = { state.parseSnapshot = it },
                setPageAssessmentSnapshots = { state.sourcePageAssessmentSnapshots = it },
                setSplitError = { state.splitError = it },
                setWorkflowInProgress = { state.workflowInProgress = it },
                resetDraft = { resetDraftState() },
                onSplitReady = onSplitReady,
                clearPendingAssessmentRecovery = { state.pendingAssessmentRecoveryRequest = null },
                clearPendingParseRecovery = { state.pendingParseRecoveryRequest = null },
                replaceWorkspace = { adopted ->
                    state.workspaceState = adopted
                    state.workspaceChangeVersion += 1
                    state.correctedTranscription = adopted.transcription
                    state.correctedTitle = adopted.title.ifBlank {
                        suggestCaptureTitle(adopted.transcription)
                    }
                },
                applyAdoptedText = { adoptedText ->
                    state.correctedTranscription = adoptedText.transcription
                    adoptedText.title?.let { state.correctedTitle = it }
                },
                buildAssessmentRequest = {
                    requestId, currentDraftId, sourceAssetId, width, height, occurredAt, consent ->
                    captureAssessmentRequest(
                        requestId = requestId,
                        draftId = currentDraftId,
                        sourceAssetId = sourceAssetId,
                        origin = activeEntryOrigin.toAssessmentOrigin(),
                        imageWidth = width,
                        imageHeight = height,
                        occurredAtEpochMillis = occurredAt,
                        agentConsentGranted = consent,
                    )
                },
                buildParseRequest = {
                    requestId, currentDraftId, basisRevision, pages, assessmentIds, occurredAt, consent ->
                    captureParseRequest(
                        requestId = requestId,
                        draftId = currentDraftId,
                        origin = activeEntryOrigin.toAssessmentOrigin(),
                        basisRevisionNumber = basisRevision,
                        sourcePages = pages,
                        assessmentRequestIds = assessmentIds,
                        occurredAtEpochMillis = occurredAt,
                        agentConsentGranted = consent,
                    )
                },
            ),
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
        modelTaskCommands.maybeSplit()
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
        agentConsentGranted,
    ) {
        modelTaskCommands.dispatchAssessment(
            provider = state.providerCapabilities,
            requestId = state.assessmentRequestId,
            sourceAssetId = state.assessmentSourceAssetId,
            draftId = state.draftId,
            occurredAt = state.assessmentOccurredAtEpochMillis,
            agentConsentGranted = agentConsentGranted,
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
        agentConsentGranted,
    ) {
        modelTaskCommands.dispatchParse(
            provider = state.providerCapabilities,
            requestId = state.parseRequestId,
            draftId = state.draftId,
            basisRevision = state.draftRevisionNumber,
            agentConsentGranted = agentConsentGranted,
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
                    resetDraftState()
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
            if (!candidateUsable) {
                if (!captureModelConsentGranted) {
                    CaptureModelAgentConsentBlock(
                        onOpenSettings = { afterWorkspaceFlush(onOpenModelSettings) },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                } else {
                    CaptureModelTaskCard(
                        snapshot = state.assessmentSnapshot,
                        parseSnapshot = state.parseSnapshot,
                        splitInProgress = state.workflowInProgress &&
                            assessmentDecision == CaptureAssessmentDecision.SPLIT,
                        splitError = state.splitError,
                        onRetrySplit = { state.splitRetryNonce += 1 },
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
            if (candidateUsable) {
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
                candidateUsable = candidateUsable,
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
