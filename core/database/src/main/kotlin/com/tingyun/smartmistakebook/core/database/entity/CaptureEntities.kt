package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Canonicalized pixels are immutable; no DAO exposes update or delete operations. */
@Entity(
    tableName = "canonical_source_asset",
    indices = [
        Index(value = ["content_sha256"], unique = true),
        Index(value = ["relative_path"], unique = true),
    ],
)
internal data class CanonicalSourceAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_draft",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalSourceAssetEntity::class,
            parentColumns = ["source_asset_id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_asset_id"]),
        Index(value = ["status", "updated_at_epoch_millis"]),
    ],
)
internal data class ProblemDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    val origin: String,
    val status: String,
    @ColumnInfo(name = "current_revision_number")
    val currentRevisionNumber: Int,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "request_fingerprint")
    val requestFingerprint: String?,
)

/** Ordered, immutable source bundle for one draft. Page zero mirrors problem_draft.source_asset_id. */
@Entity(
    tableName = "problem_draft_source_asset",
    primaryKeys = ["draft_id", "page_index"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemDraftEntity::class,
            parentColumns = ["draft_id"],
            childColumns = ["draft_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CanonicalSourceAssetEntity::class,
            parentColumns = ["source_asset_id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_asset_id"]),
        Index(value = ["draft_id", "source_asset_id"], unique = true),
    ],
)
internal data class ProblemDraftSourceAssetEntity(
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    @ColumnInfo(name = "attached_at_epoch_millis")
    val attachedAtEpochMillis: Long,
)

/** Accepted semantic candidates are append-only; keystroke workspaces live outside this chain. */
@Entity(
    tableName = "problem_draft_revision",
    primaryKeys = ["draft_id", "revision_number"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemDraftEntity::class,
            parentColumns = ["draft_id"],
            childColumns = ["draft_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["draft_id"]),
        Index(value = ["document_fingerprint"]),
    ],
)
internal data class ProblemDraftRevisionEntity(
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "basis_revision_number")
    val basisRevisionNumber: Int?,
    val subject: String?,
    val title: String,
    @ColumnInfo(name = "question_document_snapshot")
    val questionDocumentSnapshot: String,
    @ColumnInfo(name = "document_fingerprint")
    val documentFingerprint: String,
    val author: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

/** One mutable, CAS-protected editing workspace for each still-editing draft. */
@Entity(
    tableName = "problem_draft_edit_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = ProblemDraftRevisionEntity::class,
            parentColumns = ["draft_id", "revision_number"],
            childColumns = ["draft_id", "basis_revision_number"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["draft_id", "basis_revision_number"]),
    ],
)
internal data class ProblemDraftEditWorkspaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "basis_revision_number")
    val basisRevisionNumber: Int,
    @ColumnInfo(name = "workspace_version")
    val workspaceVersion: Long,
    @ColumnInfo(name = "snapshot_schema_version")
    val snapshotSchemaVersion: Int,
    @ColumnInfo(name = "workspace_snapshot")
    val workspaceSnapshot: String,
    @ColumnInfo(name = "workspace_fingerprint")
    val workspaceFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/** A tutoring session owns one exact, user-confirmed draft revision without formalizing it. */
@Entity(
    tableName = "tutor_session",
    foreignKeys = [
        ForeignKey(
            entity = ProblemDraftRevisionEntity::class,
            parentColumns = ["draft_id", "revision_number"],
            childColumns = ["draft_id", "draft_revision_number"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["draft_id"], unique = true),
        Index(value = ["draft_id", "draft_revision_number"]),
    ],
)
internal data class TutorSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "draft_revision_number")
    val draftRevisionNumber: Int,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_revision_source_asset",
    primaryKeys = ["problem_revision_id", "source_asset_id", "role"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CanonicalSourceAssetEntity::class,
            parentColumns = ["source_asset_id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["problem_revision_id"]),
        Index(value = ["source_asset_id"]),
    ],
)
internal data class ProblemRevisionSourceAssetEntity(
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    val role: String,
)

@Entity(
    tableName = "problem_draft_commit_receipt",
    foreignKeys = [
        ForeignKey(
            entity = ProblemDraftEntity::class,
            parentColumns = ["draft_id"],
            childColumns = ["draft_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ErrorBookEntryEntity::class,
            parentColumns = ["entry_id"],
            childColumns = ["error_book_entry_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["draft_id"], unique = true),
        Index(value = ["problem_id"]),
        Index(value = ["problem_revision_id"]),
        Index(value = ["practice_unit_id"]),
        Index(value = ["error_book_entry_id"]),
    ],
)
internal data class ProblemDraftCommitReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "draft_revision_number")
    val draftRevisionNumber: Int,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String,
    @ColumnInfo(name = "committed_at_epoch_millis")
    val committedAtEpochMillis: Long,
)
