package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.domain.currentCapabilityVerification
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentAction
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssue
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssueCode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentSeverity
import com.tingyun.smartmistakebook.core.model.CapturePageRelation
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureAxis
import com.tingyun.smartmistakebook.core.model.FigureCoordinate
import com.tingyun.smartmistakebook.core.model.FigureLabel
import com.tingyun.smartmistakebook.core.model.FigurePoint
import com.tingyun.smartmistakebook.core.model.FigurePolyline
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.FigureSeriesStyle
import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyOutput
import com.tingyun.smartmistakebook.core.model.ImagePipelineProblemKind
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationException
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_MAX_ASSET_BYTES
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelExecutionPermit
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelRequestBudgetExceededException
import com.tingyun.smartmistakebook.core.model.ModelRequestPayloadBudget
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.StructuredContentSanitizer
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorDifficultyTier
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import com.tingyun.smartmistakebook.core.model.TutorComparisonRow
import com.tingyun.smartmistakebook.core.model.TutorComparisonScene
import com.tingyun.smartmistakebook.core.model.TutorConceptMapScene
import com.tingyun.smartmistakebook.core.model.TutorConceptRelation
import com.tingyun.smartmistakebook.core.model.TutorCircularMotionScene
import com.tingyun.smartmistakebook.core.model.TutorDiagramAnchor
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdge
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdgeStyle
import com.tingyun.smartmistakebook.core.model.TutorDiagramNode
import com.tingyun.smartmistakebook.core.model.TutorDiagramNodeShape
import com.tingyun.smartmistakebook.core.model.TutorEvidenceChainScene
import com.tingyun.smartmistakebook.core.model.TutorDebriefInput
import com.tingyun.smartmistakebook.core.model.TutorDebriefOutput
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorEvidencePoint
import com.tingyun.smartmistakebook.core.model.TutorEvidencePointKind
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationStep
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorLinearMotionScene
import com.tingyun.smartmistakebook.core.model.TutorOscillationMotionScene
import com.tingyun.smartmistakebook.core.model.TutorProcessStage
import com.tingyun.smartmistakebook.core.model.TutorProcessTimelineScene
import com.tingyun.smartmistakebook.core.model.TutorProjectileMotionScene
import com.tingyun.smartmistakebook.core.model.TutorSceneEmphasis
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorSpatialDiagramScene
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualEntityShape
import com.tingyun.smartmistakebook.core.model.TutorVisualExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualExpressionOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualFormulaCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualLineStyle
import com.tingyun.smartmistakebook.core.model.TutorVisualLinkCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualMetricCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualNoteCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualParameter
import com.tingyun.smartmistakebook.core.model.TutorVisualPathCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTableCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualVectorCommand
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString

internal fun JsonObject.toAssessment(modelVersion: String): CaptureAssessment {
    requireOnlyKeys(CAPTURE_ASSESSMENT_WIRE_KEYS)
    val decision = enumValue<CaptureAssessmentDecision>(requiredString("decision"))
    val issues = array("issues").map { item ->
        val issue = item.objectValue()
        issue.requireOnlyKeys(CAPTURE_ASSESSMENT_ISSUE_WIRE_KEYS)
        CaptureAssessmentIssue(
            code = enumValue(issue.requiredString("code")),
            severity = enumValue(issue.requiredString("severity")),
            region = issue.optionalRegion(),
            message = issue.requiredString("message").take(512),
        )
    }
    val actions = array("suggestedActions").map { item ->
        enumValue<CaptureAssessmentAction>(item.jsonPrimitive.content)
    }.distinct()
    // questionRegions are page-local normalized coordinates; a valid response
    // must satisfy the model-layer contract enforced inside CaptureAssessment.
    val questionRegions = optionalArray("questionRegions").map { item ->
        item.objectValue().toRegion()
    }
    return CaptureAssessment(
        decision = decision,
        issues = issues,
        suggestedActions = actions,
        questionRegions = questionRegions,
        followingPageRelations = optionalArray("followingPageRelations").map { relation ->
            enumValue<CapturePageRelation>(relation.jsonPrimitive.content)
        },
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toCapturedDocument(
    input: CaptureParseInput,
    modelVersion: String,
): CaptureParseOutput {
    val wireBlocks = array("blocks")
    require(wireBlocks.isNotEmpty() && wireBlocks.size <= StructuredContentLimits.MAX_BLOCKS)
    val metadata = mutableListOf<BlockMetadata>()
    val blocks = wireBlocks.mapIndexed { blockIndex, item ->
        val block = item.objectValue()
        val blockId = "block-${blockIndex + 1}"
        val blockType = block.requiredString("type")
        val source = input.sourceAssets.getOrNull(block.optionalInt("pageIndex") ?: 0)
            ?: throw InvalidModelResponseException()
        val region = block.requiredRegion()
        val selectedRegion = source.selectedRegion
        if (selectedRegion != null && !selectedRegion.contains(region)) {
            throw InvalidModelResponseException()
        }
        metadata += BlockMetadata(
            sourceAssetId = source.assetId,
            region = region,
            writingLayer = if (blockType == "figure" || blockType == "diagram_note") {
                WritingLayer.DIAGRAM
            } else {
                enumValue(block.requiredString("writingLayer"))
            },
            confidence = block.optionalDouble("confidence"),
        )
        when (blockType) {
            "paragraph" -> ContentBlock.Paragraph(
                id = blockId,
                markdown = block.requiredString("markdown"),
            )
            "formula" -> ContentBlock.Formula(
                id = blockId,
                latex = block.requiredString("latex"),
                alternativeText = block.requiredString("alternativeText"),
            )
            "choice_group" -> ContentBlock.ChoiceGroup(
                id = blockId,
                promptMarkdown = block.requiredString("promptMarkdown"),
                choices = block.array("choices").mapIndexed { choiceIndex, choiceItem ->
                    val choice = choiceItem.objectValue()
                    StructuredChoice(
                        id = "choice-${blockIndex + 1}-${choiceIndex + 1}",
                        markdown = choice.requiredString("markdown"),
                        accessibilityLabel = choice.optionalString("accessibilityLabel"),
                    )
                },
                selectedChoiceId = null,
            )
            "figure" -> ContentBlock.Figure(
                id = blockId,
                title = block.optionalString("title"),
                alternativeText = block.requiredString("alternativeText"),
                schema = block.objectValue("schema").toFigureSchema(blockIndex),
            )
            "diagram_note" -> ContentBlock.Paragraph(
                id = blockId,
                markdown = "图示：${block.requiredString("alternativeText")}",
            )
            else -> throw InvalidModelResponseException()
        }
    }
    val rawDocument = QuestionDocument(
        id = "document-${input.draftId}",
        title = optionalString("title"),
        blocks = blocks,
    )
    val sanitized = StructuredContentSanitizer.sanitize(rawDocument)
    if (sanitized.issues.isNotEmpty() || sanitized.document.blocks.size != metadata.size) {
        throw InvalidModelResponseException()
    }
    val evidence = sanitized.document.blocks.mapIndexed { index, block ->
        val meta = metadata[index]
        QuestionBlockEvidence(
            blockId = block.id,
            sourceAssetId = meta.sourceAssetId,
            sourceRegion = meta.region,
            writingLayer = meta.writingLayer,
            provenance = QuestionBlockProvenance.MODEL_DOCUMENT_PARSE,
            confidence = meta.confidence,
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
            producerVersion = modelVersion,
        )
    }
    return CaptureParseOutput(
        capturedDocument = CapturedQuestionDocument(
            document = sanitized.document,
            blockEvidence = evidence,
        ),
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toImagePipelineClassify(
    modelVersion: String,
): ImagePipelineClassifyOutput {
    val kind = requiredString("problemKind")
    val problemKind = enumValue<ImagePipelineProblemKind>(kind.uppercase())
    val textMarkdown = optionalString("textMarkdown") ?: ""
    val formulas = array("formulas").mapNotNull { item ->
        (item as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }
    return ImagePipelineClassifyOutput(
        problemKind = problemKind,
        textMarkdown = textMarkdown,
        formulas = formulas,
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toFigureSchema(blockIndex: Int): FigureSchema =
    when (requiredString("type")) {
        "cartesian" -> FigureSchema.Cartesian(
            xAxis = objectValue("xAxis").toFigureAxis(),
            yAxis = objectValue("yAxis").toFigureAxis(),
            polylines = array("polylines").mapIndexed { index, item ->
                val polyline = item.objectValue()
                FigurePolyline(
                    id = "polyline-${blockIndex + 1}-${index + 1}",
                    points = polyline.array("points").map { it.objectValue().toFigureCoordinate() },
                    label = polyline.optionalString("label"),
                    style = polyline.optionalStyle(),
                )
            },
            points = array("points").map { item ->
                val point = item.objectValue()
                FigurePoint(
                    coordinate = point.toFigureCoordinate(),
                    label = point.optionalString("label"),
                    style = point.optionalStyle(),
                )
            },
            labels = array("labels").map { item ->
                val label = item.objectValue()
                FigureLabel(
                    coordinate = label.toFigureCoordinate(),
                    text = label.requiredString("text"),
                )
            },
        )
        "symbol_table" -> FigureSchema.SymbolTable(
            headers = array("headers").map { it.requiredPrimitiveString() },
            rows = array("rows").map { row ->
                (row as? JsonArray)?.map { it.requiredPrimitiveString() }
                    ?: throw InvalidModelResponseException()
            },
        )
        else -> throw InvalidModelResponseException()
    }

internal fun JsonObject.toFigureAxis(): FigureAxis = FigureAxis(
    minimum = requiredFiniteDouble("minimum"),
    maximum = requiredFiniteDouble("maximum"),
    label = optionalString("label").orEmpty(),
    tickCount = optionalInt("tickCount") ?: 5,
)

internal fun JsonObject.toFigureCoordinate(): FigureCoordinate = FigureCoordinate(
    x = requiredFiniteDouble("x"),
    y = requiredFiniteDouble("y"),
)

internal fun JsonObject.optionalStyle(): FigureSeriesStyle =
    optionalString("style")?.let { enumValue(it) } ?: FigureSeriesStyle.PRIMARY

internal fun JsonObject.toTutorPlan(
    input: TutorPlanInput,
    modelVersion: String,
): TutorPlanOutput {
    requireOnlyKeys(TUTOR_PLAN_WIRE_KEYS)
    val stableSuffix = MessageDigest.getInstance("SHA-256")
        .digest(
            "${input.sessionId}\n${input.draftRevisionNumber}\n${input.cycleOrdinal}\n${input.turnOrdinal}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        .joinToString("") { "%02x".format(it) }
        .take(20)
    val diagnosticItem = optionalObject("diagnosticQuestion")?.let { diagnostic ->
        diagnostic.requireOnlyKeys(TUTOR_DIAGNOSTIC_WIRE_KEYS)
        val wireChoices = diagnostic.array("choices")
        require(wireChoices.size in 2..TutorTurnPlan.MAX_INTERACTION_CHOICES)
        val correctIndexes = wireChoices.mapIndexedNotNull { index, item ->
            index.takeIf { item.objectValue().requiredBoolean("isCorrect") }
        }
        require(correctIndexes.size == 1)
        val choices = wireChoices.mapIndexed { index, item ->
            val choice = item.objectValue()
            choice.requireOnlyKeys(TUTOR_DIAGNOSTIC_CHOICE_WIRE_KEYS)
            TutorChoice(
                id = "choice-${index + 1}",
                markdown = choice.requiredString("markdown"),
                feedbackMarkdown = choice.requiredString("feedbackMarkdown"),
            )
        }
        TutorAssessmentItem(
            id = "tutor-diagnostic-$stableSuffix",
            stemMarkdown = diagnostic.requiredString("stemMarkdown"),
            promptMarkdown = diagnostic.optionalString("promptMarkdown"),
            choices = choices,
            correctChoiceId = choices[correctIndexes.single()].id,
            knowledgeNodeIds = emptySet(),
        )
    }
    val visualScene = optionalObject("visualScene")?.toTutorVisualScene(stableSuffix)
    val visualRequest = optionalObject("visualRequest")?.toTutorVisualGenerationRequest()
    val disclosedLabels = input.relevantLearningEvidence.mapTo(hashSetOf()) { it.displayName }
    val targetedLabels = array("targetedEvidenceLabels")
        .map { it.jsonPrimitive.content }
        .distinct()
    require(targetedLabels.all(disclosedLabels::contains))
    val inferredLabels = array("inferredKnowledgeLabels")
        .map { it.jsonPrimitive.content }
        .distinct()
    val suggestedMoves = array("nextMoves").mapIndexed { index, item ->
        val move = item.objectValue()
        move.requireOnlyKeys(TUTOR_MOVE_WIRE_KEYS)
        TutorSuggestedMove(
            id = "move-${input.cycleOrdinal}-${input.turnOrdinal}-${index + 1}",
            label = move.requiredString("label"),
            type = enumValue(move.requiredString("type")),
        )
    }
    return TutorPlanOutput(
        sessionId = input.sessionId,
        draftRevisionNumber = input.draftRevisionNumber,
        questionDocumentId = input.questionDocument.id,
        plan = TutorTurnPlan(
            openingMarkdown = requiredString("openingMarkdown"),
            diagnosticItem = diagnosticItem,
            visualScene = visualScene,
            visualRequest = visualRequest,
            solutionMarkdown = requiredString("solutionMarkdown"),
            alternateMethodMarkdown = requiredString("alternateMethodMarkdown"),
            difficultyReasonMarkdown = requiredString("difficultyReasonMarkdown"),
            targetedEvidenceLabels = targetedLabels,
            inferredKnowledgeLabels = inferredLabels,
            suggestedMoves = suggestedMoves,
            thinkingMarkdown = optionalString("thinkingMarkdown"),
        ),
        modelVersion = modelVersion,
        cycleOrdinal = input.cycleOrdinal,
        turnOrdinal = input.turnOrdinal,
        attachedImages = optionalArray("attachedImages").map(JsonElement::toAttachedImage),
    )
}

internal fun JsonObject.toTutorRespond(
    input: TutorRespondInput,
    modelVersion: String,
): TutorRespondOutput {
    requireOnlyKeys(TUTOR_RESPOND_WIRE_KEYS)
    val stableSuffix = MessageDigest.getInstance("SHA-256")
        .digest(
            "${input.sessionId}\n${input.draftRevisionNumber}\n${input.responseOrdinal}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        .joinToString("") { "%02x".format(it) }
        .take(20)
    val suggestedMoves = optionalArray("nextMoves").mapIndexed { index, element ->
        val move = element.objectValue()
        move.requireOnlyKeys(TUTOR_MOVE_WIRE_KEYS)
        TutorSuggestedMove(
            id = "tutor-respond-move-$stableSuffix-${index + 1}",
            label = move.requiredString("label"),
            type = enumValue(move.requiredString("type")),
        )
    }
    val intentDecision = optionalObject("intentDecision")?.toTutorIntentDecision()
        ?: TutorIntentDecision.ambiguousDefault()
    return TutorRespondOutput(
        sessionId = input.sessionId,
        draftRevisionNumber = input.draftRevisionNumber,
        questionDocumentId = input.questionDocument.id,
        responseOrdinal = input.responseOrdinal,
        cycleOrdinal = input.cycleOrdinal,
        turnOrdinal = input.turnOrdinal,
        messageMarkdown = requiredString("messageMarkdown"),
        solutionRevealed = requiredBoolean("solutionRevealed"),
        visualScene = optionalObject("visualScene")?.toTutorVisualScene(stableSuffix),
        visualRequest = optionalObject("visualRequest")?.toTutorVisualGenerationRequest(),
        suggestedMoves = suggestedMoves,
        intentDecision = intentDecision,
        thinkingMarkdown = optionalString("thinkingMarkdown"),
        attachedImages = optionalArray("attachedImages").map(JsonElement::toAttachedImage),
        modelVersion = modelVersion,
    )
}

internal val TUTOR_TOOL_REQUESTS_WIRE_KEYS = setOf("intentDecision", "toolRequests")
private val TUTOR_TOOL_CALL_WIRE_KEYS =
    setOf("tool", "terms", "rationale", "direction", "understanding", "difficultyTier", "confidence")

internal fun JsonObject.toTutorToolRequests(
    modelVersion: String,
): TutorToolRequestsOutput {
    requireOnlyKeys(TUTOR_TOOL_REQUESTS_WIRE_KEYS)
    return TutorToolRequestsOutput(
        intentDecision = objectValue("intentDecision").toTutorIntentDecision(),
        calls = optionalArray("toolRequests")
            ?.mapIndexed { index, element ->
                val call = element.objectValue()
                call.requireOnlyKeys(TUTOR_TOOL_CALL_WIRE_KEYS)
                TutorToolCall(
                    tool = enumValue(call.requiredString("tool")),
                    rationale = call.requiredString("rationale"),
                    terms = call.optionalArray("terms").map(JsonElement::requiredPrimitiveString),
                    direction = call.optionalString("direction")?.let { enumValue<TutorEvidenceDirection>(it) },
                    understanding = call.optionalString("understanding")?.let { enumValue<TutorUnderstandingTier>(it) },
                    difficultyTier = call.optionalString("difficultyTier")?.let { enumValue<TutorDifficultyTier>(it) },
                    confidence = call.optionalDouble("confidence") ?: 0.8,
                )
            }
            ?: throw InvalidModelResponseException(),
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toTutorLobby(
    input: TutorLobbyInput,
    modelVersion: String,
): TutorLobbyOutput {
    requireOnlyKeys(TUTOR_LOBBY_WIRE_KEYS)
    return TutorLobbyOutput(
        conversationId = input.conversationId,
        messageOrdinal = input.messageOrdinal,
        messageMarkdown = requiredString("messageMarkdown"),
        intentDecision = objectValue("intentDecision").toTutorIntentDecision(),
        thinkingMarkdown = optionalString("thinkingMarkdown"),
        attachedImages = optionalArray("attachedImages").map(JsonElement::toAttachedImage),
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toTutorIntentDecision(): TutorIntentDecision {
    requireOnlyKeys(TUTOR_INTENT_WIRE_KEYS)
    return TutorIntentDecision(
        intent = enumValue(requiredString("intent")),
        confidence = optionalDouble("confidence") ?: throw InvalidModelResponseException(),
        explicitActionRequest = requiredBoolean("explicitActionRequest"),
        memoryPreference = enumValue(requiredString("memoryPreference")),
        requestedLocalCapability = enumValue(requiredString("requestedLocalCapability")),
        lookupTerms = optionalArray("lookupTerms").map(JsonElement::requiredPrimitiveString),
    )
}


internal val TUTOR_DEBRIEF_WIRE_KEYS = setOf(
    "misconceptionMarkdown",
    "teachingFocusLabels",
)
internal val TUTOR_PLAN_WIRE_KEYS = setOf(
    "openingMarkdown",
    "diagnosticQuestion",
    "visualScene",
    "visualRequest",
    "solutionMarkdown",
    "alternateMethodMarkdown",
    "difficultyReasonMarkdown",
    "targetedEvidenceLabels",
    "inferredKnowledgeLabels",
    "nextMoves",
    "thinkingMarkdown",
    "attachedImages",
)
internal val TUTOR_RESPOND_WIRE_KEYS =
    setOf(
        "intentDecision",
        "messageMarkdown",
        "solutionRevealed",
        "visualScene",
        "visualRequest",
        "nextMoves",
        "thinkingMarkdown",
        "attachedImages",
    )
internal val TUTOR_LOBBY_WIRE_KEYS =
    setOf("intentDecision", "messageMarkdown", "thinkingMarkdown", "attachedImages")
internal val ATTACHED_IMAGE_WIRE_KEYS =
    setOf("imageId", "kind", "description", "accessibilityText")

/** Parses one model-authored [AttachedImage] intent from its wire object. */
internal fun JsonElement.toAttachedImage(): AttachedImage {
    val obj = objectValue()
    obj.requireOnlyKeys(ATTACHED_IMAGE_WIRE_KEYS)
    val kind = enumValue<AttachedImageKind>(obj.requiredString("kind"))
    return AttachedImage(
        imageId = obj.requiredString("imageId"),
        kind = kind,
        description = obj.requiredString("description"),
        accessibilityText = obj.optionalString("accessibilityText").orEmpty(),
    )
}
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

internal fun JsonObject.toTutorDebrief(
    input: TutorDebriefInput,
    modelVersion: String,
): TutorDebriefOutput {
    requireOnlyKeys(TUTOR_DEBRIEF_WIRE_KEYS)
    val misconception = optionalString("misconceptionMarkdown")
    val focusLabels = optionalArray("teachingFocusLabels")
        .mapNotNull { element -> (element as? kotlinx.serialization.json.JsonPrimitive)?.content }
    return TutorDebriefOutput(
        sessionId = input.sessionId,
        practiceUnitId = input.practiceUnitId,
        misconceptionMarkdown = misconception,
        teachingFocusLabels = focusLabels,
        modelVersion = modelVersion,
    )
}
