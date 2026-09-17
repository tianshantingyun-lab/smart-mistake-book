package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity

/**
 * 四科检索评测的**同一套题面与同一套口径**，供两个基准共用：
 * `FourSubjectRetrievalBenchmarkTest`（读打进包的成品包）与
 * `StagingPackRetrievalBenchmarkTest`（读 `tools/kb_build` 生成到 `build/kb-staging` 的暂存包）。
 *
 * 抽出来是为了**避免两套查询各自漂移**——两份题面一旦分叉，两个分数就没法比，
 * 而这一轮所有取舍（别名放哪几档、边界过滤留什么）都要靠这两个分数对照着裁决。
 *
 * **2026-09-14 两处修改，都是把度量对准真实问题（不是放宽门）**：
 *
 * 1. `Precision@5` 此前**恒等于 1.0**：计分处写的是 `precisionSum += 1.0`，与查询内容无关，
 *    所以它不携带任何信息——与内核审计点名的「三条断言恒真」属同一类。现在改为
 *    「前 5 名里真正命中的占比」，它才开始能随候选质量变化。
 * 2. 新增 [chapterQueries]：原 20 条**不覆盖正在逐章补材料的章节**，于是"补了这一章、分数不动"，
 *    取舍只能凭感觉。新题面的预期节点全部取自这些章里已绑定材料的节点。
 *    它**不参与** `FourSubjectRetrievalBenchmarkTest` 的阈值断言——那道门的口径**保持不变**；
 *    要不要把它并进门槛，是将来一次有数据支撑的单独决定。两个基准都会把它跑出来并写进指标文件。
 */
internal object RetrievalBenchmark {

