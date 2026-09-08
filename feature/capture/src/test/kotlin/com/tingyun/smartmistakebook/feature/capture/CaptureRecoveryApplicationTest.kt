package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRecoveryApplicationTest {
    @Test
    fun assessmentRecoveryClearsOnlyTheMatchingPageSnapshot() {
        val pages = listOf(page(0, "asset-a"), page(1, "asset-b"))
        val snapshots = listOf<ModelTaskSnapshot?>(null, null)
        val request = captureAssessmentRequest(
            requestId = "assess-recover",
            draftId = "draft-1",
            sourceAssetId = "asset-b",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 10,
            imageHeight = 20,
            occurredAtEpochMillis = 5,
            agentConsentGranted = false,
        )

        val applied = captureRecoveryApplication(request, pages, snapshots)

        assertTrue(applied is CaptureRecoveryApplication.Assessment)
        val assessment = applied as CaptureRecoveryApplication.Assessment
        assertEquals("asset-b", assessment.sourceAssetId)
        assertEquals("assess-recover", assessment.request.requestId)
        assertEquals(listOf(null, null), assessment.pageSnapshots)
        assertNull(assessment.pageSnapshots[1])
    }

    @Test
    fun parseRecoveryKeepsTheRequestAsParse() {
        val request = captureParseRequest(
            requestId = "parse-recover",
            draftId = "draft-1",
            origin = CaptureAssessmentOrigin.TUTOR,
            basisRevisionNumber = 1,
            sourcePages = listOf(page(0, "asset-a")),
            assessmentRequestIds = listOf("assess-a"),
            occurredAtEpochMillis = 8,
            agentConsentGranted = false,
        )

        val applied = captureRecoveryApplication(request, listOf(page(0, "asset-a")), emptyList())

        assertTrue(applied is CaptureRecoveryApplication.Parse)
        assertEquals("parse-recover", (applied as CaptureRecoveryApplication.Parse).request.requestId)
    }

    private fun page(index: Int, assetId: String) = CaptureSourcePage(
        pageIndex = index,
        imageUri = "file:///$assetId.png",
        sourceAssetId = assetId,
        sourceAssetSha256 = "a".repeat(64),
        width = 10,
        height = 20,
        byteSize = 100L,
        createdAtEpochMillis = 1,
    )
}
