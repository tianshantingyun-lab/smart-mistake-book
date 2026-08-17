package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.TutorCircularMotionScene
import com.tingyun.smartmistakebook.core.model.TutorComparisonRow
import com.tingyun.smartmistakebook.core.model.TutorComparisonScene
import com.tingyun.smartmistakebook.core.model.TutorConceptMapScene
import com.tingyun.smartmistakebook.core.model.TutorConceptRelation
import com.tingyun.smartmistakebook.core.model.TutorDiagramAnchor
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdge
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdgeStyle
import com.tingyun.smartmistakebook.core.model.TutorDiagramNode
import com.tingyun.smartmistakebook.core.model.TutorDiagramNodeShape
import com.tingyun.smartmistakebook.core.model.TutorEvidenceChainScene
import com.tingyun.smartmistakebook.core.model.TutorEvidencePoint
import com.tingyun.smartmistakebook.core.model.TutorEvidencePointKind
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationStep
import com.tingyun.smartmistakebook.core.model.TutorLinearMotionScene
import com.tingyun.smartmistakebook.core.model.TutorOscillationMotionScene
import com.tingyun.smartmistakebook.core.model.TutorProcessStage
import com.tingyun.smartmistakebook.core.model.TutorProcessTimelineScene
import com.tingyun.smartmistakebook.core.model.TutorProjectileMotionScene
import com.tingyun.smartmistakebook.core.model.TutorSceneEmphasis
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorSpatialDiagramScene
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityShape
import com.tingyun.smartmistakebook.core.model.TutorVisualExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualExpressionOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualFormulaCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest
import com.tingyun.smartmistakebook.core.model.TutorVisualLineStyle
import com.tingyun.smartmistakebook.core.model.TutorVisualLinkCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualMetricCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualNoteCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualParameter
import com.tingyun.smartmistakebook.core.model.TutorVisualPathCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTableCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualVectorCommand
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.toTutorVisualGenerationRequest(): TutorVisualGenerationRequest {
    requireOnlyKeys(TUTOR_VISUAL_REQUEST_WIRE_KEYS)
    return TutorVisualGenerationRequest(
        focusMarkdown = requiredString("focusMarkdown"),
    )
}

