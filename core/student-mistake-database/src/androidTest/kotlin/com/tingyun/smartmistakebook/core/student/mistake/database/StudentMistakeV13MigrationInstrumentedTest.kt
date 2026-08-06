package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeV13MigrationInstrumentedTest {
    @Test
    fun migration13To14CreatesEmptyTrustedLedgersAndNeverPromotesLegacyAnswers() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName =
                "student-mistake-v13-v14-trusted-review.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(13).use { connection ->
                    connection.seedV13LegacyAnswerContent()
                    connection.installV13ProductionOpenGuards()
                }

                helper.runMigrationsAndValidate(
                    version = 14,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_13_14),
                ).use { connection ->
                    TRUSTED_REVIEW_TABLES.forEach { tableName ->
                        assertEquals(
                            "Migration must leave $tableName empty",
                            0L,
                            connection.readLong("SELECT COUNT(*) FROM `$tableName`"),
                        )
                    }
                    assertEquals(
                        "legacy verified answer",
                        connection.readText(
                            """
                            SELECT legacy_answer_spec_snapshot
                            FROM student_problem_import_semantic_snapshot
                            WHERE revision_id = 'revision-v13'
                            """.trimIndent(),
                        ),
                    )
                    assertEquals(
                        "model-only final answer",
                        connection.readText(
                            """
                            SELECT final_answer_markdown
                            FROM student_problem_solution_analysis
                            WHERE basis_revision_id = 'revision-v13'
                            """.trimIndent(),
                        ),
                    )
                }

                val database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    val owner =
                        StudentTrustedReviewAnswerOwner(
                            learnerId = "learner-v13",
                            persistence = database.trustedReviewAnswerDao(),
                            nowEpochMillis = { 1_000L },
                            newReceiptId = { "lease-v13" },
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
}

private fun SQLiteConnection.seedV13LegacyAnswerContent() {
    execSQL(
        """
        INSERT INTO student_problem_document (
            problem_id, learner_id, subject, primary_practice_unit_id,
            current_revision_id, error_book_entry_id, lifecycle_state,
            archived_at_epoch_millis, tombstoned_at_epoch_millis,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            'problem-v13', 'learner-v13', 'MATH', 'practice-v13',
            'revision-v13', 'mistake-v13', 'ACTIVE',
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
            'revision-v13', 'problem-v13', 1, 'legacy answer', 'legacy question',
            NULL, '${"a".repeat(64)}', 10, 10
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_import_semantic_snapshot (
            revision_id, problem_id, practice_unit_id, target_unit_kind,
            target_title, target_item_family_id, target_estimated_duration_seconds,
            target_source_bundle_id, target_part_ids_wire, target_error_book_entry_id,
            legacy_semantics_present, legacy_problem_canonical_fingerprint,
            legacy_revision_source_type, legacy_revision_source_reference,
            legacy_answer_spec_id, legacy_answer_spec_snapshot,
            legacy_answer_verification_status, legacy_error_book_source_key,
            legacy_practice_unit_key, legacy_practice_unit_prompt_markdown,
            snapshot_canonical_fingerprint
        ) VALUES (
            'revision-v13', 'problem-v13', 'practice-v13', 'PROBLEM',
            'legacy answer', 'family-v13', 60,
            NULL, '0:', 'mistake-v13',
            1, '${"b".repeat(64)}',
            'TRUSTED_IMPORT', 'source-v13',
            'answer-v13', 'legacy verified answer',
            'VERIFIED', 'mistake-v13',
            'practice-v13', 'legacy question',
            '${"c".repeat(64)}'
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_solution_analysis (
            solution_analysis_id, basis_revision_id, organization_receipt_id,
            summary_markdown, final_answer_markdown, model_provider_id,
            model_id, analyzer_version, result_canonical_fingerprint,
            recorded_at_epoch_millis
        ) VALUES (
            'solution-v13', 'revision-v13', NULL,
            'model explanation', 'model-only final answer', 'provider-v13',
            'model-v13', 'analyzer-v13', '${"d".repeat(64)}', 20
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.installV13ProductionOpenGuards() {
    createStudentCutoverAndMigrationLedgerImmutabilityTriggers(this)
    createStudentImportSnapshotImmutabilityTriggers(this)
    createStudentProblemOrganizationImmutabilityTriggers(this)
    createStudentProblemIdentityReceiptImmutabilityTriggers(this)
}

private fun SQLiteConnection.readLong(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row for query: $sql" }
        statement.getLong(0)
    }

private fun SQLiteConnection.readText(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row for query: $sql" }
        statement.getText(0)
    }

private val TRUSTED_REVIEW_TABLES =
    listOf(
        "student_trusted_review_answer_rule",
        "student_trusted_review_lease_receipt",
        "student_trusted_review_attempt_receipt",
        "student_trusted_review_assistance_receipt",
    )
