package com.tingyun.smartmistakebook.core.database

import androidx.sqlite.SQLiteConnection

internal fun SQLiteConnection.tableExists(tableName: String): Boolean =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        statement.step()
    }

internal fun SQLiteConnection.columnExists(tableName: String, columnName: String): Boolean =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        while (statement.step()) {
            if (statement.getText(1) == columnName) return true
        }
        false
    }
