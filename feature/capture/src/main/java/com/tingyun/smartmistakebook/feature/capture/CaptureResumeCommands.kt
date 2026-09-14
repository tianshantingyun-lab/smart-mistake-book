package com.tingyun.smartmistakebook.feature.capture

import android.content.Context
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CaptureOwnedUriRecovery(
    val clearPendingCamera: Boolean = false,
    val clearReceived: Boolean = false,
    val clearReplacement: Boolean = false,
    val clearPendingAppend: Boolean = false,
    val error: String? = null,
)

internal fun captureOwnedUriRecovery(
    pendingCamera: CaptureRestoredUriDecision,
    received: CaptureRestoredUriDecision,
    replacement: CaptureRestoredUriDecision,
    pendingAppend: CaptureRestoredUriDecision,
): CaptureOwnedUriRecovery {
    val errors = listOf(pendingCamera, received, replacement, pendingAppend)
        .mapNotNull { (it as? CaptureRestoredUriDecision.Clear)?.error }
    return CaptureOwnedUriRecovery(
        clearPendingCamera = pendingCamera is CaptureRestoredUriDecision.Clear,
        clearReceived = received is CaptureRestoredUriDecision.Clear,
        clearReplacement = replacement is CaptureRestoredUriDecision.Clear,
        clearPendingAppend = pendingAppend is CaptureRestoredUriDecision.Clear,
        error = errors.lastOrNull(),
    )
}

internal data class CaptureResumeDraftApplication(
    val originName: String,
    val receivedImageUri: String,
    val importOccurredAtEpochMillis: Long,
    val draftId: String,
    val draftRevisionNumber: Int,
    val canonicalSha256: String,
    val sourcePages: List<CaptureSourcePage>,
    val sourcePageAssessmentSnapshots: List<ModelTaskSnapshot?>,
    val selectedSubject: String,
    val correctedTitle: String,
    val correctedTranscription: String,
    val writingLayerName: String,
    val recognitionStateName: String,
    val recognitionConfidence: Double?,
    val recognitionBlockCount: Int,
    val userHint: String,
    val tasks: CaptureResumeModelTaskSelection,
    val workspace: CaptureWorkspaceLocalSnapshot,
)

internal fun captureResumeDraftApplication(
    draft: ResumableCaptureDraft,
): CaptureResumeDraftApplication {
    val tasks = captureResumeModelTaskSelection(
        draftId = draft.draftId,
        revisionNumber = draft.currentRevisionNumber,
        sourcePages = draft.sourcePages,
        pageTasks = draft.sourcePageAssessmentTasks,
        latestParseTask = draft.latestParseTask,
    )
    return CaptureResumeDraftApplication(
        originName = draft.origin.name,
        receivedImageUri = draft.sourceImageUri,
        importOccurredAtEpochMillis = draft.draftCreatedAtEpochMillis,
        draftId = draft.draftId,
        draftRevisionNumber = draft.currentRevisionNumber,
        canonicalSha256 = draft.sourceAssetSha256,
        sourcePages = draft.sourcePages,
        sourcePageAssessmentSnapshots = draft.sourcePageAssessmentTasks,
        selectedSubject = draft.subject.orEmpty(),
        correctedTitle = draft.title,
        correctedTranscription = draft.transcription,
        writingLayerName = draft.writingLayer.name,
        recognitionStateName = captureResumeRecognitionStateName(draft.transcription),
        recognitionConfidence = draft.questionDocument.blockEvidence
            .mapNotNull { evidence -> evidence.confidence }
            .takeIf { values -> values.isNotEmpty() }
            ?.average(),
        recognitionBlockCount = draft.questionDocument.document.blocks.size,
        userHint = (tasks.assessmentSnapshot?.request?.input
            as? com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput)
            ?.userHint.orEmpty(),
        tasks = tasks,
        workspace = restoreCaptureWorkspace(draft),
    )
}

/**
 * Resume/recovery of a pending draft onto screen state. Owned-uri cache
 * pruning stays Android-side (context); the workspace application delegates to
 * [CaptureDraftStateCommands]; the cache-prune latch and tutor hand-off are
 * injected.
 */
