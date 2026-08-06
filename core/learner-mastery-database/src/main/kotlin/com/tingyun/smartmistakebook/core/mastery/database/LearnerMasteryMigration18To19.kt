package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Introduces source authentication without retrospectively trusting a v18 outbox row. */
internal val LEARNER_MASTERY_MIGRATION_18_19: Migration =
    object : Migration(18, 19) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(CREATE_MASTERY_OUTBOX_KEY_STATE_SQL)
            connection.execSQL(CREATE_MASTERY_PRE_AUTH_OUTBOX_QUARANTINE_SQL)
            MASTERY_PRE_AUTH_OUTBOX_QUARANTINE_INDEX_SQL.forEach(connection::execSQL)

            val pendingBefore = connection.migration18To19Count(
                "mastery_cross_store_outbox",
                "published_at_epoch_millis IS NULL",
            )
            connection.execSQL(
                """
                INSERT INTO mastery_pre_auth_outbox_quarantine (
                    event_id, source_store, source_store_generation, destination_store,
                    aggregate_id, aggregate_version, payload_type, payload_version,
                    learning_evidence_ref_fingerprint, problem_revision_ref_fingerprint,
                    payload_canonical_fingerprint, payload_wire,
                    envelope_canonical_fingerprint, idempotency_key,
                    created_at_epoch_millis, occurred_at_epoch_millis,
                    legacy_published_at_epoch_millis, learner_id,
                    quarantined_at_epoch_millis, quarantine_reason
                )
                SELECT
                    event_id, source_store, source_store_generation, destination_store,
                    aggregate_id, aggregate_version, payload_type, payload_version,
                    learning_evidence_ref_fingerprint, problem_revision_ref_fingerprint,
                    payload_canonical_fingerprint, payload_wire,
                    envelope_canonical_fingerprint, idempotency_key,
                    created_at_epoch_millis, occurred_at_epoch_millis,
                    published_at_epoch_millis, learner_id,
                    MAX(created_at_epoch_millis, occurred_at_epoch_millis),
                    '$MASTERY_PRE_AUTH_OUTBOX_REASON'
                FROM mastery_cross_store_outbox
                WHERE published_at_epoch_millis IS NULL
                """.trimIndent(),
            )
            check(
                connection.migration18To19Count(
                    "mastery_pre_auth_outbox_quarantine",
                    "1 = 1",
                ) == pendingBefore,
            ) { "Mastery v19 quarantine did not preserve every pending legacy outbox row" }
            connection.execSQL(
                """
                UPDATE mastery_cross_store_outbox
                SET published_at_epoch_millis = MAX(
                    created_at_epoch_millis,
                    occurred_at_epoch_millis
                )
                WHERE published_at_epoch_millis IS NULL
                """.trimIndent(),
            )
            check(
                connection.migration18To19Count(
                    "mastery_cross_store_outbox",
                    "published_at_epoch_millis IS NULL",
                ) == 0L,
            ) { "A pre-authentication mastery outbox row remained deliverable" }
            createMasteryOutboxAuthenticityGuards(connection)
        }
    }

private fun SQLiteConnection.migration18To19Count(
    tableName: String,
    predicate: String,
): Long =
    prepare("SELECT COUNT(*) FROM `$tableName` WHERE $predicate").use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private const val CREATE_MASTERY_OUTBOX_KEY_STATE_SQL =
    """
    CREATE TABLE mastery_outbox_authenticity_key_state (
        singleton_id INTEGER NOT NULL,
        key_id TEXT NOT NULL,
        key_version INTEGER NOT NULL,
        key_alias TEXT NOT NULL,
        algorithm_version TEXT NOT NULL,
        source_store_generation TEXT NOT NULL,
        relay_epoch TEXT NOT NULL,
        state TEXT NOT NULL,
        activated_at_epoch_millis INTEGER NOT NULL,
        canonical_fingerprint TEXT NOT NULL,
        keyed_sentinel_tag TEXT NOT NULL,
        PRIMARY KEY(singleton_id)
    )
    """

private const val CREATE_MASTERY_PRE_AUTH_OUTBOX_QUARANTINE_SQL =
    """
    CREATE TABLE mastery_pre_auth_outbox_quarantine (
        event_id TEXT NOT NULL,
        source_store TEXT NOT NULL,
        source_store_generation TEXT NOT NULL,
        destination_store TEXT NOT NULL,
        aggregate_id TEXT NOT NULL,
        aggregate_version INTEGER NOT NULL,
        payload_type TEXT NOT NULL,
        payload_version INTEGER NOT NULL,
        learning_evidence_ref_fingerprint TEXT NOT NULL,
        problem_revision_ref_fingerprint TEXT,
        payload_canonical_fingerprint TEXT NOT NULL,
        payload_wire TEXT NOT NULL,
        envelope_canonical_fingerprint TEXT NOT NULL,
        idempotency_key TEXT NOT NULL,
        created_at_epoch_millis INTEGER NOT NULL,
        occurred_at_epoch_millis INTEGER NOT NULL,
        legacy_published_at_epoch_millis INTEGER,
        learner_id TEXT NOT NULL,
        quarantined_at_epoch_millis INTEGER NOT NULL,
        quarantine_reason TEXT NOT NULL,
        PRIMARY KEY(event_id)
    )
    """

private val MASTERY_PRE_AUTH_OUTBOX_QUARANTINE_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX index_mastery_pre_auth_outbox_quarantine_idempotency_key ON mastery_pre_auth_outbox_quarantine(idempotency_key)",
    "CREATE UNIQUE INDEX index_mastery_pre_auth_outbox_quarantine_envelope_canonical_fingerprint ON mastery_pre_auth_outbox_quarantine(envelope_canonical_fingerprint)",
    "CREATE INDEX index_mastery_pre_auth_outbox_quarantine_learner_id_quarantined_at_epoch_millis ON mastery_pre_auth_outbox_quarantine(learner_id, quarantined_at_epoch_millis)",
)

internal const val MASTERY_PRE_AUTH_OUTBOX_REASON =
    "PRE_V19_SOURCE_AUTHENTICATION_ABSENT"
