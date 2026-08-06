package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.AppendLegacyAuthorityCutoverStageCommand
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalPort
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalWriteOutcome
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalWriteResult
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverStageReceipt
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyAuthorityCutoverJournalAdapterTest {
    @Test
    fun appendAndRestartRestoreTheExactServiceReceipt() = runBlocking {
        val port = FakePort()
        val adapter = LegacyAuthorityCutoverJournalAdapter(port)
        val receipt =
            AuthorityStageReceipt.create(
                stage = ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY,
                targetDatabaseName = LEGACY_MIGRATION_SOURCE_DATABASE_NAME,
                migratedRecordCount = 0,
                sourceCheckpoint = "legacy-schema-38",
                destinationFingerprint = sha("legacy-schema"),
                completedAtEpochMillis = 1_000,
                previousReceiptFingerprint = null,
            )

        assertEquals(receipt, adapter.appendIfAbsent(receipt))
        assertEquals(receipt, LegacyAuthorityCutoverJournalAdapter(port).readOrdered().single())
    }

    @Test
    fun legacyMigrationJournalCannotClaimFinalThreeDatabaseCompletion() {
        val port = FakePort()
        val adapter = LegacyAuthorityCutoverJournalAdapter(port)
        val completion = decodedTerminalReceipt("three-authority-cutover-complete", 2_000)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { adapter.appendIfAbsent(completion) }
        }
    }

    @Test
    fun currentReceiptFactoryCannotManufactureLegacyTerminalCompletion() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityStageReceipt.create(
                stage = ThreeAuthorityCutoverStage.CUTOVER_COMPLETE,
                targetDatabaseName = LEGACY_MIGRATION_SOURCE_DATABASE_NAME,
                migratedRecordCount = 0,
                sourceCheckpoint = "three-authority-cutover-complete",
                destinationFingerprint = sha("combined-authority-state"),
                completedAtEpochMillis = 2_000,
                previousReceiptFingerprint = sha("predecessor"),
            )
        }
    }

    @Test
    fun obsoletePersistedTerminalReceiptIsDecodedOnlyToFailClosed() {
        val terminal = decodedTerminalReceipt("obsolete-terminal", 3_000)
        val adapter =
            LegacyAuthorityCutoverJournalAdapter(
                FakePort(listOf(terminal.toLegacyReceipt())),
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { adapter.readOrdered() }
        }
    }

    @Test
    fun mismatchedDurableFingerprintFailsClosed() {
        val valid =
            command(
                stage = ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY,
                predecessor = null,
            ).toReceipt()
        val tampered =
            valid.copy(checkpoint = "${valid.checkpoint}-tampered")
        val adapter = LegacyAuthorityCutoverJournalAdapter(FakePort(listOf(tampered)))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { adapter.readOrdered() }
        }
    }

    private class FakePort(
        initial: List<LegacyAuthorityCutoverStageReceipt> = emptyList(),
    ) : LegacyAuthorityCutoverJournalPort {
        private val receipts = initial.toMutableList()

        override suspend fun appendStageReceipt(
            command: AppendLegacyAuthorityCutoverStageCommand,
        ): LegacyAuthorityCutoverJournalWriteResult {
            val receipt = command.toReceipt()
            receipts.removeAll { it.stageOrdinal == receipt.stageOrdinal }
            receipts += receipt
            return LegacyAuthorityCutoverJournalWriteResult(
                outcome = LegacyAuthorityCutoverJournalWriteOutcome.INSERTED,
                receipt = receipt,
            )
        }

        override suspend fun readStageReceipts(): List<LegacyAuthorityCutoverStageReceipt> =
            receipts.sortedBy(LegacyAuthorityCutoverStageReceipt::stageOrdinal)
    }

    private companion object {
        fun decodedTerminalReceipt(
            checkpoint: String,
            completedAtEpochMillis: Long,
        ): AuthorityStageReceipt {
            val stage = ThreeAuthorityCutoverStage.CUTOVER_COMPLETE
            val destinationFingerprint = sha("destination-$checkpoint")
            val predecessorFingerprint = sha("predecessor-$checkpoint")
            val receiptFingerprint =
                CanonicalSha256("legacy-authority-cutover-stage-receipt-v1")
                    .field("stageOrdinal", stage.ordinal + 1)
                    .field("stageName", stage.name)
                    .field(
                        "targetDatabaseName",
                        LEGACY_MIGRATION_SOURCE_DATABASE_NAME,
                    )
                    .field("migratedRecordCount", 0L)
                    .field("checkpoint", checkpoint)
                    .field("destinationFingerprint", destinationFingerprint)
                    .field("completedAtEpochMillis", completedAtEpochMillis)
                    .nullableField(
                        "predecessorReceiptFingerprint",
                        predecessorFingerprint,
                    )
                    .finish()
            return AuthorityStageReceipt.decodeLegacyReceipt(
                stage = stage,
                targetDatabaseName = LEGACY_MIGRATION_SOURCE_DATABASE_NAME,
                migratedRecordCount = 0L,
                sourceCheckpoint = checkpoint,
                destinationFingerprint = destinationFingerprint,
                completedAtEpochMillis = completedAtEpochMillis,
                previousReceiptFingerprint = predecessorFingerprint,
                receiptFingerprint = receiptFingerprint,
            )
        }

        fun command(
            stage: ThreeAuthorityCutoverStage,
            predecessor: String?,
        ) = AppendLegacyAuthorityCutoverStageCommand(
            stageOrdinal = stage.ordinal + 1,
            stageName = stage.name,
            targetDatabaseName =
                ThreeAuthorityDatabaseLayout().cutoverStorageNameFor(stage.storageTarget),
            migratedRecordCount = 0,
            checkpoint = "checkpoint-${stage.ordinal}",
            destinationFingerprint = sha("destination-${stage.ordinal}"),
            completedAtEpochMillis = 1_000L + stage.ordinal,
            predecessorReceiptFingerprint = predecessor,
        )

        fun AppendLegacyAuthorityCutoverStageCommand.toReceipt() =
            AuthorityStageReceipt.create(
                stage = ThreeAuthorityCutoverStage.entries[stageOrdinal - 1],
                targetDatabaseName = targetDatabaseName,
                migratedRecordCount = migratedRecordCount,
                sourceCheckpoint = checkpoint,
                destinationFingerprint = destinationFingerprint,
                completedAtEpochMillis = completedAtEpochMillis,
                previousReceiptFingerprint = predecessorReceiptFingerprint,
            ).let { authority ->
                LegacyAuthorityCutoverStageReceipt(
                    stageOrdinal = stageOrdinal,
                    stageName = stageName,
                    targetDatabaseName = targetDatabaseName,
                    migratedRecordCount = migratedRecordCount,
                    checkpoint = checkpoint,
                    destinationFingerprint = destinationFingerprint,
                    completedAtEpochMillis = completedAtEpochMillis,
                    predecessorReceiptFingerprint = predecessorReceiptFingerprint,
                    receiptFingerprint = authority.receiptFingerprint,
                )
            }

        fun AuthorityStageReceipt.toLegacyReceipt() =
            LegacyAuthorityCutoverStageReceipt(
                stageOrdinal = stage.ordinal + 1,
                stageName = stage.name,
                targetDatabaseName = targetDatabaseName,
                migratedRecordCount = migratedRecordCount,
                checkpoint = sourceCheckpoint,
                destinationFingerprint = destinationFingerprint,
                completedAtEpochMillis = completedAtEpochMillis,
                predecessorReceiptFingerprint = previousReceiptFingerprint,
                receiptFingerprint = receiptFingerprint,
            )

        fun sha(value: String): String =
            CanonicalSha256("legacy-authority-cutover-journal-adapter-test")
                .field("value", value)
                .finish()
    }
}
