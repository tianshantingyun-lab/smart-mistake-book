package com.tingyun.smartmistakebook.core.database

import java.util.Locale

/** Builds the bounded local index used to find relevant reviewed knowledge points. */
object KnowledgeSearchFeatureExtractor {
    const val MAX_QUERY_FEATURES = 128
    private const val MAX_NODE_FEATURES = 192
    private const val MAX_WORD_LENGTH = 48
    private const val MAX_SEARCH_FRAGMENTS = 16

    /**
     * 节点侧（索引侧）抽取规则指纹——建索引/重建索引时记入
     * `knowledge_search_index_state`，版本不一致（缺失或不同值，含实验版本遗留）时
     * [RoomKnowledgeBaseStore.ensureKnowledgeSearchIndex] 强制整科重建。
     *
     * v1（当前生产形状）：节点侧有 `MAX_SEARCH_FRAGMENTS=16` / `MAX_NODE_FEATURES=192`
     * 的有损截断（随包包 62% 的节点片段数超 16，多数节点的别名只有一部分进了生产
     * 索引）。截断即登记册 KD-24 的别名截断缺陷：2026-09-22 曾按裁定做 v2 去截断
     * 实验，实测对词面路由是净伤害（公共 gram 通胀打掉窄路召回 19 例自由落体 + 宽路
     * p95 663ms 超预算），当日回滚（docs/kb-vector-topic-decision.md §3.2）——缺陷改由
     * 已开启的同层 dense 兜底议题结构性消灭，词面侧不再以去截断方式修。
     *
     * **节点侧规则再变时必须改这个指纹（惯例 +1）**——否则旧库的索引永远触发不了重建；
     * 回滚方向同样触发（锚点存的是实验版本 2、当前是 1，不等即换血）。
     * 查询侧（[MAX_QUERY_FEATURES]）不属于这个指纹：它每次调用现算，不落库。
     */
    const val INDEX_VERSION = 1

    fun fromQuestion(text: String): Set<String> = extract(
        texts = listOf(text),
        fragmentLimit = MAX_SEARCH_FRAGMENTS,
        featureLimit = MAX_QUERY_FEATURES,
    )

    /**
     * 节点侧（索引侧）：有损截断——片段数超 [MAX_SEARCH_FRAGMENTS] 时保留
     * 末段 + 首段 + 均衡中间段，特征总数封顶 [MAX_NODE_FEATURES]（单片段
     * [MAX_WORD_LENGTH] 字上限两档共用）。
     *
     * 截断是已登记的缺陷（KD-24：62% 节点别名不全入索引）；v2 去截断实验实测净伤害
     * 已回滚，生产形状钉在 v1。重建触发靠 [INDEX_VERSION] 门控。
     */
    fun fromNode(node: KnowledgeNodeSeedRecord): Set<String> {
        val texts = buildList {
            add(node.canonicalName)
            addAll(node.aliases.sorted())
            node.boundaryMarkdown?.let(::add)
        }
        return extract(
            texts = texts,
            fragmentLimit = MAX_SEARCH_FRAGMENTS,
            featureLimit = MAX_NODE_FEATURES,
        ).ifEmpty {
            setOf(node.canonicalName.lowercase(Locale.ROOT).take(MAX_WORD_LENGTH))
        }
    }

    private fun extract(
        texts: Iterable<String>,
        fragmentLimit: Int,
        featureLimit: Int,
    ): Set<String> {
        val result = linkedSetOf<String>()
        val fragments = texts
            .flatMap { raw ->
                raw.lowercase(Locale.ROOT)
                    .split(NON_SEARCH_CHARACTER)
                    .filter(String::isNotBlank)
            }
        val selectedFragments = if (fragments.size <= fragmentLimit) {
            fragments
        } else {
            // Long captures usually put the actual ask last and the givens first.
            buildList {
                add(fragments.last())
                add(fragments.first())
                balancedStartIndexes(fragments.size).forEach { index ->
                    if (size < fragmentLimit && fragments[index] !in this) {
                        add(fragments[index])
                    }
                }
            }
        }
        val streams = ArrayDeque<Iterator<String>>()
        selectedFragments.mapTo(streams) { fragmentFeatures(it).iterator() }
        while (result.size < featureLimit && streams.isNotEmpty()) {
            val iterator = streams.removeFirst()
            if (iterator.hasNext()) result += iterator.next()
            if (iterator.hasNext()) streams.addLast(iterator)
        }
        return result
    }

    private fun fragmentFeatures(fragment: String): Sequence<String> = sequence {
        if (fragment.length in 2..MAX_WORD_LENGTH) yield(fragment)
        for (width in 2..minOf(3, fragment.length)) {
            balancedStartIndexes(fragment.length - width + 1).forEach { start ->
                yield(fragment.substring(start, start + width))
            }
        }
    }

    private fun balancedStartIndexes(count: Int): Sequence<Int> = sequence {
        if (count <= 0) return@sequence
        val pending = ArrayDeque<IntRange>()
        pending += 0 until count
        while (pending.isNotEmpty()) {
            val range = pending.removeFirst()
            val middle = (range.first + range.last) / 2
            yield(middle)
            if (range.first < middle) pending += range.first until middle
            if (middle < range.last) pending += (middle + 1)..range.last
        }
    }

    private val NON_SEARCH_CHARACTER = Regex("[^\\p{L}\\p{N}]+")
}
