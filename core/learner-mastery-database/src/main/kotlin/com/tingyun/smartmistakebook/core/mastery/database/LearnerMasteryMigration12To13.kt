package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds a locally assigned monotonic revision ordinal.
 *
 * Existing rows receive an ordinal only when it is provable from the explicit parent chain.
 * Broken, cyclic, or detached legacy chains remain NULL and are rejected by the runtime rather
 * than being ordered by wall-clock time or fingerprint.
 */
internal val LEARNER_MASTERY_MIGRATION_12_13 =
    object : Migration(12, 13) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                ALTER TABLE `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
                ADD COLUMN `revision_ordinal` INTEGER
                """.trimIndent(),
            )
            connection.execSQL(
                "DROP TRIGGER IF EXISTS " +
                    "immutable_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_update",
            )
            connection.execSQL(
                """
                WITH RECURSIVE `linearized`(
                    `receipt_fingerprint`,
                    `logical_attempt_fingerprint`,
                    `candidate_idempotency_key`,
                    `revision_ordinal`
                ) AS (
                    SELECT
                        `receipt_fingerprint`,
                        `logical_attempt_fingerprint`,
                        `candidate_idempotency_key`,
                        0
                    FROM `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
                    WHERE `revision_of_candidate_idempotency_key` IS NULL

                    UNION ALL

                    SELECT
                        child.`receipt_fingerprint`,
                        child.`logical_attempt_fingerprint`,
                        child.`candidate_idempotency_key`,
                        parent.`revision_ordinal` + 1
                    FROM `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE` child
                    JOIN `linearized` parent
                      ON child.`logical_attempt_fingerprint` =
                            parent.`logical_attempt_fingerprint`
                     AND child.`revision_of_candidate_idempotency_key` =
                            parent.`candidate_idempotency_key`
                )
                UPDATE `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
                SET `revision_ordinal` = (
                    SELECT `linearized`.`revision_ordinal`
                    FROM `linearized`
                    WHERE `linearized`.`receipt_fingerprint` =
                        `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`.
                            `receipt_fingerprint`
                )
                WHERE EXISTS (
                    SELECT 1
                    FROM `linearized`
                    WHERE `linearized`.`receipt_fingerprint` =
                        `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`.
                            `receipt_fingerprint`
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_logical_attempt_fingerprint_revision_ordinal`
                ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
                    (`logical_attempt_fingerprint`, `revision_ordinal`)
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }
