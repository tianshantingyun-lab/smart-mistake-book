package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeContextRepository
import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeConfirmationRequest
import com.tingyun.smartmistakebook.core.database.CompleteProblemOrganizationWorkAtomicallyCommand
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LegacyModelAssetDocumentReadPort
import com.tingyun.smartmistakebook.core.database.LegacyOrganizationWorkCoordinationPort
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverMistakeOrganizationBusinessPort
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverOrganizationReauthorizationPort
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.ModelTaskDatabasePort
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationAuthorityConflictException
import com.tingyun.smartmistakebook.core.database.ReauthorizeProblemOrganizationWorkCommand
import com.tingyun.smartmistakebook.core.database.ReauthorizeProblemOrganizationWorkOutcome
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.TrustedModelTaskDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedOrganizationWorkDatabaseCapability
import com.tingyun.smartmistakebook.core.domain.ConfirmedKnowledgeNodeBinding
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemClassification
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemRelation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationConfirmation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationDurableStatus
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationImmutableConflictException
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationRelationKey
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationReauthorizationOutcome
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationReauthorizationPreparation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionAuthority
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionOutcome
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_APPROVAL_TTL_MILLIS
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemRelationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_RELATION_KINDS
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class RoomMistakeOrganizationRepository(
    private val legacyBusiness: LegacyPreCutoverMistakeOrganizationBusinessPort,
    private val organizationWork: LegacyOrganizationWorkCoordinationPort,
    private val legacyReauthorization: LegacyPreCutoverOrganizationReauthorizationPort,
    private val modelTasks: ModelTaskDatabasePort,
    private val assetDocuments: LegacyModelAssetDocumentReadPort,
    private val knowledgeContext: ReviewedProblemKnowledgeContextRepository,
) : MistakeOrganizationRepository {
    override suspend fun prepare(
        key: MistakeRevisionKey,
        profile: StudyProfileOverview,
        provider: ProviderCapabilitySnapshot,
        attempt: Int,
        occurredAtEpochMillis: Long,
        approvedAtEpochMillis: Long,
    ): MistakeOrganizationPreparation = withContext(Dispatchers.IO) {
        require(attempt >= 0) { "attempt must not be negative" }
        require(provider.supports(ModelTaskKind.PROBLEM_CLASSIFY)) {
            "Current provider does not support problem organization"
        }
        val catalog = legacyBusiness.observeMistakes().first()
        val current = catalog.singleOrNull {
            it.entryId == key.entryId &&
                it.problemId == key.problemId &&
                it.problemRevisionId == key.problemRevisionId
        } ?: error("The selected mistake revision is no longer current")
        val currentDocument = legacyBusiness.readExactMistakeDetail(
            key.entryId,
            key.problemId,
            key.problemRevisionId,
        ).requireCommittedDocument()
        val candidateRows = catalog.asSequence()
            .filter { candidate ->
                candidate.problemId != current.problemId && candidate.subject == current.subject
            }
            .sortedWith(
                compareByDescending<MistakeRecord> { relationCandidateScore(current, it) }
                    .thenByDescending { it.createdAtEpochMillis }
                    .thenBy { it.title }
                    .thenBy { it.problemId },
            )
            .take(ProblemOrganizationInput.MAX_RELATION_CANDIDATES)
            .toList()
        val relationCandidates = buildList {
            candidateRows.forEach { candidate ->
                val detail = legacyBusiness.readExactMistakeDetail(
                    candidate.entryId,
                    candidate.problemId,
                    candidate.problemRevisionId,
                ) ?: return@forEach
                detail.committedDocumentOrNull()?.let { document ->
                    add(RelatedProblemCandidate(
                        problemId = candidate.problemId,
                        problemRevisionId = candidate.problemRevisionId,
                        subject = candidate.subject.toSubjectKind(),
                        title = candidate.title,
                        questionDocument = document.document,
                    ))
                }
            }
        }
        val questionText = QuestionDocumentMarkdownProjection.project(currentDocument.document)
        val knowledgeBaseNodes =
            knowledgeContext.read(
                subject = current.subject.toSubjectKind(),
                questionText = questionText,
            )
        val input = ProblemOrganizationInput(
            problemId = current.problemId,
            problemRevisionId = current.problemRevisionId,
            practiceUnitId = current.practiceUnitId,
            subject = current.subject.toSubjectKind(),
            questionDocument = currentDocument.document,
            relevantLearningEvidence = emptyList(),
            relationCandidates = relationCandidates,
            knowledgeBaseNodes = knowledgeBaseNodes,
        )
        val requestId = organizationRequestId(input, provider, attempt)
        val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
            ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = input.subjectId,
                purpose = ModelEgressPurpose.CLASSIFICATION,
                authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ORGANIZATION_PROMPT_POLICY_VERSION,
                approvedAtEpochMillis = approvedAtEpochMillis,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
                prohibitedData = ModelEgressManifest.PROBLEM_ORGANIZATION_PROHIBITED_DATA,
            )
        } else {
            null
        }
        MistakeOrganizationPreparation(
            request = ModelTaskRequest(
                requestId = requestId,
                input = input,
                occurredAtEpochMillis = occurredAtEpochMillis,
                egressManifest = manifest,
            ),
            relatedCandidateTitles = relationCandidates.map(RelatedProblemCandidate::title),
            knowledgeContextCount = knowledgeBaseNodes.size,
        )
    }

    override fun observeConfirmed(key: MistakeRevisionKey): Flow<ConfirmedMistakeOrganization> =
        legacyBusiness.observeConfirmedProblemOrganization(key.problemId, key.problemRevisionId)
            .map { record ->
                val directKnowledgeNodes = runCatching {
                    record.knowledgeBindings.map { binding ->
                        ConfirmedKnowledgeNodeBinding(
                            ref = KnowledgeNodeRef(
                                subject = SubjectKind.valueOf(binding.subject),
                                knowledgeNodeId = binding.knowledgeNodeId,
                                taxonomyVersion = binding.taxonomyVersion,
                                knowledgePackVersion = binding.knowledgePackVersion,
                            ),
                            manifestFingerprint = binding.manifestFingerprint,
                            activationGeneration = binding.activationGeneration,
                        )
                    }.also { bindings ->
                        require(bindings.distinct().size == bindings.size) {
                            "Confirmed knowledge binding provenance must be unique"
                        }
                    }
                }.getOrDefault(emptyList())
                ConfirmedMistakeOrganization(
                    classifications = record.classifications.mapNotNull { binding ->
                        val dimension = runCatching {
                            ClassificationDimension.valueOf(binding.dimension)
                        }.getOrNull()?.takeIf { it in PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS }
                            ?: return@mapNotNull null
                        ConfirmedProblemClassification(dimension, binding.labelId, binding.displayName)
                    },
                    relations = record.relations.mapNotNull { relation ->
                        val kind = runCatching {
                            ProblemRelationKind.valueOf(relation.relationType)
                        }.getOrNull()?.takeIf { it in PROBLEM_ORGANIZATION_RELATION_KINDS }
                            ?: return@mapNotNull null
                        ConfirmedProblemRelation(
                            targetProblemId = relation.targetProblemId,
                            targetProblemRevisionId = relation.targetBasisRevisionId,
                            kind = kind,
                            confidence = relation.confidence,
                        )
                    },
                    knowledgeNodeIds = record.knowledgeNodeIds,
                    directKnowledgeNodes = directKnowledgeNodes,
                )
            }

    override suspend fun applySuccessfulOrganization(
        requestId: String,
    ): ProblemOrganizationConfirmation = withContext(Dispatchers.IO) {
        applySuccessfulOrganizationOnIo(requestId)
    }

    private suspend fun applySuccessfulOrganizationOnIo(
        requestId: String,
    ): ProblemOrganizationConfirmation {
        val persisted = readSuccessfulOrganization(requestId)
        val existing = legacyBusiness.observeConfirmedProblemOrganization(
            persisted.input.problemId,
            persisted.input.problemRevisionId,
        ).first()
        val hasUserCorrection = existing.classifications.any { classification ->
            classification.acceptanceSource == BindingAcceptanceSource.USER_CORRECTED.name ||
                classification.acceptanceSource == BindingAcceptanceSource.USER_CONFIRMED.name
        }
        if (hasUserCorrection) {
            return ProblemOrganizationConfirmation(
                created = false,
                classificationCount = existing.classifications.size,
                relationCount = existing.relations.size,
                applied = false,
                preservedUserCorrection = true,
            )
        }
        if (persisted.output.plan.groundingRequests.isNotEmpty()) {
            legacyBusiness.recordKnowledgeGroundingRequests(
                buildKnowledgeGroundingRecords(
                    organizationRequestId = requestId,
                    organizationRequestFingerprint = persisted.task.requestFingerprint,
                    input = persisted.input,
                    requests = persisted.output.plan.groundingRequests,
                    occurredAtEpochMillis = persisted.task.updatedAtEpochMillis,
                ),
            )
            return ProblemOrganizationConfirmation(
                created = false,
                classificationCount = existing.classifications.size,
                relationCount = existing.relations.size,
                applied = false,
            )
        }
        val accepted = acceptOrganizationLocally(persisted.input, persisted.output)
            ?: return ProblemOrganizationConfirmation(
                created = false,
                classificationCount = 0,
                relationCount = 0,
                applied = false,
            )
        val acceptedAtEpochMillis = persisted.task.updatedAtEpochMillis
        require(acceptedAtEpochMillis > 0) {
            "Successful organization task has no durable completion time"
        }
        val classifications =
            mergeAutomaticClassifications(
                existing = existing.classifications,
                incoming = accepted.classifications,
            )
        val verifiedKnowledgeReferences =
            verifyKnowledgeReferences(
                input = persisted.input,
                classifications = classifications,
                atomicKnowledge = accepted.atomicKnowledge,
            )
        val command = persisted.buildConfirmationCommand(
            requestId = requestId,
            classifications = classifications,
            relations = accepted.relations,
            atomicKnowledge = accepted.atomicKnowledge,
            stepAttributions = accepted.stepAttributions,
            verifiedKnowledgeReferences = verifiedKnowledgeReferences,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
            acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
            replaceRelations = false,
        )
        val result = try {
            legacyBusiness.confirmProblemOrganization(command)
        } catch (_: ProblemOrganizationAuthorityConflictException) {
            val existing = legacyBusiness.observeConfirmedProblemOrganization(
                persisted.input.problemId,
                persisted.input.problemRevisionId,
            ).first()
            return ProblemOrganizationConfirmation(
                created = false,
                classificationCount = existing.classifications.size,
                relationCount = existing.relations.size,
                applied = false,
                preservedUserCorrection = true,
            )
        }
        return ProblemOrganizationConfirmation(
            created = result.created,
            classificationCount = result.receipt.classificationCount,
            relationCount = result.receipt.relationCount,
        )
    }

    override suspend fun completeSuccessfulOrganizationWork(
        authority: ProblemOrganizationWorkCompletionAuthority,
    ): ProblemOrganizationWorkCompletionOutcome = withContext(Dispatchers.IO) {
        completeSuccessfulOrganizationWorkOnIo(authority)
    }

    private suspend fun completeSuccessfulOrganizationWorkOnIo(
        authority: ProblemOrganizationWorkCompletionAuthority,
    ): ProblemOrganizationWorkCompletionOutcome {
        val persisted = readSuccessfulOrganization(authority.requestId)
        val existing = legacyBusiness.observeConfirmedProblemOrganization(
            persisted.input.problemId,
            persisted.input.problemRevisionId,
        ).first()
        val hasUserCorrection = existing.classifications.any { classification ->
            classification.acceptanceSource == BindingAcceptanceSource.USER_CORRECTED.name ||
                classification.acceptanceSource == BindingAcceptanceSource.USER_CONFIRMED.name
        }
        val acceptedAtEpochMillis = persisted.task.updatedAtEpochMillis
        require(acceptedAtEpochMillis > 0) {
            "Successful organization task has no durable completion time"
        }
        val groundingRequests = if (
            !hasUserCorrection && persisted.output.plan.groundingRequests.isNotEmpty()
        ) {
            buildKnowledgeGroundingRecords(
                organizationRequestId = authority.requestId,
                organizationRequestFingerprint = persisted.task.requestFingerprint,
                input = persisted.input,
                requests = persisted.output.plan.groundingRequests,
                occurredAtEpochMillis = acceptedAtEpochMillis,
            )
        } else {
            emptyList()
        }
        val confirmation = if (hasUserCorrection || groundingRequests.isNotEmpty()) {
            null
        } else {
            acceptOrganizationLocally(persisted.input, persisted.output)?.let { accepted ->
                val classifications = mergeAutomaticClassifications(
                    existing = existing.classifications,
                    incoming = accepted.classifications,
                )
                val verifiedKnowledgeReferences =
                    verifyKnowledgeReferences(
                        input = persisted.input,
                        classifications = classifications,
                        atomicKnowledge = accepted.atomicKnowledge,
                    )
                val provisional = persisted.buildConfirmationCommand(
                    requestId = authority.requestId,
                    classifications = classifications,
                    relations = emptyList(),
                    atomicKnowledge = accepted.atomicKnowledge,
                    stepAttributions = accepted.stepAttributions,
                    verifiedKnowledgeReferences = verifiedKnowledgeReferences,
                    acceptedAtEpochMillis = acceptedAtEpochMillis,
                    acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
                    replaceRelations = false,
                )
                val localRelations = localSameKnowledgeRelations(
                    input = persisted.input,
                    knowledgeNodeIds = provisional.knowledgeBindings
                        .mapTo(linkedSetOf()) { it.knowledgeNodeId },
                )
                if (localRelations.isEmpty()) {
                    provisional
                } else {
                    persisted.buildConfirmationCommand(
                        requestId = authority.requestId,
                        classifications = classifications,
                        relations = localRelations,
                        atomicKnowledge = accepted.atomicKnowledge,
                        stepAttributions = accepted.stepAttributions,
                        verifiedKnowledgeReferences = verifiedKnowledgeReferences,
                        acceptedAtEpochMillis = acceptedAtEpochMillis,
                        acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
                        replaceRelations = false,
                    )
                }
            }
        }
        val command = CompleteProblemOrganizationWorkAtomicallyCommand(
            workId = authority.workId,
            expectedStateVersion = authority.expectedStateVersion,
            leaseOwner = authority.leaseOwner,
            requestId = authority.requestId,
            confirmation = confirmation,
            groundingRequests = groundingRequests,
        )
        val result = try {
            try {
                legacyBusiness.confirmAndCompleteProblemOrganizationWork(command)
            } catch (conflict: ImmutablePayloadConflictException) {
                throw ProblemOrganizationImmutableConflictException(
                    message = "Organization completion conflicts with immutable local state",
                    cause = conflict,
                )
            }
        } catch (_: ProblemOrganizationAuthorityConflictException) {
            try {
                legacyBusiness.confirmAndCompleteProblemOrganizationWork(
                    command.copy(
                        confirmation = null,
                        groundingRequests = emptyList(),
                    ),
                )
            } catch (conflict: ImmutablePayloadConflictException) {
                throw ProblemOrganizationImmutableConflictException(
                    message = "Organization completion conflicts with immutable local state",
                    cause = conflict,
                )
            }
        }
        return if (result.completed) {
            ProblemOrganizationWorkCompletionOutcome.COMPLETED
        } else {
            ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY
        }
    }

    private suspend fun localSameKnowledgeRelations(
        input: ProblemOrganizationInput,
        knowledgeNodeIds: Set<String>,
    ): List<ProblemRelationSuggestion> {
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        return legacyBusiness.observeMistakes().first().asSequence()
            .filter { candidate ->
                candidate.problemId != input.problemId &&
                    candidate.subject.toSubjectKind() == input.subject &&
                    candidate.knowledgeNodeIds.any(knowledgeNodeIds::contains)
            }
            .map { candidate ->
                candidate to candidate.knowledgeNodeIds.count(knowledgeNodeIds::contains)
            }
            .sortedWith(
                compareByDescending<Pair<MistakeRecord, Int>> { it.second }
                    .thenByDescending { it.first.createdAtEpochMillis }
                    .thenBy { it.first.problemId },
            )
            .take(MAX_ACCEPTED_RELATIONS)
            .map { (candidate, _) ->
                ProblemRelationSuggestion(
                    targetProblemId = candidate.problemId,
                    targetProblemRevisionId = candidate.problemRevisionId,
                    kind = ProblemRelationKind.SAME_KNOWLEDGE,
                    rationaleMarkdown = "与本题有相同知识点，已在本机整理。",
                    confidence = 1.0,
                )
            }
            .toList()
    }

    override suspend fun confirm(
        requestId: String,
        selection: ProblemOrganizationSelection,
        acceptedAtEpochMillis: Long,
    ): ProblemOrganizationConfirmation = withContext(Dispatchers.IO) {
        confirmOnIo(requestId, selection, acceptedAtEpochMillis)
    }

    private suspend fun confirmOnIo(
        requestId: String,
        selection: ProblemOrganizationSelection,
        acceptedAtEpochMillis: Long,
    ): ProblemOrganizationConfirmation {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(acceptedAtEpochMillis > 0) { "acceptedAtEpochMillis must be positive" }
        val persisted = readSuccessfulOrganization(requestId)
        val input = persisted.input
        val output = persisted.output
        val selectedClassifications = selection.classificationIndexes.sorted().map { index ->
            output.plan.classifications.getOrNull(index)
                ?: error("Selected classification is outside the persisted model output")
        }
        val userClassifications = selection.userClassifications.map { classification ->
            classification.toSuggestion()
        }
        val confirmedClassifications = (selectedClassifications + userClassifications)
            .distinctBy { it.dimension to it.displayName.trim().lowercase(Locale.ROOT) }
        val selectedRelations = selection.relationIndexes.sorted().map { index ->
            output.plan.relations.getOrNull(index)
                ?: error("Selected relation is outside the persisted model output")
        }
        require(confirmedClassifications.isNotEmpty()) { "Select or add at least one classification" }
        require(confirmedClassifications.all { it.dimension in PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS }) {
            "Only chapter and knowledge classifications can be saved"
        }
        require(selectedRelations.all { it.kind in PROBLEM_ORGANIZATION_RELATION_KINDS }) {
            "This relation type is no longer part of problem organization"
        }
        require(selection.relationRemovals.all { it.kind in PROBLEM_ORGANIZATION_RELATION_KINDS }) {
            "This relation type is no longer part of problem organization"
        }
        require(confirmedClassifications.any { it.dimension == ClassificationDimension.KNOWLEDGE }) {
            "Keep at least one knowledge classification for review planning"
        }
        require(confirmedClassifications.any { it.dimension == ClassificationDimension.CHAPTER }) {
            "Keep at least one chapter classification for a clear hierarchy"
        }
        val confirmedRelations = acceptedRelations(
            input = input,
            suggestions = selectedRelations,
            minimumConfidence = 0.0,
        )
        if (selection.relationRemovals.isNotEmpty()) {
            val currentRelations = legacyBusiness.observeConfirmedProblemOrganization(
                input.problemId,
                input.problemRevisionId,
            ).first().relations.mapNotNullTo(linkedSetOf()) { relation ->
                val kind = runCatching { ProblemRelationKind.valueOf(relation.relationType) }
                    .getOrNull() ?: return@mapNotNullTo null
                ProblemOrganizationRelationKey(
                    targetProblemId = relation.targetProblemId,
                    targetProblemRevisionId = relation.targetBasisRevisionId,
                    kind = kind,
                )
            }
            require(currentRelations.containsAll(selection.relationRemovals)) {
                "A relation selected for removal is no longer current"
            }
        }
        val verifiedKnowledgeReferences =
            verifyKnowledgeReferences(
                input = input,
                classifications = confirmedClassifications,
                atomicKnowledge = output.plan.atomicKnowledge,
            )
        val command = persisted.buildConfirmationCommand(
            requestId = requestId,
            classifications = confirmedClassifications,
            relations = confirmedRelations,
            atomicKnowledge = output.plan.atomicKnowledge,
            stepAttributions = output.plan.stepAttributions,
            verifiedKnowledgeReferences = verifiedKnowledgeReferences,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
            acceptanceSource = BindingAcceptanceSource.USER_CORRECTED,
            relationRemovals = selection.relationRemovals,
            replaceRelations = selection.replaceRelations,
        )
        val result = legacyBusiness.confirmProblemOrganization(command)
        return ProblemOrganizationConfirmation(
            created = result.created,
            classificationCount = result.receipt.classificationCount,
            relationCount = result.receipt.relationCount,
        )
    }

    private suspend fun verifyKnowledgeReferences(
        input: ProblemOrganizationInput,
        classifications: List<ProblemClassificationSuggestion>,
        atomicKnowledge: List<AtomicKnowledgeSuggestion>,
    ): List<VerifiedKnowledgeReferenceProof> {
        val knowledgeDisplayNames =
            classifications
                .asSequence()
                .filter { classification ->
                    classification.dimension == ClassificationDimension.KNOWLEDGE
                }
                .map { classification -> classification.displayName }
                .distinctBy { displayName -> displayName.normalizedKnowledgeName() }
                .toList()
        val acceptedKnowledgeNames =
            knowledgeDisplayNames.mapTo(hashSetOf()) { displayName ->
                displayName.normalizedKnowledgeName()
            }
        val preferredKnowledgeNodeIds =
            atomicKnowledge
                .asSequence()
                .filter { atom ->
                    atom.isAcceptableForPersistence(input) &&
                        atom.parentKnowledgeDisplayName.normalizedKnowledgeName() in
                        acceptedKnowledgeNames
                }
                .mapNotNull(AtomicKnowledgeSuggestion::matchedKnowledgeNodeId)
                .toCollection(linkedSetOf())
        return knowledgeContext.verifyConfirmationReferences(
            ReviewedProblemKnowledgeConfirmationRequest(
                subject = input.subject,
                knowledgeBaseNodes = input.knowledgeBaseNodes,
                knowledgeDisplayNames = knowledgeDisplayNames,
                preferredKnowledgeNodeIds = preferredKnowledgeNodeIds,
            ),
        )
    }

    private suspend fun committedRelationCandidates(
        catalog: List<MistakeRecord>,
        current: MistakeRecord,
    ): List<RelatedProblemCandidate> {
        val candidateRows = catalog.asSequence()
            .filter { candidate ->
                candidate.problemId != current.problemId && candidate.subject == current.subject
            }
            .sortedWith(
                compareByDescending<MistakeRecord> { relationCandidateScore(current, it) }
                    .thenByDescending { it.createdAtEpochMillis }
                    .thenBy { it.title }
                    .thenBy { it.problemId },
            )
            .take(ProblemOrganizationInput.MAX_RELATION_CANDIDATES)
            .toList()
        return buildList {
            candidateRows.forEach { candidate ->
                val detail = legacyBusiness.readExactMistakeDetail(
                    candidate.entryId,
                    candidate.problemId,
                    candidate.problemRevisionId,
                ) ?: return@forEach
                detail.committedDocumentOrNull()?.let { document ->
                    add(
                        RelatedProblemCandidate(
                            problemId = candidate.problemId,
                            problemRevisionId = candidate.problemRevisionId,
                            subject = candidate.subject.toSubjectKind(),
                            title = candidate.title,
                            questionDocument = document.document,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun committedKnowledgeContext(
        subject: String,
        questionText: String,
    ): List<KnowledgeBaseNodeContext> =
        knowledgeContext.read(
            subject = subject.toSubjectKind(),
            questionText = questionText,
        )

    override suspend fun prepareCommittedWork(
        workId: String,
        provider: ProviderCapabilitySnapshot,
        authorization: ProblemOrganizationAuthorizationGrant,
        requestVersion: Long,
        occurredAtEpochMillis: Long,
    ): MistakeOrganizationPreparation = withContext(Dispatchers.IO) {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(requestVersion >= 0) { "requestVersion must not be negative" }
        require(provider.supports(ModelTaskKind.PROBLEM_CLASSIFY)) {
            "Current provider does not support problem organization"
        }
        require(provider.supportsImageInput) {
            "Image-grounded organization requires image input support"
        }
        require(authorization.matchesCurrent(provider, occurredAtEpochMillis)) {
            "Problem organization authorization is not current"
        }
        val work = organizationWork.readProblemOrganizationWork(workId)
            ?: error("Organization work was not found")
        val receipt =
            organizationWork.readProblemOrganizationWorkCommitReceipt(
                work.commitReceiptCommandId,
            )
            ?: error("Organization work commit receipt was not found")
        val draft = assetDocuments.readProblemDraft(receipt.draftId)
            ?: error("Organization source draft was not found")
        require(authorization.sourceDraftId == receipt.draftId) {
            "Problem organization authorization belongs to another source draft"
        }
        require(
            draft.status == StudyDbValue.ProblemDraftStatus.COMMITTED &&
                draft.currentRevision.revisionNumber == receipt.draftRevisionNumber,
        ) { "Organization work no longer points to its committed source revision" }
        require(
            CapturedQuestionDocumentFingerprint.of(draft.currentRevision.questionDocument) ==
                draft.currentRevision.documentFingerprint,
        ) { "Organization source document fingerprint does not match its commit revision" }
        val catalog = legacyBusiness.observeMistakes().first()
        val current = catalog.singleOrNull {
            it.problemId == receipt.problemId &&
                it.problemRevisionId == receipt.problemRevisionId &&
                it.practiceUnitId == receipt.practiceUnitId
        } ?: error("Organization work problem revision is no longer current")
        require(draft.currentRevision.subject == current.subject) {
            "Organization source subject does not match its committed problem"
        }
        val questionText = QuestionDocumentMarkdownProjection.project(
            draft.currentRevision.questionDocument.document,
        )
        val knowledgeBaseNodes = committedKnowledgeContext(
            subject = current.subject,
            questionText = questionText,
        )
        val sourceAssets = draft.sourceAssets.map { source ->
            CaptureSourceAssetRef(
                assetId = source.sourceAsset.sourceAssetId,
                sha256 = source.sourceAsset.contentSha256,
                width = source.sourceAsset.width,
                height = source.sourceAsset.height,
                pageIndex = source.pageIndex,
            )
        }
        require(authorization.assets.size == draft.sourceAssets.size) {
            "Problem organization authorization asset scope changed"
        }
        draft.sourceAssets.forEach { source ->
            val grant = authorization.assets.singleOrNull {
                it.assetId == source.sourceAsset.sourceAssetId
            } ?: error("Problem organization source asset is outside authorization")
            require(
                grant.sha256 == source.sourceAsset.contentSha256 &&
                    grant.byteSize == source.sourceAsset.byteSize &&
                    grant.width == source.sourceAsset.width &&
                    grant.height == source.sourceAsset.height &&
                    grant.selectedRegion == null,
            ) { "Problem organization source asset changed after authorization" }
        }
        val input = ProblemOrganizationV3Input(
            problemId = receipt.problemId,
            problemRevisionId = receipt.problemRevisionId,
            practiceUnitId = receipt.practiceUnitId,
            subject = current.subject.toSubjectKind(),
            capturedDocument = draft.currentRevision.questionDocument,
            sourceAssets = sourceAssets,
            relationCandidates = emptyList(),
            knowledgeBaseNodes = knowledgeBaseNodes,
        )
        val requestId = organizationV3RequestId(
            workId = workId,
            commitReceiptCommandId = receipt.commandId,
            input = input,
            provider = provider,
            authorizationId = authorization.authorizationId,
            requestVersion = requestVersion,
        )
        val manifest = authorization.toEgressManifest(requestId, input)
        MistakeOrganizationPreparation(
            request = ModelTaskRequest(
                schemaVersion = ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION,
                requestId = requestId,
                input = input,
                occurredAtEpochMillis = occurredAtEpochMillis,
                egressManifest = manifest,
            ),
            relatedCandidateTitles = emptyList(),
            knowledgeContextCount = knowledgeBaseNodes.size,
        )
    }

    override suspend fun prepareReauthorization(
        key: MistakeRevisionKey,
        provider: ProviderCapabilitySnapshot,
        occurredAtEpochMillis: Long,
    ): ProblemOrganizationReauthorizationPreparation? = withContext(Dispatchers.IO) {
        require(occurredAtEpochMillis >= 0) { "Occurrence time must not be negative" }
        val prepared = legacyReauthorization.readLatestProblemOrganizationWork(
            problemId = key.problemId,
            problemRevisionId = key.problemRevisionId,
            errorBookEntryId = key.entryId,
        ) ?: return@withContext null
        val durableStatus = when (prepared.work.status) {
            StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION ->
                ProblemOrganizationDurableStatus.WAITING_AUTHORIZATION

            StudyDbValue.ProblemOrganizationWorkStatus.PENDING,
            StudyDbValue.ProblemOrganizationWorkStatus.RUNNING,
            StudyDbValue.ProblemOrganizationWorkStatus.RETRY,
            -> ProblemOrganizationDurableStatus.ACTIVE

            StudyDbValue.ProblemOrganizationWorkStatus.SUCCEEDED,
            StudyDbValue.ProblemOrganizationWorkStatus.PERMANENT_FAILURE,
            -> ProblemOrganizationDurableStatus.TERMINAL

            else -> error("Unknown durable organization work status")
        }
        val currentGrant = ProblemOrganizationAuthorizationGrantCodec.decodeOrNull(
            prepared.work.authorizationGrantSnapshot,
        )
        ProblemOrganizationReauthorizationPreparation(
            key = key,
            workId = prepared.work.workId,
            expectedStateVersion = prepared.work.stateVersion,
            requiresStudentConfirmation =
                durableStatus == ProblemOrganizationDurableStatus.WAITING_AUTHORIZATION &&
                    currentGrant?.matchesCurrent(provider, occurredAtEpochMillis) != true,
            durableStatus = durableStatus,
        )
    }

    override suspend fun reauthorize(
        preparation: ProblemOrganizationReauthorizationPreparation,
        provider: ProviderCapabilitySnapshot,
        approvedAtEpochMillis: Long,
    ): ProblemOrganizationReauthorizationOutcome = withContext(Dispatchers.IO) {
        require(approvedAtEpochMillis >= 0) { "Approval time must not be negative" }
        require(
            preparation.durableStatus == ProblemOrganizationDurableStatus.WAITING_AUTHORIZATION,
        ) { "Only waiting organization work can be reauthorized" }
        require(
            provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                provider.supportsImageInput &&
                provider.supports(ModelTaskKind.PROBLEM_CLASSIFY),
        ) { "Current provider cannot receive an image-grounded organization request" }
        val work = organizationWork.readProblemOrganizationWork(preparation.workId)
            ?: return@withContext ProblemOrganizationReauthorizationOutcome.LOST_AUTHORITY
        if (work.stateVersion != preparation.expectedStateVersion) {
            return@withContext ProblemOrganizationReauthorizationOutcome.LOST_AUTHORITY
        }
        val receipt = organizationWork.readProblemOrganizationWorkCommitReceipt(
            work.commitReceiptCommandId,
        ) ?: return@withContext ProblemOrganizationReauthorizationOutcome.LOST_AUTHORITY
        if (
            receipt.problemId != preparation.key.problemId ||
            receipt.problemRevisionId != preparation.key.problemRevisionId ||
            receipt.errorBookEntryId != preparation.key.entryId
        ) {
            return@withContext ProblemOrganizationReauthorizationOutcome.LOST_AUTHORITY
        }
        val draft = assetDocuments.readProblemDraft(receipt.draftId)
            ?: return@withContext ProblemOrganizationReauthorizationOutcome.LOST_AUTHORITY
        val authorization = ProblemOrganizationAuthorizationGrant(
            authorizationId = "problem-organization:${UUID.randomUUID()}",
            sourceDraftId = receipt.draftId,
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            approvedAtEpochMillis = approvedAtEpochMillis,
            expiresAtEpochMillis = Math.addExact(
                approvedAtEpochMillis,
                MODEL_EGRESS_APPROVAL_TTL_MILLIS,
            ),
            assets = draft.sourceAssets.map { source ->
                ModelEgressAssetGrant(
                    assetId = source.sourceAsset.sourceAssetId,
                    sha256 = source.sourceAsset.contentSha256,
                    byteSize = source.sourceAsset.byteSize,
                    width = source.sourceAsset.width,
                    height = source.sourceAsset.height,
                )
            },
        )
        when (
            legacyReauthorization.reauthorizeProblemOrganizationWork(
                ReauthorizeProblemOrganizationWorkCommand(
                    workId = preparation.workId,
                    expectedStateVersion = preparation.expectedStateVersion,
                    problemId = preparation.key.problemId,
                    problemRevisionId = preparation.key.problemRevisionId,
                    errorBookEntryId = preparation.key.entryId,
                    provider = provider,
                    authorizationGrant = authorization,
                ),
            ).outcome
        ) {
            ReauthorizeProblemOrganizationWorkOutcome.REAUTHORIZED ->
                ProblemOrganizationReauthorizationOutcome.REAUTHORIZED

            ReauthorizeProblemOrganizationWorkOutcome.REPLAYED ->
                ProblemOrganizationReauthorizationOutcome.REPLAYED

            ReauthorizeProblemOrganizationWorkOutcome.NOT_APPLIED ->
                ProblemOrganizationReauthorizationOutcome.LOST_AUTHORITY
        }
    }

    private suspend fun readSuccessfulOrganization(requestId: String): PersistedOrganizationTask {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val task = modelTasks.readModelTask(requestId)
            ?: error("Organization task was not found")
        require(task.status == ModelTaskStatus.SUCCEEDED) {
            "Only a successful organization task can be applied"
        }
        val rawInput = task.request.input
        val input = when (rawInput) {
            is ProblemOrganizationInput -> rawInput
            is ProblemOrganizationV3Input -> rawInput.asLegacyCompatibilityInput()
            else -> error("Task is not a problem organization request")
        }
        val output = task.output as? ProblemOrganizationOutput
            ?: error("Successful organization task has no organization output")
        require(
            output.problemId == input.problemId &&
                output.problemRevisionId == input.problemRevisionId &&
                output.practiceUnitId == input.practiceUnitId,
        ) { "Organization result does not match the persisted revision" }
        val sourceCommitReceiptCommandId = when (rawInput) {
            is ProblemOrganizationV3Input -> {
                require(output.plan.schemaVersion == 3) {
                    "Image-grounded organization must produce a schema three plan"
                }
                organizationWork.readProblemOrganizationWorkByRequestId(requestId)
                    ?.commitReceiptCommandId
                    ?: error("Image-grounded organization has no source commit receipt")
            }

            is ProblemOrganizationInput -> {
                require(output.plan.schemaVersion < 3) {
                    "Schema three organization requires an image-grounded request"
                }
                null
            }
        }
        return PersistedOrganizationTask(task, input, output, sourceCommitReceiptCommandId)
    }
}

object MistakeOrganizationRepositoryFactory {
    /**
     * Retains the old Room-backed business rows only while student-organization cutover is
     * incomplete.
     *
     * Coordination and model/document access must come from owner-issued capabilities. The two
     * pre-cutover business capabilities are intentionally not obtainable from the legacy owner.
     */
    fun createLegacyDuringAuthorityMigration(
        legacyBusiness: LegacyPreCutoverMistakeOrganizationBusinessPort,
        organizationWork: TrustedOrganizationWorkDatabaseCapability,
        legacyReauthorization: LegacyPreCutoverOrganizationReauthorizationPort,
        modelTasksAndAssetDocuments: TrustedModelTaskDatabaseCapability,
        knowledgeContext: ReviewedProblemKnowledgeContextRepository,
    ): MistakeOrganizationRepository =
        RoomMistakeOrganizationRepository(
            legacyBusiness = legacyBusiness,
            organizationWork = organizationWork,
            legacyReauthorization = legacyReauthorization,
            modelTasks = modelTasksAndAssetDocuments,
            assetDocuments = modelTasksAndAssetDocuments,
            knowledgeContext = knowledgeContext,
        )
}
