package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Mastery-scheduling v36 (spec mastery-scheduling §3):
 *
 * - new [review_log][REVIEW_LOG_DDL] table (raw collected evidence, unique
 *   per learner+source id);
 * - attempt_event interaction/error columns (hint_count, revealed flag,
 *   error-type channel placeholders);
 * - learner problem-memory and knowledge-mastery states gain
 *   last-evidence reason/direction plus the cross-day streak counters the
 *   leech and graduation state machines read;
 * - one-time difficulty domain migration 0..1 → 1..10 (D = 1 + 9·d);
 * - library_catalog view re-created to read real learner projection state
 *   (linkage defect L1) instead of the legacy fixture table, with the
 *   learner exposed for query-level filtering.
 */
internal val MASTERY_SCHEDULING_MIGRATION_35_36 = object : Migration(35, 36) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(REVIEW_LOG_DDL)
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_log_learner_id_source_id` " +
                "ON `review_log` (`learner_id`, `source_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_review_log_learner_id_card_id` " +
                "ON `review_log` (`learner_id`, `card_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_review_log_learner_id_reviewed_at_utc` " +
                "ON `review_log` (`learner_id`, `reviewed_at_utc`)",
        )

        connection.execSQL(
            "ALTER TABLE attempt_event ADD COLUMN hint_count INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            "ALTER TABLE attempt_event ADD COLUMN revealed_before_answer INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL("ALTER TABLE attempt_event ADD COLUMN error_type TEXT")
        connection.execSQL("ALTER TABLE attempt_event ADD COLUMN error_type_confidence REAL")
        connection.execSQL(
            "ALTER TABLE attempt_event ADD COLUMN low_confidence_correct INTEGER NOT NULL DEFAULT 0",
        )

        connection.execSQL(
            "ALTER TABLE learner_problem_memory_state ADD COLUMN last_evidence_reason TEXT",
        )
        connection.execSQL(
            "ALTER TABLE learner_problem_memory_state ADD COLUMN last_evidence_direction TEXT",
        )
        connection.execSQL(
            "ALTER TABLE learner_problem_memory_state ADD COLUMN " +
                "consecutive_cross_day_success INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            "ALTER TABLE learner_problem_memory_state ADD COLUMN " +
                "consecutive_cross_day_again INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            "ALTER TABLE learner_knowledge_mastery_state ADD COLUMN last_evidence_reason TEXT",
        )
        connection.execSQL(
            "ALTER TABLE learner_knowledge_mastery_state ADD COLUMN last_evidence_direction TEXT",
        )

        // One-time difficulty domain conversion: 0..1 → 1..10.
        connection.execSQL(
            "UPDATE learner_problem_memory_state SET difficulty = 1.0 + 9.0 * difficulty",
        )

        connection.execSQL("DROP VIEW IF EXISTS `library_catalog`")
        connection.execSQL(LIBRARY_CATALOG_VIEW_SQL_V36)
    }
}

/** Must match the 36.json exported createSql for review_log byte for byte. */
internal const val REVIEW_LOG_DDL =
    "CREATE TABLE IF NOT EXISTS `review_log` (" +
        "`review_log_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`learner_id` TEXT NOT NULL, " +
        "`card_id` TEXT NOT NULL, " +
        "`rating` INTEGER NOT NULL, " +
        "`delta_t_days` REAL NOT NULL, " +
        "`duration_ms` INTEGER NOT NULL, " +
        "`reviewed_at_utc` INTEGER NOT NULL, " +
        "`source_kind` TEXT NOT NULL, " +
        "`source_id` TEXT NOT NULL, " +
        "`evidence_weight` REAL NOT NULL, " +
        "`scheduling_eligible` INTEGER NOT NULL DEFAULT 1, " +
        "`time_bucket` TEXT NOT NULL, " +
        "`recorded_at` INTEGER NOT NULL)"

/**
 * Byte-exact re-creation of the library_catalog view for migration 35 -> 36.
 * Room validates the executed view SQL verbatim against the exported 36.json
 * createSql (the same text the @DatabaseView annotation compiles to), so this
 * constant is generated from that schema file and must be regenerated if the
 * view definition changes. The view now joins the authoritative learner
 * projection tables (linkage L1) and exposes the learner id for
 * query-level parameterization (spec 3.3).
 */
