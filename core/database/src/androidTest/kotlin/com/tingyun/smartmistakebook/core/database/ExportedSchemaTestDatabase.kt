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
        val setupQueries = schema.getJSONArray("setupQueries")
        repeat(setupQueries.length()) { index -> database.execSQL(setupQueries.getString(index)) }
        database.version = version
    } finally {
        database.close()
    }
}
