package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity

@Dao
internal interface KnowledgeNodeRelationDao {
    @Query("SELECT COUNT(*) FROM knowledge_node_relation WHERE subject = :subject")
    suspend fun countBySubject(subject: String): Int

    @Query(
        """
        SELECT * FROM knowledge_node_relation
        WHERE subject = :subject
        ORDER BY relation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readBySubject(subject: String, limit: Int): List<KnowledgeNodeRelationEntity>

    /**
     * 某个知识点**作为被依赖方**的所有关系行。
     *
     * `relation_type` 必须显式传进来，而不是"反正这张表只有一种类型"：调用方
     * （`KnowledgePrerequisiteReader` 的前置图）**只认** `PREREQUISITE_OF`，
     * 而这个查询原先会把表里所有行都交出去。今天安全只是因为
     * `KnowledgeNodeRelationContract` 恰好只允许那一种类型，也就是说这条查询的语义
     * 依赖了一条**在别处维护、且可以在别处被放宽**的不变量（审计 R-10：
     * 「将来引入第二种关系类型就静默串味」）。把它写进 SQL 之后，第二张类型出现时
     * 前置图不会多出几条"其实不是前置"的边。
     */
    @Query(
        """
        SELECT * FROM knowledge_node_relation
        WHERE subject = :subject
          AND relation_type = :relationType
          AND dependent_knowledge_node_id IN (:dependentKnowledgeNodeIds)
        ORDER BY dependent_knowledge_node_id ASC, prerequisite_knowledge_node_id ASC, relation_id ASC
        """,
    )
    suspend fun readForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
        relationType: String,
    ): List<KnowledgeNodeRelationEntity>

    @Query("SELECT * FROM knowledge_node_relation WHERE relation_id IN (:ids)")
    suspend fun readByIds(ids: Set<String>): List<KnowledgeNodeRelationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(relations: List<KnowledgeNodeRelationEntity>): List<Long>

    @Transaction
    suspend fun importAll(relations: List<KnowledgeNodeRelationEntity>) {
        if (relations.isEmpty()) return
        insertAll(relations)
        // API 23 devices can expose SQLite's older 999-host-parameter limit.
        val actual = relations
            .map(KnowledgeNodeRelationEntity::relationId)
            .distinct()
            .chunked(RELATION_ID_QUERY_CHUNK_SIZE)
            .flatMap { ids -> readByIds(ids.toSet()) }
            .associateBy(KnowledgeNodeRelationEntity::relationId)
        relations.forEach { expected ->
            if (actual[expected.relationId] != expected) {
                throw ImmutablePayloadConflictException(
                    entityType = "knowledgeNodeRelation",
                    entityId = expected.relationId,
                )
            }
        }
    }

    private companion object {
        const val RELATION_ID_QUERY_CHUNK_SIZE = 400
    }
}
