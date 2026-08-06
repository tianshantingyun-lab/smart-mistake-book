package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the resumable Unicode search authority introduced by v5.
 *
 * No legacy problem is normalized inside the schema migration. The bounded application-level
 * backfill owns normalization and keeps the index in PREPARING until every current revision has
 * been processed.
 */
internal val STUDENT_MISTAKE_MIGRATION_4_5 =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STUDENT_MISTAKE_V5_SCHEMA_STATEMENTS.forEach(connection::execSQL)
        }
    }

internal val STUDENT_MISTAKE_V5_SCHEMA_STATEMENTS =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_search_document` (
          `rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `revision_id` TEXT NOT NULL,
          `source_canonical_fingerprint` TEXT NOT NULL,
          `normalized_text` TEXT NOT NULL,
          `tokenized_text` TEXT NOT NULL,
          `indexed_at_epoch_millis` INTEGER NOT NULL,
          FOREIGN KEY(`revision_id`) REFERENCES `student_problem_revision`(`revision_id`)
            ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_problem_search_document_revision_id`
        ON `student_problem_search_document` (`revision_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_problem_search_document_source_canonical_fingerprint`
        ON `student_problem_search_document` (`source_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `student_problem_search_fts`
        USING FTS4(
          `tokenized_text` TEXT NOT NULL,
          content=`student_problem_search_document`,
          tokenize=unicode61
        )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_BEFORE_UPDATE
        BEFORE UPDATE ON `student_problem_search_document`
        BEGIN
          DELETE FROM `student_problem_search_fts` WHERE `docid` = OLD.`rowid`;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_BEFORE_DELETE
        BEFORE DELETE ON `student_problem_search_document`
        BEGIN
          DELETE FROM `student_problem_search_fts` WHERE `docid` = OLD.`rowid`;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_AFTER_UPDATE
        AFTER UPDATE ON `student_problem_search_document`
        BEGIN
          INSERT INTO `student_problem_search_fts` (`docid`, `tokenized_text`)
          VALUES (NEW.`rowid`, NEW.`tokenized_text`);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_AFTER_INSERT
        AFTER INSERT ON `student_problem_search_document`
        BEGIN
          INSERT INTO `student_problem_search_fts` (`docid`, `tokenized_text`)
          VALUES (NEW.`rowid`, NEW.`tokenized_text`);
        END
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `student_problem_search_index_state` (
          `index_key` TEXT NOT NULL,
          `state` TEXT NOT NULL,
          `after_revision_id` TEXT,
          `indexed_document_count` INTEGER NOT NULL,
          `updated_at_epoch_millis` INTEGER NOT NULL,
          PRIMARY KEY(`index_key`)
        )
        """.trimIndent(),
        """
        INSERT OR IGNORE INTO `student_problem_search_index_state` (
          `index_key`, `state`, `after_revision_id`,
          `indexed_document_count`, `updated_at_epoch_millis`
        ) VALUES ('library-search-v1', 'PREPARING', NULL, 0, 0)
        """.trimIndent(),
    )

internal const val STUDENT_PROBLEM_SEARCH_INDEX_KEY = "library-search-v1"
