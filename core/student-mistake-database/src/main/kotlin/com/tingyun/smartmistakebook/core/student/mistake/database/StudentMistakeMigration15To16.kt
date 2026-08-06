package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Removes model confidence from the student-mistake authority.
 *
 * The parent attribution table and its evidence child are rebuilt together so no evidence can be
 * lost through the cascading foreign key. Existing rows retain only the reviewable attribution
 * facts; confidence is neither copied nor converted into another score or weight.
 */
internal val STUDENT_MISTAKE_MIGRATION_15_16 =
    object : Migration(15, 16) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val attributionCount = connection.rowCount(V15_ATTRIBUTION_TABLE)
            val evidenceCount = connection.rowCount(V15_EVIDENCE_TABLE)
            val invalidatedDestinationCount =
                connection.rowCount(V16_LEGACY_DESTINATION_INVALIDATION_SOURCE)

            connection.execSQL(V16_DESTINATION_INVALIDATION_TABLE_SQL)
            V16_DESTINATION_INVALIDATION_INDEX_SQL.forEach(connection::execSQL)
            connection.execSQL(V16_DESTINATION_REATTESTATION_RECEIPT_TABLE_SQL)
            V16_DESTINATION_REATTESTATION_RECEIPT_INDEX_SQL.forEach(connection::execSQL)
            connection.execSQL(V16_INSERT_LEGACY_DESTINATION_INVALIDATIONS_SQL)
            check(
                connection.rowCount(
                    STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE,
                ) == invalidatedDestinationCount,
            ) {
                "Student mistake v16 migration did not invalidate the exact legacy proof set"
            }
            check(
                connection.rowCount(
                    STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE,
                ) == 0L,
            ) {
                "Student mistake v16 migration must not manufacture reattestation receipts"
            }

            V15_REBUILT_TABLE_TRIGGER_NAMES.forEach { triggerName ->
                connection.execSQL("DROP TRIGGER IF EXISTS `$triggerName`")
            }
            connection.execSQL(
                "ALTER TABLE `$V15_EVIDENCE_TABLE` RENAME TO `${V15_EVIDENCE_TABLE}_v15`",
            )
            connection.execSQL(
                "ALTER TABLE `$V15_ATTRIBUTION_TABLE` RENAME TO `${V15_ATTRIBUTION_TABLE}_v15`",
            )
            V15_REBUILT_TABLE_INDEX_NAMES.forEach { indexName ->
                connection.execSQL("DROP INDEX IF EXISTS `$indexName`")
            }

            connection.execSQL(V16_ATTRIBUTION_TABLE_SQL)
            V16_ATTRIBUTION_INDEX_SQL.forEach(connection::execSQL)
            connection.execSQL(V16_EVIDENCE_TABLE_SQL)
            V16_EVIDENCE_INDEX_SQL.forEach(connection::execSQL)

            connection.execSQL(
                """
                INSERT INTO `$V15_ATTRIBUTION_TABLE` (
                    `attribution_id`, `basis_revision_id`, `organization_receipt_id`,
                    `solution_analysis_id`, `resolution_status`, `rationale_markdown`,
                    `step_ordinal`, `atomic_reference_id`, `model_provider_id`, `model_id`,
                    `analyzer_version`, `result_canonical_fingerprint`,
                    `recorded_at_epoch_millis`
                )
                SELECT `attribution_id`, `basis_revision_id`, `organization_receipt_id`,
                       `solution_analysis_id`, `resolution_status`, `rationale_markdown`,
                       `step_ordinal`, `atomic_reference_id`, `model_provider_id`, `model_id`,
                       `analyzer_version`, `result_canonical_fingerprint`,
                       `recorded_at_epoch_millis`
                FROM `${V15_ATTRIBUTION_TABLE}_v15`
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO `$V15_EVIDENCE_TABLE` (
                    `attribution_id`, `basis_revision_id`, `ordinal`, `block_id`,
                    `source_asset_id`, `evidence_kind`
                )
                SELECT `attribution_id`, `basis_revision_id`, `ordinal`, `block_id`,
                       `source_asset_id`, `evidence_kind`
                FROM `${V15_EVIDENCE_TABLE}_v15`
                """.trimIndent(),
            )

            check(connection.rowCount(V15_ATTRIBUTION_TABLE) == attributionCount) {
                "Student mistake v16 migration changed the attribution row count"
            }
            check(connection.rowCount(V15_EVIDENCE_TABLE) == evidenceCount) {
                "Student mistake v16 migration changed the attribution-evidence row count"
            }

            connection.execSQL("DROP TABLE `${V15_EVIDENCE_TABLE}_v15`")
            connection.execSQL("DROP TABLE `${V15_ATTRIBUTION_TABLE}_v15`")
            createStudentProblemOrganizationImmutabilityTriggers(connection)
            createStudentDestinationReattestationImmutabilityTriggers(connection)

            connection.verifyV16ErrorAttributionBoundary()
        }
    }

