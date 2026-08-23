package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v33 -> v34: visual-interaction attempt audit table (audit PR-11 / §12).
 *
 * Persists every locally judged student interaction with the dynamic
 * teaching GUI so the visual interaction loop has a durable trail. The
 * write path is best-effort (the sink degrades silently) and never
 * blocks the interaction itself.
 */
internal val VISUAL_INTERACTION_MIGRATION_33_34 = object : Migration(33, 34) {
    override suspend fun migrate(connection: SQLiteConnection) {
        // CREATE TABLE text must stay byte-identical to the exported 34.json
        // createSql so Room's identity check accepts migrated databases.
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `visual_interaction_attempt` (" +
                "`attempt_id` TEXT NOT NULL, " +
                "`problem_revision_id` TEXT NOT NULL, " +
                "`action_kind` TEXT NOT NULL, " +
                "`action_payload` TEXT NOT NULL, " +
                "`feasible` INTEGER NOT NULL, " +
                "`feedback` TEXT NOT NULL, " +
                "`attempted_at_epoch_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`attempt_id`))",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_visual_interaction_attempt_problem_revision_id` " +
                "ON `visual_interaction_attempt` (`problem_revision_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_visual_interaction_attempt_attempted_at_epoch_millis` " +
                "ON `visual_interaction_attempt` (`attempted_at_epoch_millis`)",
        )
    }
}
