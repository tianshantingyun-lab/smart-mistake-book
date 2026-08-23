package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord
import com.tingyun.smartmistakebook.core.model.VisualInteractionAttempt
import com.tingyun.smartmistakebook.core.model.VisualStudentAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the best-effort Room sink behind the visual interaction
 * loop (audit §12 / PR-11): mapping fidelity plus the silent-degrade
 * contract shared with the prediction-audit sink.
 */
class RoomVisualInteractionEventSinkTest {

    private val attempt = VisualInteractionAttempt(
        attemptId = "attempt-1",
        problemRevisionId = "revision-9",
        action = VisualStudentAction.DragPoint(
            elementId = "beaker",
            toX = 3.0,
            toY = 4.0,
        ),
        feasible = true,
        feedback = "操作正确",
        attemptedAtEpochMillis = 1_500,
    )

    @Test
    fun recordMapsAttemptOntoThePortRecord() {
        val stored = mutableListOf<VisualInteractionAttemptRecord>()
        val sink = RoomVisualInteractionEventSink { stored += it }

        runBlocking { sink.record(attempt) }

        val record = stored.single()
        assertEquals("attempt-1", record.attemptId)
        assertEquals("revision-9", record.problemRevisionId)
        assertEquals("DragPoint", record.actionKind)
        assertTrue("payload must carry the action body", record.actionPayload.contains("beaker"))
        assertTrue(record.feasible)
        assertEquals("操作正确", record.feedback)
        assertEquals(1_500, record.attemptedAtEpochMillis)
    }

    @Test
    fun storageFailureDegradesSilentlyInsteadOfBlockingTheInteraction() {
        val sink = RoomVisualInteractionEventSink { error("disk full") }

        // Must not throw: interaction recording is best-effort by contract.
        runBlocking { sink.record(attempt) }
    }
}
