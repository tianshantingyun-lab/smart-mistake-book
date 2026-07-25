package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity

@Dao
internal interface KnowledgeTeachingMaterialDao {
    @Query(
        """
        SELECT material.*
        FROM knowledge_teaching_material AS material
        INNER JOIN (
          SELECT
            material_id,
            MIN(
              CASE role
                WHEN 'PRIMARY' THEN 0
                WHEN 'SUPPORTING' THEN 1
                ELSE 2
              END
            ) AS best_role_rank
          FROM knowledge_teaching_material_node_binding
          WHERE knowledge_node_id IN (:knowledgeNodeIds)
          GROUP BY material_id
        ) AS matched
          ON matched.material_id = material.material_id
        WHERE material.subject = :subject
        ORDER BY
          matched.best_role_rank ASC,
          CASE material.material_type
            WHEN 'METHOD_MODEL' THEN 0
            WHEN 'CONCEPT_EXPLANATION' THEN 1
            WHEN 'DERIVATION' THEN 2
            WHEN 'REPRESENTATION_GUIDE' THEN 3
            WHEN 'WORKED_EXAMPLE' THEN 4
            WHEN 'COMPLETE_SOLUTION' THEN 5
            WHEN 'MISCONCEPTION_GUIDE' THEN 6
            ELSE 7
          END,
          material.title ASC,
          material.material_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readForKnowledgeNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialEntity>

    @Query("SELECT * FROM knowledge_teaching_material WHERE material_id IN (:ids)")
    suspend fun readByIds(ids: Set<String>): List<KnowledgeTeachingMaterialEntity>

    @Query(
        """
        SELECT * FROM knowledge_teaching_material_node_binding
        WHERE material_id IN (:materialIds)
        ORDER BY material_id ASC, knowledge_node_id ASC
        """,
    )
    suspend fun readBindingsForMaterials(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMaterials(materials: List<KnowledgeTeachingMaterialEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBindings(
        bindings: List<KnowledgeTeachingMaterialNodeBindingEntity>,
    ): List<Long>

    @Transaction
    suspend fun importAll(
        materials: List<KnowledgeTeachingMaterialEntity>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingEntity>,
    ) {
        if (materials.isEmpty() && bindings.isEmpty()) return
        insertMaterials(materials)
        insertBindings(bindings)

        val actualMaterials = materials
            .map(KnowledgeTeachingMaterialEntity::materialId)
            .distinct()
            .chunked(SQLITE_ID_CHUNK_SIZE)
            .flatMap { ids -> readByIds(ids.toSet()) }
            .associateBy(KnowledgeTeachingMaterialEntity::materialId)
        materials.forEach { expected ->
            if (actualMaterials[expected.materialId] != expected) {
                throw ImmutablePayloadConflictException(
                    entityType = "knowledgeTeachingMaterial",
                    entityId = expected.materialId,
                )
            }
        }

        val actualBindings = materials
            .map(KnowledgeTeachingMaterialEntity::materialId)
            .distinct()
            .chunked(SQLITE_ID_CHUNK_SIZE)
            .flatMap { ids -> readBindingsForMaterials(ids.toSet()) }
            .associateBy { it.materialId to it.knowledgeNodeId }
        bindings.forEach { expected ->
            if (actualBindings[expected.materialId to expected.knowledgeNodeId] != expected) {
                throw ImmutablePayloadConflictException(
                    entityType = "knowledgeTeachingMaterialNodeBinding",
                    entityId = "${expected.materialId}:${expected.knowledgeNodeId}",
                )
            }
        }
    }

    private companion object {
        const val SQLITE_ID_CHUNK_SIZE = 400
    }
}
