package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest

internal fun captureAssessmentRequest(
    requestId: String,
    draftId: String,
    sourceAssetId: String,
    origin: CaptureAssessmentOrigin,
    imageWidth: Int,
    imageHeight: Int,
    occurredAtEpochMillis: Long,
    agentConsentGranted: Boolean,
    userHint: String? = null,
): ModelTaskRequest = ModelTaskRequest(
    requestId = requestId,
    input = CaptureAssessmentInput(
        draftId = draftId,
        sourceAssetId = sourceAssetId,
        origin = origin,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        userHint = userHint?.trim()?.takeIf { it.isNotEmpty() },
    ),
    occurredAtEpochMillis = occurredAtEpochMillis,
    agentConsentGranted = agentConsentGranted,
)

internal fun captureParseRequest(
    requestId: String,
    draftId: String,
    origin: CaptureAssessmentOrigin,
    basisRevisionNumber: Int,
    sourcePages: List<CaptureSourcePage>,
    assessmentRequestIds: List<String>,
    occurredAtEpochMillis: Long,
    agentConsentGranted: Boolean,
): ModelTaskRequest = ModelTaskRequest(
    requestId = requestId,
    input = CaptureParseInput(
        draftId = draftId,
        origin = origin,
        basisRevisionNumber = basisRevisionNumber,
        sourceAssets = sourcePages.map { page ->
            CaptureSourceAssetRef(
                assetId = page.sourceAssetId,
                sha256 = page.sourceAssetSha256,
                width = page.width,
                height = page.height,
                pageIndex = page.pageIndex,
            )
        },
        assessmentRequestId = assessmentRequestIds.first(),
        assessmentRequestIds = assessmentRequestIds,
    ),
    occurredAtEpochMillis = occurredAtEpochMillis,
    agentConsentGranted = agentConsentGranted,
)
