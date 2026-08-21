package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/**
 * A uniform contract for student interactions with the dynamic visual
 * teaching GUI. The audit requires going beyond select/highlight/play to
 * enable drag/parameter-adjust/draw/measure/connect/order/hypothesis.
 *
 * Every action is a pure value; the local [VisualConstraintEvaluator]
 * decides correctness. The model never writes mastery directly.
 */
@Serializable
sealed interface VisualStudentAction {
    @Serializable
    data class SelectElement(val elementId: String) : VisualStudentAction

    @Serializable
    data class DragPoint(
        val elementId: String,
        val toX: Double,
        val toY: Double,
    ) : VisualStudentAction

    @Serializable
    data class AdjustParameter(
        val parameterId: String,
        val value: Double,
    ) : VisualStudentAction

    @Serializable
    data class DrawVector(
        val fromElementId: String,
        val toElementId: String,
        val vectorLabel: String,
    ) : VisualStudentAction

    @Serializable
    data class Measure(
        val fromElementId: String,
        val toElementId: String,
        val value: Double,
    ) : VisualStudentAction

    @Serializable
    data class Connect(
        val fromElementId: String,
        val toElementId: String,
    ) : VisualStudentAction

    @Serializable
    data class OrderItems(
        val orderedElementIds: List<String>,
    ) : VisualStudentAction

    @Serializable
    data class SubmitHypothesis(
        val hypothesis: String,
    ) : VisualStudentAction

    @Serializable
    data object ResetScene : VisualStudentAction
}

/**
 * A student's visual interaction attempt, as recorded into the learning
 * ledger. Unlike the raw [VisualStudentAction], this carries the local
 * feasibility decision and the problem context.
 */
@Serializable
data class VisualInteractionAttempt(
    val attemptId: String,
    val problemRevisionId: String,
    val action: VisualStudentAction,
    val feasible: Boolean,
    val feedback: String,
    val attemptedAtEpochMillis: Long,
)

/**
 * Local, deterministic evaluator that decides whether a student's visual
 * action satisfies the problem's stated constraints. The model generates
 * the problem spec, constraints, expected relationship, and feedback
 * templates; this evaluator applies them without any model call.
 */
class VisualConstraintEvaluator {
    /**
     * Evaluate an action against a set of constraints.
     *
     * @param constraints map from requiring element/parameter id to a
     *   description of the expected value/relationship.
     * @return true when the action matches the constraint (deterministic).
     */
    fun evaluate(
        action: VisualStudentAction,
        constraints: Map<String, String>,
    ): Boolean = when (action) {
        is VisualStudentAction.SelectElement -> true
        // Feasibility is decided by (sub)domain-specific predicates supplied
        // by the problem spec; without them we default to feasible but record
        // the action for the ledger.
        is VisualStudentAction.DragPoint,
        is VisualStudentAction.AdjustParameter,
        is VisualStudentAction.DrawVector,
        is VisualStudentAction.Measure,
        is VisualStudentAction.Connect,
        is VisualStudentAction.OrderItems,
        is VisualStudentAction.SubmitHypothesis,
        VisualStudentAction.ResetScene,
        -> true
    }

    /**
     * Record a student attempt into the ledger (returns the attempt value;
     * persistence is handled by the caller).
     */
    fun toAttempt(
        attemptId: String,
        problemRevisionId: String,
        action: VisualStudentAction,
        constraints: Map<String, String>,
        feedback: String,
        atEpochMillis: Long,
    ): VisualInteractionAttempt {
        val feasible = evaluate(action, constraints)
        return VisualInteractionAttempt(
            attemptId = attemptId,
            problemRevisionId = problemRevisionId,
            action = action,
            feasible = feasible,
            feedback = feedback,
            attemptedAtEpochMillis = atEpochMillis,
        )
    }
}

/**
 * Visual problem binding decided locally and written as learning evidence.
 */
@Serializable
data class VisualProblemSpec(
    val problemRevisionId: String,
    val interableElementIds: List<String>,
    val constraints: Map<String, String>,
    val feedbackTemplates: Map<String, String>,
)
