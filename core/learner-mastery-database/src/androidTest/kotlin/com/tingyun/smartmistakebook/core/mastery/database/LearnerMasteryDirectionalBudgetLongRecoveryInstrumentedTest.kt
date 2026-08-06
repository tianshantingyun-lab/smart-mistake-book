package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryDirectionalBudgetLongRecoveryInstrumentedTest {
    @Test
    fun v19LongDirectionalRebuildResumesAfterCrashWithoutReapplyingFacts() = runBlocking {
        assertEquals(
            "The cutover attestation reader is intentionally restricted to the real local learner",
            LOCAL_LEARNER_ID,
            LEARNER_ID,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "learner-mastery-v19-v20-long-recovery.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = LearnerMasteryRoomDatabase::class,
            )

        try {
            helper.createDatabase(19).use { connection ->
                insertV19LongInterruptedFixture(connection)
            }
            helper.runMigrationsAndValidate(
                version = 20,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_19_20),
            ).close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertImmutableFacts(sqlite)
                assertEquals(0, sqlite.queryRowCount("PRAGMA foreign_key_check"))
                assertEquals(
                    "Migration must retire the interrupted v19 generation",
                    "RETIRED",
                    sqlite.queryText(
                        "SELECT state FROM mastery_projection_generation " +
                            "WHERE generation_id = $V19_BUILDING_GENERATION_ID",
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM mastery_projection_shadow " +
                            "WHERE generation_id = $V19_BUILDING_GENERATION_ID",
                    ),
                )
                assertEquals(
                    listOf(1L, 1L, 1L, 1L, 1L),
                    sqlite.queryLongRow(
                        "SELECT discarded_active_presentation_count, " +
                            "discarded_active_problem_family_count, " +
                            "discarded_shadow_presentation_count, " +
                            "discarded_shadow_problem_family_count, " +
                            "retired_building_generation_count FROM " +
                            LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE,
                        columnCount = 5,
                    ),
                )
                assertEquals(
                    DIRECTIONAL_BUDGET_REBUILD_EPOCH,
                    sqlite.queryText(
                        "SELECT metadata_value FROM mastery_store_metadata WHERE " +
                            "metadata_key = '$DIRECTIONAL_BUDGET_REBUILD_REQUIRED_METADATA_KEY'",
                    ),
                )
            }

            val firstDatabase = openDatabase(context, databaseName)
            val buildingGenerationId: Long
            try {
                val dao = firstDatabase.masteryDao()
                val reset =
                    dao.rebuildDerivedStateChunk(
                        ownerId = "initial-rebuild-owner",
                        nowEpochMillis = REBUILD_CLOCK,
                    )
                assertEquals(MasteryProjectionRebuildStage.RESET, reset.stage)
                val firstPage =
                    dao.rebuildDerivedStateChunk(
                        ownerId = "initial-rebuild-owner",
                        nowEpochMillis = REBUILD_CLOCK + 1L,
                    )
                assertEquals(MasteryProjectionRebuildStage.PROJECTIONS, firstPage.stage)
                assertEquals(
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
                    firstPage.processedRowCount,
                )
                assertFalse(firstPage.completed)
                buildingGenerationId = checkNotNull(firstPage.generationId)
                assertTrue(buildingGenerationId > V19_BUILDING_GENERATION_ID)
            } finally {
                firstDatabase.close()
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                val oldCheckpointFingerprint =
                    extendDirectionalBudgetInputFingerprint(
                        previousFingerprint = directionalBudgetInputSeedFingerprint(),
                        rows =
                            (OLD_CHECKPOINT_FIRST_EVENT_INDEX..
                                OLD_CHECKPOINT_LAST_EVENT_INDEX).map(::v19PrimaryReplayRow),
                    )
                assertEquals(OLD_CHECKPOINT_INPUT_FINGERPRINT_GOLDEN, oldCheckpointFingerprint)
                listOf(
                    "mastery_presentation_node_budget_shadow",
                    "mastery_problem_family_node_budget_shadow",
                ).forEach { table ->
                    sqlite.execSQL(
                        "DELETE FROM $table WHERE generation_id = $buildingGenerationId " +
                            "AND last_event_id > '$OLD_CHECKPOINT_EVENT_ID'",
                    )
                    assertEquals(
                        OLD_PROJECTION_PAGE_SIZE.toLong(),
                        sqlite.queryLong(
                            "SELECT COUNT(*) FROM $table WHERE generation_id = " +
                                buildingGenerationId,
                        ),
                    )
                }
                sqlite.execSQL(
                    """
                    UPDATE mastery_projection_generation
                    SET cursor_learner_id = '$LEARNER_ID',
                        cursor_subject = '',
                        cursor_event_sequence = ${OLD_CHECKPOINT_LAST_EVENT_INDEX + 1L},
                        cursor_ordinal = 0,
                        cursor_occurred_at_epoch_millis =
                            ${EVENT_CLOCK + OLD_CHECKPOINT_LAST_EVENT_INDEX},
                        cursor_event_id = '$OLD_CHECKPOINT_EVENT_ID',
                        cursor_direction = 'NEGATIVE',
                        budget_input_snapshot_fingerprint = '$oldCheckpointFingerprint',
                        budget_input_row_count = $OLD_PROJECTION_PAGE_SIZE
                    WHERE generation_id = $buildingGenerationId AND state = 'BUILDING'
                    """.trimIndent(),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM mastery_projection_shadow " +
                            "WHERE generation_id = $buildingGenerationId",
                    ),
                )
                assertEquals(
                    OLD_PROJECTION_PAGE_SIZE.toLong(),
                    sqlite.queryLong(
                        "SELECT budget_input_row_count FROM mastery_projection_generation " +
                            "WHERE generation_id = $buildingGenerationId",
                    ),
                )
                assertEquals(
                    OLD_CHECKPOINT_EVENT_ID,
                    sqlite.queryText(
                        "SELECT cursor_event_id FROM mastery_projection_generation " +
                            "WHERE generation_id = $buildingGenerationId",
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT cursor_ordinal FROM mastery_projection_generation " +
                            "WHERE generation_id = $buildingGenerationId",
                    ),
                )
                assertEquals(
                    DIRECTIONAL_BUDGET_REBUILD_EPOCH,
                    sqlite.queryText(
                        "SELECT metadata_value FROM mastery_store_metadata WHERE metadata_key = " +
                            "'$DIRECTIONAL_BUDGET_GENERATION_EPOCH_METADATA_PREFIX" +
                            "$buildingGenerationId'",
                    ),
                )
                sqlite.execSQL(
                    "UPDATE mastery_projection_generation SET " +
                        "lease_owner_id = 'crashed-rebuild-owner', " +
                        "lease_expires_at_epoch_millis = ? WHERE generation_id = ? " +
                        "AND state = 'BUILDING'",
                    arrayOf(CRASHED_LEASE_EXPIRY, buildingGenerationId),
                )
            }

            val resumedDatabase = openDatabase(context, databaseName)
            try {
                val dao = resumedDatabase.masteryDao()
                val busy =
                    dao.rebuildDerivedStateChunk(
                        ownerId = "takeover-rebuild-owner",
                        nowEpochMillis = CRASHED_LEASE_EXPIRY - 1L,
                    )
                assertTrue("Unexpired crashed lease must remain exclusive", busy.leaseBusy)
                assertEquals(buildingGenerationId, busy.generationId)

                var clock = CRASHED_LEASE_EXPIRY + 1L
                var result =
                    dao.rebuildDerivedStateChunk(
                        ownerId = "takeover-rebuild-owner",
                        nowEpochMillis = clock,
                    )
                assertFalse(result.leaseBusy)
                assertEquals(MasteryProjectionRebuildStage.PROJECTIONS, result.stage)
                assertEquals(
                    ACTIVE_ATTRIBUTION_ROW_COUNT - OLD_PROJECTION_PAGE_SIZE,
                    result.processedRowCount,
                )
                assertEquals(buildingGenerationId, result.generationId)

                var projectionRowsProcessed =
                    OLD_PROJECTION_PAGE_SIZE + result.processedRowCount
                var boundedTransactions = 2
                while (!result.completed) {
                    clock += 1L
                    result =
                        dao.rebuildDerivedStateChunk(
                            ownerId = "takeover-rebuild-owner",
                            nowEpochMillis = clock,
                        )
                    assertTrue(
                        "Every recovery transaction must stay within the keyset page budget",
                        result.processedRowCount <= LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
                    )
                    if (result.stage == MasteryProjectionRebuildStage.PROJECTIONS) {
                        projectionRowsProcessed += result.processedRowCount
                    }
                    boundedTransactions += 1
                    assertTrue("Recovery did not converge", boundedTransactions < 32)
                }
                assertEquals(ACTIVE_ATTRIBUTION_ROW_COUNT, projectionRowsProcessed)
                assertEquals(buildingGenerationId, result.generationId)
            } finally {
                resumedDatabase.close()
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertImmutableFacts(sqlite)
                assertEquals(0, sqlite.queryRowCount("PRAGMA foreign_key_check"))
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM mastery_projection_generation " +
                            "WHERE state = 'BUILDING'",
                    ),
                )
                assertEquals(
                    1L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM mastery_projection_generation " +
                            "WHERE state = 'ACTIVE' AND generation_id = $buildingGenerationId " +
                            "AND target_projection_policy_version = " +
                            "'$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'",
                    ),
                )
                assertEquals(
                    "RETIRED",
                    sqlite.queryText(
                        "SELECT state FROM mastery_projection_generation " +
                            "WHERE generation_id = $V19_ACTIVE_GENERATION_ID",
                    ),
                )
                assertEquals(
                    "RETIRED",
                    sqlite.queryText(
                        "SELECT state FROM mastery_projection_generation " +
                            "WHERE generation_id = $V19_BUILDING_GENERATION_ID",
                    ),
                )
                assertEquals(
                    "Old v1 epoch bindings may remain as audit history but must not keep a " +
                        "generation live",
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM mastery_projection_generation generation " +
                            "INNER JOIN mastery_store_metadata metadata ON metadata.metadata_key = " +
                            "'$PRESENTATION_FINGERPRINT_BUDGET_GENERATION_EPOCH_METADATA_PREFIX' " +
                            "|| generation.generation_id WHERE generation.state IN " +
                            "('ACTIVE', 'BUILDING')",
                    ),
                )
                assertEquals(
                    1L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM mastery_store_metadata WHERE metadata_key = " +
                            "'$DIRECTIONAL_BUDGET_GENERATION_EPOCH_METADATA_PREFIX" +
                            "$buildingGenerationId' AND metadata_value = " +
                            "'$DIRECTIONAL_BUDGET_REBUILD_EPOCH'",
                    ),
                )
                assertEquals(
                    EXPECTED_DERIVED_ROW_COUNT.toLong(),
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_knowledge_projection"),
                )
                assertDirectionalBudgetsAppliedExactlyOnce(sqlite, buildingGenerationId)
            }

            var firstUsedPageCount = 0L
            var sqlitePageSize = 0L
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
                sqlitePageSize = sqlite.queryLong("PRAGMA page_size")
                firstUsedPageCount =
                    sqlite.queryLong("PRAGMA page_count") -
                        sqlite.queryLong("PRAGMA freelist_count")
                sqlite.execSQL(
                    """
                    UPDATE mastery_projection_generation
                    SET target_projection_policy_version = 'force-second-rebuild',
                        target_calibration_version = 'force-second-rebuild'
                    WHERE generation_id = $buildingGenerationId AND state = 'ACTIVE'
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    INSERT OR REPLACE INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                    """.trimIndent(),
                )
            }

            val secondDatabase = openDatabase(context, databaseName)
            var secondGenerationId = -1L
            try {
                val dao = secondDatabase.masteryDao()
                var result =
                    dao.rebuildDerivedStateChunk(
                        ownerId = "second-rebuild-owner",
                        nowEpochMillis = REBUILD_CLOCK + 20_000L,
                    )
                var secondRebuildTransactionCount = 1
                while (!result.completed) {
                    result =
                        dao.rebuildDerivedStateChunk(
                            ownerId = "second-rebuild-owner",
                            nowEpochMillis = REBUILD_CLOCK + 20_000L + secondRebuildTransactionCount,
                        )
                    secondRebuildTransactionCount += 1
                    assertTrue(
                        "Second rebuild did not converge",
                        secondRebuildTransactionCount < 32,
                    )
                }
                secondGenerationId = checkNotNull(result.generationId)
                assertTrue(secondGenerationId > buildingGenerationId)
            } finally {
                secondDatabase.close()
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
                val secondPageCount = sqlite.queryLong("PRAGMA page_count")
                val secondFreelistCount = sqlite.queryLong("PRAGMA freelist_count")
                val secondUsedPageCount = secondPageCount - secondFreelistCount
                assertTrue(
                    "Two rebuilds retained logical shadow pages: firstUsed=$firstUsedPageCount " +
                        "secondUsed=$secondUsedPageCount pageSize=$sqlitePageSize " +
                        "allocated=$secondPageCount freelist=$secondFreelistCount",
                    secondUsedPageCount <= firstUsedPageCount + SECOND_REBUILD_PAGE_HEADROOM,
                )
                assertEquals(
                    0L,
                    sqlite.queryLong(retiredShadowRowCountSql()),
                )
                listOf(
                    "mastery_projection_shadow" to "mastery_knowledge_projection",
                    "mastery_subject_digest_shadow" to "mastery_subject_digest",
                    "mastery_presentation_node_budget_shadow" to
                        "mastery_presentation_node_budget",
                    "mastery_problem_family_node_budget_shadow" to
                        "mastery_problem_family_node_budget",
                ).forEach { (shadowTable, activeTable) ->
                    assertEquals(
                        "$shadowTable must retain only the active attestation witness",
                        sqlite.queryLong("SELECT COUNT(*) FROM $activeTable"),
                        sqlite.queryLong(
                            "SELECT COUNT(*) FROM $shadowTable WHERE generation_id = " +
                                secondGenerationId,
                        ),
                    )
                }
                assertEquals(
                    2L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    1L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM mastery_projection_generation " +
                            "WHERE generation_id = $buildingGenerationId AND state = 'RETIRED'",
                    ),
                )
                assertDirectionalBudgetsAppliedExactlyOnce(sqlite, secondGenerationId)
            }

            val intactWitness = readActiveShadowWitness(context, databaseName)
            assertEquals(secondGenerationId, intactWitness.generationId)
            assertTrue(
                "ACTIVE shadow witness was inconsistent before tampering: $intactWitness",
                intactWitness.isConsistent,
            )
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                val tamperStatement =
                    sqlite.compileStatement(
                        "UPDATE mastery_projection_shadow SET mastery_score_micros = " +
                            "mastery_score_micros + 1 " +
                            "WHERE generation_id = $secondGenerationId " +
                            "AND knowledge_node_id = 'math.long-node.cross-page'",
                    )
                try {
                    assertEquals(
                        "The tamper probe must change exactly one retained ACTIVE shadow row",
                        1,
                        tamperStatement.executeUpdateDelete(),
                    )
                } finally {
                    tamperStatement.close()
                }
            }
            val tamperedWitness = readActiveShadowWitness(context, databaseName)
            assertFalse(
                "The retained ACTIVE shadow must expose projection tampering",
                tamperedWitness.isConsistent,
            )
            assertEquals(1L, tamperedWitness.health.projectionShadowMismatchCount)
            assertTrue(tamperedWitness.receiptMatchesActive)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun insertV19LongInterruptedFixture(connection: SQLiteConnection) {
        connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
        try {
            repeat(EVENT_COUNT) { index ->
                insertV19Event(connection, index)
            }
            insertV19Supersession(connection)
            insertV19ActiveDerivedState(connection)
            insertV19InterruptedGeneration(connection)
            insertV19RebuildMarkers(connection)
            connection.execSQL("COMMIT")
        } catch (failure: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw failure
        }
    }

    private fun insertV19Event(connection: SQLiteConnection, index: Int) {
        val suffix = index.toString().padStart(3, '0')
        val eventId = "event-long-$suffix"
        val sharesCrossPageBudget = index == 0 || index == 2
        val budgetSuffix = if (sharesCrossPageBudget) "cross-page" else suffix
        val occurredAtEpochMillis =
            if (index == 0) {
                EVENT_CLOCK + EVENT_COUNT - 1L
            } else {
                EVENT_CLOCK + index
            }
        val direction =
            if (index % 2 == 0 || index == MULTI_ATTRIBUTION_EVENT_INDEX) {
                "POSITIVE"
            } else {
                "NEGATIVE"
            }
        val sourceProofFingerprint = fingerprint(10_000 + index)
        val sourceCanonicalFingerprint = fingerprint(30_000 + index)
        connection.execSQL(
            """
            INSERT INTO mastery_source_fact(
                source_fact_id, learner_id, subject, source_kind,
                source_reference_id, presentation_id, outcome, assistance,
                retry_state, authority, source_payload_fingerprint,
                occurred_at_epoch_millis, attested_at_epoch_millis,
                received_at_epoch_millis, source_policy_version, idempotency_key,
                canonical_fingerprint, problem_family_fingerprint,
                presentation_fingerprint, response_form, independently_answered,
                hint_count, answer_revealed, verification_kind, evidence_context_kind
            ) VALUES(
                'fact-long-$suffix', '$LEARNER_ID', 'MATHEMATICS',
                'SAVED_MISTAKE_IMPORT', 'source-long-$suffix',
                'presentation-long-$budgetSuffix',
                '${if (direction == "POSITIVE") "CORRECT" else "INCORRECT"}',
                'NONE', 'FIRST_ATTEMPT', 'LOCAL_VERIFIED',
                '${fingerprint(50_000 + index)}', $occurredAtEpochMillis,
                ${EVENT_CLOCK + EVENT_COUNT + index},
                ${EVENT_CLOCK + EVENT_COUNT + index},
                '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 'source-idempotency-$suffix',
                '$sourceCanonicalFingerprint', 'family-long-$budgetSuffix',
                'presentation-long-$budgetSuffix', 'CHOICE', 1, 0, 0,
                'LOCALLY_VERIFIED', 'SAVED_MISTAKE'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_source_proof(
                source_fact_id, source_fact_canonical_fingerprint,
                source_policy_version, policy_supported, proof_fingerprint,
                created_at_epoch_millis
            ) VALUES(
                'fact-long-$suffix', '$sourceCanonicalFingerprint',
                '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 1,
                '$sourceProofFingerprint', ${EVENT_CLOCK + EVENT_COUNT + index}
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_observation_candidate(
                candidate_id, learner_id, subject, source_fact_id, confidence,
                model_version, requested_policy_version, proposed_at_epoch_millis,
                received_at_epoch_millis, idempotency_key, canonical_fingerprint,
                candidate_origin
            ) VALUES(
                'candidate-long-$suffix', '$LEARNER_ID', 'MATHEMATICS',
                'fact-long-$suffix', 'HIGH', 'fixture-v19',
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                ${EVENT_CLOCK + EVENT_COUNT + index},
                ${EVENT_CLOCK + EVENT_COUNT + index},
                'candidate-idempotency-$suffix', '${fingerprint(40_000 + index)}',
                'TRUSTED_LOCAL'
            )
            """.trimIndent(),
        )
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
                '$eventId', 'candidate-long-$suffix', 'fact-long-$suffix',
                '$sourceProofFingerprint', '$LEARNER_ID', 'MATHEMATICS',
                '$direction', ${index + 1}, $occurredAtEpochMillis,
                ${EVENT_CLOCK + EVENT_COUNT + index},
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                'legacy-admission-v19', '$LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION',
                '${fingerprint(index + 1)}', 'family-long-$budgetSuffix',
                'presentation-long-$budgetSuffix', 500000, 1
            )
            """.trimIndent(),
        )
        val attributionCount = if (index == MULTI_ATTRIBUTION_EVENT_INDEX) 3 else 1
        repeat(attributionCount) { ordinal ->
            val evidenceMass =
                when {
                    attributionCount == 1 -> 200_000
                    ordinal == 0 -> 100_000
                    else -> 50_000
                }
            val nodeSuffix =
                if (ordinal == 0 && sharesCrossPageBudget) {
                    "cross-page"
                } else if (ordinal == 0) {
                    suffix
                } else {
                    "$suffix-extra-$ordinal"
                }
            val nodeReferenceFingerprint =
                if (ordinal == 0 && sharesCrossPageBudget) {
                    fingerprint(20_002)
                } else {
                    fingerprint(20_000 + index * 4 + ordinal)
                }
            connection.execSQL(
                """
                INSERT INTO mastery_learning_event_attribution(
                    event_id, ordinal, subject, knowledge_node_id, taxonomy_version,
                    knowledge_pack_version, knowledge_node_ref_fingerprint,
                    evidence_mass_micros
                ) VALUES(
                    '$eventId', $ordinal, 'MATHEMATICS', 'math.long-node.$nodeSuffix',
                    'taxonomy-v19', 'pack-v19',
                    '$nodeReferenceFingerprint', $evidenceMass
                )
                """.trimIndent(),
            )
        }
    }

    private fun insertV19Supersession(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO mastery_learning_evidence_supersession(
                supersession_id, learner_id, subject, original_source_fact_id,
                original_source_fact_canonical_fingerprint, original_event_id,
                original_event_canonical_fingerprint, replacement_source_fact_id,
                replacement_source_fact_canonical_fingerprint, replacement_candidate_id,
                replacement_candidate_canonical_fingerprint, replacement_event_id,
                authority, authority_version, correction_evidence_fingerprint,
                idempotency_key, canonical_fingerprint, superseded_at_epoch_millis
            ) VALUES(
                'supersession-long-001', '$LEARNER_ID', 'MATHEMATICS', 'fact-long-001',
                '${fingerprint(30_001)}', 'event-long-001', '${fingerprint(2)}',
                'fact-long-002', '${fingerprint(30_002)}', 'candidate-long-002',
                '${fingerprint(40_002)}', 'event-long-002', 'LOCAL_CORRECTION',
                'fixture-v19', '${fingerprint(60_001)}', 'supersession-idempotency-001',
                '${fingerprint(60_002)}', ${EVENT_CLOCK + EVENT_COUNT * 3L}
            )
            """.trimIndent(),
        )
    }

    private fun insertV19ActiveDerivedState(connection: SQLiteConnection) {
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
            ) VALUES(
                $V19_ACTIVE_GENERATION_ID, 'ACTIVE',
                '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                '$LEARNER_MASTERY_CALIBRATION_VERSION', $EVENT_COUNT, 1, 'COMPLETE',
                '', '', -1, -1, NULL, NULL, '${"a".repeat(64)}', 1, 1,
                ${EVENT_CLOCK - 2L}, ${EVENT_CLOCK - 1L}
            )
            """.trimIndent(),
        )
        connection.execSQL(legacyProjectionInsertSql("mastery_knowledge_projection"))
        connection.execSQL(
            """
            INSERT INTO mastery_subject_digest(
                learner_id, subject, needs_reinforcement_count, familiarizing_count,
                steady_count, last_event_sequence, updated_at_epoch_millis,
                projection_policy_version
            ) VALUES(
                '$LEARNER_ID', 'MATHEMATICS', 0, 1, 0, 1, $EVENT_CLOCK,
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_presentation_node_budget VALUES(
                '$LEARNER_ID', 'presentation-long-000', 'MATHEMATICS',
                'math.long-node.000', 'taxonomy-v19', '$LEGACY_NODE_FINGERPRINT',
                200000, 'event-long-000', $EVENT_CLOCK
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_problem_family_node_budget VALUES(
                '$LEARNER_ID', 'family-long-000', 'MATHEMATICS',
                'math.long-node.000', 'taxonomy-v19', '$LEGACY_NODE_FINGERPRINT', 1,
                200000, 'event-long-000', $EVENT_CLOCK
            )
            """.trimIndent(),
        )
    }

    private fun insertV19InterruptedGeneration(connection: SQLiteConnection) {
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
            ) VALUES(
                $V19_BUILDING_GENERATION_ID, 'BUILDING',
                '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                '$LEARNER_MASTERY_CALIBRATION_VERSION', $EVENT_COUNT, 1, 'PROJECTIONS',
                '$LEARNER_ID', 'MATHEMATICS', 128, 0, 'v19-crashed-owner',
                ${EVENT_CLOCK - 1L}, NULL, NULL, NULL, $EVENT_CLOCK, NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            legacyProjectionInsertSql(
                tableName = "mastery_projection_shadow",
                generationId = V19_BUILDING_GENERATION_ID,
            ),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_subject_digest_shadow(
                generation_id, learner_id, subject, needs_reinforcement_count,
                familiarizing_count, steady_count, last_event_sequence,
                updated_at_epoch_millis, projection_policy_version
            ) VALUES(
                $V19_BUILDING_GENERATION_ID, '$LEARNER_ID', 'MATHEMATICS',
                0, 1, 0, 1, $EVENT_CLOCK,
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_presentation_node_budget_shadow VALUES(
                $V19_BUILDING_GENERATION_ID, '$LEARNER_ID', 'presentation-long-000',
                'MATHEMATICS', 'math.long-node.000', 'taxonomy-v19',
                '$LEGACY_NODE_FINGERPRINT', 200000, 'event-long-000', $EVENT_CLOCK
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_problem_family_node_budget_shadow VALUES(
                $V19_BUILDING_GENERATION_ID, '$LEARNER_ID', 'family-long-000',
                'MATHEMATICS', 'math.long-node.000', 'taxonomy-v19',
                '$LEGACY_NODE_FINGERPRINT', 1, 200000, 'event-long-000', $EVENT_CLOCK
            )
            """.trimIndent(),
        )
    }

    private fun insertV19RebuildMarkers(connection: SQLiteConnection) {
        val markers =
            listOf(
                "projection_rebuild_policy" to "REQUIRED_V2",
                "projection_rebuild_completed_v2" to
                    LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                "projection_rebuild_progress_v2:00000000000000000007" to
                    legacyProgressCheckpoint(),
                PRESENTATION_FINGERPRINT_BUDGET_REBUILD_REQUIRED_METADATA_KEY to
                    PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH,
                PRESENTATION_FINGERPRINT_BUDGET_REBUILD_COMPLETED_METADATA_KEY to
                    PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH,
                presentationFingerprintBudgetGenerationEpochMetadataKey(
                    V19_ACTIVE_GENERATION_ID,
                ) to PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH,
                presentationFingerprintBudgetGenerationEpochMetadataKey(
                    V19_BUILDING_GENERATION_ID,
                ) to PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH,
            )
        markers.forEach { (key, value) ->
            connection.execSQL(
                "INSERT INTO mastery_store_metadata(metadata_key, metadata_value) " +
                    "VALUES('$key', '$value')",
            )
        }
    }

    private fun legacyProjectionInsertSql(
        tableName: String,
        generationId: Long? = null,
    ): String {
        val generationColumn = generationId?.let { "generation_id, " }.orEmpty()
        val generationValue = generationId?.let { "$it, " }.orEmpty()
        return """
            INSERT INTO $tableName(
                ${generationColumn}learner_id, subject, knowledge_node_id,
                taxonomy_version, latest_evidence_knowledge_pack_version,
                stable_node_identity_fingerprint, positive_evidence_micros,
                negative_evidence_micros, mastery_score_micros, mastery_state, trend,
                observation_count, memory_stability_millis, recall_due_at_epoch_millis,
                last_positive_at_epoch_millis, last_negative_at_epoch_millis,
                last_evidence_at_epoch_millis, last_event_sequence,
                last_ordered_event_id, projection_policy_version,
                evidence_quality_micros, independent_problem_family_count,
                distinct_presentation_count
            ) VALUES(
                ${generationValue}'$LEARNER_ID', 'MATHEMATICS', 'math.long-node.000',
                'taxonomy-v19', 'pack-v19', '$LEGACY_NODE_FINGERPRINT', 200000, 0,
                545455, 'FAMILIARIZING', 'IMPROVING', 1, 86400000,
                ${EVENT_CLOCK + 86400000L}, $EVENT_CLOCK, NULL, $EVENT_CLOCK, 1,
                'event-long-000', '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                500000, 1, 1
            )
        """.trimIndent()
    }

    private fun assertImmutableFacts(sqlite: SQLiteDatabase) {
        listOf(
            "mastery_source_fact",
            "mastery_source_proof",
            "mastery_observation_candidate",
            "mastery_learning_event",
        ).forEach { table ->
            assertEquals(
                "$table immutable row count",
                EVENT_COUNT.toLong(),
                sqlite.queryLong("SELECT COUNT(*) FROM $table"),
            )
        }
        assertEquals(
            EVENT_COUNT.toLong(),
            sqlite.queryLong(
                "SELECT COUNT(DISTINCT canonical_fingerprint) " +
                    "FROM mastery_learning_event",
            ),
        )
        assertEquals(
            (EVENT_COUNT.toLong() * (EVENT_COUNT + 1L)) / 2L,
            sqlite.queryLong("SELECT SUM(event_sequence) FROM mastery_learning_event"),
        )
        assertEquals(
            ATTRIBUTION_ROW_COUNT.toLong(),
            sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event_attribution"),
        )
        assertEquals(
            1L,
            sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_evidence_supersession"),
        )
        assertEquals(
            RAW_EVIDENCE_MASS_MICROS,
            sqlite.queryLong(
                "SELECT SUM(evidence_mass_micros) " +
                    "FROM mastery_learning_event_attribution",
            ),
        )
        assertEquals(
            3L,
            sqlite.queryLong(
                "SELECT COUNT(*) FROM mastery_learning_event_attribution " +
                    "WHERE event_id = '$MULTI_ATTRIBUTION_EVENT_ID'",
            ),
        )
        assertEquals(
            "POSITIVE",
            sqlite.queryText(
                "SELECT direction FROM mastery_learning_event " +
                    "WHERE event_id = '$MULTI_ATTRIBUTION_EVENT_ID'",
            ),
        )
        val positiveEventCount =
            (0 until EVENT_COUNT).count { index ->
                index % 2 == 0 || index == MULTI_ATTRIBUTION_EVENT_INDEX
            }.toLong()
        assertEquals(
            listOf(positiveEventCount, EVENT_COUNT.toLong() - positiveEventCount),
            sqlite.queryLongRow(
                "SELECT " +
                    "SUM(CASE WHEN direction = 'POSITIVE' THEN 1 ELSE 0 END), " +
                    "SUM(CASE WHEN direction = 'NEGATIVE' THEN 1 ELSE 0 END) " +
                    "FROM mastery_learning_event",
                columnCount = 2,
            ),
        )
    }

    private fun assertDirectionalBudgetsAppliedExactlyOnce(
        sqlite: SQLiteDatabase,
        activeGenerationId: Long,
    ) {
        listOf(
            "mastery_presentation_node_budget",
            "mastery_problem_family_node_budget",
        ).forEach { table ->
            assertEquals(
                "$table row count",
                EXPECTED_DERIVED_ROW_COUNT.toLong(),
                sqlite.queryLong("SELECT COUNT(*) FROM $table"),
            )
            assertEquals(
                "$table evidence mass",
                ACTIVE_EVIDENCE_MASS_MICROS,
                sqlite.queryLong("SELECT SUM(consumed_mass_micros) FROM $table"),
            )
            assertEquals(
                "$table per-key cap",
                400_000L,
                sqlite.queryLong("SELECT MAX(consumed_mass_micros) FROM $table"),
            )
        }
        listOf(
            "mastery_presentation_node_budget" to "presentation_id = 'presentation-long-cross-page'",
            "mastery_problem_family_node_budget" to
                "problem_family_fingerprint = 'family-long-cross-page'",
        ).forEach { (table, identityPredicate) ->
            assertEquals(
                "$table cross-page mass, recency, and latest event",
                listOf(400_000L, 2L, EVENT_CLOCK + EVENT_COUNT + 2L),
                sqlite.queryLongRow(
                    "SELECT consumed_mass_micros, " +
                        (if (table == "mastery_problem_family_node_budget") {
                            "observation_count"
                        } else {
                            "2"
                        }) +
                        ", updated_at_epoch_millis FROM $table WHERE $identityPredicate " +
                        "AND knowledge_node_id = 'math.long-node.cross-page' " +
                        "AND direction = 'POSITIVE'",
                    columnCount = 3,
                ),
            )
            assertEquals(
                "$table cross-page latest event",
                "event-long-002",
                sqlite.queryText(
                    "SELECT last_event_id FROM $table WHERE $identityPredicate " +
                        "AND knowledge_node_id = 'math.long-node.cross-page' " +
                        "AND direction = 'POSITIVE'",
                ),
            )
        }
        assertEquals(
            FINAL_INPUT_RECEIPT_FINGERPRINT_GOLDEN,
            sqlite.queryText(
                "SELECT input_snapshot_fingerprint FROM " +
                    LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE +
                    " WHERE generation_id = $activeGenerationId",
            ),
        )
        assertEquals(
            ACTIVE_EVIDENCE_MASS_MICROS,
            sqlite.queryLong(
                "SELECT SUM(positive_evidence_micros + negative_evidence_micros) " +
                    "FROM mastery_knowledge_projection",
            ),
        )
        assertEquals(
            ACTIVE_ATTRIBUTION_ROW_COUNT.toLong(),
            sqlite.queryLong(
                "SELECT SUM(observation_count) FROM mastery_knowledge_projection",
            ),
        )
        assertEquals(
            1L,
            sqlite.queryLong(
                "SELECT COUNT(*) FROM $LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE " +
                    "WHERE generation_id = $activeGenerationId " +
                    "AND budget_policy_version = '$LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION' " +
                    "AND algorithm_version = " +
                    "'$LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION' " +
                    "AND source_event_count = $EVENT_COUNT " +
                    "AND source_supersession_count = 1 " +
                    "AND input_row_count = $ACTIVE_ATTRIBUTION_ROW_COUNT " +
                    "AND presentation_budget_row_count = $EXPECTED_DERIVED_ROW_COUNT " +
                    "AND problem_family_budget_row_count = $EXPECTED_DERIVED_ROW_COUNT",
            ),
        )
    }

    private fun openDatabase(
        context: android.content.Context,
        databaseName: String,
    ): LearnerMasteryRoomDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            LearnerMasteryRoomDatabase::class.java,
            databaseName,
        ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
            .setDriver(AndroidSQLiteDriver())
            .build()

    private suspend fun readActiveShadowWitness(
        context: android.content.Context,
        databaseName: String,
    ): ActiveShadowWitnessSnapshot {
        val database = openDatabase(context, databaseName)
        return try {
            val dao = database.cutoverAttestationDao()
            val active = checkNotNull(dao.readActiveProjectionGeneration())
            val receipt = dao.readProjectionBudgetRebuildReceipt(active.generationId)
            val health =
                dao.readProjectionHealth(
                    learnerId = LEARNER_ID,
                    generationId = active.generationId,
                    projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                    calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
                )
            ActiveShadowWitnessSnapshot(
                active = active,
                receipt = receipt,
                health = health,
                invalidDirectionalBudgetRowCount = dao.countInvalidDirectionalBudgetRows(),
            )
        } finally {
            database.close()
        }
    }

    private data class ActiveShadowWitnessSnapshot(
        val active: MasteryProjectionGenerationEntity,
        val receipt: ProjectionBudgetRebuildReceiptEntity?,
        val health: LearnerMasteryCutoverProjectionHealthRow,
        val invalidDirectionalBudgetRowCount: Long,
    ) {
        val generationId: Long
            get() = active.generationId

        val receiptMatchesActive: Boolean
            get() {
                val completedReceipt = receipt ?: return false
                return completedReceipt.budgetPolicyVersion ==
                    LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION &&
                    completedReceipt.algorithmVersion ==
                    LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION &&
                    completedReceipt.sourceEventCount == active.sourceEventCount &&
                    completedReceipt.sourceSupersessionCount == active.sourceSupersessionCount &&
                    completedReceipt.inputRowCount == active.budgetInputRowCount &&
                    completedReceipt.inputSnapshotFingerprint ==
                    active.budgetInputSnapshotFingerprint &&
                    completedReceipt.presentationBudgetRowCount ==
                    active.presentationBudgetRowCount &&
                    completedReceipt.problemFamilyBudgetRowCount ==
                    active.problemFamilyBudgetRowCount &&
                    completedReceipt.outputFingerprint == active.budgetOutputFingerprint &&
                    completedReceipt.completedAtEpochMillis == active.activatedAtEpochMillis
            }

        val isConsistent: Boolean
            get() =
                active.state == MasteryProjectionGenerationState.ACTIVE.name &&
                    active.stage == MasteryProjectionRebuildStage.COMPLETE.name &&
                    health.activeGenerationCount == 1L &&
                    health.buildingGenerationCount == 0L &&
                    health.projectionCount == health.activeShadowProjectionCount &&
                    health.subjectDigestCount == health.activeShadowDigestCount &&
                    health.presentationBudgetCount ==
                    health.activeShadowPresentationBudgetCount &&
                    health.problemFamilyBudgetCount ==
                    health.activeShadowProblemFamilyBudgetCount &&
                    health.nonActiveShadowCount == 0L &&
                    health.projectionShadowMismatchCount == 0L &&
                    health.digestShadowMismatchCount == 0L &&
                    health.presentationShadowMismatchCount == 0L &&
                    health.problemFamilyShadowMismatchCount == 0L &&
                    invalidDirectionalBudgetRowCount == 0L &&
                    receiptMatchesActive
    }

    private fun retiredShadowRowCountSql(): String =
        """
        SELECT
            (SELECT COUNT(*) FROM mastery_projection_shadow shadow
             JOIN mastery_projection_generation generation USING(generation_id)
             WHERE generation.state = 'RETIRED') +
            (SELECT COUNT(*) FROM mastery_subject_digest_shadow shadow
             JOIN mastery_projection_generation generation USING(generation_id)
             WHERE generation.state = 'RETIRED') +
            (SELECT COUNT(*) FROM mastery_presentation_node_budget_shadow shadow
             JOIN mastery_projection_generation generation USING(generation_id)
             WHERE generation.state = 'RETIRED') +
            (SELECT COUNT(*) FROM mastery_problem_family_node_budget_shadow shadow
             JOIN mastery_projection_generation generation USING(generation_id)
             WHERE generation.state = 'RETIRED')
        """.trimIndent()

    private fun SQLiteDatabase.queryLong(sql: String): Long =
        rawQuery(sql, emptyArray<String>()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SQLiteDatabase.queryText(sql: String): String =
        rawQuery(sql, emptyArray<String>()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.queryRowCount(sql: String): Int =
        rawQuery(sql, emptyArray<String>()).use { cursor -> cursor.count }

    private fun SQLiteDatabase.queryLongRow(
        sql: String,
        columnCount: Int,
    ): List<Long> =
        rawQuery(sql, emptyArray<String>()).use { cursor ->
            check(cursor.moveToFirst())
            List(columnCount, cursor::getLong)
        }

    private fun fingerprint(seed: Int): String = seed.toString(16).padStart(64, '0')

    private fun v19PrimaryReplayRow(index: Int): MasteryEventAttributionReplayRow {
        val suffix = index.toString().padStart(3, '0')
        val sharesCrossPageBudget = index == 0 || index == 2
        val budgetSuffix = if (sharesCrossPageBudget) "cross-page" else suffix
        val nodeSuffix = if (sharesCrossPageBudget) "cross-page" else suffix
        return MasteryEventAttributionReplayRow(
            eventId = "event-long-$suffix",
            candidateId = "candidate-long-$suffix",
            sourceFactId = "fact-long-$suffix",
            sourceProofFingerprint = fingerprint(10_000 + index),
            learnerId = LEARNER_ID,
            subject = "MATHEMATICS",
            direction = if (index % 2 == 0) "POSITIVE" else "NEGATIVE",
            eventSequence = index + 1L,
            occurredAtEpochMillis = EVENT_CLOCK + index,
            admittedAtEpochMillis = EVENT_CLOCK + EVENT_COUNT + index,
            projectionPolicyVersion =
                LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION,
            admissionPolicyVersion = "legacy-admission-v19",
            calibrationVersion = LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION,
            calibrationSnapshotFingerprint = null,
            calibrationProfileId = null,
            reviewResolutionFingerprint = null,
            problemFamilyFingerprint = "family-long-$budgetSuffix",
            presentationFingerprint = "presentation-long-$budgetSuffix",
            evidenceQualityMicros = 500_000L,
            independentlyAnswered = true,
            eventCanonicalFingerprint = fingerprint(index + 1),
            ordinal = 0,
            knowledgeNodeId = "math.long-node.$nodeSuffix",
            taxonomyVersion = "taxonomy-v19",
            knowledgePackVersion = "pack-v19",
            knowledgeNodeRefFingerprint =
                if (sharesCrossPageBudget) fingerprint(20_002) else fingerprint(20_000 + index * 4),
            evidenceMassMicros = 200_000L,
        )
    }

    private fun legacyProgressCheckpoint(): String =
        listOf(
            "7",
            MasteryProjectionRebuildStage.PROJECTIONS.name,
            LEARNER_ID,
            "MATHEMATICS",
            "math.long-node.127",
            "taxonomy-v19",
            "event-sequence-stream-v1",
            "128",
            "event-long-127",
            "0",
        ).joinToString(separator = "") { part -> "${part.length}:$part" }

    private companion object {
        const val LEARNER_ID = LOCAL_LEARNER_ID
        const val EVENT_COUNT = LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE + 2
        const val OLD_PROJECTION_PAGE_SIZE = 256
        const val OLD_CHECKPOINT_FIRST_EVENT_INDEX = 2
        const val OLD_CHECKPOINT_LAST_EVENT_INDEX = 257
        const val OLD_CHECKPOINT_EVENT_ID = "event-long-257"
        const val OLD_CHECKPOINT_INPUT_FINGERPRINT_GOLDEN =
            "5e9ae998eda6be973224556bdcd679c7c7c6d798409c2834fb7d2f699341a5b4"
        const val FINAL_INPUT_RECEIPT_FINGERPRINT_GOLDEN =
            "07e6c98a276e17484dc184df92eb7add049860b9be2bd3a9f8427e1925776b79"
        const val ATTRIBUTION_ROW_COUNT = EVENT_COUNT + 2
        const val ACTIVE_ATTRIBUTION_ROW_COUNT = ATTRIBUTION_ROW_COUNT - 1
        const val EXPECTED_DERIVED_ROW_COUNT = ACTIVE_ATTRIBUTION_ROW_COUNT - 1
        const val MULTI_ATTRIBUTION_EVENT_INDEX =
            LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE - 1
        val MULTI_ATTRIBUTION_EVENT_ID =
            "event-long-${MULTI_ATTRIBUTION_EVENT_INDEX.toString().padStart(3, '0')}"
        const val V19_ACTIVE_GENERATION_ID = 41L
        const val V19_BUILDING_GENERATION_ID = 42L
        const val EVENT_CLOCK = 1_700_000_000_000L
        const val REBUILD_CLOCK = 1_800_000_000_000L
        const val CRASHED_LEASE_EXPIRY = REBUILD_CLOCK + 10_000L
        const val RAW_EVIDENCE_MASS_MICROS = EVENT_COUNT * 200_000L
        const val ACTIVE_EVIDENCE_MASS_MICROS = (EVENT_COUNT - 1) * 200_000L
        const val SECOND_REBUILD_PAGE_HEADROOM = 128L
        val LEGACY_NODE_FINGERPRINT =
            MasteryProjectionIdentity.fingerprint(
                subject = "MATHEMATICS",
                knowledgeNodeId = "math.long-node.000",
                taxonomyVersion = "taxonomy-v19",
            )
    }
}
