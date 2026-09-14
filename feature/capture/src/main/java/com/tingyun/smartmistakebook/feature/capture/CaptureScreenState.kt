package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot

/**
 * Mutable state of [CaptureScreen], extracted so the command builders and the
 * UI tree can live in their own files.
 *
 * Two memory scopes mirror the original per-field remember/rememberSaveable
 * split, which [CaptureScreenState.Saver] preserves exactly:
 *
 * - [saved] fields were `rememberSaveable` — they survive configuration
 *   change and process death and are round-tripped through the Saver.
 * - The remaining fields were plain `remember` (model-task snapshots, the
 *   source-page list and the workspace which hold runtime objects) — they are
 *   deliberately NOT in the Saver, so they reset to their defaults on restore
 *   and are re-derived by the resume/workspace effects, exactly as before.
 */
internal class CaptureScreenState {
    // --- saveable (survive config change + process death via the Saver) ---
    var pendingCameraUri: String? by mutableStateOf(null)
    var receivedImageUri: String? by mutableStateOf(null)
    var receivedInputSource: String? by mutableStateOf(null)
    var captureError: String? by mutableStateOf(null)
    var importRequestId: String? by mutableStateOf(null)
    var importOccurredAtEpochMillis: Long? by mutableStateOf(null)
    var commitOutcomeUnknown: Boolean by mutableStateOf(false)
    var draftId: String? by mutableStateOf(null)
    var draftRevisionNumber: Int? by mutableStateOf(null)
    var canonicalSha256: String? by mutableStateOf(null)
    var committedEntryId: String? by mutableStateOf(null)
    var selectedSubject: String by mutableStateOf("")
    var correctedTitle: String by mutableStateOf("")
    var titleEditedByUser: Boolean by mutableStateOf(false)
    var correctedTranscription: String by mutableStateOf("")
    var writingLayerName: String by mutableStateOf(CaptureWritingLayer.UNKNOWN.name)
    var recognitionStateName: String by mutableStateOf(CaptureRecognitionState.NOT_ATTEMPTED.name)
    var recognitionConfidence: Double? by mutableStateOf(null)
    var recognitionBlockCount: Int by mutableStateOf(0)
    var assessmentRequestId: String? by mutableStateOf(null)
    var assessmentSourceAssetId: String? by mutableStateOf(null)
    var assessmentOccurredAtEpochMillis: Long? by mutableStateOf(null)
    var assessmentRetryNonce: Int by mutableStateOf(0)
    var userHint: String by mutableStateOf("")
    var splitRetryNonce: Int by mutableStateOf(0)
    var manualSplitNonce: Int by mutableStateOf(0)
    /** 评估判 SPLIT 后等待用户选择"自动拆分 / 手动框选"。 */
    var splitPendingChoice: Boolean by mutableStateOf(false)
    var splitError: String? by mutableStateOf(null)
    var selectedSourcePageIndex: Int by mutableStateOf(0)
    var parseRequestId: String? by mutableStateOf(null)
    var parseRetryNonce: Int by mutableStateOf(0)
    var transcriptionEditedByUser: Boolean by mutableStateOf(false)
    var activeEntryOriginName: String by mutableStateOf(CaptureEntryOrigin.LIBRARY.name)
    var resumeLoadStateName: String by mutableStateOf(CaptureResumeLoadState.NOT_REQUESTED.name)
    var acquisitionPurposeName: String by mutableStateOf(CaptureAcquisitionPurpose.NEW_CAPTURE.name)
    var replacementCandidateUri: String? by mutableStateOf(null)
    var replacementInputSourceName: String? by mutableStateOf(null)
    var replacementRequestId: String? by mutableStateOf(null)
    var replacementOccurredAtEpochMillis: Long? by mutableStateOf(null)
    var replacementError: String? by mutableStateOf(null)

