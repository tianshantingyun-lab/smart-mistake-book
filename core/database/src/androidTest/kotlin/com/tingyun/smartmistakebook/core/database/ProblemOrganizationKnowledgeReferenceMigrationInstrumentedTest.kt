package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemOrganizationKnowledgeReferenceMigrationInstrumentedTest {
    @Test
    fun versionThirtyNinePreservesOpaqueBindingsAsPendingWithoutLegacyKnowledgeForeignKeys() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "problem-knowledge-reference-v40-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            try {
                createDatabaseFromExportedSchema(context, databaseName, version = 39)
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use(::insertVersionThirtyNineBinding)

                StudyDatabaseFactory.open(context, databaseName).use { store ->
                    store.readPendingCaptureStudentSaveHandoffs(
                        ReadPendingCaptureStudentSaveHandoffsQuery("learner:migration-probe"),
                    )
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { database ->
                    assertEquals(STUDY_DATABASE_VERSION, database.version)
                    database.assertPendingReference(
                        table = "practice_unit_knowledge_binding",
                        idColumn = "binding_id",
                        id = "legacy-binding",
                    )
                    database.assertPendingReference(
                        table = "problem_step_knowledge_binding",
                        idColumn = "solution_step_id",
                        id = "legacy-step",
                    )
                    database.assertPendingReference(
                        table = "problem_error_attribution_candidate",
                        idColumn = "error_attribution_candidate_id",
                        id = "legacy-error-candidate",
                    )
                    ORGANIZATION_KNOWLEDGE_TABLES.forEach { table ->
                        assertFalse(
                            "$table still treats legacy knowledge_node as an authority",
                            "knowledge_node" in database.foreignKeyParentTables(table),
                        )
                    }
                    DEPENDENT_EVIDENCE_TABLES.forEach { table ->
                        assertEquals("Migration lost historical evidence in $table", 1, database.rowCount(table))
                    }

                    database.execSQL(
                        "DELETE FROM knowledge_node WHERE knowledge_node_id = 'legacy-node'",
                    )
                    ORGANIZATION_KNOWLEDGE_TABLES.forEach { table ->
                        assertEquals(1, database.rowCount(table))
                    }
                    DEPENDENT_EVIDENCE_TABLES.forEach { table ->
                        assertEquals(1, database.rowCount(table))
                    }
                    database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                        assertFalse("Migration left broken foreign keys", cursor.moveToFirst())
                    }
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    private fun insertVersionThirtyNineBinding(database: SQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO problem (
                problem_id, canonical_fingerprint, subject,
                created_at_epoch_millis, archived_at_epoch_millis
            ) VALUES ('legacy-problem', '${"a".repeat(64)}', 'MATH', 1000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_revision (
                revision_id, problem_id, revision_number, title, problem_markdown,
                question_document_snapshot, answer_spec_id, answer_spec_snapshot,
                answer_verification_status, source_type, source_reference,
                content_fingerprint, created_at_epoch_millis
            ) VALUES (
                'legacy-revision', 'legacy-problem', 1, '旧题目', '题干',
                NULL, NULL, NULL, 'UNKNOWN', 'TEST', NULL, '${"b".repeat(64)}', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO practice_unit (
                practice_unit_id, problem_id, problem_revision_id, unit_key,
                unit_kind, title, prompt_markdown, estimated_seconds,
                created_at_epoch_millis
            ) VALUES (
                'legacy-practice', 'legacy-problem', 'legacy-revision', 'whole-problem',
                'WHOLE_PROBLEM', '旧题目', '题干', 60, 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO error_book_entry (
                entry_id, practice_unit_id, problem_id, current_revision_id, source_key,
                status, accepted_at_epoch_millis, updated_at_epoch_millis
            ) VALUES (
                'legacy-entry', 'legacy-practice', 'legacy-problem', 'legacy-revision', NULL,
                'ACTIVE', 1000, 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO assessment_evidence_snapshot (
                snapshot_id, assessment_item_id, practice_unit_id, problem_revision_id,
                answer_spec_id, item_family_id, source_bundle_id, taxonomy_version,
                verification, calibration_support, calibration_source_id,
                calibration_version, calibration_valid_from_epoch_millis,
                calibration_valid_until_epoch_millis, captured_at_epoch_millis
            ) VALUES (
                'legacy-snapshot', 'legacy-item', 'legacy-practice', 'legacy-revision',
                'legacy-answer', 'legacy-family', NULL, 'user-corrected-v1',
                'VERIFIED', 'SUPPORTED', 'legacy-calibration',
                'v1', 1, 2000, 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO learning_observation_candidate (
                candidate_id, learner_id, source, source_reference_id, source_fact_id,
                practice_unit_id, problem_revision_id, direction, evidence_level,
                evidence_weight, independence, occurred_at_epoch_millis, model_version,
                evidence_locator, status, retry_count, payload_fingerprint,
                created_at_epoch_millis, updated_at_epoch_millis
            ) VALUES (
                'legacy-observation-candidate', 'legacy-learner', 'ATTEMPT',
                'legacy-attempt', NULL, 'legacy-practice', 'legacy-revision',
                'CORRECT', 'DIRECT', 1.0, 'INDEPENDENT', 1000, 'legacy-model',
                'legacy-locator', 'APPLIED', 0, '${"1".repeat(64)}', 1000, 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO attributed_learning_observation_event (
                event_id, candidate_id, source_fact_id, learner_id, practice_unit_id,
                problem_revision_id, subject, direction, evidence_level, evidence_weight,
                independence, occurred_at_epoch_millis, confirmed_at_epoch_millis,
                model_version, evidence_locator, event_sequence, canonical_fingerprint
            ) VALUES (
                'legacy-observation-event', 'legacy-observation-candidate', NULL,
                'legacy-learner', 'legacy-practice', 'legacy-revision', 'MATH',
                'CORRECT', 'DIRECT', 1.0, 'INDEPENDENT', 1000, 1000,
                'legacy-model', 'legacy-locator', 1, '${"2".repeat(64)}'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO canonical_source_asset (
                source_asset_id, content_sha256, relative_path, mime_type, byte_size,
                width, height, source_type, created_at_epoch_millis
            ) VALUES (
                'legacy-asset', '${"c".repeat(64)}', 'legacy/source.png', 'image/png', 1,
                1, 1, 'TEST', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_draft (
                draft_id, source_asset_id, origin, status, current_revision_number,
                created_at_epoch_millis, updated_at_epoch_millis, request_fingerprint
            ) VALUES (
                'legacy-draft', 'legacy-asset', 'TEST', 'COMMITTED', 1,
                1000, 1000, '${"d".repeat(64)}'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_draft_commit_receipt (
                command_id, payload_fingerprint, draft_id, draft_revision_number,
                problem_id, problem_revision_id, practice_unit_id, error_book_entry_id,
                committed_at_epoch_millis
            ) VALUES (
                'legacy-commit', '${"e".repeat(64)}', 'legacy-draft', 1,
                'legacy-problem', 'legacy-revision', 'legacy-practice', 'legacy-entry', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_organization_receipt (
                command_id, payload_fingerprint, problem_id, problem_revision_id,
                practice_unit_id, classification_count, relation_count,
                accepted_at_epoch_millis
            ) VALUES (
                'legacy-organization', '${"f".repeat(64)}', 'legacy-problem', 'legacy-revision',
                'legacy-practice', 2, 0, 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_solution_step (
                solution_step_id, organization_command_id, problem_id, problem_revision_id,
                practice_unit_id, source_commit_receipt_command_id, step_ordinal,
                summary_markdown, created_at_epoch_millis
            ) VALUES (
                'legacy-step', 'legacy-organization', 'legacy-problem', 'legacy-revision',
                'legacy-practice', 'legacy-commit', 1, '旧步骤', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO knowledge_node (
                knowledge_node_id, stable_code, subject, display_name, canonical_name,
                node_kind, granularity, aliases_text, boundary_markdown,
                verification_status, parent_knowledge_node_id, taxonomy_version,
                created_at_epoch_millis
            ) VALUES (
                'legacy-node', 'math:legacy', 'MATH', '旧知识', '旧知识',
                'TOPIC', 'TOPIC', '', NULL, 'MODEL_CANDIDATE', NULL, 'legacy-taxonomy', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO practice_unit_knowledge_binding (
                binding_id, practice_unit_id, knowledge_node_id, basis_revision_id,
                strength, source_type, taxonomy_version, accepted_at_epoch_millis
            ) VALUES (
                'legacy-binding', 'legacy-practice', 'legacy-node', 'legacy-revision',
                1.0, 'USER_CORRECTED', 'user-corrected-v1', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO assessment_evidence_attribution (
                snapshot_id, binding_id, practice_unit_id, knowledge_node_id, weight,
                basis_revision_id, taxonomy_version, role, certainty
            ) VALUES (
                'legacy-snapshot', 'legacy-binding', 'legacy-practice', 'legacy-node', 1.0,
                'legacy-revision', 'user-corrected-v1', 'PRIMARY', 'CERTAIN'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO learning_observation_event_attribution (
                event_id, practice_unit_id, ordinal, binding_id, knowledge_node_id,
                weight, basis_revision_id, taxonomy_version, role, certainty
            ) VALUES (
                'legacy-observation-event', 'legacy-practice', 0, 'legacy-binding',
                'legacy-node', 1.0, 'legacy-revision', 'user-corrected-v1',
                'PRIMARY', 'CERTAIN'
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_step_knowledge_binding (
                solution_step_id, knowledge_node_id, knowledge_reference_id
            ) VALUES ('legacy-step', 'legacy-node', 'legacy-reference')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_error_attribution_candidate (
                error_attribution_candidate_id, organization_command_id, candidate_ordinal,
                problem_id, problem_revision_id, practice_unit_id,
                source_commit_receipt_command_id, resolution_status, solution_step_id,
                knowledge_node_id, knowledge_reference_id, rationale_markdown, confidence,
                model_version, created_at_epoch_millis
            ) VALUES (
                'legacy-error-candidate', 'legacy-organization', 0,
                'legacy-problem', 'legacy-revision', 'legacy-practice',
                'legacy-commit', 'RESOLVED', 'legacy-step',
                'legacy-node', 'legacy-reference', '旧归因', 1.0,
                'legacy-model', 1000
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO problem_error_candidate_evidence (
                error_attribution_candidate_id, evidence_ordinal, block_id,
                source_asset_id, evidence_kind
            ) VALUES (
                'legacy-error-candidate', 0, 'legacy-block', 'legacy-asset', 'QUESTION_REGION'
            )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.assertPendingReference(
        table: String,
        idColumn: String,
        id: String,
    ) {
        rawQuery(
            """
            SELECT knowledge_node_id,
                   knowledge_subject,
                   knowledge_taxonomy_version,
                   knowledge_pack_version,
                   knowledge_manifest_fingerprint,
                   knowledge_activation_generation,
                   knowledge_reference_status
            FROM `$table`
            WHERE `$idColumn` = ?
            """.trimIndent(),
            arrayOf(id),
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("legacy-node", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
            assertNull(cursor.getString(4))
            assertTrue(cursor.isNull(5))
            assertEquals(
                StudyDbValue.KnowledgeReferenceStatus.PENDING_REATTRIBUTION,
                cursor.getString(6),
            )
        }
    }

    private fun SQLiteDatabase.foreignKeyParentTables(table: String): Set<String> =
        rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(2))
            }
        }

    private fun SQLiteDatabase.rowCount(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        val ORGANIZATION_KNOWLEDGE_TABLES =
            setOf(
                "practice_unit_knowledge_binding",
                "problem_step_knowledge_binding",
                "problem_error_attribution_candidate",
            )
        val DEPENDENT_EVIDENCE_TABLES =
            setOf(
                "assessment_evidence_attribution",
                "learning_observation_event_attribution",
                "problem_error_candidate_evidence",
            )
    }
}
