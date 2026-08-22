package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v31 -> v32: library full-text search projection (audit PR-09).
 *
 * Creates the materialized content table, the external-content FTS4 index,
 * and the outbox table plus triggers that keep it fed on every
 * problem_revision insert/update/delete. The FTS index itself starts empty;
 * the first search triggers a segmented full rebuild in [RoomStudyDatabase]
 * so Chinese text is indexed with proper tokenization.
 */
internal val LIBRARY_SEARCH_FTS_MIGRATION_31_32 = object : Migration(31, 32) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_search_content` (
                `rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `stem_text` TEXT NOT NULL,
                `options_text` TEXT NOT NULL,
                `solution_text` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `chapter` TEXT NOT NULL,
                `knowledge_points` TEXT NOT NULL,
                `tags` TEXT NOT NULL,
                `error_reason` TEXT NOT NULL,
                `formula_tokens` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_library_search_content_problem_revision_id` " +
                "ON `library_search_content` (`problem_revision_id`)",
        )
        connection.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `library_search_fts`
            USING FTS4(
                `stem_text` TEXT NOT NULL,
                `options_text` TEXT NOT NULL,
                `solution_text` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `chapter` TEXT NOT NULL,
                `knowledge_points` TEXT NOT NULL,
                `tags` TEXT NOT NULL,
                `error_reason` TEXT NOT NULL,
                `formula_tokens` TEXT NOT NULL,
                content=`library_search_content`
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_search_outbox` (
                `outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `revision_id` TEXT NOT NULL,
                `queued_at_epoch_millis` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_insert
            AFTER INSERT ON problem_revision
            BEGIN
                INSERT INTO library_search_outbox (revision_id, queued_at_epoch_millis)
                VALUES (NEW.revision_id, NEW.created_at_epoch_millis);
            END
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_update
            AFTER UPDATE ON problem_revision
            BEGIN
                INSERT INTO library_search_outbox (revision_id, queued_at_epoch_millis)
                VALUES (NEW.revision_id, NEW.created_at_epoch_millis);
            END
            """.trimIndent(),
        )
        // Initial content backfill from the authoritative revision rows; the
        // runtime rebuild re-segments these into CJK tokens before indexing.
        connection.execSQL(
            """
            INSERT INTO library_search_content
                (problem_revision_id, stem_text, options_text, solution_text, subject,
                 chapter, knowledge_points, tags, error_reason, formula_tokens)
            SELECT revision.revision_id,
                   COALESCE(revision.title, '') || CHAR(10) ||
                       COALESCE(revision.problem_markdown, ''),
                   '', '', '', '', '', '', '', ''
            FROM problem_revision AS revision
            WHERE EXISTS (
                SELECT 1 FROM error_book_entry AS entry
                WHERE entry.current_revision_id = revision.revision_id
                  AND entry.status = 'ACTIVE'
            )
            """.trimIndent(),
        )
    }
}