    // --- reset on restore (were plain remember; re-derived by effects) ---
    var cameraLaunchInProgress: Boolean by mutableStateOf(false)
    var photoImportInProgress: Boolean by mutableStateOf(false)
    var workflowInProgress: Boolean by mutableStateOf(false)
    var assessmentSnapshot: ModelTaskSnapshot? by mutableStateOf(null)
    var pendingAssessmentRecoveryRequest: ModelTaskRequest? by mutableStateOf(null)
    var sourcePages: List<CaptureSourcePage> by mutableStateOf(emptyList())
    var sourcePageAssessmentSnapshots: List<ModelTaskSnapshot?> by mutableStateOf(emptyList())
    var parseSnapshot: ModelTaskSnapshot? by mutableStateOf(null)
    var pendingParseRecoveryRequest: ModelTaskRequest? by mutableStateOf(null)
    var providerCapabilities: ProviderCapabilitySnapshot? by mutableStateOf(null)
    var workspaceState: CaptureWorkspaceUiState? by mutableStateOf(null)
    var workspaceIdentity: CaptureDraftWorkspaceIdentity? by mutableStateOf(null)
    var workspaceUpdatedAtEpochMillis: Long by mutableStateOf(0L)
    var workspaceHydratedDraftId: String? by mutableStateOf(null)
    var workspaceChangeVersion: Long by mutableStateOf(0L)
    var workspaceSaveError: String? by mutableStateOf(null)
    var workspaceSaving: Boolean by mutableStateOf(false)
    var pendingAppendOwnedUri: String? by mutableStateOf(null)

    /**
     * Snapshot capture of the saveable subset. Called on every recomposition
     * only to build the Saver payload when the composition asks to save state
     * (the Saver itself is a plain function and is not a snapshot point), so
     * reading here is safe.
     */
    private fun snapshotSaveable(): Map<String, Any?> = mapOf(
        "pendingCameraUri" to pendingCameraUri,
        "receivedImageUri" to receivedImageUri,
        "receivedInputSource" to receivedInputSource,
        "captureError" to captureError,
        "importRequestId" to importRequestId,
        "importOccurredAtEpochMillis" to importOccurredAtEpochMillis,
        "commitOutcomeUnknown" to commitOutcomeUnknown,
        "draftId" to draftId,
        "draftRevisionNumber" to draftRevisionNumber,
        "canonicalSha256" to canonicalSha256,
        "committedEntryId" to committedEntryId,
        "selectedSubject" to selectedSubject,
        "correctedTitle" to correctedTitle,
        "titleEditedByUser" to titleEditedByUser,
        "correctedTranscription" to correctedTranscription,
        "writingLayerName" to writingLayerName,
        "recognitionStateName" to recognitionStateName,
        "recognitionConfidence" to recognitionConfidence,
        "recognitionBlockCount" to recognitionBlockCount,
        "assessmentRequestId" to assessmentRequestId,
        "assessmentSourceAssetId" to assessmentSourceAssetId,
        "assessmentOccurredAtEpochMillis" to assessmentOccurredAtEpochMillis,
        "assessmentRetryNonce" to assessmentRetryNonce,
        "userHint" to userHint,
        "splitRetryNonce" to splitRetryNonce,
        "manualSplitNonce" to manualSplitNonce,
        "splitPendingChoice" to splitPendingChoice,
        "splitError" to splitError,
        "selectedSourcePageIndex" to selectedSourcePageIndex,
        "parseRequestId" to parseRequestId,
        "parseRetryNonce" to parseRetryNonce,
        "transcriptionEditedByUser" to transcriptionEditedByUser,
        "activeEntryOriginName" to activeEntryOriginName,
        "resumeLoadStateName" to resumeLoadStateName,
        "acquisitionPurposeName" to acquisitionPurposeName,
        "replacementCandidateUri" to replacementCandidateUri,
        "replacementInputSourceName" to replacementInputSourceName,
        "replacementRequestId" to replacementRequestId,
        "replacementOccurredAtEpochMillis" to replacementOccurredAtEpochMillis,
        "replacementError" to replacementError,
    )

