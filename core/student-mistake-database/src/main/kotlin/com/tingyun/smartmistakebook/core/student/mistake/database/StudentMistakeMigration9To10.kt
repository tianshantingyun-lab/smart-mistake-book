package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val STUDENT_MISTAKE_MIGRATION_9_10 =
    object : Migration(9, 10) {
        override suspend fun migrate(connection: SQLiteConnection) {
            relaxStudentCaptureSaveHandoffTargetIndexes(connection)
            createStudentProblemErrorOccurrenceTables(connection)
            createStudentProblemCanonicalIdentityTables(connection)
            createStudentCaptureOccurrenceTransactionTable(connection)
            createStudentProblemOrganizationReceiptTable(connection)
            rebuildStudentProblemRevisionAuthorityTables(connection)
            rebuildStudentProblemClassificationTable(connection)
            createStudentProblemStepKnowledgeBindingTable(connection)
            createStudentProblemOrganizationFacetTable(connection)
            createStudentProblemOrganizationOccurrenceBindingTable(connection)
            createStudentProblemOrganizationImmutabilityTriggers(connection)
        }
    }

private fun relaxStudentCaptureSaveHandoffTargetIndexes(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        DROP INDEX IF EXISTS `index_student_capture_save_handoff_target_revision_id`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_capture_save_handoff_target_revision_id`
        ON `student_capture_save_handoff` (`target_revision_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        DROP INDEX IF EXISTS
            `index_student_capture_save_handoff_target_canonical_fingerprint`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_capture_save_handoff_target_canonical_fingerprint`
        ON `student_capture_save_handoff` (`target_canonical_fingerprint`)
        """.trimIndent(),
    )
}

private fun createStudentProblemErrorOccurrenceTables(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_error_occurrence` (
            `occurrence_id` TEXT NOT NULL,
            `idempotency_key` TEXT NOT NULL,
            `schema_version` INTEGER NOT NULL,
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `problem_id` TEXT NOT NULL,
            `practice_unit_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `basis_revision_number` INTEGER NOT NULL,
            `basis_document_canonical_fingerprint` TEXT NOT NULL,
            `batch_canonical_fingerprint` TEXT NOT NULL,
            `import_source_canonical_fingerprint` TEXT NOT NULL,
            `occurred_at_epoch_millis` INTEGER NOT NULL,
            `imported_at_epoch_millis` INTEGER NOT NULL,
            `attribution_status` TEXT,
            `evidence_count` INTEGER NOT NULL,
            `occurrence_canonical_fingerprint` TEXT NOT NULL,
            PRIMARY KEY(`occurrence_id`),
            FOREIGN KEY(`basis_revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_learner_id_idempotency_key`
        ON `student_problem_error_occurrence` (`learner_id`, `idempotency_key`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_learner_id_batch_canonical_fingerprint_basis_revision_id`
        ON `student_problem_error_occurrence`
            (`learner_id`, `batch_canonical_fingerprint`, `basis_revision_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_learner_id_batch_canonical_fingerprint_import_source_canonical_fingerprint`
        ON `student_problem_error_occurrence`
            (`learner_id`, `batch_canonical_fingerprint`,
             `import_source_canonical_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_basis_revision_id_occurred_at_epoch_millis_imported_at_epoch_millis_occurrence_id`
        ON `student_problem_error_occurrence`
            (`basis_revision_id`, `occurred_at_epoch_millis`,
             `imported_at_epoch_millis`, `occurrence_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_learner_id_problem_id_occurred_at_epoch_millis`
        ON `student_problem_error_occurrence`
            (`learner_id`, `problem_id`, `occurred_at_epoch_millis`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_import_source_canonical_fingerprint`
        ON `student_problem_error_occurrence` (`import_source_canonical_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_occurrence_canonical_fingerprint`
        ON `student_problem_error_occurrence` (`occurrence_canonical_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_error_occurrence_evidence` (
            `occurrence_id` TEXT NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `block_id` TEXT NOT NULL,
            `source_asset_id` TEXT NOT NULL,
            `evidence_kind` TEXT NOT NULL,
            PRIMARY KEY(`occurrence_id`, `ordinal`),
            FOREIGN KEY(`occurrence_id`)
                REFERENCES `student_problem_error_occurrence`(`occurrence_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_evidence_occurrence_id_block_id_source_asset_id_evidence_kind`
        ON `student_problem_error_occurrence_evidence`
            (`occurrence_id`, `block_id`, `source_asset_id`, `evidence_kind`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_error_occurrence_evidence_block_id_source_asset_id`
        ON `student_problem_error_occurrence_evidence` (`block_id`, `source_asset_id`)
        """.trimIndent(),
    )
}

private fun createStudentCaptureOccurrenceTransactionTable(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_capture_occurrence_transaction` (
            `transaction_id` TEXT NOT NULL,
            `transaction_canonical_fingerprint` TEXT NOT NULL,
            `request_canonical_fingerprint` TEXT NOT NULL,
            `schema_version` INTEGER NOT NULL,
            `learner_id` TEXT NOT NULL,
            `capture_intent_id` TEXT NOT NULL,
            `source_kind` TEXT NOT NULL,
            `source_canonical_fingerprint` TEXT NOT NULL,
            `identity_namespace` TEXT NOT NULL,
            `identity_version` TEXT NOT NULL,
            `identity_stable_key` TEXT NOT NULL,
            `identity_canonical_fingerprint` TEXT NOT NULL,
            `identity_resolution_kind` TEXT NOT NULL,
            `identity_evidence_kind` TEXT NOT NULL,
            `identity_evidence_canonical_fingerprint` TEXT NOT NULL,
            `trusted_source_proof_fingerprint` TEXT,
            `reviewed_alias_proof_fingerprint` TEXT,
            `target_save_receipt_id` TEXT NOT NULL,
            `target_problem_id` TEXT NOT NULL,
            `target_revision_id` TEXT NOT NULL,
            `target_revision_number` INTEGER NOT NULL,
            `target_document_canonical_fingerprint` TEXT NOT NULL,
            `target_canonical_fingerprint` TEXT NOT NULL,
            `save_outcome` TEXT NOT NULL,
            `occurrence_id` TEXT NOT NULL,
            `occurrence_canonical_fingerprint` TEXT NOT NULL,
            `batch_canonical_fingerprint` TEXT NOT NULL,
            `import_source_canonical_fingerprint` TEXT NOT NULL,
            `asset_manifest_canonical_fingerprint` TEXT NOT NULL,
            `selected_region_canonical_fingerprint` TEXT NOT NULL,
            `committed_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`transaction_id`),
            FOREIGN KEY(`capture_intent_id`)
                REFERENCES `student_capture_save_handoff`(`intent_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`target_save_receipt_id`)
                REFERENCES `student_mistake_save_receipt`(`intent_confirmation_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`target_revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`occurrence_id`)
                REFERENCES `student_problem_error_occurrence`(`occurrence_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_capture_occurrence_transaction_transaction_canonical_fingerprint`
        ON `student_capture_occurrence_transaction`
            (`transaction_canonical_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_capture_occurrence_transaction_capture_intent_id`
        ON `student_capture_occurrence_transaction` (`capture_intent_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_capture_occurrence_transaction_target_save_receipt_id`
        ON `student_capture_occurrence_transaction` (`target_save_receipt_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_capture_occurrence_transaction_target_revision_id`
        ON `student_capture_occurrence_transaction` (`target_revision_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_capture_occurrence_transaction_occurrence_id`
        ON `student_capture_occurrence_transaction` (`occurrence_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_capture_occurrence_transaction_learner_id_committed_at_epoch_millis_transaction_id`
        ON `student_capture_occurrence_transaction`
            (`learner_id`, `committed_at_epoch_millis`, `transaction_id`)
        """.trimIndent(),
    )
}

private fun createStudentProblemCanonicalIdentityTables(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_canonical_identity` (
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `identity_namespace` TEXT NOT NULL,
            `identity_version` TEXT NOT NULL,
            `stable_key` TEXT NOT NULL,
            `identity_canonical_fingerprint` TEXT NOT NULL,
            `issuance_kind` TEXT NOT NULL,
            `problem_id` TEXT NOT NULL,
            `revision_id` TEXT NOT NULL,
            `revision_number` INTEGER NOT NULL,
            `document_canonical_fingerprint` TEXT NOT NULL,
            `practice_unit_id` TEXT NOT NULL,
            `error_book_entry_id` TEXT NOT NULL,
            `target_save_receipt_id` TEXT NOT NULL,
            `target_canonical_fingerprint` TEXT NOT NULL,
            `created_transaction_id` TEXT NOT NULL,
            `created_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(
                `learner_id`,
                `subject`,
                `identity_namespace`,
                `identity_version`,
                `stable_key`
            ),
            FOREIGN KEY(`problem_id`)
                REFERENCES `student_problem_document`(`problem_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`target_save_receipt_id`)
                REFERENCES `student_mistake_save_receipt`(`intent_confirmation_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_canonical_identity_identity_canonical_fingerprint`
        ON `student_problem_canonical_identity` (`identity_canonical_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_canonical_identity_problem_id`
        ON `student_problem_canonical_identity` (`problem_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_canonical_identity_revision_id`
        ON `student_problem_canonical_identity` (`revision_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_canonical_identity_target_save_receipt_id`
        ON `student_problem_canonical_identity` (`target_save_receipt_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_canonical_source_binding` (
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `evidence_kind` TEXT NOT NULL,
            `evidence_canonical_fingerprint` TEXT NOT NULL,
            `identity_namespace` TEXT NOT NULL,
            `identity_version` TEXT NOT NULL,
            `identity_stable_key` TEXT NOT NULL,
            `asset_manifest_canonical_fingerprint` TEXT NOT NULL,
            `selected_region_canonical_fingerprint` TEXT NOT NULL,
            `document_canonical_fingerprint` TEXT NOT NULL,
            `bound_revision_id` TEXT NOT NULL,
            `locator_namespace` TEXT,
            `locator_version` TEXT,
            `item_locator_canonical_fingerprint` TEXT,
            `trusted_source_proof_fingerprint` TEXT,
            `reviewed_alias_proof_fingerprint` TEXT,
            `review_case_id` TEXT,
            `review_revision` INTEGER,
            `review_decision_canonical_fingerprint` TEXT,
            `created_transaction_id` TEXT NOT NULL,
            `created_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(
                `learner_id`,
                `subject`,
                `evidence_kind`,
                `evidence_canonical_fingerprint`
            ),
            FOREIGN KEY(
                `learner_id`,
                `subject`,
                `identity_namespace`,
                `identity_version`,
                `identity_stable_key`
            )
                REFERENCES `student_problem_canonical_identity`(
                    `learner_id`,
                    `subject`,
                    `identity_namespace`,
                    `identity_version`,
                    `stable_key`
                )
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`bound_revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_canonical_source_binding_learner_id_subject_identity_namespace_identity_version_identity_stable_key`
        ON `student_problem_canonical_source_binding` (
            `learner_id`,
            `subject`,
            `identity_namespace`,
            `identity_version`,
            `identity_stable_key`
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_canonical_source_binding_bound_revision_id`
        ON `student_problem_canonical_source_binding` (`bound_revision_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_canonical_source_binding_trusted_source_proof_fingerprint`
        ON `student_problem_canonical_source_binding`
            (`trusted_source_proof_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_canonical_source_binding_reviewed_alias_proof_fingerprint`
        ON `student_problem_canonical_source_binding`
            (`reviewed_alias_proof_fingerprint`)
        """.trimIndent(),
    )
}

private fun createStudentProblemOrganizationReceiptTable(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_organization_receipt` (
            `receipt_id` TEXT NOT NULL,
            `request_id` TEXT NOT NULL,
            `request_canonical_fingerprint` TEXT NOT NULL,
            `request_version` INTEGER NOT NULL,
            `reviewed_request_version` INTEGER NOT NULL,
            `organization_revision` INTEGER NOT NULL,
            `supersedes_receipt_id` TEXT,
            `previous_payload_canonical_fingerprint` TEXT,
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `problem_id` TEXT NOT NULL,
            `practice_unit_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `basis_revision_number` INTEGER NOT NULL,
            `basis_document_canonical_fingerprint` TEXT NOT NULL,
            `model_provider_id` TEXT NOT NULL,
            `model_id` TEXT NOT NULL,
            `requested_model_version` TEXT NOT NULL,
            `result_model_version` TEXT NOT NULL,
            `provider_configuration_version` TEXT NOT NULL,
            `model_task_schema_version` INTEGER NOT NULL,
            `organization_plan_schema_version` INTEGER NOT NULL,
            `review_source` TEXT NOT NULL,
            `review_version` TEXT NOT NULL,
            `review_issuer_key_id` TEXT NOT NULL,
            `review_issuer_version` TEXT NOT NULL,
            `review_issued_at_epoch_millis` INTEGER NOT NULL,
            `review_expires_at_epoch_millis` INTEGER NOT NULL,
            `payload_canonical_fingerprint` TEXT NOT NULL,
            `error_occurrence_count` INTEGER NOT NULL,
            `classification_count` INTEGER NOT NULL,
            `step_knowledge_binding_count` INTEGER NOT NULL,
            `error_attribution_count` INTEGER NOT NULL,
            `facet_count` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `completed_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`receipt_id`),
            FOREIGN KEY(`basis_revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`supersedes_receipt_id`)
                REFERENCES `student_problem_organization_receipt`(`receipt_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_organization_receipt_request_id`
        ON `student_problem_organization_receipt` (`request_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_organization_receipt_basis_revision_id_organization_revision`
        ON `student_problem_organization_receipt`
            (`basis_revision_id`, `organization_revision`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_organization_receipt_basis_revision_id_status_organization_revision`
        ON `student_problem_organization_receipt`
            (`basis_revision_id`, `status`, `organization_revision`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_organization_receipt_supersedes_receipt_id`
        ON `student_problem_organization_receipt` (`supersedes_receipt_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_organization_receipt_payload_canonical_fingerprint`
        ON `student_problem_organization_receipt` (`payload_canonical_fingerprint`)
        """.trimIndent(),
    )
}

private fun rebuildStudentProblemRevisionAuthorityTables(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        ALTER TABLE `student_problem_error_evidence`
        RENAME TO `student_problem_error_evidence_v9`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        ALTER TABLE `student_problem_error_attribution`
        RENAME TO `student_problem_error_attribution_v9`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        ALTER TABLE `student_problem_solution_step`
        RENAME TO `student_problem_solution_step_v9`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        ALTER TABLE `student_problem_solution_analysis`
        RENAME TO `student_problem_solution_analysis_v9`
        """.trimIndent(),
    )
    STUDENT_PROBLEM_REVISION_AUTHORITY_V9_INDEXES.forEach { indexName ->
        connection.execSQL("DROP INDEX IF EXISTS `$indexName`")
    }
    connection.execSQL(
        """
        CREATE TABLE `student_problem_solution_analysis` (
            `solution_analysis_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `organization_receipt_id` TEXT,
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
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`organization_receipt_id`)
                REFERENCES `student_problem_organization_receipt`(`receipt_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX
            `index_student_problem_solution_analysis_solution_analysis_id_basis_revision_id`
        ON `student_problem_solution_analysis`
            (`solution_analysis_id`, `basis_revision_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE `student_problem_solution_step` (
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
                REFERENCES `student_problem_solution_analysis`
                    (`solution_analysis_id`, `basis_revision_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE `student_problem_error_attribution` (
            `attribution_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `organization_receipt_id` TEXT,
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
                REFERENCES `student_problem_solution_analysis`
                    (`solution_analysis_id`, `basis_revision_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`organization_receipt_id`)
                REFERENCES `student_problem_organization_receipt`(`receipt_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX
            `index_student_problem_error_attribution_attribution_id_basis_revision_id`
        ON `student_problem_error_attribution`
            (`attribution_id`, `basis_revision_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE `student_problem_error_evidence` (
            `attribution_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `block_id` TEXT NOT NULL,
            `source_asset_id` TEXT NOT NULL,
            `evidence_kind` TEXT NOT NULL,
            PRIMARY KEY(`attribution_id`, `ordinal`),
            FOREIGN KEY(`attribution_id`, `basis_revision_id`)
                REFERENCES `student_problem_error_attribution`
                    (`attribution_id`, `basis_revision_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `student_problem_solution_analysis` (
            `solution_analysis_id`, `basis_revision_id`, `organization_receipt_id`,
            `summary_markdown`, `final_answer_markdown`, `model_provider_id`,
            `model_id`, `analyzer_version`, `result_canonical_fingerprint`,
            `recorded_at_epoch_millis`
        )
        SELECT `solution_analysis_id`, `basis_revision_id`, NULL,
               `summary_markdown`, `final_answer_markdown`, `model_provider_id`,
               `model_id`, `analyzer_version`, `result_canonical_fingerprint`,
               `recorded_at_epoch_millis`
        FROM `student_problem_solution_analysis_v9`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `student_problem_solution_step` (
            `solution_analysis_id`, `basis_revision_id`, `step_id`, `ordinal`,
            `summary_markdown`, `reasoning_markdown`, `result_markdown`,
            `step_canonical_fingerprint`
        )
        SELECT `solution_analysis_id`, `basis_revision_id`, `step_id`, `ordinal`,
               `summary_markdown`, `reasoning_markdown`, `result_markdown`,
               `step_canonical_fingerprint`
        FROM `student_problem_solution_step_v9`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `student_problem_error_attribution` (
            `attribution_id`, `basis_revision_id`, `organization_receipt_id`,
            `solution_analysis_id`, `resolution_status`, `rationale_markdown`,
            `confidence`, `step_ordinal`, `atomic_reference_id`,
            `model_provider_id`, `model_id`, `analyzer_version`,
            `result_canonical_fingerprint`, `recorded_at_epoch_millis`
        )
        SELECT `attribution_id`, `basis_revision_id`, NULL,
               `solution_analysis_id`, `resolution_status`, `rationale_markdown`,
               `confidence`, `step_ordinal`, `atomic_reference_id`,
               `model_provider_id`, `model_id`, `analyzer_version`,
               `result_canonical_fingerprint`, `recorded_at_epoch_millis`
        FROM `student_problem_error_attribution_v9`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `student_problem_error_evidence` (
            `attribution_id`, `basis_revision_id`, `ordinal`, `block_id`,
            `source_asset_id`, `evidence_kind`
        )
        SELECT `attribution_id`, `basis_revision_id`, `ordinal`, `block_id`,
               `source_asset_id`, `evidence_kind`
        FROM `student_problem_error_evidence_v9`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `student_problem_error_evidence_v9`")
    connection.execSQL("DROP TABLE `student_problem_error_attribution_v9`")
    connection.execSQL("DROP TABLE `student_problem_solution_step_v9`")
    connection.execSQL("DROP TABLE `student_problem_solution_analysis_v9`")
    createStudentProblemRevisionAuthorityIndexes(connection)
}

private fun createStudentProblemRevisionAuthorityIndexes(
    connection: SQLiteConnection,
) {
    STUDENT_PROBLEM_REVISION_AUTHORITY_INDEX_SQL.forEach(connection::execSQL)
}

private fun rebuildStudentProblemClassificationTable(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        ALTER TABLE `student_problem_classification_result`
        RENAME TO `student_problem_classification_result_v9`
        """.trimIndent(),
    )
    STUDENT_PROBLEM_CLASSIFICATION_V9_INDEXES.forEach { indexName ->
        connection.execSQL("DROP INDEX IF EXISTS `$indexName`")
    }
    connection.execSQL(
        """
        CREATE TABLE `student_problem_classification_result` (
            `classification_id` TEXT NOT NULL,
            `problem_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `organization_receipt_id` TEXT,
            `dimension` TEXT NOT NULL,
            `label_id` TEXT NOT NULL,
            `display_name` TEXT,
            `knowledge_subject` TEXT,
            `knowledge_node_id` TEXT,
            `knowledge_taxonomy_version` TEXT,
            `knowledge_pack_version` TEXT,
            `knowledge_manifest_fingerprint` TEXT,
            `knowledge_activation_generation` INTEGER,
            `model_provider_id` TEXT NOT NULL,
            `model_id` TEXT NOT NULL,
            `classifier_version` TEXT NOT NULL,
            `result_canonical_fingerprint` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `supersedes_classification_id` TEXT,
            `recorded_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`classification_id`),
            FOREIGN KEY(`problem_id`)
                REFERENCES `student_problem_document`(`problem_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`basis_revision_id`)
                REFERENCES `student_problem_revision`(`revision_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`organization_receipt_id`)
                REFERENCES `student_problem_organization_receipt`(`receipt_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `student_problem_classification_result` (
            `classification_id`, `problem_id`, `basis_revision_id`,
            `organization_receipt_id`, `dimension`, `label_id`, `display_name`,
            `knowledge_subject`, `knowledge_node_id`, `knowledge_taxonomy_version`,
            `knowledge_pack_version`, `knowledge_manifest_fingerprint`,
            `knowledge_activation_generation`, `model_provider_id`, `model_id`,
            `classifier_version`, `result_canonical_fingerprint`, `status`,
            `supersedes_classification_id`, `recorded_at_epoch_millis`
        )
        SELECT `classification_id`, `problem_id`, `basis_revision_id`, NULL,
               `dimension`, `label_id`, `display_name`, `knowledge_subject`,
               `knowledge_node_id`, `knowledge_taxonomy_version`,
               `knowledge_pack_version`, `knowledge_manifest_fingerprint`,
               `knowledge_activation_generation`, `model_provider_id`, `model_id`,
               `classifier_version`, `result_canonical_fingerprint`, `status`,
               `supersedes_classification_id`, `recorded_at_epoch_millis`
        FROM `student_problem_classification_result_v9`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `student_problem_classification_result_v9`")
    STUDENT_PROBLEM_CLASSIFICATION_INDEX_SQL.forEach(connection::execSQL)
}

private fun createStudentProblemStepKnowledgeBindingTable(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_step_knowledge_binding` (
            `binding_id` TEXT NOT NULL,
            `organization_receipt_id` TEXT NOT NULL,
            `basis_revision_id` TEXT NOT NULL,
            `solution_analysis_id` TEXT NOT NULL,
            `step_id` TEXT NOT NULL,
            `step_ordinal` INTEGER NOT NULL,
            `knowledge_reference_id` TEXT NOT NULL,
            `knowledge_subject` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `knowledge_taxonomy_version` TEXT NOT NULL,
            `knowledge_pack_version` TEXT NOT NULL,
            `knowledge_content_canonical_fingerprint` TEXT NOT NULL,
            `knowledge_activation_generation` INTEGER NOT NULL,
            `binding_canonical_fingerprint` TEXT NOT NULL,
            `recorded_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`binding_id`),
            FOREIGN KEY(`organization_receipt_id`)
                REFERENCES `student_problem_organization_receipt`(`receipt_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`solution_analysis_id`, `step_ordinal`)
                REFERENCES `student_problem_solution_step`
                    (`solution_analysis_id`, `ordinal`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_step_knowledge_binding_organization_receipt_id_binding_id`
        ON `student_problem_step_knowledge_binding`
            (`organization_receipt_id`, `binding_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_step_knowledge_binding_solution_analysis_id_step_ordinal`
        ON `student_problem_step_knowledge_binding`
            (`solution_analysis_id`, `step_ordinal`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_step_knowledge_binding_organization_receipt_id_solution_analysis_id_step_ordinal_knowledge_reference_id`
        ON `student_problem_step_knowledge_binding`
            (`organization_receipt_id`, `solution_analysis_id`, `step_ordinal`,
             `knowledge_reference_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_step_knowledge_binding_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version`
        ON `student_problem_step_knowledge_binding`
            (`knowledge_subject`, `knowledge_node_id`, `knowledge_taxonomy_version`,
             `knowledge_pack_version`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_step_knowledge_binding_binding_canonical_fingerprint`
        ON `student_problem_step_knowledge_binding`
            (`binding_canonical_fingerprint`)
        """.trimIndent(),
    )
}

private fun createStudentProblemOrganizationFacetTable(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_organization_facet` (
            `organization_receipt_id` TEXT NOT NULL,
            `dimension` TEXT NOT NULL,
            `family_id` TEXT NOT NULL,
            `family_version` TEXT NOT NULL,
            `binding_canonical_fingerprint` TEXT NOT NULL,
            PRIMARY KEY(`organization_receipt_id`, `dimension`),
            FOREIGN KEY(`organization_receipt_id`)
                REFERENCES `student_problem_organization_receipt`(`receipt_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_organization_facet_dimension_family_id_family_version`
        ON `student_problem_organization_facet`
            (`dimension`, `family_id`, `family_version`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_organization_facet_binding_canonical_fingerprint`
        ON `student_problem_organization_facet`
            (`binding_canonical_fingerprint`)
        """.trimIndent(),
    )
}

private fun createStudentProblemOrganizationOccurrenceBindingTable(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `student_problem_organization_occurrence_binding` (
            `organization_receipt_id` TEXT NOT NULL,
            `occurrence_id` TEXT NOT NULL,
            `occurrence_canonical_fingerprint` TEXT NOT NULL,
            PRIMARY KEY(`organization_receipt_id`, `occurrence_id`),
            FOREIGN KEY(`organization_receipt_id`)
                REFERENCES `student_problem_organization_receipt`(`receipt_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`occurrence_id`)
                REFERENCES `student_problem_error_occurrence`(`occurrence_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_student_problem_organization_occurrence_binding_occurrence_id`
        ON `student_problem_organization_occurrence_binding` (`occurrence_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_student_problem_organization_occurrence_binding_organization_receipt_id_occurrence_canonical_fingerprint`
        ON `student_problem_organization_occurrence_binding`
            (`organization_receipt_id`, `occurrence_canonical_fingerprint`)
        """.trimIndent(),
    )
}

internal fun createStudentProblemOrganizationImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    STUDENT_PROBLEM_ORGANIZATION_IMMUTABLE_TABLES.forEach { tableName ->
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `immutable_${tableName}_update`
            BEFORE UPDATE ON `$tableName`
            BEGIN
                SELECT RAISE(ABORT, 'immutable student problem organization');
            END
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `immutable_${tableName}_delete`
            BEFORE DELETE ON `$tableName`
            BEGIN
                SELECT RAISE(ABORT, 'immutable student problem organization');
            END
            """.trimIndent(),
        )
    }
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_solution_analysis_organization_update`
        BEFORE UPDATE ON `student_problem_solution_analysis`
        WHEN OLD.`organization_receipt_id` IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized solution analysis');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_solution_analysis_organization_delete`
        BEFORE DELETE ON `student_problem_solution_analysis`
        WHEN OLD.`organization_receipt_id` IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized solution analysis');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_solution_step_organization_update`
        BEFORE UPDATE ON `student_problem_solution_step`
        WHEN EXISTS (
            SELECT 1
            FROM `student_problem_solution_analysis` AS analysis
            WHERE analysis.`solution_analysis_id` = OLD.`solution_analysis_id`
              AND analysis.`organization_receipt_id` IS NOT NULL
        )
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized solution step');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_solution_step_organization_delete`
        BEFORE DELETE ON `student_problem_solution_step`
        WHEN EXISTS (
            SELECT 1
            FROM `student_problem_solution_analysis` AS analysis
            WHERE analysis.`solution_analysis_id` = OLD.`solution_analysis_id`
              AND analysis.`organization_receipt_id` IS NOT NULL
        )
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized solution step');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_error_attribution_organization_update`
        BEFORE UPDATE ON `student_problem_error_attribution`
        WHEN OLD.`organization_receipt_id` IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized error attribution');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_error_attribution_organization_delete`
        BEFORE DELETE ON `student_problem_error_attribution`
        WHEN OLD.`organization_receipt_id` IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized error attribution');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_error_evidence_organization_update`
        BEFORE UPDATE ON `student_problem_error_evidence`
        WHEN EXISTS (
            SELECT 1
            FROM `student_problem_error_attribution` AS attribution
            WHERE attribution.`attribution_id` = OLD.`attribution_id`
              AND attribution.`organization_receipt_id` IS NOT NULL
        )
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized error evidence');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_error_evidence_organization_delete`
        BEFORE DELETE ON `student_problem_error_evidence`
        WHEN EXISTS (
            SELECT 1
            FROM `student_problem_error_attribution` AS attribution
            WHERE attribution.`attribution_id` = OLD.`attribution_id`
              AND attribution.`organization_receipt_id` IS NOT NULL
        )
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized error evidence');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_classification_result_organization_update`
        BEFORE UPDATE ON `student_problem_classification_result`
        WHEN OLD.`organization_receipt_id` IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized classification');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `immutable_student_problem_classification_result_organization_delete`
        BEFORE DELETE ON `student_problem_classification_result`
        WHEN OLD.`organization_receipt_id` IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'immutable organized classification');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            `reject_legacy_classification_after_organization`
        BEFORE INSERT ON `student_problem_classification_result`
        WHEN NEW.`organization_receipt_id` IS NULL
          AND EXISTS (
            SELECT 1
            FROM `student_problem_organization_receipt` AS receipt
            WHERE receipt.`basis_revision_id` = NEW.`basis_revision_id`
              AND receipt.`status` = 'COMPLETED'
          )
        BEGIN
            SELECT RAISE(ABORT, 'organized revision requires versioned classification');
        END
        """.trimIndent(),
    )
}

private val STUDENT_PROBLEM_ORGANIZATION_IMMUTABLE_TABLES =
    listOf(
        "student_problem_canonical_identity",
        "student_problem_canonical_source_binding",
        "student_capture_occurrence_transaction",
        "student_problem_error_occurrence",
        "student_problem_error_occurrence_evidence",
        "student_problem_organization_receipt",
        "student_problem_step_knowledge_binding",
        "student_problem_organization_facet",
        "student_problem_organization_occurrence_binding",
    )

internal val STUDENT_PROBLEM_ORGANIZATION_IMMUTABILITY_TRIGGER_NAMES: Set<String> =
    buildSet {
        STUDENT_PROBLEM_ORGANIZATION_IMMUTABLE_TABLES.forEach { tableName ->
            add("immutable_${tableName}_update")
            add("immutable_${tableName}_delete")
        }
        add("immutable_student_problem_solution_analysis_organization_update")
        add("immutable_student_problem_solution_analysis_organization_delete")
        add("immutable_student_problem_solution_step_organization_update")
        add("immutable_student_problem_solution_step_organization_delete")
        add("immutable_student_problem_error_attribution_organization_update")
        add("immutable_student_problem_error_attribution_organization_delete")
        add("immutable_student_problem_error_evidence_organization_update")
        add("immutable_student_problem_error_evidence_organization_delete")
        add("immutable_student_problem_classification_result_organization_update")
        add("immutable_student_problem_classification_result_organization_delete")
        add("reject_legacy_classification_after_organization")
    }

private val STUDENT_PROBLEM_REVISION_AUTHORITY_V9_INDEXES =
    listOf(
        "index_student_problem_solution_analysis_basis_revision_id",
        "index_student_problem_solution_analysis_solution_analysis_id_basis_revision_id",
        "index_student_problem_solution_analysis_result_canonical_fingerprint",
        "index_student_problem_solution_step_solution_analysis_id_basis_revision_id",
        "index_student_problem_solution_step_solution_analysis_id_step_id",
        "index_student_problem_solution_step_step_canonical_fingerprint",
        "index_student_problem_error_attribution_basis_revision_id_recorded_at_epoch_millis_attribution_id",
        "index_student_problem_error_attribution_solution_analysis_id_basis_revision_id",
        "index_student_problem_error_attribution_result_canonical_fingerprint",
        "index_student_problem_error_attribution_attribution_id_basis_revision_id",
        "index_student_problem_error_evidence_attribution_id_basis_revision_id",
        "index_student_problem_error_evidence_basis_revision_id_block_id_source_asset_id",
    )

private val STUDENT_PROBLEM_REVISION_AUTHORITY_INDEX_SQL =
    listOf(
        """
        CREATE INDEX `index_student_problem_solution_analysis_basis_revision_id_recorded_at_epoch_millis_solution_analysis_id`
        ON `student_problem_solution_analysis`
            (`basis_revision_id`, `recorded_at_epoch_millis`, `solution_analysis_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX `index_student_problem_solution_analysis_organization_receipt_id`
        ON `student_problem_solution_analysis` (`organization_receipt_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_solution_analysis_result_canonical_fingerprint`
        ON `student_problem_solution_analysis` (`result_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_solution_step_solution_analysis_id_basis_revision_id`
        ON `student_problem_solution_step` (`solution_analysis_id`, `basis_revision_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX `index_student_problem_solution_step_solution_analysis_id_step_id`
        ON `student_problem_solution_step` (`solution_analysis_id`, `step_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_solution_step_step_canonical_fingerprint`
        ON `student_problem_solution_step` (`step_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_error_attribution_basis_revision_id_recorded_at_epoch_millis_attribution_id`
        ON `student_problem_error_attribution`
            (`basis_revision_id`, `recorded_at_epoch_millis`, `attribution_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_error_attribution_solution_analysis_id_basis_revision_id`
        ON `student_problem_error_attribution`
            (`solution_analysis_id`, `basis_revision_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_error_attribution_organization_receipt_id`
        ON `student_problem_error_attribution` (`organization_receipt_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_error_attribution_result_canonical_fingerprint`
        ON `student_problem_error_attribution` (`result_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_error_evidence_attribution_id_basis_revision_id`
        ON `student_problem_error_evidence` (`attribution_id`, `basis_revision_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_error_evidence_basis_revision_id_block_id_source_asset_id`
        ON `student_problem_error_evidence`
            (`basis_revision_id`, `block_id`, `source_asset_id`)
        """.trimIndent(),
    )

private val STUDENT_PROBLEM_CLASSIFICATION_V9_INDEXES =
    listOf(
        "index_student_problem_classification_result_problem_id_basis_revision_id",
        "index_student_problem_classification_result_basis_revision_id_dimension_status_label_id",
        "index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version",
        "index_student_problem_classification_result_result_canonical_fingerprint",
        "index_student_problem_classification_result_supersedes_classification_id",
    )

private val STUDENT_PROBLEM_CLASSIFICATION_INDEX_SQL =
    listOf(
        """
        CREATE INDEX `index_student_problem_classification_result_problem_id_basis_revision_id`
        ON `student_problem_classification_result` (`problem_id`, `basis_revision_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_classification_result_organization_receipt_id`
        ON `student_problem_classification_result` (`organization_receipt_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_classification_result_basis_revision_id_dimension_status_label_id`
        ON `student_problem_classification_result`
            (`basis_revision_id`, `dimension`, `status`, `label_id`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version`
        ON `student_problem_classification_result`
            (`basis_revision_id`, `dimension`, `status`, `knowledge_subject`,
             `knowledge_node_id`, `knowledge_taxonomy_version`,
             `knowledge_pack_version`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_classification_result_result_canonical_fingerprint`
        ON `student_problem_classification_result` (`result_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX `index_student_problem_classification_result_supersedes_classification_id`
        ON `student_problem_classification_result` (`supersedes_classification_id`)
        """.trimIndent(),
    )