internal fun JsonObject.toTutorVisualGenerate(
    input: TutorVisualGenerateInput,
    modelVersion: String,
): TutorVisualGenerateOutput {
    requireOnlyKeys(TUTOR_VISUAL_GENERATE_WIRE_KEYS)
    val decision = enumValue<TutorVisualGenerationDecision>(requiredString("decision"))
    val scene = optionalObject("scene")?.toTutorVisualDocumentScene(
        sceneId = input.visualSceneId(),
    )
    return TutorVisualGenerateOutput(
        sessionId = input.sessionId,
        draftRevisionNumber = input.draftRevisionNumber,
        questionDocumentId = input.questionDocument.id,
        anchor = input.anchor,
        decision = decision,
        confidence = requiredFiniteDouble("confidence"),
        scene = scene,
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toTutorVisualReview(
    input: TutorVisualReviewInput,
    modelVersion: String,
): TutorVisualReviewOutput {
    requireOnlyKeys(TUTOR_VISUAL_REVIEW_WIRE_KEYS)
    val decision = enumValue<TutorVisualReviewDecision>(requiredString("decision"))
    val scene = optionalObject("scene")?.toTutorVisualDocumentScene(
        sceneId = input.candidateScene.sceneId,
    )
    return TutorVisualReviewOutput(
        sessionId = input.sessionId,
        draftRevisionNumber = input.draftRevisionNumber,
        questionDocumentId = input.questionDocument.id,
        anchor = input.anchor,
        decision = decision,
        confidence = requiredFiniteDouble("confidence"),
        scene = scene,
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toTutorVisualDocumentScene(sceneId: String): TutorVisualDocumentScene {
    requireOnlyKeys(TUTOR_VISUAL_DOCUMENT_WIRE_KEYS)
    require(requiredString("kind") == "visual_document")
    val normalized = toMutableMap().apply {
        remove("kind")
        put("sceneId", JsonPrimitive(sceneId))
        put("schemaVersion", JsonPrimitive(2))
    }
    return runCatching {
        TUTOR_VISUAL_DOCUMENT_JSON.decodeFromJsonElement(
            TutorVisualDocumentScene.serializer(),
            JsonObject(normalized),
        )
    }.getOrElse {
        throw InvalidModelResponseException()
    }
}

internal fun TutorVisualGenerateInput.visualSceneId(): String {
    val identity = buildString {
        append(sessionId)
        append('\n').append(draftRevisionNumber)
        append('\n').append(anchor.surface.name)
        append('\n').append(anchor.cycleOrdinal)
        append('\n').append(anchor.turnOrdinal)
        append('\n').append(anchor.responseOrdinal ?: 0)
    }
    val suffix = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(20)
    return "tutor-visual-$suffix"
}

internal fun JsonObject.toTutorVisualScene(stableSuffix: String): TutorVisualScene =
    when (requiredString("kind")) {
        "visual_program" -> toTutorVisualProgram(stableSuffix)

        "step_flow" -> {
            requireOnlyKeys(TUTOR_STEP_FLOW_WIRE_KEYS)
            TutorStepFlowScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                steps = array("steps").mapIndexed { index, element ->
                    val step = element.objectValue()
                    step.requireOnlyKeys(TUTOR_STEP_WIRE_KEYS)
                    TutorSceneStep(
                        stepId = "tutor-scene-$stableSuffix-step-${index + 1}",
                        label = step.requiredString("label"),
                        bodyMarkdown = step.requiredString("bodyMarkdown"),
                        formula = step.optionalString("formula"),
                        emphasis = step.optionalString("emphasis")
                            ?.let { enumValue<TutorSceneEmphasis>(it) }
                            ?: TutorSceneEmphasis.NORMAL,
                    )
                },
            )
        }

        "comparison" -> {
            requireOnlyKeys(TUTOR_COMPARISON_WIRE_KEYS)
            TutorComparisonScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                leftTitle = requiredString("leftTitle"),
                rightTitle = requiredString("rightTitle"),
                rows = array("rows").mapIndexed { index, element ->
                    val row = element.objectValue()
                    row.requireOnlyKeys(TUTOR_COMPARISON_ROW_WIRE_KEYS)
                    TutorComparisonRow(
                        rowId = "tutor-scene-$stableSuffix-row-${index + 1}",
                        criterion = row.requiredString("criterion"),
                        leftMarkdown = row.requiredString("leftMarkdown"),
                        rightMarkdown = row.requiredString("rightMarkdown"),
                        takeawayMarkdown = row.optionalString("takeawayMarkdown"),
                    )
                },
            )
        }

        "evidence_chain" -> {
            requireOnlyKeys(TUTOR_EVIDENCE_CHAIN_WIRE_KEYS)
            TutorEvidenceChainScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                claimMarkdown = requiredString("claimMarkdown"),
                evidence = array("evidence").mapIndexed { index, element ->
                    val point = element.objectValue()
                    point.requireOnlyKeys(TUTOR_EVIDENCE_POINT_WIRE_KEYS)
                    TutorEvidencePoint(
                        pointId = "tutor-scene-$stableSuffix-point-${index + 1}",
                        kind = enumValue<TutorEvidencePointKind>(point.requiredString("kind")),
                        markdown = point.requiredString("markdown"),
                    )
                },
                conclusionMarkdown = requiredString("conclusionMarkdown"),
            )
        }

        "process_timeline" -> {
            requireOnlyKeys(TUTOR_PROCESS_TIMELINE_WIRE_KEYS)
            TutorProcessTimelineScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                stages = array("stages").mapIndexed { index, element ->
                    val stage = element.objectValue()
                    stage.requireOnlyKeys(TUTOR_PROCESS_STAGE_WIRE_KEYS)
                    TutorProcessStage(
                        stageId = "tutor-scene-$stableSuffix-stage-${index + 1}",
                        label = stage.requiredString("label"),
                        bodyMarkdown = stage.requiredString("bodyMarkdown"),
                        transitionMarkdown = stage.optionalString("transitionMarkdown"),
                    )
                },
            )
        }

        "concept_map" -> {
            requireOnlyKeys(TUTOR_CONCEPT_MAP_WIRE_KEYS)
            TutorConceptMapScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                centerMarkdown = requiredString("centerMarkdown"),
                relations = array("relations").mapIndexed { index, element ->
                    val relation = element.objectValue()
                    relation.requireOnlyKeys(TUTOR_CONCEPT_RELATION_WIRE_KEYS)
                    TutorConceptRelation(
                        relationId = "tutor-scene-$stableSuffix-relation-${index + 1}",
                        relationLabel = relation.requiredString("relationLabel"),
                        targetMarkdown = relation.requiredString("targetMarkdown"),
                        detailMarkdown = relation.optionalString("detailMarkdown"),
                    )
                },
            )
        }

        "formula_derivation" -> {
            requireOnlyKeys(TUTOR_FORMULA_DERIVATION_WIRE_KEYS)
            TutorFormulaDerivationScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                startFormula = requiredString("startFormula"),
                steps = array("steps").mapIndexed { index, element ->
                    val step = element.objectValue()
                    step.requireOnlyKeys(TUTOR_FORMULA_DERIVATION_STEP_WIRE_KEYS)
                    TutorFormulaDerivationStep(
                        stepId = "tutor-scene-$stableSuffix-derivation-${index + 1}",
                        reasonMarkdown = step.requiredString("reasonMarkdown"),
                        resultFormula = step.requiredString("resultFormula"),
                    )
                },
            )
        }

        "spatial_diagram" -> {
            requireOnlyKeys(TUTOR_SPATIAL_DIAGRAM_WIRE_KEYS)
            val nodes = array("nodes").mapIndexed { index, element ->
                val node = element.objectValue()
                node.requireOnlyKeys(TUTOR_SPATIAL_NODE_WIRE_KEYS)
                TutorDiagramNode(
                    nodeId = "tutor-scene-$stableSuffix-node-${index + 1}",
                    label = node.requiredString("label"),
                    anchor = enumValue<TutorDiagramAnchor>(node.requiredString("anchor")),
                    shape = enumValue<TutorDiagramNodeShape>(node.requiredString("shape")),
                )
            }
            TutorSpatialDiagramScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                nodes = nodes,
                edges = array("edges").mapIndexed { index, element ->
                    val edge = element.objectValue()
                    edge.requireOnlyKeys(TUTOR_SPATIAL_EDGE_WIRE_KEYS)
                    val fromIndex = edge.optionalInt("fromIndex")
                        ?.takeIf { it in 1..nodes.size }
                        ?: throw InvalidModelResponseException()
                    val toIndex = edge.optionalInt("toIndex")
                        ?.takeIf { it in 1..nodes.size }
                        ?: throw InvalidModelResponseException()
                    TutorDiagramEdge(
                        edgeId = "tutor-scene-$stableSuffix-edge-${index + 1}",
                        fromNodeId = nodes[fromIndex - 1].nodeId,
                        toNodeId = nodes[toIndex - 1].nodeId,
                        label = edge.optionalString("label"),
                        style = enumValue<TutorDiagramEdgeStyle>(edge.requiredString("style")),
                    )
                },
                captionMarkdown = optionalString("captionMarkdown"),
            )
        }

        "linear_motion" -> {
            requireOnlyKeys(TUTOR_LINEAR_MOTION_WIRE_KEYS)
            TutorLinearMotionScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                durationSeconds = requiredFiniteDouble("durationSeconds"),
                initialPositionMeters = requiredFiniteDouble("initialPositionMeters"),
                initialVelocityMetersPerSecond =
                    requiredFiniteDouble("initialVelocityMetersPerSecond"),
                accelerationMetersPerSecondSquared =
                    requiredFiniteDouble("accelerationMetersPerSecondSquared"),
            )
        }

        "projectile_motion" -> {
            requireOnlyKeys(TUTOR_PROJECTILE_MOTION_WIRE_KEYS)
            TutorProjectileMotionScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                durationSeconds = requiredFiniteDouble("durationSeconds"),
                initialHeightMeters = requiredFiniteDouble("initialHeightMeters"),
                horizontalVelocityMetersPerSecond =
                    requiredFiniteDouble("horizontalVelocityMetersPerSecond"),
                verticalVelocityMetersPerSecond =
                    requiredFiniteDouble("verticalVelocityMetersPerSecond"),
                gravityMetersPerSecondSquared =
                    requiredFiniteDouble("gravityMetersPerSecondSquared"),
            )
        }

        "circular_motion" -> {
            requireOnlyKeys(TUTOR_CIRCULAR_MOTION_WIRE_KEYS)
            TutorCircularMotionScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                durationSeconds = requiredFiniteDouble("durationSeconds"),
                radiusMeters = requiredFiniteDouble("radiusMeters"),
                angularVelocityRadiansPerSecond =
                    requiredFiniteDouble("angularVelocityRadiansPerSecond"),
                initialAngleRadians = requiredFiniteDouble("initialAngleRadians"),
            )
        }

        "oscillation_motion" -> {
            requireOnlyKeys(TUTOR_OSCILLATION_MOTION_WIRE_KEYS)
            TutorOscillationMotionScene(
                sceneId = "tutor-scene-$stableSuffix",
                title = requiredString("title"),
                durationSeconds = requiredFiniteDouble("durationSeconds"),
                equilibriumPositionMeters =
                    requiredFiniteDouble("equilibriumPositionMeters"),
                amplitudeMeters = requiredFiniteDouble("amplitudeMeters"),
                periodSeconds = requiredFiniteDouble("periodSeconds"),
                initialPhaseRadians = requiredFiniteDouble("initialPhaseRadians"),
            )
        }

        else -> throw InvalidModelResponseException()
    }

