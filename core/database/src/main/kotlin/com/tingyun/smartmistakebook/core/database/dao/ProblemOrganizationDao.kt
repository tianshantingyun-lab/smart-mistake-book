package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemClassificationBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import kotlinx.coroutines.flow.Flow

internal data class ReviewedKnowledgeCoverageRow(
    val subject: String,
    val topicCount: Int,
    val atomicKnowledgeCount: Int,
    val reviewedSourceCount: Int,
    val latestReviewedAtEpochMillis: Long,
)

@Dao
internal interface ProblemOrganizationDao {
    @Query(
        """
        SELECT
            node.subject AS subject,
            COUNT(DISTINCT CASE
                WHEN node.granularity = 'TOPIC' AND node.verification_status = 'CURATED'
                THEN node.knowledge_node_id
            END) AS topicCount,
            COUNT(DISTINCT CASE
                WHEN node.granularity = 'ATOMIC' AND node.verification_status = 'SOURCE_GROUNDED'
                THEN node.knowledge_node_id
            END) AS atomicKnowledgeCount,
            COUNT(DISTINCT provenance.source_id) AS reviewedSourceCount,
            MAX(provenance.reviewed_at_epoch_millis) AS latestReviewedAtEpochMillis
        FROM knowledge_node AS node
        INNER JOIN knowledge_node_source_binding AS provenance
            ON provenance.knowledge_node_id = node.knowledge_node_id
           AND provenance.reviewed_at_epoch_millis IS NOT NULL
        WHERE node.verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
          AND node.status != 'RETIRED'
        GROUP BY node.subject
        ORDER BY node.subject ASC
        """,
    )
    fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRow>>

