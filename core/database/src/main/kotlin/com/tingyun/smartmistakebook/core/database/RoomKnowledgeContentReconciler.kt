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
            packMaterialIds = command.materials
                .mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialId),
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
        val accepted = linkedMapOf<String, KnowledgeNodeSeedRecord>()
        val rejected = linkedMapOf<String, String>()

        for (node in nodes) {
            val problem = KnowledgeBaseImportContract.problemWith(node, byId)
            if (problem != null) {
                rejected[node.knowledgeNodeId] = problem
                continue
            }
            val existing = existingById[node.knowledgeNodeId]
            // 只升不降：库里已人工确认过的，调和不得把它改回包里的值。
            accepted[node.knowledgeNodeId] = if (
                existing != null &&
                existing.verificationStatus == KnowledgeNodeVerificationStatus.USER_CONFIRMED.name &&
                node.verificationStatus != KnowledgeNodeVerificationStatus.USER_CONFIRMED.name
            ) {
                node.copy(verificationStatus = KnowledgeNodeVerificationStatus.USER_CONFIRMED.name)
            } else {
                node
            }
        }

        // **级联**：父级写不进去（被校验拒了，或父级本身没通过），子级也写不进去——
        // 写进去会撞 `parent_knowledge_node_id` 的外键，整批崩掉。
        // 这是"逐对象跳过"必须闭合的影响面：单看那一行没毛病，坏在它与父级的关系上。
        // 迭代到不动点，因为跳过一层会牵动下一层。
        var changed = true
        while (changed) {
            changed = false
            for ((id, node) in accepted.toList()) {
                val parentId = node.parentKnowledgeNodeId ?: continue
                if (parentId in existingById || parentId in accepted) continue
                accepted.remove(id)
                rejected[id] = "父级 $parentId 不在库中且本轮未被接受"
                changed = true
            }
        }

        rejected.forEach { (id, reason) -> skipped += "node:$id: $reason" }
        val upserts = mutableListOf<KnowledgeNodeSeedRecord>()
        var inserted = 0
        var updated = 0
        accepted.forEach { (id, node) ->
            val existing = existingById[id]
            when {
                existing == null -> { inserted++; upserts += node }
                existing != node.toEntity() -> { updated++; upserts += node }
            }
        }
        // **按父级先序写出**。`knowledge_node.parent_knowledge_node_id` 是自引用外键（RESTRICT），
        // 而 `@Upsert` 逐行插入、逐行检查——子级排在父级前面就会撞外键、整批崩。
        //
        // 不假定包里 topics 数组的顺序是对的：实测当前包里有 4 个 topic 的父级排在它**后面**
        // （`MATH·综合` 在 `MATH` 之前等），而"数组顺序"既不是包契约的一部分、也没被任何门钉住。
        // 由写入方负责这个顺序，等于让一个排版细节决定安装能否成功。
        return NodePlan(
            upserts = parentFirstOrder(accepted, existingById.keys),
            inserted = inserted,
            updated = updated,
            skippedIds = rejected.keys.toSet(),
        )
    }

    /**
     * 把节点排成"父级一定在子级之前"。
     *
     * 迭代发出可发出者，直到没有进展——父级要么已在库里，要么本轮已发出。契约已排除
     * 父子成环（`validateTopicHierarchyIsAcyclic`），而父级不在集合里的节点在级联阶段
     * 已被剔除，所以循环必然把 accepted 清空。
     */
    private fun parentFirstOrder(
        accepted: Map<String, KnowledgeNodeSeedRecord>,
        existingIds: Set<String>,
    ): List<KnowledgeNodeSeedRecord> {
        val emitted = mutableSetOf<String>()
        val out = mutableListOf<KnowledgeNodeSeedRecord>()
        val pending = accepted.keys.toMutableSet()
        var progressed = true
        while (pending.isNotEmpty() && progressed) {
            progressed = false
            for (id in pending.toList()) {
                val parentId = accepted.getValue(id).parentKnowledgeNodeId
                if (parentId == null || parentId in existingIds || parentId in emitted) {
                    out += accepted.getValue(id)
                    emitted += id
                    pending -= id
                    progressed = true
                }
            }
        }
        check(pending.isEmpty()) {
            "Knowledge nodes are not a forest; ${pending.size} node(s) never became writable"
        }
        return out
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
     * 退役两类材料：**移出包的**，以及**此刻已无绑定的**。
     *
     * 后者是影响面闭合里最容易漏的一格：`replaceMaterialBindings` 会把包内材料的绑定**整体替换**，
     * 于是"绑定目标被丢弃"或"校验没过被跳过"的材料会剩 0 条绑定，而检索/讲题参考/复习题
     * 全都要经过绑定表——0 绑定等于不可达。不退役它就会留一条 status=ACTIVE 却谁也到不了的
     * 行，让"库里有多少材料"与"运行时能用到多少"永久对不上。
     */
    private suspend fun retireMaterials(
        existingMaterials: List<KnowledgeTeachingMaterialEntity>,
        packMaterialIds: Set<String>,
    ): Int {
        val dao = database.knowledgeTeachingMaterialDao()
        val inDatabase = existingMaterials.mapTo(linkedSetOf(), KnowledgeTeachingMaterialEntity::materialId)
        val standing = inDatabase.toMutableSet().apply { removeAll(packMaterialIds) }
        var retired = standing.count { dao.retireMaterial(it) > 0 }

        val stillInPack = inDatabase.intersect(packMaterialIds)
        if (stillInPack.isNotEmpty()) {
            val stillBound = dao.readBoundMaterialIds(stillInPack).toHashSet()
            retired += (stillInPack - stillBound).count { dao.retireMaterial(it) > 0 }
        }
        return retired
    }
}
