package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.database.ConfirmProblemOrganizationCommand
import com.tingyun.smartmistakebook.core.database.ProblemErrorAttributionCandidateSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemErrorAttributionEvidenceSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemSolutionStepSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemStepKnowledgeReferenceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingRequestRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.ProblemClassificationBindingRecord
import com.tingyun.smartmistakebook.core.database.ProblemRelationSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationRelationKey
import com.tingyun.smartmistakebook.core.domain.UserProblemClassification
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingRequest
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemRelationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_RELATION_KINDS
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal const val ORGANIZATION_PROMPT_POLICY_VERSION =
    com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions.PROBLEM_ORGANIZATION
internal const val ORGANIZATION_TAXONOMY_VERSION = "local-policy-v1"
internal const val USER_CORRECTION_TAXONOMY_VERSION = "user-corrected-v1"
internal const val MIN_ATOMIC_BINDING_STRENGTH = 0.15
internal const val CLASSIFICATION_ACCEPTANCE_CONFIDENCE = 0.78
internal const val ATOMIC_KNOWLEDGE_ACCEPTANCE_CONFIDENCE = 0.72
internal const val RELATION_ACCEPTANCE_CONFIDENCE = 0.90
internal const val MAX_ACCEPTED_CHAPTERS = 3
internal const val MAX_ACCEPTED_KNOWLEDGE = 8
internal const val MAX_ACCEPTED_RELATIONS = 4
internal data class PersistedOrganizationTask(
    val task: ModelTaskSnapshot,
    val input: ProblemOrganizationInput,
    val output: ProblemOrganizationOutput,
    val sourceCommitReceiptCommandId: String?,
)

internal fun PersistedOrganizationTask.buildConfirmationCommand(
    requestId: String,
    classifications: List<ProblemClassificationSuggestion>,
    relations: List<ProblemRelationSuggestion>,
    atomicKnowledge: List<AtomicKnowledgeSuggestion>,
    stepAttributions: List<ProblemStepKnowledgeAttribution>,
    verifiedKnowledgeReferences: List<VerifiedKnowledgeReferenceProof>,
    acceptedAtEpochMillis: Long,
    acceptanceSource: BindingAcceptanceSource,
    relationRemovals: Set<ProblemOrganizationRelationKey> = emptySet(),
    replaceRelations: Boolean = false,
): ConfirmProblemOrganizationCommand = sourceCommitReceiptCommandId?.let { sourceReceipt ->
    buildV3ConfirmationCommand(
        requestId = requestId,
        input = input,
        classifications = classifications,
        relations = relations,
        atomicKnowledge = atomicKnowledge,
        stepAttributions = stepAttributions,
        verifiedKnowledgeReferences = verifiedKnowledgeReferences,
        errorAttributionCandidates = output.plan.errorAttributionCandidates,
        modelVersion = output.modelVersion,
        sourceCommitReceiptCommandId = sourceReceipt,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
        acceptanceSource = acceptanceSource,
        relationRemovals = relationRemovals,
        replaceRelations = replaceRelations,
    )
} ?: buildConfirmationCommand(
    requestId = requestId,
    input = input,
    classifications = classifications,
    relations = relations,
    atomicKnowledge = atomicKnowledge,
    stepAttributions = stepAttributions,
    verifiedKnowledgeReferences = verifiedKnowledgeReferences,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    acceptanceSource = acceptanceSource,
    relationRemovals = relationRemovals,
    replaceRelations = replaceRelations,
)

internal data class LocallyAcceptedOrganization(
    val classifications: List<ProblemClassificationSuggestion>,
    val relations: List<ProblemRelationSuggestion>,
    val atomicKnowledge: List<AtomicKnowledgeSuggestion>,
    val stepAttributions: List<ProblemStepKnowledgeAttribution>,
)

/**
 * Automatic reruns may add a trusted label, but omission is never a deletion signal. Existing
 * accepted labels stay first and therefore survive the per-dimension safety budget. Only the
 * explicit user-correction path is allowed to replace the visible classification set.
 */
internal fun mergeAutomaticClassifications(
    existing: List<ProblemClassificationBindingRecord>,
    incoming: List<ProblemClassificationSuggestion>,
): List<ProblemClassificationSuggestion> {
    val retained = existing.mapNotNull { record ->
        val dimension = runCatching { ClassificationDimension.valueOf(record.dimension) }
            .getOrNull()
            ?.takeIf(PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS::contains)
            ?: return@mapNotNull null
        runCatching {
            ProblemClassificationSuggestion(
                dimension = dimension,
                displayName = record.displayName,
                rationaleMarkdown = "此前已整理并保留；模型本次未提及不代表删除。",
                confidence = 1.0,
            )
        }.getOrNull()
    }
    val merged = (retained + incoming).distinctBy { suggestion ->
        suggestion.dimension to suggestion.displayName
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
    }
    return merged.filter { it.dimension == ClassificationDimension.CHAPTER }
        .take(MAX_ACCEPTED_CHAPTERS) +
        merged.filter { it.dimension == ClassificationDimension.KNOWLEDGE }
            .take(MAX_ACCEPTED_KNOWLEDGE)
}

internal fun acceptOrganizationLocally(
    input: ProblemOrganizationInput,
    output: ProblemOrganizationOutput,
): LocallyAcceptedOrganization? {
    if (
        output.problemId != input.problemId ||
        output.problemRevisionId != input.problemRevisionId ||
        output.practiceUnitId != input.practiceUnitId
    ) {
        return null
    }
    if (
        output.plan.schemaVersion < 2 ||
        output.plan.groundingRequests.isNotEmpty() ||
        output.plan.atomicKnowledge.isEmpty()
    ) {
        return null
    }
    if (output.plan.atomicKnowledge.any { atom -> !atom.isAcceptableForPersistence(input) }) {
        return null
    }
    val acceptedByDimension = output.plan.classifications.asSequence()
        .filter { suggestion ->
            suggestion.dimension in PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS &&
                suggestion.confidence >= CLASSIFICATION_ACCEPTANCE_CONFIDENCE
        }
        .mapNotNull(ProblemClassificationSuggestion::normalizedForLocalAcceptance)
        .distinctBy { suggestion ->
            suggestion.dimension to suggestion.displayName.lowercase(Locale.ROOT)
        }
        .groupBy(ProblemClassificationSuggestion::dimension)
    val chapters = acceptedByDimension[ClassificationDimension.CHAPTER]
        .orEmpty()
        .take(MAX_ACCEPTED_CHAPTERS)
    val knowledge = acceptedByDimension[ClassificationDimension.KNOWLEDGE]
        .orEmpty()
        .take(MAX_ACCEPTED_KNOWLEDGE)
    if (chapters.isEmpty() || knowledge.isEmpty()) return null
    return LocallyAcceptedOrganization(
        classifications = chapters + knowledge,
        relations = acceptedRelations(
            input = input,
            suggestions = output.plan.relations,
            minimumConfidence = RELATION_ACCEPTANCE_CONFIDENCE,
        ),
        atomicKnowledge = output.plan.atomicKnowledge,
        stepAttributions = output.plan.stepAttributions,
    )
}

internal fun buildKnowledgeGroundingRecords(
    organizationRequestId: String,
    organizationRequestFingerprint: String,
    input: ProblemOrganizationInput,
    requests: List<KnowledgeGroundingRequest>,
    occurredAtEpochMillis: Long,
): List<KnowledgeGroundingRequestRecord> {
    require(organizationRequestId.isNotBlank()) { "organizationRequestId must not be blank" }
    require(organizationRequestFingerprint.matches(Regex("[a-f0-9]{64}"))) {
        "organizationRequestFingerprint must be a lowercase SHA-256 digest"
    }
    require(occurredAtEpochMillis > 0) { "occurredAtEpochMillis must be positive" }
    return requests.mapIndexed { index, request ->
        val groundingKey = KnowledgeGroundingFingerprint.of(
            subject = input.subject,
            expectedParentKnowledgeDisplayName = request.expectedParentKnowledgeDisplayName,
            query = request.query,
        )
        KnowledgeGroundingRequestRecord(
            groundingRequestId = KnowledgeGroundingFingerprint.occurrenceId(
                organizationRequestId = organizationRequestId,
                requestOrdinal = index,
                groundingKey = groundingKey,
            ),
            groundingKey = groundingKey,
            organizationRequestId = organizationRequestId,
            organizationRequestFingerprint = organizationRequestFingerprint,
            requestOrdinal = index,
            problemId = input.problemId,
            problemRevisionId = input.problemRevisionId,
            practiceUnitId = input.practiceUnitId,
            subject = input.subject.name,
            query = request.query,
            expectedParentKnowledgeDisplayName = request.expectedParentKnowledgeDisplayName,
            reasonMarkdown = request.reasonMarkdown,
            createdAtEpochMillis = occurredAtEpochMillis,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )
    }
}

