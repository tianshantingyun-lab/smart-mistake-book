package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryMigration21To22InstrumentedTest {
    @Test
    fun migrationQuarantineSurvivesRebuildAndEveryStudentReadSurface() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v21-v22-end-to-end-quarantine.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            try {
                helper.createDatabase(21).use { connection ->
                    connection.execSQL("PRAGMA foreign_keys = OFF")
                    insertLegacyEvent(
                        connection = connection,
                        eventId = TRUSTED_EVENT_ID,
                        candidateId = TRUSTED_CANDIDATE_ID,
                        sourceFactId = TRUSTED_SOURCE_FACT_ID,
                        direction = "POSITIVE",
                        eventSequence = 1L,
                        occurredAtEpochMillis = DAY_1,
                        evidenceMassMicros = TRUSTED_EVIDENCE_MASS_MICROS,
                        presentationFingerprint = TRUSTED_PRESENTATION_FINGERPRINT,
                        problemFamilyFingerprint = TRUSTED_PROBLEM_FAMILY_FINGERPRINT,
                    )
                    insertLegacyEvent(
                        connection = connection,
                        eventId = QUARANTINED_EVENT_ID,
                        candidateId = QUARANTINED_CANDIDATE_ID,
                        sourceFactId = QUARANTINED_SOURCE_FACT_ID,
                        direction = "NEGATIVE",
                        eventSequence = 2L,
                        occurredAtEpochMillis = DAY_2,
                        evidenceMassMicros = QUARANTINED_EVIDENCE_MASS_MICROS,
                        presentationFingerprint = QUARANTINED_PRESENTATION_FINGERPRINT,
                        problemFamilyFingerprint = QUARANTINED_PROBLEM_FAMILY_FINGERPRINT,
                    )
                    insertAcceptedOpenResponseDecision(connection)
                    insertStaleDerivedState(connection)
                }

                helper.runMigrationsAndValidate(
                    version = 22,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_21_22),
                ).use { connection ->
                    persistLearnerMasteryCalibrationRegistry(connection)
                }

                val firstGeneration = rebuildToCompletion(context, databaseName, "first-rebuild")
                val first = readStudentFacingState(context, databaseName)
                assertOnlyTrustedEvidenceIsVisible(first)

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    sqlite.execSQL(
                        """
                        UPDATE mastery_projection_generation
                        SET target_projection_policy_version = 'force-idempotent-replay',
                            target_calibration_version = 'force-idempotent-replay'
                        WHERE generation_id = ? AND state = 'ACTIVE'
                        """.trimIndent(),
                        arrayOf(firstGeneration),
                    )
                }

                val secondGeneration =
                    rebuildToCompletion(context, databaseName, "idempotent-rebuild")
                assertTrue(secondGeneration > firstGeneration)
                val replayed = readStudentFacingState(context, databaseName)

                assertEquals(first, replayed)
                assertOnlyTrustedEvidenceIsVisible(replayed)
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        2L,
                        sqlite.longForMigrationQuery(
                            "SELECT COUNT(*) FROM mastery_learning_event",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.longForMigrationQuery(
                            """
                            SELECT COUNT(*)
                            FROM mastery_open_response_legacy_quarantine
                            WHERE accepted_event_id = '$QUARANTINED_EVENT_ID'
                            """.trimIndent(),
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.longForMigrationQuery(
                            """
                            SELECT COUNT(*)
                            FROM mastery_open_response_legacy_quarantine
                            WHERE accepted_event_id = '$TRUSTED_EVENT_ID'
                            """.trimIndent(),
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.longForMigrationQuery(
                            "SELECT COUNT(*) FROM mastery_projection_generation WHERE state='ACTIVE'",
                        ),
                    )
                    assertEquals(
                        2L,
                        sqlite.longForMigrationQuery(
                            "SELECT COUNT(*) FROM mastery_projection_generation WHERE state='RETIRED'",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun proofStateDigestIsContentSensitiveAndStreamsARealisticLargeLedger() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v22-proof-content-watermark.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            try {
                helper.createDatabase(22).use { connection ->
                    connection.execSQL("PRAGMA foreign_keys = OFF")
                    connection.prepare(
                        """
                        INSERT INTO mastery_open_response_legacy_quarantine(
                            accepted_event_id, event_canonical_fingerprint,
                            decision_fingerprint, quarantine_reason, policy_version,
                            quarantined_at_epoch_millis, quarantine_fingerprint
                        ) VALUES(?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { insert ->
                        repeat(LARGE_PROOF_ROW_COUNT) { index ->
                            insert.reset()
                            insert.bindText(1, "event-${index.toString().padStart(6, '0')}")
                            insert.bindText(2, fingerprint("event-$index"))
                            insert.bindText(3, fingerprint("decision-$index"))
                            insert.bindText(
                                4,
                                LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_REASON,
                            )
                            insert.bindText(
                                5,
                                LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_POLICY_VERSION,
                            )
                            insert.bindLong(6, index.toLong())
                            insert.bindText(7, fingerprint("quarantine-$index"))
                            insert.step()
                        }
                    }

                    var firstDigest = ""
                    val elapsedMillis =
                        kotlin.system.measureTimeMillis {
                            firstDigest =
                                learnerMasteryOpenResponseProofStateFingerprint(connection)
                        }
                    val repeatedDigest =
                        learnerMasteryOpenResponseProofStateFingerprint(connection)
                    assertEquals(firstDigest, repeatedDigest)
                    assertTrue(
                        "Streaming proof audit exceeded the bounded device budget: ${elapsedMillis}ms",
                        elapsedMillis < LARGE_PROOF_AUDIT_BUDGET_MILLIS,
                    )

                    connection.execSQL(
                        """
                        UPDATE mastery_open_response_legacy_quarantine
                        SET quarantined_at_epoch_millis = quarantined_at_epoch_millis + 1
                        WHERE accepted_event_id = 'event-000123'
                        """.trimIndent(),
                    )
                    assertEquals(
                        LARGE_PROOF_ROW_COUNT.toLong(),
                        connection.prepare(
                            "SELECT COUNT(*) FROM mastery_open_response_legacy_quarantine",
                        ).use { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        },
                    )
                    assertEquals(
                        LARGE_PROOF_ROW_COUNT.toLong(),
                        connection.prepare(
                            "SELECT MAX(rowid) FROM mastery_open_response_legacy_quarantine",
                        ).use { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        },
                    )
                    assertNotEquals(
                        firstDigest,
                        learnerMasteryOpenResponseProofStateFingerprint(connection),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun legacyModelAcceptedEventIsImmutablyQuarantinedAndActiveProjectionIsRetired() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v21-v22-open-response-quarantine.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            try {
                helper.createDatabase(21).use { connection ->
                    connection.execSQL("PRAGMA foreign_keys = OFF")
                    connection.execSQL(
                        """
                        INSERT INTO mastery_learning_event(
                            event_id, candidate_id, source_fact_id, source_proof_fingerprint,
                            learner_id, subject, direction, event_sequence,
                            occurred_at_epoch_millis, admitted_at_epoch_millis,
                            projection_policy_version, admission_policy_version,
                            calibration_version, canonical_fingerprint, presentation_fingerprint,
                            evidence_quality_micros, independently_answered
                        ) VALUES(
                            'legacy-open-event', 'legacy-candidate', 'legacy-fact',
                            '${fingerprint("proof")}', 'local-learner', 'MATHEMATICS', 'NEGATIVE',
                            1, 100, 110, 'learner-mastery-projection-v3',
                            'legacy-model-semantic-admission', 'learner-mastery-calibration-v3',
                            '${fingerprint("event")}', '${fingerprint("presentation")}', 500000, 1
                        )
                        """.trimIndent(),
                    )
                    connection.execSQL(
                        """
                        INSERT INTO mastery_open_response_dedicated_decision(
                            decision_fingerprint, attestation_fingerprint, receipt_fingerprint,
                            review_case_id, candidate_id, source_fact_id, learner_id, subject,
                            disposition, direction, local_reason, selected_scope_fingerprint,
                            selected_knowledge_count, local_policy_version,
                            calibration_snapshot_fingerprint, accepted_event_id,
                            independently_completed, decided_at_epoch_millis
                        ) VALUES(
                            '${fingerprint("decision")}', '${fingerprint("attestation")}',
                            '${fingerprint("receipt")}', 'legacy-review', 'legacy-candidate',
                            'legacy-fact', 'local-learner', 'MATHEMATICS', 'ACCEPTED', 'NEGATIVE',
                            NULL, '${fingerprint("scope")}', 1,
                            'learner-mastery-open-response-dedicated-v1',
                            '${fingerprint("calibration")}', 'legacy-open-event', 1, 120
                        )
                        """.trimIndent(),
                    )
                    connection.execSQL(
                        """
                        INSERT INTO mastery_projection_generation(
                            generation_id, state, target_projection_policy_version,
                            target_calibration_version, source_event_count,
                            source_supersession_count, stage, cursor_learner_id, cursor_subject,
                            cursor_event_sequence, cursor_ordinal, created_at_epoch_millis
                        ) VALUES(
                            1, 'ACTIVE', 'learner-mastery-projection-v3',
                            'learner-mastery-calibration-v3', 1, 0, 'COMPLETE', '', '', -1, -1, 115
                        )
                        """.trimIndent(),
                    )
                }

                helper.runMigrationsAndValidate(
                    version = 22,
                    migrations = listOf(LEARNER_MASTERY_MIGRATION_21_22),
                ).use { connection ->
                    assertEquals(
                        1L,
                        connection.prepare(
                            "SELECT COUNT(*) FROM mastery_open_response_legacy_quarantine",
                        ).use { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        },
                    )
                    assertEquals(
                        0L,
                        connection.prepare(
                            """
                            SELECT COUNT(*) FROM mastery_learning_event AS event
                            WHERE NOT EXISTS (
                                SELECT 1 FROM mastery_open_response_legacy_quarantine AS quarantine
                                WHERE quarantine.accepted_event_id = event.event_id
                            )
                            """.trimIndent(),
                        ).use { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        },
                    )
                    assertEquals(
                        0L,
                        connection.prepare(
                            "SELECT COUNT(*) FROM mastery_projection_generation WHERE state='ACTIVE'",
                        ).use { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        },
                    )
                    assertEquals(
                        1L,
                        connection.prepare(
                            "SELECT COUNT(*) FROM mastery_projection_generation WHERE state='RETIRED'",
                        ).use { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        },
                    )
                    assertEquals(
                        1L,
                        connection.prepare("SELECT COUNT(*) FROM mastery_learning_event").use {
                            statement ->
                            check(statement.step())
                            statement.getLong(0)
                        },
                    )
                    assertTrue(
                        runCatching {
                            connection.execSQL(
                                "DELETE FROM mastery_open_response_legacy_quarantine",
                            )
                        }.isFailure,
                    )
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { reopened ->
                    assertEquals(22, reopened.version)
                    assertEquals(
                        1L,
                        reopened.rawQuery(
                            "SELECT COUNT(*) FROM mastery_open_response_legacy_quarantine",
                            null,
                        ).use { cursor ->
                            check(cursor.moveToFirst())
                            cursor.getLong(0)
                        },
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    private fun fingerprint(seed: String): String =
        com.tingyun.smartmistakebook.core.model.CanonicalSha256(
            "learner-mastery-migration-21-22-test",
        ).field("seed", seed).finish()

    private fun insertLegacyEvent(
        connection: androidx.sqlite.SQLiteConnection,
        eventId: String,
        candidateId: String,
        sourceFactId: String,
        direction: String,
        eventSequence: Long,
        occurredAtEpochMillis: Long,
        evidenceMassMicros: Long,
        presentationFingerprint: String,
        problemFamilyFingerprint: String,
    ) {
        val sourceCanonicalFingerprint = fingerprint("source:$sourceFactId")
        val sourceProofFingerprint = fingerprint("proof:$sourceFactId")
        connection.execSQL(
            """
            INSERT INTO mastery_source_fact(
                source_fact_id, learner_id, subject, source_kind, source_reference_id,
                presentation_id, outcome, assistance, retry_state, authority,
                source_payload_fingerprint, occurred_at_epoch_millis,
                attested_at_epoch_millis, received_at_epoch_millis,
                source_policy_version, idempotency_key, canonical_fingerprint,
                problem_family_fingerprint, presentation_fingerprint,
                response_form, independently_answered, hint_count, answer_revealed,
                verification_kind, evidence_context_kind
            ) VALUES(
                '$sourceFactId', '$LEARNER_ID', 'MATH', 'SAVED_PROBLEM_REVIEW',
                'source:$sourceFactId', '$presentationFingerprint',
                '${if (direction == "POSITIVE") "CORRECT" else "INCORRECT"}',
                'INDEPENDENT', 'FIRST_ATTEMPT', 'LOCAL_VERIFIED',
                '${fingerprint("payload:$sourceFactId")}', $occurredAtEpochMillis,
                $occurredAtEpochMillis, $occurredAtEpochMillis,
                '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 'source-key:$sourceFactId',
                '$sourceCanonicalFingerprint', '$problemFamilyFingerprint',
                '$presentationFingerprint', 'MULTIPLE_CHOICE', 1, 0, 0,
                'DEVICE_OBSERVED', 'SAVED_MISTAKE'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_source_proof(
                source_fact_id, source_fact_canonical_fingerprint, source_policy_version,
                policy_supported, proof_fingerprint, created_at_epoch_millis
            ) VALUES(
                '$sourceFactId', '$sourceCanonicalFingerprint',
                '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 1,
                '$sourceProofFingerprint', $occurredAtEpochMillis
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
                '$candidateId', '$LEARNER_ID', 'MATH', '$sourceFactId', 'HIGH',
                'legacy-test-model', 'learner-mastery-admission-v5',
                $occurredAtEpochMillis, $occurredAtEpochMillis,
                'candidate-key:$candidateId', '${fingerprint("candidate:$candidateId")}',
                '${if (eventId == QUARANTINED_EVENT_ID) "MODEL_SCOPED" else "TRUSTED_LOCAL"}'
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
                '$eventId', '$candidateId', '$sourceFactId', '$sourceProofFingerprint',
                '$LEARNER_ID', 'MATH', '$direction', $eventSequence,
                $occurredAtEpochMillis, $occurredAtEpochMillis,
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                'learner-mastery-admission-v5',
                '$LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION',
                '${fingerprint("event:$eventId")}', '$problemFamilyFingerprint',
                '$presentationFingerprint', 900000, 1
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
                '$eventId', 0, 'MATH', '$KNOWLEDGE_NODE_ID', '$TAXONOMY_VERSION',
                '$KNOWLEDGE_PACK_VERSION', '${knowledgeNode().canonicalFingerprint}',
                $evidenceMassMicros
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_applied_event(
                event_id, event_canonical_fingerprint, learner_id, event_sequence,
                projection_policy_version, application_fingerprint,
                applied_at_epoch_millis
            ) VALUES(
                '$eventId', '${fingerprint("event:$eventId")}', '$LEARNER_ID',
                $eventSequence, '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                '${fingerprint("application:$eventId")}', $occurredAtEpochMillis
            )
            """.trimIndent(),
        )
    }

    private fun insertAcceptedOpenResponseDecision(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO mastery_open_response_dedicated_decision(
                decision_fingerprint, attestation_fingerprint, receipt_fingerprint,
                review_case_id, candidate_id, source_fact_id, learner_id, subject,
                disposition, direction, local_reason, selected_scope_fingerprint,
                selected_knowledge_count, local_policy_version,
                calibration_snapshot_fingerprint, accepted_event_id,
                independently_completed, decided_at_epoch_millis
            ) VALUES(
                '${fingerprint("decision")}', '${fingerprint("attestation")}',
                '${fingerprint("receipt")}', 'legacy-review', '$QUARANTINED_CANDIDATE_ID',
                '$QUARANTINED_SOURCE_FACT_ID', '$LEARNER_ID', 'MATH', 'ACCEPTED',
                'NEGATIVE', NULL, '${fingerprint("scope")}', 1,
                'learner-mastery-open-response-dedicated-v1',
                '${fingerprint("calibration")}', '$QUARANTINED_EVENT_ID', 1, $DAY_2
            )
            """.trimIndent(),
        )
    }

    private fun insertStaleDerivedState(connection: androidx.sqlite.SQLiteConnection) {
        val stableNodeFingerprint =
            MasteryProjectionIdentity.fingerprint(
                subject = "MATH",
                knowledgeNodeId = KNOWLEDGE_NODE_ID,
                taxonomyVersion = TAXONOMY_VERSION,
            )
        connection.execSQL(
            """
            INSERT INTO mastery_knowledge_projection(
                learner_id, subject, knowledge_node_id, taxonomy_version,
                latest_evidence_knowledge_pack_version, stable_node_identity_fingerprint,
                positive_evidence_micros, negative_evidence_micros, mastery_score_micros,
                mastery_state, trend, observation_count, memory_stability_millis,
                recall_due_at_epoch_millis, last_positive_at_epoch_millis,
                last_negative_at_epoch_millis, last_evidence_at_epoch_millis,
                last_event_sequence, last_ordered_event_id, projection_policy_version,
                evidence_quality_micros, independent_problem_family_count,
                distinct_presentation_count
            ) VALUES(
                '$LEARNER_ID', 'MATH', '$KNOWLEDGE_NODE_ID', '$TAXONOMY_VERSION',
                '$KNOWLEDGE_PACK_VERSION', '$stableNodeFingerprint',
                $TRUSTED_EVIDENCE_MASS_MICROS, $QUARANTINED_EVIDENCE_MASS_MICROS,
                250000, 'NEEDS_REINFORCEMENT', 'WAVERING', 2, 86400000,
                $DAY_2, $DAY_1, $DAY_2, $DAY_2, 2, '$QUARANTINED_EVENT_ID',
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION', 1800000, 2, 2
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mastery_subject_digest VALUES(
                '$LEARNER_ID', 'MATH', 1, 0, 0, 2, $DAY_2,
                '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION'
            )
            """.trimIndent(),
        )
        insertStaleBudget(
            connection = connection,
            table = "mastery_presentation_node_budget",
            identityColumn = "presentation_id",
            identity = TRUSTED_PRESENTATION_FINGERPRINT,
            direction = "POSITIVE",
            eventId = TRUSTED_EVENT_ID,
            mass = TRUSTED_EVIDENCE_MASS_MICROS,
            stableNodeFingerprint = stableNodeFingerprint,
        )
        insertStaleBudget(
            connection = connection,
            table = "mastery_presentation_node_budget",
            identityColumn = "presentation_id",
            identity = QUARANTINED_PRESENTATION_FINGERPRINT,
            direction = "NEGATIVE",
            eventId = QUARANTINED_EVENT_ID,
            mass = QUARANTINED_EVIDENCE_MASS_MICROS,
            stableNodeFingerprint = stableNodeFingerprint,
        )
        insertStaleBudget(
            connection = connection,
            table = "mastery_problem_family_node_budget",
            identityColumn = "problem_family_fingerprint",
            identity = TRUSTED_PROBLEM_FAMILY_FINGERPRINT,
            direction = "POSITIVE",
            eventId = TRUSTED_EVENT_ID,
            mass = TRUSTED_EVIDENCE_MASS_MICROS,
            stableNodeFingerprint = stableNodeFingerprint,
        )
        insertStaleBudget(
            connection = connection,
            table = "mastery_problem_family_node_budget",
            identityColumn = "problem_family_fingerprint",
            identity = QUARANTINED_PROBLEM_FAMILY_FINGERPRINT,
            direction = "NEGATIVE",
            eventId = QUARANTINED_EVENT_ID,
            mass = QUARANTINED_EVIDENCE_MASS_MICROS,
            stableNodeFingerprint = stableNodeFingerprint,
        )
        connection.execSQL(
            """
            INSERT INTO mastery_projection_generation(
                generation_id, state, target_projection_policy_version,
                target_calibration_version, source_event_count,
                source_supersession_count, stage, cursor_learner_id, cursor_subject,
                cursor_event_sequence, cursor_ordinal, snapshot_fingerprint,
                projection_row_count, subject_digest_row_count,
                presentation_budget_row_count, problem_family_budget_row_count,
                created_at_epoch_millis, activated_at_epoch_millis
            ) VALUES(
                1, 'ACTIVE', '$LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION',
                '$LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION', 2, 0, 'COMPLETE',
                '', '', -1, -1, '${fingerprint("stale-generation")}', 1, 1, 2, 2,
                $DAY_2, $DAY_2
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "INSERT INTO mastery_ledger_sequence VALUES('$LEARNER_ID', 2)",
        )
        connection.execSQL(
            """
            INSERT INTO mastery_store_metadata(metadata_key, metadata_value)
            VALUES('projection_rebuild_policy', 'REQUIRED_V2')
            """.trimIndent(),
        )
    }

    private fun insertStaleBudget(
        connection: androidx.sqlite.SQLiteConnection,
        table: String,
        identityColumn: String,
        identity: String,
        direction: String,
        eventId: String,
        mass: Long,
        stableNodeFingerprint: String,
    ) {
        val observationCountColumn =
            if (table == "mastery_problem_family_node_budget") "observation_count," else ""
        val observationCountValue =
            if (table == "mastery_problem_family_node_budget") "1," else ""
        connection.execSQL(
            """
            INSERT INTO $table(
                learner_id, $identityColumn, subject, knowledge_node_id,
                taxonomy_version, direction, stable_node_identity_fingerprint,
                $observationCountColumn consumed_mass_micros, last_event_id,
                updated_at_epoch_millis
            ) VALUES(
                '$LEARNER_ID', '$identity', 'MATH', '$KNOWLEDGE_NODE_ID',
                '$TAXONOMY_VERSION', '$direction', '$stableNodeFingerprint',
                $observationCountValue $mass, '$eventId', $DAY_2
            )
            """.trimIndent(),
        )
    }

    private suspend fun rebuildToCompletion(
        context: android.content.Context,
        databaseName: String,
        ownerId: String,
    ): Long {
        val database = openDatabase(context, databaseName)
        return try {
            var result =
                database.masteryDao().rebuildDerivedStateChunk(
                    ownerId = ownerId,
                    nowEpochMillis = NOW,
                )
            var transactionCount = 1
            while (!result.completed) {
                result =
                    database.masteryDao().rebuildDerivedStateChunk(
                        ownerId = ownerId,
                        nowEpochMillis = NOW + transactionCount,
                    )
                transactionCount += 1
                assertTrue("Projection rebuild did not converge", transactionCount < 32)
            }
            checkNotNull(result.generationId)
        } finally {
            database.close()
        }
    }

    private suspend fun readStudentFacingState(
        context: android.content.Context,
        databaseName: String,
    ): MigratedStudentFacingState {
        val database = openDatabase(context, databaseName)
        val (digest, timeline, displayItem) =
            RoomLearnerMasteryStore(database, nowEpochMillis = { NOW }).use { store ->
                val currentDigest =
                    store.querySubjectDigest(
                        SubjectMasteryDigestQuery(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH,
                        ),
                    )
                val currentTimeline =
                    store.querySubjectTimeline(
                        SubjectMasteryTimelineQuery(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH,
                        ),
                    )
                val revision =
                    store.observeDisplayRevision(
                        BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                    ).first()
                val page =
                    store.readDisplayKnowledgePage(
                        BoundLearnerMasteryDisplayPageQuery(
                            learnerId = LEARNER_ID,
                            request =
                                LearnerMasteryDisplayPageRequest(
                                    subject = SubjectKind.MATH,
                                    expectedRevision = revision,
                                    orderedKnowledgeNodes = listOf(knowledgeNode()),
                                ),
                        ),
                    ) as LearnerMasteryDisplayPageResult.Current
                Triple(currentDigest, currentTimeline, page.items.single())
            }
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            return MigratedStudentFacingState(
                digest = digest,
                timeline = timeline,
                displayItem = displayItem,
                projection = sqlite.readProjectionSnapshot(),
                presentationBudgets =
                    sqlite.readBudgetSnapshots(
                        table = "mastery_presentation_node_budget",
                        identityColumn = "presentation_id",
                    ),
                problemFamilyBudgets =
                    sqlite.readBudgetSnapshots(
                        table = "mastery_problem_family_node_budget",
                        identityColumn = "problem_family_fingerprint",
                    ),
            )
        }
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

    private fun assertOnlyTrustedEvidenceIsVisible(state: MigratedStudentFacingState) {
        assertEquals(1, state.digest.stateCounts.total())
        assertEquals(1, state.digest.focus.size)
        assertEquals(knowledgeNode(), state.digest.focus.single().knowledgeNode)
        assertEquals(1, state.timeline.sumOf(SubjectMasteryTimelineEntry::observationCount))
        assertEquals(SubjectMasteryTimelineSignal.PROGRESS, state.timeline.single().signal)
        assertEquals(knowledgeNode(), state.displayItem.knowledgeNode)
        assertEquals(TRUSTED_EVIDENCE_MASS_MICROS, state.projection.positiveEvidenceMicros)
        assertEquals(0L, state.projection.negativeEvidenceMicros)
        assertEquals(1, state.projection.observationCount)
        assertEquals(TRUSTED_EVENT_ID, state.projection.lastEventId)
        assertEquals(
            TRUSTED_PRESENTATION_FINGERPRINT,
            state.presentationBudgets.single().identity,
        )
        assertEquals(
            TRUSTED_PROBLEM_FAMILY_FINGERPRINT,
            state.problemFamilyBudgets.single().identity,
        )
        listOf(state.presentationBudgets, state.problemFamilyBudgets).forEach { budgets ->
            val budget = budgets.single()
            assertEquals("POSITIVE", budget.direction)
            assertEquals(TRUSTED_EVIDENCE_MASS_MICROS, budget.consumedMassMicros)
            assertEquals(TRUSTED_EVENT_ID, budget.lastEventId)
        }
    }

    private fun knowledgeNode(): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = KNOWLEDGE_NODE_ID,
            taxonomyVersion = TAXONOMY_VERSION,
            knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
        )

    private companion object {
        const val LEARNER_ID = "local-learner"
        const val KNOWLEDGE_NODE_ID = "math.algebra.linear-equation"
        const val TAXONOMY_VERSION = "taxonomy-v1"
        const val KNOWLEDGE_PACK_VERSION = "pack-v1"
        const val TRUSTED_SOURCE_FACT_ID = "trusted-source-fact"
        const val TRUSTED_CANDIDATE_ID = "trusted-candidate"
        const val TRUSTED_EVENT_ID = "trusted-event"
        const val QUARANTINED_SOURCE_FACT_ID = "legacy-open-source-fact"
        const val QUARANTINED_CANDIDATE_ID = "legacy-open-candidate"
        const val QUARANTINED_EVENT_ID = "legacy-open-event"
        const val TRUSTED_EVIDENCE_MASS_MICROS = 400_000L
        const val QUARANTINED_EVIDENCE_MASS_MICROS = 900_000L
        const val DAY_1 = 1_700_006_400_000L
        const val DAY_2 = DAY_1 + 86_400_000L
        const val NOW = DAY_2 + 86_400_000L
        val TRUSTED_PRESENTATION_FINGERPRINT = "1".repeat(64)
        val TRUSTED_PROBLEM_FAMILY_FINGERPRINT = "2".repeat(64)
        val QUARANTINED_PRESENTATION_FINGERPRINT = "3".repeat(64)
        val QUARANTINED_PROBLEM_FAMILY_FINGERPRINT = "4".repeat(64)
        const val LARGE_PROOF_ROW_COUNT = 12_000
        const val LARGE_PROOF_AUDIT_BUDGET_MILLIS = 15_000L
    }
}

private data class MigratedStudentFacingState(
    val digest: SubjectMasteryDigest,
    val timeline: List<SubjectMasteryTimelineEntry>,
    val displayItem: LearnerMasteryDisplayKnowledgeItem,
    val projection: MigratedProjectionSnapshot,
    val presentationBudgets: List<MigratedBudgetSnapshot>,
    val problemFamilyBudgets: List<MigratedBudgetSnapshot>,
)

private data class MigratedProjectionSnapshot(
    val positiveEvidenceMicros: Long,
    val negativeEvidenceMicros: Long,
    val observationCount: Int,
    val lastEventId: String,
)

private data class MigratedBudgetSnapshot(
    val identity: String,
    val direction: String,
    val consumedMassMicros: Long,
    val lastEventId: String,
)

private fun SubjectMasteryStateCounts.total(): Int =
    needsReinforcement + familiarizing + steady

private fun SQLiteDatabase.readProjectionSnapshot(): MigratedProjectionSnapshot =
    rawQuery(
        """
        SELECT positive_evidence_micros, negative_evidence_micros,
               observation_count, last_ordered_event_id
        FROM mastery_knowledge_projection
        WHERE learner_id = 'local-learner'
          AND subject = 'MATH'
          AND knowledge_node_id = 'math.algebra.linear-equation'
          AND taxonomy_version = 'taxonomy-v1'
        """.trimIndent(),
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        MigratedProjectionSnapshot(
            positiveEvidenceMicros = cursor.getLong(0),
            negativeEvidenceMicros = cursor.getLong(1),
            observationCount = cursor.getInt(2),
            lastEventId = cursor.getString(3),
        )
    }

private fun SQLiteDatabase.readBudgetSnapshots(
    table: String,
    identityColumn: String,
): List<MigratedBudgetSnapshot> =
    rawQuery(
        """
        SELECT $identityColumn, direction, consumed_mass_micros, last_event_id
        FROM $table
        WHERE learner_id = 'local-learner'
          AND subject = 'MATH'
          AND knowledge_node_id = 'math.algebra.linear-equation'
          AND taxonomy_version = 'taxonomy-v1'
        ORDER BY direction, $identityColumn
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    MigratedBudgetSnapshot(
                        identity = cursor.getString(0),
                        direction = cursor.getString(1),
                        consumedMassMicros = cursor.getLong(2),
                        lastEventId = cursor.getString(3),
                    ),
                )
            }
        }
    }

private fun SQLiteDatabase.longForMigrationQuery(sql: String): Long =
    rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
