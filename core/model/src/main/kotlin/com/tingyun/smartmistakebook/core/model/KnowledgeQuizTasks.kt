package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One answer choice of a knowledge review quiz item. */
@Serializable
data class KnowledgeQuizChoice(
    val choiceId: String,
    val markdown: String,
) {
    init {
        require(choiceId.isNotBlank()) { "Knowledge quiz choice id must not be blank" }
        require(markdown.isNotBlank()) { "Knowledge quiz choice markdown must not be blank" }
    }
}

/**
 * Knowledge review quiz (spec dual-review-entry §3.3): ask the model to compose a
 * multiple-choice item for one knowledge node, anchored to its teaching material's
 * boundary so it can't stray beyond the node's real scope. Local-only, text-only,
 * no image assets. The material's key fields are inlined so `core:model` stays
 * decoupled from the Room/database teaching-material record.
 */
@Serializable
@SerialName("knowledge_quiz_input")
data class KnowledgeQuizInput(
    val knowledgeNodeId: String,
    override val subjectId: String,
    val materialTitle: String,
    val materialContentMarkdown: String,
    val materialBoundaryMarkdown: String,
    val lastMasteryScore: Double? = null,
    val lastEvidenceAtEpochMillis: Long? = null,
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.KNOWLEDGE_QUIZ

    init {
        require(knowledgeNodeId.isNotBlank()) { "Knowledge quiz node id must not be blank" }
        require(subjectId.isNotBlank()) { "Knowledge quiz subject id must not be blank" }
        require(materialTitle.isNotBlank()) { "Knowledge quiz material title must not be blank" }
        require(materialContentMarkdown.isNotBlank()) {
            "Knowledge quiz material content must not be blank"
        }
        require(materialBoundaryMarkdown.isNotBlank()) {
            "Knowledge quiz material boundary must not be blank"
        }
        require(lastMasteryScore == null || lastMasteryScore.isFinite() && lastMasteryScore in 0.0..1.0) {
            "Knowledge quiz last mastery must be between zero and one"
        }
        require(lastEvidenceAtEpochMillis == null || lastEvidenceAtEpochMillis >= 0) {
            "Knowledge quiz last evidence time must not be negative"
        }
    }
}

@Serializable
@SerialName("knowledge_quiz_output")
data class KnowledgeQuizOutput(
    val questionMarkdown: String,
    val choices: List<KnowledgeQuizChoice>,
    val correctChoiceId: String,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        require(questionMarkdown.isNotBlank()) { "Knowledge quiz question must not be blank" }
        require(choices.size >= 2) { "Knowledge quiz needs at least two choices" }
        require(choices.map { it.choiceId }.distinct().size == choices.size) {
            "Knowledge quiz choice ids must be unique"
        }
        require(choices.any { it.choiceId == correctChoiceId }) {
            "Knowledge quiz correct choice must be present among choices"
        }
        require(correctChoiceId.isNotBlank()) { "Knowledge quiz correct choice id must not be blank" }
        modelVersion.requireSafeModelText(
            label = "Knowledge quiz model version",
            maxChars = MAX_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
    }
}

enum class ModelTaskCompletionIssueCode {
    REQUEST_OUTPUT_TYPE_MISMATCH,
    INVALID_CAPTURE_DOCUMENT,
    DRAFT_DOCUMENT_MISMATCH,
    SOURCE_ASSET_MISMATCH,
    INVALID_CAPTURE_PROVENANCE,
    MODEL_CANNOT_CONFIRM_USER_REVIEW,
    MODEL_CANNOT_APPLY_LOCAL_POLICY,
    MISSING_PRODUCER_VERSION,
    PRODUCER_VERSION_MISMATCH,
    MISSING_SOURCE_REGION,
    SOURCE_REGION_OUTSIDE_REQUEST,
    UNRESOLVED_WRITING_LAYER,
    PRESELECTED_ANSWER,
    PAGE_RELATION_MISMATCH,
    TUTOR_CONTEXT_MISMATCH,
    TUTOR_INTENT_BOUNDARY_VIOLATION,
    MODEL_CLAIMED_UNDISCLOSED_EVIDENCE,
    MODEL_TARGETED_MASTERED_EVIDENCE,
    MODEL_ASSIGNED_TRUSTED_KNOWLEDGE_IDS,
    ORGANIZATION_CONTEXT_MISMATCH,
    ORGANIZATION_ATOMIC_DECOMPOSITION_REQUIRED,
    ORGANIZATION_UNKNOWN_RELATION_TARGET,
    ORGANIZATION_UNKNOWN_KNOWLEDGE_PREREQUISITE,
}

