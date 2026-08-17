package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.util.UUID

internal class CaptureDraftStateCommands(
    private val sink: CaptureDraftStateSink,
) {
    fun clearEgressApproval() {
        sink.clearEgressApproval()
    }

    fun approveEgress(provider: ProviderCapabilitySnapshot): ModelEgressManifest? {
        val currentDraftId = sink.draftId() ?: return null
        val pages = sink.sourcePages()
        if (pages.isEmpty()) return null
        val authorizationId = UUID.randomUUID().toString()
        val approvedAt = System.currentTimeMillis()
        val manifest = buildCaptureEgressManifest(
            authorizationId = authorizationId,
            draftId = currentDraftId,
            provider = provider,
            sourcePages = pages,
            approvedAtEpochMillis = approvedAt,
        )
        sink.rememberEgressApproval(manifest, provider)
        return manifest
    }

    fun retryAssessment() {
        sink.clearPendingAssessmentRecovery()
        val retry = nextCaptureTaskRetry(sink.assessmentSnapshot()?.status)
        if (retry.replaceRequestId) {
            sink.replaceAssessmentRequestId("capture-assess:${UUID.randomUUID()}")
        }
        if (retry.clearSnapshot) {
            sink.clearAssessmentSnapshotForActivePage()
        }
        if (retry.incrementNonce) sink.incrementAssessmentRetryNonce()
    }

    fun retryParse() {
        sink.clearPendingParseRecovery()
        val retry = nextCaptureTaskRetry(sink.parseSnapshot()?.status)
        if (retry.replaceRequestId) {
            sink.replaceParseRequestId("capture-parse:${UUID.randomUUID()}")
        }
        if (retry.clearSnapshot) sink.clearParseSnapshot()
        if (retry.incrementNonce) sink.incrementParseRetryNonce()
    }

    fun resetDraft() {
        sink.resetDraftFields()
        clearEgressApproval()
        sink.clearWorkspace()
    }

    fun applyWorkspace(restored: CaptureWorkspaceLocalSnapshot) {
        val effectiveState = sink.parseOutput()?.capturedDocument?.let {
            restored.state.adoptModelCandidateIfPristine(it)
        } ?: restored.state
        sink.applyWorkspaceSnapshot(restored.copy(state = effectiveState))
    }

    fun updateWorkspace(transform: (CaptureWorkspaceUiState) -> CaptureWorkspaceUiState) {
        val current = sink.workspace() ?: return
        val updated = transform(current)
        if (updated == current) return
        sink.replaceWorkspace(updated)
    }

    fun applyDraftSummary(
        draft: CaptureDraftSummary,
        requestId: String,
        occurredAtEpochMillis: Long,
        sourceEgressIntent: CaptureSourceEgressIntent? = null,
    ) {
        val imported = captureImportedNewModelTasks(
            requestId = requestId,
            sourceAssetId = draft.sourceAssetId,
            pageCount = draft.sourcePages.size,
        )
        sink.applyImportedSummary(draft, imported, occurredAtEpochMillis)
        clearEgressApproval()
        sink.bindImportedEgress(draft, sourceEgressIntent)
    }
}

internal class CaptureDraftStateSink(
    val draftId: () -> String?,
    val sourcePages: () -> List<com.tingyun.smartmistakebook.core.domain.CaptureSourcePage>,
    val assessmentSnapshot: () -> ModelTaskSnapshot?,
    val parseSnapshot: () -> ModelTaskSnapshot?,
    val parseOutput: () -> CaptureParseOutput?,
    val workspace: () -> CaptureWorkspaceUiState?,
    val clearEgressApproval: () -> Unit,
    val rememberEgressApproval: (ModelEgressManifest, ProviderCapabilitySnapshot) -> Unit,
    val clearPendingAssessmentRecovery: () -> Unit,
    val replaceAssessmentRequestId: (String) -> Unit,
    val clearAssessmentSnapshotForActivePage: () -> Unit,
    val incrementAssessmentRetryNonce: () -> Unit,
    val clearPendingParseRecovery: () -> Unit,
    val replaceParseRequestId: (String) -> Unit,
    val clearParseSnapshot: () -> Unit,
    val incrementParseRetryNonce: () -> Unit,
    val resetDraftFields: () -> Unit,
    val clearWorkspace: () -> Unit,
    val applyWorkspaceSnapshot: (CaptureWorkspaceLocalSnapshot) -> Unit,
    val replaceWorkspace: (CaptureWorkspaceUiState) -> Unit,
    val applyImportedSummary: (
        CaptureDraftSummary,
        CaptureImportedNewModelTasks,
        Long,
    ) -> Unit,
    val bindImportedEgress: (CaptureDraftSummary, CaptureSourceEgressIntent?) -> Unit,
)
