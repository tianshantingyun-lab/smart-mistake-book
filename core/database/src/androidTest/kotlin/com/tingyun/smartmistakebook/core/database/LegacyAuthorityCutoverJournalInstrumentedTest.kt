package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyAuthorityCutoverJournalInstrumentedTest {
    @Test
    fun versionThirtySevenCreatesAnEmptyJournalWithoutInventingCutoverStages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-authority-cutover-v38-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 37)

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                assertEquals(emptyList<LegacyAuthorityCutoverStageReceipt>(), store
                    .readLegacyAuthorityCutoverStageReceipts())
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals(0, database.rowCount(JOURNAL_TABLE))
                assertEquals(
                    setOf(STAGE_NAME_INDEX, RECEIPT_FINGERPRINT_INDEX),
                    database.indexNames(JOURNAL_TABLE),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun receiptsRemainOrderedAcrossRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-authority-restart-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            val expected = StudyDatabaseFactory.open(context, databaseName).use { store ->
                appendThreeStages(store)
            }

            StudyDatabaseFactory.open(context, databaseName).use { reopened ->
                assertEquals(expected, reopened.readLegacyAuthorityCutoverStageReceipts())
                assertEquals(listOf(1, 2, 3), expected.map { it.stageOrdinal })
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun concurrentDuplicateIsIdempotentAndDifferentReceiptConflicts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        StudyDatabaseFactory.openInMemory(context).use { store ->
            val command = firstCommand()
            val writes = List(24) {
                async(Dispatchers.IO) {
                    store.appendLegacyAuthorityCutoverStageReceipt(command)
                }
            }.awaitAll()

            assertEquals(
                1,
                writes.count {
                    it.outcome == LegacyAuthorityCutoverJournalWriteOutcome.INSERTED
                },
            )
            assertEquals(
                23,
                writes.count {
                    it.outcome == LegacyAuthorityCutoverJournalWriteOutcome.REPLAYED
                },
            )
            assertEquals(1, writes.map { it.receipt.receiptFingerprint }.distinct().size)

            val failure = try {
                store.appendLegacyAuthorityCutoverStageReceipt(
                    command.copy(
                        checkpoint = "student-mistakes:page:collision",
                        destinationFingerprint = "d".repeat(64),
                    ),
                )
                null
            } catch (caught: Throwable) {
                caught
            }
            assertTrue(failure is LegacyAuthorityCutoverReceiptConflictException)
            assertEquals(
                listOf(writes.first().receipt),
                store.readLegacyAuthorityCutoverStageReceipts(),
            )
        }
    }

    @Test
    fun corruptedChainFailsClosedAndIsNotRepairedDuringRead() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-authority-corrupt-${System.nanoTime()}.db"
        val tamperedPredecessor = "f".repeat(64)
        context.deleteDatabase(databaseName)
        try {
            StudyDatabaseFactory.open(context, databaseName).use { store ->
                appendThreeStages(store)
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    UPDATE `$JOURNAL_TABLE`
                    SET `predecessor_receipt_fingerprint` = ?
                    WHERE `stage_ordinal` = 2
                    """.trimIndent(),
                    arrayOf(tamperedPredecessor),
                )
            }

            StudyDatabaseFactory.open(context, databaseName).use { reopened ->
                val failure = try {
                    reopened.readLegacyAuthorityCutoverStageReceipts()
                    null
                } catch (caught: Throwable) {
                    caught
                }
                assertTrue(failure is LegacyAuthorityCutoverJournalIntegrityException)
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(
                    tamperedPredecessor,
                    database.singleText(
                        """
                        SELECT predecessor_receipt_fingerprint
                        FROM `$JOURNAL_TABLE`
                        WHERE stage_ordinal = 2
                        """.trimIndent(),
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun appendThreeStages(
        store: StudyDatabasePort,
    ): List<LegacyAuthorityCutoverStageReceipt> {
        val first = store.appendLegacyAuthorityCutoverStageReceipt(firstCommand()).receipt
        val second = store.appendLegacyAuthorityCutoverStageReceipt(
            AppendLegacyAuthorityCutoverStageCommand(
                stageOrdinal = 2,
                stageName = "learner-mastery-authority",
                targetDatabaseName = LegacyAuthorityDatabaseName.LEARNER_MASTERY,
                migratedRecordCount = 31,
                checkpoint = "learner-mastery:event:31",
                destinationFingerprint = "b".repeat(64),
                completedAtEpochMillis = 200,
                predecessorReceiptFingerprint = first.receiptFingerprint,
            ),
        ).receipt
        val third = store.appendLegacyAuthorityCutoverStageReceipt(
            AppendLegacyAuthorityCutoverStageCommand(
                stageOrdinal = 3,
                stageName = "knowledge-authority",
                targetDatabaseName = LegacyAuthorityDatabaseName.HIGH_SCHOOL_KNOWLEDGE,
                migratedRecordCount = 0,
                checkpoint = "knowledge-pack:active",
                destinationFingerprint = "c".repeat(64),
                completedAtEpochMillis = 300,
                predecessorReceiptFingerprint = second.receiptFingerprint,
            ),
        ).receipt
        return listOf(first, second, third)
    }

    private fun firstCommand() = AppendLegacyAuthorityCutoverStageCommand(
        stageOrdinal = 1,
        stageName = "student-mistakes-authority",
        targetDatabaseName = LegacyAuthorityDatabaseName.STUDENT_MISTAKES,
        migratedRecordCount = 17,
        checkpoint = "student-mistakes:page:1",
        destinationFingerprint = "a".repeat(64),
        completedAtEpochMillis = 100,
        predecessorReceiptFingerprint = null,
    )

    private fun SQLiteDatabase.rowCount(tableName: String): Int =
        rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SQLiteDatabase.indexNames(tableName: String): Set<String> =
        rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ? " +
                "AND name NOT LIKE 'sqlite_autoindex_%'",
            arrayOf(tableName),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private fun SQLiteDatabase.singleText(sql: String): String =
        rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val JOURNAL_TABLE = "legacy_authority_cutover_stage_receipt"
        const val STAGE_NAME_INDEX =
            "index_legacy_authority_cutover_stage_receipt_stage_name"
        const val RECEIPT_FINGERPRINT_INDEX =
            "index_legacy_authority_cutover_stage_receipt_receipt_fingerprint"
    }
}