data class ModelTaskCompletionIssue(
    val code: ModelTaskCompletionIssueCode,
    val blockId: String? = null,
)

/**
 * Trust boundary between a model adapter and durable task state.
 *
 * Adapters may deserialize untrusted output, but repositories must call [requireValid] before
 * recording a successful task. In particular, a model can only produce review candidates; it
 * cannot claim that a student confirmed its transcription.
 */
object ModelTaskCompletionValidator {
    fun validate(
        request: ModelTaskRequest,
        output: ModelTaskOutput,
    ): List<ModelTaskCompletionIssue> = when (val input = request.input) {
        is TutorDebriefInput -> if (output is TutorDebriefOutput && output.sessionId == input.sessionId) {
            emptyList()
        } else {
            listOf(typeMismatch())
        }
        is CaptureAssessmentInput -> if (output is CaptureAssessmentOutput) {
            if (
                input.followingSourceAssets.size ==
                output.assessment.followingPageRelations.size
            ) {
                emptyList()
            } else {
                listOf(
                    ModelTaskCompletionIssue(
                        ModelTaskCompletionIssueCode.PAGE_RELATION_MISMATCH,
                    ),
                )
            }
        } else {
            listOf(typeMismatch())
        }
        is ImagePipelineClassifyInput -> if (output is ImagePipelineClassifyOutput) {
            emptyList()
        } else {
            listOf(typeMismatch())
        }
        is CaptureParseInput -> if (output is CaptureParseOutput) {
            validateCaptureParse(input, output)
        } else {
            listOf(typeMismatch())
        }
        is KnowledgeQuizInput -> if (output is KnowledgeQuizOutput) {
            emptyList()
        } else {
            listOf(typeMismatch())
        }
        is TutorPlanInput -> if (output is TutorPlanOutput) {
            validateTutorPlan(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorRespondInput -> if (output is TutorRespondOutput) {
            validateTutorRespond(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorVisualGenerateInput -> if (output is TutorVisualGenerateOutput) {
            validateTutorVisualGenerate(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorVisualReviewInput -> if (output is TutorVisualReviewOutput) {
            validateTutorVisualReview(input, output)
        } else {
            listOf(typeMismatch())
        }
        is TutorLobbyInput -> if (output is TutorLobbyOutput) {
            validateTutorLobby(input, output)
        } else {
            listOf(typeMismatch())
        }
        is ProblemOrganizationInput -> if (output is ProblemOrganizationOutput) {
            validateProblemOrganization(input, output)
        } else {
            listOf(typeMismatch())
        }
    }

    fun requireValid(request: ModelTaskRequest, output: ModelTaskOutput) {
        val issues = validate(request, output)
        require(issues.isEmpty()) {
            "Invalid model task completion: ${issues.joinToString { issue -> issue.code.name }}"
        }
    }

    private fun validateCaptureParse(
        input: CaptureParseInput,
        output: CaptureParseOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        val expectedSources = input.sourceAssets.associateBy(CaptureSourceAssetRef::assetId)
        if (output.capturedDocument.document.id != "document-${input.draftId}") {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.DRAFT_DOCUMENT_MISMATCH))
        }
        CapturedQuestionDocumentValidator.validateDraft(output.capturedDocument).forEach { issue ->
            add(
                ModelTaskCompletionIssue(
                    code = ModelTaskCompletionIssueCode.INVALID_CAPTURE_DOCUMENT,
                    blockId = issue.blockId,
                ),
            )
        }
        output.capturedDocument.blockEvidence.forEach { evidence ->
            val source = expectedSources[evidence.sourceAssetId]
            if (source == null) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.SOURCE_ASSET_MISMATCH,
                        blockId = evidence.blockId,
                    ),
                )
            }
            val sourceRegion = evidence.sourceRegion
            if (sourceRegion == null) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MISSING_SOURCE_REGION,
                        blockId = evidence.blockId,
                    ),
                )
            } else if (
                source?.selectedRegion != null &&
                !source.selectedRegion.contains(sourceRegion)
            ) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.SOURCE_REGION_OUTSIDE_REQUEST,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.writingLayer == WritingLayer.UNKNOWN) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.UNRESOLVED_WRITING_LAYER,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.provenance != QuestionBlockProvenance.MODEL_DOCUMENT_PARSE) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.INVALID_CAPTURE_PROVENANCE,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.reviewStatus == QuestionBlockReviewStatus.USER_CONFIRMED) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MODEL_CANNOT_CONFIRM_USER_REVIEW,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.reviewStatus == QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MODEL_CANNOT_APPLY_LOCAL_POLICY,
                        blockId = evidence.blockId,
                    ),
                )
            }
            if (evidence.producerVersion.isNullOrBlank()) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.MISSING_PRODUCER_VERSION,
                        blockId = evidence.blockId,
                    ),
                )
            } else if (evidence.producerVersion != output.modelVersion) {
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.PRODUCER_VERSION_MISMATCH,
                        blockId = evidence.blockId,
                    ),
                )
            }
        }
        output.capturedDocument.document.blocks
            .filterIsInstance<ContentBlock.ChoiceGroup>()
            .filter { it.selectedChoiceId != null }
            .forEach { block ->
                add(
                    ModelTaskCompletionIssue(
                        code = ModelTaskCompletionIssueCode.PRESELECTED_ANSWER,
                        blockId = block.id,
                    ),
                )
            }
    }.distinct()

    private fun typeMismatch() = ModelTaskCompletionIssue(
        ModelTaskCompletionIssueCode.REQUEST_OUTPUT_TYPE_MISMATCH,
    )

    private fun validateTutorPlan(
        input: TutorPlanInput,
        output: TutorPlanOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.sessionId != input.sessionId ||
            output.draftRevisionNumber != input.draftRevisionNumber ||
            output.questionDocumentId != input.questionDocument.id ||
            output.cycleOrdinal != input.cycleOrdinal ||
            output.turnOrdinal != input.turnOrdinal
        ) {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }
        val disclosedLabels = input.relevantLearningEvidence
            .mapTo(mutableSetOf(), TutorKnowledgeEvidence::displayName)
        if (output.plan.targetedEvidenceLabels.any { it !in disclosedLabels }) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_CLAIMED_UNDISCLOSED_EVIDENCE,
                ),
            )
        }
        val masteredLabels = input.relevantLearningEvidence
            .filter { it.level == TutorEvidenceLevel.MASTERED }
            .mapTo(mutableSetOf(), TutorKnowledgeEvidence::displayName)
        if (output.plan.targetedEvidenceLabels.any { it in masteredLabels }) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_TARGETED_MASTERED_EVIDENCE,
                ),
            )
        }
        if (output.plan.diagnosticItem?.knowledgeNodeIds?.isNotEmpty() == true) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_ASSIGNED_TRUSTED_KNOWLEDGE_IDS,
                ),
            )
        }
    }

    private fun validateTutorRespond(
        input: TutorRespondInput,
        output: TutorRespondOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.sessionId != input.sessionId ||
            output.draftRevisionNumber != input.draftRevisionNumber ||
            output.questionDocumentId != input.questionDocument.id ||
            output.responseOrdinal != input.responseOrdinal ||
            output.cycleOrdinal != input.cycleOrdinal ||
            output.turnOrdinal != input.turnOrdinal
        ) {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }
        if (
            (output.solutionRevealed && !input.studentAuthorizedSolutionRequest()) ||
            (
                output.intentDecision.intent != TutorMessageIntent.CURRENT_QUESTION_HELP &&
                    (
                        output.solutionRevealed ||
                            output.visualScene != null ||
                            output.visualRequest != null ||
                            output.suggestedMoves.isNotEmpty()
                        )
                )
        ) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION,
                ),
            )
        }
    }

    private fun validateTutorVisualGenerate(
        input: TutorVisualGenerateInput,
        output: TutorVisualGenerateOutput,
    ): List<ModelTaskCompletionIssue> =
        if (
            output.sessionId == input.sessionId &&
            output.draftRevisionNumber == input.draftRevisionNumber &&
            output.questionDocumentId == input.questionDocument.id &&
            output.anchor == input.anchor
        ) {
            emptyList()
        } else {
            listOf(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }

    private fun validateTutorVisualReview(
        input: TutorVisualReviewInput,
        output: TutorVisualReviewOutput,
    ): List<ModelTaskCompletionIssue> =
        if (
            output.sessionId == input.sessionId &&
            output.draftRevisionNumber == input.draftRevisionNumber &&
            output.questionDocumentId == input.questionDocument.id &&
            output.anchor == input.anchor
        ) {
            emptyList()
        } else {
            listOf(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }

    private fun validateTutorLobby(
        input: TutorLobbyInput,
        output: TutorLobbyOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.conversationId != input.conversationId ||
            output.messageOrdinal != input.messageOrdinal
        ) {
            add(ModelTaskCompletionIssue(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH))
        }
        if (
            output.intentDecision.requestedLocalCapability !in
            TutorLobbyOutput.ALLOWED_LOCAL_CAPABILITIES
        ) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION,
                ),
            )
        }
    }

    private fun validateProblemOrganization(
        input: ProblemOrganizationInput,
        output: ProblemOrganizationOutput,
    ): List<ModelTaskCompletionIssue> = buildList {
        if (
            output.problemId != input.problemId ||
            output.problemRevisionId != input.problemRevisionId ||
            output.practiceUnitId != input.practiceUnitId
        ) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.ORGANIZATION_CONTEXT_MISMATCH,
                ),
            )
        }
        if (output.plan.schemaVersion < ProblemOrganizationPlan.SCHEMA_VERSION) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.ORGANIZATION_ATOMIC_DECOMPOSITION_REQUIRED,
                ),
            )
        }
        val disclosedLabels = input.relevantLearningEvidence
            .mapTo(mutableSetOf(), TutorKnowledgeEvidence::displayName)
        if (output.plan.targetedEvidenceLabels.any { it !in disclosedLabels }) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.MODEL_CLAIMED_UNDISCLOSED_EVIDENCE,
                ),
            )
        }
        val contextById = input.knowledgeBaseNodes.associateBy(
            KnowledgeBaseNodeContext::knowledgeNodeId,
        )
        val matchedNodeIdByReference = output.plan.atomicKnowledge.associate { atom ->
            atom.referenceId to atom.matchedKnowledgeNodeId
        }
        val hasInventedPrerequisite = output.plan.atomicKnowledge.any { dependent ->
            val allowed = dependent.matchedKnowledgeNodeId
                ?.let(contextById::get)
                ?.prerequisiteKnowledgeNodeIds
                .orEmpty()
                .toSet()
            dependent.prerequisiteReferenceIds.any { prerequisiteReference ->
                matchedNodeIdByReference[prerequisiteReference] !in allowed
            }
        }
        if (hasInventedPrerequisite) {
            add(
                ModelTaskCompletionIssue(
                    ModelTaskCompletionIssueCode.ORGANIZATION_UNKNOWN_KNOWLEDGE_PREREQUISITE,
                ),
            )
        }
        // Relations are optional enrichment. Their target allowlist and confidence are evaluated
        // independently by the local acceptance policy so a bad relation cannot discard valid
        // chapter/knowledge classifications from the same response.
    }
}

private fun NormalizedSourceRegion.contains(other: NormalizedSourceRegion): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