internal fun ProblemClassificationSuggestion.normalizedForLocalAcceptance(): ProblemClassificationSuggestion? {
    val normalizedName = displayName.trim().replace(Regex("\\s+"), " ")
    if (normalizedName.isEmpty()) return null
    return runCatching { copy(displayName = normalizedName) }.getOrNull()
}

internal fun acceptedRelations(
    input: ProblemOrganizationInput,
    suggestions: List<ProblemRelationSuggestion>,
    minimumConfidence: Double,
): List<ProblemRelationSuggestion> {
    val allowedTargets = input.relationCandidates.asSequence()
        .filter { candidate ->
            candidate.problemId != input.problemId && candidate.subject == input.subject
        }
        .mapTo(hashSetOf()) { candidate ->
            candidate.problemId to candidate.problemRevisionId
        }
    return suggestions.asSequence()
        .filter { suggestion ->
            suggestion.kind in PROBLEM_ORGANIZATION_RELATION_KINDS &&
                suggestion.confidence >= minimumConfidence &&
                (suggestion.targetProblemId to suggestion.targetProblemRevisionId) in allowedTargets
        }
        .distinctBy { suggestion ->
            Triple(suggestion.targetProblemId, suggestion.targetProblemRevisionId, suggestion.kind)
        }
        .take(MAX_ACCEPTED_RELATIONS)
        .toList()
}

internal fun MistakeDetailRecord?.requireCommittedDocument(): CapturedQuestionDocument =
    this?.committedDocumentOrNull() ?: error("The confirmed question document is unavailable")

internal fun MistakeDetailRecord.committedDocumentOrNull(): CapturedQuestionDocument? {
    val snapshot = questionDocumentSnapshot ?: return null
    return runCatching { CapturedQuestionDocumentCodec.decode(snapshot) }.getOrNull()
        ?.takeIf { document ->
            CapturedQuestionDocumentValidator.validateForCommit(document).isEmpty() &&
                CapturedQuestionDocumentFingerprint.of(document) == contentFingerprint
        }
}

internal fun String.toSubjectKind(): SubjectKind =
    runCatching { SubjectKind.valueOf(uppercase(Locale.ROOT)) }.getOrDefault(SubjectKind.GENERAL)

internal fun organizationRequestId(
    input: ProblemOrganizationInput,
    provider: ProviderCapabilitySnapshot,
    attempt: Int,
): String = "problem-organization:${sha256(
    listOf(
        input.problemId,
        input.problemRevisionId,
        provider.providerId,
        provider.modelId,
        provider.providerConfigurationVersion,
        ORGANIZATION_PROMPT_POLICY_VERSION,
        attempt.toString(),
    ).joinToString("|")
).take(40)}"

internal fun organizationV3RequestId(
    workId: String,
    commitReceiptCommandId: String,
    input: ProblemOrganizationV3Input,
    provider: ProviderCapabilitySnapshot,
    authorizationId: String,
    requestVersion: Long,
): String = "problem-organization-v3:${sha256(
    listOf(
        workId,
        commitReceiptCommandId,
        input.problemId,
        input.problemRevisionId,
        input.practiceUnitId,
        provider.providerId,
        provider.modelId,
        provider.providerConfigurationVersion,
        ORGANIZATION_PROMPT_POLICY_VERSION,
        authorizationId,
        requestVersion.toString(),
    ).joinToString("|")
).take(40)}"

