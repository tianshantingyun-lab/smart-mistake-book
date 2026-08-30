package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v40: learner_chat_evidence（spec model-intent-routing §5）——模型在工具环内
 * 提交的学习证据事件表。模型只给 direction/reason，weight 由本地封顶常量赋值，
 * 掌握度数值仍由投影器公式产生。conversation_id + source_kind 是审计与批量
 * 撤销的锚点。
 */
internal val CHAT_EVIDENCE_MIGRATION_39_40 = object : Migration(39, 40) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(LEARNER_CHAT_EVIDENCE_DDL)
        connection.execSQL(LEARNER_CHAT_EVIDENCE_SESSION_INDEX_DDL)
    }
}

internal const val LEARNER_CHAT_EVIDENCE_DDL =
    "CREATE TABLE IF NOT EXISTS `learner_chat_evidence` (" +
        "`evidence_id` TEXT NOT NULL, " +
        "`learner_id` TEXT NOT NULL, " +
        "`conversation_id` TEXT NOT NULL, " +
        "`knowledge_node_id` TEXT NOT NULL, " +
        "`direction` TEXT NOT NULL, " +
        "`weight` REAL NOT NULL, " +
        "`reason_markdown` TEXT NOT NULL, " +
        "`confidence` REAL NOT NULL, " +
        "`source_kind` TEXT NOT NULL, " +
        "`created_at_epoch_millis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`evidence_id`))"

internal const val LEARNER_CHAT_EVIDENCE_SESSION_INDEX_DDL =
    "CREATE INDEX IF NOT EXISTS `index_learner_chat_evidence_conversation_id` " +
        "ON `learner_chat_evidence` (`conversation_id`)"
