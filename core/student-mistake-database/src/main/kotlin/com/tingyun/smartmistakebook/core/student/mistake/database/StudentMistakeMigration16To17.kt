package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof

/**
 * Establishes the student-outbox authenticity root without blessing legacy review observations.
 *
 * Keystore work cannot run inside a Room SQL migration. The key-state table is intentionally left
 * empty; the production owner must complete [StudentOutboxAuthenticityKeyBootstrap] before it
 * publishes any review or relay capability. Every pending unsigned V1/V2 review is preserved as
 * an immutable tombstone and can never be relayed as learning evidence.
 */
internal val STUDENT_MISTAKE_MIGRATION_16_17 =
    object : Migration(16, 17) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE_SQL)
            connection.execSQL(STUDENT_RETIRE_UNSIGNED_REVIEW_OUTBOX_SQL)
            createStudentOutboxAuthenticityImmutabilityTriggers(connection)

            check(connection.countPendingUnsignedReviewObservations() == 0L) {
                "Student v17 migration left a pending unsigned review observation"
            }
            check(connection.countStudentOutboxAuthenticityKeyStates() == 0L) {
                "Student v17 migration must not manufacture a Keystore authority"
            }
            connection.prepare("PRAGMA foreign_key_check").use { statement ->
                check(!statement.step()) {
                    "Student v17 authenticity migration left a broken foreign key"
                }
            }
        }
    }

internal fun createStudentOutboxAuthenticityImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_NAMES_V17.forEach { triggerName ->
        connection.execSQL("DROP TRIGGER IF EXISTS `$triggerName`")
    }
    STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_SQL_V17.values.forEach(connection::execSQL)
}

internal fun verifyStudentOutboxAuthenticityImmutabilityTriggers(
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
        STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_SQL_V17.filter { (name, expectedSql) ->
            installed[name]?.canonicalStudentOutboxAuthenticityTriggerSql() !=
                expectedSql.canonicalStudentOutboxAuthenticityTriggerSql()
        }.keys
    check(invalid.isEmpty()) {
        "Student-outbox authenticity trigger definitions are invalid: ${invalid.sorted()}"
    }
}

private fun String.canonicalStudentOutboxAuthenticityTriggerSql(): String =
    replace('`', ' ')
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

private fun SQLiteConnection.countPendingUnsignedReviewObservations(): Long =
    prepare(
        """
        SELECT COUNT(*)
        FROM student_store_outbox
        WHERE delivery_state = 'PENDING'
          AND source_store = 'STUDENT_MISTAKES'
          AND destination_store = 'LEARNER_MASTERY'
          AND payload_type = 'review_observation_captured'
          AND payload_version IN (1, 2)
        """.trimIndent(),
    ).use { statement ->
        check(statement.step()) { "Expected one unsigned-review count" }
        statement.getLong(0)
    }

private fun SQLiteConnection.countStudentOutboxAuthenticityKeyStates(): Long =
    prepare(
        "SELECT COUNT(*) FROM `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE`",
    ).use { statement ->
        check(statement.step()) { "Expected one outbox-authenticity key-state count" }
        statement.getLong(0)
    }

private val STUDENT_RETIRE_UNSIGNED_REVIEW_OUTBOX_SQL =
    """
    UPDATE student_store_outbox
    SET delivery_state = 'RETIRED_UNSAFE_LEGACY',
        delivered_at_epoch_millis = occurred_at_epoch_millis
    WHERE delivery_state = 'PENDING'
      AND source_store = 'STUDENT_MISTAKES'
      AND destination_store = 'LEARNER_MASTERY'
      AND payload_type = 'review_observation_captured'
      AND payload_version IN (1, 2)
      AND delivered_at_epoch_millis IS NULL
    """.trimIndent()

private val STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE_SQL =
    """
    CREATE TABLE `$STUDENT_OUTBOX_AUTHENTICITY_KEY_STATE_TABLE` (
        `singleton_id` INTEGER NOT NULL,
        `key_id` TEXT NOT NULL,
        `key_version` INTEGER NOT NULL,
        `algorithm_version` TEXT NOT NULL,
        `source_store_generation` TEXT NOT NULL,
        `state` TEXT NOT NULL,
        `activated_at_epoch_millis` INTEGER NOT NULL,
        `canonical_fingerprint` TEXT NOT NULL,
        PRIMARY KEY(`singleton_id`)
    )
    """.trimIndent()

private val STUDENT_LEGACY_REVIEW_RETIREMENT_TRIGGER_NAMES_V17 =
    setOf(
        "reject_inserted_student_legacy_review_retirement",
        "validate_student_legacy_review_retirement",
        "immutable_student_legacy_review_retirement_update",
        "immutable_student_legacy_review_retirement_delete",
    )

private val STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_SQL_V17 =
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
        "reject_inserted_student_legacy_review_retirement" to
        """
        CREATE TRIGGER reject_inserted_student_legacy_review_retirement
        BEFORE INSERT ON student_store_outbox
        WHEN NEW.delivery_state = 'RETIRED_UNSAFE_LEGACY'
        BEGIN
            SELECT RAISE(ABORT, 'legacy review retirement requires a pending owner row');
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
        CREATE TRIGGER immutable_student_legacy_review_retirement_update
        BEFORE UPDATE ON student_store_outbox
        WHEN OLD.delivery_state = 'RETIRED_UNSAFE_LEGACY'
        BEGIN
            SELECT RAISE(ABORT, 'immutable legacy review retirement');
        END
        """.trimIndent(),
        "immutable_student_legacy_review_retirement_delete" to
        """
        CREATE TRIGGER immutable_student_legacy_review_retirement_delete
        BEFORE DELETE ON student_store_outbox
        WHEN OLD.delivery_state = 'RETIRED_UNSAFE_LEGACY'
        BEGIN
            SELECT RAISE(ABORT, 'immutable legacy review retirement');
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
                AND NEW.algorithm_version =
                    '${StudentOutboxAuthenticityProof.ALGORITHM_VERSION}'
                AND length(trim(NEW.source_store_generation)) BETWEEN 1 AND 256
                AND NEW.source_store_generation = trim(NEW.source_store_generation)
                AND EXISTS (
                    SELECT 1
                    FROM student_store_metadata
                    WHERE metadata_key = '$STORE_GENERATION_METADATA_KEY'
                      AND metadata_value = NEW.source_store_generation
                )
                AND NEW.state = 'ACTIVE'
                AND NEW.activated_at_epoch_millis >= 0
                AND length(NEW.canonical_fingerprint) = 64
                AND NEW.canonical_fingerprint NOT GLOB '*[^0-9a-f]*'
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
    )

private val STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_NAMES_V17 =
    STUDENT_OUTBOX_AUTHENTICITY_TRIGGER_SQL_V17.keys