internal fun buildConfirmationCommand(
    requestId: String,
    input: ProblemOrganizationInput,
    classifications: List<ProblemClassificationSuggestion>,
    relations: List<ProblemRelationSuggestion>,
    acceptedAtEpochMillis: Long,
    atomicKnowledge: List<AtomicKnowledgeSuggestion> = emptyList(),
    stepAttributions: List<ProblemStepKnowledgeAttribution> = emptyList(),
    verifiedKnowledgeReferences: List<VerifiedKnowledgeReferenceProof>,
    acceptanceSource: BindingAcceptanceSource = BindingAcceptanceSource.USER_CORRECTED,
    relationRemovals: Set<ProblemOrganizationRelationKey> = emptySet(),
    replaceRelations: Boolean = false,
): ConfirmProblemOrganizationCommand {
    require(
        acceptanceSource == BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED ||
            acceptanceSource == BindingAcceptanceSource.USER_CORRECTED,
    ) { "New organization writes need an explicit acceptance authority" }
    require(classifications.all { it.dimension in PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS }) {
        "Only chapter and knowledge classifications can be saved"
    }
    require(classifications.any { it.dimension == ClassificationDimension.CHAPTER }) {
        "At least one chapter classification is required"
    }
    require(classifications.any { it.dimension == ClassificationDimension.KNOWLEDGE }) {
        "At least one knowledge classification is required"
    }
    require(relations.all { it.kind in PROBLEM_ORGANIZATION_RELATION_KINDS }) {
        "This relation type is no longer part of problem organization"
    }
    require(relationRemovals.all { it.kind in PROBLEM_ORGANIZATION_RELATION_KINDS }) {
        "This relation type is no longer part of problem organization"
    }
    val taxonomyVersion = if (acceptanceSource == BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED) {
        ORGANIZATION_TAXONOMY_VERSION
    } else {
        USER_CORRECTION_TAXONOMY_VERSION
    }
    val classificationRecords = classifications.map { suggestion ->
        val labelId = classificationLabelId(input.subject, suggestion.dimension, suggestion.displayName)
        ProblemClassificationBindingRecord(
            bindingId = "classification:${sha256("${input.problemRevisionId}|${suggestion.dimension}|$labelId").take(40)}",
            problemId = input.problemId,
            basisRevisionId = input.problemRevisionId,
            dimension = suggestion.dimension.name,
            labelId = labelId,
            displayName = suggestion.displayName,
            taxonomyVersion = taxonomyVersion,
            acceptanceSource = acceptanceSource.name,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
        )
    }.distinctBy { Triple(it.problemId, it.dimension, it.labelId) }
    val acceptedKnowledgeNames =
        classificationRecords
            .asSequence()
            .filter { classification ->
                classification.dimension == ClassificationDimension.KNOWLEDGE.name
            }
            .map { classification -> classification.displayName.normalizedKnowledgeName() }
            .toSet()
    val contextById = input.knowledgeBaseNodes.associateBy(
        KnowledgeBaseNodeContext::knowledgeNodeId,
    )
    val acceptedAtoms = atomicKnowledge.filter { atom ->
        atom.isAcceptableForPersistence(input) &&
            atom.parentKnowledgeDisplayName.normalizedKnowledgeName() in acceptedKnowledgeNames
    }
    val atomicNodePairs = acceptedAtoms.map { atom ->
        atom to requireNotNull(contextById[atom.matchedKnowledgeNodeId])
    }.distinctBy { (_, node) -> node.knowledgeNodeId }
    val attributedStepCounts = stepAttributions
        .flatMap { step -> step.atomicReferenceIds.map { referenceId -> referenceId to step.stepOrdinal } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ordinals) -> ordinals.distinct().size }
    val atomByNodeId = atomicNodePairs.associate { (atom, node) ->
        node.knowledgeNodeId to atom
    }
    require(
        verifiedKnowledgeReferences.isNotEmpty() &&
            verifiedKnowledgeReferences.map { proof -> proof.ref }.distinct().size ==
                verifiedKnowledgeReferences.size &&
            verifiedKnowledgeReferences.all { proof -> proof.ref.subject == input.subject },
    ) {
        "Organization confirmation requires distinct same-subject catalog proof"
    }
    if (atomicNodePairs.isNotEmpty()) {
        require(
            verifiedKnowledgeReferences.mapTo(linkedSetOf()) { proof ->
                proof.ref.knowledgeNodeId
            } == atomicNodePairs.mapTo(linkedSetOf()) { (_, node) -> node.knowledgeNodeId },
        ) {
            "Verified catalog references must exactly match accepted atomic knowledge"
        }
    }
    val knowledgeBindings = verifiedKnowledgeReferences.map { proof ->
        val knowledgeNodeId = proof.ref.knowledgeNodeId
        val atom = atomByNodeId[knowledgeNodeId]
        val strength = if (atom == null) {
            1.0
        } else {
            val attributedSteps = attributedStepCounts[atom.referenceId] ?: 0
            attributedSteps.toDouble()
                .div(stepAttributions.size.coerceAtLeast(1))
                .coerceIn(MIN_ATOMIC_BINDING_STRENGTH, 1.0)
        }
        val bindingIdentity =
            listOf(
                input.practiceUnitId,
                proof.ref.canonicalFingerprint,
                proof.manifestFingerprint,
                proof.activationGeneration.toString(),
                input.problemRevisionId,
                taxonomyVersion,
            ).joinToString("|")
        KnowledgeBindingSeedRecord(
            bindingId = "knowledge-binding:${sha256(bindingIdentity).take(40)}",
            practiceUnitId = input.practiceUnitId,
            knowledgeNodeId = knowledgeNodeId,
            basisRevisionId = input.problemRevisionId,
            strength = strength,
            sourceType = acceptanceSource.name,
            taxonomyVersion = taxonomyVersion,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
            verifiedKnowledgeReference = proof,
        )
    }
    val relationRecords = relations.map { suggestion ->
        ProblemRelationSeedRecord(
            relationId = relationId(
                input.problemRevisionId,
                suggestion.targetProblemRevisionId,
                suggestion.kind,
            ),
            sourceProblemId = input.problemId,
            targetProblemId = suggestion.targetProblemId,
            relationType = suggestion.kind.name,
            status = StudyDbValue.RelationStatus.ACTIVE,
            sourceBasisRevisionId = input.problemRevisionId,
            targetBasisRevisionId = suggestion.targetProblemRevisionId,
            confidence = suggestion.confidence,
            createdAtEpochMillis = acceptedAtEpochMillis,
            updatedAtEpochMillis = acceptedAtEpochMillis,
        )
    }.distinctBy { Triple(it.targetProblemId, it.relationType, it.targetBasisRevisionId) }
    val relationIdsToRemove = relationRemovals.mapTo(linkedSetOf()) { removal ->
        relationId(input.problemRevisionId, removal.targetProblemRevisionId, removal.kind)
    }
    val canonicalPayload = buildString {
        append(requestId).append('|')
        append(input.problemId).append('|').append(input.problemRevisionId).append('|')
        append(input.practiceUnitId).append('|').append(acceptedAtEpochMillis).append('|')
        append(acceptanceSource.name).append('|').append(replaceRelations).append('|')
        classificationRecords.sortedBy { it.bindingId }.forEach { append(it.bindingId).append('|') }
        knowledgeBindings.sortedBy { it.bindingId }.forEach { binding ->
            val proof = checkNotNull(binding.verifiedKnowledgeReference)
            append(binding.bindingId).append(':')
            append(proof.ref.canonicalFingerprint).append(':')
            append(proof.manifestFingerprint).append(':')
            append(proof.activationGeneration).append(':')
            append(binding.strength).append('|')
        }
        relationRecords.sortedBy { it.relationId }.forEach { append(it.relationId).append('|') }
        relationIdsToRemove.sorted().forEach { append("remove:").append(it).append('|') }
    }
    val fingerprint = sha256(canonicalPayload)
    return ConfirmProblemOrganizationCommand(
        commandId = if (acceptanceSource == BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED) {
            "organization-apply:${sha256(requestId).take(40)}"
        } else {
            "organization-correction:${fingerprint.take(40)}"
        },
        payloadFingerprint = fingerprint,
        problemId = input.problemId,
        problemRevisionId = input.problemRevisionId,
        practiceUnitId = input.practiceUnitId,
        knowledgeNodes = emptyList(),
        knowledgeBindings = knowledgeBindings,
        classifications = classificationRecords,
        relations = relationRecords,
        relationIdsToRemove = relationIdsToRemove,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
        replaceRelations = replaceRelations,
    )
}

