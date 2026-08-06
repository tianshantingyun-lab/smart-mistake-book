package com.tingyun.smartmistakebook.core.model.provider

import kotlinx.serialization.json.Json

internal val TUTOR_PLAN_WIRE_KEYS = setOf(
    "responseIntent",
    "solutionRevealed",
    "openingMarkdown",
    "diagnosticQuestion",
    "visualRequest",
    "solutionMarkdown",
    "alternateMethodMarkdown",
    "difficultyReasonMarkdown",
    "targetedEvidenceLabels",
    "inferredKnowledgeLabels",
    "nextMoves",
    "interactionDirective",
    "hintMarkdown",
)
internal val TUTOR_RESPOND_WIRE_KEYS =
    setOf(
        "intentDecision",
        "responseIntent",
        "messageMarkdown",
        "solutionRevealed",
        "visualRequest",
        "nextMoves",
        "interactionDirective",
        "freeResponseEvaluation",
    )
internal val TUTOR_VISUAL_REQUEST_WIRE_KEYS = setOf("focusMarkdown")
internal val TUTOR_VISUAL_GENERATE_WIRE_KEYS = setOf("decision", "confidence", "scene")
internal val TUTOR_VISUAL_REVIEW_WIRE_KEYS = setOf("decision", "confidence", "scene")
internal val TUTOR_VISUAL_DOCUMENT_WIRE_KEYS = setOf(
    "kind",
    "title",
    "panels",
    "variables",
    "elements",
    "bindings",
    "steps",
    "durationSeconds",
    "fallbackMarkdown",
    "accessibilitySummary",
)
internal val TUTOR_VISUAL_DOCUMENT_JSON = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
}
internal val TUTOR_LOBBY_WIRE_KEYS = setOf(
    "intentDecision",
    "messageMarkdown",
    "responseIntent",
    "interactionDirective",
)
internal val TUTOR_INTENT_WIRE_KEYS = setOf(
    "intent",
    "confidence",
    "explicitActionRequest",
    "memoryPreference",
    "requestedLocalCapability",
    "lookupTerms",
)
internal val TUTOR_DIAGNOSTIC_WIRE_KEYS = setOf("stemMarkdown", "promptMarkdown", "choices")
internal val TUTOR_DIAGNOSTIC_CHOICE_WIRE_KEYS =
    setOf("markdown", "feedbackMarkdown", "isCorrect")
internal val TUTOR_MOVE_WIRE_KEYS = setOf("label", "type")
internal val TUTOR_INTERACTION_CHOICE_WIRE_KEYS = setOf("id", "labelMarkdown")
internal val TUTOR_STEP_FLOW_WIRE_KEYS = setOf("kind", "title", "steps")
internal val TUTOR_STEP_WIRE_KEYS = setOf("label", "bodyMarkdown", "formula", "emphasis")
internal val TUTOR_COMPARISON_WIRE_KEYS =
    setOf("kind", "title", "leftTitle", "rightTitle", "rows")
internal val TUTOR_COMPARISON_ROW_WIRE_KEYS =
    setOf("criterion", "leftMarkdown", "rightMarkdown", "takeawayMarkdown")
internal val TUTOR_EVIDENCE_CHAIN_WIRE_KEYS =
    setOf("kind", "title", "claimMarkdown", "evidence", "conclusionMarkdown")
internal val TUTOR_EVIDENCE_POINT_WIRE_KEYS = setOf("kind", "markdown")
internal val TUTOR_PROCESS_TIMELINE_WIRE_KEYS = setOf("kind", "title", "stages")
internal val TUTOR_PROCESS_STAGE_WIRE_KEYS =
    setOf("label", "bodyMarkdown", "transitionMarkdown")
internal val TUTOR_CONCEPT_MAP_WIRE_KEYS =
    setOf("kind", "title", "centerMarkdown", "relations")
internal val TUTOR_CONCEPT_RELATION_WIRE_KEYS =
    setOf("relationLabel", "targetMarkdown", "detailMarkdown")
