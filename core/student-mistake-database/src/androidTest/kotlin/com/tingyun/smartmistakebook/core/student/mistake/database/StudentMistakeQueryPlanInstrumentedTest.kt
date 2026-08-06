package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies indexed plans whose work stays bounded when a learner reaches 100k owned rows. */
@RunWith(AndroidJUnit4::class)
class StudentMistakeQueryPlanInstrumentedTest {
    @Test
    fun currentSchemaUsesOwnedIndexesForLibraryRevisionKnowledgeAndIdempotencyReads() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName = "student-mistake-v16-query-plan.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(15).close()
                helper.runMigrationsAndValidate(
                    version = 16,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_15_16),
                ).close()
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.assertIndexedPlan(
                        sql = LIBRARY_TIMELINE_PLAN_SQL,
                        expectedIndexes =
                            setOf(
                                "index_student_problem_collection_learner_id_mistake_state_changed_at_epoch_millis_problem_id",
                            ),
                        forbiddenScans = setOf("collection"),
                    )
                    database.assertIndexedPlan(
                        sql = KNOWLEDGE_FILTER_PLAN_SQL,
                        expectedIndexes =
                            setOf(
                                "index_student_problem_collection_learner_id_mistake_state_changed_at_epoch_millis_problem_id",
                                "index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version",
                            ),
                        forbiddenScans = setOf("collection", "knowledge_result"),
                    )
                    database.assertIndexedPlan(
                        sql = REVISION_HISTORY_PLAN_SQL,
                        expectedIndexes =
                            setOf(
                                "index_student_problem_document_error_book_entry_id",
                                "index_student_problem_revision_problem_id_revision_number",
                            ),
                        forbiddenScans = setOf("problem", "revision"),
                    )
                    database.assertIndexedPlan(
                        sql = ERROR_OCCURRENCE_IDEMPOTENCY_PLAN_SQL,
                        expectedIndexes =
                            setOf(
                                "index_student_problem_error_occurrence_learner_id_idempotency_key",
                            ),
                        forbiddenScans = setOf("student_problem_error_occurrence"),
                    )
                    database.assertIndexedPlan(
                        sql = KNOWLEDGE_BINDING_PLAN_SQL,
                        expectedIndexes =
                            setOf(
                                "index_student_problem_step_knowledge_binding_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version",
                            ),
                        forbiddenScans = setOf("student_problem_step_knowledge_binding"),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private fun SQLiteDatabase.assertIndexedPlan(
    sql: String,
    expectedIndexes: Set<String>,
    forbiddenScans: Set<String>,
) {
    val plan =
        rawQuery("EXPLAIN QUERY PLAN $sql", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }
    expectedIndexes.forEach { indexName ->
        assertTrue("missing $indexName in $plan", plan.any { detail -> indexName in detail })
    }
    forbiddenScans.forEach { tableAlias ->
        assertFalse(
            "unbounded scan of $tableAlias in $plan",
            plan.any { detail -> detail.startsWith("SCAN $tableAlias") },
        )
    }
    assertFalse("query requires a temporary sort in $plan", plan.any { "TEMP B-TREE" in it })
}

private val LIBRARY_TIMELINE_PLAN_SQL =
    """
    SELECT collection.problem_id
    FROM student_problem_collection AS collection
    INNER JOIN student_problem_document AS problem
      ON problem.problem_id = collection.problem_id
     AND problem.learner_id = collection.learner_id
    INNER JOIN student_problem_revision AS revision
      ON revision.revision_id = problem.current_revision_id
    INNER JOIN student_practice_unit AS unit
      ON unit.practice_unit_id = collection.practice_unit_id
     AND unit.problem_id = collection.problem_id
    WHERE collection.learner_id = 'learner-scale'
      AND collection.mistake_state = 'ACTIVE'
      AND problem.lifecycle_state = 'ACTIVE'
      AND problem.error_book_entry_id IS NOT NULL
      AND problem.subject = 'MATH'
    ORDER BY collection.changed_at_epoch_millis DESC, collection.problem_id ASC
    LIMIT 50
    """.trimIndent()

private val KNOWLEDGE_FILTER_PLAN_SQL =
    """
    SELECT collection.problem_id
    FROM student_problem_collection AS collection
    INNER JOIN student_problem_document AS problem
      ON problem.problem_id = collection.problem_id
     AND problem.learner_id = collection.learner_id
    INNER JOIN student_problem_revision AS revision
      ON revision.revision_id = problem.current_revision_id
    INNER JOIN student_practice_unit AS unit
      ON unit.practice_unit_id = collection.practice_unit_id
     AND unit.problem_id = collection.problem_id
    WHERE collection.learner_id = 'learner-scale'
      AND collection.mistake_state = 'ACTIVE'
      AND problem.lifecycle_state = 'ACTIVE'
      AND problem.error_book_entry_id IS NOT NULL
      AND EXISTS (
        SELECT 1
        FROM student_problem_classification_result AS knowledge_result
        WHERE knowledge_result.basis_revision_id = revision.revision_id
          AND knowledge_result.dimension = 'KNOWLEDGE'
          AND knowledge_result.status = 'ACCEPTED'
          AND knowledge_result.knowledge_subject = 'MATH'
          AND knowledge_result.knowledge_node_id = 'math.function.quadratic'
          AND knowledge_result.knowledge_taxonomy_version = 'taxonomy-v1'
          AND knowledge_result.knowledge_pack_version = 'pack-v1'
      )
    ORDER BY collection.changed_at_epoch_millis DESC, collection.problem_id ASC
    LIMIT 50
    """.trimIndent()

private val REVISION_HISTORY_PLAN_SQL =
    """
    SELECT revision.revision_id
    FROM student_problem_document AS problem
    INNER JOIN student_problem_revision AS revision
      ON revision.problem_id = problem.problem_id
    LEFT JOIN student_problem_solution_analysis AS solution
      ON solution.basis_revision_id = revision.revision_id
    WHERE problem.error_book_entry_id = 'entry-scale'
    ORDER BY revision.revision_number DESC, revision.revision_id ASC
    LIMIT 50
    """.trimIndent()

private val ERROR_OCCURRENCE_IDEMPOTENCY_PLAN_SQL =
    """
    SELECT occurrence_id
    FROM student_problem_error_occurrence
    WHERE learner_id = 'learner-scale'
      AND idempotency_key = 'idempotency-scale'
    LIMIT 1
    """.trimIndent()

private val KNOWLEDGE_BINDING_PLAN_SQL =
    """
    SELECT binding_id
    FROM student_problem_step_knowledge_binding
    WHERE knowledge_subject = 'MATH'
      AND knowledge_node_id = 'math.function.quadratic'
      AND knowledge_taxonomy_version = 'taxonomy-v1'
      AND knowledge_pack_version = 'pack-v1'
    LIMIT 50
    """.trimIndent()