internal val LIBRARY_CATALOG_VIEW_SQL_V36: String =
    "CREATE VIEW `library_catalog` AS SELECT\n" + "            entry.entry_id,\n" + "            entry.problem_id,\n" + "            revision.revision_id AS problem_revision_id,\n" + "            entry.practice_unit_id,\n" + "            problem.subject,\n" + "            unit.title,\n" + "            revision.problem_markdown,\n" + "            entry.accepted_at_epoch_millis AS created_at_epoch_millis,\n" + "            entry.updated_at_epoch_millis AS updated_at_epoch_millis,\n" + "            memory.next_review_at_epoch_millis,\n" + "            NULL AS retrievability,\n" + "            memory.learner_id AS memory_learner_id,\n" + "            (\n" + "                SELECT\n" + "                    CASE\n" + "                        WHEN COUNT(*) = 0 THEN 'unknown'\n" + "                        WHEN SUM(CASE WHEN mastery.status = 'CONFLICTED' THEN 1 ELSE 0 END) > 0\n" + "                            THEN 'conflicted'\n" + "                        WHEN SUM(CASE WHEN mastery.status = 'STALE' THEN 1 ELSE 0 END) > 0\n" + "                            THEN 'stale'\n" + "                        WHEN SUM(CASE WHEN mastery.status = 'LEARNING' THEN 1 ELSE 0 END) > 0\n" + "                            THEN 'learning'\n" + "                        WHEN SUM(CASE WHEN mastery.status = 'MASTERED' THEN 1 ELSE 0 END) =\n" + "                            COUNT(*) THEN 'mastered'\n" + "                        ELSE 'unknown'\n" + "                    END\n" + "                FROM learner_knowledge_mastery_state AS mastery\n" + "                INNER JOIN practice_unit_knowledge_binding AS binding\n" + "                    ON binding.knowledge_node_id = mastery.knowledge_node_id\n" + "                   AND binding.practice_unit_id = entry.practice_unit_id\n" + "                   AND binding.basis_revision_id = revision.revision_id\n" + "                WHERE mastery.projection_name = 'study-experience-v1'\n" + "                  AND mastery.learner_id = memory.learner_id\n" + "            ) AS mastery_id,\n" + "            (\n" + "                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))\n" + "                FROM problem_classification_binding AS classification\n" + "                WHERE classification.problem_id = entry.problem_id\n" + "                  AND classification.basis_revision_id = revision.revision_id\n" + "                  AND classification.dimension = 'CHAPTER'\n" + "            ) AS chapter_labels,\n" + "            (\n" + "                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))\n" + "                FROM problem_classification_binding AS classification\n" + "                WHERE classification.problem_id = entry.problem_id\n" + "                  AND classification.basis_revision_id = revision.revision_id\n" + "                  AND classification.dimension = 'KNOWLEDGE'\n" + "            ) AS knowledge_labels\n" + "        FROM error_book_entry AS entry\n" + "        JOIN practice_unit AS unit\n" + "            ON unit.practice_unit_id = entry.practice_unit_id\n" + "        JOIN problem AS problem\n" + "            ON problem.problem_id = entry.problem_id\n" + "        JOIN problem_revision AS revision\n" + "            ON revision.revision_id = entry.current_revision_id\n" + "           AND revision.problem_id = entry.problem_id\n" + "        LEFT JOIN learner_problem_memory_state AS memory\n" + "            ON memory.practice_unit_id = entry.practice_unit_id\n" + "           AND memory.projection_name = 'study-experience-v1'\n" + "        WHERE entry.status = 'ACTIVE'"


/**
 * Interaction-signal columns (spec §2.14) appended to review_log by silent
 * UI collectors: upward scroll count, answer/text edit count, and the number
 * of process-level interruptions during one review event.
 */
internal val INTERACTION_SIGNAL_MIGRATION_36_37 = object : Migration(36, 37) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE review_log ADD COLUMN scroll_up_count INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE review_log ADD COLUMN edit_count INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE review_log ADD COLUMN interruption_count INTEGER NOT NULL DEFAULT 0")
    }
}


/**
 * v38: attention-signal duration ([away_millis], spec §2.14) and the planner
 * reason snapshot ([planned_reason], spec §6 weight-calibration channel) on
 * review_log.
 */
internal val ATTENTION_SIGNAL_MIGRATION_37_38 = object : Migration(37, 38) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE review_log ADD COLUMN away_millis INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE review_log ADD COLUMN planned_reason TEXT")
    }
}
