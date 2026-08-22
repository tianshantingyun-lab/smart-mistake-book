package com.tingyun.smartmistakebook.core.domain.visual

import com.tingyun.smartmistakebook.core.model.VisualInteractionAttempt
import com.tingyun.smartmistakebook.core.model.VisualStudentAction

/**
 * Typed, locally-decidable constraints for a visual problem (audit 12.2).
 * The model may only generate this specification; the evaluator below
 * applies it deterministically and the model never writes mastery.
 */
data class VisualProblemConstraints(
    val problemRevisionId: String,
    /** Inclusive target rectangle per draggable element, in scene coordinates. */
    val dragTargetRegions: Map<String, ClosedFloatingPointRange<Double>> = emptyMap(),
    /** Allowed x-range per draggable element. */
    val dragTargetX: Map<String, ClosedFloatingPointRange<Double>> = emptyMap(),
    /** Allowed y-range per draggable element. */
    val dragTargetY: Map<String, ClosedFloatingPointRange<Double>> = emptyMap(),
    /** Allowed inclusive range per adjustable parameter. */
    val parameterRanges: Map<String, ClosedFloatingPointRange<Double>> = emptyMap(),
    /** Expected undirected connections between element ids. */
    val expectedConnections: Set<Pair<String, String>> = emptySet(),
    /** Expected ordering of element ids (prefix match is accepted). */
    val expectedOrder: List<String> = emptyList(),
    /** Keywords any accepted hypothesis must contain (case-insensitive). */
    val hypothesisKeywords: List<String> = emptyList(),
    /** Elements that may be dragged at all. */
    val draggableElementIds: Set<String> = emptySet(),
)

enum class VisualEvaluationVerdict { SATISFIED, VIOLATED, UNDECIDABLE }

data class VisualEvaluationResult(
    val verdict: VisualEvaluationVerdict,
    val violatedConstraintIds: List<String>,
    val feedback: String,
)

/**
 * Local, deterministic judge for student visual actions. No model call is
 * involved: the spec above decides everything (audit section 12.2).
 */
class LocalVisualConstraintEvaluator {

    fun evaluate(
        action: VisualStudentAction,
        constraints: VisualProblemConstraints,
    ): VisualEvaluationResult {
        val violations = mutableListOf<String>()
        when (action) {
            is VisualStudentAction.SelectElement -> Unit

            is VisualStudentAction.DragPoint -> {
                if (action.elementId !in constraints.draggableElementIds) {
                    violations += "drag:not-draggable:${action.elementId}"
                }
                constraints.dragTargetX[action.elementId]?.let { range ->
                    if (action.toX !in range) violations += "drag:x-range:${action.elementId}"
                }
                constraints.dragTargetY[action.elementId]?.let { range ->
                    if (action.toY !in range) violations += "drag:y-range:${action.elementId}"
                }
            }

            is VisualStudentAction.AdjustParameter -> {
                constraints.parameterRanges[action.parameterId]?.let { range ->
                    if (action.value !in range) {
                        violations += "parameter:range:${action.parameterId}"
                    }
                }
            }

            is VisualStudentAction.Connect -> {
                val key = normalizePair(action.fromElementId, action.toElementId)
                val expected = constraints.expectedConnections.map {
                    normalizePair(it.first, it.second)
                }.toSet()
                if (expected.isNotEmpty() && key !in expected) {
                    violations += "connect:not-expected:$key"
                }
            }

            is VisualStudentAction.OrderItems -> {
                if (constraints.expectedOrder.isNotEmpty()) {
                    val expectedPrefix =
                        constraints.expectedOrder.take(action.orderedElementIds.size)
                    if (action.orderedElementIds != expectedPrefix) {
                        violations += "order:mismatch"
                    }
                }
            }

            is VisualStudentAction.SubmitHypothesis -> {
                if (constraints.hypothesisKeywords.isNotEmpty()) {
                    val text = action.hypothesis.lowercase()
                    val hit = constraints.hypothesisKeywords.any { keyword ->
                        text.contains(keyword.lowercase())
                    }
                    if (!hit) violations += "hypothesis:keyword-miss"
                }
            }

            is VisualStudentAction.DrawVector,
            is VisualStudentAction.Measure,
            VisualStudentAction.ResetScene,
            -> return VisualEvaluationResult(
                verdict = VisualEvaluationVerdict.UNDECIDABLE,
                violatedConstraintIds = emptyList(),
                feedback = "该操作由讲题流程判定，已记录到学习账本",
            )
        }
        return if (violations.isEmpty()) {
            VisualEvaluationResult(
                verdict = VisualEvaluationVerdict.SATISFIED,
                violatedConstraintIds = emptyList(),
                feedback = "操作正确",
            )
        } else {
            VisualEvaluationResult(
                verdict = VisualEvaluationVerdict.VIOLATED,
                violatedConstraintIds = violations,
                feedback = "再试一次：操作不满足题目条件",
            )
        }
    }

    fun toAttempt(
        attemptId: String,
        action: VisualStudentAction,
        constraints: VisualProblemConstraints,
        result: VisualEvaluationResult,
        atEpochMillis: Long,
    ): VisualInteractionAttempt = VisualInteractionAttempt(
        attemptId = attemptId,
        problemRevisionId = constraints.problemRevisionId,
        action = action,
        feasible = result.verdict != VisualEvaluationVerdict.VIOLATED,
        feedback = result.feedback,
        attemptedAtEpochMillis = atEpochMillis,
    )

    private fun normalizePair(a: String, b: String) =
        if (a <= b) a to b else b to a
}

/**
 * Bridge from judged attempts into the learning ledger. Persistence lives
 * behind this interface so domain stays storage-agnostic.
 */
interface VisualInteractionEventSink {
    suspend fun record(attempt: VisualInteractionAttempt)
}

class InMemoryVisualInteractionEventSink : VisualInteractionEventSink {
    private val recorded = mutableListOf<VisualInteractionAttempt>()
    val attempts: List<VisualInteractionAttempt> get() = recorded.toList()
    override suspend fun record(attempt: VisualInteractionAttempt) {
        recorded += attempt
    }
}
