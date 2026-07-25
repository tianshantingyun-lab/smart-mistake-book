package com.tingyun.smartmistakebook.feature.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAutoPersistPolicyTest {
    @Test
    fun receivedImageStartsDurableImportWithoutAnotherConfirmationTap() {
        assertTrue(
            shouldAutoPersistCapture(
                receivedImageUri = "content://capture/question",
                draftId = null,
                workflowInProgress = false,
            ),
        )
    }

    @Test
    fun existingDraftOrActiveImportDoesNotStartAnotherImport() {
        assertFalse(
            shouldAutoPersistCapture(
                receivedImageUri = "content://capture/question",
                draftId = "draft-1",
                workflowInProgress = false,
            ),
        )
        assertFalse(
            shouldAutoPersistCapture(
                receivedImageUri = "content://capture/question",
                draftId = null,
                workflowInProgress = true,
            ),
        )
    }

    @Test
    fun emptyCaptureNeverStartsAnImport() {
        assertFalse(
            shouldAutoPersistCapture(
                receivedImageUri = null,
                draftId = null,
                workflowInProgress = false,
            ),
        )
    }
}
