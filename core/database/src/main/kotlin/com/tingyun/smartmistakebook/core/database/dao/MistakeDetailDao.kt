package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.MistakeRevisionSummaryRecord
import kotlinx.coroutines.flow.Flow

internal data class MistakeDetailRow(
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    val subject: String,
    val title: String,
    @ColumnInfo(name = "problem_markdown")
    val problemMarkdown: String,
    @ColumnInfo(name = "question_document_snapshot")
    val questionDocumentSnapshot: String?,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "tutor_session_id")
    val tutorSessionId: String?,
    @ColumnInfo(name = "tutor_question_revision_number")
    val tutorQuestionRevisionNumber: Int?,
    @ColumnInfo(name = "user_note")
    val userNote: String? = null,
)

internal data class EntryRevisionRow(
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
)

internal data class MistakeDetailSourceAssetRow(
    val role: String,
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

internal data class MistakeDetailBatchSourceAssetRow(
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    val role: String,
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

internal data class MistakeRevisionSummaryRow(
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    val title: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "is_current")
    val isCurrent: Boolean,
)

@Dao
internal abstract class MistakeDetailDao {
    @Query(
        """
        UPDATE error_book_entry
        SET user_note = :note,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE entry_id = :entryId
        """,
    )
    protected abstract suspend fun updateEntryNote(
        entryId: String,
        note: String?,
        updatedAtEpochMillis: Long,
    ): Int

    open suspend fun setUserNote(
        entryId: String,
        note: String?,
        updatedAtEpochMillis: Long,
    ): Boolean {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
        require(updatedAtEpochMillis >= 0) { "updatedAtEpochMillis must not be negative" }
        return updateEntryNote(entryId, note, updatedAtEpochMillis) == 1
    }

    @Query(
        """
        UPDATE error_book_entry
        SET status = :status,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE entry_id = :entryId
        """,
    )
    protected abstract suspend fun updateEntryStatus(
        entryId: String,
        status: String,
        updatedAtEpochMillis: Long,
    ): Int

    open suspend fun setEntryStatus(
        entryId: String,
        status: String,
        updatedAtEpochMillis: Long,
    ): Boolean {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
        require(updatedAtEpochMillis >= 0) { "updatedAtEpochMillis must not be negative" }
        return updateEntryStatus(entryId, status, updatedAtEpochMillis) == 1
    }

    @Query(
        """
        SELECT entry_id, current_revision_id
        FROM error_book_entry
        WHERE entry_id = :entryId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findEntryRevision(entryId: String): EntryRevisionRow?

    open suspend fun readEntryRevision(entryId: String): EntryRevisionRow? =
        findEntryRevision(entryId)

    @Query(
        """
        SELECT entry_id
        FROM error_book_entry
        WHERE status = 'ARCHIVED'
        ORDER BY updated_at_epoch_millis DESC
        """,
    )
    protected abstract fun observeArchivedEntryIds(): Flow<List<String>>

    open fun archivedEntries(): Flow<List<String>> = observeArchivedEntryIds()

    @Query(
        """
        SELECT
            entry.entry_id,
            entry.problem_id,
            revision.revision_id AS problem_revision_id,
            entry.practice_unit_id,
            revision.revision_number,
            problem.subject,
            revision.title,
            revision.problem_markdown,
            revision.question_document_snapshot,
            revision.content_fingerprint,
            entry.user_note,
            tutor.session_id AS tutor_session_id,
            tutor.draft_revision_number AS tutor_question_revision_number
        FROM error_book_entry AS entry
        JOIN problem AS problem
            ON problem.problem_id = entry.problem_id
        JOIN problem_revision AS revision
            ON revision.revision_id = entry.current_revision_id
            AND revision.problem_id = entry.problem_id
        LEFT JOIN problem_draft_commit_receipt AS receipt
            ON receipt.problem_revision_id = revision.revision_id
        LEFT JOIN tutor_session AS tutor
            ON tutor.draft_id = receipt.draft_id
            AND tutor.draft_revision_number = receipt.draft_revision_number
        WHERE entry.entry_id = :errorBookEntryId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findDetail(errorBookEntryId: String): MistakeDetailRow?

    @Query(
        """
        SELECT
            entry.entry_id,
            entry.problem_id,
            revision.revision_id AS problem_revision_id,
            entry.practice_unit_id,
            revision.revision_number,
            problem.subject,
            revision.title,
            revision.problem_markdown,
            revision.question_document_snapshot,
            revision.content_fingerprint,
            entry.user_note,
            tutor.session_id AS tutor_session_id,
            tutor.draft_revision_number AS tutor_question_revision_number
        FROM error_book_entry AS entry
        JOIN problem AS problem
            ON problem.problem_id = entry.problem_id
        JOIN problem_revision AS revision
            ON revision.revision_id = :problemRevisionId
            AND revision.problem_id = entry.problem_id
        LEFT JOIN problem_draft_commit_receipt AS receipt
            ON receipt.problem_revision_id = revision.revision_id
        LEFT JOIN tutor_session AS tutor
            ON tutor.draft_id = receipt.draft_id
            AND tutor.draft_revision_number = receipt.draft_revision_number
        WHERE entry.entry_id = :entryId
          AND entry.problem_id = :problemId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findExactDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRow?

    @Query(
        """
        SELECT
            entry.entry_id,
            entry.problem_id,
            revision.revision_id AS problem_revision_id,
            entry.practice_unit_id,
            revision.revision_number,
            problem.subject,
            revision.title,
            revision.problem_markdown,
            revision.question_document_snapshot,
            revision.content_fingerprint,
            entry.user_note,
            NULL AS tutor_session_id,
            NULL AS tutor_question_revision_number
        FROM error_book_entry AS entry
        JOIN problem AS problem
            ON problem.problem_id = entry.problem_id
        JOIN problem_revision AS revision
            ON revision.revision_id = entry.current_revision_id
            AND revision.problem_id = entry.problem_id
        WHERE entry.entry_id IN (:entryIds)
        """,
    )
    protected abstract suspend fun findCurrentDetails(
        entryIds: List<String>,
    ): List<MistakeDetailRow>

    @Query(
        """
        SELECT
            link.role,
            asset.source_asset_id,
            asset.content_sha256,
            asset.relative_path,
            asset.mime_type,
            asset.byte_size,
            asset.width,
            asset.height,
            asset.source_type,
            asset.created_at_epoch_millis
        FROM problem_revision_source_asset AS link
        JOIN canonical_source_asset AS asset
            ON asset.source_asset_id = link.source_asset_id
        WHERE link.problem_revision_id = :problemRevisionId
        ORDER BY link.role ASC, asset.source_asset_id ASC
        """,
    )
    protected abstract suspend fun findSourceAssets(
        problemRevisionId: String,
    ): List<MistakeDetailSourceAssetRow>

    @Query(
        """
        SELECT
            link.problem_revision_id,
            link.role,
            asset.source_asset_id,
            asset.content_sha256,
            asset.relative_path,
            asset.mime_type,
            asset.byte_size,
            asset.width,
            asset.height,
            asset.source_type,
            asset.created_at_epoch_millis
        FROM problem_revision_source_asset AS link
        JOIN canonical_source_asset AS asset
            ON asset.source_asset_id = link.source_asset_id
        WHERE link.problem_revision_id IN (:problemRevisionIds)
        ORDER BY link.problem_revision_id ASC, link.role ASC, asset.source_asset_id ASC
        """,
    )
    protected abstract suspend fun findSourceAssetsForRevisions(
        problemRevisionIds: List<String>,
    ): List<MistakeDetailBatchSourceAssetRow>

    @Query(
        """
        SELECT
            entry.entry_id,
            revision.problem_id,
            revision.revision_id AS problem_revision_id,
            revision.revision_number,
            revision.title,
            revision.created_at_epoch_millis,
            revision.revision_id = entry.current_revision_id AS is_current
        FROM error_book_entry AS entry
        JOIN problem_revision AS revision
            ON revision.problem_id = entry.problem_id
        WHERE entry.entry_id = :errorBookEntryId
        ORDER BY revision.revision_number DESC
        """,
    )
    protected abstract suspend fun findRevisionHistory(
        errorBookEntryId: String,
    ): List<MistakeRevisionSummaryRow>

    @Transaction
    open suspend fun read(errorBookEntryId: String): MistakeDetailRecord? {
        val detail = findDetail(errorBookEntryId) ?: return null
        return detail.toRecord(findSourceAssets(detail.problemRevisionId))
    }

    @Transaction
    open suspend fun readExact(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord? {
        val detail = findExactDetail(entryId, problemId, problemRevisionId) ?: return null
        return detail.toRecord(findSourceAssets(detail.problemRevisionId))
    }

    @Transaction
    open suspend fun readCurrentBatch(entryIds: List<String>): List<MistakeDetailRecord> {
        require(entryIds.size <= MAX_BATCH_SIZE) { "Mistake-detail batch is too large" }
        if (entryIds.isEmpty()) return emptyList()
        val details = findCurrentDetails(entryIds.distinct())
        val assetsByRevision = findSourceAssetsForRevisions(
            details.map(MistakeDetailRow::problemRevisionId).distinct(),
        ).groupBy(MistakeDetailBatchSourceAssetRow::problemRevisionId)
        return details.map { detail ->
            detail.toRecord(
                assetsByRevision[detail.problemRevisionId]
                    .orEmpty()
                    .map(MistakeDetailBatchSourceAssetRow::toSourceAssetRow),
            )
        }
    }

    open suspend fun readRevisionHistory(
        errorBookEntryId: String,
    ): List<MistakeRevisionSummaryRecord> = findRevisionHistory(errorBookEntryId).map { row ->
        MistakeRevisionSummaryRecord(
            entryId = row.entryId,
            problemId = row.problemId,
            problemRevisionId = row.problemRevisionId,
            revisionNumber = row.revisionNumber,
            title = row.title,
            createdAtEpochMillis = row.createdAtEpochMillis,
            isCurrent = row.isCurrent,
        )
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
    }
}

private fun MistakeDetailBatchSourceAssetRow.toSourceAssetRow() = MistakeDetailSourceAssetRow(
    role = role,
    sourceAssetId = sourceAssetId,
    contentSha256 = contentSha256,
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = byteSize,
    width = width,
    height = height,
    sourceType = sourceType,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun MistakeDetailRow.toRecord(
    sourceAssets: List<MistakeDetailSourceAssetRow>,
) = MistakeDetailRecord(
    entryId = entryId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    revisionNumber = revisionNumber,
    subject = subject,
    title = title,
    problemMarkdown = problemMarkdown,
    questionDocumentSnapshot = questionDocumentSnapshot,
    contentFingerprint = contentFingerprint,
    sourceAssets = sourceAssets.map { row ->
        MistakeDetailSourceAssetRecord(
            role = row.role,
            sourceAsset = CanonicalSourceAssetRecord(
                sourceAssetId = row.sourceAssetId,
                contentSha256 = row.contentSha256,
                relativePath = row.relativePath,
                mimeType = row.mimeType,
                byteSize = row.byteSize,
                width = row.width,
                height = row.height,
                sourceType = row.sourceType,
                createdAtEpochMillis = row.createdAtEpochMillis,
            ),
        )
    },
    tutorSessionId = tutorSessionId,
    tutorQuestionRevisionNumber = tutorQuestionRevisionNumber,
    userNote = userNote,
)
