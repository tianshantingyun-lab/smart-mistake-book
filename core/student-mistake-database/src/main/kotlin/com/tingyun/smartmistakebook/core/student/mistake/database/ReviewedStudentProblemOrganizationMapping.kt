package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskCompletionValidator
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemFamilySuggestion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Locale

/**
 * The only semantics-preserving projection from a reviewed organization-v3 result into the
 * student-mistake authority.
 *
 * The current model schema does not contain final-answer, per-step result, or organization-facet
 * semantics. This mapping therefore leaves those fields empty instead of manufacturing meaning.
 */
const val STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION =
    "reviewed-problem-organization-v3-to-student-store-v1"

data class ReviewedStudentProblemOrganizationLocalContext(
    val receiptId: String,
    val organizationRevision: Int,
    val supersedesReceiptId: String?,
    val previousPayloadCanonicalFingerprint: String?,
    val problemRevision: com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef,
    val errorOccurrences: List<StudentProblemErrorOccurrenceRef>,
    val reviewIssuedAtEpochMillis: Long,
    val reviewExpiresAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
)

data class ReviewedStudentProblemOrganizationBinding(
    val reviewedOutputCanonicalFingerprint: String,
    val mappingPolicyVersion: String,
    val knowledgeManifestFingerprint: String,
    val knowledgeActivationGeneration: Long,
    val finalCommandCanonicalFingerprint: String,
)

object ReviewedStudentProblemOrganizationMapping {
    @JvmStatic
    fun mapUnsigned(
        local: ReviewedStudentProblemOrganizationLocalContext,
        reviewedTask: ModelTaskSnapshot,
        verifiedKnowledgeReferences: List<VerifiedKnowledgeReferenceProof>,
    ): OrganizeStudentProblemCommand {
        val reviewed = reviewedTask.requireReviewedOrganizationV3()
        val input = reviewed.input
        val output = reviewed.output
        val provider = checkNotNull(reviewedTask.provider) {
            "Organization mapping requires the reviewed provider snapshot"
        }
        val egress = checkNotNull(reviewedTask.request.egressManifest) {
            "Organization mapping requires the reviewed egress manifest"
        }
        require(
            provider.providerId == egress.providerId &&
                provider.modelId == egress.modelId &&
                provider.providerConfigurationVersion == egress.providerConfigurationVersion,
        ) {
            "Organization provider and egress identities disagree"
        }
        require(
            local.problemRevision.problem.problemId == input.problemId &&
                local.problemRevision.revisionId == input.problemRevisionId &&
                local.problemRevision.problem.practiceUnitId == input.practiceUnitId &&
                local.problemRevision.problem.subject == input.subject &&
                local.problemRevision.documentCanonicalFingerprint ==
                CapturedQuestionDocumentFingerprint.of(input.capturedDocument),
        ) {
            "Organization mapping targets a different committed problem revision"
        }
        require(
            reviewedTask.updatedAtEpochMillis <= local.reviewIssuedAtEpochMillis &&
                local.completedAtEpochMillis in
                local.reviewIssuedAtEpochMillis..local.reviewExpiresAtEpochMillis,
        ) {
            "Organization mapping review chronology is invalid"
        }

        val outputFingerprint = reviewedOutputFingerprint(output)
        val knowledge = resolveKnowledge(input, output, verifiedKnowledgeReferences)
        val solution = mapSolution(local, output, outputFingerprint, provider.providerId, provider.modelId)
        val classifications =
            mapClassifications(
                local = local,
                output = output,
                knowledge = knowledge,
                outputFingerprint = outputFingerprint,
            )
        val stepBindings =
            mapStepKnowledgeBindings(
                local = local,
                output = output,
                solution = solution,
                knowledge = knowledge,
                outputFingerprint = outputFingerprint,
            )
        val errors =
            mapErrorAttributions(
                local = local,
                output = output,
                solution = solution,
                outputFingerprint = outputFingerprint,
                modelProviderId = provider.providerId,
                modelId = provider.modelId,
            )
        val facets =
            output.plan.problemFamily
                ?.let { family ->
                    problemFamilyFacet(
                        family = family,
                        input = input,
                        output = output,
                        outputFingerprint = outputFingerprint,
                        knowledge = knowledge,
                    )
                }
                .let { listOfNotNull(it) }

        return OrganizeStudentProblemCommand(
            receiptId = local.receiptId,
            requestId = reviewedTask.request.requestId,
            requestCanonicalFingerprint = ModelTaskFingerprint.of(reviewedTask.request),
            organizationRevision = local.organizationRevision,
            supersedesReceiptId = local.supersedesReceiptId,
            previousPayloadCanonicalFingerprint = local.previousPayloadCanonicalFingerprint,
            problemRevision = local.problemRevision,
            errorOccurrences = local.errorOccurrences,
            provenance =
                StudentProblemOrganizationProvenance(
                    modelProviderId = provider.providerId,
                    modelId = provider.modelId,
                    requestedModelVersion = output.modelVersion,
                    resultModelVersion = output.modelVersion,
                    providerConfigurationVersion = provider.providerConfigurationVersion,
                    requestVersion = STUDENT_PROBLEM_ORGANIZATION_REQUEST_VERSION,
                    reviewedRequestVersion = STUDENT_PROBLEM_ORGANIZATION_REQUEST_VERSION,
                    modelTaskSchemaVersion = reviewedTask.request.schemaVersion,
                    organizationPlanSchemaVersion = output.plan.schemaVersion,
                    reviewSource = StudentProblemOrganizationReviewSource.LOCAL_POLICY_ACCEPTED,
                    reviewVersion = STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION,
                    reviewIssuerKeyId = STUDENT_PROBLEM_ORGANIZATION_REVIEW_ISSUER_KEY_ID,
                    reviewIssuerVersion = STUDENT_PROBLEM_ORGANIZATION_REVIEW_ISSUER_VERSION,
                    reviewIssuedAtEpochMillis = local.reviewIssuedAtEpochMillis,
                    reviewExpiresAtEpochMillis = local.reviewExpiresAtEpochMillis,
                ),
            solutionAnalysis = solution,
            errorAttributions = errors,
            classifications = classifications,
            stepKnowledgeBindings = stepBindings,
            facets = facets,
            completedAtEpochMillis = local.completedAtEpochMillis,
        )
    }

