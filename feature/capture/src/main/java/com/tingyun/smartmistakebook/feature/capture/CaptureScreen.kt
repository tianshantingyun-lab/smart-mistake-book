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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
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


private enum class CaptureResumeLoadState {
    NOT_REQUESTED,
    LOADING,
    READY,
    MISSING,
    SOURCE_UNAVAILABLE,
    REDIRECTING,
}

internal enum class CaptureAcquisitionPurpose {
    NEW_CAPTURE,
    REPLACE_DRAFT,
    APPEND_DRAFT,
}

internal enum class CaptureResultAction {
    APPLY_AS_NEW,
    REPLACE_EXISTING,
    APPEND_EXISTING,
    KEEP_CURRENT,
}

internal fun captureResultAction(
    saved: Boolean,
    isEligibleImage: Boolean,
    purpose: CaptureAcquisitionPurpose,
): CaptureResultAction = when {
    !saved || !isEligibleImage -> CaptureResultAction.KEEP_CURRENT
    purpose == CaptureAcquisitionPurpose.REPLACE_DRAFT -> CaptureResultAction.REPLACE_EXISTING
    purpose == CaptureAcquisitionPurpose.APPEND_DRAFT -> CaptureResultAction.APPEND_EXISTING
    else -> CaptureResultAction.APPLY_AS_NEW
}

internal fun retakeAcquisitionPurpose(hasDraft: Boolean): CaptureAcquisitionPurpose =
    if (hasDraft) CaptureAcquisitionPurpose.REPLACE_DRAFT
    else CaptureAcquisitionPurpose.NEW_CAPTURE

