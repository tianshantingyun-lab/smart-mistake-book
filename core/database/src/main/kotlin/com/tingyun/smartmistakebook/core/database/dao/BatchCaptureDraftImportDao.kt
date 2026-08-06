package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.BatchCaptureContentBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftBatchImportReceiptEntity

internal data class SequencedBatchCaptureDraftImportReceipt(
    @ColumnInfo(name = "receipt_sequence")
    val receiptSequence: Long,
    @Embedded
    val receipt: CaptureDraftBatchImportReceiptEntity,
)

@Dao
internal interface BatchCaptureDraftImportDao {
    @Query(
        """
        SELECT *
        FROM batch_capture_content_binding
        WHERE batch_job_id = :batchJobId
          AND content_sha256 = :contentSha256
        """,
    )
    suspend fun readContentBinding(
        batchJobId: String,
        contentSha256: String,
    ): BatchCaptureContentBindingEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContentBinding(binding: BatchCaptureContentBindingEntity): Long

    @Query(
        """
        SELECT *
        FROM capture_draft_batch_import_receipt
        WHERE batch_job_id = :batchJobId
          AND batch_page_index = :batchPageIndex
        """,
    )
    suspend fun readReceipt(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftBatchImportReceiptEntity?

    @Query(
        """
        SELECT rowid AS receipt_sequence, *
        FROM capture_draft_batch_import_receipt
        WHERE batch_job_id = :batchJobId
          AND rowid > :afterReceiptSequenceExclusive
        ORDER BY rowid
        LIMIT :limit
        """,
    )
    suspend fun readReceipts(
        batchJobId: String,
        afterReceiptSequenceExclusive: Long,
        limit: Int,
    ): List<SequencedBatchCaptureDraftImportReceipt>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: CaptureDraftBatchImportReceiptEntity)
}
