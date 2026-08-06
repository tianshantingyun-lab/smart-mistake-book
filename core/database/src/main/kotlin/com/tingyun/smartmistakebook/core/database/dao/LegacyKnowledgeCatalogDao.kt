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
import kotlinx.coroutines.flow.Flow

internal data class ReviewedKnowledgeCoverageRow(
    val subject: String,
    val topicCount: Int,
    val atomicKnowledgeCount: Int,
    val reviewedSourceCount: Int,
    val latestReviewedAtEpochMillis: Long,
)

/**
 * Transitional access to the embedded legacy catalog.
 *
 * New organization confirmation deliberately has no reference to this DAO. The independent
 * high-school catalog is reached through its typed read contract in core:data.
 */
@Dao
internal interface LegacyKnowledgeCatalogDao {
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
        WHERE node.verification_status IN ('CURATED', 'SOURCE_GROUNDED')
        GROUP BY node.subject
        ORDER BY node.subject ASC
        """,
    )
    fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRow>>

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
          AND verification_status IN ('CURATED', 'SOURCE_GROUNDED')
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
          AND node.verification_status IN ('CURATED', 'SOURCE_GROUNDED')
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
          AND verification_status IN ('CURATED', 'SOURCE_GROUNDED')
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
}
