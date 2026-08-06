package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryMigration15To16InstrumentedTest {
    @Test
    fun standardSchema15MigratesTo16WithoutChangingExistingReceiptLedger() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "mastery-v15-v16-standard.mastery-test.db"
        context.deleteDatabase(databaseName)
        val helper = MigrationTestHelper(
            instrumentation = instrumentation,
            file = context.getDatabasePath(databaseName),
            driver = AndroidSQLiteDriver(),
            databaseClass = LearnerMasteryRoomDatabase::class,
        )
        try {
            helper.createDatabase(15).close()
            helper.runMigrationsAndValidate(
                version = 16,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_15_16),
            ).close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationRepairsMissingOpenResponseReceiptLedgerAndInstallsImmutableGuards() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "mastery-v15-v16-open-response-receipt.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper = MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = LearnerMasteryRoomDatabase::class,
            )
            try {
                helper.createDatabase(15).use { connection ->
                    // Reproduces the only unsafe v15 shape: the owner exists in code but its
                    // durable receipt table was absent from the installed database.
                    connection.execSQL(
                        "DROP TABLE IF EXISTS " +
                            "`$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`",
                    )
                }

                helper.runMigrationsAndValidate(
                    version = 16,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_15_16),
                ).close()

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        1,
                        sqlite.countSchemaObjects(
                            type = "table",
                            name = LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                        ),
                    )
                    assertEquals(
                        // Ten declared Room indexes plus SQLite's primary-key autoindex.
                        11,
                        sqlite.countSchemaObjects(
                            type = "index",
                            tableName =
                                LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                        ),
                    )
                    assertEquals(
                        1,
                        sqlite.countSchemaObjects(
                            type = "trigger",
                            name =
                                "immutable_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_update",
                        ),
                    )
                    assertEquals(
                        1,
                        sqlite.countSchemaObjects(
                            type = "trigger",
                            name =
                                "immutable_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_delete",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
}

private fun SQLiteDatabase.countSchemaObjects(
    type: String,
    name: String? = null,
    tableName: String? = null,
): Int {
    val clauses = mutableListOf("type = ?")
    val arguments = mutableListOf(type)
    name?.let {
        clauses += "name = ?"
        arguments += it
    }
    tableName?.let {
        clauses += "tbl_name = ?"
        arguments += it
    }
    return rawQuery(
        "SELECT COUNT(*) FROM sqlite_master WHERE ${clauses.joinToString(" AND ")}",
        arguments.toTypedArray(),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
