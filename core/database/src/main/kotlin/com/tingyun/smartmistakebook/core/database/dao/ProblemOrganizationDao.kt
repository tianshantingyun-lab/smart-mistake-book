package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
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

    @Query(
        """
        SELECT * FROM knowledge_node
        WHERE subject = :subject
          AND verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
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

    @Query(
        """
        SELECT COUNT(*) FROM knowledge_node
        WHERE subject = :subject
          AND verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
        """,
    )
    suspend fun countReviewedKnowledgeNodesBySubject(subject: String): Int

    @Query(
        """
        SELECT COUNT(DISTINCT knowledge_node_id)
        FROM knowledge_search_feature
        WHERE subject = :subject
        """,
    )
    suspend fun countIndexedKnowledgeNodesBySubject(subject: String): Int

    @Query("SELECT * FROM knowledge_node WHERE knowledge_node_id IN (:ids)")
    suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_source WHERE source_id IN (:ids)")
    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceEntity>

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
