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

/** 迁移矩阵夹具里那道题的 id（四张表共用一套，视图靠它们 join）。 */
internal const val SEEDED_PROBLEM_ID = "matrix-problem"
internal const val SEEDED_REVISION_ID = "matrix-revision"
internal const val SEEDED_PRACTICE_UNIT_ID = "matrix-practice-unit"
internal const val SEEDED_ENTRY_ID = "matrix-entry"

/** 夹具题面；迁移之后必须**逐字**还在，否则内容被迁移改写了。 */
internal const val SEEDED_PROBLEM_MARKDOWN = "迁移矩阵夹具题面。"

/**
 * 往「按某个已导出 schema 版本建出来的库」里写一行最小可检索的错题。
 *
 * 存在的理由（审计 `migration-matrix-empty-assertion`）：迁移矩阵此前只在**空库**上断言
 * `libraryCatalogCount(...) == 0`，那句话对任何迁移都成立——**破坏性回退一条也测不出来**。
 * 要测出"数据没有被迁移弄丢"，先得有数据。
 *
 * 列按**该版本自己的 `PRAGMA table_info`** 适配，而不是按当前版本的列名写死：1..44 里这四张
 * 表的形状变过三次（`problem_revision.question_document_snapshot` 自 v3 起、
 * `error_book_entry.user_note` 自 v44 起），写死列名会让老版本直接插不进去。
 *
 * 某个 **NOT NULL 且没有默认值**的列拿不到值时**当场抛**，而不是插一行残缺数据：
 * 将来某版给这四张表加了必填列，要在这里补上，否则"迁移丢数据"和"夹具没插进去"这两种红
 * 会长得一模一样，把人带向错误的方向。
 */
internal fun seedMinimalLibraryRow(context: Context, databaseName: String, version: Int) {
    val values = mapOf(
        "problem" to mapOf(
            "problem_id" to SEEDED_PROBLEM_ID,
            "canonical_fingerprint" to "a".repeat(64),
            "subject" to "MATH",
            "created_at_epoch_millis" to 1_000L,
        ),
        "problem_revision" to mapOf(
            "revision_id" to SEEDED_REVISION_ID,
            "problem_id" to SEEDED_PROBLEM_ID,
            "revision_number" to 1,
            "title" to "迁移矩阵夹具",
            "problem_markdown" to SEEDED_PROBLEM_MARKDOWN,
            "answer_verification_status" to "UNKNOWN",
            "source_type" to "TEST",
            "content_fingerprint" to "b".repeat(64),
            "created_at_epoch_millis" to 1_000L,
        ),
        "practice_unit" to mapOf(
            "practice_unit_id" to SEEDED_PRACTICE_UNIT_ID,
            "problem_id" to SEEDED_PROBLEM_ID,
            "problem_revision_id" to SEEDED_REVISION_ID,
            "unit_key" to "unit:matrix",
            "unit_kind" to "PROBLEM",
            "title" to "迁移矩阵夹具",
            "prompt_markdown" to SEEDED_PROBLEM_MARKDOWN,
            "estimated_seconds" to 180,
            "created_at_epoch_millis" to 1_000L,
        ),
        "error_book_entry" to mapOf(
            "entry_id" to SEEDED_ENTRY_ID,
            "practice_unit_id" to SEEDED_PRACTICE_UNIT_ID,
            "problem_id" to SEEDED_PROBLEM_ID,
            "current_revision_id" to SEEDED_REVISION_ID,
            "status" to "ACTIVE",
            "accepted_at_epoch_millis" to 1_000L,
            "updated_at_epoch_millis" to 1_000L,
        ),
    )
    val database = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).absolutePath,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    )
    try {
        // 顺序即依赖顺序：problem → revision → practice_unit → error_book_entry。
        values.forEach { (table, columnValues) ->
            insertAdaptedRow(database, version, table, columnValues)
        }
    } finally {
        database.close()
    }
}

/** 只写该版本**确实有**的列；必填列缺值就抛。 */
private fun insertAdaptedRow(
    database: SQLiteDatabase,
    version: Int,
    table: String,
    values: Map<String, Any?>,
) {
    val columns = mutableListOf<String>()
    val placeholders = mutableListOf<String>()
    val arguments = mutableListOf<Any?>()
    database.rawQuery("PRAGMA table_info(`$table`)", null).use { info ->
        if (!info.moveToFirst()) error("v$version 的导出 schema 里没有表 $table")
        do {
            val name = info.getString(1)
            val value = values[name]
            if (value != null) {
                columns += name
                placeholders += "?"
                arguments += value
            } else if (info.getInt(NOT_NULL_COLUMN) != 0 && info.isNull(DEFAULT_VALUE_COLUMN)) {
                error(
                    "v$version 的 $table.$name 是 NOT NULL 且没有默认值，夹具却没给它值——" +
                        "该版本给这张表加了必填列，需要在 seedMinimalLibraryRow 里补上",
                )
            }
        } while (info.moveToNext())
    }
    database.execSQL(
        "INSERT INTO `$table` (${columns.joinToString(", ") { "`$it`" }}) " +
            "VALUES (${placeholders.joinToString(", ")})",
        arguments.toTypedArray(),
    )
}

/** `PRAGMA table_info` 的列序：1=name、2=type、3=notnull、4=dflt_value。 */
private const val NOT_NULL_COLUMN = 3
private const val DEFAULT_VALUE_COLUMN = 4


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
