package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "model_task_operation",
    indices = [Index(value = ["subject_id", "task_kind"])],
)
internal data class ModelTaskOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_fingerprint")
    val operationFingerprint: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "task_kind")
    val taskKind: String,
    @ColumnInfo(name = "dispatch_count")
    val dispatchCount: Int,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "model_task",
    foreignKeys = [
        ForeignKey(
            entity = ModelTaskOperationEntity::class,
            parentColumns = ["operation_fingerprint"],
            childColumns = ["operation_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["request_id"], unique = true),
        Index(value = ["operation_fingerprint"]),
        Index(value = ["subject_id", "task_kind"]),
        Index(
            value = [
                "subject_id",
                "task_kind",
                "created_at_epoch_millis",
                "request_id",
            ],
        ),
        Index(
            value = ["subject_id", "task_kind", "tutor_response_ordinal"],
        ),
        Index(value = ["status", "updated_at_epoch_millis"]),
    ],
)
internal data class ModelTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "request_fingerprint")
    val requestFingerprint: String,
    @ColumnInfo(name = "operation_fingerprint")
    val operationFingerprint: String,
    @ColumnInfo(name = "request_snapshot")
    val requestSnapshot: String,
    @ColumnInfo(name = "task_kind")
    val taskKind: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "tutor_response_ordinal")
    val tutorResponseOrdinal: Int?,
    val status: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    val stage: String,
    @ColumnInfo(name = "user_message")
    val userMessage: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "provider_snapshot")
    val providerSnapshot: String?,
    @ColumnInfo(name = "output_snapshot")
    val outputSnapshot: String?,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
    @ColumnInfo(name = "failure_retryable")
    val failureRetryable: Boolean?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/** Append-only audit trail for state recovery and provider diagnostics. */
@Entity(
    tableName = "model_task_event",
    primaryKeys = ["task_id", "state_version"],
    foreignKeys = [
        ForeignKey(
            entity = ModelTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["task_id"])],
)
internal data class ModelTaskEventEntity(
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "previous_status")
    val previousStatus: String?,
    @ColumnInfo(name = "next_status")
    val nextStatus: String,
    val stage: String,
    @ColumnInfo(name = "user_message")
    val userMessage: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "provider_snapshot")
    val providerSnapshot: String?,
    @ColumnInfo(name = "output_snapshot")
    val outputSnapshot: String?,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
    @ColumnInfo(name = "failure_retryable")
    val failureRetryable: Boolean?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)
