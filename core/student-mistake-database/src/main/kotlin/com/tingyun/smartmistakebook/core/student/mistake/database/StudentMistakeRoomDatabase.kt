package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal const val STUDENT_MISTAKE_DATABASE_VERSION = 20

internal fun verifyStudentProblemIdentityReceiptImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    val installed =
        connection.prepare(
            """
            SELECT name, sql
            FROM sqlite_master
            WHERE type = 'trigger'
              AND name IN (
                '$STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_NAME',
                '$STUDENT_PROBLEM_IDENTITY_RECEIPT_DELETE_TRIGGER_NAME'
              )
            """.trimIndent(),
        ).use { statement ->
            buildMap {
                while (statement.step()) {
                    put(statement.getText(0), statement.getText(1))
                }
            }
        }
    val invalid =
        STUDENT_PROBLEM_IDENTITY_RECEIPT_IMMUTABILITY_TRIGGER_SQL
            .filter { (name, expectedSql) ->
                installed[name]?.canonicalTriggerSql() !=
                    expectedSql.canonicalTriggerSql()
            }
            .keys
    check(invalid.isEmpty()) {
        "Student problem identity receipt immutability trigger definitions are invalid: " +
            invalid.sorted()
    }
}

private fun String.canonicalTriggerSql(): String =
    replace('`', ' ')
        .lowercase()
        // sqlite_schema omits the creation-only clause even when it was supplied.
        .replace(Regex("\\bif\\s+not\\s+exists\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun verifyStudentProblemOrganizationImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    val installed =
        connection.prepare(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'trigger'
            """.trimIndent(),
        ).use { statement ->
            buildSet {
                while (statement.step()) add(statement.getText(0))
            }
        }
    val missing = STUDENT_PROBLEM_ORGANIZATION_IMMUTABILITY_TRIGGER_NAMES - installed
    check(missing.isEmpty()) {
        "Student problem organization immutability triggers are missing: ${missing.sorted()}"
    }
}

internal fun verifyStudentCutoverAndMigrationLedgerImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    val installed =
        connection.prepare(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'trigger'
            """.trimIndent(),
        ).use { statement ->
            buildSet {
                while (statement.step()) add(statement.getText(0))
            }
        }
    val expected =
        STUDENT_CUTOVER_IMMUTABILITY_TRIGGER_NAMES +
            STUDENT_IMPORT_SNAPSHOT_IMMUTABILITY_TRIGGER_NAMES +
            STUDENT_DESTINATION_REATTESTATION_IMMUTABILITY_TRIGGER_NAMES
    val missing = expected - installed
    check(missing.isEmpty()) {
        "Student cutover/migration immutability triggers are missing: ${missing.sorted()}"
    }
}

internal fun createStudentReviewReceiptImmutabilityTriggers(connection: SQLiteConnection) {
    createStudentReviewReceiptImmutabilityTriggers(
        connection = connection,
        tableNames = STUDENT_REVIEW_IMMUTABLE_RECEIPT_TABLES
            .filter { tableName -> connection.tableExists(tableName) },
    )
}

internal fun createStudentReviewReceiptImmutabilityTriggers(
    connection: SQLiteConnection,
    tableNames: Iterable<String>,
) {
    tableNames.forEach { tableName ->
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS immutable_${tableName}_update
            BEFORE UPDATE ON `$tableName`
            BEGIN
                SELECT RAISE(ABORT, 'immutable student review receipt');
            END
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS immutable_${tableName}_delete
            BEFORE DELETE ON `$tableName`
            BEGIN
                SELECT RAISE(ABORT, 'immutable student review receipt');
            END
            """.trimIndent(),
        )
    }
}

internal fun verifyStudentReviewReceiptImmutabilityTriggers(connection: SQLiteConnection) {
    verifyStudentReviewReceiptImmutabilityTriggers(
        connection = connection,
        tableNames = STUDENT_REVIEW_IMMUTABLE_RECEIPT_TABLES
            .filter { tableName -> connection.tableExists(tableName) },
    )
}

internal fun verifyStudentReviewReceiptImmutabilityTriggers(
    connection: SQLiteConnection,
    tableNames: Iterable<String>,
) {
    val installed =
        connection.prepare(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'trigger'
            """.trimIndent(),
        ).use { statement ->
            buildSet {
                while (statement.step()) add(statement.getText(0))
            }
        }
    val expected =
        tableNames.flatMapTo(mutableSetOf()) { tableName ->
            listOf(
                "immutable_${tableName}_update",
                "immutable_${tableName}_delete",
            )
        }
    val missing = expected - installed
    check(missing.isEmpty()) {
        "Student review receipt immutability triggers are missing: ${missing.sorted()}"
    }
}

internal fun createStudentLegacyReviewRetirementTriggers(connection: SQLiteConnection) {
    STUDENT_LEGACY_REVIEW_RETIREMENT_TRIGGER_SQL.values.forEach(connection::execSQL)
}

internal fun verifyStudentLegacyReviewRetirementTriggers(connection: SQLiteConnection) {
    val installed = connection.prepare(
        """
        SELECT name, sql
        FROM sqlite_master
        WHERE type = 'trigger'
          AND name IN (${STUDENT_LEGACY_REVIEW_RETIREMENT_TRIGGER_SQL.keys.joinToString { "'$it'" }})
        """.trimIndent(),
    ).use { statement ->
        buildMap {
            while (statement.step()) put(statement.getText(0), statement.getText(1))
        }
    }
    val invalid = STUDENT_LEGACY_REVIEW_RETIREMENT_TRIGGER_SQL.filter { (name, expected) ->
        installed[name]?.canonicalTriggerSql() != expected.canonicalTriggerSql()
    }.keys
    check(invalid.isEmpty()) {
        "Student legacy review retirement trigger definitions are invalid: ${invalid.sorted()}"
    }
}

private val STUDENT_LEGACY_REVIEW_RETIREMENT_TRIGGER_SQL = linkedMapOf(
    "reject_inserted_student_legacy_review_retirement" to
        """
        CREATE TRIGGER IF NOT EXISTS reject_inserted_student_legacy_review_retirement
        BEFORE INSERT ON student_store_outbox
        WHEN NEW.delivery_state = 'RETIRED_UNSAFE_LEGACY'
        BEGIN
            SELECT RAISE(ABORT, 'legacy review retirement requires a pending owner row');
        END
        """.trimIndent(),
    "validate_student_legacy_review_retirement" to
        """
        CREATE TRIGGER IF NOT EXISTS validate_student_legacy_review_retirement
        BEFORE UPDATE ON student_store_outbox
        WHEN NEW.delivery_state = 'RETIRED_UNSAFE_LEGACY'
             AND OLD.delivery_state != 'RETIRED_UNSAFE_LEGACY'
        BEGIN
            SELECT CASE WHEN NOT (
                OLD.delivery_state = 'PENDING'
                AND OLD.source_store = 'STUDENT_MISTAKES'
                AND OLD.destination_store = 'LEARNER_MASTERY'
                AND OLD.payload_type = 'review_observation_captured'
                AND OLD.payload_version IN (1, 2)
                AND OLD.delivered_at_epoch_millis IS NULL
                AND NEW.delivered_at_epoch_millis IS NOT NULL
                AND NEW.delivered_at_epoch_millis >= OLD.occurred_at_epoch_millis
                AND NEW.event_id IS OLD.event_id
                AND NEW.source_store IS OLD.source_store
                AND NEW.destination_store IS OLD.destination_store
                AND NEW.learner_id IS OLD.learner_id
                AND NEW.aggregate_id IS OLD.aggregate_id
                AND NEW.aggregate_version IS OLD.aggregate_version
                AND NEW.payload_type IS OLD.payload_type
                AND NEW.payload_version IS OLD.payload_version
                AND NEW.payload_canonical_fingerprint IS OLD.payload_canonical_fingerprint
                AND NEW.payload_wire IS OLD.payload_wire
                AND NEW.envelope_canonical_fingerprint IS OLD.envelope_canonical_fingerprint
                AND NEW.occurred_at_epoch_millis IS OLD.occurred_at_epoch_millis
                AND NEW.idempotency_key IS OLD.idempotency_key
                AND NEW.source_store_generation IS OLD.source_store_generation
                AND NEW.delivery_attempt_count IS OLD.delivery_attempt_count
                AND NEW.available_at_epoch_millis IS OLD.available_at_epoch_millis
            ) THEN RAISE(ABORT, 'invalid legacy review retirement') END;
        END
        """.trimIndent(),
    "immutable_student_legacy_review_retirement_update" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_legacy_review_retirement_update
        BEFORE UPDATE ON student_store_outbox
        WHEN OLD.delivery_state = 'RETIRED_UNSAFE_LEGACY'
        BEGIN
            SELECT RAISE(ABORT, 'immutable legacy review retirement');
        END
        """.trimIndent(),
    "immutable_student_legacy_review_retirement_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_legacy_review_retirement_delete
        BEFORE DELETE ON student_store_outbox
        WHEN OLD.delivery_state = 'RETIRED_UNSAFE_LEGACY'
        BEGIN
            SELECT RAISE(ABORT, 'immutable legacy review retirement');
        END
        """.trimIndent(),
)

private val STUDENT_REVIEW_IMMUTABLE_RECEIPT_TABLES =
    listOf(
        "student_review_transition_receipt",
        "student_review_reveal_receipt",
        "student_trusted_review_answer_rule",
        "student_trusted_review_lease_receipt",
        "student_trusted_review_attempt_receipt",
        "student_trusted_review_assistance_receipt",
        "student_tutor_interaction_answer_certificate",
        "student_tutor_interaction_answer_certificate_status_event",
        "student_tutor_interaction_answer_certificate_lease_receipt",
        "student_tutor_interaction_answer_evaluation_receipt",
    )

internal val STUDENT_REVIEW_V6_IMMUTABLE_RECEIPT_TABLES =
    listOf(
        "student_review_transition_receipt",
        "student_review_reveal_receipt",
    )

internal const val STUDENT_PROBLEM_ORGANIZATION_REVIEW_ISSUER_KEY_ID =
    "student-problem-organization-review-owner"
internal const val STUDENT_PROBLEM_ORGANIZATION_REVIEW_ISSUER_VERSION = "v1"
internal const val STUDENT_PROBLEM_IDENTITY_EVIDENCE_ISSUER_KEY_ID =
    "student-problem-identity-evidence-owner"
internal const val STUDENT_PROBLEM_IDENTITY_EVIDENCE_ISSUER_VERSION = "v1"