    @JvmStatic
    fun requireExact(
        command: OrganizeStudentProblemCommand,
        reviewedTask: ModelTaskSnapshot,
        verifiesKnowledgeReference: (VerifiedKnowledgeReferenceProof) -> Boolean,
    ): ReviewedStudentProblemOrganizationBinding {
        require(command.verifiedReviewProof == null) {
            "An organization command cannot arrive pre-signed"
        }
        val proofs =
            (
                command.classifications.map {
                    it.verifiedKnowledgeReference
                } +
                    command.stepKnowledgeBindings.map {
                        it.verifiedKnowledgeReference
                    }
            ).distinctBy(VerifiedKnowledgeReferenceProof::persistedIdentity)
        require(proofs.isNotEmpty() && proofs.all(verifiesKnowledgeReference)) {
            "Organization mapping requires locally verified knowledge references"
        }
        val local =
            ReviewedStudentProblemOrganizationLocalContext(
                receiptId = command.receiptId,
                organizationRevision = command.organizationRevision,
                supersedesReceiptId = command.supersedesReceiptId,
                previousPayloadCanonicalFingerprint =
                    command.previousPayloadCanonicalFingerprint,
                problemRevision = command.problemRevision,
                errorOccurrences = command.errorOccurrences,
                reviewIssuedAtEpochMillis =
                    command.provenance.reviewIssuedAtEpochMillis,
                reviewExpiresAtEpochMillis =
                    command.provenance.reviewExpiresAtEpochMillis,
                completedAtEpochMillis = command.completedAtEpochMillis,
            )
        val expected = mapUnsigned(local, reviewedTask, proofs)
        require(command == expected) {
            "Organization command is not the exact reviewed-output mapping"
        }
        val snapshot = proofs.singleSnapshot()
        return ReviewedStudentProblemOrganizationBinding(
            reviewedOutputCanonicalFingerprint =
                reviewedOutputFingerprint(
                    reviewedTask.output as ProblemOrganizationOutput,
                ),
            mappingPolicyVersion = STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION,
            knowledgeManifestFingerprint = snapshot.first,
            knowledgeActivationGeneration = snapshot.second,
            finalCommandCanonicalFingerprint = command.payloadCanonicalFingerprint,
        )
    }

