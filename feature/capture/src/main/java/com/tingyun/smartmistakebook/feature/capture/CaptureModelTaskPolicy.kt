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
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion

internal const val CAPTURE_SPLIT_NEEDS_SINGLE_PAGE =
    "自动拆分只支持单页题目，这一页有多页。可以对单页使用自动拆分或手动框选。"
internal const val CAPTURE_SPLIT_FAILED =
    "这次拆分没有完成，原图已经保留。可以重试，也可以改用手动框选每道题的范围。"
internal const val CAPTURE_SPLIT_MANUAL_REGION_LIMIT = 12

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

/**
 * 手动框选拆分请求：区域来自用户框选（按阅读顺序排序保证题号），
 * 独立 requestId 携带 nonce，避免与自动拆分的幂等重放冲突。
 */
internal fun captureManualSplitDraftRequest(
    decision: CaptureSplitDecision.Run,
    regions: List<NormalizedSourceRegion>,
    nonce: Int,
): SplitCaptureDraftRequest = SplitCaptureDraftRequest(
    requestId = "capture-split-manual:${decision.snapshot.request.requestId}:$nonce",
    draftId = decision.draftId,
    expectedRevisionNumber = decision.revisionNumber,
    assessmentRequestId = decision.snapshot.request.requestId,
    sourceAssetId = decision.page.sourceAssetId,
    regions = regions.sortedWith(compareBy({ it.top }, { it.left })),
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
