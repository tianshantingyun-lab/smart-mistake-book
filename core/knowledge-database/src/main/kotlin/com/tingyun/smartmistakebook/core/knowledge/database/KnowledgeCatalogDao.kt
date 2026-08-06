package com.tingyun.smartmistakebook.core.knowledge.database

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

internal data class KnowledgeSearchRow(
    @Embedded
    val node: KnowledgeNodeEntity,
    @ColumnInfo(name = "matched_feature_count")
    val matchedFeatureCount: Long,
    @ColumnInfo(name = "best_rank_weight")
    val bestRankWeight: Int,
)

internal data class KnowledgeRelationForOriginRow(
    @Embedded
    val relation: KnowledgeNodeRelationEntity,
    @ColumnInfo(name = "origin_knowledge_node_id")
    val originKnowledgeNodeId: String,
    @ColumnInfo(name = "origin_rank")
    val originRank: Int,
)

internal data class KnowledgeTeachingMaterialRow(
    @Embedded
    val material: KnowledgeTeachingMaterialEntity,
    @ColumnInfo(name = "binding_role")
    val role: String,
)

internal data class KnowledgeNodeDisplayRow(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    val subject: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "node_kind")
    val nodeKind: String,
    val granularity: String,
    @ColumnInfo(name = "parent_knowledge_node_id")
    val parentKnowledgeNodeId: String?,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
)

internal data class KnowledgeNodeDisplayOrderRow(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    val subject: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "display_order_token")
    val displayOrderToken: String,
)

internal data class KnowledgeTeachingMaterialSummaryRow(
    @ColumnInfo(name = "material_id")
    val materialId: String,
    @ColumnInfo(name = "stable_code")
    val stableCode: String,
    val subject: String,
    @ColumnInfo(name = "material_type")
    val materialType: String,
    val title: String,
    @ColumnInfo(name = "summary_markdown")
    val summaryMarkdown: String,
    @ColumnInfo(name = "applicability_markdown")
    val applicabilityMarkdown: String,
    @ColumnInfo(name = "boundary_markdown")
    val boundaryMarkdown: String,
    @ColumnInfo(name = "derivation_kind")
    val derivationKind: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long,
    @ColumnInfo(name = "content_markdown_length")
    val contentMarkdownLength: Int,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "binding_role")
    val role: String,
)

internal data class KnowledgeTeachingMaterialBodyRow(
    @ColumnInfo(name = "material_id")
    val materialId: String,
    @ColumnInfo(name = "content_markdown")
    val contentMarkdown: String,
)

@Dao
internal interface KnowledgeCatalogDao {
    @Query(
        """
        SELECT * FROM knowledge_pack_manifest
        WHERE manifest_key = 'active'
        LIMIT 1
        """,
    )
    suspend fun readManifest(): KnowledgePackManifestEntity?

