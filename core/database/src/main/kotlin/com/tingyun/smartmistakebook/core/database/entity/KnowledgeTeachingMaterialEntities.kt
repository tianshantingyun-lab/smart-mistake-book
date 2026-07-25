package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

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
