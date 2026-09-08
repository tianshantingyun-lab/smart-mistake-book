package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureFailureCode
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot

internal class CaptureWorkflowEventCommands(
    private val sink: CaptureWorkflowEventSink,
) {
    fun applyImported(event: CaptureDraftImportedEvent) {
        when (event.purpose) {
            CaptureAcquisitionPurpose.NEW_CAPTURE -> {
                sink.applyDraftSummary(
                    event.summary,
                    event.requestId,
                    event.occurredAtEpochMillis,
                )
                sink.clearCaptureError()
            }
            CaptureAcquisitionPurpose.APPEND_DRAFT -> {
                val appended = captureImportedAppendModelTasks(
                    requestId = event.requestId,
                    sourcePages = event.summary.sourcePages,
                    existingSnapshots = sink.pageAssessmentSnapshots(),
                )
                sink.applyAppendedPages(
                    event.summary.sourcePages,
                    appended.pageSnapshots,
                    appended.selectedPageIndex,
                    appended.assessmentRequestId,
                    appended.assessmentSourceAssetId,
                    event.occurredAtEpochMillis,
                    appended.parseRequestId,
                )
                sink.resetAcquisitionPurpose()
                sink.deleteOwnedUri(event.sourceUri)
                sink.clearPendingAppend()
                sink.clearCaptureError()
            }
            CaptureAcquisitionPurpose.REPLACE_DRAFT -> {
                sink.deleteOwnedUri(sink.receivedImageUri())
                sink.setReceivedImage(event.sourceUri)
                sink.resetDraft()
                sink.applyDraftSummary(
                    event.summary,
                    event.requestId,
                    event.occurredAtEpochMillis,
                )
                sink.clearReplacementState()
                sink.clearCaptureError()
            }
        }
        sink.setWorkflowInProgress(false)
        sink.consumeDraftImported(event.requestId)
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
            sink.commitLibraryEntry(savedEntryId, saved.nextRevisionNumber)
        }
        sink.deleteOwnedUri(sink.receivedImageUri())
        sink.clearReceivedImage()
        sink.clearWorkspace()
        sink.markCommitKnown()
        sink.clearCaptureError()
        sink.setWorkflowInProgress(false)
    }

    fun applyFailed(phase: CaptureWorkflowPhase, failureCode: CaptureFailureCode?) {
        if (captureFailedWorkflowMarksUnknownOutcome(phase, failureCode)) {
            sink.setWorkflowInProgress(false)
            sink.markCommitUnknown()
        } else if (phase == CaptureWorkflowPhase.FAILED) {
            sink.setWorkflowInProgress(false)
        }
    }

    fun consumeTutorSession(session: ConfirmedTutorSession) {
        // First tutor plan runs under the global agent consent; navigation hands off
        // with no capture-side authorization state.
        sink.onTutorSessionReady(session.sessionId)
        sink.setWorkflowInProgress(false)
        sink.consumeTutorSession(session.sessionId)
    }
}

internal class CaptureWorkflowEventSink(
    val applyDraftSummary: (
        CaptureDraftSummary,
        String,
        Long,
    ) -> Unit,
    val clearCaptureError: () -> Unit,
    val pageAssessmentSnapshots: () -> List<ModelTaskSnapshot?>,
    val applyAppendedPages: (
        pages: List<CaptureSourcePage>,
        snapshots: List<ModelTaskSnapshot?>,
        selectedPageIndex: Int,
        assessmentRequestId: String,
        assessmentSourceAssetId: String,
        assessmentOccurredAtEpochMillis: Long,
        parseRequestId: String,
    ) -> Unit,
    val resetAcquisitionPurpose: () -> Unit,
    val deleteOwnedUri: (String?) -> Unit,
    val clearPendingAppend: () -> Unit,
    val receivedImageUri: () -> String?,
    val setReceivedImage: (String) -> Unit,
    val resetDraft: () -> Unit,
    val clearReplacementState: () -> Unit,
    val setWorkflowInProgress: (Boolean) -> Unit,
    val consumeDraftImported: (String) -> Unit,
    val commitLibraryEntry: (String?, Int?) -> Unit,
    val clearReceivedImage: () -> Unit,
    val clearWorkspace: () -> Unit,
    val markCommitKnown: () -> Unit,
    val markCommitUnknown: () -> Unit,
    val onTutorSessionReady: (String) -> Unit,
    val consumeTutorSession: (String) -> Unit,
)
