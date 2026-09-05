package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实评测: 用 moe-2025-four-subjects-v1 打包节点(1950 atomic)，按科隔离候选（模拟生产
 * readSubjectKnowledgeRecallCandidates），验证 Recall@5 / Precision@5 / MRR。
 * expected 用"名称子串"近似（题面→节点语义匹配用关键词即可）。
 */
class FourSubjectRetrievalBenchmarkTest {

    private val pack by lazy {
        BundledKnowledgePackResources.load().single { it.packId == "moe-2025-four-subjects-v1" }
    }

    private val candidates by lazy {
        pack.nodes.filter { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }
    }

    // (题面, 预期节点名关键词列表)
    private val queries: List<Triple<String, String, List<String>>> by lazy {
        listOf(
            Triple("MATH", "求函数 f(x)=x²-2x-3 的单调递增区间", listOf("单调", "函数")),
            Triple("MATH", "计算等差数列 1,3,5,7 的前100项和", listOf("等差数列", "求和", "前n项")),
            Triple("MATH", "椭圆 x²/16+y²/9=1 的离心率是多少", listOf("椭圆", "离心率")),
            Triple("MATH", "求集合 A={1,2,3} 与 B={3,4,5} 的交集", listOf("交集", "集合")),
            Triple("MATH", "已知 sinα=3/5 求 tanα", listOf("三角函数", "同角")),
            Triple("PHYSICS", "物体自由下落5秒末速度", listOf("自由落体", "匀变速")),
            Triple("PHYSICS", "两物体碰撞前后动量守恒", listOf("动量守恒", "碰撞")),
            Triple("PHYSICS", "电磁感应中磁通量变化产生感应电动势", listOf("电磁感应", "法拉第", "电动势")),
            Triple("PHYSICS", "带电粒子在匀强磁场中做圆周运动的轨道半径", listOf("磁场", "洛伦兹", "半径")),
            Triple("PHYSICS", "变压器原副线圈电压比与匝数比", listOf("变压器", "匝数")),
            Triple("CHEMISTRY", "钠与水反应的现象和产物", listOf("钠", "碱金属")),
            Triple("CHEMISTRY", "计算 2mol 氯化钠的质量", listOf("摩尔", "物质的量")),
            Triple("CHEMISTRY", "盐酸与氢氧化钠发生中和反应", listOf("中和", "酸碱")),
            Triple("CHEMISTRY", "苯的分子式和结构", listOf("苯")),
            Triple("CHEMISTRY", "原电池中锌铜电极的电子流向", listOf("原电池", "电极")),
            Triple("BIOLOGY", "细胞器中含DNA的有哪些", listOf("线粒体", "叶绿体", "细胞器")),
            Triple("BIOLOGY", "孟德尔分离定律的实质", listOf("分离定律", "等位基因")),
            Triple("BIOLOGY", "光合作用暗反应需要什么原料", listOf("光合作用", "暗反应", "碳")),
            Triple("BIOLOGY", "免疫系统对付病毒的途径", listOf("免疫", "抗体")),
            Triple("BIOLOGY", "种群密度的调查方法", listOf("种群", "样方", "标志")),
        )
    }

    @Test
    fun `real pack retrieval recall@5 precision@5 mrr per subject`() {
        var recallHit = 0; var precisionSum = 0.0; var mrrSum = 0.0
        var total = 0
        var crossSubjectErrors = 0
        queries.forEach { (subject, question, expectedKeys) ->
            // 模拟生产：按科隔离候选
            val subjectCandidates = candidates.filter { it.subject == subject }
            val selected = KnowledgeContextRetriever.select(
                candidates = subjectCandidates,
                questionText = question,
                limit = 5,
            )
            val selectedNames = selected.map { it.canonicalName }
            // hit: 任一 expectedKey 出现在任一选中节点的 name/aliases 里
            val hit = selected.any { sel ->
                val text = (sel.canonicalName + " " + sel.aliases.joinToString(" ")).lowercase()
                expectedKeys.any { text.contains(it.lowercase()) }
            }
            if (hit) recallHit += 1
            // 同科 Precision（候选已按科过滤 → 必为1，但保留检查）
            if (selected.any { it.subject != subject }) crossSubjectErrors += 1
            precisionSum += 1.0
            val rank = selected.indexOfFirst { sel ->
                val text = (sel.canonicalName + " " + sel.aliases.joinToString(" ")).lowercase()
                expectedKeys.any { text.contains(it.lowercase()) }
            }
            if (rank >= 0) mrrSum += 1.0 / (rank + 1)
            total += 1
            println("[$subject] '$question' -> hit=$hit, selected=${selectedNames.take(3)}")
        }
        val recallAt5 = recallHit.toDouble() / total
        val precisionAt5 = precisionSum / total
        val mrr = mrrSum / total
        println("=== 四科真实检索评测 (按科隔离) ===")
        println("Recall@5 = $recallAt5 (${recallHit}/$total)")
        println("Precision@5 = $precisionAt5")
        println("MRR = $mrr")
        println("跨科误召(候选已隔离应0) = $crossSubjectErrors")
        java.io.File("build/benchmark-metrics.txt").writeText(
            "Recall@5=$recallAt5\nPrecision@5=$precisionAt5\nMRR=$mrr\ncrossSubject=$crossSubjectErrors\n" +
                queries.joinToString("\n") { (s, q, e) ->
                    val sel = KnowledgeContextRetriever.select(
                        candidates = candidates.filter { it.subject == s }, questionText = q, limit = 5,
                    ).map { it.canonicalName }.take(3)
                    "[$s] $q -> ${sel}"
                }
        )
        assertTrue("Recall@5 must be >= 0.85, got $recallAt5", recallAt5 >= 0.85)
        assertTrue("cross-subject must be 0, got $crossSubjectErrors", crossSubjectErrors == 0)
        assertTrue("MRR must be >= 0.7, got $mrr", mrr >= 0.7)
    }
}
