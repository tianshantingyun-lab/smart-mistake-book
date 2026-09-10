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
    /**
     * 按（绑定角色 → 重教类型优先级 → title → material_id）取该科目下命中指定知识点的讲解材料。
     *
     * **类型 CASE 必须与 `TutorTeachingReferenceSelector.reTeachPriority` 逐值一致。**
     * 这里的顺序决定哪些行进入 `LIMIT`（因而进入调用方的字符预算），所以它不只是"提示"；
     * 而可测的权威表达在那个纯函数里，两处若漂移会让"预算先给了哪类材料"重新变成未定义行为。
     * 语义依据（misconception-guide / worked-example 优先、complete-solution 最后）见
     * `docs/research/leech-remediation-research.md` R5/R6。
     */
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
            WHEN 'MISCONCEPTION_GUIDE' THEN 0
            WHEN 'WORKED_EXAMPLE' THEN 1
            WHEN 'METHOD_MODEL' THEN 2
            WHEN 'DERIVATION' THEN 3
            WHEN 'CONCEPT_EXPLANATION' THEN 4
            WHEN 'REPRESENTATION_GUIDE' THEN 5
            WHEN 'COMPLETE_SOLUTION' THEN 6
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
