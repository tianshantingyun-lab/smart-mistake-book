package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSourceBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Installs reviewed curriculum maps and optional teaching support.
 *
 * Teaching support may contain methods, explanations, and worked examples. It remains physically
 * separate from practice units and therefore cannot become a question bank or review item.
 */
object BundledKnowledgeBaseInstaller {
    private val installMutex = Mutex()

    suspend fun install(database: StudyDatabasePort) = installMutex.withLock {
        BundledKnowledgePackResources.load().forEach { pack ->
            pack.validate()
            installPack(database, pack)
        }
    }

    private suspend fun installPack(database: StudyDatabasePort, pack: KnowledgeBasePack) {
        val expectedById = pack.nodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        val existingById = database.readKnowledgeNodesByIds(expectedById.keys)
            .associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        if (existingById.isEmpty()) {
            database.importKnowledgeBase(pack.sources, pack.nodes, pack.bindings)
        } else {
            val expectedSources = pack.sources.associateBy(KnowledgeSourceSeedRecord::sourceId)
            val existingSources = database.readKnowledgeSourcesByIds(expectedSources.keys)
                .associateBy(KnowledgeSourceSeedRecord::sourceId)
            val expectedBindings = pack.bindings.associateBy(KnowledgeNodeSourceBindingSeedRecord::key)
            val existingBindings = database.readKnowledgeNodeSourceBindings(expectedById.keys)
                .associateBy(KnowledgeNodeSourceBindingSeedRecord::key)
            require(
                existingById == expectedById &&
                    existingSources == expectedSources &&
                    existingBindings == expectedBindings,
            ) {
                "Bundled knowledge pack ${pack.packId} is incomplete or conflicts with local data"
            }
        }
        database.importKnowledgeNodeRelations(pack.relations)
        installTeachingMaterials(database, pack)
    }

    private suspend fun installTeachingMaterials(
        database: StudyDatabasePort,
        pack: KnowledgeBasePack,
    ) {
        if (pack.teachingMaterials.isEmpty()) return
        val expectedMaterials = pack.teachingMaterials.associateBy(
            KnowledgeTeachingMaterialRecord::materialId,
        )
        val existingMaterials = database.readKnowledgeTeachingMaterialsByIds(expectedMaterials.keys)
            .associateBy(KnowledgeTeachingMaterialRecord::materialId)
        if (existingMaterials.isEmpty()) {
            // 每次导入受 DB 契约单批上限约束（2048 条材料），bundled sidecar 总量超过它，
            // 因此按上限分批，每批携带各自材料、绑定与涉及的来源。
            val maxPerBatch = 2_000
            val sourceById = pack.teachingSources.associateBy(KnowledgeSourceSeedRecord::sourceId)
            pack.teachingMaterials.chunked(maxPerBatch).forEach { batch ->
                val batchIds = batch.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialId)
                val batchBindings = pack.teachingMaterialBindings.filter {
                    it.materialId in batchIds
                }
                val batchSourceIds = batch.mapTo(linkedSetOf()) { it.sourceId }
                database.importKnowledgeTeachingMaterials(
                    materials = batch,
                    bindings = batchBindings,
                    sources = batchSourceIds.mapNotNullTo(ArrayList()) { sourceById[it] },
                )
            }
            return
        }
        val expectedSources = pack.teachingSources.associateBy(
            KnowledgeSourceSeedRecord::sourceId,
        )
        val existingSources = database.readKnowledgeSourcesByIds(expectedSources.keys)
            .associateBy(KnowledgeSourceSeedRecord::sourceId)
        val expectedBindings = pack.teachingMaterialBindings.associateBy(
            KnowledgeTeachingMaterialNodeBindingRecord::key,
        )
        val existingBindings = database.readKnowledgeTeachingMaterialNodeBindings(
            expectedMaterials.keys,
        ).associateBy(KnowledgeTeachingMaterialNodeBindingRecord::key)
        require(
            existingMaterials == expectedMaterials &&
                existingSources == expectedSources &&
                existingBindings == expectedBindings,
        ) {
            "Bundled knowledge pack ${pack.packId} has incomplete or conflicting teaching support"
        }
    }
}

private fun KnowledgeNodeSourceBindingSeedRecord.key() =
    Triple(knowledgeNodeId, sourceId, sourceLocator)

private fun KnowledgeTeachingMaterialNodeBindingRecord.key() =
    materialId to knowledgeNodeId
