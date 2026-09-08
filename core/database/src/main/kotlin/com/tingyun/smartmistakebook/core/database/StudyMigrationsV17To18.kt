package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Migration chain 16→19 (v16..v18). Split from StudyMigrationsV10To18.kt. */

internal val TUTOR_ANSWER_SURFACE_MIGRATION_16_17 = object : Migration(16, 17) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE `tutor_answer_exposure_v17` (
                `exposure_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `cycle_ordinal` INTEGER NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `surface_kind` TEXT NOT NULL,
                `model_task_request_id` TEXT,
                `response_ordinal` INTEGER,
                `exposed_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`exposure_id`),
                FOREIGN KEY(`model_task_request_id`) REFERENCES `model_task`(`request_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `tutor_answer_exposure_outcome_v17` (
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
                FOREIGN KEY(`session_id`) REFERENCES `tutor_session_problem_anchor`(`session_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `applied_tutor_answer_exposure_record_v17` (
                `projection_name` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `outcome_id` TEXT NOT NULL,
                `exposure_id` TEXT NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                PRIMARY KEY(`projection_name`, `learner_id`, `outcome_id`),
                FOREIGN KEY(`projection_name`, `learner_id`)
                    REFERENCES `learner_projection_snapshot`(`projection_name`, `learner_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        connection.execSQL(
            """
            INSERT INTO `tutor_answer_exposure_v17` (
                `exposure_id`, `learner_id`, `session_id`, `question_document_id`,
                `question_revision_number`, `cycle_ordinal`, `turn_ordinal`, `surface_kind`,
                `model_task_request_id`, `response_ordinal`, `exposed_at_epoch_millis`
            )
            SELECT
                `exposure_id`, `learner_id`, `session_id`, `question_document_id`,
                `question_revision_number`, `cycle_ordinal`, `turn_ordinal`, 'LEGACY_TURN',
                NULL, NULL, `exposed_at_epoch_millis`
            FROM `tutor_answer_exposure`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `tutor_answer_exposure_outcome_v17` (
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            )
            SELECT
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            FROM `tutor_answer_exposure_outcome`
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `applied_tutor_answer_exposure_record_v17` (
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            )
            SELECT
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            FROM `applied_tutor_answer_exposure_record`
            """.trimIndent(),
        )

        connection.execSQL("DROP TABLE `applied_tutor_answer_exposure_record`")
        connection.execSQL("DROP TABLE `tutor_answer_exposure_outcome`")
        connection.execSQL("DROP TABLE `tutor_answer_exposure`")
        connection.execSQL(
            "ALTER TABLE `tutor_answer_exposure_v17` RENAME TO `tutor_answer_exposure`",
        )
        connection.execSQL(
            """
            CREATE TABLE `tutor_answer_exposure_outcome` (
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
            """
            INSERT INTO `tutor_answer_exposure_outcome` (
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            )
            SELECT
                `outcome_id`, `exposure_id`, `learner_id`, `session_id`,
                `question_document_id`, `question_revision_number`, `cycle_ordinal`,
                `turn_ordinal`, `problem_revision_id`, `practice_unit_id`, `event_sequence`,
                `canonical_fingerprint`, `occurred_at_epoch_millis`
            FROM `tutor_answer_exposure_outcome_v17`
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX `index_tutor_answer_exposure_outcome_v17_learner_outcome` " +
                "ON `tutor_answer_exposure_outcome` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            """
            CREATE TABLE `applied_tutor_answer_exposure_record` (
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
            """
            INSERT INTO `applied_tutor_answer_exposure_record` (
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            )
            SELECT
                `projection_name`, `learner_id`, `outcome_id`, `exposure_id`,
                `canonical_fingerprint`, `event_sequence`
            FROM `applied_tutor_answer_exposure_record_v17`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `applied_tutor_answer_exposure_record_v17`")
        connection.execSQL("DROP TABLE `tutor_answer_exposure_outcome_v17`")

        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_learner_id` " +
                "ON `tutor_answer_exposure` (`learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_session_id` " +
                "ON `tutor_answer_exposure` (`session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_session_id_cycle_ordinal_turn_ordinal` " +
                "ON `tutor_answer_exposure` (`session_id`, `cycle_ordinal`, `turn_ordinal`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX `index_tutor_answer_exposure_model_task_request_id` " +
                "ON `tutor_answer_exposure` (`model_task_request_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX `index_tutor_answer_exposure_outcome_exposure_id` " +
                "ON `tutor_answer_exposure_outcome` (`exposure_id`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_tutor_answer_exposure_outcome_session_id` " +
                "ON `tutor_answer_exposure_outcome` (`session_id`)",
        )
        connection.execSQL(
            "CREATE INDEX " +
                "`index_tutor_answer_exposure_outcome_practice_unit_id_problem_revision_id` " +
                "ON `tutor_answer_exposure_outcome` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX " +
                "`index_tutor_answer_exposure_outcome_learner_id_event_sequence` " +
                "ON `tutor_answer_exposure_outcome` (`learner_id`, `event_sequence`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX " +
                "`index_tutor_answer_exposure_outcome_learner_id_outcome_id` " +
                "ON `tutor_answer_exposure_outcome` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            "DROP INDEX `index_tutor_answer_exposure_outcome_v17_learner_outcome`",
        )
        connection.execSQL(
            "CREATE INDEX " +
                "`index_applied_tutor_answer_exposure_record_projection_name_learner_id` " +
                "ON `applied_tutor_answer_exposure_record` (`projection_name`, `learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX " +
                "`index_applied_tutor_answer_exposure_record_learner_id_outcome_id` " +
                "ON `applied_tutor_answer_exposure_record` (`learner_id`, `outcome_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX " +
                "`index_applied_tutor_answer_exposure_record_projection_name_learner_id_event_sequence` " +
                "ON `applied_tutor_answer_exposure_record` " +
                "(`projection_name`, `learner_id`, `event_sequence`)",
        )
    }
}

