package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeBaseMigrationInstrumentedTest {
    @Test
    fun versionSeventeenKnowledgeNodeBecomesACompatibleTopicNode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-base-v17-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 17)
            seedLegacyKnowledgeNode(context, databaseName)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val node = migrated.readSubjectKnowledgeNodes("MATH", limit = 8).single()

            assertEquals("利用导数研究函数单调性", node.canonicalName)
            assertEquals("TOPIC", node.nodeKind)
            assertEquals("TOPIC", node.granularity)
            assertTrue(node.aliases.isEmpty())
            assertEquals("MODEL_CANDIDATE", node.verificationStatus)
            migrated.close()

            val database = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                assertEquals(STUDY_DATABASE_VERSION, database.version)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun seedLegacyKnowledgeNode(context: Context, databaseName: String) {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.execSQL(
                """
                INSERT INTO knowledge_node (
                    knowledge_node_id, stable_code, subject, display_name,
                    parent_knowledge_node_id, taxonomy_version, created_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "knowledge-1",
                    "math:knowledge:derivative-monotonicity",
                    "MATH",
                    "利用导数研究函数单调性",
                    null,
                    "organization-v1",
                    1_000L,
                ),
            )
        } finally {
            database.close()
        }
    }
}
