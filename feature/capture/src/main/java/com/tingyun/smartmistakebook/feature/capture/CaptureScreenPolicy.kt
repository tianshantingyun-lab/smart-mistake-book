package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot

internal enum class CaptureResultAction {
    APPLY_AS_NEW,
    REPLACE_EXISTING,
    APPEND_EXISTING,
    KEEP_CURRENT,
}

internal fun captureResultAction(
    saved: Boolean,
    isEligibleImage: Boolean,
    purpose: CaptureAcquisitionPurpose,
): CaptureResultAction = when {
    !saved || !isEligibleImage -> CaptureResultAction.KEEP_CURRENT
    purpose == CaptureAcquisitionPurpose.REPLACE_DRAFT -> CaptureResultAction.REPLACE_EXISTING
    purpose == CaptureAcquisitionPurpose.APPEND_DRAFT -> CaptureResultAction.APPEND_EXISTING
    else -> CaptureResultAction.APPLY_AS_NEW
}

internal fun retakeAcquisitionPurpose(hasDraft: Boolean): CaptureAcquisitionPurpose =
    if (hasDraft) CaptureAcquisitionPurpose.REPLACE_DRAFT
    else CaptureAcquisitionPurpose.NEW_CAPTURE

internal fun prepareCaptureCommitAttempt(
    workspace: CaptureWorkspaceUiState,
    workspaceUpdatedAtEpochMillis: Long,
    requestIdFactory: () -> String,
    nowEpochMillis: () -> Long,
): CaptureWorkspaceUiState {
    val acceptedWorkspace = workspace.prepareForFinalCommit()
    if (acceptedWorkspace.finalConfirmationRequest != null) return acceptedWorkspace
    return acceptedWorkspace.ensureFinalConfirmation(
        requestIdFactory = requestIdFactory,
        occurredAtEpochMillis = {
            maxOf(nowEpochMillis(), workspaceUpdatedAtEpochMillis)
        },
    )
}

internal fun CaptureEntryOrigin.toAssessmentOrigin(): CaptureAssessmentOrigin = when (this) {
    CaptureEntryOrigin.TUTOR -> CaptureAssessmentOrigin.TUTOR
    CaptureEntryOrigin.LIBRARY -> CaptureAssessmentOrigin.LIBRARY
}

internal fun resumeAssessmentRequestId(draftId: String, pageIndex: Int = 0): String =
    if (pageIndex == 0) {
        "capture-assess:resume:$draftId"
    } else {
        "capture-assess:resume:$draftId:p$pageIndex"
    }

internal fun resumeParseRequestId(draftId: String, revisionNumber: Int): String =
    "capture-parse:resume:$draftId:r$revisionNumber"

