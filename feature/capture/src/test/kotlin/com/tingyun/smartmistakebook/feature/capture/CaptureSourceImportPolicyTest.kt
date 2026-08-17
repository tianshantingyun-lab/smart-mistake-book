package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSourceImportPolicyTest {
    @Test
    fun newImportReusesThePersistedRequestIdentity() {
        val first = captureImportRequestIdentity(
            existingRequestId = null,
            existingOccurredAtEpochMillis = null,
            nowEpochMillis = 10,
            newRequestId = { "req-1" },
        )
        assertEquals("req-1", first.requestId)
        assertEquals(10L, first.occurredAtEpochMillis)
        assertTrue(first.persistRequestId)
        assertTrue(first.persistOccurredAt)

        val reused = captureImportRequestIdentity(
            existingRequestId = "req-1",
            existingOccurredAtEpochMillis = 10,
            nowEpochMillis = 99,
            newRequestId = { "req-2" },
        )
        assertEquals("req-1", reused.requestId)
        assertEquals(10L, reused.occurredAtEpochMillis)
        assertFalse(reused.persistRequestId)
        assertFalse(reused.persistOccurredAt)
    }

    @Test
    fun workflowStartAndCommitAreBlockedWhileBusyOrIncomplete() {
        assertTrue(captureWorkflowCanStart(false))
        assertFalse(captureWorkflowCanStart(true))
        assertEquals(
            CaptureCommitDecision.Blocked,
            captureCommitDecision(
                entryGateOpen = false,
                draftId = "draft",
                workspace = null,
                workflowInProgress = false,
            ),
        )
        assertEquals(
            CaptureCommitDecision.WorkspaceNotReady,
            captureCommitDecision(
                entryGateOpen = true,
                draftId = "draft",
                workspace = null,
                workflowInProgress = false,
            ),
        )
        assertEquals(
            CaptureCommitDecision.Blocked,
            captureCommitDecision(
                entryGateOpen = true,
                draftId = "draft",
                workspace = null,
                workflowInProgress = true,
            ),
        )
    }

    @Test
    fun tooManyPagesUsesTheStudentFacingLimitCopy() {
        assertTrue(CAPTURE_TOO_MANY_PAGES_ERROR.contains("$MAX_CAPTURE_SOURCE_PAGES"))
        assertFalse(CAPTURE_TOO_MANY_PAGES_ERROR.contains("MAX_"))
        assertFalse(CAPTURE_WORKSPACE_NOT_READY_ERROR.contains("workspace"))
        assertFalse(CAPTURE_WORKSPACE_NOT_READY_ERROR.contains("null"))
    }
}
