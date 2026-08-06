package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val STUDENT_MISTAKE_MIGRATION_7_8 =
    object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                ALTER TABLE `student_mistake_migration_checkpoint`
                ADD COLUMN `destination_ledger_version` INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `student_cutover_fence` (
                    `singleton_key` TEXT NOT NULL,
                    `cutover_generation` INTEGER NOT NULL,
                    `student_import_evidence_fingerprint` TEXT NOT NULL,
                    `mastery_import_evidence_fingerprint` TEXT NOT NULL,
                    `cutover_intent_fingerprint` TEXT NOT NULL,
                    `fence_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`singleton_key`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `student_cutover_completion_receipt` (
                    `singleton_key` TEXT NOT NULL,
                    `cutover_generation` INTEGER NOT NULL,
                    `cutover_intent_fingerprint` TEXT NOT NULL,
                    `authority_fence_fingerprint` TEXT NOT NULL,
                    `receipt_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(`singleton_key`),
                    FOREIGN KEY(`singleton_key`)
                        REFERENCES `student_cutover_fence`(`singleton_key`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `student_mistake_migration_destination_record` (
                    `migration_id` TEXT NOT NULL,
                    `source_page_canonical_fingerprint` TEXT NOT NULL,
                    `page_record_ordinal` INTEGER NOT NULL,
                    `committed_at_epoch_millis` INTEGER NOT NULL,
                    `problem_id` TEXT NOT NULL,
                    `revision_number` INTEGER NOT NULL,
                    `revision_id` TEXT NOT NULL,
                    `destination_record_canonical_fingerprint` TEXT NOT NULL,
                    PRIMARY KEY(
                        `migration_id`,
                        `source_page_canonical_fingerprint`,
                        `page_record_ordinal`
                    ),
                    FOREIGN KEY(`migration_id`)
                        REFERENCES `student_mistake_migration_checkpoint`(`migration_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`revision_id`)
                        REFERENCES `student_problem_revision`(`revision_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_student_mistake_migration_destination_record_migration_id_revision_id`
                ON `student_mistake_migration_destination_record`
                    (`migration_id`, `revision_id`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_mistake_migration_destination_record_revision_id`
                ON `student_mistake_migration_destination_record` (`revision_id`)
                """.trimIndent(),
            )
            createStudentCutoverAndMigrationLedgerImmutabilityTriggers(connection)
        }
    }

internal fun createStudentCutoverAndMigrationLedgerImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_cutover_fence_insert
        BEFORE INSERT ON student_cutover_fence
        WHEN NEW.singleton_key != '$STUDENT_CUTOVER_SINGLETON_KEY'
        BEGIN
            SELECT RAISE(ABORT, 'invalid student cutover singleton');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_cutover_fence_update
        BEFORE UPDATE ON student_cutover_fence
        BEGIN
            SELECT RAISE(ABORT, 'immutable student cutover fence');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_cutover_fence_delete
        BEFORE DELETE ON student_cutover_fence
        BEGIN
            SELECT RAISE(ABORT, 'immutable student cutover fence');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_cutover_completion_receipt_insert
        BEFORE INSERT ON student_cutover_completion_receipt
        WHEN NEW.singleton_key != '$STUDENT_CUTOVER_SINGLETON_KEY'
          OR NOT EXISTS (
              SELECT 1
              FROM student_cutover_fence AS fence
              WHERE fence.singleton_key = NEW.singleton_key
                AND fence.cutover_generation = NEW.cutover_generation
                AND fence.cutover_intent_fingerprint =
                    NEW.cutover_intent_fingerprint
                AND fence.fence_fingerprint =
                    NEW.authority_fence_fingerprint
          )
        BEGIN
            SELECT RAISE(ABORT, 'student cutover receipt is not bound to its fence');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_cutover_completion_receipt_update
        BEFORE UPDATE ON student_cutover_completion_receipt
        BEGIN
            SELECT RAISE(ABORT, 'immutable student cutover completion receipt');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_cutover_completion_receipt_delete
        BEFORE DELETE ON student_cutover_completion_receipt
        BEGIN
            SELECT RAISE(ABORT, 'immutable student cutover completion receipt');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS sealed_student_mistake_migration_checkpoint_insert
        BEFORE INSERT ON student_mistake_migration_checkpoint
        WHEN EXISTS (SELECT 1 FROM student_cutover_fence)
          OR EXISTS (SELECT 1 FROM student_cutover_completion_receipt)
          OR EXISTS (
              SELECT 1
              FROM student_mistake_migration_checkpoint AS checkpoint
              WHERE checkpoint.migration_id = NEW.migration_id
                AND checkpoint.completed = 1
          )
          OR EXISTS (
              SELECT 1
              FROM student_mistake_migration_receipt AS receipt
              WHERE receipt.migration_id = NEW.migration_id
                AND receipt.result_completed = 1
          )
        BEGIN
            SELECT RAISE(ABORT, 'student migration ledger is terminal');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS sealed_student_mistake_migration_receipt_insert
        BEFORE INSERT ON student_mistake_migration_receipt
        WHEN EXISTS (SELECT 1 FROM student_cutover_fence)
          OR EXISTS (SELECT 1 FROM student_cutover_completion_receipt)
          OR EXISTS (
              SELECT 1
              FROM student_mistake_migration_checkpoint AS checkpoint
              WHERE checkpoint.migration_id = NEW.migration_id
                AND checkpoint.completed = 1
          )
          OR EXISTS (
              SELECT 1
              FROM student_mistake_migration_receipt AS receipt
              WHERE receipt.migration_id = NEW.migration_id
                AND receipt.result_completed = 1
          )
        BEGIN
            SELECT RAISE(ABORT, 'student migration ledger is terminal');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS sealed_student_mistake_migration_destination_record_insert
        BEFORE INSERT ON student_mistake_migration_destination_record
        WHEN EXISTS (SELECT 1 FROM student_cutover_fence)
          OR EXISTS (SELECT 1 FROM student_cutover_completion_receipt)
          OR EXISTS (
              SELECT 1
              FROM student_mistake_migration_checkpoint AS checkpoint
              WHERE checkpoint.migration_id = NEW.migration_id
                AND checkpoint.completed = 1
          )
          OR EXISTS (
              SELECT 1
              FROM student_mistake_migration_receipt AS receipt
              WHERE receipt.migration_id = NEW.migration_id
                AND receipt.result_completed = 1
          )
        BEGIN
            SELECT RAISE(ABORT, 'student migration ledger is terminal');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mistake_migration_receipt_update
        BEFORE UPDATE ON student_mistake_migration_receipt
        BEGIN
            SELECT RAISE(ABORT, 'immutable student migration receipt');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mistake_migration_receipt_delete
        BEFORE DELETE ON student_mistake_migration_receipt
        BEGIN
            SELECT RAISE(ABORT, 'immutable student migration receipt');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_migration_destination_record_update
        BEFORE UPDATE ON student_mistake_migration_destination_record
        BEGIN
            SELECT RAISE(ABORT, 'immutable student migration destination record');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_migration_destination_record_delete
        BEFORE DELETE ON student_mistake_migration_destination_record
        BEGIN
            SELECT RAISE(ABORT, 'immutable student migration destination record');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mistake_migration_checkpoint_delete
        BEFORE DELETE ON student_mistake_migration_checkpoint
        BEGIN
            SELECT RAISE(ABORT, 'immutable student migration checkpoint');
        END
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mistake_migration_checkpoint_terminal_update
        BEFORE UPDATE ON student_mistake_migration_checkpoint
        WHEN OLD.completed = 1
          OR NEW.migration_id != OLD.migration_id
          OR NEW.source_database_canonical_fingerprint !=
              OLD.source_database_canonical_fingerprint
          OR EXISTS (SELECT 1 FROM student_cutover_fence)
          OR EXISTS (SELECT 1 FROM student_cutover_completion_receipt)
          OR (
              EXISTS (
                  SELECT 1
                  FROM student_mistake_migration_receipt AS terminal
                  WHERE terminal.migration_id = OLD.migration_id
                    AND terminal.result_completed = 1
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM student_mistake_migration_receipt AS receipt
                  WHERE receipt.migration_id = NEW.migration_id
                    AND receipt.result_completed = 1
                    AND receipt.result_last_committed_at_epoch_millis
                        IS NEW.last_committed_at_epoch_millis
                    AND receipt.result_last_problem_id IS NEW.last_problem_id
                    AND receipt.result_last_revision_number
                        IS NEW.last_revision_number
                    AND receipt.result_last_revision_id IS NEW.last_revision_id
                    AND receipt.result_total_record_count =
                        NEW.imported_record_count
                    AND receipt.checkpoint_canonical_fingerprint =
                        NEW.checkpoint_canonical_fingerprint
                    AND receipt.applied_at_epoch_millis =
                        NEW.updated_at_epoch_millis
                    AND NEW.completed = 1
              )
          )
        BEGIN
            SELECT RAISE(ABORT, 'immutable completed student migration checkpoint');
        END
        """.trimIndent(),
    )
}

internal val STUDENT_CUTOVER_IMMUTABILITY_TRIGGER_NAMES: Set<String> =
    setOf(
        "immutable_student_cutover_fence_insert",
        "immutable_student_cutover_fence_update",
        "immutable_student_cutover_fence_delete",
        "immutable_student_cutover_completion_receipt_insert",
        "immutable_student_cutover_completion_receipt_update",
        "immutable_student_cutover_completion_receipt_delete",
        "sealed_student_mistake_migration_checkpoint_insert",
        "sealed_student_mistake_migration_receipt_insert",
        "sealed_student_mistake_migration_destination_record_insert",
        "immutable_student_mistake_migration_receipt_update",
        "immutable_student_mistake_migration_receipt_delete",
        "immutable_student_mistake_migration_destination_record_update",
        "immutable_student_mistake_migration_destination_record_delete",
        "immutable_student_mistake_migration_checkpoint_delete",
        "immutable_student_mistake_migration_checkpoint_terminal_update",
    )
