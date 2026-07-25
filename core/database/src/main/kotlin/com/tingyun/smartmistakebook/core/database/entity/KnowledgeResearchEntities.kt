package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "knowledge_research_review_bundle",
    primaryKeys = ["bundle_id"],
    indices = [
        Index(value = ["status", "created_at_epoch_millis"]),
        Index(value = ["grounding_key", "status"]),
    ],
)
internal data class KnowledgeResearchReviewBundleEntity(
    @ColumnInfo(name = "bundle_id")
    val bundleId: String,
    @ColumnInfo(name = "grounding_key")
    val groundingKey: String,
    val subject: String,
    val query: String,
    @ColumnInfo(name = "expected_parent_knowledge_display_name")
    val expectedParentKnowledgeDisplayName: String,
    @ColumnInfo(name = "related_question_count")
    val relatedQuestionCount: Int,
    @ColumnInfo(name = "workflow_version")
    val workflowVersion: String,
    val status: String,
    @ColumnInfo(name = "reviewer_reference")
    val reviewerReference: String?,
    @ColumnInfo(name = "decision_note")
    val decisionNote: String?,
    @ColumnInfo(name = "reviewed_at_epoch_millis")
    val reviewedAtEpochMillis: Long?,
    @ColumnInfo(name = "applied_pack_fingerprint")
    val appliedPackFingerprint: String?,
    @ColumnInfo(name = "applied_at_epoch_millis")
    val appliedAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_research_review_source",
    primaryKeys = ["bundle_id", "source_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeResearchReviewBundleEntity::class,
            parentColumns = ["bundle_id"],
            childColumns = ["bundle_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bundle_id", "canonical_source_uri"], unique = true),
        Index(value = ["content_fingerprint"]),
    ],
)
internal data class KnowledgeResearchReviewSourceEntity(
    @ColumnInfo(name = "bundle_id")
    val bundleId: String,
    @ColumnInfo(name = "source_ordinal")
    val sourceOrdinal: Int,
    @ColumnInfo(name = "canonical_source_uri")
    val canonicalSourceUri: String,
    val title: String,
    val publisher: String?,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "license_status")
    val licenseStatus: String,
    @ColumnInfo(name = "search_rank")
    val searchRank: Int,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "content_length_bytes")
    val contentLengthBytes: Long,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "verified_at_epoch_millis")
    val verifiedAtEpochMillis: Long,
)
