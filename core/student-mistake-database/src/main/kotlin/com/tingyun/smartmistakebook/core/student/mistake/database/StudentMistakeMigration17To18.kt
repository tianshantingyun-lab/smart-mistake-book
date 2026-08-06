package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof

/**
 * Rotates the first authenticity experiment into a persisted-proof relay epoch.
 *
 * Every pre-v18 student-to-mastery row is unsigned by definition. It is retained as immutable
 * history but retired from delivery. The open callback provisions a fresh random key identity,
 * alias and relay epoch; no old row is retrospectively blessed.
 */
internal val STUDENT_MISTAKE_MIGRATION_17_18 =
    object : Migration(17, 18) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_NAMES_V17_FOR_REPLACEMENT.forEach { name ->
                connection.execSQL("DROP TRIGGER IF EXISTS `$name`")
            }
            connection.execSQL("DROP TABLE `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE`")
            connection.execSQL(STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE_SQL_V18)
            STUDENT_OUTBOX_PROOF_COLUMNS_V18.forEach { definition ->
                connection.execSQL(
                    "ALTER TABLE student_store_outbox ADD COLUMN $definition",
                )
            }
            connection.execSQL(
                """
                UPDATE student_store_outbox
                SET delivery_state = 'RETIRED_UNSAFE_LEGACY',
                    delivered_at_epoch_millis =
                        CASE
                            WHEN delivered_at_epoch_millis IS NULL
                                THEN occurred_at_epoch_millis
                            ELSE delivered_at_epoch_millis
                        END
                WHERE source_store = 'STUDENT_MISTAKES'
                  AND destination_store = 'LEARNER_MASTERY'
                  AND delivery_state = 'PENDING'
                """.trimIndent(),
            )
            createStudentOutboxAuthenticityV18ImmutabilityTriggers(connection)
            check(connection.countPendingPreV18StudentRelayRows() == 0L) {
                "Student v18 migration left a pre-v18 relay row deliverable"
            }
        }
    }

internal fun createStudentOutboxAuthenticityV18ImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_SQL_V18.forEach { (name, sql) ->
        connection.execSQL("DROP TRIGGER IF EXISTS `$name`")
        connection.execSQL(sql)
    }
}

internal fun verifyStudentOutboxAuthenticityV18ImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    val installed =
        connection.prepare(
            "SELECT name, sql FROM sqlite_master WHERE type = 'trigger'",
        ).use { statement ->
            buildMap {
                while (statement.step()) put(statement.getText(0), statement.getText(1))
            }
        }
    val invalid =
        STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_SQL_V18.filter { (name, expectedSql) ->
            installed[name]?.canonicalV18AuthenticityTriggerSql() !=
                expectedSql.canonicalV18AuthenticityTriggerSql()
        }.keys
    check(invalid.isEmpty()) {
        "Student v18 outbox-authenticity triggers are invalid: ${invalid.sorted()}"
    }
}

private fun String.canonicalV18AuthenticityTriggerSql(): String =
    replace('`', ' ')
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

private fun SQLiteConnection.countPendingPreV18StudentRelayRows(): Long =
    prepare(
        """
        SELECT COUNT(*)
        FROM student_store_outbox
        WHERE source_store = 'STUDENT_MISTAKES'
          AND destination_store = 'LEARNER_MASTERY'
          AND delivery_state = 'PENDING'
          AND authenticity_tag_hex IS NULL
        """.trimIndent(),
    ).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private val STUDENT_OUTBOX_PROOF_COLUMNS_V18 =
    listOf(
        "`authenticity_proof_protocol_version` INTEGER",
        "`authenticity_algorithm_version` TEXT",
        "`authenticity_issuer_key_id` TEXT",
        "`authenticity_learner_id` TEXT",
        "`authenticity_envelope_fingerprint` TEXT",
        "`authenticity_tag_hex` TEXT",
        "`authenticity_relay_epoch` TEXT",
    )

private val STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE_SQL_V18 =
    """
    CREATE TABLE `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE` (
        `singleton_id` INTEGER NOT NULL,
        `key_id` TEXT NOT NULL,
        `key_version` INTEGER NOT NULL,
        `key_alias` TEXT NOT NULL,
        `algorithm_version` TEXT NOT NULL,
        `source_store_generation` TEXT NOT NULL,
        `relay_epoch` TEXT NOT NULL,
        `state` TEXT NOT NULL,
        `activated_at_epoch_millis` INTEGER NOT NULL,
        `canonical_fingerprint` TEXT NOT NULL,
        `keyed_sentinel_tag` TEXT NOT NULL,
        PRIMARY KEY(`singleton_id`)
    )
    """.trimIndent()

