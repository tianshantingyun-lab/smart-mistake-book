package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
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
class LearnerMasteryMigration13To14InstrumentedTest {
    @Test
    fun migrationPreservesRealLedgerDerivedStateAndPreviousGenerationCanonically() =
        runBlocking {
            lateinit var expectedLegacyRows: Map<String, List<String>>
            val migrated =
                withMigratedDatabase("mastery-v13-v14-preservation.mastery-test.db") {
                    connection ->
                    insertLegacyPreservationFixture(connection)
                    expectedLegacyRows = connection.readLegacyCanonicalRows()
                }
            migrated.verify { connection ->
                assertEquals(expectedLegacyRows, connection.readLegacyCanonicalRows())
                assertTrue(connection.textRowsForDb14Test("PRAGMA foreign_key_check").isEmpty())
                assertEquals(
                    ACTIVE_SNAPSHOT_FINGERPRINT,
                    connection.textForDb14Test(
                        "SELECT snapshot_fingerprint FROM mastery_projection_generation " +
                            "WHERE generation_id = 7",
                    ),
                )
                assertEquals(
                    LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                    connection.textForDb14Test(
                        "SELECT target_projection_policy_version " +
                            "FROM mastery_projection_generation WHERE generation_id = 7",
                    ),
                )
                assertEquals(
                    LEARNER_MASTERY_CALIBRATION_VERSION,
                    connection.textForDb14Test(
                        "SELECT target_calibration_version " +
                            "FROM mastery_projection_generation WHERE generation_id = 7",
                    ),
                )
                assertEquals(
                    1L,
                    connection.longForDb14Test(
                        "SELECT COUNT(*) FROM mastery_projection_generation " +
                            "WHERE generation_id = 7 AND calibration_release_id IS NULL",
                    ),
                )
                NEW_DB14_TABLES.forEach { table ->
                    assertEquals(
                        "Migration guessed rows for $table",
                        0L,
                        connection.longForDb14Test("SELECT COUNT(*) FROM `$table`"),
                    )
                }
            }
        }

    @Test
    fun migrationScaffoldingCannotActivateOrAcceptExecutableMetadata() =
        runBlocking {
            val migrated =
                withMigratedDatabase("mastery-v13-v14-guards.mastery-test.db") {
                    connection ->
                    insertLegacyPreservationFixture(connection)
                }
            migrated.verify { connection ->
                assertSqlFailure {
                    insertCalibrationRelease(connection, state = "ACTIVE")
                }
                insertCalibrationRelease(connection, state = "CANDIDATE")
                assertSqlFailure {
                    connection.execSQL(
                        "UPDATE $LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE " +
                            "SET state = 'REJECTED' WHERE release_id = '$RELEASE_ID'",
                    )
                }
                listOf(
                    "payload;DROP_TABLE",
                    "https://example.invalid/profile",
                    "C:\\calibration\\profile",
                    "weight*0.5",
                    "SELECT value",
                    "<script>",
                ).forEach { unsafeMetadata ->
                    assertSqlFailure {
                        insertProfileHeader(
                            connection = connection,
                            parameterSchemaVersion = unsafeMetadata,
                        )
                    }
                }
                assertSqlFailure {
                    connection.execSQL(
                        "UPDATE mastery_projection_generation " +
                            "SET calibration_release_id = '$RELEASE_ID' " +
                            "WHERE generation_id = 7",
                    )
                }
                assertEquals(
                    1L,
                    connection.longForDb14Test(
                        "SELECT COUNT(*) FROM mastery_projection_generation " +
                            "WHERE generation_id = 7 AND calibration_release_id IS NULL",
                    ),
                )
                assertSqlFailure {
                    insertFutureActiveGeneration(connection)
                }
                assertEquals(
                    1L,
                    connection.longForDb14Test(
                        "SELECT COUNT(*) FROM mastery_projection_generation " +
                            "WHERE state = 'ACTIVE' AND generation_id = 7",
                    ),
                )
            }
        }