internal fun buildV3ConfirmationCommand(
    requestId: String,
    input: ProblemOrganizationInput,
    classifications: List<ProblemClassificationSuggestion>,
    relations: List<ProblemRelationSuggestion>,
    atomicKnowledge: List<AtomicKnowledgeSuggestion>,
    stepAttributions: List<ProblemStepKnowledgeAttribution>,
    verifiedKnowledgeReferences: List<VerifiedKnowledgeReferenceProof>,
    errorAttributionCandidates: List<com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate>,
    modelVersion: String,
    sourceCommitReceiptCommandId: String,
    acceptedAtEpochMillis: Long,
    acceptanceSource: BindingAcceptanceSource,
    relationRemovals: Set<ProblemOrganizationRelationKey>,
    replaceRelations: Boolean,
): ConfirmProblemOrganizationCommand {
    require(sourceCommitReceiptCommandId.isNotBlank()) {
        "Schema three organization requires a source commit receipt"
    }
    val base = buildConfirmationCommand(
        requestId = requestId,
        input = input,
        classifications = classifications,
        relations = relations,
        atomicKnowledge = atomicKnowledge,
        stepAttributions = stepAttributions,
        verifiedKnowledgeReferences = verifiedKnowledgeReferences,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
        acceptanceSource = acceptanceSource,
        relationRemovals = relationRemovals,
        replaceRelations = replaceRelations,
    )
    val knowledgeNodeIdByReference = atomicKnowledge.associate { atom ->
        atom.referenceId to requireNotNull(atom.matchedKnowledgeNodeId) {
            "Schema three solution steps require grounded atomic knowledge"
        }
    }
    val solutionSteps = stepAttributions
        .sortedBy(ProblemStepKnowledgeAttribution::stepOrdinal)
        .map { attribution ->
            ProblemSolutionStepSeedRecord(
                stepOrdinal = attribution.stepOrdinal,
                summaryMarkdown = attribution.stepSummaryMarkdown,
                knowledgeReferences = attribution.atomicReferenceIds
                    .sorted()
                    .map { referenceId ->
                        ProblemStepKnowledgeReferenceSeedRecord(
                            knowledgeReferenceId = referenceId,
                            knowledgeNodeId = requireNotNull(knowledgeNodeIdByReference[referenceId]) {
                                "Schema three step references unknown atomic knowledge"
                            },
                        )
                    },
            )
        }
    val errorCandidates = errorAttributionCandidates.mapIndexed { ordinal, candidate ->
        val knowledgeReferenceId = candidate.atomicReferenceId
        ProblemErrorAttributionCandidateSeedRecord(
            candidateOrdinal = ordinal,
            resolutionStatus = candidate.resolutionStatus.name,
            stepOrdinal = candidate.stepOrdinal,
            knowledgeReferenceId = knowledgeReferenceId,
            knowledgeNodeId = knowledgeReferenceId?.let { referenceId ->
                requireNotNull(knowledgeNodeIdByReference[referenceId]) {
                    "Schema three error attribution references unknown atomic knowledge"
                }
            },
            rationaleMarkdown = candidate.rationaleMarkdown,
            confidence = candidate.confidence,
            modelVersion = modelVersion,
            evidence = candidate.evidenceRefs
                .sortedWith(
                    compareBy<com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef> {
                        it.blockId
                    }.thenBy { it.sourceAssetId }.thenBy { it.evidenceKind.name },
                )
                .map { evidence ->
                    ProblemErrorAttributionEvidenceSeedRecord(
                        blockId = evidence.blockId,
                        sourceAssetId = evidence.sourceAssetId,
                        evidenceKind = evidence.evidenceKind.name,
                    )
                },
        )
    }
    val provisional = base.copy(
        planSchemaVersion = 3,
        solutionSteps = solutionSteps,
        errorAttributionCandidates = errorCandidates,
        sourceCommitReceiptCommandId = sourceCommitReceiptCommandId,
    )
    val fingerprint = canonicalV3OrganizationFingerprint(requestId, provisional)
    return provisional.copy(
        commandId = if (acceptanceSource == BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED) {
            "organization-apply:${sha256(requestId).take(40)}"
        } else {
            "organization-correction:${fingerprint.take(40)}"
        },
        payloadFingerprint = fingerprint,
    )
}

