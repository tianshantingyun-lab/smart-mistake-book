package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "batch_capture_content_binding",
    primaryKeys = ["batch_job_id", "content_sha256"],
)
internal data class BatchCaptureContentBindingEntity(
    @ColumnInfo(name = "batch_job_id")
    val batchJobId: String,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
    @ColumnInfo(name = "original_draft_id")
    val originalDraftId: String,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    @ColumnInfo(name = "bound_at_epoch_millis")
    val boundAtEpochMillis: Long,
)

@Entity(
    tableName = "capture_draft_batch_import_receipt",
    primaryKeys = ["batch_job_id", "batch_page_index"],
    indices = [
        Index(value = ["receipt_reference"], unique = true),
        Index(value = ["receipt_fingerprint"], unique = true),
    ],
)
internal data class CaptureDraftBatchImportReceiptEntity(
    @ColumnInfo(name = "receipt_reference")
    val receiptReference: String,
    @ColumnInfo(name = "batch_job_id")
    val batchJobId: String,
    @ColumnInfo(name = "batch_page_index")
    val batchPageIndex: Int,
    @ColumnInfo(name = "request_fingerprint")
    val requestFingerprint: String,
    @ColumnInfo(name = "original_draft_id")
    val originalDraftId: String,
    @ColumnInfo(name = "source_asset_id")
    val sourceAssetId: String,
    val disposition: String,
    @ColumnInfo(name = "imported_at_epoch_millis")
    val importedAtEpochMillis: Long,
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
)