    private fun restoreSaveable(saved: Map<String, Any?>) {
        pendingCameraUri = saved["pendingCameraUri"] as String?
        receivedImageUri = saved["receivedImageUri"] as String?
        receivedInputSource = saved["receivedInputSource"] as String?
        captureError = saved["captureError"] as String?
        importRequestId = saved["importRequestId"] as String?
        importOccurredAtEpochMillis = saved["importOccurredAtEpochMillis"] as Long?
        commitOutcomeUnknown = saved["commitOutcomeUnknown"] as Boolean
        draftId = saved["draftId"] as String?
        draftRevisionNumber = saved["draftRevisionNumber"] as Int?
        canonicalSha256 = saved["canonicalSha256"] as String?
        committedEntryId = saved["committedEntryId"] as String?
        selectedSubject = saved["selectedSubject"] as String
        correctedTitle = saved["correctedTitle"] as String
        titleEditedByUser = saved["titleEditedByUser"] as Boolean
        correctedTranscription = saved["correctedTranscription"] as String
        writingLayerName = saved["writingLayerName"] as String
        recognitionStateName = saved["recognitionStateName"] as String
        recognitionConfidence = saved["recognitionConfidence"] as Double?
        recognitionBlockCount = saved["recognitionBlockCount"] as Int
        assessmentRequestId = saved["assessmentRequestId"] as String?
        assessmentSourceAssetId = saved["assessmentSourceAssetId"] as String?
        assessmentOccurredAtEpochMillis = saved["assessmentOccurredAtEpochMillis"] as Long?
        assessmentRetryNonce = saved["assessmentRetryNonce"] as Int
        userHint = saved["userHint"] as String? ?: ""
        splitRetryNonce = saved["splitRetryNonce"] as Int
        manualSplitNonce = saved["manualSplitNonce"] as Int
        splitPendingChoice = saved["splitPendingChoice"] as Boolean
        splitError = saved["splitError"] as String?
        selectedSourcePageIndex = saved["selectedSourcePageIndex"] as Int
        parseRequestId = saved["parseRequestId"] as String?
        parseRetryNonce = saved["parseRetryNonce"] as Int
        transcriptionEditedByUser = saved["transcriptionEditedByUser"] as Boolean
        activeEntryOriginName = saved["activeEntryOriginName"] as String
        resumeLoadStateName = saved["resumeLoadStateName"] as String
        acquisitionPurposeName = saved["acquisitionPurposeName"] as String
        replacementCandidateUri = saved["replacementCandidateUri"] as String?
        replacementInputSourceName = saved["replacementInputSourceName"] as String?
        replacementRequestId = saved["replacementRequestId"] as String?
        replacementOccurredAtEpochMillis = saved["replacementOccurredAtEpochMillis"] as Long?
        replacementError = saved["replacementError"] as String?
    }

    companion object {
        /**
         * Round-trips only the saveable fields. A field the original code kept
         * in plain `remember` is intentionally absent here; on restore it goes
         * back to its default and the resume/workspace effects re-derive it —
         * which is exactly what happened on configuration change before.
         */
        val Saver: Saver<CaptureScreenState, Any> = mapSaver(
            save = { it.snapshotSaveable() },
            restore = { saved ->
                CaptureScreenState().apply { restoreSaveable(saved) }
            },
        )
    }
}

/** Recreates [CaptureScreenState] with the original saveable memory scope. */
@Composable
internal fun rememberCaptureScreenState(
    resumeDraftId: String?,
    entryOrigin: CaptureEntryOrigin,
): CaptureScreenState = rememberSaveable(
    inputs = arrayOf<Any?>(resumeDraftId, entryOrigin),
    saver = CaptureScreenState.Saver,
) {
    CaptureScreenState().apply {
        activeEntryOriginName = entryOrigin.name
        resumeLoadStateName = if (resumeDraftId == null) {
            CaptureResumeLoadState.NOT_REQUESTED.name
        } else {
            CaptureResumeLoadState.LOADING.name
        }
    }
}
