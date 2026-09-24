package com.tingyun.smartmistakebook.core.database

import androidx.room3.withWriteTransaction
import android.util.Log
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeRecallCandidateRow
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.entity.ContentInstallStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchIndexStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.port.KnowledgeReadPort
import com.tingyun.smartmistakebook.core.database.port.PracticeUnitKnowledgeBindingRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Placeholder display name for pseudo-KC fallback nodes (spec §3.4). */
internal const val PSEUDO_NODE_DISPLAY_NAME = "未归类知识点"

/** Taxonomy marker carried by pseudo knowledge nodes themselves. */
internal const val PSEUDO_TAXONOMY_VERSION = "pseudo-node-v1"

private const val KNOWLEDGE_NODE_QUERY_CHUNK_SIZE = 400

/** 稠密腿（可选）的日志标签。 */
private const val TAG = "KnowledgeRecall"

/**
 * Knowledge-base import/validation, grounding requests, and the pseudo-KC
 * fallback binding (spec §3.4).
 */
internal class RoomKnowledgeBaseStore(
    private val database: StudyDatabase,
    private val reviewStore: RoomKnowledgeResearchReviewStore,
    /**
     * 稠密腿（Stage-3）：`null` = 未装配 ⇒ 与纯词面形态（Stage-1/2）完全一致。
     * 装配点在 `StudyDatabaseFactory.open`；总开关在 `DenseRecallAssembly.ENABLED`（core:data）。
     */
    private val denseRerank: DenseRecallReranker? = null,
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

    /**
     * matched 优先、父 topic 随后（供上层解释）；D1 落地依据见 docs/kb-stage2-report-2026-09-23.md
     *
     * **Stage-3 稠密腿只改 matched 的次序**（spec §2.4 的 α=0.5 归一化分数融合）：
     * - 候选域 = 词面召回集本身（`subject` 作用域、可信过滤、`limit` 上限都不动）；
     * - 稠密腿不可用（未装配 / 无查询文本 / 加载或推理失败）⇒ 原样返回词面次序，**不抛错**；
     * - 返回集合的长度语义不变：仍是"≤ limit 个 matched + 其父节点"。
     *
     * 与离线参考数的**已知差异**（如实记录，不藏）：离线判分器的排序域是该科全部节点
     * （含词面腿无分的原子节点），端侧按任务书只重排词面召回集 —— 实测差异
     * = 90 条里 1 条的 top-5 成员、命中数 0 条（见 `tools/dense_build/stage3_device_sim.py`）。
     *
     * @param queryText 原始查询文本（稠密腿的输入）。`null` = 该调用方不要稠密腿
     *   （例：`MASTERY_FOCUS_RESOLUTION_LIMIT` 的裸 B 路，D7 冻结了它的形态）。
     */
    suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
        queryText: String? = null,
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
        val rows = dao.searchSubjectKnowledgeRecallCandidates(subject, searchFeatures, limit)
        val matched = rerankMatchOrder(subject, queryText, rows)
        val matchedIds = matched.mapTo(hashSetOf(), KnowledgeNodeEntity::knowledgeNodeId)
        val parents = dao.readKnowledgeNodesByIds(
            matched.mapNotNullTo(hashSetOf(), KnowledgeNodeEntity::parentKnowledgeNodeId) - matchedIds,
        )
        return (matched + parents)
            .distinctBy(KnowledgeNodeEntity::knowledgeNodeId)
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    /**
     * 稠密腿重排（仅次序）。任何一步不成立都退回词面次序——这条路径上"少一条可选的腿"
     * 永远优于"主检索失败"。次序守卫（排列校验）在 [DenseRecallOrdering] 里，JVM 可测。
     */
    private suspend fun rerankMatchOrder(
        subject: String,
        queryText: String?,
        rows: List<KnowledgeRecallCandidateRow>,
    ): List<KnowledgeNodeEntity> {
        val lexicalOrder = rows.map(KnowledgeRecallCandidateRow::node)
        val reranker = denseRerank
        // 单条候选的重排是恒等变换：不为了"跑一遍"去加载模型与 17MB 资产。
        if (reranker == null || queryText.isNullOrBlank() || rows.size < 2) return lexicalOrder
        val reranked = try {
            reranker.order(
                subject = subject,
                queryText = queryText,
                candidates = rows.map { DenseRecallCandidate(it.node.knowledgeNodeId, it.matchCount) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // 接口契约说"失败回 null"，但实现是另一个模块（core:data）给的；
            // 这里再兜一层，使"绝不崩、绝不返回空"不依赖对方守约。
            Log.w(TAG, "dense rerank threw for subject=$subject: $failure")
            null
        }
        return DenseRecallOrdering.orderOrLexical(lexicalOrder, reranked)
    }

    /**
     * 检索索引自愈，两级门：
     *
     * 1. **版本锚点**（`knowledge_search_index_state`）：节点侧抽取规则变化时见
     *    [KnowledgeSearchFeatureExtractor.INDEX_VERSION]（当前 = v1 的 16 片段 / 192 特征
     *    截断；2026-09-22 的 v2 去截断实验实测净伤害、当日回滚）——锚点缺失或**不等**
     *    （两个方向都换血：legacy 库无锚点、v2 实验库锚点=2 当前=1）⇒ **整科重建**。
     *    必须整科换血：旧截断行在"已索引数 ≥ 已审校数"检查下是满的，没有锚点会永远留下。
     * 2. **只补缺**（原语义）：同版本下新增节点按需补建——节点内容不可变，
     *    只增不删（`OnConflictStrategy.IGNORE`）是正确的。
     *
     * 崩溃安全照 `content_install_state` 的纪律：锚点**最后写**，
     * "锚点当前"⟺"上一次重建完整跑完"；崩在中途则锚点没推进，下次重跑收敛。
     */
    private suspend fun ensureKnowledgeSearchIndex(subject: String) {
        val dao = database.problemOrganizationDao()
        val stateDao = database.knowledgeSearchIndexStateDao()
        if (stateDao.readVersion(subject) == KnowledgeSearchFeatureExtractor.INDEX_VERSION) {
            val reviewedCount = dao.countReviewedKnowledgeNodesBySubject(subject)
            if (reviewedCount == 0 || dao.countIndexedKnowledgeNodesBySubject(subject) >= reviewedCount) return
            database.withWriteTransaction {
                val missingCheckCount = dao.countReviewedKnowledgeNodesBySubject(subject)
                if (dao.countIndexedKnowledgeNodesBySubject(subject) < missingCheckCount) {
                    val nodes = dao.readSubjectKnowledgeRecallCandidates(subject, missingCheckCount)
                    dao.insertKnowledgeSearchFeatures(nodes.flatMap(KnowledgeNodeEntity::toSearchFeatures))
                }
            }
            return
        }
        database.withWriteTransaction {
            val reviewedCount = dao.countReviewedKnowledgeNodesBySubject(subject)
            if (reviewedCount > 0) {
                dao.deleteSearchFeaturesForSubject(subject)
                val nodes = dao.readSubjectKnowledgeRecallCandidates(subject, reviewedCount)
                dao.insertKnowledgeSearchFeatures(nodes.flatMap(KnowledgeNodeEntity::toSearchFeatures))
            }
            stateDao.replace(
                KnowledgeSearchIndexStateEntity(subject, KnowledgeSearchFeatureExtractor.INDEX_VERSION),
            )
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

    suspend fun readActiveKnowledgeNodeIds(ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return ids.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readActiveKnowledgeNodeIds(chunk.toSet())
            }
            .toSet()
    }

    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readKnowledgeSourcesByIds(chunk.toSet())
            }
            .map(KnowledgeSourceEntity::toSeedRecord)
    }

    /**
     * `退役 id -> 存活 id`。投影据此把历史证据算到存活节点上（"合并并入"），
     * 而历史行一字不改。今天全库为空（尚无节点被退役）。
     */
    suspend fun readKnowledgeNodeSuccessors(): Map<String, String> =
        database.problemOrganizationDao()
            .readKnowledgeNodeSuccessors()
            .associate { it.retiredId to it.successorId }

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

internal fun KnowledgeSourceSeedRecord.toEntity() = KnowledgeSourceEntity(
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

internal fun KnowledgeNodeRelationRecord.toEntity() = KnowledgeNodeRelationEntity(
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

internal fun KnowledgeTeachingMaterialRecord.toEntity() = KnowledgeTeachingMaterialEntity(
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

internal fun KnowledgeTeachingMaterialNodeBindingRecord.toEntity() =
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

internal fun KnowledgeNodeSourceBindingSeedRecord.toEntity() = KnowledgeNodeSourceBindingEntity(
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