internal fun canonicalV3OrganizationFingerprint(
    requestId: String,
    command: ConfirmProblemOrganizationCommand,
): String {
    val payload = buildString {
        fun field(value: Any?) {
            if (value == null) {
                append("-1:")
                return
            }
            val text = value.toString()
            append(text.length).append(':').append(text)
        }
        fun <T> fields(values: Iterable<T>, write: (T) -> Unit) {
            val list = values.toList()
            field(list.size)
            list.forEach(write)
        }

        field("problem-organization-command-v3")
        field(requestId)
        field(command.problemId)
        field(command.problemRevisionId)
        field(command.practiceUnitId)
        field(command.acceptedAtEpochMillis)
        field(command.replaceRelations)
        field(command.planSchemaVersion)
        field(command.sourceCommitReceiptCommandId)
        fields(command.classifications.sortedBy { it.bindingId }) { record ->
            field(record.bindingId); field(record.problemId); field(record.basisRevisionId)
            field(record.dimension); field(record.labelId); field(record.displayName)
            field(record.taxonomyVersion); field(record.acceptanceSource); field(record.acceptedAtEpochMillis)
        }
        fields(command.knowledgeNodes.sortedBy { it.knowledgeNodeId }) { node ->
            field(node.knowledgeNodeId); field(node.stableCode); field(node.subject); field(node.displayName)
            field(node.parentKnowledgeNodeId); field(node.taxonomyVersion); field(node.createdAtEpochMillis)
            field(node.canonicalName); field(node.nodeKind); field(node.granularity)
            field(node.verificationStatus); fields(node.aliases.sorted()) { field(it) }
            field(node.boundaryMarkdown)
        }
        fields(command.knowledgeBindings.sortedBy { it.bindingId }) { binding ->
            val proof = checkNotNull(binding.verifiedKnowledgeReference)
            field(binding.bindingId); field(binding.practiceUnitId); field(binding.knowledgeNodeId)
            field(binding.basisRevisionId); field(binding.strength); field(binding.sourceType)
            field(binding.taxonomyVersion); field(binding.acceptedAtEpochMillis)
            field(proof.ref.canonicalFingerprint); field(proof.manifestFingerprint)
            field(proof.activationGeneration)
        }
        fields(command.relations.sortedBy { it.relationId }) { relation ->
            field(relation.relationId); field(relation.sourceProblemId); field(relation.targetProblemId)
            field(relation.relationType); field(relation.status); field(relation.sourceBasisRevisionId)
            field(relation.targetBasisRevisionId); field(relation.confidence); field(relation.createdAtEpochMillis)
            field(relation.updatedAtEpochMillis)
        }
        fields(command.relationIdsToRemove.sorted()) { field(it) }
        fields(command.solutionSteps.sortedBy { it.stepOrdinal }) { step ->
            field(step.stepOrdinal); field(step.summaryMarkdown)
            fields(step.knowledgeReferences.sortedBy { it.knowledgeReferenceId }) { reference ->
                field(reference.knowledgeReferenceId); field(reference.knowledgeNodeId)
            }
        }
        fields(command.errorAttributionCandidates.sortedBy { it.candidateOrdinal }) { candidate ->
            field(candidate.candidateOrdinal); field(candidate.resolutionStatus); field(candidate.stepOrdinal)
            field(candidate.knowledgeReferenceId); field(candidate.knowledgeNodeId); field(candidate.rationaleMarkdown)
            field(candidate.confidence); field(candidate.modelVersion)
            fields(
                candidate.evidence.sortedWith(
                    compareBy<ProblemErrorAttributionEvidenceSeedRecord> { it.blockId }
                        .thenBy { it.sourceAssetId }
                        .thenBy { it.evidenceKind },
                ),
            ) { evidence ->
                field(evidence.blockId); field(evidence.sourceAssetId); field(evidence.evidenceKind)
            }
        }
    }
    return sha256(payload)
}

