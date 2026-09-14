package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Model-task assessment/parse orchestration. Writes [CaptureScreenState]
 * directly; the split-ready hand-off, the structured-document projection and
 * the request builders (which depend on the screen's entry origin) are
 * injected so this class stays free of screen-derived values.
 */
internal class CaptureModelTaskCommands(
    private val repository: CaptureWorkflowRepository,
    private val modelTasks: ModelTaskRepository,
    private val coordinator: CaptureModelTaskCoordinator,
    private val state: CaptureScreenState,
    private val draftState: CaptureDraftStateCommands,
    private val onSplitReady: (String) -> Unit,
    private val structuredProjection: () -> String,
    private val buildAssessmentRequest: (
        String, String, String, Int, Int, Long, Boolean,
    ) -> ModelTaskRequest,
    private val buildParseRequest: (
        String, String, Int, List<CaptureSourcePage>, List<String>, Long, Boolean,
    ) -> ModelTaskRequest,
) {
    suspend fun observeAssessment(requestId: String) {
        modelTasks.observe(requestId).collect { snapshot ->
            state.assessmentSnapshot = snapshot
            state.sourcePageAssessmentSnapshots = captureUpdatedPageAssessmentSnapshots(
                sourcePages = state.sourcePages,
                existing = state.sourcePageAssessmentSnapshots,
                snapshot = snapshot,
            )
        }
    }

    suspend fun maybeSplit() {
        when (
            val decision = captureSplitDecision(
                snapshot = state.assessmentSnapshot,
                draftId = state.draftId,
                revisionNumber = state.draftRevisionNumber,
                sourcePages = state.sourcePages,
            )
        ) {
            CaptureSplitDecision.Skip -> return
            CaptureSplitDecision.NeedSinglePage -> {
                state.splitError = CAPTURE_SPLIT_NEEDS_SINGLE_PAGE
                return
            }
            is CaptureSplitDecision.Run -> {
                state.workflowInProgress = true
                state.splitError = null
                try {
                    val result = withContext(Dispatchers.IO) {
                        repository.splitDraft(captureSplitDraftRequest(decision))
                    }
                    draftState.resetDraft()
                    onSplitReady(result.splitJobId.orEmpty())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state.splitError = CAPTURE_SPLIT_FAILED
                } finally {
                    state.workflowInProgress = false
                }
            }
        }
    }

    /** 用户手动框选后的拆分：regions 来自框选，请求独立幂等键。 */
    suspend fun manualSplit(regions: List<NormalizedSourceRegion>) {
        val decision = captureSplitDecision(
            snapshot = state.assessmentSnapshot,
            draftId = state.draftId,
            revisionNumber = state.draftRevisionNumber,
            sourcePages = state.sourcePages,
        )
        if (decision !is CaptureSplitDecision.Run) return
        state.workflowInProgress = true
        state.splitError = null
        state.splitPendingChoice = false
        try {
            val request = captureManualSplitDraftRequest(
                decision = decision,
                regions = regions,
                nonce = ++state.manualSplitNonce,
            )
            val result = withContext(Dispatchers.IO) {
                repository.splitDraft(request)
            }
            draftState.resetDraft()
            onSplitReady(result.splitJobId.orEmpty())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.splitError = CAPTURE_SPLIT_FAILED
        } finally {
            state.workflowInProgress = false
        }
    }

    fun dispatchAssessment(
        provider: ProviderCapabilitySnapshot?,
        requestId: String?,
        sourceAssetId: String?,
        draftId: String?,
        occurredAt: Long?,
        egressAllowed: Boolean,
    ) {
        val currentProvider = provider ?: return
        val currentRequestId = requestId ?: return
        val currentAssetId = sourceAssetId ?: return
        val currentDraftId = draftId ?: return
        val assessedPage = state.sourcePages.singleOrNull { it.sourceAssetId == currentAssetId }
            ?: return
        val currentOccurredAt = occurredAt ?: return
        if (currentProvider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
            !egressAllowed
        ) {
            return
        }
        val request = captureTaskRequestToDispatch(
            requestId = currentRequestId,
            pendingRecoveryRequest = state.pendingAssessmentRecoveryRequest,
            persistedRequest = state.assessmentSnapshot?.request,
        ) {
            buildAssessmentRequest(
                currentRequestId,
                currentDraftId,
                currentAssetId,
                assessedPage.width,
                assessedPage.height,
                currentOccurredAt,
                egressAllowed,
            )
        }
        coordinator.executeAssessment(request) { snapshot ->
            state.pendingAssessmentRecoveryRequest = null
            state.assessmentSnapshot = snapshot
        }
    }

    suspend fun observeParse(requestId: String) {
        modelTasks.observe(requestId).collect { snapshot ->
            state.parseSnapshot = snapshot
        }
    }

    fun dispatchParse(
        provider: ProviderCapabilitySnapshot?,
        requestId: String?,
        draftId: String?,
        basisRevision: Int?,
        egressAllowed: Boolean,
    ) {
        val currentProvider = provider ?: return
        val currentRequestId = requestId ?: return
        val currentDraftId = draftId ?: return
        val currentRevision = basisRevision ?: return
        val readiness = captureParseReadiness(state.sourcePages, state.sourcePageAssessmentSnapshots)
            ?: return
        if (currentProvider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
            !egressAllowed
        ) {
            return
        }
        val request = captureTaskRequestToDispatch(
            requestId = currentRequestId,
            pendingRecoveryRequest = state.pendingParseRecoveryRequest,
            persistedRequest = state.parseSnapshot?.request,
        ) {
            buildParseRequest(
                currentRequestId,
                currentDraftId,
                currentRevision,
                state.sourcePages,
                readiness.assessmentRequestIds,
                readiness.occurredAtEpochMillis,
                egressAllowed,
            )
        }
        coordinator.executeParse(request) { snapshot ->
            state.pendingParseRecoveryRequest = null
            state.parseSnapshot = snapshot
        }
    }

    fun adoptParseOutput(output: CaptureParseOutput?) {
        if (output == null) return
        val currentWorkspace = state.workspaceState
        if (currentWorkspace != null) {
            val adopted = currentWorkspace.adoptModelCandidateIfPristine(output.capturedDocument)
            if (adopted != currentWorkspace) {
                replaceWorkspace(adopted)
            }
            return
        }
        val adoptedText = captureParseTextAdoption(
            transcriptionEditedByUser = state.transcriptionEditedByUser,
            titleEditedByUser = state.titleEditedByUser,
            structuredProjection = structuredProjection(),
            documentTitle = output.capturedDocument.document.title,
        ) ?: return
        state.correctedTranscription = adoptedText.transcription
        adoptedText.title?.let { state.correctedTitle = it }
    }

    private fun replaceWorkspace(adopted: CaptureWorkspaceUiState) {
        state.workspaceState = adopted
        state.workspaceChangeVersion += 1
        state.correctedTranscription = adopted.transcription
        state.correctedTitle = adopted.title.ifBlank {
            suggestCaptureTitle(adopted.transcription)
        }
    }
}
