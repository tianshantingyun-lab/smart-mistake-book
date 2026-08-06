package com.tingyun.smartmistakebook.core.student.mistake.database

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
class StudentMistakeV15MigrationInstrumentedTest {
    @Test
    fun migration15To16DropsOnlyConfidenceAndPreservesGroundedAttributionEvidence() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v15-v16-confidence.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(15).use { connection ->
                    connection.seedV15GroundedErrorAttribution()
                    createStudentProblemOrganizationImmutabilityTriggers(connection)
                }

                helper.runMigrationsAndValidate(
                    version = 16,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_15_16),
                ).use { connection ->
                    val columns = connection.columnNames("student_problem_error_attribution")
                    assertFalse("model confidence must leave the owned schema", "confidence" in columns)
                    assertEquals(V16_EXPECTED_ATTRIBUTION_COLUMNS, columns)
                    assertEquals(1L, connection.readLong("SELECT COUNT(*) FROM student_problem_error_attribution"))
                    assertEquals(1L, connection.readLong("SELECT COUNT(*) FROM student_problem_error_evidence"))
                    assertEquals(
                        1L,
                        connection.readLong(
                            "SELECT COUNT(*) FROM student_mistake_destination_attestation_invalidation",
                        ),
                    )
                    assertEquals(
                        "revision-v15",
                        connection.readText(
                            "SELECT revision_id FROM student_mistake_destination_attestation_invalidation",
                        ),
                    )
                    assertEquals(
                        0L,
                        connection.readLong(
                            "SELECT COUNT(*) FROM student_mistake_destination_reattestation_receipt",
                        ),
                    )
                    assertEquals(
                        "grounded sign error",
                        connection.readText(
                            "SELECT rationale_markdown FROM student_problem_error_attribution WHERE attribution_id = 'attribution-v15'",
                        ),
                    )
                    assertEquals(
                        "student-work-v15",
                        connection.readText(
                            "SELECT block_id FROM student_problem_error_evidence WHERE attribution_id = 'attribution-v15'",
                        ),
                    )
                    assertEquals(0L, connection.foreignKeyViolationCount())

                    val indexes = connection.schemaObjectNames("index")
                    assertTrue(indexes.containsAll(V16_EXPECTED_ATTRIBUTION_INDEXES))
                    val triggers = connection.schemaObjectNames("trigger")
                    assertTrue(triggers.containsAll(V16_REBUILT_TABLE_TRIGGERS))
                    assertTrue(
                        triggers.containsAll(
                            STUDENT_DESTINATION_REATTESTATION_IMMUTABILITY_TRIGGER_NAMES,
                        ),
                    )

                    assertTrue(
                        runCatching {
                            connection.execSQL(
                                "UPDATE student_problem_error_attribution SET rationale_markdown = 'changed' WHERE attribution_id = 'attribution-v15'",
                            )
                        }.isFailure,
                    )
                    assertTrue(
                        runCatching {
                            connection.execSQL(
                                "DELETE FROM student_mistake_destination_attestation_invalidation",
                            )
                        }.isFailure,
                    )
                    assertTrue(
                        runCatching {
                            connection.execSQL(
                                "DELETE FROM student_problem_error_evidence WHERE attribution_id = 'attribution-v15'",
                            )
                        }.isFailure,
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private fun SQLiteConnection.seedV15GroundedErrorAttribution() {
    execSQL(
        """
        INSERT INTO student_problem_document (
            problem_id, learner_id, subject, primary_practice_unit_id,
            current_revision_id, error_book_entry_id, lifecycle_state,
            archived_at_epoch_millis, tombstoned_at_epoch_millis,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            'problem-v15', 'learner-v15', 'MATH', 'practice-v15',
            'revision-v15', 'mistake-v15', 'ACTIVE', NULL, NULL, 10, 10
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
            'problem-v15-unaffected', 'learner-v15', 'MATH', 'practice-v15-unaffected',
            'revision-v15-unaffected', 'mistake-v15-unaffected', 'ACTIVE',
            NULL, NULL, 11, 11
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
            'revision-v15', 'problem-v15', 1, 'question', 'question',
            NULL, '${"a".repeat(64)}', 10, 10
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
            'revision-v15-unaffected', 'problem-v15-unaffected', 1,
            'unaffected question', 'unaffected question', NULL,
            '${"1".repeat(64)}', 11, 11
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
            updated_at_epoch_millis, destination_ledger_version
        ) VALUES (
            'migration-v15', '${"2".repeat(64)}', 11,
            'problem-v15-unaffected', 1, 'revision-v15-unaffected', 2,
            0, '${"3".repeat(64)}', 12, 2
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_mistake_migration_destination_record (
            migration_id, source_page_canonical_fingerprint, page_record_ordinal,
            committed_at_epoch_millis, problem_id, revision_number, revision_id,
            import_snapshot_canonical_fingerprint,
            destination_record_canonical_fingerprint
        ) VALUES
            ('migration-v15', '${"4".repeat(64)}', 0, 10,
             'problem-v15', 1, 'revision-v15', NULL, '${"5".repeat(64)}'),
            ('migration-v15', '${"4".repeat(64)}', 1, 11,
             'problem-v15-unaffected', 1, 'revision-v15-unaffected', NULL,
             '${"6".repeat(64)}')
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_organization_receipt (
            receipt_id, request_id, request_canonical_fingerprint, request_version,
            reviewed_request_version, organization_revision, supersedes_receipt_id,
            previous_payload_canonical_fingerprint, learner_id, subject, problem_id,
            practice_unit_id, basis_revision_id, basis_revision_number,
            basis_document_canonical_fingerprint, model_provider_id, model_id,
            requested_model_version, result_model_version, provider_configuration_version,
            model_task_schema_version, organization_plan_schema_version, review_source,
            review_version, review_issuer_key_id, review_issuer_version,
            review_issued_at_epoch_millis, review_expires_at_epoch_millis,
            payload_canonical_fingerprint, error_occurrence_count, classification_count,
            step_knowledge_binding_count, error_attribution_count, facet_count, status,
            completed_at_epoch_millis
        ) VALUES (
            'receipt-v15', 'request-v15', '${"b".repeat(64)}', 1,
            1, 1, NULL,
            NULL, 'learner-v15', 'MATH', 'problem-v15',
            'practice-v15', 'revision-v15', 1,
            '${"a".repeat(64)}', 'provider-v15', 'model-v15',
            'requested-v15', 'result-v15', 'provider-config-v15',
            3, 3, 'INDEPENDENT_MODEL_REVIEW',
            'review-v15', 'review-owner-v15', 'review-issuer-v15',
            20, 2000, '${"c".repeat(64)}', 1, 1,
            1, 1, 0, 'COMPLETED', 30
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_solution_analysis (
            solution_analysis_id, basis_revision_id, organization_receipt_id,
            summary_markdown, final_answer_markdown, model_provider_id, model_id,
            analyzer_version, result_canonical_fingerprint, recorded_at_epoch_millis
        ) VALUES (
            'solution-v15', 'revision-v15', 'receipt-v15',
            'reviewed solution', NULL, 'provider-v15', 'model-v15',
            'analyzer-v15', '${"d".repeat(64)}', 25
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_solution_step (
            solution_analysis_id, basis_revision_id, step_id, ordinal,
            summary_markdown, reasoning_markdown, result_markdown,
            step_canonical_fingerprint
        ) VALUES (
            'solution-v15', 'revision-v15', 'step-v15', 1,
            'substitute the condition', 'preserve the sign', NULL,
            '${"f".repeat(64)}'
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_error_attribution (
            attribution_id, basis_revision_id, organization_receipt_id,
            solution_analysis_id, resolution_status, rationale_markdown, confidence,
            step_ordinal, atomic_reference_id, model_provider_id, model_id,
            analyzer_version, result_canonical_fingerprint, recorded_at_epoch_millis
        ) VALUES (
            'attribution-v15', 'revision-v15', 'receipt-v15',
            'solution-v15', 'RESOLVED', 'grounded sign error', 0.875,
            1, 'knowledge-reference-v15', 'provider-v15', 'model-v15',
            'analyzer-v15', '${"e".repeat(64)}', 26
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_error_evidence (
            attribution_id, basis_revision_id, ordinal, block_id,
            source_asset_id, evidence_kind
        ) VALUES (
            'attribution-v15', 'revision-v15', 0, 'student-work-v15',
            'source-page-v15', 'STUDENT_WORK'
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.columnNames(tableName: String): Set<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteConnection.schemaObjectNames(type: String): Set<String> =
    prepare("SELECT name FROM sqlite_master WHERE type = '$type'").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(0))
        }
    }

private fun SQLiteConnection.foreignKeyViolationCount(): Long =
    prepare("PRAGMA foreign_key_check").use { statement ->
        var count = 0L
        while (statement.step()) count += 1
        count
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

private val V16_EXPECTED_ATTRIBUTION_COLUMNS =
    setOf(
        "attribution_id",
        "basis_revision_id",
        "organization_receipt_id",
        "solution_analysis_id",
        "resolution_status",
        "rationale_markdown",
        "step_ordinal",
        "atomic_reference_id",
        "model_provider_id",
        "model_id",
        "analyzer_version",
        "result_canonical_fingerprint",
        "recorded_at_epoch_millis",
    )

private val V16_EXPECTED_ATTRIBUTION_INDEXES =
    setOf(
        "index_student_problem_error_attribution_basis_revision_id_recorded_at_epoch_millis_attribution_id",
        "index_student_problem_error_attribution_solution_analysis_id_basis_revision_id",
        "index_student_problem_error_attribution_organization_receipt_id",
        "index_student_problem_error_attribution_result_canonical_fingerprint",
        "index_student_problem_error_attribution_attribution_id_basis_revision_id",
        "index_student_problem_error_evidence_attribution_id_basis_revision_id",
        "index_student_problem_error_evidence_basis_revision_id_block_id_source_asset_id",
    )

private val V16_REBUILT_TABLE_TRIGGERS =
    setOf(
        "immutable_student_problem_error_attribution_organization_update",
        "immutable_student_problem_error_attribution_organization_delete",
        "immutable_student_problem_error_evidence_organization_update",
        "immutable_student_problem_error_evidence_organization_delete",
    )
