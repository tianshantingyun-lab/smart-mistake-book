package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryMigration20To21InstrumentedTest {
    @Test
    fun migrationPreservesLegacyReceiptAsInertAndFabricatesNoAttribution() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v20-v21-open-response-proof.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )

            helper.createDatabase(20).use { connection ->
                connection.execSQL("PRAGMA foreign_keys = OFF")
                connection.execSQL(
                    """
                    INSERT INTO mastery_open_response_weak_candidate_receipt(
                        receipt_fingerprint, canonical_fingerprint,
                        logical_attempt_fingerprint, lineage_parent_fingerprint,
                        revision_ordinal, candidate_id, candidate_canonical_fingerprint,
                        source_fact_id, review_case_id, review_case_fingerprint,
                        learner_id, subject, scope_fingerprint, conversation_id,
                        conversation_generation, conversation_state_version,
                        question_document_id, question_revision_number, question_fingerprint,
                        answer_fingerprint, evidence_request_id, presentation_fingerprint,
                        problem_fingerprint, problem_family_fingerprint, turn_reference_id,
                        turn_ordinal, turn_generation, mode_version, request_version,
                        attempt_ordinal, hint_count, answer_was_revealed, model_task_request_id,
                        model_response_schema_version, evaluator_request_version,
                        candidate_idempotency_key, revision_of_candidate_idempotency_key,
                        evidence_fingerprint, model_version, outcome,
                        occurred_at_epoch_millis, received_at_epoch_millis
                    ) VALUES(
                        '${fingerprint("receipt")}', '${fingerprint("canonical")}',
                        '${fingerprint("attempt")}', '${fingerprint("lineage")}',
                        0, 'candidate-v20', '${fingerprint("candidate")}',
                        'fact-v20', 'review-v20', '${fingerprint("review")}',
                        'local-learner', 'MATHEMATICS', '${fingerprint("scope")}',
                        'conversation-v20', 2, 3, 'question-v20', 1,
                        '${fingerprint("question")}', '${fingerprint("response-binding")}',
                        'request-v20', '${fingerprint("presentation")}',
                        '${fingerprint("problem")}', '${fingerprint("family")}',
                        'turn-v20', 4, 5, 6, 7, 1, 0, 0, 'model-task-v20', 1, 7,
                        '${fingerprint("idempotency")}', NULL, '${fingerprint("evidence")}',
                        'model-v20', 'INCORRECT', 100, 101
                    )
                    """.trimIndent(),
                )
            }

            helper.runMigrationsAndValidate(
                version = 21,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_20_21),
            ).use { connection ->
                assertEquals(
                    0L,
                    connection.longForOpenResponseMigrationQuery(
                        "SELECT proof_chain_version FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                listOf(
                    LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
                    LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
                    LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                ).forEach { table ->
                    assertEquals(
                        0L,
                        connection.longForOpenResponseMigrationQuery(
                            "SELECT COUNT(*) FROM `$table`",
                        ),
                    )
                    assertFalse(
                        connection.columnNamesForOpenResponseMigration(table).any { column ->
                            listOf(
                                "answer",
                                "prompt",
                                "body",
                                "display",
                                "label",
                                "weight",
                                "confidence",
                                "sql",
                            ).any(column.lowercase()::contains)
                        },
                    )
                }
                assertEquals(
                    0L,
                    connection.longForOpenResponseMigrationQuery(
                        "SELECT COUNT(*) FROM mastery_learning_event",
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForOpenResponseMigrationQuery(
                        "SELECT COUNT(*) FROM mastery_learning_event_attribution",
                    ),
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            "DELETE FROM " +
                                LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            "UPDATE " +
                                LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE +
                                " SET proof_chain_version = 1",
                        )
                    }.isFailure,
                )
                assertEquals(
                    0L,
                    connection.longForOpenResponseMigrationQuery(
                        "SELECT proof_chain_version FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { reopened ->
                assertEquals(21, reopened.version)
                assertEquals(
                    0L,
                    reopened.longForOpenResponseMigrationQuery(
                        "SELECT proof_chain_version FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    reopened.longForOpenResponseMigrationQuery(
                        "SELECT COUNT(*) FROM mastery_learning_event",
                    ),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun interruptedMigrationRollsBackAndReopenCanApplyTheRealMigration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "learner-mastery-v20-v21-interrupted.db"
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        val helper = MigrationTestHelper(
            instrumentation = instrumentation,
            file = databaseFile,
            driver = AndroidSQLiteDriver(),
            databaseClass = LearnerMasteryRoomDatabase::class,
        )
        try {
            helper.createDatabase(20).close()
            val interruptedMigration = object : Migration(20, 21) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    LEARNER_MASTERY_MIGRATION_20_21.migrate(connection)
                    error("injected process interruption after migration body")
                }
            }

            assertTrue(
                runCatching {
                    helper.runMigrationsAndValidate(
                        version = 21,
                        migrations = listOf(interruptedMigration),
                    ).close()
                }.isFailure,
            )
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { rolledBack ->
                assertEquals(20, rolledBack.version)
                assertEquals(
                    0L,
                    rolledBack.longForOpenResponseMigrationQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('" +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE +
                            "') WHERE name = 'proof_chain_version'",
                    ),
                )
                assertEquals(
                    0L,
                    rolledBack.longForOpenResponseMigrationQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" +
                            LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE + "'",
                    ),
                )
            }

            helper.runMigrationsAndValidate(
                version = 21,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_20_21),
            ).close()
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { reopened ->
                assertEquals(21, reopened.version)
                assertEquals(
                    1L,
                    reopened.longForOpenResponseMigrationQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('" +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE +
                            "') WHERE name = 'proof_chain_version'",
                    ),
                )
                listOf(
                    LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
                    LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
                    LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                ).forEach { table ->
                    assertEquals(
                        0L,
                        reopened.longForOpenResponseMigrationQuery(
                            "SELECT COUNT(*) FROM `$table`",
                        ),
                    )
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun fingerprint(seed: String): String =
        com.tingyun.smartmistakebook.core.model.CanonicalSha256(
            "learner-mastery-migration-20-21-test",
        ).field("seed", seed).finish()
}

private fun SQLiteConnection.longForOpenResponseMigrationQuery(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private fun SQLiteConnection.columnNamesForOpenResponseMigration(
    tableName: String,
): Set<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteDatabase.longForOpenResponseMigrationQuery(sql: String): Long =
    rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
