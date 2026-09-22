package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 别名倒排索引（`knowledge_search_feature`）的**每科抽取规则版本锚点**。
 *
 * **它消灭的失败**：2026-09-22 前节点侧抽取有 `MAX_SEARCH_FRAGMENTS=16` /
 * `MAX_NODE_FEATURES=192` 的有损截断（随包包 62% 节点片段数超 16，多数别名只有一部分
 * 进了生产索引）。上限删除后，旧库里已建的截断索引在"已索引数 ≥ 已审校数"的自愈
 * 检查下**永远看起来是满的**——没有这个锚点，旧库会带着残缺索引一直跑下去，
 * 去截断的收益永远落不了地。
 *
 * 语义：`index_version` 记录**本科特征行是哪一版 [KnowledgeSearchFeatureExtractor]
 * 节点侧规则写出来的**。读路径（`RoomKnowledgeBaseStore.ensureKnowledgeSearchIndex`）
 * 发现锚点缺失或**不等于**当前版本时（两个方向都换血：legacy 库无锚点；v2 去截断
 * 实验库锚点=2、当前回滚回 v1 锚点=1），整科删旧特征行、按当前规则重建、再推进锚点。
 * 缺失 = legacy（表由 v51 迁移新建，任何 v50 及以前的库都没有锚点行，一律重建一次）。
 * 节点内容本身不可变（验证状态是唯一单调属性，不参与特征抽取），所以
 * "同版本内只增不删"的自愈语义在锚点一致后依然成立。
 */
@Entity(tableName = "knowledge_search_index_state")
internal data class KnowledgeSearchIndexStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "index_version")
    val indexVersion: Int,
)
