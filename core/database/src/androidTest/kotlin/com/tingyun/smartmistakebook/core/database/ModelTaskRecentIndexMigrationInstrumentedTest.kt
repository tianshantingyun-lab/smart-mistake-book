package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelTaskRecentIndexMigrationInstrumentedTest {
    @Test
    fun versionTwentySixAddsAnIndexForBoundedRecentConversationRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "model-task-recent-index-v26-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 26)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            migrated.observeRecentModelTasks(
                subjectId = "tutor-lobby",
                kind = ModelTaskKind.TUTOR_LOBBY,
                limit = 64,
            ).first()
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertTrue(database.modelTaskIndexNames().contains(MODEL_TASK_RECENT_INDEX_NAME))
                val queryPlan = database.recentModelTaskQueryPlan()
                assertTrue(queryPlan.any { it.contains(MODEL_TASK_RECENT_INDEX_NAME) })
                assertFalse(queryPlan.any { it.contains("USE TEMP B-TREE") })
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.modelTaskIndexNames(): Set<String> =
        rawQuery("PRAGMA index_list(`model_task`)", null).use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    private fun SQLiteDatabase.recentModelTaskQueryPlan(): List<String> =
        rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT *
            FROM model_task
            WHERE subject_id = ? AND task_kind = ?
            ORDER BY created_at_epoch_millis DESC, request_id DESC
            LIMIT 64
            """.trimIndent(),
            arrayOf("tutor-lobby", "TUTOR_LOBBY"),
        ).use { cursor ->
            val detailColumn = cursor.getColumnIndexOrThrow("detail")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(detailColumn))
            }
        }
}
