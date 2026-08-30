package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Migration chain 10→15 (v10..v14). Split from StudyMigrationsV10To18.kt. */

internal val BATCH_IMPORT_MIGRATION_10_11 = object : Migration(10, 11) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `batch_import_job` (
                `job_id` TEXT NOT NULL,
                `request_id` TEXT NOT NULL,
                `request_fingerprint` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`job_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_batch_import_job_request_id` ON `batch_import_job` (`request_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `batch_import_page` (
                `job_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `source_uri` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `result_draft_id` TEXT,
                `failure_code` TEXT,
                `attempt_count` INTEGER NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`job_id`, `page_index`),
                FOREIGN KEY(`job_id`) REFERENCES `batch_import_job`(`job_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`result_draft_id`) REFERENCES `problem_draft`(`draft_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_batch_import_page_job_id_status_page_index` ON `batch_import_page` (`job_id`, `status`, `page_index`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_batch_import_page_result_draft_id` ON `batch_import_page` (`result_draft_id`)",
        )
    }
}

internal val TUTOR_ACTION_MIGRATION_11_12 = object : Migration(11, 12) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tutor_turn_response` RENAME TO `tutor_turn_response_v11`",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_turn_response` (
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `diagnostic_stem_markdown` TEXT,
                `selected_choice_id` TEXT,
                `selected_choice_markdown` TEXT,
                `selection_was_correct` INTEGER,
                `feedback_markdown` TEXT,
                `requested_move` TEXT,
                `solution_revealed` INTEGER NOT NULL,
                `submitted_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`, `cycle_ordinal`, `turn_ordinal`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `tutor_turn_response` (
                `session_id`,
                `question_document_id`,
                `revision_number`,
                `cycle_ordinal`,
                `turn_ordinal`,
                `diagnostic_stem_markdown`,
                `selected_choice_id`,
                `selected_choice_markdown`,
                `selection_was_correct`,
                `feedback_markdown`,
                `requested_move`,
                `solution_revealed`,
                `submitted_at_epoch_millis`,
                `updated_at_epoch_millis`
            )
            SELECT
                `session_id`,
                `question_document_id`,
                `revision_number`,
                `cycle_ordinal`,
                `turn_ordinal`,
                `diagnostic_stem_markdown`,
                `selected_choice_id`,
                `selected_choice_markdown`,
                `selection_was_correct`,
                `feedback_markdown`,
                `requested_move`,
                `solution_revealed`,
                `submitted_at_epoch_millis`,
                `updated_at_epoch_millis`
            FROM `tutor_turn_response_v11`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `tutor_turn_response_v11`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_turn_response_question_document_id_revision_number` ON `tutor_turn_response` (`question_document_id`, `revision_number`)",
        )
    }
}

internal val TUTOR_RESPONSE_SLOT_MIGRATION_12_13 = object : Migration(12, 13) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `model_task` ADD COLUMN `tutor_response_ordinal` INTEGER DEFAULT NULL",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_model_task_subject_id_task_kind_tutor_response_ordinal` " +
                "ON `model_task` (`subject_id`, `task_kind`, `tutor_response_ordinal`)",
        )
    }
}

internal val TUTOR_CHOICE_TIMESTAMP_MIGRATION_13_14 = object : Migration(13, 14) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `tutor_turn_response` ADD COLUMN `choice_submitted_at_epoch_millis` INTEGER",
        )
        // v13 stored only a row-level timestamp, so submitted_at is the closest available
        // approximation for legacy rows that already contain a choice.
        connection.execSQL(
            """
            UPDATE `tutor_turn_response`
            SET `choice_submitted_at_epoch_millis` = `submitted_at_epoch_millis`
            WHERE `diagnostic_stem_markdown` IS NOT NULL
            """.trimIndent(),
        )
    }
}

internal val ATTEMPT_RESPONSE_MIGRATION_14_15 = object : Migration(14, 15) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `attempt_event` ADD COLUMN `submitted_choice_id` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `attempt_event` ADD COLUMN `submitted_choice_markdown` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `attempt_event` ADD COLUMN `response_submitted_at_epoch_millis` INTEGER",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_session_problem_anchor` (
                `session_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `anchor_source` TEXT NOT NULL,
                `anchored_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`session_id`),
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_problem_anchor_learner_id` ON `tutor_session_problem_anchor` (`learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_problem_anchor_practice_unit_id_problem_revision_id` ON `tutor_session_problem_anchor` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_session_problem_anchor_problem_revision_id` ON `tutor_session_problem_anchor` (`problem_revision_id`)",
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `tutor_session_problem_anchor` (
                `session_id`, `learner_id`, `problem_revision_id`, `practice_unit_id`,
                `anchor_source`, `anchored_at_epoch_millis`
            )
            SELECT
                tutor.`session_id`,
                'learner:local',
                receipt.`problem_revision_id`,
                receipt.`practice_unit_id`,
                'DRAFT_COMMIT',
                receipt.`committed_at_epoch_millis`
            FROM `tutor_session` AS tutor
            JOIN `problem_draft_commit_receipt` AS receipt
              ON receipt.`draft_id` = tutor.`draft_id`
             AND receipt.`draft_revision_number` = tutor.`draft_revision_number`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_answer_exposure` (
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `exposed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`exposure_id`),
                FOREIGN KEY(`session_id`, `cycle_ordinal`, `turn_ordinal`)
                    REFERENCES `tutor_turn_response`(`session_id`, `cycle_ordinal`, `turn_ordinal`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_learner_id` ON `tutor_answer_exposure` (`learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_session_id` ON `tutor_answer_exposure` (`session_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_session_id_cycle_ordinal_turn_ordinal` ON `tutor_answer_exposure` (`session_id`, `cycle_ordinal`, `turn_ordinal`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tutor_answer_exposure_outcome` (
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`outcome_id`),
                FOREIGN KEY(`exposure_id`) REFERENCES `tutor_answer_exposure`(`exposure_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`session_id`) REFERENCES `tutor_session_problem_anchor`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_exposure_id` ON `tutor_answer_exposure_outcome` (`exposure_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_session_id` ON `tutor_answer_exposure_outcome` (`session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_practice_unit_id_problem_revision_id` ON `tutor_answer_exposure_outcome` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_learner_id_event_sequence` ON `tutor_answer_exposure_outcome` (`learner_id`, `event_sequence`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tutor_answer_exposure_outcome_learner_id_outcome_id` ON `tutor_answer_exposure_outcome` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `applied_tutor_answer_exposure_record` (
                `projection_name` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                PRIMARY KEY(`projection_name`, `learner_id`, `outcome_id`),
                FOREIGN KEY(`projection_name`, `learner_id`)
                    REFERENCES `learner_projection_snapshot`(`projection_name`, `learner_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`learner_id`, `outcome_id`)
                    REFERENCES `tutor_answer_exposure_outcome`(`learner_id`, `outcome_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_applied_tutor_answer_exposure_record_projection_name_learner_id` ON `applied_tutor_answer_exposure_record` (`projection_name`, `learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_applied_tutor_answer_exposure_record_learner_id_outcome_id` ON `applied_tutor_answer_exposure_record` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_applied_tutor_answer_exposure_record_projection_name_learner_id_event_sequence` ON `applied_tutor_answer_exposure_record` (`projection_name`, `learner_id`, `event_sequence`)",
        )
    }
}

