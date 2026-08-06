package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryMigration14To15InstrumentedTest {
    @Test
    fun migrationKeepsOldActiveReadableThenCutsOverFingerprintBudgetsExactlyOnce() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "mastery-v14-v15-presentation-epoch.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            lateinit var immutableBefore: Map<String, List<String>>
            try {
                helper.createDatabase(14).use { connection ->
                    insertDb14PresentationBudgetFixture(connection)
                    immutableBefore = connection.db15MigrationPreservationSnapshot()
                }
                helper.runMigrationsAndValidate(
                    version = 15,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_14_15),
                ).use { connection ->
                    assertEquals(
                        immutableBefore,
                        connection.db15MigrationPreservationSnapshot(),
                    )
                    assertEquals(
                        PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH,
                        connection.db15Text(
                            "SELECT metadata_value FROM mastery_store_metadata " +
                                "WHERE metadata_key = " +
                                "'$PRESENTATION_FINGERPRINT_BUDGET_REBUILD_REQUIRED_METADATA_KEY'",
                        ),
                    )
                    assertEquals(
                        0L,
                        connection.db15Long(
                            "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                                "'$PRESENTATION_FINGERPRINT_BUDGET_REBUILD_COMPLETED_METADATA_KEY'",
                        ),
                    )
                }

                val database =
                    LearnerMasteryStoreFactory.openDatabaseForTest(context, databaseName)
                val activatedGenerationId: Long
                try {
                    val dao = database.masteryDao()
                    var result = dao.prepareProjectionRebuild(nowEpochMillis = 2_000L)
                    assertFalse(result.completed)
                    assertTrue(result.activeGenerationAvailable)
                    assertTrue(result.generationId != STALE_DB14_BUILDING_GENERATION_ID)
                    assertOldActiveGenerationStillReadable(databaseName)

                    var chunkCount = 0
                    while (!result.completed && chunkCount < 12) {
                        result =
                            dao.rebuildDerivedStateChunk(
                                ownerId = "db15-presentation-epoch-owner",
                                nowEpochMillis = 2_001L + chunkCount,
                            )
                        chunkCount += 1
                        if (!result.completed) {
                            assertOldActiveGenerationStillReadable(databaseName)
                        }
                    }
                    assertTrue("DB15 presentation-budget rebuild did not finish", result.completed)
                    activatedGenerationId = checkNotNull(result.generationId)
                    assertTrue(chunkCount > 1)
                } finally {
                    database.close()
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        0L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_presentation_node_budget " +
                                "WHERE presentation_id = '$RAW_PRESENTATION_ID'",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_presentation_node_budget " +
                                "WHERE presentation_id = '$PRESENTATION_FINGERPRINT' " +
                                "AND consumed_mass_micros = 500000",
                        ),
                    )
                    assertEquals(
                        "1|600000|0|1",
                        sqlite.db15Text(
                            "SELECT observation_count || '|' || evidence_quality_micros || " +
                                "'|' || independent_problem_family_count || '|' || " +
                                "distinct_presentation_count FROM mastery_knowledge_projection",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_projection_generation " +
                                "WHERE generation_id = $STALE_DB14_BUILDING_GENERATION_ID " +
                                "AND state = 'RETIRED'",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                                "'$DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY' " +
                                "AND metadata_value = " +
                                "'$DIRECTIONAL_BUDGET_REBUILD_EPOCH'",
                        ),
                    )
                }

                val reopened =
                    LearnerMasteryStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    val resumed =
                        reopened.masteryDao().prepareProjectionRebuild(nowEpochMillis = 3_000L)
                    assertTrue(resumed.completed)
                    assertEquals(activatedGenerationId, resumed.generationId)
                } finally {
                    reopened.close()
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                                "'$DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY'",
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_projection_generation " +
                                "WHERE state = 'BUILDING'",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_presentation_node_budget " +
                                "WHERE presentation_id = '$PRESENTATION_FINGERPRINT'",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun freshDb15CompletesTheEpochWithoutStartingAnEmptyRebuildLoop() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "mastery-v15-fresh-presentation-epoch.mastery-test.db"
            context.deleteDatabase(databaseName)
            try {
                val database =
                    LearnerMasteryStoreFactory.openDatabaseForTest(context, databaseName)
                val firstGenerationId: Long
                try {
                    val first =
                        database.masteryDao().prepareProjectionRebuild(nowEpochMillis = 4_000L)
                    assertTrue(first.completed)
                    assertTrue(first.activeGenerationAvailable)
                    firstGenerationId = checkNotNull(first.generationId)
                    val second =
                        database.masteryDao().prepareProjectionRebuild(nowEpochMillis = 4_001L)
                    assertTrue(second.completed)
                    assertEquals(firstGenerationId, second.generationId)
                } finally {
                    database.close()
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                                "'$DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY'",
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_projection_generation " +
                                "WHERE state = 'BUILDING'",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun migrationRejectsPreseededCompletionAndGenerationEpochMetadata() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val reservedMetadata =
                listOf(
                    PRESENTATION_FINGERPRINT_BUDGET_REBUILD_COMPLETED_METADATA_KEY to
                        PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH,
                    presentationFingerprintBudgetGenerationEpochMetadataKey(7L) to
                        PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH,
                )

            reservedMetadata.forEachIndexed { index, (metadataKey, metadataValue) ->
                val databaseName = "mastery-v14-v15-reserved-metadata-$index.mastery-test.db"
                context.deleteDatabase(databaseName)
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = LearnerMasteryRoomDatabase::class,
                    )
                try {
                    helper.createDatabase(14).use { connection ->
                        connection.execSQL(
                            "INSERT INTO mastery_store_metadata(metadata_key, metadata_value) " +
                                "VALUES ('$metadataKey', '$metadataValue')",
                        )
                    }

                    val failure =
                        runCatching {
                            helper.runMigrationsAndValidate(
                                version = 15,
                                migrations = listOf(LEARNER_MASTERY_MIGRATION_14_15),
                            ).close()
                        }.exceptionOrNull()
                    assertTrue("Reserved DB15 metadata was accepted by DB14 migration", failure != null)
                    assertTrue(
                        "Migration failure did not identify reserved DB15 metadata",
                        generateSequence(failure) { it.cause }
                            .any { cause ->
                                cause.message?.contains(
                                    "reserved DB15 presentation-budget metadata",
                                ) == true
                            },
                    )
                } finally {
                    context.deleteDatabase(databaseName)
                }
            }
        }

    @Test
    fun migratedDb14WithoutActiveGenerationCannotCompleteTheEpochBeforeCrashRecoveryCutover() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "mastery-v14-v15-adoption-crash.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            try {
                helper.createDatabase(14).use { connection ->
                    insertDb14PresentationBudgetFixture(
                        connection = connection,
                        includeProjectionGenerations = false,
                    )
                    connection.execSQL(
                        "INSERT INTO mastery_store_metadata(metadata_key, metadata_value) " +
                            "VALUES ('$PROJECTION_REBUILD_COMPLETED_METADATA_KEY', " +
                            "'$LEARNER_MASTERY_PROJECTION_POLICY_VERSION')",
                    )
                }
                helper.runMigrationsAndValidate(
                    version = 15,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_14_15),
                ).close()

                val interruptedGenerationId: Long
                val interrupted =
                    LearnerMasteryStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    val database = interrupted
                    val prepared = database.masteryDao().prepareProjectionRebuild(5_000L)
                    assertFalse(prepared.completed)
                    assertFalse(prepared.activeGenerationAvailable)
                    assertNull(prepared.generationId)
                    val started =
                        database.masteryDao().rebuildDerivedStateChunk(
                            ownerId = "db15-adoption-crash-owner",
                            nowEpochMillis = 5_000L,
                        )
                    assertFalse(started.completed)
                    interruptedGenerationId = checkNotNull(started.generationId)
                } finally {
                    interrupted.close()
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        0L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                                "'$DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY'",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_projection_generation " +
                                "WHERE generation_id = $interruptedGenerationId " +
                                "AND state = 'BUILDING'",
                        ),
                    )
                }

                val reopened =
                    LearnerMasteryStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    var result = reopened.masteryDao().prepareProjectionRebuild(5_100L)
                    assertFalse(result.completed)
                    assertEquals(interruptedGenerationId, result.generationId)
                    var chunkCount = 0
                    while (!result.completed && chunkCount < 12) {
                        result =
                            reopened.masteryDao().rebuildDerivedStateChunk(
                                ownerId = "db15-adoption-crash-owner",
                                nowEpochMillis = 5_101L + chunkCount,
                            )
                        chunkCount += 1
                    }
                    assertTrue("Recovered DB15 rebuild did not finish", result.completed)
                    assertEquals(interruptedGenerationId, result.generationId)
                } finally {
                    reopened.close()
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                                "'$DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY'",
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_presentation_node_budget " +
                                "WHERE presentation_id = '$RAW_PRESENTATION_ID'",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.db15Long(
                            "SELECT COUNT(*) FROM mastery_presentation_node_budget " +
                                "WHERE presentation_id = '$PRESENTATION_FINGERPRINT'",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    private fun assertOldActiveGenerationStillReadable(databaseName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            assertEquals(
                1L,
                sqlite.db15Long(
                    "SELECT COUNT(*) FROM mastery_knowledge_projection " +
                        "WHERE learner_id = '$LEARNER_ID'",
                ),
            )
            assertEquals(
                1L,
                sqlite.db15Long(
                    "SELECT COUNT(*) FROM mastery_projection_generation " +
                        "WHERE generation_id = $DB14_ACTIVE_GENERATION_ID AND state = 'ACTIVE'",
                ),
            )
        }
    }

    private fun insertDb14PresentationBudgetFixture(
        connection: SQLiteConnection,
        includeProjectionGenerations: Boolean = true,
    ) {
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
                '$SOURCE_FACT_ID', '$LEARNER_ID', '$SUBJECT', 'TUTOR_CHOICE', 'problem-v14-1',
                '$RAW_PRESENTATION_ID', 'INCORRECT', 'INDEPENDENT', 'FIRST_ATTEMPT',
                'LOCAL_VERIFIED', '$SOURCE_PAYLOAD_FINGERPRINT', 1000, 1000, 1000,
                '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 'fixture-fact-v14-1',
                '$SOURCE_FACT_FINGERPRINT', '$PROBLEM_FAMILY_FINGERPRINT',
                '$PRESENTATION_FINGERPRINT', 'MULTIPLE_CHOICE', 0, 'DEVICE_OBSERVED',
                'EPHEMERAL_TUTOR_PROBLEM', '$EPHEMERAL_PROBLEM_FINGERPRINT',
                '$SUBMISSION_FINGERPRINT', 'fixture-model-v14'
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
                'fixture-model-v14', '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION', 1000, 1000,
                'fixture-candidate-v14-1', '$CANDIDATE_FINGERPRINT', 'TRUSTED_LOCAL'
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
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                '$LEARNER_MASTERY_ADMISSION_POLICY_VERSION',
                '$LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION', '$EVENT_FINGERPRINT',
                '$PROBLEM_FAMILY_FINGERPRINT', '$PRESENTATION_FINGERPRINT', 600000, 0, NULL, NULL
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
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                '$APPLICATION_FINGERPRINT', 1000
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "INSERT INTO mastery_ledger_sequence(learner_id, last_allocated_sequence) " +
                "VALUES ('$LEARNER_ID', $EVENT_SEQUENCE)",
        )
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
                $EVENT_SEQUENCE, '$EVENT_ID',
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                600000, 0, 1, NULL, NULL, NULL
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
                '$LEARNER_ID', '$RAW_PRESENTATION_ID', '$SUBJECT', '$KNOWLEDGE_NODE_ID',
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
        if (includeProjectionGenerations) {
            insertDb14Generation(
                connection = connection,
                generationId = DB14_ACTIVE_GENERATION_ID,
                state = "ACTIVE",
                stage = "COMPLETE",
                snapshotFingerprint = ACTIVE_SNAPSHOT_FINGERPRINT,
            )
            insertDb14Generation(
                connection = connection,
                generationId = STALE_DB14_BUILDING_GENERATION_ID,
                state = "BUILDING",
                stage = "PROJECTIONS",
                snapshotFingerprint = null,
            )
        }
    }

    private fun insertDb14Generation(
        connection: SQLiteConnection,
        generationId: Long,
        state: String,
        stage: String,
        snapshotFingerprint: String?,
    ) {
        val snapshot = snapshotFingerprint?.let { "'$it'" } ?: "NULL"
        val activatedAt = if (state == "ACTIVE") "1000" else "NULL"
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
                $generationId, '$state', '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                '$LEARNER_MASTERY_CALIBRATION_VERSION', 1, 0, '$stage', '', '', -1, -1,
                NULL, NULL, $snapshot, ${if (state == "ACTIVE") 1 else "NULL"},
                ${if (state == "ACTIVE") 1 else "NULL"}, 1000, $activatedAt,
                NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteConnection.db15MigrationPreservationSnapshot(): Map<String, List<String>> =
        linkedMapOf(
            "events" to
                db15Rows(
                    "SELECT event_id || '|' || canonical_fingerprint || '|' || " +
                        "presentation_fingerprint || '|' || event_sequence " +
                        "FROM mastery_learning_event ORDER BY event_id",
                ),
            "projections" to
                db15Rows(
                    "SELECT learner_id || '|' || knowledge_node_id || '|' || " +
                        "observation_count || '|' || evidence_quality_micros " +
                        "FROM mastery_knowledge_projection ORDER BY learner_id, knowledge_node_id",
                ),
            "presentation_budgets" to
                db15Rows(
                    "SELECT learner_id || '|' || presentation_id || '|' || " +
                        "knowledge_node_id || '|' || consumed_mass_micros " +
                        "FROM mastery_presentation_node_budget " +
                        "ORDER BY learner_id, presentation_id, knowledge_node_id",
                ),
            "generations" to
                db15Rows(
                    "SELECT generation_id || '|' || state || '|' || stage || '|' || " +
                        "COALESCE(snapshot_fingerprint, 'NULL') " +
                        "FROM mastery_projection_generation ORDER BY generation_id",
                ),
        )

    private companion object {
        const val LEARNER_ID = "learner-v14-presentation-epoch"
        const val SUBJECT = "MATH"
        const val KNOWLEDGE_NODE_ID = "math.algebra.linear"
        const val TAXONOMY_VERSION = "taxonomy-v1"
        const val KNOWLEDGE_PACK_VERSION = "pack-v1"
        const val SOURCE_FACT_ID = "fact-v14-presentation-epoch"
        const val CANDIDATE_ID = "candidate-v14-presentation-epoch"
        const val EVENT_ID = "event-v14-presentation-epoch"
        const val RAW_PRESENTATION_ID = "raw-presentation-id-v14"
        const val EVENT_SEQUENCE = 11L
        const val DB14_ACTIVE_GENERATION_ID = 7L
        const val STALE_DB14_BUILDING_GENERATION_ID = 8L
        val ACTIVE_SNAPSHOT_FINGERPRINT = "a".repeat(64)
        val SOURCE_FACT_FINGERPRINT = "1".repeat(64)
        val SOURCE_PROOF_FINGERPRINT = "2".repeat(64)
        val CANDIDATE_FINGERPRINT = "3".repeat(64)
        val EVENT_FINGERPRINT = "4".repeat(64)
        val KNOWLEDGE_NODE_REF_FINGERPRINT =
            KnowledgeNodeRef(
                subject = SubjectKind.valueOf(SUBJECT),
                knowledgeNodeId = KNOWLEDGE_NODE_ID,
                taxonomyVersion = TAXONOMY_VERSION,
                knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
            ).canonicalFingerprint
        val STABLE_NODE_FINGERPRINT = "6".repeat(64)
        val PROBLEM_FAMILY_FINGERPRINT = "7".repeat(64)
        val PRESENTATION_FINGERPRINT = "8".repeat(64)
        val SOURCE_PAYLOAD_FINGERPRINT = "9".repeat(64)
        val SUBMISSION_FINGERPRINT = "a".repeat(64)
        val EPHEMERAL_PROBLEM_FINGERPRINT = "b".repeat(64)
        val ATTRIBUTION_PROPOSAL_FINGERPRINT = "c".repeat(64)
        val APPLICATION_FINGERPRINT = "d".repeat(64)
    }
}

private fun SQLiteConnection.db15Long(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private fun SQLiteConnection.db15Text(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getText(0)
    }

private fun SQLiteConnection.db15Rows(sql: String): List<String> =
    prepare(sql).use { statement ->
        buildList {
            while (statement.step()) add(statement.getText(0))
        }
    }

private fun SQLiteDatabase.db15Long(sql: String): Long =
    rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun SQLiteDatabase.db15Text(sql: String): String =
    rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }
