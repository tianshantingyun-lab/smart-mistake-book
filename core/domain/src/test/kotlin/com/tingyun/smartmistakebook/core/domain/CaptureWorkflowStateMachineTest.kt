package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureWorkflowStateMachineTest {
    @Test
    fun `import assessment parse commit follow the frozen phase order`() {
        val started = CaptureWorkflowStateMachine.reduce(
            CaptureWorkflowState(),
            CaptureWorkflowAction.ImportStarted(requestId = "import-1"),
        )
        assertEquals(CaptureWorkflowPhase.IMPORTING, started.phase)

        val imported = CaptureWorkflowStateMachine.reduce(
            started,
            CaptureWorkflowAction.DraftImported(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "import-1",
            ),
        )
        assertEquals(CaptureWorkflowPhase.ASSESSING, imported.phase)

        val assessed = CaptureWorkflowStateMachine.reduce(
            imported,
            CaptureWorkflowAction.AssessmentSucceeded(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "assess-1",
            ),
        )
        assertEquals(CaptureWorkflowPhase.PARSING, assessed.phase)

        val parsed = CaptureWorkflowStateMachine.reduce(
            assessed,
            CaptureWorkflowAction.ParseSucceeded(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "parse-1",
            ),
        )
        assertEquals(CaptureWorkflowPhase.REVIEWING, parsed.phase)

        val committing = CaptureWorkflowStateMachine.reduce(
            parsed,
            CaptureWorkflowAction.CommitStarted(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "commit-1",
            ),
        )
        assertEquals(CaptureWorkflowPhase.COMMITTING, committing.phase)

        val saved = CaptureWorkflowStateMachine.reduce(
            committing,
            CaptureWorkflowAction.CommitSucceeded(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "commit-1",
                savedEntryId = "entry-1",
            ),
        )
        assertEquals(CaptureWorkflowPhase.SAVED, saved.phase)
        assertEquals("entry-1", saved.savedEntryId)
    }

    @Test
    fun `stale result from an older draft fails closed and stays retryable`() {
        val current = CaptureWorkflowState(
            phase = CaptureWorkflowPhase.PARSING,
            draftId = "draft-2",
            basisRevisionNumber = 2,
            latestRequestId = "parse-2",
        )

        val stale = CaptureWorkflowStateMachine.reduce(
            current,
            CaptureWorkflowAction.ParseSucceeded(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "parse-old",
            ),
        )

        assertEquals(CaptureWorkflowPhase.FAILED, stale.phase)
        assertEquals(CaptureFailureCode.STALE_RESULT, stale.failureCode)
        assertTrue(stale.canRetry)
    }

    @Test
    fun `append advances the basis revision only for the active source operation`() {
        val started = CaptureWorkflowStateMachine.reduce(
            CaptureWorkflowState(),
            CaptureWorkflowAction.ImportStarted(requestId = "import-1"),
        )
        val imported = CaptureWorkflowStateMachine.reduce(
            started,
            CaptureWorkflowAction.DraftImported(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "import-1",
            ),
        )
        val appendStarted = CaptureWorkflowStateMachine.reduce(
            imported,
            CaptureWorkflowAction.ImportStarted(
                draftId = "draft-1",
                basisRevisionNumber = 1,
                requestId = "append-1",
            ),
        )

        val appended = CaptureWorkflowStateMachine.reduce(
            appendStarted,
            CaptureWorkflowAction.DraftImported(
                draftId = "draft-1",
                basisRevisionNumber = 2,
                requestId = "append-1",
            ),
        )

        assertEquals(CaptureWorkflowPhase.ASSESSING, appended.phase)
        assertEquals(2, appended.basisRevisionNumber)
    }

    @Test
    fun `failed phase keeps draft identity and reset returns to idle`() {
        val failed = CaptureWorkflowStateMachine.reduce(
            CaptureWorkflowState(),
            CaptureWorkflowAction.Failed(
                draftId = "draft-3",
                basisRevisionNumber = 1,
                requestId = "import-3",
                code = CaptureFailureCode.IMPORT_REJECTED,
                canRetry = true,
            ),
        )
        assertEquals(CaptureWorkflowPhase.FAILED, failed.phase)
        assertEquals("draft-3", failed.draftId)

        val reset = CaptureWorkflowStateMachine.reduce(
            failed,
            CaptureWorkflowAction.Reset(),
        )
        assertEquals(CaptureWorkflowPhase.IDLE, reset.phase)
        assertNull(reset.draftId)
        assertNull(reset.failureCode)
    }
}
