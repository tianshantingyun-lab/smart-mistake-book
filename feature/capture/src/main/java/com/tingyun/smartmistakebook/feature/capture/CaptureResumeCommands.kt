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
        tasks = tasks,
        workspace = restoreCaptureWorkspace(draft),
    )
}

internal class CaptureResumeCommands(
    private val context: Context,
    private val repository: CaptureWorkflowRepository,
    private val sink: CaptureResumeSink,
) {
    suspend fun pruneAndRecoverOwnedUris() {
        try {
            withContext(Dispatchers.IO) {
                pruneOwnedCaptureCache(
                    context = context,
                    retainedUris = listOfNotNull(
                        sink.pendingCameraUri(),
                        sink.receivedImageUri(),
                        sink.replacementCandidateUri(),
                        sink.pendingAppendOwnedUri(),
                    ),
                )
            }
            fun recover(uri: String?): CaptureRestoredUriDecision =
                captureRestoredUriDecision(
                    uri = uri,
                    isOwnedCapture = isOwnedCaptureUri(uri),
                    fileExists = ownedCaptureExists(context, uri),
                )
            sink.applyOwnedUriRecovery(
                captureOwnedUriRecovery(
                    pendingCamera = recover(sink.pendingCameraUri()),
                    received = recover(sink.receivedImageUri()),
                    replacement = recover(sink.replacementCandidateUri()),
                    pendingAppend = recover(sink.pendingAppendOwnedUri()),
                ),
            )
        } finally {
            sink.completeCachePrune()
        }
    }

    suspend fun loadResume(requestedDraftId: String) {
        if (captureResumeShouldSkipLoad(requestedDraftId, sink.draftId(), sink.receivedImageUri())) {
            sink.setResumeState(CaptureResumeLoadState.READY)
            return
        }
        sink.setResumeState(CaptureResumeLoadState.LOADING)
        val resumable = try {
            withContext(Dispatchers.IO) { repository.readPendingCapture(requestedDraftId) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            sink.setResumeState(CaptureResumeLoadState.SOURCE_UNAVAILABLE)
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
                sink.setResumeState(CaptureResumeLoadState.SOURCE_UNAVAILABLE)
                return
            }
            CaptureResumeLoadedDecision.Missing -> {
                sink.setResumeState(CaptureResumeLoadState.MISSING)
                return
            }
            is CaptureResumeLoadedDecision.RedirectTutor -> {
                sink.setResumeState(CaptureResumeLoadState.REDIRECTING)
                sink.redirectTutor(decision.sessionId)
                return
            }
            CaptureResumeLoadedDecision.Apply -> Unit
        }
        sink.applyResumeDraft(captureResumeDraftApplication(requireNotNull(resumable)))
        sink.setResumeState(CaptureResumeLoadState.READY)
    }

    suspend fun hydrateWorkspace(currentDraftId: String) {
        if (sink.workspaceHydratedDraftId() == currentDraftId) return
        val resumable = runCatching {
            withContext(Dispatchers.IO) { repository.readPendingCapture(currentDraftId) }
        }.getOrNull() ?: return
        sink.applyWorkspace(restoreCaptureWorkspace(resumable))
    }
}

internal class CaptureResumeSink(
    val pendingCameraUri: () -> String?,
    val receivedImageUri: () -> String?,
    val replacementCandidateUri: () -> String?,
    val pendingAppendOwnedUri: () -> String?,
    val draftId: () -> String?,
    val workspaceHydratedDraftId: () -> String?,
    val applyOwnedUriRecovery: (CaptureOwnedUriRecovery) -> Unit,
    val completeCachePrune: () -> Unit,
    val setResumeState: (CaptureResumeLoadState) -> Unit,
    val redirectTutor: (String) -> Unit,
    val applyResumeDraft: (CaptureResumeDraftApplication) -> Unit,
    val applyWorkspace: (CaptureWorkspaceLocalSnapshot) -> Unit,
)