    @JvmStatic
    fun reviewedOutputFingerprint(output: ProblemOrganizationOutput): String =
        CanonicalSha256(REVIEWED_OUTPUT_FINGERPRINT_DOMAIN)
            .field("encodedOutput", ModelTaskCodec.encodeOutput(output))
            .finish()
}

private data class ReviewedOrganizationV3(
    val input: ProblemOrganizationV3Input,
    val output: ProblemOrganizationOutput,
)

private data class ResolvedOrganizationKnowledge(
    val chapterSuggestion: ProblemClassificationSuggestion,
    val chapterProof: VerifiedKnowledgeReferenceProof,
    val atomProofs: Map<String, VerifiedKnowledgeReferenceProof>,
)

private fun ModelTaskSnapshot.requireReviewedOrganizationV3(): ReviewedOrganizationV3 {
    require(status == ModelTaskStatus.SUCCEEDED) {
        "Organization mapping requires a successful task"
    }
    val input = request.input as? ProblemOrganizationV3Input
        ?: error("Organization mapping requires a v3 input")
    val output = output as? ProblemOrganizationOutput
        ?: error("Organization mapping requires an organization output")
    ModelTaskCompletionValidator.requireValid(request, output)
    return ReviewedOrganizationV3(input, output)
}

private fun resolveKnowledge(
    input: ProblemOrganizationV3Input,
    output: ProblemOrganizationOutput,
    proofs: List<VerifiedKnowledgeReferenceProof>,
): ResolvedOrganizationKnowledge {
    val plan = output.plan
    val chapterSuggestion =
        plan.classifications.singleOrNull {
            it.dimension == ClassificationDimension.CHAPTER
        } ?: error("Reviewed organization requires exactly one chapter classification")
    require(
        plan.classifications.any {
            it.dimension == ClassificationDimension.KNOWLEDGE
        },
    ) {
        "Reviewed organization requires a visible knowledge classification"
    }
    require(plan.atomicKnowledge.isNotEmpty() && plan.groundingRequests.isEmpty()) {
        "Only fully grounded organization output may be mapped"
    }
    val contextById = input.knowledgeBaseNodes.associateBy(KnowledgeBaseNodeContext::knowledgeNodeId)
    val chapterCandidates =
        input.knowledgeBaseNodes.filter { node ->
            node.granularity == KnowledgeNodeGranularity.TOPIC &&
                node.names().contains(chapterSuggestion.displayName.normalizedOrganizationName())
        }
    val chapterNode =
        chapterCandidates.singleOrNull()
            ?: error("Chapter classification does not resolve to one disclosed topic")
    val atomsByReference = plan.atomicKnowledge.associateBy(AtomicKnowledgeSuggestion::referenceId)
    require(atomsByReference.size == plan.atomicKnowledge.size) {
        "Atomic knowledge references must be unique"
    }
    val nodeIdByAtomicReference =
        plan.atomicKnowledge.associate { atom ->
            val nodeId =
                atom.matchedKnowledgeNodeId
                    ?: error("Every persisted knowledge point must be locally grounded")
            val node =
                contextById[nodeId]
                    ?: error("Grounded knowledge point is outside the disclosed local context")
            require(
                atom.kind == node.kind &&
                    node.names().contains(atom.canonicalName.normalizedOrganizationName()) &&
                    node.parentCanonicalName?.normalizedOrganizationName() ==
                    atom.parentKnowledgeDisplayName.normalizedOrganizationName(),
            ) {
                "Grounded knowledge point semantics disagree with the local context"
            }
            atom.referenceId to nodeId
        }
    require(nodeIdByAtomicReference.values.distinct().size == nodeIdByAtomicReference.size) {
        "Two organization atoms cannot collapse into one persisted knowledge point"
    }
    val expectedNodeIds =
        setOf(chapterNode.knowledgeNodeId) + nodeIdByAtomicReference.values
    require(expectedNodeIds.size == nodeIdByAtomicReference.size + 1) {
        "Chapter and knowledge classifications require distinct formal nodes"
    }
    val proofsByNodeId =
        proofs.associateBy { it.ref.knowledgeNodeId }
    require(
        proofsByNodeId.size == proofs.size &&
            proofsByNodeId.keys == expectedNodeIds,
    ) {
        "Verified references must exactly cover the reviewed chapter and knowledge points"
    }
    proofs.forEach { proof ->
        val node =
            contextById[proof.ref.knowledgeNodeId]
                ?: error("Knowledge proof references an undisclosed node")
        require(
            proof.ref.subject == input.subject &&
                proof.ref.taxonomyVersion == node.taxonomyVersion,
        ) {
            "Knowledge proof does not match the disclosed local catalog node"
        }
    }
    proofs.singleSnapshot()
    return ResolvedOrganizationKnowledge(
        chapterSuggestion = chapterSuggestion,
        chapterProof = checkNotNull(proofsByNodeId[chapterNode.knowledgeNodeId]),
        atomProofs =
            nodeIdByAtomicReference.mapValues { (_, nodeId) ->
                checkNotNull(proofsByNodeId[nodeId])
            },
    )
}

