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
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.isModelEgressApprovalFresh
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
    onTutorSessionReady: (
        sessionId: String,
        autoStartAuthorization: TutorAutoStartAuthorization?,
    ) -> Unit,
    onLibraryEntryReady: (String) -> Unit,
    onSplitReady: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resumeDraftId: String? = null,
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
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var receivedImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var receivedInputSource by rememberSaveable { mutableStateOf<String?>(null) }
    var captureError by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraLaunchInProgress by remember { mutableStateOf(false) }
    var photoImportInProgress by remember { mutableStateOf(false) }
    var workflowInProgress by remember { mutableStateOf(false) }
    var importRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var importOccurredAtEpochMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var commitOutcomeUnknown by rememberSaveable { mutableStateOf(false) }
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftRevisionNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    var canonicalSha256 by rememberSaveable { mutableStateOf<String?>(null) }
    var committedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSubject by rememberSaveable { mutableStateOf("") }
    var correctedTitle by rememberSaveable { mutableStateOf("") }
    var titleEditedByUser by rememberSaveable { mutableStateOf(false) }
    var correctedTranscription by rememberSaveable { mutableStateOf("") }
    var writingLayerName by rememberSaveable { mutableStateOf(CaptureWritingLayer.UNKNOWN.name) }
    var recognitionStateName by rememberSaveable {
        mutableStateOf(CaptureRecognitionState.NOT_ATTEMPTED.name)
    }
    var recognitionConfidence by rememberSaveable { mutableStateOf<Double?>(null) }
    var recognitionBlockCount by rememberSaveable { mutableStateOf(0) }
    var assessmentRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var assessmentSourceAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var assessmentOccurredAtEpochMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var assessmentRetryNonce by rememberSaveable { mutableStateOf(0) }
    var splitRetryNonce by rememberSaveable { mutableStateOf(0) }
    var splitError by rememberSaveable { mutableStateOf<String?>(null) }
    var assessmentSnapshot by remember { mutableStateOf<ModelTaskSnapshot?>(null) }
    var pendingAssessmentRecoveryRequest by remember {
        mutableStateOf<ModelTaskRequest?>(null)
    }
    var sourcePages by remember { mutableStateOf<List<CaptureSourcePage>>(emptyList()) }
    var sourcePageAssessmentSnapshots by remember {
        mutableStateOf<List<ModelTaskSnapshot?>>(emptyList())
    }
    var selectedSourcePageIndex by rememberSaveable { mutableStateOf(0) }
    var parseRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var parseRetryNonce by rememberSaveable { mutableStateOf(0) }
    var parseSnapshot by remember { mutableStateOf<ModelTaskSnapshot?>(null) }
    var pendingParseRecoveryRequest by remember { mutableStateOf<ModelTaskRequest?>(null) }
    var transcriptionEditedByUser by rememberSaveable { mutableStateOf(false) }
    var providerCapabilities by remember(modelTasks) {
        mutableStateOf<ProviderCapabilitySnapshot?>(null)
    }
    var activeCaptureAuthorizationId by remember(modelTasks) {
        mutableStateOf<String?>(null)
    }
    var initialTutorPlanCaptureAuthorizationId by remember(modelTasks) {
        mutableStateOf<String?>(null)
    }
    var freshCaptureEgressIntent by remember(modelTasks) {
        mutableStateOf<CaptureDraftEgressIntent?>(null)
    }
    val informedEgressIntentSession = remember(modelTasks) {
        CaptureInformedEgressIntentSession()
    }
    val captureExecutionLaunchGuard = remember(modelTasks) {
        CaptureExternalExecutionLaunchGuard()
    }
    val captureModelTaskCoordinator = remember(modelTasks, coroutineScope) {
        CaptureModelTaskCoordinator(
            modelTasks = modelTasks,
            launchGuard = captureExecutionLaunchGuard,
            scope = coroutineScope,
        )
    }
    var egressAuthorizationId by rememberSaveable { mutableStateOf<String?>(null) }
    var egressApprovedAtEpochMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var egressApprovedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var egressApprovedModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var egressApprovedProviderConfigurationVersion by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var activeEntryOriginName by rememberSaveable(resumeDraftId, entryOrigin) {
        mutableStateOf(entryOrigin.name)
    }
    var resumeLoadStateName by rememberSaveable(resumeDraftId) {
        mutableStateOf(
            if (resumeDraftId == null) {
                CaptureResumeLoadState.NOT_REQUESTED.name
            } else {
                CaptureResumeLoadState.LOADING.name
            },
        )
    }
    var acquisitionPurposeName by rememberSaveable {
        mutableStateOf(CaptureAcquisitionPurpose.NEW_CAPTURE.name)
    }
    var replacementCandidateUri by rememberSaveable { mutableStateOf<String?>(null) }
    var replacementInputSourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var replacementRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var replacementOccurredAtEpochMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var replacementError by rememberSaveable { mutableStateOf<String?>(null) }
    var workspaceState by remember { mutableStateOf<CaptureWorkspaceUiState?>(null) }
    var workspaceIdentity by remember { mutableStateOf<com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity?>(null) }
    var workspaceUpdatedAtEpochMillis by remember { mutableStateOf(0L) }
    var workspaceHydratedDraftId by remember { mutableStateOf<String?>(null) }
    var workspaceChangeVersion by remember { mutableStateOf(0L) }
    var workspaceSaveError by remember { mutableStateOf<String?>(null) }
    var workspaceSaving by remember { mutableStateOf(false) }
    var pendingAppendOwnedUri by remember { mutableStateOf<String?>(null) }

    val activeEntryOrigin = runCatching {
        CaptureEntryOrigin.valueOf(activeEntryOriginName)
    }.getOrDefault(entryOrigin)
    val resumeLoadState = runCatching {
        CaptureResumeLoadState.valueOf(resumeLoadStateName)
    }.getOrDefault(CaptureResumeLoadState.SOURCE_UNAVAILABLE)
    val acquisitionPurpose = runCatching {
        CaptureAcquisitionPurpose.valueOf(acquisitionPurposeName)
    }.getOrDefault(CaptureAcquisitionPurpose.NEW_CAPTURE)

    val realParseOutput = (parseSnapshot?.output as? CaptureParseOutput)
        ?.takeIf { parseSnapshot?.provider?.isDemo == false }
    val structuredCandidate = realParseOutput?.capturedDocument
    val visibleStructuredDocument = workspaceState?.workingDocument ?: structuredCandidate
    val structuredProjection = visibleStructuredDocument?.let {
        QuestionDocumentMarkdownProjection.project(it.document).trim()
    }.orEmpty()
    val assessmentDecision = (assessmentSnapshot?.output as? CaptureAssessmentOutput)
        ?.assessment?.decision
    val assessmentBlocksEntry = assessmentDecision != null &&
        assessmentDecision != CaptureAssessmentDecision.PASS
    val egressApprovalMustBeRenewed = captureEgressApprovalMustBeRenewed(
        assessmentSnapshot = assessmentSnapshot,
        parseSnapshot = parseSnapshot,
    )
    val persistedCaptureEgress = assessmentSnapshot?.request?.egressManifest
        ?: parseSnapshot?.request?.egressManifest
    val captureAuthorizationNow = System.currentTimeMillis()
    val savedCaptureEgress = providerCapabilities?.let { provider ->
        val authorizationId = egressAuthorizationId
        val approvedAt = egressApprovedAtEpochMillis
        val currentDraftId = draftId
        if (
            authorizationId != null &&
            approvedAt != null &&
            currentDraftId != null &&
            sourcePages.isNotEmpty() &&
            egressApprovedProviderId == provider.providerId &&
            egressApprovedModelId == provider.modelId &&
            egressApprovedProviderConfigurationVersion ==
            provider.providerConfigurationVersion
        ) {
            buildCaptureEgressManifest(
                authorizationId = authorizationId,
                draftId = currentDraftId,
                provider = provider,
                sourcePages = sourcePages,
                approvedAtEpochMillis = approvedAt,
            ).takeIf { manifest ->
                manifest.isModelEgressApprovalFresh(captureAuthorizationNow)
            }
        } else {
            null
        }
    }
    val matchingPersistedCaptureEgress = providerCapabilities?.let { provider ->
        val currentDraftId = draftId
        persistedCaptureEgress?.takeIf { persisted ->
            currentDraftId != null &&
                sourcePages.isNotEmpty() &&
                persisted.matchesCaptureApproval(
                    draftId = currentDraftId,
                    provider = provider,
                    sourcePages = sourcePages,
                ) && persisted.isModelEgressApprovalFresh(captureAuthorizationNow)
        }
    }
    val captureEgressManifest = if (egressApprovalMustBeRenewed) {
        null
    } else {
        listOfNotNull(savedCaptureEgress, matchingPersistedCaptureEgress)
            .firstOrNull { manifest ->
                manifest.authorizationId == activeCaptureAuthorizationId
            }
    }
    val captureEgressApprovalRequired =
        providerCapabilities?.requiresCaptureEgressApproval() == true
    val captureRecoveryTask = providerCapabilities
        ?.takeIf(ProviderCapabilitySnapshot::requiresCaptureEgressApproval)
        ?.let {
            parseSnapshot?.takeUnless { task -> task.status == ModelTaskStatus.SUCCEEDED }
                ?: assessmentSnapshot?.takeUnless { task ->
                    task.status == ModelTaskStatus.SUCCEEDED
                }
        }
        ?.takeIf { captureEgressManifest == null }
    val captureRecoveryAction = captureModelRecoveryAction(assessmentSnapshot, parseSnapshot)
    val captureSettingsOnly = captureRecoveryAction == CaptureModelRecoveryAction.OPEN_SETTINGS &&
        captureRecoveryTask?.let { task ->
            providerCapabilities?.let(task::matchesCaptureProvider) != false
        } != false
    val captureContinuationRequired = captureEgressApprovalRequired &&
        captureEgressManifest == null &&
        freshCaptureEgressIntent == null &&
        draftId != null &&
        (resumeDraftId != null ||
            captureRecoveryTask != null ||
            persistedCaptureEgress != null ||
            savedCaptureEgress != null ||
            assessmentRequestId != null)
    val candidateKind = if (structuredCandidate != null) {
        CaptureCandidateKind.MODEL_STRUCTURED
    } else if (recognitionStateName == CaptureRecognitionState.CANDIDATE_AVAILABLE.name) {
        CaptureCandidateKind.LOCAL_TRANSITIONAL
    } else {
        CaptureCandidateKind.NONE
    }
    val correctedStructuredCandidate =
        workspaceState?.editorMode == CaptureDraftEditorMode.STRUCTURED_DOCUMENT &&
            workspaceState?.userEditedBlockIds?.isNotEmpty() == true
    val finalConfirmationPending = workspaceState?.finalConfirmationRequest != null
    val candidateUsable = captureCandidateIsUsable(visibleStructuredDocument) && (
        structuredCandidate != null ||
            correctedStructuredCandidate ||
            finalConfirmationPending
        ) && (!assessmentBlocksEntry || finalConfirmationPending)
    val entryGateOpen = candidateUsable

    val draftState = remember {
        CaptureDraftStateCommands(
            sink = CaptureDraftStateSink(
                draftId = { draftId },
                sourcePages = { sourcePages },
                assessmentSnapshot = { assessmentSnapshot },
                parseSnapshot = { parseSnapshot },
                parseOutput = { realParseOutput },
                workspace = { workspaceState },
                clearEgressApproval = {
                    activeCaptureAuthorizationId = null
                    initialTutorPlanCaptureAuthorizationId = null
                    egressAuthorizationId = null
                    egressApprovedAtEpochMillis = null
                    egressApprovedProviderId = null
                    egressApprovedModelId = null
                    egressApprovedProviderConfigurationVersion = null
                },
                rememberEgressApproval = { manifest, provider ->
                    egressAuthorizationId = manifest.authorizationId
                    egressApprovedAtEpochMillis = manifest.approvedAtEpochMillis
                    egressApprovedProviderId = provider.providerId
                    egressApprovedModelId = provider.modelId
                    egressApprovedProviderConfigurationVersion =
                        provider.providerConfigurationVersion
                    activeCaptureAuthorizationId = manifest.authorizationId
                },
                clearPendingAssessmentRecovery = { pendingAssessmentRecoveryRequest = null },
                replaceAssessmentRequestId = { requestId ->
                    assessmentRequestId = requestId
                    assessmentOccurredAtEpochMillis = System.currentTimeMillis()
                },
                clearAssessmentSnapshotForActivePage = {
                    assessmentSnapshot = null
                    val activeAssetId = assessmentSourceAssetId
                    sourcePageAssessmentSnapshots = sourcePageAssessmentSnapshots.mapIndexed {
                            index,
                            existing,
                        ->
                        if (sourcePages.getOrNull(index)?.sourceAssetId == activeAssetId) {
                            null
                        } else {
                            existing
                        }
                    }
                },
                incrementAssessmentRetryNonce = { assessmentRetryNonce += 1 },
                clearPendingParseRecovery = { pendingParseRecoveryRequest = null },
                replaceParseRequestId = { parseRequestId = it },
                clearParseSnapshot = { parseSnapshot = null },
                incrementParseRetryNonce = { parseRetryNonce += 1 },
                resetDraftFields = {
                    importRequestId = UUID.randomUUID().toString()
                    importOccurredAtEpochMillis = System.currentTimeMillis()
                    commitOutcomeUnknown = false
                    draftId = null
                    draftRevisionNumber = null
                    canonicalSha256 = null
                    committedEntryId = null
                    selectedSubject = ""
                    correctedTitle = ""
                    titleEditedByUser = false
                    correctedTranscription = ""
                    writingLayerName = CaptureWritingLayer.UNKNOWN.name
                    recognitionStateName = CaptureRecognitionState.NOT_ATTEMPTED.name
                    recognitionConfidence = null
                    recognitionBlockCount = 0
                    assessmentRequestId = null
                    assessmentSourceAssetId = null
                    assessmentOccurredAtEpochMillis = null
                    assessmentRetryNonce = 0
                    splitRetryNonce = 0
                    splitError = null
                    assessmentSnapshot = null
                    pendingAssessmentRecoveryRequest = null
                    sourcePages = emptyList()
                    sourcePageAssessmentSnapshots = emptyList()
                    selectedSourcePageIndex = 0
                    parseRequestId = null
                    parseRetryNonce = 0
                    parseSnapshot = null
                    pendingParseRecoveryRequest = null
                    transcriptionEditedByUser = false
                    freshCaptureEgressIntent = null
                },
                clearWorkspace = {
                    workspaceState = null
                    workspaceIdentity = null
                    workspaceUpdatedAtEpochMillis = 0
                    workspaceHydratedDraftId = null
                    workspaceChangeVersion = 0
                    workspaceSaveError = null
                    workspaceSaving = false
                },
                applyWorkspaceSnapshot = { restored ->
                    workspaceState = restored.state
                    workspaceIdentity = restored.identity
                    workspaceUpdatedAtEpochMillis = restored.updatedAtEpochMillis
                    workspaceHydratedDraftId = restored.state.draftId
                    selectedSubject = restored.state.subject
                    correctedTitle = restored.state.title
                    correctedTranscription = restored.state.transcription
                    writingLayerName = restored.state.captureWritingLayer().name
                    titleEditedByUser =
                        com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField.TITLE in
                            restored.state.userEditedFields
                    transcriptionEditedByUser = restored.state.userEditedFields.any {
                        it == com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField.TRANSCRIPTION ||
                            it == com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField.STRUCTURE
                    }
                    workspaceSaveError = null
                },
                replaceWorkspace = { updated ->
                    workspaceState = updated
                    workspaceChangeVersion += 1
                    workspaceSaveError = null
                    selectedSubject = updated.subject
                    correctedTitle = updated.title
                    correctedTranscription = updated.transcription
                    writingLayerName = updated.captureWritingLayer().name
                },
                applyImportedSummary = { draft, imported, occurredAt ->
                    draftId = draft.draftId
                    draftRevisionNumber = draft.revisionNumber
                    canonicalSha256 = draft.sourceAssetSha256
                    sourcePages = draft.sourcePages
                    sourcePageAssessmentSnapshots = imported.pageSnapshots
                    selectedSourcePageIndex = 0
                    recognitionStateName = draft.recognition.state.name
                    recognitionConfidence = draft.recognition.confidence
                    recognitionBlockCount = draft.recognition.candidateBlockCount
                    correctedTranscription = draft.recognition.candidateText
                    correctedTitle = suggestCaptureTitle(draft.recognition.candidateText)
                    titleEditedByUser = false
                    assessmentRequestId = imported.assessmentRequestId
                    assessmentSourceAssetId = imported.assessmentSourceAssetId
                    assessmentOccurredAtEpochMillis = occurredAt
                    parseRequestId = imported.parseRequestId
                },
                bindImportedEgress = { draft, sourceEgressIntent ->
                    freshCaptureEgressIntent = sourceEgressIntent?.bindDraft(
                        draftId = draft.draftId,
                        sourcePages = draft.sourcePages,
                    )
                    sourceEgressIntent?.let { informedEgressIntentSession.complete(it.intentId) }
                },
            ),
        )
    }

    fun clearCaptureEgressApproval() {
        draftState.clearEgressApproval()
    }

    fun approveCaptureEgress(
        provider: ProviderCapabilitySnapshot,
    ): ModelEgressManifest? = draftState.approveEgress(provider)

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
        sourceEgressIntent: CaptureSourceEgressIntent? = null,
    ) {
        draftState.applyDraftSummary(draft, requestId, occurredAtEpochMillis, sourceEgressIntent)
    }

    val workspaceCommands = remember(workspaceWriter) {
        CaptureWorkspaceCommands(
            writer = workspaceWriter,
            scope = coroutineScope,
            sink = CaptureWorkspaceSink(
                currentWorkspace = { workspaceState },
                currentIdentity = { workspaceIdentity },
                saving = { workspaceSaving },
                workflowInProgress = { workflowInProgress },
                setSaving = { workspaceSaving = it },
                applySave = { applied ->
                    if (applied.identity != null) {
                        workspaceIdentity = applied.identity
                        workspaceUpdatedAtEpochMillis = applied.updatedAtEpochMillis ?: workspaceUpdatedAtEpochMillis
                    }
                    workspaceSaveError = applied.error
                },
            ),
        )
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

    val sourceImport = remember(workspaceCommands) {
        CaptureSourceImportCommands(
            scope = coroutineScope,
            sink = CaptureSourceImportSink(
                receivedImageUri = { receivedImageUri },
                receivedInputSource = {
                    receivedInputSource?.let(CaptureInputSource::valueOf)
                },
                importRequestId = { importRequestId },
                importOccurredAtEpochMillis = { importOccurredAtEpochMillis },
                rememberImportIdentity = { identity ->
                    if (identity.persistRequestId) importRequestId = identity.requestId
                    if (identity.persistOccurredAt) {
                        importOccurredAtEpochMillis = identity.occurredAtEpochMillis
                    }
                },
                draftId = { draftId },
                revisionNumber = { draftRevisionNumber },
                pageCount = { sourcePages.size },
                workflowInProgress = { workflowInProgress },
                setWorkflowInProgress = { workflowInProgress = it },
                setCaptureError = { captureError = it },
                deleteOwnedUri = { uri -> deleteOwnedCaptureAsync(context, uri) },
                setPendingAppendOwnedUri = { pendingAppendOwnedUri = it },
                replacementCandidateUri = { replacementCandidateUri },
                replacementInputSource = {
                    replacementInputSourceName?.let(CaptureInputSource::valueOf)
                },
                replacementRequestId = { replacementRequestId },
                replacementOccurredAtEpochMillis = { replacementOccurredAtEpochMillis },
                clearReplacementError = { replacementError = null },
                clearReplacementState = {
                    replacementCandidateUri = null
                    replacementInputSourceName = null
                    replacementRequestId = null
                    replacementOccurredAtEpochMillis = null
                    acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                    replacementError = null
                },
                entryGateOpen = { entryGateOpen },
                workspace = { workspaceState },
                workspaceUpdatedAtEpochMillis = { workspaceUpdatedAtEpochMillis },
                workspaceIdentity = { workspaceIdentity },
                setWorkspaceSaveError = { workspaceSaveError = it },
                replaceWorkspace = { updated ->
                    workspaceState = updated
                    workspaceChangeVersion += 1
                },
                saveWorkspaceNow = { state, occurredAt ->
                    saveWorkspaceNow(state, occurredAt)
                },
                confirmWorkspace = { identity ->
                    workflowViewModel.confirm(identity, activeEntryOrigin)
                },
                acceptSource = { uri, source, purpose, requestId, occurredAt, expectedPageCount ->
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
            ),
        )
    }

    fun persistAdditionalPage(localUri: String, source: CaptureInputSource) {
        sourceImport.persistAdditionalPage(localUri, source)
    }

    val latestPendingCameraUri by rememberUpdatedState(pendingCameraUri)
    val latestReceivedImageUri by rememberUpdatedState(receivedImageUri)
    val latestReplacementCandidateUri by rememberUpdatedState(replacementCandidateUri)
    val latestPendingAppendOwnedUri by rememberUpdatedState(pendingAppendOwnedUri)

    val returnedImages = remember {
        CaptureReturnedImageCommands(
            sink = CaptureReturnedImageSink(
                deleteOwnedUri = { uri -> deleteOwnedCaptureAsync(context, uri) },
                clearPendingCamera = { pendingCameraUri = null },
                setReceivedImage = { uri, source ->
                    receivedImageUri = uri
                    receivedInputSource = source.name
                },
                resetDraft = { resetDraftState() },
                bindReturnedSource = { purpose, uri ->
                    informedEgressIntentSession.bindReturnedSource(
                        purpose = purpose,
                        sourceUri = uri,
                    )
                },
                clearCaptureError = { captureError = null },
                setReplacement = { uri, source ->
                    replacementCandidateUri = uri
                    replacementInputSourceName = source.name
                },
                clearReplacementError = { replacementError = null },
                persistAdditionalPage = { uri, source -> persistAdditionalPage(uri, source) },
                cancelAcquisition = { informedEgressIntentSession.cancelAcquisition() },
                resetPurpose = {
                    acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                },
                clearReplacementRequest = {
                    replacementRequestId = null
                    replacementOccurredAtEpochMillis = null
                },
                setCaptureError = { captureError = it },
            ),
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
        pendingCameraUri = pendingCameraUri,
        receivedImageUri = receivedImageUri,
        replacementCandidateUri = replacementCandidateUri,
        acquisitionPurpose = acquisitionPurpose,
        onPendingCameraUriChange = { pendingCameraUri = it },
        onPhotoImportInProgressChange = { photoImportInProgress = it },
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
                hasWorkspace = { workspaceState != null },
                cameraLaunchInProgress = { cameraLaunchInProgress },
                photoImportInProgress = { photoImportInProgress },
                workflowInProgress = { workflowInProgress },
                setPurpose = { acquisitionPurposeName = it.name },
                applyReplacementPrep = { prep ->
                    if (prep.requestId != null) {
                        replacementRequestId = prep.requestId
                        replacementOccurredAtEpochMillis = prep.occurredAtEpochMillis
                    }
                    if (prep.clearReplacementError) {
                        replacementError = null
                    }
                },
                setCameraLaunchInProgress = { cameraLaunchInProgress = it },
                setPhotoImportInProgress = { photoImportInProgress = it },
                setPendingCameraUri = { pendingCameraUri = it },
                clearCaptureError = { captureError = null },
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
        acquisition.requestRetake(hasDraft = draftId != null)
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
            sink = CaptureWorkflowEventSink(
                applyDraftSummary = { draft, requestId, occurredAt, intent ->
                    applyDraftSummary(draft, requestId, occurredAt, intent)
                },
                sourceEgressIntent = { purpose, uri ->
                    informedEgressIntentSession.sourceFor(purpose = purpose, sourceUri = uri)
                },
                clearCaptureError = { captureError = null },
                pageAssessmentSnapshots = { sourcePageAssessmentSnapshots },
                applyAppendedPages = {
                    pages, snapshots, selectedPageIndex, assessmentId, assetId, occurredAt, parseId ->
                    sourcePages = pages
                    sourcePageAssessmentSnapshots = snapshots
                    selectedSourcePageIndex = selectedPageIndex
                    assessmentSnapshot = null
                    assessmentRequestId = assessmentId
                    assessmentSourceAssetId = assetId
                    assessmentOccurredAtEpochMillis = occurredAt
                    assessmentRetryNonce = 0
                    parseSnapshot = null
                    parseRequestId = parseId
                    parseRetryNonce = 0
                },
                clearCaptureEgressApproval = { clearCaptureEgressApproval() },
                bindFreshEgressIntent = { freshCaptureEgressIntent = it },
                completeEgressIntent = { informedEgressIntentSession.complete(it) },
                resetAcquisitionPurpose = {
                    acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                },
                deleteOwnedUri = { uri -> deleteOwnedCaptureAsync(context, uri) },
                clearPendingAppend = { pendingAppendOwnedUri = null },
                receivedImageUri = { receivedImageUri },
                setReceivedImage = { uri ->
                    receivedImageUri = uri
                    receivedInputSource = null
                },
                resetDraft = { resetDraftState() },
                clearReplacementState = {
                    replacementCandidateUri = null
                    replacementInputSourceName = null
                    replacementRequestId = null
                    replacementOccurredAtEpochMillis = null
                    acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                    replacementError = null
                },
                setWorkflowInProgress = { workflowInProgress = it },
                consumeDraftImported = { workflowViewModel.consumeDraftImported(it) },
                commitLibraryEntry = { entryId, nextRevision ->
                    committedEntryId = entryId
                    draftRevisionNumber = nextRevision
                },
                clearReceivedImage = { receivedImageUri = null },
                clearWorkspace = {
                    workspaceState = null
                    workspaceIdentity = null
                    workspaceUpdatedAtEpochMillis = 0
                    workspaceHydratedDraftId = null
                },
                markCommitKnown = { commitOutcomeUnknown = false },
                markCommitUnknown = { commitOutcomeUnknown = true },
                onTutorSessionReady = onTutorSessionReady,
                consumeTutorSession = { workflowViewModel.onTutorSessionConsumed(it) },
            ),
        )
    }

    LaunchedEffect(receivedImageUri, draftId) {
        if (shouldAutoPersistCapture(receivedImageUri, draftId, workflowInProgress)) {
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
            currentRevisionNumber = draftRevisionNumber,
            savedEntryId = workflowUiState.workflow.savedEntryId,
        )
    }

    LaunchedEffect(workflowUiState.confirmedTutorSession?.sessionId) {
        val session = workflowUiState.confirmedTutorSession ?: return@LaunchedEffect
        workflowEvents.consumeTutorSession(
            session = session,
            provider = providerCapabilities,
            manifest = captureEgressManifest,
            activeAuthorizationId = activeCaptureAuthorizationId,
            initialTutorPlanAuthorizationId = initialTutorPlanCaptureAuthorizationId,
            nowEpochMillis = System.currentTimeMillis(),
        )
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

    LaunchedEffect(replacementCandidateUri) {
        if (replacementCandidateUri != null && replacementError == null) {
            replaceDraftWithCandidate()
        }
    }

    val resumeCommands = remember {
        CaptureResumeCommands(
            context = context,
            repository = repository,
            sink = CaptureResumeSink(
                pendingCameraUri = { pendingCameraUri },
                receivedImageUri = { receivedImageUri },
                replacementCandidateUri = { replacementCandidateUri },
                pendingAppendOwnedUri = { pendingAppendOwnedUri },
                draftId = { draftId },
                workspaceHydratedDraftId = { workspaceHydratedDraftId },
                applyOwnedUriRecovery = { recovery ->
                    if (recovery.clearPendingCamera) pendingCameraUri = null
                    if (recovery.clearReceived) {
                        receivedImageUri = null
                        receivedInputSource = null
                    }
                    if (recovery.clearReplacement) {
                        replacementCandidateUri = null
                        replacementInputSourceName = null
                        replacementRequestId = null
                        replacementOccurredAtEpochMillis = null
                    }
                    if (recovery.clearPendingAppend) pendingAppendOwnedUri = null
                    recovery.error?.let { captureError = it }
                },
                completeCachePrune = { initialCachePrune.complete(Unit) },
                setResumeState = { resumeLoadStateName = it.name },
                redirectTutor = { onTutorSessionReady(it, null) },
                applyResumeDraft = { applied ->
                    activeEntryOriginName = applied.originName
                    receivedImageUri = applied.receivedImageUri
                    receivedInputSource = null
                    importRequestId = null
                    importOccurredAtEpochMillis = applied.importOccurredAtEpochMillis
                    commitOutcomeUnknown = false
                    draftId = applied.draftId
                    draftRevisionNumber = applied.draftRevisionNumber
                    canonicalSha256 = applied.canonicalSha256
                    sourcePages = applied.sourcePages
                    sourcePageAssessmentSnapshots = applied.sourcePageAssessmentSnapshots
                    selectedSourcePageIndex = 0
                    committedEntryId = null
                    selectedSubject = applied.selectedSubject
                    correctedTitle = applied.correctedTitle
                    titleEditedByUser = false
                    correctedTranscription = applied.correctedTranscription
                    writingLayerName = applied.writingLayerName
                    recognitionStateName = applied.recognitionStateName
                    recognitionConfidence = applied.recognitionConfidence
                    recognitionBlockCount = applied.recognitionBlockCount
                    assessmentSnapshot = applied.tasks.assessmentSnapshot
                    assessmentRequestId = applied.tasks.assessmentRequestId
                    assessmentSourceAssetId = applied.tasks.assessmentSourceAssetId
                    assessmentOccurredAtEpochMillis = applied.tasks.assessmentOccurredAtEpochMillis
                    assessmentRetryNonce = 0
                    parseSnapshot = applied.tasks.parseSnapshot
                    parseRequestId = applied.tasks.parseRequestId
                    parseRetryNonce = 0
                    transcriptionEditedByUser = false
                    captureError = null
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

    LaunchedEffect(draftId, workspaceHydratedDraftId) {
        val currentDraftId = draftId ?: return@LaunchedEffect
        resumeCommands.hydrateWorkspace(currentDraftId)
    }

    LaunchedEffect(workspaceHydratedDraftId, workspaceChangeVersion) {
        if (
            workspaceState == null ||
            workspaceHydratedDraftId == null ||
            workspaceState?.finalConfirmationRequest != null
        ) {
            return@LaunchedEffect
        }
        delay(CAPTURE_WORKSPACE_DEBOUNCE_MILLIS)
        if (workspaceState?.finalConfirmationRequest != null) return@LaunchedEffect
        flushWorkspaceNow()
    }

    DisposableEffect(lifecycleOwner, modelTasks) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> lifecycleOwner.lifecycleScope.launch {
                    providerCapabilities = runCatching { modelTasks.capabilities() }.getOrNull()
                }
                Lifecycle.Event.ON_STOP -> if (workspaceState != null) {
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
            if (workspaceState != null) {
                lifecycleOwner.lifecycleScope.launch {
                    withContext(NonCancellable) { flushWorkspaceNow() }
                }
            }
        }
    }

    BackHandler(enabled = workspaceState != null && committedEntryId == null) {
        afterWorkspaceFlush(onBack)
    }

    val modelTaskCommands = remember(captureModelTaskCoordinator) {
        CaptureModelTaskCommands(
            repository = repository,
            modelTasks = modelTasks,
            coordinator = captureModelTaskCoordinator,
            sink = CaptureModelTaskSink(
                sourcePages = { sourcePages },
                pageAssessmentSnapshots = { sourcePageAssessmentSnapshots },
                assessmentSnapshot = { assessmentSnapshot },
                parseSnapshot = { parseSnapshot },
                draftId = { draftId },
                revisionNumber = { draftRevisionNumber },
                workspace = { workspaceState },
                pendingAssessmentRecoveryRequest = { pendingAssessmentRecoveryRequest },
                pendingParseRecoveryRequest = { pendingParseRecoveryRequest },
                transcriptionEditedByUser = { transcriptionEditedByUser },
                titleEditedByUser = { titleEditedByUser },
                structuredProjection = { structuredProjection },
                setAssessmentSnapshot = { assessmentSnapshot = it },
                setParseSnapshot = { parseSnapshot = it },
                setPageAssessmentSnapshots = { sourcePageAssessmentSnapshots = it },
                clearActiveAuthorization = { activeCaptureAuthorizationId = null },
                setSplitError = { splitError = it },
                setWorkflowInProgress = { workflowInProgress = it },
                resetDraft = { resetDraftState() },
                onSplitReady = onSplitReady,
                clearFreshEgressIntent = { freshCaptureEgressIntent = null },
                approveCaptureEgress = { approveCaptureEgress(it) },
                setInitialTutorPlanAuthorizationId = {
                    initialTutorPlanCaptureAuthorizationId = it
                },
                clearPendingAssessmentRecovery = { pendingAssessmentRecoveryRequest = null },
                clearPendingParseRecovery = { pendingParseRecoveryRequest = null },
                replaceWorkspace = { adopted ->
                    workspaceState = adopted
                    workspaceChangeVersion += 1
                    correctedTranscription = adopted.transcription
                    correctedTitle = adopted.title.ifBlank {
                        suggestCaptureTitle(adopted.transcription)
                    }
                },
                applyAdoptedText = { adoptedText ->
                    correctedTranscription = adoptedText.transcription
                    adoptedText.title?.let { correctedTitle = it }
                },
                buildAssessmentRequest = {
                    requestId, currentDraftId, sourceAssetId, width, height, occurredAt, manifest ->
                    captureAssessmentRequest(
                        requestId = requestId,
                        draftId = currentDraftId,
                        sourceAssetId = sourceAssetId,
                        origin = activeEntryOrigin.toAssessmentOrigin(),
                        imageWidth = width,
                        imageHeight = height,
                        occurredAtEpochMillis = occurredAt,
                        egressManifest = manifest,
                    )
                },
                buildParseRequest = {
                    requestId, currentDraftId, basisRevision, pages, assessmentIds, occurredAt, manifest ->
                    captureParseRequest(
                        requestId = requestId,
                        draftId = currentDraftId,
                        origin = activeEntryOrigin.toAssessmentOrigin(),
                        basisRevisionNumber = basisRevision,
                        sourcePages = pages,
                        assessmentRequestIds = assessmentIds,
                        occurredAtEpochMillis = occurredAt,
                        egressManifest = manifest,
                    )
                },
            ),
        )
    }

    LaunchedEffect(assessmentRequestId) {
        val requestId = assessmentRequestId ?: return@LaunchedEffect
        modelTaskCommands.observeAssessment(requestId)
    }

    LaunchedEffect(
        assessmentSnapshot?.stateVersion,
        splitRetryNonce,
        draftId,
        draftRevisionNumber,
        sourcePages,
    ) {
        modelTaskCommands.maybeSplit()
    }

    LaunchedEffect(modelTasks) {
        providerCapabilities = runCatching { modelTasks.capabilities() }.getOrNull()
    }

    LaunchedEffect(
        freshCaptureEgressIntent,
        providerCapabilities,
        draftId,
        sourcePages,
    ) {
        modelTaskCommands.bindFreshEgress(
            informedIntent = freshCaptureEgressIntent,
            provider = providerCapabilities,
            draftId = draftId,
            sourcePages = sourcePages,
            nowEpochMillis = System.currentTimeMillis(),
        )
    }

    LaunchedEffect(
        assessmentRequestId,
        assessmentSourceAssetId,
        assessmentOccurredAtEpochMillis,
        assessmentRetryNonce,
        providerCapabilities,
        captureEgressManifest?.authorizationId,
    ) {
        modelTaskCommands.dispatchAssessment(
            provider = providerCapabilities,
            requestId = assessmentRequestId,
            sourceAssetId = assessmentSourceAssetId,
            draftId = draftId,
            occurredAt = assessmentOccurredAtEpochMillis,
            manifest = captureEgressManifest,
            activeAuthorizationId = activeCaptureAuthorizationId,
        )
    }

    LaunchedEffect(parseRequestId) {
        val requestId = parseRequestId ?: return@LaunchedEffect
        modelTaskCommands.observeParse(requestId)
    }

    LaunchedEffect(
        parseRequestId,
        sourcePageAssessmentSnapshots.map { it?.stateVersion },
        sourcePages,
        parseRetryNonce,
        providerCapabilities,
        captureEgressManifest?.authorizationId,
    ) {
        modelTaskCommands.dispatchParse(
            provider = providerCapabilities,
            requestId = parseRequestId,
            draftId = draftId,
            basisRevision = draftRevisionNumber,
            manifest = captureEgressManifest,
            activeAuthorizationId = activeCaptureAuthorizationId,
        )
    }

    LaunchedEffect(parseSnapshot?.stateVersion) {
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
                snapshot = assessmentSnapshot,
                parseSnapshot = parseSnapshot,
                sourcePersisted = draftId != null,
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
                committedEntryId != null -> "已存入错题本"
                draftId != null -> "整理题目"
                receivedImageUri != null -> "图片已安全接收"
                else -> "拍下完整题目"
            },
            modifier = Modifier.padding(top = 18.dp),
        )

        if (committedEntryId != null && activeEntryOrigin == CaptureEntryOrigin.LIBRARY) {
            CaptureCommittedCard(
                onView = { onLibraryEntryReady(committedEntryId.orEmpty()) },
                onCaptureAnother = {
                    resetDraftState()
                    receivedImageUri = null
                    receivedInputSource = null
                    launchCamera()
                },
                modifier = Modifier.padding(top = 14.dp),
            )
        } else if (draftId != null) {
            if (sourcePages.isNotEmpty()) {
                CaptureSourcePageBar(
                    pages = sourcePages,
                    selectedPageIndex = selectedSourcePageIndex,
                    enabled = !workflowInProgress && sourcePages.size < MAX_CAPTURE_SOURCE_PAGES,
                    onSelectPage = {
                        selectedSourcePageIndex = it
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
            (sourcePages.getOrNull(selectedSourcePageIndex)?.imageUri ?: receivedImageUri)
                ?.let { imageUri ->
                CaptureSourceImagePreview(
                    imageUri = imageUri,
                    onStateChange = {},
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (!candidateUsable) {
                providerCapabilities
                    ?.takeIf {
                        captureContinuationRequired && !captureSettingsOnly &&
                            it.requiresCaptureEgressApproval()
                    }
                    ?.let { provider ->
                        CaptureModelEgressConsentCard(
                            provider = provider,
                            approved = false,
                            approveActionText = "继续整理这道题",
                            onApprove = {
                                if (
                                    pendingAssessmentRecoveryRequest != null ||
                                    pendingParseRecoveryRequest != null
                                ) {
                                    return@CaptureModelEgressConsentCard
                                }
                                val freshManifest = approveCaptureEgress(provider)
                                    ?: return@CaptureModelEgressConsentCard
                                captureRecoveryTask?.let { failedTask ->
                                    val recoveryRequest = rebuildCaptureRequestAfterApproval(
                                        failedTask = failedTask,
                                        provider = provider,
                                        freshManifest = freshManifest,
                                    )
                                    when (
                                        val application = captureRecoveryApplication(
                                            recoveryRequest = recoveryRequest,
                                            sourcePages = sourcePages,
                                            pageSnapshots = sourcePageAssessmentSnapshots,
                                        )
                                    ) {
                                        is CaptureRecoveryApplication.Assessment -> {
                                            pendingAssessmentRecoveryRequest = application.request
                                            assessmentRequestId = application.request.requestId
                                            assessmentSourceAssetId = application.sourceAssetId
                                            assessmentOccurredAtEpochMillis =
                                                application.request.occurredAtEpochMillis
                                            assessmentRetryNonce = 0
                                            assessmentSnapshot = null
                                            sourcePageAssessmentSnapshots = application.pageSnapshots
                                        }
                                        is CaptureRecoveryApplication.Parse -> {
                                            pendingParseRecoveryRequest = application.request
                                            parseRequestId = application.request.requestId
                                            parseRetryNonce = 0
                                            parseSnapshot = null
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                if (
                    !captureEgressApprovalRequired ||
                    captureEgressManifest != null ||
                    captureSettingsOnly
                ) {
                    CaptureModelTaskCard(
                        snapshot = assessmentSnapshot,
                        parseSnapshot = parseSnapshot,
                        splitInProgress = workflowInProgress &&
                            assessmentDecision == CaptureAssessmentDecision.SPLIT,
                        splitError = splitError,
                        onRetrySplit = { splitRetryNonce += 1 },
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
                subject = workspaceState?.subject ?: selectedSubject,
                title = workspaceState?.title ?: correctedTitle,
                transcription = workspaceState?.transcription ?: correctedTranscription,
                writingLayer = runCatching { CaptureWritingLayer.valueOf(writingLayerName) }
                    .getOrDefault(CaptureWritingLayer.UNKNOWN),
                writingLayerResolved = workspaceState?.hasResolvedWritingLayers() == true,
                recognitionState = runCatching {
                    CaptureRecognitionState.valueOf(recognitionStateName)
                }.getOrDefault(CaptureRecognitionState.NOT_ATTEMPTED),
                recognitionConfidence = structuredCandidate?.blockEvidence
                    ?.mapNotNull { it.confidence }
                    ?.takeIf { it.isNotEmpty() }
                    ?.average() ?: recognitionConfidence,
                recognitionBlockCount = structuredCandidate?.document?.blocks?.size
                    ?: recognitionBlockCount,
                candidateKind = candidateKind,
                candidateUsable = candidateUsable,
                entryGateOpen = entryGateOpen,
                entryGateMessage = if (assessmentBlocksEntry) "请先补拍完整的一道题。" else "",
                structuredProjectionEdited = workspaceState?.userEditedFields?.isNotEmpty() == true,
                transcriptionEditable = workspaceState?.canEditAsOneTextField() != false,
                completionIntent = when (activeEntryOrigin) {
                    CaptureEntryOrigin.TUTOR -> CaptureCompletionIntent.START_TUTORING
                    CaptureEntryOrigin.LIBRARY -> CaptureCompletionIntent.SAVE_TO_LIBRARY
                },
                isSaving = workflowInProgress || workspaceSaving || workspaceState == null,
                isRetryLocked = commitOutcomeUnknown || finalConfirmationPending,
                onSubjectChange = {
                    selectedSubject = it
                    updateWorkspace { state -> state.editSubject(it) }
                },
                onTitleChange = {
                    correctedTitle = it
                    titleEditedByUser = true
                    updateWorkspace { state -> state.editTitle(it) }
                },
                onTranscriptionChange = {
                    correctedTranscription = it
                    transcriptionEditedByUser = true
                    updateWorkspace { state -> state.editSingleParagraph(it) }
                },
                onWritingLayerChange = {
                    writingLayerName = it.name
                    updateWorkspace { state -> state.editWritingLayer(it) }
                },
                onCommit = ::commitCorrection,
                modifier = Modifier.padding(top = 12.dp),
                structuredEditorState = workspaceState?.takeUnless {
                    it.canEditAsOneTextField()
                },
                onStructuredBlockChange = { updatedBlock ->
                    transcriptionEditedByUser = true
                    updateWorkspace { it.editBlock(updatedBlock) }
                },
            )
            }
        } else if (receivedImageUri == null) {
            // The controls and their callbacks must share this exact disclosure snapshot. Reading
            // delegated state again inside an old callback could authorize a provider the student
            // has not yet seen after capabilities refresh asynchronously.
            val disclosedProvider = providerCapabilities
            val disclosedEntryOrigin = activeEntryOrigin
            CaptureActions(
                provider = disclosedProvider,
                entryOrigin = disclosedEntryOrigin,
                onTakePicture = {
                    if (!cameraLaunchInProgress && !photoImportInProgress && !workflowInProgress) {
                        informedEgressIntentSession.begin(
                            provider = disclosedProvider,
                            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                            intentId = UUID.randomUUID().toString(),
                            nowEpochMillis = System.currentTimeMillis(),
                            authorizesInitialTutorPlan =
                                disclosedEntryOrigin == CaptureEntryOrigin.TUTOR,
                        )
                        launchCamera()
                    }
                },
                onPickPhoto = {
                    if (!cameraLaunchInProgress && !photoImportInProgress && !workflowInProgress) {
                        informedEgressIntentSession.begin(
                            provider = disclosedProvider,
                            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                            intentId = UUID.randomUUID().toString(),
                            nowEpochMillis = System.currentTimeMillis(),
                            authorizesInitialTutorPlan =
                                disclosedEntryOrigin == CaptureEntryOrigin.TUTOR,
                        )
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
                isImporting = workflowInProgress,
                modifier = Modifier.padding(top = 14.dp),
            )
        }


        if (replacementCandidateUri != null) {
            ReplacementStatusCard(
                isReplacing = workflowInProgress && replacementError == null,
                error = replacementError,
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
            captureError == null
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
        if (captureError != null) {
            CaptureError(
                message = captureError.orEmpty(),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (workspaceSaveError != null) {
            WorkspaceSaveErrorCard(
                message = workspaceSaveError.orEmpty(),
                saving = workspaceSaving,
                onRetry = { coroutineScope.launch { flushWorkspaceNow() } },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

    }


}
