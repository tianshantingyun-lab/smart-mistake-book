package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "capture_draft_merge_session_receipt",
    primaryKeys = ["batch_job_id", "batch_page_index"],
    indices = [
        Index(value = ["receipt_reference"], unique = true),
    ],
)
internal data class CaptureDraftMergeSessionReceiptEntity(
    @ColumnInfo(name = "receipt_reference")
    val receiptReference: String,
    @ColumnInfo(name = "batch_job_id")
    val batchJobId: String,
    @ColumnInfo(name = "batch_page_index")
    val batchPageIndex: Int,
    @ColumnInfo(name = "primary_draft_id")
    val primaryDraftId: String,
    @ColumnInfo(name = "following_draft_id")
    val followingDraftId: String,
    @ColumnInfo(name = "merged_draft_id")
    val mergedDraftId: String,
    @ColumnInfo(name = "asset_order_fingerprint")
    val assetOrderFingerprint: String,
    @ColumnInfo(name = "session_version")
    val sessionVersion: Long,
    @ColumnInfo(name = "source_asset_count")
    val sourceAssetCount: Int,
    @ColumnInfo(name = "request_canonical_fingerprint")
    val requestCanonicalFingerprint: String,
    @ColumnInfo(name = "merged_at_epoch_millis")
    val mergedAtEpochMillis: Long,
)