    @Test
    fun projectionInputRejectsEveryDriftedParentIdentityAndUsesEventThenBehaviorReplayOrder() =
        runBlocking {
            val databaseName = "mastery-v13-v14-input-integrity.mastery-test.db"
            val migrated =
                withMigratedDatabase(databaseName) {
                    connection -> insertLegacyPreservationFixture(connection)
                }
            migrated.verify { connection ->
                assertSqlFailure {
                    insertProjectionInputFact(connection, learnerId = "another-learner")
                }
                assertSqlFailure {
                    insertProjectionInputFact(
                        connection,
                        eventCanonicalFingerprint = "0".repeat(64),
                    )
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, subject = "PHYSICS")
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, knowledgeNodeId = "math.other-node")
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, taxonomyVersion = "taxonomy-v2")
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, eventSequence = EVENT_SEQUENCE + 1L)
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, sourceProofFingerprint = "f".repeat(64))
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, independentlyAnswered = true)
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, outcome = "CORRECT")
                }
                assertSqlFailure {
                    insertProjectionInputFact(connection, attributionFingerprint = "0".repeat(64))
                }

                insertProjectionInputFact(connection)
                assertEquals(
                    1L,
                    connection.longForDb14Test(
                        "SELECT COUNT(*) FROM $LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE",
                    ),
                )

                val queryPlan =
                    queryPlanRowsForDb14Test(
                        databaseName = databaseName,
                        sql =
                            """
                        EXPLAIN QUERY PLAN
                        SELECT event_id, attribution_ordinal
                        FROM $LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE
                        WHERE learner_id = '$LEARNER_ID'
                          AND subject = '$SUBJECT'
                          AND knowledge_node_id = '$KNOWLEDGE_NODE_ID'
                          AND taxonomy_version = '$TAXONOMY_VERSION'
                          AND event_sequence >= 0
                        ORDER BY event_sequence, behavior_order, event_id, attribution_ordinal
                        LIMIT 32
                            """.trimIndent(),
                        columnIndex = 3,
                    )
                assertTrue(
                    "Replay query did not use $LEARNER_MASTERY_PROJECTION_INPUT_REPLAY_INDEX: " +
                        queryPlan.joinToString(),
                    queryPlan.any { detail ->
                        detail.contains(LEARNER_MASTERY_PROJECTION_INPUT_REPLAY_INDEX)
                    },
                )
                assertTrue(
                    "Replay query used a temporary B-tree for ordering: " +
                        queryPlan.joinToString(),
                    queryPlan.none { detail ->
                        detail.uppercase().contains("USE TEMP B-TREE FOR ORDER BY")
                    },
                )
            }
        }

    @Test
    fun projectionInputCoverageGapRejectsEveryDriftedParentIdentity() =
        runBlocking {
            val migrated =
                withMigratedDatabase("mastery-v13-v14-coverage-integrity.mastery-test.db") {
                    connection -> insertLegacyPreservationFixture(connection)
                }
            migrated.verify { connection ->
                assertSqlFailure {
                    insertProjectionInputCoverageGap(connection, learnerId = "another-learner")
                }
                assertSqlFailure {
                    insertProjectionInputCoverageGap(connection, subject = "PHYSICS")
                }
                assertSqlFailure {
                    insertProjectionInputCoverageGap(
                        connection,
                        eventSequence = EVENT_SEQUENCE + 1L,
                    )
                }

                insertProjectionInputCoverageGap(connection)
                assertEquals(
                    1L,
                    connection.longForDb14Test(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
                    ),
                )
            }
        }

    @Test
    fun everyDb14FoundationTableRejectsUpdateAndDeleteAfterValidInsert() =
        runBlocking {
            val migrated =
                withMigratedDatabase("mastery-v13-v14-append-only.mastery-test.db") {
                    connection -> insertLegacyPreservationFixture(connection)
                }
            migrated.verify { connection ->
                insertProjectionInputFact(connection)
                insertProjectionInputCoverageGap(connection)
                insertCalibrationRelease(connection, state = "CANDIDATE")
                insertProfileHeader(connection, parameterSchemaVersion = "parameters-v1")
                insertValidationMetric(connection)

                NEW_DB14_TABLES.forEach { table ->
                    assertEquals(1L, connection.longForDb14Test("SELECT COUNT(*) FROM `$table`"))
                    assertSqlFailure {
                        connection.execSQL("UPDATE `$table` SET rowid = rowid")
                    }
                    assertSqlFailure {
                        connection.execSQL("DELETE FROM `$table`")
                    }
                    assertEquals(1L, connection.longForDb14Test("SELECT COUNT(*) FROM `$table`"))
                }
            }
        }

    private suspend fun withMigratedDatabase(
        databaseName: String,
        beforeMigration: (SQLiteConnection) -> Unit,
    ): MigratedDatabaseVerification {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = LearnerMasteryRoomDatabase::class,
            )
        helper.createDatabase(13).use(beforeMigration)
        return MigratedDatabaseVerification(
            connection =
                helper.runMigrationsAndValidate(
                    version = 14,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_13_14),
                ),
            close = { context.deleteDatabase(databaseName) },
        )
    }

    private class MigratedDatabaseVerification(
        private val connection: SQLiteConnection,
        private val close: () -> Unit,
    ) {
        infix fun verify(block: (SQLiteConnection) -> Unit) {
            try {
                connection.use(block)
            } finally {
                close()
            }
        }
    }

    private fun insertLegacyPreservationFixture(connection: SQLiteConnection) {
        insertLegacyLearningLedger(connection)
        insertLegacyDerivedState(connection)
        insertLegacyProjectionGenerations(connection)
    }

    private fun insertLegacyLearningLedger(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO mastery_source_fact (
                source_fact_id, learner_id, subject, source_kind, source_reference_id,
                presentation_id, outcome, assistance, retry_state, authority,
                source_payload_fingerprint, occurred_at_epoch_millis,
                attested_at_epoch_millis, received_at_epoch_millis, source_policy_version,
                idempotency_key, canonical_fingerprint, problem_family_fingerprint,
                presentation_fingerprint, response_form, independently_answered,
                verification_kind, evidence_context_kind, ephemeral_problem_fingerprint,
                submission_evidence_fingerprint, attribution_model_version
            ) VALUES (
                '$SOURCE_FACT_ID', '$LEARNER_ID', '$SUBJECT', 'TUTOR_CHOICE', 'problem-v13-1',
                '$PRESENTATION_ID', 'INCORRECT', 'INDEPENDENT', 'FIRST_ATTEMPT', 'LOCAL_VERIFIED',
                '$SOURCE_PAYLOAD_FINGERPRINT', 1000, 1000, 1000,
                '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 'fixture-fact-v13-1',
                '$SOURCE_FACT_FINGERPRINT', '$PROBLEM_FAMILY_FINGERPRINT',
                '$PRESENTATION_FINGERPRINT', 'MULTIPLE_CHOICE', 0, 'DEVICE_OBSERVED',
                'EPHEMERAL_TUTOR_PROBLEM', '$EPHEMERAL_PROBLEM_FINGERPRINT',
                '$SUBMISSION_FINGERPRINT', 'fixture-model-v13'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_source_proof (
                source_fact_id, source_fact_canonical_fingerprint, source_policy_version,
                policy_supported, proof_fingerprint, created_at_epoch_millis
            ) VALUES (
                '$SOURCE_FACT_ID', '$SOURCE_FACT_FINGERPRINT',
                '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 1, '$SOURCE_PROOF_FINGERPRINT', 1000
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_observation_candidate (
                candidate_id, learner_id, subject, source_fact_id, confidence, model_version,
                requested_policy_version, proposed_at_epoch_millis, received_at_epoch_millis,
                idempotency_key, canonical_fingerprint, candidate_origin
            ) VALUES (
                '$CANDIDATE_ID', '$LEARNER_ID', '$SUBJECT', '$SOURCE_FACT_ID', 'HIGH',
                'fixture-model-v13', '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION', 1000, 1000,
                'fixture-candidate-v13-1', '$CANDIDATE_FINGERPRINT', 'TRUSTED_LOCAL'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_candidate_attribution (
                candidate_id, ordinal, subject, knowledge_node_id, taxonomy_version,
                knowledge_pack_version, knowledge_node_ref_fingerprint, role, certainty,
                proposal_fingerprint
            ) VALUES (
                '$CANDIDATE_ID', 0, '$SUBJECT', '$KNOWLEDGE_NODE_ID', '$TAXONOMY_VERSION',
                '$KNOWLEDGE_PACK_VERSION', '$KNOWLEDGE_NODE_REF_FINGERPRINT', 'PRIMARY', 'DIRECT',
                '$ATTRIBUTION_PROPOSAL_FINGERPRINT'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_learning_event (
                event_id, candidate_id, source_fact_id, source_proof_fingerprint, learner_id,
                subject, direction, event_sequence, occurred_at_epoch_millis,
                admitted_at_epoch_millis, projection_policy_version, admission_policy_version,
                calibration_version, canonical_fingerprint, problem_family_fingerprint,
                presentation_fingerprint, evidence_quality_micros, independently_answered,
                calibration_snapshot_fingerprint, calibration_profile_id
            ) VALUES (
                '$EVENT_ID', '$CANDIDATE_ID', '$SOURCE_FACT_ID', '$SOURCE_PROOF_FINGERPRINT',
                '$LEARNER_ID', '$SUBJECT', 'NEGATIVE', $EVENT_SEQUENCE, 1000, 1000,
                '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                '$LEARNER_MASTERY_ADMISSION_POLICY_VERSION', '$LEARNER_MASTERY_CALIBRATION_VERSION',
                '$EVENT_FINGERPRINT', '$PROBLEM_FAMILY_FINGERPRINT', '$PRESENTATION_FINGERPRINT',
                600000, 0, NULL, NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_learning_event_attribution (
                event_id, ordinal, subject, knowledge_node_id, taxonomy_version,
                knowledge_pack_version, knowledge_node_ref_fingerprint, evidence_mass_micros
            ) VALUES (
                '$EVENT_ID', 0, '$SUBJECT', '$KNOWLEDGE_NODE_ID', '$TAXONOMY_VERSION',
                '$KNOWLEDGE_PACK_VERSION', '$KNOWLEDGE_NODE_REF_FINGERPRINT', 500000
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_applied_event (
                event_id, event_canonical_fingerprint, learner_id, event_sequence,
                projection_policy_version, application_fingerprint, applied_at_epoch_millis
            ) VALUES (
                '$EVENT_ID', '$EVENT_FINGERPRINT', '$LEARNER_ID', $EVENT_SEQUENCE,
                '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION', '$APPLICATION_FINGERPRINT', 1000
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "INSERT INTO mastery_ledger_sequence(learner_id, last_allocated_sequence) " +
                "VALUES ('$LEARNER_ID', $EVENT_SEQUENCE)",
        )
    }

    private fun insertLegacyDerivedState(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO mastery_knowledge_projection (
                learner_id, subject, knowledge_node_id, taxonomy_version,
                latest_evidence_knowledge_pack_version, stable_node_identity_fingerprint,
                positive_evidence_micros, negative_evidence_micros, mastery_score_micros,
                mastery_state, trend, observation_count, memory_stability_millis,
                recall_due_at_epoch_millis, last_positive_at_epoch_millis,
                last_negative_at_epoch_millis, last_evidence_at_epoch_millis,
                last_event_sequence, last_ordered_event_id, projection_policy_version,
                evidence_quality_micros, independent_problem_family_count,
                distinct_presentation_count, calibration_snapshot_fingerprint,
                calibration_profile_id, calibration_version
            ) VALUES (
                '$LEARNER_ID', '$SUBJECT', '$KNOWLEDGE_NODE_ID', '$TAXONOMY_VERSION',
                '$KNOWLEDGE_PACK_VERSION', '$STABLE_NODE_FINGERPRINT', 0, 500000, 250000,
                'FAMILIARIZING', 'STABLE', 1, 5000, 6000, NULL, 1000, 1000,
                $EVENT_SEQUENCE, '$EVENT_ID', '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                600000, 1, 1, NULL, NULL, NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_subject_digest (
                learner_id, subject, needs_reinforcement_count, familiarizing_count,
                steady_count, last_event_sequence, updated_at_epoch_millis,
                projection_policy_version
            ) VALUES (
                '$LEARNER_ID', '$SUBJECT', 0, 1, 0, $EVENT_SEQUENCE, 1000,
                '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_presentation_node_budget (
                learner_id, presentation_id, subject, knowledge_node_id, taxonomy_version,
                stable_node_identity_fingerprint, consumed_mass_micros, last_event_id,
                updated_at_epoch_millis
            ) VALUES (
                '$LEARNER_ID', '$PRESENTATION_ID', '$SUBJECT', '$KNOWLEDGE_NODE_ID',
                '$TAXONOMY_VERSION', '$STABLE_NODE_FINGERPRINT', 500000, '$EVENT_ID', 1000
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_problem_family_node_budget (
                learner_id, problem_family_fingerprint, subject, knowledge_node_id,
                taxonomy_version, stable_node_identity_fingerprint, observation_count,
                consumed_mass_micros, last_event_id, updated_at_epoch_millis
            ) VALUES (
                '$LEARNER_ID', '$PROBLEM_FAMILY_FINGERPRINT', '$SUBJECT', '$KNOWLEDGE_NODE_ID',
                '$TAXONOMY_VERSION', '$STABLE_NODE_FINGERPRINT', 1, 500000, '$EVENT_ID', 1000
            )
            """.trimIndent(),
        )
    }

    private fun insertLegacyProjectionGenerations(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO mastery_projection_generation(
                generation_id, state, target_projection_policy_version,
                target_calibration_version, source_event_count,
                source_supersession_count, stage, cursor_learner_id,
                cursor_subject, cursor_event_sequence, cursor_ordinal,
                lease_owner_id, lease_expires_at_epoch_millis, snapshot_fingerprint,
                projection_row_count, subject_digest_row_count,
                created_at_epoch_millis, activated_at_epoch_millis
            ) VALUES (
                6, 'RETIRED', 'previous-active-projection', 'previous-active-calibration',
                1, 0, 'COMPLETE', '', '', -1, -1, NULL, NULL,
                '$PREVIOUS_SNAPSHOT_FINGERPRINT', 1, 1, 900, 900
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
                lease_owner_id, lease_expires_at_epoch_millis, snapshot_fingerprint,
                projection_row_count, subject_digest_row_count,
                created_at_epoch_millis, activated_at_epoch_millis
            ) VALUES (
                7, 'ACTIVE', '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                '$LEARNER_MASTERY_CALIBRATION_VERSION', 1, 0, 'COMPLETE', '', '', -1, -1,
                NULL, NULL, '$ACTIVE_SNAPSHOT_FINGERPRINT', 1, 1, 1000, 1000
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteConnection.readLegacyCanonicalRows(): Map<String, List<String>> =
        linkedMapOf(
            "mastery_learning_event" to
                textRowsForDb14Test(
                    "SELECT event_id || '|' || candidate_id || '|' || source_fact_id || '|' || " +
                        "learner_id || '|' || subject || '|' || " +
                        "direction || '|' || event_sequence || '|' || occurred_at_epoch_millis || " +
                        "'|' || admitted_at_epoch_millis || '|' || source_proof_fingerprint || " +
                        "'|' || projection_policy_version || '|' || admission_policy_version || " +
                        "'|' || calibration_version || '|' || canonical_fingerprint || '|' || " +
                        "COALESCE(problem_family_fingerprint, 'NULL') || '|' || " +
                        "presentation_fingerprint || '|' || evidence_quality_micros || '|' || " +
                        "independently_answered FROM mastery_learning_event ORDER BY event_id",
                ),
            "mastery_learning_event_attribution" to
                textRowsForDb14Test(
                    "SELECT event_id || '|' || ordinal || '|' || subject || '|' || " +
                        "knowledge_node_id || '|' || taxonomy_version || '|' || " +
                        "knowledge_pack_version || '|' || knowledge_node_ref_fingerprint || '|' || " +
                        "evidence_mass_micros FROM mastery_learning_event_attribution " +
                        "ORDER BY event_id, ordinal",
                ),
            "mastery_knowledge_projection" to
                textRowsForDb14Test(
                    "SELECT learner_id || '|' || subject || '|' || knowledge_node_id || '|' || " +
                        "taxonomy_version || '|' || stable_node_identity_fingerprint || '|' || " +
                        "positive_evidence_micros || '|' || negative_evidence_micros || '|' || " +
                        "mastery_score_micros || '|' || mastery_state || '|' || trend || '|' || " +
                        "observation_count || '|' || memory_stability_millis || '|' || " +
                        "recall_due_at_epoch_millis || '|' || " +
                        "COALESCE(last_positive_at_epoch_millis, 'NULL') || '|' || " +
                        "COALESCE(last_negative_at_epoch_millis, 'NULL') || '|' || " +
                        "last_evidence_at_epoch_millis || '|' || last_event_sequence || '|' || " +
                        "last_ordered_event_id || '|' || projection_policy_version || '|' || " +
                        "evidence_quality_micros || '|' || independent_problem_family_count || " +
                        "'|' || distinct_presentation_count FROM mastery_knowledge_projection " +
                        "ORDER BY learner_id, subject, knowledge_node_id, taxonomy_version",
                ),
            "mastery_subject_digest" to
                textRowsForDb14Test(
                    "SELECT learner_id || '|' || subject || '|' || " +
                        "needs_reinforcement_count || '|' || familiarizing_count || '|' || " +
                        "steady_count || '|' || last_event_sequence || '|' || " +
                        "updated_at_epoch_millis || '|' || projection_policy_version " +
                        "FROM mastery_subject_digest " +
                        "ORDER BY learner_id, subject",
                ),
            "mastery_presentation_node_budget" to
                textRowsForDb14Test(
                    "SELECT learner_id || '|' || presentation_id || '|' || subject || '|' || " +
                        "knowledge_node_id || '|' || taxonomy_version || '|' || " +
                        "stable_node_identity_fingerprint || '|' || consumed_mass_micros || '|' || " +
                        "last_event_id || '|' || updated_at_epoch_millis " +
                        "FROM mastery_presentation_node_budget " +
                        "ORDER BY learner_id, presentation_id, subject, knowledge_node_id",
                ),
            "mastery_problem_family_node_budget" to
                textRowsForDb14Test(
                    "SELECT learner_id || '|' || problem_family_fingerprint || '|' || subject || " +
                        "'|' || knowledge_node_id || '|' || taxonomy_version || '|' || " +
                        "stable_node_identity_fingerprint || '|' || observation_count || '|' || " +
                        "consumed_mass_micros || '|' || last_event_id || '|' || " +
                        "updated_at_epoch_millis " +
                        "FROM mastery_problem_family_node_budget " +
                        "ORDER BY learner_id, problem_family_fingerprint, subject, knowledge_node_id",
                ),
            "mastery_projection_generation" to
                textRowsForDb14Test(
                    "SELECT generation_id || '|' || state || '|' || " +
                        "target_projection_policy_version || '|' || target_calibration_version || " +
                        "'|' || source_event_count || '|' || source_supersession_count || '|' || " +
                        "stage || '|' || cursor_learner_id || '|' || cursor_subject || '|' || " +
                        "cursor_event_sequence || '|' || cursor_ordinal || '|' || " +
                        "snapshot_fingerprint || '|' || projection_row_count || '|' || " +
                        "subject_digest_row_count || '|' || created_at_epoch_millis || '|' || " +
                        "activated_at_epoch_millis " +
                        "FROM mastery_projection_generation ORDER BY generation_id",
                ),
        )

    private fun insertCalibrationRelease(
        connection: SQLiteConnection,
        state: String,
    ) {
        connection.execSQL(
            """
            INSERT INTO $LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE(
                release_id, state, release_schema_version, projection_policy_version,
                calibration_version, source_input_set_fingerprint,
                projection_implementation_fingerprint, profile_manifest_fingerprint,
                validation_manifest_fingerprint, release_fingerprint,
                created_at_epoch_millis
            ) VALUES (
                '$RELEASE_ID', '$state', 'release-v1', 'learner-mastery-projection-v5',
                'learner-mastery-calibration-v5', '${"b".repeat(64)}',
                '${"c".repeat(64)}', '${"d".repeat(64)}', '${"e".repeat(64)}',
                '${"f".repeat(64)}', 2000
            )
            """.trimIndent(),
        )
    }

    private fun insertProfileHeader(
        connection: SQLiteConnection,
        parameterSchemaVersion: String,
    ) {
        connection.execSQL(
            """
            INSERT INTO $LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE(
                release_id, profile_id, subject, taxonomy_version, population_scope,
                parameter_schema_version, parameter_set_fingerprint,
                training_source_fingerprint, evaluation_plan_fingerprint, header_fingerprint
            ) VALUES (
                '$RELEASE_ID', 'math-profile-v1', 'MATH', 'taxonomy-v1', 'all-learners',
                '$parameterSchemaVersion', '${"1".repeat(64)}', '${"2".repeat(64)}',
                '${"3".repeat(64)}', '${"4".repeat(64)}'
            )
            """.trimIndent(),
        )
    }

    private fun insertValidationMetric(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO $LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE(
                release_id, profile_id, metric_id, split_id, sample_count,
                metric_value_micros, cohort_fingerprint, metric_fingerprint,
                evaluated_at_epoch_millis
            ) VALUES (
                '$RELEASE_ID', 'math-profile-v1', 'brier', 'time-forward', 100,
                125000, '${"5".repeat(64)}', '${"6".repeat(64)}', 2000
            )
            """.trimIndent(),
        )
    }

    private fun insertProjectionInputFact(
        connection: SQLiteConnection,
        learnerId: String = LEARNER_ID,
        subject: String = SUBJECT,
        knowledgeNodeId: String = KNOWLEDGE_NODE_ID,
        taxonomyVersion: String = TAXONOMY_VERSION,
        eventCanonicalFingerprint: String = EVENT_FINGERPRINT,
        eventSequence: Long = EVENT_SEQUENCE,
        sourceProofFingerprint: String = SOURCE_PROOF_FINGERPRINT,
        independentlyAnswered: Boolean = false,
        outcome: String = "INCORRECT",
        attributionFingerprint: String = ATTRIBUTION_PROPOSAL_FINGERPRINT,
    ) {
        connection.execSQL(
            """
            INSERT INTO $LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE(
                event_id, attribution_ordinal, event_canonical_fingerprint, learner_id,
                subject, knowledge_node_id, taxonomy_version, event_sequence, behavior_order,
                eligibility_basis, projection_eligible, outcome, assistance, retry_state,
                attempt_ordinal, hint_count, answer_was_revealed, independently_answered,
                source_proof_fingerprint, attribution_fingerprint, canonical_fingerprint,
                captured_at_epoch_millis
            ) VALUES (
                '$EVENT_ID', 0, '$eventCanonicalFingerprint', '$learnerId', '$subject',
                '$knowledgeNodeId', '$taxonomyVersion', $eventSequence, 0,
                'LOCAL_VERIFIED', 1, '$outcome', 'INDEPENDENT', 'FIRST_ATTEMPT',
                1, 0, 0, ${if (independentlyAnswered) 1 else 0}, '$sourceProofFingerprint',
                '$attributionFingerprint', '$PROJECTION_INPUT_FINGERPRINT', 2000
            )
            """.trimIndent(),
        )
    }

    private fun insertProjectionInputCoverageGap(
        connection: SQLiteConnection,
        learnerId: String = LEARNER_ID,
        subject: String = SUBJECT,
        eventSequence: Long = EVENT_SEQUENCE,
    ) {
        connection.execSQL(
            """
            INSERT INTO $LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE(
                gap_fingerprint, event_id, event_canonical_fingerprint, learner_id, subject,
                event_sequence, missing_component, detector_version, detected_at_epoch_millis
            ) VALUES (
                '$COVERAGE_GAP_FINGERPRINT', '$EVENT_ID', '$EVENT_FINGERPRINT', '$learnerId',
                '$subject', $eventSequence, 'BEHAVIOR_ORDER_HISTORY', 'detector-v1', 2000
            )
            """.trimIndent(),
        )
    }

    private fun insertFutureActiveGeneration(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO mastery_projection_generation(
                generation_id, state, target_projection_policy_version,
                target_calibration_version, source_event_count,
                source_supersession_count, stage, cursor_learner_id,
                cursor_subject, cursor_event_sequence, cursor_ordinal,
                lease_owner_id, lease_expires_at_epoch_millis, snapshot_fingerprint,
                projection_row_count, subject_digest_row_count,
                created_at_epoch_millis, activated_at_epoch_millis,
                calibration_release_id, calibration_release_fingerprint,
                projection_input_set_fingerprint, projection_implementation_fingerprint,
                generation_manifest_fingerprint
            ) VALUES (
                8, 'ACTIVE', 'learner-mastery-projection-v5',
                'learner-mastery-calibration-v5', 0, 0, 'COMPLETE', '', '', -1, -1,
                NULL, NULL, '${"5".repeat(64)}', 0, 0, 2000, 2000,
                '$RELEASE_ID', '${"f".repeat(64)}', '${"b".repeat(64)}',
                '${"c".repeat(64)}', '${"6".repeat(64)}'
            )
            """.trimIndent(),
        )
    }

    private fun assertSqlFailure(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private companion object {
        const val RELEASE_ID = "calibration-release-v5-candidate"
        const val LEARNER_ID = "learner-v13-1"
        const val SUBJECT = "MATH"
        const val KNOWLEDGE_NODE_ID = "math.algebra.linear"
        const val TAXONOMY_VERSION = "taxonomy-v1"
        const val KNOWLEDGE_PACK_VERSION = "pack-v1"
        const val SOURCE_FACT_ID = "fact-v13-1"
        const val CANDIDATE_ID = "candidate-v13-1"
        const val EVENT_ID = "event-v13-1"
        const val PRESENTATION_ID = "presentation-v13-1"
        const val EVENT_SEQUENCE = 11L
        val ACTIVE_SNAPSHOT_FINGERPRINT = "a".repeat(64)
        val PREVIOUS_SNAPSHOT_FINGERPRINT = "0".repeat(64)
        val SOURCE_FACT_FINGERPRINT = "1".repeat(64)
        val SOURCE_PROOF_FINGERPRINT = "2".repeat(64)
        val CANDIDATE_FINGERPRINT = "3".repeat(64)
        val EVENT_FINGERPRINT = "4".repeat(64)
        val KNOWLEDGE_NODE_REF_FINGERPRINT = "5".repeat(64)
        val STABLE_NODE_FINGERPRINT = "6".repeat(64)
        val PROBLEM_FAMILY_FINGERPRINT = "7".repeat(64)
        val PRESENTATION_FINGERPRINT = "8".repeat(64)
        val SOURCE_PAYLOAD_FINGERPRINT = "9".repeat(64)
        val SUBMISSION_FINGERPRINT = "a".repeat(64)
        val EPHEMERAL_PROBLEM_FINGERPRINT = "b".repeat(64)
        val ATTRIBUTION_PROPOSAL_FINGERPRINT = "c".repeat(64)
        val APPLICATION_FINGERPRINT = "d".repeat(64)
        val PROJECTION_INPUT_FINGERPRINT = "e".repeat(64)
        val COVERAGE_GAP_FINGERPRINT = "f".repeat(64)
        val NEW_DB14_TABLES =
            listOf(
                LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE,
                LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
                LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE,
                LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE,
                LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE,
            )
    }
}

private fun SQLiteConnection.longForDb14Test(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private fun SQLiteConnection.textForDb14Test(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getText(0)
    }

private fun SQLiteConnection.textRowsForDb14Test(
    sql: String,
    columnIndex: Int = 0,
): List<String> =
    prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add(statement.getText(columnIndex))
            }
        }
    }

private fun queryPlanRowsForDb14Test(
    databaseName: String,
    sql: String,
    columnIndex: Int,
): List<String> {
    val databasePath =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getDatabasePath(databaseName)
            .absolutePath
    return SQLiteDatabase
        .openDatabase(databasePath, null, SQLiteDatabase.OPEN_READONLY)
        .use { database ->
            database.rawQuery(sql, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(columnIndex))
                    }
                }
            }
        }
}
