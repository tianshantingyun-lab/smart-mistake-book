package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import java.util.UUID

/**
 * Draft-state orchestration for the capture screen. Operates directly on
 * [CaptureScreenState] (no sink indirection): every transition the state
 * holder cannot express as a single field assignment lives here as a method.
 */
internal class CaptureDraftStateCommands(
    private val state: CaptureScreenState,
) {
    fun retryAssessment() {
        state.pendingAssessmentRecoveryRequest = null
        val retry = nextCaptureTaskRetry(state.assessmentSnapshot?.status)
        if (retry.replaceRequestId) {
            state.assessmentRequestId = "capture-assess:${UUID.randomUUID()}"
            state.assessmentOccurredAtEpochMillis = System.currentTimeMillis()
        }
        if (retry.clearSnapshot) clearAssessmentSnapshotForActivePage()
        if (retry.incrementNonce) state.assessmentRetryNonce += 1
    }

    fun retryParse() {
        state.pendingParseRecoveryRequest = null
        val retry = nextCaptureTaskRetry(state.parseSnapshot?.status)
        if (retry.replaceRequestId) {
            state.parseRequestId = "capture-parse:${UUID.randomUUID()}"
        }
        if (retry.clearSnapshot) state.parseSnapshot = null
        if (retry.incrementNonce) state.parseRetryNonce += 1
    }

    fun resetDraft() {
        resetDraftFields()
        clearWorkspace()
    }

    fun applyWorkspace(restored: CaptureWorkspaceLocalSnapshot) {
        val effectiveState = parseOutput()?.capturedDocument?.let {
            restored.state.adoptModelCandidateIfPristine(it)
        } ?: restored.state
        state.workspaceState = effectiveState
        state.workspaceIdentity = restored.identity
        state.workspaceUpdatedAtEpochMillis = restored.updatedAtEpochMillis
        state.workspaceHydratedDraftId = effectiveState.draftId
        state.selectedSubject = effectiveState.subject
        state.correctedTitle = effectiveState.title
        state.correctedTranscription = effectiveState.transcription
        state.writingLayerName = effectiveState.captureWritingLayer().name
        state.titleEditedByUser =
            CaptureDraftEditedField.TITLE in effectiveState.userEditedFields
        state.transcriptionEditedByUser = effectiveState.userEditedFields.any {
            it == CaptureDraftEditedField.TRANSCRIPTION ||
                it == CaptureDraftEditedField.STRUCTURE
        }
        state.workspaceSaveError = null
    }

    fun updateWorkspace(transform: (CaptureWorkspaceUiState) -> CaptureWorkspaceUiState) {
        val current = state.workspaceState ?: return
        val updated = transform(current)
        if (updated == current) return
        state.workspaceState = updated
        state.workspaceChangeVersion += 1
        state.workspaceSaveError = null
        state.selectedSubject = updated.subject
        state.correctedTitle = updated.title
        state.correctedTranscription = updated.transcription
        state.writingLayerName = updated.captureWritingLayer().name
    }

    fun applyDraftSummary(
        draft: CaptureDraftSummary,
        requestId: String,
        occurredAtEpochMillis: Long,
    ) {
        val imported = captureImportedNewModelTasks(
            requestId = requestId,
            sourceAssetId = draft.sourceAssetId,
            pageCount = draft.sourcePages.size,
        )
        state.draftId = draft.draftId
        state.draftRevisionNumber = draft.revisionNumber
        state.canonicalSha256 = draft.sourceAssetSha256
        state.sourcePages = draft.sourcePages
        state.sourcePageAssessmentSnapshots = imported.pageSnapshots
        state.selectedSourcePageIndex = 0
        state.recognitionStateName = draft.recognition.state.name
        state.recognitionConfidence = draft.recognition.confidence
        state.recognitionBlockCount = draft.recognition.candidateBlockCount
        state.correctedTranscription = draft.recognition.candidateText
        state.correctedTitle = suggestCaptureTitle(draft.recognition.candidateText)
        state.titleEditedByUser = false
        state.assessmentRequestId = imported.assessmentRequestId
        state.assessmentSourceAssetId = imported.assessmentSourceAssetId
        state.assessmentOccurredAtEpochMillis = occurredAtEpochMillis
        state.parseRequestId = imported.parseRequestId
    }

    /** Same filtering the rest of the screen applies: demo parses never adopt. */
    private fun parseOutput(): CaptureParseOutput? =
        (state.parseSnapshot?.output as? CaptureParseOutput)
            ?.takeIf { state.parseSnapshot?.provider?.isDemo == false }

    private fun clearAssessmentSnapshotForActivePage() {
        state.assessmentSnapshot = null
        val activeAssetId = state.assessmentSourceAssetId
        state.sourcePageAssessmentSnapshots = state.sourcePageAssessmentSnapshots.mapIndexed {
                index,
                existing,
            ->
            if (state.sourcePages.getOrNull(index)?.sourceAssetId == activeAssetId) {
                null
            } else {
                existing
            }
        }
    }

    private fun resetDraftFields() {
        state.importRequestId = UUID.randomUUID().toString()
        state.importOccurredAtEpochMillis = System.currentTimeMillis()
        state.commitOutcomeUnknown = false
        state.draftId = null
        state.draftRevisionNumber = null
        state.canonicalSha256 = null
        state.committedEntryId = null
        state.selectedSubject = ""
        state.correctedTitle = ""
        state.titleEditedByUser = false
        state.correctedTranscription = ""
        state.writingLayerName = CaptureWritingLayer.UNKNOWN.name
        state.recognitionStateName = CaptureRecognitionState.NOT_ATTEMPTED.name
        state.recognitionConfidence = null
        state.recognitionBlockCount = 0
        state.assessmentRequestId = null
        state.assessmentSourceAssetId = null
        state.assessmentOccurredAtEpochMillis = null
        state.assessmentRetryNonce = 0
        state.splitRetryNonce = 0
        state.splitError = null
        state.assessmentSnapshot = null
        state.pendingAssessmentRecoveryRequest = null
        state.sourcePages = emptyList()
        state.sourcePageAssessmentSnapshots = emptyList()
        state.selectedSourcePageIndex = 0
        state.parseRequestId = null
        state.parseRetryNonce = 0
        state.parseSnapshot = null
        state.pendingParseRecoveryRequest = null
        state.transcriptionEditedByUser = false
    }

    private fun clearWorkspace() {
        state.workspaceState = null
        state.workspaceIdentity = null
        state.workspaceUpdatedAtEpochMillis = 0
        state.workspaceHydratedDraftId = null
        state.workspaceChangeVersion = 0
        state.workspaceSaveError = null
        state.workspaceSaving = false
    }
}
