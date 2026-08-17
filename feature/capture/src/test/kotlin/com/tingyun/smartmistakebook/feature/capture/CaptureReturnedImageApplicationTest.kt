package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReturnedImageApplicationTest {
    @Test
    fun applyAsNewResetsDraftAndDropsPreviousFiles() {
        val application = captureReturnedImageApplication(
            plan = CaptureReturnedImagePlan.ApplyAsNew(
                uri = "file:///new.jpg",
                deletePreviousUri = "file:///old.jpg",
                alsoDeleteUri = "file:///pending.jpg",
                resetDraft = true,
            ),
            source = CaptureInputSource.PHOTO_PICKER,
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        )
        assertEquals(listOf("file:///old.jpg", "file:///pending.jpg"), application.deleteUris)
        assertTrue(application.clearPendingCamera)
        assertEquals("file:///new.jpg", application.receivedUri)
        assertEquals(CaptureInputSource.PHOTO_PICKER, application.receivedSource)
        assertTrue(application.resetDraft)
        assertEquals(CaptureAcquisitionPurpose.NEW_CAPTURE, application.bindPurpose)
        assertEquals("file:///new.jpg", application.bindUri)
        assertTrue(application.clearCaptureError)
        assertNull(application.appendUri)
    }

    @Test
    fun replaceExistingOnlyTouchesTheCandidate() {
        val application = captureReturnedImageApplication(
            plan = CaptureReturnedImagePlan.ReplaceExisting(
                uri = "file:///next.jpg",
                deletePreviousUri = "file:///prev.jpg",
            ),
            source = CaptureInputSource.CAMERA,
            purpose = CaptureAcquisitionPurpose.REPLACE_DRAFT,
        )
        assertEquals(listOf("file:///prev.jpg"), application.deleteUris)
        assertEquals("file:///next.jpg", application.replacementUri)
        assertEquals(CaptureInputSource.CAMERA, application.replacementSource)
        assertTrue(application.clearReplacementError)
        assertTrue(application.clearCaptureError)
        assertFalse(application.resetDraft)
        assertNull(application.receivedUri)
    }

    @Test
    fun appendDelegatesToPageImport() {
        val application = captureReturnedImageApplication(
            plan = CaptureReturnedImagePlan.Append("file:///page.jpg"),
            source = CaptureInputSource.CAMERA,
            purpose = CaptureAcquisitionPurpose.APPEND_DRAFT,
        )
        assertEquals("file:///page.jpg", application.appendUri)
        assertEquals(CaptureInputSource.CAMERA, application.appendSource)
        assertEquals(CaptureAcquisitionPurpose.APPEND_DRAFT, application.bindPurpose)
        assertTrue(application.deleteUris.isEmpty())
        assertFalse(application.resetDraft)
    }

    @Test
    fun keepCurrentCancelsAndExplainsWithoutEngineeringTerms() {
        val application = captureReturnedImageApplication(
            plan = CaptureReturnedImagePlan.KeepCurrent(
                deleteUri = "file:///bad.jpg",
                error = "相机返回的图片为空或超过 20 MB 安全上限；原题仍保留，请重试。",
            ),
            source = CaptureInputSource.CAMERA,
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
        )
        assertEquals(listOf("file:///bad.jpg"), application.deleteUris)
        assertTrue(application.cancelAcquisition)
        assertTrue(application.resetPurpose)
        assertTrue(application.clearReplacementRequest)
        assertEquals(
            "相机返回的图片为空或超过 20 MB 安全上限；原题仍保留，请重试。",
            application.captureError,
        )
        assertFalse(application.captureError.orEmpty().contains("Exception"))
        assertFalse(application.captureError.orEmpty().contains("URI"))
    }
}
