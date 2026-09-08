package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration chain 1→10 (v1..v9).
 * Split from StudyDatabase.kt to keep the database entry point readable.
 * Every migration is registered via StudyDatabaseFactory.
 */

internal val REVIEW_RECEIPT_MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `review_session_advance_receipt` (
                `review_session_id` TEXT NOT NULL,
                `from_version` INTEGER NOT NULL,
                `to_version` INTEGER NOT NULL,
                `review_queue_item_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `attempt_id` TEXT NOT NULL,
                `submission_id` TEXT NOT NULL,
                `presentation_id` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`attempt_id`),
                FOREIGN KEY(`review_session_id`, `from_version`)
                    REFERENCES `review_session_revision`(`review_session_id`, `state_version`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`review_session_id`, `to_version`)
                    REFERENCES `review_session_revision`(`review_session_id`, `state_version`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`review_queue_item_id`)
                    REFERENCES `review_queue_item`(`review_queue_item_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`attempt_id`)
                    REFERENCES `attempt_event`(`attempt_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_review_session_advance_receipt_review_session_id_from_version`
            ON `review_session_advance_receipt` (`review_session_id`, `from_version`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_review_session_advance_receipt_review_session_id_to_version`
            ON `review_session_advance_receipt` (`review_session_id`, `to_version`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_review_session_advance_receipt_review_queue_item_id`
            ON `review_session_advance_receipt` (`review_queue_item_id`)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_review_session_advance_receipt_practice_unit_id`
            ON `review_session_advance_receipt` (`practice_unit_id`)
            """.trimIndent(),
        )
    }
}

internal val CAPTURE_MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `problem_revision` ADD COLUMN `question_document_snapshot` TEXT",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `canonical_source_asset` (
                `source_asset_id` TEXT NOT NULL,
                `content_sha256` TEXT NOT NULL,
                `relative_path` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `byte_size` INTEGER NOT NULL,
                `width` INTEGER NOT NULL,
                `height` INTEGER NOT NULL,
                `source_type` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`source_asset_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_source_asset_content_sha256` ON `canonical_source_asset` (`content_sha256`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_source_asset_relative_path` ON `canonical_source_asset` (`relative_path`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft` (
                `draft_id` TEXT NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `current_revision_number` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`),
                FOREIGN KEY(`source_asset_id`) REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_source_asset_id` ON `problem_draft` (`source_asset_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_status_updated_at_epoch_millis` ON `problem_draft` (`status`, `updated_at_epoch_millis`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_revision` (
                `draft_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `basis_revision_number` INTEGER,
                `subject` TEXT,
                `title` TEXT NOT NULL,
                `question_document_snapshot` TEXT NOT NULL,
                `document_fingerprint` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`, `revision_number`),
                FOREIGN KEY(`draft_id`) REFERENCES `problem_draft`(`draft_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_revision_draft_id` ON `problem_draft_revision` (`draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_revision_document_fingerprint` ON `problem_draft_revision` (`document_fingerprint`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_revision_source_asset` (
                `problem_revision_id` TEXT NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                PRIMARY KEY(`problem_revision_id`, `source_asset_id`, `role`),
                FOREIGN KEY(`problem_revision_id`) REFERENCES `problem_revision`(`revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_asset_id`) REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_revision_source_asset_problem_revision_id` ON `problem_revision_source_asset` (`problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_revision_source_asset_source_asset_id` ON `problem_revision_source_asset` (`source_asset_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_commit_receipt` (
                `command_id` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `draft_id` TEXT NOT NULL,
                `draft_revision_number` INTEGER NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `error_book_entry_id` TEXT NOT NULL,
                `committed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`command_id`),
                FOREIGN KEY(`draft_id`) REFERENCES `problem_draft`(`draft_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`problem_id`) REFERENCES `problem`(`problem_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`problem_revision_id`) REFERENCES `problem_revision`(`revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`) REFERENCES `practice_unit`(`practice_unit_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`error_book_entry_id`) REFERENCES `error_book_entry`(`entry_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_draft_id` ON `problem_draft_commit_receipt` (`draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_problem_id` ON `problem_draft_commit_receipt` (`problem_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_problem_revision_id` ON `problem_draft_commit_receipt` (`problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_practice_unit_id` ON `problem_draft_commit_receipt` (`practice_unit_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_commit_receipt_error_book_entry_id` ON `problem_draft_commit_receipt` (`error_book_entry_id`)",
        )
    }
}

internal val MODEL_TASK_MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `model_task` (
                `task_id` TEXT NOT NULL,
                `request_id` TEXT NOT NULL,
                `request_fingerprint` TEXT NOT NULL,
                `request_snapshot` TEXT NOT NULL,
                `task_kind` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_model_task_request_id` ON `model_task` (`request_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_subject_id_task_kind` ON `model_task` (`subject_id`, `task_kind`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_status_updated_at_epoch_millis` ON `model_task` (`status`, `updated_at_epoch_millis`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `model_task_event` (
                `task_id` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `previous_status` TEXT,
                `next_status` TEXT NOT NULL,
                `stage` TEXT NOT NULL,
                `user_message` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `provider_snapshot` TEXT,
                `output_snapshot` TEXT,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `failure_retryable` INTEGER,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `state_version`),
                FOREIGN KEY(`task_id`) REFERENCES `model_task`(`task_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_model_task_event_task_id` ON `model_task_event` (`task_id`)",
        )
    }
}

internal val TUTOR_SESSION_MIGRATION_4_5 = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_session` (
                `session_id` TEXT NOT NULL,
                `draft_id` TEXT NOT NULL,
                `draft_revision_number` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`),
                FOREIGN KEY(`draft_id`, `draft_revision_number`)
                    REFERENCES `problem_draft_revision`(`draft_id`, `revision_number`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_session_draft_id` ON `tutor_session` (`draft_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_draft_id_draft_revision_number` ON `tutor_session` (`draft_id`, `draft_revision_number`)",
        )
    }
}

internal val DRAFT_EDIT_WORKSPACE_MIGRATION_5_6 = object : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_edit_snapshot` (
                `draft_id` TEXT NOT NULL,
                `basis_revision_number` INTEGER NOT NULL,
                `workspace_version` INTEGER NOT NULL,
                `snapshot_schema_version` INTEGER NOT NULL,
                `workspace_snapshot` TEXT NOT NULL,
                `workspace_fingerprint` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`),
                FOREIGN KEY(`draft_id`, `basis_revision_number`)
                    REFERENCES `problem_draft_revision`(`draft_id`, `revision_number`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_edit_snapshot_draft_id_basis_revision_number` ON `problem_draft_edit_snapshot` (`draft_id`, `basis_revision_number`)",
        )
    }
}

internal val CAPTURE_REQUEST_BINDING_MIGRATION_6_7 = object : Migration(6, 7) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `problem_draft` ADD COLUMN `request_fingerprint` TEXT DEFAULT NULL",
        )
    }
}

internal val PROBLEM_ORGANIZATION_MIGRATION_7_8 = object : Migration(7, 8) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_classification_binding` (
                `binding_id` TEXT NOT NULL,
                `problem_id` TEXT NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `dimension` TEXT NOT NULL,
                `label_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `taxonomy_version` TEXT NOT NULL,
                `acceptance_source` TEXT NOT NULL,
                `accepted_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`binding_id`),
                FOREIGN KEY(`problem_id`, `basis_revision_id`)
                    REFERENCES `problem_revision`(`problem_id`, `revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_classification_binding_problem_id_basis_revision_id` ON `problem_classification_binding` (`problem_id`, `basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_classification_binding_dimension_label_id` ON `problem_classification_binding` (`dimension`, `label_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_classification_binding_problem_id_basis_revision_id_dimension_label_id` ON `problem_classification_binding` (`problem_id`, `basis_revision_id`, `dimension`, `label_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_organization_receipt` (
                `command_id` TEXT NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `classification_count` INTEGER NOT NULL,
                `relation_count` INTEGER NOT NULL,
                `accepted_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`command_id`),
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_organization_receipt_problem_id_problem_revision_id` ON `problem_organization_receipt` (`problem_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_organization_receipt_practice_unit_id_problem_revision_id` ON `problem_organization_receipt` (`practice_unit_id`, `problem_revision_id`)",
        )
    }
}

internal val DRAFT_SOURCE_BUNDLE_MIGRATION_8_9 = object : Migration(8, 9) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_draft_source_asset` (
                `draft_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `attached_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`draft_id`, `page_index`),
                FOREIGN KEY(`draft_id`) REFERENCES `problem_draft`(`draft_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_asset_id`) REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_draft_source_asset_source_asset_id` ON `problem_draft_source_asset` (`source_asset_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_draft_source_asset_draft_id_source_asset_id` ON `problem_draft_source_asset` (`draft_id`, `source_asset_id`)",
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `problem_draft_source_asset`
                (`draft_id`, `page_index`, `source_asset_id`, `attached_at_epoch_millis`)
            SELECT `draft_id`, 0, `source_asset_id`, `created_at_epoch_millis`
            FROM `problem_draft`
            """.trimIndent(),
        )
    }
}

internal val TUTOR_INTERACTION_MIGRATION_9_10 = object : Migration(9, 10) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_turn_response` (
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `diagnostic_stem_markdown` TEXT NOT NULL,
                `selected_choice_id` TEXT NOT NULL,
                `selected_choice_markdown` TEXT NOT NULL,
                `selection_was_correct` INTEGER NOT NULL,
                `feedback_markdown` TEXT NOT NULL,
                `requested_move` TEXT,
                `solution_revealed` INTEGER NOT NULL,
                `submitted_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`, `cycle_ordinal`, `turn_ordinal`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_turn_response_question_document_id_revision_number` ON `tutor_turn_response` (`question_document_id`, `revision_number`)",
        )
    }
}