internal fun JsonObject.toTutorVisualProgram(stableSuffix: String): TutorVisualProgramScene {
    requireOnlyKeys(TUTOR_VISUAL_PROGRAM_WIRE_KEYS)
    val parameterIds = array("parameters").mapIndexed { index, _ ->
        "tutor-scene-$stableSuffix-parameter-${index + 1}"
    }
    val parameters = array("parameters").mapIndexed { index, element ->
        val parameter = element.objectValue()
        parameter.requireOnlyKeys(TUTOR_VISUAL_PARAMETER_WIRE_KEYS)
        TutorVisualParameter(
            parameterId = parameterIds[index],
            label = parameter.requiredString("label"),
            value = parameter.requiredFiniteDouble("value"),
            unit = parameter.optionalString("unit"),
        )
    }
    val wireCommands = array("commands")
    val entityIds = wireCommands
        .filter { it.objectValue().optionalString("kind") == "entity" }
        .mapIndexed { index, _ -> "tutor-scene-$stableSuffix-entity-${index + 1}" }
    var entityOrdinal = 0
    val commands = wireCommands.mapIndexed { commandIndex, element ->
        val command = element.objectValue()
        val commandId = "tutor-scene-$stableSuffix-command-${commandIndex + 1}"
        when (command.requiredString("kind")) {
            "entity" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_ENTITY_WIRE_KEYS)
                TutorVisualEntityCommand(
                    commandId = entityIds[entityOrdinal++],
                    label = command.requiredString("label"),
                    shape = enumValue(command.requiredString("shape")),
                    x = command.objectValue("x").toTutorVisualExpression(parameterIds),
                    y = command.objectValue("y").toTutorVisualExpression(parameterIds),
                )
            }
            "link" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_LINK_WIRE_KEYS)
                TutorVisualLinkCommand(
                    commandId = commandId,
                    fromEntityId = command.requiredEntityId("fromIndex", entityIds),
                    toEntityId = command.requiredEntityId("toIndex", entityIds),
                    label = command.optionalString("label"),
                    style = enumValue(command.requiredString("style")),
                )
            }
            "path" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_PATH_WIRE_KEYS)
                TutorVisualPathCommand(
                    commandId = commandId,
                    entityId = command.requiredEntityId("targetIndex", entityIds),
                )
            }
            "vector" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_VECTOR_WIRE_KEYS)
                TutorVisualVectorCommand(
                    commandId = commandId,
                    label = command.requiredString("label"),
                    originEntityId = command.requiredEntityId("originIndex", entityIds),
                    x = command.objectValue("x").toTutorVisualExpression(parameterIds),
                    y = command.objectValue("y").toTutorVisualExpression(parameterIds),
                    unit = command.optionalString("unit"),
                )
            }
            "metric" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_METRIC_WIRE_KEYS)
                TutorVisualMetricCommand(
                    commandId = commandId,
                    label = command.requiredString("label"),
                    expression = command.objectValue("value").toTutorVisualExpression(parameterIds),
                    unit = command.optionalString("unit"),
                )
            }
            "note" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_NOTE_WIRE_KEYS)
                TutorVisualNoteCommand(
                    commandId = commandId,
                    markdown = command.requiredString("markdown"),
                )
            }
            "formula" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_FORMULA_WIRE_KEYS)
                TutorVisualFormulaCommand(
                    commandId = commandId,
                    formula = command.requiredString("formula"),
                )
            }
            "table" -> {
                command.requireOnlyKeys(TUTOR_VISUAL_TABLE_WIRE_KEYS)
                TutorVisualTableCommand(
                    commandId = commandId,
                    columns = command.array("columns").map(JsonElement::requiredPrimitiveString),
                    rows = command.array("rows").map { row ->
                        (row as? JsonArray)
                            ?.map(JsonElement::requiredPrimitiveString)
                            ?: throw InvalidModelResponseException()
                    },
                )
            }
            else -> throw InvalidModelResponseException()
        }
    }
    return TutorVisualProgramScene(
        sceneId = "tutor-scene-$stableSuffix",
        title = requiredString("title"),
        accessibilitySummary = requiredString("accessibilitySummary"),
        parameters = parameters,
        commands = commands,
        durationSeconds = optionalFiniteDouble("durationSeconds"),
        showAxes = if ("showAxes" in this) requiredBoolean("showAxes") else false,
        xUnit = optionalString("xUnit"),
        yUnit = optionalString("yUnit"),
    )
}

