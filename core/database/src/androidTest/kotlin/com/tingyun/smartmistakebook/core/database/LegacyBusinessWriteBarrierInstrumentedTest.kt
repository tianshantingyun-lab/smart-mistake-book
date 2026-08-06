package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyBusinessWriteBarrierInstrumentedTest {
    @Test
    fun freshProductionDatabaseActivatesOnceBlocksEveryBusinessTableAndSurvivesReopen() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "legacy-business-barrier-fresh-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            try {
                val firstRecord =
                    StudyDatabaseFactory.open(context, databaseName).use { store ->
                        val capability =
                            StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store)
                        val barrier = capability.read()
                        assertNotNull(barrier)
                        val durableBarrier = checkNotNull(barrier)
                        assertEquals(
                            LegacyBusinessWriteBarrierActivationKind.FRESH_EMPTY,
                            durableBarrier.activationKind,
                        )
                        assertEquals(null, durableBarrier.terminalStageOrdinal)
                        durableBarrier
                    }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { database ->
                    LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables
                        .sorted()
                        .forEach { tableName ->
                            database.assertBarrierRejected(
                                "INSERT INTO `$tableName` DEFAULT VALUES",
                            )
                        }
                    database.verifyEveryCoordinationTableCrudWorks()
                    assertEquals(
                        LegacyBusinessWriteBarrierSchema
                            .canonicalBarrierTableSql()
                            .canonicalBarrierSql(),
                        checkNotNull(
                            database.schemaSql(
                                type = "table",
                                name = LegacyBusinessWriteBarrierSchema.TABLE_NAME,
                            ),
                        ).canonicalBarrierSql(),
                    )
                    database.assertBarrierRejected(
                        """
                        UPDATE `${LegacyBusinessWriteBarrierSchema.TABLE_NAME}`
                        SET `activated_at_epoch_millis` = `activated_at_epoch_millis` + 1
                        """.trimIndent(),
                        messageFragment = "immutable",
                    )
                    database.assertBarrierRejected(
                        "DELETE FROM `${LegacyBusinessWriteBarrierSchema.TABLE_NAME}`",
                        messageFragment = "immutable",
                    )
                }

                StudyDatabaseFactory.open(context, databaseName).use { reopened ->
                    assertEquals(
                        firstRecord,
                        StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(reopened).read(),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun terminalBarrierRejectsInsertUpdateAndDeleteForEveryBlockedTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-business-barrier-all-mutations-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 44)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL("PRAGMA foreign_keys = OFF")
                database.execSQL("PRAGMA ignore_check_constraints = ON")
                LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables
                    .sorted()
                    .forEachIndexed { index, tableName ->
                        database.insertSyntheticRow(tableName, token = index + 1)
                    }
            }

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                val terminal = appendTerminalCutoverJournal(store)
                StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store).activate(
                    ActivateLegacyBusinessWriteBarrierCommand(
                        terminal.receiptFingerprint,
                    ),
                )
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL("PRAGMA foreign_keys = OFF")
                database.execSQL("PRAGMA ignore_check_constraints = ON")
                LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables
                    .sorted()
                    .forEach { tableName ->
                        database.assertBarrierRejected(
                            "INSERT INTO `$tableName` DEFAULT VALUES",
                        )
                        val firstColumn = database.firstColumnName(tableName)
                        database.assertBarrierRejected(
                            "UPDATE `$tableName` SET `$firstColumn` = `$firstColumn`",
                        )
                        database.assertBarrierRejected("DELETE FROM `$tableName`")
                    }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun onOpenRepairsAMissingCanonicalTriggerBeforeReturningTheHandle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-business-barrier-repair-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val triggerName =
            LegacyBusinessWriteBarrierSchema.triggerName(
                "problem",
                LegacyBusinessMutation.INSERT,
            )
        try {
            openAndReadBarrier(context, databaseName)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL("DROP TRIGGER `$triggerName`")
            }

            openAndReadBarrier(context, databaseName)

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                val definition =
                    LegacyBusinessWriteBarrierSchema.triggerDefinitions().getValue(triggerName)
                assertEquals(
                    definition.sql.canonicalBarrierSql(),
                    checkNotNull(database.schemaSql("trigger", triggerName))
                        .canonicalBarrierSql(),
                )
                database.assertBarrierRejected(problemInsertSql("repaired-trigger"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun onOpenRejectsSameNameWrongTriggerAndEveryUnclassifiedTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val triggerDatabaseName =
            "legacy-business-barrier-wrong-trigger-${System.nanoTime()}.db"
        val futureDatabaseName =
            "legacy-business-barrier-future-table-${System.nanoTime()}.db"
        val triggerName =
            LegacyBusinessWriteBarrierSchema.triggerName(
                "problem",
                LegacyBusinessMutation.INSERT,
            )
        listOf(triggerDatabaseName, futureDatabaseName).forEach { databaseName ->
            context.deleteDatabase(databaseName)
        }
        try {
            openAndReadBarrier(context, triggerDatabaseName)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(triggerDatabaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL("DROP TRIGGER `$triggerName`")
                database.execSQL(
                    """
                    CREATE TRIGGER `$triggerName`
                    BEFORE INSERT ON `problem`
                    BEGIN
                        SELECT 1;
                    END
                    """.trimIndent(),
                )
            }
            assertOpenFailsClosed(context, triggerDatabaseName, "not canonical")

            openAndReadBarrier(context, futureDatabaseName)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(futureDatabaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    "CREATE TABLE `sqliteX_unclassified` (`id` INTEGER PRIMARY KEY)",
                )
            }
            assertOpenFailsClosed(context, futureDatabaseName, "inventory mismatch")
        } finally {
            listOf(triggerDatabaseName, futureDatabaseName).forEach { databaseName ->
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun onOpenRejectsEveryNonCanonicalTriggerTokenBeforeReturningAHandle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName =
            "legacy-business-barrier-trigger-token-${System.nanoTime()}.db"
        val triggerName =
            LegacyBusinessWriteBarrierSchema.triggerName(
                "problem",
                LegacyBusinessMutation.INSERT,
            )
        val canonical =
            LegacyBusinessWriteBarrierSchema.triggerDefinitions().getValue(triggerName).sql
        val key = LEGACY_BUSINESS_WRITE_BARRIER_KEY
        val message = LEGACY_BUSINESS_WRITE_REJECTED_MESSAGE
        val rejected =
            listOf(
                canonical.replace(key, "legacy-business-`write`-barrier-v1"),
                canonical.replace(key, "legacy-business-\"write\"-barrier-v1"),
                canonical.replace(message, "legacy business `authority` is read-only after cutover"),
                canonical.replace(message, "legacy business \"authority\" is read-only after cutover"),
                canonical.replace(
                    message,
                    "legacy business authority''s data is read-only after cutover",
                ),
                canonical.replace("BEFORE INSERT", "BEFORE /* boundary-1 */ INSERT"),
                canonical.replace(
                    "`$triggerName`\nBEFORE",
                    "`$triggerName` -- boundary-2\nBEFORE",
                ),
                canonical.replace("WHEN EXISTS", "WHEN /* boundary-3 */ EXISTS"),
                canonical.replace(";\nEND", "; /* boundary-4 */\nEND"),
                canonical.replace("BEFORE INSERT", "BEFORE UPDATE"),
                canonical.replace("ON `problem`", "ON `problem_revision`"),
                canonical.replace("WHEN EXISTS", "WHEN NOT EXISTS"),
                canonical.replace("SELECT RAISE", "SELECT \"RAISE\""),
                canonical.replace("RAISE(ABORT", "RAISE(FAIL"),
                canonical.replace("read-only", "writable"),
                canonical.replace(
                    "`barrier_key` = '$key'",
                    "`barrier_key` COLLATE BINARY = '$key'",
                ),
                canonical.replace(
                    "`barrier_key` = '$key'",
                    "'$key' = `barrier_key`",
                ),
            )
        context.deleteDatabase(databaseName)
        try {
            openAndReadBarrier(context, databaseName)
            rejected.forEachIndexed { index, changedSql ->
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { database ->
                    database.execSQL("DROP TRIGGER `$triggerName`")
                    database.execSQL(changedSql)
                }
                assertOpenFailsClosed(
                    context = context,
                    databaseName = databaseName,
                    expectedMessage = "not canonical",
                    caseLabel = "trigger mutation $index",
                )
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL("DROP TRIGGER `$triggerName`")
                database.execSQL(canonical)
            }
            openAndReadBarrier(context, databaseName)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun upgradedPreOpenHandleCannotWriteAfterExactTerminalActivationAndReplayIsIdempotent() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "legacy-business-barrier-preopen-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            createDatabaseFromExportedSchema(context, databaseName, version = 44)
            val preOpenHandle =
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
            try {
                val activated =
                    StudyDatabaseFactory.open(context, databaseName).use { store ->
                        val terminal = appendTerminalCutoverJournal(store)
                        val capability =
                            StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store)
                        assertEquals(null, capability.read())

                        val command =
                            ActivateLegacyBusinessWriteBarrierCommand(
                                terminalReceiptFingerprint = terminal.receiptFingerprint,
                            )
                        val first = capability.activate(command)
                        val replay = capability.activate(command)
                        val conflictingFingerprint =
                            if (terminal.receiptFingerprint == "f".repeat(64)) {
                                "e".repeat(64)
                            } else {
                                "f".repeat(64)
                            }
                        val conflict =
                            runCatching {
                                capability.activate(
                                    ActivateLegacyBusinessWriteBarrierCommand(
                                        conflictingFingerprint,
                                    ),
                                )
                            }.exceptionOrNull()

                        assertEquals(
                            LegacyBusinessWriteBarrierWriteOutcome.ACTIVATED,
                            first.outcome,
                        )
                        assertEquals(
                            LegacyBusinessWriteBarrierWriteOutcome.REPLAYED,
                            replay.outcome,
                        )
                        assertEquals(first.barrier, replay.barrier)
                        assertTrue(conflict is LegacyBusinessWriteBarrierConflictException)
                        first.barrier
                    }

                preOpenHandle.assertBarrierRejected(problemInsertSql("pre-open-late-write"))

                StudyDatabaseFactory.open(context, databaseName).use { reopened ->
                    assertEquals(
                        activated,
                        StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(reopened).read(),
                    )
                }
            } finally {
                preOpenHandle.close()
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun incompleteCutoverProofCannotActivateAndPreCutoverWritesRemainAvailable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-business-barrier-incomplete-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 44)
            StudyDatabaseFactory.open(context, databaseName).use { store ->
                val partial = appendTerminalCutoverJournal(store, stageLimit = 11)
                val failure =
                    runCatching {
                        StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store).activate(
                            ActivateLegacyBusinessWriteBarrierCommand(
                                terminalReceiptFingerprint = partial.receiptFingerprint,
                            ),
                        )
                    }.exceptionOrNull()
                assertTrue(failure is LegacyBusinessWriteBarrierEligibilityException)
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(problemInsertSql("still-pre-cutover"))
                assertEquals(1, database.countProblem("still-pre-cutover"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun preOpenReadTransactionCannotPromoteItsStaleSnapshotIntoAPostCutoverWrite() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "legacy-business-barrier-reader-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            try {
                createDatabaseFromExportedSchema(context, databaseName, version = 44)
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { database ->
                    database.enableWriteAheadLogging()
                }
                StudyDatabaseFactory.open(context, databaseName).use { store ->
                    val terminal = appendTerminalCutoverJournal(store)
                    val capability =
                        StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store)
                    AndroidSQLiteDriver()
                        .open(context.getDatabasePath(databaseName).path)
                        .use { reader ->
                            reader.execSQL("BEGIN DEFERRED TRANSACTION")
                            reader.prepare("SELECT COUNT(*) FROM problem").use { statement ->
                                assertTrue(statement.step())
                                assertEquals(0L, statement.getLong(0))
                            }

                            val activation =
                                async(Dispatchers.IO) {
                                    capability.activate(
                                        ActivateLegacyBusinessWriteBarrierCommand(
                                            terminal.receiptFingerprint,
                                        ),
                                    )
                                }
                            val committedWhileReaderOpen =
                                withTimeoutOrNull(2_000) { activation.await() }
                            if (committedWhileReaderOpen != null) {
                                val staleWriteFailure =
                                    runCatching {
                                        reader.execSQL(problemInsertSql("stale-reader-write"))
                                    }.exceptionOrNull()
                                assertNotNull(staleWriteFailure)
                            }
                            reader.execSQL("ROLLBACK")
                            if (committedWhileReaderOpen == null) {
                                withTimeout(5_000) { activation.await() }
                            }
                        }
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { database ->
                    assertEquals(0, database.countProblem("stale-reader-write"))
                    database.assertBarrierRejected(problemInsertSql("post-reader-write"))
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun concurrentWriterThatOwnsTheLockCommitsBeforeActivationAndLaterWriterIsRejected() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "legacy-business-barrier-writer-order-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            createDatabaseFromExportedSchema(context, databaseName, version = 44)
            val writer =
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
            try {
                StudyDatabaseFactory.open(context, databaseName).use { store ->
                    val terminal = appendTerminalCutoverJournal(store)
                    val capability =
                        StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store)

                    writer.beginTransaction()
                    writer.execSQL(problemInsertSql("writer-before-cutover"))
                    val activationEntered = CountDownLatch(1)
                    val activation =
                        async(Dispatchers.IO) {
                            activationEntered.countDown()
                            capability.activate(
                                ActivateLegacyBusinessWriteBarrierCommand(
                                    terminal.receiptFingerprint,
                                ),
                            )
                        }
                    assertTrue(activationEntered.await(2, TimeUnit.SECONDS))
                    writer.setTransactionSuccessful()
                    writer.endTransaction()
                    withTimeout(5_000) { activation.await() }
                }

                assertEquals(1, writer.countProblem("writer-before-cutover"))
                writer.assertBarrierRejected(problemInsertSql("writer-after-cutover"))
            } finally {
                if (writer.inTransaction()) {
                    writer.endTransaction()
                }
                writer.close()
                context.deleteDatabase(databaseName)
            }
        }
}

private suspend fun appendTerminalCutoverJournal(
    store: StudyDatabasePort,
    stageLimit: Int = LegacyBusinessWriteBarrierSchema.TERMINAL_STAGE_ORDINAL,
): LegacyAuthorityCutoverStageReceipt {
    require(stageLimit in 1..LegacyBusinessWriteBarrierSchema.TERMINAL_STAGE_ORDINAL)
    var predecessor: String? = null
    return LegacyBusinessWriteBarrierSchema.terminalCutoverStages
        .take(stageLimit)
        .map { stage ->
            store.appendLegacyAuthorityCutoverStageReceipt(
                AppendLegacyAuthorityCutoverStageCommand(
                    stageOrdinal = stage.ordinal,
                    stageName = stage.stageName,
                    targetDatabaseName = stage.targetDatabaseName,
                    migratedRecordCount = stage.ordinal.toLong(),
                    checkpoint = "terminal-checkpoint-${stage.ordinal}",
                    destinationFingerprint = stage.ordinal.toString(16).padStart(64, '0'),
                    completedAtEpochMillis = stage.ordinal.toLong(),
                    predecessorReceiptFingerprint = predecessor,
                ),
            ).receipt.also { receipt -> predecessor = receipt.receiptFingerprint }
        }
        .last()
}

private fun SQLiteDatabase.verifyEveryCoordinationTableCrudWorks() {
    execSQL("PRAGMA foreign_keys = OFF")
    execSQL("PRAGMA ignore_check_constraints = ON")
    LegacyBusinessWriteBarrierSchema.allowedCoordinationTables
        .sorted()
        .forEachIndexed { index, tableName ->
            insertSyntheticRow(tableName, token = 1_000 + index)
            val firstColumn = firstColumnName(tableName)
            execSQL("UPDATE `$tableName` SET `$firstColumn` = `$firstColumn`")
            execSQL("DELETE FROM `$tableName`")
            assertEquals(0, rowCount(tableName))
        }
}

private fun SQLiteDatabase.assertBarrierRejected(
    sql: String,
    messageFragment: String = LEGACY_BUSINESS_WRITE_REJECTED_MESSAGE,
) {
    val failure = runCatching { execSQL(sql) }.exceptionOrNull()
    if (failure == null) {
        fail("Expected legacy barrier to reject: $sql")
        return
    }
    assertTrue(
        generateSequence<Throwable>(failure) { current -> current.cause }
            .mapNotNull { current -> current.message }
            .any { message -> message.contains(messageFragment) },
    )
}

private fun SQLiteDatabase.countProblem(problemId: String): Int =
    rawQuery(
        "SELECT COUNT(*) FROM problem WHERE problem_id = ?",
        arrayOf(problemId),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun SQLiteDatabase.insertSyntheticRow(
    tableName: String,
    token: Int,
) {
    val columns =
        rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SyntheticColumn(
                            name = cursor.getString(1),
                            declaredType = cursor.getString(2).orEmpty(),
                        ),
                    )
                }
            }
        }
    check(columns.isNotEmpty()) { "No columns found for $tableName" }
    val names = columns.joinToString { column -> "`${column.name}`" }
    val values =
        columns.joinToString { column ->
            column.syntheticSqlLiteral(tableName, token)
        }
    execSQL("INSERT INTO `$tableName` ($names) VALUES ($values)")
}

