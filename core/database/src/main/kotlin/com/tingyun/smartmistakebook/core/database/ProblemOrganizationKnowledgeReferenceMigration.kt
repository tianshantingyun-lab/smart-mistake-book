package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Removes the legacy knowledge catalog as a referential authority for organization records.
 *
 * Version 39 stored only a node id and an organization-taxonomy version. It did not persist the
 * catalog pack version or proof provenance, so no existing row can honestly be promoted to a
 * versioned KnowledgeNodeRef. Those rows are preserved and explicitly marked for reattribution.
 */
internal val PROBLEM_ORGANIZATION_KNOWLEDGE_REFERENCE_MIGRATION_39_40 =
    object : Migration(39, 40) {
        override suspend fun migrate(connection: SQLiteConnection) {
            detachPracticeUnitKnowledgeBindingDependents(connection)
            detachProblemErrorAttributionCandidateDependents(connection)
            rebuildPracticeUnitKnowledgeBindings(connection)
            restorePracticeUnitKnowledgeBindingDependents(connection)
            rebuildProblemStepKnowledgeBindings(connection)
            rebuildProblemErrorAttributionCandidates(connection)
            restoreProblemErrorAttributionCandidateDependents(connection)
        }
    }

/**
 * SQLite applies RESTRICT immediately when a referenced table is dropped, even when foreign-key
 * checks are deferred. Preserve the two historical-evidence tables that reference organization
 * bindings, remove their constraints during the parent rebuild, and restore them afterwards.
 */
