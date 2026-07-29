package com.tingyun.smartmistakebook.core.knowledge.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

internal const val ACTIVE_MANIFEST_KEY = "active"
internal const val ALIAS_SEPARATOR = "\u001f"

@Entity(tableName = "knowledge_pack_manifest")
internal data class KnowledgePackManifestEntity(
    @PrimaryKey
    @ColumnInfo(name = "manifest_key")
    val manifestKey: String,
    @ColumnInfo(name = "pack_id")
    val packId: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "knowledge_pack_version")
    val knowledgePackVersion: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "search_index_version")
    val searchIndexVersion: String,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "built_at_epoch_millis")
    val builtAtEpochMillis: Long,
    @ColumnInfo(name = "node_count")
    val nodeCount: Int,
    @ColumnInfo(name = "source_count")
    val sourceCount: Int,
    @ColumnInfo(name = "relation_count")
    val relationCount: Int,
    @ColumnInfo(name = "material_count")
    val materialCount: Int,
    @ColumnInfo(name = "search_feature_count")
    val searchFeatureCount: Int,
)

@Entity(
    tableName = "knowledge_node",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["parent_knowledge_node_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["stable_code", "taxonomy_version"], unique = true),
        Index(value = ["subject", "canonical_name"]),
        Index(value = ["subject", "display_name"]),
        Index(value = ["subject", "granularity"]),
        Index(value = ["parent_knowledge_node_id"]),
    ],
)
internal data class KnowledgeNodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "stable_code")
    val stableCode: String,
    val subject: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,
    @ColumnInfo(name = "node_kind")
    val nodeKind: String,
    val granularity: String,
    @ColumnInfo(name = "aliases_text")
    val aliasesText: String,
    @ColumnInfo(name = "boundary_markdown")
    val boundaryMarkdown: String?,
    @ColumnInfo(name = "verification_status")
    val verificationStatus: String,
    @ColumnInfo(name = "parent_knowledge_node_id")
    val parentKnowledgeNodeId: String?,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_source",
    indices = [
        Index(value = ["content_fingerprint"], unique = true),
        Index(value = ["subject", "source_type"]),
    ],
)
internal data class KnowledgeSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    val subject: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    val title: String,
    val publisher: String?,
    val edition: String?,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String?,
    @ColumnInfo(name = "license_status")
    val licenseStatus: String,
    @ColumnInfo(name = "content_use_policy")
    val contentUsePolicy: String,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "license_expression")
    val licenseExpression: String?,
    @ColumnInfo(name = "license_uri")
    val licenseUri: String?,
    @ColumnInfo(name = "attribution_text")
    val attributionText: String?,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_node_source_binding",
    primaryKeys = ["knowledge_node_id", "source_id", "source_locator"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeSourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["knowledge_node_id"]),
        Index(value = ["source_id"]),
    ],
)
internal data class KnowledgeNodeSourceBindingEntity(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String,
    @ColumnInfo(name = "derivation_note")
    val derivationNote: String,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_node_relation",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["from_knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["to_knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = KnowledgeSourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "from_knowledge_node_id",
                "to_knowledge_node_id",
                "relation_type",
                "taxonomy_version",
            ],
            unique = true,
        ),
        Index(value = ["subject", "from_knowledge_node_id", "relation_type"]),
        Index(value = ["subject", "to_knowledge_node_id", "relation_type"]),
        Index(value = ["to_knowledge_node_id"]),
        Index(value = ["source_id"]),
    ],
)
internal data class KnowledgeNodeRelationEntity(
    @PrimaryKey
    @ColumnInfo(name = "relation_id")
    val relationId: String,
    val subject: String,
    @ColumnInfo(name = "from_knowledge_node_id")
    val fromKnowledgeNodeId: String,
    @ColumnInfo(name = "to_knowledge_node_id")
    val toKnowledgeNodeId: String,
    @ColumnInfo(name = "relation_type")
    val relationType: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_search_feature",
    primaryKeys = ["subject", "search_feature", "knowledge_node_id"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["knowledge_node_id"]),
        Index(value = ["subject", "knowledge_node_id"]),
    ],
)
internal data class KnowledgeSearchFeatureEntity(
    val subject: String,
    @ColumnInfo(name = "search_feature")
    val searchFeature: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "feature_kind")
    val featureKind: String,
    @ColumnInfo(name = "rank_weight")
    val rankWeight: Int,
)

@Entity(
    tableName = "knowledge_teaching_material",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeSourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stable_code"], unique = true),
        Index(value = ["content_fingerprint"], unique = true),
        Index(value = ["source_id"]),
        Index(value = ["subject", "material_type"]),
    ],
)
internal data class KnowledgeTeachingMaterialEntity(
    @PrimaryKey
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
    @ColumnInfo(name = "content_markdown")
    val contentMarkdown: String,
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
)

@Entity(
    tableName = "knowledge_teaching_material_node_binding",
    primaryKeys = ["material_id", "knowledge_node_id"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeTeachingMaterialEntity::class,
            parentColumns = ["material_id"],
            childColumns = ["material_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["material_id", "role"]),
        Index(value = ["knowledge_node_id", "role"]),
    ],
)
internal data class KnowledgeTeachingMaterialNodeBindingEntity(
    @ColumnInfo(name = "material_id")
    val materialId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    val role: String,
)
