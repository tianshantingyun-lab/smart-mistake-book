package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningObservationMigrationInstrumentedTest {
    @Test
    fun versionThirtyOneAddsEmptyObservationTablesWithoutInventingEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "learning-observation-v31-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 31)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    INSERT INTO problem(problem_id, canonical_fingerprint, subject, created_at_epoch_millis)
                    VALUES('legacy-problem', 'legacy-problem-fingerprint', 'MATH', 10)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO learning_sequence(learner_id, last_allocated_sequence)
                    VALUES('legacy-learner', 7)
                    """.trimIndent(),
                )
            }

            StudyDatabaseFactory.open(context, databaseName).close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals(1, database.scalarInt("SELECT COUNT(*) FROM problem"))
                assertEquals(
                    7,
                    database.scalarInt(
                        "SELECT last_allocated_sequence FROM learning_sequence WHERE learner_id = 'legacy-learner'",
                    ),
                )
                assertEquals(
                    0,
                    database.scalarInt("SELECT COUNT(*) FROM learning_observation_candidate"),
                )
                assertEquals(
                    0,
                    database.scalarInt("SELECT COUNT(*) FROM attributed_learning_observation_event"),
                )
                assertEquals(
                    0,
                    database.scalarInt("SELECT COUNT(*) FROM learning_evidence_review_case"),
                )
                assertEquals(0, database.scalarInt("SELECT COUNT(*) FROM projection_outbox"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.scalarInt(query: String): Int =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
