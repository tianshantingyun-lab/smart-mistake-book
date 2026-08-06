@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.tingyun.smartmistakebook.core.model

import java.util.Locale
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RelatedProblemCandidate(
    val problemId: String,
    val problemRevisionId: String,
    val subject: SubjectKind,
    val title: String,
    val questionDocument: QuestionDocument,
) {
    init {
        problemId.requireSafeModelText("Related problem id", ModelTaskRequest.MAX_ID_CHARS, false)
        problemRevisionId.requireSafeModelText(
            "Related problem revision id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        title.requireSafeModelText("Related problem title", MAX_TITLE_CHARS, false)
        require(questionDocument.blocks.isNotEmpty()) { "A related candidate needs question content" }
    }

    companion object {
        const val MAX_TITLE_CHARS = 160
    }
}

/** Immutable catalog snapshot that produced one bounded model knowledge context. */
@Serializable
data class KnowledgeBaseCatalogProvenance(
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestFingerprint: String,
    val activationGeneration: Long,
) {
    init {
        packId.requireSafeModelText(
            "Knowledge-base pack id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        knowledgePackVersion.requireSafeModelText(
            "Knowledge-base pack version",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        taxonomyVersion.requireSafeModelText(
            "Knowledge-base taxonomy version",
            KnowledgeBaseNodeContext.MAX_TAXONOMY_VERSION_CHARS,
            false,
        )
        require(KNOWLEDGE_MANIFEST_FINGERPRINT.matches(manifestFingerprint)) {
            "Knowledge-base manifest fingerprint must be lowercase SHA-256"
        }
        require(activationGeneration > 0L) {
            "Knowledge-base activation generation must be positive"
        }
    }
}

/** A bounded slice of the local subject knowledge base. Local ids are aliased before egress. */
@Serializable
data class KnowledgeBaseNodeContext(
    val knowledgeNodeId: String,
    val subject: SubjectKind,
    val canonicalName: String,
    val aliases: List<String>,
    val kind: KnowledgeNodeKind,
    val granularity: KnowledgeNodeGranularity,
    val parentCanonicalName: String?,
    val taxonomyVersion: String,
    val catalogProvenance: KnowledgeBaseCatalogProvenance,
    val verificationStatus: KnowledgeNodeVerificationStatus,
    val boundaryMarkdown: String? = null,
    val prerequisiteKnowledgeNodeIds: List<String> = emptyList(),
) {
    init {
        knowledgeNodeId.requireSafeModelText(
            "Knowledge-base node id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        canonicalName.requireSafeModelText("Knowledge-base canonical name", MAX_LABEL_CHARS, false)
        require(aliases.size <= MAX_ALIASES)
        aliases.forEach { it.requireSafeModelText("Knowledge-base alias", MAX_LABEL_CHARS, false) }
        require(aliases.distinct().size == aliases.size) { "Knowledge-base aliases must be unique" }
        parentCanonicalName?.requireSafeModelText(
            "Knowledge-base parent name",
            MAX_LABEL_CHARS,
            false,
        )
        taxonomyVersion.requireSafeModelText(
            "Knowledge-base taxonomy version",
            MAX_TAXONOMY_VERSION_CHARS,
            false,
        )
        require(taxonomyVersion == catalogProvenance.taxonomyVersion) {
            "Knowledge-base node and catalog provenance must share one taxonomy"
        }
        require((kind == KnowledgeNodeKind.TOPIC) == (granularity == KnowledgeNodeGranularity.TOPIC)) {
            "Only topic knowledge-base nodes may use topic granularity"
        }
        require(granularity != KnowledgeNodeGranularity.ATOMIC || parentCanonicalName != null) {
            "Every atomic knowledge-base node must belong to a visible knowledge topic"
        }
        boundaryMarkdown?.requireOrganizationMarkdown(
            "Knowledge-base node boundary",
            AtomicKnowledgeSuggestion.MAX_EXPLANATION_CHARS,
        )
        require(prerequisiteKnowledgeNodeIds.size <= AtomicKnowledgeSuggestion.MAX_PREREQUISITES)
        require(prerequisiteKnowledgeNodeIds.none(String::isBlank))
        require(prerequisiteKnowledgeNodeIds.distinct().size == prerequisiteKnowledgeNodeIds.size)
        require(knowledgeNodeId !in prerequisiteKnowledgeNodeIds)
    }

    companion object {
        const val MAX_LABEL_CHARS = 96
        const val MAX_ALIASES = 8
        const val MAX_TAXONOMY_VERSION_CHARS = 160
    }
}

/**
 * One bounded organization request for a committed revision. Local identifiers stay in the task
 * envelope; external adapters must replace them with opaque aliases before disclosure.
 */
@Serializable
@SerialName("problem_organization")
data class ProblemOrganizationInput(
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val subject: SubjectKind,
    val questionDocument: QuestionDocument,
    val relevantLearningEvidence: List<TutorKnowledgeEvidence>,
    val relationCandidates: List<RelatedProblemCandidate>,
    val knowledgeBaseNodes: List<KnowledgeBaseNodeContext> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.PROBLEM_CLASSIFY

    override val subjectId: String
        get() = problemRevisionId

    init {
        problemId.requireSafeModelText("Organization problem id", ModelTaskRequest.MAX_ID_CHARS, false)
        problemRevisionId.requireSafeModelText(
            "Organization revision id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        practiceUnitId.requireSafeModelText(
            "Organization practice unit id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(questionDocument.blocks.isNotEmpty()) { "Organization requires a confirmed question" }
        require(relevantLearningEvidence.isEmpty()) {
            "Problem organization must not carry learning-mastery evidence"
        }
        require(relevantLearningEvidence.size <= MAX_RELEVANT_EVIDENCE)
        require(relationCandidates.size <= MAX_RELATION_CANDIDATES)
        require(knowledgeBaseNodes.size <= MAX_KNOWLEDGE_BASE_NODES)
        require(
            relevantLearningEvidence.map(TutorKnowledgeEvidence::knowledgeNodeId).distinct().size ==
                relevantLearningEvidence.size,
        ) { "Organization evidence ids must be unique" }
        require(relationCandidates.none { it.problemId == problemId }) {
            "A problem cannot be its own relation candidate"
        }
        require(relationCandidates.map(RelatedProblemCandidate::problemId).distinct().size == relationCandidates.size) {
            "Relation candidate problem ids must be unique"
        }
        require(knowledgeBaseNodes.all { it.subject == subject }) {
            "Organization knowledge context must stay inside one subject"
        }
        require(knowledgeBaseNodes.map(KnowledgeBaseNodeContext::knowledgeNodeId).distinct().size == knowledgeBaseNodes.size) {
            "Organization knowledge context ids must be unique"
        }
        require(
            knowledgeBaseNodes.map(KnowledgeBaseNodeContext::catalogProvenance).distinct().size <= 1,
        ) {
            "Organization knowledge context must come from one catalog snapshot"
        }
        val disclosedKnowledgeIds = knowledgeBaseNodes.mapTo(hashSetOf()) { it.knowledgeNodeId }
        require(knowledgeBaseNodes.all { node ->
            node.prerequisiteKnowledgeNodeIds.all(disclosedKnowledgeIds::contains)
        }) { "Organization knowledge prerequisites must stay inside the bounded context" }
    }

    companion object {
        const val MAX_RELEVANT_EVIDENCE = 12
        const val MAX_RELATION_CANDIDATES = 8
        const val MAX_KNOWLEDGE_BASE_NODES = 64
    }
}

private val KNOWLEDGE_MANIFEST_FINGERPRINT = Regex("[0-9a-f]{64}")

/**
 * Image-grounded organization request. The model receives only aliased identifiers; source
 * coordinates remain local and error candidates can refer only to the exact captured evidence.
 */
@Serializable
@SerialName("problem_organization_v3")
data class ProblemOrganizationV3Input(
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val subject: SubjectKind,
    val capturedDocument: CapturedQuestionDocument,
    val sourceAssets: List<CaptureSourceAssetRef>,
    val relationCandidates: List<RelatedProblemCandidate>,
    val knowledgeBaseNodes: List<KnowledgeBaseNodeContext> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.PROBLEM_CLASSIFY

    override val subjectId: String
        get() = problemRevisionId

    init {
        problemId.requireSafeModelText("Organization problem id", ModelTaskRequest.MAX_ID_CHARS, false)
        problemRevisionId.requireSafeModelText(
            "Organization revision id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        practiceUnitId.requireSafeModelText(
            "Organization practice unit id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(capturedDocument.document.blocks.isNotEmpty()) {
            "Organization requires a captured question"
        }
        require(CapturedQuestionDocumentValidator.validateDraft(capturedDocument).isEmpty()) {
            "Organization captured document is invalid"
        }
        require(
            capturedDocument.blockEvidence.all { evidence ->
                evidence.reviewStatus == QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED ||
                    evidence.reviewStatus == QuestionBlockReviewStatus.USER_CONFIRMED
            },
        ) { "Organization requires committed block evidence" }
        require(sourceAssets.isNotEmpty()) { "Organization requires exact source assets" }
        require(sourceAssets.size <= MAX_CAPTURE_SOURCE_ASSETS) {
            "Organization source asset scope exceeds budget"
        }
        require(sourceAssets.map(CaptureSourceAssetRef::assetId).distinct().size == sourceAssets.size) {
            "Organization source asset ids must be unique"
        }
        require(sourceAssets.map(CaptureSourceAssetRef::pageIndex).distinct().size == sourceAssets.size) {
            "Organization source page indexes must be unique"
        }
        require(sourceAssets.map(CaptureSourceAssetRef::pageIndex).sorted() == sourceAssets.indices.toList()) {
            "Organization source page indexes must be contiguous from zero"
        }
        val sourceById = sourceAssets.associateBy(CaptureSourceAssetRef::assetId)
        require(capturedDocument.blockEvidence.all { evidence ->
            val source = sourceById[evidence.sourceAssetId] ?: return@all false
            val evidenceRegion = evidence.sourceRegion
            source.selectedRegion == null ||
                evidenceRegion == null ||
                source.selectedRegion.containsOrganizationRegion(evidenceRegion)
        }) { "Organization block evidence must stay inside its exact source asset scope" }
        require(relationCandidates.isEmpty()) {
            "Problem organization v3 must not disclose other questions"
        }
        require(knowledgeBaseNodes.size <= ProblemOrganizationInput.MAX_KNOWLEDGE_BASE_NODES)
        require(knowledgeBaseNodes.all { it.subject == subject }) {
            "Organization knowledge context must stay inside one subject"
        }
        require(knowledgeBaseNodes.map(KnowledgeBaseNodeContext::knowledgeNodeId).distinct().size == knowledgeBaseNodes.size) {
            "Organization knowledge context ids must be unique"
        }
        require(
            knowledgeBaseNodes.map(KnowledgeBaseNodeContext::catalogProvenance).distinct().size <= 1,
        ) {
            "Organization knowledge context must come from one catalog snapshot"
        }
        val disclosedKnowledgeIds = knowledgeBaseNodes.mapTo(hashSetOf()) { it.knowledgeNodeId }
        require(knowledgeBaseNodes.all { node ->
            node.prerequisiteKnowledgeNodeIds.all(disclosedKnowledgeIds::contains)
        }) { "Organization knowledge prerequisites must stay inside the bounded context" }
    }
}

@Serializable
data class ProblemClassificationSuggestion(
    val dimension: ClassificationDimension,
    val displayName: String,
    val rationaleMarkdown: String,
    val confidence: Double,
) {
    init {
        require(dimension in PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS) {
            "Problem organization only accepts chapter and knowledge classifications"
        }
        displayName.requireSafeModelText("Classification label", MAX_LABEL_CHARS, false)
        rationaleMarkdown.requireOrganizationMarkdown("Classification rationale", MAX_RATIONALE_CHARS)
        displayName.requireStudentFacingOrganizationText("Classification label")
        rationaleMarkdown.requireStudentFacingOrganizationText("Classification rationale")
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    companion object {
        const val MAX_LABEL_CHARS = 96
        const val MAX_RATIONALE_CHARS = 1_000
    }
}

@Serializable
data class ProblemRelationSuggestion(
    val targetProblemId: String,
    val targetProblemRevisionId: String,
    val kind: ProblemRelationKind,
    val rationaleMarkdown: String,
    val confidence: Double,
) {
    init {
        targetProblemId.requireSafeModelText(
            "Relation target problem id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        targetProblemRevisionId.requireSafeModelText(
            "Relation target revision id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(kind in PROBLEM_ORGANIZATION_RELATION_KINDS) {
            "This relation kind is not part of problem organization"
        }
        rationaleMarkdown.requireOrganizationMarkdown("Relation rationale", MAX_RATIONALE_CHARS)
        rationaleMarkdown.requireStudentFacingOrganizationText("Relation rationale")
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    companion object {
        const val MAX_RATIONALE_CHARS = 1_200
    }
}

@Serializable
data class AtomicKnowledgeSuggestion(
    val referenceId: String,
    val canonicalName: String,
    val aliases: List<String>,
    val kind: KnowledgeNodeKind,
    val parentKnowledgeDisplayName: String,
    val matchedKnowledgeNodeId: String?,
    val prerequisiteReferenceIds: List<String>,
    val observableOutcomeMarkdown: String,
    val boundaryMarkdown: String,
    val confidence: Double,
) {
    init {
        referenceId.requireSafeModelText("Atomic knowledge reference", MAX_REFERENCE_CHARS, false)
        canonicalName.requireSafeModelText("Atomic knowledge name", MAX_LABEL_CHARS, false)
        require(kind != KnowledgeNodeKind.TOPIC) { "An atomic knowledge node cannot be a topic" }
        parentKnowledgeDisplayName.requireSafeModelText(
            "Atomic knowledge parent",
            MAX_LABEL_CHARS,
            false,
        )
        matchedKnowledgeNodeId?.requireSafeModelText(
            "Matched knowledge-node id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(aliases.size <= MAX_ALIASES)
        aliases.forEach { it.requireSafeModelText("Atomic knowledge alias", MAX_LABEL_CHARS, false) }
        require(aliases.distinct().size == aliases.size) { "Atomic knowledge aliases must be unique" }
        require(canonicalName !in aliases) { "Atomic knowledge aliases must not repeat its canonical name" }
        require(prerequisiteReferenceIds.size <= MAX_PREREQUISITES)
        require(prerequisiteReferenceIds.none(String::isBlank))
        require(prerequisiteReferenceIds.distinct().size == prerequisiteReferenceIds.size)
        require(referenceId !in prerequisiteReferenceIds) { "An atomic node cannot require itself" }
        observableOutcomeMarkdown.requireOrganizationMarkdown(
            "Atomic knowledge observable outcome",
            MAX_EXPLANATION_CHARS,
        )
        boundaryMarkdown.requireOrganizationMarkdown(
            "Atomic knowledge boundary",
            MAX_EXPLANATION_CHARS,
        )
        canonicalName.requireStudentFacingOrganizationText("Knowledge point name")
        aliases.forEach { it.requireStudentFacingOrganizationText("Knowledge point alias") }
        parentKnowledgeDisplayName.requireStudentFacingOrganizationText("Knowledge point parent")
        observableOutcomeMarkdown.requireStudentFacingOrganizationText("Knowledge point outcome")
        boundaryMarkdown.requireStudentFacingOrganizationText("Knowledge point boundary")
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    companion object {
        const val MAX_REFERENCE_CHARS = 48
        const val MAX_LABEL_CHARS = 96
        const val MAX_ALIASES = 8
        const val MAX_PREREQUISITES = 8
        const val MAX_EXPLANATION_CHARS = 600
    }
}

@Serializable
data class ProblemStepKnowledgeAttribution(
    val stepOrdinal: Int,
    val stepSummaryMarkdown: String,
    val atomicReferenceIds: List<String>,
) {
    init {
        require(stepOrdinal > 0) { "Organization step ordinal must be positive" }
        stepSummaryMarkdown.requireOrganizationMarkdown(
            "Organization step summary",
            MAX_STEP_SUMMARY_CHARS,
        )
        stepSummaryMarkdown.requireStudentFacingOrganizationText("Organization step summary")
        require(atomicReferenceIds.isNotEmpty()) { "Every organization step needs an atomic ability" }
        require(atomicReferenceIds.size <= MAX_ATOMS_PER_STEP)
        require(atomicReferenceIds.none(String::isBlank))
        require(atomicReferenceIds.distinct().size == atomicReferenceIds.size)
    }

    companion object {
        const val MAX_STEP_SUMMARY_CHARS = 500
        const val MAX_ATOMS_PER_STEP = 8
    }
}

/** A fail-closed request for authoritative grounding when the model cannot atomize safely. */
@Serializable
data class KnowledgeGroundingRequest(
    val query: String,
    val expectedParentKnowledgeDisplayName: String,
    val reasonMarkdown: String,
) {
    init {
        query.requireSafeModelText("Knowledge grounding query", MAX_QUERY_CHARS, false)
        expectedParentKnowledgeDisplayName.requireSafeModelText(
            "Knowledge grounding parent",
            AtomicKnowledgeSuggestion.MAX_LABEL_CHARS,
            false,
        )
        reasonMarkdown.requireOrganizationMarkdown(
            "Knowledge grounding reason",
            MAX_REASON_CHARS,
        )
    }

    companion object {
        const val MAX_QUERY_CHARS = 160
        const val MAX_REASON_CHARS = 500
    }
}

@Serializable
enum class ProblemErrorAttributionResolutionStatus {
    RESOLVED,
    UNRESOLVED,
}

@Serializable
enum class ProblemErrorEvidenceKind {
    QUESTION_CONTENT,
    STUDENT_WORK,
    MARKING_OR_CORRECTION,
}

@Serializable
data class ProblemErrorEvidenceRef(
    val blockId: String,
    val sourceAssetId: String,
    val evidenceKind: ProblemErrorEvidenceKind,
) {
    init {
        blockId.requireSafeModelText(
            "Error evidence block id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        sourceAssetId.requireSafeModelText(
            "Error evidence source asset id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
    }
}

/**
 * A review candidate, never a trusted diagnosis. Resolved candidates are fully grounded in one
 * disclosed solution step, one disclosed atomic reference, and exact captured block evidence.
 */
@Serializable
data class ProblemErrorAttributionCandidate(
    val resolutionStatus: ProblemErrorAttributionResolutionStatus,
    val rationaleMarkdown: String,
    val confidence: Double,
    val stepOrdinal: Int? = null,
    val atomicReferenceId: String? = null,
    val evidenceRefs: List<ProblemErrorEvidenceRef> = emptyList(),
) {
    init {
        rationaleMarkdown.requireOrganizationMarkdown(
            "Error attribution rationale",
            MAX_RATIONALE_CHARS,
        )
        rationaleMarkdown.requireStudentFacingOrganizationText("Error attribution rationale")
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidenceRefs.size <= MAX_EVIDENCE_REFS)
        require(evidenceRefs.distinct().size == evidenceRefs.size) {
            "Error attribution evidence references must be unique"
        }
        when (resolutionStatus) {
            ProblemErrorAttributionResolutionStatus.RESOLVED -> {
                require(stepOrdinal != null && stepOrdinal > 0) {
                    "A resolved error attribution must reference a disclosed step"
                }
                atomicReferenceId?.requireSafeModelText(
                    "Error attribution atomic reference",
                    AtomicKnowledgeSuggestion.MAX_REFERENCE_CHARS,
                    false,
                ) ?: error("A resolved error attribution must reference a disclosed atomic node")
                require(evidenceRefs.isNotEmpty()) {
                    "A resolved error attribution must reference exact captured evidence"
                }
            }

            ProblemErrorAttributionResolutionStatus.UNRESOLVED -> {
                require(stepOrdinal == null) {
                    "An unresolved error attribution cannot invent a step reference"
                }
                require(atomicReferenceId == null) {
                    "An unresolved error attribution cannot invent an atomic reference"
                }
                require(evidenceRefs.isEmpty()) {
                    "An unresolved error attribution cannot invent evidence references"
                }
            }
        }
    }

    companion object {
        const val MAX_RATIONALE_CHARS = 1_200
        const val MAX_EVIDENCE_REFS = 8
    }
}

/** Reviewed-stable problem-family identity proposed by the model for one saved mistake. */
@Serializable
data class ProblemFamilySuggestion(
    val familyKey: String,
    val rationaleMarkdown: String,
    val confidence: Double,
) {
    init {
        familyKey.requireSafeModelText("Problem family key", MAX_FAMILY_KEY_CHARS, false)
        require(PROBLEM_FAMILY_KEY.matches(familyKey)) {
            "Problem family key must be a stable lowercase opaque identity"
        }
        rationaleMarkdown.requireOrganizationMarkdown(
            "Problem family rationale",
            MAX_RATIONALE_CHARS,
        )
        rationaleMarkdown.requireStudentFacingOrganizationText("Problem family rationale")
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    companion object {
        const val MAX_FAMILY_KEY_CHARS = 64
        val PROBLEM_FAMILY_KEY = Regex("[a-z0-9][a-z0-9._-]{7,63}")
        const val MAX_RATIONALE_CHARS = 600
    }
}

@Serializable
data class ProblemOrganizationPlan(
    val summaryMarkdown: String,
    val reviewPriorityMarkdown: String,
    val targetedEvidenceLabels: List<String>,
    val classifications: List<ProblemClassificationSuggestion>,
    val relations: List<ProblemRelationSuggestion>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val problemFamily: ProblemFamilySuggestion? = null,
    /** Version one remains readable for persisted tasks; all new requests require version two. */
    val schemaVersion: Int = 1,
    val atomicKnowledge: List<AtomicKnowledgeSuggestion> = emptyList(),
    val stepAttributions: List<ProblemStepKnowledgeAttribution> = emptyList(),
    val groundingRequests: List<KnowledgeGroundingRequest> = emptyList(),
    val errorAttributionCandidates: List<ProblemErrorAttributionCandidate> = emptyList(),
) {
    init {
        summaryMarkdown.requireOrganizationMarkdown("Organization summary", MAX_SUMMARY_CHARS)
        reviewPriorityMarkdown.requireOrganizationMarkdown(
            "Organization review priority",
            MAX_SUMMARY_CHARS,
        )
        summaryMarkdown.requireStudentFacingOrganizationText("Organization summary")
        reviewPriorityMarkdown.requireStudentFacingOrganizationText("Organization review priority")
        require(classifications.size in 1..MAX_CLASSIFICATIONS)
        require(relations.size <= ProblemOrganizationInput.MAX_RELATION_CANDIDATES)
        require(targetedEvidenceLabels.size <= ProblemOrganizationInput.MAX_RELEVANT_EVIDENCE)
        targetedEvidenceLabels.forEach { label ->
            label.requireSafeModelText(
                "Organization evidence label",
                TutorKnowledgeEvidence.MAX_LABEL_CHARS,
                false,
            )
            label.requireStudentFacingOrganizationText("Organization evidence label")
        }
        require(targetedEvidenceLabels.distinct().size == targetedEvidenceLabels.size)
        require(classifications.distinctBy { it.dimension to it.displayName }.size == classifications.size)
        require(relations.distinctBy { Triple(it.targetProblemId, it.kind, it.targetProblemRevisionId) }.size == relations.size)
        require(classifications.any { it.dimension == ClassificationDimension.KNOWLEDGE }) {
            "Organization must identify at least one knowledge label"
        }
        require(schemaVersion in 1..SCHEMA_VERSION) { "Unsupported organization schema version" }
        require(schemaVersion >= 3 || problemFamily == null) {
            "Problem-family suggestions require organization schema three"
        }
        require(errorAttributionCandidates.size <= MAX_ERROR_ATTRIBUTION_CANDIDATES)
        require(schemaVersion >= 3 || errorAttributionCandidates.isEmpty()) {
            "Legacy organization plans cannot contain error attribution candidates"
        }
        if (schemaVersion >= 2) {
            require(atomicKnowledge.size <= MAX_ATOMIC_KNOWLEDGE)
            require(stepAttributions.size <= MAX_STEP_ATTRIBUTIONS)
            require(groundingRequests.size <= MAX_GROUNDING_REQUESTS)
            require((atomicKnowledge.isNotEmpty()) xor (groundingRequests.isNotEmpty())) {
                "A grounded organization needs atoms; an unresolved one needs grounding requests"
            }
            val visibleKnowledge = classifications
                .filter { it.dimension == ClassificationDimension.KNOWLEDGE }
                .map { it.displayName.normalizedKnowledgeLabel() }
                .toSet()
            groundingRequests.forEach { request ->
                require(request.expectedParentKnowledgeDisplayName.normalizedKnowledgeLabel() in visibleKnowledge) {
                    "A grounding request must belong to a visible knowledge classification"
                }
            }
            if (atomicKnowledge.isNotEmpty()) {
                require(stepAttributions.isNotEmpty()) {
                    "Grounded atomic knowledge needs a step attribution map"
                }
                require(atomicKnowledge.map(AtomicKnowledgeSuggestion::referenceId).distinct().size == atomicKnowledge.size) {
                    "Atomic knowledge references must be unique"
                }
                require(stepAttributions.map(ProblemStepKnowledgeAttribution::stepOrdinal).distinct().size == stepAttributions.size) {
                    "Organization step ordinals must be unique"
                }
                val atomicReferences = atomicKnowledge.mapTo(hashSetOf()) { it.referenceId }
                atomicKnowledge.forEach { atom ->
                    require(atom.parentKnowledgeDisplayName.normalizedKnowledgeLabel() in visibleKnowledge) {
                        "Every atomic node must belong to a visible knowledge classification"
                    }
                    require(atom.prerequisiteReferenceIds.all(atomicReferences::contains)) {
                        "Atomic prerequisites must reference nodes in the same bounded plan"
                    }
                }
                val attributedReferences = stepAttributions.flatMapTo(hashSetOf()) { step ->
                    require(step.atomicReferenceIds.all(atomicReferences::contains)) {
                        "Step attributions must reference known atomic nodes"
                    }
                    step.atomicReferenceIds
                }
                require(attributedReferences == atomicReferences) {
                    "Every atomic node must be observable in at least one solution step"
                }
            } else {
                require(stepAttributions.isEmpty()) {
                    "An unresolved organization cannot invent step attributions"
                }
            }
        }
        if (schemaVersion >= 3) {
            val disclosedStepOrdinals =
                stepAttributions.mapTo(hashSetOf(), ProblemStepKnowledgeAttribution::stepOrdinal)
            val disclosedAtomicReferences =
                atomicKnowledge.mapTo(hashSetOf(), AtomicKnowledgeSuggestion::referenceId)
            errorAttributionCandidates
                .filter {
                    it.resolutionStatus == ProblemErrorAttributionResolutionStatus.RESOLVED
                }
                .forEach { candidate ->
                    require(candidate.stepOrdinal in disclosedStepOrdinals) {
                        "A resolved error attribution must reference a disclosed step"
                    }
                    require(candidate.atomicReferenceId in disclosedAtomicReferences) {
                        "A resolved error attribution must reference a disclosed atomic node"
                    }
                }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 3
        const val MAX_SUMMARY_CHARS = 2_000
        const val MAX_CLASSIFICATIONS = 16
        const val MAX_ATOMIC_KNOWLEDGE = 24
        const val MAX_STEP_ATTRIBUTIONS = 16
        const val MAX_GROUNDING_REQUESTS = 4
        const val MAX_ERROR_ATTRIBUTION_CANDIDATES = 16
    }
}

private fun String.normalizedKnowledgeLabel(): String =
    trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun NormalizedSourceRegion.containsOrganizationRegion(
    other: NormalizedSourceRegion,
): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

@Serializable
@SerialName("problem_organization_output")
data class ProblemOrganizationOutput(
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val plan: ProblemOrganizationPlan,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        problemId.requireSafeModelText("Organization output problem id", ModelTaskRequest.MAX_ID_CHARS, false)
        problemRevisionId.requireSafeModelText(
            "Organization output revision id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        practiceUnitId.requireSafeModelText(
            "Organization output practice unit id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        modelVersion.requireSafeModelText("Organization model version", MAX_MODEL_VERSION_CHARS, false)
    }
}

val PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS = setOf(
    ClassificationDimension.CHAPTER,
    ClassificationDimension.KNOWLEDGE,
)

val PROBLEM_ORGANIZATION_RELATION_KINDS = setOf(
    ProblemRelationKind.SAME_KNOWLEDGE,
    ProblemRelationKind.VARIANT_OF,
    ProblemRelationKind.PREREQUISITE_OF,
    ProblemRelationKind.SAME_FIGURE_PATTERN,
    ProblemRelationKind.POSSIBLE_DUPLICATE,
)

private fun String.requireOrganizationMarkdown(label: String, maxChars: Int) {
    requireSafeModelText(label, maxChars, true)
    val normalized = lowercase()
    require("<script" !in normalized && "javascript:" !in normalized) {
        "$label contains active content"
    }
}

private fun String.requireStudentFacingOrganizationText(label: String) {
    StudentFacingLanguagePolicy.requirePlainLanguage(this, label)
}
