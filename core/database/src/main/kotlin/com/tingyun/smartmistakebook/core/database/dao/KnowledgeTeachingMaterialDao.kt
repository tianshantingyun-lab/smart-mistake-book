package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
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
          AND material.status != 'RETIRED'
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

    // ---- 内容调和用的读/写面（见 BundledKnowledgeBaseInstaller）----

    /**
     * 某个内容包登记在库里的**全部**材料。按 `stable_code` 前缀匹配——它的构法是
     * `"$packId:$subject:teaching:$slug"`，所以前缀 `"$packId:"` 恰好圈定这个包，
     * 不会把另一个包的侧车混进来。
     */
    @Query("SELECT * FROM knowledge_teaching_material WHERE stable_code LIKE :packPrefix || '%'")
    suspend fun readByStableCodePrefix(packPrefix: String): List<KnowledgeTeachingMaterialEntity>

    /** upsert 而非 REPLACE：REPLACE 先删后插，会撞上引用材料的 RESTRICT 外键。 */
    @Upsert
    suspend fun upsertMaterials(materials: List<KnowledgeTeachingMaterialEntity>)

    /**
     * 退役一条材料。学生可能用过它生成的复习题，所以**永不物删**；退役后它不再进
     * 新的讲题参考与复习题。`status != 'RETIRED'` 保证重复调用是空操作。
     */
    @Query(
        "UPDATE knowledge_teaching_material SET status = 'RETIRED' " +
            "WHERE material_id = :id AND status != 'RETIRED'",
    )
    suspend fun retireMaterial(id: String): Int

    @Query("DELETE FROM knowledge_teaching_material_node_binding WHERE material_id IN (:materialIds)")
    suspend fun deleteBindingsForMaterials(materialIds: Set<String>)

    /** 按复合主键删一条绑定（绑定是纯内容，无学生数据引用，可真删）。 */
    @Query(
        "DELETE FROM knowledge_teaching_material_node_binding " +
            "WHERE material_id = :materialId AND knowledge_node_id = :knowledgeNodeId",
    )
    suspend fun deleteBinding(materialId: String, knowledgeNodeId: String)

    @Upsert
    suspend fun upsertBindings(bindings: List<KnowledgeTeachingMaterialNodeBindingEntity>)

    /**
     * 这些材料里**仍有绑定**的那些。
     *
     * 调和用它找出"绑定全部失效"的材料：目标节点退役或移出包之后，材料会剩 0 条绑定——
     * 而检索、讲题参考、复习题全都要经过绑定表，所以 0 绑定的材料等于不可达。
     * 不退役它就会留一条永远进不了任何链路的行，也会让"材料总数"这类统计虚高。
     */
    @Query(
        "SELECT DISTINCT material_id FROM knowledge_teaching_material_node_binding " +
            "WHERE material_id IN (:materialIds)",
    )
    suspend fun readBoundMaterialIds(materialIds: Set<String>): List<String>

    /**
     * 某条材料当前的绑定——调和时用来判断"绑定要不要改"。绑定表是纯内容
     * （没有学生数据引用它），所以按 (material_id, knowledge_node_id) 做主键 upsert/删除即可。
     */
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
