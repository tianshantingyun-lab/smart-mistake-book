package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasterySchemaMigrationInstrumentedTest {
    @Test
    fun migration6To7AddsEmptyAppendOnlyCutoverAndDestinationLedgerState() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v6-v7.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )

            helper.createDatabase(6).use {}
            helper.runMigrationsAndValidate(
                version = 7,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_6_7),
            ).use { connection ->
                assertEquals(
                    0L,
                    connection.longForQuery("SELECT COUNT(*) FROM mastery_cutover_fence"),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_cutover_completion_receipt",
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            "mastery_legacy_fact_migration_destination_record",
                    ),
                )
                assertEquals(
                    setOf(
                        "singleton_key",
                        "cutover_generation",
                        "student_import_evidence_fingerprint",
                        "mastery_import_evidence_fingerprint",
                        "cutover_intent_fingerprint",
                        "fence_fingerprint",
                    ),
                    connection.columnNames(LEARNER_MASTERY_CUTOVER_FENCE_TABLE),
                )
                assertEquals(
                    setOf(
                        "singleton_key",
                        "cutover_generation",
                        "cutover_intent_fingerprint",
                        "authority_fence_fingerprint",
                        "learner_id",
                        "source_generation",
                        "migration_ledger_canonical_digest",
                        "ledger_binding_fingerprint",
                        "receipt_fingerprint",
                    ),
                    connection.columnNames(
                        LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    setOf(
                        "learner_id",
                        "source_generation",
                        "batch_sequence",
                        "observation_ordinal",
                        "source_fact_id",
                        "source_fact_canonical_fingerprint",
                        "candidate_id",
                        "candidate_canonical_fingerprint",
                        "destination_record_canonical_fingerprint",
                    ),
                    connection.columnNames(
                        LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
                    ),
                )
                val triggerNames =
                    connection.textSetForQuery(
                        """
                        SELECT name
                        FROM sqlite_master
                        WHERE type = 'trigger'
                        """.trimIndent(),
                    )
                assertTrue(
                    triggerNames.containsAll(
                        LEARNER_MASTERY_CUTOVER_IMMUTABILITY_TRIGGER_NAMES
                            .filterNotTo(mutableSetOf()) { trigger ->
                                "legacy_observation_snapshot" in trigger
                            },
                    ),
                )
                assertTrue(
                    triggerNames.containsAll(
                        setOf(
                            "immutable_mastery_learning_event_update",
                            "immutable_mastery_learning_event_delete",
                        ),
                    ),
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            """
                            INSERT INTO mastery_cutover_fence (
                                singleton_key, cutover_generation,
                                student_import_evidence_fingerprint,
                                mastery_import_evidence_fingerprint,
                                cutover_intent_fingerprint, fence_fingerprint
                            ) VALUES (
                                'wrong-authority', 1,
                                '${"a".repeat(64)}', '${"b".repeat(64)}',
                                '${"c".repeat(64)}', '${"d".repeat(64)}'
                            )
                            """.trimIndent(),
                        )
                    }.isFailure,
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration6To7FailsClosedForCheckpointRowsWithoutDestinationProof() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v6-ledger.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )

            helper.createDatabase(6).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO mastery_legacy_fact_migration_checkpoint (
                        learner_id, source_generation, batch_sequence,
                        batch_fingerprint, observation_count, final_batch,
                        source_policy_version, projection_policy_version,
                        completed_at_epoch_millis
                    ) VALUES (
                        '$LOCAL_LEARNER_ID', 'legacy-v1', 1,
                        '${"a".repeat(64)}', 1, 1,
                        '$LEARNER_MASTERY_SOURCE_POLICY_VERSION',
                        '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                        100
                    )
                    """.trimIndent(),
                )
            }
            helper.runMigrationsAndValidate(
                version = 7,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_6_7),
            ).use { connection ->
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            "mastery_legacy_fact_migration_destination_record",
                    ),
                )
            }
            LearnerMasteryCutoverControlPortFactory.openForTest(
                context = context,
                databaseName = databaseName,
                learnerId = LOCAL_LEARNER_ID,
            ).use { port ->
                assertNull(port.recomputeCompletedMigrationLedger("legacy-v1"))
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration7To8AddsEmptyRawLedgerAndDoesNotPromoteV7SemanticRows() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v7-v8.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )

            helper.createDatabase(7).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO mastery_legacy_fact_migration_checkpoint (
                        learner_id, source_generation, batch_sequence,
                        batch_fingerprint, observation_count, final_batch,
                        source_policy_version, projection_policy_version,
                        completed_at_epoch_millis
                    ) VALUES (
                        '$LOCAL_LEARNER_ID', '${"a".repeat(64)}', 1,
                        '${"b".repeat(64)}', 0, 1,
                        '$LEARNER_MASTERY_SOURCE_POLICY_VERSION',
                        '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                        100
                    )
                    """.trimIndent(),
                )
            }
            helper.runMigrationsAndValidate(
                version = 8,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_7_8),
            ).use { connection ->
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
                    ),
                )
                assertEquals(
                    1L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_legacy_fact_migration_checkpoint",
                    ),
                )
                assertEquals(
                    setOf(
                        "learner_id",
                        "source_generation",
                        "batch_sequence",
                        "source_page_canonical_fingerprint",
                        "after_occurred_at_epoch_millis",
                        "after_source_fact_id",
                        "terminal_occurred_at_epoch_millis",
                        "terminal_source_fact_id",
                        "snapshot_count",
                        "final_batch",
                        "page_receipt_canonical_fingerprint",
                    ),
                    connection.columnNames(LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE),
                )
                assertTrue(
                    connection.textSetForQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'trigger'",
                    ).containsAll(
                        LEARNER_MASTERY_CUTOVER_IMMUTABILITY_TRIGGER_NAMES,
                    ),
                )
            }
            LearnerMasteryCutoverControlPortFactory.openForTest(
                context = context,
                databaseName = databaseName,
                learnerId = LOCAL_LEARNER_ID,
            ).use { port ->
                assertNull(
                    port.recomputeCompletedMigrationLedger("a".repeat(64)),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration8To9AddsAnEmptyImmutableAttemptLedgerWithoutBackfill() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v8-v9.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )

            helper.createDatabase(8).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO mastery_observation_candidate (
                        candidate_id, learner_id, subject, source_fact_id, confidence,
                        model_version, requested_policy_version,
                        proposed_at_epoch_millis, received_at_epoch_millis,
                        idempotency_key, canonical_fingerprint, candidate_origin
                    ) VALUES (
                        'historical-candidate', '$LOCAL_LEARNER_ID', 'MATH',
                        'historical-source', 'LOW', 'historical-model-v1',
                        '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                        10, 10, 'historical-idempotency', '${"a".repeat(64)}',
                        'MODEL_SCOPED'
                    )
                    """.trimIndent(),
                )
            }
            helper.runMigrationsAndValidate(
                version = 9,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_8_9),
            ).use { connection ->
                assertEquals(
                    1L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_observation_candidate",
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
                    ),
                )
                connection.execSQL(
                    """
                    INSERT INTO $LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE (
                        receipt_fingerprint, request_generation_fingerprint,
                        primary_attempt_key, learner_id, subject, source_fact_id,
                        model_version, request_version, mode_version,
                        proposal_fingerprint, terminal_reason, candidate_id,
                        admission_receipt_fingerprint, received_at_epoch_millis
                    ) VALUES (
                        '${"b".repeat(64)}', '${"c".repeat(64)}', '${"c".repeat(64)}',
                        '$LOCAL_LEARNER_ID', 'MATH', 'historical-source',
                        'model-v1', 'request-v1', 'direct-v1', '${"d".repeat(64)}',
                        'MALFORMED_SUBMISSION', NULL, NULL, 20
                    )
                    """.trimIndent(),
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            "UPDATE " +
                                LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                                " SET terminal_reason = 'ADMITTED'",
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            "DELETE FROM " +
                                LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
                        )
                    }.isFailure,
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration9To10PreservesDataAndInstallsCompleteHistoricalCalibrationRegistry() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v9-v10.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            val historical =
                LocalMasteryCalibrationRegistry.allSnapshots().single {
                    it.subject == "MATH" &&
                        it.calibrationVersion ==
                        LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION
                }
            val stableNodeFingerprint =
                MasteryProjectionIdentity.fingerprint(
                    subject = "MATH",
                    knowledgeNodeId = "math.migration.calibration",
                    taxonomyVersion = "taxonomy-v1",
                )

            helper.createDatabase(9).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO mastery_calibration_snapshot (
                        subject, profile_id, calibration_version,
                        projection_policy_version, prior_log_odds_micros,
                        positive_log_likelihood_micros,
                        negative_log_likelihood_micros, steady_threshold_micros,
                        reinforcement_threshold_micros,
                        recall_half_life_scale_micros, snapshot_fingerprint
                    ) VALUES (
                        '${historical.subject}', '${historical.profileId}',
                        '${historical.calibrationVersion}',
                        '${historical.projectionPolicyVersion}',
                        ${historical.priorLogOddsMicros},
                        ${historical.positiveLogLikelihoodMicros},
                        ${historical.negativeLogLikelihoodMicros},
                        ${historical.steadyThresholdMicros},
                        ${historical.reinforcementThresholdMicros},
                        ${historical.recallHalfLifeScaleMicros},
                        '${historical.snapshotFingerprint}'
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_knowledge_projection (
                        learner_id, subject, knowledge_node_id, taxonomy_version,
                        latest_evidence_knowledge_pack_version,
                        stable_node_identity_fingerprint,
                        positive_evidence_micros, negative_evidence_micros,
                        mastery_score_micros, mastery_state, trend,
                        observation_count, memory_stability_millis,
                        recall_due_at_epoch_millis,
                        last_positive_at_epoch_millis,
                        last_negative_at_epoch_millis,
                        last_evidence_at_epoch_millis, last_event_sequence,
                        last_ordered_event_id, projection_policy_version,
                        evidence_quality_micros,
                        independent_problem_family_count,
                        distinct_presentation_count, historical_log_odds_micros,
                        calibration_snapshot_fingerprint,
                        calibration_profile_id,
                        recall_familiarizing_at_epoch_millis,
                        recall_reinforcement_at_epoch_millis
                    ) VALUES (
                        '$LOCAL_LEARNER_ID', 'MATH',
                        'math.migration.calibration', 'taxonomy-v1', 'pack-v1',
                        '$stableNodeFingerprint',
                        1000000, 0, 600000, 'FAMILIARIZING', 'STABLE',
                        1, 86400000, 172800000, 1000, NULL, 1000, 1,
                        'historical-event', '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                        1000000, 1, 1, 500000,
                        '${historical.snapshotFingerprint}',
                        '${historical.profileId}', NULL, 259200000
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_source_fact (
                        source_fact_id, learner_id, subject, source_kind,
                        source_reference_id, presentation_id, outcome, assistance,
                        retry_state, authority, source_payload_fingerprint,
                        occurred_at_epoch_millis, attested_at_epoch_millis,
                        received_at_epoch_millis, source_policy_version,
                        idempotency_key, canonical_fingerprint
                    ) VALUES (
                        'legacy-review-source', '$LOCAL_LEARNER_ID', 'MATH',
                        'TUTOR_GUIDANCE', 'legacy-review-reference',
                        'legacy-review-presentation', 'INCORRECT', 'INDEPENDENT',
                        'FIRST_ATTEMPT', 'MODEL_REVIEWED', '${"1".repeat(64)}',
                        10, 10, 10, '$LEARNER_MASTERY_SOURCE_POLICY_VERSION',
                        'legacy-review-source-idempotency', '${"2".repeat(64)}'
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_source_proof (
                        source_fact_id, source_fact_canonical_fingerprint,
                        source_policy_version, policy_supported, proof_fingerprint,
                        created_at_epoch_millis
                    ) VALUES (
                        'legacy-review-source', '${"2".repeat(64)}',
                        '$LEARNER_MASTERY_SOURCE_POLICY_VERSION', 1,
                        '${"3".repeat(64)}', 10
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_observation_candidate (
                        candidate_id, learner_id, subject, source_fact_id, confidence,
                        model_version, requested_policy_version,
                        proposed_at_epoch_millis, received_at_epoch_millis,
                        idempotency_key, canonical_fingerprint, candidate_origin
                    ) VALUES (
                        'legacy-review-candidate', '$LOCAL_LEARNER_ID', 'MATH',
                        'legacy-review-source', 'HIGH', 'legacy-review-model-v1',
                        '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION',
                        10, 10, 'legacy-review-candidate-idempotency',
                        '${"4".repeat(64)}', 'MODEL_SCOPED'
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_evidence_review_case (
                        review_case_id, candidate_id,
                        candidate_canonical_fingerprint, source_fact_id,
                        source_proof_fingerprint, learner_id, subject, reason,
                        admission_policy_version, calibration_version,
                        review_case_fingerprint, created_at_epoch_millis
                    ) VALUES (
                        'legacy-review-case', 'legacy-review-candidate',
                        '${"4".repeat(64)}', 'legacy-review-source',
                        '${"3".repeat(64)}', '$LOCAL_LEARNER_ID', 'MATH',
                        '${LearningObservationInertReason.WEAK_CONFLICT_REQUIRES_REVIEW.name}',
                        '$LEARNER_MASTERY_ADMISSION_POLICY_VERSION',
                        '${historical.calibrationVersion}', '${"5".repeat(64)}', 10
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO mastery_evidence_review_resolution (
                        resolution_id, review_case_id, learner_id, subject,
                        decision, authority, reviewer_version,
                        review_evidence_fingerprint,
                        calibration_snapshot_fingerprint, idempotency_key,
                        resolution_fingerprint, decided_at_epoch_millis
                    ) VALUES (
                        'legacy-review-resolution', 'legacy-review-case',
                        '$LOCAL_LEARNER_ID', 'MATH', 'REJECT',
                        'INDEPENDENT_MODEL_REVIEW', 'legacy-reviewer-v1',
                        '${"6".repeat(64)}', '${historical.snapshotFingerprint}',
                        'legacy-review-resolution-idempotency',
                        '${"7".repeat(64)}', 11
                    )
                    """.trimIndent(),
                )
            }

            helper.runMigrationsAndValidate(
                version = 10,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_9_10),
            ).use { connection ->
                assertEquals(
                    27L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_calibration_snapshot",
                    ),
                )
                assertEquals(
                    1L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_knowledge_projection",
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_learning_event",
                    ),
                )
                assertEquals(
                    LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION,
                    connection.textForQuery(
                        """
                        SELECT calibration_version
                        FROM mastery_knowledge_projection
                        WHERE learner_id = '$LOCAL_LEARNER_ID'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    historical.oneHintScaleMicros,
                    connection.longForQuery(
                        """
                        SELECT one_hint_scale_micros
                        FROM mastery_calibration_snapshot
                        WHERE subject = 'MATH'
                          AND calibration_version =
                              '$LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION'
                          AND snapshot_fingerprint =
                              '${historical.snapshotFingerprint}'
                        """.trimIndent(),
                    ),
                )
                assertTrue(
                    connection.columnNames("mastery_calibration_snapshot")
                        .containsAll(
                            setOf(
                                "steady_minimum_observation_count",
                                "maximum_absolute_log_odds_micros",
                                "problem_family_mass_cap_micros",
                                "open_response_cap_micros",
                            ),
                        ),
                )
                assertEquals(
                    1L,
                    connection.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM mastery_evidence_review_case
                        WHERE review_case_id = 'legacy-review-case'
                          AND calibration_binding_status =
                              '${MasteryCalibrationBindingStatus.LEGACY_UNCALIBRATED.name}'
                          AND calibration_version IS NULL
                          AND calibration_profile_id IS NULL
                          AND calibration_snapshot_fingerprint IS NULL
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM mastery_evidence_review_resolution",
                    ),
                )
                assertEquals(
                    1L,
                    connection.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE
                        WHERE resolution_id = 'legacy-review-resolution'
                          AND review_case_id = 'legacy-review-case'
                          AND unverified_calibration_snapshot_fingerprint =
                              '${historical.snapshotFingerprint}'
                          AND resolution_fingerprint = '${"7".repeat(64)}'
                        """.trimIndent(),
                    ),
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            """
                            UPDATE $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE
                            SET decision = 'ACCEPT'
                            WHERE resolution_id = 'legacy-review-resolution'
                            """.trimIndent(),
                        )
                    }.isFailure,
                )
                connection.prepare("PRAGMA foreign_key_check").use { statement ->
                    assertTrue(!statement.step())
                }
                assertEquals(
                    1L,
                    connection.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM sqlite_master
                        WHERE type = 'trigger'
                          AND name =
                              'validate_mastery_evidence_review_resolution_calibration_insert'
                        """.trimIndent(),
                    ),
                )
                connection.execSQL(
                    "DROP TRIGGER IF EXISTS " +
                        "immutable_mastery_evidence_review_case_update",
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            """
                            UPDATE mastery_evidence_review_case
                            SET calibration_version =
                                '$LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION'
                            WHERE review_case_id = 'legacy-review-case'
                            """.trimIndent(),
                        )
                    }.isFailure,
                )
                connection.execSQL("PRAGMA foreign_keys = OFF")
                assertEquals(0L, connection.longForQuery("PRAGMA foreign_keys"))
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            """
                            INSERT INTO mastery_evidence_review_resolution (
                                resolution_id, review_case_id, learner_id, subject,
                                decision, authority, reviewer_version,
                                review_evidence_fingerprint,
                                calibration_snapshot_fingerprint, idempotency_key,
                                resolution_fingerprint, decided_at_epoch_millis
                            ) VALUES (
                                'swapped-resolution', 'legacy-review-case',
                                '$LOCAL_LEARNER_ID', 'MATH', 'REJECT',
                                'INDEPENDENT_MODEL_REVIEW', 'reviewer-v1',
                                '${"8".repeat(64)}', '${historical.snapshotFingerprint}',
                                'swapped-resolution-idempotency',
                                '${"9".repeat(64)}', 12
                            )
                            """.trimIndent(),
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        connection.execSQL(
                            """
                            UPDATE mastery_calibration_snapshot
                            SET one_hint_scale_micros = 1
                            """.trimIndent(),
                        )
                    }.isFailure,
                )
            }
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { 30L },
            ).use { store ->
                assertTrue(
                    store.readPendingEvidenceReviews(
                        learnerId = LOCAL_LEARNER_ID,
                        subject = com.tingyun.smartmistakebook.core.model.SubjectKind.MATH,
                        limit = 8,
                    ).none { it.reviewCaseId == "legacy-review-case" },
                )
                listOf(
                    LearningEvidenceReviewDecision.ACCEPT,
                    LearningEvidenceReviewDecision.REJECT,
                ).forEachIndexed { index, decision ->
                    assertEquals(
                        LearningEvidenceReviewWriteDisposition.CONFLICT,
                        store.resolveEvidenceReview(
                            learnerId = LOCAL_LEARNER_ID,
                            command =
                                ResolveLearningEvidenceReviewCommand(
                                    reviewCaseId = "legacy-review-case",
                                    decision = decision,
                                    authority =
                                        LearningEvidenceReviewAuthority
                                            .INDEPENDENT_MODEL_REVIEW,
                                    reviewerVersion = "legacy-reviewer-v1",
                                    reviewEvidenceFingerprint =
                                        (if (index == 0) "6" else "7").repeat(64),
                                    idempotencyKey = "legacy-review-resolution-$index",
                                    decidedAtEpochMillis = 30L,
                                ),
                        ).disposition,
                    )
                }
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                listOf(
                    "mastery_evidence_review_resolution",
                    "mastery_learning_event",
                    "mastery_presentation_node_budget",
                    "mastery_problem_family_node_budget",
                ).forEach { table ->
                    sqlite.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0L, cursor.getLong(0))
                    }
                }
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM mastery_knowledge_projection",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
            }
            val reopened =
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                )
            try {
                assertEquals(
                    1,
                    reopened.displayDao().readExactKnowledgeProjections(
                        learnerId = LOCAL_LEARNER_ID,
                        subject = "MATH",
                        stableNodeFingerprints = listOf(stableNodeFingerprint),
                    ).size,
                )
            } finally {
                reopened.close()
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration10To11PreservesLedgerStateAndAddsEmptyProjectionGenerationTables() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v10-v11.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            helper.createDatabase(10).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO mastery_ledger_sequence(
                        learner_id,
                        last_allocated_sequence
                    ) VALUES ('$LOCAL_LEARNER_ID', 41)
                    """.trimIndent(),
                )
            }

            helper.runMigrationsAndValidate(
                version = 11,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_10_11),
            ).use { connection ->
                assertEquals(
                    41L,
                    connection.longForQuery(
                        """
                        SELECT last_allocated_sequence
                        FROM mastery_ledger_sequence
                        WHERE learner_id = '$LOCAL_LEARNER_ID'
                        """.trimIndent(),
                    ),
                )
                assertTrue(
                    connection.textSetForQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table'",
                    ).containsAll(
                        setOf(
                            "mastery_projection_generation",
                            "mastery_projection_shadow",
                            "mastery_subject_digest_shadow",
                            "mastery_presentation_node_budget_shadow",
                            "mastery_problem_family_node_budget_shadow",
                        ),
                    ),
                )
                assertTrue(
                    connection.textSetForQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index'",
                    ).containsAll(
                        setOf(
                            "index_mastery_projection_generation_state_generation_id",
                            "index_mastery_projection_generation_lease_expires_at_epoch_millis",
                            "index_mastery_projection_shadow_generation_id_learner_id_subject",
                            "index_mastery_projection_shadow_generation_id_stable_node_identity_fingerprint",
                            "index_mastery_subject_digest_shadow_generation_id_learner_id",
                            "index_mastery_presentation_node_budget_shadow_generation_id_learner_id_subject",
                            "index_mastery_problem_family_node_budget_shadow_generation_id_learner_id_subject",
                        ),
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        """
                        SELECT
                            (SELECT COUNT(*) FROM mastery_projection_generation) +
                            (SELECT COUNT(*) FROM mastery_projection_shadow) +
                            (SELECT COUNT(*) FROM mastery_subject_digest_shadow) +
                            (SELECT COUNT(*) FROM mastery_presentation_node_budget_shadow) +
                            (SELECT COUNT(*) FROM mastery_problem_family_node_budget_shadow)
                        """.trimIndent(),
                    ),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration11To12AddsEmptyImmutableOpenResponseOwnerReceipts() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v11-v12.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            helper.createDatabase(11).use { connection ->
                connection.execSQL(
                    """
                    INSERT INTO mastery_ledger_sequence(
                        learner_id,
                        last_allocated_sequence
                    ) VALUES ('$LOCAL_LEARNER_ID', 52)
                    """.trimIndent(),
                )
            }

            helper.runMigrationsAndValidate(
                version = 12,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_11_12),
            ).use { connection ->
                assertEquals(
                    52L,
                    connection.longForQuery(
                        """
                        SELECT last_allocated_sequence
                        FROM mastery_ledger_sequence
                        WHERE learner_id = '$LOCAL_LEARNER_ID'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                assertTrue(
                    connection.textSetForQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index'",
                    ).containsAll(
                        setOf(
                            "index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_candidate_idempotency_key",
                            "index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_lineage_parent_fingerprint",
                            "index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_logical_attempt_fingerprint",
                            "index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_learner_id_subject_received_at_epoch_millis",
                        ),
                    ),
                )
                assertTrue(
                    connection.textSetForQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'trigger'",
                    ).containsAll(
                        setOf(
                            "immutable_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_update",
                            "immutable_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_delete",
                        ),
                    ),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration12To13DerivesOnlyProvableOpenResponseRevisionOrdinals() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "learner-mastery-v12-v13.mastery-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = LearnerMasteryRoomDatabase::class,
                )
            helper.createDatabase(12).use { connection ->
                connection.execSQL("PRAGMA foreign_keys = OFF")
                connection.insertV12OpenResponseReceipt(
                    receipt = "receipt-root",
                    logicalAttempt = "logical-linear",
                    lineageParent = "logical-linear",
                    idempotencyKey = "candidate-root",
                    revisionOf = null,
                )
                connection.insertV12OpenResponseReceipt(
                    receipt = "receipt-child",
                    logicalAttempt = "logical-linear",
                    lineageParent = "candidate-root",
                    idempotencyKey = "candidate-child",
                    revisionOf = "candidate-root",
                )
                connection.insertV12OpenResponseReceipt(
                    receipt = "receipt-detached",
                    logicalAttempt = "logical-detached",
                    lineageParent = "missing-parent",
                    idempotencyKey = "candidate-detached",
                    revisionOf = "missing-parent",
                )
            }

            helper.runMigrationsAndValidate(
                version = 13,
                migrations = listOf(LEARNER_MASTERY_MIGRATION_12_13),
            ).use { connection ->
                assertEquals(
                    0L,
                    connection.longForQuery(
                        """
                        SELECT revision_ordinal
                        FROM $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE
                        WHERE candidate_idempotency_key = 'candidate-root'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    1L,
                    connection.longForQuery(
                        """
                        SELECT revision_ordinal
                        FROM $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE
                        WHERE candidate_idempotency_key = 'candidate-child'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    1L,
                    connection.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE
                        WHERE candidate_idempotency_key = 'candidate-detached'
                          AND revision_ordinal IS NULL
                        """.trimIndent(),
                    ),
                )
                assertTrue(
                    connection.textSetForQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index'",
                    ).contains(
                        "index_" +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE +
                            "_logical_attempt_fingerprint_revision_ordinal",
                    ),
                )
                assertTrue(
                    connection.textSetForQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'trigger'",
                    ).containsAll(
                        setOf(
                            "immutable_" +
                                LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE +
                                "_update",
                            "immutable_" +
                                LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE +
                                "_delete",
                        ),
                    ),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun allSupportedLegacyChainsReachV10WithoutPrematureAttemptTriggers() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            listOf(1, 6, 7, 8).forEach { startVersion ->
                val databaseName =
                    "learner-mastery-v$startVersion-v10-chain.mastery-test.db"
                context.deleteDatabase(databaseName)
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = LearnerMasteryRoomDatabase::class,
                    )
                helper.createDatabase(startVersion).use {}
                val migrations: List<Migration> =
                    when (startVersion) {
                        1 ->
                            listOf(
                                LEARNER_MASTERY_MIGRATION_1_2,
                                LEARNER_MASTERY_MIGRATION_2_3,
                                LEARNER_MASTERY_MIGRATION_3_4,
                                LEARNER_MASTERY_MIGRATION_4_5,
                                LEARNER_MASTERY_MIGRATION_5_6,
                                LEARNER_MASTERY_MIGRATION_6_7,
                                LEARNER_MASTERY_MIGRATION_7_8,
                                LEARNER_MASTERY_MIGRATION_8_9,
                                LEARNER_MASTERY_MIGRATION_9_10,
                            )
                        6 ->
                            listOf(
                                LEARNER_MASTERY_MIGRATION_6_7,
                                LEARNER_MASTERY_MIGRATION_7_8,
                                LEARNER_MASTERY_MIGRATION_8_9,
                                LEARNER_MASTERY_MIGRATION_9_10,
                            )
                        7 ->
                            listOf(
                                LEARNER_MASTERY_MIGRATION_7_8,
                                LEARNER_MASTERY_MIGRATION_8_9,
                                LEARNER_MASTERY_MIGRATION_9_10,
                            )
                        else ->
                            listOf(
                                LEARNER_MASTERY_MIGRATION_8_9,
                                LEARNER_MASTERY_MIGRATION_9_10,
                            )
                    }
                helper.runMigrationsAndValidate(
                    version = 10,
                    migrations = migrations,
                ).use { connection ->
                    assertEquals(
                        27L,
                        connection.longForQuery(
                            "SELECT COUNT(*) FROM mastery_calibration_snapshot",
                        ),
                    )
                    assertEquals(
                        0L,
                        connection.longForQuery(
                            "SELECT COUNT(*) FROM " +
                                LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
                        ),
                    )
                    assertTrue(
                        connection.textSetForQuery(
                            "SELECT name FROM sqlite_master WHERE type = 'trigger'",
                        ).containsAll(
                            setOf(
                                "immutable_" +
                                    LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                                    "_update",
                                "immutable_" +
                                    LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                                    "_delete",
                            ),
                        ),
                    )
                }
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private fun SQLiteConnection.columnNames(
    tableName: String,
): Set<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteConnection.longForQuery(
    sql: String,
): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row for scalar query" }
        statement.getLong(0)
    }