internal fun suggestCaptureTitle(candidateText: String): String {
    val firstMeaningfulLine = candidateText
        .lineSequence()
        .map { line -> line.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull(String::isNotBlank)
        .orEmpty()
    if (firstMeaningfulLine.isBlank()) return "新拍题目"
    return if (firstMeaningfulLine.length <= MAX_CAPTURE_TITLE_CHARS) {
        firstMeaningfulLine
    } else {
        firstMeaningfulLine.take(MAX_CAPTURE_TITLE_CHARS - 1).trimEnd() + "…"
    }
}

internal fun shouldAutoPersistCapture(
    receivedImageUri: String?,
    draftId: String?,
    workflowInProgress: Boolean,
): Boolean = receivedImageUri != null && draftId == null && !workflowInProgress

internal const val MAX_CAPTURE_TITLE_CHARS = 24
internal const val MAX_CAPTURE_SOURCE_PAGES = 8


internal data class CaptureTaskRetry(
    val replaceRequestId: Boolean,
    val clearSnapshot: Boolean,
    val incrementNonce: Boolean,
)

internal fun nextCaptureTaskRetry(status: ModelTaskStatus?): CaptureTaskRetry {
    val replace = status == ModelTaskStatus.CANCELLED ||
        status == ModelTaskStatus.PERMANENT_FAILURE
    return CaptureTaskRetry(
        replaceRequestId = replace,
        clearSnapshot = replace,
        incrementNonce = !replace,
    )
}


internal sealed interface CaptureRecoveryApplication {
    data class Assessment(
        val request: ModelTaskRequest,
        val sourceAssetId: String,
        val pageSnapshots: List<ModelTaskSnapshot?>,
    ) : CaptureRecoveryApplication

    data class Parse(
        val request: ModelTaskRequest,
    ) : CaptureRecoveryApplication
}

internal fun captureRecoveryApplication(
    recoveryRequest: ModelTaskRequest,
    sourcePages: List<CaptureSourcePage>,
    pageSnapshots: List<ModelTaskSnapshot?>,
): CaptureRecoveryApplication = when (recoveryRequest.input.kind) {
    ModelTaskKind.CAPTURE_ASSESS -> {
        val sourceAssetId = (recoveryRequest.input as CaptureAssessmentInput).sourceAssetId
        CaptureRecoveryApplication.Assessment(
            request = recoveryRequest,
            sourceAssetId = sourceAssetId,
            pageSnapshots = pageSnapshots.mapIndexed { index, existing ->
                if (sourcePages.getOrNull(index)?.sourceAssetId == sourceAssetId) null else existing
            },
        )
    }
    ModelTaskKind.CAPTURE_PARSE -> CaptureRecoveryApplication.Parse(recoveryRequest)
    else -> error("Unexpected capture recovery task")
}


internal enum class CaptureAcquisitionLaunchDecision {
    FLUSH_WORKSPACE_FIRST,
    BUSY,
    LAUNCH,
}

internal fun captureAcquisitionLaunchDecision(
    hasWorkspace: Boolean,
    workspaceAlreadyFlushed: Boolean,
    cameraLaunchInProgress: Boolean,
    photoImportInProgress: Boolean,
    workflowInProgress: Boolean,
): CaptureAcquisitionLaunchDecision = when {
    hasWorkspace && !workspaceAlreadyFlushed ->
        CaptureAcquisitionLaunchDecision.FLUSH_WORKSPACE_FIRST
    cameraLaunchInProgress || photoImportInProgress || workflowInProgress ->
        CaptureAcquisitionLaunchDecision.BUSY
    else -> CaptureAcquisitionLaunchDecision.LAUNCH
}

internal fun captureKeepCurrentUserMessage(
    saved: Boolean,
    completedUri: String?,
): String? = if (saved && completedUri != null) {
    "相机返回的图片为空或超过 20 MB 安全上限；原题仍保留，请重试。"
} else {
    null
}


internal sealed interface CaptureReturnedImagePlan {
    data class ApplyAsNew(
        val uri: String,
        val deletePreviousUri: String?,
        val alsoDeleteUri: String? = null,
        val resetDraft: Boolean = true,
    ) : CaptureReturnedImagePlan

    data class ReplaceExisting(
        val uri: String,
        val deletePreviousUri: String?,
    ) : CaptureReturnedImagePlan

    data class Append(
        val uri: String,
    ) : CaptureReturnedImagePlan

    data class KeepCurrent(
        val deleteUri: String?,
        val error: String?,
        val cancelAcquisition: Boolean = true,
        val resetPurpose: Boolean = true,
        val clearReplacementRequest: Boolean = true,
    ) : CaptureReturnedImagePlan
}

internal fun captureCameraReturnedImagePlan(
    action: CaptureResultAction,
    completedUri: String?,
    currentReceivedUri: String?,
    currentReplacementUri: String?,
    saved: Boolean,
): CaptureReturnedImagePlan = when (action) {
    CaptureResultAction.APPLY_AS_NEW -> CaptureReturnedImagePlan.ApplyAsNew(
        uri = requireNotNull(completedUri),
        deletePreviousUri = currentReceivedUri.takeUnless { it == completedUri },
    )
    CaptureResultAction.REPLACE_EXISTING -> CaptureReturnedImagePlan.ReplaceExisting(
        uri = requireNotNull(completedUri),
        deletePreviousUri = currentReplacementUri.takeUnless { it == completedUri },
    )
    CaptureResultAction.APPEND_EXISTING -> CaptureReturnedImagePlan.Append(
        uri = requireNotNull(completedUri),
    )
    CaptureResultAction.KEEP_CURRENT -> CaptureReturnedImagePlan.KeepCurrent(
        deleteUri = completedUri,
        error = captureKeepCurrentUserMessage(saved, completedUri),
    )
}

internal fun capturePickerReturnedImagePlan(
    importedUri: String?,
    purpose: CaptureAcquisitionPurpose,
    currentReceivedUri: String?,
    pendingCameraUri: String?,
): CaptureReturnedImagePlan = when {
    importedUri == null -> CaptureReturnedImagePlan.KeepCurrent(
        deleteUri = null,
        error = "所选图片为空、格式不受支持或超过 20 MB 安全上限，请重试。",
        resetPurpose = false,
        clearReplacementRequest = false,
    )
    purpose == CaptureAcquisitionPurpose.APPEND_DRAFT ->
        CaptureReturnedImagePlan.Append(importedUri)
    else -> CaptureReturnedImagePlan.ApplyAsNew(
        uri = importedUri,
        deletePreviousUri = currentReceivedUri,
        alsoDeleteUri = pendingCameraUri,
    )
}

internal fun capturePickerCancelledPlan(): CaptureReturnedImagePlan =
    CaptureReturnedImagePlan.KeepCurrent(
        deleteUri = null,
        error = null,
        cancelAcquisition = true,
        resetPurpose = true,
        clearReplacementRequest = false,
    )


internal const val CAPTURE_NO_SYSTEM_CAMERA_ERROR =
    "此设备没有可用的系统相机，请改用系统照片选择器。"
internal const val CAPTURE_CANNOT_CREATE_PHOTO_FILE_ERROR =
    "无法创建本地照片文件，请确认设备存储空间后重试。"
internal const val CAPTURE_PHOTO_PICKER_UNAVAILABLE_ERROR =
    "系统照片选择器暂不可用，请稍后重试。"

internal data class CaptureReplacementLaunchPrep(
    val requestId: String?,
    val occurredAtEpochMillis: Long?,
    val clearReplacementError: Boolean,
)

internal fun captureReplacementLaunchPrep(
    purpose: CaptureAcquisitionPurpose,
    nowEpochMillis: Long,
    newRequestId: () -> String,
): CaptureReplacementLaunchPrep =
    if (purpose == CaptureAcquisitionPurpose.REPLACE_DRAFT) {
        CaptureReplacementLaunchPrep(
            requestId = newRequestId(),
            occurredAtEpochMillis = nowEpochMillis,
            clearReplacementError = true,
        )
    } else {
        CaptureReplacementLaunchPrep(
            requestId = null,
            occurredAtEpochMillis = null,
            clearReplacementError = false,
        )
    }

internal fun captureCameraUnavailablePlan(
    createdUri: String?,
): CaptureReturnedImagePlan.KeepCurrent = CaptureReturnedImagePlan.KeepCurrent(
    deleteUri = createdUri,
    error = CAPTURE_NO_SYSTEM_CAMERA_ERROR,
)

internal fun captureCreatePhotoFileFailedPlan(): CaptureReturnedImagePlan.KeepCurrent =
    CaptureReturnedImagePlan.KeepCurrent(
        deleteUri = null,
        error = CAPTURE_CANNOT_CREATE_PHOTO_FILE_ERROR,
    )

internal fun capturePhotoPickerUnavailablePlan(): CaptureReturnedImagePlan.KeepCurrent =
    CaptureReturnedImagePlan.KeepCurrent(
        deleteUri = null,
        error = CAPTURE_PHOTO_PICKER_UNAVAILABLE_ERROR,
        clearReplacementRequest = false,
    )


internal fun captureCameraActivityResultPlan(
    saved: Boolean,
    completedUri: String?,
    eligible: Boolean,
    purpose: CaptureAcquisitionPurpose,
    currentReceivedUri: String?,
    currentReplacementUri: String?,
): CaptureReturnedImagePlan = captureCameraReturnedImagePlan(
    action = captureResultAction(saved, eligible, purpose),
    completedUri = completedUri,
    currentReceivedUri = currentReceivedUri,
    currentReplacementUri = currentReplacementUri,
    saved = saved,
)


internal sealed interface CaptureCameraCreateOutcome {
    data class Ready(val uriString: String) : CaptureCameraCreateOutcome
    data class LaunchFailed(val uriString: String) : CaptureCameraCreateOutcome
    data object CreateFailed : CaptureCameraCreateOutcome
}

internal fun captureCameraCreateOutcome(
    createdUriString: String?,
    launchSucceeded: Boolean,
): CaptureCameraCreateOutcome = when {
    createdUriString == null -> CaptureCameraCreateOutcome.CreateFailed
    !launchSucceeded -> CaptureCameraCreateOutcome.LaunchFailed(createdUriString)
    else -> CaptureCameraCreateOutcome.Ready(createdUriString)
}

internal sealed interface CapturePickerLaunchOutcome {
    data object Launched : CapturePickerLaunchOutcome
    data object Unavailable : CapturePickerLaunchOutcome
}

internal fun capturePickerLaunchOutcome(launchSucceeded: Boolean): CapturePickerLaunchOutcome =
    if (launchSucceeded) {
        CapturePickerLaunchOutcome.Launched
    } else {
        CapturePickerLaunchOutcome.Unavailable
    }
