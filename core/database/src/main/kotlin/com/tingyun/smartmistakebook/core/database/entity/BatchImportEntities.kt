package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "batch_import_job",
    indices = [Index(value = ["request_id"], unique = true)],
    primaryKeys = ["job_id"],
)
internal data class BatchImportJobEntity(
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "request_fingerprint")
    val requestFingerprint: String,
    val status: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "batch_import_page",
    primaryKeys = ["job_id", "page_index"],
    foreignKeys = [
        ForeignKey(
            entity = BatchImportJobEntity::class,
            parentColumns = ["job_id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProblemDraftEntity::class,
            parentColumns = ["draft_id"],
            childColumns = ["result_draft_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["job_id", "status", "page_index"]),
        Index(value = ["result_draft_id"]),
    ],
)
internal data class BatchImportPageEntity(
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String,
    val status: String,
    @ColumnInfo(name = "result_draft_id")
    val resultDraftId: String?,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "boundary_after_status", defaultValue = "PENDING")
    val boundaryAfterStatus: String,
    @ColumnInfo(name = "boundary_claimed_at_epoch_millis")
    val boundaryClaimedAtEpochMillis: Long?,
)

@Entity(
    tableName = "batch_import_boundary_resolution_receipt",
    primaryKeys = ["job_id", "page_index"],
    foreignKeys = [
        ForeignKey(
            entity = BatchImportPageEntity::class,
            parentColumns = ["job_id", "page_index"],
            childColumns = ["job_id", "page_index"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class BatchImportBoundaryResolutionReceiptEntity(
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "primary_draft_session_id")
    val primaryDraftSessionId: String,
    @ColumnInfo(name = "following_draft_session_id")
    val followingDraftSessionId: String,
    val resolution: String,
    @ColumnInfo(name = "boundary_claimed_at_epoch_millis")
    val boundaryClaimedAtEpochMillis: Long,
    @ColumnInfo(name = "capture_merge_receipt_ref")
    val captureMergeReceiptRef: String?,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
)
