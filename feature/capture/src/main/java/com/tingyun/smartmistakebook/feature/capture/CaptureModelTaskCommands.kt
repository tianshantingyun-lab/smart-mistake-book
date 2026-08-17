package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal class CaptureModelTaskCommands(
    private val repository: CaptureWorkflowRepository,
    private val modelTasks: ModelTaskRepository,
    private val coordinator: CaptureModelTaskCoordinator,
    private val sink: CaptureModelTaskSink,
) {
    suspend fun observeAssessment(requestId: String) {
        modelTasks.observe(requestId).collect { snapshot ->
            sink.setAssessmentSnapshot(snapshot)
            if (captureFailedTaskClearsAuthorization(snapshot?.status)) {
                sink.clearActiveAuthorization()
            }
            sink.setPageAssessmentSnapshots(
                captureUpdatedPageAssessmentSnapshots(
                    sourcePages = sink.sourcePages(),
                    existing = sink.pageAssessmentSnapshots(),
                    snapshot = snapshot,
                ),
            )
        }
    }

    suspend fun maybeSplit() {
        when (
            val decision = captureSplitDecision(
                snapshot = sink.assessmentSnapshot(),
                draftId = sink.draftId(),
                revisionNumber = sink.revisionNumber(),
                sourcePages = sink.sourcePages(),
            )
        ) {
            CaptureSplitDecision.Skip -> return
            CaptureSplitDecision.NeedSinglePage -> {
                sink.setSplitError(CAPTURE_SPLIT_NEEDS_SINGLE_PAGE)
                return
            }
            is CaptureSplitDecision.Run -> {
                sink.setWorkflowInProgress(true)
                sink.setSplitError(null)
                try {
                    withContext(Dispatchers.IO) {
                        repository.splitDraft(captureSplitDraftRequest(decision))
                    }
                    sink.resetDraft()
                    sink.onSplitReady()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    sink.setSplitError(CAPTURE_SPLIT_FAILED)
                } finally {
                    sink.setWorkflowInProgress(false)
                }
            }
        }
    }

    fun bindFreshEgress(
        informedIntent: CaptureDraftEgressIntent?,
        provider: ProviderCapabilitySnapshot?,
        draftId: String?,
        sourcePages: List<CaptureSourcePage>,
        nowEpochMillis: Long,
    ) {
        if (informedIntent == null || provider == null || draftId == null ||
            !informedIntent.matches(
                provider = provider,
                draftId = draftId,
                sourcePages = sourcePages,
                nowEpochMillis = nowEpochMillis,
            )
        ) {
            sink.clearFreshEgressIntent()
            return
        }
        val approvedManifest = sink.approveCaptureEgress(provider)
        sink.setInitialTutorPlanAuthorizationId(
            approvedManifest?.authorizationId?.takeIf { informedIntent.authorizesInitialTutorPlan },
        )
        sink.clearFreshEgressIntent()
    }

    fun dispatchAssessment(
        provider: ProviderCapabilitySnapshot?,
        requestId: String?,
        sourceAssetId: String?,
        draftId: String?,
        occurredAt: Long?,
        manifest: ModelEgressManifest?,
        activeAuthorizationId: String?,
    ) {
        val currentProvider = provider ?: return
        val currentRequestId = requestId ?: return
        val currentAssetId = sourceAssetId ?: return
        val currentDraftId = draftId ?: return
        val assessedPage = sink.sourcePages().singleOrNull { it.sourceAssetId == currentAssetId }
            ?: return
        val currentOccurredAt = occurredAt ?: return
        val request = captureTaskRequestToDispatch(
            requestId = currentRequestId,
            pendingRecoveryRequest = sink.pendingAssessmentRecoveryRequest(),
            persistedRequest = sink.assessmentSnapshot()?.request,
        ) {
            sink.buildAssessmentRequest(
                currentRequestId,
                currentDraftId,
                currentAssetId,
                assessedPage.width,
                assessedPage.height,
                currentOccurredAt,
                manifest,
            )
        }
        coordinator.executeAssessment(
            request = request,
            provider = currentProvider,
            manifest = manifest,
            activeAuthorizationId = activeAuthorizationId,
        ) { snapshot ->
            sink.clearPendingAssessmentRecovery()
            sink.setAssessmentSnapshot(snapshot)
            if (captureFailedTaskClearsAuthorization(snapshot.status)) {
                sink.clearActiveAuthorization()
            }
        }
    }

    suspend fun observeParse(requestId: String) {
        modelTasks.observe(requestId).collect { snapshot ->
            sink.setParseSnapshot(snapshot)
            if (captureFailedTaskClearsAuthorization(snapshot?.status)) {
                sink.clearActiveAuthorization()
            }
        }
    }

    fun dispatchParse(
        provider: ProviderCapabilitySnapshot?,
        requestId: String?,
        draftId: String?,
        basisRevision: Int?,
        manifest: ModelEgressManifest?,
        activeAuthorizationId: String?,
    ) {
        val currentProvider = provider ?: return
        val currentRequestId = requestId ?: return
        val currentDraftId = draftId ?: return
        val currentRevision = basisRevision ?: return
        val readiness = captureParseReadiness(sink.sourcePages(), sink.pageAssessmentSnapshots())
            ?: return
        val request = captureTaskRequestToDispatch(
            requestId = currentRequestId,
            pendingRecoveryRequest = sink.pendingParseRecoveryRequest(),
            persistedRequest = sink.parseSnapshot()?.request,
        ) {
            sink.buildParseRequest(
                currentRequestId,
                currentDraftId,
                currentRevision,
                sink.sourcePages(),
                readiness.assessmentRequestIds,
                readiness.occurredAtEpochMillis,
                manifest,
            )
        }
        coordinator.executeParse(
            request = request,
            provider = currentProvider,
            manifest = manifest,
            activeAuthorizationId = activeAuthorizationId,
        ) { snapshot ->
            sink.clearPendingParseRecovery()
            sink.setParseSnapshot(snapshot)
            if (captureFailedTaskClearsAuthorization(snapshot.status)) {
                sink.clearActiveAuthorization()
            }
        }
    }

    fun adoptParseOutput(output: CaptureParseOutput?) {
        if (output == null) return
        val currentWorkspace = sink.workspace()
        if (currentWorkspace != null) {
            val adopted = currentWorkspace.adoptModelCandidateIfPristine(output.capturedDocument)
            if (adopted != currentWorkspace) {
                sink.replaceWorkspace(adopted)
            }
            return
        }
        val adoptedText = captureParseTextAdoption(
            transcriptionEditedByUser = sink.transcriptionEditedByUser(),
            titleEditedByUser = sink.titleEditedByUser(),
            structuredProjection = sink.structuredProjection(),
            documentTitle = output.capturedDocument.document.title,
        ) ?: return
        sink.applyAdoptedText(adoptedText)
    }
}

