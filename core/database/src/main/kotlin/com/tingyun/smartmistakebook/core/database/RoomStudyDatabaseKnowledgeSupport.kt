package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity

internal class RoomStudyDatabaseKnowledgeSupport(
    private val database: StudyDatabase,
) {
    suspend fun ensureKnowledgeSearchIndex(subject: String) {
        val dao = database.legacyKnowledgeCatalogDao()
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
                database.legacyKnowledgeCatalogDao().readKnowledgeNodesByIds(chunk.toSet())
            }
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.legacyKnowledgeCatalogDao().readKnowledgeSourcesByIds(chunk.toSet())
            }
            .map(KnowledgeSourceEntity::toSeedRecord)
    }

    suspend fun readAppliedKnowledgeResearchResolutions(
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

    suspend fun applyReviewedKnowledgePackInTransaction(
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

    internal suspend fun readKnowledgeBaseDependencies(
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
}
