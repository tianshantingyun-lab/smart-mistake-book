package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds authoritative review sessions and append-only transition receipts.
 *
 * Existing v5 queue states are preserved exactly. In particular, the migration never guesses
 * whether a legacy PRESENTED item is still active and never synthesizes a session or observation.
 */
internal val STUDENT_MISTAKE_MIGRATION_5_6 =
    object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            migrateLegacyV5SearchDocumentIfRequired(connection)
            val legacyReviewAuthority = prepareLegacyV5ReviewAuthorityMigration(connection)
            STUDENT_MISTAKE_V6_SCHEMA_STATEMENTS.forEach(connection::execSQL)
            legacyReviewAuthority?.let { legacyTables ->
                copyLegacyV5ReviewAuthority(connection, legacyTables)
            }
            createStudentReviewReceiptImmutabilityTriggers(
                connection = connection,
                tableNames = STUDENT_REVIEW_V6_IMMUTABLE_RECEIPT_TABLES,
            )
        }
    }

/**
 * v5 already stored review sessions and receipts, but its foreign keys only checked individual ids.
 * v6 makes learner and plan ownership part of every relationship and adds plan_id to both receipt
 * tables. SQLite cannot strengthen those constraints in place, so the authoritative tables are
 * rebuilt while preserving every coherent row.
 *
 * A legacy ownership mismatch is rejected instead of being guessed or silently discarded.
 */
private fun prepareLegacyV5ReviewAuthorityMigration(
    connection: SQLiteConnection,
): LegacyV5ReviewAuthorityTables? {
    val legacyTables =
        LegacyV5ReviewAuthorityTables(
            hasSession = connection.tableExists(STUDENT_REVIEW_SESSION_TABLE),
            hasTransitionReceipt = connection.tableExists(STUDENT_REVIEW_TRANSITION_TABLE),
            hasRevealReceipt = connection.tableExists(STUDENT_REVIEW_REVEAL_TABLE),
        )
    if (!legacyTables.hasAnyTable) return null

    check(legacyTables.hasSession) {
        "Cannot migrate v5 review receipts without their review-session authority"
    }
    validateLegacyV5ReviewOwnership(connection, legacyTables)

    STUDENT_MISTAKE_REVIEW_IMMUTABILITY_TRIGGER_NAMES.forEach { triggerName ->
        connection.execSQL("DROP TRIGGER IF EXISTS `$triggerName`")
    }
    STUDENT_MISTAKE_REVIEW_AUTHORITY_INDEX_NAMES.forEach { indexName ->
        connection.execSQL("DROP INDEX IF EXISTS `$indexName`")
    }

    if (legacyTables.hasTransitionReceipt) {
        connection.execSQL(
            """
            ALTER TABLE `$STUDENT_REVIEW_TRANSITION_TABLE`
            RENAME TO `$LEGACY_V5_REVIEW_TRANSITION_TABLE`
            """.trimIndent(),
        )
    }
    if (legacyTables.hasRevealReceipt) {
        connection.execSQL(
            """
            ALTER TABLE `$STUDENT_REVIEW_REVEAL_TABLE`
            RENAME TO `$LEGACY_V5_REVIEW_REVEAL_TABLE`
            """.trimIndent(),
        )
    }
    connection.execSQL(
        """
        ALTER TABLE `$STUDENT_REVIEW_SESSION_TABLE`
        RENAME TO `$LEGACY_V5_REVIEW_SESSION_TABLE`
        """.trimIndent(),
    )
    return legacyTables
}

