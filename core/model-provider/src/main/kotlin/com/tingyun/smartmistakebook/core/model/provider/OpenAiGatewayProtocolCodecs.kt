package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentAction
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssue
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
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.StructuredContentSanitizer
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
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
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorResponseIntent
import com.tingyun.smartmistakebook.core.model.TutorEvidencePoint
import com.tingyun.smartmistakebook.core.model.TutorEvidencePointKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorFreeResponseEvaluation
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationStep
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
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
import com.tingyun.smartmistakebook.core.model.TutorVisualExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualExpressionOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualFormulaCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualLinkCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualMetricCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualNoteCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualParameter
import com.tingyun.smartmistakebook.core.model.TutorVisualPathCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTableCommand
import com.tingyun.smartmistakebook.core.model.TutorVisualVectorCommand
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.locallyConstrainedFor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Real, consent-gated multimodal adapter for OpenAI-compatible chat-completions endpoints.
 * It receives no Room handle and can read image bytes only through [RestrictedModelAssetSource].
 */
internal fun serializeZeroImageRequestForBudget(
    modelId: String,
    input: com.tingyun.smartmistakebook.core.model.ModelTaskInput,
    authorizedDisclosures: Set<ModelEgressDataClass>,
    stream: Boolean = false,
): String = OpenAiModelProtocol.textOnlyRequestBody(
    modelId = modelId,
    input = input,
    imageMimeTypes = emptyList(),
    authorizedDisclosures = authorizedDisclosures,
    stream = stream,
)

internal fun com.tingyun.smartmistakebook.core.model.ModelTaskInput.isTutorStreamInput(): Boolean =
    this is TutorRespondInput || this is TutorLobbyInput

internal fun ModelTaskOutput.applyLocalTutorResponseBoundary(
    input: com.tingyun.smartmistakebook.core.model.ModelTaskInput,
): ModelTaskOutput =
    if (this is TutorRespondOutput && input is TutorRespondInput) {
        locallyConstrainedFor(input) ?: throw InvalidModelResponseException()
    } else {
        this
    }

internal fun ModelHttpResponse.toGatewayEvent(
    execution: ModelGatewayExecution,
    modelVersion: String,
): ModelGatewayEvent {
    if (statusCode !in 200..299) {
        return failure(
            when (statusCode) {
                401, 403 -> ModelTaskFailure(
                    ModelFailureCode.AUTHENTICATION_FAILED,
                    "模型认证失败，请在“我的”中检查 API Key",
                    false,
                )
                429 -> ModelTaskFailure(
                    ModelFailureCode.RATE_LIMITED,
                    "模型请求较多，任务已保留，可以稍后重试",
                    true,
                )
                in 500..599 -> ModelTaskFailure(
                    ModelFailureCode.NETWORK_UNAVAILABLE,
                    "模型服务暂时不可用，任务已保留",
                    true,
                )
                else -> ModelTaskFailure(
                    ModelFailureCode.PROVIDER_REJECTED_INPUT,
                    "模型服务拒绝了这次请求，请检查模型兼容性",
                    false,
                )
            },
        )
    }
    return try {
        ModelGatewayEvent.Completed(
            OpenAiModelProtocol.parseResponse(body, execution.request.input, modelVersion)
                .applyLocalTutorResponseBoundary(execution.request.input),
        )
    } catch (_: Exception) {
        failure(INVALID_RESPONSE)
    }
}

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
    return CaptureAssessment(
        decision = decision,
        issues = issues,
        suggestedActions = actions,
        questionRegions = optionalArray("questionRegions").map { item ->
            item.objectValue().toRegion()
        },
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
        require(wireChoices.size in 2..TutorInteractionDirective.MAX_CHOICES)
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
    val visualRequest = optionalObject("visualRequest")?.toTutorVisualGenerationRequest()
    val disclosedLabels = input.teachingConstraints.mapTo(hashSetOf()) { it.label }
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
    val interactionDirective = optionalObject("interactionDirective")
        ?.toTutorInteractionDirective()
    require(diagnosticItem == null || interactionDirective == null)
    val solutionMarkdown = requiredString("solutionMarkdown")
    val openingMarkdown = requiredString("openingMarkdown")
    val responseIntent = optionalString("responseIntent")
        ?.let { enumValue<TutorResponseIntent>(it) }
        ?: if (diagnosticItem != null || interactionDirective != null) {
            TutorResponseIntent.ASK
        } else {
            TutorResponseIntent.EXPLAIN
        }
    val rawOutput = TutorPlanOutput(
        sessionId = input.sessionId,
        draftRevisionNumber = input.draftRevisionNumber,
        questionDocumentId = input.questionDocument.id,
        plan = TutorTurnPlan(
            openingMarkdown = openingMarkdown,
            showOpening = !(
                input.explanationMode == TutorExplanationMode.DIRECT &&
                    responseIntent == TutorResponseIntent.ASK
                ),
            responseIntent = responseIntent,
            solutionRevealed = if ("solutionRevealed" in this) {
                requiredBoolean("solutionRevealed")
            } else {
                false
            },
            diagnosticItem = diagnosticItem,
            interactionDirective = interactionDirective,
            hintMarkdown = optionalString("hintMarkdown")
                .takeIf { input.explanationMode == TutorExplanationMode.GUIDED },
            visualScene = null,
            visualRequest = visualRequest,
            solutionMarkdown = solutionMarkdown,
            alternateMethodMarkdown = requiredString("alternateMethodMarkdown"),
            difficultyReasonMarkdown = requiredString("difficultyReasonMarkdown"),
            targetedEvidenceLabels = targetedLabels,
            inferredKnowledgeLabels = inferredLabels,
            suggestedMoves = suggestedMoves,
        ),
        modelVersion = modelVersion,
        cycleOrdinal = input.cycleOrdinal,
        turnOrdinal = input.turnOrdinal,
    )
    return rawOutput.locallyConstrainedFor(input)
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
    val responseIntent = enumValue<TutorResponseIntent>(requiredString("responseIntent"))
    val solutionRevealed = requiredBoolean("solutionRevealed")
    val interactionDirective = optionalObject("interactionDirective")
        ?.toTutorInteractionDirective()
    require(
        interactionDirective == null ||
            responseIntent == TutorResponseIntent.ASK &&
            input.explanationMode == TutorExplanationMode.GUIDED &&
            intentDecision.intent == com.tingyun.smartmistakebook.core.model.TutorMessageIntent.CURRENT_QUESTION_HELP &&
            !solutionRevealed,
    )
    require(responseIntent != TutorResponseIntent.ASK || interactionDirective != null)
    require(
        input.explanationMode != TutorExplanationMode.DIRECT ||
            intentDecision.intent != com.tingyun.smartmistakebook.core.model.TutorMessageIntent.CURRENT_QUESTION_HELP ||
            responseIntent == TutorResponseIntent.EXPLAIN && solutionRevealed,
    )
    return TutorRespondOutput(
        sessionId = input.sessionId,
        draftRevisionNumber = input.draftRevisionNumber,
        questionDocumentId = input.questionDocument.id,
        responseOrdinal = input.responseOrdinal,
        cycleOrdinal = input.cycleOrdinal,
        turnOrdinal = input.turnOrdinal,
        messageMarkdown = requiredString("messageMarkdown"),
        responseIntent = responseIntent,
        solutionRevealed = solutionRevealed,
        visualScene = null,
        visualRequest = optionalObject("visualRequest")?.toTutorVisualGenerationRequest(),
        interactionDirective = interactionDirective,
        freeResponseEvaluation = optionalString("freeResponseEvaluation")
            ?.let { value -> enumValue<TutorFreeResponseEvaluation>(value) }
            ?: TutorFreeResponseEvaluation.UNKNOWN,
        suggestedMoves = suggestedMoves,
        intentDecision = intentDecision,
        modelVersion = modelVersion,
    )
}

internal fun JsonObject.toTutorLobby(
    input: TutorLobbyInput,
    modelVersion: String,
): TutorLobbyOutput {
    requireOnlyKeys(TUTOR_LOBBY_WIRE_KEYS)
    val responseIntent = optionalString("responseIntent")
        ?.let { value -> enumValue<TutorResponseIntent>(value) }
        ?: TutorResponseIntent.EXPLAIN
    val candidate = TutorLobbyOutput(
        conversationId = input.conversationId,
        messageOrdinal = input.messageOrdinal,
        messageMarkdown = requiredString("messageMarkdown"),
        intentDecision = objectValue("intentDecision").toTutorIntentDecision(),
        explanationMode = input.explanationMode,
        modeVersion = input.modeVersion,
        responseIntent = responseIntent,
        interactionDirective = optionalObject("interactionDirective")
            ?.toTutorInteractionDirective(),
        modelVersion = modelVersion,
    )
    return candidate.locallyConstrainedFor(input) ?: throw InvalidModelResponseException()
}

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
        put("schemaVersion", JsonPrimitive(TutorVisualScene.DOCUMENT_SCHEMA_VERSION))
        put(
            "provenanceSchemaVersion",
            JsonPrimitive(TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION),
        )
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

internal data class BlockMetadata(
    val sourceAssetId: String,
    val region: NormalizedSourceRegion,
    val writingLayer: WritingLayer,
    val confidence: Double?,
)

