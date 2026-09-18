package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.entity.ContentInstallStateEntity
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
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
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

    /**
     * **内容调和**：让库里的内容等于这份包。
     *
     * 三条性质，各有它消灭的失败：
     *
     * 1. **逐对象判定**（插入 / 更新 / 退役）。整批全有或全无的写法让一条坏数据就能让
     *    整包停摆——KD-15 正是这个形态：80 条材料的 `reviewedAt` 比来源 `importedAt`
     *    早几毫秒，另外一万条一起被挡在门外、每次启动弹横幅。这里改为逐条校验，
     *    坏的那条跳过并记进 [KnowledgeContentUpdateResult.skipped]，其余照常落地。
     * 2. **退役而非物删**。8 张表以 RESTRICT 引用 `knowledge_node`，而
     *    `knowledge_mastery_state` / `learner_knowledge_mastery_state` 挂着学生的错题绑定、
     *    掌握度与复习队列——物删要么被外键挡住，要么对 CASCADE 表静默带走学生数据。
     * 3. **不重写人工确认**。包里的节点在库里可能已被 `USER_CONFIRMED` 提升过；
     *    调和只升不降，不把学生的确认改回包里的值。
     *
     * **崩溃安全靠差分的幂等性，不靠大事务**：每个对象独立判定，任一步崩掉，下次启动
     * 重跑同一份差分即收敛；`content_install_state` 的版本行由调用方在**最后**推进，
     * 所以"版本已推进"⟺"上一次跑完了"。
     *
     * 不做的事：**合并的学生数据重指**。那由账本事件 `KC_MERGED` 在投影时生效，
     * 从而不重写任何历史行、重放仍逐字段可复现。
     */
    suspend fun applyKnowledgeContentUpdate(
        command: KnowledgeContentUpdateCommand,
    ): KnowledgeContentUpdateResult {
        val dao = database.problemOrganizationDao()
        val materialDao = database.knowledgeTeachingMaterialDao()
        val relationDao = database.knowledgeNodeRelationDao()
        val skipped = mutableListOf<String>()

        // ---- 来源：只补缺，不改已存在的 ----
        // 来源是溯源记录不是内容。改它要动 content_fingerprint，而那个列有唯一索引——
        // 重算后与另一条来源撞索引就会把整批拖垮。缺什么补什么即可。
        //
        // 节点来源与材料来源是**两组**（`sources` / `teachingSources`），必须都补：
        // 只补一组会让另一组的使用方全部因"缺来源"被挡掉。
        val allSources = command.sources + command.teachingSources
        val sourcesById = allSources.associateBy(KnowledgeSourceSeedRecord::sourceId)
        val existingSourceIds = dao.readKnowledgeSourcesByIds(sourcesById.keys)
            .mapTo(hashSetOf(), KnowledgeSourceEntity::sourceId)
        val sourcesToInsert = allSources
            .distinctBy(KnowledgeSourceSeedRecord::sourceId)
            .filter { it.sourceId !in existingSourceIds }
        if (sourcesToInsert.isNotEmpty()) {
            dao.upsertKnowledgeSources(sourcesToInsert.map { it.toEntity() })
        }

        // ---- 节点 ----
        val nodesById = command.nodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        val existingNodes = dao.readKnowledgeNodesByTaxonomy(command.packId)
            .associateBy(KnowledgeNodeEntity::knowledgeNodeId)

        val nodesToUpsert = mutableListOf<KnowledgeNodeSeedRecord>()
        var nodesInserted = 0
        var nodesUpdated = 0
        for (node in command.nodes) {
            val problem = KnowledgeBaseImportContract.problemWith(node, nodesById)
            if (problem != null) {
                skipped += "node:${node.knowledgeNodeId}: $problem"
                continue
            }
            val existing = existingNodes[node.knowledgeNodeId]
            // 只升不降：库里已人工确认过的，调和不得把它改回包里的值。
            val desired = if (
                existing != null &&
                existing.verificationStatus ==
                KnowledgeNodeVerificationStatus.USER_CONFIRMED.name &&
                node.verificationStatus != KnowledgeNodeVerificationStatus.USER_CONFIRMED.name
            ) {
                node.copy(
                    verificationStatus = KnowledgeNodeVerificationStatus.USER_CONFIRMED.name,
                )
            } else {
                node
            }
            when {
                existing == null -> {
                    nodesInserted++
                    nodesToUpsert += desired
                }
                existing != desired.toEntity() -> {
                    nodesUpdated++
                    nodesToUpsert += desired
                }
            }
        }
        if (nodesToUpsert.isNotEmpty()) {
            dao.upsertKnowledgeNodes(nodesToUpsert.map(KnowledgeNodeSeedRecord::toEntity))
        }

        val retainedNodeIds = command.nodes
            .filterNot { node -> skipped.any { it.startsWith("node:${node.knowledgeNodeId}:") } }
            .mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)

        // ---- 节点退役 ----
        val standingIds = existingNodes.keys - command.nodes
            .mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        var nodesRetired = 0
        for (nodeId in standingIds) {
            if (dao.retireKnowledgeNode(nodeId, command.nodeRetirements[nodeId]) > 0) {
                nodesRetired++
            }
        }
        if (standingIds.isNotEmpty()) {
            // 与退役同步：自愈逻辑靠"已索引数 ≥ 已审校数"判断是否补建，退役节点若留着
            // 特征行，两个计数会永久漂移。
            dao.deleteSearchFeaturesForNodes(standingIds)
        }

        // ---- 节点—来源绑定：纯内容，整体替换 ----
        val packNodeIds = command.nodes.mapTo(hashSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        if (packNodeIds.isNotEmpty()) {
            dao.deleteKnowledgeNodeSourceBindingsForNodes(packNodeIds)
        }
        val bindingsToInsert = command.nodeSourceBindings.filter { it.knowledgeNodeId in retainedNodeIds }
        if (bindingsToInsert.isNotEmpty()) {
            dao.importKnowledgeNodeSourceBindings(
                bindingsToInsert.map(KnowledgeNodeSourceBindingSeedRecord::toEntity),
            )
        }

        // ---- 前置边：纯内容，整体替换 ----
        if (packNodeIds.isNotEmpty()) {
            relationDao.deleteByDependents(packNodeIds)
        }
        val relationsToInsert = command.relations.filter {
            it.dependentKnowledgeNodeId in retainedNodeIds &&
                it.prerequisiteKnowledgeNodeId in retainedNodeIds
        }
        if (relationsToInsert.isNotEmpty()) {
            relationDao.upsertAll(relationsToInsert.map(KnowledgeNodeRelationRecord::toEntity))
        }

        // ---- 材料 ----
        val existingMaterials = materialDao
            .readByStableCodePrefix("${command.packId}:")
            .associateBy(KnowledgeTeachingMaterialEntity::materialId)
        val acceptedNodes = command.nodes.filter { it.knowledgeNodeId in retainedNodeIds }

        val materialsToUpsert = mutableListOf<KnowledgeTeachingMaterialRecord>()
        var materialsInserted = 0
        var materialsUpdated = 0
        val acceptedMaterialIds = mutableSetOf<String>()
        // 材料先按 (nodeId -> 材料) 分组，好在校验时只喂给它自己那几条绑定——
        // 契约的逐条入口就是把"这一条材料 + 它的绑定"当一批校验。
        val bindingsByMaterial = command.materialBindings.groupBy(
            KnowledgeTeachingMaterialNodeBindingRecord::materialId,
        )
        for (material in command.materials) {
            val ownBindings = bindingsByMaterial[material.materialId].orEmpty()
            if (ownBindings.isEmpty()) {
                // 无绑定的材料在装载时会被静默剔除，等于白写；这里显式记账而不是无声丢弃。
                skipped += "material:${material.materialId}: 没有任何知识节点绑定"
                continue
            }
            val problem = KnowledgeTeachingMaterialContract.problemWith(
                material = material,
                bindings = ownBindings,
                nodes = acceptedNodes,
                sources = command.teachingSources,
            )
            if (problem != null) {
                skipped += "material:${material.materialId}: $problem"
                continue
            }
            acceptedMaterialIds += material.materialId
            val existing = existingMaterials[material.materialId]
            if (existing == null) {
                materialsInserted++
                materialsToUpsert += material
            } else if (existing != material.toEntity()) {
                materialsUpdated++
                materialsToUpsert += material
            }
        }
        if (materialsToUpsert.isNotEmpty()) {
            materialDao.upsertMaterials(materialsToUpsert.map(KnowledgeTeachingMaterialRecord::toEntity))
        }

        // ---- 材料绑定：纯内容，整体替换 ----
        val allPackMaterialIds = command.materials.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialId)
        if (allPackMaterialIds.isNotEmpty()) {
            materialDao.deleteBindingsForMaterials(allPackMaterialIds)
        }
        val materialBindingsToInsert = command.materialBindings.filter {
            it.materialId in acceptedMaterialIds &&
                it.knowledgeNodeId in retainedNodeIds
        }
        if (materialBindingsToInsert.isNotEmpty()) {
            materialDao.upsertBindings(
                materialBindingsToInsert.map(KnowledgeTeachingMaterialNodeBindingRecord::toEntity),
            )
        }

        // ---- 材料退役 ----
        var materialsRetired = 0
        for (materialId in existingMaterials.keys - allPackMaterialIds) {
            if (materialDao.retireMaterial(materialId) > 0) materialsRetired++
        }
        // **绑定全部失效的材料同样要退役**：目标节点退役或移出包之后，材料会剩 0 条绑定，
        // 而检索/讲题参考/复习题全都要经过绑定表——0 绑定等于不可达，留着只会让统计虚高，
        // 还会在下次装载时被静默剔除（unbound 材料的处理不一致正是本文档前面抱怨过的形态）。
        if (allPackMaterialIds.isNotEmpty()) {
            val stillBound = materialDao.readBoundMaterialIds(allPackMaterialIds).toHashSet()
            for (materialId in allPackMaterialIds - stillBound) {
                if (materialDao.retireMaterial(materialId) > 0) materialsRetired++
            }
        }

        return KnowledgeContentUpdateResult(
            nodesInserted = nodesInserted,
            nodesUpdated = nodesUpdated,
            nodesRetired = nodesRetired,
            materialsInserted = materialsInserted,
            materialsUpdated = materialsUpdated,
            materialsRetired = materialsRetired,
            relationsInserted = relationsToInsert.size,
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

    suspend fun applyReviewedKnowledgePack(        command: ApplyReviewedKnowledgePackCommand,
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
