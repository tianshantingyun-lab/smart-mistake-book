package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v45→46：知识点与教学材料的**内容生命周期**字段 + 调和进度表。
 *
 * **它消灭的失败**：发布前安装器是"逐行相等否则拒绝"，于是任何一次内容改动都会让知识库
 * 整包停摆；而"退役一个知识点"在这套结构下根本不可表达——外键在 8 张表上是 RESTRICT，
 * 物删要么被挡住，要么对 `knowledge_mastery_state` 这类 CASCADE 表静默带走学生数据。
 *
 * 加这三样之后，"内容更新"才有一条可走的路：
 * - `knowledge_node.status` / `superseded_by`：退役而不物删，合并时留 1:1 重定向
 * - `knowledge_teaching_material.status`：材料同样可退役（学生可能用过它生成的题）
 * - `content_install_state`：调和的进度锚点，让"装到一半崩了"可被下一次启动发现
 *
 * 三条 DDL 的默认值必须与实体上的 `@ColumnInfo(defaultValue = …)` **逐字一致**——
 * Room 在开库时用 `TableInfo` 做列级比对，默认值不一致会在 `onValidateSchema` 失败。
 * 存量行因此一律是 `ACTIVE`：退役是**将来**事实，不能靠迁移倒填。
 */
internal val KNOWLEDGE_CONTENT_LIFECYCLE_MIGRATION_45_46 = object : Migration(45, 46) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `knowledge_node` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'",
        )
        // 软指针，刻意不建外键：悬空无害（解析不到就退回显示原节点自己的名字），
        // 建外键会引入自引用约束，并让"取代目标本身也被退役"的链式情形难以迁移。
        connection.execSQL("ALTER TABLE `knowledge_node` ADD COLUMN `superseded_by` TEXT")
        connection.execSQL(
            "ALTER TABLE `knowledge_teaching_material` ADD COLUMN `status` " +
                "TEXT NOT NULL DEFAULT 'ACTIVE'",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `content_install_state` (
                `pack_id` TEXT NOT NULL,
                `content_version` TEXT NOT NULL,
                `applied_at_epoch_millis` INTEGER NOT NULL,
                `skipped_count` INTEGER NOT NULL DEFAULT 0,
                `skipped_detail` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`pack_id`)
            )
            """,
        )
    }
}
