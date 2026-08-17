package com.tingyun.smartmistakebook.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureWorkspaceFlushPolicyTest {
    @Test
    fun emptyWorkspaceLeavesImmediately() {
        assertEquals(
            CaptureWorkspaceLeaveDecision.LEAVE_NOW,
            captureWorkspaceLeaveDecision(hasWorkspace = false),
        )
    }

    @Test
    fun dirtyWorkspaceMustFlushBeforeLeaving() {
        assertEquals(
            CaptureWorkspaceLeaveDecision.FLUSH_THEN_LEAVE,
            captureWorkspaceLeaveDecision(hasWorkspace = true),
        )
    }

    @Test
    fun flushDoesNotStartWhileSavingOrWorkflowBusy() {
        assertFalse(captureWorkspaceFlushCanStart(saving = true, workflowInProgress = false))
        assertFalse(captureWorkspaceFlushCanStart(saving = false, workflowInProgress = true))
        assertTrue(captureWorkspaceFlushCanStart(saving = false, workflowInProgress = false))
    }

    @Test
    fun failedWorkspaceWriteUsesTheStudentFacingError() {
        val applied = applyWorkspaceWriteResult(
            CaptureWorkspaceWriteResult.Failed(IllegalStateException("disk")),
        )
        assertNull(applied.identity)
        assertNull(applied.updatedAtEpochMillis)
        assertEquals(CAPTURE_WORKSPACE_SAVE_ERROR, applied.error)
    }

    @Test
    fun appendRejectsMissingDraftTooManyPagesAndBusyWorkflow() {
        assertEquals(
            CaptureAppendPageDecision.MissingDraft,
            captureAppendPageDecision(
                draftId = null,
                revisionNumber = 1,
                pageCount = 1,
                workflowInProgress = false,
            ),
        )
        assertEquals(
            CaptureAppendPageDecision.TooManyPages,
            captureAppendPageDecision(
                draftId = "draft",
                revisionNumber = 1,
                pageCount = MAX_CAPTURE_SOURCE_PAGES,
                workflowInProgress = false,
            ),
        )
        assertEquals(
            CaptureAppendPageDecision.Busy,
            captureAppendPageDecision(
                draftId = "draft",
                revisionNumber = 1,
                pageCount = 1,
                workflowInProgress = true,
            ),
        )
        assertEquals(
            CaptureAppendPageDecision.Append,
            captureAppendPageDecision(
                draftId = "draft",
                revisionNumber = 1,
                pageCount = 1,
                workflowInProgress = false,
            ),
        )
    }
}
