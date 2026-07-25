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
class KnowledgeTeachingMaterialMigrationInstrumentedTest {
    @Test
    fun versionTwentyFiveAddsEmptyTeachingSupportWithoutCreatingPracticeItems() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-teaching-material-v25-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 25)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(
                migrated.readKnowledgeTeachingMaterialsForNodes(
                    subject = "MATH",
                    knowledgeNodeIds = setOf("missing"),
                    limit = 8,
                ).isEmpty(),
            )
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals(0, database.countRows("knowledge_teaching_material"))
                assertEquals(0, database.countRows("knowledge_teaching_material_node_binding"))
                assertEquals(0, database.countRows("practice_unit"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.countRows(tableName: String): Int =
        rawQuery(
            "SELECT COUNT(*) FROM ${requireSupportedTable(tableName)}",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun requireSupportedTable(tableName: String): String {
        require(
            tableName in setOf(
                "knowledge_teaching_material",
                "knowledge_teaching_material_node_binding",
                "practice_unit",
            ),
        )
        return tableName
    }
}
