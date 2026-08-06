package com.tingyun.smartmistakebook.core.database

/**
 * Read-only view of the cutover journal.
 *
 * Appending a terminal receipt and activating the v45 barrier are deliberately absent, so a
 * public session owner cannot assemble a self-authorizing cutover path.
 */
interface LegacyAuthorityCutoverJournalReadPort {
    suspend fun readStageReceipts(): List<LegacyAuthorityCutoverStageReceipt>
}

/**
 * Append-only transition journal for retiring authority from the legacy study database.
 *
 * This port can record and read migration receipts only. It cannot query or mutate mistakes,
 * learner mastery, curriculum knowledge, or the destination databases.
 */
interface LegacyAuthorityCutoverJournalPort : LegacyAuthorityCutoverJournalReadPort {
    suspend fun appendStageReceipt(
        command: AppendLegacyAuthorityCutoverStageCommand,
    ): LegacyAuthorityCutoverJournalWriteResult

    override suspend fun readStageReceipts(): List<LegacyAuthorityCutoverStageReceipt>
}

/**
 * Capability adapter that never retains the full [StudyDatabasePort].
 */
class StudyDatabaseLegacyAuthorityCutoverJournalAdapter(
    private val appendReceipt:
        suspend (
            AppendLegacyAuthorityCutoverStageCommand,
        ) -> LegacyAuthorityCutoverJournalWriteResult,
    private val readReceipts: suspend () -> List<LegacyAuthorityCutoverStageReceipt>,
) : LegacyAuthorityCutoverJournalPort {
    override suspend fun appendStageReceipt(
        command: AppendLegacyAuthorityCutoverStageCommand,
    ): LegacyAuthorityCutoverJournalWriteResult = appendReceipt(command)

    override suspend fun readStageReceipts(): List<LegacyAuthorityCutoverStageReceipt> =
        readReceipts()
}
