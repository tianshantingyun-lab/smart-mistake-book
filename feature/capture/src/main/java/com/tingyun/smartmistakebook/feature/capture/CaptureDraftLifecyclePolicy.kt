package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureFailureCode
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot

internal fun captureResumeShouldSkipLoad(
    requestedDraftId: String,
    currentDraftId: String?,
    receivedImageUri: String?,
): Boolean = currentDraftId == requestedDraftId && receivedImageUri != null

internal sealed interface CaptureResumeLoadedDecision {
    data object Missing : CaptureResumeLoadedDecision
    data object SourceUnavailable : CaptureResumeLoadedDecision
    data class RedirectTutor(val sessionId: String) : CaptureResumeLoadedDecision
    data object Apply : CaptureResumeLoadedDecision
}

internal fun captureResumeLoadedDecision(
    loadFailed: Boolean,
    tutorSessionId: String?,
    draftFound: Boolean,
): CaptureResumeLoadedDecision = when {
    loadFailed -> CaptureResumeLoadedDecision.SourceUnavailable
    !draftFound -> CaptureResumeLoadedDecision.Missing
    tutorSessionId != null -> CaptureResumeLoadedDecision.RedirectTutor(tutorSessionId)
    else -> CaptureResumeLoadedDecision.Apply
}

internal fun captureResumeRecognitionStateName(transcription: String): String =
    if (transcription.isBlank()) {
        CaptureRecognitionState.NOT_ATTEMPTED.name
    } else {
        CaptureRecognitionState.CANDIDATE_AVAILABLE.name
    }

internal data class CaptureResumeModelTaskSelection(
    val pageIndex: Int,
    val assessmentSnapshot: ModelTaskSnapshot?,
    val assessmentRequestId: String,
    val assessmentSourceAssetId: String,
    val assessmentOccurredAtEpochMillis: Long,
    val parseSnapshot: ModelTaskSnapshot?,
    val parseRequestId: String,
)

internal fun captureResumeModelTaskSelection(
    draftId: String,
    revisionNumber: Int,
    sourcePages: List<CaptureSourcePage>,
    pageTasks: List<ModelTaskSnapshot?>,
    latestParseTask: ModelTaskSnapshot?,
): CaptureResumeModelTaskSelection {
    val pageIndex = captureResumePendingAssessmentPageIndex(
        pageCount = sourcePages.size,
        pageTasks = pageTasks,
    )
    val page = sourcePages[pageIndex]
    val assessment = pageTasks[pageIndex]
    return CaptureResumeModelTaskSelection(
        pageIndex = pageIndex,
        assessmentSnapshot = assessment,
        assessmentRequestId = assessment?.request?.requestId
            ?: resumeAssessmentRequestId(draftId, pageIndex),
        assessmentSourceAssetId = page.sourceAssetId,
        assessmentOccurredAtEpochMillis = assessment?.request?.occurredAtEpochMillis
            ?: page.createdAtEpochMillis,
        parseSnapshot = latestParseTask,
        parseRequestId = latestParseTask?.request?.requestId
            ?: resumeParseRequestId(draftId, revisionNumber),
    )
}

internal data class CaptureImportedNewModelTasks(
    val pageSnapshots: List<ModelTaskSnapshot?>,
    val assessmentRequestId: String,
    val assessmentSourceAssetId: String,
    val parseRequestId: String,
)

internal fun captureImportedNewModelTasks(
    requestId: String,
    sourceAssetId: String,
    pageCount: Int,
): CaptureImportedNewModelTasks = CaptureImportedNewModelTasks(
    pageSnapshots = List(pageCount) { null },
    assessmentRequestId = "capture-assess:$requestId",
    assessmentSourceAssetId = sourceAssetId,
    parseRequestId = "capture-parse:$requestId",
)

internal data class CaptureImportedAppendModelTasks(
    val selectedPageIndex: Int,
    val pageSnapshots: List<ModelTaskSnapshot?>,
    val assessmentRequestId: String,
    val assessmentSourceAssetId: String,
    val parseRequestId: String,
)

internal fun captureImportedAppendModelTasks(
    requestId: String,
    sourcePages: List<CaptureSourcePage>,
    existingSnapshots: List<ModelTaskSnapshot?>,
): CaptureImportedAppendModelTasks {
    val appendedPage = sourcePages.last()
    return CaptureImportedAppendModelTasks(
        selectedPageIndex = appendedPage.pageIndex,
        pageSnapshots = captureAppendedPageAssessmentSnapshots(
            existing = existingSnapshots,
            newPageCount = sourcePages.size,
            appendedPageIndex = appendedPage.pageIndex,
        ),
        assessmentRequestId = "capture-assess:$requestId:p${appendedPage.pageIndex}",
        assessmentSourceAssetId = appendedPage.sourceAssetId,
        parseRequestId = "capture-parse:$requestId:pages${sourcePages.size}",
    )
}

internal data class CaptureSavedWorkflowApplication(
    val commitLibraryEntry: Boolean,
    val nextRevisionNumber: Int?,
)

internal fun captureSavedWorkflowApplication(
    phase: CaptureWorkflowPhase,
    origin: CaptureEntryOrigin,
    currentRevisionNumber: Int?,
): CaptureSavedWorkflowApplication? {
    if (phase != CaptureWorkflowPhase.SAVED) return null
    val commitLibrary = origin == CaptureEntryOrigin.LIBRARY
    return CaptureSavedWorkflowApplication(
        commitLibraryEntry = commitLibrary,
        nextRevisionNumber = if (commitLibrary) (currentRevisionNumber ?: 0) + 1 else null,
    )
}

internal fun captureFailedWorkflowMarksUnknownOutcome(
    phase: CaptureWorkflowPhase,
    failureCode: CaptureFailureCode?,
): Boolean = phase == CaptureWorkflowPhase.FAILED &&
    failureCode == CaptureFailureCode.COMMIT_REJECTED

internal data class CaptureParseTextAdoption(
    val transcription: String,
    val title: String?,
)

internal fun captureParseTextAdoption(
    transcriptionEditedByUser: Boolean,
    titleEditedByUser: Boolean,
    structuredProjection: String,
    documentTitle: String?,
): CaptureParseTextAdoption? {
    if (transcriptionEditedByUser || structuredProjection.isBlank()) return null
    val title = if (titleEditedByUser) {
        null
    } else {
        documentTitle
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_CAPTURE_TITLE_CHARS)
            ?: suggestCaptureTitle(structuredProjection)
    }
    return CaptureParseTextAdoption(
        transcription = structuredProjection,
        title = title,
    )
}


internal const val CAPTURE_RESTORED_IMAGE_MISSING = "刚才的照片已经不在了，请重新拍一张。"

internal sealed interface CaptureRestoredUriDecision {
    data object Keep : CaptureRestoredUriDecision
    data class Clear(val error: String) : CaptureRestoredUriDecision
}

internal fun captureRestoredUriDecision(
    uri: String?,
    isOwnedCapture: Boolean,
    fileExists: Boolean,
): CaptureRestoredUriDecision {
    if (uri.isNullOrBlank() || !isOwnedCapture) return CaptureRestoredUriDecision.Keep
    return if (fileExists) {
        CaptureRestoredUriDecision.Keep
    } else {
        CaptureRestoredUriDecision.Clear(CAPTURE_RESTORED_IMAGE_MISSING)
    }
}