    @Query(
        """
        SELECT * FROM knowledge_node
        WHERE subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
        LIMIT 1
        """,
    )
    suspend fun findNode(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): KnowledgeNodeEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM knowledge_node
            WHERE subject = :subject
              AND knowledge_node_id = :knowledgeNodeId
              AND taxonomy_version = :taxonomyVersion
        )
        """,
    )
    suspend fun containsNodeReference(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): Boolean

    @Query(
        """
        SELECT knowledge_node_id,
               subject,
               display_name,
               node_kind,
               granularity,
               parent_knowledge_node_id,
               taxonomy_version
        FROM knowledge_node
        WHERE taxonomy_version = :taxonomyVersion
          AND knowledge_node_id IN (:knowledgeNodeIds)
        ORDER BY knowledge_node_id ASC
        LIMIT :rowLimit
        """,
    )
    suspend fun findNodes(
        taxonomyVersion: String,
        knowledgeNodeIds: List<String>,
        rowLimit: Int,
    ): List<KnowledgeNodeDisplayRow>

    @Query(
        """
        SELECT node.*
        FROM knowledge_node AS node
        WHERE node.subject = :subject
          AND node.taxonomy_version = :taxonomyVersion
          AND node.knowledge_node_id IN (:knowledgeNodeIds)
        ORDER BY node.knowledge_node_id ASC
        LIMIT :rowLimit
        """,
    )
    suspend fun findCatalogNodes(
        subject: String,
        taxonomyVersion: String,
        knowledgeNodeIds: List<String>,
        rowLimit: Int,
    ): List<KnowledgeNodeEntity>

    @Query(
        """
        WITH RECURSIVE display_tree(
            knowledge_node_id,
            subject,
            taxonomy_version,
            display_order_token
        ) AS (
            SELECT
                root.knowledge_node_id,
                root.subject,
                root.taxonomy_version,
                lower(hex(root.stable_code))
            FROM knowledge_node AS root
            WHERE root.subject = :subject
              AND root.taxonomy_version = :taxonomyVersion
              AND root.parent_knowledge_node_id IS NULL
            UNION ALL
            SELECT
                child.knowledge_node_id,
                child.subject,
                child.taxonomy_version,
                parent.display_order_token || '00' || lower(hex(child.stable_code))
            FROM knowledge_node AS child
            INNER JOIN display_tree AS parent
                ON child.parent_knowledge_node_id = parent.knowledge_node_id
               AND child.subject = parent.subject
               AND child.taxonomy_version = parent.taxonomy_version
        )
        SELECT
            knowledge_node_id,
            subject,
            taxonomy_version,
            display_order_token
        FROM display_tree
        WHERE :afterOrderToken IS NULL OR display_order_token > :afterOrderToken
        ORDER BY display_order_token ASC
        LIMIT :rowLimit
        """,
    )
    suspend fun readDisplayOrderPage(
        subject: String,
        taxonomyVersion: String,
        afterOrderToken: String?,
        rowLimit: Int,
    ): List<KnowledgeNodeDisplayOrderRow>

    @Query(
        """
        SELECT
          node.*,
          COUNT(DISTINCT feature.search_feature) AS matched_feature_count,
          MAX(feature.rank_weight) AS best_rank_weight
        FROM knowledge_search_feature AS feature
        INNER JOIN knowledge_node AS node
          ON node.knowledge_node_id = feature.knowledge_node_id
         AND node.subject = feature.subject
        WHERE feature.subject = :subject
          AND node.taxonomy_version = :taxonomyVersion
          AND feature.search_feature IN (:features)
        GROUP BY node.knowledge_node_id
        ORDER BY
          best_rank_weight DESC,
          matched_feature_count DESC,
          node.stable_code ASC,
          node.knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    suspend fun recall(
        subject: String,
        taxonomyVersion: String,
        features: List<String>,
        limit: Int,
    ): List<KnowledgeSearchRow>

    @Query(
        """
        WITH relation_candidates AS (
          SELECT outgoing.*
               , outgoing.from_knowledge_node_id AS origin_knowledge_node_id
          FROM knowledge_node_relation AS outgoing
          WHERE outgoing.subject = :subject
            AND outgoing.taxonomy_version = :taxonomyVersion
            AND outgoing.from_knowledge_node_id IN (:knowledgeNodeIds)
          UNION ALL
          SELECT incoming.*
               , incoming.to_knowledge_node_id AS origin_knowledge_node_id
          FROM knowledge_node_relation AS incoming
          WHERE incoming.subject = :subject
            AND incoming.taxonomy_version = :taxonomyVersion
            AND incoming.to_knowledge_node_id IN (:knowledgeNodeIds)
        ),
        ranked_relations AS (
          SELECT relation_candidates.*,
                 ROW_NUMBER() OVER (
                   PARTITION BY origin_knowledge_node_id
                   ORDER BY relation_type ASC,
                            from_knowledge_node_id ASC,
                            to_knowledge_node_id ASC,
                            relation_id ASC
                 ) AS origin_rank
          FROM relation_candidates
        )
        SELECT *
        FROM ranked_relations
        WHERE origin_rank <= :perNodeLimit
        ORDER BY origin_knowledge_node_id ASC,
                 origin_rank ASC,
                 relation_id ASC
        LIMIT :rowLimit
        """,
    )
    suspend fun readRelationsForNodes(
        subject: String,
        taxonomyVersion: String,
        knowledgeNodeIds: List<String>,
        perNodeLimit: Int,
        rowLimit: Int,
    ): List<KnowledgeRelationForOriginRow>

    @Query(
        """
        SELECT relation.* FROM knowledge_node_relation AS relation
        INNER JOIN knowledge_node AS from_node
          ON from_node.knowledge_node_id = relation.from_knowledge_node_id
         AND from_node.subject = relation.subject
         AND from_node.taxonomy_version = relation.taxonomy_version
        INNER JOIN knowledge_node AS to_node
          ON to_node.knowledge_node_id = relation.to_knowledge_node_id
         AND to_node.subject = relation.subject
         AND to_node.taxonomy_version = relation.taxonomy_version
        WHERE relation.subject = :subject
          AND relation.taxonomy_version = :taxonomyVersion
          AND relation.from_knowledge_node_id = :knowledgeNodeId
        ORDER BY relation.relation_type ASC,
                 relation.to_knowledge_node_id ASC,
                 relation.relation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readOutgoingRelations(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        limit: Int,
    ): List<KnowledgeNodeRelationEntity>

    @Query(
        """
        SELECT relation.* FROM knowledge_node_relation AS relation
        INNER JOIN knowledge_node AS from_node
          ON from_node.knowledge_node_id = relation.from_knowledge_node_id
         AND from_node.subject = relation.subject
         AND from_node.taxonomy_version = relation.taxonomy_version
        INNER JOIN knowledge_node AS to_node
          ON to_node.knowledge_node_id = relation.to_knowledge_node_id
         AND to_node.subject = relation.subject
         AND to_node.taxonomy_version = relation.taxonomy_version
        WHERE relation.subject = :subject
          AND relation.taxonomy_version = :taxonomyVersion
          AND relation.from_knowledge_node_id = :knowledgeNodeId
          AND relation.relation_type IN (:relationTypes)
        ORDER BY relation.relation_type ASC,
                 relation.to_knowledge_node_id ASC,
                 relation.relation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readOutgoingRelationsOfTypes(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        relationTypes: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeRelationEntity>

    @Query(
        """
        SELECT relation.* FROM knowledge_node_relation AS relation
        INNER JOIN knowledge_node AS from_node
          ON from_node.knowledge_node_id = relation.from_knowledge_node_id
         AND from_node.subject = relation.subject
         AND from_node.taxonomy_version = relation.taxonomy_version
        INNER JOIN knowledge_node AS to_node
          ON to_node.knowledge_node_id = relation.to_knowledge_node_id
         AND to_node.subject = relation.subject
         AND to_node.taxonomy_version = relation.taxonomy_version
        WHERE relation.subject = :subject
          AND relation.taxonomy_version = :taxonomyVersion
          AND relation.to_knowledge_node_id = :knowledgeNodeId
        ORDER BY relation.relation_type ASC,
                 relation.from_knowledge_node_id ASC,
                 relation.relation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readIncomingRelations(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        limit: Int,
    ): List<KnowledgeNodeRelationEntity>

    @Query(
        """
        SELECT relation.* FROM knowledge_node_relation AS relation
        INNER JOIN knowledge_node AS from_node
          ON from_node.knowledge_node_id = relation.from_knowledge_node_id
         AND from_node.subject = relation.subject
         AND from_node.taxonomy_version = relation.taxonomy_version
        INNER JOIN knowledge_node AS to_node
          ON to_node.knowledge_node_id = relation.to_knowledge_node_id
         AND to_node.subject = relation.subject
         AND to_node.taxonomy_version = relation.taxonomy_version
        WHERE relation.subject = :subject
          AND relation.taxonomy_version = :taxonomyVersion
          AND relation.to_knowledge_node_id = :knowledgeNodeId
          AND relation.relation_type IN (:relationTypes)
        ORDER BY relation.relation_type ASC,
                 relation.from_knowledge_node_id ASC,
                 relation.relation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readIncomingRelationsOfTypes(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        relationTypes: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeRelationEntity>

    @Query(
        """
        SELECT material.*, binding.role AS binding_role
        FROM knowledge_teaching_material_node_binding AS binding
        INNER JOIN knowledge_teaching_material AS material
          ON material.material_id = binding.material_id
        INNER JOIN knowledge_node AS node
          ON node.knowledge_node_id = binding.knowledge_node_id
        WHERE node.subject = :subject
          AND node.knowledge_node_id = :knowledgeNodeId
          AND node.taxonomy_version = :taxonomyVersion
          AND material.subject = :subject
        ORDER BY
          CASE binding.role
            WHEN 'PRIMARY' THEN 0
            WHEN 'SUPPORTING' THEN 1
            WHEN 'PREREQUISITE' THEN 2
            ELSE 3
          END ASC,
          CASE material.material_type
            WHEN 'CONCEPT_EXPLANATION' THEN 0
            WHEN 'METHOD_MODEL' THEN 1
            WHEN 'DERIVATION' THEN 2
            WHEN 'MISCONCEPTION_GUIDE' THEN 3
            WHEN 'REPRESENTATION_GUIDE' THEN 4
            WHEN 'WORKED_EXAMPLE' THEN 5
            WHEN 'COMPLETE_SOLUTION' THEN 6
            ELSE 7
          END ASC,
          material.stable_code ASC,
          material.material_id ASC
        LIMIT :limit
        """,
    )
    suspend fun readTeachingMaterials(
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRow>

    @Query(
        """
        SELECT
          material.material_id,
          material.stable_code,
          material.subject,
          material.material_type,
          material.title,
          material.summary_markdown,
          material.applicability_markdown,
          material.boundary_markdown,
          material.derivation_kind,
          material.source_id,
          material.source_locator,
          material.content_fingerprint,
          material.reviewed_at_epoch_millis,
          length(material.content_markdown) AS content_markdown_length,
          binding.knowledge_node_id,
          binding.role AS binding_role
        FROM knowledge_teaching_material_node_binding AS binding
        INNER JOIN knowledge_teaching_material AS material
          ON material.material_id = binding.material_id
        INNER JOIN knowledge_node AS node
          ON node.knowledge_node_id = binding.knowledge_node_id
        WHERE node.subject = :subject
          AND node.taxonomy_version = :taxonomyVersion
          AND node.knowledge_node_id IN (:knowledgeNodeIds)
          AND material.subject = :subject
        ORDER BY
          CASE binding.role
            WHEN 'PRIMARY' THEN 0
            WHEN 'SUPPORTING' THEN 1
            WHEN 'PREREQUISITE' THEN 2
            ELSE 3
          END ASC,
          CASE material.material_type
            WHEN 'CONCEPT_EXPLANATION' THEN 0
            WHEN 'METHOD_MODEL' THEN 1
            WHEN 'DERIVATION' THEN 2
            WHEN 'MISCONCEPTION_GUIDE' THEN 3
            WHEN 'REPRESENTATION_GUIDE' THEN 4
            WHEN 'WORKED_EXAMPLE' THEN 5
            WHEN 'COMPLETE_SOLUTION' THEN 6
            ELSE 7
          END ASC,
          material.stable_code ASC,
          material.material_id ASC,
          binding.knowledge_node_id ASC
        LIMIT :rowLimit
        """,
    )
    suspend fun readTeachingMaterialSummaries(
        subject: String,
        taxonomyVersion: String,
        knowledgeNodeIds: List<String>,
        rowLimit: Int,
    ): List<KnowledgeTeachingMaterialSummaryRow>

    @Query(
        """
        SELECT material_id, content_markdown
        FROM knowledge_teaching_material
        WHERE material_id IN (:materialIds)
        ORDER BY material_id ASC
        LIMIT :rowLimit
        """,
    )
    suspend fun readTeachingMaterialBodies(
        materialIds: List<String>,
        rowLimit: Int,
    ): List<KnowledgeTeachingMaterialBodyRow>
}

/**
 * Forces Room's schema gate before a closed pack enters streamed content verification.
 * Runtime callers never receive this DAO, and it deliberately exposes no bulk content reads.
 */
@Dao
internal interface KnowledgeCatalogVerificationDao {
    @Query(
        """
        SELECT manifest_key, pack_id, schema_version, knowledge_pack_version,
               taxonomy_version, search_index_version, content_fingerprint,
               built_at_epoch_millis, node_count, source_count, relation_count,
               material_count, search_feature_count
        FROM knowledge_pack_manifest
        WHERE manifest_key = 'active'
        LIMIT 1
        """,
    )
    suspend fun readManifest(): KnowledgePackManifestEntity?
}

@Dao
internal abstract class KnowledgeCatalogInstallDao {
    @Query("DELETE FROM knowledge_teaching_material_node_binding")
    protected abstract suspend fun deleteMaterialBindings()

    @Query("DELETE FROM knowledge_teaching_material")
    protected abstract suspend fun deleteMaterials()

    @Query("DELETE FROM knowledge_search_feature")
    protected abstract suspend fun deleteSearchFeatures()

    @Query("DELETE FROM knowledge_node_relation")
    protected abstract suspend fun deleteRelations()

    @Query("DELETE FROM knowledge_node_source_binding")
    protected abstract suspend fun deleteNodeSourceBindings()

    @Query("DELETE FROM knowledge_node")
    protected abstract suspend fun deleteNodes()

    @Query("DELETE FROM knowledge_source")
    protected abstract suspend fun deleteSources()

    @Query("DELETE FROM knowledge_pack_manifest")
    protected abstract suspend fun deleteManifest()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertManifest(manifest: KnowledgePackManifestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertNodes(nodes: List<KnowledgeNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSources(sources: List<KnowledgeSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertNodeSourceBindings(
        bindings: List<KnowledgeNodeSourceBindingEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRelations(relations: List<KnowledgeNodeRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSearchFeatures(
        features: List<KnowledgeSearchFeatureEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMaterials(materials: List<KnowledgeTeachingMaterialEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMaterialBindings(
        bindings: List<KnowledgeTeachingMaterialNodeBindingEntity>,
    )

    @Transaction
    open suspend fun replacePack(bundle: ValidatedKnowledgePack) {
        deleteMaterialBindings()
        deleteMaterials()
        deleteSearchFeatures()
        deleteRelations()
        deleteNodeSourceBindings()
        deleteNodes()
        deleteSources()
        deleteManifest()

        bundle.sources.insertWhenNotEmpty(::insertSources)
        bundle.nodes.insertWhenNotEmpty(::insertNodes)
        bundle.nodeSourceBindings.insertWhenNotEmpty(::insertNodeSourceBindings)
        bundle.relations.insertWhenNotEmpty(::insertRelations)
        bundle.searchFeatures.insertWhenNotEmpty(::insertSearchFeatures)
        bundle.materials.insertWhenNotEmpty(::insertMaterials)
        bundle.materialBindings.insertWhenNotEmpty(::insertMaterialBindings)
        insertManifest(bundle.manifest)
    }
}

private suspend fun <T> List<T>.insertWhenNotEmpty(insert: suspend (List<T>) -> Unit) {
    if (isNotEmpty()) insert(this)
}
