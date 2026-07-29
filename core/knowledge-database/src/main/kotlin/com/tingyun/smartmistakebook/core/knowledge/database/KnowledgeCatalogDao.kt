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
        SELECT
          node.*,
          COUNT(DISTINCT feature.search_feature) AS matched_feature_count,
          MAX(feature.rank_weight) AS best_rank_weight
        FROM knowledge_search_feature AS feature
        INNER JOIN knowledge_node AS node
          ON node.knowledge_node_id = feature.knowledge_node_id
         AND node.subject = feature.subject
        WHERE feature.subject = :subject
          AND feature.search_feature IN (:features)
        GROUP BY node.knowledge_node_id
        ORDER BY
          matched_feature_count DESC,
          best_rank_weight DESC,
          node.stable_code ASC,
          node.knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    suspend fun recall(
        subject: String,
        features: List<String>,
        limit: Int,
    ): List<KnowledgeSearchRow>

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
    open suspend fun replacePack(bundle: KnowledgePackInstallBundle) {
        val parentFirstNodes = bundle.validateAndOrderNodes()
        deleteMaterialBindings()
        deleteMaterials()
        deleteSearchFeatures()
        deleteRelations()
        deleteNodeSourceBindings()
        deleteNodes()
        deleteSources()
        deleteManifest()

        bundle.sources.insertWhenNotEmpty(::insertSources)
        parentFirstNodes.insertWhenNotEmpty(::insertNodes)
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
