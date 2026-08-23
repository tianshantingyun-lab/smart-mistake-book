package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/**
 * A uniform contract for student interactions with the dynamic visual
 * teaching GUI. The audit requires going beyond select/highlight/play to
 * enable drag/parameter-adjust/draw/measure/connect/order/hypothesis.
 *
 * Every action is a pure value; the local constraint evaluator
 * (core:domain LocalVisualConstraintEvaluator) decides correctness.
 * The model never writes mastery directly.
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
 * Visual problem binding decided locally and written as learning evidence.
 */
@Serializable
data class VisualProblemSpec(
    val problemRevisionId: String,
    val interableElementIds: List<String>,
    val constraints: Map<String, String>,
    val feedbackTemplates: Map<String, String>,
)
