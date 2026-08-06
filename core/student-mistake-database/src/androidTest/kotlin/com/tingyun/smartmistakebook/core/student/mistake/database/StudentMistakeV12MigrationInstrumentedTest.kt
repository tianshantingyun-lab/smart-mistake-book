package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeV12MigrationInstrumentedTest {
    @Test
    fun migration12To13RemovesDisplaySnapshotsPreservesReferencesAndReopens() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName =
                "student-mistake-v12-v13-no-knowledge-snapshot.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(12).use { connection ->
                    connection.seedV12ClassificationSnapshots()
                    connection.installV12ProductionOpenGuards()
                }

                helper.runMigrationsAndValidate(
                    version = 13,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_12_13),
                ).use { connection ->
                    assertFalse(
                        "display_name" in
                            connection.v13ColumnNames(
                                "student_problem_classification_result",
                            ),
                    )
                    assertEquals(
                        0L,
                        connection.v13Long(
                            """
                            SELECT COUNT(*)
                            FROM sqlite_master
                            WHERE name = 'student_problem_classification_result_v12'
                               OR (
                                 name = 'student_problem_classification_result' AND
                                 lower(sql) LIKE '%display_name%'
                               )
                            """.trimIndent(),
                        ),
                    )
                    assertEquals(
                        2L,
                        connection.v13Long(
                            """
                            SELECT COUNT(*)
                            FROM student_problem_classification_result
                            WHERE knowledge_subject = 'MATH'
                              AND knowledge_node_id = label_id
                              AND knowledge_taxonomy_version = 'taxonomy-v1'
                              AND knowledge_pack_version = 'pack-v1'
                              AND knowledge_manifest_fingerprint = '${"f".repeat(64)}'
                              AND knowledge_activation_generation = 3
                            """.trimIndent(),
                        ),
                    )
                }

                val database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    val classifications =
                        RoomStudentMistakeStore(database).readClassifications(V12_REVISION_REF)
                    assertEquals(2, classifications.size)
                    val section =
                        classifications.single {
                            it.dimension ==
                                StudentProblemClassificationDimension.CURRICULUM_SECTION
                        }
                    val knowledge =
                        classifications.single {
                            it.dimension == StudentProblemClassificationDimension.KNOWLEDGE
                        }
                    assertEquals(STUDENT_CLASSIFICATION_DISPLAY_PLACEHOLDER, section.displayName)
                    assertFalse(section.displayName == V12_SECTION_DISPLAY_NAME)
                    assertNull(knowledge.displayName)
                    assertTrue(classifications.all { it.knowledgeNode != null })
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private fun SQLiteConnection.seedV12ClassificationSnapshots() {
    execSQL(
        """
        INSERT INTO student_problem_document (
            problem_id, learner_id, subject, primary_practice_unit_id,
            current_revision_id, error_book_entry_id, lifecycle_state,
            archived_at_epoch_millis, tombstoned_at_epoch_millis,
            created_at_epoch_millis, updated_at_epoch_millis
        ) VALUES (
            '$V12_PROBLEM_ID', '$V12_LEARNER_ID', 'MATH', '$V12_PRACTICE_UNIT_ID',
            '$V12_REVISION_ID', NULL, 'ACTIVE', NULL, NULL, 10, 10
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
            '$V12_REVISION_ID', '$V12_PROBLEM_ID', 1, '迁移题', '求函数性质。',
            NULL, '$V12_DOCUMENT_FINGERPRINT', 10, 10
        )
        """.trimIndent(),
    )
    execSQL(
        v12ClassificationInsert(
            id = "classification-v12-section",
            dimension = "CURRICULUM_SECTION",
            nodeId = "math.function",
            displayName = V12_SECTION_DISPLAY_NAME,
            fingerprint = "a".repeat(64),
        ),
    )
    execSQL(
        v12ClassificationInsert(
            id = "classification-v12-knowledge",
            dimension = "KNOWLEDGE",
            nodeId = "math.function.quadratic",
            displayName = "同样不得保留的旧知识名称",
            fingerprint = "b".repeat(64),
        ),
    )
}

private fun v12ClassificationInsert(
    id: String,
    dimension: String,
    nodeId: String,
    displayName: String,
    fingerprint: String,
): String =
    """
    INSERT INTO student_problem_classification_result (
        classification_id, problem_id, basis_revision_id, organization_receipt_id,
        dimension, label_id, display_name, knowledge_subject, knowledge_node_id,
        knowledge_taxonomy_version, knowledge_pack_version,
        knowledge_manifest_fingerprint, knowledge_activation_generation,
        model_provider_id, model_id, classifier_version,
        result_canonical_fingerprint, status, supersedes_classification_id,
        recorded_at_epoch_millis
    ) VALUES (
        '$id', '$V12_PROBLEM_ID', '$V12_REVISION_ID', NULL,
        '$dimension', '$nodeId', '$displayName', 'MATH', '$nodeId',
        'taxonomy-v1', 'pack-v1', '${"f".repeat(64)}', 3,
        'provider-v12', 'model-v12', 'classifier-v12',
        '$fingerprint', 'ACCEPTED', NULL, 20
    )
    """.trimIndent()

private fun SQLiteConnection.installV12ProductionOpenGuards() {
    createStudentCutoverAndMigrationLedgerImmutabilityTriggers(this)
    createStudentImportSnapshotImmutabilityTriggers(this)
    createStudentProblemOrganizationImmutabilityTriggers(this)
    createStudentProblemIdentityReceiptImmutabilityTriggers(this)
}

private fun SQLiteConnection.v13ColumnNames(tableName: String): Set<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteConnection.v13Long(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row for query: $sql" }
        statement.getLong(0)
    }

private const val V12_LEARNER_ID = "learner-v12"
private const val V12_PROBLEM_ID = "problem-v12"
private const val V12_PRACTICE_UNIT_ID = "practice-v12"
private const val V12_REVISION_ID = "revision-v12"
private const val V12_SECTION_DISPLAY_NAME = "二次函数与图像"
private val V12_DOCUMENT_FINGERPRINT = "c".repeat(64)
private val V12_REVISION_REF =
    StudentProblemRevisionRef(
        problem =
            StudentProblemRef(
                learnerId = V12_LEARNER_ID,
                subject = SubjectKind.MATH,
                problemId = V12_PROBLEM_ID,
                practiceUnitId = V12_PRACTICE_UNIT_ID,
            ),
        revisionId = V12_REVISION_ID,
        revisionNumber = 1,
        documentCanonicalFingerprint = V12_DOCUMENT_FINGERPRINT,
    )
