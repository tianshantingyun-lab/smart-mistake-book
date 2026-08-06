package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Seals the student-to-mastery boundary.
 *
 * V17 inbox rows contain routing fingerprints but no owner-verification receipt. They are copied
 * byte-for-byte to a quarantine archive and removed from the active authorization inbox. A source
 * must then be re-delivered through the authenticated owner bridge before it can authorize new
 * mastery writes.
 */
internal val LEARNER_MASTERY_MIGRATION_17_18: Migration =
    object : Migration(17, 18) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(CREATE_STUDENT_RELAY_SOURCE_BINDING_SQL)
            connection.execSQL(CREATE_AUTHENTICATED_STUDENT_INBOX_RECEIPT_SQL)
            AUTHENTICATED_STUDENT_INBOX_RECEIPT_INDEX_SQL.forEach(connection::execSQL)
            connection.execSQL(CREATE_PRE_AUTH_STUDENT_INBOX_QUARANTINE_SQL)
            PRE_AUTH_STUDENT_INBOX_QUARANTINE_INDEX_SQL.forEach(connection::execSQL)

            val activeInboxCount =
                connection.masteryMigration17To18Count("mastery_cross_store_inbox")
            connection.execSQL(
                """
                INSERT INTO mastery_pre_auth_student_inbox_quarantine (
                    event_id,
                    source_store,
                    destination_store,
                    aggregate_id,
                    aggregate_version,
                    payload_type,
                    payload_version,
                    payload_canonical_fingerprint,
                    payload_wire,
                    envelope_canonical_fingerprint,
                    idempotency_key,
                    source_store_generation,
                    occurred_at_epoch_millis,
                    received_at_epoch_millis,
                    processed_at_epoch_millis,
                    quarantined_at_epoch_millis,
                    quarantine_reason
                )
                SELECT
                    event_id,
                    source_store,
                    destination_store,
                    aggregate_id,
                    aggregate_version,
                    payload_type,
                    payload_version,
                    payload_canonical_fingerprint,
                    payload_wire,
                    envelope_canonical_fingerprint,
                    idempotency_key,
                    source_store_generation,
                    occurred_at_epoch_millis,
                    received_at_epoch_millis,
                    processed_at_epoch_millis,
                    MAX(
                        occurred_at_epoch_millis,
                        received_at_epoch_millis,
                        COALESCE(processed_at_epoch_millis, 0)
                    ),
                    '$MASTERY_PRE_AUTH_STUDENT_INBOX_QUARANTINE_REASON'
                FROM mastery_cross_store_inbox
                """.trimIndent(),
            )
            check(
                connection.masteryMigration17To18Count(
                    "mastery_pre_auth_student_inbox_quarantine",
                ) == activeInboxCount,
            ) {
                "Pre-authentication mastery inbox quarantine did not preserve every row"
            }

            // These rows derive authorization solely from the pre-authentication inbox.
            connection.execSQL("DELETE FROM mastery_problem_binding_authority")
            connection.execSQL("DELETE FROM mastery_problem_binding_authority_state")
            connection.execSQL("DELETE FROM mastery_cross_store_inbox")
            check(connection.masteryMigration17To18Count("mastery_cross_store_inbox") == 0L) {
                "Pre-authentication mastery inbox rows remained active after migration"
            }
        }
    }

private fun SQLiteConnection.masteryMigration17To18Count(tableName: String): Long =
    prepare("SELECT COUNT(*) FROM `$tableName`").use { statement ->
        check(statement.step()) { "SQLite did not return a migration row count" }
        statement.getLong(0)
    }

private const val CREATE_STUDENT_RELAY_SOURCE_BINDING_SQL =
    """
    CREATE TABLE IF NOT EXISTS `mastery_student_relay_source_binding` (
        `learner_id` TEXT NOT NULL,
        `source_store` TEXT NOT NULL,
        `source_store_generation` TEXT NOT NULL,
        `relay_epoch` TEXT NOT NULL,
        `algorithm_version` TEXT NOT NULL,
        `first_issuer_key_id` TEXT NOT NULL,
        `first_verification_receipt_canonical_fingerprint` TEXT NOT NULL,
        `pinned_at_epoch_millis` INTEGER NOT NULL,
        PRIMARY KEY(`learner_id`)
    )
    """

