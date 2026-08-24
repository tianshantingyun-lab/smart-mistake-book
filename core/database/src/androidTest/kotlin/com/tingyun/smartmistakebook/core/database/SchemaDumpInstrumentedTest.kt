package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY (task #39, cluster A): dumps the real v34 schema shape
 * (sqlite_master rows + room_master_table identity hash) of a freshly
 * created Room database so CI can reconstruct the stale exported 34.json
 * whose identityHash lags the current entities. Delete together with the
 * schema-export workflow once schema alignment is restored.
 */
@RunWith(AndroidJUnit4::class)
class SchemaDumpInstrumentedTest {
    @Test
    fun dumpCurrentSchemaShape() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "schema-dump-${System.nanoTime()}.db"
        val store = StudyDatabaseFactory.open(context, databaseName)
        try {
            // Force the open helper to create all tables.
            store.libraryCatalogCount("", null, null, null, null)
        } finally {
            store.close()
        }
        val lines = mutableListOf<String>()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { raw ->
            raw.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    lines += "IDENTITY_HASH " + cursor.getString(0)
                }
            }
            raw.rawQuery(
                "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    lines += listOf(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3) ?: "",
                    ).joinToString("\u001f")
                }
            }
        }
        val dump = File(context.getExternalFilesDir(null), "room34-dump.txt")
        dump.writeText(lines.joinToString("\n"))
        println("schema-dump written to ${dump.absolutePath} lines=${lines.size}")
        context.deleteDatabase(databaseName)
    }
}
