package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val PROBLEM_ORGANIZATION_WORK_MIGRATION_32_33 = object : Migration(32, 33) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_solution_step` (
                `solution_step_id` TEXT NOT NULL,
                `organization_command_id` TEXT NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `source_commit_receipt_command_id` TEXT NOT NULL,
                `step_ordinal` INTEGER NOT NULL,
                `summary_markdown` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`solution_step_id`),
                FOREIGN KEY(`organization_command_id`)
                    REFERENCES `problem_organization_receipt`(`command_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_commit_receipt_command_id`)
                    REFERENCES `problem_draft_commit_receipt`(`command_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_problem_solution_step_organization_command_id_step_ordinal` " +
                "ON `problem_solution_step` (`organization_command_id`, `step_ordinal`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_problem_solution_step_practice_unit_id_problem_revision_id` " +
                "ON `problem_solution_step` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_problem_solution_step_source_commit_receipt_command_id` " +
                "ON `problem_solution_step` (`source_commit_receipt_command_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_step_knowledge_binding` (
                `solution_step_id` TEXT NOT NULL,
                `knowledge_node_id` TEXT NOT NULL,
                `knowledge_reference_id` TEXT NOT NULL,
                PRIMARY KEY(`solution_step_id`, `knowledge_node_id`),
                FOREIGN KEY(`solution_step_id`)
                    REFERENCES `problem_solution_step`(`solution_step_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`knowledge_node_id`)
                    REFERENCES `knowledge_node`(`knowledge_node_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_step_knowledge_binding_knowledge_node_id` " +
                "ON `problem_step_knowledge_binding` (`knowledge_node_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_error_attribution_candidate` (
                `error_attribution_candidate_id` TEXT NOT NULL,
                `organization_command_id` TEXT NOT NULL,
                `candidate_ordinal` INTEGER NOT NULL,
                `problem_id` TEXT NOT NULL,
                `problem_revision_id` TEXT NOT NULL,
                `practice_unit_id` TEXT NOT NULL,
                `source_commit_receipt_command_id` TEXT NOT NULL,
                `resolution_status` TEXT NOT NULL,
                `solution_step_id` TEXT,
                `knowledge_node_id` TEXT,
                `knowledge_reference_id` TEXT,
                `rationale_markdown` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `model_version` TEXT NOT NULL,
                `created_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`error_attribution_candidate_id`),
                FOREIGN KEY(`organization_command_id`)
                    REFERENCES `problem_organization_receipt`(`command_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`practice_unit_id`, `problem_revision_id`)
                    REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_commit_receipt_command_id`)
                    REFERENCES `problem_draft_commit_receipt`(`command_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`solution_step_id`)
                    REFERENCES `problem_solution_step`(`solution_step_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`knowledge_node_id`)
                    REFERENCES `knowledge_node`(`knowledge_node_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_problem_error_attribution_candidate_organization_command_id_candidate_ordinal` " +
                "ON `problem_error_attribution_candidate` (`organization_command_id`, `candidate_ordinal`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_problem_error_attribution_candidate_practice_unit_id_problem_revision_id` " +
                "ON `problem_error_attribution_candidate` (`practice_unit_id`, `problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_problem_error_attribution_candidate_source_commit_receipt_command_id` " +
                "ON `problem_error_attribution_candidate` (`source_commit_receipt_command_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_error_attribution_candidate_solution_step_id` " +
                "ON `problem_error_attribution_candidate` (`solution_step_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_error_attribution_candidate_knowledge_node_id` " +
                "ON `problem_error_attribution_candidate` (`knowledge_node_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_problem_error_attribution_candidate_resolution_status_created_at_epoch_millis` " +
                "ON `problem_error_attribution_candidate` " +
                "(`resolution_status`, `created_at_epoch_millis`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_error_candidate_evidence` (
                `error_attribution_candidate_id` TEXT NOT NULL,
                `evidence_ordinal` INTEGER NOT NULL,
                `block_id` TEXT NOT NULL,
                `source_asset_id` TEXT NOT NULL,
                `evidence_kind` TEXT NOT NULL,
                PRIMARY KEY(`error_attribution_candidate_id`, `evidence_ordinal`),
                FOREIGN KEY(`error_attribution_candidate_id`)
                    REFERENCES `problem_error_attribution_candidate`(`error_attribution_candidate_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`source_asset_id`)
                    REFERENCES `canonical_source_asset`(`source_asset_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_problem_error_candidate_evidence_source_asset_id` " +
                "ON `problem_error_candidate_evidence` (`source_asset_id`)",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `problem_organization_work` (
                `work_id` TEXT NOT NULL,
                `commit_receipt_command_id` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `state_version` INTEGER NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `not_before_epoch_millis` INTEGER NOT NULL,
                `request_id` TEXT,
                `request_snapshot` TEXT,
                `authorization_grant_snapshot` TEXT,
                `lease_owner` TEXT,
                `lease_expires_at_epoch_millis` INTEGER,
                `failure_code` TEXT,
                `failure_message` TEXT,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`work_id`),
                FOREIGN KEY(`commit_receipt_command_id`)
                    REFERENCES `problem_draft_commit_receipt`(`command_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_problem_organization_work_commit_receipt_command_id` " +
                "ON `problem_organization_work` (`commit_receipt_command_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_problem_organization_work_status_not_before_epoch_millis_created_at_epoch_millis` " +
                "ON `problem_organization_work` " +
                "(`status`, `not_before_epoch_millis`, `created_at_epoch_millis`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_organization_work_request_id` " +
                "ON `problem_organization_work` (`request_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_problem_organization_work_lease_expires_at_epoch_millis` " +
                "ON `problem_organization_work` (`lease_expires_at_epoch_millis`)",
        )
    }
}
