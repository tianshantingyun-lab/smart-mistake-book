package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureFailureCode
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization

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
                    sink.sourceEgressIntent(CaptureAcquisitionPurpose.NEW_CAPTURE, event.sourceUri),
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
                sink.clearCaptureEgressApproval()
                val sourceEgressIntent = sink.sourceEgressIntent(
                    CaptureAcquisitionPurpose.APPEND_DRAFT,
                    event.sourceUri,
                )
                sink.bindFreshEgressIntent(
                    sourceEgressIntent?.bindDraft(
                        draftId = event.summary.draftId,
                        sourcePages = event.summary.sourcePages,
                    ),
                )
                sourceEgressIntent?.let { sink.completeEgressIntent(it.intentId) }
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
                    sink.sourceEgressIntent(CaptureAcquisitionPurpose.REPLACE_DRAFT, event.sourceUri),
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

    fun consumeTutorSession(
        session: ConfirmedTutorSession,
        provider: ProviderCapabilitySnapshot?,
        manifest: ModelEgressManifest?,
        activeAuthorizationId: String?,
        initialTutorPlanAuthorizationId: String?,
        nowEpochMillis: Long,
    ) {
        val autoStart = captureTutorAutoStartAuthorization(
            sessionId = session.sessionId,
            questionDocumentId = session.questionDocument.document.id,
            revisionNumber = session.draftRevisionNumber,
            provider = provider,
            manifest = manifest,
            activeAuthorizationId = activeAuthorizationId,
            initialTutorPlanAuthorizationId = initialTutorPlanAuthorizationId,
            nowEpochMillis = nowEpochMillis,
        )
        sink.onTutorSessionReady(session.sessionId, autoStart)
        sink.setWorkflowInProgress(false)
        sink.consumeTutorSession(session.sessionId)
    }
}

internal class CaptureWorkflowEventSink(
    val applyDraftSummary: (
        CaptureDraftSummary,
        String,
        Long,
        CaptureSourceEgressIntent?,
    ) -> Unit,
    val sourceEgressIntent: (CaptureAcquisitionPurpose, String) -> CaptureSourceEgressIntent?,
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
    val clearCaptureEgressApproval: () -> Unit,
    val bindFreshEgressIntent: (CaptureDraftEgressIntent?) -> Unit,
    val completeEgressIntent: (String) -> Unit,
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
    val onTutorSessionReady: (String, TutorAutoStartAuthorization?) -> Unit,
    val consumeTutorSession: (String) -> Unit,
)
