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
class TutorLearningMemoryMigrationInstrumentedTest {
    @Test
    fun versionThirtyThreeAddsEmptyMemoryTablesWithoutRewritingLearningHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-learning-memory-v34-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 33)
            val before = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.seedVersionThirtyThreeHistory()
                database.legacyHistorySnapshot()
            }

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                store.openTutorConversation(
                    learnerId = "migration-probe",
                    conversationId = "missing-conversation",
                    conversationGeneration = 1,
                )
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.setForeignKeyConstraintsEnabled(true)
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals(before, database.legacyHistorySnapshot())
                assertTrue(NEW_TABLES.all { database.rowCount(it) == 0 })
                assertEquals("ok", database.scalarText("PRAGMA integrity_check"))
                assertEquals(1, database.scalarInt("PRAGMA foreign_keys"))
                assertEquals(0, database.resultRowCount("PRAGMA foreign_key_check"))
                assertEquals(
                    ANCHOR_COLUMNS,
                    database.tableColumns("learning_problem_anchor"),
                )
                assertEquals(
                    0,
                    database.indexUniqueness(
                        table = "learning_observation_source_fact",
                        index = "index_learning_observation_source_fact_payload_fingerprint",
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.seedVersionThirtyThreeHistory() {
        execSQL(
            """
            INSERT INTO problem(
                problem_id, canonical_fingerprint, subject, created_at_epoch_millis, archived_at_epoch_millis
            ) VALUES('legacy-problem', '${"a".repeat(64)}', 'MATH', 10, NULL)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO learning_sequence(learner_id, last_allocated_sequence)
            VALUES('legacy-learner', 7)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO projection_outbox(
                outbox_id, learner_id, outbox_sequence, event_kind, event_id,
                canonical_fingerprint, status, created_at_epoch_millis
            ) VALUES(
                'legacy-outbox', 'legacy-learner', 7, 'ATTEMPT', 'legacy-event',
                '${"b".repeat(64)}', 'PENDING', 11
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO learning_observation_candidate(
                candidate_id, learner_id, source, source_reference_id,
                practice_unit_id, problem_revision_id, direction, evidence_level,
                evidence_weight, independence, occurred_at_epoch_millis, model_version,
                evidence_locator, status, retry_count, payload_fingerprint,
                created_at_epoch_millis, updated_at_epoch_millis
            ) VALUES(
                'legacy-candidate', 'legacy-learner', 'TUTOR_CHOICE', 'legacy-source',
                NULL, NULL, 'POSITIVE', 'STRONG', 1.0, 'INDEPENDENT', 12, 'legacy-model',
                'turn:1', 'PENDING_ATTRIBUTION', 0, '${"c".repeat(64)}', 12, 12
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.legacyHistorySnapshot(): List<String> =
        listOf(
            scalarText(
                """
                SELECT problem_id || '|' || canonical_fingerprint || '|' || subject || '|' ||
                    created_at_epoch_millis || '|' || COALESCE(archived_at_epoch_millis, '')
                FROM problem WHERE problem_id = 'legacy-problem'
                """.trimIndent(),
            ),
            scalarText(
                """
                SELECT learner_id || '|' || last_allocated_sequence
                FROM learning_sequence WHERE learner_id = 'legacy-learner'
                """.trimIndent(),
            ),
            scalarText(
                """
                SELECT outbox_id || '|' || learner_id || '|' || outbox_sequence || '|' ||
                    event_kind || '|' || event_id || '|' || canonical_fingerprint || '|' ||
                    status || '|' || created_at_epoch_millis
                FROM projection_outbox WHERE outbox_id = 'legacy-outbox'
                """.trimIndent(),
            ),
            scalarText(
                """
                SELECT candidate_id || '|' || learner_id || '|' || source || '|' ||
                    source_reference_id || '|' || direction || '|' || evidence_level || '|' ||
                    evidence_weight || '|' || independence || '|' || occurred_at_epoch_millis || '|' ||
                    model_version || '|' || evidence_locator || '|' || status || '|' || retry_count || '|' ||
                    payload_fingerprint || '|' || created_at_epoch_millis || '|' || updated_at_epoch_millis
                FROM learning_observation_candidate WHERE candidate_id = 'legacy-candidate'
                """.trimIndent(),
            ),
        )

    private fun SQLiteDatabase.rowCount(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SQLiteDatabase.scalarInt(query: String): Int =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SQLiteDatabase.scalarText(query: String): String =
        rawQuery(query, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.resultRowCount(query: String): Int =
        rawQuery(query, null).use { cursor -> cursor.count }

    private fun SQLiteDatabase.tableColumns(table: String): Set<String> =
        rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun SQLiteDatabase.indexUniqueness(table: String, index: String): Int =
        rawQuery("PRAGMA index_list(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == index) return cursor.getInt(uniqueIndex)
            }
            error("Missing index $index on $table")
        }

    private companion object {
        val NEW_TABLES = listOf(
            "tutor_conversation",
            "tutor_turn_receipt",
            "tutor_evidence_request",
            "learning_problem_anchor",
            "learning_observation_source_fact",
        )
        val ANCHOR_COLUMNS = setOf(
            "anchor_id",
            "learner_id",
            "subject",
            "question_fingerprint",
            "revision_fingerprint",
            "fingerprint_version",
            "created_at_epoch_millis",
        )
    }
}
