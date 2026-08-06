package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Removes the last catalog-content snapshot from the student-mistake authority.
 *
 * A classification keeps only its stable catalog reference, activation provenance, and audit
 * fingerprints. The old display text is deliberately not copied into the rebuilt table.
 */
internal val STUDENT_MISTAKE_MIGRATION_12_13 =
    object : Migration(12, 13) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("PRAGMA secure_delete = ON")
            connection.execSQL(
                """
                ALTER TABLE `student_problem_classification_result`
                RENAME TO `student_problem_classification_result_v12`
                """.trimIndent(),
            )
            STUDENT_PROBLEM_CLASSIFICATION_V12_INDEXES.forEach { indexName ->
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
                    `organization_receipt_id`, `dimension`, `label_id`,
                    `knowledge_subject`, `knowledge_node_id`,
                    `knowledge_taxonomy_version`, `knowledge_pack_version`,
                    `knowledge_manifest_fingerprint`, `knowledge_activation_generation`,
                    `model_provider_id`, `model_id`, `classifier_version`,
                    `result_canonical_fingerprint`, `status`,
                    `supersedes_classification_id`, `recorded_at_epoch_millis`
                )
                SELECT `classification_id`, `problem_id`, `basis_revision_id`,
                       `organization_receipt_id`, `dimension`, `label_id`,
                       `knowledge_subject`, `knowledge_node_id`,
                       `knowledge_taxonomy_version`, `knowledge_pack_version`,
                       `knowledge_manifest_fingerprint`, `knowledge_activation_generation`,
                       `model_provider_id`, `model_id`, `classifier_version`,
                       `result_canonical_fingerprint`, `status`,
                       `supersedes_classification_id`, `recorded_at_epoch_millis`
                FROM `student_problem_classification_result_v12`
                """.trimIndent(),
            )
            connection.execSQL("DROP TABLE `student_problem_classification_result_v12`")
            STUDENT_PROBLEM_CLASSIFICATION_V13_INDEX_SQL.forEach(connection::execSQL)
            createStudentProblemOrganizationImmutabilityTriggers(connection)
            verifyStudentProblemOrganizationImmutabilityTriggers(connection)
        }
    }

private val STUDENT_PROBLEM_CLASSIFICATION_V12_INDEXES =
    listOf(
        "index_student_problem_classification_result_problem_id_basis_revision_id",
        "index_student_problem_classification_result_organization_receipt_id",
        "index_student_problem_classification_result_basis_revision_id_dimension_status_label_id",
        "index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version",
        "index_student_problem_classification_result_result_canonical_fingerprint",
        "index_student_problem_classification_result_supersedes_classification_id",
    )

private val STUDENT_PROBLEM_CLASSIFICATION_V13_INDEX_SQL =
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