    /** (科目, 题面, 预期节点名关键词)。`expected` 用"名称/别名子串"近似题面到节点的语义匹配。 */
    val queries: List<Triple<String, String, List<String>>> = listOf(
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

    /**
     * 覆盖"逐章补材料"那几章的题面。预期关键词都取自这些章里**已经绑定材料**的节点名，
     * 所以它们对"材料标题 → 别名 → 召回"这条链敏感：只有别名表按当前绑定重建过，
     * 这些题面才可能命中。这正是"五三炼化"这一线唯一能看见成效的地方。
     */
    val chapterQueries: List<Triple<String, String, List<String>>> = listOf(
        Triple("CHEMISTRY", "弱电解质在水溶液里是不是完全电离", listOf("弱电解质")),
        Triple("CHEMISTRY", "醋酸加水稀释时电离平衡往哪边移动", listOf("电离平衡")),
        Triple("CHEMISTRY", "碳酸钠溶液为什么显碱性", listOf("盐类水解")),
        Triple("CHEMISTRY", "怎么判断溶液里有没有沉淀生成", listOf("溶度积")),
        Triple("CHEMISTRY", "中和滴定的滴定曲线怎么读起点和突变", listOf("滴定曲线")),
        Triple("CHEMISTRY", "银镜反应是检验醛基的吗", listOf("醛")),
        Triple("CHEMISTRY", "酰胺在强碱里加热会水解吗", listOf("酰胺")),
        Triple("CHEMISTRY", "铁在氯气里燃烧生成几价铁", listOf("铁的化学性质")),
        Triple("CHEMISTRY", "实验室怎么检验铵根离子", listOf("铵盐")),
        Triple("CHEMISTRY", "葡萄糖和果糖都是单糖吗", listOf("单糖")),
        Triple("CHEMISTRY", "蛋白质遇浓硝酸为什么会变黄", listOf("蛋白质")),
        Triple("PHYSICS", "查德威克是怎么发现中子的", listOf("中子")),
        Triple("PHYSICS", "铀核裂变会放出几个中子", listOf("核裂变")),
        Triple("PHYSICS", "太阳发光靠的是哪一种核反应", listOf("核聚变")),
        Triple("PHYSICS", "比结合能越大原子核就越稳定吗", listOf("结合能")),
    )

    data class Result(
        /** 本次计分用的题面——`report`/`metricsFile` 必须与判分**用同一份**，否则行数会错位。 */
        val queries: List<Triple<String, String, List<String>>>,
        val recallHit: Int,
        val total: Int,
        val precisionAt5: Double,
        val mrr: Double,
        val crossSubjectErrors: Int,
        /** 每题是否命中。**与判分是同一次计算**，不要在外部按候选名重推一遍。 */
        val hits: List<Boolean>,
        /** 每题命中的前 3 个候选名（写入指标文件，与判分用的是同一次选择）。 */
        val ranked: List<List<String>>,
    ) {
        val recallAt5: Double get() = recallHit.toDouble() / total
    }

    /** 守门用的口径（原 20 条）。 */
    fun run(candidates: List<KnowledgeNodeSeedRecord>): Result = evaluate(candidates, queries)

    /** 覆盖在飞章节的口径（见 [chapterQueries]），当前只用于测量报告。 */
    fun runChapterQueries(candidates: List<KnowledgeNodeSeedRecord>): Result =
        evaluate(candidates, chapterQueries)

    /**
     * 按科隔离候选（模拟生产 `readSubjectKnowledgeRecallCandidates`），逐题取前 5。
     * 命中口径：任一预期关键词出现在任一选中节点的 名称/别名 里。
     * Precision@5 = 平均「前 5 名里命中节点所占的比例」。
     */
    private fun evaluate(
        candidates: List<KnowledgeNodeSeedRecord>,
        queries: List<Triple<String, String, List<String>>>,
    ): Result {
        var recallHit = 0
        var precisionSum = 0.0
        var mrrSum = 0.0
        var crossSubjectErrors = 0
        val hits = ArrayList<Boolean>(queries.size)
        val ranked = ArrayList<List<String>>(queries.size)
        queries.forEach { (subject, question, expectedKeys) ->
            val subjectCandidates = candidates.filter { it.subject == subject }
            val selected = KnowledgeContextRetriever.select(
                candidates = subjectCandidates,
                questionText = question,
                limit = 5,
            )
            ranked.add(selected.map { it.canonicalName }.take(3))
            fun matches(name: String, aliases: Set<String>): Boolean {
                val text = (name + " " + aliases.joinToString(" ")).lowercase()
                return expectedKeys.any { text.contains(it.lowercase()) }
            }
            val hitCount = selected.count { matches(it.canonicalName, it.aliases) }
            val hit = hitCount > 0
            hits.add(hit)
            if (hit) recallHit += 1
            if (selected.any { it.subject != subject }) crossSubjectErrors += 1
            precisionSum += if (selected.isEmpty()) 0.0 else hitCount.toDouble() / selected.size
            val rank = selected.indexOfFirst { matches(it.canonicalName, it.aliases) }
            if (rank >= 0) mrrSum += 1.0 / (rank + 1)
        }
        return Result(
            queries = queries,
            recallHit = recallHit,
            total = queries.size,
            precisionAt5 = precisionSum / queries.size,
            mrr = mrrSum / queries.size,
            crossSubjectErrors = crossSubjectErrors,
            hits = hits,
            ranked = ranked,
        )
    }

    /** 控制台输出。`title` 让两份报告的抬头可区分，其余逐字与原先一致。 */
    fun report(title: String, result: Result): String = buildString {
        result.queries.forEachIndexed { index, (subject, question, _) ->
            appendLine("[$subject] '$question' -> hit=${result.hits[index]}, selected=${result.ranked[index]}")
        }
        appendLine("=== $title ===")
        appendLine("Recall@5 = ${result.recallAt5} (${result.recallHit}/${result.total})")
        appendLine("Precision@5 = ${result.precisionAt5}")
        appendLine("MRR = ${result.mrr}")
        appendLine("跨科误召(候选已隔离应0) = ${result.crossSubjectErrors}")
    }

    /** 指标文件内容。格式与 `build/benchmark-metrics.txt` 原先逐字一致，便于两份直接对照。 */
    fun metricsFile(result: Result): String =
        "Recall@5=${result.recallAt5}\nPrecision@5=${result.precisionAt5}\nMRR=${result.mrr}\n" +
            "crossSubject=${result.crossSubjectErrors}\n" +
            result.queries.mapIndexed { index, (subject, question, _) ->
                "[$subject] $question -> ${result.ranked[index]}"
            }.joinToString("\n")

    /** 只取原子节点做候选——与两个基准原先的取法一致。 */
    fun atomicNodes(nodes: List<KnowledgeNodeSeedRecord>): List<KnowledgeNodeSeedRecord> =
        nodes.filter { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }
}