internal class CaptureResumeCommands(
    private val context: Context,
    private val repository: CaptureWorkflowRepository,
    private val state: CaptureScreenState,
    private val draftState: CaptureDraftStateCommands,
    private val onCompleteCachePrune: () -> Unit,
    private val onRedirectTutor: (String) -> Unit,
) {
    suspend fun pruneAndRecoverOwnedUris() {
        try {
            withContext(Dispatchers.IO) {
                pruneOwnedCaptureCache(
                    context = context,
                    retainedUris = listOfNotNull(
                        state.pendingCameraUri,
                        state.receivedImageUri,
                        state.replacementCandidateUri,
                        state.pendingAppendOwnedUri,
                    ),
                )
            }
            fun recover(uri: String?): CaptureRestoredUriDecision =
                captureRestoredUriDecision(
                    uri = uri,
                    isOwnedCapture = isOwnedCaptureUri(uri),
                    fileExists = ownedCaptureExists(context, uri),
                )
            applyOwnedUriRecovery(
                captureOwnedUriRecovery(
                    pendingCamera = recover(state.pendingCameraUri),
                    received = recover(state.receivedImageUri),
                    replacement = recover(state.replacementCandidateUri),
                    pendingAppend = recover(state.pendingAppendOwnedUri),
                ),
            )
        } finally {
            onCompleteCachePrune()
        }
    }

    suspend fun loadResume(requestedDraftId: String) {
        if (
            captureResumeShouldSkipLoad(
                requestedDraftId = requestedDraftId,
                currentDraftId = state.draftId,
                receivedImageUri = state.receivedImageUri,
                sourcePages = state.sourcePages,
            )
        ) {
            state.resumeLoadStateName = CaptureResumeLoadState.READY.name
            return
        }
        state.resumeLoadStateName = CaptureResumeLoadState.LOADING.name
        val resumable = try {
            withContext(Dispatchers.IO) { repository.readPendingCapture(requestedDraftId) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.resumeLoadStateName = CaptureResumeLoadState.SOURCE_UNAVAILABLE.name
            return
        }
        when (
            val decision = captureResumeLoadedDecision(
                loadFailed = false,
                tutorSessionId = resumable?.tutorSessionId,
                draftFound = resumable != null,
            )
        ) {
            CaptureResumeLoadedDecision.SourceUnavailable -> {
                state.resumeLoadStateName = CaptureResumeLoadState.SOURCE_UNAVAILABLE.name
                return
            }
            CaptureResumeLoadedDecision.Missing -> {
                state.resumeLoadStateName = CaptureResumeLoadState.MISSING.name
                return
            }
            is CaptureResumeLoadedDecision.RedirectTutor -> {
                state.resumeLoadStateName = CaptureResumeLoadState.REDIRECTING.name
                onRedirectTutor(decision.sessionId)
                return
            }
            CaptureResumeLoadedDecision.Apply -> Unit
        }
        applyResumeDraft(captureResumeDraftApplication(requireNotNull(resumable)))
        state.resumeLoadStateName = CaptureResumeLoadState.READY.name
    }

    suspend fun hydrateWorkspace(currentDraftId: String) {
        if (state.workspaceHydratedDraftId == currentDraftId) return
        val resumable = runCatching {
            withContext(Dispatchers.IO) { repository.readPendingCapture(currentDraftId) }
        }.getOrNull() ?: return
        draftState.applyWorkspace(restoreCaptureWorkspace(resumable))
    }

    private fun applyOwnedUriRecovery(recovery: CaptureOwnedUriRecovery) {
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
    }

    private fun applyResumeDraft(applied: CaptureResumeDraftApplication) {
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
        state.userHint = applied.userHint
        state.parseSnapshot = applied.tasks.parseSnapshot
        state.parseRequestId = applied.tasks.parseRequestId
        state.parseRetryNonce = 0
        state.transcriptionEditedByUser = false
        state.captureError = null
        draftState.applyWorkspace(applied.workspace)
    }
}
