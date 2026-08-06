package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Seals the mastery-to-student route and quarantines every pre-authentication inbox row. */
internal val STUDENT_MISTAKE_MIGRATION_18_19: Migration =
    object : Migration(18, 19) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STUDENT_MASTERY_RELAY_V19_TABLE_SQL.forEach(connection::execSQL)
            STUDENT_MASTERY_RELAY_V19_INDEX_SQL.forEach(connection::execSQL)
            val legacyCount = connection.studentV19Count("student_store_inbox")
            connection.execSQL(
                """
                INSERT INTO student_pre_auth_inbox_quarantine (
                    event_id, source_store, destination_store, aggregate_id,
                    aggregate_version, payload_type, payload_version,
                    payload_canonical_fingerprint, payload_wire,
                    envelope_canonical_fingerprint, occurred_at_epoch_millis,
                    idempotency_key, source_store_generation, legacy_apply_state,
                    received_at_epoch_millis, legacy_applied_at_epoch_millis,
                    quarantined_at_epoch_millis, quarantine_reason
                )
                SELECT
                    event_id, source_store, destination_store, aggregate_id,
                    aggregate_version, payload_type, payload_version,
                    payload_canonical_fingerprint, payload_wire,
                    envelope_canonical_fingerprint, occurred_at_epoch_millis,
                    idempotency_key, source_store_generation, apply_state,
                    received_at_epoch_millis, applied_at_epoch_millis,
                    MAX(
                        occurred_at_epoch_millis,
                        received_at_epoch_millis,
                        COALESCE(applied_at_epoch_millis, 0)
                    ),
                    '$STUDENT_PRE_AUTH_INBOX_REASON'
                FROM student_store_inbox
                """.trimIndent(),
            )
            check(connection.studentV19Count("student_pre_auth_inbox_quarantine") == legacyCount) {
                "Student v19 quarantine did not preserve every legacy inbox row"
            }
            connection.execSQL("DELETE FROM student_store_inbox")
            check(connection.studentV19Count("student_store_inbox") == 0L) {
                "A pre-authentication student inbox row remained active"
            }
            createStudentMasteryRelayV19Guards(connection)
        }
    }

internal fun createStudentMasteryRelayV19Guards(connection: SQLiteConnection) {
    STUDENT_MASTERY_RELAY_V19_TRIGGER_SQL.values.forEach(connection::execSQL)
}

