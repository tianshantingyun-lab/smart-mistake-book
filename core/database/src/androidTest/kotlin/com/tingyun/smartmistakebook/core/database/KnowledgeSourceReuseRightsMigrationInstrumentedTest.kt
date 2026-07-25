package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeSourceReuseRightsMigrationInstrumentedTest {
    @Test
    fun versionTwentySevenPreservesSourcesAsSynthesisOnlyUntilReuseIsProven() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-source-rights-v27-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 27)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    INSERT INTO knowledge_source(
                        source_id, subject, source_type, title, publisher, edition,
                        source_uri, license_status, content_fingerprint,
                        imported_at_epoch_millis
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        "source:legacy:math",
                        "MATH",
                        "MANUAL_RESEARCH",
                        "历史资料",
                        "示例发布者",
                        "旧版",
                        "https://example.org/legacy",
                        "REFERENCE_ONLY",
                        "A".repeat(64),
                        1L,
                    ),
                )
            }

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            migrated.readKnowledgeSourcesByIds(setOf("source:legacy:math"))
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    """
                    SELECT content_use_policy, license_expression, license_uri, attribution_text
                    FROM knowledge_source
                    WHERE source_id = ?
                    """.trimIndent(),
                    arrayOf("source:legacy:math"),
                ).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("REVIEWED_SYNTHESIS_ONLY", cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertNull(cursor.getString(2))
                    assertNull(cursor.getString(3))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
