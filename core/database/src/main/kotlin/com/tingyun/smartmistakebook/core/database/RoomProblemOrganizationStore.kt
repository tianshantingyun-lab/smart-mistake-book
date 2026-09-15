package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemClassificationBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_RELATION_KINDS
import com.tingyun.smartmistakebook.core.model.SubjectKind
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
    ): ConfirmProblemOrganizationResult {
        validateCommand(command)
        return database.withWriteTransaction {
            val dao = database.problemOrganizationDao()
            val expectedReceipt = command.toReceiptEntity()
            dao.readReceipt(command.commandId)?.let { existing ->
                if (existing != expectedReceipt) {
                    throw ImmutablePayloadConflictException(
                        "problem_organization_receipt",
                        command.commandId,
                    )
                }
                return@withWriteTransaction ConfirmProblemOrganizationResult(
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
            val incomingAcceptanceSource = command.classifications
                .mapTo(linkedSetOf()) { it.acceptanceSource }
                .single()
            if (
                incomingAcceptanceSource == BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED.name &&
                dao.readClassificationAcceptanceSources(command.problemId, command.problemRevisionId)
                    .any(USER_OWNED_ORGANIZATION_SOURCES::contains)
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
            // 全量替换的防御（fail-loud）：当前 CHAPTER/KNOWLEDGE 两维是唯一写入者；
            // 一旦既有行出现其他维度（未来的错因/标签等），拒绝而不是静默清掉。
            val existingDimensions = dao
                .readClassificationDimensions(command.problemId, command.problemRevisionId)
                .toSet()
            val incomingDimensions = command.classifications
                .mapTo(linkedSetOf()) { it.dimension }
            if ((existingDimensions - incomingDimensions).isNotEmpty()) {
                throw ImmutablePayloadConflictException(
                    "problem_classification_dimensions",
                    command.problemId,
                )
            }
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
                    if (rowId == -1L) {
                        if (!dao.readKnowledgeNode(entity.knowledgeNodeId).sameAcceptedFact(entity)) {
                            throw ImmutablePayloadConflictException(
                                "knowledge_node",
                                entity.knowledgeNodeId,
                            )
                        }
                        // The node already exists with the same fact; only its
                        // verification status may have been strengthened by this
                        // write (MODEL_CANDIDATE → USER_CONFIRMED).
                        if (
                            entity.verificationStatus ==
                            KnowledgeNodeVerificationStatus.USER_CONFIRMED.name
                        ) {
                            dao.promoteKnowledgeNodeToUserConfirmed(entity.knowledgeNodeId)
                        }
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
                return@withWriteTransaction ConfirmProblemOrganizationResult(
                    created = false,
                    receipt = winner.toRecord(),
                )
            }
            ConfirmProblemOrganizationResult(
                created = true,
                receipt = expectedReceipt.toRecord(),
            )
        }
    }
}

private val SHA_256_HEX = Regex("^[0-9a-f]{64}$")
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
    val boundKnowledgeNodeIds = command.knowledgeBindings.mapTo(linkedSetOf()) { it.knowledgeNodeId }
    require(boundKnowledgeNodeIds.isNotEmpty()) {
        "At least one topic or grounded atomic node must bind to the confirmed practice unit"
    }
    require(
        boundKnowledgeNodeIds == visibleTopicNodeIds ||
            boundKnowledgeNodeIds.intersect(visibleTopicNodeIds).isEmpty(),
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
        // Verification status is monotonic and upgraded explicitly by the
        // caller (promoteKnowledgeNodeToUserConfirmed), so a stronger status on
        // a later write is not a conflicting payload.
        verificationStatus = other.verificationStatus,
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
