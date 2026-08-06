package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.PooledConnection
import androidx.room3.Transactor
import androidx.room3.executeSQL
import androidx.room3.immediateTransaction

/**
 * Explicit user-requested erase. This is the only path that intentionally bypasses the immutable
 * ledger guards; it clears learner tables and leaves Room to reinstall the exact guard set when
 * the database is reopened.
 */
internal suspend fun eraseAllLearnerMasteryRows(connection: Transactor) {
    val triggerNames = readAllTriggerNames(connection)
    val tableNames = readAllTableNames(connection)
    connection.executeSQL("PRAGMA foreign_keys = OFF")
    try {
        connection.immediateTransaction {
            triggerNames.forEach { name ->
                executeSQL("DROP TRIGGER IF EXISTS `$name`")
            }
            tableNames.forEach { name ->
                executeSQL("DELETE FROM `$name`")
            }
        }
    } finally {
        connection.executeSQL("PRAGMA foreign_keys = ON")
    }
}

private suspend fun readAllTriggerNames(connection: PooledConnection): List<String> =
    connection.usePrepared(
        "SELECT name FROM sqlite_master WHERE type = 'trigger'",
    ) { statement ->
        buildList {
            while (statement.step()) {
                add(statement.getText(0))
            }
        }
    }

private suspend fun readAllTableNames(connection: PooledConnection): List<String> =
    connection.usePrepared(
        """
        SELECT name
        FROM sqlite_master
        WHERE type = 'table'
          AND name NOT LIKE 'sqlite_%'
          AND name NOT IN (
            'room_master_table',
            'android_metadata',
            'mastery_store_metadata',
            'mastery_outbox_authenticity_key_state',
            'mastery_ledger_sequence'
          )
        """.trimIndent(),
    ) { statement ->
        buildList {
            while (statement.step()) {
                add(statement.getText(0))
            }
        }
    }