internal val TUTOR_FORMULA_DERIVATION_WIRE_KEYS =
    setOf("kind", "title", "startFormula", "steps")
internal val TUTOR_FORMULA_DERIVATION_STEP_WIRE_KEYS =
    setOf("reasonMarkdown", "resultFormula")
internal val TUTOR_SPATIAL_DIAGRAM_WIRE_KEYS =
    setOf("kind", "title", "nodes", "edges", "captionMarkdown")
internal val TUTOR_SPATIAL_NODE_WIRE_KEYS = setOf("label", "anchor", "shape")
internal val TUTOR_SPATIAL_EDGE_WIRE_KEYS =
    setOf("fromIndex", "toIndex", "label", "style")
internal val TUTOR_LINEAR_MOTION_WIRE_KEYS = setOf(
    "kind",
    "title",
    "durationSeconds",
    "initialPositionMeters",
    "initialVelocityMetersPerSecond",
    "accelerationMetersPerSecondSquared",
)
internal val TUTOR_PROJECTILE_MOTION_WIRE_KEYS = setOf(
    "kind",
    "title",
    "durationSeconds",
    "initialHeightMeters",
    "horizontalVelocityMetersPerSecond",
    "verticalVelocityMetersPerSecond",
    "gravityMetersPerSecondSquared",
)
internal val TUTOR_CIRCULAR_MOTION_WIRE_KEYS = setOf(
    "kind",
    "title",
    "durationSeconds",
    "radiusMeters",
    "angularVelocityRadiansPerSecond",
    "initialAngleRadians",
)
internal val TUTOR_OSCILLATION_MOTION_WIRE_KEYS = setOf(
    "kind",
    "title",
    "durationSeconds",
    "equilibriumPositionMeters",
    "amplitudeMeters",
    "periodSeconds",
    "initialPhaseRadians",
)
internal val TUTOR_VISUAL_PROGRAM_WIRE_KEYS = setOf(
    "kind",
    "title",
    "accessibilitySummary",
    "parameters",
    "commands",
    "durationSeconds",
    "showAxes",
    "xUnit",
    "yUnit",
)
internal val TUTOR_VISUAL_PARAMETER_WIRE_KEYS = setOf("label", "value", "unit")
internal val TUTOR_VISUAL_ENTITY_WIRE_KEYS = setOf("kind", "label", "shape", "x", "y")
internal val TUTOR_VISUAL_LINK_WIRE_KEYS =
    setOf("kind", "fromIndex", "toIndex", "label", "style")
internal val TUTOR_VISUAL_PATH_WIRE_KEYS = setOf("kind", "targetIndex")
internal val TUTOR_VISUAL_VECTOR_WIRE_KEYS =
    setOf("kind", "label", "originIndex", "x", "y", "unit")
internal val TUTOR_VISUAL_METRIC_WIRE_KEYS = setOf("kind", "label", "value", "unit")
internal val TUTOR_VISUAL_NOTE_WIRE_KEYS = setOf("kind", "markdown")
internal val TUTOR_VISUAL_FORMULA_WIRE_KEYS = setOf("kind", "formula")
internal val TUTOR_VISUAL_TABLE_WIRE_KEYS = setOf("kind", "columns", "rows")
internal val TUTOR_VISUAL_CONSTANT_EXPRESSION_WIRE_KEYS = setOf("op", "value")
internal val TUTOR_VISUAL_TIME_EXPRESSION_WIRE_KEYS = setOf("op")
internal val TUTOR_VISUAL_PARAMETER_EXPRESSION_WIRE_KEYS = setOf("op", "parameterIndex")
internal val TUTOR_VISUAL_ARGUMENT_EXPRESSION_WIRE_KEYS = setOf("op", "argument")
internal val TUTOR_VISUAL_BINARY_EXPRESSION_WIRE_KEYS = setOf("op", "left", "right")
