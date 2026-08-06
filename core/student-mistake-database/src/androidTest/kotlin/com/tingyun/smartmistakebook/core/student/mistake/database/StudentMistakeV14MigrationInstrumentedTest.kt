package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeV14MigrationInstrumentedTest {
    @Test
    fun migration14To15DoesNotAuthorizeAPreexistingPresentation() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v14-v15-fence.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(14).use { connection ->
                    connection.seedV14AnswerablePresentationWithoutFence()
                    connection.installV14ProductionOpenGuards()
                }

                helper.runMigrationsAndValidate(
                    version = 15,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_14_15),
                ).use { connection ->
                    assertEquals(
                        0L,
                        connection.readV15Long(
                            "SELECT COUNT(*) FROM student_trusted_review_presentation_fence",
                        ),
                    )
                    assertEquals(
                        1L,
                        connection.readV15Long(
                            "SELECT COUNT(*) FROM student_trusted_review_answer_rule",
                        ),
                    )
                    assertEquals(
                        1L,
                        connection.readV15Long(
                            "SELECT COUNT(*) FROM student_review_session WHERE state = 'ACTIVE'",
                        ),
                    )
                }

                val database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    val owner =
                        StudentTrustedReviewAnswerOwner(
                            learnerId = V14_LEARNER_ID,
                            persistence = database.trustedReviewAnswerDao(),
                            nowEpochMillis = { 1_100L },
                            newReceiptId = { "lease-after-v15-migration" },
                        )
                    assertNull(owner.issueCurrentLease())
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }

    @Test
    fun exactPendingFenceBindsOnceAndOnlyAnUnassistedAttemptPromotesIt() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v15-fence-transaction.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(14).use { connection ->
                    connection.seedV14AnswerablePresentationWithoutFence()
                    connection.installV14ProductionOpenGuards()
                }
                helper.runMigrationsAndValidate(
                    version = 15,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_14_15),
                ).use { connection ->
                    connection.insertExactPendingV15Fence()
                }

                val database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    val persistence = database.trustedReviewAnswerDao()
                    val owner =
                        StudentTrustedReviewAnswerOwner(
                            learnerId = V14_LEARNER_ID,
                            persistence = persistence,
                            nowEpochMillis = { 100L },
                            newReceiptId = { "lease-v15-exact" },
                        )
                    val lease = requireNotNull(owner.issueCurrentLease())
                    assertEquals("presentation-v14", lease.presentationId)
                    assertEquals("error-book-v14", lease.errorBookEntryId)
                    assertEquals(1L, lease.expectedSessionVersion)
                    assertTrue(
                        StudentTrustedReviewAnswerLease::class.java.declaredMethods.none {
                            it.name.contains("answerRule", ignoreCase = true)
                        },
                    )

                    val recreatedOwner =
                        StudentTrustedReviewAnswerOwner(
                            learnerId = V14_LEARNER_ID,
                            persistence = persistence,
                            nowEpochMillis = { 110L },
                            newReceiptId = { "lease-v15-recreated" },
                        )
                    assertNull(recreatedOwner.issueCurrentLease())
                    assertNull(
                        StudentTrustedReviewAnswerOwner(
                            learnerId = "other-learner",
                            persistence = persistence,
                            nowEpochMillis = { 110L },
                            newReceiptId = { "lease-v15-other-learner" },
                        ).issueCurrentLease(),
                    )

                    val attempt =
                        requireNotNull(
                            persistence.claimAttempt(
                                learnerId = V14_LEARNER_ID,
                                leaseReceiptId = lease.receiptId,
                                leaseCanonicalFingerprint = lease.canonicalFingerprint,
                                submittedAtEpochMillis = 110L,
                            ),
                        )
                    assertEquals(0, attempt.hintCount)
                    assertTrue(!attempt.answerWasRevealed)
                    assertNull(
                        persistence.claimAttempt(
                            learnerId = V14_LEARNER_ID,
                            leaseReceiptId = lease.receiptId,
                            leaseCanonicalFingerprint = lease.canonicalFingerprint,
                            submittedAtEpochMillis = 111L,
                        ),
                    )
                } finally {
                    database.close()
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        "STRONG_EVIDENCE_ELIGIBLE",
                        sqlite.readV15Text(
                            "SELECT status FROM student_trusted_review_presentation_fence",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.readV15AndroidLong(
                            "SELECT COUNT(*) FROM student_trusted_review_lease_receipt",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.readV15AndroidLong(
                            "SELECT COUNT(*) FROM student_trusted_review_attempt_receipt",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private fun SQLiteConnection.seedV14AnswerablePresentationWithoutFence() {
    execSQL(
        """
        INSERT INTO student_problem_document (
            problem_id, learner_id, subject, primary_practice_unit_id,
            current_revision_id, error_book_entry_id, lifecycle_state,
            archived_at_epoch_millis, tombstoned_at_epoch_millis,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            'problem-v14', '$V14_LEARNER_ID', 'MATH', 'practice-v14',
            'revision-v14', 'error-book-v14', 'ACTIVE',
            NULL, NULL, 10, 10
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
            'revision-v14', 'problem-v14', 1, 'question', 'question',
            NULL, '${"a".repeat(64)}', 10, 10
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
            'practice-v14', 'problem-v14', 'revision-v14', 'PROBLEM', 'question',
            'family-v14', 60, NULL, '0:', 10, 10
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_collection (
            practice_unit_id, problem_id, learner_id, mistake_state, favorite,
            added_at_epoch_millis, archived_at_epoch_millis, trashed_at_epoch_millis,
            changed_at_epoch_millis
        ) VALUES (
            'practice-v14', 'problem-v14', '$V14_LEARNER_ID', 'ACTIVE', 0,
            10, NULL, NULL, 10
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_review_plan (
            plan_id, plan_canonical_fingerprint, learner_id, local_day_epoch_day,
            time_zone_id, time_budget_seconds, generated_at_epoch_millis, planner_version
        ) VALUES (
            'plan-v14', '${"b".repeat(64)}', '$V14_LEARNER_ID', 1,
            'Asia/Shanghai', 600, 20, 'planner-v14'
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_review_queue_item (
            queue_item_id, plan_id, learner_id, practice_unit_id, basis_revision_id,
            scheduled_order, estimated_duration_seconds, reason_codes_wire,
            source_evidence_event_kind, source_evidence_event_id,
            source_evidence_sequence, source_evidence_canonical_fingerprint,
            state, created_at_epoch_millis, state_changed_at_epoch_millis
        ) VALUES (
            'queue-v14', 'plan-v14', '$V14_LEARNER_ID', 'practice-v14', 'revision-v14',
            0, 60, '1:7:MISTAKE',
            NULL, NULL, NULL, NULL,
            'PRESENTED', 20, 30
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_review_session (
            session_id, session_canonical_fingerprint, learner_id, plan_id,
            active_learner_id, state, session_version, current_queue_item_id,
            current_presentation_id, started_at_epoch_millis,
            updated_at_epoch_millis, completed_at_epoch_millis
        ) VALUES (
            'session-v14', '${"c".repeat(64)}', '$V14_LEARNER_ID', 'plan-v14',
            '$V14_LEARNER_ID', 'ACTIVE', 1, 'queue-v14',
            'presentation-v14', 30, 30, NULL
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_trusted_review_answer_rule (
            answer_rule_id, learner_id, problem_id, basis_revision_id,
            question_generation, question_version, rule_kind,
            accepted_values_wire, correct_values_wire, expected_numeric_value,
            absolute_tolerance, expected_unit, answer_spec_version, provenance_kind,
            provenance_reference_id, provenance_canonical_fingerprint,
            rule_canonical_fingerprint, admitted_at_epoch_millis
        ) VALUES (
            'rule-v14', '$V14_LEARNER_ID', 'problem-v14', 'revision-v14',
            1, 'question-v14', 'NUMERIC',
            NULL, NULL, '2',
            '0', NULL, 'answer-v14', 'DETERMINISTIC_VALIDATION',
            'validator-v14', '${"d".repeat(64)}',
            '${V14_RULE.canonicalFingerprint}', 25
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.insertExactPendingV15Fence() {
    execSQL(
        """
        INSERT INTO student_trusted_review_presentation_fence (
            fence_id, fence_canonical_fingerprint, learner_id, plan_id, session_id,
            queue_item_id, presentation_id, problem_id, basis_revision_id,
            practice_unit_id, error_book_entry_id, status,
            bound_lease_receipt_id, bound_lease_canonical_fingerprint,
            eligible_attempt_receipt_id, eligible_at_epoch_millis, created_at_epoch_millis
        ) VALUES (
            'fence-v15', '${"f".repeat(64)}', '$V14_LEARNER_ID', 'plan-v14', 'session-v14',
            'queue-v14', 'presentation-v14', 'problem-v14', 'revision-v14',
            'practice-v14', 'error-book-v14', 'PENDING',
            NULL, NULL, NULL, NULL, 30
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.installV14ProductionOpenGuards() {
    createStudentCutoverAndMigrationLedgerImmutabilityTriggers(this)
    createStudentImportSnapshotImmutabilityTriggers(this)
    createStudentProblemOrganizationImmutabilityTriggers(this)
    createStudentProblemIdentityReceiptImmutabilityTriggers(this)
    createStudentReviewReceiptImmutabilityTriggers(this)
}

private fun SQLiteConnection.readV15Long(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row for query: $sql" }
        statement.getLong(0)
    }

private fun SQLiteDatabase.readV15AndroidLong(sql: String): Long =
    rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one row for query: $sql" }
        cursor.getLong(0)
    }

private fun SQLiteDatabase.readV15Text(sql: String): String =
    rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst()) { "Expected one row for query: $sql" }
        cursor.getString(0)
    }

private const val V14_LEARNER_ID = "learner-v14"
private val V14_RULE =
    StudentTrustedReviewAnswerRule.Numeric(
        expectedValue = BigDecimal("2"),
        absoluteTolerance = BigDecimal.ZERO,
        expectedUnit = null,
        answerSpecVersion = "answer-v14",
    )
