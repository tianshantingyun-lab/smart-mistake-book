package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemClassificationBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemErrorAttributionCandidateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemErrorCandidateEvidenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemSolutionStepEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemStepKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_RELATION_KINDS
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal class RoomProblemOrganizationStore(
    private val database: StudyDatabase,
) {
    fun observe(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord> {
        require(problemId.isNotBlank()) { "problemId must not be blank" }
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        val dao = database.problemOrganizationDao()
        return combine(
            dao.observeClassifications(problemId, problemRevisionId),
            dao.observeRelations(problemId, problemRevisionId),
            dao.observeCurrentKnowledgeNodeIds(problemId, problemRevisionId),
        ) { classifications, relations, knowledgeNodeIds ->
            ConfirmedProblemOrganizationRecord(
                classifications = classifications.map(ProblemClassificationBindingEntity::toRecord),
                relations = relations.map(ProblemRelationEntity::toRecord),
                knowledgeNodeIds = knowledgeNodeIds.toSet(),
            )
        }
    }

    suspend fun confirm(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult = database.withWriteTransaction {
        confirmInCurrentTransaction(command)
    }

    suspend fun confirmInCurrentTransaction(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult {
        validateCommand(command)
        val dao = database.problemOrganizationDao()
        val expectedReceipt = command.toReceiptEntity()
        dao.readReceipt(command.commandId)?.let { existing ->
            if (existing != expectedReceipt) {
                throw ImmutablePayloadConflictException(
                    "problem_organization_receipt",
                    command.commandId,
                )
            }
            return ConfirmProblemOrganizationResult(
                created = false,
                receipt = existing.toRecord(),
            )
        }
            if (
                dao.exactPracticeUnitCount(
                    command.practiceUnitId,
                    command.problemId,
                    command.problemRevisionId,
                ) != 1
            ) {
                throw ImmutablePayloadConflictException(
                    "problem_organization_target",
                    command.practiceUnitId,
                )
            }
            val detailedOrganization = buildDetailedOrganizationPersistence(database, command)
            val incomingAcceptanceSource = command.classifications
                .mapTo(linkedSetOf()) { it.acceptanceSource }
                .single()
            if (
                incomingAcceptanceSource == BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED.name &&
                hasUserOwnedOrganization(command.problemId, command.problemRevisionId)
            ) {
                throw ProblemOrganizationAuthorityConflictException(command.problemRevisionId)
            }
            dao.readLatestReceipt(command.problemId, command.problemRevisionId)?.let { latest ->
                if (latest.acceptedAtEpochMillis >= command.acceptedAtEpochMillis) {
                    throw ImmutablePayloadConflictException(
                        "problem_organization_stale_confirmation",
                        command.commandId,
                    )
                }
            }
            // Classification and relation replacement are separate authorities. A plain
            // reclassification must never erase accepted relations as a side effect.
            // Evidence attributions are immutable historical facts. Retain bindings they reference;
            // the replaceable classification table remains the authority for the current catalog.
            dao.deleteUnreferencedKnowledgeBindings(command.practiceUnitId, command.problemRevisionId)
            dao.deleteClassifications(command.problemId, command.problemRevisionId)
            if (command.replaceRelations) {
                dao.deleteOutgoingRelations(command.problemId, command.problemRevisionId)
            } else if (command.relationIdsToRemove.isNotEmpty()) {
                dao.deleteOutgoingRelationsById(
                    command.problemId,
                    command.problemRevisionId,
                    command.relationIdsToRemove.sorted(),
                )
            }
            val knowledgeNodes = command.knowledgeNodes.map(KnowledgeNodeSeedRecord::toOrganizationEntity)
            if (knowledgeNodes.isNotEmpty()) {
                dao.insertKnowledgeNodes(knowledgeNodes).zip(knowledgeNodes).forEach { (rowId, entity) ->
                    if (
                        rowId == -1L &&
                        !dao.readKnowledgeNode(entity.knowledgeNodeId).sameAcceptedFact(entity)
                    ) {
                        throw ImmutablePayloadConflictException("knowledge_node", entity.knowledgeNodeId)
                    }
                }
            }
            val knowledgeBindings = command.knowledgeBindings
                .map(KnowledgeBindingSeedRecord::toOrganizationEntity)
            if (knowledgeBindings.isNotEmpty()) {
                val expectedSubject = knowledgeNodes.mapTo(linkedSetOf()) { it.subject }.single()
                knowledgeBindings.forEach { binding ->
                    val boundNode = dao.readKnowledgeNode(binding.knowledgeNodeId)
                        ?: throw DatabaseContractViolationException(
                            "Knowledge binding references an unknown node",
                        )
                    if (boundNode.subject != expectedSubject) {
                        throw DatabaseContractViolationException(
                            "Knowledge binding crosses subject boundaries",
                        )
                    }
                }
                dao.insertKnowledgeBindings(knowledgeBindings).zip(knowledgeBindings)
                    .forEach { (rowId, entity) ->
                        val acceptedExistingFact = rowId == -1L && listOfNotNull(
                            dao.readKnowledgeBinding(entity.bindingId),
                            dao.readKnowledgeBindingByIdentity(
                                entity.practiceUnitId,
                                entity.knowledgeNodeId,
                                entity.basisRevisionId,
                                entity.taxonomyVersion,
                            ),
                        ).any { existing -> existing.sameAcceptedFact(entity) }
                        if (rowId == -1L && !acceptedExistingFact) {
                            throw ImmutablePayloadConflictException("knowledge_binding", entity.bindingId)
                        }
                    }
            }
            val classifications = command.classifications
                .map(ProblemClassificationBindingRecord::toOrganizationEntity)
            dao.insertClassificationBindings(classifications).zip(classifications)
                .forEach { (rowId, entity) ->
                    if (
                        rowId == -1L &&
                        !dao.readClassificationBinding(entity.bindingId).sameAcceptedFact(entity)
                    ) {
                        throw ImmutablePayloadConflictException(
                            "problem_classification_binding",
                            entity.bindingId,
                        )
                    }
                }
            val relations = command.relations.map(ProblemRelationSeedRecord::toOrganizationEntity)
            if (relations.isNotEmpty()) {
                dao.insertRelations(relations).zip(relations).forEach { (rowId, entity) ->
                    if (
                        rowId == -1L &&
                        !dao.readRelation(entity.relationId).sameAcceptedFact(entity)
                    ) {
                        throw ImmutablePayloadConflictException("problem_relation", entity.relationId)
                    }
                }
            }
            if (dao.insertReceipt(expectedReceipt) == -1L) {
                val winner = dao.readReceipt(command.commandId)
                    ?: throw ImmutablePayloadConflictException(
                        "problem_organization_receipt",
                        command.commandId,
                    )
                if (winner != expectedReceipt) {
                    throw ImmutablePayloadConflictException(
                        "problem_organization_receipt",
                        command.commandId,
                    )
                }
                return ConfirmProblemOrganizationResult(
                    created = false,
                    receipt = winner.toRecord(),
                )
            }
            val workDao = database.problemOrganizationWorkDao()
            if (detailedOrganization.steps.isNotEmpty()) {
                workDao.insertSolutionSteps(detailedOrganization.steps)
                workDao.insertStepKnowledgeBindings(
                    detailedOrganization.stepKnowledgeBindings,
                )
            }
            if (detailedOrganization.errorCandidates.isNotEmpty()) {
                workDao.insertErrorAttributionCandidates(
                    detailedOrganization.errorCandidates,
                )
                if (detailedOrganization.errorEvidence.isNotEmpty()) {
                    workDao.insertErrorCandidateEvidence(detailedOrganization.errorEvidence)
                }
            }
        return ConfirmProblemOrganizationResult(
            created = true,
            receipt = expectedReceipt.toRecord(),
        )
    }

    suspend fun requireLocalPolicyAuthority(
        problemId: String,
        problemRevisionId: String,
    ) {
        if (hasUserOwnedOrganization(problemId, problemRevisionId)) {
            throw ProblemOrganizationAuthorityConflictException(problemRevisionId)
        }
    }

    private suspend fun hasUserOwnedOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Boolean = database.problemOrganizationDao()
        .readClassificationAcceptanceSources(problemId, problemRevisionId)
        .any(USER_OWNED_ORGANIZATION_SOURCES::contains)
}

private data class DetailedOrganizationPersistence(
    val steps: List<ProblemSolutionStepEntity>,
    val stepKnowledgeBindings: List<ProblemStepKnowledgeBindingEntity>,
    val errorCandidates: List<ProblemErrorAttributionCandidateEntity>,
    val errorEvidence: List<ProblemErrorCandidateEvidenceEntity>,
) {
    companion object {
        val Empty = DetailedOrganizationPersistence(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

private suspend fun buildDetailedOrganizationPersistence(
    database: StudyDatabase,
    command: ConfirmProblemOrganizationCommand,
): DetailedOrganizationPersistence {
    if (command.planSchemaVersion < 3) {
        require(command.solutionSteps.isEmpty()) {
            "Legacy organization commands cannot persist solution steps"
        }
        require(command.errorAttributionCandidates.isEmpty()) {
            "Legacy organization commands cannot persist error attributions"
        }
        require(command.sourceCommitReceiptCommandId == null) {
            "Legacy organization commands cannot claim a source occurrence"
        }
        return DetailedOrganizationPersistence.Empty
    }
    require(command.planSchemaVersion == 3) { "Unsupported organization plan schema" }
    val sourceCommitReceiptId = requireNotNull(command.sourceCommitReceiptCommandId) {
        "V3 organization requires its exact source commit receipt"
    }
    require(sourceCommitReceiptId.isNotBlank()) {
        "sourceCommitReceiptCommandId must not be blank"
    }
    val sourceReceipt = database.problemOrganizationWorkDao()
        .readCommitReceipt(sourceCommitReceiptId)
        ?: throw DatabaseContractViolationException(
            "Organization source commit receipt does not exist",
        )
    require(
        sourceReceipt.problemId == command.problemId &&
            sourceReceipt.problemRevisionId == command.problemRevisionId &&
            sourceReceipt.practiceUnitId == command.practiceUnitId
    ) { "Organization source occurrence does not match the confirmed problem" }
    val sourceDraft = database.problemDraftTransactionDao().read(sourceReceipt.draftId)
        ?: throw DatabaseContractViolationException("Organization source draft does not exist")
    require(
        sourceDraft.status == StudyDbValue.ProblemDraftStatus.COMMITTED &&
            sourceDraft.currentRevision.revisionNumber == sourceReceipt.draftRevisionNumber
    ) { "Organization source draft no longer matches its commit receipt" }
    val sourceAssetIds = sourceDraft.sourceAssets
        .mapTo(linkedSetOf()) { it.sourceAsset.sourceAssetId }
    val sourceAssetByBlock = sourceDraft.currentRevision.questionDocument.blockEvidence
        .associate { it.blockId to it.sourceAssetId }
    require(
        sourceAssetByBlock.keys ==
            sourceDraft.currentRevision.questionDocument.document.blocks.mapTo(linkedSetOf()) { it.id }
    ) { "Organization source document has incomplete block evidence" }
    require(sourceAssetByBlock.values.all(sourceAssetIds::contains)) {
        "Organization source document references an asset outside its import occurrence"
    }

    val stepIdsByOrdinal = command.solutionSteps.associate { step ->
        step.stepOrdinal to detailedIdentity("solution-step", command.commandId, step.stepOrdinal)
    }
    val steps = command.solutionSteps.map { step ->
        ProblemSolutionStepEntity(
            solutionStepId = checkNotNull(stepIdsByOrdinal[step.stepOrdinal]),
            organizationCommandId = command.commandId,
            problemId = command.problemId,
            problemRevisionId = command.problemRevisionId,
            practiceUnitId = command.practiceUnitId,
            sourceCommitReceiptCommandId = sourceCommitReceiptId,
            stepOrdinal = step.stepOrdinal,
            summaryMarkdown = step.summaryMarkdown,
            createdAtEpochMillis = command.acceptedAtEpochMillis,
        )
    }
    val stepKnowledgeBindings = command.solutionSteps.flatMap { step ->
        val stepId = checkNotNull(stepIdsByOrdinal[step.stepOrdinal])
        step.knowledgeReferences.map { reference ->
            ProblemStepKnowledgeBindingEntity(
                solutionStepId = stepId,
                knowledgeNodeId = reference.knowledgeNodeId,
                knowledgeReferenceId = reference.knowledgeReferenceId,
            )
        }
    }
    val nodeByReference = command.solutionSteps
        .flatMap(ProblemSolutionStepSeedRecord::knowledgeReferences)
        .groupBy(ProblemStepKnowledgeReferenceSeedRecord::knowledgeReferenceId)
        .mapValues { (referenceId, references) ->
            references.mapTo(linkedSetOf()) { it.knowledgeNodeId }.singleOrNull()
                ?: throw DatabaseContractViolationException(
                    "Knowledge reference $referenceId maps to multiple nodes",
                )
        }
    val referencesByStep = command.solutionSteps.associate { step ->
        step.stepOrdinal to step.knowledgeReferences.associate {
            it.knowledgeReferenceId to it.knowledgeNodeId
        }
    }
    val errorCandidates = command.errorAttributionCandidates.map { candidate ->
        val resolved = candidate.resolutionStatus ==
            StudyDbValue.ProblemErrorAttributionResolution.RESOLVED
        val stepId = candidate.stepOrdinal?.let(stepIdsByOrdinal::get)
        if (resolved) {
            require(stepId != null) { "Resolved error attribution must reference a known step" }
            require(
                referencesByStep[candidate.stepOrdinal]
                    ?.get(candidate.knowledgeReferenceId) == candidate.knowledgeNodeId &&
                    nodeByReference[candidate.knowledgeReferenceId] == candidate.knowledgeNodeId
            ) { "Resolved error attribution must reference knowledge used by its step" }
            require(candidate.evidence.isNotEmpty()) {
                "Resolved error attribution needs visible source evidence"
            }
        } else {
            require(
                candidate.resolutionStatus ==
                    StudyDbValue.ProblemErrorAttributionResolution.UNRESOLVED &&
                    candidate.stepOrdinal == null &&
                    candidate.knowledgeReferenceId == null &&
                    candidate.knowledgeNodeId == null &&
                    candidate.evidence.isEmpty()
            ) { "Unresolved error attribution cannot invent precise references" }
        }
        ProblemErrorAttributionCandidateEntity(
            errorAttributionCandidateId = detailedIdentity(
                "error-attribution",
                command.commandId,
                candidate.candidateOrdinal,
            ),
            organizationCommandId = command.commandId,
            candidateOrdinal = candidate.candidateOrdinal,
            problemId = command.problemId,
            problemRevisionId = command.problemRevisionId,
            practiceUnitId = command.practiceUnitId,
            sourceCommitReceiptCommandId = sourceCommitReceiptId,
            resolutionStatus = candidate.resolutionStatus,
            solutionStepId = stepId,
            knowledgeNodeId = candidate.knowledgeNodeId,
            knowledgeReferenceId = candidate.knowledgeReferenceId,
            rationaleMarkdown = candidate.rationaleMarkdown,
            confidence = candidate.confidence,
            modelVersion = candidate.modelVersion,
            createdAtEpochMillis = command.acceptedAtEpochMillis,
        )
    }
    val errorEvidence = command.errorAttributionCandidates.flatMap { candidate ->
        val candidateId = detailedIdentity(
            "error-attribution",
            command.commandId,
            candidate.candidateOrdinal,
        )
        candidate.evidence.mapIndexed { evidenceOrdinal, evidence ->
            require(evidence.sourceAssetId in sourceAssetIds) {
                "Error attribution references an asset outside its import occurrence"
            }
            require(sourceAssetByBlock[evidence.blockId] == evidence.sourceAssetId) {
                "Error attribution evidence does not match the captured block provenance"
            }
            ProblemErrorCandidateEvidenceEntity(
                errorAttributionCandidateId = candidateId,
                evidenceOrdinal = evidenceOrdinal,
                blockId = evidence.blockId,
                sourceAssetId = evidence.sourceAssetId,
                evidenceKind = evidence.evidenceKind,
            )
        }
    }
    return DetailedOrganizationPersistence(
        steps = steps,
        stepKnowledgeBindings = stepKnowledgeBindings,
        errorCandidates = errorCandidates,
        errorEvidence = errorEvidence,
    )
}

private fun detailedIdentity(prefix: String, commandId: String, ordinal: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$prefix\u001F$commandId\u001F$ordinal".toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$prefix:${digest.take(40)}"
}

private val SHA_256_HEX = Regex("^[0-9a-f]{64}$")
private val ERROR_EVIDENCE_KIND = Regex("^[A-Z][A-Z0-9_]{0,47}$")
private val SUBJECTS = SubjectKind.entries.mapTo(hashSetOf()) { it.name }
private val ORGANIZATION_CLASSIFICATION_DIMENSIONS =
    PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS.mapTo(hashSetOf()) { it.name }
private val ORGANIZATION_ACCEPTANCE_SOURCES = setOf(
    BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED.name,
    BindingAcceptanceSource.USER_CORRECTED.name,
)
private val USER_OWNED_ORGANIZATION_SOURCES = setOf(
    BindingAcceptanceSource.USER_CORRECTED.name,
    BindingAcceptanceSource.USER_CONFIRMED.name,
)
private val USER_CONFIRMABLE_RELATION_TYPES =
    PROBLEM_ORGANIZATION_RELATION_KINDS.mapTo(hashSetOf()) { it.name }

private fun validateCommand(command: ConfirmProblemOrganizationCommand) {
    require(command.commandId.isNotBlank()) { "commandId must not be blank" }
    require(SHA_256_HEX.matches(command.payloadFingerprint)) {
        "payloadFingerprint must be a lowercase SHA-256 hex string"
    }
    require(command.problemId.isNotBlank()) { "problemId must not be blank" }
    require(command.problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
    require(command.practiceUnitId.isNotBlank()) { "practiceUnitId must not be blank" }
    require(command.acceptedAtEpochMillis > 0) { "acceptedAtEpochMillis must be positive" }
    require(command.planSchemaVersion in 1..3) { "Unsupported organization plan schema" }
    require(command.knowledgeNodes.size <= 24) { "At most 24 knowledge nodes may be accepted" }
    require(command.knowledgeBindings.size <= 24) { "At most 24 knowledge bindings may be accepted" }
    require(command.classifications.size in 1..32) { "Between 1 and 32 classifications are required" }
    require(command.relations.size <= 8) { "At most 8 relations may be accepted" }
    require(command.relationIdsToRemove.size <= 64) { "At most 64 relations may be removed" }
    require(!command.replaceRelations || command.relationIdsToRemove.isEmpty()) {
        "Full replacement and exact relation removal cannot be combined"
    }
    require(command.relationIdsToRemove.all { it.isNotBlank() && it.length <= 128 }) {
        "Every removed relation id must be bounded and non-blank"
    }
    require(command.solutionSteps.size <= 16) { "At most 16 solution steps may be persisted" }
    require(command.errorAttributionCandidates.size <= 16) {
        "At most 16 error-attribution candidates may be persisted"
    }
    require(command.solutionSteps.map { it.stepOrdinal }.distinct().size == command.solutionSteps.size)
    require(command.solutionSteps.all { step ->
        step.stepOrdinal > 0 &&
            step.summaryMarkdown.isNotBlank() &&
            step.summaryMarkdown.length <= 500 &&
            step.knowledgeReferences.isNotEmpty() &&
            step.knowledgeReferences.size <= 8 &&
            step.knowledgeReferences.map { it.knowledgeReferenceId }.distinct().size ==
            step.knowledgeReferences.size &&
            step.knowledgeReferences.all { reference ->
                reference.knowledgeReferenceId.isNotBlank() &&
                    reference.knowledgeReferenceId.length <= 64 &&
                    reference.knowledgeNodeId.isNotBlank() &&
                    reference.knowledgeNodeId.length <= 256
            }
    }) { "Every persisted solution step must have bounded knowledge references" }
    val boundKnowledgeNodeIds = command.knowledgeBindings
        .mapTo(hashSetOf()) { it.knowledgeNodeId }
    require(command.solutionSteps.all { step ->
        step.knowledgeReferences.all { it.knowledgeNodeId in boundKnowledgeNodeIds }
    }) { "Solution steps can only reference accepted knowledge bindings" }
    require(
        command.errorAttributionCandidates.map { it.candidateOrdinal }.distinct().size ==
            command.errorAttributionCandidates.size,
    )
    require(command.errorAttributionCandidates.all { candidate ->
            candidate.candidateOrdinal >= 0 &&
            candidate.rationaleMarkdown.isNotBlank() &&
            candidate.rationaleMarkdown.length <= ProblemErrorAttributionCandidate.MAX_RATIONALE_CHARS &&
            candidate.confidence.isFinite() &&
            candidate.confidence in 0.0..1.0 &&
            candidate.modelVersion.isNotBlank() &&
            candidate.modelVersion.length <= 128 &&
            candidate.evidence.size <= 8 &&
            candidate.evidence.all { evidence ->
                evidence.blockId.isNotBlank() &&
                    evidence.blockId.length <= 256 &&
                    evidence.sourceAssetId.isNotBlank() &&
                    evidence.sourceAssetId.length <= 256 &&
                    ERROR_EVIDENCE_KIND.matches(evidence.evidenceKind)
            }
    }) { "Error-attribution candidates must stay within persistence budgets" }
    if (command.planSchemaVersion < 3) {
        require(
            command.solutionSteps.isEmpty() &&
                command.errorAttributionCandidates.isEmpty() &&
                command.sourceCommitReceiptCommandId == null
        ) { "Legacy organization commands cannot persist v3 attribution facts" }
    } else {
        require(!command.sourceCommitReceiptCommandId.isNullOrBlank()) {
            "V3 organization requires its source commit occurrence"
        }
    }
    require(command.classifications.map { it.bindingId }.distinct().size == command.classifications.size)
    require(command.classifications.mapTo(hashSetOf()) { it.acceptanceSource }.size == 1) {
        "Every classification in one command must share an acceptance authority"
    }
    require(
        command.knowledgeBindings.all { binding ->
            binding.sourceType == command.classifications.first().acceptanceSource
        },
    ) { "Knowledge bindings and classifications must share an acceptance authority" }
    require(command.relations.map { it.relationId }.distinct().size == command.relations.size)
    require(command.knowledgeNodes.map { it.knowledgeNodeId }.distinct().size == command.knowledgeNodes.size)
    require(command.knowledgeBindings.map { it.bindingId }.distinct().size == command.knowledgeBindings.size)
    require(command.classifications.all {
        it.bindingId.isNotBlank() &&
            it.problemId == command.problemId &&
            it.basisRevisionId == command.problemRevisionId &&
            it.dimension in ORGANIZATION_CLASSIFICATION_DIMENSIONS &&
            it.labelId.isNotBlank() &&
            it.displayName.isNotBlank() &&
            it.taxonomyVersion.isNotBlank() &&
            it.acceptanceSource in ORGANIZATION_ACCEPTANCE_SOURCES &&
            it.acceptedAtEpochMillis == command.acceptedAtEpochMillis
    }) { "Every classification must target the confirmed problem revision" }
    require(command.classifications.any { it.dimension == ClassificationDimension.KNOWLEDGE.name }) {
        "At least one knowledge classification is required"
    }
    require(command.classifications.any { it.dimension == ClassificationDimension.CHAPTER.name }) {
        "At least one chapter classification is required"
    }
    require(command.knowledgeBindings.all {
        it.bindingId.isNotBlank() &&
            it.practiceUnitId == command.practiceUnitId &&
            it.knowledgeNodeId.isNotBlank() &&
            it.basisRevisionId == command.problemRevisionId &&
            it.strength.isFinite() &&
            it.strength in 0.0..1.0 &&
            it.sourceType in ORGANIZATION_ACCEPTANCE_SOURCES &&
            it.taxonomyVersion.isNotBlank() &&
            it.acceptedAtEpochMillis == command.acceptedAtEpochMillis
    }) { "Every knowledge binding must target the confirmed practice unit revision" }
    require(command.knowledgeNodes.all {
        it.knowledgeNodeId.isNotBlank() &&
            it.stableCode.isNotBlank() &&
            it.subject in SUBJECTS &&
            it.displayName.isNotBlank() &&
            it.taxonomyVersion.isNotBlank() &&
            it.createdAtEpochMillis == command.acceptedAtEpochMillis
    }) { "Every accepted knowledge node must be complete and current" }
    val knowledgeLabels = command.classifications
        .filter { it.dimension == ClassificationDimension.KNOWLEDGE.name }
        .mapTo(linkedSetOf()) { it.labelId }
    require(command.knowledgeNodes.mapTo(linkedSetOf()) { it.stableCode } == knowledgeLabels) {
        "Visible topic nodes must exactly match the accepted knowledge classifications"
    }
    val visibleTopicNodeIds = command.knowledgeNodes.mapTo(linkedSetOf()) { it.knowledgeNodeId }
    val boundKnowledgeNodes = command.knowledgeBindings.mapTo(linkedSetOf()) { it.knowledgeNodeId }
    require(boundKnowledgeNodes.isNotEmpty()) {
        "At least one topic or grounded atomic node must bind to the confirmed practice unit"
    }
    require(
        boundKnowledgeNodes == visibleTopicNodeIds ||
            boundKnowledgeNodes.intersect(visibleTopicNodeIds).isEmpty(),
    ) { "A command must bind either visible topics or grounded atomic nodes, not a mixture" }
    require(command.relations.all {
        it.relationId.isNotBlank() &&
            it.sourceProblemId == command.problemId &&
            it.sourceBasisRevisionId == command.problemRevisionId &&
            it.targetProblemId.isNotBlank() &&
            it.targetProblemId != command.problemId &&
            it.targetBasisRevisionId.isNotBlank() &&
            it.relationType in USER_CONFIRMABLE_RELATION_TYPES &&
            it.status == StudyDbValue.RelationStatus.ACTIVE &&
            it.confidence.isFinite() &&
            it.confidence in 0.0..1.0 &&
            it.createdAtEpochMillis == command.acceptedAtEpochMillis &&
            it.updatedAtEpochMillis == command.acceptedAtEpochMillis
    }) { "Every relation must originate from the confirmed problem revision" }
}

private fun KnowledgeNodeEntity?.sameAcceptedFact(other: KnowledgeNodeEntity): Boolean =
    this != null && copy(
        displayName = other.displayName,
        taxonomyVersion = other.taxonomyVersion,
        createdAtEpochMillis = other.createdAtEpochMillis,
    ) == other

private fun PracticeUnitKnowledgeBindingEntity?.sameAcceptedFact(
    other: PracticeUnitKnowledgeBindingEntity,
): Boolean = this != null && copy(
    bindingId = other.bindingId,
    acceptedAtEpochMillis = other.acceptedAtEpochMillis,
) == other

private fun ProblemClassificationBindingEntity?.sameAcceptedFact(
    other: ProblemClassificationBindingEntity,
): Boolean = this != null && copy(acceptedAtEpochMillis = other.acceptedAtEpochMillis) == other

private fun ProblemRelationEntity?.sameAcceptedFact(other: ProblemRelationEntity): Boolean =
    this != null && copy(
        confidence = other.confidence,
        createdAtEpochMillis = other.createdAtEpochMillis,
        updatedAtEpochMillis = other.updatedAtEpochMillis,
    ) == other

private fun KnowledgeNodeSeedRecord.toOrganizationEntity() = KnowledgeNodeEntity(
    knowledgeNodeId = knowledgeNodeId,
    stableCode = stableCode,
    subject = subject,
    displayName = displayName,
    canonicalName = canonicalName,
    nodeKind = nodeKind,
    granularity = granularity,
    aliasesText = aliases.sorted().joinToString("\u001F"),
    boundaryMarkdown = boundaryMarkdown,
    verificationStatus = verificationStatus,
    parentKnowledgeNodeId = parentKnowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun KnowledgeBindingSeedRecord.toOrganizationEntity() =
    PracticeUnitKnowledgeBindingEntity(
        bindingId,
        practiceUnitId,
        knowledgeNodeId,
        basisRevisionId,
        strength,
        sourceType,
        taxonomyVersion,
        acceptedAtEpochMillis,
    )

private fun ProblemClassificationBindingRecord.toOrganizationEntity() =
    ProblemClassificationBindingEntity(
        bindingId,
        problemId,
        basisRevisionId,
        dimension,
        labelId,
        displayName,
        taxonomyVersion,
        acceptanceSource,
        acceptedAtEpochMillis,
    )

private fun ProblemRelationSeedRecord.toOrganizationEntity() = ProblemRelationEntity(
    relationId,
    sourceProblemId,
    targetProblemId,
    relationType,
    status,
    sourceBasisRevisionId,
    targetBasisRevisionId,
    confidence,
    createdAtEpochMillis,
    updatedAtEpochMillis,
)

private fun ProblemClassificationBindingEntity.toRecord() = ProblemClassificationBindingRecord(
    bindingId,
    problemId,
    basisRevisionId,
    dimension,
    labelId,
    displayName,
    taxonomyVersion,
    acceptanceSource,
    acceptedAtEpochMillis,
)

private fun ProblemRelationEntity.toRecord() = ProblemRelationSeedRecord(
    relationId,
    sourceProblemId,
    targetProblemId,
    relationType,
    status,
    sourceBasisRevisionId,
    targetBasisRevisionId,
    confidence,
    createdAtEpochMillis,
    updatedAtEpochMillis,
)

private fun ConfirmProblemOrganizationCommand.toReceiptEntity() =
    ProblemOrganizationReceiptEntity(
        commandId,
        payloadFingerprint,
        problemId,
        problemRevisionId,
        practiceUnitId,
        classifications.size,
        relations.size,
        acceptedAtEpochMillis,
    )

private fun ProblemOrganizationReceiptEntity.toRecord() = ProblemOrganizationReceiptRecord(
    commandId,
    payloadFingerprint,
    problemId,
    problemRevisionId,
    practiceUnitId,
    classificationCount,
    relationCount,
    acceptedAtEpochMillis,
)
