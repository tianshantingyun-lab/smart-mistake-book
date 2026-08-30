package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v42: learner_problem_memory_state gains `last_reviewed_epoch_day` (learner-local calendar day of
 * the last review) so FSRS can compute calendar-day delta_t between reviews instead of a 24-hour
 * wall-clock floor. A review under 24 hours apart that crosses the learner-local midnight is a new
 * study day (spec mastery-scheduling §2.1/§2.15).
 *
 * Backfill uses the UTC epoch-day approximation (`millis / 86400000`) because legacy rows carry no
 * time-zone information; newly projected rows store the exact learner-local epoch day. The
 * approximation is at most one day off for a UTC-offset time zone, which only affects the first
 * post-migration review's delta_t, never a correctness hazard for already-scheduled cards.
 */
internal val CALENDAR_DAY_MIGRATION_41_42 = object : Migration(41, 42) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `learner_problem_memory_state` " +
                "ADD COLUMN `last_reviewed_epoch_day` INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            "UPDATE `learner_problem_memory_state` " +
                "SET `last_reviewed_epoch_day` = `last_reviewed_at_epoch_millis` / 86400000 " +
                "WHERE `last_reviewed_at_epoch_millis` > 0",
        )
    }
}
