package com.tingyun.smartmistakebook.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRetakePolicyTest {
    @Test
    fun `cancelled or invalid retake keeps the current draft`() {
        listOf(
            false to true,
            true to false,
            false to false,
        ).forEach { (saved, eligible) ->
            assertEquals(
                CaptureResultAction.KEEP_CURRENT,
                captureResultAction(
                    saved = saved,
                    isEligibleImage = eligible,
                    purpose = CaptureAcquisitionPurpose.REPLACE_DRAFT,
                ),
            )
        }
    }

    @Test
    fun `eligible image respects new replacement and append purpose`() {
        assertEquals(
            CaptureResultAction.APPLY_AS_NEW,
            captureResultAction(true, true, CaptureAcquisitionPurpose.NEW_CAPTURE),
        )
        assertEquals(
            CaptureResultAction.REPLACE_EXISTING,
            captureResultAction(true, true, CaptureAcquisitionPurpose.REPLACE_DRAFT),
        )
        assertEquals(
            CaptureResultAction.APPEND_EXISTING,
            captureResultAction(true, true, CaptureAcquisitionPurpose.APPEND_DRAFT),
        )
    }

    @Test
    fun `retake launches staged replacement directly when a draft exists`() {
        assertEquals(
            CaptureAcquisitionPurpose.REPLACE_DRAFT,
            retakeAcquisitionPurpose(hasDraft = true),
        )
        assertEquals(
            CaptureAcquisitionPurpose.NEW_CAPTURE,
            retakeAcquisitionPurpose(hasDraft = false),
        )
    }
}
