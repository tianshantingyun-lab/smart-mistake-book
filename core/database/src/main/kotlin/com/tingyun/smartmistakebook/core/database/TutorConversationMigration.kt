package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val TUTOR_CONVERSATION_MIGRATION_28_29 = object : Migration(28, 29) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_conversation` (
                `conversation_id` TEXT NOT NULL,
                `anchor_kind` TEXT NOT NULL,
                `anchor_id` TEXT,
                `anchor_revision_id` TEXT,
                `status` TEXT NOT NULL,
                `title` TEXT,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                `last_turn_ordinal` INTEGER NOT NULL,
                PRIMARY KEY(`conversation_id`)
            )
            """,
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_conversation_updated_at_epoch_millis` " +
                "ON `tutor_conversation` (`updated_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_conversation_status` " +
                "ON `tutor_conversation` (`status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_tutor_conversation_anchor_kind_anchor_id_anchor_revision_id` " +
                "ON `tutor_conversation` (`anchor_kind`, `anchor_id`, `anchor_revision_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_message` (
                `message_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `role` TEXT NOT NULL,
                `body_markdown` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `logical_operation_id` TEXT,
                `reply_to_message_id` TEXT,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `completed_at_epoch_millis` INTEGER,
                `error_code` TEXT,
                PRIMARY KEY(`message_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `tutor_conversation`(`conversation_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """,
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_tutor_message_conversation_id_ordinal` " +
                "ON `tutor_message` (`conversation_id`, `ordinal`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_message_logical_operation_id` " +
                "ON `tutor_message` (`logical_operation_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_message_status` " +
                "ON `tutor_message` (`status`)",
        )
    }
}

internal val TUTOR_CONVERSATION_DRAFT_MIGRATION_30_31 = object : Migration(30, 31) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tutor_conversation` ADD COLUMN `student_draft` TEXT",
        )
        // v31 replaces the boolean study-day trust flag with the richer
        // time_trust classification; legacy rows were only admitted when
        // trusted, so they default to TRUSTED.
        connection.execSQL(
            "ALTER TABLE `independent_correct_observation` " +
                "ADD COLUMN `time_trust` TEXT NOT NULL DEFAULT 'TRUSTED'",
        )
    }
}
