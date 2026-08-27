package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v34 -> v35: split-import job ledger (cut one import into individual
 * questions before they are confirmed).
 *
 * The CREATE TABLE text below must stay byte-identical to the exported
 * 35.json createSql so Room's identity check accepts migrated databases.
 * A job exists only while the student confirms which cut pieces to keep;
 * confirmed pieces move into the normal draft workflows.
 */
internal val SPLIT_IMPORT_MIGRATION_34_35 = object : Migration(34, 35) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `split_import_job` (" +
                "`job_id` TEXT NOT NULL, " +
                "`source_kind` TEXT NOT NULL, " +
                "`source_fingerprint` TEXT NOT NULL, " +
                "`source_uri` TEXT NOT NULL, " +
                "`page_count` INTEGER NOT NULL, " +
                "`question_count` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`created_at_epoch_millis` INTEGER NOT NULL, " +
                "`updated_at_epoch_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`job_id`))",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_split_import_job_source_fingerprint` " +
                "ON `split_import_job` (`source_fingerprint`)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `split_import_question` (" +
                "`job_id` TEXT NOT NULL, " +
                "`question_ordinal` INTEGER NOT NULL, " +
                "`page_index` INTEGER NOT NULL, " +
                "`region_left` REAL NOT NULL, " +
                "`region_top` REAL NOT NULL, " +
                "`region_right` REAL NOT NULL, " +
                "`region_bottom` REAL NOT NULL, " +
                "`selected` INTEGER NOT NULL, " +
                "`confirm_state` TEXT NOT NULL, " +
                "`split_draft_id` TEXT, " +
                "PRIMARY KEY(`job_id`, `question_ordinal`), " +
                "FOREIGN KEY(`job_id`) REFERENCES `split_import_job`(`job_id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`split_draft_id`) REFERENCES `problem_draft`(`draft_id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_split_import_question_job_id_split_draft_id` " +
                "ON `split_import_question` (`job_id`, `split_draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_split_import_question_split_draft_id` " +
                "ON `split_import_question` (`split_draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_split_import_question_job_id_confirm_state` " +
                "ON `split_import_question` (`job_id`, `confirm_state`)",
        )
    }
}