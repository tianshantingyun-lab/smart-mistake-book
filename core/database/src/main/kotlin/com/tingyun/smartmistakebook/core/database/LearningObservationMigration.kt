package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds empty observation workflow and ledger tables. Existing v31 rows are intentionally not
 * transformed: an imported mistake, accepted binding, or elapsed time is not mastery evidence.
 */
internal val LEARNING_OBSERVATION_MIGRATION_31_32 = object : Migration(31, 32) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_observation_source_authority` (
                `learner_id` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `source_reference_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `source_payload_fingerprint` TEXT NOT NULL,
                `verified_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`learner_id`, `source`, `source_reference_id`),
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_source_authority_practice_unit_id_problem_revision_id` ON `learning_observation_source_authority` (`practice_unit_id`, `problem_revision_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_observation_candidate` (
                `candidate_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `source_reference_id` TEXT NOT NULL,
                `practice_unit_id` TEXT,
                `problem_revision_id` TEXT,
                `direction` TEXT NOT NULL,
                `evidence_level` TEXT NOT NULL,
                `evidence_weight` REAL NOT NULL,
                `independence` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                `model_version` TEXT NOT NULL,
                `evidence_locator` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `retry_count` INTEGER NOT NULL,
                `payload_fingerprint` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`candidate_id`),
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_candidate_practice_unit_id_problem_revision_id` ON `learning_observation_candidate` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_candidate_learner_id_status_retry_count` ON `learning_observation_candidate` (`learner_id`, `status`, `retry_count`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_observation_candidate_learner_id_source_source_reference_id` ON `learning_observation_candidate` (`learner_id`, `source`, `source_reference_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_observation_candidate_payload_fingerprint` ON `learning_observation_candidate` (`payload_fingerprint`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_observation_candidate_attribution` (
                `candidate_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `binding_id` TEXT NOT NULL,
                `knowledge_node_id` TEXT NOT NULL,
                `weight` REAL NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `taxonomy_version` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `certainty` TEXT NOT NULL,
                PRIMARY KEY(`candidate_id`, `ordinal`),
                FOREIGN KEY(`candidate_id`) REFERENCES `learning_observation_candidate`(`candidate_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_observation_candidate_attribution_candidate_id_binding_id` ON `learning_observation_candidate_attribution` (`candidate_id`, `binding_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_observation_candidate_attribution_candidate_id_knowledge_node_id` ON `learning_observation_candidate_attribution` (`candidate_id`, `knowledge_node_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `attributed_learning_observation_event` (
                `event_id` TEXT NOT NULL,
                `candidate_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `evidence_level` TEXT NOT NULL,
                `evidence_weight` REAL NOT NULL,
                `independence` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                `confirmed_at_epoch_millis` INTEGER NOT NULL,
                `model_version` TEXT NOT NULL,
                `evidence_locator` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                PRIMARY KEY(`event_id`),
                FOREIGN KEY(`candidate_id`) REFERENCES `learning_observation_candidate`(`candidate_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_attributed_learning_observation_event_candidate_id` ON `attributed_learning_observation_event` (`candidate_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attributed_learning_observation_event_practice_unit_id_problem_revision_id` ON `attributed_learning_observation_event` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_attributed_learning_observation_event_learner_id_event_sequence` ON `attributed_learning_observation_event` (`learner_id`, `event_sequence`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_attributed_learning_observation_event_learner_id_event_id` ON `attributed_learning_observation_event` (`learner_id`, `event_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attributed_learning_observation_event_learner_id_subject_occurred_at_epoch_millis` ON `attributed_learning_observation_event` (`learner_id`, `subject`, `occurred_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_attributed_learning_observation_event_canonical_fingerprint` ON `attributed_learning_observation_event` (`canonical_fingerprint`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_attributed_learning_observation_event_event_id_practice_unit_id` ON `attributed_learning_observation_event` (`event_id`, `practice_unit_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_observation_event_attribution` (
                `event_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `binding_id` TEXT NOT NULL,
                `knowledge_node_id` TEXT NOT NULL,
                `weight` REAL NOT NULL,
                `basis_revision_id` TEXT NOT NULL,
                `taxonomy_version` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `certainty` TEXT NOT NULL,
                PRIMARY KEY(`event_id`, `ordinal`),
                FOREIGN KEY(`event_id`, `practice_unit_id`)
                    REFERENCES `attributed_learning_observation_event`(`event_id`, `practice_unit_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(
                    `binding_id`,
                    `practice_unit_id`,
                    `knowledge_node_id`,
                    `basis_revision_id`,
                    `taxonomy_version`
                ) REFERENCES `practice_unit_knowledge_binding`(
                    `binding_id`,
                    `practice_unit_id`,
                    `knowledge_node_id`,
                    `basis_revision_id`,
                    `taxonomy_version`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_event_attribution_event_id_practice_unit_id` ON `learning_observation_event_attribution` (`event_id`, `practice_unit_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_observation_event_attribution_binding_id_practice_unit_id_knowledge_node_id_basis_revision_id_taxonomy_version` ON `learning_observation_event_attribution` (`binding_id`, `practice_unit_id`, `knowledge_node_id`, `basis_revision_id`, `taxonomy_version`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_observation_event_attribution_event_id_binding_id` ON `learning_observation_event_attribution` (`event_id`, `binding_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_observation_event_attribution_event_id_knowledge_node_id` ON `learning_observation_event_attribution` (`event_id`, `knowledge_node_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learning_evidence_review_case` (
                `review_case_id` TEXT NOT NULL,
                `candidate_id` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `proposed_event_id` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `detail` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `resolved_at_epoch_millis` INTEGER,
                PRIMARY KEY(`review_case_id`),
                FOREIGN KEY(`candidate_id`) REFERENCES `learning_observation_candidate`(`candidate_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_evidence_review_case_candidate_id` ON `learning_evidence_review_case` (`candidate_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_evidence_review_case_learner_id_status_created_at_epoch_millis` ON `learning_evidence_review_case` (`learner_id`, `status`, `created_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_evidence_review_case_candidate_id_proposed_event_id_reason` ON `learning_evidence_review_case` (`candidate_id`, `proposed_event_id`, `reason`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `applied_learning_observation_record` (
                `projection_name` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `event_id` TEXT NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `event_sequence` INTEGER NOT NULL,
                PRIMARY KEY(`projection_name`, `learner_id`, `event_id`),
                FOREIGN KEY(`projection_name`, `learner_id`)
                    REFERENCES `learner_projection_snapshot`(`projection_name`, `learner_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`learner_id`, `event_id`)
                    REFERENCES `attributed_learning_observation_event`(`learner_id`, `event_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_applied_learning_observation_record_projection_name_learner_id` ON `applied_learning_observation_record` (`projection_name`, `learner_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_applied_learning_observation_record_learner_id_event_id` ON `applied_learning_observation_record` (`learner_id`, `event_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_applied_learning_observation_record_projection_name_learner_id_event_sequence` ON `applied_learning_observation_record` (`projection_name`, `learner_id`, `event_sequence`)",
        )
    }
}
