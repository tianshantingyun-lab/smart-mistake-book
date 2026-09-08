package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.data.knowledge.BundledKnowledgeBaseInstaller
import com.tingyun.smartmistakebook.core.data.knowledge.KnowledgeContextRetriever
import com.tingyun.smartmistakebook.core.database.ConfirmProblemOrganizationCommand
import com.tingyun.smartmistakebook.core.database.KnowledgeBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingRequestRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.database.MAX_KNOWLEDGE_RECALL_CANDIDATES
import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.ProblemClassificationBindingRecord
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationAuthorityConflictException
import com.tingyun.smartmistakebook.core.database.ProblemRelationSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemClassification
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemRelation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationOptions
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.OrganizationOption
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationConfirmation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationRelationKey
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.UserProblemClassification
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingRequest
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemRelationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_RELATION_KINDS
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val ORGANIZATION_PROMPT_POLICY_VERSION =
    com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions.PROBLEM_ORGANIZATION
private const val ORGANIZATION_TAXONOMY_VERSION = "local-policy-v1"
private const val USER_CORRECTION_TAXONOMY_VERSION = "user-corrected-v1"
// Retain the first automatic taxonomy salt so nodes already accepted this cycle keep their id.
// Acceptance authority belongs to classifications and bindings, not knowledge-node identity.
private const val KNOWLEDGE_NODE_IDENTITY_SALT = ORGANIZATION_TAXONOMY_VERSION
private const val KNOWLEDGE_NODE_TAXONOMY_VERSION = "organization-v1"
private const val MIN_ATOMIC_BINDING_STRENGTH = 0.15
internal const val CLASSIFICATION_ACCEPTANCE_CONFIDENCE = 0.78
internal const val ATOMIC_KNOWLEDGE_ACCEPTANCE_CONFIDENCE = 0.72
internal const val RELATION_ACCEPTANCE_CONFIDENCE = 0.90
private const val MAX_ACCEPTED_CHAPTERS = 3
private const val MAX_ACCEPTED_KNOWLEDGE = 8
private const val MAX_ACCEPTED_RELATIONS = 4
internal class RoomMistakeOrganizationRepository(
    private val database: StudyDatabasePort,
) : MistakeOrganizationRepository {
    override suspend fun prepare(
        key: MistakeRevisionKey,
        profile: StudyProfileOverview,
        provider: ProviderCapabilitySnapshot,
        attempt: Int,
        occurredAtEpochMillis: Long,
        approvedAtEpochMillis: Long,
    ): MistakeOrganizationPreparation = withContext(Dispatchers.IO) {
        BundledKnowledgeBaseInstaller.install(database)
        require(attempt >= 0) { "attempt must not be negative" }
        require(provider.supports(ModelTaskKind.PROBLEM_CLASSIFY)) {
            "Current provider does not support problem organization"
        }
        val catalog = database.observeMistakes().first()
        val current = catalog.singleOrNull {
            it.entryId == key.entryId &&
                it.problemId == key.problemId &&
                it.problemRevisionId == key.problemRevisionId
        } ?: error("The selected mistake revision is no longer current")
        val currentDocument = database.readExactMistakeDetail(
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
                val detail = database.readExactMistakeDetail(
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
        val recallCandidates = database.readSubjectKnowledgeRecallCandidates(
            subject = current.subject,
            searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(questionText),
            limit = MAX_KNOWLEDGE_RECALL_CANDIDATES,
        )
        val initialKnowledgeRecords = KnowledgeContextRetriever.select(
            candidates = recallCandidates,
            questionText = questionText,
            limit = ProblemOrganizationInput.MAX_KNOWLEDGE_BASE_NODES,
        )
        val knowledgeRelations = database.readKnowledgeNodeRelationsForDependents(
            subject = current.subject,
            dependentKnowledgeNodeIds = initialKnowledgeRecords
                .mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId),
        )
        val prerequisiteRecords = database.readKnowledgeNodesByIds(
            knowledgeRelations.mapTo(
                hashSetOf(),
                KnowledgeNodeRelationRecord::prerequisiteKnowledgeNodeId,
            ),
        )
        val prerequisiteParents = database.readKnowledgeNodesByIds(
            prerequisiteRecords.mapNotNullTo(
                hashSetOf(),
                KnowledgeNodeSeedRecord::parentKnowledgeNodeId,
            ),
        )
        val knowledgeRecords = KnowledgeContextRetriever.select(
            candidates = recallCandidates + prerequisiteRecords + prerequisiteParents,
            relations = knowledgeRelations,
            questionText = questionText,
            limit = ProblemOrganizationInput.MAX_KNOWLEDGE_BASE_NODES,
        )
        val parentNames = knowledgeRecords.associate { record ->
            record.knowledgeNodeId to record.canonicalName
        }
        val selectedKnowledgeIds = knowledgeRecords.mapTo(hashSetOf()) { it.knowledgeNodeId }
        val prerequisitesByDependent = knowledgeRelations
            .filter { relation ->
                relation.prerequisiteKnowledgeNodeId in selectedKnowledgeIds &&
                    relation.dependentKnowledgeNodeId in selectedKnowledgeIds
            }
            .groupBy(
                { it.dependentKnowledgeNodeId },
                { it.prerequisiteKnowledgeNodeId },
            )
        val knowledgeBaseNodes = knowledgeRecords.mapNotNull { record ->
            record.toKnowledgeBaseContext(
                subject = current.subject.toSubjectKind(),
                parentCanonicalName = record.parentKnowledgeNodeId?.let(parentNames::get),
                prerequisiteKnowledgeNodeIds = prerequisitesByDependent[record.knowledgeNodeId]
                    .orEmpty(),
            )
        }
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
                authorizationId = "authorization:$requestId",
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
        database.observeConfirmedProblemOrganization(key.problemId, key.problemRevisionId)
            .map { record ->
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
        val existing = database.observeConfirmedProblemOrganization(
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
            database.recordKnowledgeGroundingRequests(
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
        val command = buildConfirmationCommand(
            requestId = requestId,
            input = persisted.input,
            classifications = mergeAutomaticClassifications(
                existing = existing.classifications,
                incoming = accepted.classifications,
            ),
            relations = accepted.relations,
            atomicKnowledge = accepted.atomicKnowledge,
            stepAttributions = accepted.stepAttributions,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
            acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
            replaceRelations = false,
        )
        val result = try {
            database.confirmProblemOrganization(command)
        } catch (_: ProblemOrganizationAuthorityConflictException) {
            val existing = database.observeConfirmedProblemOrganization(
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
            val currentRelations = database.observeConfirmedProblemOrganization(
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
        val command = buildConfirmationCommand(
            requestId = requestId,
            input = input,
            classifications = confirmedClassifications,
            relations = confirmedRelations,
            atomicKnowledge = output.plan.atomicKnowledge,
            stepAttributions = output.plan.stepAttributions,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
            acceptanceSource = BindingAcceptanceSource.USER_CORRECTED,
            relationRemovals = selection.relationRemovals,
            replaceRelations = selection.replaceRelations,
        )
        val result = database.confirmProblemOrganization(command)
        return ProblemOrganizationConfirmation(
            created = result.created,
            classificationCount = result.receipt.classificationCount,
            relationCount = result.receipt.relationCount,
        )
    }

    private suspend fun readSuccessfulOrganization(requestId: String): PersistedOrganizationTask {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val task = database.readModelTask(requestId)
            ?: error("Organization task was not found")
        require(task.status == ModelTaskStatus.SUCCEEDED) {
            "Only a successful organization task can be applied"
        }
        val input = task.request.input as? ProblemOrganizationInput
            ?: error("Task is not a problem organization request")
        val output = task.output as? ProblemOrganizationOutput
            ?: error("Successful organization task has no organization output")
        require(
            output.problemId == input.problemId &&
                output.problemRevisionId == input.problemRevisionId &&
                output.practiceUnitId == input.practiceUnitId,
        ) { "Organization result does not match the persisted revision" }
        return PersistedOrganizationTask(task, input, output)
    }

    override suspend fun correctConfirmedOrganization(
        key: MistakeRevisionKey,
        selection: ProblemOrganizationSelection,
        correctedAtEpochMillis: Long,
    ): ProblemOrganizationConfirmation = withContext(Dispatchers.IO) {
        require(correctedAtEpochMillis > 0) { "correctedAtEpochMillis must be positive" }
        require(selection.classificationIndexes.isEmpty() && selection.relationIndexes.isEmpty()) {
            "Offline correction selects reviewed options, not model-output indexes"
        }
        require(selection.relationRemovals.isEmpty() && !selection.replaceRelations) {
            "Offline correction does not change relations"
        }
        val current = currentOrganizationFor(key)
        val command = buildOfflineCorrectionCommand(
            entryId = key.entryId,
            current = current.toOfflineCorrectionFacts(),
            userClassifications = selection.userClassifications,
            correctedAtEpochMillis = correctedAtEpochMillis,
        )
        val result = try {
            database.confirmProblemOrganization(command)
        } catch (_: ProblemOrganizationAuthorityConflictException) {
            return@withContext ProblemOrganizationConfirmation(
                created = false,
                classificationCount = 0,
                relationCount = 0,
                applied = false,
                preservedUserCorrection = true,
            )
        }
        ProblemOrganizationConfirmation(
            created = result.created,
            classificationCount = result.receipt.classificationCount,
            relationCount = result.receipt.relationCount,
        )
    }

    override fun observeOrganizationOptions(
        key: MistakeRevisionKey,
    ): Flow<MistakeOrganizationOptions> =
        database.observeConfirmedProblemOrganization(key.problemId, key.problemRevisionId)
            .map { confirmed ->
                // The confirmed organization only exposes the current subject
                // indirectly through its labels; chapter/knowledge options are
                // derived from the same confirmed classifications plus the
                // mistake catalog subject (kept simple: current values are the
                // seed options; richer reviewed-tree options are a follow-up).
                val subject = runCatching {
                    database.observeMistakes().first().singleOrNull {
                        it.entryId == key.entryId &&
                            it.problemId == key.problemId &&
                            it.problemRevisionId == key.problemRevisionId
                    }?.subject
                }.getOrNull().orEmpty()
                MistakeOrganizationOptions(
                    subject = subject,
                    chapters = confirmed.classifications
                        .filter { it.dimension == ClassificationDimension.CHAPTER.name }
                        .map { OrganizationOption(labelId = it.labelId, displayName = it.displayName) },
                    knowledgeNodes = confirmed.classifications
                        .filter { it.dimension == ClassificationDimension.KNOWLEDGE.name }
                        .map { OrganizationOption(labelId = it.labelId, displayName = it.displayName) },
                )
            }

    private suspend fun currentOrganizationFor(
        key: MistakeRevisionKey,
    ): CurrentOrganizationFacts {
        val mistake = database.observeMistakes().first().singleOrNull {
            it.entryId == key.entryId &&
                it.problemId == key.problemId &&
                it.problemRevisionId == key.problemRevisionId
        } ?: error("The selected mistake revision is no longer current")
        val detail = database.readExactMistakeDetail(
            key.entryId,
            key.problemId,
            key.problemRevisionId,
        ).requireCommittedDocument()
        val subjectKind = runCatching { mistake.subject.toSubjectKind() }
            .getOrDefault(SubjectKind.MATH)
        return CurrentOrganizationFacts(
            problemId = key.problemId,
            problemRevisionId = key.problemRevisionId,
            practiceUnitId = mistake.practiceUnitId,
            subject = subjectKind,
            questionDocument = detail.document,
        )
    }

    private data class CurrentOrganizationFacts(
        val problemId: String,
        val problemRevisionId: String,
        val practiceUnitId: String,
        val subject: SubjectKind,
        val questionDocument: com.tingyun.smartmistakebook.core.model.QuestionDocument,
    ) {
        fun toOfflineCorrectionFacts() = OfflineCorrectionFacts(
            problemId = problemId,
            problemRevisionId = problemRevisionId,
            practiceUnitId = practiceUnitId,
            subject = subject,
            questionDocument = questionDocument,
        )
    }
}

/**
 * Deterministic offline-correction command: rebuilds the durable organization
 * facts from the currently confirmed mistake plus the user's picks, with no
 * model task, no relations, and USER_CORRECTED authority. Pure so the
 * authority/identity invariants are unit-testable without a database.
 */
internal fun buildOfflineCorrectionCommand(
    entryId: String,
    current: OfflineCorrectionFacts,
    userClassifications: List<UserProblemClassification>,
    correctedAtEpochMillis: Long,
): ConfirmProblemOrganizationCommand {
    require(correctedAtEpochMillis > 0) { "correctedAtEpochMillis must be positive" }
    val classifications = userClassifications.map { classification ->
        classification.toSuggestion()
    }
    require(classifications.any { it.dimension == ClassificationDimension.KNOWLEDGE }) {
        "Keep at least one knowledge classification for review planning"
    }
    require(classifications.any { it.dimension == ClassificationDimension.CHAPTER }) {
        "Keep at least one chapter classification for a clear hierarchy"
    }
    val input = ProblemOrganizationInput(
        problemId = current.problemId,
        problemRevisionId = current.problemRevisionId,
        practiceUnitId = current.practiceUnitId,
        subject = current.subject,
        questionDocument = current.questionDocument,
        relevantLearningEvidence = emptyList(),
        relationCandidates = emptyList(),
        knowledgeBaseNodes = emptyList(),
    )
    return buildConfirmationCommand(
        requestId = "offline-correction:$entryId:$correctedAtEpochMillis",
        input = input,
        classifications = classifications,
        relations = emptyList(),
        acceptedAtEpochMillis = correctedAtEpochMillis,
        acceptanceSource = BindingAcceptanceSource.USER_CORRECTED,
    )
}

internal data class OfflineCorrectionFacts(
    val problemId: String,
    val problemRevisionId: String,
    val practiceUnitId: String,
    val subject: SubjectKind,
    val questionDocument: com.tingyun.smartmistakebook.core.model.QuestionDocument,
)

object MistakeOrganizationRepositoryFactory {
    fun create(database: StudyDatabasePort): MistakeOrganizationRepository =
        RoomMistakeOrganizationRepository(database)
}

private data class PersistedOrganizationTask(
    val task: ModelTaskSnapshot,
    val input: ProblemOrganizationInput,
    val output: ProblemOrganizationOutput,
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

private fun ProblemClassificationSuggestion.normalizedForLocalAcceptance(): ProblemClassificationSuggestion? {
    val normalizedName = displayName.trim().replace(Regex("\\s+"), " ")
    if (normalizedName.isEmpty()) return null
    return runCatching { copy(displayName = normalizedName) }.getOrNull()
}

private fun acceptedRelations(
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

private fun MistakeDetailRecord?.requireCommittedDocument(): CapturedQuestionDocument =
    this?.committedDocumentOrNull() ?: error("The confirmed question document is unavailable")

private fun MistakeDetailRecord.committedDocumentOrNull(): CapturedQuestionDocument? {
    val snapshot = questionDocumentSnapshot ?: return null
    return runCatching { CapturedQuestionDocumentCodec.decode(snapshot) }.getOrNull()
        ?.takeIf { document ->
            CapturedQuestionDocumentValidator.validateForCommit(document).isEmpty() &&
                CapturedQuestionDocumentFingerprint.of(document) == contentFingerprint
        }
}

private fun String.toSubjectKind(): SubjectKind =
    runCatching { SubjectKind.valueOf(uppercase(Locale.ROOT)) }.getOrDefault(SubjectKind.GENERAL)

private fun KnowledgeNodeSeedRecord.toKnowledgeBaseContext(
    subject: SubjectKind,
    parentCanonicalName: String?,
    prerequisiteKnowledgeNodeIds: List<String>,
): KnowledgeBaseNodeContext? = runCatching {
    KnowledgeBaseNodeContext(
        knowledgeNodeId = knowledgeNodeId,
        subject = subject,
        canonicalName = canonicalName,
        aliases = aliases.sorted(),
        kind = KnowledgeNodeKind.valueOf(nodeKind),
        granularity = KnowledgeNodeGranularity.valueOf(granularity),
        parentCanonicalName = parentCanonicalName,
        taxonomyVersion = taxonomyVersion,
        verificationStatus = KnowledgeNodeVerificationStatus.valueOf(verificationStatus),
        boundaryMarkdown = boundaryMarkdown,
        prerequisiteKnowledgeNodeIds = prerequisiteKnowledgeNodeIds,
    )
}.getOrNull()

private fun organizationRequestId(
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

internal fun buildConfirmationCommand(
    requestId: String,
    input: ProblemOrganizationInput,
    classifications: List<ProblemClassificationSuggestion>,
    relations: List<ProblemRelationSuggestion>,
    acceptedAtEpochMillis: Long,
    atomicKnowledge: List<AtomicKnowledgeSuggestion> = emptyList(),
    stepAttributions: List<ProblemStepKnowledgeAttribution> = emptyList(),
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
    val knowledgeClassifications = classificationRecords.filter {
        it.dimension == ClassificationDimension.KNOWLEDGE.name
    }
    val topicNodes = knowledgeClassifications.map { classification ->
        KnowledgeNodeSeedRecord(
            knowledgeNodeId = "knowledge:${sha256("${classification.labelId}|$KNOWLEDGE_NODE_IDENTITY_SALT").take(40)}",
            stableCode = classification.labelId,
            subject = input.subject.name,
            displayName = classification.displayName,
            parentKnowledgeNodeId = null,
            taxonomyVersion = KNOWLEDGE_NODE_TAXONOMY_VERSION,
            createdAtEpochMillis = acceptedAtEpochMillis,
            canonicalName = classification.displayName,
            nodeKind = KnowledgeNodeKind.TOPIC.name,
            granularity = KnowledgeNodeGranularity.TOPIC.name,
            // A node the student typed or corrected is user authority, not an
            // unverified model candidate: it must be reusable as a candidate for
            // later problems of the same knowledge point (audit 2026-09-09).
            // Model-proposed labels accepted by local policy stay candidates.
            verificationStatus = if (classification.acceptanceSource ==
                BindingAcceptanceSource.USER_CORRECTED.name
            ) {
                KnowledgeNodeVerificationStatus.USER_CONFIRMED.name
            } else {
                KnowledgeNodeVerificationStatus.MODEL_CANDIDATE.name
            },
        )
    }
    val topicNodesByName = topicNodes.associateBy { node ->
        node.displayName.normalizedKnowledgeName()
    }
    val contextById = input.knowledgeBaseNodes.associateBy(
        KnowledgeBaseNodeContext::knowledgeNodeId,
    )
    val acceptedAtoms = atomicKnowledge.filter { atom ->
        atom.isAcceptableForPersistence(input) &&
            topicNodesByName.containsKey(atom.parentKnowledgeDisplayName.normalizedKnowledgeName())
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
    val nodeIdsToBind = if (atomicNodePairs.isNotEmpty()) {
        atomicNodePairs.map { (_, node) -> node.knowledgeNodeId }
    } else {
        topicNodes.map(KnowledgeNodeSeedRecord::knowledgeNodeId)
    }
    val knowledgeBindings = nodeIdsToBind.map { knowledgeNodeId ->
        val atom = atomByNodeId[knowledgeNodeId]
        val strength = if (atom == null) {
            1.0
        } else {
            val attributedSteps = attributedStepCounts[atom.referenceId] ?: 0
            attributedSteps.toDouble()
                .div(stepAttributions.size.coerceAtLeast(1))
                .coerceIn(MIN_ATOMIC_BINDING_STRENGTH, 1.0)
        }
        KnowledgeBindingSeedRecord(
            bindingId = "knowledge-binding:${sha256("${input.practiceUnitId}|$knowledgeNodeId|${input.problemRevisionId}|$taxonomyVersion").take(40)}",
            practiceUnitId = input.practiceUnitId,
            knowledgeNodeId = knowledgeNodeId,
            basisRevisionId = input.problemRevisionId,
            strength = strength,
            sourceType = acceptanceSource.name,
            taxonomyVersion = taxonomyVersion,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
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
        topicNodes.sortedBy { it.knowledgeNodeId }.forEach { append(it.knowledgeNodeId).append('|') }
        knowledgeBindings.sortedBy { it.bindingId }.forEach { binding ->
            append(binding.bindingId).append(':').append(binding.strength).append('|')
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
        knowledgeNodes = topicNodes,
        knowledgeBindings = knowledgeBindings,
        classifications = classificationRecords,
        relations = relationRecords,
        relationIdsToRemove = relationIdsToRemove,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
        replaceRelations = replaceRelations,
    )
}

private fun relationId(
    sourceRevisionId: String,
    targetRevisionId: String,
    kind: ProblemRelationKind,
): String = "relation:${sha256("$sourceRevisionId|$targetRevisionId|$kind").take(40)}"

private fun classificationLabelId(
    subject: SubjectKind,
    dimension: ClassificationDimension,
    displayName: String,
): String {
    val normalized = displayName.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    return "${subject.name.lowercase(Locale.ROOT)}:${dimension.name.lowercase(Locale.ROOT)}:${sha256(normalized).take(24)}"
}

private fun String.normalizedKnowledgeName(): String =
    trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun AtomicKnowledgeSuggestion.isAcceptableForPersistence(
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

private fun UserProblemClassification.toSuggestion() = ProblemClassificationSuggestion(
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

private fun String.normalizedLabel(): String = trim().lowercase(Locale.ROOT)

private fun String.normalizedBigrams(): Set<String> {
    val compact = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    if (compact.length < 2) return compact.takeIf(String::isNotEmpty)?.let(::setOf).orEmpty()
    return (0 until compact.lastIndex).mapTo(linkedSetOf()) { index ->
        compact.substring(index, index + 2)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
