package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyBusinessWriteBarrierMigrationInstrumentedTest {
    @Test
    fun v44ToV45AddsOnlyTheEmptyBarrierAndExactWriteTriggerInventory(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-business-barrier-v44-to-v45-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 44)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                assertEquals(
                    LegacyBusinessWriteBarrierSchema.v44ApplicationTables,
                    database.applicationTableNames(),
                )
                database.insertProblem("before-migration")
            }

            AndroidSQLiteDriver()
                .open(context.getDatabasePath(databaseName).path)
                .use { connection ->
                    LEGACY_BUSINESS_WRITE_BARRIER_MIGRATION_44_45.migrate(connection)
                    connection.execSQL("PRAGMA user_version = 45")
                }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                assertEquals(45, database.version)
                assertEquals(
                    LegacyBusinessWriteBarrierSchema.v45ApplicationTables,
                    database.applicationTableNames(),
                )
                assertEquals(
                    LegacyBusinessWriteBarrierSchema.triggerDefinitions()
                        .mapValues { (_, definition) ->
                            definition.tableName to definition.sql.canonicalSql()
                        },
                    database.legacyBarrierTriggers(),
                )
                assertEquals(
                    LegacyBusinessWriteBarrierSchema.canonicalBarrierTableSql().canonicalSql(),
                    checkNotNull(
                        database.schemaSql(
                            type = "table",
                            name = LegacyBusinessWriteBarrierSchema.TABLE_NAME,
                        ),
                    ).canonicalSql(),
                )
                assertEquals(
                    0,
                    database.rowCount(LegacyBusinessWriteBarrierSchema.TABLE_NAME),
                )

                // An upgrade does not invent terminal cutover proof or block ordinary migration work.
                database.insertProblem("after-migration-before-cutover")
                assertEquals(2, database.rowCount("problem"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v44MigrationRejectsEveryUnclassifiedApplicationTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("future_unclassified", "sqliteX_not_system").forEach { extraTable ->
            val databaseName =
                "legacy-business-barrier-v44-extra-${extraTable}-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            try {
                createDatabaseFromExportedSchema(context, databaseName, version = 44)
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { database ->
                    database.execSQL("CREATE TABLE `$extraTable` (`id` INTEGER PRIMARY KEY)")
                }

                val failure =
                    runCatching {
                        AndroidSQLiteDriver()
                            .open(context.getDatabasePath(databaseName).path)
                            .use { connection ->
                                LEGACY_BUSINESS_WRITE_BARRIER_MIGRATION_44_45
                                    .migrate(connection)
                            }
                    }.exceptionOrNull()
                assertTrue(failure is LegacyBusinessWriteBarrierIntegrityException)
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun quotedNotIsATypeNameChangeNotAnIdentifierQuotePersistenceDifference() {
        val quotedKeywordSql =
            "CREATE TABLE `legacy_business_write_barrier` " +
                "(`barrier_key` TEXT \"NOT\" NULL, " +
                "`activation_kind` TEXT NOT NULL, " +
                "`terminal_stage_ordinal` INTEGER, " +
                "`terminal_receipt_fingerprint` TEXT, " +
                "`activation_receipt_fingerprint` TEXT NOT NULL, " +
                "`activated_at_epoch_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`barrier_key`))"

        assertFalse(
            LegacyBusinessWriteBarrierSchema
                .isExactKnownRoomGeneratedWeakBarrierTableSql(quotedKeywordSql),
        )
        SQLiteDatabase.create(null).use { database ->
            database.execSQL(quotedKeywordSql)
            database.rawQuery(
                "PRAGMA table_info(`legacy_business_write_barrier`)",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("barrier_key", cursor.getString(1))
                assertEquals("TEXT \"NOT\"", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
        }
    }
}

private fun SQLiteDatabase.applicationTableNames(): Set<String> =
    rawQuery(
        """
        SELECT name
        FROM sqlite_master
        WHERE type = 'table'
        ORDER BY name
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (
                    name != "android_metadata" &&
                    name != "room_master_table" &&
                    !name.startsWith("sqlite_")
                ) {
                    add(name)
                }
            }
        }
    }

private fun SQLiteDatabase.legacyBarrierTriggers(): Map<String, Pair<String, String>> =
    rawQuery(
        """
        SELECT name, tbl_name, sql
        FROM sqlite_master
        WHERE type = 'trigger'
        ORDER BY name
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name.startsWith("legacy_business_barrier_")) {
                    put(
                        name,
                        cursor.getString(1) to cursor.getString(2).canonicalSql(),
                    )
                }
            }
        }
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

private fun SQLiteDatabase.insertProblem(problemId: String) {
    execSQL(
        """
        INSERT INTO problem (
            problem_id,
            canonical_fingerprint,
            subject,
            created_at_epoch_millis
        ) VALUES (?, ?, ?, ?)
        """.trimIndent(),
        arrayOf<Any>(problemId, problemId.padEnd(64, '0').take(64), "PHYSICS", 1L),
    )
}

private fun SQLiteDatabase.rowCount(tableName: String): Int =
    rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

private fun String.canonicalSql(): String =
    trim()
        .trimEnd(';')
        .replace("`", "")
        .replace("\"", "")
        .replace(Regex("\\s+"), " ")
