package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEntity

internal data class CanonicalSourceAssetRow(
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

@Dao
internal interface PendingCaptureDao {
    @Query(
        """
        SELECT asset.source_asset_id,
               asset.content_sha256,
               asset.relative_path,
               asset.mime_type,
               asset.byte_size,
               asset.width,
               asset.height,
               asset.source_type,
               asset.created_at_epoch_millis
        FROM canonical_source_asset AS asset
        WHERE NOT EXISTS (
            SELECT 1 FROM problem_revision_source_asset AS revision_link
            WHERE revision_link.source_asset_id = asset.source_asset_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM problem_draft_source_asset AS draft_link
            WHERE draft_link.source_asset_id = asset.source_asset_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM problem_draft AS draft
            WHERE draft.source_asset_id = asset.source_asset_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM tutor_message_source_asset AS message_link
            WHERE message_link.source_asset_id = asset.source_asset_id
        )
        ORDER BY asset.created_at_epoch_millis ASC, asset.source_asset_id ASC
        """,
    )
    suspend fun findUnreferencedCanonicalAssets(): List<CanonicalSourceAssetRow>

    @Query(
        """
        DELETE FROM canonical_source_asset AS asset
        WHERE NOT EXISTS (
            SELECT 1 FROM problem_revision_source_asset AS revision_link
            WHERE revision_link.source_asset_id = asset.source_asset_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM problem_draft_source_asset AS draft_link
            WHERE draft_link.source_asset_id = asset.source_asset_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM problem_draft AS draft
            WHERE draft.source_asset_id = asset.source_asset_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM tutor_message_source_asset AS message_link
            WHERE message_link.source_asset_id = asset.source_asset_id
        )
        """,
    )
    suspend fun deleteUnreferencedCanonicalAssets(): Int

    @Query(
        """
        SELECT request_id
        FROM model_task
        WHERE subject_id = :draftId AND task_kind = 'CAPTURE_ASSESS'
        ORDER BY updated_at_epoch_millis DESC,
                 state_version DESC,
                 created_at_epoch_millis DESC,
                 task_id DESC
        LIMIT 64
        """,
    )
    suspend fun readAssessmentRequestIds(draftId: String): List<String>

    @Query(
        """
        SELECT d.draft_id AS draft_id,
               d.source_asset_id AS primary_source_asset_id,
               d.origin AS draft_origin,
               d.status AS draft_status,
               d.current_revision_number AS draft_current_revision_number,
               d.created_at_epoch_millis AS draft_created_at_epoch_millis,
               d.updated_at_epoch_millis AS draft_updated_at_epoch_millis,
               d.request_fingerprint AS draft_request_fingerprint,
               revision.revision_number AS revision_number,
               revision.basis_revision_number AS revision_basis_revision_number,
               revision.subject AS revision_subject,
               revision.title AS revision_title,
               revision.question_document_snapshot AS revision_question_document_snapshot,
               revision.document_fingerprint AS revision_document_fingerprint,
               revision.author AS revision_author,
               revision.created_at_epoch_millis AS revision_created_at_epoch_millis,
               ws.basis_revision_number AS workspace_basis_revision_number,
               ws.workspace_version AS workspace_version,
               ws.snapshot_schema_version AS workspace_snapshot_schema_version,
               ws.workspace_snapshot AS workspace_snapshot,
               ws.workspace_fingerprint AS workspace_fingerprint,
               ws.created_at_epoch_millis AS workspace_created_at_epoch_millis,
               ws.updated_at_epoch_millis AS workspace_updated_at_epoch_millis,
               ts.session_id AS tutor_session_id,
               ts.draft_revision_number AS tutor_session_draft_revision_number
        FROM problem_draft d
        LEFT JOIN problem_draft_revision revision
               ON revision.draft_id = d.draft_id
              AND revision.revision_number = d.current_revision_number
        LEFT JOIN tutor_session ts ON ts.draft_id = d.draft_id
        LEFT JOIN problem_draft_edit_snapshot ws ON ws.draft_id = d.draft_id
        WHERE d.status = 'EDITING'
        ORDER BY COALESCE(ws.updated_at_epoch_millis, d.updated_at_epoch_millis) DESC,
                 d.draft_id ASC
        """,
    )
    suspend fun readPendingHeads(): List<PendingCaptureHeadRow>

    @Query(
        """
        SELECT binding.draft_id AS draft_id,
               binding.page_index AS page_index,
               asset.source_asset_id AS source_asset_id,
               asset.content_sha256 AS content_sha256,
               asset.relative_path AS relative_path,
               asset.mime_type AS mime_type,
               asset.byte_size AS byte_size,
               asset.width AS width,
               asset.height AS height,
               asset.source_type AS source_type,
               asset.created_at_epoch_millis AS asset_created_at_epoch_millis
        FROM problem_draft_source_asset binding
        JOIN problem_draft draft ON draft.draft_id = binding.draft_id
        JOIN canonical_source_asset asset ON asset.source_asset_id = binding.source_asset_id
        WHERE draft.status = 'EDITING'
        ORDER BY binding.draft_id ASC, binding.page_index ASC
        """,
    )
    suspend fun readPendingSourceAssets(): List<PendingCaptureSourceAssetRow>

    @Query(
        """
        SELECT task.*
        FROM model_task task
        JOIN problem_draft draft ON draft.draft_id = task.subject_id
        WHERE draft.status = 'EDITING'
          AND task.task_kind = 'CAPTURE_ASSESS'
          AND task.request_id IN (
              SELECT recent.request_id
              FROM model_task recent
              WHERE recent.subject_id = task.subject_id
                AND recent.task_kind = 'CAPTURE_ASSESS'
              ORDER BY recent.updated_at_epoch_millis DESC,
                       recent.state_version DESC,
                       recent.created_at_epoch_millis DESC,
                       recent.task_id DESC
              LIMIT 64
          )
        ORDER BY task.subject_id ASC,
                 task.updated_at_epoch_millis DESC,
                 task.state_version DESC,
                 task.created_at_epoch_millis DESC,
                 task.task_id DESC
        """,
    )
    suspend fun readRecentPendingAssessmentTasks(): List<ModelTaskEntity>

    @Query(
        """
        SELECT task.*
        FROM model_task task
        JOIN problem_draft draft ON draft.draft_id = task.subject_id
        WHERE draft.status = 'EDITING'
          AND task.task_kind = 'CAPTURE_PARSE'
          AND task.request_id = (
              SELECT recent.request_id
              FROM model_task recent
              WHERE recent.subject_id = task.subject_id
                AND recent.task_kind = 'CAPTURE_PARSE'
              ORDER BY recent.updated_at_epoch_millis DESC,
                       recent.state_version DESC,
                       recent.created_at_epoch_millis DESC,
                       recent.task_id DESC
              LIMIT 1
          )
        ORDER BY task.subject_id ASC
        """,
    )
    suspend fun readLatestPendingParseTasks(): List<ModelTaskEntity>

    @Query(
        """
        SELECT d.draft_id AS draft_id,
               d.updated_at_epoch_millis AS draft_updated_at_epoch_millis,
               ws.basis_revision_number AS workspace_basis_revision_number,
               ws.workspace_version AS workspace_version,
               ws.snapshot_schema_version AS workspace_snapshot_schema_version,
               ws.workspace_snapshot AS workspace_snapshot,
               ws.workspace_fingerprint AS workspace_fingerprint,
               ws.created_at_epoch_millis AS workspace_created_at_epoch_millis,
               ws.updated_at_epoch_millis AS workspace_updated_at_epoch_millis,
               ts.session_id AS tutor_session_id,
               ts.draft_revision_number AS tutor_session_draft_revision_number,
               (
                   SELECT mt.request_id
                   FROM model_task mt
                   WHERE mt.subject_id = d.draft_id
                     AND mt.task_kind = 'CAPTURE_ASSESS'
                   ORDER BY mt.updated_at_epoch_millis DESC,
                            mt.state_version DESC,
                            mt.created_at_epoch_millis DESC,
                            mt.task_id DESC
                   LIMIT 1
               ) AS latest_assessment_request_id,
               (
                   SELECT mt.request_id
                   FROM model_task mt
                   WHERE mt.subject_id = d.draft_id
                     AND mt.task_kind = 'CAPTURE_PARSE'
                   ORDER BY mt.updated_at_epoch_millis DESC,
                            mt.state_version DESC,
                            mt.created_at_epoch_millis DESC,
                            mt.task_id DESC
                   LIMIT 1
               ) AS latest_parse_request_id
        FROM problem_draft d
        LEFT JOIN tutor_session ts ON ts.draft_id = d.draft_id
        LEFT JOIN problem_draft_edit_snapshot ws ON ws.draft_id = d.draft_id
        WHERE d.status = 'EDITING' AND d.draft_id = :draftId
        LIMIT 1
        """,
    )
    suspend fun readPending(draftId: String): PendingCaptureIndexRow?
}

internal data class PendingCaptureIndexRow(
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "draft_updated_at_epoch_millis")
    val draftUpdatedAtEpochMillis: Long,
    @ColumnInfo(name = "workspace_basis_revision_number")
    override val workspaceBasisRevisionNumber: Int?,
    @ColumnInfo(name = "workspace_version")
    override val workspaceVersion: Long?,
    @ColumnInfo(name = "workspace_snapshot_schema_version")
    override val workspaceSnapshotSchemaVersion: Int?,
    @ColumnInfo(name = "workspace_snapshot")
    override val workspaceSnapshot: String?,
    @ColumnInfo(name = "workspace_fingerprint")
    override val workspaceFingerprint: String?,
    @ColumnInfo(name = "workspace_created_at_epoch_millis")
    override val workspaceCreatedAtEpochMillis: Long?,
    @ColumnInfo(name = "workspace_updated_at_epoch_millis")
    override val workspaceUpdatedAtEpochMillis: Long?,
    @ColumnInfo(name = "tutor_session_id")
    val tutorSessionId: String?,
    @ColumnInfo(name = "tutor_session_draft_revision_number")
    val tutorSessionDraftRevisionNumber: Int?,
    @ColumnInfo(name = "latest_assessment_request_id")
    val latestAssessmentRequestId: String?,
    @ColumnInfo(name = "latest_parse_request_id")
    val latestParseRequestId: String?,
) : PendingCaptureWorkspaceColumns