private fun SQLiteConnection.textForQuery(
    sql: String,
): String =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row for scalar query" }
        statement.getText(0)
    }

private fun SQLiteConnection.textSetForQuery(
    sql: String,
): Set<String> =
    prepare(sql).use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(0))
        }
    }

private fun SQLiteConnection.insertV12OpenResponseReceipt(
    receipt: String,
    logicalAttempt: String,
    lineageParent: String,
    idempotencyKey: String,
    revisionOf: String?,
) {
    val revisionSql = revisionOf?.let { "'$it'" } ?: "NULL"
    execSQL(
        """
        INSERT INTO $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE(
            receipt_fingerprint,
            canonical_fingerprint,
            logical_attempt_fingerprint,
            lineage_parent_fingerprint,
            candidate_id,
            candidate_canonical_fingerprint,
            source_fact_id,
            review_case_id,
            review_case_fingerprint,
            learner_id,
            subject,
            scope_fingerprint,
            conversation_id,
            conversation_generation,
            conversation_state_version,
            question_document_id,
            question_revision_number,
            question_fingerprint,
            answer_fingerprint,
            evidence_request_id,
            presentation_fingerprint,
            problem_fingerprint,
            problem_family_fingerprint,
            turn_reference_id,
            turn_ordinal,
            turn_generation,
            mode_version,
            request_version,
            attempt_ordinal,
            hint_count,
            answer_was_revealed,
            model_task_request_id,
            model_response_schema_version,
            evaluator_request_version,
            candidate_idempotency_key,
            revision_of_candidate_idempotency_key,
            evidence_fingerprint,
            model_version,
            outcome,
            occurred_at_epoch_millis,
            received_at_epoch_millis
        ) VALUES (
            '$receipt',
            'canonical-$receipt',
            '$logicalAttempt',
            '$lineageParent',
            'candidate',
            'candidate-canonical',
            'source',
            'review',
            'review-fingerprint',
            '$LOCAL_LEARNER_ID',
            'MATH',
            'scope',
            'conversation',
            1,
            1,
            'question',
            1,
            'question-fingerprint',
            'answer-fingerprint',
            'evidence-request',
            'presentation',
            'problem',
            'problem-family',
            'turn',
            1,
            1,
            1,
            1,
            1,
            0,
            0,
            'model-task',
            1,
            1,
            '$idempotencyKey',
            $revisionSql,
            'evidence',
            'model',
            'INCORRECT',
            100,
            100
        )
        """.trimIndent(),
    )
}