    @Query(
        """
        SELECT COUNT(*) FROM practice_unit
        WHERE practice_unit_id = :practiceUnitId
          AND problem_id = :problemId
          AND problem_revision_id = :problemRevisionId
        """,
    )
    suspend fun exactPracticeUnitCount(
        practiceUnitId: String,
        problemId: String,
        problemRevisionId: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnowledgeNodes(nodes: List<KnowledgeNodeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKnowledgeSources(sources: List<KnowledgeSourceEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKnowledgeNodeSourceBindings(
        bindings: List<KnowledgeNodeSourceBindingEntity>,
    ): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnowledgeSearchFeatures(features: List<KnowledgeSearchFeatureEntity>): List<Long>

    @Transaction
    suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceEntity>,
        nodes: List<KnowledgeNodeEntity>,
        bindings: List<KnowledgeNodeSourceBindingEntity>,
        searchFeatures: List<KnowledgeSearchFeatureEntity>,
    ) {
        insertKnowledgeSources(sources)
        insertKnowledgeNodes(nodes)
        insertKnowledgeNodeSourceBindings(bindings)
        insertKnowledgeSearchFeatures(searchFeatures)
    }

    @Query("SELECT * FROM knowledge_node WHERE knowledge_node_id = :id")
    suspend fun readKnowledgeNode(id: String): KnowledgeNodeEntity?

    /**
     * Verification status is the one monotonic attribute of an otherwise
     * immutable knowledge node: once the student confirms the same label, a
     * model candidate becomes user authority and must be reusable as a
     * classification candidate (audit 2026-09-09). Never downgrades.
     */
    @Query(
        "UPDATE knowledge_node SET verification_status = 'USER_CONFIRMED' " +
            "WHERE knowledge_node_id = :id AND verification_status = 'MODEL_CANDIDATE'",
    )
    suspend fun promoteKnowledgeNodeToUserConfirmed(id: String): Int

    @Query(
        """
        SELECT * FROM knowledge_node
        WHERE subject = :subject
          AND status != 'RETIRED'
        ORDER BY
            CASE verification_status
                WHEN 'CURATED' THEN 0
                WHEN 'SOURCE_GROUNDED' THEN 1
                ELSE 2
            END,
            CASE granularity WHEN 'ATOMIC' THEN 0 ELSE 1 END,
            canonical_name ASC,
            knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readSubjectKnowledgeNodes(subject: String, limit: Int): List<KnowledgeNodeEntity>

    // ---- "当前工作"的读路径：一律排除已退役内容 ----
    //
    // 退役的节点从新工作里消失（不再被召回、不再进整理提示词、不再进新复习计划），
    // 但**历史解释路径不过滤**——学生错题上的标签、掌握度列表仍要能解释旧记录。
    // 这条分界是本文件里几个查询带 status 过滤、而 readKnowledgeNodesByIds 不带的原因。

    @Query(
        """
        SELECT * FROM knowledge_node
        WHERE subject = :subject
          AND verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
          AND status != 'RETIRED'
        ORDER BY canonical_name ASC
        LIMIT :limit
        """,
    )
    suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeEntity>

    @Query(
        """
        SELECT node.*
        FROM knowledge_search_feature AS feature
        INNER JOIN knowledge_node AS node
          ON node.knowledge_node_id = feature.knowledge_node_id
        WHERE feature.subject = :subject
          AND feature.search_feature IN (:searchFeatures)
          AND node.verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
          AND node.status != 'RETIRED'
        GROUP BY node.knowledge_node_id
        ORDER BY
            COUNT(DISTINCT feature.search_feature) DESC,
            CASE node.granularity WHEN 'ATOMIC' THEN 0 ELSE 1 END,
            node.canonical_name ASC,
            node.knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeEntity>

    /**
     * 检索索引自愈的分母。过滤条件**必须与召回查询逐条一致**（同样的
     * `verification_status`、同样排 `RETIRED`）——否则两个计数会永久漂移，
     * 自愈要么空转要么反复补建。
     */
    @Query(
        """
        SELECT COUNT(*) FROM knowledge_node
        WHERE subject = :subject
          AND verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
          AND status != 'RETIRED'
        """,
    )
    suspend fun countReviewedKnowledgeNodesBySubject(subject: String): Int

    /**
     * 已索引的节点数（自愈的分子）。**不必**再排 RETIRED：退役时会
     * 删掉该节点的特征行（见 `deleteSearchFeaturesForNodes`），所以两个计数同步下降。
     * 若哪天真要改这里，必须同时改退役路径——两处必须一起动。
     */
    @Query(
        """
        SELECT COUNT(DISTINCT knowledge_node_id)
        FROM knowledge_search_feature
        WHERE subject = :subject
        """,
    )
    suspend fun countIndexedKnowledgeNodesBySubject(subject: String): Int

    /**
     * 这些 id 里**仍然有效**的那些（未退役）。
     *
     * "当前工作"的读路径用它把集合收一道。**刻意不把它塞进 [readKnowledgeNodesByIds]**：
     * 那是历史解释路径（错题标签、掌握度列表要能解释旧记录），在那过滤会让学生的旧记录凭空消失。
     * 某 id 该不该在集合里，由**构造集合的那一方**决定，而不是由名字解析器决定。
     */
    @Query(
        "SELECT knowledge_node_id FROM knowledge_node " +
            "WHERE knowledge_node_id IN (:ids) AND status != 'RETIRED'",
    )
    suspend fun readActiveKnowledgeNodeIds(ids: Set<String>): List<String>

    /**
     * 按 id 读节点。**刻意不过滤 RETIRED**：这是历史解释路径（错题详情、掌握度列表要能
     * 解释旧记录），过滤会让"学生错题上的知识点标签、他的掌握度条目"凭空消失。
     * 新的工作路径请用上面带过滤的那几个查询。
     */
    @Query("SELECT * FROM knowledge_node WHERE knowledge_node_id IN (:ids)")
    suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeEntity>

    // ---- 内容调和用的读/写面（见 BundledKnowledgeBaseInstaller）----

    /**
     * 某个内容包登记在库里的**全部**节点——调和用它算"包里有、库里没有的"和
     * "库里有、包里没有的（该退役了）"。
     *
     * 不能按 subject 读：2020 样例包与 2025 四科包在 MATH 等科目上重叠，按科读会把
     * 另一个包的节点混进来，于是它们会被误判成"包里没有"而遭退役。
     */
    @Query("SELECT * FROM knowledge_node WHERE taxonomy_version = :taxonomyVersion")
    suspend fun readKnowledgeNodesByTaxonomy(taxonomyVersion: String): List<KnowledgeNodeEntity>

    /**
     * 按主键 upsert 节点内容。**必须是 upsert 而不是 REPLACE**：REPLACE 会先删后插，
     * 而 8 张表以 RESTRICT 引用 `knowledge_node`，删除会被外键直接拒绝。
     */
    @Upsert
    suspend fun upsertKnowledgeNodes(nodes: List<KnowledgeNodeEntity>)

    /**
     * 退役一个节点。**永不物删**——学生错题绑定/掌握度/复习队列还引用着它。
     * `status != 'RETIRED'` 的条件让重复调用是空操作（幂等）。
     */
    @Query(
        "UPDATE knowledge_node SET status = 'RETIRED', superseded_by = :supersededBy " +
            "WHERE knowledge_node_id = :id AND status != 'RETIRED'",
    )
    suspend fun retireKnowledgeNode(id: String, supersededBy: String?): Int

    /**
     * 删掉这些节点的检索特征。退役必须同时做这一步：自愈逻辑
     * （`ensureKnowledgeSearchIndex`）靠"已索引数 ≥ 已审校数"判断是否需要补建，
     * 若退役节点仍留着特征行，两个计数会永久漂移，自愈要么空转要么反复补建。
     */
    @Query("DELETE FROM knowledge_search_feature WHERE knowledge_node_id IN (:ids)")
    suspend fun deleteSearchFeaturesForNodes(ids: Set<String>)

    @Upsert
    suspend fun upsertKnowledgeSources(sources: List<KnowledgeSourceEntity>)

    /**
     * 按复合主键删一条节点—来源绑定。绑定是纯内容（没有学生数据引用它），
     * 所以这里可以真删；与节点/材料的"退役而非物删"不同。
     */
    @Query(
        "DELETE FROM knowledge_node_source_binding " +
            "WHERE knowledge_node_id = :nodeId AND source_id = :sourceId " +
            "AND source_locator = :sourceLocator",
    )
    suspend fun deleteKnowledgeNodeSourceBinding(
        nodeId: String,
        sourceId: String,
        sourceLocator: String,
    )

    @Query("DELETE FROM knowledge_node_source_binding WHERE knowledge_node_id IN (:nodeIds)")
    suspend fun deleteKnowledgeNodeSourceBindingsForNodes(nodeIds: Set<String>)

    /** 调和用：节点—来源绑定是纯内容（无学生数据引用），整体替换即可。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importKnowledgeNodeSourceBindings(
        bindings: List<KnowledgeNodeSourceBindingEntity>,
    )

    @Query("SELECT * FROM knowledge_source WHERE source_id IN (:ids)")
    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceEntity>

    /**
     * 已退役且指定了取代目标的节点：`退役 id -> 存活 id`。
     *
     * 投影用它把历史证据算到存活节点上（"合并并入"）。链式合并（`X→Y` 之后 `Y→Z`）由读取侧
     * 做传递闭包——这里只给原始边，不折叠。
     */
    @Query(
        "SELECT knowledge_node_id AS retiredId, superseded_by AS successorId " +
            "FROM knowledge_node WHERE superseded_by IS NOT NULL",
    )
    suspend fun readKnowledgeNodeSuccessors(): List<KnowledgeNodeSuccessorRow>

    @Query(
        "SELECT * FROM knowledge_node_source_binding WHERE knowledge_node_id IN (:knowledgeNodeIds)",
    )
    suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnowledgeBindings(
        bindings: List<PracticeUnitKnowledgeBindingEntity>,
    ): List<Long>

    @Query("SELECT * FROM practice_unit_knowledge_binding WHERE binding_id = :id")
    suspend fun readKnowledgeBinding(id: String): PracticeUnitKnowledgeBindingEntity?

    @Query(
        """
        SELECT * FROM practice_unit_knowledge_binding
        WHERE practice_unit_id = :practiceUnitId
          AND knowledge_node_id = :knowledgeNodeId
          AND basis_revision_id = :problemRevisionId
          AND taxonomy_version = :taxonomyVersion
        LIMIT 1
        """,
    )
    suspend fun readKnowledgeBindingByIdentity(
        practiceUnitId: String,
        knowledgeNodeId: String,
        problemRevisionId: String,
        taxonomyVersion: String,
    ): PracticeUnitKnowledgeBindingEntity?

    @Query(
        """
        SELECT * FROM practice_unit_knowledge_binding
        WHERE practice_unit_id = :practiceUnitId
        ORDER BY accepted_at_epoch_millis, binding_id
        """,
    )
    suspend fun readKnowledgeBindingsForPracticeUnit(
        practiceUnitId: String,
    ): List<PracticeUnitKnowledgeBindingEntity>

    @Query(
        """
        SELECT DISTINCT binding.knowledge_node_id
        FROM practice_unit_knowledge_binding AS binding
        INNER JOIN practice_unit AS unit
          ON unit.practice_unit_id = binding.practice_unit_id
         AND unit.problem_revision_id = binding.basis_revision_id
        INNER JOIN knowledge_node AS node
          ON node.knowledge_node_id = binding.knowledge_node_id
        WHERE unit.problem_id = :problemId
          AND unit.problem_revision_id = :problemRevisionId
          AND (
              NOT EXISTS (
                  SELECT 1
                  FROM problem_organization_receipt AS receipt
                  WHERE receipt.problem_id = unit.problem_id
                    AND receipt.problem_revision_id = unit.problem_revision_id
              )
              OR (
                  binding.accepted_at_epoch_millis = (
                      SELECT MAX(receipt.accepted_at_epoch_millis)
                      FROM problem_organization_receipt AS receipt
                      WHERE receipt.problem_id = unit.problem_id
                        AND receipt.problem_revision_id = unit.problem_revision_id
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM problem_classification_binding AS classification
                      WHERE classification.problem_id = unit.problem_id
                        AND classification.basis_revision_id = unit.problem_revision_id
                        AND classification.dimension = 'KNOWLEDGE'
                        AND classification.taxonomy_version = binding.taxonomy_version
                        AND classification.accepted_at_epoch_millis =
                            binding.accepted_at_epoch_millis
                  )
              )
          )
        ORDER BY binding.knowledge_node_id
        """,
    )
    fun observeCurrentKnowledgeNodeIds(
        problemId: String,
        problemRevisionId: String,
    ): Flow<List<String>>

    @Query(
        """
        DELETE FROM practice_unit_knowledge_binding
        WHERE practice_unit_id = :practiceUnitId
          AND basis_revision_id = :problemRevisionId
          AND NOT EXISTS (
              SELECT 1 FROM assessment_evidence_attribution AS attribution
              WHERE attribution.binding_id = practice_unit_knowledge_binding.binding_id
                AND attribution.practice_unit_id = practice_unit_knowledge_binding.practice_unit_id
                AND attribution.knowledge_node_id = practice_unit_knowledge_binding.knowledge_node_id
                AND attribution.basis_revision_id = practice_unit_knowledge_binding.basis_revision_id
                AND attribution.taxonomy_version = practice_unit_knowledge_binding.taxonomy_version
          )
        """,
    )
    suspend fun deleteUnreferencedKnowledgeBindings(
        practiceUnitId: String,
        problemRevisionId: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClassificationBindings(
        bindings: List<ProblemClassificationBindingEntity>,
    ): List<Long>

    @Query("SELECT * FROM problem_classification_binding WHERE binding_id = :id")
    suspend fun readClassificationBinding(id: String): ProblemClassificationBindingEntity?

    @Query(
        """
        DELETE FROM problem_classification_binding
        WHERE problem_id = :problemId
          AND basis_revision_id = :problemRevisionId
        """,
    )
    suspend fun deleteClassifications(
        problemId: String,
        problemRevisionId: String,
    ): Int

    @Query(
        """
        SELECT DISTINCT dimension FROM problem_classification_binding
        WHERE problem_id = :problemId
          AND basis_revision_id = :problemRevisionId
        """,
    )
    suspend fun readClassificationDimensions(
        problemId: String,
        problemRevisionId: String,
    ): List<String>

    @Query(
        """
        SELECT DISTINCT acceptance_source FROM problem_classification_binding
        WHERE problem_id = :problemId
          AND basis_revision_id = :problemRevisionId
        """,
    )
    suspend fun readClassificationAcceptanceSources(
        problemId: String,
        problemRevisionId: String,
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRelations(relations: List<ProblemRelationEntity>): List<Long>

    @Query("SELECT * FROM problem_relation WHERE relation_id = :id")
    suspend fun readRelation(id: String): ProblemRelationEntity?

    @Query(
        """
        DELETE FROM problem_relation
        WHERE source_problem_id = :problemId
          AND source_basis_revision_id = :problemRevisionId
        """,
    )
    suspend fun deleteOutgoingRelations(
        problemId: String,
        problemRevisionId: String,
    ): Int

    @Query(
        """
        DELETE FROM problem_relation
        WHERE source_problem_id = :problemId
          AND source_basis_revision_id = :problemRevisionId
          AND relation_id IN (:relationIds)
        """,
    )
    suspend fun deleteOutgoingRelationsById(
        problemId: String,
        problemRevisionId: String,
        relationIds: List<String>,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: ProblemOrganizationReceiptEntity): Long

    @Query("SELECT * FROM problem_organization_receipt WHERE command_id = :commandId")
    suspend fun readReceipt(commandId: String): ProblemOrganizationReceiptEntity?

    @Query(
        """
        SELECT * FROM problem_organization_receipt
        WHERE problem_id = :problemId
          AND problem_revision_id = :problemRevisionId
        ORDER BY accepted_at_epoch_millis DESC, command_id DESC
        LIMIT 1
        """,
    )
    suspend fun readLatestReceipt(
        problemId: String,
        problemRevisionId: String,
    ): ProblemOrganizationReceiptEntity?

    @Query(
        """
        SELECT * FROM problem_classification_binding
        WHERE problem_id = :problemId AND basis_revision_id = :problemRevisionId
          AND dimension IN ('SUBJECT', 'CHAPTER', 'KNOWLEDGE')
        ORDER BY dimension ASC, display_name ASC, binding_id ASC
        """,
    )
    fun observeClassifications(
        problemId: String,
        problemRevisionId: String,
    ): Flow<List<ProblemClassificationBindingEntity>>

    @Query(
        """
        SELECT * FROM problem_relation
        WHERE source_problem_id = :problemId
          AND source_basis_revision_id = :problemRevisionId
          AND status = 'ACTIVE'
        ORDER BY relation_type ASC, target_problem_id ASC, relation_id ASC
        """,
    )
    fun observeRelations(
        problemId: String,
        problemRevisionId: String,
    ): Flow<List<ProblemRelationEntity>>
}

/** `readKnowledgeNodeSuccessors` 的行投影：一条合并重定向的原始边。 */
internal data class KnowledgeNodeSuccessorRow(
    val retiredId: String,
    val successorId: String,
)