private fun validateLegacyV5ReviewOwnership(
    connection: SQLiteConnection,
    legacyTables: LegacyV5ReviewAuthorityTables,
) {
    val invalidSessions =
        connection.readCount(
            """
            SELECT COUNT(*)
            FROM `$STUDENT_REVIEW_SESSION_TABLE` AS session
            LEFT JOIN `student_review_plan` AS plan
              ON plan.`plan_id` = session.`plan_id`
             AND plan.`learner_id` = session.`learner_id`
            LEFT JOIN `student_review_queue_item` AS queue_item
              ON queue_item.`queue_item_id` = session.`current_queue_item_id`
             AND queue_item.`plan_id` = session.`plan_id`
             AND queue_item.`learner_id` = session.`learner_id`
            WHERE plan.`plan_id` IS NULL
               OR (
                 session.`current_queue_item_id` IS NOT NULL
                 AND queue_item.`queue_item_id` IS NULL
               )
            """.trimIndent(),
        )
    check(invalidSessions == 0L) {
        "Cannot migrate v5 review sessions with inconsistent learner or plan ownership"
    }

    if (legacyTables.hasTransitionReceipt) {
        val invalidTransitions =
            connection.readCount(
                """
                SELECT COUNT(*)
                FROM `$STUDENT_REVIEW_TRANSITION_TABLE` AS receipt
                LEFT JOIN `$STUDENT_REVIEW_SESSION_TABLE` AS session
                  ON session.`session_id` = receipt.`session_id`
                 AND session.`learner_id` = receipt.`learner_id`
                LEFT JOIN `student_review_queue_item` AS queue_item
                  ON queue_item.`queue_item_id` = receipt.`queue_item_id`
                 AND queue_item.`learner_id` = receipt.`learner_id`
                 AND queue_item.`plan_id` = session.`plan_id`
                WHERE session.`session_id` IS NULL
                   OR queue_item.`queue_item_id` IS NULL
                """.trimIndent(),
            )
        check(invalidTransitions == 0L) {
            "Cannot migrate v5 review transitions with inconsistent learner or plan ownership"
        }
    }

    if (legacyTables.hasRevealReceipt) {
        val invalidReveals =
            connection.readCount(
                """
                SELECT COUNT(*)
                FROM `$STUDENT_REVIEW_REVEAL_TABLE` AS receipt
                LEFT JOIN `$STUDENT_REVIEW_SESSION_TABLE` AS session
                  ON session.`session_id` = receipt.`session_id`
                 AND session.`learner_id` = receipt.`learner_id`
                LEFT JOIN `student_review_queue_item` AS queue_item
                  ON queue_item.`queue_item_id` = receipt.`queue_item_id`
                 AND queue_item.`learner_id` = receipt.`learner_id`
                 AND queue_item.`plan_id` = session.`plan_id`
                WHERE session.`session_id` IS NULL
                   OR queue_item.`queue_item_id` IS NULL
                """.trimIndent(),
            )
        check(invalidReveals == 0L) {
            "Cannot migrate v5 answer reveals with inconsistent learner or plan ownership"
        }
    }
}

