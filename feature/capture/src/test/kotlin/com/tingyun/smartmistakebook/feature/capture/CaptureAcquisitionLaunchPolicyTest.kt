package com.tingyun.smartmistakebook.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAcquisitionLaunchPolicyTest {
    @Test
    fun unsavedWorkspaceMustFlushBeforeCameraOrPicker() {
        assertEquals(
            CaptureAcquisitionLaunchDecision.FLUSH_WORKSPACE_FIRST,
            captureAcquisitionLaunchDecision(
                hasWorkspace = true,
                workspaceAlreadyFlushed = false,
                cameraLaunchInProgress = false,
                photoImportInProgress = false,
                workflowInProgress = false,
            ),
        )
    }

    @Test
    fun busyAcquisitionDoesNotStartASecondLaunch() {
        assertEquals(
            CaptureAcquisitionLaunchDecision.BUSY,
            captureAcquisitionLaunchDecision(
                hasWorkspace = false,
                workspaceAlreadyFlushed = false,
                cameraLaunchInProgress = true,
                photoImportInProgress = false,
                workflowInProgress = false,
            ),
        )
        assertEquals(
            CaptureAcquisitionLaunchDecision.BUSY,
            captureAcquisitionLaunchDecision(
                hasWorkspace = true,
                workspaceAlreadyFlushed = true,
                cameraLaunchInProgress = false,
                photoImportInProgress = false,
                workflowInProgress = true,
            ),
        )
    }

    @Test
    fun idleFlushedSessionCanLaunch() {
        assertEquals(
            CaptureAcquisitionLaunchDecision.LAUNCH,
            captureAcquisitionLaunchDecision(
                hasWorkspace = true,
                workspaceAlreadyFlushed = true,
                cameraLaunchInProgress = false,
                photoImportInProgress = false,
                workflowInProgress = false,
            ),
        )
    }

    @Test
    fun keepCurrentExplainsOnlyWhenASavedFileCameBack() {
        assertEquals(
            "相机返回的图片为空或超过 20 MB 安全上限；原题仍保留，请重试。",
            captureKeepCurrentUserMessage(saved = true, completedUri = "file:///tmp/x.jpg"),
        )
        assertNull(captureKeepCurrentUserMessage(saved = false, completedUri = "file:///tmp/x.jpg"))
        assertNull(captureKeepCurrentUserMessage(saved = true, completedUri = null))
    }

    @Test
    fun replaceDraftPreparesAFreshReplacementRequest() {
        val prep = captureReplacementLaunchPrep(
            purpose = CaptureAcquisitionPurpose.REPLACE_DRAFT,
            nowEpochMillis = 42L,
            newRequestId = { "replace-1" },
        )
        assertEquals("replace-1", prep.requestId)
        assertEquals(42L, prep.occurredAtEpochMillis)
        assertTrue(prep.clearReplacementError)
    }

    @Test
    fun newCaptureDoesNotOpenAReplacementRequest() {
        val prep = captureReplacementLaunchPrep(
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
            nowEpochMillis = 42L,
            newRequestId = { error("should not allocate") },
        )
        assertNull(prep.requestId)
        assertNull(prep.occurredAtEpochMillis)
        assertFalse(prep.clearReplacementError)
    }

    @Test
    fun cameraCreateOutcomeDistinguishesReadyLaunchFailedAndCreateFailed() {
        assertEquals(
            CaptureCameraCreateOutcome.Ready("file:///ok.jpg"),
            captureCameraCreateOutcome("file:///ok.jpg", launchSucceeded = true),
        )
        assertEquals(
            CaptureCameraCreateOutcome.LaunchFailed("file:///ok.jpg"),
            captureCameraCreateOutcome("file:///ok.jpg", launchSucceeded = false),
        )
        assertEquals(
            CaptureCameraCreateOutcome.CreateFailed,
            captureCameraCreateOutcome(null, launchSucceeded = false),
        )
    }

    @Test
    fun pickerLaunchOutcomeIsUnavailableWhenTheSystemPickerFails() {
        assertEquals(
            CapturePickerLaunchOutcome.Launched,
            capturePickerLaunchOutcome(true),
        )
        assertEquals(
            CapturePickerLaunchOutcome.Unavailable,
            capturePickerLaunchOutcome(false),
        )
    }

    @Test
    fun cameraAndPickerLaunchFailuresUseTheStudentFacingCopy() {
        assertEquals(
            CAPTURE_NO_SYSTEM_CAMERA_ERROR,
            captureCameraUnavailablePlan("file:///tmp/x.jpg").error,
        )
        assertEquals("file:///tmp/x.jpg", captureCameraUnavailablePlan("file:///tmp/x.jpg").deleteUri)
        assertEquals(
            CAPTURE_CANNOT_CREATE_PHOTO_FILE_ERROR,
            captureCreatePhotoFileFailedPlan().error,
        )
        assertEquals(
            CAPTURE_PHOTO_PICKER_UNAVAILABLE_ERROR,
            capturePhotoPickerUnavailablePlan().error,
        )
    }
}