private const val CREATE_AUTHENTICATED_STUDENT_INBOX_RECEIPT_SQL =
    """
    CREATE TABLE IF NOT EXISTS `mastery_authenticated_student_inbox_receipt` (
        `event_id` TEXT NOT NULL,
        `learner_id` TEXT NOT NULL,
        `source_store` TEXT NOT NULL,
        `source_store_generation` TEXT NOT NULL,
        `relay_epoch` TEXT NOT NULL,
        `issuer_key_id` TEXT NOT NULL,
        `algorithm_version` TEXT NOT NULL,
        `envelope_canonical_fingerprint` TEXT NOT NULL,
        `proof_canonical_fingerprint` TEXT NOT NULL,
        `verification_receipt_canonical_fingerprint` TEXT NOT NULL,
        `received_at_epoch_millis` INTEGER NOT NULL,
        PRIMARY KEY(`event_id`),
        FOREIGN KEY(`event_id`) REFERENCES `mastery_cross_store_inbox`(`event_id`)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """

private val AUTHENTICATED_STUDENT_INBOX_RECEIPT_INDEX_SQL =
    listOf(
        "CREATE INDEX IF NOT EXISTS `index_mastery_authenticated_student_inbox_receipt_learner_id_received_at_epoch_millis` ON `mastery_authenticated_student_inbox_receipt` (`learner_id`, `received_at_epoch_millis`)",
        "CREATE INDEX IF NOT EXISTS `index_mastery_authenticated_student_inbox_receipt_source_store_generation_relay_epoch` ON `mastery_authenticated_student_inbox_receipt` (`source_store_generation`, `relay_epoch`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_mastery_authenticated_student_inbox_receipt_proof_canonical_fingerprint` ON `mastery_authenticated_student_inbox_receipt` (`proof_canonical_fingerprint`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_mastery_authenticated_student_inbox_receipt_verification_receipt_canonical_fingerprint` ON `mastery_authenticated_student_inbox_receipt` (`verification_receipt_canonical_fingerprint`)",
    )

private const val CREATE_PRE_AUTH_STUDENT_INBOX_QUARANTINE_SQL =
    """
    CREATE TABLE IF NOT EXISTS `mastery_pre_auth_student_inbox_quarantine` (
        `event_id` TEXT NOT NULL,
        `source_store` TEXT NOT NULL,
        `destination_store` TEXT NOT NULL,
        `aggregate_id` TEXT NOT NULL,
        `aggregate_version` INTEGER NOT NULL,
        `payload_type` TEXT NOT NULL,
        `payload_version` INTEGER NOT NULL,
        `payload_canonical_fingerprint` TEXT NOT NULL,
        `payload_wire` TEXT NOT NULL,
        `envelope_canonical_fingerprint` TEXT NOT NULL,
        `idempotency_key` TEXT NOT NULL,
        `source_store_generation` TEXT NOT NULL,
        `occurred_at_epoch_millis` INTEGER NOT NULL,
        `received_at_epoch_millis` INTEGER NOT NULL,
        `processed_at_epoch_millis` INTEGER,
        `quarantined_at_epoch_millis` INTEGER NOT NULL,
        `quarantine_reason` TEXT NOT NULL,
        PRIMARY KEY(`event_id`)
    )
    """

private val PRE_AUTH_STUDENT_INBOX_QUARANTINE_INDEX_SQL =
    listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_mastery_pre_auth_student_inbox_quarantine_idempotency_key` ON `mastery_pre_auth_student_inbox_quarantine` (`idempotency_key`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_mastery_pre_auth_student_inbox_quarantine_envelope_canonical_fingerprint` ON `mastery_pre_auth_student_inbox_quarantine` (`envelope_canonical_fingerprint`)",
        "CREATE INDEX IF NOT EXISTS `index_mastery_pre_auth_student_inbox_quarantine_quarantined_at_epoch_millis` ON `mastery_pre_auth_student_inbox_quarantine` (`quarantined_at_epoch_millis`)",
    )