private fun SQLiteConnection.verifyV16ErrorAttributionBoundary() {
    val columns =
        prepare("PRAGMA table_info(`$V15_ATTRIBUTION_TABLE`)").use { statement ->
            buildSet {
                while (statement.step()) add(statement.getText(1))
            }
        }
    check(columns == V16_ATTRIBUTION_COLUMNS) {
        "Student mistake v16 attribution columns are invalid: ${columns.sorted()}"
    }
    val installedIndexes =
        prepare(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'index'
              AND tbl_name IN ('$V15_ATTRIBUTION_TABLE', '$V15_EVIDENCE_TABLE')
            """.trimIndent(),
        ).use { statement ->
            buildSet {
                while (statement.step()) add(statement.getText(0))
            }
        }
    val missingIndexes = V16_REBUILT_TABLE_INDEX_NAMES - installedIndexes
    check(missingIndexes.isEmpty()) {
        "Student mistake v16 attribution indexes are missing: ${missingIndexes.sorted()}"
    }
    prepare("PRAGMA foreign_key_check").use { statement ->
        check(!statement.step()) { "Student mistake v16 migration left a broken foreign key" }
    }
    verifyStudentProblemOrganizationImmutabilityTriggers(this)
    verifyStudentDestinationReattestationImmutabilityTriggers(this)
}

private fun SQLiteConnection.rowCount(tableName: String): Long =
    prepare("SELECT COUNT(*) FROM `$tableName`").use { statement ->
        check(statement.step()) { "Expected one row count for $tableName" }
        statement.getLong(0)
    }

private fun SQLiteConnection.rowCount(query: LegacyDestinationInvalidationSource): Long =
    prepare(query.sql).use { statement ->
        check(statement.step()) { "Expected one legacy destination invalidation count" }
        statement.getLong(0)
    }

internal fun createStudentDestinationReattestationImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    V16_DESTINATION_REATTESTATION_TRIGGER_SQL.forEach(connection::execSQL)
}

internal fun verifyStudentDestinationReattestationImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    val installed =
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { statement ->
            buildSet {
                while (statement.step()) add(statement.getText(0))
            }
        }
    val missing = STUDENT_DESTINATION_REATTESTATION_IMMUTABILITY_TRIGGER_NAMES - installed
    check(missing.isEmpty()) {
        "Student destination reattestation immutability triggers are missing: " +
            missing.sorted()
    }
}

private const val V15_ATTRIBUTION_TABLE = "student_problem_error_attribution"
private const val V15_EVIDENCE_TABLE = "student_problem_error_evidence"

private val V15_REBUILT_TABLE_TRIGGER_NAMES =
    listOf(
        "immutable_student_problem_error_attribution_organization_update",
        "immutable_student_problem_error_attribution_organization_delete",
        "immutable_student_problem_error_evidence_organization_update",
        "immutable_student_problem_error_evidence_organization_delete",
    )

private val V15_REBUILT_TABLE_INDEX_NAMES =
    setOf(
        "index_student_problem_error_attribution_basis_revision_id_recorded_at_epoch_millis_attribution_id",
        "index_student_problem_error_attribution_solution_analysis_id_basis_revision_id",
        "index_student_problem_error_attribution_organization_receipt_id",
        "index_student_problem_error_attribution_result_canonical_fingerprint",
        "index_student_problem_error_attribution_attribution_id_basis_revision_id",
        "index_student_problem_error_evidence_attribution_id_basis_revision_id",
        "index_student_problem_error_evidence_basis_revision_id_block_id_source_asset_id",
    )

private val V16_ATTRIBUTION_COLUMNS =
    setOf(
        "attribution_id",
        "basis_revision_id",
        "organization_receipt_id",
        "solution_analysis_id",
        "resolution_status",
        "rationale_markdown",
        "step_ordinal",
        "atomic_reference_id",
        "model_provider_id",
        "model_id",
        "analyzer_version",
        "result_canonical_fingerprint",
        "recorded_at_epoch_millis",
    )

private val V16_REBUILT_TABLE_INDEX_NAMES = V15_REBUILT_TABLE_INDEX_NAMES

private val V16_ATTRIBUTION_TABLE_SQL =
    """
    CREATE TABLE `$V15_ATTRIBUTION_TABLE` (
        `attribution_id` TEXT NOT NULL,
        `basis_revision_id` TEXT NOT NULL,
        `organization_receipt_id` TEXT,
        `solution_analysis_id` TEXT,
        `resolution_status` TEXT NOT NULL,
        `rationale_markdown` TEXT NOT NULL,
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
    """.trimIndent()

private val V16_EVIDENCE_TABLE_SQL =
    """
    CREATE TABLE `$V15_EVIDENCE_TABLE` (
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
    """.trimIndent()

private val V16_ATTRIBUTION_INDEX_SQL =
    listOf(
        "CREATE INDEX `index_student_problem_error_attribution_basis_revision_id_recorded_at_epoch_millis_attribution_id` ON `$V15_ATTRIBUTION_TABLE` (`basis_revision_id`, `recorded_at_epoch_millis`, `attribution_id`)",
        "CREATE INDEX `index_student_problem_error_attribution_solution_analysis_id_basis_revision_id` ON `$V15_ATTRIBUTION_TABLE` (`solution_analysis_id`, `basis_revision_id`)",
        "CREATE INDEX `index_student_problem_error_attribution_organization_receipt_id` ON `$V15_ATTRIBUTION_TABLE` (`organization_receipt_id`)",
        "CREATE INDEX `index_student_problem_error_attribution_result_canonical_fingerprint` ON `$V15_ATTRIBUTION_TABLE` (`result_canonical_fingerprint`)",
        "CREATE UNIQUE INDEX `index_student_problem_error_attribution_attribution_id_basis_revision_id` ON `$V15_ATTRIBUTION_TABLE` (`attribution_id`, `basis_revision_id`)",
    )

private val V16_EVIDENCE_INDEX_SQL =
    listOf(
        "CREATE INDEX `index_student_problem_error_evidence_attribution_id_basis_revision_id` ON `$V15_EVIDENCE_TABLE` (`attribution_id`, `basis_revision_id`)",
        "CREATE INDEX `index_student_problem_error_evidence_basis_revision_id_block_id_source_asset_id` ON `$V15_EVIDENCE_TABLE` (`basis_revision_id`, `block_id`, `source_asset_id`)",
    )

private val V16_DESTINATION_INVALIDATION_TABLE_SQL =
    """
    CREATE TABLE `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE` (
        `migration_id` TEXT NOT NULL,
        `revision_id` TEXT NOT NULL,
        `legacy_destination_record_canonical_fingerprint` TEXT NOT NULL,
        `invalidation_reason` TEXT NOT NULL,
        `replacement_policy_version` INTEGER NOT NULL,
        `invalidated_at_schema_version` INTEGER NOT NULL,
        PRIMARY KEY(`migration_id`, `revision_id`),
        FOREIGN KEY(`migration_id`, `revision_id`)
            REFERENCES `$STUDENT_MIGRATION_DESTINATION_RECORD_TABLE`
                (`migration_id`, `revision_id`)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent()

private val V16_DESTINATION_REATTESTATION_RECEIPT_TABLE_SQL =
    """
    CREATE TABLE `$STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE` (
        `migration_id` TEXT NOT NULL,
        `revision_id` TEXT NOT NULL,
        `legacy_destination_record_canonical_fingerprint` TEXT NOT NULL,
        `replacement_destination_record_canonical_fingerprint` TEXT NOT NULL,
        `canonical_policy_version` INTEGER NOT NULL,
        `issuer_key_id` TEXT NOT NULL,
        `issuer_version` TEXT NOT NULL,
        `issued_at_epoch_millis` INTEGER NOT NULL,
        `receipt_canonical_fingerprint` TEXT NOT NULL,
        PRIMARY KEY(`migration_id`, `revision_id`),
        FOREIGN KEY(`migration_id`, `revision_id`)
            REFERENCES `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE`
                (`migration_id`, `revision_id`)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent()

private val V16_DESTINATION_INVALIDATION_INDEX_SQL =
    listOf(
        "CREATE INDEX `index_student_mistake_destination_attestation_invalidation_revision_id` ON `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE` (`revision_id`)",
        "CREATE INDEX `index_student_mistake_destination_attestation_invalidation_legacy_destination_record_canonical_fingerprint` ON `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE` (`legacy_destination_record_canonical_fingerprint`)",
    )

private val V16_DESTINATION_REATTESTATION_RECEIPT_INDEX_SQL =
    listOf(
        "CREATE INDEX `index_student_mistake_destination_reattestation_receipt_replacement_destination_record_canonical_fingerprint` ON `$STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE` (`replacement_destination_record_canonical_fingerprint`)",
        "CREATE UNIQUE INDEX `index_student_mistake_destination_reattestation_receipt_receipt_canonical_fingerprint` ON `$STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE` (`receipt_canonical_fingerprint`)",
    )

private val V16_LEGACY_DESTINATION_INVALIDATION_SOURCE =
    LegacyDestinationInvalidationSource(
        """
        SELECT COUNT(*)
        FROM `$STUDENT_MIGRATION_DESTINATION_RECORD_TABLE` AS destination
        INNER JOIN `student_mistake_migration_checkpoint` AS checkpoint
          ON checkpoint.migration_id = destination.migration_id
         AND checkpoint.destination_ledger_version = 2
        WHERE EXISTS (
            SELECT 1
            FROM `$V15_ATTRIBUTION_TABLE` AS attribution
            WHERE attribution.basis_revision_id = destination.revision_id
        )
        """.trimIndent(),
    )

private val V16_INSERT_LEGACY_DESTINATION_INVALIDATIONS_SQL =
    """
    INSERT INTO `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE` (
        `migration_id`, `revision_id`,
        `legacy_destination_record_canonical_fingerprint`, `invalidation_reason`,
        `replacement_policy_version`, `invalidated_at_schema_version`
    )
    SELECT destination.migration_id, destination.revision_id,
           destination.destination_record_canonical_fingerprint,
           '$STUDENT_DESTINATION_REATTESTATION_REASON_CONFIDENCE_REMOVED',
           $STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION,
           $STUDENT_MISTAKE_DATABASE_VERSION
    FROM `$STUDENT_MIGRATION_DESTINATION_RECORD_TABLE` AS destination
    INNER JOIN `student_mistake_migration_checkpoint` AS checkpoint
      ON checkpoint.migration_id = destination.migration_id
     AND checkpoint.destination_ledger_version = 2
    WHERE EXISTS (
        SELECT 1
        FROM `$V15_ATTRIBUTION_TABLE` AS attribution
        WHERE attribution.basis_revision_id = destination.revision_id
    )
    """.trimIndent()

private val V16_DESTINATION_REATTESTATION_TRIGGER_SQL =
    listOf(
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_destination_attestation_invalidation_insert
        BEFORE INSERT ON `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE`
        WHEN NEW.invalidation_reason !=
                '$STUDENT_DESTINATION_REATTESTATION_REASON_CONFIDENCE_REMOVED'
          OR NEW.replacement_policy_version !=
                $STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION
          OR NEW.invalidated_at_schema_version != $STUDENT_MISTAKE_DATABASE_VERSION
          OR NOT EXISTS (
              SELECT 1
              FROM `$STUDENT_MIGRATION_DESTINATION_RECORD_TABLE` AS destination
              INNER JOIN `student_mistake_migration_checkpoint` AS checkpoint
                ON checkpoint.migration_id = destination.migration_id
               AND checkpoint.destination_ledger_version = 2
              WHERE destination.migration_id = NEW.migration_id
                AND destination.revision_id = NEW.revision_id
                AND destination.destination_record_canonical_fingerprint =
                    NEW.legacy_destination_record_canonical_fingerprint
                AND EXISTS (
                    SELECT 1
                    FROM `$V15_ATTRIBUTION_TABLE` AS attribution
                    WHERE attribution.basis_revision_id = destination.revision_id
                )
          )
        BEGIN
            SELECT RAISE(ABORT, 'invalid legacy destination attestation invalidation');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_destination_attestation_invalidation_update
        BEFORE UPDATE ON `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE`
        BEGIN
            SELECT RAISE(ABORT, 'immutable destination attestation invalidation');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_destination_attestation_invalidation_delete
        BEFORE DELETE ON `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE`
        BEGIN
            SELECT RAISE(ABORT, 'immutable destination attestation invalidation');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_destination_reattestation_receipt_insert
        BEFORE INSERT ON `$STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE`
        WHEN NEW.canonical_policy_version != $STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION
          OR NOT EXISTS (
              SELECT 1
              FROM `$STUDENT_MIGRATION_DESTINATION_ATTESTATION_INVALIDATION_TABLE` AS invalidation
              WHERE invalidation.migration_id = NEW.migration_id
                AND invalidation.revision_id = NEW.revision_id
                AND invalidation.legacy_destination_record_canonical_fingerprint =
                    NEW.legacy_destination_record_canonical_fingerprint
                AND invalidation.replacement_policy_version =
                    NEW.canonical_policy_version
          )
        BEGIN
            SELECT RAISE(ABORT, 'destination reattestation is not bound to its invalidation');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_destination_reattestation_receipt_update
        BEFORE UPDATE ON `$STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE`
        BEGIN
            SELECT RAISE(ABORT, 'immutable destination reattestation receipt');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
            immutable_student_mistake_destination_reattestation_receipt_delete
        BEFORE DELETE ON `$STUDENT_MIGRATION_DESTINATION_REATTESTATION_RECEIPT_TABLE`
        BEGIN
            SELECT RAISE(ABORT, 'immutable destination reattestation receipt');
        END
        """.trimIndent(),
    )

internal val STUDENT_DESTINATION_REATTESTATION_IMMUTABILITY_TRIGGER_NAMES =
    setOf(
        "immutable_student_mistake_destination_attestation_invalidation_insert",
        "immutable_student_mistake_destination_attestation_invalidation_update",
        "immutable_student_mistake_destination_attestation_invalidation_delete",
        "immutable_student_mistake_destination_reattestation_receipt_insert",
        "immutable_student_mistake_destination_reattestation_receipt_update",
        "immutable_student_mistake_destination_reattestation_receipt_delete",
    )

internal const val STUDENT_DESTINATION_REATTESTATION_REASON_CONFIDENCE_REMOVED =
    "CONFIDENCE_REMOVED_V16"

private data class LegacyDestinationInvalidationSource(
    val sql: String,
)
