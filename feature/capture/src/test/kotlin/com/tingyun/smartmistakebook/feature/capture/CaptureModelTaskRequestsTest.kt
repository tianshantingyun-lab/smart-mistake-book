package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureModelTaskRequestsTest {
    @Test
    fun assessmentRequestCarriesExactDraftAssetAndManifest() {
        val request = captureAssessmentRequest(
            requestId = "assess-1",
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 100,
            imageHeight = 200,
            occurredAtEpochMillis = 10,
            egressManifest = null,
        )

        assertEquals("assess-1", request.requestId)
        assertEquals(10L, request.occurredAtEpochMillis)
        val input = request.input as CaptureAssessmentInput
        assertEquals("draft-1", input.draftId)
        assertEquals("asset-1", input.sourceAssetId)
        assertEquals(CaptureAssessmentOrigin.LIBRARY, input.origin)
        assertEquals(100, input.imageWidth)
        assertEquals(200, input.imageHeight)
    }

    @Test
    fun parseRequestMapsPagesInOrderAndKeepsAllAssessmentIds() {
        val pages = listOf(
            page(0, "asset-a", "a".repeat(64)),
            page(1, "asset-b", "b".repeat(64)),
        )

        val request = captureParseRequest(
            requestId = "parse-1",
            draftId = "draft-1",
            origin = CaptureAssessmentOrigin.TUTOR,
            basisRevisionNumber = 2,
            sourcePages = pages,
            assessmentRequestIds = listOf("assess-a", "assess-b"),
            occurredAtEpochMillis = 20,
            egressManifest = null,
        )

        val input = request.input as CaptureParseInput
        assertEquals(2, input.basisRevisionNumber)
        assertEquals(listOf("assess-a", "assess-b"), input.assessmentRequestIds)
        assertEquals(
            pages.map { page ->
                CaptureSourceAssetRef(
                    assetId = page.sourceAssetId,
                    sha256 = page.sourceAssetSha256,
                    width = page.width,
                    height = page.height,
                    pageIndex = page.pageIndex,
                )
            },
            input.sourceAssets,
        )
    }

    private fun page(index: Int, assetId: String, sha: String) = CaptureSourcePage(
        pageIndex = index,
        imageUri = "file:///$assetId.png",
        sourceAssetId = assetId,
        sourceAssetSha256 = sha,
        width = 10 + index,
        height = 20 + index,
        byteSize = 100L + index,
        createdAtEpochMillis = 1,
    )
}