internal data class PendingCaptureHeadRow(
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "primary_source_asset_id")
    val primarySourceAssetId: String,
    @ColumnInfo(name = "draft_origin")
    val draftOrigin: String,
    @ColumnInfo(name = "draft_status")
    val draftStatus: String,
    @ColumnInfo(name = "draft_current_revision_number")
    val draftCurrentRevisionNumber: Int,
    @ColumnInfo(name = "draft_created_at_epoch_millis")
    val draftCreatedAtEpochMillis: Long,
    @ColumnInfo(name = "draft_updated_at_epoch_millis")
    val draftUpdatedAtEpochMillis: Long,
    @ColumnInfo(name = "draft_request_fingerprint")
    val draftRequestFingerprint: String?,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int?,
    @ColumnInfo(name = "revision_basis_revision_number")
    val revisionBasisRevisionNumber: Int?,
    @ColumnInfo(name = "revision_subject")
    val revisionSubject: String?,
    @ColumnInfo(name = "revision_title")
    val revisionTitle: String?,
    @ColumnInfo(name = "revision_question_document_snapshot")
    val revisionQuestionDocumentSnapshot: String?,
    @ColumnInfo(name = "revision_document_fingerprint")
    val revisionDocumentFingerprint: String?,
    @ColumnInfo(name = "revision_author")
    val revisionAuthor: String?,
    @ColumnInfo(name = "revision_created_at_epoch_millis")
    val revisionCreatedAtEpochMillis: Long?,
    @ColumnInfo(name = "workspace_basis_revision_number")
    override val workspaceBasisRevisionNumber: Int?,
    @ColumnInfo(name = "workspace_version")
    override val workspaceVersion: Long?,
    @ColumnInfo(name = "workspace_snapshot_schema_version")
    override val workspaceSnapshotSchemaVersion: Int?,
    @ColumnInfo(name = "workspace_snapshot")
    override val workspaceSnapshot: String?,
    @ColumnInfo(name = "workspace_fingerprint")
    override val workspaceFingerprint: String?,
    @ColumnInfo(name = "workspace_created_at_epoch_millis")
    override val workspaceCreatedAtEpochMillis: Long?,
    @ColumnInfo(name = "workspace_updated_at_epoch_millis")
    override val workspaceUpdatedAtEpochMillis: Long?,
    @ColumnInfo(name = "tutor_session_id")
    val tutorSessionId: String?,
    @ColumnInfo(name = "tutor_session_draft_revision_number")
    val tutorSessionDraftRevisionNumber: Int?,
) : PendingCaptureWorkspaceColumns

internal data class PendingCaptureSourceAssetRow(
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
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
    @ColumnInfo(name = "asset_created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

internal interface PendingCaptureWorkspaceColumns {
    val workspaceBasisRevisionNumber: Int?
    val workspaceVersion: Long?
    val workspaceSnapshotSchemaVersion: Int?
    val workspaceSnapshot: String?
    val workspaceFingerprint: String?
    val workspaceCreatedAtEpochMillis: Long?
    val workspaceUpdatedAtEpochMillis: Long?
}
