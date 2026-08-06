package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryMigration19To20InstrumentedTest {
    @Test
    fun migrationPreservesFactsDiscardsDirectionlessCachesAndSchedulesBoundedReplay() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v19-v20-directional-budget.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )

            helper.createDatabase(19).use { connection ->
                connection.execSQL("PRAGMA foreign_keys = OFF")
                connection.execSQL(
                    """
                    INSERT INTO mastery_learning_event(
                        event_id, candidate_id, source_fact_id, source_proof_fingerprint,
                        learner_id, subject, direction, event_sequence,
                        occurred_at_epoch_millis, admitted_at_epoch_millis,
                        projection_policy_version, admission_policy_version,
                        calibration_version, canonical_fingerprint,
                        problem_family_fingerprint, presentation_fingerprint,
                        evidence_quality_micros, independently_answered
                    ) VALUES(
                        'event-v19', 'candidate-v19', 'fact-v19', '${"a".repeat(64)}',
                        'local-learner', 'MATHEMATICS', 'NEGATIVE', 1, 100, 101,
                        'legacy-projection', 'legacy-admission', 'legacy-calibration',
                        '${"b".repeat(64)}', 'family-v19', 'presentation-v19', 500000, 1
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_learning_event_attribution(
                        event_id, ordinal, subject, knowledge_node_id, taxonomy_version,
                        knowledge_pack_version, knowledge_node_ref_fingerprint,
                        evidence_mass_micros
                    ) VALUES(
                        'event-v19', 0, 'MATHEMATICS', 'node-v19', 'taxonomy-v19',
                        'pack-v19', '${"c".repeat(64)}', 300000
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_projection_generation(
                        generation_id, state, target_projection_policy_version,
                        target_calibration_version, source_event_count,
                        source_supersession_count, stage, cursor_learner_id,
                        cursor_subject, cursor_event_sequence, cursor_ordinal,
                        created_at_epoch_millis
                    ) VALUES(
                        7, 'BUILDING', 'legacy-projection', 'legacy-calibration',
                        1, 0, 'PROJECTIONS', '', '', -1, -1, 100
                    )
                    """.trimIndent(),
                )
                insertDirectionlessBudgets(connection)
            }

            helper.runMigrationsAndValidate(
                version = 20,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_19_20),
            ).use { connection ->
                assertEquals(1L, connection.longForQuery("SELECT COUNT(*) FROM mastery_learning_event"))
                assertEquals(
                    1L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_learning_event_attribution",
                    ),
                )
                assertEquals(
                    "NEGATIVE",
                    connection.textForQuery(
                        "SELECT direction FROM mastery_learning_event WHERE event_id = 'event-v19'",
                    ),
                )
                listOf(
                    "mastery_presentation_node_budget",
                    "mastery_problem_family_node_budget",
                    "mastery_presentation_node_budget_shadow",
                    "mastery_problem_family_node_budget_shadow",
                ).forEach { table ->
                    assertEquals(0L, connection.longForQuery("SELECT COUNT(*) FROM $table"))
                    assertTrue("direction" in connection.columnNames(table))
                }
                assertEquals(
                    "RETIRED",
                    connection.textForQuery(
                        "SELECT state FROM mastery_projection_generation WHERE generation_id = 7",
                    ),
                )
                assertEquals(
                    DIRECTIONAL_BUDGET_REBUILD_EPOCH,
                    connection.textForQuery(
                        "SELECT metadata_value FROM mastery_store_metadata " +
                            "WHERE metadata_key = '$DIRECTIONAL_BUDGET_REBUILD_REQUIRED_METADATA_KEY'",
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM $LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE",
                    ),
                )
                assertMigrationReceipt(connection)
                assertIndependentDirectionalIdentity(connection)
                assertTrue(
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM sqlite_schema WHERE type = 'index' " +
                            "AND name = 'index_mastery_learning_event_directional_budget_replay'",
                    ) == 1L,
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    private fun insertDirectionlessBudgets(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO mastery_presentation_node_budget VALUES(
                'local-learner', 'presentation-v19', 'MATHEMATICS', 'node-v19',
                'taxonomy-v19', '${"d".repeat(64)}', 300000, 'event-v19', 101
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_problem_family_node_budget VALUES(
                'local-learner', 'family-v19', 'MATHEMATICS', 'node-v19',
                'taxonomy-v19', '${"d".repeat(64)}', 1, 300000, 'event-v19', 101
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_presentation_node_budget_shadow VALUES(
                7, 'local-learner', 'presentation-v19', 'MATHEMATICS', 'node-v19',
                'taxonomy-v19', '${"d".repeat(64)}', 300000, 'event-v19', 101
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_problem_family_node_budget_shadow VALUES(
                7, 'local-learner', 'family-v19', 'MATHEMATICS', 'node-v19',
                'taxonomy-v19', '${"d".repeat(64)}', 1, 300000, 'event-v19', 101
            )
            """.trimIndent(),
        )
    }

    private fun assertMigrationReceipt(connection: SQLiteConnection) {
        assertEquals(
            1L,
            connection.longForQuery(
                "SELECT COUNT(*) FROM $LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE",
            ),
        )
        assertEquals(
            listOf(1L, 1L, 0L, 1L, 1L, 1L, 1L, 1L),
            connection.longRowForQuery(
                "SELECT immutable_event_count, immutable_attribution_count, " +
                    "supersession_count, discarded_active_presentation_count, " +
                    "discarded_active_problem_family_count, " +
                    "discarded_shadow_presentation_count, " +
                    "discarded_shadow_problem_family_count, " +
                    "retired_building_generation_count FROM " +
                    LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE,
                columnCount = 8,
            ),
        )
        assertTrue(
            runCatching {
                connection.execSQL(
                    "DELETE FROM $LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE",
                )
            }.isFailure,
        )
    }

    private fun assertIndependentDirectionalIdentity(connection: SQLiteConnection) {
        listOf("POSITIVE", "NEGATIVE").forEachIndexed { index, direction ->
            connection.execSQL(
                """
                INSERT INTO mastery_presentation_node_budget(
                    learner_id, presentation_id, subject, knowledge_node_id,
                    taxonomy_version, direction, stable_node_identity_fingerprint,
                    consumed_mass_micros, last_event_id, updated_at_epoch_millis
                ) VALUES(
                    'local-learner', 'presentation-v19', 'MATHEMATICS', 'node-v19',
                    'taxonomy-v19', '$direction', '${"e".repeat(64)}',
                    ${100000 + index}, 'event-$direction', 200
                )
                """.trimIndent(),
            )
        }
        assertEquals(
            2L,
            connection.longForQuery(
                "SELECT COUNT(*) FROM mastery_presentation_node_budget " +
                    "WHERE presentation_id = 'presentation-v19'",
            ),
        )
    }
}

private fun SQLiteConnection.longForQuery(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private fun SQLiteConnection.textForQuery(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getText(0)
    }

private fun SQLiteConnection.longRowForQuery(
    sql: String,
    columnCount: Int,
): List<Long> =
    prepare(sql).use { statement ->
        check(statement.step())
        List(columnCount, statement::getLong)
    }

private fun SQLiteConnection.columnNames(tableName: String): Set<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }
