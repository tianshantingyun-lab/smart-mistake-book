package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v47→48：给批量导入的每一页记下"可选的切题阶段"是否已经了结。
 *
 * 背景：页的切题尝试发生在它已经 READY **之后**（录入绝不能被模型阻塞，这是有意的），
 * 而原来的代码只把这次尝试当作内存里的一次调用。进程在"页已 READY"与"切题已了结"
 * 之间被回收时，没有任何东西记得这一页还没切过，于是它静默退化成整页草稿，重开 App
 * 也不会再试。新列 PENDING/SETTLED 就是那次尝试的持久标记，让同一趟驱动能重新扫到它。
 *
 * 回填：已存在的 READY/SKIPPED/FAILED 页统一标为 SETTLED，表示"不归本次驱动管"。
 * 这不是偷懒——这些页的暂存原图早已被清理，重跑切题只会写出一条 source_uri 指向已删除
 * 文件的分题记录，复核页的原图会打不开。只有 QUEUED/IMPORTING 的页（还没走过这一趟的）
 * 保留 PENDING，随新流程正常处理。
 */
internal val BATCH_IMPORT_SPLIT_STATE_MIGRATION_47_48 = object : Migration(47, 48) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `batch_import_page` ADD COLUMN `split_after_status` TEXT NOT NULL DEFAULT 'PENDING'",
        )
        connection.execSQL(
            """
            UPDATE `batch_import_page` SET `split_after_status` = 'SETTLED'
            WHERE `status` IN ('READY', 'SKIPPED', 'FAILED')
            """.trimIndent(),
        )
    }
}
