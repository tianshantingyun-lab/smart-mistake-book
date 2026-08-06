package com.tingyun.smartmistakebook.core.student.mistake.database

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
class StudentMistakeV10MigrationInstrumentedTest {
    @Test
    fun migration9To10AddsEmptyCanonicalCaptureAndOrganizationLedgersWithEnforcedContracts() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v9-v10-ledger.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )

                helper.createDatabase(9).use { connection ->
                    connection.seedOrdinaryV9SavedProblem()
                    connection.seedNonEmptyV9RevisionAuthorityTables()
                }

                helper.runMigrationsAndValidate(
                    version = 10,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_9_10),
                ).use { connection ->
                    assertEquals(
                        1L,
                        connection.v10LongForQuery(
                            "SELECT COUNT(*) FROM student_problem_document " +
                                "WHERE problem_id = '$V9_PROBLEM_ID'",
                        ),
                    )
                    assertEquals(
                        1L,
                        connection.v10LongForQuery(
                            "SELECT COUNT(*) FROM student_problem_revision " +
                                "WHERE revision_id = '$V9_REVISION_ID'",
                        ),
                    )
                    assertEquals(
                        1L,
                        connection.v10LongForQuery(
                            "SELECT COUNT(*) FROM student_mistake_save_receipt " +
                                "WHERE intent_confirmation_id = '$V9_SAVE_RECEIPT_ID'",
                        ),
                    )
                    connection
                        .assertV10RevisionAuthorityRowsPreservedWithoutOrganizationFacts()

                    V10_EMPTY_LEDGER_TABLES.forEach { tableName ->
                        assertEquals(
                            "Migration must not synthesize rows in $tableName",
                            0L,
                            connection.v10LongForQuery("SELECT COUNT(*) FROM `$tableName`"),
                        )
                    }

                    assertEquals(
                        V10_CANONICAL_IDENTITY_COLUMNS,
                        connection.v10ColumnNames("student_problem_canonical_identity"),
                    )
                    assertEquals(
                        V10_CANONICAL_SOURCE_BINDING_COLUMNS,
                        connection.v10ColumnNames(
                            "student_problem_canonical_source_binding",
                        ),
                    )
                    assertEquals(
                        V10_CAPTURE_OCCURRENCE_TRANSACTION_COLUMNS,
                        connection.v10ColumnNames(
                            "student_capture_occurrence_transaction",
                        ),
                    )
                    assertTrue(
                        connection.v10ColumnNames(
                            "student_problem_canonical_source_binding",
                        ).containsAll(
                            setOf(
                                "selected_region_canonical_fingerprint",
                                "review_decision_canonical_fingerprint",
                            ),
                        ),
                    )
                    assertTrue(
                        connection.v10ColumnNames(
                            "student_capture_occurrence_transaction",
                        ).containsAll(
                            setOf(
                                "request_canonical_fingerprint",
                                "identity_resolution_kind",
                                "selected_region_canonical_fingerprint",
                            ),
                        ),
                    )

                    val installedTriggers =
                        connection.v10TextSetForQuery(
                            """
                            SELECT name
                            FROM sqlite_master
                            WHERE type = 'trigger'
                            """.trimIndent(),
                        )
                    assertEquals(
                        V10_IMMUTABLE_TRIGGER_NAMES,
                        STUDENT_PROBLEM_ORGANIZATION_IMMUTABILITY_TRIGGER_NAMES,
                    )
                    assertEquals(
                        emptySet<String>(),
                        V10_IMMUTABLE_TRIGGER_NAMES - installedTriggers,
                    )

                    assertEquals(
                        false,
                        connection.v10RequireIndexUnique(
                            tableName = "student_capture_save_handoff",
                            indexName =
                                "index_student_capture_save_handoff_target_revision_id",
                        ),
                    )
                    assertEquals(
                        false,
                        connection.v10RequireIndexUnique(
                            tableName = "student_capture_save_handoff",
                            indexName =
                                "index_student_capture_save_handoff_" +
                                    "target_canonical_fingerprint",
                        ),
                    )
                    assertEquals(
                        true,
                        connection.v10RequireIndexUnique(
                            tableName = "student_problem_error_occurrence",
                            indexName =
                                "index_student_problem_error_occurrence_" +
                                    "learner_id_idempotency_key",
                        ),
                    )
                    assertEquals(
                        listOf("learner_id", "idempotency_key"),
                        connection.v10IndexColumns(
                            "index_student_problem_error_occurrence_" +
                                "learner_id_idempotency_key",
                        ),
                    )

                    connection.execSQL("PRAGMA foreign_keys = ON")
                    assertEquals(1L, connection.v10LongForQuery("PRAGMA foreign_keys"))
                    connection.assertV10CanonicalIdentityForeignKeys()
                    connection.insertV10CanonicalIdentity()
                    connection.assertV10CanonicalSourceBindingForeignKeys()
                    connection.insertV10CanonicalSourceBinding()
                    connection.assertV10CanonicalLedgerIsAppendOnly()
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private fun SQLiteConnection.seedOrdinaryV9SavedProblem() {
    execSQL(
        """
        INSERT INTO student_problem_document (
            problem_id, learner_id, subject, primary_practice_unit_id,
            current_revision_id, error_book_entry_id, lifecycle_state,
            archived_at_epoch_millis, tombstoned_at_epoch_millis,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            '$V9_PROBLEM_ID', '$V9_LEARNER_ID', 'MATH', '$V9_PRACTICE_UNIT_ID',
            '$V9_REVISION_ID', '$V9_ERROR_BOOK_ENTRY_ID', 'ACTIVE',
            NULL, NULL, 100, 100
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
            '$V9_REVISION_ID', '$V9_PROBLEM_ID', 1, 'v9 migration fixture',
            'What is 1 + 1?', NULL, '$V9_DOCUMENT_FINGERPRINT', 100, 100
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_mistake_save_receipt (
            intent_confirmation_id, intent_canonical_fingerprint, learner_id,
            problem_id, basis_revision_id, error_book_entry_id,
            confirmed_at_epoch_millis, saved_at_epoch_millis
        ) VALUES (
            '$V9_SAVE_RECEIPT_ID', '$V9_SAVE_INTENT_FINGERPRINT', '$V9_LEARNER_ID',
            '$V9_PROBLEM_ID', '$V9_REVISION_ID', '$V9_ERROR_BOOK_ENTRY_ID',
            100, 110
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.seedNonEmptyV9RevisionAuthorityTables() {
    execSQL(
        """
        INSERT INTO student_problem_solution_analysis (
            solution_analysis_id, basis_revision_id, summary_markdown,
            final_answer_markdown, model_provider_id, model_id, analyzer_version,
            result_canonical_fingerprint, recorded_at_epoch_millis
        ) VALUES (
            '$V9_SOLUTION_ANALYSIS_ID', '$V9_REVISION_ID', 'v9 analysis summary',
            '2', 'v9-provider', 'v9-solution-model', 'v9-analyzer',
            '$V9_SOLUTION_ANALYSIS_FINGERPRINT', 120
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
            '$V9_SOLUTION_ANALYSIS_ID', '$V9_REVISION_ID', '$V9_SOLUTION_STEP_ID', 1,
            'v9 step summary', '1 + 1 equals 2', '2',
            '$V9_SOLUTION_STEP_FINGERPRINT'
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_error_attribution (
            attribution_id, basis_revision_id, solution_analysis_id,
            resolution_status, rationale_markdown, confidence, step_ordinal,
            atomic_reference_id, model_provider_id, model_id, analyzer_version,
            result_canonical_fingerprint, recorded_at_epoch_millis
        ) VALUES (
            '$V9_ERROR_ATTRIBUTION_ID', '$V9_REVISION_ID', '$V9_SOLUTION_ANALYSIS_ID',
            'RESOLVED', 'v9 attribution rationale', 0.875, 1,
            'v9-atomic-reference', 'v9-provider', 'v9-attribution-model',
            'v9-analyzer', '$V9_ERROR_ATTRIBUTION_FINGERPRINT', 130
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_error_evidence (
            attribution_id, basis_revision_id, ordinal, block_id,
            source_asset_id, evidence_kind
        ) VALUES (
            '$V9_ERROR_ATTRIBUTION_ID', '$V9_REVISION_ID', 0,
            'v9-block', 'v9-source-asset', 'QUESTION_BLOCK'
        )
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO student_problem_classification_result (
            classification_id, problem_id, basis_revision_id, dimension,
            label_id, display_name, knowledge_subject, knowledge_node_id,
            knowledge_taxonomy_version, knowledge_pack_version,
            knowledge_manifest_fingerprint, knowledge_activation_generation,
            model_provider_id, model_id, classifier_version,
            result_canonical_fingerprint, status, supersedes_classification_id,
            recorded_at_epoch_millis
        ) VALUES (
            '$V9_CLASSIFICATION_ID', '$V9_PROBLEM_ID', '$V9_REVISION_ID', 'KNOWLEDGE',
            'v9-label', 'v9 display', 'MATH', 'v9-knowledge-node',
            'v9-taxonomy', 'v9-pack', '$V9_KNOWLEDGE_MANIFEST_FINGERPRINT', 7,
            'v9-provider', 'v9-classifier-model', 'v9-classifier',
            '$V9_CLASSIFICATION_FINGERPRINT', 'ACCEPTED', NULL, 140
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.assertV10RevisionAuthorityRowsPreservedWithoutOrganizationFacts() {
    V10_PRESERVED_REVISION_AUTHORITY_TABLES.forEach { tableName ->
        assertEquals(
            "Migration must preserve exactly one v9 row in $tableName",
            1L,
            v10LongForQuery("SELECT COUNT(*) FROM `$tableName`"),
        )
    }
    assertEquals(
        1L,
        v10LongForQuery(
            """
            SELECT COUNT(*)
            FROM student_problem_solution_analysis
            WHERE solution_analysis_id = '$V9_SOLUTION_ANALYSIS_ID'
              AND basis_revision_id = '$V9_REVISION_ID'
              AND organization_receipt_id IS NULL
              AND summary_markdown = 'v9 analysis summary'
              AND final_answer_markdown = '2'
              AND model_provider_id = 'v9-provider'
              AND model_id = 'v9-solution-model'
              AND analyzer_version = 'v9-analyzer'
              AND result_canonical_fingerprint = '$V9_SOLUTION_ANALYSIS_FINGERPRINT'
              AND recorded_at_epoch_millis = 120
            """.trimIndent(),
        ),
    )
    assertEquals(
        1L,
        v10LongForQuery(
            """
            SELECT COUNT(*)
            FROM student_problem_solution_step
            WHERE solution_analysis_id = '$V9_SOLUTION_ANALYSIS_ID'
              AND basis_revision_id = '$V9_REVISION_ID'
              AND step_id = '$V9_SOLUTION_STEP_ID'
              AND ordinal = 1
              AND summary_markdown = 'v9 step summary'
              AND reasoning_markdown = '1 + 1 equals 2'
              AND result_markdown = '2'
              AND step_canonical_fingerprint = '$V9_SOLUTION_STEP_FINGERPRINT'
            """.trimIndent(),
        ),
    )
    assertEquals(
        1L,
        v10LongForQuery(
            """
            SELECT COUNT(*)
            FROM student_problem_error_attribution
            WHERE attribution_id = '$V9_ERROR_ATTRIBUTION_ID'
              AND basis_revision_id = '$V9_REVISION_ID'
              AND organization_receipt_id IS NULL
              AND solution_analysis_id = '$V9_SOLUTION_ANALYSIS_ID'
              AND resolution_status = 'RESOLVED'
              AND rationale_markdown = 'v9 attribution rationale'
              AND confidence = 0.875
              AND step_ordinal = 1
              AND atomic_reference_id = 'v9-atomic-reference'
              AND model_provider_id = 'v9-provider'
              AND model_id = 'v9-attribution-model'
              AND analyzer_version = 'v9-analyzer'
              AND result_canonical_fingerprint = '$V9_ERROR_ATTRIBUTION_FINGERPRINT'
              AND recorded_at_epoch_millis = 130
            """.trimIndent(),
        ),
    )
    assertEquals(
        1L,
        v10LongForQuery(
            """
            SELECT COUNT(*)
            FROM student_problem_error_evidence
            WHERE attribution_id = '$V9_ERROR_ATTRIBUTION_ID'
              AND basis_revision_id = '$V9_REVISION_ID'
              AND ordinal = 0
              AND block_id = 'v9-block'
              AND source_asset_id = 'v9-source-asset'
              AND evidence_kind = 'QUESTION_BLOCK'
            """.trimIndent(),
        ),
    )
    assertEquals(
        1L,
        v10LongForQuery(
            """
            SELECT COUNT(*)
            FROM student_problem_classification_result
            WHERE classification_id = '$V9_CLASSIFICATION_ID'
              AND problem_id = '$V9_PROBLEM_ID'
              AND basis_revision_id = '$V9_REVISION_ID'
              AND organization_receipt_id IS NULL
              AND dimension = 'KNOWLEDGE'
              AND label_id = 'v9-label'
              AND display_name = 'v9 display'
              AND knowledge_subject = 'MATH'
              AND knowledge_node_id = 'v9-knowledge-node'
              AND knowledge_taxonomy_version = 'v9-taxonomy'
              AND knowledge_pack_version = 'v9-pack'
              AND knowledge_manifest_fingerprint = '$V9_KNOWLEDGE_MANIFEST_FINGERPRINT'
              AND knowledge_activation_generation = 7
              AND model_provider_id = 'v9-provider'
              AND model_id = 'v9-classifier-model'
              AND classifier_version = 'v9-classifier'
              AND result_canonical_fingerprint = '$V9_CLASSIFICATION_FINGERPRINT'
              AND status = 'ACCEPTED'
              AND supersedes_classification_id IS NULL
              AND recorded_at_epoch_millis = 140
            """.trimIndent(),
        ),
    )
}

private fun SQLiteConnection.assertV10CanonicalIdentityForeignKeys() {
    assertV10SqlRejected(
        """
        INSERT INTO student_problem_canonical_identity (
            learner_id, subject, identity_namespace, identity_version, stable_key,
            identity_canonical_fingerprint, issuance_kind, problem_id, revision_id,
            revision_number, document_canonical_fingerprint, practice_unit_id,
            error_book_entry_id, target_save_receipt_id,
            target_canonical_fingerprint, created_transaction_id,
            created_at_epoch_millis
        ) VALUES (
            '$V9_LEARNER_ID', 'MATH', 'trusted-catalog', '1', 'missing-target',
            '${"1".repeat(64)}', 'TRUSTED_SOURCE', 'missing-problem',
            'missing-revision', 1, '$V9_DOCUMENT_FINGERPRINT',
            '$V9_PRACTICE_UNIT_ID', '$V9_ERROR_BOOK_ENTRY_ID',
            'missing-receipt', '${"2".repeat(64)}', 'transaction-missing', 200
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.insertV10CanonicalIdentity() {
    execSQL(
        """
        INSERT INTO student_problem_canonical_identity (
            learner_id, subject, identity_namespace, identity_version, stable_key,
            identity_canonical_fingerprint, issuance_kind, problem_id, revision_id,
            revision_number, document_canonical_fingerprint, practice_unit_id,
            error_book_entry_id, target_save_receipt_id,
            target_canonical_fingerprint, created_transaction_id,
            created_at_epoch_millis
        ) VALUES (
            '$V9_LEARNER_ID', 'MATH', '$V10_IDENTITY_NAMESPACE',
            '$V10_IDENTITY_VERSION', '$V10_STABLE_KEY',
            '$V10_IDENTITY_FINGERPRINT', 'TRUSTED_SOURCE', '$V9_PROBLEM_ID',
            '$V9_REVISION_ID', 1, '$V9_DOCUMENT_FINGERPRINT',
            '$V9_PRACTICE_UNIT_ID', '$V9_ERROR_BOOK_ENTRY_ID',
            '$V9_SAVE_RECEIPT_ID', '$V10_TARGET_FINGERPRINT',
            '$V10_CREATED_TRANSACTION_ID', 200
        )
        """.trimIndent(),
    )
}

private fun SQLiteConnection.assertV10CanonicalSourceBindingForeignKeys() {
    assertV10SqlRejected(
        v10SourceBindingInsertSql(
            evidenceFingerprint = "8".repeat(64),
            identityStableKey = "missing-stable-key",
            boundRevisionId = V9_REVISION_ID,
        ),
    )
    assertV10SqlRejected(
        v10SourceBindingInsertSql(
            evidenceFingerprint = "9".repeat(64),
            identityStableKey = V10_STABLE_KEY,
            boundRevisionId = "missing-revision",
        ),
    )
}

private fun SQLiteConnection.insertV10CanonicalSourceBinding() {
    execSQL(
        v10SourceBindingInsertSql(
            evidenceFingerprint = V10_SOURCE_EVIDENCE_FINGERPRINT,
            identityStableKey = V10_STABLE_KEY,
            boundRevisionId = V9_REVISION_ID,
        ),
    )
}

private fun v10SourceBindingInsertSql(
    evidenceFingerprint: String,
    identityStableKey: String,
    boundRevisionId: String,
): String =
    """
    INSERT INTO student_problem_canonical_source_binding (
        learner_id, subject, evidence_kind, evidence_canonical_fingerprint,
        identity_namespace, identity_version, identity_stable_key,
        asset_manifest_canonical_fingerprint,
        selected_region_canonical_fingerprint,
        document_canonical_fingerprint, bound_revision_id,
        locator_namespace, locator_version, item_locator_canonical_fingerprint,
        trusted_source_proof_fingerprint, reviewed_alias_proof_fingerprint,
        review_case_id, review_revision, review_decision_canonical_fingerprint,
        created_transaction_id, created_at_epoch_millis
    ) VALUES (
        '$V9_LEARNER_ID', 'MATH', 'TRUSTED_SOURCE', '$evidenceFingerprint',
        '$V10_IDENTITY_NAMESPACE', '$V10_IDENTITY_VERSION', '$identityStableKey',
        '${"3".repeat(64)}', '${"4".repeat(64)}', '$V9_DOCUMENT_FINGERPRINT',
        '$boundRevisionId', 'catalog', '1', '${"5".repeat(64)}',
        '${"6".repeat(64)}', NULL, NULL, NULL, NULL,
        '$V10_CREATED_TRANSACTION_ID', 210
    )
    """.trimIndent()

private fun SQLiteConnection.assertV10CanonicalLedgerIsAppendOnly() {
    assertV10SqlRejected(
        """
        UPDATE student_problem_canonical_identity
        SET issuance_kind = 'FRESH_OPAQUE'
        WHERE learner_id = '$V9_LEARNER_ID'
          AND subject = 'MATH'
          AND identity_namespace = '$V10_IDENTITY_NAMESPACE'
          AND identity_version = '$V10_IDENTITY_VERSION'
          AND stable_key = '$V10_STABLE_KEY'
        """.trimIndent(),
    )
    assertV10SqlRejected(
        """
        DELETE FROM student_problem_canonical_identity
        WHERE learner_id = '$V9_LEARNER_ID'
          AND subject = 'MATH'
          AND identity_namespace = '$V10_IDENTITY_NAMESPACE'
          AND identity_version = '$V10_IDENTITY_VERSION'
          AND stable_key = '$V10_STABLE_KEY'
        """.trimIndent(),
    )
    assertV10SqlRejected(
        """
        UPDATE student_problem_canonical_source_binding
        SET locator_version = '2'
        WHERE learner_id = '$V9_LEARNER_ID'
          AND subject = 'MATH'
          AND evidence_kind = 'TRUSTED_SOURCE'
          AND evidence_canonical_fingerprint = '$V10_SOURCE_EVIDENCE_FINGERPRINT'
        """.trimIndent(),
    )
    assertV10SqlRejected(
        """
        DELETE FROM student_problem_canonical_source_binding
        WHERE learner_id = '$V9_LEARNER_ID'
          AND subject = 'MATH'
          AND evidence_kind = 'TRUSTED_SOURCE'
          AND evidence_canonical_fingerprint = '$V10_SOURCE_EVIDENCE_FINGERPRINT'
        """.trimIndent(),
    )
}

private fun SQLiteConnection.assertV10SqlRejected(sql: String) {
    val failure = runCatching { execSQL(sql) }.exceptionOrNull()
    assertTrue("Expected SQL to be rejected: $sql", failure != null)
}

private fun SQLiteConnection.v10ColumnNames(tableName: String): Set<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteConnection.v10RequireIndexUnique(
    tableName: String,
    indexName: String,
): Boolean =
    prepare("PRAGMA index_list(`$tableName`)").use { statement ->
        while (statement.step()) {
            if (statement.getText(1) == indexName) {
                return@use statement.getLong(2) == 1L
            }
        }
        error("Expected index $indexName on $tableName")
    }

private fun SQLiteConnection.v10IndexColumns(indexName: String): List<String> =
    prepare("PRAGMA index_info(`$indexName`)").use { statement ->
        buildList {
            while (statement.step()) add(statement.getText(2))
        }
    }

private fun SQLiteConnection.v10LongForQuery(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row for scalar query" }
        statement.getLong(0)
    }

private fun SQLiteConnection.v10TextSetForQuery(sql: String): Set<String> =
    prepare(sql).use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(0))
        }
    }

private val V10_EMPTY_LEDGER_TABLES =
    listOf(
        "student_problem_canonical_identity",
        "student_problem_canonical_source_binding",
        "student_problem_error_occurrence",
        "student_problem_error_occurrence_evidence",
        "student_capture_occurrence_transaction",
        "student_problem_organization_receipt",
        "student_problem_step_knowledge_binding",
        "student_problem_organization_facet",
        "student_problem_organization_occurrence_binding",
    )

private val V10_PRESERVED_REVISION_AUTHORITY_TABLES =
    listOf(
        "student_problem_solution_analysis",
        "student_problem_solution_step",
        "student_problem_error_attribution",
        "student_problem_error_evidence",
        "student_problem_classification_result",
    )

private val V10_CANONICAL_IDENTITY_COLUMNS =
    setOf(
        "learner_id",
        "subject",
        "identity_namespace",
        "identity_version",
        "stable_key",
        "identity_canonical_fingerprint",
        "issuance_kind",
        "problem_id",
        "revision_id",
        "revision_number",
        "document_canonical_fingerprint",
        "practice_unit_id",
        "error_book_entry_id",
        "target_save_receipt_id",
        "target_canonical_fingerprint",
        "created_transaction_id",
        "created_at_epoch_millis",
    )

private val V10_CANONICAL_SOURCE_BINDING_COLUMNS =
    setOf(
        "learner_id",
        "subject",
        "evidence_kind",
        "evidence_canonical_fingerprint",
        "identity_namespace",
        "identity_version",
        "identity_stable_key",
        "asset_manifest_canonical_fingerprint",
        "selected_region_canonical_fingerprint",
        "document_canonical_fingerprint",
        "bound_revision_id",
        "locator_namespace",
        "locator_version",
        "item_locator_canonical_fingerprint",
        "trusted_source_proof_fingerprint",
        "reviewed_alias_proof_fingerprint",
        "review_case_id",
        "review_revision",
        "review_decision_canonical_fingerprint",
        "created_transaction_id",
        "created_at_epoch_millis",
    )

private val V10_CAPTURE_OCCURRENCE_TRANSACTION_COLUMNS =
    setOf(
        "transaction_id",
        "transaction_canonical_fingerprint",
        "request_canonical_fingerprint",
        "schema_version",
        "learner_id",
        "capture_intent_id",
        "source_kind",
        "source_canonical_fingerprint",
        "identity_namespace",
        "identity_version",
        "identity_stable_key",
        "identity_canonical_fingerprint",
        "identity_resolution_kind",
        "identity_evidence_kind",
        "identity_evidence_canonical_fingerprint",
        "trusted_source_proof_fingerprint",
        "reviewed_alias_proof_fingerprint",
        "target_save_receipt_id",
        "target_problem_id",
        "target_revision_id",
        "target_revision_number",
        "target_document_canonical_fingerprint",
        "target_canonical_fingerprint",
        "save_outcome",
        "occurrence_id",
        "occurrence_canonical_fingerprint",
        "batch_canonical_fingerprint",
        "import_source_canonical_fingerprint",
        "asset_manifest_canonical_fingerprint",
        "selected_region_canonical_fingerprint",
        "committed_at_epoch_millis",
    )

private val V10_IMMUTABLE_TRIGGER_NAMES =
    setOf(
        "immutable_student_problem_canonical_identity_update",
        "immutable_student_problem_canonical_identity_delete",
        "immutable_student_problem_canonical_source_binding_update",
        "immutable_student_problem_canonical_source_binding_delete",
        "immutable_student_capture_occurrence_transaction_update",
        "immutable_student_capture_occurrence_transaction_delete",
        "immutable_student_problem_error_occurrence_update",
        "immutable_student_problem_error_occurrence_delete",
        "immutable_student_problem_error_occurrence_evidence_update",
        "immutable_student_problem_error_occurrence_evidence_delete",
        "immutable_student_problem_organization_receipt_update",
        "immutable_student_problem_organization_receipt_delete",
        "immutable_student_problem_step_knowledge_binding_update",
        "immutable_student_problem_step_knowledge_binding_delete",
        "immutable_student_problem_organization_facet_update",
        "immutable_student_problem_organization_facet_delete",
        "immutable_student_problem_organization_occurrence_binding_update",
        "immutable_student_problem_organization_occurrence_binding_delete",
        "immutable_student_problem_solution_analysis_organization_update",
        "immutable_student_problem_solution_analysis_organization_delete",
        "immutable_student_problem_solution_step_organization_update",
        "immutable_student_problem_solution_step_organization_delete",
        "immutable_student_problem_error_attribution_organization_update",
        "immutable_student_problem_error_attribution_organization_delete",
        "immutable_student_problem_error_evidence_organization_update",
        "immutable_student_problem_error_evidence_organization_delete",
        "immutable_student_problem_classification_result_organization_update",
        "immutable_student_problem_classification_result_organization_delete",
        "reject_legacy_classification_after_organization",
    )

private const val V9_LEARNER_ID = "learner-v9-ledger"
private const val V9_PROBLEM_ID = "problem-v9-ledger"
private const val V9_REVISION_ID = "revision-v9-ledger"
private const val V9_PRACTICE_UNIT_ID = "practice-v9-ledger"
private const val V9_ERROR_BOOK_ENTRY_ID = "error-book-v9-ledger"
private const val V9_SAVE_RECEIPT_ID = "save-receipt-v9-ledger"
private const val V9_SOLUTION_ANALYSIS_ID = "solution-analysis-v9-ledger"
private const val V9_SOLUTION_STEP_ID = "solution-step-v9-ledger"
private const val V9_ERROR_ATTRIBUTION_ID = "error-attribution-v9-ledger"
private const val V9_CLASSIFICATION_ID = "classification-v9-ledger"
private const val V10_IDENTITY_NAMESPACE = "trusted-catalog"
private const val V10_IDENTITY_VERSION = "1"
private const val V10_STABLE_KEY = "catalog-item-42"
private const val V10_CREATED_TRANSACTION_ID = "transaction-created-v10"
private val V9_DOCUMENT_FINGERPRINT = "a".repeat(64)
private val V9_SAVE_INTENT_FINGERPRINT = "b".repeat(64)
private val V9_SOLUTION_ANALYSIS_FINGERPRINT = "1".repeat(64)
private val V9_SOLUTION_STEP_FINGERPRINT = "2".repeat(64)
private val V9_ERROR_ATTRIBUTION_FINGERPRINT = "3".repeat(64)
private val V9_CLASSIFICATION_FINGERPRINT = "4".repeat(64)
private val V9_KNOWLEDGE_MANIFEST_FINGERPRINT = "5".repeat(64)
private val V10_IDENTITY_FINGERPRINT = "c".repeat(64)
private val V10_TARGET_FINGERPRINT = "d".repeat(64)
private val V10_SOURCE_EVIDENCE_FINGERPRINT = "e".repeat(64)
