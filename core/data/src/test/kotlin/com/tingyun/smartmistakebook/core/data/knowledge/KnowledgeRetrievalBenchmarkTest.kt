package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Benchmark tests for knowledge retrieval quality.
 * Tests Recall@5, MRR, and grounding precision.
 */
class KnowledgeRetrievalBenchmarkTest {

    @Test
    fun `recall at 5 retrieves relevant knowledge points`() {
        val candidates = createMathKnowledgeNodes()
        val queries = listOf(
            "函数在哪些区间单调递增" to listOf("read-monotonicity-from-graph"),
            "求解一元二次方程" to listOf("solve-quadratic-equation"),
            "勾股定理的应用" to listOf("apply-pythagorean-theorem"),
            "计算圆的面积" to listOf("calculate-circle-area-circumference"),
            "数据的平均数" to listOf("calculate-mean-median-mode"),
        )

        var totalRecall = 0
        queries.forEach { (query, expectedIds) ->
            val selected = KnowledgeContextRetriever.select(
                candidates = candidates,
                questionText = query,
                limit = 5,
            )
            val retrievedIds = selected.map { it.knowledgeNodeId }
            val recalled = expectedIds.count { it in retrievedIds }
            totalRecall += recalled
            assertTrue(
                "Should recall at least one relevant node for '$query'",
                recalled > 0,
            )
        }

        val recallAt5 = totalRecall.toDouble() / queries.size
        assertTrue("Recall@5 should be at least 0.6", recallAt5 >= 0.6)
    }

    @Test
    fun `mrr rewards earlier relevant results`() {
        val candidates = createMathKnowledgeNodes()
        val query = "函数单调性"
        val expectedId = "read-monotonicity-from-graph"

        val selected = KnowledgeContextRetriever.select(
            candidates = candidates,
            questionText = query,
            limit = 5,
        )
        val retrievedIds = selected.map { it.knowledgeNodeId }
        val rank = retrievedIds.indexOf(expectedId) + 1

        assertTrue("Relevant node should be in top 5", rank in 1..5)
        val mrr = 1.0 / rank
        assertTrue("MRR should be at least 0.2", mrr >= 0.2)
    }

    @Test
    fun `grounding precision checks that retrieved nodes are actually relevant`() {
        val candidates = createMathKnowledgeNodes()
        val query = "求解方程"

        val selected = KnowledgeContextRetriever.select(
            candidates = candidates,
            questionText = query,
            limit = 3,
        )

        val relevantKinds = setOf(
            "solve-linear-equation",
            "solve-quadratic-equation",
            "verify-equation-solution",
        )
        val relevantCount = selected.count { it.knowledgeNodeId in relevantKinds }
        val precision = relevantCount.toDouble() / selected.size

        assertTrue("Grounding precision should be at least 0.5", precision >= 0.5)
    }

    @Test
    fun `unreviewed nodes are never retrieved`() {
        val candidates = createMathKnowledgeNodes() + listOf(
            node(
                id = "unreviewed-node",
                name = "未审核的知识点",
                granularity = KnowledgeNodeGranularity.ATOMIC,
                verificationStatus = KnowledgeNodeVerificationStatus.DRAFT,
            ),
        )

        val selected = KnowledgeContextRetriever.select(
            candidates = candidates,
            questionText = "任何问题",
            limit = 10,
        )

        assertTrue(
            "Unreviewed nodes should not be retrieved",
            selected.none { it.knowledgeNodeId == "unreviewed-node" },
        )
    }

    @Test
    fun `boundary mismatch reduces relevance score`() {
        val candidates = listOf(
            node(
                id = "topic-algebra",
                name = "代数",
                granularity = KnowledgeNodeGranularity.TOPIC,
            ),
            node(
                id = "atomic-equation-solving",
                name = "求解一元一次方程",
                granularity = KnowledgeNodeGranularity.ATOMIC,
                boundary = "只处理标准形式的一元一次方程",
            ),
        )

        val selected = KnowledgeContextRetriever.select(
            candidates = candidates,
            questionText = "解方程 2x + 3 = 7",
            limit = 2,
        )

        assertEquals(
            "Atomic node should be ranked first",
            "atomic-equation-solving",
            selected.first().knowledgeNodeId,
        )
    }

    private fun createMathKnowledgeNodes(): List<KnowledgeNodeSeedRecord> = listOf(
        node("topic-function", "函数性质", KnowledgeNodeGranularity.TOPIC),
        node(
            id = "read-monotonicity-from-graph",
            name = "借助函数图象判断单调区间",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-function",
            boundary = "只依据图象读取增减区间",
        ),
        node(
            id = "express-monotonicity-symbolically",
            name = "用符号语言表达函数单调性",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-function",
        ),
        node(
            id = "read-extrema-from-graph",
            name = "从函数图象识别最大值与最小值",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-function",
        ),
        node("topic-equation", "方程求解", KnowledgeNodeGranularity.TOPIC),
        node(
            id = "solve-linear-equation",
            name = "求解一元一次方程",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-equation",
            boundary = "只处理标准形式的一元一次方程",
        ),
        node(
            id = "solve-quadratic-equation",
            name = "求解一元二次方程",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-equation",
        ),
        node(
            id = "verify-equation-solution",
            name = "检验方程解的合理性",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-equation",
        ),
        node("topic-geometry", "几何图形", KnowledgeNodeGranularity.TOPIC),
        node(
            id = "identify-triangle-properties",
            name = "识别三角形的基本性质",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-geometry",
        ),
        node(
            id = "apply-pythagorean-theorem",
            name = "应用勾股定理求解问题",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-geometry",
        ),
        node(
            id = "calculate-circle-area-circumference",
            name = "计算圆的面积与周长",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-geometry",
        ),
        node("topic-statistics", "统计与概率", KnowledgeNodeGranularity.TOPIC),
        node(
            id = "calculate-mean-median-mode",
            name = "计算数据的平均数、中位数和众数",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-statistics",
        ),
        node(
            id = "interpret-statistical-graphs",
            name = "解读统计图表中的信息",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-statistics",
        ),
        node(
            id = "calculate-simple-probability",
            name = "计算简单事件的概率",
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentId = "topic-statistics",
        ),
    )

    private fun node(
        id: String,
        name: String,
        granularity: KnowledgeNodeGranularity,
        parentId: String? = null,
        aliases: Set<String> = emptySet(),
        boundary: String? = null,
        verificationStatus: KnowledgeNodeVerificationStatus = KnowledgeNodeVerificationStatus.APPROVED,
    ): KnowledgeNodeSeedRecord = KnowledgeNodeSeedRecord(
        knowledgeNodeId = id,
        subject = "MATH",
        granularity = granularity.name,
        displayName = name,
        aliases = aliases,
        boundary = boundary,
        kind = KnowledgeNodeKind.CONCEPT.name,
        sourceLocator = "test",
        verificationStatus = verificationStatus.name,
        parentKnowledgeNodeId = parentId,
    )
}
