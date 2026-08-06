package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Expands the independent student-mistake authority without reading or joining another database.
 * Legacy rows remain readable: newly authoritative snapshots and source metadata are nullable.
 */
internal val STUDENT_MISTAKE_MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `student_problem_document` " +
                "ADD COLUMN `error_book_entry_id` TEXT",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_problem_document_error_book_entry_id` " +
                "ON `student_problem_document` (`error_book_entry_id`)",
        )
        connection.execSQL(
            "ALTER TABLE `student_problem_revision` " +
                "ADD COLUMN `captured_question_document_wire` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `student_problem_image_reference` ADD COLUMN `width_pixels` INTEGER",
        )
        connection.execSQL(
            "ALTER TABLE `student_problem_image_reference` ADD COLUMN `height_pixels` INTEGER",
        )
        connection.execSQL(
            "ALTER TABLE `student_problem_image_reference` ADD COLUMN `byte_size` INTEGER",
        )
        connection.execSQL(
            "ALTER TABLE `student_problem_image_reference` " +
                "ADD COLUMN `selected_regions_wire` TEXT NOT NULL DEFAULT '0:'",
        )
        connection.execSQL(
            "ALTER TABLE `student_store_outbox` ADD COLUMN `learner_id` TEXT",
        )
        connection.execSQL(
            """
            UPDATE `student_store_outbox`
            SET `learner_id` = (
                SELECT problem.`learner_id`
                FROM `student_problem_revision` AS revision
                INNER JOIN `student_problem_document` AS problem
                    ON problem.`problem_id` = revision.`problem_id`
                WHERE revision.`revision_id` = `student_store_outbox`.`aggregate_id`
                LIMIT 1
            )
            WHERE `learner_id` IS NULL
              AND `source_store` = 'STUDENT_MISTAKES'
            """.trimIndent(),
        )
        connection.execSQL(
            "DROP INDEX IF EXISTS " +
                "`index_student_store_outbox_delivery_state_available_at_epoch_millis_occurred_at_epoch_millis`",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_store_outbox_learner_id_delivery_state_available_at_epoch_millis_occurred_at_epoch_millis` " +
                "ON `student_store_outbox` " +
                "(`learner_id`, `delivery_state`, `available_at_epoch_millis`, " +
                "`occurred_at_epoch_millis`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_problem_solution_analysis` (
                `solution_analysis_id` TEXT NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `summary_markdown` TEXT NOT NULL,
                `final_answer_markdown` TEXT,
                `model_provider_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `analyzer_version` TEXT NOT NULL,
                `result_canonical_fingerprint` TEXT NOT NULL,
                `recorded_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`solution_analysis_id`),
                FOREIGN KEY(`basis_revision_id`)
                    REFERENCES `student_problem_revision`(`revision_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_problem_solution_analysis_basis_revision_id` " +
                "ON `student_problem_solution_analysis` (`basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_problem_solution_analysis_solution_analysis_id_basis_revision_id` " +
                "ON `student_problem_solution_analysis` (`solution_analysis_id`, `basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_solution_analysis_result_canonical_fingerprint` " +
                "ON `student_problem_solution_analysis` (`result_canonical_fingerprint`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_problem_solution_step` (
                `solution_analysis_id` TEXT NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `step_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `summary_markdown` TEXT NOT NULL,
                `reasoning_markdown` TEXT NOT NULL,
                `result_markdown` TEXT,
                `step_canonical_fingerprint` TEXT NOT NULL,
                PRIMARY KEY(`solution_analysis_id`, `ordinal`),
                FOREIGN KEY(`solution_analysis_id`, `basis_revision_id`)
                    REFERENCES `student_problem_solution_analysis`(
                        `solution_analysis_id`,
                        `basis_revision_id`
                    ) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_solution_step_solution_analysis_id_basis_revision_id` " +
                "ON `student_problem_solution_step` (`solution_analysis_id`, `basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_problem_solution_step_solution_analysis_id_step_id` " +
                "ON `student_problem_solution_step` (`solution_analysis_id`, `step_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_solution_step_step_canonical_fingerprint` " +
                "ON `student_problem_solution_step` (`step_canonical_fingerprint`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_problem_error_attribution` (
                `attribution_id` TEXT NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `solution_analysis_id` TEXT,
                `resolution_status` TEXT NOT NULL,
                `rationale_markdown` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `step_ordinal` INTEGER,
                `atomic_reference_id` TEXT,
                `model_provider_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `analyzer_version` TEXT NOT NULL,
                `result_canonical_fingerprint` TEXT NOT NULL,
                `recorded_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`attribution_id`),
                FOREIGN KEY(`basis_revision_id`)
                    REFERENCES `student_problem_revision`(`revision_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`solution_analysis_id`, `basis_revision_id`)
                    REFERENCES `student_problem_solution_analysis`(
                        `solution_analysis_id`,
                        `basis_revision_id`
                    ) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_error_attribution_basis_revision_id_recorded_at_epoch_millis_attribution_id` " +
                "ON `student_problem_error_attribution` " +
                "(`basis_revision_id`, `recorded_at_epoch_millis`, `attribution_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_error_attribution_solution_analysis_id_basis_revision_id` " +
                "ON `student_problem_error_attribution` (`solution_analysis_id`, `basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_error_attribution_result_canonical_fingerprint` " +
                "ON `student_problem_error_attribution` (`result_canonical_fingerprint`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_problem_error_attribution_attribution_id_basis_revision_id` " +
                "ON `student_problem_error_attribution` (`attribution_id`, `basis_revision_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_problem_error_evidence` (
                `attribution_id` TEXT NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `block_id` TEXT NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `evidence_kind` TEXT NOT NULL,
                PRIMARY KEY(`attribution_id`, `ordinal`),
                FOREIGN KEY(`attribution_id`, `basis_revision_id`)
                    REFERENCES `student_problem_error_attribution`(
                        `attribution_id`,
                        `basis_revision_id`
                    ) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_error_evidence_attribution_id_basis_revision_id` " +
                "ON `student_problem_error_evidence` (`attribution_id`, `basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_problem_error_evidence_basis_revision_id_block_id_source_asset_id` " +
                "ON `student_problem_error_evidence` " +
                "(`basis_revision_id`, `block_id`, `source_asset_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_learner_change` (
                `learner_id` TEXT NOT NULL,
                `change_version` INTEGER NOT NULL,
                PRIMARY KEY(`learner_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `student_learner_change` (`learner_id`, `change_version`)
            SELECT DISTINCT `learner_id`, 1 FROM `student_problem_document`
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_mistake_save_receipt` (
                `intent_confirmation_id` TEXT NOT NULL,
                `intent_canonical_fingerprint` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `problem_id` TEXT NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `error_book_entry_id` TEXT NOT NULL,
                `confirmed_at_epoch_millis` INTEGER NOT NULL,
                `saved_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`intent_confirmation_id`),
                FOREIGN KEY(`basis_revision_id`)
                    REFERENCES `student_problem_revision`(`revision_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_mistake_save_receipt_basis_revision_id` " +
                "ON `student_mistake_save_receipt` (`basis_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_mistake_save_receipt_error_book_entry_id` " +
                "ON `student_mistake_save_receipt` (`error_book_entry_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_mistake_save_receipt_intent_canonical_fingerprint` " +
                "ON `student_mistake_save_receipt` (`intent_canonical_fingerprint`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_mistake_migration_checkpoint` (
                `migration_id` TEXT NOT NULL,
                `source_database_canonical_fingerprint` TEXT NOT NULL,
                `last_committed_at_epoch_millis` INTEGER,
                `last_problem_id` TEXT,
                `last_revision_number` INTEGER,
                `last_revision_id` TEXT,
                `imported_record_count` INTEGER NOT NULL,
                `completed` INTEGER NOT NULL,
                `checkpoint_canonical_fingerprint` TEXT NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`migration_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_mistake_migration_checkpoint_source_database_canonical_fingerprint` " +
                "ON `student_mistake_migration_checkpoint` " +
                "(`source_database_canonical_fingerprint`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_mistake_migration_checkpoint_completed` " +
                "ON `student_mistake_migration_checkpoint` (`completed`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_mistake_migration_receipt` (
                `migration_id` TEXT NOT NULL,
                `source_page_canonical_fingerprint` TEXT NOT NULL,
                `imported_record_count` INTEGER NOT NULL,
                `result_last_committed_at_epoch_millis` INTEGER,
                `result_last_problem_id` TEXT,
                `result_last_revision_number` INTEGER,
                `result_last_revision_id` TEXT,
                `result_total_record_count` INTEGER NOT NULL,
                `result_completed` INTEGER NOT NULL,
                `checkpoint_canonical_fingerprint` TEXT NOT NULL,
                `receipt_canonical_fingerprint` TEXT NOT NULL,
                `applied_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`migration_id`, `source_page_canonical_fingerprint`),
                FOREIGN KEY(`migration_id`)
                    REFERENCES `student_mistake_migration_checkpoint`(`migration_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_student_mistake_migration_receipt_migration_id` " +
                "ON `student_mistake_migration_receipt` (`migration_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_student_mistake_migration_receipt_receipt_canonical_fingerprint` " +
                "ON `student_mistake_migration_receipt` (`receipt_canonical_fingerprint`)",
        )
    }
}