private fun mapSolution(
    local: ReviewedStudentProblemOrganizationLocalContext,
    output: ProblemOrganizationOutput,
    outputFingerprint: String,
    modelProviderId: String,
    modelId: String,
): StudentProblemSolutionAnalysis {
    val orderedSteps = output.plan.stepAttributions
    require(orderedSteps.map(ProblemStepKnowledgeAttribution::stepOrdinal) ==
        (1..orderedSteps.size).toList()) {
        "Persisted solution steps must be contiguous and preserve reviewed order"
    }
    val solutionId =
        opaqueId(
            prefix = "solution",
            domain = SOLUTION_ID_DOMAIN,
            outputFingerprint,
        )
    val steps =
        orderedSteps.map { attribution ->
            val stepId =
                opaqueId(
                    prefix = "step-${attribution.stepOrdinal.toString().padStart(4, '0')}",
                    domain = STEP_ID_DOMAIN,
                    outputFingerprint,
                    attribution.stepOrdinal.toString(),
                    attribution.stepSummaryMarkdown,
                )
            StudentProblemSolutionStep(
                stepId = stepId,
                ordinal = attribution.stepOrdinal,
                summaryMarkdown = attribution.stepSummaryMarkdown,
                // V3 exposes one reviewed explanation per step; no second rationale is invented.
                reasoningMarkdown = attribution.stepSummaryMarkdown,
                resultMarkdown = null,
                stepCanonicalFingerprint =
                    semanticFingerprint(
                        STEP_FINGERPRINT_DOMAIN,
                        stepId,
                        attribution.stepOrdinal.toString(),
                        attribution.stepSummaryMarkdown,
                    ),
            )
        }
    return StudentProblemSolutionAnalysis(
        solutionAnalysisId = solutionId,
        problemRevision = local.problemRevision,
        summaryMarkdown = output.plan.summaryMarkdown,
        finalAnswerMarkdown = null,
        steps = steps,
        modelProviderId = modelProviderId,
        modelId = modelId,
        analyzerVersion = output.modelVersion,
        resultCanonicalFingerprint =
            semanticFingerprint(
                SOLUTION_FINGERPRINT_DOMAIN,
                outputFingerprint,
                solutionId,
            ),
        recordedAtEpochMillis = local.reviewIssuedAtEpochMillis,
    )
}

