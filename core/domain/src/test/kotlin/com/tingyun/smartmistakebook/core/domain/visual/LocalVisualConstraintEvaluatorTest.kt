package com.tingyun.smartmistakebook.core.domain.visual

import com.tingyun.smartmistakebook.core.model.VisualStudentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisualConstraintEvaluatorTest {

    private val evaluator = LocalVisualConstraintEvaluator()

    private val constraints = VisualProblemConstraints(
        problemRevisionId = "rev-1",
        dragTargetX = mapOf("point-a" to 0.0..10.0),
        dragTargetY = mapOf("point-a" to 0.0..10.0),
        draggableElementIds = setOf("point-a"),
        parameterRanges = mapOf("angle" to 0.0..90.0),
        expectedConnections = setOf("point-a" to "point-b"),
        expectedOrder = listOf("step-1", "step-2", "step-3"),
        hypothesisKeywords = listOf("平行"),
    )

    @Test
    fun `drag inside target region is satisfied`() {
        val result = evaluator.evaluate(
            VisualStudentAction.DragPoint("point-a", 5.0, 5.0),
            constraints,
        )
        assertEquals(VisualEvaluationVerdict.SATISFIED, result.verdict)
    }

    @Test
    fun `drag outside x range is violated with constraint id`() {
        val result = evaluator.evaluate(
            VisualStudentAction.DragPoint("point-a", 50.0, 5.0),
            constraints,
        )
        assertEquals(VisualEvaluationVerdict.VIOLATED, result.verdict)
        assertTrue(result.violatedConstraintIds.contains("drag:x-range:point-a"))
    }

    @Test
    fun `drag of non-draggable element is violated`() {
        val result = evaluator.evaluate(
            VisualStudentAction.DragPoint("static-node", 1.0, 1.0),
            constraints,
        )
        assertEquals(VisualEvaluationVerdict.VIOLATED, result.verdict)
        assertTrue(result.violatedConstraintIds.contains("drag:not-draggable:static-node"))
    }

    @Test
    fun `parameter outside allowed range is violated`() {
        val result = evaluator.evaluate(
            VisualStudentAction.AdjustParameter("angle", 120.0),
            constraints,
        )
        assertEquals(VisualEvaluationVerdict.VIOLATED, result.verdict)
        assertTrue(result.violatedConstraintIds.contains("parameter:range:angle"))
    }

    @Test
    fun `connect in either direction matches expected connection`() {
        val ab = evaluator.evaluate(
            VisualStudentAction.Connect("point-a", "point-b"),
            constraints,
        )
        val ba = evaluator.evaluate(
            VisualStudentAction.Connect("point-b", "point-a"),
            constraints,
        )
        assertEquals(VisualEvaluationVerdict.SATISFIED, ab.verdict)
        assertEquals(VisualEvaluationVerdict.SATISFIED, ba.verdict)
    }

    @Test
    fun `wrong order is violated`() {
        val result = evaluator.evaluate(
            VisualStudentAction.OrderItems(listOf("step-2", "step-1")),
            constraints,
        )
        assertEquals(VisualEvaluationVerdict.VIOLATED, result.verdict)
    }

    @Test
    fun `hypothesis missing all keywords is violated`() {
        val miss = evaluator.evaluate(
            VisualStudentAction.SubmitHypothesis("两条线相交"),
            constraints,
        )
        val hit = evaluator.evaluate(
            VisualStudentAction.SubmitHypothesis("这两条线平行"),
            constraints,
        )
        assertEquals(VisualEvaluationVerdict.VIOLATED, miss.verdict)
        assertEquals(VisualEvaluationVerdict.SATISFIED, hit.verdict)
    }

    @Test
    fun `measure without typed constraint is undecidable and recorded`() =
        kotlinx.coroutines.runBlocking {
            val result = evaluator.evaluate(
                VisualStudentAction.Measure("a", "b", 3.0),
                constraints,
            )
            assertEquals(VisualEvaluationVerdict.UNDECIDABLE, result.verdict)
            val sink = InMemoryVisualInteractionEventSink()
            val attempt = evaluator.toAttempt(
                attemptId = "attempt-1",
                action = VisualStudentAction.Measure("a", "b", 3.0),
                constraints = constraints,
                result = result,
                atEpochMillis = 42L,
            )
            sink.record(attempt)
            assertEquals(1, sink.attempts.size)
            assertEquals("rev-1", sink.attempts.single().problemRevisionId)
        }
}
