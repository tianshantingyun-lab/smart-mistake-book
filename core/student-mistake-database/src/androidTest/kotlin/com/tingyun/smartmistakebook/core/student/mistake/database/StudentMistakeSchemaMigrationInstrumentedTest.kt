package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeSchemaMigrationInstrumentedTest {
    @Test
    fun migration8To9LeavesV8LedgerUnverifiedAndDoesNotInventSemanticSnapshots() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v8-v9.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = StudentMistakeRoomDatabase::class,
                )

            helper.createDatabase(8).use { connection ->
                connection.seedCompletedV7MigrationLedger()
                connection.execSQL(
                    """
                    UPDATE student_mistake_migration_checkpoint
                    SET destination_ledger_version = 1
                    WHERE migration_id = '$V7_MIGRATION_ID'
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    INSERT INTO student_mistake_migration_destination_record (
                        migration_id, source_page_canonical_fingerprint,
                        page_record_ordinal, committed_at_epoch_millis,
                        problem_id, revision_number, revision_id,
                        destination_record_canonical_fingerprint
                    ) VALUES (
                        '$V7_MIGRATION_ID', '$V7_SOURCE_PAGE_FINGERPRINT',
                        0, $V7_COMMITTED_AT, '$V7_PROBLEM_ID', 1,
                        '$V7_REVISION_ID', '${"6".repeat(64)}'
                    )
                    """.trimIndent(),
                )
            }
            helper.runMigrationsAndValidate(
                version = 9,
                migrations = listOf(STUDENT_MISTAKE_MIGRATION_8_9),
            ).use { connection ->
                assertEquals(
                    0L,
                    connection.longForQuery(
                        "SELECT COUNT(*) FROM student_problem_import_semantic_snapshot",
                    ),
                )
                assertEquals(
                    1L,
                    connection.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM student_mistake_migration_destination_record
                        WHERE migration_id = '$V7_MIGRATION_ID'
                          AND import_snapshot_canonical_fingerprint IS NULL
                        """.trimIndent(),
                    ),
                )
                assertTrue(
                    connection.textSetForQuery(
                        """
                        SELECT name
                        FROM sqlite_master
                        WHERE type = 'trigger'
                        """.trimIndent(),
                    ).containsAll(
                        STUDENT_CUTOVER_IMMUTABILITY_TRIGGER_NAMES +
                            STUDENT_IMPORT_SNAPSHOT_IMMUTABILITY_TRIGGER_NAMES,
                    ),
                )
            }

            assertNull(
                StudentMistakeCutoverControlPortFactory
                    .openForTest(context, databaseName)
                    .use { it.recomputeCompletedMigrationLedger(V7_MIGRATION_ID) },
            )
            StudentMistakeMigrationPortFactory.openForTest(context, databaseName).use { migration ->
                migration.applyPage(
                    ApplyStudentMistakeMigrationPageCommand(
                        migrationId = V8_EXACT_MIGRATION_ID,
                        sourceDatabaseCanonicalFingerprint = "d".repeat(64),
                        sourcePageCanonicalFingerprint = "e".repeat(64),
                        expectedCheckpointCanonicalFingerprint = null,
                        afterExclusive = null,
                        records = listOf(v7ExactMigrationRecord()),
                        isLastPage = true,
                        appliedAtEpochMillis = 300,
                    ),
                )
            }
            val exact =
                StudentMistakeCutoverControlPortFactory
                    .openForTest(context, databaseName)
                    .use {
                        checkNotNull(
                            it.recomputeCompletedMigrationLedger(V8_EXACT_MIGRATION_ID),
                        )
                    }
            assertEquals(3, exact.destinationLedgerVersion)
            assertEquals(1L, exact.immutableImportSnapshotCount)
            assertEquals(1L, exact.legacySemanticSnapshotCount)
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun migration7To8AddsEmptyAppendOnlyStudentCutoverState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v7-v8.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = StudentMistakeRoomDatabase::class,
            )

        helper.createDatabase(7).use {}
        helper.runMigrationsAndValidate(
            version = 8,
            migrations = listOf(STUDENT_MISTAKE_MIGRATION_7_8),
        ).use { connection ->
            assertEquals(
                0L,
                connection.longForQuery("SELECT COUNT(*) FROM student_cutover_fence"),
            )
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_cutover_completion_receipt",
                ),
            )
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_mistake_migration_destination_record",
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
                connection.columnNames("student_cutover_fence"),
            )
            assertEquals(
                setOf(
                    "singleton_key",
                    "cutover_generation",
                    "cutover_intent_fingerprint",
                    "authority_fence_fingerprint",
                    "receipt_fingerprint",
                ),
                connection.columnNames("student_cutover_completion_receipt"),
            )
            assertTrue(
                connection.textSetForQuery(
                    """
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'trigger'
                    """.trimIndent(),
                ).containsAll(STUDENT_CUTOVER_IMMUTABILITY_TRIGGER_NAMES),
            )
            assertTrue(
                runCatching {
                    connection.execSQL(
                        """
                        INSERT INTO student_cutover_fence (
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
    fun migration7To8PreservesInterleavedNonEmptyV7LedgerAsUnverified() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v7-v8-ledger.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = StudentMistakeRoomDatabase::class,
            )

        helper.createDatabase(7).use { connection ->
            connection.seedCompletedV7MigrationLedger()
        }
        helper.runMigrationsAndValidate(
            version = 8,
            migrations = listOf(STUDENT_MISTAKE_MIGRATION_7_8),
        ).use { connection ->
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_mistake_migration_destination_record",
                ),
            )
            assertEquals(
                1L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_mistake_migration_checkpoint",
                ),
            )
            assertEquals(
                1L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_mistake_migration_receipt",
                ),
            )
            assertEquals(
                0L,
                connection.longForQuery(
                    """
                    SELECT destination_ledger_version
                    FROM student_mistake_migration_checkpoint
                    WHERE migration_id = '$V7_MIGRATION_ID'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                2L,
                connection.longForQuery(
                    """
                    SELECT COUNT(*)
                    FROM student_problem_revision
                    WHERE revision_id IN ('$V7_REVISION_ID', '$V7_NATIVE_REVISION_ID')
                    """.trimIndent(),
                ),
            )
            assertSqlFailsWithMessage(
                connection,
                """
                INSERT INTO student_mistake_migration_receipt (
                    migration_id, source_page_canonical_fingerprint,
                    imported_record_count, result_last_committed_at_epoch_millis,
                    result_last_problem_id, result_last_revision_number,
                    result_last_revision_id, result_total_record_count,
                    result_completed, checkpoint_canonical_fingerprint,
                    receipt_canonical_fingerprint, applied_at_epoch_millis
                ) VALUES (
                    '$V7_MIGRATION_ID', '${"e".repeat(64)}',
                    0, $V7_COMMITTED_AT, '$V7_PROBLEM_ID', 1,
                    '$V7_REVISION_ID', 1, 1,
                    '${"f".repeat(64)}', '${"1".repeat(64)}', 300
                )
                """.trimIndent(),
                "student migration ledger is terminal",
            )
            assertSqlFailsWithMessage(
                connection,
                """
                INSERT INTO student_mistake_migration_destination_record (
                    migration_id, source_page_canonical_fingerprint,
                    page_record_ordinal, committed_at_epoch_millis,
                    problem_id, revision_number, revision_id,
                    destination_record_canonical_fingerprint
                ) VALUES (
                    '$V7_MIGRATION_ID', '${"e".repeat(64)}', 0,
                    $V7_COMMITTED_AT, '$V7_PROBLEM_ID', 1, '$V7_REVISION_ID',
                    '${"f".repeat(64)}'
                )
                """.trimIndent(),
                "student migration ledger is terminal",
            )
        }

        val legacyDigest =
            StudentMistakeCutoverControlPortFactory
                .openForTest(context, databaseName)
                .use { port ->
                    port.recomputeCompletedMigrationLedger(V7_MIGRATION_ID)
                }
        assertNull(legacyDigest)

        StudentMistakeMigrationPortFactory.openForTest(context, databaseName).use { migration ->
            migration.applyPage(
                ApplyStudentMistakeMigrationPageCommand(
                    migrationId = V8_EXACT_MIGRATION_ID,
                    sourceDatabaseCanonicalFingerprint = "d".repeat(64),
                    sourcePageCanonicalFingerprint = "e".repeat(64),
                    expectedCheckpointCanonicalFingerprint = null,
                    afterExclusive = null,
                    records = listOf(v7ExactMigrationRecord()),
                    isLastPage = true,
                    appliedAtEpochMillis = 300,
                ),
            )
        }
        val exactDigest =
            StudentMistakeCutoverControlPortFactory
                .openForTest(context, databaseName)
                .use { port ->
                    checkNotNull(
                        port.recomputeCompletedMigrationLedger(V8_EXACT_MIGRATION_ID),
                    )
                }
        assertEquals(1L, exactDigest.migratedRecordCount)
        assertNull(
            StudentMistakeCutoverControlPortFactory
                .openForTest(context, databaseName)
                .use { port ->
                    port.recomputeCompletedMigrationLedger(V7_MIGRATION_ID)
                },
        )
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun migration6To7AddsEmptyStudentOwnedCaptureHandoffOutbox() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v6-v7.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = StudentMistakeRoomDatabase::class,
            )

        helper.createDatabase(6).use {}
        helper.runMigrationsAndValidate(
            version = 7,
            migrations = listOf(STUDENT_MISTAKE_MIGRATION_6_7),
        ).use { connection ->
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_capture_save_handoff",
                ),
            )
            assertTrue(
                setOf(
                    "intent_id",
                    "source_kind",
                    "source_canonical_fingerprint",
                    "learner_id",
                    "draft_id",
                    "target_revision_id",
                    "target_canonical_fingerprint",
                    "acknowledged_at_epoch_millis",
                ).all(connection.columnNames("student_capture_save_handoff")::contains),
            )
        }
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun migration5To6PreservesSearchAndRefusesToGuessLegacyPresentedSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v5-v6.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = StudentMistakeRoomDatabase::class,
            )

        helper.createDatabase(5).use { connection ->
            connection.seedLegacyPresentedReview()
        }
        helper.runMigrationsAndValidate(
            version = 6,
            migrations = listOf(STUDENT_MISTAKE_MIGRATION_5_6),
        ).use { connection ->
            assertEquals(
                setOf(
                    "rowid",
                    "revision_id",
                    "source_canonical_fingerprint",
                    "normalized_text",
                    "tokenized_text",
                    "indexed_at_epoch_millis",
                ),
                connection.columnNames("student_problem_search_document"),
            )
            assertEquals(
                "函数",
                connection.textForQuery(
                    """
                    SELECT normalized_text
                    FROM student_problem_search_document
                    WHERE revision_id = 'revision-legacy'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "READY",
                connection.textForQuery(
                    """
                    SELECT state
                    FROM student_problem_search_index_state
                    WHERE index_key = 'library-search-v1'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                1L,
                connection.longForQuery(
                    """
                    SELECT COUNT(*)
                    FROM student_problem_search_fts
                    WHERE student_problem_search_fts MATCH '函 数'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "PRESENTED",
                connection.textForQuery(
                    """
                    SELECT state
                    FROM student_review_queue_item
                    WHERE queue_item_id = 'queue-legacy'
                    """.trimIndent(),
                ),
            )
            assertEquals(0L, connection.longForQuery("SELECT COUNT(*) FROM student_review_session"))
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_review_transition_receipt",
                ),
            )
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_review_reveal_receipt",
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
                    setOf(
                        "immutable_student_review_transition_receipt_update",
                        "immutable_student_review_transition_receipt_delete",
                        "immutable_student_review_reveal_receipt_update",
                        "immutable_student_review_reveal_receipt_delete",
                        "room_fts_content_sync_student_problem_search_fts_BEFORE_UPDATE",
                        "room_fts_content_sync_student_problem_search_fts_BEFORE_DELETE",
                        "room_fts_content_sync_student_problem_search_fts_AFTER_UPDATE",
                        "room_fts_content_sync_student_problem_search_fts_AFTER_INSERT",
                    ),
                ),
            )
        }

        val store = StudentMistakeStoreFactory.openForTest(context, databaseName)
        try {
            val result =
                store.reviewSessionsForLearner("learner-legacy")
                    .startOrResume(
                        StartStudentReviewSessionCommand(
                            sessionId = "session-after-migration",
                            planId = "plan-legacy",
                            expectedPlanCanonicalFingerprint = "d".repeat(64),
                            startedAtEpochMillis = 300,
                        ),
                    )
            assertTrue(result is StartStudentReviewSessionResult.LegacyActivityConflict)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration4To6CreatesPreparingSearchWithoutSyntheticReviewFacts() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v4-v6.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = StudentMistakeRoomDatabase::class,
            )

        helper.createDatabase(4).close()
        helper.runMigrationsAndValidate(
            version = 6,
            migrations =
                listOf(
                    STUDENT_MISTAKE_MIGRATION_4_5,
                    STUDENT_MISTAKE_MIGRATION_5_6,
                ),
        ).use { connection ->
            assertEquals(
                "PREPARING",
                connection.textForQuery(
                    """
                    SELECT state
                    FROM student_problem_search_index_state
                    WHERE index_key = 'library-search-v1'
                    """.trimIndent(),
                ),
            )
            assertEquals(0L, connection.longForQuery("SELECT COUNT(*) FROM student_review_session"))
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_review_transition_receipt",
                ),
            )
            assertEquals(
                0L,
                connection.longForQuery(
                    "SELECT COUNT(*) FROM student_review_reveal_receipt",
                ),
            )
        }
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun migration5To6PreservesCoherentLegacyReviewAuthority() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v5-review-v6.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = context.getDatabasePath(databaseName),
                driver = AndroidSQLiteDriver(),
                databaseClass = StudentMistakeRoomDatabase::class,
            )

        helper.createDatabase(5).use { connection ->
            connection.seedLegacyPresentedReview()
            connection.seedLegacyReviewAuthorityReceipts()
        }
        helper.runMigrationsAndValidate(
            version = 6,
            migrations = listOf(STUDENT_MISTAKE_MIGRATION_5_6),
        ).use { connection ->
            assertEquals(
                "plan-legacy",
                connection.textForQuery(
                    """
                    SELECT plan_id
                    FROM student_review_transition_receipt
                    WHERE transition_id = 'transition-legacy'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "plan-legacy",
                connection.textForQuery(
                    """
                    SELECT plan_id
                    FROM student_review_reveal_receipt
                    WHERE reveal_id = 'reveal-legacy'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                1L,
                connection.longForQuery(
                    """
                    SELECT COUNT(*)
                    FROM student_review_session
                    WHERE session_id = 'session-legacy'
                      AND plan_id = 'plan-legacy'
                      AND learner_id = 'learner-legacy'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                0L,
                connection.longForQuery(
                    """
                    SELECT COUNT(*)
                    FROM sqlite_master
                    WHERE type = 'table' AND name GLOB '*_legacy_v5'
                    """.trimIndent(),
                ),
            )
        }
        context.deleteDatabase(databaseName)
        Unit
    }
}

private fun SQLiteConnection.seedLegacyPresentedReview() {
    execSQL(
        """
        INSERT INTO student_problem_document (
          problem_id, learner_id, subject, primary_practice_unit_id,
          current_revision_id, error_book_entry_id, lifecycle_state,
          archived_at_epoch_millis, tombstoned_at_epoch_millis,
          created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
          'problem-legacy', 'learner-legacy', 'MATH', 'practice-legacy',
          'revision-legacy', NULL, 'ACTIVE', NULL, NULL, 100, 100
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_revision (
          revision_id, problem_id, revision_number, title, stem_markdown,
          captured_question_document_wire, document_canonical_fingerprint,
          created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
          'revision-legacy', 'problem-legacy', 1, '函数', '函数',
          NULL, '${"a".repeat(64)}', 100, 100
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_practice_unit (
          practice_unit_id, problem_id, basis_revision_id, unit_kind, title,
          item_family_id, estimated_duration_seconds, source_bundle_id,
          part_ids_wire, created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
          'practice-legacy', 'problem-legacy', 'revision-legacy', 'WHOLE_PROBLEM',
          '函数', 'family-function', 60, NULL, '[]', 100, 100
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_collection (
          practice_unit_id, problem_id, learner_id, mistake_state, favorite,
          added_at_epoch_millis, archived_at_epoch_millis,
          trashed_at_epoch_millis, changed_at_epoch_millis
        ) VALUES (
          'practice-legacy', 'problem-legacy', 'learner-legacy', 'ACTIVE', 0,
          100, NULL, NULL, 100
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_search_document (
          revision_id, source_canonical_fingerprint, normalized_text,
          tokenized_text, indexed_at_epoch_millis
        ) VALUES (
          'revision-legacy', '${"b".repeat(64)}', '函数', '函 数', 120
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_search_index_state (
          index_key, state, after_revision_id, indexed_document_count,
          updated_at_epoch_millis
        ) VALUES (
          'library-search-v1', 'READY', 'revision-legacy', 1, 120
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_review_plan (
          plan_id, plan_canonical_fingerprint, learner_id, local_day_epoch_day,
          time_zone_id, time_budget_seconds, generated_at_epoch_millis,
          planner_version
        ) VALUES (
          'plan-legacy', '${"d".repeat(64)}', 'learner-legacy', 20000,
          'Asia/Shanghai', 900, 200, 'planner-v1'
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_review_queue_item (
          queue_item_id, plan_id, learner_id, practice_unit_id,
          basis_revision_id, scheduled_order, estimated_duration_seconds,
          reason_codes_wire, source_evidence_event_kind,
          source_evidence_event_id, source_evidence_sequence,
          source_evidence_canonical_fingerprint, state,
          created_at_epoch_millis, state_changed_at_epoch_millis
        ) VALUES (
          'queue-legacy', 'plan-legacy', 'learner-legacy', 'practice-legacy',
          'revision-legacy', 0, 60, 'recent-mistake', NULL, NULL, NULL, NULL,
          'PRESENTED', 200, 230
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.seedLegacyReviewAuthorityReceipts() {
    execSQL(
        """
        INSERT INTO student_review_session (
          session_id, session_canonical_fingerprint, learner_id, plan_id,
          active_learner_id, state, session_version, current_queue_item_id,
          current_presentation_id, started_at_epoch_millis, updated_at_epoch_millis,
          completed_at_epoch_millis
        ) VALUES (
          'session-legacy', '${"e".repeat(64)}', 'learner-legacy', 'plan-legacy',
          'learner-legacy', 'ACTIVE', 2, 'queue-legacy',
          'presentation-legacy', 210, 240, NULL
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_review_transition_receipt (
          transition_id, transition_canonical_fingerprint, learner_id, session_id,
          queue_item_id, action_kind, expected_session_version,
          resulting_session_version, presentation_id, observation_id, submission_id,
          response_form, response_canonical_fingerprint, verification_outcome,
          attempt_ordinal, hint_count, answer_was_revealed,
          verification_policy_version, elapsed_duration_millis,
          next_available_at_epoch_millis, next_due_at_epoch_millis,
          scheduling_policy_version, outbox_event_id, occurred_at_epoch_millis
        ) VALUES (
          'transition-legacy', '${"f".repeat(64)}', 'learner-legacy', 'session-legacy',
          'queue-legacy', 'RESPONSE', 1, 2, 'presentation-legacy',
          'observation-legacy', 'submission-legacy', 'CHOICE', '${"1".repeat(64)}',
          'INCORRECT', 1, 0, 0, 'verification-v1', 30000,
          300, 400, 'schedule-v1', 'outbox-legacy', 250
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_review_reveal_receipt (
          reveal_id, reveal_canonical_fingerprint, learner_id, session_id,
          queue_item_id, presentation_id, revealed_at_epoch_millis
        ) VALUES (
          'reveal-legacy', '${"2".repeat(64)}', 'learner-legacy', 'session-legacy',
          'queue-legacy', 'presentation-legacy', 260
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.seedCompletedV7MigrationLedger() {
    val revision = v7MigratedRevision()
    val checkpointFingerprint =
        CanonicalSha256("student-mistake-migration-checkpoint-v1")
            .field("migrationId", V7_MIGRATION_ID)
            .field("sourceDatabaseCanonicalFingerprint", V7_SOURCE_DATABASE_FINGERPRINT)
            .field("sourcePageCanonicalFingerprint", V7_SOURCE_PAGE_FINGERPRINT)
            .nullableField("lastCommittedAtEpochMillis", V7_COMMITTED_AT.toString())
            .nullableField("lastProblemId", V7_PROBLEM_ID)
            .nullableField("lastRevisionNumber", "1")
            .nullableField("lastRevisionId", V7_REVISION_ID)
            .field("importedRecordCount", 1L)
            .field("completed", true)
            .finish()
    val receiptFingerprint =
        CanonicalSha256("student-mistake-migration-receipt-v1")
            .field("migrationId", V7_MIGRATION_ID)
            .field("sourcePageCanonicalFingerprint", V7_SOURCE_PAGE_FINGERPRINT)
            .field("importedRecordCount", 1)
            .field("checkpointCanonicalFingerprint", checkpointFingerprint)
            .finish()
    execSQL(
        """
        INSERT INTO student_problem_document (
            problem_id, learner_id, subject, primary_practice_unit_id,
            current_revision_id, error_book_entry_id, lifecycle_state,
            archived_at_epoch_millis, tombstoned_at_epoch_millis,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            '$V7_PROBLEM_ID', 'learner-v7', 'MATH', 'unit-v7',
            '$V7_REVISION_ID', 'entry-v7', 'ACTIVE',
            NULL, NULL, $V7_COMMITTED_AT, $V7_COMMITTED_AT
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_revision (
            revision_id, problem_id, revision_number, title, stem_markdown,
            captured_question_document_wire, document_canonical_fingerprint,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            '${revision.revisionId}', '${revision.problemId}', ${revision.revisionNumber},
            '${revision.title}', '${revision.stemMarkdown}', NULL,
            '${revision.documentCanonicalFingerprint}',
            ${revision.createdAtEpochMillis}, ${revision.updatedAtEpochMillis}
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_practice_unit (
            practice_unit_id, problem_id, basis_revision_id, unit_kind, title,
            item_family_id, estimated_duration_seconds, source_bundle_id,
            part_ids_wire, created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            'unit-v7', '$V7_PROBLEM_ID', '$V7_REVISION_ID', 'WHOLE_PROBLEM',
            'Migrated v7 unit', 'family-v7', 60, NULL, '0:',
            $V7_COMMITTED_AT, $V7_COMMITTED_AT
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_document (
            problem_id, learner_id, subject, primary_practice_unit_id,
            current_revision_id, error_book_entry_id, lifecycle_state,
            archived_at_epoch_millis, tombstoned_at_epoch_millis,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            '$V7_NATIVE_PROBLEM_ID', 'learner-v7', 'MATH', 'unit-v7-native',
            '$V7_NATIVE_REVISION_ID', 'entry-v7-native', 'ACTIVE',
            NULL, NULL, 50, 50
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_revision (
            revision_id, problem_id, revision_number, title, stem_markdown,
            captured_question_document_wire, document_canonical_fingerprint,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            '$V7_NATIVE_REVISION_ID', '$V7_NATIVE_PROBLEM_ID', 1,
            'Native v7', 'Interleaved native stem.', NULL, '${"8".repeat(64)}',
            50, 50
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_mistake_migration_checkpoint (
            migration_id, source_database_canonical_fingerprint,
            last_committed_at_epoch_millis, last_problem_id,
            last_revision_number, last_revision_id, imported_record_count,
            completed, checkpoint_canonical_fingerprint,
            updated_at_epoch_millis
        ) VALUES (
            '$V7_MIGRATION_ID', '$V7_SOURCE_DATABASE_FINGERPRINT',
            $V7_COMMITTED_AT, '$V7_PROBLEM_ID', 1, '$V7_REVISION_ID',
            1, 1, '$checkpointFingerprint', $V7_APPLIED_AT
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_mistake_migration_receipt (
            migration_id, source_page_canonical_fingerprint,
            imported_record_count, result_last_committed_at_epoch_millis,
            result_last_problem_id, result_last_revision_number,
            result_last_revision_id, result_total_record_count,
            result_completed, checkpoint_canonical_fingerprint,
            receipt_canonical_fingerprint, applied_at_epoch_millis
        ) VALUES (
            '$V7_MIGRATION_ID', '$V7_SOURCE_PAGE_FINGERPRINT',
            1, $V7_COMMITTED_AT, '$V7_PROBLEM_ID', 1, '$V7_REVISION_ID',
            1, 1, '$checkpointFingerprint', '$receiptFingerprint', $V7_APPLIED_AT
        )
        """.trimIndent(),
    )
}

private fun v7MigratedRevision(): StudentProblemRevisionEntity =
    StudentProblemRevisionEntity(
        revisionId = V7_REVISION_ID,
        problemId = V7_PROBLEM_ID,
        revisionNumber = 1,
        title = "Migrated v7",
        stemMarkdown = "Legacy imported stem.",
        capturedQuestionDocumentWire = null,
        documentCanonicalFingerprint = "c".repeat(64),
        createdAtEpochMillis = V7_COMMITTED_AT,
        updatedAtEpochMillis = V7_COMMITTED_AT,
    )

private fun v7ExactMigrationRecord(): StudentMistakeMigrationRecord {
    val problem =
        StudentProblemRef(
            learnerId = "learner-v7",
            subject = SubjectKind.MATH,
            problemId = V7_PROBLEM_ID,
            practiceUnitId = "unit-v7",
        )
    val command =
        CommitStudentProblemCommand(
            revision =
                StudentProblemRevisionRef(
                    problem = problem,
                    revisionId = V7_REVISION_ID,
                    revisionNumber = 1,
                    documentCanonicalFingerprint = "c".repeat(64),
                ),
            title = "Migrated v7",
            stemMarkdown = "Legacy imported stem.",
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
            practiceUnitTitle = "Migrated v7 unit",
            itemFamilyId = "family-v7",
            estimatedDurationSeconds = 60,
            sourceBundleId = null,
            partIds = emptyList(),
            originalImages = emptyList(),
            committedAtEpochMillis = V7_COMMITTED_AT,
            errorBookEntryId = "entry-v7",
        )
    return StudentMistakeMigrationRecord(
        problem = command,
        collection =
            SetStudentProblemCollectionCommand(
                problem = problem,
                mistakeState = StudentMistakeEntryState.ACTIVE,
                favorite = false,
                changedAtEpochMillis = V7_COMMITTED_AT,
            ),
        importSemanticSnapshot =
            StudentMistakeImportSemanticSnapshot(
                problemCanonicalFingerprint = "7".repeat(64),
                revisionSourceType = "CAPTURE_CONFIRMED",
                revisionSourceReference = "capture:v7",
                answerSpecId = "answer-v7",
                answerSpecSnapshot = """{"answer":"B"}""",
                answerVerificationStatus = "VERIFIED",
                errorBookSourceKey = "error-book:v7",
                practiceUnitKey = "whole-problem",
                practiceUnitPromptMarkdown = "请选择正确结论。",
            ),
    )
}

private fun assertSqlFailsWithMessage(
    connection: SQLiteConnection,
    sql: String,
    expectedMessage: String,
) {
    val failure =
        checkNotNull(runCatching { connection.execSQL(sql) }.exceptionOrNull()) {
            "Expected SQL to fail: $sql"
        }
    assertTrue(
        "Expected SQL failure to contain '$expectedMessage', but was: $failure",
        generateSequence(failure) { it.cause }
            .any { it.message?.contains(expectedMessage) == true },
    )
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

private const val V7_MIGRATION_ID = "legacy-student-v7-terminal"
private const val V8_EXACT_MIGRATION_ID = "legacy-student-v8-exact"
private const val V7_PROBLEM_ID = "problem-v7-migrated"
private const val V7_REVISION_ID = "revision-v7-migrated"
private const val V7_NATIVE_PROBLEM_ID = "problem-v7-native"
private const val V7_NATIVE_REVISION_ID = "revision-v7-native"
private const val V7_COMMITTED_AT = 100L
private const val V7_APPLIED_AT = 200L
private val V7_SOURCE_DATABASE_FINGERPRINT = "a".repeat(64)
private val V7_SOURCE_PAGE_FINGERPRINT = "b".repeat(64)
