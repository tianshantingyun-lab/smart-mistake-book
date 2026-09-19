package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.entity.ContentInstallStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus

/**
 * **内容调和**：让库里的内容等于随包内容。
 *
 * 从 [RoomKnowledgeBaseStore] 分出来，因为它是一个独立关注点：那个 store 管的是"怎么读、
 * 怎么导入"，这里管的是"库里这份内容与包里那份差在哪、该怎么对齐"。混在一起会让一个通用
 * 存储类承担内容生命周期决策，也把它推到千行硬线上（拆分前 997 行）。
 *
 * 它消灭的失败：改前安装器只在"库里一行都没有"时导入，否则要求节点/来源/绑定/材料四样
 * 逐行完全相等——于是发布后任何一次内容改动都会让知识库整包停摆（KD-15：80 条材料的
 * 时间戳倒挂，把另外一万条一起挡在门外）。
 *
 * 四条不变量，逐条对应一个已发生的失败：
 * 1. **@Upsert 而非 REPLACE**：REPLACE 先删后插，而 8 张表以 RESTRICT 引用
 *    `knowledge_node` / `knowledge_teaching_material`，删除会被外键直接拒绝。
 * 2. **退役而非物删**：学生错题绑定/掌握度/复习队列还引用着它们；掌握度表是 CASCADE，
 *    物删会静默带走学生数据。
 * 3. **退役必须同时删检索特征行**：自愈逻辑靠"已索引数 ≥ 已审校数"判断补建，
 *    留着特征行会让两个计数永久漂移。
 * 4. **只升不降**：库里已 `USER_CONFIRMED` 的节点不被改回包里的值。
 *
 * 崩溃安全靠**差分的幂等性**而非大事务：每个对象独立判定，任一步崩掉，下次重跑同一份
 * 差分即收敛。进度行由调用方在**最后**推进，所以"版本已推进"⟺"上一次跑完了"。
 *
 * 不做的事：**合并的学生数据重指**。那由账本事件 `KC_MERGED` 在投影时生效，
 * 从而不重写任何历史行、重放仍逐字段可复现。
 */
internal class RoomKnowledgeContentReconciler(private val database: StudyDatabase) {

    suspend fun applyKnowledgeContentUpdate(
        command: KnowledgeContentUpdateCommand,
    ): KnowledgeContentUpdateResult {
        val skipped = mutableListOf<String>()

        upsertMissingSources(command)

        val existingNodes = database.problemOrganizationDao()
            .readKnowledgeNodesByTaxonomy(command.packId)
        val nodes = planNodes(command.nodes, existingNodes, skipped)
        if (nodes.upserts.isNotEmpty()) {
            database.problemOrganizationDao()
                .upsertKnowledgeNodes(nodes.upserts.map(KnowledgeNodeSeedRecord::toEntity))
        }
        val nodesRetired = retireNodes(
            existingNodes = existingNodes,
            requestedNodeIds = command.nodes.mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId),
            retirements = command.nodeRetirements,
        )

        // 被逐条校验挡掉的节点不能作为绑定的目标：写了会撞外键，读也读不到。
        // 其余包内节点都是合法目标——**包括内容没变、本轮不需要写的那些**。
        val retainedNodeIds = command.nodes
            .mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
            .apply { removeAll(nodes.skippedIds) }

        replaceNodeSourceBindings(command.nodeSourceBindings, retainedNodeIds, command.nodes)
        val relationsWritten = replaceRelations(command.relations, retainedNodeIds, command.nodes)

        val existingMaterials = database.knowledgeTeachingMaterialDao()
            .readByStableCodePrefix("${command.packId}:")
        val materials = planMaterials(
            materials = command.materials,
            bindingsByMaterial = command.materialBindings.groupBy(
                KnowledgeTeachingMaterialNodeBindingRecord::materialId,
            ),
            acceptedNodeIds = retainedNodeIds,
            acceptedNodes = command.nodes.filter { it.knowledgeNodeId in retainedNodeIds },
            teachingSources = command.teachingSources,
            existingMaterials = existingMaterials,
            skipped = skipped,
        )
        if (materials.upserts.isNotEmpty()) {
            database.knowledgeTeachingMaterialDao()
                .upsertMaterials(materials.upserts.map(KnowledgeTeachingMaterialRecord::toEntity))
        }
        replaceMaterialBindings(
            bindings = command.materialBindings,
            packMaterialIds = command.materials
                .mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialId),
            acceptedMaterialIds = materials.acceptedIds,
        )
        val materialsRetired = retireMaterials(
            existingMaterials = existingMaterials,
            requestedMaterialIds =
                command.materials.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialId),
            acceptedIds = materials.acceptedIds,
        )

        return KnowledgeContentUpdateResult(
            nodesInserted = nodes.inserted,
            nodesUpdated = nodes.updated,
            nodesRetired = nodesRetired,
            materialsInserted = materials.inserted,
            materialsUpdated = materials.updated,
            materialsRetired = materialsRetired,
            relationsInserted = relationsWritten,
            relationsDeleted = 0,
            skipped = skipped,
        )
    }

    suspend fun readContentInstallState(packId: String): ContentInstallStateRecord? =
        database.contentInstallStateDao().read(packId)?.let { entity ->
            ContentInstallStateRecord(
                packId = entity.packId,
                contentVersion = entity.contentVersion,
                appliedAtEpochMillis = entity.appliedAtEpochMillis,
                skippedCount = entity.skippedCount,
                skippedDetail = entity.skippedDetail,
            )
        }

    suspend fun recordContentInstallState(record: ContentInstallStateRecord) {
        database.contentInstallStateDao().upsert(
            ContentInstallStateEntity(
                packId = record.packId,
                contentVersion = record.contentVersion,
                appliedAtEpochMillis = record.appliedAtEpochMillis,
                skippedCount = record.skippedCount,
                skippedDetail = record.skippedDetail,
            ),
        )
    }

    // ---- 来源 ----

    /**
     * 只补缺，不改已存在的。
     *
     * 来源是溯源记录不是内容；改它要动 `content_fingerprint`，而那个列有**唯一索引**——
     * 重算后与另一条来源撞索引就会把整批拖垮。
     *
     * 节点来源与材料来源是**两组**（`sources` / `teachingSources`），必须都补：
     * 只补一组会让另一组的使用方全部因"缺来源"被挡掉（实测踩到过，整批材料落不了地）。
     */
    private suspend fun upsertMissingSources(command: KnowledgeContentUpdateCommand) {
        val allSources = command.sources + command.teachingSources
        val dao = database.problemOrganizationDao()
        val existing = dao.readKnowledgeSourcesByIds(
            allSources.mapTo(linkedSetOf(), KnowledgeSourceSeedRecord::sourceId),
        ).mapTo(hashSetOf(), KnowledgeSourceEntity::sourceId)
        val missing = allSources
            .distinctBy(KnowledgeSourceSeedRecord::sourceId)
            .filterNot { it.sourceId in existing }
        if (missing.isNotEmpty()) dao.upsertKnowledgeSources(missing.map { it.toEntity() })
    }

    // ---- 节点 ----

    /** 一个差分计划：要写什么、各是插入还是更新、跳过了谁。 */
    private data class NodePlan(
        val upserts: List<KnowledgeNodeSeedRecord>,
        val inserted: Int,
        val updated: Int,
        /** 被逐条校验挡掉的节点 id。**显式带出来**，不去解析跳过原因的字符串——
         *  节点 id 本身含冒号（`kb:pack:subject:atomic:slug`），按冒号切必然切错。 */
        val skippedIds: Set<String>,
    )

    private fun planNodes(
        nodes: List<KnowledgeNodeSeedRecord>,
        existingNodes: List<KnowledgeNodeEntity>,
        skipped: MutableList<String>,
    ): NodePlan {
        val existingById = existingNodes.associateBy(KnowledgeNodeEntity::knowledgeNodeId)
        val byId = nodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        val upserts = mutableListOf<KnowledgeNodeSeedRecord>()
        val skippedIds = mutableSetOf<String>()
        var inserted = 0
        var updated = 0
        for (node in nodes) {
            val problem = KnowledgeBaseImportContract.problemWith(node, byId)
            if (problem != null) {
                skipped += "node:${node.knowledgeNodeId}: $problem"
                skippedIds += node.knowledgeNodeId
                continue
            }
            val existing = existingById[node.knowledgeNodeId]
            // 只升不降：库里已人工确认过的，调和不得把它改回包里的值。
            val desired = if (
                existing != null &&
                existing.verificationStatus == KnowledgeNodeVerificationStatus.USER_CONFIRMED.name &&
                node.verificationStatus != KnowledgeNodeVerificationStatus.USER_CONFIRMED.name
            ) {
                node.copy(verificationStatus = KnowledgeNodeVerificationStatus.USER_CONFIRMED.name)
            } else {
                node
            }
            when {
                existing == null -> { inserted++; upserts += desired }
                existing != desired.toEntity() -> { updated++; upserts += desired }
            }
        }
        return NodePlan(upserts, inserted, updated, skippedIds)
    }

    /** 包里有、库里没有 / 库里没有的都要退役；`retirements` 给出 1:1 取代目标（可能为 null）。 */
    private suspend fun retireNodes(
        existingNodes: List<KnowledgeNodeEntity>,
        requestedNodeIds: Set<String>,
        retirements: Map<String, String?>,
    ): Int {
        val standing = existingNodes
            .mapTo(linkedSetOf(), KnowledgeNodeEntity::knowledgeNodeId)
            .apply { removeAll(requestedNodeIds) }
        if (standing.isEmpty()) return 0
        val dao = database.problemOrganizationDao()
        val retired = standing.count { dao.retireKnowledgeNode(it, retirements[it]) > 0 }
        // 与退役同步：自愈逻辑靠"已索引数 ≥ 已审校数"判断是否补建，退役节点若留着
        // 特征行，两个计数会永久漂移。
        dao.deleteSearchFeaturesForNodes(standing)
        return retired
    }

    // ---- 节点—来源绑定与前置边：纯内容，整体替换 ----

    /**
     * 这两张表没有学生数据引用（与节点/材料不同），所以整体替换比逐条 diff 更简单且等价：
     * 删掉这个包的全部，再按包写入。**只在该包的对象上操作**，不会碰到别的包或用户自建内容。
     */
    private suspend fun replaceNodeSourceBindings(
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
        retainedNodeIds: Set<String>,
        nodes: List<KnowledgeNodeSeedRecord>,
    ) {
        val dao = database.problemOrganizationDao()
        val packNodeIds = nodes.mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        if (packNodeIds.isEmpty()) return
        dao.deleteKnowledgeNodeSourceBindingsForNodes(packNodeIds)
        val toInsert = bindings.filter { it.knowledgeNodeId in retainedNodeIds }
        if (toInsert.isNotEmpty()) {
            dao.importKnowledgeNodeSourceBindings(toInsert.map(KnowledgeNodeSourceBindingSeedRecord::toEntity))
        }
    }

    /** 返回实际写入的边数。两端都必须保留——只有一端在包里的边是悬空的，写进去也读不到。 */
    private suspend fun replaceRelations(
        relations: List<KnowledgeNodeRelationRecord>,
        retainedNodeIds: Set<String>,
        nodes: List<KnowledgeNodeSeedRecord>,
    ): Int {
        val dao = database.knowledgeNodeRelationDao()
        val packNodeIds = nodes.mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        if (packNodeIds.isEmpty()) return 0
        dao.deleteByDependents(packNodeIds)
        val toInsert = relations.filter {
            it.dependentKnowledgeNodeId in retainedNodeIds &&
                it.prerequisiteKnowledgeNodeId in retainedNodeIds
        }
        if (toInsert.isNotEmpty()) {
            dao.upsertAll(toInsert.map(KnowledgeNodeRelationRecord::toEntity))
        }
        return toInsert.size
    }

    // ---- 材料 ----

    private data class MaterialPlan(
        val upserts: List<KnowledgeTeachingMaterialRecord>,
        val acceptedIds: Set<String>,
        val inserted: Int,
        val updated: Int,
    )

    private fun planMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindingsByMaterial: Map<String, List<KnowledgeTeachingMaterialNodeBindingRecord>>,
        acceptedNodeIds: Set<String>,
        acceptedNodes: List<KnowledgeNodeSeedRecord>,
        teachingSources: List<KnowledgeSourceSeedRecord>,
        existingMaterials: List<KnowledgeTeachingMaterialEntity>,
        skipped: MutableList<String>,
    ): MaterialPlan {
        val existingById = existingMaterials.associateBy(KnowledgeTeachingMaterialEntity::materialId)
        val upserts = mutableListOf<KnowledgeTeachingMaterialRecord>()
        val acceptedIds = mutableSetOf<String>()
        var inserted = 0
        var updated = 0
        for (material in materials) {
            val ownBindings = bindingsByMaterial[material.materialId].orEmpty()
            if (ownBindings.isEmpty()) {
                // 无绑定的材料在装载时会被静默剔除、等于白写：显式记账，不无声丢弃。
                skipped += "material:${material.materialId}: 没有任何知识节点绑定"
                continue
            }
            val problem = KnowledgeTeachingMaterialContract.problemWith(
                material = material,
                bindings = ownBindings.filter { it.knowledgeNodeId in acceptedNodeIds },
                nodes = acceptedNodes,
                sources = teachingSources,
            )
            if (problem != null) {
                skipped += "material:${material.materialId}: $problem"
                continue
            }
            acceptedIds += material.materialId
            val existing = existingById[material.materialId]
            when {
                existing == null -> { inserted++; upserts += material }
                existing != material.toEntity() -> { updated++; upserts += material }
            }
        }
        return MaterialPlan(upserts, acceptedIds, inserted, updated)
    }

    private suspend fun replaceMaterialBindings(
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        packMaterialIds: Set<String>,
        acceptedMaterialIds: Set<String>,
    ) {
        if (packMaterialIds.isEmpty()) return
        val dao = database.knowledgeTeachingMaterialDao()
        // 按**包的**材料集删（不是按绑定集）：一条材料在新版里丢了全部绑定，它的旧绑定
        // 也必须清掉，否则会留下指向已退役节点的悬空行。
        dao.deleteBindingsForMaterials(packMaterialIds)
        val toInsert = bindings.filter { it.materialId in acceptedMaterialIds }
        if (toInsert.isNotEmpty()) {
            dao.upsertBindings(toInsert.map { it.toEntity() })
        }
    }

    /**
     * 两批材料要退役：**移出包的**，以及**绑定全部失效的**。
     *
     * 后者是影响面闭合：目标节点退役或移出包之后，材料会剩 0 条绑定，而检索/讲题参考/复习题
     * 全都要经过绑定表——0 绑定等于不可达。留着只会让统计虚高，并让"库里有多少材料"与
     * "运行时能用到多少"永久对不上。
     */
    private suspend fun retireMaterials(
        existingMaterials: List<KnowledgeTeachingMaterialEntity>,
        requestedMaterialIds: Set<String>,
        acceptedIds: Set<String>,
    ): Int {
        val dao = database.knowledgeTeachingMaterialDao()
        val standing = existingMaterials
            .mapTo(linkedSetOf(), KnowledgeTeachingMaterialEntity::materialId)
            .apply { removeAll(requestedMaterialIds) }
        var retired = standing.count { dao.retireMaterial(it) > 0 }
        if (acceptedIds.isNotEmpty()) {
            val stillBound = dao.readBoundMaterialIds(acceptedIds).toHashSet()
            retired += (acceptedIds - stillBound).count { dao.retireMaterial(it) > 0 }
        }
        return retired
    }
}