private fun mapClassifications(
    local: ReviewedStudentProblemOrganizationLocalContext,
    output: ProblemOrganizationOutput,
    knowledge: ResolvedOrganizationKnowledge,
    outputFingerprint: String,
): List<StudentProblemOrganizationClassification> {
    val chapter =
        StudentProblemOrganizationClassification(
            classificationId =
                indexedId(
                    "classification",
                    0,
                    CLASSIFICATION_ID_DOMAIN,
                    outputFingerprint,
                    knowledge.chapterProof.ref.canonicalFingerprint,
                ),
            dimension = StudentProblemClassificationDimension.CURRICULUM_SECTION,
            curriculumDisplayName = knowledge.chapterSuggestion.displayName,
            verifiedKnowledgeReference = knowledge.chapterProof,
            resultCanonicalFingerprint =
                semanticFingerprint(
                    CLASSIFICATION_FINGERPRINT_DOMAIN,
                    outputFingerprint,
                    "chapter",
                    knowledge.chapterSuggestion.displayName,
                    knowledge.chapterSuggestion.rationaleMarkdown,
                    java.lang.Double.toString(knowledge.chapterSuggestion.confidence),
                    knowledge.chapterProof.persistedIdentity(),
                ),
            recordedAtEpochMillis = local.reviewIssuedAtEpochMillis,
        )
    val atoms =
        output.plan.atomicKnowledge.mapIndexed { index, atom ->
            val proof = checkNotNull(knowledge.atomProofs[atom.referenceId])
            StudentProblemOrganizationClassification(
                classificationId =
                    indexedId(
                        "classification",
                        index + 1,
                        CLASSIFICATION_ID_DOMAIN,
                        outputFingerprint,
                        atom.referenceId,
                        proof.ref.canonicalFingerprint,
                    ),
                dimension = StudentProblemClassificationDimension.KNOWLEDGE,
                curriculumDisplayName = null,
                verifiedKnowledgeReference = proof,
                resultCanonicalFingerprint =
                    semanticFingerprint(
                        CLASSIFICATION_FINGERPRINT_DOMAIN,
                        outputFingerprint,
                        "knowledge",
                        atom.referenceId,
                        atom.canonicalName,
                        atom.parentKnowledgeDisplayName,
                        proof.persistedIdentity(),
                    ),
                recordedAtEpochMillis = local.reviewIssuedAtEpochMillis,
            )
        }
    return (listOf(chapter) + atoms).sortedBy {
        it.classificationId
    }
}

private fun mapStepKnowledgeBindings(
    local: ReviewedStudentProblemOrganizationLocalContext,
    output: ProblemOrganizationOutput,
    solution: StudentProblemSolutionAnalysis,
    knowledge: ResolvedOrganizationKnowledge,
    outputFingerprint: String,
): List<StudentProblemStepKnowledgeBinding> {
    val stepByOrdinal = solution.steps.associateBy(StudentProblemSolutionStep::ordinal)
    return output.plan.stepAttributions.flatMap { attribution ->
        attribution.atomicReferenceIds.mapIndexed { referenceIndex, referenceId ->
            val proof = checkNotNull(knowledge.atomProofs[referenceId])
            val step = checkNotNull(stepByOrdinal[attribution.stepOrdinal])
            val bindingId =
                indexedPairId(
                    prefix = "step-knowledge",
                    first = attribution.stepOrdinal,
                    second = referenceIndex,
                    domain = STEP_BINDING_ID_DOMAIN,
                    outputFingerprint,
                    referenceId,
                    proof.ref.canonicalFingerprint,
                )
            StudentProblemStepKnowledgeBinding(
                bindingId = bindingId,
                solutionAnalysisId = solution.solutionAnalysisId,
                stepId = step.stepId,
                stepOrdinal = attribution.stepOrdinal,
                knowledgeReferenceId = referenceId,
                verifiedKnowledgeReference = proof,
                bindingCanonicalFingerprint =
                    semanticFingerprint(
                        STEP_BINDING_FINGERPRINT_DOMAIN,
                        outputFingerprint,
                        bindingId,
                        step.stepId,
                        referenceId,
                        proof.persistedIdentity(),
                    ),
                recordedAtEpochMillis = local.reviewIssuedAtEpochMillis,
            )
        }
    }.sortedBy(StudentProblemStepKnowledgeBinding::bindingId)
}

