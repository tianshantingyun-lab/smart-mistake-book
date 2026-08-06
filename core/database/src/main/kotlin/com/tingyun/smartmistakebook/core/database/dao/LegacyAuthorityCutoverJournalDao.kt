package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.AppendLegacyAuthorityCutoverStageCommand
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalChain
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalIntegrityException
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalWriteOutcome
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalWriteResult
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverReceiptConflictException
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverStageReceipt
import com.tingyun.smartmistakebook.core.database.entity.LegacyAuthorityCutoverStageReceiptEntity

@Dao
internal abstract class LegacyAuthorityCutoverJournalDao {
    @Query(
        """
        SELECT stage_ordinal, stage_name, target_database_name, migrated_record_count, checkpoint,
            destination_fingerprint, completed_at_epoch_millis,
            predecessor_receipt_fingerprint, receipt_fingerprint
        FROM legacy_authority_cutover_stage_receipt
        ORDER BY stage_ordinal ASC
        """,
    )
    protected abstract suspend fun readEntitiesOrdered():
        List<LegacyAuthorityCutoverStageReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(
        entity: LegacyAuthorityCutoverStageReceiptEntity,
    ): Long

    @Transaction
    open suspend fun append(
        command: AppendLegacyAuthorityCutoverStageCommand,
    ): LegacyAuthorityCutoverJournalWriteResult {
        val persisted = readVerified()
        val candidate = LegacyAuthorityCutoverJournalChain.receipt(command)
        persisted.firstOrNull { it.stageOrdinal == candidate.stageOrdinal }?.let { existing ->
            if (existing == candidate) {
                return LegacyAuthorityCutoverJournalWriteResult(
                    outcome = LegacyAuthorityCutoverJournalWriteOutcome.REPLAYED,
                    receipt = existing,
                )
            }
            throw LegacyAuthorityCutoverReceiptConflictException(candidate.stageOrdinal)
        }
        if (persisted.any { it.stageName == candidate.stageName }) {
            throw LegacyAuthorityCutoverReceiptConflictException(candidate.stageOrdinal)
        }
        LegacyAuthorityCutoverJournalChain.requireNext(persisted, candidate)

        val inserted = insertIfAbsent(candidate.toEntity()) != -1L
        val afterInsert = readVerified()
        val durable = afterInsert.firstOrNull { it.stageOrdinal == candidate.stageOrdinal }
            ?: throw LegacyAuthorityCutoverJournalIntegrityException(
                "Inserted cutover receipt was not readable",
            )
        if (durable != candidate) {
            throw LegacyAuthorityCutoverReceiptConflictException(candidate.stageOrdinal)
        }
        return LegacyAuthorityCutoverJournalWriteResult(
            outcome = if (inserted) {
                LegacyAuthorityCutoverJournalWriteOutcome.INSERTED
            } else {
                LegacyAuthorityCutoverJournalWriteOutcome.REPLAYED
            },
            receipt = durable,
        )
    }

    @Transaction
    open suspend fun readOrdered(): List<LegacyAuthorityCutoverStageReceipt> = readVerified()

    private suspend fun readVerified(): List<LegacyAuthorityCutoverStageReceipt> {
        val receipts = readEntitiesOrdered().map { entity ->
            try {
                entity.toReceipt()
            } catch (failure: IllegalArgumentException) {
                throw LegacyAuthorityCutoverJournalIntegrityException(
                    "Cutover journal contains an invalid persisted receipt",
                    failure,
                )
            }
        }
        LegacyAuthorityCutoverJournalChain.verify(receipts)
        return receipts
    }
}

private fun LegacyAuthorityCutoverStageReceipt.toEntity() =
    LegacyAuthorityCutoverStageReceiptEntity(
        stageOrdinal = stageOrdinal,
        stageName = stageName,
        targetDatabaseName = targetDatabaseName,
        migratedRecordCount = migratedRecordCount,
        checkpoint = checkpoint,
        destinationFingerprint = destinationFingerprint,
        completedAtEpochMillis = completedAtEpochMillis,
        predecessorReceiptFingerprint = predecessorReceiptFingerprint,
        receiptFingerprint = receiptFingerprint,
    )

private fun LegacyAuthorityCutoverStageReceiptEntity.toReceipt() =
    LegacyAuthorityCutoverStageReceipt(
        stageOrdinal = stageOrdinal,
        stageName = stageName,
        targetDatabaseName = targetDatabaseName,
        migratedRecordCount = migratedRecordCount,
        checkpoint = checkpoint,
        destinationFingerprint = destinationFingerprint,
        completedAtEpochMillis = completedAtEpochMillis,
        predecessorReceiptFingerprint = predecessorReceiptFingerprint,
        receiptFingerprint = receiptFingerprint,
    )