internal fun prepareCaptureCommitAttempt(
    workspace: CaptureWorkspaceUiState,
    workspaceUpdatedAtEpochMillis: Long,
    requestIdFactory: () -> String,
    nowEpochMillis: () -> Long,
): CaptureWorkspaceUiState {
    val acceptedWorkspace = workspace.prepareForFinalCommit()
    if (acceptedWorkspace.finalConfirmationRequest != null) return acceptedWorkspace
    return acceptedWorkspace.ensureFinalConfirmation(
        requestIdFactory = requestIdFactory,
        occurredAtEpochMillis = {
            maxOf(nowEpochMillis(), workspaceUpdatedAtEpochMillis)
        },
    )
}

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

    fun clearCaptureEgressApproval() {
        activeCaptureAuthorizationId = null
        initialTutorPlanCaptureAuthorizationId = null
        egressAuthorizationId = null
        egressApprovedAtEpochMillis = null
        egressApprovedProviderId = null
        egressApprovedModelId = null
        egressApprovedProviderConfigurationVersion = null
    }

    fun approveCaptureEgress(
        provider: ProviderCapabilitySnapshot,
    ): ModelEgressManifest? {
        val currentDraftId = draftId ?: return null
        if (sourcePages.isEmpty()) return null
        val authorizationId = UUID.randomUUID().toString()
        val approvedAt = System.currentTimeMillis()
        val manifest = buildCaptureEgressManifest(
            authorizationId = authorizationId,
            draftId = currentDraftId,
            provider = provider,
            sourcePages = sourcePages,
            approvedAtEpochMillis = approvedAt,
        )
        egressAuthorizationId = authorizationId
        egressApprovedAtEpochMillis = approvedAt
        egressApprovedProviderId = provider.providerId
        egressApprovedModelId = provider.modelId
        egressApprovedProviderConfigurationVersion = provider.providerConfigurationVersion
        activeCaptureAuthorizationId = authorizationId
        return manifest
    }

    fun retryAssessmentProcessing() {
        pendingAssessmentRecoveryRequest = null
        if (assessmentSnapshot?.status in setOf(
                ModelTaskStatus.CANCELLED,
                ModelTaskStatus.PERMANENT_FAILURE,
            )
        ) {
            assessmentRequestId = "capture-assess:${UUID.randomUUID()}"
            assessmentOccurredAtEpochMillis = System.currentTimeMillis()
            assessmentSnapshot = null
            val activeAssetId = assessmentSourceAssetId
            sourcePageAssessmentSnapshots = sourcePageAssessmentSnapshots.mapIndexed {
                    index,
                    existing,
                ->
                if (sourcePages.getOrNull(index)?.sourceAssetId == activeAssetId) null else existing
            }
        } else {
            assessmentRetryNonce += 1
        }
    }

    fun retryParseProcessing() {
        pendingParseRecoveryRequest = null
        if (parseSnapshot?.status in setOf(
                ModelTaskStatus.CANCELLED,
                ModelTaskStatus.PERMANENT_FAILURE,
            )
        ) {
            parseRequestId = "capture-parse:${UUID.randomUUID()}"
            parseSnapshot = null
        } else {
            parseRetryNonce += 1
        }
    }

    fun resetDraftState() {
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
        clearCaptureEgressApproval()
        workspaceState = null
        workspaceIdentity = null
        workspaceUpdatedAtEpochMillis = 0
        workspaceHydratedDraftId = null
        workspaceChangeVersion = 0
        workspaceSaveError = null
        workspaceSaving = false
    }

    fun applyWorkspace(restored: CaptureWorkspaceLocalSnapshot) {
        val effectiveState = realParseOutput?.capturedDocument?.let {
            restored.state.adoptModelCandidateIfPristine(it)
        } ?: restored.state
        workspaceState = effectiveState
        workspaceIdentity = restored.identity
        workspaceUpdatedAtEpochMillis = restored.updatedAtEpochMillis
        workspaceHydratedDraftId = effectiveState.draftId
        selectedSubject = effectiveState.subject
        correctedTitle = effectiveState.title
        correctedTranscription = effectiveState.transcription
        writingLayerName = effectiveState.captureWritingLayer().name
        titleEditedByUser = com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField.TITLE in
            effectiveState.userEditedFields
        transcriptionEditedByUser = effectiveState.userEditedFields.any {
            it == com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField.TRANSCRIPTION ||
                it == com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField.STRUCTURE
        }
        workspaceSaveError = null
    }

    fun updateWorkspace(transform: (CaptureWorkspaceUiState) -> CaptureWorkspaceUiState) {
        val current = workspaceState ?: return
        val updated = transform(current)
        if (updated == current) return
        workspaceState = updated
        workspaceChangeVersion += 1
        workspaceSaveError = null
        selectedSubject = updated.subject
        correctedTitle = updated.title
        correctedTranscription = updated.transcription
        writingLayerName = updated.captureWritingLayer().name
    }

    fun applyDraftSummary(
        draft: CaptureDraftSummary,
        requestId: String,
        occurredAtEpochMillis: Long,
        sourceEgressIntent: CaptureSourceEgressIntent? = null,
    ) {
        draftId = draft.draftId
        draftRevisionNumber = draft.revisionNumber
        canonicalSha256 = draft.sourceAssetSha256
        sourcePages = draft.sourcePages
        sourcePageAssessmentSnapshots = List(draft.sourcePages.size) { null }
        selectedSourcePageIndex = 0
        recognitionStateName = draft.recognition.state.name
        recognitionConfidence = draft.recognition.confidence
        recognitionBlockCount = draft.recognition.candidateBlockCount
        correctedTranscription = draft.recognition.candidateText
        correctedTitle = suggestCaptureTitle(draft.recognition.candidateText)
        titleEditedByUser = false
        clearCaptureEgressApproval()
        assessmentRequestId = "capture-assess:$requestId"
        assessmentSourceAssetId = draft.sourceAssetId
        assessmentOccurredAtEpochMillis = occurredAtEpochMillis
        parseRequestId = "capture-parse:$requestId"
        freshCaptureEgressIntent = sourceEgressIntent?.bindDraft(
            draftId = draft.draftId,
            sourcePages = draft.sourcePages,
        )
        sourceEgressIntent?.let { informedEgressIntentSession.complete(it.intentId) }
    }

    suspend fun saveWorkspaceNow(
        state: CaptureWorkspaceUiState,
        occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity? {
        workspaceSaving = true
        return try {
            withContext(NonCancellable) {
                when (
                    val result = workspaceWriter.save(
                        state = state,
                        currentIdentity = { workspaceIdentity },
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    )
                ) {
                    is CaptureWorkspaceWriteResult.Saved -> {
                        workspaceIdentity = result.snapshot.identity
                        workspaceUpdatedAtEpochMillis = result.snapshot.updatedAtEpochMillis
                        workspaceSaveError = null
                        result.snapshot.identity
                    }
                    is CaptureWorkspaceWriteResult.Failed -> {
                        workspaceSaveError = "这次修改还没保存好，请重试后再离开。"
                        null
                    }
                }
            }
        } finally {
            workspaceSaving = false
        }
    }

    suspend fun flushWorkspaceNow(): Boolean {
        val current = workspaceState ?: return true
        return saveWorkspaceNow(current) != null
    }

    fun afterWorkspaceFlush(action: () -> Unit) {
        if (workspaceSaving || workflowInProgress) return
        coroutineScope.launch {
            if (flushWorkspaceNow()) action()
        }
    }

    fun requestBackWithFlush() {
        if (workspaceState == null) onBack() else afterWorkspaceFlush(onBack)
    }

    fun persistAdditionalPage(localUri: String, source: CaptureInputSource) {
        val currentDraftId = draftId ?: return
        val revisionNumber = draftRevisionNumber ?: return
        if (sourcePages.size >= MAX_CAPTURE_SOURCE_PAGES) {
            captureError = "单道题最多保存 $MAX_CAPTURE_SOURCE_PAGES 页；请先完成当前题目，再单独录入下一题。"
            deleteOwnedCaptureAsync(context, localUri)
            return
        }
        if (workflowInProgress) return
        val requestId = UUID.randomUUID().toString()
        val occurredAt = System.currentTimeMillis()
        workflowInProgress = true
        coroutineScope.launch {
            try {
                val summary = repository.appendDraftPage(
                    AppendCaptureDraftPageRequest(
                        requestId = requestId,
                        draftId = currentDraftId,
                        expectedRevisionNumber = revisionNumber,
                        expectedPageCount = sourcePages.size,
                        localUri = localUri,
                        source = source,
                        occurredAtEpochMillis = occurredAt,
                    ),
                )
                val appendedPage = summary.sourcePages.last()
                sourcePages = summary.sourcePages
                sourcePageAssessmentSnapshots = List(summary.sourcePages.size) { index ->
                    if (index < appendedPage.pageIndex) {
                        sourcePageAssessmentSnapshots.getOrNull(index)
                    } else {
                        null
                    }
                }
                selectedSourcePageIndex = appendedPage.pageIndex
                assessmentSnapshot = null
                assessmentRequestId = "capture-assess:$requestId:p${appendedPage.pageIndex}"
                assessmentSourceAssetId = appendedPage.sourceAssetId
                assessmentOccurredAtEpochMillis = occurredAt
                assessmentRetryNonce = 0
                parseSnapshot = null
                parseRequestId = "capture-parse:$requestId:pages${summary.sourcePages.size}"
                parseRetryNonce = 0
                clearCaptureEgressApproval()
                val sourceEgressIntent = informedEgressIntentSession.sourceFor(
                    purpose = CaptureAcquisitionPurpose.APPEND_DRAFT,
                    sourceUri = localUri,
                )
                freshCaptureEgressIntent = sourceEgressIntent?.bindDraft(
                    draftId = summary.draftId,
                    sourcePages = summary.sourcePages,
                )
                sourceEgressIntent?.let {
                    informedEgressIntentSession.complete(it.intentId)
                }
                captureError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                captureError = "补拍页没有保存成功，原来的页面仍然安全保留，请重试。"
            } finally {
                workflowInProgress = false
                acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                deleteOwnedCaptureAsync(context, localUri)
            }
        }
    }

    val latestPendingCameraUri by rememberUpdatedState(pendingCameraUri)
    val latestReceivedImageUri by rememberUpdatedState(receivedImageUri)
    val latestReplacementCandidateUri by rememberUpdatedState(replacementCandidateUri)

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val completedCaptureUri = pendingCameraUri
        val completedPurpose = acquisitionPurpose
        pendingCameraUri = null
        completedCaptureUri?.let { revokeCaptureGrant(context, it) }
        val eligible = completedCaptureUri != null &&
            ownedCaptureWithinLimit(context, completedCaptureUri)
        when (captureResultAction(saved, eligible, acquisitionPurpose)) {
            CaptureResultAction.APPLY_AS_NEW -> {
                if (receivedImageUri != completedCaptureUri) {
                    deleteOwnedCaptureAsync(context, receivedImageUri)
                }
                receivedImageUri = completedCaptureUri
                receivedInputSource = CaptureInputSource.CAMERA.name
                resetDraftState()
                completedCaptureUri?.let { sourceUri ->
                    informedEgressIntentSession.bindReturnedSource(
                        purpose = completedPurpose,
                        sourceUri = sourceUri,
                    )
                }
                captureError = null
            }
            CaptureResultAction.REPLACE_EXISTING -> {
                if (replacementCandidateUri != completedCaptureUri) {
                    deleteOwnedCaptureAsync(context, replacementCandidateUri)
                }
                replacementCandidateUri = completedCaptureUri
                replacementInputSourceName = CaptureInputSource.CAMERA.name
                completedCaptureUri?.let { sourceUri ->
                    informedEgressIntentSession.bindReturnedSource(
                        purpose = completedPurpose,
                        sourceUri = sourceUri,
                    )
                }
                replacementError = null
                captureError = null
            }
            CaptureResultAction.APPEND_EXISTING -> {
                completedCaptureUri?.let {
                    informedEgressIntentSession.bindReturnedSource(
                        purpose = completedPurpose,
                        sourceUri = it,
                    )
                    persistAdditionalPage(it, CaptureInputSource.CAMERA)
                }
            }
            CaptureResultAction.KEEP_CURRENT -> {
                informedEgressIntentSession.cancelAcquisition()
                deleteOwnedCaptureAsync(context, completedCaptureUri)
                acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                replacementRequestId = null
                replacementOccurredAtEpochMillis = null
                if (saved && completedCaptureUri != null) {
                    captureError = "相机返回的图片为空或超过 20 MB 安全上限；原题仍保留，请重试。"
                }
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            informedEgressIntentSession.cancelAcquisition()
            photoImportInProgress = false
            acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
        } else {
            val completedPurpose = acquisitionPurpose
            coroutineScope.launch {
                var importedUri: Uri? = null
                try {
                    val result = withContext(Dispatchers.IO) {
                        importPickedPhoto(context, uri)
                    }
                    result
                        .onSuccess { localUri ->
                            importedUri = localUri
                            if (completedPurpose == CaptureAcquisitionPurpose.APPEND_DRAFT) {
                                informedEgressIntentSession.bindReturnedSource(
                                    purpose = completedPurpose,
                                    sourceUri = localUri.toString(),
                                )
                                importedUri = null
                                persistAdditionalPage(
                                    localUri.toString(),
                                    CaptureInputSource.PHOTO_PICKER,
                                )
                            } else {
                                deleteOwnedCaptureAsync(context, receivedImageUri)
                                deleteOwnedCaptureAsync(context, pendingCameraUri)
                                pendingCameraUri = null
                                receivedImageUri = localUri.toString()
                                receivedInputSource = CaptureInputSource.PHOTO_PICKER.name
                                resetDraftState()
                                informedEgressIntentSession.bindReturnedSource(
                                    purpose = completedPurpose,
                                    sourceUri = localUri.toString(),
                                )
                                captureError = null
                            }
                        }
                        .onFailure {
                            informedEgressIntentSession.cancelAcquisition()
                            captureError = "所选图片为空、格式不受支持或超过 20 MB 安全上限，请重试。"
                        }
                } finally {
                    photoImportInProgress = false
                    if (!isActive) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            importedUri?.toString()?.let { deleteOwnedCapture(context, it) }
                        }
                    }
                }
            }
        }
    }

    fun launchCamera(
        purpose: CaptureAcquisitionPurpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        workspaceAlreadyFlushed: Boolean = false,
    ) {
        if (workspaceState != null && !workspaceAlreadyFlushed) {
            afterWorkspaceFlush { launchCamera(purpose, workspaceAlreadyFlushed = true) }
            return
        }
        if (cameraLaunchInProgress || photoImportInProgress || workflowInProgress) return
        acquisitionPurposeName = purpose.name
        if (purpose == CaptureAcquisitionPurpose.REPLACE_DRAFT) {
            replacementRequestId = UUID.randomUUID().toString()
            replacementOccurredAtEpochMillis = System.currentTimeMillis()
            replacementError = null
        }
        cameraLaunchInProgress = true
        coroutineScope.launch {
            var createdUri: Uri? = null
            try {
                initialCachePrune.await()
                val result = withContext(Dispatchers.IO) { createCaptureUri(context) }
                cameraLaunchInProgress = false
                result
                    .onSuccess { uri ->
                        createdUri = uri
                        pendingCameraUri = uri.toString()
                        captureError = null
                        runCatching { takePicture.launch(uri) }
                            .onFailure {
                                informedEgressIntentSession.cancelAcquisition()
                                pendingCameraUri = null
                                acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                                revokeCaptureGrant(context, uri.toString())
                                deleteOwnedCaptureAsync(context, uri.toString())
                                captureError = "此设备没有可用的系统相机，请改用系统照片选择器。"
                            }
                    }
                    .onFailure {
                        informedEgressIntentSession.cancelAcquisition()
                        acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                        captureError = "无法创建本地照片文件，请确认设备存储空间后重试。"
                    }
            } finally {
                if (!isActive) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        createdUri?.toString()?.let { deleteOwnedCapture(context, it) }
                    }
                }
            }
        }
    }

    fun launchPhotoPicker(
        purpose: CaptureAcquisitionPurpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        workspaceAlreadyFlushed: Boolean = false,
    ) {
        if (workspaceState != null && !workspaceAlreadyFlushed) {
            afterWorkspaceFlush {
                launchPhotoPicker(purpose, workspaceAlreadyFlushed = true)
            }
            return
        }
        if (cameraLaunchInProgress || photoImportInProgress || workflowInProgress) return
        acquisitionPurposeName = purpose.name
        photoImportInProgress = true
        runCatching {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            informedEgressIntentSession.cancelAcquisition()
            photoImportInProgress = false
            acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
            captureError = "系统照片选择器暂不可用，请稍后重试。"
        }
    }

    fun requestRetake() {
        launchCamera(retakeAcquisitionPurpose(hasDraft = draftId != null))
    }

    fun persistSourceAndStartCorrection() {
        val uri = receivedImageUri ?: return
        val source = receivedInputSource?.let(CaptureInputSource::valueOf) ?: return
        val sourceEgressIntent = informedEgressIntentSession.sourceFor(
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
            sourceUri = uri,
        )
        val requestId = importRequestId ?: UUID.randomUUID().toString().also {
            importRequestId = it
        }
        val occurredAtEpochMillis = importOccurredAtEpochMillis ?: System.currentTimeMillis().also {
            importOccurredAtEpochMillis = it
        }
        if (workflowInProgress) return
        workflowInProgress = true
        coroutineScope.launch {
            try {
                val draft = repository.importDraft(
                    CaptureDraftImportRequest(
                        requestId = requestId,
                        localUri = uri,
                        source = source,
                        origin = activeEntryOrigin,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                )
                applyDraftSummary(
                    draft = draft,
                    requestId = requestId,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    sourceEgressIntent = sourceEgressIntent,
                )
                captureError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                captureError = "这张图片暂时无法保存，请换一张图片或检查设备存储空间后重试。"
            } finally {
                workflowInProgress = false
            }
        }
    }

    fun replaceDraftWithCandidate() {
        val replacedDraftId = draftId ?: return
        val expectedRevision = draftRevisionNumber ?: return
        val candidateUri = replacementCandidateUri ?: return
        val source = replacementInputSourceName?.let(CaptureInputSource::valueOf) ?: return
        val sourceEgressIntent = informedEgressIntentSession.sourceFor(
            purpose = CaptureAcquisitionPurpose.REPLACE_DRAFT,
            sourceUri = candidateUri,
        )
        val requestId = replacementRequestId ?: return
        val occurredAt = replacementOccurredAtEpochMillis ?: return
        if (workflowInProgress) return
        workflowInProgress = true
        replacementError = null
        coroutineScope.launch {
            try {
                val replacement = repository.replaceDraft(
                    ReplaceCaptureDraftRequest(
                        requestId = requestId,
                        replacedDraftId = replacedDraftId,
                        expectedReplacedRevisionNumber = expectedRevision,
                        localUri = candidateUri,
                        source = source,
                        occurredAtEpochMillis = occurredAt,
                    ),
                )
                deleteOwnedCaptureAsync(context, receivedImageUri)
                receivedImageUri = candidateUri
                receivedInputSource = null
                resetDraftState()
                applyDraftSummary(
                    draft = replacement,
                    requestId = requestId,
                    occurredAtEpochMillis = occurredAt,
                    sourceEgressIntent = sourceEgressIntent,
                )
                replacementCandidateUri = null
                replacementInputSourceName = null
                replacementRequestId = null
                replacementOccurredAtEpochMillis = null
                acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                replacementError = null
                captureError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                replacementError = "新照片还没有替换成功，原题和当前题面都已保留。"
            } finally {
                workflowInProgress = false
            }
        }
    }

    fun keepCurrentDraftAfterReplacementFailure() {
        deleteOwnedCaptureAsync(context, replacementCandidateUri)
        replacementCandidateUri = null
        replacementInputSourceName = null
        replacementRequestId = null
        replacementOccurredAtEpochMillis = null
        acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
        replacementError = null
    }

    fun commitCorrection() {
        if (!entryGateOpen) return
        val currentDraftId = draftId ?: return
        val currentWorkspace = workspaceState ?: run {
            workspaceSaveError = "题面还在恢复中，请稍后重试。"
            return
        }
        val finalizedWorkspace = prepareCaptureCommitAttempt(
            workspace = currentWorkspace,
            workspaceUpdatedAtEpochMillis = workspaceUpdatedAtEpochMillis,
            requestIdFactory = { UUID.randomUUID().toString() },
            nowEpochMillis = System::currentTimeMillis,
        )
        val finalOccurredAtEpochMillis = checkNotNull(
            finalizedWorkspace.finalConfirmationRequest,
        ).occurredAtEpochMillis
        if (finalizedWorkspace != currentWorkspace) {
            workspaceState = finalizedWorkspace
            workspaceChangeVersion += 1
        }
        if (workflowInProgress) return
        workflowInProgress = true
        coroutineScope.launch {
            try {
                val persistedFinalIdentity = workspaceIdentity
                    ?.takeIf { it.matchesPersistedFinalState(finalizedWorkspace) }
                val exactWorkspaceIdentity = persistedFinalIdentity
                    ?: saveWorkspaceNow(
                        state = finalizedWorkspace,
                        occurredAtEpochMillis = finalOccurredAtEpochMillis,
                    )
                    ?: return@launch
                val confirmation = ConfirmCapturedProblemRequest(
                    draftId = currentDraftId,
                    workspaceIdentity = exactWorkspaceIdentity,
                )
                when (activeEntryOrigin) {
                    CaptureEntryOrigin.LIBRARY -> {
                        val committed = repository.confirmAndCommit(confirmation)
                        draftRevisionNumber = (draftRevisionNumber ?: 0) + 1
                        committedEntryId = committed.errorBookEntryId
                    }
                    CaptureEntryOrigin.TUTOR -> {
                        val session = repository.confirmForTutoring(confirmation)
                        draftRevisionNumber = session.draftRevisionNumber
                        val currentProvider = providerCapabilities?.takeIf { provider ->
                            provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                                provider.supports(ModelTaskKind.TUTOR_PLAN)
                        }
                        val currentManifest = captureEgressManifest
                        val autoStartAuthorization = if (
                            currentProvider != null &&
                            currentManifest != null &&
                            currentManifest.authorizationId == activeCaptureAuthorizationId &&
                            currentManifest.authorizationId ==
                            initialTutorPlanCaptureAuthorizationId &&
                            currentManifest.isModelEgressApprovalFresh(System.currentTimeMillis())
                        ) {
                            TutorAutoStartAuthorization.grant(
                                authorizationId = currentManifest.authorizationId,
                                sessionId = session.sessionId,
                                questionDocumentId = session.questionDocument.document.id,
                                revisionNumber = session.draftRevisionNumber,
                                provider = currentProvider,
                                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
                                approvedAtEpochMillis = currentManifest.approvedAtEpochMillis,
                            )
                        } else {
                            null
                        }
                        onTutorSessionReady(session.sessionId, autoStartAuthorization)
                    }
                }
                deleteOwnedCaptureAsync(context, receivedImageUri)
                receivedImageUri = null
                workspaceState = null
                workspaceIdentity = null
                workspaceUpdatedAtEpochMillis = 0
                workspaceHydratedDraftId = null
                commitOutcomeUnknown = false
                captureError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                commitOutcomeUnknown = true
                captureError = when (activeEntryOrigin) {
                    CaptureEntryOrigin.TUTOR ->
                        "题目已经留在本机，但讲题会话可能还没有打开。请直接重试；不会自动存入错题本。"
                    CaptureEntryOrigin.LIBRARY ->
                        "题目可能还没有保存完成。请直接重试，系统不会重复建题。"
                }
            } finally {
                workflowInProgress = false
            }
        }
    }

    LaunchedEffect(receivedImageUri, draftId) {
        if (shouldAutoPersistCapture(receivedImageUri, draftId, workflowInProgress)) {
            persistSourceAndStartCorrection()
        }
    }

    LaunchedEffect(replacementCandidateUri) {
        if (replacementCandidateUri != null && replacementError == null) {
            replaceDraftWithCandidate()
        }
    }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                pruneOwnedCaptureCache(
                    context = context,
                    retainedUris = listOfNotNull(pendingCameraUri, receivedImageUri),
                )
            }
        } finally {
            initialCachePrune.complete(Unit)
        }
    }

    LaunchedEffect(resumeDraftId) {
        val requestedDraftId = resumeDraftId ?: return@LaunchedEffect
        if (draftId == requestedDraftId && receivedImageUri != null) {
            resumeLoadStateName = CaptureResumeLoadState.READY.name
            return@LaunchedEffect
        }
        resumeLoadStateName = CaptureResumeLoadState.LOADING.name
        val resumable = try {
            withContext(Dispatchers.IO) {
                repository.readPendingCapture(requestedDraftId)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            resumeLoadStateName = CaptureResumeLoadState.SOURCE_UNAVAILABLE.name
            return@LaunchedEffect
        }
        if (resumable == null) {
            resumeLoadStateName = CaptureResumeLoadState.MISSING.name
            return@LaunchedEffect
        }
        resumable.tutorSessionId?.let { sessionId ->
            resumeLoadStateName = CaptureResumeLoadState.REDIRECTING.name
            onTutorSessionReady(sessionId, null)
            return@LaunchedEffect
        }

        activeEntryOriginName = resumable.origin.name
        receivedImageUri = resumable.sourceImageUri
        receivedInputSource = null
        importRequestId = null
        importOccurredAtEpochMillis = resumable.draftCreatedAtEpochMillis
        commitOutcomeUnknown = false
        draftId = resumable.draftId
        draftRevisionNumber = resumable.currentRevisionNumber
        canonicalSha256 = resumable.sourceAssetSha256
        sourcePages = resumable.sourcePages
        sourcePageAssessmentSnapshots = resumable.sourcePageAssessmentTasks
        selectedSourcePageIndex = 0
        committedEntryId = null
        selectedSubject = resumable.subject.orEmpty()
        correctedTitle = resumable.title
        titleEditedByUser = false
        correctedTranscription = resumable.transcription
        writingLayerName = resumable.writingLayer.name
        recognitionStateName = if (resumable.transcription.isBlank()) {
            CaptureRecognitionState.NOT_ATTEMPTED.name
        } else {
            CaptureRecognitionState.CANDIDATE_AVAILABLE.name
        }
        recognitionConfidence = resumable.questionDocument.blockEvidence
            .mapNotNull { evidence -> evidence.confidence }
            .takeIf { values -> values.isNotEmpty() }
            ?.average()
        recognitionBlockCount = resumable.questionDocument.document.blocks.size
        val pendingAssessmentPageIndex = resumable.sourcePageAssessmentTasks
            .indexOfFirst { task -> task?.status != ModelTaskStatus.SUCCEEDED }
            .takeIf { it >= 0 }
            ?: resumable.sourcePages.lastIndex
        val pendingAssessmentPage = resumable.sourcePages[pendingAssessmentPageIndex]
        val pendingAssessmentTask = resumable.sourcePageAssessmentTasks[pendingAssessmentPageIndex]
        assessmentSnapshot = pendingAssessmentTask
        assessmentRequestId = pendingAssessmentTask?.request?.requestId
            ?: resumeAssessmentRequestId(resumable.draftId, pendingAssessmentPageIndex)
        assessmentSourceAssetId = pendingAssessmentPage.sourceAssetId
        assessmentOccurredAtEpochMillis = pendingAssessmentTask
            ?.request
            ?.occurredAtEpochMillis
            ?: pendingAssessmentPage.createdAtEpochMillis
        assessmentRetryNonce = 0
        parseSnapshot = resumable.latestParseTask
        parseRequestId = resumable.latestParseTask?.request?.requestId
            ?: resumeParseRequestId(
                draftId = resumable.draftId,
                revisionNumber = resumable.currentRevisionNumber,
            )
        parseRetryNonce = 0
        transcriptionEditedByUser = false
        captureError = null
        applyWorkspace(restoreCaptureWorkspace(resumable))
        resumeLoadStateName = CaptureResumeLoadState.READY.name
    }

    LaunchedEffect(draftId, workspaceHydratedDraftId) {
        val currentDraftId = draftId ?: return@LaunchedEffect
        if (workspaceHydratedDraftId == currentDraftId) return@LaunchedEffect
        val resumable = runCatching {
            withContext(Dispatchers.IO) { repository.readPendingCapture(currentDraftId) }
        }.getOrNull() ?: return@LaunchedEffect
        applyWorkspace(restoreCaptureWorkspace(resumable))
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

    LaunchedEffect(assessmentRequestId) {
        val requestId = assessmentRequestId ?: return@LaunchedEffect
        modelTasks.observe(requestId).collect { snapshot ->
            assessmentSnapshot = snapshot
            if (snapshot?.status in setOf(
                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                )
            ) {
                activeCaptureAuthorizationId = null
            }
            val assessedAssetId = (snapshot?.request?.input as? CaptureAssessmentInput)
                ?.sourceAssetId
                ?: return@collect
            val pageIndex = sourcePages.indexOfFirst { it.sourceAssetId == assessedAssetId }
            if (pageIndex >= 0) {
                sourcePageAssessmentSnapshots = sourcePageAssessmentSnapshots.mapIndexed {
                        index,
                        existing,
                    ->
                    if (index == pageIndex) snapshot else existing
                }
            }
        }
    }

    LaunchedEffect(
        assessmentSnapshot?.stateVersion,
        splitRetryNonce,
        draftId,
        draftRevisionNumber,
        sourcePages,
    ) {
        val snapshot = assessmentSnapshot ?: return@LaunchedEffect
        val assessment = (snapshot.output as? CaptureAssessmentOutput)
            ?.assessment
            ?: return@LaunchedEffect
        if (
            snapshot.status != ModelTaskStatus.SUCCEEDED ||
            snapshot.provider?.isDemo != false ||
            assessment.decision != CaptureAssessmentDecision.SPLIT
        ) {
            return@LaunchedEffect
        }
        val currentDraftId = draftId ?: return@LaunchedEffect
        val revisionNumber = draftRevisionNumber ?: return@LaunchedEffect
        val page = sourcePages.singleOrNull() ?: run {
            splitError = "这页暂时不能自动整理，原图已经保留。"
            return@LaunchedEffect
        }
        val assessmentInput = snapshot.request.input as? CaptureAssessmentInput
            ?: return@LaunchedEffect
        if (assessmentInput.sourceAssetId != page.sourceAssetId) return@LaunchedEffect

        workflowInProgress = true
        splitError = null
        try {
            withContext(Dispatchers.IO) {
                repository.splitDraft(
                    SplitCaptureDraftRequest(
                        requestId = "capture-split:${snapshot.request.requestId}",
                        draftId = currentDraftId,
                        expectedRevisionNumber = revisionNumber,
                        assessmentRequestId = snapshot.request.requestId,
                        sourceAssetId = page.sourceAssetId,
                        regions = assessment.questionRegions,
                        occurredAtEpochMillis = snapshot.updatedAtEpochMillis,
                    ),
                )
            }
            resetDraftState()
            onSplitReady()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            splitError = "这页还没整理好，原图已经保留。"
        } finally {
            workflowInProgress = false
        }
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
        val informedIntent = freshCaptureEgressIntent ?: return@LaunchedEffect
        val provider = providerCapabilities ?: return@LaunchedEffect
        val currentDraftId = draftId
        if (
            currentDraftId == null ||
            !informedIntent.matches(
                provider = provider,
                draftId = currentDraftId,
                sourcePages = sourcePages,
                nowEpochMillis = System.currentTimeMillis(),
            )
        ) {
            freshCaptureEgressIntent = null
            return@LaunchedEffect
        }
        val approvedManifest = approveCaptureEgress(provider)
        initialTutorPlanCaptureAuthorizationId = approvedManifest
            ?.authorizationId
            ?.takeIf { informedIntent.authorizesInitialTutorPlan }
        freshCaptureEgressIntent = null
    }

    LaunchedEffect(
        assessmentRequestId,
        assessmentSourceAssetId,
        assessmentOccurredAtEpochMillis,
        assessmentRetryNonce,
        providerCapabilities,
        captureEgressManifest?.authorizationId,
    ) {
        val provider = providerCapabilities ?: return@LaunchedEffect
        val requestId = assessmentRequestId ?: return@LaunchedEffect
        val sourceAssetId = assessmentSourceAssetId ?: return@LaunchedEffect
        val currentDraftId = draftId ?: return@LaunchedEffect
        val assessedPage = sourcePages.singleOrNull { it.sourceAssetId == sourceAssetId }
            ?: return@LaunchedEffect
        val width = assessedPage.width
        val height = assessedPage.height
        val occurredAt = assessmentOccurredAtEpochMillis ?: return@LaunchedEffect
        val request = pendingAssessmentRecoveryRequest?.takeIf { pending ->
            pending.requestId == requestId
        } ?: assessmentSnapshot?.request?.takeIf { persisted ->
            persisted.requestId == requestId
        } ?: ModelTaskRequest(
            requestId = requestId,
            input = CaptureAssessmentInput(
                draftId = currentDraftId,
                sourceAssetId = sourceAssetId,
                origin = activeEntryOrigin.toAssessmentOrigin(),
                imageWidth = width,
                imageHeight = height,
            ),
            occurredAtEpochMillis = occurredAt,
            egressManifest = captureEgressManifest,
        )
        if (
            !captureExecutionLaunchGuard.claim(
                request = request,
                snapshot = assessmentSnapshot,
                provider = provider,
                manifest = captureEgressManifest,
                activeAuthorizationId = activeCaptureAuthorizationId,
                nowEpochMillis = System.currentTimeMillis(),
            )
        ) {
            return@LaunchedEffect
        }
        modelTasks.execute(request).collect {
            pendingAssessmentRecoveryRequest = null
            assessmentSnapshot = it
            if (it.status in setOf(
                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                )
            ) {
                activeCaptureAuthorizationId = null
            }
        }
    }

    LaunchedEffect(parseRequestId) {
        val requestId = parseRequestId ?: return@LaunchedEffect
        modelTasks.observe(requestId).collect { snapshot ->
            parseSnapshot = snapshot
            if (snapshot?.status in setOf(
                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                )
            ) {
                activeCaptureAuthorizationId = null
            }
        }
    }

    LaunchedEffect(
        parseRequestId,
        sourcePageAssessmentSnapshots.map { it?.stateVersion },
        sourcePages,
        parseRetryNonce,
        providerCapabilities,
        captureEgressManifest?.authorizationId,
    ) {
        val provider = providerCapabilities ?: return@LaunchedEffect
        val requestId = parseRequestId ?: return@LaunchedEffect
        val currentDraftId = draftId ?: return@LaunchedEffect
        val basisRevision = draftRevisionNumber ?: return@LaunchedEffect
        if (sourcePages.isEmpty() || sourcePageAssessmentSnapshots.size != sourcePages.size) {
            return@LaunchedEffect
        }
        val assessments = sourcePageAssessmentSnapshots.map { it ?: return@LaunchedEffect }
        if (assessments.withIndex().any { (pageIndex, snapshot) ->
                val decision = (snapshot.output as? CaptureAssessmentOutput)?.assessment?.decision
                snapshot.status != ModelTaskStatus.SUCCEEDED ||
                    (decision != CaptureAssessmentDecision.PASS &&
                        !(decision == CaptureAssessmentDecision.NEED_MORE_IMAGE &&
                            pageIndex < sourcePages.lastIndex))
            }
        ) return@LaunchedEffect
        val assessmentRequestIds = assessments.map { it.request.requestId }
        val occurredAt = assessments.maxOf { it.request.occurredAtEpochMillis }
        val request = pendingParseRecoveryRequest?.takeIf { pending ->
            pending.requestId == requestId
        } ?: parseSnapshot?.request?.takeIf { persisted ->
            persisted.requestId == requestId
        } ?: ModelTaskRequest(
            requestId = requestId,
            input = CaptureParseInput(
                draftId = currentDraftId,
                origin = activeEntryOrigin.toAssessmentOrigin(),
                basisRevisionNumber = basisRevision,
                sourceAssets = sourcePages.map { page ->
                    CaptureSourceAssetRef(
                        assetId = page.sourceAssetId,
                        sha256 = page.sourceAssetSha256,
                        width = page.width,
                        height = page.height,
                        pageIndex = page.pageIndex,
                    )
                },
                assessmentRequestId = assessmentRequestIds.first(),
                assessmentRequestIds = assessmentRequestIds,
            ),
            occurredAtEpochMillis = occurredAt,
            egressManifest = captureEgressManifest,
        )
        if (
            !captureExecutionLaunchGuard.claim(
                request = request,
                snapshot = parseSnapshot,
                provider = provider,
                manifest = captureEgressManifest,
                activeAuthorizationId = activeCaptureAuthorizationId,
                nowEpochMillis = System.currentTimeMillis(),
            )
        ) {
            return@LaunchedEffect
        }
        modelTasks.execute(request).collect {
            pendingParseRecoveryRequest = null
            parseSnapshot = it
            if (it.status in setOf(
                    ModelTaskStatus.RETRYABLE_FAILURE,
                    ModelTaskStatus.PERMANENT_FAILURE,
                    ModelTaskStatus.CANCELLED,
                )
            ) {
                activeCaptureAuthorizationId = null
            }
        }
    }

    LaunchedEffect(parseSnapshot?.stateVersion) {
        val output = realParseOutput ?: return@LaunchedEffect
        workspaceState?.let { current ->
            val adopted = current.adoptModelCandidateIfPristine(output.capturedDocument)
            if (adopted != current) {
                workspaceState = adopted
                workspaceChangeVersion += 1
                correctedTranscription = adopted.transcription
                correctedTitle = adopted.title.ifBlank { suggestCaptureTitle(adopted.transcription) }
            }
            return@LaunchedEffect
        }
        if (!transcriptionEditedByUser && structuredProjection.isNotBlank()) {
            correctedTranscription = structuredProjection
            if (!titleEditedByUser) {
                correctedTitle = output.capturedDocument.document.title
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.take(MAX_CAPTURE_TITLE_CHARS)
                    ?: suggestCaptureTitle(structuredProjection)
            }
        }
    }

    DisposableEffect(context) {
        onDispose {
            if (context.findActivity()?.isChangingConfigurations != true) {
                val ownedUris = buildList {
                    latestPendingCameraUri?.let(::add)
                    latestReceivedImageUri?.let(::add)
                    latestReplacementCandidateUri?.let(::add)
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
                                    when (recoveryRequest.input.kind) {
                                        ModelTaskKind.CAPTURE_ASSESS -> {
                                            val sourceAssetId =
                                                (recoveryRequest.input as CaptureAssessmentInput)
                                                    .sourceAssetId
                                            pendingAssessmentRecoveryRequest = recoveryRequest
                                            assessmentRequestId = recoveryRequest.requestId
                                            assessmentSourceAssetId = sourceAssetId
                                            assessmentOccurredAtEpochMillis =
                                                recoveryRequest.occurredAtEpochMillis
                                            assessmentRetryNonce = 0
                                            assessmentSnapshot = null
                                            sourcePageAssessmentSnapshots =
                                                sourcePageAssessmentSnapshots.mapIndexed {
                                                        index,
                                                        existing,
                                                    ->
                                                    if (
                                                        sourcePages.getOrNull(index)?.sourceAssetId ==
                                                        sourceAssetId
                                                    ) {
                                                        null
                                                    } else {
                                                        existing
                                                    }
                                                }
                                        }
                                        ModelTaskKind.CAPTURE_PARSE -> {
                                            pendingParseRecoveryRequest = recoveryRequest
                                            parseRequestId = recoveryRequest.requestId
                                            parseRetryNonce = 0
                                            parseSnapshot = null
                                        }
                                        else -> error("Unexpected capture recovery task")
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

@Composable
private fun WorkspaceSaveErrorCard(
    message: String,
    saving: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ErrorWarm, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("capture_workspace_save_error"),
    ) {
        Text(message, color = ErrorWarm, style = MaterialTheme.typography.bodySmall)
        OutlineActionChip(
            text = if (saving) "正在重试…" else "重新保存",
            onClick = onRetry,
            enabled = !saving,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("capture_workspace_save_retry"),
        )
    }
}

@Composable
private fun ReplacementStatusCard(
    isReplacing: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onKeepCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (error == null) Outline else ErrorWarm, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag("capture_replacement_status"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isReplacing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = JadeActive,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = ErrorWarm,
                )
            }
            Text(
                text = if (isReplacing) "正在安全替换题图" else error.orEmpty(),
                modifier = Modifier.padding(start = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!isReplacing && error != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionChip(
                    text = "重试替换",
                    onClick = onRetry,
                    modifier = Modifier.testTag("capture_replacement_retry"),
                )
                OutlineActionChip(
                    text = "保留原题",
                    onClick = onKeepCurrent,
                    modifier = Modifier.testTag("capture_replacement_keep"),
                )
            }
        }
    }
}

@Composable
private fun CaptureTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("capture_back_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Ink,
            )
        }
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            color = Ink,
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
private fun CaptureSourcePageBar(
    pages: List<CaptureSourcePage>,
    selectedPageIndex: Int,
    enabled: Boolean,
    onSelectPage: (Int) -> Unit,
    onAddByCamera: () -> Unit,
    onAddFromPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "本题原图 · ${pages.size} 页",
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEach { page ->
                FilterChip(
                    selected = page.pageIndex == selectedPageIndex,
                    onClick = { onSelectPage(page.pageIndex) },
                    label = { Text("第 ${page.pageIndex + 1} 页") },
                    modifier = Modifier.testTag("capture_source_page_${page.pageIndex}"),
                )
            }
            IconButton(
                onClick = onAddByCamera,
                enabled = enabled,
                modifier = Modifier.testTag("capture_add_page_camera"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = "拍照补充本题下一页",
                    tint = JadeActive,
                )
            }
            IconButton(
                onClick = onAddFromPhotos,
                enabled = enabled,
                modifier = Modifier.testTag("capture_add_page_photos"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = "从照片补充本题下一页",
                    tint = JadeActive,
                )
            }
        }
        Text(
            text = "跨页题按顺序补拍；每一页原图都会保留。",
            modifier = Modifier.padding(top = 4.dp),
            color = InkMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun CaptureActions(
    provider: ProviderCapabilitySnapshot?,
    entryOrigin: CaptureEntryOrigin = CaptureEntryOrigin.LIBRARY,
    onTakePicture: () -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PrimaryActionButton(
            text = "拍照并整理",
            onClick = onTakePicture,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("capture_take_picture_button"),
            icon = Icons.Outlined.PhotoCamera,
            contentDescription = "使用系统相机拍摄题目并整理",
        )
        OutlineActionChip(
            text = "选图并整理",
            onClick = onPickPhoto,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .testTag("capture_pick_photo_button"),
            icon = Icons.Outlined.PhotoLibrary,
            contentDescription = "使用系统照片选择器选择题目图片并整理",
        )
        Text(
            text = captureInitialEgressDisclosure(provider, entryOrigin),
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .testTag("capture_initial_egress_disclosure"),
            color = InkMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CaptureGuidance(modifier: Modifier = Modifier) {
    Text(
        text = "尽量把题干、选项和配图拍完整。",
        modifier = modifier.fillMaxWidth(),
        color = InkSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun CaptureEntryOrigin.toAssessmentOrigin(): CaptureAssessmentOrigin = when (this) {
    CaptureEntryOrigin.TUTOR -> CaptureAssessmentOrigin.TUTOR
    CaptureEntryOrigin.LIBRARY -> CaptureAssessmentOrigin.LIBRARY
}

internal fun resumeAssessmentRequestId(draftId: String, pageIndex: Int = 0): String =
    if (pageIndex == 0) {
        "capture-assess:resume:$draftId"
    } else {
        "capture-assess:resume:$draftId:p$pageIndex"
    }

internal fun resumeParseRequestId(draftId: String, revisionNumber: Int): String =
    "capture-parse:resume:$draftId:r$revisionNumber"

internal fun suggestCaptureTitle(candidateText: String): String {
    val firstMeaningfulLine = candidateText
        .lineSequence()
        .map { line -> line.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull(String::isNotBlank)
        .orEmpty()
    if (firstMeaningfulLine.isBlank()) return "新拍题目"
    return if (firstMeaningfulLine.length <= MAX_CAPTURE_TITLE_CHARS) {
        firstMeaningfulLine
    } else {
        firstMeaningfulLine.take(MAX_CAPTURE_TITLE_CHARS - 1).trimEnd() + "…"
    }
}

private const val MAX_CAPTURE_TITLE_CHARS = 24
private const val MAX_CAPTURE_SOURCE_PAGES = 8

internal fun shouldAutoPersistCapture(
    receivedImageUri: String?,
    draftId: String?,
    workflowInProgress: Boolean,
): Boolean = receivedImageUri != null && draftId == null && !workflowInProgress

@Composable
private fun AwaitingCorrectionCard(
    onRetake: () -> Unit,
    onPickAnother: () -> Unit,
    onStartCorrection: () -> Unit,
    isImporting: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ErrorWarm, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(16.dp)
            .testTag("capture_pending_correction"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = ErrorWarm,
            )
            Text(
                text = if (isImporting) "正在保存题图" else "题图待重试",
                modifier = Modifier.padding(start = 10.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = if (isImporting) {
                "图片已收到，正在自动保存到本机；保存完成后会继续整理题面。"
            } else {
                "这张图还没有安全保存，已暂停后续处理。请重试，或重新拍摄。"
            },
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlineActionChip(
                text = "重新拍摄",
                onClick = onRetake,
                modifier = Modifier
                    .weight(1f)
                    .testTag("capture_retake_button"),
                icon = Icons.Outlined.PhotoCamera,
                enabled = !isImporting,
            )
            OutlineActionChip(
                text = "另选照片",
                onClick = onPickAnother,
                modifier = Modifier
                    .weight(1f)
                    .testTag("capture_pick_another_button"),
                icon = Icons.Outlined.PhotoLibrary,
                enabled = !isImporting,
            )
        }
        PrimaryActionButton(
            text = if (isImporting) "正在安全保存…" else "重试保存并继续",
            onClick = onStartCorrection,
            enabled = !isImporting,
            icon = Icons.Outlined.Save,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .testTag("capture_start_correction_button"),
        )
    }
}

@Composable
private fun CaptureCommittedCard(
    onView: () -> Unit,
    onCaptureAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JadeSoft.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(16.dp)
            .testTag("capture_committed"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = JadeActive,
            )
            Text(
                text = "已经存入错题本",
                modifier = Modifier.padding(start = 8.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = "原图和整理后的题面都已保存。现在可以查看这道题，或继续录入下一道。",
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        PrimaryActionButton(
            text = "查看这道题",
            onClick = onView,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .testTag("capture_view_saved_item"),
        )
        OutlineActionChip(
            text = "再录一道",
            onClick = onCaptureAnother,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("capture_another_item"),
        )
    }
}

@Composable
private fun CaptureError(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ErrorWarm, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("capture_error"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = ErrorWarm,
        )
        Text(
            text = message,
            modifier = Modifier.padding(start = 8.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CaptureResumeStateCard(
    state: CaptureResumeLoadState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = state == CaptureResumeLoadState.LOADING ||
        state == CaptureResumeLoadState.REDIRECTING
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isBusy) Outline else ErrorWarm,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(16.dp)
            .testTag("capture_resume_${state.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = JadeActive,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ErrorWarm,
                )
            }
            Text(
                text = when (state) {
                    CaptureResumeLoadState.LOADING -> "正在恢复题目…"
                    CaptureResumeLoadState.REDIRECTING -> "正在打开讲题会话…"
                    CaptureResumeLoadState.MISSING -> "这道题已处理或不存在"
                    CaptureResumeLoadState.SOURCE_UNAVAILABLE -> "原图暂时无法打开"
                    CaptureResumeLoadState.NOT_REQUESTED,
                    CaptureResumeLoadState.READY,
                    -> "正在恢复题目…"
                },
                modifier = Modifier.padding(start = 10.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = when (state) {
                CaptureResumeLoadState.LOADING ->
                    "正在读取本机保存的原图、题面和上次处理状态。"
                CaptureResumeLoadState.REDIRECTING ->
                    "这道题已经准备好讲解，将回到原来的临时会话。"
                CaptureResumeLoadState.MISSING ->
                    "它可能已经存入错题本，返回后列表会自动更新。"
                CaptureResumeLoadState.SOURCE_UNAVAILABLE ->
                    "原图暂时无法打开，请返回后重新拍摄。"
                CaptureResumeLoadState.NOT_REQUESTED,
                CaptureResumeLoadState.READY,
                -> "正在读取本机保存的题目。"
            },
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!isBusy) {
            OutlineActionChip(
                text = "返回待处理题目",
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capture_resume_back_to_inbox"),
            )
        }
    }
}