private fun copyLegacyV5ReviewAuthority(
    connection: SQLiteConnection,
    legacyTables: LegacyV5ReviewAuthorityTables,
) {
    connection.execSQL(
        """
        INSERT INTO `$STUDENT_REVIEW_SESSION_TABLE` (
          `session_id`, `session_canonical_fingerprint`, `learner_id`, `plan_id`,
          `active_learner_id`, `state`, `session_version`, `current_queue_item_id`,
          `current_presentation_id`, `started_at_epoch_millis`, `updated_at_epoch_millis`,
          `completed_at_epoch_millis`
        )
        SELECT
          `session_id`, `session_canonical_fingerprint`, `learner_id`, `plan_id`,
          `active_learner_id`, `state`, `session_version`, `current_queue_item_id`,
          `current_presentation_id`, `started_at_epoch_millis`, `updated_at_epoch_millis`,
          `completed_at_epoch_millis`
        FROM `$LEGACY_V5_REVIEW_SESSION_TABLE`
        """.trimIndent(),
    )

    if (legacyTables.hasTransitionReceipt) {
        connection.execSQL(
            """
            INSERT INTO `$STUDENT_REVIEW_TRANSITION_TABLE` (
              `transition_id`, `transition_canonical_fingerprint`, `learner_id`, `plan_id`,
              `session_id`, `queue_item_id`, `action_kind`, `expected_session_version`,
              `resulting_session_version`, `presentation_id`, `observation_id`, `submission_id`,
              `response_form`, `response_canonical_fingerprint`, `verification_outcome`,
              `attempt_ordinal`, `hint_count`, `answer_was_revealed`,
              `verification_policy_version`, `elapsed_duration_millis`,
              `next_available_at_epoch_millis`, `next_due_at_epoch_millis`,
              `scheduling_policy_version`, `outbox_event_id`, `occurred_at_epoch_millis`
            )
            SELECT
              receipt.`transition_id`, receipt.`transition_canonical_fingerprint`,
              receipt.`learner_id`, session.`plan_id`, receipt.`session_id`,
              receipt.`queue_item_id`, receipt.`action_kind`,
              receipt.`expected_session_version`, receipt.`resulting_session_version`,
              receipt.`presentation_id`, receipt.`observation_id`, receipt.`submission_id`,
              receipt.`response_form`, receipt.`response_canonical_fingerprint`,
              receipt.`verification_outcome`, receipt.`attempt_ordinal`,
              receipt.`hint_count`, receipt.`answer_was_revealed`,
              receipt.`verification_policy_version`, receipt.`elapsed_duration_millis`,
              receipt.`next_available_at_epoch_millis`, receipt.`next_due_at_epoch_millis`,
              receipt.`scheduling_policy_version`, receipt.`outbox_event_id`,
              receipt.`occurred_at_epoch_millis`
            FROM `$LEGACY_V5_REVIEW_TRANSITION_TABLE` AS receipt
            INNER JOIN `$STUDENT_REVIEW_SESSION_TABLE` AS session
              ON session.`session_id` = receipt.`session_id`
             AND session.`learner_id` = receipt.`learner_id`
            """.trimIndent(),
        )
        check(
            connection.readCount("SELECT COUNT(*) FROM `$STUDENT_REVIEW_TRANSITION_TABLE`") ==
                connection.readCount("SELECT COUNT(*) FROM `$LEGACY_V5_REVIEW_TRANSITION_TABLE`"),
        ) {
            "v5 review-transition migration did not preserve every receipt"
        }
    }

    if (legacyTables.hasRevealReceipt) {
        connection.execSQL(
            """
            INSERT INTO `$STUDENT_REVIEW_REVEAL_TABLE` (
              `reveal_id`, `reveal_canonical_fingerprint`, `learner_id`, `plan_id`,
              `session_id`, `queue_item_id`, `presentation_id`, `revealed_at_epoch_millis`
            )
            SELECT
              receipt.`reveal_id`, receipt.`reveal_canonical_fingerprint`,
              receipt.`learner_id`, session.`plan_id`, receipt.`session_id`,
              receipt.`queue_item_id`, receipt.`presentation_id`,
              receipt.`revealed_at_epoch_millis`
            FROM `$LEGACY_V5_REVIEW_REVEAL_TABLE` AS receipt
            INNER JOIN `$STUDENT_REVIEW_SESSION_TABLE` AS session
              ON session.`session_id` = receipt.`session_id`
             AND session.`learner_id` = receipt.`learner_id`
            """.trimIndent(),
        )
        check(
            connection.readCount("SELECT COUNT(*) FROM `$STUDENT_REVIEW_REVEAL_TABLE`") ==
                connection.readCount("SELECT COUNT(*) FROM `$LEGACY_V5_REVIEW_REVEAL_TABLE`"),
        ) {
            "v5 answer-reveal migration did not preserve every receipt"
        }
    }

    check(
        connection.readCount("SELECT COUNT(*) FROM `$STUDENT_REVIEW_SESSION_TABLE`") ==
            connection.readCount("SELECT COUNT(*) FROM `$LEGACY_V5_REVIEW_SESSION_TABLE`"),
    ) {
        "v5 review-session migration did not preserve every session"
    }

    if (legacyTables.hasTransitionReceipt) {
        connection.execSQL("DROP TABLE `$LEGACY_V5_REVIEW_TRANSITION_TABLE`")
    }
    if (legacyTables.hasRevealReceipt) {
        connection.execSQL("DROP TABLE `$LEGACY_V5_REVIEW_REVEAL_TABLE`")
    }
    connection.execSQL("DROP TABLE `$LEGACY_V5_REVIEW_SESSION_TABLE`")
}