private fun mapErrorAttributions(
    local: ReviewedStudentProblemOrganizationLocalContext,
    output: ProblemOrganizationOutput,
    solution: StudentProblemSolutionAnalysis,
    outputFingerprint: String,
    modelProviderId: String,
    modelId: String,
): List<StudentProblemErrorAttribution> =
    output.plan.errorAttributionCandidates.mapIndexed { index, candidate ->
        val attributionId =
            indexedId(
                "error-attribution",
                index,
                ERROR_ATTRIBUTION_ID_DOMAIN,
                outputFingerprint,
                candidate.semanticIdentity(),
            )
        StudentProblemErrorAttribution(
            attributionId = attributionId,
            problemRevision = local.problemRevision,
            solutionAnalysisId =
                if (
                    candidate.resolutionStatus ==
                    ProblemErrorAttributionResolutionStatus.RESOLVED
                ) {
                    solution.solutionAnalysisId
                } else {
                    null
                },
            candidate = candidate,
            modelProviderId = modelProviderId,
            modelId = modelId,
            analyzerVersion = output.modelVersion,
            resultCanonicalFingerprint =
                semanticFingerprint(
                    ERROR_ATTRIBUTION_FINGERPRINT_DOMAIN,
                    outputFingerprint,
                    index.toString(),
                    candidate.semanticIdentity(),
                ),
            recordedAtEpochMillis = local.reviewIssuedAtEpochMillis,
        )
    }

private fun problemFamilyFacet(
    family: ProblemFamilySuggestion,
    input: ProblemOrganizationV3Input,
    output: ProblemOrganizationOutput,
    outputFingerprint: String,
    knowledge: ResolvedOrganizationKnowledge,
): StudentProblemOrganizationFacet? {
    if (family.confidence < PROBLEM_FAMILY_ACCEPTANCE_CONFIDENCE) return null
    val evidence =
        (listOf(knowledge.chapterProof) + knowledge.atomProofs.values)
            .map(VerifiedKnowledgeReferenceProof::persistedIdentity)
            .distinct()
            .sorted()
    require(evidence.isNotEmpty()) {
        "Problem family requires locally verified knowledge evidence"
    }
    val binding =
        CanonicalSha256("student-organization-problem-family-local-evidence-v1")
            .field("policyVersion", PROBLEM_FAMILY_LOCAL_EVIDENCE_POLICY_VERSION)
            .field("familyId", family.familyKey)
            .field("familyVersion", output.modelVersion)
            .field("subject", input.subject.name)
            .field(
                "documentCanonicalFingerprint",
                CapturedQuestionDocumentFingerprint.of(input.capturedDocument),
            )
            .field("output", outputFingerprint)
            .field(
                "knowledgeManifest",
                knowledge.chapterProof.manifestFingerprint,
            )
            .field(
                "knowledgeActivationGeneration",
                knowledge.chapterProof.activationGeneration,
            )
            .field("evidenceCount", evidence.size)
    evidence.forEachIndexed { index, fingerprint ->
        binding.field("evidence[$index]", fingerprint)
    }
    return StudentProblemOrganizationFacet(
        dimension = StudentProblemOrganizationFacetDimension.PROBLEM_FAMILY,
        familyId = family.familyKey,
        familyVersion = output.modelVersion,
        bindingCanonicalFingerprint = binding.finish(),
    )
}

