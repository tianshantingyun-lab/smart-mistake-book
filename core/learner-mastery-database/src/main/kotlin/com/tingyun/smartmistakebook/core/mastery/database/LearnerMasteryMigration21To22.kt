package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Closes the pre-v22 model-semantic admission gap without rewriting immutable history.
 *
 * DB21 had no independently verifiable, non-model confirmation credential. Consequently every
 * persisted open-response ACCEPTED decision is conservatively quarantined. The derived active
 * generation is retired so no caller can observe a projection that still contains those events;
 * the normal bounded rebuild then replays only authorized inputs.
 */
internal val LEARNER_MASTERY_MIGRATION_21_22: Migration =
    object : Migration(21, 22) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(CREATE_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE)
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_legacy_quarantine_decision_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE(" +
                    "decision_fingerprint)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_legacy_quarantine_accepted_event_id_event_canonical_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE(" +
                    "accepted_event_id, event_canonical_fingerprint)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_legacy_quarantine_quarantine_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE(" +
                    "quarantine_fingerprint)",
            )

            var legacyAcceptedCount = 0L
            connection.prepare(
                """
                SELECT decision.accepted_event_id, event.canonical_fingerprint,
                       decision.decision_fingerprint,
                       MAX(decision.decided_at_epoch_millis, event.admitted_at_epoch_millis)
                FROM $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE AS decision
                JOIN mastery_learning_event AS event
                  ON event.event_id = decision.accepted_event_id
                WHERE decision.disposition = 'ACCEPTED'
                ORDER BY decision.accepted_event_id
                """.trimIndent(),
            ).use { source ->
                connection.prepare(
                    """
                    INSERT OR IGNORE INTO $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE(
                        accepted_event_id, event_canonical_fingerprint, decision_fingerprint,
                        quarantine_reason, policy_version, quarantined_at_epoch_millis,
                        quarantine_fingerprint
                    ) VALUES(?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { sink ->
                    while (source.step()) {
                        val eventId = source.getText(0)
                        val eventFingerprint = source.getText(1)
                        val decisionFingerprint = source.getText(2)
                        val quarantinedAt = source.getLong(3)
                        sink.reset()
                        sink.bindText(1, eventId)
                        sink.bindText(2, eventFingerprint)
                        sink.bindText(3, decisionFingerprint)
                        sink.bindText(4, LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_REASON)
                        sink.bindText(5, LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_POLICY_VERSION)
                        sink.bindLong(6, quarantinedAt)
                        sink.bindText(
                            7,
                            openResponseLegacyQuarantineFingerprint(
                                acceptedEventId = eventId,
                                eventCanonicalFingerprint = eventFingerprint,
                                decisionFingerprint = decisionFingerprint,
                                quarantinedAtEpochMillis = quarantinedAt,
                            ),
                        )
                        sink.step()
                        legacyAcceptedCount += 1L
                    }
                }
            }

            if (legacyAcceptedCount > 0L) {
                connection.execSQL(
                    """
                    UPDATE mastery_projection_generation
                    SET state = 'RETIRED', lease_owner_id = NULL, lease_expires_at_epoch_millis = NULL
                    WHERE state IN ('ACTIVE', 'BUILDING')
                    """.trimIndent(),
                )
                // These are replaceable projections, not learning facts. Clearing them prevents
                // display readers from observing the retired generation while the bounded replay
                // constructs its quarantine-aware replacement.
                connection.execSQL("DELETE FROM mastery_knowledge_projection")
                connection.execSQL("DELETE FROM mastery_subject_digest")
                connection.execSQL("DELETE FROM mastery_presentation_node_budget")
                connection.execSQL("DELETE FROM mastery_problem_family_node_budget")
            }
            check(
                connection.countRowsForOpenResponseLegacyQuarantine() == legacyAcceptedCount,
            ) { "DB21→DB22 did not quarantine every legacy open-response accepted event" }
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

private fun SQLiteConnection.countRowsForOpenResponseLegacyQuarantine(): Long =
    prepare(
        "SELECT COUNT(*) FROM $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE",
    ).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private val CREATE_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE =
    """
    CREATE TABLE $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE (
        accepted_event_id TEXT NOT NULL,
        event_canonical_fingerprint TEXT NOT NULL,
        decision_fingerprint TEXT NOT NULL,
        quarantine_reason TEXT NOT NULL,
        policy_version TEXT NOT NULL,
        quarantined_at_epoch_millis INTEGER NOT NULL,
        quarantine_fingerprint TEXT NOT NULL,
        PRIMARY KEY(accepted_event_id),
        FOREIGN KEY(decision_fingerprint)
            REFERENCES $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE(decision_fingerprint)
            ON UPDATE NO ACTION ON DELETE RESTRICT,
        FOREIGN KEY(accepted_event_id, event_canonical_fingerprint)
            REFERENCES mastery_learning_event(event_id, canonical_fingerprint)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent()
