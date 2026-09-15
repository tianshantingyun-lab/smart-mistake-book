package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.port.KnowledgeReadPort
import com.tingyun.smartmistakebook.core.database.port.PracticeUnitKnowledgeBindingRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Placeholder display name for pseudo-KC fallback nodes (spec §3.4). */
internal const val PSEUDO_NODE_DISPLAY_NAME = "未归类知识点"

/** Taxonomy marker carried by pseudo knowledge nodes themselves. */
internal const val PSEUDO_TAXONOMY_VERSION = "pseudo-node-v1"

private const val KNOWLEDGE_NODE_QUERY_CHUNK_SIZE = 400

/**
 * Knowledge-base import/validation, grounding requests, and the pseudo-KC
 * fallback binding (spec §3.4).
 */
internal class RoomKnowledgeBaseStore(
    private val database: StudyDatabase,
    private val reviewStore: RoomKnowledgeResearchReviewStore,
) {
    suspend fun ensurePseudoKnowledgeBinding(
        practiceUnitId: String,
        problemRevisionId: String,
        taxonomyVersion: String,
        subject: String,
        acceptedAtEpochMillis: Long,
    ): PracticeUnitKnowledgeBindingRecord? {
        require(subject.isNotBlank()) { "subject must not be blank" }
        val knowledgeNodeId = "pseudo:${subject.uppercase()}"
        val bindingId = "pseudo-binding:$practiceUnitId:$problemRevisionId:$taxonomyVersion:$knowledgeNodeId"
        val organizationDao = database.problemOrganizationDao()
        // The pseudo node is a placeholder KC (spec §3.4): it exists so the
        // knowledge-node foreign keys on mastery state are satisfied; it is
        // never human-verified, hence MODEL_CANDIDATE.
        if (organizationDao.readKnowledgeNode(knowledgeNodeId) == null) {
            organizationDao.insertKnowledgeNodes(
                listOf(
                    KnowledgeNodeEntity(
                        knowledgeNodeId = knowledgeNodeId,
                        stableCode = knowledgeNodeId,
                        subject = subject,
                        displayName = PSEUDO_NODE_DISPLAY_NAME,
                        canonicalName = "",
                        nodeKind = "TOPIC",
                        granularity = "TOPIC",
                        aliasesText = "",
                        boundaryMarkdown = null,
                        verificationStatus = "MODEL_CANDIDATE",
                        parentKnowledgeNodeId = null,
                        taxonomyVersion = PSEUDO_TAXONOMY_VERSION,
                        createdAtEpochMillis = acceptedAtEpochMillis,
                    ),
                ),
            )
        }
        val binding = PracticeUnitKnowledgeBindingEntity(
            bindingId = bindingId,
            practiceUnitId = practiceUnitId,
            knowledgeNodeId = knowledgeNodeId,
            basisRevisionId = problemRevisionId,
            strength = 1.0,
            sourceType = "PSEUDO_FALLBACK",
            taxonomyVersion = taxonomyVersion,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
        )
        organizationDao.insertKnowledgeBindings(listOf(binding))
        val persisted = organizationDao.readKnowledgeBinding(bindingId) ?: return null
        return PracticeUnitKnowledgeBindingRecord(
            bindingId = persisted.bindingId,
            practiceUnitId = persisted.practiceUnitId,
            knowledgeNodeId = persisted.knowledgeNodeId,
            basisRevisionId = persisted.basisRevisionId,
            taxonomyVersion = persisted.taxonomyVersion,
            acceptedAtEpochMillis = persisted.acceptedAtEpochMillis,
        )
    }

    suspend fun readSubjectKnowledgeNodes(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..256) { "knowledge-node limit is outside the supported range" }
        return database.problemOrganizationDao().readSubjectKnowledgeNodes(subject, limit).map {
            it.toSeedRecord()
        }
    }

    suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..MAX_KNOWLEDGE_RECALL_CANDIDATES) {
            "knowledge recall candidate limit is outside the supported range"
        }
        require(searchFeatures.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES) {
            "knowledge recall query has too many search features"
        }
        if (searchFeatures.isEmpty()) {
            return readSubjectKnowledgeNodes(subject, limit.coerceAtMost(256))
        }
        ensureKnowledgeSearchIndex(subject)
        val dao = database.problemOrganizationDao()
        val matched = dao.searchSubjectKnowledgeRecallCandidates(subject, searchFeatures, limit)
        val matchedIds = matched.mapTo(hashSetOf(), KnowledgeNodeEntity::knowledgeNodeId)
        val parents = dao.readKnowledgeNodesByIds(
            matched.mapNotNullTo(hashSetOf(), KnowledgeNodeEntity::parentKnowledgeNodeId) - matchedIds,
        )
        return (parents + matched)
            .distinctBy(KnowledgeNodeEntity::knowledgeNodeId)
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    private suspend fun ensureKnowledgeSearchIndex(subject: String) {
        val dao = database.problemOrganizationDao()
        val reviewedCount = dao.countReviewedKnowledgeNodesBySubject(subject)
        if (reviewedCount == 0 || dao.countIndexedKnowledgeNodesBySubject(subject) >= reviewedCount) return
        database.withWriteTransaction {
            val missingCheckCount = dao.countReviewedKnowledgeNodesBySubject(subject)
            if (dao.countIndexedKnowledgeNodesBySubject(subject) < missingCheckCount) {
                val nodes = dao.readSubjectKnowledgeRecallCandidates(subject, missingCheckCount)
                dao.insertKnowledgeSearchFeatures(nodes.flatMap(KnowledgeNodeEntity::toSearchFeatures))
            }
        }
    }

    suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readKnowledgeNodesByIds(chunk.toSet())
            }
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readKnowledgeSourcesByIds(chunk.toSet())
            }
            .map(KnowledgeSourceEntity::toSeedRecord)
    }

    suspend fun readSubjectKnowledgeNodeRelations(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeRelationRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..16_384) { "knowledge relation limit is outside the supported range" }
        return database.knowledgeNodeRelationDao().readBySubject(subject, limit)
            .map(KnowledgeNodeRelationEntity::toRecord)
    }

    suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(dependentKnowledgeNodeIds.size <= 256) {
            "too many dependent knowledge nodes were requested"
        }
        if (dependentKnowledgeNodeIds.isEmpty()) return emptyList()
        return database.knowledgeNodeRelationDao()
            .readForDependents(subject, dependentKnowledgeNodeIds)
            .map(KnowledgeNodeRelationEntity::toRecord)
    }

    suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(knowledgeNodeIds.size <= 256) {
            "too many knowledge nodes were requested for teaching context"
        }
        require(limit in 1..KnowledgeReadPort.MAX_TEACHING_MATERIAL_CANDIDATES) {
            "teaching-material limit is outside the supported range"
        }
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        return database.knowledgeTeachingMaterialDao()
            .readForKnowledgeNodes(subject, knowledgeNodeIds, limit)
            .map(KnowledgeTeachingMaterialEntity::toRecord)
    }

    suspend fun readKnowledgeTeachingMaterialsByIds(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialRecord> {
        if (materialIds.isEmpty()) return emptyList()
        return materialIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                database.knowledgeTeachingMaterialDao().readByIds(ids.toSet())
            }
            .map(KnowledgeTeachingMaterialEntity::toRecord)
    }

    suspend fun readKnowledgeTeachingMaterialNodeBindings(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingRecord> {
        if (materialIds.isEmpty()) return emptyList()
        return materialIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                database.knowledgeTeachingMaterialDao().readBindingsForMaterials(ids.toSet())
            }
            .map(KnowledgeTeachingMaterialNodeBindingEntity::toRecord)
    }

    suspend fun importKnowledgeNodeRelations(relations: List<KnowledgeNodeRelationRecord>) {
        if (relations.isEmpty()) return
        database.withWriteTransaction {
            val nodeIds = relations.flatMapTo(mutableSetOf()) {
                listOf(it.prerequisiteKnowledgeNodeId, it.dependentKnowledgeNodeId)
            }
            val sourceIds = relations.mapTo(mutableSetOf(), KnowledgeNodeRelationRecord::sourceId)
            val subjects = relations.mapTo(mutableSetOf(), KnowledgeNodeRelationRecord::subject)
            val existingRelations = subjects.flatMap { subject ->
                require(database.knowledgeNodeRelationDao().countBySubject(subject) <= 16_384) {
                    "knowledge relation graph exceeds the supported validation budget"
                }
                database.knowledgeNodeRelationDao().readBySubject(subject, 16_384)
            }.map(KnowledgeNodeRelationEntity::toRecord)
            KnowledgeNodeRelationContract.validate(
                incoming = relations,
                nodes = readKnowledgeNodesByIds(nodeIds),
                sources = readKnowledgeSourcesByIds(sourceIds),
                existing = existingRelations,
            )
            database.knowledgeNodeRelationDao().importAll(
                relations.map(KnowledgeNodeRelationRecord::toEntity),
            )
        }
    }

    suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    ) {
        if (materials.isEmpty() && bindings.isEmpty() && sources.isEmpty()) return
        database.withWriteTransaction {
            KnowledgeBaseImportContract.validateSourcesOnly(sources)
            val nodes = readKnowledgeNodesByIds(
                bindings.mapTo(hashSetOf(), KnowledgeTeachingMaterialNodeBindingRecord::knowledgeNodeId),
            )
            val requiredSourceIds = materials.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::sourceId)
                .apply {
                    addAll(sources.map(KnowledgeSourceSeedRecord::sourceId))
                }
            val existingSources = readKnowledgeSourcesByIds(requiredSourceIds)
            val existingSourcesById = existingSources.associateBy(KnowledgeSourceSeedRecord::sourceId)
            sources.forEach { source ->
                existingSourcesById[source.sourceId]?.let { existing ->
                    if (existing != source) {
                        throw ImmutablePayloadConflictException(
                            entityType = "knowledgeSource",
                            entityId = source.sourceId,
                        )
                    }
                }
            }
            val sourcesToInsert = sources.filterNot { source ->
                existingSourcesById.containsKey(source.sourceId)
            }
            val validatedSources = (
                existingSources + sourcesToInsert
                ).distinctBy(KnowledgeSourceSeedRecord::sourceId)
            KnowledgeTeachingMaterialContract.validate(
                materials = materials,
                bindings = bindings,
                nodes = nodes,
                sources = validatedSources,
            )
            if (sourcesToInsert.isNotEmpty()) {
                database.problemOrganizationDao().insertKnowledgeSources(
                    sourcesToInsert.map(KnowledgeSourceSeedRecord::toEntity),
                )
            }
            database.knowledgeTeachingMaterialDao().importAll(
                materials = materials.map(KnowledgeTeachingMaterialRecord::toEntity),
                bindings = bindings.map(KnowledgeTeachingMaterialNodeBindingRecord::toEntity),
            )
        }
    }

    suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord> {
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        return knowledgeNodeIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readKnowledgeNodeSourceBindings(chunk.toSet())
            }
            .map(KnowledgeNodeSourceBindingEntity::toSeedRecord)
    }

    suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ) {
        val dependencies = readKnowledgeBaseDependencies(sources, nodes, bindings)
        KnowledgeBaseImportContract.validate(
            sources = sources,
            nodes = nodes,
            bindings = bindings,
            existingSources = dependencies.sources,
            existingParentNodes = dependencies.parentNodes,
        )
        database.problemOrganizationDao().importKnowledgeBase(
            sources = sources.map(KnowledgeSourceSeedRecord::toEntity),
            nodes = nodes.map(KnowledgeNodeSeedRecord::toEntity),
            bindings = bindings.map(KnowledgeNodeSourceBindingSeedRecord::toEntity),
            searchFeatures = nodes.flatMap(KnowledgeNodeSeedRecord::toSearchFeatures),
        )
    }

    suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = database.withWriteTransaction {
        applyReviewedKnowledgePackInTransaction(command)
    }

    suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = database.withWriteTransaction {
        val review = reviewStore.read(command.reviewBundleId)
            ?: throw DatabaseContractViolationException(
                "Knowledge research review bundle does not exist",
            )
        val packFingerprint = ApprovedKnowledgeResearchPackContract.validate(review, command)
        if (review.status == StudyDbValue.KnowledgeResearchReviewStatus.APPLIED) {
            if (
                review.appliedPackFingerprint != packFingerprint ||
                review.appliedAtEpochMillis != command.appliedAtEpochMillis
            ) {
                throw ImmutablePayloadConflictException(
                    entityType = "approved knowledge research pack",
                    entityId = command.reviewBundleId,
                )
            }
            return@withWriteTransaction readAppliedKnowledgeResearchResolutions(command)
        }
        val resolutions = applyReviewedKnowledgePackInTransaction(command.pack)
        reviewStore.markApplied(
            bundleId = command.reviewBundleId,
            packFingerprint = packFingerprint,
            appliedAtEpochMillis = command.appliedAtEpochMillis,
        )
        resolutions
    }

    private suspend fun readAppliedKnowledgeResearchResolutions(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> {
        val groundingKeys = command.pack.resolutions
            .mapTo(mutableSetOf(), ResolveKnowledgeGroundingCommand::groundingKey)
        val byGroundingKey = database.knowledgeGroundingDao().readResolutions(groundingKeys)
            .associateBy(KnowledgeGroundingResolutionEntity::groundingKey)
        return command.pack.resolutions.map { expected ->
            byGroundingKey[expected.groundingKey]?.toRecord()
                ?: throw DatabaseContractViolationException(
                    "Applied knowledge research resolution is missing",
                )
        }
    }

    private suspend fun applyReviewedKnowledgePackInTransaction(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> {
        val dependencies = readKnowledgeBaseDependencies(
            command.sources,
            command.nodes,
            command.bindings,
        )
        val resolutions = ReviewedKnowledgePackContract.validate(
            command = command,
            existingSources = dependencies.sources,
            existingParentNodes = dependencies.parentNodes,
        )
        val relationNodeIds = command.relations.flatMapTo(mutableSetOf()) {
            listOf(it.prerequisiteKnowledgeNodeId, it.dependentKnowledgeNodeId)
        }
        val relationSourceIds = command.relations.mapTo(
            mutableSetOf(),
            KnowledgeNodeRelationRecord::sourceId,
        )
        val relationSubjects = command.relations.mapTo(
            mutableSetOf(),
            KnowledgeNodeRelationRecord::subject,
        )
        KnowledgeNodeRelationContract.validate(
            incoming = command.relations,
            nodes = (command.nodes + readKnowledgeNodesByIds(
                relationNodeIds - command.nodes.mapTo(mutableSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId),
            )).distinctBy(KnowledgeNodeSeedRecord::knowledgeNodeId),
            sources = (command.sources + readKnowledgeSourcesByIds(
                relationSourceIds - command.sources.mapTo(mutableSetOf(), KnowledgeSourceSeedRecord::sourceId),
            )).distinctBy(KnowledgeSourceSeedRecord::sourceId),
            existing = relationSubjects.flatMap { subject ->
                require(database.knowledgeNodeRelationDao().countBySubject(subject) <= 16_384) {
                    "knowledge relation graph exceeds the supported validation budget"
                }
                database.knowledgeNodeRelationDao().readBySubject(subject, 16_384)
            }.map(KnowledgeNodeRelationEntity::toRecord),
        )
        return database.knowledgeGroundingDao().applyReviewedPack(
            sources = command.sources.map(KnowledgeSourceSeedRecord::toEntity),
            nodes = command.nodes.map(KnowledgeNodeSeedRecord::toEntity),
            sourceBindings = command.bindings.map(KnowledgeNodeSourceBindingSeedRecord::toEntity),
            relations = command.relations.map(KnowledgeNodeRelationRecord::toEntity),
            searchFeatures = command.nodes.flatMap(KnowledgeNodeSeedRecord::toSearchFeatures),
            resolutions = resolutions,
        ).map(KnowledgeGroundingResolutionEntity::toRecord)
    }

    private suspend fun readKnowledgeBaseDependencies(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ): KnowledgeBaseDependencies {
        val newNodeIds = nodes.mapTo(mutableSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        val newSourceIds = sources.mapTo(mutableSetOf(), KnowledgeSourceSeedRecord::sourceId)
        return KnowledgeBaseDependencies(
            parentNodes = readKnowledgeNodesByIds(
                nodes.mapNotNullTo(
                    mutableSetOf(),
                    KnowledgeNodeSeedRecord::parentKnowledgeNodeId,
                ) - newNodeIds,
            ),
            sources = readKnowledgeSourcesByIds(
                bindings.mapTo(
                    mutableSetOf(),
                    KnowledgeNodeSourceBindingSeedRecord::sourceId,
                ) - newSourceIds,
            ),
        )
    }

    fun observePendingKnowledgeGroundingRequests(
        limit: Int,
    ): Flow<List<KnowledgeGroundingRequestRecord>> {
        require(limit in 1..512) { "Knowledge-grounding queue limit must be in 1..512" }
        return database.knowledgeGroundingDao().observePending(limit).map { requests ->
            requests.map(KnowledgeGroundingRequestEntity::toRecord)
        }
    }

    fun observePendingKnowledgeGroundingSummaries(
        limit: Int,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> {
        require(limit in 1..256) { "Knowledge-grounding summary limit must be in 1..256" }
        return database.knowledgeGroundingDao().observePendingSummaries(limit).map { summaries ->
            summaries.map(KnowledgeGroundingSummaryRow::toRecord)
        }
    }

    fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        database.problemOrganizationDao().observeReviewedKnowledgeCoverage().map { rows ->
            rows.map(ReviewedKnowledgeCoverageRow::toRecord)
        }

    suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    ) {
        KnowledgeGroundingRequestContract.validate(requests)
        database.knowledgeGroundingDao().recordAll(
            requests.map(KnowledgeGroundingRequestRecord::toEntity),
        )
    }

    suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord {
        val resolutionId = KnowledgeGroundingResolutionContract.validate(command)
        return database.knowledgeGroundingDao()
            .resolve(command, resolutionId)
            .toRecord()
    }

    suspend fun readKnowledgeGroundingResolution(
        groundingKey: String,
    ): KnowledgeGroundingResolutionRecord? =
        database.knowledgeGroundingDao().readResolution(groundingKey)?.toRecord()
}

private data class KnowledgeBaseDependencies(
    val sources: List<KnowledgeSourceSeedRecord>,
    val parentNodes: List<KnowledgeNodeSeedRecord>,
)

/** Seed-fixture writes also map knowledge nodes/bindings, hence internal. */
internal fun KnowledgeNodeSeedRecord.toEntity() = KnowledgeNodeEntity(
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

private fun KnowledgeNodeSeedRecord.toSearchFeatures(): List<KnowledgeSearchFeatureEntity> =
    KnowledgeSearchFeatureExtractor.fromNode(this).map { feature ->
        KnowledgeSearchFeatureEntity(
            subject = subject,
            searchFeature = feature,
            knowledgeNodeId = knowledgeNodeId,
        )
    }

private fun KnowledgeNodeEntity.toSearchFeatures(): List<KnowledgeSearchFeatureEntity> =
    toSeedRecord().toSearchFeatures()

private fun KnowledgeNodeEntity.toSeedRecord() = KnowledgeNodeSeedRecord(
    knowledgeNodeId = knowledgeNodeId,
    stableCode = stableCode,
    subject = subject,
    displayName = displayName,
    parentKnowledgeNodeId = parentKnowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    createdAtEpochMillis = createdAtEpochMillis,
    canonicalName = canonicalName,
    nodeKind = nodeKind,
    granularity = granularity,
    aliases = aliasesText.split("\u001F").filter(String::isNotBlank).toSet(),
    boundaryMarkdown = boundaryMarkdown,
    verificationStatus = verificationStatus,
)

private fun KnowledgeSourceSeedRecord.toEntity() = KnowledgeSourceEntity(
    sourceId = sourceId,
    subject = subject,
    sourceType = sourceType,
    title = title,
    publisher = publisher,
    edition = edition,
    sourceUri = sourceUri,
    licenseStatus = licenseStatus,
    contentFingerprint = contentFingerprint,
    importedAtEpochMillis = importedAtEpochMillis,
    contentUsePolicy = contentUsePolicy,
    licenseExpression = licenseExpression,
    licenseUri = licenseUri,
    attributionText = attributionText,
)

private fun KnowledgeSourceEntity.toSeedRecord() = KnowledgeSourceSeedRecord(
    sourceId = sourceId,
    subject = subject,
    sourceType = sourceType,
    title = title,
    publisher = publisher,
    edition = edition,
    sourceUri = sourceUri,
    licenseStatus = licenseStatus,
    contentFingerprint = contentFingerprint,
    importedAtEpochMillis = importedAtEpochMillis,
    contentUsePolicy = contentUsePolicy,
    licenseExpression = licenseExpression,
    licenseUri = licenseUri,
    attributionText = attributionText,
)

private fun KnowledgeNodeRelationRecord.toEntity() = KnowledgeNodeRelationEntity(
    relationId = relationId,
    subject = subject,
    prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
    dependentKnowledgeNodeId = dependentKnowledgeNodeId,
    relationType = relationType,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeNodeRelationEntity.toRecord() = KnowledgeNodeRelationRecord(
    relationId = relationId,
    subject = subject,
    prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
    dependentKnowledgeNodeId = dependentKnowledgeNodeId,
    relationType = relationType,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeTeachingMaterialRecord.toEntity() = KnowledgeTeachingMaterialEntity(
    materialId = materialId,
    stableCode = stableCode,
    subject = subject,
    materialType = materialType,
    title = title,
    summaryMarkdown = summaryMarkdown,
    applicabilityMarkdown = applicabilityMarkdown,
    contentMarkdown = contentMarkdown,
    boundaryMarkdown = boundaryMarkdown,
    derivationKind = derivationKind,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    contentFingerprint = contentFingerprint,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeTeachingMaterialEntity.toRecord() = KnowledgeTeachingMaterialRecord(
    materialId = materialId,
    stableCode = stableCode,
    subject = subject,
    materialType = materialType,
    title = title,
    summaryMarkdown = summaryMarkdown,
    applicabilityMarkdown = applicabilityMarkdown,
    contentMarkdown = contentMarkdown,
    boundaryMarkdown = boundaryMarkdown,
    derivationKind = derivationKind,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    contentFingerprint = contentFingerprint,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeTeachingMaterialNodeBindingRecord.toEntity() =
    KnowledgeTeachingMaterialNodeBindingEntity(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = role,
    )

private fun KnowledgeTeachingMaterialNodeBindingEntity.toRecord() =
    KnowledgeTeachingMaterialNodeBindingRecord(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = role,
    )

private fun KnowledgeGroundingRequestRecord.toEntity() = KnowledgeGroundingRequestEntity(
    groundingRequestId = groundingRequestId,
    groundingKey = groundingKey,
    organizationRequestId = organizationRequestId,
    organizationRequestFingerprint = organizationRequestFingerprint,
    requestOrdinal = requestOrdinal,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    query = query,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    reasonMarkdown = reasonMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun KnowledgeGroundingRequestEntity.toRecord() = KnowledgeGroundingRequestRecord(
    groundingRequestId = groundingRequestId,
    groundingKey = groundingKey,
    organizationRequestId = organizationRequestId,
    organizationRequestFingerprint = organizationRequestFingerprint,
    requestOrdinal = requestOrdinal,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    query = query,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    reasonMarkdown = reasonMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun KnowledgeGroundingSummaryRow.toRecord() = KnowledgeGroundingSummaryRecord(
    groundingKey = groundingKey,
    subject = subject,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    query = query,
    relatedQuestionCount = relatedQuestionCount,
    firstObservedAtEpochMillis = firstObservedAtEpochMillis,
    lastObservedAtEpochMillis = lastObservedAtEpochMillis,
)

private fun KnowledgeGroundingResolutionEntity.toRecord() = KnowledgeGroundingResolutionRecord(
    resolutionId = resolutionId,
    groundingKey = groundingKey,
    subject = subject,
    knowledgeNodeId = knowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    resolvedOccurrenceCount = resolvedOccurrenceCount,
    linkedPracticeUnitCount = linkedPracticeUnitCount,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
)

private fun ReviewedKnowledgeCoverageRow.toRecord() = ReviewedKnowledgeCoverageRecord(
    subject = subject,
    topicCount = topicCount,
    atomicKnowledgeCount = atomicKnowledgeCount,
    reviewedSourceCount = reviewedSourceCount,
    latestReviewedAtEpochMillis = latestReviewedAtEpochMillis,
)

private fun KnowledgeNodeSourceBindingSeedRecord.toEntity() = KnowledgeNodeSourceBindingEntity(
    knowledgeNodeId = knowledgeNodeId,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    derivationNote = derivationNote,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeNodeSourceBindingEntity.toSeedRecord() =
    KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = knowledgeNodeId,
        sourceId = sourceId,
        sourceLocator = sourceLocator,
        derivationNote = derivationNote,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
    )

/** Seed-fixture writes also map knowledge bindings, hence internal. */
internal fun KnowledgeBindingSeedRecord.toEntity() = PracticeUnitKnowledgeBindingEntity(
    bindingId = bindingId,
    practiceUnitId = practiceUnitId,
    knowledgeNodeId = knowledgeNodeId,
    basisRevisionId = basisRevisionId,
    strength = strength,
    sourceType = sourceType,
    taxonomyVersion = taxonomyVersion,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
)