internal fun ProblemOrganizationV3Input.asLegacyCompatibilityInput() = ProblemOrganizationInput(
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    questionDocument = capturedDocument.document,
    relevantLearningEvidence = emptyList(),
    relationCandidates = relationCandidates,
    knowledgeBaseNodes = knowledgeBaseNodes,
)

internal fun relationId(
    sourceRevisionId: String,
    targetRevisionId: String,
    kind: ProblemRelationKind,
): String = "relation:${sha256("$sourceRevisionId|$targetRevisionId|$kind").take(40)}"

internal fun classificationLabelId(
    subject: SubjectKind,
    dimension: ClassificationDimension,
    displayName: String,
): String {
    val normalized = displayName.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    return "${subject.name.lowercase(Locale.ROOT)}:${dimension.name.lowercase(Locale.ROOT)}:${sha256(normalized).take(24)}"
}

internal fun String.normalizedKnowledgeName(): String =
    trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

internal fun AtomicKnowledgeSuggestion.isAcceptableForPersistence(
    input: ProblemOrganizationInput,
): Boolean {
    if (confidence < ATOMIC_KNOWLEDGE_ACCEPTANCE_CONFIDENCE) return false
    val matchedId = matchedKnowledgeNodeId ?: return false
    val matched = input.knowledgeBaseNodes.firstOrNull { node ->
        node.knowledgeNodeId == matchedId
    } ?: return false
    return matched.granularity == KnowledgeNodeGranularity.ATOMIC &&
        matched.kind == kind &&
        matched.verificationStatus != KnowledgeNodeVerificationStatus.MODEL_CANDIDATE &&
        matched.parentCanonicalName?.normalizedKnowledgeName() ==
        parentKnowledgeDisplayName.normalizedKnowledgeName() &&
        canonicalName.normalizedKnowledgeName() in
        (matched.aliases + matched.canonicalName).map(String::normalizedKnowledgeName)
}

