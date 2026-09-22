package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSearchFeatureExtractorTest {
    @Test
    fun `question and reviewed node share Chinese phrase features`() {
        val node = KnowledgeNodeSeedRecord(
            knowledgeNodeId = "knowledge:math:monotonicity",
            stableCode = "math:monotonicity",
            subject = "MATH",
            displayName = "判断函数单调性",
            parentKnowledgeNodeId = "topic:function",
            taxonomyVersion = "test-v1",
            createdAtEpochMillis = 1,
            canonicalName = "根据导数符号判断函数单调区间",
            nodeKind = KnowledgeNodeKind.REASONING.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            aliases = setOf("导数与单调性"),
            boundaryMarkdown = "判断原函数在哪些区间递增或递减。",
            verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
        )

        val overlap = KnowledgeSearchFeatureExtractor.fromNode(node) intersect
            KnowledgeSearchFeatureExtractor.fromQuestion(
                "已知导函数的符号变化，求原函数的单调递增区间。",
            )

        assertTrue(overlap.contains("单调"))
        assertTrue(overlap.contains("函数"))
        assertTrue(overlap.isNotEmpty())
    }

    @Test
    fun `query feature count is bounded`() {
        val features = KnowledgeSearchFeatureExtractor.fromQuestion("知识点检索".repeat(1_000))

        assertTrue(features.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES)
    }

    @Test
    fun `long multi-part question keeps concepts from the final paragraph`() {
        val earlyParagraphs = List(200) { index ->
            "第${index}段给出与计算无关的背景材料和条件说明。"
        }
        val question = (earlyParagraphs + "最后请根据导数符号判断函数的单调区间。")
            .joinToString("\n")

        val features = KnowledgeSearchFeatureExtractor.fromQuestion(question)

        assertTrue("Tail concept was dropped from a bounded query", "导数" in features)
        assertTrue("Tail concept was dropped from a bounded query", "单调" in features)
        assertTrue(features.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES)
    }

    @Test
    fun `feature extraction is deterministic`() {
        val question = "先阅读材料。\n再根据图象判断函数的单调区间。"

        assertEquals(
            KnowledgeSearchFeatureExtractor.fromQuestion(question),
            KnowledgeSearchFeatureExtractor.fromQuestion(question),
        )
    }

    @Test
    fun `node side keeps the v1 truncation on a 178 fragment node`() {
        // 178 = 随包 moe-2025 包里实测单节点最大片段数（审计 2026-09-21 复算）。
        // v1 形状（2026-09-22 的 v2 去截断实验实测对词面路由净伤害、当日回滚后钉住，
        // 见 KD-24 与 docs/kb-vector-topic-decision.md §3.2）：178 片段只选 16 个入预算
        // （末段 + 首段 + 均衡中间段），特征总数封顶 192。若 v2 式全量抽取回来，
        // 特征数会冲破 192、未选中片段（如第 50/123 个）会整体入特征——两条都红。
        // 均衡选择（balancedStartIndexes 中点递归、中点队列）在这个夹具下选中的片段
        // 索引是 {0,10,21,32,43,54,65,76,88,99,110,121,133,144,155,177}（已用与
        // Kotlin 逐句一致的 Python 端口独立算出）：首段（规范名，索引 0）与末段
        // （索引 177 = 排序后最后一个别名 别名词177号）必在，未选中片段（如索引
        // 50/123）必不在。片段索引 i≥1 = 第 i 个别名（tokens[i-1]）。
        val tokens = (1..177).map { index -> "别名词${index.toString().padStart(3, '0')}号" }
        val node = KnowledgeNodeSeedRecord(
            knowledgeNodeId = "knowledge:math:many-fragments",
            stableCode = "math:many-fragments",
            subject = "MATH",
            displayName = "多片段压力节点",
            parentKnowledgeNodeId = null,
            taxonomyVersion = "test-v1",
            createdAtEpochMillis = 1,
            canonicalName = "第一个片段就是规范名",
            nodeKind = KnowledgeNodeKind.REASONING.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            aliases = tokens.toSet(),
            boundaryMarkdown = null,
            verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
        )

        val features = KnowledgeSearchFeatureExtractor.fromNode(node)

        assertTrue(
            "Canonical fragment (fragment 1 of 178) must be indexed",
            "第一个片段就是规范名" in features,
        )
        assertTrue(
            "Selected tail fragment (alias 177) must be indexed",
            "别名词177号" in features,
        )
        assertTrue(
            "Selected balanced middle fragment (index 21 → alias 21) must be indexed",
            "别名词021号" in features,
        )
        assertTrue(
            "Selected balanced middle fragment (index 144 → alias 144) must be indexed",
            "别名词144号" in features,
        )
        assertTrue(
            "Fragment index 50 is not in the balanced 16-selection: its whole string must stay out",
            "别名词050号" !in features,
        )
        assertTrue(
            "Fragment index 123 is not in the balanced 16-selection: its whole string must stay out",
            "别名词123号" !in features,
        )
        assertTrue(
            "Node feature count must stay under the v1 cap of 192, got ${features.size}",
            features.size <= 192,
        )
    }

    @Test
    fun `query side fragment selection stays bounded on long questions`() {
        // 查询侧行为钉住：>16 片段长题面仍只保留 末段+首段+均衡中间段，特征 ≤128。
        // 查询侧口径一变，FourSubject 守门分数就失去可比性（D-04）；v2 实验与本次
        // 回滚都只动节点侧，查询侧自始至终是这一档。
        val earlyParagraphs = List(60) { index ->
            "第${index}段是背景说明甲${index.toString().padStart(2, '0')}号。"
        }
        val question = (earlyParagraphs + "最后问导数与单调区间。").joinToString("\n")

        val features = KnowledgeSearchFeatureExtractor.fromQuestion(question)

        assertTrue("Tail concept kept", "导数" in features)
        assertTrue(features.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES)
        // 背景段 50 既非首段也非末段，均衡选择（16 片段）不覆盖它：
        // 若查询侧片段选择被破坏（全量片段入预算），整个背景片段会作为
        // 2..48 字片段整体进特征——它缺席即证明选择约束仍在。
        assertTrue(
            "Background fragment 50 must not leak into the bounded query",
            "第50段是背景说明甲50号" !in features,
        )
    }
}
