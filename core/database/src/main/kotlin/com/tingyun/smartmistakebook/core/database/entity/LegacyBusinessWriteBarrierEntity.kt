package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "legacy_business_write_barrier")
internal data class LegacyBusinessWriteBarrierEntity(
    @PrimaryKey
    @ColumnInfo(name = "barrier_key")
    val barrierKey: String,
    @ColumnInfo(name = "activation_kind")
    val activationKind: String,
    @ColumnInfo(name = "terminal_stage_ordinal")
    val terminalStageOrdinal: Int?,
    @ColumnInfo(name = "terminal_receipt_fingerprint")
    val terminalReceiptFingerprint: String?,
    @ColumnInfo(name = "activation_receipt_fingerprint")
    val activationReceiptFingerprint: String,
    @ColumnInfo(name = "activated_at_epoch_millis")
    val activatedAtEpochMillis: Long,
)
