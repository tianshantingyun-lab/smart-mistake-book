package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v45→46 的内容生命周期迁移。
 *
 * **它消灭的失败**：迁移若把存量行倒填成非 ACTIVE，或让新增列带了非空默认值却没给旧行填上，
 * 老用户升级后一开库就会撞上 `onValidateSchema` 失败或"自己的知识点全不见了"。
 * 退役是**将来**事实，迁移只能把已完成的行标成 ACTIVE。
 *
 * 打开数据库这一步本身就是最强断言：`StudyDatabaseFactory.open` 会先跑迁移、再让 Room
 * 执行 `onValidateSchema`（逐列比对名称/类型/非空/默认值）。新列与实体声明若有任何不一致，
 * 这里就会抛，而不是等运行时。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeContentLifecycleMigrationInstrumentedTest {

    @Test
    fun versionFortyFiveRowsBecomeActiveAndStateTableStartsEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-content-lifecycle-v45-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 45)
            seedVersionFortyFiveRows(context, databaseName)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            // **必须先真读一次再关**：Room 的迁移是惰性的，不触发查询就不会跑，
            // 于是 user_version 还停在 45（本用例第一版正是这样挂的：expected 46 but was 45）。
            // 这一读同时验证了存量节点在迁移后仍可被领域层读到。
            assertEquals(
                1,
                migrated.readKnowledgeNodesByIds(setOf("legacy-node")).size,
            )
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                assertEquals(46, database.version)

                // 存量节点：ACTIVE，且没有取代目标——退役是将来事实，迁移不倒填
                assertRow(database, "knowledge_node", "knowledge_node_id", "legacy-node") { c ->
                    assertEquals("ACTIVE", c.getString(c.getColumnIndexOrThrow("status")))
                    assertNull(c.getString(c.getColumnIndexOrThrow("superseded_by")))
                }
                assertRow(database, "knowledge_teaching_material", "material_id", "legacy-material") { c ->
                    assertEquals("ACTIVE", c.getString(c.getColumnIndexOrThrow("status")))
                }

                // 调和进度表存在且为空：一行都没有 = 这个包还没被调和过
                assertEquals(0, database.countRows("content_install_state"))

                // 旧行没被删掉（迁移不是重建表）
                assertEquals(1, database.countRows("knowledge_node"))
                assertEquals(1, database.countRows("knowledge_teaching_material"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /** 按 **v45 的列集**写入，刻意不带新列——模拟真实的旧库。 */
    private fun seedVersionFortyFiveRows(context: Context, databaseName: String) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO knowledge_node
                  (knowledge_node_id, stable_code, subject, display_name, taxonomy_version,
                   created_at_epoch_millis)
                VALUES ('legacy-node', 'legacy:stable', 'MATH', '图存的旧知识点',
                        'moe-2025-four-subjects-v1', 1)
                """,
            )
            database.execSQL(
                """
                INSERT INTO knowledge_source
                  (source_id, subject, source_type, title, license_status,
                   content_fingerprint, imported_at_epoch_millis)
                VALUES ('legacy-source', 'MATH', 'AUTHORIZED_EDUCATION_MATERIAL', '旧来源',
                        'REFERENCE_ONLY', '${"A".repeat(64)}', 1)
                """,
            )
            database.execSQL(
                """
                INSERT INTO knowledge_teaching_material
                  (material_id, stable_code, subject, material_type, title, summary_markdown,
                   applicability_markdown, content_markdown, boundary_markdown, derivation_kind,
                   source_id, source_locator, content_fingerprint, reviewed_at_epoch_millis)
                VALUES ('legacy-material', 'legacy:material:stable', 'MATH', 'CONCEPT_EXPLANATION',
                        '旧材料', '摘要', '适用', '正文', '边界', 'REVIEWED_SYNTHESIS',
                        'legacy-source', '旧定位', '${"B".repeat(64)}', 1)
                """,
            )
        }
    }

    private fun assertRow(
        database: SQLiteDatabase,
        table: String,
        idColumn: String,
        id: String,
        check: (android.database.Cursor) -> Unit,
    ) {
        database.rawQuery("SELECT * FROM $table WHERE $idColumn = ?", arrayOf(id)).use { cursor ->
            assertTrue("$table 里应有一行 $id", cursor.moveToFirst())
            check(cursor)
        }
    }

    private fun SQLiteDatabase.countRows(tableName: String): Int =
        rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
