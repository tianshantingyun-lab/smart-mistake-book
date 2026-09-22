package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v49→50：`learner_chat_evidence` 增加 `anchor_class` 列（ADR 0001 / D10）。
 *
 * 三值（CONFIRMED / CANDIDATE / DISCLOSED）在写入时由系统按目标知识点的代号角色机械确立，
 * 是**纯审计数据列**：投影积分只读 `weight`——非 CONFIRMED 的降权（×0.5，D9 唯一数值
 * 分支）在写入时已施加在 weight 上，本列不承担计算职责，只为"确认绑定 vs 未锚定会话
 * 证据各自推动了投影多少"留校准数据。
 *
 * 可空、不回填：旧行 NULL = legacy（列引入前写入），读回按全权重对待。回填成任何三值都
 * 是伪造历史——当年的写入不知道锚定等级（代号通道当时不存在），"不知道"只能落成 NULL。
 */
internal val CHAT_EVIDENCE_ANCHOR_CLASS_MIGRATION_49_50 = object : Migration(49, 50) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `learner_chat_evidence` ADD COLUMN `anchor_class` TEXT")
    }
}
