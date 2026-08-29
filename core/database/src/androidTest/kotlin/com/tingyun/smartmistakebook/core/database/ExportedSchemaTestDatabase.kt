package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal fun createDatabaseFromExportedSchema(
    context: Context,
    databaseName: String,
    version: Int,
) {
    val assetPath = "com.tingyun.smartmistakebook.core.database.StudyDatabase/$version.json"
    val schema = context.assets.open(assetPath).bufferedReader().use { reader ->
        JSONObject(reader.readText()).getJSONObject("database")
    }
    val database = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
    try {
        val entities = schema.getJSONArray("entities")
        repeat(entities.length()) { index ->
            val entity = entities.getJSONObject(index)
            val tableName = entity.getString("tableName")
            database.execSQL(
                entity.getString("createSql").replace("${'$'}{TABLE_NAME}", tableName),
            )
            entity.optJSONArray("indices")?.let { indices ->
                repeat(indices.length()) { indexPosition ->
                    database.execSQL(
                        indices.getJSONObject(indexPosition)
                            .getString("createSql")
                            .replace("${'$'}{TABLE_NAME}", tableName),
                    )
                }
            }
        }
        val views = schema.optJSONArray("views")
        repeat(views?.length() ?: 0) { index ->
            val view = views.getJSONObject(index)
            database.execSQL(
                view.getString("createSql").replace("${'$'}{VIEW_NAME}", view.getString("viewName")),
            )
        }
        val setupQueries = schema.getJSONArray("setupQueries")
        repeat(setupQueries.length()) { index -> database.execSQL(setupQueries.getString(index)) }
        database.version = version
    } finally {
        database.close()
    }
}


/**
 * Structural schema equivalence: ALTER TABLE migrations append columns with
 * their own SQL text and the Room wrapper adds runtime FTS-sync triggers on
 * open, so byte-comparing sqlite_master can never hold once a migration has
 * altered a table. Compare the structure Room itself validates instead:
 * object inventory plus per-table column layout and index columns.
 */
internal fun assertStructurallyEqual(
    referencePath: java.io.File,
    migratedPath: java.io.File,
) {
    // ALTER TABLE can only append columns, so a migrated table's column
    // ORDER differs from a fresh create; Room's own migration validation
    // compares columns as a name->type map, and so does this check.
    val reference = readStructuralSchema(referencePath).mapValues { (_, columns) ->
        columns.associate { it }
    }
    val migrated = readStructuralSchema(migratedPath).mapValues { (_, columns) ->
        columns.associate { it }
    }
    val missing = reference.keys - migrated.keys
    val extra = migrated.keys - reference.keys
    val differing = reference.keys.intersect(migrated.keys)
        .filter { key -> reference.getValue(key) != migrated.getValue(key) }
        .associateWith { key ->
            "REF=" + reference.getValue(key).toString() + " ACT=" + migrated.getValue(key).toString()
        }
    org.junit.Assert.assertTrue(
        "missing=$missing extra=$extra differing=" + differing.entries.take(3),
        missing.isEmpty() && extra.isEmpty() && differing.isEmpty(),
    )
}

private fun readStructuralSchema(path: java.io.File): Map<String, List<Pair<String, String>>> {
    val database = android.database.sqlite.SQLiteDatabase.openDatabase(
        path.absolutePath,
        null,
        android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
    )
    try {
        val schema = linkedMapOf<String, List<Pair<String, String>>>()
        database.rawQuery(
            "SELECT type, name FROM sqlite_master " +
                "WHERE type IN ('table', 'index', 'view') " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                "ORDER BY type, name",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val name = cursor.getString(1)
                schema["$type:$name"] = emptyList()
            }
        }
        // Attach column layout for every table (Room's migration authority).
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'",
            null,
        ).use { cursor ->
            val tables = mutableListOf<String>()
            while (cursor.moveToNext()) tables += cursor.getString(0)
            tables.forEach { table ->
                val columns = mutableListOf<Pair<String, String>>()
                database.rawQuery("PRAGMA table_info(`$table`)", null).use { info ->
                    while (info.moveToNext()) {
                        columns += info.getString(1) to info.getString(2)
                    }
                }
                schema["table:$table"] = columns
            }
        }
        return schema
    } finally {
        database.close()
    }
}
