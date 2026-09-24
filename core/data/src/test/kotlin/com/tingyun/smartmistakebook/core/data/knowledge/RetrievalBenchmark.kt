package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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

    // ---------------------------------------------------------------------
    // 金标集（tools/kb_coverage/tables/golden_queries_v1.json，sha256 冻结）的
    // 双路评测：A 路精排（参考上界）/ B 路镜像（生产 v1 形状：裸 B 路 limit=5、
    // 返回形态 = matched 优先（D1 落地后），2026-09-22 的 B512→A 统一路由实验实测净伤害、
    // 当日回滚）。口径写死在这里，测试文件只负责取数与落盘。
    // ---------------------------------------------------------------------

    /** 金标集一条：题面 + 预期节点 slug + 所属章。字段与冻结 JSON 逐字对应。 */
    data class GoldenCase(
        val chapter: String,
        val expectedSlug: String,
        val query: String,
        val subject: String,
    )

    /** 单条金标在一条路线上的判分结果。 */
    data class GoldenScore(
        val case: GoldenCase,
        val hit: Boolean,
        /** 预期节点在 top-5 内的 1 起始名次；未命中为 0。 */
        val rank: Int,
        /** 前 3 名候选的规范名（落盘用，与判分同一次计算）。 */
        val top3: List<String>,
    )

    /** 一条路线跑完全套金标的汇总。 */
    data class GoldenRouteResult(
        val route: String,
        val scores: List<GoldenScore>,
    ) {
        val total: Int get() = scores.size
        val hits: Int get() = scores.count { it.hit }
        val recallAt5: Double get() = if (total == 0) 0.0 else hits.toDouble() / total
        val mrr: Double get() =
            if (total == 0) 0.0 else scores.sumOf { if (it.rank > 0) 1.0 / it.rank else 0.0 } / total
        /** 逐章 Recall@5（章 → 命中率），按金标集自身顺序。 */
        val byChapter: List<Pair<String, Double>> get() =
            scores.groupBy { it.case.chapter }.entries.map { (chapter, list) ->
                chapter to list.count { it.hit }.toDouble() / list.size
            }
    }

    /**
     * MISS 诊断的**放宽深度**——不是判分口径。
     *
     * 判分固定 top-5（[SCORED_TOP_K]，口径不得动）；这里只回答"再放宽看，预期节点排到第几、
     * 还是在候选里根本不存在"。这一个数把 D-01 的两种病因分开：
     * **rank=名次** = 在候选里、只是被更强的候选挤下去（排序问题，IDF/长度归一化能救）；
     * **rank=absent** = 该深度内根本没有（索引/特征问题，排序怎么调都救不回来）。
     *
     * **名次口径**（两侧必须逐字相同才可比）：把该路线的候选放宽到本深度后，取**返回序列
     * （matched 优先、父节点随后）的前 [MISS_PROBE_LIMIT] 名**当诊断窗口，名次是窗口内的 1 起始位置。
     * 判分用的 top-5 结果**不从这里取**，所以放宽不会污染任何已出的分数。
     *
     * **跨侧对照纪律**（2026-09-23 实测）：两侧 MISS **集合**逐题一致（对称差 0）；**名次**
     * 可差 1~3 位——父节点随后块的条数与序不同（镜像按包内出现序、真 SQL 按 rowid 序）会让
     * 窗口内位置整体位移。所以集合是两侧对齐的判据，名次只在本侧窗口内解释，不跨侧当同一口径。
     */
    const val MISS_PROBE_LIMIT = 256

    /** 一条未命中 top-5 的题 + 它的放宽诊断。 */
    data class GoldenMiss(
        val case: GoldenCase,
        /** 放宽到 [MISS_PROBE_LIMIT] 后预期节点的 1 起始名次；0 = absent（该深度内不存在）。 */
        val probeRank: Int,
    )

    /**
     * 逐题 MISS 清单（`query` + `expectedSlug` + `rank|absent` 三件套）。
     *
     * 只对 [GoldenRouteResult.scores] 里未命中的题调 [probe]（已命中的题不进清单，
     * 免得清单被"命中的题"稀释）。[probe] 收到 `(题, 深度)`，返回该路线的候选序列——
     * 判分用的 top-5 结果**不从这里取**，所以放宽不会污染任何已出过的分数。
     * 名次取**返回序列的前 [MISS_PROBE_LIMIT] 名**内的位置（`take` 不能省：store 的返回形态
     * 是"matched 优先 + 父节点随后"，序列本身可以长过深度，不截断就会报出超过声明深度的名次）。
     */
    fun goldenMisses(
        result: GoldenRouteResult,
        probe: (GoldenCase, Int) -> List<KnowledgeNodeSeedRecord>,
    ): List<GoldenMiss> = result.scores.filterNot { it.hit }.map { score ->
        val window = probe(score.case, MISS_PROBE_LIMIT).take(MISS_PROBE_LIMIT)
        val probeRank = window.indexOfFirst {
            it.knowledgeNodeId.endsWith(ATOMIC_ID_SUFFIX + score.case.expectedSlug)
        }.let { if (it < 0) 0 else it + 1 }
        GoldenMiss(score.case, probeRank)
    }

    /**
     * MISS 清单的渲染。**JVM 落盘与仪表化日志用同一格式**（纯字符串拼接：
     * 仪表化侧实测过模板插值渲染异常，两侧都避开插值，日志才能逐行对照）。
     */
    fun goldenMissLines(misses: List<GoldenMiss>): List<String> =
        if (misses.isEmpty()) {
            listOf("  （无 MISS：本路线 top-5 全命中）")
        } else {
            misses.map { miss ->
                "  MISS [" + miss.case.subject + "] " + miss.case.query +
                    " | expectedSlug=" + miss.case.expectedSlug +
                    " | chapter=" + miss.case.chapter +
                    " | rank=" + (if (miss.probeRank > 0) miss.probeRank.toString() else "absent")
            }
        }

    /** 逐章分布行（命中/总数 = Recall@5 + 该章 MISS 数），两侧同格式。 */
    fun goldenChapterLines(result: GoldenRouteResult): List<String> =
        result.byChapter.map { (chapter, value) ->
            val chapterScores = result.scores.filter { it.case.chapter == chapter }
            "  " + chapter + " = " + chapterScores.count { it.hit } + "/" + chapterScores.size +
                " = " + value + " (MISS " + chapterScores.count { !it.hit } + ")"
        }

    /**
     * 金标集的**唯一解析入口**（JVM 测量台与 Stage-1 实验台共用这一份）。
     *
     * "两处各解析一遍"是本评测台真实存在的失败类：同一份冻结题面被两处读成两种含义时，
     * 两个分数就没法比，而两边都"看起来正常"。字段集与非空字符串在这里一次钉死。
     */
    fun loadGoldenCases(goldenFile: File): List<GoldenCase> =
        (json.parseToJsonElement(goldenFile.readText(Charsets.UTF_8)) as JsonArray).map { element ->
            val value = element.jsonObject
            require(value.keys == GOLDEN_FIELD_NAMES) {
                "金标集字段漂移：${value.keys.sorted()}"
            }
            GoldenCase(
                chapter = value.stringField("chapter"),
                expectedSlug = value.stringField("expectedSlug"),
                query = value.stringField("query"),
                subject = value.stringField("subject"),
            )
        }

    /** 冻结金标集的 sha256（十六进制小写）——封存值复核（`.sha256` 文件）用。 */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val json = Json { ignoreUnknownKeys = false }
    private val GOLDEN_FIELD_NAMES = setOf("query", "expectedSlug", "chapter", "subject")

    private fun JsonObject.stringField(name: String): String {
        val primitive = this[name] as? JsonPrimitive ?: error("金标集字段 $name 不是字符串")
        val content = primitive.contentOrNull ?: error("金标集字段 $name 不是字符串")
        require(content.isNotBlank()) { "金标集字段 $name 为空" }
        return content
    }

    /** 判分口径：预期 slug 的原子节点是否落在该路线返回的前 [SCORED_TOP_K] 名。 */
    fun scoreGolden(
        routeName: String,
        cases: List<GoldenCase>,
        route: (GoldenCase) -> List<KnowledgeNodeSeedRecord>,
    ): GoldenRouteResult {
        val scores = cases.map { case ->
            val selected = route(case).take(SCORED_TOP_K)
            val rank = selected.indexOfFirst {
                it.knowledgeNodeId.endsWith(ATOMIC_ID_SUFFIX + case.expectedSlug)
            }.let { if (it < 0) 0 else it + 1 }
            GoldenScore(case, rank > 0, rank, selected.map { it.canonicalName }.take(3))
        }
        return GoldenRouteResult(route = routeName, scores = scores)
    }

    private const val SCORED_TOP_K = 5
    private const val ATOMIC_ID_SUFFIX = ":atomic:"

    /**
     * 生产 B 路的**可信状态过滤集**（`ProblemOrganizationDao.searchSubjectKnowledgeRecallCandidates`
     * 的 `verification_status IN (...)`）。抽成一份给所有金标路线共用：镜像与 Stage-1 各臂
     * 各自写一遍，就等于"候选池"这一层可以悄悄分叉而没人发现（§2 共用口径：候选过滤一致）。
     */
    val TRUSTED_VERIFICATION_STATUSES: Set<String> = setOf(
        KnowledgeNodeVerificationStatus.CURATED.name,
        KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
        KnowledgeNodeVerificationStatus.USER_CONFIRMED.name,
    )

    /** 空查询特征时生产 B 路的回退顺序（store 的 `readSubjectKnowledgeNodes` 排序，limit 压到 256）。 */
    fun emptyQueryFallbackOrder(trusted: List<KnowledgeNodeSeedRecord>): List<KnowledgeNodeSeedRecord> =
        trusted.sortedWith(
            compareBy(
                { verificationOrder(it.verificationStatus) },
                { if (it.granularity == KnowledgeNodeGranularity.ATOMIC.name) 0 else 1 },
                { it.canonicalName },
                { it.knowledgeNodeId },
            ),
        ).take(EMPTY_QUERY_FALLBACK_LIMIT)

    private const val EMPTY_QUERY_FALLBACK_LIMIT = 256

    private fun verificationOrder(status: String): Int = when (status) {
        KnowledgeNodeVerificationStatus.CURATED.name -> 0
        KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name -> 1
        else -> 2
    }

    /**
     * **B 路（生产 SQL 倒排）的 JVM 镜像**——SQL 排序的 JVM 镜像，真 SQL 以仪表化为准。
     *
     * 逐条对齐 `RoomKnowledgeBaseStore.readSubjectKnowledgeRecallCandidates` 的生产语义：
     * - 索引内容 = `KnowledgeSearchFeatureExtractor.fromNode` 的真实输出（与
     *   `toSearchFeatures` 写库的同一份），按 `subject + search_feature` 二值 TF；
     * - 候选过滤 = `verification_status IN (CURATED, SOURCE_GROUNDED, USER_CONFIRMED)`
     *   且非退役（`ProblemOrganizationDao.searchSubjectKnowledgeRecallCandidates`）；
     * - 排序 = `ORDER BY COUNT(DISTINCT search_feature) DESC,
     *   CASE granularity WHEN 'ATOMIC' THEN 0 ELSE 1 END, canonical_name ASC,
     *   knowledge_node_id ASC`（与 DAO 逐字一致）；
     * - 空查询特征 = 回退 `readSubjectKnowledgeNodes` 的顺序（verification CASE →
     *   granularity → canonical_name → id，limit 压到 256）；
     * - 返回 = `matched + parents`（**D1 落地后的生产形态**（2026-09-24）：matched 在前、
     *   匹配节点的父 topic 随后（供上层解释）——生产 store 的真实返回形态，
     *   父节点在生产的行序是 rowid 序，镜像按包内出现序取，保证 JVM 侧确定性可复现）。
     *   另有两个**只作诊断**的返回形态：[parentsFirst]（D1 落地前的旧生产形态：父 topic 前置，
     *   只作历史记账/诊断）与 [matchedOnly]（不注入父节点——各臂判分数所在的口径）。
     *   排序与候选过滤三者逐字相同。
     *
     * 它回答的问题：生产 v1 形状（裸 B 路 limit=5，返回形态 = matched 优先，SQL 倒排二值 TF 排序）在 JVM 上
     * 保真到什么程度——[goldenBRoute] 在它上面跑同一判分口径（top-5 裸排序），与
     * 真 SQL 仪表化（GoldenRetrievalInstrumentedTest）逐题对照。B512→A 统一路由实验
     * 2026-09-22 实测净伤害已回滚（其数封存于 docs/kb-vector-topic-decision.md §3.1 /
     * KD-24）。索引内容跟随 [KnowledgeSearchFeatureExtractor.fromNode] 的当前规则
     * （回滚后 = v1 截断），A/B 两条数必须**同一次构建、同一份包、同一套题面**才有
     * 可比性（P3 的教训）。
     */
    class BRouteMirrorIndex(nodes: List<KnowledgeNodeSeedRecord>) {
        private val nodeFeatures: Map<String, Set<String>> =
            nodes.associate { it.knowledgeNodeId to KnowledgeSearchFeatureExtractor.fromNode(it) }
        private val nodesInPackOrder = nodes

        val totalFeatureRows: Long = nodeFeatures.values.sumOf { it.size.toLong() }
        val maxFeaturesPerNode: Int = nodeFeatures.values.maxOfOrNull { it.size } ?: 0

        /**
         * **生产 store 的返回形态（D1 已落地）**：`.distinctBy(id)` 后的 `matched + parents`。
         *
         * 2026-09-24 D1 之前这里是 `parents + matched`（父 topic 前置）；改动只换序列位置，
         * 集合与长度语义不变（生产侧同样不加 `take`）。旧形态留在 [parentsFirst] 作历史记账。
         */
        fun recall(subject: String, questionText: String, limit: Int): List<KnowledgeNodeSeedRecord> {
            val queryFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(questionText)
            if (queryFeatures.isEmpty()) {
                // 镜像 store 的空特征回退（readSubjectKnowledgeNodes 的顺序）：生产此时不走倒排、也不注入父节点
                return emptyQueryFallbackOrder(trustedPool(subject)).take(limit)
            }
            val matched = matchedRanked(subject, queryFeatures, limit)
            return (matched + parentsOf(matched)).distinctBy { it.knowledgeNodeId }
        }

        /**
         * **旧生产形态（D1 落地前，parents + matched）**——**不是当前生产形态，只作历史记账/诊断**：
         * §10 口径对照表与预注册基线 0.5444 钉的是这一形状的数，D1 之后仍要能复算出同一个值。
         * 排序与候选过滤与 [recall] 逐字相同，差别只在父 topic 是否挤占返回序列前部。
         */
        fun parentsFirst(subject: String, questionText: String, limit: Int): List<KnowledgeNodeSeedRecord> {
            val queryFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(questionText)
            if (queryFeatures.isEmpty()) {
                return emptyQueryFallbackOrder(trustedPool(subject)).take(limit)
            }
            val matched = matchedRanked(subject, queryFeatures, limit)
            return (parentsOf(matched) + matched).distinctBy { it.knowledgeNodeId }
        }

        /**
         * **matched-only 口径**（**不是生产判分口径**，只作诊断）：只返回排序后的 matched 序列，
         * **不注入父节点**。它是"排序本身有多好"的这一维——Stage-1 各臂的判分数（如臂 A 的
         * 0.6556）都出自这个口径，而生产 store 返回的是 [recall] 的 `matched + parents`。
         */
        fun matchedOnly(subject: String, questionText: String, limit: Int): List<KnowledgeNodeSeedRecord> {
            val queryFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(questionText)
            return if (queryFeatures.isEmpty()) {
                emptyQueryFallbackOrder(trustedPool(subject)).take(limit)
            } else {
                matchedRanked(subject, queryFeatures, limit)
            }
        }

        private fun trustedPool(subject: String): List<KnowledgeNodeSeedRecord> =
            nodesInPackOrder.filter {
                it.subject == subject && it.verificationStatus in TRUSTED_VERIFICATION_STATUSES
            }

        /**
         * 生产 v1 排序的**全部带分候选**：`node → COUNT(DISTINCT feature)`，顺序 = 生产 SQL 的
         * `ORDER BY COUNT(DISTINCT search_feature) DESC,` ATOMIC 优先, `canonical_name ASC,
         * knowledge_node_id ASC`；只含 `count > 0` 的行（= SQL 里 INNER JOIN 的命中集）。
         *
         * **为什么它是 public**：生产词面腿的分数要导出给离线融合当输入（Stage-3 参考数，
         * `build/production-lexical-leg.tsv`）。若导出侧自己再写一遍"计数 + 排序"，就等于
         * 多出一份会悄悄漂移的第二实现——[matchedRanked] 与导出走的必须是这**同一份**。
         * 它不改变任何既有判分数（[matchedRanked] 只是它 `take(limit)` 的投影）。
         */
        fun matchedScored(
            subject: String,
            queryFeatures: Set<String>,
        ): List<Pair<KnowledgeNodeSeedRecord, Int>> = trustedPool(subject)
            .map { node -> node to nodeFeatures.getValue(node.knowledgeNodeId).count { it in queryFeatures } }
            .filter { entry -> entry.second > 0 }
            .sortedWith(
                compareByDescending<Pair<KnowledgeNodeSeedRecord, Int>> { it.second }
                    .thenBy { entry ->
                        if (entry.first.granularity == KnowledgeNodeGranularity.ATOMIC.name) 0 else 1
                    }
                    .thenBy { entry -> entry.first.canonicalName }
                    .thenBy { entry -> entry.first.knowledgeNodeId },
            )

        /**
         * 生产 SQL 的排序镜像（`ORDER BY COUNT(DISTINCT search_feature) DESC,` ATOMIC 优先,
         * `canonical_name ASC, knowledge_node_id ASC`）取前 [limit]：matched 序列本身。
         */
        private fun matchedRanked(
            subject: String,
            queryFeatures: Set<String>,
            limit: Int,
        ): List<KnowledgeNodeSeedRecord> = matchedScored(subject, queryFeatures)
            .take(limit)
            .map { entry -> entry.first }

        /**
         * matched 的父节点（去掉同时也在 matched 里的），按**包内出现序**——生产 `readKnowledgeNodesByIds`
         * 的 rowid 序在 JVM 侧的确定性镜像（见类注释）。
         */
        private fun parentsOf(matched: List<KnowledgeNodeSeedRecord>): List<KnowledgeNodeSeedRecord> {
            val matchedIds = matched.mapTo(HashSet<String>()) { it.knowledgeNodeId }
            val parentSet = matched.mapNotNullTo(HashSet<String>()) { it.parentKnowledgeNodeId }
            val parentIds = parentSet.minus(matchedIds)
            return nodesInPackOrder.filter { it.knowledgeNodeId in parentIds }
        }
    }

    /** A 路（内存精排）金标口径：与守门基准同一取法——按科隔离的原子节点全集进 [KnowledgeContextRetriever.select]。 */
    fun goldenARoute(
        candidates: List<KnowledgeNodeSeedRecord>,
        cases: List<GoldenCase>,
    ): GoldenRouteResult = scoreGolden("A-route-rerank", cases) { case ->
        KnowledgeContextRetriever.select(
            candidates = candidates.filter { it.subject == case.subject },
            questionText = case.query,
            limit = SCORED_TOP_K,
        )
    }

    /**
     * 生产 v1 形状（裸 B 路 limit=5，无 A 路精排；**D1 落地后返回形态 = matched 优先**）的
     * 金标 JVM 镜像口径：与 `GoldenRetrievalInstrumentedTest` 的真 SQL 判分目标同构
     * （2026-09-22 的 B512→A 统一路由实验实测净伤害、当日回滚，KD-24），镜像只做交叉核对、
     * 不作判定依据（判定以真 SQL 为准，漂移纪律见 docs/kb-vector-topic-decision.md §5）。
     */
    fun goldenBRoute(
        index: BRouteMirrorIndex,
        cases: List<GoldenCase>,
    ): GoldenRouteResult = scoreGolden("B-route-mirror(v1-bare-B5, matched-first)", cases) { case ->
        index.recall(
            case.subject,
            case.query,
            limit = SCORED_TOP_K,
        )
    }

    /**
     * 金标 JVM 指标文件。测量台性质——**不含阈值断言**（预注册判据见 docs/kb-vector-topic-decision.md）。
     *
     * 每条路线一节：主集 Recall@5 / MRR、逐章分布（命中/总数 + MISS 数）、逐题 MISS 清单
     * （`query` + `expectedSlug` + `rank|absent`）。[missesByRoute] 以 `route` 名为键——
     * 漏传某条路线的 MISS 清单会让该节只剩表头，由 `GoldenRetrievalJvmTest` 的
     * 产物契约断言抓（清单条数必须等于该路线未命中数）。
     */
    fun goldenMetricsFile(
        packId: String,
        nodeCount: Int,
        indexInfo: String,
        goldenInfo: String,
        a: GoldenRouteResult,
        b: GoldenRouteResult,
        missesByRoute: Map<String, List<GoldenMiss>> = emptyMap(),
    ): String = buildString {
        appendLine("# golden-jvm-metrics — GoldenRetrievalJvmTest 测量台（不断言质量阈值，D12 预注册判据见 docs/kb-vector-topic-decision.md）")
        appendLine("# pack=$packId nodes=$nodeCount")
        appendLine("# index: $indexInfo")
        appendLine("# golden: $goldenInfo")
        for (result in listOf(a, b)) {
            val misses = missesByRoute[result.route].orEmpty()
            appendLine("")
            appendLine("== ${result.route} ==")
            appendLine("Recall@5(主集)=${result.recallAt5} (${result.hits}/${result.total})")
            appendLine("MRR=${result.mrr}")
            appendLine("逐章 Recall@5:")
            goldenChapterLines(result).forEach(::appendLine)
            appendLine(
                "MISS 清单（${misses.size} 例；rank = 预期节点在本路线返回序列放宽窗口" +
                    "（前 $MISS_PROBE_LIMIT 名）内的名次，absent = 该窗口内不存在）:",
            )
            goldenMissLines(misses).forEach(::appendLine)
        }
        appendLine("")
        appendLine("== 逐题 ==")
        a.scores.forEachIndexed { i, sa ->
            val sb = b.scores[i]
            appendLine(
                "[${sa.case.subject}] ${sa.case.query} -> A=hit:${sa.hit} rank:${sa.rank} " +
                    "B=hit:${sb.hit} rank:${sb.rank} | A-top3=${sa.top3} B-top3=${sb.top3}",
            )
        }
    }
}