private fun SQLiteConnection.studentV19Count(tableName: String): Long =
    prepare("SELECT COUNT(*) FROM `$tableName`").use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private val STUDENT_MASTERY_RELAY_V19_TABLE_SQL = listOf(
    """
    CREATE TABLE student_mastery_relay_source_binding (
        learner_id TEXT NOT NULL,
        source_store TEXT NOT NULL,
        source_store_generation TEXT NOT NULL,
        relay_epoch TEXT NOT NULL,
        issuer_key_id TEXT NOT NULL,
        algorithm_version TEXT NOT NULL,
        first_verification_receipt_canonical_fingerprint TEXT NOT NULL,
        pinned_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(learner_id)
    )
    """.trimIndent(),
    """
    CREATE TABLE student_authenticated_mastery_inbox_receipt (
        event_id TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        problem_id TEXT NOT NULL,
        problem_revision_id TEXT NOT NULL,
        event_sequence INTEGER NOT NULL,
        review_session_id TEXT NOT NULL,
        review_queue_item_id TEXT NOT NULL,
        submission_id TEXT NOT NULL,
        presentation_id TEXT NOT NULL,
        scope_canonical_fingerprint TEXT NOT NULL,
        source_store TEXT NOT NULL,
        source_store_generation TEXT NOT NULL,
        relay_epoch TEXT NOT NULL,
        issuer_key_id TEXT NOT NULL,
        algorithm_version TEXT NOT NULL,
        envelope_canonical_fingerprint TEXT NOT NULL,
        proof_canonical_fingerprint TEXT NOT NULL,
        verification_receipt_canonical_fingerprint TEXT NOT NULL,
        recorded_at_epoch_millis INTEGER NOT NULL,
        received_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(event_id),
        FOREIGN KEY(event_id) REFERENCES student_store_inbox(event_id)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent(),
    """
    CREATE TABLE student_pre_auth_inbox_quarantine (
        event_id TEXT NOT NULL,
        source_store TEXT NOT NULL,
        destination_store TEXT NOT NULL,
        aggregate_id TEXT NOT NULL,
        aggregate_version INTEGER NOT NULL,
        payload_type TEXT NOT NULL,
        payload_version INTEGER NOT NULL,
        payload_canonical_fingerprint TEXT NOT NULL,
        payload_wire TEXT NOT NULL,
        envelope_canonical_fingerprint TEXT NOT NULL,
        occurred_at_epoch_millis INTEGER NOT NULL,
        idempotency_key TEXT NOT NULL,
        source_store_generation TEXT NOT NULL,
        legacy_apply_state TEXT NOT NULL,
        received_at_epoch_millis INTEGER NOT NULL,
        legacy_applied_at_epoch_millis INTEGER,
        quarantined_at_epoch_millis INTEGER NOT NULL,
        quarantine_reason TEXT NOT NULL,
        PRIMARY KEY(event_id)
    )
    """.trimIndent(),
    """
    CREATE TABLE student_mastery_relay_reauthorization_case (
        case_id TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        bound_source_store_generation TEXT NOT NULL,
        bound_relay_epoch TEXT NOT NULL,
        bound_issuer_key_id TEXT NOT NULL,
        candidate_source_store_generation TEXT NOT NULL,
        candidate_relay_epoch TEXT NOT NULL,
        candidate_issuer_key_id TEXT NOT NULL,
        candidate_algorithm_version TEXT NOT NULL,
        candidate_verification_receipt_canonical_fingerprint TEXT NOT NULL,
        detected_at_epoch_millis INTEGER NOT NULL,
        reason TEXT NOT NULL,
        PRIMARY KEY(case_id)
    )
    """.trimIndent(),
    """
    CREATE TABLE student_mastery_relay_reauthorization_resolution (
        resolution_id TEXT NOT NULL,
        case_id TEXT NOT NULL,
        authorization_canonical_fingerprint TEXT NOT NULL,
        authorized_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(resolution_id),
        FOREIGN KEY(case_id) REFERENCES student_mastery_relay_reauthorization_case(case_id)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent(),
)

private val STUDENT_MASTERY_RELAY_V19_INDEX_SQL = listOf(
    "CREATE INDEX index_student_authenticated_mastery_inbox_receipt_learner_id_received_at_epoch_millis ON student_authenticated_mastery_inbox_receipt(learner_id, received_at_epoch_millis)",
    "CREATE INDEX index_student_authenticated_mastery_inbox_receipt_source_store_generation_relay_epoch ON student_authenticated_mastery_inbox_receipt(source_store_generation, relay_epoch)",
    "CREATE UNIQUE INDEX index_student_authenticated_mastery_inbox_receipt_proof_canonical_fingerprint ON student_authenticated_mastery_inbox_receipt(proof_canonical_fingerprint)",
    "CREATE UNIQUE INDEX index_student_authenticated_mastery_inbox_receipt_verification_receipt_canonical_fingerprint ON student_authenticated_mastery_inbox_receipt(verification_receipt_canonical_fingerprint)",
    "CREATE INDEX index_student_authenticated_mastery_inbox_receipt_problem_id_problem_revision_id_event_sequence ON student_authenticated_mastery_inbox_receipt(problem_id, problem_revision_id, event_sequence)",
    "CREATE UNIQUE INDEX index_student_pre_auth_inbox_quarantine_idempotency_key ON student_pre_auth_inbox_quarantine(idempotency_key)",
    "CREATE UNIQUE INDEX index_student_pre_auth_inbox_quarantine_envelope_canonical_fingerprint ON student_pre_auth_inbox_quarantine(envelope_canonical_fingerprint)",
    "CREATE INDEX index_student_pre_auth_inbox_quarantine_quarantined_at_epoch_millis ON student_pre_auth_inbox_quarantine(quarantined_at_epoch_millis)",
    "CREATE INDEX index_student_mastery_relay_reauthorization_case_learner_id_detected_at_epoch_millis ON student_mastery_relay_reauthorization_case(learner_id, detected_at_epoch_millis)",
    "CREATE UNIQUE INDEX index_student_mastery_relay_reauthorization_case_candidate_verification_receipt_canonical_fingerprint ON student_mastery_relay_reauthorization_case(candidate_verification_receipt_canonical_fingerprint)",
    "CREATE UNIQUE INDEX index_student_mastery_relay_reauthorization_resolution_case_id ON student_mastery_relay_reauthorization_resolution(case_id)",
)

private val STUDENT_MASTERY_RELAY_V19_TRIGGER_SQL = linkedMapOf(
    "immutable_student_mastery_source_binding_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mastery_source_binding_delete
        BEFORE DELETE ON student_mastery_relay_source_binding
        BEGIN SELECT RAISE(ABORT, 'mastery relay source binding cannot be deleted'); END
        """.trimIndent(),
    "immutable_student_authenticated_mastery_receipt_update" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_authenticated_mastery_receipt_update
        BEFORE UPDATE ON student_authenticated_mastery_inbox_receipt
        BEGIN SELECT RAISE(ABORT, 'immutable authenticated mastery receipt'); END
        """.trimIndent(),
    "immutable_student_authenticated_mastery_receipt_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_authenticated_mastery_receipt_delete
        BEFORE DELETE ON student_authenticated_mastery_inbox_receipt
        BEGIN SELECT RAISE(ABORT, 'immutable authenticated mastery receipt'); END
        """.trimIndent(),
    "immutable_student_pre_auth_inbox_quarantine_update" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_pre_auth_inbox_quarantine_update
        BEFORE UPDATE ON student_pre_auth_inbox_quarantine
        BEGIN SELECT RAISE(ABORT, 'immutable pre-authentication inbox quarantine'); END
        """.trimIndent(),
    "immutable_student_pre_auth_inbox_quarantine_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_pre_auth_inbox_quarantine_delete
        BEFORE DELETE ON student_pre_auth_inbox_quarantine
        BEGIN SELECT RAISE(ABORT, 'immutable pre-authentication inbox quarantine'); END
        """.trimIndent(),
    "immutable_student_mastery_reauthorization_case_update" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mastery_reauthorization_case_update
        BEFORE UPDATE ON student_mastery_relay_reauthorization_case
        BEGIN SELECT RAISE(ABORT, 'immutable mastery relay reauthorization case'); END
        """.trimIndent(),
    "immutable_student_mastery_reauthorization_case_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mastery_reauthorization_case_delete
        BEFORE DELETE ON student_mastery_relay_reauthorization_case
        BEGIN SELECT RAISE(ABORT, 'immutable mastery relay reauthorization case'); END
        """.trimIndent(),
    "immutable_student_mastery_reauthorization_resolution_update" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mastery_reauthorization_resolution_update
        BEFORE UPDATE ON student_mastery_relay_reauthorization_resolution
        BEGIN SELECT RAISE(ABORT, 'immutable mastery relay reauthorization resolution'); END
        """.trimIndent(),
    "immutable_student_mastery_reauthorization_resolution_delete" to
        """
        CREATE TRIGGER IF NOT EXISTS immutable_student_mastery_reauthorization_resolution_delete
        BEFORE DELETE ON student_mastery_relay_reauthorization_resolution
        BEGIN SELECT RAISE(ABORT, 'immutable mastery relay reauthorization resolution'); END
        """.trimIndent(),
)

internal const val STUDENT_PRE_AUTH_INBOX_REASON =
    "PRE_V19_DESTINATION_AUTHENTICATION_ABSENT"