internal val KNOWLEDGE_BASE_MIGRATION_17_18 = object : Migration(17, 18) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `canonical_name` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL("UPDATE `knowledge_node` SET `canonical_name` = `display_name`")
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `node_kind` TEXT NOT NULL DEFAULT 'TOPIC'",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `granularity` TEXT NOT NULL DEFAULT 'TOPIC'",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `aliases_text` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `boundary_markdown` TEXT",
        )
        connection.execSQL(
            "ALTER TABLE `knowledge_node` " +
                "ADD COLUMN `verification_status` TEXT NOT NULL DEFAULT 'MODEL_CANDIDATE'",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_subject_canonical_name` " +
                "ON `knowledge_node` (`subject`, `canonical_name`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_subject_granularity` " +
                "ON `knowledge_node` (`subject`, `granularity`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_source` (
                `source_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `publisher` TEXT,
                `edition` TEXT,
                `source_uri` TEXT,
                `license_status` TEXT NOT NULL,
                `content_fingerprint` TEXT NOT NULL,
                `imported_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`source_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_source_content_fingerprint` " +
                "ON `knowledge_source` (`content_fingerprint`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_source_subject_source_type` " +
                "ON `knowledge_source` (`subject`, `source_type`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_node_source_binding` (
                `knowledge_node_id` TEXT NOT NULL,
                `source_id` TEXT NOT NULL,
                `source_locator` TEXT NOT NULL,
                `derivation_note` TEXT NOT NULL,
                `reviewed_at_epoch_millis` INTEGER,
                PRIMARY KEY(`knowledge_node_id`, `source_id`, `source_locator`),
                FOREIGN KEY(`knowledge_node_id`) REFERENCES `knowledge_node`(`knowledge_node_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_id`) REFERENCES `knowledge_source`(`source_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_source_binding_knowledge_node_id` " +
                "ON `knowledge_node_source_binding` (`knowledge_node_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_node_source_binding_source_id` " +
                "ON `knowledge_node_source_binding` (`source_id`)",
        )
    }
}

internal val KNOWLEDGE_GROUNDING_MIGRATION_18_19 = object : Migration(18, 19) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_grounding_request` (
                `grounding_request_id` TEXT NOT NULL,
                `grounding_key` TEXT NOT NULL,
                `organization_request_id` TEXT NOT NULL,
                `organization_request_fingerprint` TEXT NOT NULL,
                `request_ordinal` INTEGER NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `query` TEXT NOT NULL,
                `expected_parent_knowledge_display_name` TEXT NOT NULL,
                `reason_markdown` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`grounding_request_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_knowledge_grounding_request_organization_request_id_request_ordinal` " +
                "ON `knowledge_grounding_request` (`organization_request_id`, `request_ordinal`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_knowledge_grounding_request_status_created_at_epoch_millis` " +
                "ON `knowledge_grounding_request` (`status`, `created_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_grounding_request_subject_status` " +
                "ON `knowledge_grounding_request` (`subject`, `status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_grounding_request_grounding_key_status` " +
                "ON `knowledge_grounding_request` (`grounding_key`, `status`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_knowledge_grounding_request_problem_revision_id` " +
                "ON `knowledge_grounding_request` (`problem_revision_id`)",
        )
    }
}