internal fun JsonObject.toTutorVisualExpression(
    parameterIds: List<String>,
): TutorVisualExpression {
    val operation = enumValue<TutorVisualExpressionOperation>(requiredString("op"))
    return when (operation) {
        TutorVisualExpressionOperation.CONSTANT -> {
            requireOnlyKeys(TUTOR_VISUAL_CONSTANT_EXPRESSION_WIRE_KEYS)
            TutorVisualExpression.constant(requiredFiniteDouble("value"))
        }
        TutorVisualExpressionOperation.TIME -> {
            requireOnlyKeys(TUTOR_VISUAL_TIME_EXPRESSION_WIRE_KEYS)
            TutorVisualExpression.time()
        }
        TutorVisualExpressionOperation.PARAMETER -> {
            requireOnlyKeys(TUTOR_VISUAL_PARAMETER_EXPRESSION_WIRE_KEYS)
            val parameterIndex = optionalInt("parameterIndex")
                ?.takeIf { it in 1..parameterIds.size }
                ?: throw InvalidModelResponseException()
            TutorVisualExpression.parameter(parameterIds[parameterIndex - 1])
        }
        TutorVisualExpressionOperation.NEGATE,
        TutorVisualExpressionOperation.SIN,
        TutorVisualExpressionOperation.COS,
        TutorVisualExpressionOperation.SQRT,
        TutorVisualExpressionOperation.ABS,
        -> {
            requireOnlyKeys(TUTOR_VISUAL_ARGUMENT_EXPRESSION_WIRE_KEYS)
            TutorVisualExpression.unary(
                operation,
                objectValue("argument").toTutorVisualExpression(parameterIds),
            )
        }
        TutorVisualExpressionOperation.ADD,
        TutorVisualExpressionOperation.SUBTRACT,
        TutorVisualExpressionOperation.MULTIPLY,
        TutorVisualExpressionOperation.DIVIDE,
        TutorVisualExpressionOperation.MIN,
        TutorVisualExpressionOperation.MAX,
        -> {
            requireOnlyKeys(TUTOR_VISUAL_BINARY_EXPRESSION_WIRE_KEYS)
            TutorVisualExpression.binary(
                operation,
                objectValue("left").toTutorVisualExpression(parameterIds),
                objectValue("right").toTutorVisualExpression(parameterIds),
            )
        }
    }
}

internal fun JsonObject.requiredEntityId(name: String, entityIds: List<String>): String {
    val index = optionalInt(name)
        ?.takeIf { it in 1..entityIds.size }
        ?: throw InvalidModelResponseException()
    return entityIds[index - 1]
}

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