private fun SQLiteDatabase.firstColumnName(tableName: String): String =
    rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
        check(cursor.moveToFirst()) { "No columns found for $tableName" }
        cursor.getString(1)
    }

private fun SQLiteDatabase.rowCount(tableName: String): Int =
    rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun SQLiteDatabase.schemaSql(
    type: String,
    name: String,
): String? =
    rawQuery(
        "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?",
        arrayOf(type, name),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private suspend fun openAndReadBarrier(
    context: Context,
    databaseName: String,
) {
    StudyDatabaseFactory.open(context, databaseName).use { store ->
        assertNotNull(
            StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store).read(),
        )
    }
}

private suspend fun assertOpenFailsClosed(
    context: Context,
    databaseName: String,
    expectedMessage: String,
    caseLabel: String = databaseName,
) {
    var handleReturned = false
    val failure =
        runCatching {
            StudyDatabaseFactory.open(context, databaseName).use { store ->
                handleReturned = true
                StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(store).read()
            }
        }.exceptionOrNull()
    assertFalse("Database handle returned for $caseLabel", handleReturned)
    assertNotNull(failure)
    assertTrue(
        generateSequence(checkNotNull(failure)) { current -> current.cause }
            .mapNotNull(Throwable::message)
            .any { message -> message.contains(expectedMessage) },
    )
}

private data class SyntheticColumn(
    val name: String,
    val declaredType: String,
) {
    fun syntheticSqlLiteral(
        tableName: String,
        token: Int,
    ): String {
        val affinity = declaredType.uppercase()
        return when {
            "INT" in affinity -> token.toString()
            "REAL" in affinity || "FLOA" in affinity || "DOUB" in affinity ->
                "$token.25"
            "BLOB" in affinity -> "X'${token.toString(16).padStart(8, '0')}'"
            else -> "'seed_${tableName}_${name}_$token'"
        }
    }
}

private fun String.canonicalBarrierSql(): String =
    trim()
        .trimEnd(';')
        .replace("`", "")
        .replace("\"", "")
        .replace(Regex("\\s+"), " ")

private fun problemInsertSql(problemId: String): String =
    """
    INSERT INTO problem (
        problem_id,
        canonical_fingerprint,
        subject,
        created_at_epoch_millis
    ) VALUES (
        '$problemId',
        '${problemId.padEnd(64, '0').take(64)}',
        'PHYSICS',
        1
    )
    """.trimIndent()
