package com.tingyun.smartmistakebook.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReturnedImagePlanTest {
    @Test
    fun cameraApplyAsNewDeletesThePreviousReceivedImageWhenItChanged() {
        val plan = captureCameraReturnedImagePlan(
            action = CaptureResultAction.APPLY_AS_NEW,
            completedUri = "file:///new.jpg",
            currentReceivedUri = "file:///old.jpg",
            currentReplacementUri = null,
            saved = true,
        )
        assertTrue(plan is CaptureReturnedImagePlan.ApplyAsNew)
        plan as CaptureReturnedImagePlan.ApplyAsNew
        assertEquals("file:///new.jpg", plan.uri)
        assertEquals("file:///old.jpg", plan.deletePreviousUri)
        assertNull(plan.alsoDeleteUri)
    }

    @Test
    fun cameraReplaceExistingOnlyDropsTheOldReplacementCandidate() {
        val plan = captureCameraReturnedImagePlan(
            action = CaptureResultAction.REPLACE_EXISTING,
            completedUri = "file:///next.jpg",
            currentReceivedUri = "file:///kept.jpg",
            currentReplacementUri = "file:///prev.jpg",
            saved = true,
        )
        assertTrue(plan is CaptureReturnedImagePlan.ReplaceExisting)
        plan as CaptureReturnedImagePlan.ReplaceExisting
        assertEquals("file:///next.jpg", plan.uri)
        assertEquals("file:///prev.jpg", plan.deletePreviousUri)
    }

    @Test
    fun cameraKeepCurrentDeletesTheRejectedFileAndExplainsWhenSaved() {
        val plan = captureCameraReturnedImagePlan(
            action = CaptureResultAction.KEEP_CURRENT,
            completedUri = "file:///bad.jpg",
            currentReceivedUri = "file:///kept.jpg",
            currentReplacementUri = null,
            saved = true,
        )
        assertTrue(plan is CaptureReturnedImagePlan.KeepCurrent)
        plan as CaptureReturnedImagePlan.KeepCurrent
        assertEquals("file:///bad.jpg", plan.deleteUri)
        assertEquals(
            "相机返回的图片为空或超过 20 MB 安全上限；原题仍保留，请重试。",
            plan.error,
        )
    }

    @Test
    fun pickerAppendKeepsTheImportedFileForTheDraft() {
        val plan = capturePickerReturnedImagePlan(
            importedUri = "file:///picked.jpg",
            purpose = CaptureAcquisitionPurpose.APPEND_DRAFT,
            currentReceivedUri = "file:///current.jpg",
            pendingCameraUri = "file:///pending.jpg",
        )
        assertEquals(CaptureReturnedImagePlan.Append("file:///picked.jpg"), plan)
    }

    @Test
    fun pickerNewCaptureAlsoDropsThePendingCameraFile() {
        val plan = capturePickerReturnedImagePlan(
            importedUri = "file:///picked.jpg",
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
            currentReceivedUri = "file:///current.jpg",
            pendingCameraUri = "file:///pending.jpg",
        )
        assertTrue(plan is CaptureReturnedImagePlan.ApplyAsNew)
        plan as CaptureReturnedImagePlan.ApplyAsNew
        assertEquals("file:///current.jpg", plan.deletePreviousUri)
        assertEquals("file:///pending.jpg", plan.alsoDeleteUri)
    }

    @Test
    fun cameraActivityResultUsesEligibilityAndPurpose() {
        val plan = captureCameraActivityResultPlan(
            saved = true,
            completedUri = "file:///new.jpg",
            eligible = true,
            purpose = CaptureAcquisitionPurpose.REPLACE_DRAFT,
            currentReceivedUri = "file:///kept.jpg",
            currentReplacementUri = "file:///prev.jpg",
        )
        assertTrue(plan is CaptureReturnedImagePlan.ReplaceExisting)
        plan as CaptureReturnedImagePlan.ReplaceExisting
        assertEquals("file:///new.jpg", plan.uri)
        assertEquals("file:///prev.jpg", plan.deletePreviousUri)
    }

    @Test
    fun cancelledPickerDoesNotDeleteAnything() {
        val plan = capturePickerCancelledPlan()
        assertTrue(plan is CaptureReturnedImagePlan.KeepCurrent)
        plan as CaptureReturnedImagePlan.KeepCurrent
        assertNull(plan.deleteUri)
        assertNull(plan.error)
        assertTrue(plan.cancelAcquisition)
        assertTrue(plan.resetPurpose)
    }
}