internal fun SQLiteConnection.tableExists(tableName: String): Boolean =
    prepare(
        """
        SELECT 1
        FROM sqlite_master
        WHERE type = 'table' AND name = '$tableName'
        LIMIT 1
        """.trimIndent(),
    ).use { statement -> statement.step() }

private fun SQLiteConnection.readCount(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Count query returned no row" }
        statement.getLong(0)
    }

private data class LegacyV5ReviewAuthorityTables(
    val hasSession: Boolean,
    val hasTransitionReceipt: Boolean,
    val hasRevealReceipt: Boolean,
) {
    val hasAnyTable: Boolean
        get() = hasSession || hasTransitionReceipt || hasRevealReceipt
}

/**
 * Early v5 databases used the implicit SQLite rowid behind a revision-id primary key. The final
 * search authority needs a stable explicit rowid so document updates never silently move the FTS
 * docid. A database produced by the final 4→5 migration already has that column and is left alone.
 */
private fun migrateLegacyV5SearchDocumentIfRequired(
    connection: SQLiteConnection,
) {
    val hasExplicitRowId =
        connection.prepare("PRAGMA table_info(`student_problem_search_document`)").use { statement ->
            var found = false
            while (statement.step()) {
                if (statement.getText(1) == "rowid") {
                    found = true
                    break
                }
            }
            found
        }
    if (hasExplicitRowId) return

    STUDENT_MISTAKE_V5_SEARCH_TRIGGER_NAMES.forEach { triggerName ->
        connection.execSQL("DROP TRIGGER IF EXISTS `$triggerName`")
    }
    connection.execSQL("DROP TABLE IF EXISTS `student_problem_search_fts`")
    connection.execSQL(
        "DROP INDEX IF EXISTS `index_student_problem_search_document_revision_id`",
    )
    connection.execSQL(
        """
        DROP INDEX IF EXISTS
        `index_student_problem_search_document_source_canonical_fingerprint`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        ALTER TABLE `student_problem_search_document`
        RENAME TO `student_problem_search_document_legacy_v5`
        """.trimIndent(),
    )
    connection.execSQL(STUDENT_MISTAKE_V5_SEARCH_DOCUMENT_CREATE_SQL)
    STUDENT_MISTAKE_V5_SEARCH_INDEX_AND_FTS_STATEMENTS.forEach(connection::execSQL)
    connection.execSQL(
        """
        INSERT INTO `student_problem_search_document` (
          `revision_id`, `source_canonical_fingerprint`, `normalized_text`,
          `tokenized_text`, `indexed_at_epoch_millis`
        )
        SELECT `revision_id`, `source_canonical_fingerprint`, `normalized_text`,
               `tokenized_text`, `indexed_at_epoch_millis`
        FROM `student_problem_search_document_legacy_v5`
        ORDER BY `revision_id`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `student_problem_search_document_legacy_v5`")
}

internal val STUDENT_MISTAKE_V6_SCHEMA_STATEMENTS =
    listOf(
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_BEFORE_UPDATE
        BEFORE UPDATE ON `student_problem_search_document`
        BEGIN
          DELETE FROM `student_problem_search_fts` WHERE `docid` = OLD.`rowid`;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_BEFORE_DELETE
        BEFORE DELETE ON `student_problem_search_document`
        BEGIN
          DELETE FROM `student_problem_search_fts` WHERE `docid` = OLD.`rowid`;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_AFTER_UPDATE
        AFTER UPDATE ON `student_problem_search_document`
        BEGIN
          INSERT INTO `student_problem_search_fts` (`docid`, `tokenized_text`)
          VALUES (NEW.`rowid`, NEW.`tokenized_text`);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_AFTER_INSERT
        AFTER INSERT ON `student_problem_search_document`
        BEGIN
          INSERT INTO `student_problem_search_fts` (`docid`, `tokenized_text`)
          VALUES (NEW.`rowid`, NEW.`tokenized_text`);
        END
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_plan_plan_id_learner_id`
        ON `student_review_plan` (`plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_queue_item_queue_item_id_plan_id_learner_id`
        ON `student_review_queue_item` (`queue_item_id`, `plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `student_review_session` (
          `session_id` TEXT NOT NULL,
          `session_canonical_fingerprint` TEXT NOT NULL,
          `learner_id` TEXT NOT NULL,
          `plan_id` TEXT NOT NULL,
          `active_learner_id` TEXT,
          `state` TEXT NOT NULL,
          `session_version` INTEGER NOT NULL,
          `current_queue_item_id` TEXT,
          `current_presentation_id` TEXT,
          `started_at_epoch_millis` INTEGER NOT NULL,
          `updated_at_epoch_millis` INTEGER NOT NULL,
          `completed_at_epoch_millis` INTEGER,
          PRIMARY KEY(`session_id`),
          FOREIGN KEY(`plan_id`, `learner_id`)
            REFERENCES `student_review_plan`(`plan_id`, `learner_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION,
          FOREIGN KEY(`current_queue_item_id`, `plan_id`, `learner_id`)
            REFERENCES `student_review_queue_item`(
              `queue_item_id`, `plan_id`, `learner_id`
            )
            ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_session_session_canonical_fingerprint`
        ON `student_review_session` (`session_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_student_review_session_plan_id`
        ON `student_review_session` (`plan_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_student_review_session_plan_id_learner_id`
        ON `student_review_session` (`plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_session_learner_id_state_updated_at_epoch_millis`
        ON `student_review_session` (`learner_id`, `state`, `updated_at_epoch_millis`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_session_learner_id_plan_id_updated_at_epoch_millis`
        ON `student_review_session` (`learner_id`, `plan_id`, `updated_at_epoch_millis`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_student_review_session_active_learner_id`
        ON `student_review_session` (`active_learner_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_student_review_session_current_queue_item_id`
        ON `student_review_session` (`current_queue_item_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_session_current_queue_item_id_plan_id_learner_id`
        ON `student_review_session` (`current_queue_item_id`, `plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_session_session_id_plan_id_learner_id`
        ON `student_review_session` (`session_id`, `plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `student_review_transition_receipt` (
          `transition_id` TEXT NOT NULL,
          `transition_canonical_fingerprint` TEXT NOT NULL,
          `learner_id` TEXT NOT NULL,
          `plan_id` TEXT NOT NULL,
          `session_id` TEXT NOT NULL,
          `queue_item_id` TEXT NOT NULL,
          `action_kind` TEXT NOT NULL,
          `expected_session_version` INTEGER NOT NULL,
          `resulting_session_version` INTEGER NOT NULL,
          `presentation_id` TEXT NOT NULL,
          `observation_id` TEXT,
          `submission_id` TEXT,
          `response_form` TEXT,
          `response_canonical_fingerprint` TEXT,
          `verification_outcome` TEXT,
          `attempt_ordinal` INTEGER,
          `hint_count` INTEGER,
          `answer_was_revealed` INTEGER,
          `verification_policy_version` TEXT,
          `elapsed_duration_millis` INTEGER,
          `next_available_at_epoch_millis` INTEGER NOT NULL,
          `next_due_at_epoch_millis` INTEGER,
          `scheduling_policy_version` TEXT NOT NULL,
          `outbox_event_id` TEXT,
          `occurred_at_epoch_millis` INTEGER NOT NULL,
          PRIMARY KEY(`transition_id`),
          FOREIGN KEY(`session_id`, `plan_id`, `learner_id`)
            REFERENCES `student_review_session`(`session_id`, `plan_id`, `learner_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION,
          FOREIGN KEY(`queue_item_id`, `plan_id`, `learner_id`)
            REFERENCES `student_review_queue_item`(
              `queue_item_id`, `plan_id`, `learner_id`
            )
            ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_transition_receipt_transition_canonical_fingerprint`
        ON `student_review_transition_receipt` (`transition_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_transition_receipt_session_id_resulting_session_version`
        ON `student_review_transition_receipt` (`session_id`, `resulting_session_version`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_transition_receipt_queue_item_id`
        ON `student_review_transition_receipt` (`queue_item_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_transition_receipt_session_id_plan_id_learner_id`
        ON `student_review_transition_receipt` (`session_id`, `plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_transition_receipt_queue_item_id_plan_id_learner_id`
        ON `student_review_transition_receipt` (`queue_item_id`, `plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_transition_receipt_learner_id_occurred_at_epoch_millis`
        ON `student_review_transition_receipt` (`learner_id`, `occurred_at_epoch_millis`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_transition_receipt_outbox_event_id`
        ON `student_review_transition_receipt` (`outbox_event_id`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `student_review_reveal_receipt` (
          `reveal_id` TEXT NOT NULL,
          `reveal_canonical_fingerprint` TEXT NOT NULL,
          `learner_id` TEXT NOT NULL,
          `plan_id` TEXT NOT NULL,
          `session_id` TEXT NOT NULL,
          `queue_item_id` TEXT NOT NULL,
          `presentation_id` TEXT NOT NULL,
          `revealed_at_epoch_millis` INTEGER NOT NULL,
          PRIMARY KEY(`reveal_id`),
          FOREIGN KEY(`session_id`, `plan_id`, `learner_id`)
            REFERENCES `student_review_session`(`session_id`, `plan_id`, `learner_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION,
          FOREIGN KEY(`queue_item_id`, `plan_id`, `learner_id`)
            REFERENCES `student_review_queue_item`(
              `queue_item_id`, `plan_id`, `learner_id`
            )
            ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_reveal_receipt_reveal_canonical_fingerprint`
        ON `student_review_reveal_receipt` (`reveal_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_review_reveal_receipt_session_id_queue_item_id`
        ON `student_review_reveal_receipt` (`session_id`, `queue_item_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_reveal_receipt_queue_item_id`
        ON `student_review_reveal_receipt` (`queue_item_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_reveal_receipt_session_id_plan_id_learner_id`
        ON `student_review_reveal_receipt` (`session_id`, `plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_reveal_receipt_queue_item_id_plan_id_learner_id`
        ON `student_review_reveal_receipt` (`queue_item_id`, `plan_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_review_reveal_receipt_learner_id_revealed_at_epoch_millis`
        ON `student_review_reveal_receipt` (`learner_id`, `revealed_at_epoch_millis`)
        """.trimIndent(),
    )

private const val STUDENT_MISTAKE_V5_SEARCH_DOCUMENT_CREATE_SQL =
    """
    CREATE TABLE IF NOT EXISTS `student_problem_search_document` (
      `rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
      `revision_id` TEXT NOT NULL,
      `source_canonical_fingerprint` TEXT NOT NULL,
      `normalized_text` TEXT NOT NULL,
      `tokenized_text` TEXT NOT NULL,
      `indexed_at_epoch_millis` INTEGER NOT NULL,
      FOREIGN KEY(`revision_id`) REFERENCES `student_problem_revision`(`revision_id`)
        ON UPDATE NO ACTION ON DELETE CASCADE
    )
    """

private val STUDENT_MISTAKE_V5_SEARCH_INDEX_AND_FTS_STATEMENTS =
    listOf(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
        `index_student_problem_search_document_revision_id`
        ON `student_problem_search_document` (`revision_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
        `index_student_problem_search_document_source_canonical_fingerprint`
        ON `student_problem_search_document` (`source_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `student_problem_search_fts`
        USING FTS4(
          `tokenized_text` TEXT NOT NULL,
          content=`student_problem_search_document`,
          tokenize=unicode61
        )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_BEFORE_UPDATE
        BEFORE UPDATE ON `student_problem_search_document`
        BEGIN
          DELETE FROM `student_problem_search_fts` WHERE `docid` = OLD.`rowid`;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_BEFORE_DELETE
        BEFORE DELETE ON `student_problem_search_document`
        BEGIN
          DELETE FROM `student_problem_search_fts` WHERE `docid` = OLD.`rowid`;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_AFTER_UPDATE
        AFTER UPDATE ON `student_problem_search_document`
        BEGIN
          INSERT INTO `student_problem_search_fts` (`docid`, `tokenized_text`)
          VALUES (NEW.`rowid`, NEW.`tokenized_text`);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS
        room_fts_content_sync_student_problem_search_fts_AFTER_INSERT
        AFTER INSERT ON `student_problem_search_document`
        BEGIN
          INSERT INTO `student_problem_search_fts` (`docid`, `tokenized_text`)
          VALUES (NEW.`rowid`, NEW.`tokenized_text`);
        END
        """.trimIndent(),
    )

private val STUDENT_MISTAKE_V5_SEARCH_TRIGGER_NAMES =
    listOf(
        "room_fts_content_sync_student_problem_search_fts_BEFORE_UPDATE",
        "room_fts_content_sync_student_problem_search_fts_BEFORE_DELETE",
        "room_fts_content_sync_student_problem_search_fts_AFTER_UPDATE",
        "room_fts_content_sync_student_problem_search_fts_AFTER_INSERT",
    )

private const val STUDENT_REVIEW_SESSION_TABLE = "student_review_session"
private const val STUDENT_REVIEW_TRANSITION_TABLE = "student_review_transition_receipt"
private const val STUDENT_REVIEW_REVEAL_TABLE = "student_review_reveal_receipt"
private const val LEGACY_V5_REVIEW_SESSION_TABLE = "student_review_session_legacy_v5"
private const val LEGACY_V5_REVIEW_TRANSITION_TABLE =
    "student_review_transition_receipt_legacy_v5"
private const val LEGACY_V5_REVIEW_REVEAL_TABLE = "student_review_reveal_receipt_legacy_v5"

private val STUDENT_MISTAKE_REVIEW_IMMUTABILITY_TRIGGER_NAMES =
    listOf(
        "immutable_student_review_transition_receipt_update",
        "immutable_student_review_transition_receipt_delete",
        "immutable_student_review_reveal_receipt_update",
        "immutable_student_review_reveal_receipt_delete",
    )

private val STUDENT_MISTAKE_REVIEW_AUTHORITY_INDEX_NAMES =
    listOf(
        "index_student_review_session_session_canonical_fingerprint",
        "index_student_review_session_plan_id",
        "index_student_review_session_plan_id_learner_id",
        "index_student_review_session_learner_id_state_updated_at_epoch_millis",
        "index_student_review_session_learner_id_plan_id_updated_at_epoch_millis",
        "index_student_review_session_active_learner_id",
        "index_student_review_session_current_queue_item_id",
        "index_student_review_session_current_queue_item_id_plan_id_learner_id",
        "index_student_review_session_session_id_plan_id_learner_id",
        "index_student_review_transition_receipt_transition_canonical_fingerprint",
        "index_student_review_transition_receipt_session_id_resulting_session_version",
        "index_student_review_transition_receipt_queue_item_id",
        "index_student_review_transition_receipt_session_id_plan_id_learner_id",
        "index_student_review_transition_receipt_queue_item_id_plan_id_learner_id",
        "index_student_review_transition_receipt_learner_id_occurred_at_epoch_millis",
        "index_student_review_transition_receipt_outbox_event_id",
        "index_student_review_reveal_receipt_reveal_canonical_fingerprint",
        "index_student_review_reveal_receipt_session_id_queue_item_id",
        "index_student_review_reveal_receipt_queue_item_id",
        "index_student_review_reveal_receipt_session_id_plan_id_learner_id",
        "index_student_review_reveal_receipt_queue_item_id_plan_id_learner_id",
        "index_student_review_reveal_receipt_learner_id_revealed_at_epoch_millis",
    )
