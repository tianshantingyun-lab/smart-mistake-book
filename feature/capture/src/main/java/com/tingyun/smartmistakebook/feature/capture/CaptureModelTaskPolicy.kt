package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus

internal const val CAPTURE_SPLIT_NEEDS_SINGLE_PAGE =
    "这页暂时不能自动整理，原图已经保留。请先裁剪图片，只保留一道题再录入。"
internal const val CAPTURE_SPLIT_FAILED =
    "这页还没整理好，原图已经保留。请先裁剪图片，只保留一道题再录入。"

internal fun captureFailedTaskClearsAuthorization(status: ModelTaskStatus?): Boolean =
    status == ModelTaskStatus.RETRYABLE_FAILURE ||
        status == ModelTaskStatus.PERMANENT_FAILURE ||
        status == ModelTaskStatus.CANCELLED

internal sealed interface CaptureSplitDecision {
    data object Skip : CaptureSplitDecision
    data object NeedSinglePage : CaptureSplitDecision
    data class Run(
        val draftId: String,
        val revisionNumber: Int,
        val page: CaptureSourcePage,
        val snapshot: ModelTaskSnapshot,
        val assessment: CaptureAssessment,
    ) : CaptureSplitDecision
}

internal fun captureSplitDecision(
    snapshot: ModelTaskSnapshot?,
    draftId: String?,
    revisionNumber: Int?,
    sourcePages: List<CaptureSourcePage>,
): CaptureSplitDecision {
    val current = snapshot ?: return CaptureSplitDecision.Skip
    val output = current.output as? CaptureAssessmentOutput ?: return CaptureSplitDecision.Skip
    if (
        current.status != ModelTaskStatus.SUCCEEDED ||
        current.provider?.isDemo != false ||
        output.assessment.decision != CaptureAssessmentDecision.SPLIT
    ) {
        return CaptureSplitDecision.Skip
    }
    val currentDraftId = draftId ?: return CaptureSplitDecision.Skip
    val currentRevision = revisionNumber ?: return CaptureSplitDecision.Skip
    val page = sourcePages.singleOrNull() ?: return CaptureSplitDecision.NeedSinglePage
    val assessmentInput = current.request.input as? CaptureAssessmentInput
        ?: return CaptureSplitDecision.Skip
    if (assessmentInput.sourceAssetId != page.sourceAssetId) return CaptureSplitDecision.Skip
    return CaptureSplitDecision.Run(
        draftId = currentDraftId,
        revisionNumber = currentRevision,
        page = page,
        snapshot = current,
        assessment = output.assessment,
    )
}

internal fun captureSplitDraftRequest(
    decision: CaptureSplitDecision.Run,
): SplitCaptureDraftRequest = SplitCaptureDraftRequest(
    requestId = "capture-split:${decision.snapshot.request.requestId}",
    draftId = decision.draftId,
    expectedRevisionNumber = decision.revisionNumber,
    assessmentRequestId = decision.snapshot.request.requestId,
    sourceAssetId = decision.page.sourceAssetId,
    regions = decision.assessment.questionRegions,
    occurredAtEpochMillis = decision.snapshot.updatedAtEpochMillis,
)

internal data class CaptureParseReadiness(
    val assessments: List<ModelTaskSnapshot>,
    val assessmentRequestIds: List<String>,
    val occurredAtEpochMillis: Long,
)

internal fun captureAssessmentsReadyForParse(
    sourcePages: List<CaptureSourcePage>,
    snapshots: List<ModelTaskSnapshot?>,
): Boolean {
    if (sourcePages.isEmpty() || snapshots.size != sourcePages.size) return false
    if (snapshots.any { it == null }) return false
    return snapshots.withIndex().none { (pageIndex, snapshot) ->
        val decision = (snapshot?.output as? CaptureAssessmentOutput)?.assessment?.decision
        snapshot?.status != ModelTaskStatus.SUCCEEDED ||
            (decision != CaptureAssessmentDecision.PASS &&
                !(decision == CaptureAssessmentDecision.NEED_MORE_IMAGE &&
                    pageIndex < sourcePages.lastIndex))
    }
}

internal fun captureParseReadiness(
    sourcePages: List<CaptureSourcePage>,
    snapshots: List<ModelTaskSnapshot?>,
): CaptureParseReadiness? {
    if (!captureAssessmentsReadyForParse(sourcePages, snapshots)) return null
    val assessments = snapshots.map { it ?: return null }
    return CaptureParseReadiness(
        assessments = assessments,
        assessmentRequestIds = assessments.map { snapshot -> snapshot.request.requestId },
        occurredAtEpochMillis = assessments.maxOf { snapshot -> snapshot.request.occurredAtEpochMillis },
    )
}

internal fun captureTaskRequestToDispatch(
    requestId: String,
    pendingRecoveryRequest: ModelTaskRequest?,
    persistedRequest: ModelTaskRequest?,
    buildRequest: () -> ModelTaskRequest,
): ModelTaskRequest = pendingRecoveryRequest?.takeIf { it.requestId == requestId }
    ?: persistedRequest?.takeIf { it.requestId == requestId }
    ?: buildRequest()

internal fun captureParseRequestToDispatch(
    requestId: String,
    pendingRecoveryRequest: ModelTaskRequest?,
    persistedRequest: ModelTaskRequest?,
    buildRequest: () -> ModelTaskRequest,
): ModelTaskRequest = captureTaskRequestToDispatch(
    requestId = requestId,
    pendingRecoveryRequest = pendingRecoveryRequest,
    persistedRequest = persistedRequest,
    buildRequest = buildRequest,
)

internal fun captureUpdatedPageAssessmentSnapshots(
    sourcePages: List<CaptureSourcePage>,
    existing: List<ModelTaskSnapshot?>,
    snapshot: ModelTaskSnapshot?,
): List<ModelTaskSnapshot?> {
    val assessedAssetId = (snapshot?.request?.input as? CaptureAssessmentInput)?.sourceAssetId
        ?: return existing
    val pageIndex = sourcePages.indexOfFirst { page -> page.sourceAssetId == assessedAssetId }
    if (pageIndex < 0) return existing
    return existing.mapIndexed { index, current ->
        if (index == pageIndex) snapshot else current
    }
}

internal fun captureAppendedPageAssessmentSnapshots(
    existing: List<ModelTaskSnapshot?>,
    newPageCount: Int,
    appendedPageIndex: Int,
): List<ModelTaskSnapshot?> = List(newPageCount) { index ->
    if (index < appendedPageIndex) existing.getOrNull(index) else null
}

internal fun captureResumePendingAssessmentPageIndex(
    pageCount: Int,
    pageTasks: List<ModelTaskSnapshot?>,
): Int {
    val pending = pageTasks.indexOfFirst { task -> task?.status != ModelTaskStatus.SUCCEEDED }
    return if (pending >= 0) pending else (pageCount - 1).coerceAtLeast(0)
}
