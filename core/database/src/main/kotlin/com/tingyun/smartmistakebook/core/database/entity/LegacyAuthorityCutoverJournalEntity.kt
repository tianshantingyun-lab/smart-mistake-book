package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "legacy_authority_cutover_stage_receipt",
    indices = [
        Index(value = ["stage_name"], unique = true),
        Index(value = ["receipt_fingerprint"], unique = true),
    ],
)
internal data class LegacyAuthorityCutoverStageReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "stage_ordinal")
    val stageOrdinal: Int,
    @ColumnInfo(name = "stage_name")
    val stageName: String,
    @ColumnInfo(name = "target_database_name")
    val targetDatabaseName: String,
    @ColumnInfo(name = "migrated_record_count")
    val migratedRecordCount: Long,
    val checkpoint: String,
    @ColumnInfo(name = "destination_fingerprint")
    val destinationFingerprint: String,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long,
    @ColumnInfo(name = "predecessor_receipt_fingerprint")
    val predecessorReceiptFingerprint: String?,
    @ColumnInfo(name = "receipt_fingerprint")
    val receiptFingerprint: String,
)
