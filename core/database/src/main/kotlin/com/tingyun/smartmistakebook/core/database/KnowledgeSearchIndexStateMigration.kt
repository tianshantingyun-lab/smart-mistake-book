package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v50→51：新建 `knowledge_search_index_state`（别名倒排索引的每科抽取规则版本锚点）。
 *
 * 背景：节点侧特征抽取 2026-09-22 去截断（删 `MAX_SEARCH_FRAGMENTS=16` /
 * `MAX_NODE_FEATURES=192`，容量不设限，用户裁定）。旧库的 `knowledge_search_feature`
 * 是 v1 规则建的截断索引，且"已索引数 ≥ 已审校数"的自愈检查对它是满的——不重建就
 * 永远带着残缺索引跑。本表让重建可观测、只发生一次：锚点缺失或小于当前
 * `KnowledgeSearchFeatureExtractor.INDEX_VERSION` 的科，在首次读召回时整科重建。
 *
 * 不回填任何行：回填成"当前版本"等于谎称旧行是新版规则建的；缺失 = legacy = 重建，
 * 正是想要的语义。
 */
internal val KNOWLEDGE_SEARCH_INDEX_STATE_MIGRATION_50_51 = object : Migration(50, 51) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_search_index_state` (
                `subject` TEXT NOT NULL,
                `index_version` INTEGER NOT NULL,
                PRIMARY KEY(`subject`)
            )
            """.trimIndent(),
        )
    }
}
