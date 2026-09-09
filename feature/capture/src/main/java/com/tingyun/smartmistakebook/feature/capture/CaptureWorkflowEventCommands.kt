package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureFailureCode
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot

/**
 * Maps ViewModel workflow events onto screen state. Draft-summary and
 * reset steps delegate to [CaptureDraftStateCommands]; ViewModel consumption
 * and owned-uri deletion are injected.
 */
internal class CaptureWorkflowEventCommands(
    private val state: CaptureScreenState,
    private val draftState: CaptureDraftStateCommands,
    private val onDeleteOwnedUri: (String?) -> Unit,
    private val onConsumeDraftImported: (String) -> Unit,
    private val onConsumeTutorSession: (String) -> Unit,
    private val onTutorSessionReady: (String) -> Unit,
) {
    fun applyImported(event: CaptureDraftImportedEvent) {
        when (event.purpose) {
            CaptureAcquisitionPurpose.NEW_CAPTURE -> {
                draftState.applyDraftSummary(
                    event.summary,
                    event.requestId,
                    event.occurredAtEpochMillis,
                )
                state.captureError = null
            }
            CaptureAcquisitionPurpose.APPEND_DRAFT -> {
                val appended = captureImportedAppendModelTasks(
                    requestId = event.requestId,
                    sourcePages = event.summary.sourcePages,
                    existingSnapshots = state.sourcePageAssessmentSnapshots,
                )
                applyAppendedPages(
                    event.summary.sourcePages,
                    appended.pageSnapshots,
                    appended.selectedPageIndex,
                    appended.assessmentRequestId,
                    appended.assessmentSourceAssetId,
                    event.occurredAtEpochMillis,
                    appended.parseRequestId,
                )
                state.acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
                onDeleteOwnedUri(event.sourceUri)
                state.pendingAppendOwnedUri = null
                state.captureError = null
            }
            CaptureAcquisitionPurpose.REPLACE_DRAFT -> {
                onDeleteOwnedUri(state.receivedImageUri)
                state.receivedImageUri = event.sourceUri
                state.receivedInputSource = null
                draftState.resetDraft()
                draftState.applyDraftSummary(
                    event.summary,
                    event.requestId,
                    event.occurredAtEpochMillis,
                )
                clearReplacementState()
                state.captureError = null
            }
        }
        state.workflowInProgress = false
        onConsumeDraftImported(event.requestId)
    }

    fun applySaved(
        phase: CaptureWorkflowPhase,
        origin: CaptureEntryOrigin,
        currentRevisionNumber: Int?,
        savedEntryId: String?,
    ) {
        val saved = captureSavedWorkflowApplication(
            phase = phase,
            origin = origin,
            currentRevisionNumber = currentRevisionNumber,
        ) ?: return
        if (saved.commitLibraryEntry) {
            state.committedEntryId = savedEntryId
            state.draftRevisionNumber = saved.nextRevisionNumber
        }
        onDeleteOwnedUri(state.receivedImageUri)
        state.receivedImageUri = null
        clearWorkspace()
        state.commitOutcomeUnknown = false
        state.captureError = null
        state.workflowInProgress = false
    }

    fun applyFailed(phase: CaptureWorkflowPhase, failureCode: CaptureFailureCode?) {
        if (captureFailedWorkflowMarksUnknownOutcome(phase, failureCode)) {
            state.workflowInProgress = false
            state.commitOutcomeUnknown = true
        } else if (phase == CaptureWorkflowPhase.FAILED) {
            state.workflowInProgress = false
        }
    }

    fun consumeTutorSession(session: ConfirmedTutorSession) {
        // First tutor plan runs under the global agent consent; navigation hands off
        // with no capture-side authorization state.
        onTutorSessionReady(session.sessionId)
        state.workflowInProgress = false
        onConsumeTutorSession(session.sessionId)
    }

    private fun applyAppendedPages(
        pages: List<CaptureSourcePage>,
        snapshots: List<ModelTaskSnapshot?>,
        selectedPageIndex: Int,
        assessmentRequestId: String,
        assessmentSourceAssetId: String,
        assessmentOccurredAtEpochMillis: Long,
        parseRequestId: String,
    ) {
        state.sourcePages = pages
        state.sourcePageAssessmentSnapshots = snapshots
        state.selectedSourcePageIndex = selectedPageIndex
        state.assessmentSnapshot = null
        state.assessmentRequestId = assessmentRequestId
        state.assessmentSourceAssetId = assessmentSourceAssetId
        state.assessmentOccurredAtEpochMillis = assessmentOccurredAtEpochMillis
        state.assessmentRetryNonce = 0
        state.parseSnapshot = null
        state.parseRequestId = parseRequestId
        state.parseRetryNonce = 0
    }

    private fun clearReplacementState() {
        state.replacementCandidateUri = null
        state.replacementInputSourceName = null
        state.replacementRequestId = null
        state.replacementOccurredAtEpochMillis = null
        state.acquisitionPurposeName = CaptureAcquisitionPurpose.NEW_CAPTURE.name
        state.replacementError = null
    }

    private fun clearWorkspace() {
        state.workspaceState = null
        state.workspaceIdentity = null
        state.workspaceUpdatedAtEpochMillis = 0
        state.workspaceHydratedDraftId = null
    }
}