private fun detachPracticeUnitKnowledgeBindingDependents(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `assessment_evidence_attribution_v39_backup` AS
        SELECT
            `snapshot_id`,
            `binding_id`,
            `practice_unit_id`,
            `knowledge_node_id`,
            `weight`,
            `basis_revision_id`,
            `taxonomy_version`,
            `role`,
            `certainty`
        FROM `assessment_evidence_attribution`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `assessment_evidence_attribution`")
    connection.execSQL(
        """
        CREATE TABLE `learning_observation_event_attribution_v39_backup` AS
        SELECT
            `event_id`,
            `practice_unit_id`,
            `ordinal`,
            `binding_id`,
            `knowledge_node_id`,
            `weight`,
            `basis_revision_id`,
            `taxonomy_version`,
            `role`,
            `certainty`
        FROM `learning_observation_event_attribution`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `learning_observation_event_attribution`")
}

private fun restorePracticeUnitKnowledgeBindingDependents(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `assessment_evidence_attribution` (
            `snapshot_id` TEXT NOT NULL,
            `binding_id` TEXT NOT NULL,
            `practice_unit_id` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `weight` REAL NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `taxonomy_version` TEXT NOT NULL,
            `role` TEXT NOT NULL,
            `certainty` TEXT NOT NULL,
            PRIMARY KEY(`snapshot_id`, `binding_id`),
            FOREIGN KEY(
                `snapshot_id`,
                `practice_unit_id`,
                `basis_revision_id`,
                `taxonomy_version`
            ) REFERENCES `assessment_evidence_snapshot`(
                `snapshot_id`,
                `practice_unit_id`,
                `problem_revision_id`,
                `taxonomy_version`
            ) ON UPDATE NO ACTION ON DELETE CASCADE,
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
        """
        INSERT INTO `assessment_evidence_attribution`
        SELECT
            `snapshot_id`,
            `binding_id`,
            `practice_unit_id`,
            `knowledge_node_id`,
            `weight`,
            `basis_revision_id`,
            `taxonomy_version`,
            `role`,
            `certainty`
        FROM `assessment_evidence_attribution_v39_backup`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `assessment_evidence_attribution_v39_backup`")
    connection.execSQL(
        "CREATE INDEX `index_assessment_evidence_attribution_snapshot_id` " +
            "ON `assessment_evidence_attribution` (`snapshot_id`)",
    )
    connection.execSQL(
        "CREATE INDEX `index_assessment_evidence_attribution_knowledge_node_id` " +
            "ON `assessment_evidence_attribution` (`knowledge_node_id`)",
    )
    connection.execSQL(
        """
        CREATE INDEX
            `index_assessment_evidence_attribution_snapshot_id_practice_unit_id_basis_revision_id_taxonomy_version`
        ON `assessment_evidence_attribution` (
            `snapshot_id`,
            `practice_unit_id`,
            `basis_revision_id`,
            `taxonomy_version`
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX
            `index_assessment_evidence_attribution_binding_id_practice_unit_id_knowledge_node_id_basis_revision_id_taxonomy_version`
        ON `assessment_evidence_attribution` (
            `binding_id`,
            `practice_unit_id`,
            `knowledge_node_id`,
            `basis_revision_id`,
            `taxonomy_version`
        )
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE `learning_observation_event_attribution` (
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
                REFERENCES `attributed_learning_observation_event`(
                    `event_id`,
                    `practice_unit_id`
                ) ON UPDATE NO ACTION ON DELETE CASCADE,
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
        """
        INSERT INTO `learning_observation_event_attribution`
        SELECT
            `event_id`,
            `practice_unit_id`,
            `ordinal`,
            `binding_id`,
            `knowledge_node_id`,
            `weight`,
            `basis_revision_id`,
            `taxonomy_version`,
            `role`,
            `certainty`
        FROM `learning_observation_event_attribution_v39_backup`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `learning_observation_event_attribution_v39_backup`")
    connection.execSQL(
        """
        CREATE INDEX
            `index_learning_observation_event_attribution_event_id_practice_unit_id`
        ON `learning_observation_event_attribution` (`event_id`, `practice_unit_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX
            `index_learning_observation_event_attribution_binding_id_practice_unit_id_knowledge_node_id_basis_revision_id_taxonomy_version`
        ON `learning_observation_event_attribution` (
            `binding_id`,
            `practice_unit_id`,
            `knowledge_node_id`,
            `basis_revision_id`,
            `taxonomy_version`
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX
            `index_learning_observation_event_attribution_event_id_binding_id`
        ON `learning_observation_event_attribution` (`event_id`, `binding_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX
            `index_learning_observation_event_attribution_event_id_knowledge_node_id`
        ON `learning_observation_event_attribution` (`event_id`, `knowledge_node_id`)
        """.trimIndent(),
    )
}

private fun detachProblemErrorAttributionCandidateDependents(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `problem_error_candidate_evidence_v39_backup` AS
        SELECT
            `error_attribution_candidate_id`,
            `evidence_ordinal`,
            `block_id`,
            `source_asset_id`,
            `evidence_kind`
        FROM `problem_error_candidate_evidence`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `problem_error_candidate_evidence`")
}

private fun restoreProblemErrorAttributionCandidateDependents(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `problem_error_candidate_evidence` (
            `error_attribution_candidate_id` TEXT NOT NULL,
            `evidence_ordinal` INTEGER NOT NULL,
            `block_id` TEXT NOT NULL,
            `source_asset_id` TEXT NOT NULL,
            `evidence_kind` TEXT NOT NULL,
            PRIMARY KEY(`error_attribution_candidate_id`, `evidence_ordinal`),
            FOREIGN KEY(`error_attribution_candidate_id`)
                REFERENCES `problem_error_attribution_candidate`(
                    `error_attribution_candidate_id`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`source_asset_id`)
                REFERENCES `canonical_source_asset`(`source_asset_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `problem_error_candidate_evidence`
        SELECT
            `error_attribution_candidate_id`,
            `evidence_ordinal`,
            `block_id`,
            `source_asset_id`,
            `evidence_kind`
        FROM `problem_error_candidate_evidence_v39_backup`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `problem_error_candidate_evidence_v39_backup`")
    connection.execSQL(
        "CREATE INDEX `index_problem_error_candidate_evidence_source_asset_id` " +
            "ON `problem_error_candidate_evidence` (`source_asset_id`)",
    )
}

private fun rebuildPracticeUnitKnowledgeBindings(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `practice_unit_knowledge_binding_v40` (
            `binding_id` TEXT NOT NULL,
            `practice_unit_id` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `knowledge_subject` TEXT,
            `knowledge_taxonomy_version` TEXT,
            `knowledge_pack_version` TEXT,
            `knowledge_manifest_fingerprint` TEXT,
            `knowledge_activation_generation` INTEGER,
            `knowledge_reference_status` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `strength` REAL NOT NULL,
            `source_type` TEXT NOT NULL,
            `taxonomy_version` TEXT NOT NULL,
            `accepted_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`binding_id`),
            FOREIGN KEY(`practice_unit_id`, `basis_revision_id`)
                REFERENCES `practice_unit`(`practice_unit_id`, `problem_revision_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `practice_unit_knowledge_binding_v40` (
            `binding_id`,
            `practice_unit_id`,
            `knowledge_node_id`,
            `knowledge_subject`,
            `knowledge_taxonomy_version`,
            `knowledge_pack_version`,
            `knowledge_manifest_fingerprint`,
            `knowledge_activation_generation`,
            `knowledge_reference_status`,
            `basis_revision_id`,
            `strength`,
            `source_type`,
            `taxonomy_version`,
            `accepted_at_epoch_millis`
        )
        SELECT
            `binding_id`,
            `practice_unit_id`,
            `knowledge_node_id`,
            NULL,
            NULL,
            NULL,
            NULL,
            NULL,
            '${StudyDbValue.KnowledgeReferenceStatus.PENDING_REATTRIBUTION}',
            `basis_revision_id`,
            `strength`,
            `source_type`,
            `taxonomy_version`,
            `accepted_at_epoch_millis`
        FROM `practice_unit_knowledge_binding`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `practice_unit_knowledge_binding`")
    connection.execSQL(
        "ALTER TABLE `practice_unit_knowledge_binding_v40` " +
            "RENAME TO `practice_unit_knowledge_binding`",
    )
    connection.execSQL(
        "CREATE INDEX `index_practice_unit_knowledge_binding_practice_unit_id` " +
            "ON `practice_unit_knowledge_binding` (`practice_unit_id`)",
    )
    connection.execSQL(
        "CREATE INDEX `index_practice_unit_knowledge_binding_basis_revision_id` " +
            "ON `practice_unit_knowledge_binding` (`basis_revision_id`)",
    )
    connection.execSQL(
        "CREATE INDEX `index_practice_unit_knowledge_binding_practice_unit_id_basis_revision_id` " +
            "ON `practice_unit_knowledge_binding` (`practice_unit_id`, `basis_revision_id`)",
    )
    connection.execSQL(
        "CREATE INDEX `index_practice_unit_knowledge_binding_knowledge_node_id` " +
            "ON `practice_unit_knowledge_binding` (`knowledge_node_id`)",
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX
            `index_practice_unit_knowledge_binding_binding_id_practice_unit_id_knowledge_node_id_basis_revision_id_taxonomy_version`
        ON `practice_unit_knowledge_binding` (
            `binding_id`,
            `practice_unit_id`,
            `knowledge_node_id`,
            `basis_revision_id`,
            `taxonomy_version`
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX
            `index_practice_unit_knowledge_binding_practice_unit_id_knowledge_node_id_knowledge_subject_knowledge_taxonomy_version_knowledge_pack_version_basis_revision_id_taxonomy_version`
        ON `practice_unit_knowledge_binding` (
            `practice_unit_id`,
            `knowledge_node_id`,
            `knowledge_subject`,
            `knowledge_taxonomy_version`,
            `knowledge_pack_version`,
            `basis_revision_id`,
            `taxonomy_version`
        )
        """.trimIndent(),
    )
}

private fun rebuildProblemStepKnowledgeBindings(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `problem_step_knowledge_binding_v40` (
            `solution_step_id` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `knowledge_subject` TEXT,
            `knowledge_taxonomy_version` TEXT,
            `knowledge_pack_version` TEXT,
            `knowledge_manifest_fingerprint` TEXT,
            `knowledge_activation_generation` INTEGER,
            `knowledge_reference_status` TEXT NOT NULL,
            `knowledge_reference_id` TEXT NOT NULL,
            PRIMARY KEY(`solution_step_id`, `knowledge_node_id`),
            FOREIGN KEY(`solution_step_id`)
                REFERENCES `problem_solution_step`(`solution_step_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `problem_step_knowledge_binding_v40` (
            `solution_step_id`,
            `knowledge_node_id`,
            `knowledge_subject`,
            `knowledge_taxonomy_version`,
            `knowledge_pack_version`,
            `knowledge_manifest_fingerprint`,
            `knowledge_activation_generation`,
            `knowledge_reference_status`,
            `knowledge_reference_id`
        )
        SELECT
            `solution_step_id`,
            `knowledge_node_id`,
            NULL,
            NULL,
            NULL,
            NULL,
            NULL,
            '${StudyDbValue.KnowledgeReferenceStatus.PENDING_REATTRIBUTION}',
            `knowledge_reference_id`
        FROM `problem_step_knowledge_binding`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `problem_step_knowledge_binding`")
    connection.execSQL(
        "ALTER TABLE `problem_step_knowledge_binding_v40` " +
            "RENAME TO `problem_step_knowledge_binding`",
    )
    connection.execSQL(
        "CREATE INDEX `index_problem_step_knowledge_binding_knowledge_node_id` " +
            "ON `problem_step_knowledge_binding` (`knowledge_node_id`)",
    )
}

private fun rebuildProblemErrorAttributionCandidates(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `problem_error_attribution_candidate_v40` (
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
            `knowledge_subject` TEXT,
            `knowledge_taxonomy_version` TEXT,
            `knowledge_pack_version` TEXT,
            `knowledge_manifest_fingerprint` TEXT,
            `knowledge_activation_generation` INTEGER,
            `knowledge_reference_status` TEXT,
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
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `problem_error_attribution_candidate_v40` (
            `error_attribution_candidate_id`,
            `organization_command_id`,
            `candidate_ordinal`,
            `problem_id`,
            `problem_revision_id`,
            `practice_unit_id`,
            `source_commit_receipt_command_id`,
            `resolution_status`,
            `solution_step_id`,
            `knowledge_node_id`,
            `knowledge_subject`,
            `knowledge_taxonomy_version`,
            `knowledge_pack_version`,
            `knowledge_manifest_fingerprint`,
            `knowledge_activation_generation`,
            `knowledge_reference_status`,
            `knowledge_reference_id`,
            `rationale_markdown`,
            `confidence`,
            `model_version`,
            `created_at_epoch_millis`
        )
        SELECT
            `error_attribution_candidate_id`,
            `organization_command_id`,
            `candidate_ordinal`,
            `problem_id`,
            `problem_revision_id`,
            `practice_unit_id`,
            `source_commit_receipt_command_id`,
            `resolution_status`,
            `solution_step_id`,
            `knowledge_node_id`,
            NULL,
            NULL,
            NULL,
            NULL,
            NULL,
            CASE
                WHEN `knowledge_node_id` IS NULL THEN NULL
                ELSE '${StudyDbValue.KnowledgeReferenceStatus.PENDING_REATTRIBUTION}'
            END,
            `knowledge_reference_id`,
            `rationale_markdown`,
            `confidence`,
            `model_version`,
            `created_at_epoch_millis`
        FROM `problem_error_attribution_candidate`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `problem_error_attribution_candidate`")
    connection.execSQL(
        "ALTER TABLE `problem_error_attribution_candidate_v40` " +
            "RENAME TO `problem_error_attribution_candidate`",
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX
            `index_problem_error_attribution_candidate_organization_command_id_candidate_ordinal`
        ON `problem_error_attribution_candidate` (
            `organization_command_id`,
            `candidate_ordinal`
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX
            `index_problem_error_attribution_candidate_practice_unit_id_problem_revision_id`
        ON `problem_error_attribution_candidate` (
            `practice_unit_id`,
            `problem_revision_id`
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX
            `index_problem_error_attribution_candidate_source_commit_receipt_command_id`
        ON `problem_error_attribution_candidate` (`source_commit_receipt_command_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX `index_problem_error_attribution_candidate_solution_step_id` " +
            "ON `problem_error_attribution_candidate` (`solution_step_id`)",
    )
    connection.execSQL(
        "CREATE INDEX `index_problem_error_attribution_candidate_knowledge_node_id` " +
            "ON `problem_error_attribution_candidate` (`knowledge_node_id`)",
    )
    connection.execSQL(
        """
        CREATE INDEX
            `index_problem_error_attribution_candidate_resolution_status_created_at_epoch_millis`
        ON `problem_error_attribution_candidate` (
            `resolution_status`,
            `created_at_epoch_millis`
        )
        """.trimIndent(),
    )
}