internal fun UserProblemClassification.toSuggestion() = ProblemClassificationSuggestion(
    dimension = dimension,
    displayName = displayName,
    rationaleMarkdown = "由你补充或纠正，保存后作为本题的确认分类。",
    confidence = 1.0,
)

/** Deterministic privacy/cost shortlist only; the model still decides semantic relations. */
internal fun relationCandidateScore(current: MistakeRecord, candidate: MistakeRecord): Int {
    if (current.subject != candidate.subject || current.problemId == candidate.problemId) return Int.MIN_VALUE
    val currentKnowledge = current.knowledgeLabels.mapTo(hashSetOf()) { it.normalizedLabel() }
    val sharedKnowledge = candidate.knowledgeLabels.count { it.normalizedLabel() in currentKnowledge }
    val titleOverlap = current.title.normalizedBigrams()
        .intersect(candidate.title.normalizedBigrams())
        .size
    return sharedKnowledge * 100 + titleOverlap.coerceAtMost(20)
}

internal fun String.normalizedLabel(): String = trim().lowercase(Locale.ROOT)

internal fun String.normalizedBigrams(): Set<String> {
    val compact = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    if (compact.length < 2) return compact.takeIf(String::isNotEmpty)?.let(::setOf).orEmpty()
    return (0 until compact.lastIndex).mapTo(linkedSetOf()) { index ->
        compact.substring(index, index + 2)
    }
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
