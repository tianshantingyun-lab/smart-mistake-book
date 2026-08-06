package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Replaces only indexes used by the learner-bound mistake-library read model.
 *
 * No table, column, or row is rewritten. Superseded prefix indexes are removed so large imports
 * do not pay to maintain both the old and covering forms.
 */
internal val STUDENT_MISTAKE_MIGRATION_3_4 =
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            LIBRARY_READ_INDEXES.forEach(connection::execSQL)
        }
    }

internal val LIBRARY_READ_INDEXES: List<String> =
    listOf(
        """
        DROP INDEX IF EXISTS
        `index_student_problem_collection_learner_id_mistake_state_changed_at_epoch_millis`
        """.trimIndent(),
        """
        DROP INDEX IF EXISTS
        `index_student_problem_collection_learner_id_favorite_changed_at_epoch_millis`
        """.trimIndent(),
        """
        DROP INDEX IF EXISTS
        `index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version`
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_problem_document_learner_id_error_book_entry_id`
        ON `student_problem_document` (`learner_id`, `error_book_entry_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_problem_collection_learner_id_mistake_state_changed_at_epoch_millis_problem_id`
        ON `student_problem_collection`
        (`learner_id` ASC, `mistake_state` ASC,
         `changed_at_epoch_millis` DESC, `problem_id` ASC)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_problem_collection_learner_id_mistake_state_favorite_changed_at_epoch_millis_problem_id`
        ON `student_problem_collection`
        (`learner_id` ASC, `mistake_state` ASC, `favorite` ASC,
         `changed_at_epoch_millis` DESC, `problem_id` ASC)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version`
        ON `student_problem_classification_result`
        (`basis_revision_id`, `dimension`, `status`, `knowledge_subject`,
         `knowledge_node_id`, `knowledge_taxonomy_version`, `knowledge_pack_version`)
        """.trimIndent(),
    )