private val STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_SQL_V18 =
    linkedMapOf(
        "validate_student_store_generation_insert" to
            """
            CREATE TRIGGER validate_student_store_generation_insert
            BEFORE INSERT ON student_store_metadata
            WHEN NEW.metadata_key = '$STORE_GENERATION_METADATA_KEY'
            BEGIN
                SELECT CASE WHEN NOT (
                    length(trim(NEW.metadata_value)) BETWEEN 1 AND 256
                    AND NEW.metadata_value = trim(NEW.metadata_value)
                    AND NEW.created_at_epoch_millis >= 0
                    AND NEW.updated_at_epoch_millis = NEW.created_at_epoch_millis
                ) THEN RAISE(ABORT, 'invalid student store generation') END;
            END
            """.trimIndent(),
        "immutable_student_store_generation_update" to
            """
            CREATE TRIGGER immutable_student_store_generation_update
            BEFORE UPDATE ON student_store_metadata
            WHEN OLD.metadata_key = '$STORE_GENERATION_METADATA_KEY'
                 OR NEW.metadata_key = '$STORE_GENERATION_METADATA_KEY'
            BEGIN
                SELECT RAISE(ABORT, 'immutable student store generation');
            END
            """.trimIndent(),
        "immutable_student_store_generation_delete" to
            """
            CREATE TRIGGER immutable_student_store_generation_delete
            BEFORE DELETE ON student_store_metadata
            WHEN OLD.metadata_key = '$STORE_GENERATION_METADATA_KEY'
            BEGIN
                SELECT RAISE(ABORT, 'immutable student store generation');
            END
            """.trimIndent(),
        "validate_student_outbox_authenticity_key_state_insert" to
            """
            CREATE TRIGGER validate_student_outbox_authenticity_key_state_insert
            BEFORE INSERT ON `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE`
            BEGIN
                SELECT CASE WHEN NOT (
                    NEW.singleton_id = 1
                    AND length(trim(NEW.key_id)) BETWEEN 1 AND 256
                    AND NEW.key_id = trim(NEW.key_id)
                    AND NEW.key_version = ${StudentOutboxAuthenticator.KEY_VERSION}
                    AND length(trim(NEW.key_alias)) BETWEEN 1 AND 256
                    AND NEW.key_alias = trim(NEW.key_alias)
                    AND NEW.algorithm_version =
                        '${StudentOutboxAuthenticityProof.ALGORITHM_VERSION}'
                    AND length(trim(NEW.source_store_generation)) BETWEEN 1 AND 256
                    AND NEW.source_store_generation = trim(NEW.source_store_generation)
                    AND length(trim(NEW.relay_epoch)) BETWEEN 1 AND 256
                    AND NEW.relay_epoch = trim(NEW.relay_epoch)
                    AND NEW.state = 'ACTIVE'
                    AND NEW.activated_at_epoch_millis >= 0
                    AND length(NEW.canonical_fingerprint) = 64
                    AND NEW.canonical_fingerprint NOT GLOB '*[^0-9a-f]*'
                    AND length(NEW.keyed_sentinel_tag) = 64
                    AND NEW.keyed_sentinel_tag NOT GLOB '*[^0-9a-f]*'
                    AND EXISTS (
                        SELECT 1 FROM student_store_metadata
                        WHERE metadata_key = '$STORE_GENERATION_METADATA_KEY'
                          AND metadata_value = NEW.source_store_generation
                    )
                ) THEN RAISE(ABORT, 'invalid student-outbox authenticity key state') END;
            END
            """.trimIndent(),
        "immutable_student_outbox_authenticity_key_state_update" to
            """
            CREATE TRIGGER immutable_student_outbox_authenticity_key_state_update
            BEFORE UPDATE ON `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE`
            BEGIN
                SELECT RAISE(ABORT, 'immutable student-outbox authenticity key state');
            END
            """.trimIndent(),
        "immutable_student_outbox_authenticity_key_state_delete" to
            """
            CREATE TRIGGER immutable_student_outbox_authenticity_key_state_delete
            BEFORE DELETE ON `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE`
            BEGIN
                SELECT RAISE(ABORT, 'immutable student-outbox authenticity key state');
            END
            """.trimIndent(),
        "validate_student_authenticated_outbox_insert" to
            """
            CREATE TRIGGER validate_student_authenticated_outbox_insert
            BEFORE INSERT ON student_store_outbox
            WHEN NEW.source_store = 'STUDENT_MISTAKES'
                 AND NEW.destination_store = 'LEARNER_MASTERY'
                 AND NEW.delivery_state = 'PENDING'
            BEGIN
                SELECT CASE WHEN NOT (
                    NEW.authenticity_proof_protocol_version =
                        ${StudentOutboxAuthenticityProof.PROTOCOL_VERSION}
                    AND NEW.authenticity_algorithm_version =
                        '${StudentOutboxAuthenticityProof.ALGORITHM_VERSION}'
                    AND NEW.authenticity_learner_id = NEW.learner_id
                    AND NEW.authenticity_envelope_fingerprint =
                        NEW.envelope_canonical_fingerprint
                    AND length(NEW.authenticity_tag_hex) = 64
                    AND NEW.authenticity_tag_hex NOT GLOB '*[^0-9a-f]*'
                    AND EXISTS (
                        SELECT 1
                        FROM `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE` key_state
                        WHERE key_state.singleton_id = 1
                          AND key_state.state = 'ACTIVE'
                          AND key_state.key_id = NEW.authenticity_issuer_key_id
                          AND key_state.source_store_generation =
                              NEW.source_store_generation
                          AND key_state.relay_epoch = NEW.authenticity_relay_epoch
                    )
                ) THEN RAISE(ABORT, 'student outbox requires persisted owner proof') END;
            END
            """.trimIndent(),
        "immutable_student_outbox_proof_update" to
            """
            CREATE TRIGGER immutable_student_outbox_proof_update
            BEFORE UPDATE ON student_store_outbox
            WHEN OLD.authenticity_tag_hex IS NOT NULL
                 AND NOT (
                     NEW.authenticity_proof_protocol_version IS
                         OLD.authenticity_proof_protocol_version
                     AND NEW.authenticity_algorithm_version IS
                         OLD.authenticity_algorithm_version
                     AND NEW.authenticity_issuer_key_id IS OLD.authenticity_issuer_key_id
                     AND NEW.authenticity_learner_id IS OLD.authenticity_learner_id
                     AND NEW.authenticity_envelope_fingerprint IS
                         OLD.authenticity_envelope_fingerprint
                     AND NEW.authenticity_tag_hex IS OLD.authenticity_tag_hex
                     AND NEW.authenticity_relay_epoch IS OLD.authenticity_relay_epoch
                 )
            BEGIN
                SELECT RAISE(ABORT, 'immutable student outbox authenticity proof');
            END
            """.trimIndent(),
        "reject_inserted_student_authenticity_rejection" to
            """
            CREATE TRIGGER reject_inserted_student_authenticity_rejection
            BEFORE INSERT ON student_store_outbox
            WHEN NEW.delivery_state = 'AUTHENTICITY_REJECTED'
            BEGIN
                SELECT RAISE(ABORT, 'authenticity rejection requires a pending owner row');
            END
            """.trimIndent(),
        "validate_student_authenticity_rejection" to
            """
            CREATE TRIGGER validate_student_authenticity_rejection
            BEFORE UPDATE ON student_store_outbox
            WHEN NEW.delivery_state = 'AUTHENTICITY_REJECTED'
                 AND OLD.delivery_state != 'AUTHENTICITY_REJECTED'
            BEGIN
                SELECT CASE WHEN NOT (
                    OLD.delivery_state = 'PENDING'
                    AND OLD.source_store = 'STUDENT_MISTAKES'
                    AND OLD.destination_store = 'LEARNER_MASTERY'
                    AND OLD.delivered_at_epoch_millis IS NULL
                    AND NEW.delivered_at_epoch_millis IS NOT NULL
                    AND NEW.delivered_at_epoch_millis >= OLD.occurred_at_epoch_millis
                    AND OLD.authenticity_tag_hex IS NOT NULL
                    AND NEW.event_id IS OLD.event_id
                    AND NEW.source_store IS OLD.source_store
                    AND NEW.destination_store IS OLD.destination_store
                    AND NEW.learner_id IS OLD.learner_id
                    AND NEW.aggregate_id IS OLD.aggregate_id
                    AND NEW.aggregate_version IS OLD.aggregate_version
                    AND NEW.payload_type IS OLD.payload_type
                    AND NEW.payload_version IS OLD.payload_version
                    AND NEW.payload_canonical_fingerprint IS
                        OLD.payload_canonical_fingerprint
                    AND NEW.payload_wire IS OLD.payload_wire
                    AND NEW.envelope_canonical_fingerprint IS
                        OLD.envelope_canonical_fingerprint
                    AND NEW.occurred_at_epoch_millis IS OLD.occurred_at_epoch_millis
                    AND NEW.idempotency_key IS OLD.idempotency_key
                    AND NEW.source_store_generation IS OLD.source_store_generation
                    AND NEW.authenticity_proof_protocol_version IS
                        OLD.authenticity_proof_protocol_version
                    AND NEW.authenticity_algorithm_version IS
                        OLD.authenticity_algorithm_version
                    AND NEW.authenticity_issuer_key_id IS OLD.authenticity_issuer_key_id
                    AND NEW.authenticity_learner_id IS OLD.authenticity_learner_id
                    AND NEW.authenticity_envelope_fingerprint IS
                        OLD.authenticity_envelope_fingerprint
                    AND NEW.authenticity_tag_hex IS OLD.authenticity_tag_hex
                    AND NEW.authenticity_relay_epoch IS OLD.authenticity_relay_epoch
                    AND NEW.delivery_attempt_count IS OLD.delivery_attempt_count
                    AND NEW.available_at_epoch_millis IS OLD.available_at_epoch_millis
                ) THEN RAISE(ABORT, 'invalid student authenticity rejection') END;
            END
            """.trimIndent(),
        "immutable_student_authenticity_rejection_update" to
            """
            CREATE TRIGGER immutable_student_authenticity_rejection_update
            BEFORE UPDATE ON student_store_outbox
            WHEN OLD.delivery_state = 'AUTHENTICITY_REJECTED'
            BEGIN
                SELECT RAISE(ABORT, 'immutable student authenticity rejection');
            END
            """.trimIndent(),
        "immutable_student_authenticity_rejection_delete" to
            """
            CREATE TRIGGER immutable_student_authenticity_rejection_delete
            BEFORE DELETE ON student_store_outbox
            WHEN OLD.delivery_state = 'AUTHENTICITY_REJECTED'
            BEGIN
                SELECT RAISE(ABORT, 'immutable student authenticity rejection');
            END
            """.trimIndent(),
        "reject_inserted_student_legacy_review_retirement" to
            """
            CREATE TRIGGER reject_inserted_student_legacy_review_retirement
            BEFORE INSERT ON student_store_outbox
            WHEN NEW.delivery_state = 'RETIRED_UNSAFE_LEGACY'
            BEGIN
                SELECT RAISE(ABORT, 'legacy relay retirement requires a pending owner row');
            END
            """.trimIndent(),
        "validate_student_legacy_review_retirement" to
            """
            CREATE TRIGGER validate_student_legacy_review_retirement
            BEFORE UPDATE ON student_store_outbox
            WHEN NEW.delivery_state = 'RETIRED_UNSAFE_LEGACY'
                 AND OLD.delivery_state != 'RETIRED_UNSAFE_LEGACY'
            BEGIN
                SELECT CASE WHEN NOT (
                    OLD.delivery_state = 'PENDING'
                    AND OLD.source_store = 'STUDENT_MISTAKES'
                    AND OLD.destination_store = 'LEARNER_MASTERY'
                    AND OLD.delivered_at_epoch_millis IS NULL
                    AND NEW.delivered_at_epoch_millis IS NOT NULL
                    AND NEW.delivered_at_epoch_millis >= OLD.occurred_at_epoch_millis
                    AND (
                        OLD.authenticity_proof_protocol_version IS NULL
                        OR OLD.authenticity_algorithm_version IS NULL
                        OR OLD.authenticity_issuer_key_id IS NULL
                        OR OLD.authenticity_learner_id IS NULL
                        OR OLD.authenticity_envelope_fingerprint IS NULL
                        OR OLD.authenticity_tag_hex IS NULL
                        OR OLD.authenticity_relay_epoch IS NULL
                    )
                ) THEN RAISE(ABORT, 'invalid legacy relay retirement') END;
            END
            """.trimIndent(),
        "immutable_student_legacy_review_retirement_update" to
            """
            CREATE TRIGGER immutable_student_legacy_review_retirement_update
            BEFORE UPDATE ON student_store_outbox
            WHEN OLD.delivery_state = 'RETIRED_UNSAFE_LEGACY'
            BEGIN
                SELECT RAISE(ABORT, 'immutable legacy relay retirement');
            END
            """.trimIndent(),
        "immutable_student_legacy_review_retirement_delete" to
            """
            CREATE TRIGGER immutable_student_legacy_review_retirement_delete
            BEFORE DELETE ON student_store_outbox
            WHEN OLD.delivery_state = 'RETIRED_UNSAFE_LEGACY'
            BEGIN
                SELECT RAISE(ABORT, 'immutable legacy relay retirement');
            END
            """.trimIndent(),
    )

private val STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_NAMES_V17_FOR_REPLACEMENT =
    setOf(
        "validate_student_store_generation_insert",
        "immutable_student_store_generation_update",
        "immutable_student_store_generation_delete",
        "reject_inserted_student_legacy_review_retirement",
        "validate_student_legacy_review_retirement",
        "immutable_student_legacy_review_retirement_update",
        "immutable_student_legacy_review_retirement_delete",
        "validate_student_outbox_authenticity_key_state_insert",
        "immutable_student_outbox_authenticity_key_state_update",
        "immutable_student_outbox_authenticity_key_state_delete",
    )
