package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v31 -> v32: library full-text search projection (audit PR-09).
 *
 * Creates the materialized content table (primary key column
 * `content_row_id`, matching [com.tingyun.smartmistakebook.core.database.entity.LibrarySearchContentEntity]
 * and the exported 32.json schema byte-for-byte), the external-content FTS4
 * index, and the outbox table, then backfills every searchable column from
 * the authoritative library rows.
 *
 * The CREATE statements below must stay byte-identical to the createSql
 * recorded in schemas/...StudyDatabase/32.json: sqlite_master stores the
 * creation text verbatim, and the schema-reconciliation test compares
 * migration output against a database built from the exported schema.
 *
 * Triggers are intentionally NOT created here. Room does not create
 * content-sync triggers while applying migrations, and the exported schema
 * test builds its reference database without them; RoomStudyDatabase creates
 * them lazily (ensureSearchTriggers) before the first search projection
 * refresh so both code paths converge on the same sqlite_master state.
 */
internal val LIBRARY_SEARCH_MIGRATION_31_32 = object : Migration(31, 32) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_search_content` (" +
                "`content_row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`problem_revision_id` TEXT NOT NULL, " +
                "`stem_text` TEXT NOT NULL, " +
                "`options_text` TEXT NOT NULL, " +
                "`solution_text` TEXT NOT NULL, " +
                "`subject` TEXT NOT NULL, " +
                "`chapter` TEXT NOT NULL, " +
                "`knowledge_points` TEXT NOT NULL, " +
                "`tags` TEXT NOT NULL, " +
                "`error_reason` TEXT NOT NULL, " +
                "`formula_tokens` TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_library_search_content_problem_revision_id` ON " +
                "`library_search_content` (`problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `library_search_fts` USING FTS4(" +
                "`stem_text` TEXT NOT NULL, " +
                "`options_text` TEXT NOT NULL, " +
                "`solution_text` TEXT NOT NULL, " +
                "`subject` TEXT NOT NULL, " +
                "`chapter` TEXT NOT NULL, " +
                "`knowledge_points` TEXT NOT NULL, " +
                "`tags` TEXT NOT NULL, " +
                "`error_reason` TEXT NOT NULL, " +
                "`formula_tokens` TEXT NOT NULL, " +
                "content=`library_search_content`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_search_outbox` (" +
                "`outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`revision_id` TEXT NOT NULL, " +
                "`queued_at_epoch_millis` INTEGER NOT NULL)",
        )
        // Initial backfill of every searchable column from the authoritative
        // library rows. Trigger-less at this point, so these inserts do not
        // touch the (intentionally empty) FTS index; the first projection
        // refresh re-segments the text with CjkTextTokenizer and indexes it.
        connection.execSQL(
            """
            INSERT INTO library_search_content
                (problem_revision_id, stem_text, options_text, solution_text, subject,
                 chapter, knowledge_points, tags, error_reason, formula_tokens)
            SELECT
                revision.revision_id,
                revision.title || CHAR(10) || revision.problem_markdown,
                COALESCE(revision.question_document_snapshot, ''),
                COALESCE(revision.answer_spec_snapshot, ''),
                problem.subject,
                COALESCE(
                    (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                     FROM problem_classification_binding AS classification
                     WHERE classification.problem_id = entry.problem_id
                       AND classification.basis_revision_id = revision.revision_id
                       AND classification.dimension = 'CHAPTER'), ''),
                COALESCE(
                    (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                     FROM problem_classification_binding AS classification
                     WHERE classification.problem_id = entry.problem_id
                       AND classification.basis_revision_id = revision.revision_id
                       AND classification.dimension = 'KNOWLEDGE'), ''),
                COALESCE(
                    (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                     FROM problem_classification_binding AS classification
                     WHERE classification.problem_id = entry.problem_id
                       AND classification.basis_revision_id = revision.revision_id
                       AND classification.dimension NOT IN ('CHAPTER', 'KNOWLEDGE')), ''),
                COALESCE(
                    (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                     FROM problem_classification_binding AS classification
                     WHERE classification.problem_id = entry.problem_id
                       AND classification.basis_revision_id = revision.revision_id
                       AND classification.dimension = 'ERROR_CAUSE'), ''),
                ''
            FROM error_book_entry AS entry
            JOIN problem_revision AS revision
                ON revision.revision_id = entry.current_revision_id
               AND revision.problem_id = entry.problem_id
            JOIN problem AS problem
                ON problem.problem_id = entry.problem_id
            WHERE entry.status = 'ACTIVE'
            GROUP BY revision.revision_id
            """.trimIndent(),
        )
    }
}
