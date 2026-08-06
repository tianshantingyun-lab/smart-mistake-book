package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val STUDENT_MISTAKE_MIGRATION_8_9 =
    object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                ALTER TABLE `student_mistake_migration_destination_record`
                ADD COLUMN `import_snapshot_canonical_fingerprint` TEXT
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `student_problem_import_semantic_snapshot` (
                    `revision_id` TEXT NOT NULL,
                    `problem_id` TEXT NOT NULL,
                    `practice_unit_id` TEXT NOT NULL,
                    `target_unit_kind` TEXT NOT NULL,
                    `target_title` TEXT NOT NULL,
                    `target_item_family_id` TEXT NOT NULL,
                    `target_estimated_duration_seconds` INTEGER NOT NULL,
                    `target_source_bundle_id` TEXT,
                    `target_part_ids_wire` TEXT NOT NULL,
                    `target_error_book_entry_id` TEXT,
                    `legacy_semantics_present` INTEGER NOT NULL,
                    `legacy_problem_canonical_fingerprint` TEXT,
                    `legacy_revision_source_type` TEXT,
                    `legacy_revision_source_reference` TEXT,
                    `legacy_answer_spec_id` TEXT,
                    `legacy_answer_spec_snapshot` TEXT,
                    `legacy_answer_verification_status` TEXT,
                    `legacy_error_book_source_key` TEXT,
                    `legacy_practice_unit_key` TEXT,
                    `legacy_practice_unit_prompt_markdown` TEXT,
                    `snapshot_canonical_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`revision_id`),
                    FOREIGN KEY(`revision_id`)
                        REFERENCES `student_problem_revision`(`revision_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_problem_import_semantic_snapshot_problem_id`
                ON `student_problem_import_semantic_snapshot` (`problem_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_problem_import_semantic_snapshot_practice_unit_id`
                ON `student_problem_import_semantic_snapshot` (`practice_unit_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_problem_import_semantic_snapshot_legacy_problem_canonical_fingerprint`
                ON `student_problem_import_semantic_snapshot`
                    (`legacy_problem_canonical_fingerprint`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_problem_import_semantic_snapshot_legacy_error_book_source_key`
                ON `student_problem_import_semantic_snapshot`
                    (`legacy_error_book_source_key`)
                """.trimIndent(),
            )
            createStudentCutoverAndMigrationLedgerImmutabilityTriggers(connection)
            createStudentImportSnapshotImmutabilityTriggers(connection)
        }
    }

internal fun createStudentImportSnapshotImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS sealed_student_problem_import_semantic_snapshot_insert
        BEFORE INSERT ON student_problem_import_semantic_snapshot
        WHEN EXISTS (SELECT 1 FROM student_cutover_fence)
          OR EXISTS (SELECT 1 FROM student_cutover_completion_receipt)
        BEGIN
            SELECT RAISE(ABORT, 'student import snapshot store is sealed');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_problem_import_semantic_snapshot_update
        BEFORE UPDATE ON student_problem_import_semantic_snapshot
        BEGIN
            SELECT RAISE(ABORT, 'immutable student import snapshot');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_problem_import_semantic_snapshot_delete
        BEFORE DELETE ON student_problem_import_semantic_snapshot
        BEGIN
            SELECT RAISE(ABORT, 'immutable student import snapshot');
        END
        """.trimIndent(),
    )
}

internal val STUDENT_IMPORT_SNAPSHOT_IMMUTABILITY_TRIGGER_NAMES =
    setOf(
        "sealed_student_problem_import_semantic_snapshot_insert",
        "immutable_student_problem_import_semantic_snapshot_update",
        "immutable_student_problem_import_semantic_snapshot_delete",
    )
