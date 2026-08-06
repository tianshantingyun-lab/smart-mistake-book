package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.ColumnInfo
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemClassificationBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ProblemOrganizationDao {
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
          AND knowledge_subject = :knowledgeSubject
          AND knowledge_taxonomy_version = :knowledgeTaxonomyVersion
          AND knowledge_pack_version = :knowledgePackVersion
          AND basis_revision_id = :problemRevisionId
          AND taxonomy_version = :taxonomyVersion
          AND knowledge_reference_status = 'VERIFIED_AT_CONFIRMATION'
        LIMIT 1
        """,
    )
    suspend fun readKnowledgeBindingByIdentity(
        practiceUnitId: String,
        knowledgeNodeId: String,
        knowledgeSubject: String,
        knowledgeTaxonomyVersion: String,
        knowledgePackVersion: String,
        problemRevisionId: String,
        taxonomyVersion: String,
    ): PracticeUnitKnowledgeBindingEntity?

    @Query(
        """
        SELECT DISTINCT
            binding.knowledge_node_id,
            binding.knowledge_subject,
            binding.knowledge_taxonomy_version,
            binding.knowledge_pack_version,
            binding.knowledge_manifest_fingerprint,
            binding.knowledge_activation_generation
        FROM practice_unit_knowledge_binding AS binding
        INNER JOIN practice_unit AS unit
          ON unit.practice_unit_id = binding.practice_unit_id
         AND unit.problem_revision_id = binding.basis_revision_id
        WHERE unit.problem_id = :problemId
          AND unit.problem_revision_id = :problemRevisionId
          AND binding.knowledge_reference_status = 'VERIFIED_AT_CONFIRMATION'
          AND binding.knowledge_subject IS NOT NULL
          AND binding.knowledge_taxonomy_version IS NOT NULL
          AND binding.knowledge_pack_version IS NOT NULL
          AND binding.knowledge_manifest_fingerprint IS NOT NULL
          AND binding.knowledge_activation_generation > 0
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
    fun observeCurrentKnowledgeBindings(
        problemId: String,
        problemRevisionId: String,
    ): Flow<List<CurrentKnowledgeBindingProjection>>

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

internal data class CurrentKnowledgeBindingProjection(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "knowledge_subject")
    val knowledgeSubject: String,
    @ColumnInfo(name = "knowledge_taxonomy_version")
    val knowledgeTaxonomyVersion: String,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "knowledge_manifest_fingerprint")
    val knowledgeManifestFingerprint: String,
    @ColumnInfo(name = "knowledge_activation_generation")
    val knowledgeActivationGeneration: Long,
)
