package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.AppendLegacyAuthorityCutoverStageCommand
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalPort
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalReadPort
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverStageReceipt

/**
 * Maps the service cutover chain to the narrow append-only legacy journal capability.
 *
 * Both layers deliberately use the same canonical fingerprint schema. A restart therefore restores
 * the exact service receipt rather than inventing a second chain or trusting an unchecked mapping.
 */
internal class LegacyAuthorityCutoverJournalAdapter(
    private val journal: LegacyAuthorityCutoverJournalPort,
) : AuthorityCutoverJournal {
    override suspend fun readOrdered(): List<AuthorityStageReceipt> =
        journal.readOrderedAuthorityReceipts()

    override suspend fun appendIfAbsent(
        receipt: AuthorityStageReceipt,
    ): AuthorityStageReceipt {
        check(receipt.stage != ThreeAuthorityCutoverStage.CUTOVER_COMPLETE) {
            "Final three-database completion cannot be written to the legacy migration source"
        }
        check(receipt.hasValidFingerprint()) {
            "A tampered cutover receipt cannot be appended to the legacy migration journal"
        }
        val persisted =
            journal.appendStageReceipt(
                AppendLegacyAuthorityCutoverStageCommand(
                    stageOrdinal = receipt.stage.ordinal + 1,
                    stageName = receipt.stage.name,
                    targetDatabaseName = receipt.targetDatabaseName,
                    migratedRecordCount = receipt.migratedRecordCount,
                    checkpoint = receipt.sourceCheckpoint,
                    destinationFingerprint = receipt.destinationFingerprint,
                    completedAtEpochMillis = receipt.completedAtEpochMillis,
                    predecessorReceiptFingerprint = receipt.previousReceiptFingerprint,
                ),
            ).receipt.toAuthorityReceipt()
        check(persisted == receipt) {
            "Durable cutover journal returned a different immutable stage receipt"
        }
        return persisted
    }
}

internal suspend fun LegacyAuthorityCutoverJournalReadPort.readOrderedAuthorityReceipts():
    List<AuthorityStageReceipt> =
    readStageReceipts()
        .map(LegacyAuthorityCutoverStageReceipt::toAuthorityReceipt)
        .also { receipts ->
            check(
                receipts.none {
                    it.stage == ThreeAuthorityCutoverStage.CUTOVER_COMPLETE
                },
            ) {
                "Final three-database completion cannot be owned by the legacy migration source"
            }
        }

private fun LegacyAuthorityCutoverStageReceipt.toAuthorityReceipt(): AuthorityStageReceipt {
    val stage =
        ThreeAuthorityCutoverStage.entries.getOrNull(stageOrdinal - 1)
            ?: error("Durable cutover journal contains an unknown stage ordinal")
    check(stageName == stage.name) {
        "Durable cutover journal stage name does not match its ordinal"
    }
    val mapped =
        AuthorityStageReceipt.decodeLegacyReceipt(
            stage = stage,
            targetDatabaseName = targetDatabaseName,
            migratedRecordCount = migratedRecordCount,
            sourceCheckpoint = checkpoint,
            destinationFingerprint = destinationFingerprint,
            completedAtEpochMillis = completedAtEpochMillis,
            previousReceiptFingerprint = predecessorReceiptFingerprint,
            receiptFingerprint = receiptFingerprint,
        )
    check(mapped.hasValidFingerprint()) {
        "Durable cutover receipt fingerprint does not match its stored fields"
    }
    return mapped
}