internal class CaptureModelTaskSink(
    val sourcePages: () -> List<CaptureSourcePage>,
    val pageAssessmentSnapshots: () -> List<ModelTaskSnapshot?>,
    val assessmentSnapshot: () -> ModelTaskSnapshot?,
    val parseSnapshot: () -> ModelTaskSnapshot?,
    val draftId: () -> String?,
    val revisionNumber: () -> Int?,
    val workspace: () -> CaptureWorkspaceUiState?,
    val pendingAssessmentRecoveryRequest: () -> ModelTaskRequest?,
    val pendingParseRecoveryRequest: () -> ModelTaskRequest?,
    val transcriptionEditedByUser: () -> Boolean,
    val titleEditedByUser: () -> Boolean,
    val structuredProjection: () -> String,
    val setAssessmentSnapshot: (ModelTaskSnapshot?) -> Unit,
    val setParseSnapshot: (ModelTaskSnapshot?) -> Unit,
    val setPageAssessmentSnapshots: (List<ModelTaskSnapshot?>) -> Unit,
    val clearActiveAuthorization: () -> Unit,
    val setSplitError: (String?) -> Unit,
    val setWorkflowInProgress: (Boolean) -> Unit,
    val resetDraft: () -> Unit,
    val onSplitReady: () -> Unit,
    val clearFreshEgressIntent: () -> Unit,
    val approveCaptureEgress: (ProviderCapabilitySnapshot) -> ModelEgressManifest?,
    val setInitialTutorPlanAuthorizationId: (String?) -> Unit,
    val clearPendingAssessmentRecovery: () -> Unit,
    val clearPendingParseRecovery: () -> Unit,
    val replaceWorkspace: (CaptureWorkspaceUiState) -> Unit,
    val applyAdoptedText: (CaptureParseTextAdoption) -> Unit,
    val buildAssessmentRequest: (
        String, String, String, Int, Int, Long, ModelEgressManifest?,
    ) -> ModelTaskRequest,
    val buildParseRequest: (
        String, String, Int, List<CaptureSourcePage>, List<String>, Long, ModelEgressManifest?,
    ) -> ModelTaskRequest,
)
