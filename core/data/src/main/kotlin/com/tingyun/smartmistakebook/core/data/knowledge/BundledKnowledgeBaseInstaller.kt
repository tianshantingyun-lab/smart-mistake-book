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
        var existingMaterials = database.readKnowledgeTeachingMaterialsByIds(expectedMaterials.keys)
            .associateBy(KnowledgeTeachingMaterialRecord::materialId)
        if (existingMaterials.size < expectedMaterials.size) {
            // 「没装齐」才重装，而这个条件一次覆盖两种情形：全新安装（0 条）与**上一次进程在
            // 两个批次之间被杀**（0 < n < 全部）。第二种原先会直接落到下面的 `require` 上——
            // 于是此后每一次启动都抛同一句「incomplete or conflicting」，
            // **半装状态永久卡死**（审计 N-10）。
            //
            // 重跑全部批次而不是只补缺的那几批：判断"哪几批缺"要么把批次划分规则复制一份，
            // 要么先做完一次全表扫描；而恢复是罕见路径，整体重跑的代价可以接受。
            // 重跑本身是安全的——`importKnowledgeTeachingMaterials` 每批一个事务、插入用 IGNORE，
            // 且 DAO 插完会**逐行回读比对**（`KnowledgeTeachingMaterialDao.importAll`），
            // 所以已存在的行是 no-op，而载荷不同的行会立刻抛 `ImmutablePayloadConflictException`
            // （冲突就该失败，不该被"修"成一个谁也没写过的状态）。
            //
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
            // 装完**重读**：上面那一读只用来决定"要不要装"，而下面那句 `require` 判的是
            // "装齐了没有"。用陈旧读数会让刚补齐的这一次被自己判成"不完整"——
            // 那正是把 N-10 从"卡死"改成"卡死得更隐蔽"。
            existingMaterials = database.readKnowledgeTeachingMaterialsByIds(expectedMaterials.keys)
                .associateBy(KnowledgeTeachingMaterialRecord::materialId)
        }
        // 落到这里校验，而不是在导入分支里 `return`：于是全新安装那一路也**校验自己刚写的东西**。
        // 原先的分支结构让"导入完就返回"与"校验"互斥，导入出问题没有任何断言会说话。
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