private fun ProblemErrorAttributionCandidate.semanticIdentity(): String =
    buildString {
        append(resolutionStatus.name)
        append('|').append(rationaleMarkdown)
        append('|').append(java.lang.Double.toString(confidence))
        append('|').append(stepOrdinal ?: "")
        append('|').append(atomicReferenceId.orEmpty())
        evidenceRefs.forEach { evidence ->
            append('|').append(evidence.blockId)
            append('|').append(evidence.sourceAssetId)
            append('|').append(evidence.evidenceKind.name)
        }
    }

private fun List<VerifiedKnowledgeReferenceProof>.singleSnapshot(): Pair<String, Long> {
    val snapshots = map { it.manifestFingerprint to it.activationGeneration }.distinct()
    require(snapshots.size == 1) {
        "One organization mapping must use one knowledge catalog generation"
    }
    return snapshots.single()
}

private fun VerifiedKnowledgeReferenceProof.persistedIdentity(): String =
    "${ref.canonicalFingerprint}|$manifestFingerprint|$activationGeneration"

private fun KnowledgeBaseNodeContext.names(): Set<String> =
    (listOf(canonicalName) + aliases)
        .mapTo(linkedSetOf(), String::normalizedOrganizationName)

private fun String.normalizedOrganizationName(): String =
    trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun opaqueId(
    prefix: String,
    domain: String,
    vararg values: String,
): String = "$prefix:${semanticFingerprint(domain, *values).take(40)}"

private fun indexedId(
    prefix: String,
    index: Int,
    domain: String,
    vararg values: String,
): String =
    "$prefix:${index.toString().padStart(4, '0')}:" +
        semanticFingerprint(domain, *values).take(32)

private fun indexedPairId(
    prefix: String,
    first: Int,
    second: Int,
    domain: String,
    vararg values: String,
): String =
    "$prefix:${first.toString().padStart(4, '0')}:" +
        "${second.toString().padStart(4, '0')}:" +
        semanticFingerprint(domain, *values).take(24)

private fun semanticFingerprint(
    domain: String,
    vararg values: String,
): String {
    val canonical = CanonicalSha256(domain).field("valueCount", values.size)
    values.forEachIndexed { index, value ->
        canonical.field("value[$index]", value)
    }
    return canonical.finish()
}

private const val REVIEWED_OUTPUT_FINGERPRINT_DOMAIN =
    "reviewed-student-problem-organization-output-v1"
private const val SOLUTION_ID_DOMAIN = "reviewed-student-problem-solution-id-v1"
private const val SOLUTION_FINGERPRINT_DOMAIN =
    "reviewed-student-problem-solution-fingerprint-v1"
private const val STEP_ID_DOMAIN = "reviewed-student-problem-step-id-v1"
private const val STEP_FINGERPRINT_DOMAIN =
    "reviewed-student-problem-step-fingerprint-v1"
private const val CLASSIFICATION_ID_DOMAIN =
    "reviewed-student-problem-classification-id-v1"
private const val CLASSIFICATION_FINGERPRINT_DOMAIN =
    "reviewed-student-problem-classification-fingerprint-v1"
private const val STEP_BINDING_ID_DOMAIN =
    "reviewed-student-problem-step-binding-id-v1"
private const val STEP_BINDING_FINGERPRINT_DOMAIN =
    "reviewed-student-problem-step-binding-fingerprint-v1"
private const val ERROR_ATTRIBUTION_ID_DOMAIN =
    "reviewed-student-problem-error-attribution-id-v1"
private const val ERROR_ATTRIBUTION_FINGERPRINT_DOMAIN =
    "reviewed-student-problem-error-attribution-fingerprint-v1"
private const val PROBLEM_FAMILY_ACCEPTANCE_CONFIDENCE = 0.90
private const val PROBLEM_FAMILY_LOCAL_EVIDENCE_POLICY_VERSION =
    "reviewed-problem-family-local-evidence-v1"
