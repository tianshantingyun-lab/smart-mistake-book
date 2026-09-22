package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import java.io.File

/**
 * Stage-1 实验的执行体：把 [Stage1LexicalLab] 的索引/词典拼成 WP-A 预注册的七条臂，
 * 逐题出数、收集诊断、生成落盘产物（报告 + 逐臂指标 + 判读输入 JSON）。
 *
 * **不在这里改判读线**：本类只出数与整理证据；"过线/不过线"由编排脚本按
 * `build/stage1-verdict.json` 机械求值（§1）。
 *
 * 所有"扫描诊断"（k1/b 变体、K 变体、去覆盖层、父节点前置）都只落在报告第 9 节，
 * **显式标注非判读数**，不参与 chosenArm（§3）。
 *
 * 报告第 10 节是**口径对照**（[paradigmCells]）：同一判分口径下把"候选排序 × 返回形态"两维摆开，
 * 回答"臂 A 的 matched-only 判分数在**生产口径**（store 的 `parents + matched` 取前 5）下还剩多少"。
 * 它同样是**诊断段**——不改判读线、不进 chosenArm、不写进 `arms`。
 */
internal class Stage1Experiment(
    private val corpus: Stage1LexicalLab.Corpus,
    private val cases: List<RetrievalBenchmark.GoldenCase>,
    private val repoRoot: File,
    private val packId: String,
    private val goldenInfo: String,
) : AutoCloseable {

    private val fts = Stage1LexicalLab.Fts5Index(corpus)
    private val trigramFts = Stage1LexicalLab.Fts5Index(corpus, TRIGRAM)
    private val custom = Stage1LexicalLab.CustomBm25Index(corpus)
    private val dictionary = Stage1LexicalLab.AliasDictionary(corpus)

    /** v1 裸 B 路 SQL 排序的 JVM 镜像（§10 口径对照的基线排序侧；与金标测量台同一份实现）。 */
    private val v1Mirror = RetrievalBenchmark.BRouteMirrorIndex(corpus.nodes)

    /** 语料级文档长度对账（A' 的 `len = 特征数` / `tf 二值` 前提）。 */
    val lengthCheck: Stage1LexicalLab.LengthCheck = fts.verifyDocumentLengths()

    /** bm25 公式校验（固定夹具：词频 >1、idf 钳位都被走到）。 */
    val formulaCheck: Stage1LexicalLab.FormulaCheck = Stage1LexicalLab.verifyBm25Formula()

    /** A' 索引自检（postings 行数 = Σ 特征数；两臂索引内容逐字同源的证据）。 */
    val customDocCount: Int = custom.docCount
    val customPostingCount: Long = custom.postingCount

    /** 实验环境事实（测试断言它们成立，否则整份数据无意义）。 */
    val ftsSqliteVersion: String = fts.sqliteVersion
    val trigramAvailable: Boolean = trigramFts.trigramAvailable
    val mathFunctionsAvailable: Boolean = fts.mathFunctionsAvailable
    val ftsIndexedDocs: Int = fts.indexedDocs

    /** FTS5 查询计划（证明 MATCH 走的是倒排而不是全表扫）。 */
    val queryPlan: List<String> = run {
        val case = cases.first()
        fts.queryPlan(case.subject, Stage1LexicalLab.matchExpression(termsOf(case)), SCORED_TOP_K)
    }

    /** A ↔ A' 一致性逐题日志（报告逐字留痕；判据是"容差 0"）。 */
    val agreementLog = ArrayList<String>()

    /** 臂 B 的词典扩展逐题日志。 */
    val expansionLog = ArrayList<String>()

    /** 臂 C 的章映射/推断日志。 */
    val chapterLog = ArrayList<String>()

    /** trigram 对照的失配统计日志。 */
    val trigramLog = ArrayList<String>()

    private val chaptersInPool: List<String> = corpus.chaptersBySubject.values.flatten().distinct().sorted()

    val indexInfo: String =
        "indexVersion=${KnowledgeSearchFeatureExtractor.INDEX_VERSION} " +
            "docs=${corpus.nodes.size} featureRows=${corpus.indexRows} " +
            "maxFeaturesPerNode=${corpus.maxFeaturesPerNode}"

    // ------------------------------------------------------------------
    // 线路（每条臂一个检索函数，签名统一为 (case, limit) -> 返回序列）
    // ------------------------------------------------------------------

    /**
     * 臂 A：FTS5 `bm25()` 升序取前 limit。空特征时走生产回退顺序（本套金标不触发，
     * 但形状保持与 store 一致，免得"空查询"这一支在生产里是另一套行为）。
     */
    fun retrieveA(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        retrieveFts(fts, termsOf(case), case.subject, limit, chapter = null, coverage = null)

    /** 臂 A'：自写 df+BM25 SQL（同一份特征、同一份候选过滤）。 */
    fun retrieveAprim(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> {
        val terms = termsOf(case)
        if (terms.isEmpty()) return emptyFallback(case.subject, limit)
        return corpus.records(custom.search(case.subject, terms, limit).map { it.nodeId })
    }

    /** 臂 B：A 的排序 + 别名词典查询扩展（组内 OR 词 + 组间覆盖排序键）。 */
    fun retrieveB(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        retrieveB(case, limit, withCoverage = true)

    /** 臂 B 的**去覆盖层**变体（扫描诊断用，非判读数）：只用扩展词、排序回到裸 bm25。 */
    fun retrieveBExpansionOnly(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        retrieveB(case, limit, withCoverage = false)

    /** 臂 C-上界（天线口径）：按金标 chapter 过滤候选后重排——只能当上界，不可上生产。 */
    fun retrieveCUpper(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        retrieveFts(fts, termsOf(case), case.subject, limit, chapter = case.chapter, coverage = null)

    /** 臂 C-两段式（可生产）：宽召回 → 章多数推断 → 章内重排。 */
    fun retrieveCTwoStage(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        twoStage(
            case = case,
            limit = limit,
            wideK = WIDE_RECALL_K,
            wide = ::retrieveA,
            rerankTerms = termsOf(case),
            rerankCoverage = null,
        )

    /** 臂 D：A+B + 两段式章门控（推断用的宽召回与章内重排都走 B 的排序）。 */
    fun retrieveD(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> {
        val expansion = expansionOf(case)
        return twoStage(
            case = case,
            limit = limit,
            wideK = WIDE_RECALL_K,
            wide = ::retrieveB,
            rerankTerms = termsOf(case) + expansion.terms,
            rerankCoverage = expansion.coverage(),
        )
    }

    /** 臂 T（对照）：同索引内容、只换 trigram tokenizer。 */
    fun retrieveTrigram(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        retrieveFts(trigramFts, termsOf(case), case.subject, limit, chapter = null, coverage = null)

    /** 形状敏感性诊断（非判读数）：A 的得分前 5 + 生产镜像的"父节点前置"返回形态。 */
    fun retrieveAWithParentPrefix(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        aWithParents(case, limit, parentsFirst = true)

    /** 形状敏感性诊断（非判读数）：A 的得分前 5 + 父节点排在其后（D1 候选返回形态）。 */
    fun retrieveAWithParentSuffix(case: RetrievalBenchmark.GoldenCase, limit: Int): List<KnowledgeNodeSeedRecord> =
        aWithParents(case, limit, parentsFirst = false)

    /** A 排序 + 父节点：只换父节点在序列里的位置（前置 = 生产镜像；后置 = D1 候选）。 */
    private fun aWithParents(
        case: RetrievalBenchmark.GoldenCase,
        limit: Int,
        parentsFirst: Boolean,
    ): List<KnowledgeNodeSeedRecord> {
        val matched = fts.search(
            subject = case.subject,
            matchExpression = matchOrEmpty(termsOf(case)),
            limit = limit,
        ).map { it.nodeId }
        val matchedSet = matched.toHashSet()
        val parentIds = matched.mapNotNullTo(linkedSetOf()) { id ->
            corpus.nodeById[id]?.parentKnowledgeNodeId
        }.minus(matchedSet)
        val parents = corpus.nodes.filter { it.knowledgeNodeId in parentIds }
        val records = corpus.records(matched)
        return (if (parentsFirst) parents + records else records + parents).distinctBy { it.knowledgeNodeId }
    }

    // ------------------------------------------------------------------
    // §10 口径对照（诊断段）：候选排序 × 返回形态
    // ------------------------------------------------------------------

    /** §10 口径对照的一格：一条**候选排序** × 一种**返回形态**的判分结果。 */
    data class ParadigmCell(
        val route: String,
        val shape: String,
        val note: String,
        val result: RetrievalBenchmark.GoldenRouteResult,
    ) {
        val main: Double get() = result.recallAt5
        val hits: Int get() = result.hits
        val total: Int get() = result.total
        val mrr: Double get() = result.mrr
        val chapterMin: Double get() = result.byChapter.minOfOrNull { it.second } ?: 0.0
    }

    /**
     * **§10 口径对照**（诊断段）：同一份语料 / 题面 / 判分口径（top-5、按科隔离、可信状态过滤、
     * 命中定义）下，把"**候选排序** × **返回形态**"两维摆开——回答的正是本轮最关键的问题：
     * **FTS5 排序相对 v1 的增益，在生产口径下是否还在**。
     *
     * 三种返回形态（排序与候选过滤三者逐字相同，只有序列位置不同）：
     * - `生产形 parents+matched` = 生产 store 的真实返回（`RoomKnowledgeBaseStore.kt:125-133`），
     *   仪表化判分取该序列前 5 ⇒ **生产口径**；
     * - `matched-only` = 只返回排序后的 matched（**不是生产判分口径，只作诊断**）——各臂的
     *   判分数（如臂 A 的 0.6556）出自这个口径；
     * - `D1 候选 matched 前置` = matched 在前、父节点排其后（父节点仍返回，供上层解释）。
     *
     * 本方法**只出数**：它不进 [runAll] 的参评臂集、不进 `chosenArm`、不动 §1 判读线。
     */
    fun paradigmCells(): List<ParadigmCell> {
        fun cell(
            route: String,
            shape: String,
            note: String,
            retrieve: (RetrievalBenchmark.GoldenCase, Int) -> List<KnowledgeNodeSeedRecord>,
        ): ParadigmCell = ParadigmCell(
            route = route,
            shape = shape,
            note = note,
            result = RetrievalBenchmark.scoreGolden("$route × $shape", cases) { case ->
                retrieve(case, SCORED_TOP_K)
            },
        )
        val v1Production = { case: RetrievalBenchmark.GoldenCase, limit: Int ->
            v1Mirror.recall(case.subject, case.query, limit)
        }
        val v1MatchedOnly = { case: RetrievalBenchmark.GoldenCase, limit: Int ->
            v1Mirror.matchedOnly(case.subject, case.query, limit)
        }
        val v1MatchedFirst = { case: RetrievalBenchmark.GoldenCase, limit: Int ->
            v1Mirror.matchedFirst(case.subject, case.query, limit)
        }
        return listOf(
            cell(V1_ROUTE, SHAPE_PRODUCTION, "基线排序 + 生产真实返回形态（仪表化判分口径）", v1Production),
            cell(V1_ROUTE, SHAPE_MATCHED_ONLY, "基线排序 + 诊断口径（无父节点前置）", v1MatchedOnly),
            cell(V1_ROUTE, SHAPE_D1, "基线排序 + D1 候选形态（matched 前置）", v1MatchedFirst),
            cell(A_ROUTE, SHAPE_PRODUCTION, "FTS5 排序 + 生产真实返回形态", ::retrieveAWithParentPrefix),
            cell(A_ROUTE, SHAPE_MATCHED_ONLY, "FTS5 排序 + 诊断口径（= 臂 A 判分数所在口径）", ::retrieveA),
            cell(A_ROUTE, SHAPE_D1, "FTS5 排序 + D1 候选形态（matched 前置）", ::retrieveAWithParentSuffix),
        )
    }

    // ------------------------------------------------------------------
    // 跑齐所有臂
    // ------------------------------------------------------------------

    fun runAll(): List<Stage1LexicalLab.Arm> {
        collectDiagnostics()
        val evidenceBase = "FTS5($UNICODE61) 全量节点 ${corpus.nodes.size} 篇；查询=fromQuestion 特征 OR；" +
            "ORDER BY bm25 ASC, node_id ASC；top5 无父节点前置"
        val arms = listOf(
            Stage1LexicalLab.score("A", ROUTE_A, true, evidenceBase, cases, ::retrieveA),
            Stage1LexicalLab.score(
                "A'", ROUTE_APRIM, false,
                "自写 documents/postings/metadata + df/BM25 SQL（k1=1.2 b=0.75 tf 二值，idf≤0 钳 1e-6）；" +
                    "与 A 同特征同过滤，用于验证 FTS5 排序口径 —— 非独立候选",
                cases, ::retrieveAprim,
            ),
            Stage1LexicalLab.score(
                "B", ROUTE_B, true,
                "A 的排序 + 别名词典扩展（surface ${dictionary.surfaceCount} 个 / 组 ${dictionary.groupCount} 个；" +
                    "泛称剔除 ${dictionary.droppedGenerics.size} 个）；组内 OR 进 MATCH，组覆盖为第一排序键",
                cases, ::retrieveB,
            ),
            Stage1LexicalLab.score(
                "C-upper", ROUTE_C_UPPER, false,
                "天线口径（不可上生产、不参评）：按金标 chapter 过滤候选（池内章 ${chaptersInPool.size} 个）后重排",
                cases, ::retrieveCUpper,
            ),
            Stage1LexicalLab.score(
                "C-2stage", ROUTE_C_2STAGE, true,
                "宽召回 K=$WIDE_RECALL_K（臂 A）→ 前 K 候选的章多数推断 → 章内重排（同 bm25 排序）",
                cases, ::retrieveCTwoStage,
            ),
            Stage1LexicalLab.score(
                "D", ROUTE_D, true,
                "A+B（同 ROUTE_B 的排序含组覆盖）+ 两段式章门控（宽召回走 B）",
                cases, ::retrieveD,
            ),
            Stage1LexicalLab.score(
                "T", ROUTE_T, false,
                "对照（不参评）：同索引内容换 tokenize='trigram'；<3 字特征无法命中，失配影响见诊断段",
                cases, ::retrieveTrigram,
            ),
        )
        return arms
    }

    // ------------------------------------------------------------------
    // 内部：检索与门控
    // ------------------------------------------------------------------

    private fun retrieveB(
        case: RetrievalBenchmark.GoldenCase,
        limit: Int,
        withCoverage: Boolean,
    ): List<KnowledgeNodeSeedRecord> {
        val terms = termsOf(case)
        val expansion = expansionOf(case)
        if (terms.isEmpty() && expansion.terms.isEmpty()) return emptyFallback(case.subject, limit)
        return retrieveFts(
            index = fts,
            terms = terms + expansion.terms,
            subject = case.subject,
            limit = limit,
            chapter = null,
            coverage = if (withCoverage) expansion.coverage() else null,
        )
    }

    /** 词典扩展按题面缓存（同一题在一次实验里被多条臂/诊断反复取用，重复算纯属浪费）。 */
    private fun expansionOf(case: RetrievalBenchmark.GoldenCase): Stage1LexicalLab.Expansion =
        expansionCache.getOrPut(case.query) { dictionary.expansion(case.query) }

    private val expansionCache = HashMap<String, Stage1LexicalLab.Expansion>()

    private fun retrieveFts(
        index: Stage1LexicalLab.Fts5Index,
        terms: Collection<String>,
        subject: String,
        limit: Int,
        chapter: String?,
        coverage: Map<String, Int>?,
    ): List<KnowledgeNodeSeedRecord> {
        if (terms.isEmpty()) return emptyFallback(subject, limit)
        val hits = index.search(subject, Stage1LexicalLab.matchExpression(terms), limit, chapter, coverage)
        return corpus.records(hits.map { it.nodeId })
    }

    private fun emptyFallback(subject: String, limit: Int): List<KnowledgeNodeSeedRecord> =
        RetrievalBenchmark.emptyQueryFallbackOrder(corpus.trustedBySubject[subject].orEmpty()).take(limit)

    private fun matchOrEmpty(terms: Collection<String>): String =
        if (terms.isEmpty()) "" else Stage1LexicalLab.matchExpression(terms)

    private fun termsOf(case: RetrievalBenchmark.GoldenCase): Set<String> =
        KnowledgeSearchFeatureExtractor.fromQuestion(case.query)

    /**
     * 两段式章门控：宽召回 [wideK] 条 → 章多数推断 → 章内重排。
     * 推断不出章（宽召回为空或候选都没章）时**退回不过滤**（等价于第一段排序），
     * 免得门控把候选全砍光；**章内重排用与第一段同一套排序函数**（[rerankTerms] /
     * [rerankCoverage] 与 wide 同源），不换另一套打分。
     */
    private fun twoStage(
        case: RetrievalBenchmark.GoldenCase,
        limit: Int,
        wideK: Int,
        wide: (RetrievalBenchmark.GoldenCase, Int) -> List<KnowledgeNodeSeedRecord>,
        rerankTerms: Collection<String>,
        rerankCoverage: Map<String, Int>?,
    ): List<KnowledgeNodeSeedRecord> {
        val inferred = majorityChapter(wide(case, wideK))
        return if (inferred == null) {
            wide(case, limit)
        } else {
            retrieveFts(fts, rerankTerms, case.subject, limit, chapter = inferred, coverage = rerankCoverage)
        }
    }

    /** 宽召回序列里出现最多的章（同票按先出现者优先，保证可复算）。 */
    private fun majorityChapter(wide: List<KnowledgeNodeSeedRecord>): String? {
        val order = ArrayList<String>()
        val counts = HashMap<String, Int>()
        wide.forEach { node ->
            val chapter = corpus.chapterByNodeId[node.knowledgeNodeId] ?: return@forEach
            if (counts.getOrPut(chapter) { 0 } == 0) order.add(chapter)
            counts[chapter] = counts.getValue(chapter) + 1
        }
        return order.maxWithOrNull(compareBy({ counts.getValue(it) }, { -order.indexOf(it) }, { it }))
    }

    // ------------------------------------------------------------------
    // 诊断（非判读数）
    // ------------------------------------------------------------------

    private fun collectDiagnostics() {
        // A ↔ A'：逐题名次、top-5 序列、全窗口（前 256）序列、分数对账。
        var rankMismatch = 0
        var windowMismatch = 0
        var scoreMismatch = 0
        var maxScoreDelta = 0.0
        cases.forEach { case ->
            val aIds = retrieveA(case, MISS_PROBE).map { it.knowledgeNodeId }
            val pIds = retrieveAprim(case, MISS_PROBE).map { it.knowledgeNodeId }
            val aTop = aIds.take(SCORED_TOP_K)
            val pTop = pIds.take(SCORED_TOP_K)
            agreementLog.add(
                "  [" + case.subject + "] " + case.query +
                    " | A-top5=" + aTop + " | A'-top5=" + pTop +
                    " | top5相等=" + (aTop == pTop) +
                    " | 窗口(前 $MISS_PROBE)序列相等=" + (aIds == pIds),
            )
            if (aTop != pTop) rankMismatch++
            if (aIds != pIds) windowMismatch++
            val aTerms = termsOf(case)
            val aHits = fts.search(case.subject, Stage1LexicalLab.matchExpression(aTerms), MISS_PROBE)
            val pScores = custom.search(case.subject, aTerms, MISS_PROBE)
                .associate { it.nodeId to it.score }
            aHits.forEach { hit ->
                val customScore = pScores[hit.nodeId]
                if (customScore != null) {
                    // 两侧同符号约定（都取 FTS5 的负分），差值直接可比。
                    val delta = kotlin.math.abs(customScore - hit.score)
                    if (delta > 1e-9) scoreMismatch++
                    if (delta > maxScoreDelta) maxScoreDelta = delta
                }
            }
        }
        agreementLog.add(
            "  汇总: top5 不等 $rankMismatch/${cases.size}；窗口(前 $MISS_PROBE)不等 $windowMismatch/${cases.size}；" +
                "同题同节点的 bm25 分值差 >1e-9 的项 $scoreMismatch 个（最大差 $maxScoreDelta）",
        )

        // 臂 B：词典扩展逐题日志。
        var withExpansion = 0
        var totalSurfaces = 0
        var totalTerms = 0
        var emptyGroups = 0
        cases.forEach { case ->
            val expansion = expansionOf(case)
            if (expansion.matchedSurfaces.isNotEmpty()) withExpansion++
            totalSurfaces += expansion.matchedSurfaces.size
            totalTerms += expansion.terms.size
            if (expansion.matchedSurfaces.isNotEmpty() && expansion.groups.isEmpty()) emptyGroups++
            expansionLog.add(
                "  [" + case.subject + "] " + case.query +
                    " | 命中 surface=" + dictionary.describe(expansion) +
                    " | 组节点数=" + expansion.nodesByGroup.values.map { it.size } +
                    " | expectedSlug=" + case.expectedSlug,
            )
        }
        expansionLog.add(
            "  汇总: 90 题中命中 surface 的 $withExpansion 题；命中 surface 共 $totalSurfaces 个；" +
                "扩展词共 $totalTerms 个；命中 surface 却落不到组上的 $emptyGroups 题；" +
                "泛称剔除 ${dictionary.droppedGenerics.size} 个（跨多主题且高频）：" +
                dictionary.droppedGenerics.take(20) +
                "；组内表面形式上界触发的组数 ${dictionary.truncatedGroups}",
        )

        // 臂 C：章映射与推断日志。
        chapterLog.add("  候选池内出现过的章共 ${chaptersInPool.size} 个（按科：${corpus.chaptersBySubject.mapValues { it.value.size }}）")
        var chapterAgree = 0
        var chapterEmpty = 0
        cases.forEach { case ->
            val upper = retrieveCUpper(case, SCORED_TOP_K).map { it.knowledgeNodeId }
            val wideA = retrieveA(case, WIDE_RECALL_K).map { it.knowledgeNodeId }
            val inferred = majorityChapter(corpus.records(wideA))
            if (inferred == case.chapter) chapterAgree++
            if (inferred == null) chapterEmpty++
            chapterLog.add(
                "  [" + case.subject + "] " + case.query +
                    " | chapter=" + case.chapter +
                    " | 推断章=" + inferred +
                    " | 章内候选(上界池)=" + fts.search(
                        case.subject,
                        Stage1LexicalLab.matchExpression(termsOf(case)),
                        MISS_PROBE,
                        chapter = case.chapter,
                    ).size +
                    " | 上界 top5=" + corpus.records(upper).map { it.canonicalName },
            )
        }
        chapterLog.add(
            "  汇总: 宽召回（前 $WIDE_RECALL_K，臂 A）多数推断的章与金标章一致 $chapterAgree/${cases.size} 题" +
                "（推断不出章的 $chapterEmpty 题）——章门控是硬过滤，推错章的题不可能命中，" +
                "所以 $chapterAgree/${cases.size} 就是两段式变体的天花板。",
        )

        // 臂 T：trigram 的 <3 字失配影响。
        var queriesWithShort = 0
        var queriesEmpty = 0
        var shortTerms = 0
        var allTerms = 0
        cases.forEach { case ->
            val terms = termsOf(case)
            val short = trigramFts.shortFeatures(terms)
            allTerms += terms.size
            shortTerms += short
            if (short > 0) queriesWithShort++
            val hits = trigramFts.search(case.subject, Stage1LexicalLab.matchExpression(terms), SCORED_TOP_K)
            if (hits.isEmpty()) queriesEmpty++
        }
        trigramLog.add(
            "  trigram 对照：90 题里含 <3 字特征的 $queriesWithShort 题；" +
                "特征总数 $allTerms，其中 <3 字（trigram 索引下注定失配）$shortTerms 个；" +
                "前 5 返回为空的 $queriesEmpty 题",
        )
    }

    /** 逐臂扫描（非判读数）：k1/b 变体、K 变体、去覆盖层、父节点前置。 */
    fun scans(): List<Stage1LexicalLab.Arm> {
        val scanArms = ArrayList<Stage1LexicalLab.Arm>()
        // A' 的 k1/b 变体（FTS5 的 k1/b 硬编码不可调，只能在自写 SQL 上扫）。
        listOf(0.9 to 0.4, 1.5 to 0.9, 2.0 to 1.0).forEach { (k1, b) ->
            Stage1LexicalLab.CustomBm25Index(corpus, k1, b).use { variant ->
                scanArms.add(
                    Stage1LexicalLab.score(
                        "scan-A'-k1${k1}-b$b", "scan-Aprim-k1_${k1}_b_${b}", false,
                        "非判读数：k1/b 变体（判读配置固定 k1=$K1 b=$B）", cases,
                    ) { case, limit ->
                        val terms = termsOf(case)
                        if (terms.isEmpty()) {
                            emptyFallback(case.subject, limit)
                        } else {
                            corpus.records(variant.search(case.subject, terms, limit).map { it.nodeId })
                        }
                    },
                )
            }
        }
        // C-两段式的 K 变体（臂定义固定 K=$WIDE_RECALL_K）。
        listOf(16, 32, 128).forEach { k ->
            scanArms.add(
                Stage1LexicalLab.score(
                    "scan-C-2stage-K$k", "scan-C2stage-K_$k", false,
                    "非判读数：门控宽度变体（臂定义固定 K=$WIDE_RECALL_K）", cases,
                ) { case, limit ->
                    twoStage(case, limit, k, ::retrieveA, termsOf(case), null)
                },
            )
        }
        // 形状敏感性：A + 生产镜像的父节点前置（不是排序变体，是返回形态变体）。
        scanArms.add(
            Stage1LexicalLab.score(
                "scan-A-parentprefix", "scan-A-parentprefix", false,
                "非判读数：A 的排序 + 生产镜像的父节点前置返回形态", cases, ::retrieveAWithParentPrefix,
            ),
        )
        // 臂 B 的去覆盖层（只留扩展词）——分离"扩展"与"组覆盖排序"各自的作用。
        scanArms.add(
            Stage1LexicalLab.score(
                "scan-B-expansionOnly", "scan-B-expansionOnly", false,
                "非判读数：臂 B 去掉组覆盖排序键，只保留扩展词", cases, ::retrieveBExpansionOnly,
            ),
        )
        return scanArms
    }

    override fun close() {
        fts.close()
        trigramFts.close()
        custom.close()
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /** 全部产物一次写完：逐臂指标 + 报告 + 判读输入 JSON。返回 (报告文件, 判读 JSON 文件)。 */
    fun writeArtifacts(
        arms: List<Stage1LexicalLab.Arm>,
        scans: List<Stage1LexicalLab.Arm>,
        baseline: Measurement,
    ): Artifacts {
        val buildDir = File(repoRoot, "build")
        buildDir.mkdirs()
        arms.forEach { arm ->
            File(repoRoot, arm.metricsFile).writeText(
                Stage1LexicalLab.metricsFileText(arm, packId, goldenInfo, indexInfo),
            )
        }
        val chosen = Stage1LexicalLab.chooseArm(arms)
        val agree = ftsVsCustomAgree(arms)
        val paradigm = paradigmCells()
        val reportFile = File(buildDir, "stage1-experiments.md")
        reportFile.writeText(renderReport(arms, scans, chosen, agree, baseline, paradigm))
        val verdictFile = File(buildDir, "stage1-verdict.json")
        verdictFile.writeText(
            Stage1LexicalLab.verdictJson(
                Stage1LexicalLab.verdictOf(arms, chosen, agree, verdictNotes(arms, chosen, baseline, scans)),
            ),
        )
        return Artifacts(reportFile, verdictFile, chosen, agree, paradigm)
    }

    /** 臂 A' 的**索引自检**（产物契约：documents = 节点数、postings = Σ 特征数）。 */
    fun customIndexSelfCheck(): String {
        val expectedPostings = corpus.indexRows
        val terms = termsOf(cases.first())
        return "documents=${custom.docCount}（节点 ${corpus.nodes.size}）" +
            " postings=${custom.postingCount}（Σ特征 $expectedPostings）" +
            " | 首题查询自检: ${custom.debugQuery(cases.first().subject, terms)}"
    }

    /** A ↔ A' 是否**容差 0** 一致（主集 + MRR，即 §2 的验收硬项）。 */
    fun ftsVsCustomAgree(arms: List<Stage1LexicalLab.Arm>): Boolean {
        val a = arms.single { it.name == "A" }
        val prim = arms.single { it.name == "A'" }
        return a.hits == prim.hits && a.mrr == prim.mrr && a.ranks == prim.ranks &&
            a.topIds == prim.topIds
    }

    data class Artifacts(
        val reportFile: File,
        val verdictFile: File,
        val chosen: Stage1LexicalLab.Arm,
        val agree: Boolean,
        /** §10 口径对照的逐格判分结果（与报告 §10 表同一份数据源）。 */
        val paradigm: List<ParadigmCell>,
    )

    /** 基线一次本会话测量（同一次构建/同一份包/同一套题面）。 */
    data class Measurement(val label: String, val main: Double, val mrr: Double, val hits: Int, val total: Int)

    private fun renderReport(
        arms: List<Stage1LexicalLab.Arm>,
        scans: List<Stage1LexicalLab.Arm>,
        chosen: Stage1LexicalLab.Arm,
        agree: Boolean,
        baseline: Measurement,
        paradigm: List<ParadigmCell>,
    ): String = buildString {
        appendLine("# Stage-1 词面检索实验 · 出数报告（WP-C）")
        appendLine()
        appendLine(
            "> 由 `Stage1LexicalLabTest` 在运行时写出（每次跑覆盖，可复算）。规则/判读线/固定参数" +
                "见 `docs/kb-lexical-stage1-experiments.md`；本报告**只填数、不改规则**。" +
                "复算：`./gradlew :core:data:testDebugUnitTest --tests \"*Stage1LexicalLabTest*\" --rerun`",
        )
        appendLine()
        appendLine("## 1. 口径与固定参数（§2/§3）")
        appendLine()
        appendLine("- 题面 = 冻结金标集：$goldenInfo")
        appendLine("- 分词 = 生产提取器 `KnowledgeSearchFeatureExtractor`（索引侧 `fromNode`、查询侧 `fromQuestion`），未另写分词")
        appendLine("- 命中定义 = 前 5 名里存在 `knowledgeNodeId` 以 `:atomic:<expectedSlug>` 结尾；top-5、按科隔离、可信状态过滤与既有测量台同一套")
        appendLine("- 排序 = FTS5 `bm25()` 升序（k1=$K1 / b=$B，SQLite 硬编码不可调）+ 确定性次级键 `node_id ASC`")
        appendLine("- 候选过滤写在 SQL 的 WHERE（按科 + 可信状态 + 可选章），**索引域 = 全量节点**（含 TOPIC）：FTS5 的 N/avgdl/df 是整张表上的量")
        appendLine("- 包：`$packId`；$indexInfo")
        appendLine()
        appendLine("## 2. 实验环境与保真检查")
        appendLine()
        appendLine("- sqlite-jdbc（xerial）内嵌 SQLite 版本：`${fts.sqliteVersion}`；FTS5 建表成功；" +
            "`trigram` tokenizer 可用=${trigramFts.trigramAvailable}；数学函数 `ln()` 可用=${fts.mathFunctionsAvailable}")
        appendLine("- 分词保真 / 文档长度对账（唯一特征反推：`D=特征数`、`avgdl=Σ特征数/N` 与 FTS5 实打分一致）：" +
            "抽 ${lengthCheck.sampled} 篇，不一致 ${lengthCheck.mismatches.size} 篇，最大偏差 ${lengthCheck.maxDelta}" +
            "${if (lengthCheck.ok) "（⇒ 臂 A' 的 len=特征数、tf 二值成立）" else "：${lengthCheck.mismatches}"}")
        appendLine("- bm25 公式校验（固定夹具 ${formulaCheck.docsInFixture} 篇，avgdl=${formulaCheck.avgdlInFixture}，含 tf>1 与 idf 钳位）：" +
            "与 FTS5 实打分最大偏差 ${formulaCheck.maxDelta}；不套钳位时的最大偏差 ${formulaCheck.maxUnclampedDelta}" +
            "（钳位分支被走到=${formulaCheck.clampExercised}）")
        appendLine("- 公式校验逐行：")
        formulaCheck.rows.forEach { appendLine("  - $it") }
        appendLine("- 长度对账抽样（唯一特征反推）：")
        appendLine("  - 抽 ${lengthCheck.sampled} 篇，最大偏差 ${lengthCheck.maxDelta}，不一致 ${lengthCheck.mismatches.size} 例${if (lengthCheck.ok) "（全部一致）" else ""}")
        appendLine("- FTS5 查询计划（臂 A 的首题）：${queryPlan.joinToString(" / ")}")
        appendLine()
        appendLine("## 3. 全臂表（判读数：chosenArm = 可上生产臂中主集最高者）")
        appendLine()
        appendLine("| 臂 | 路由 | 参评 | 主集 Recall@5 | 命中 | 逐章最小 Recall@5 | MRR | 指标文件 |")
        appendLine("|---|---|---|---|---|---|---|---|")
        arms.forEach { arm ->
            appendLine(
                "| ${arm.name} | `${arm.route}` | ${if (arm.productionCandidate) "✅" else "❌"} | " +
                    "${arm.main} | ${arm.hits}/${arm.total} | ${arm.chapterMin} | ${arm.mrr} | `${arm.metricsFile}` |",
            )
        }
        appendLine()
        appendLine(
            "基线对照（预注册，§1）：v1 裸 B5 真 SQL 主集 **$BASELINE_MAIN**（49/90）/ MRR **$BASELINE_MRR**；" +
                "本会话同构建实测的 JVM 镜像 B 路（形状同构）：${baseline.label} 主集 ${baseline.main}（${baseline.hits}/${baseline.total}）/ MRR ${baseline.mrr}",
        )
        appendLine()
        appendLine("各臂相对基线的差（主集，基线 $BASELINE_MAIN）：")
        arms.forEach { arm ->
            val delta = arm.main - BASELINE_MAIN
            appendLine("- ${arm.name}: ${"%.4f".format(java.util.Locale.ROOT, delta)}（主集 ${arm.main} − $BASELINE_MAIN）")
        }
        appendLine()
        appendLine("## 4. 逐章 Recall@5")
        appendLine()
        arms.forEach { arm ->
            appendLine("### ${arm.name}（`${arm.route}`）")
            appendLine("```")
            RetrievalBenchmark.goldenChapterLines(arm.result).forEach(::appendLine)
            appendLine("```")
        }
        appendLine("## 5. MISS 清单")
        appendLine()
        arms.forEach { arm ->
            appendLine("### ${arm.name}（MISS ${arm.misses.size}/${arm.total}）")
            appendLine("```")
            RetrievalBenchmark.goldenMissLines(arm.misses).forEach(::appendLine)
            appendLine("```")
        }
        appendLine("## 6. A ↔ A' 一致性证据（验收硬项：主集/MRR 容差 0）")
        appendLine()
        appendLine("- 结论：A 与 A' 的主集/MRR 一致（容差 0） = $agree")
        appendLine(
            "- 出数过程中的一次语义修正（回归证据，勿删）：自写 SQL 初版把 BM25 的**符号/方向**写反了" +
                "（`ORDER BY SUM(...) ASC`：FTS5 的 `bm25()` 返回**负分**、升序即最优，自写侧却是**正分**升序，" +
                "于是取到的是证据最弱的候选，A' 当时主集 0/90），当轮 A↔A' 对账当场报红；" +
                "按 FTS5 的符号约定改成 `-SUM(...) ASC` 后两臂逐位一致。根因 = **排序方向约定**，" +
                "不是分词口径 / 文档长度 / tf 定义（后三者的独立性由 §2 的公式夹具与文档长度对账分别钉住）。",
        )
        appendLine("- 逐题逐字日志（top5 序列 + 全窗口序列 + 分值对账）：")
        appendLine("```")
        agreementLog.forEach(::appendLine)
        appendLine("```")
        appendLine()
        appendLine(
            "A 主集 ${arms.single { it.name == "A" }.main}（${arms.single { it.name == "A" }.hits}/90）" +
                "与 A' 主集 ${arms.single { it.name == "A'" }.main}（${arms.single { it.name == "A'" }.hits}/90）" +
                "、A MRR ${arms.single { it.name == "A" }.mrr} 与 A' MRR " +
                "${arms.single { it.name == "A'" }.mrr} 逐位相同。",
        )
        appendLine()
        appendLine("## 7. 臂 B：别名词典与查询扩展日志")
        appendLine()
        appendLine("```")
        expansionLog.forEach(::appendLine)
        appendLine("```")
        appendLine()
        appendLine("## 8. 臂 C：章映射与推断日志")
        appendLine()
        appendLine("```")
        chapterLog.forEach(::appendLine)
        appendLine("```")
        appendLine()
        appendLine("## 9. 扫描诊断（**全部为非判读数**，不参与 chosenArm 与判读）")
        appendLine()
        appendLine("| 扫描 | 主集 Recall@5 | 逐章最小 | MRR | 说明 |")
        appendLine("|---|---|---|---|---|")
        scans.forEach { scan ->
            appendLine("| ${scan.name} | ${scan.main} | ${scan.chapterMin} | ${scan.mrr} | ${scan.evidence} |")
        }
        appendLine()
        appendLine("trigram 对照臂（臂 T）的失配影响：")
        appendLine("```")
        trigramLog.forEach(::appendLine)
        appendLine("```")
        appendLine()
        appendLine("## 10. 口径对照（**诊断段**：候选排序 × 返回形态；不改 §1 判读）")
        appendLine()
        appendLine(
            "> **matched-only 口径不是生产判分口径，只作诊断。** 生产 store 返回 `(parents + matched)`" +
                "（`core/database/.../RoomKnowledgeBaseStore.kt:125-133`），仪表化判分（`GoldenRetrievalInstrumentedTest`）" +
                "取该序列前 5 = **生产口径**；matched-only 只返回排序后的 matched（无父节点前置）——" +
                "各臂的判分数（如臂 A 的 0.6556/0.5569）出自这个口径。本节各格与 §1/§2 的判分口径" +
                "（top-5、按科隔离、可信状态过滤、命中定义）逐字相同，只换**候选排序**与**返回形态**两维；" +
                "它不参与 `chosenArm`、不进 §1 判读。" +
                "FTS5 两行是**实验台诊断**（FTS5 排序尚未上生产，生产现状仍是 v1 排序）：" +
                "回答的是'把臂 A 的排序装进生产返回形态会是多少'；v1 两行是生产现状排序的 JVM 镜像。",
        )
        appendLine()
        appendLine("| 路线（候选排序） | 返回形态 | 主集 Recall@5 | 命中 | 逐章最小 Recall@5 | MRR | 说明 |")
        appendLine("|---|---|---|---|---|---|---|")
        paradigm.forEach { cell ->
            appendLine(
                "| ${cell.route} | ${cell.shape} | ${cell.main} | ${cell.hits}/${cell.total} | " +
                    "${cell.chapterMin} | ${cell.mrr} | ${cell.note} |",
            )
        }
        appendLine()
        val v1Prod = paradigm.single { it.route == V1_ROUTE && it.shape == SHAPE_PRODUCTION }
        val v1MatchedOnly = paradigm.single { it.route == V1_ROUTE && it.shape == SHAPE_MATCHED_ONLY }
        val v1D1 = paradigm.single { it.route == V1_ROUTE && it.shape == SHAPE_D1 }
        val aProd = paradigm.single { it.route == A_ROUTE && it.shape == SHAPE_PRODUCTION }
        val aMatchedOnly = paradigm.single { it.route == A_ROUTE && it.shape == SHAPE_MATCHED_ONLY }
        val aD1 = paradigm.single { it.route == A_ROUTE && it.shape == SHAPE_D1 }
        appendLine("读法（本节只作诊断，不改判读）：")
        appendLine(
            "- **生产口径**（parents+matched 取前 5）：v1 ${v1Prod.main}（${v1Prod.hits}/${v1Prod.total}） vs " +
                "臂 A ${aProd.main}（${aProd.hits}/${aProd.total}）⇒ 差 ${f4(aProd.main - v1Prod.main)}" +
                "（${if (aProd.main > v1Prod.main) "FTS5 排序在生产口径下更高" else "FTS5 排序在生产口径下不更高"}）",
        )
        appendLine(
            "- **返回形态的作用**（同一条排序，只换形态）：臂 A matched-only ${aMatchedOnly.main} − 生产形 " +
                "${aProd.main} = ${f4(aMatchedOnly.main - aProd.main)}；v1 matched-only ${v1MatchedOnly.main} − 生产形 " +
                "${v1Prod.main} = ${f4(v1MatchedOnly.main - v1Prod.main)}——生产口径下被父节点挤掉的名次" +
                "（父节点不是金标预期节点的原子节点，占位即丢分）",
        )
        appendLine(
            "- **D1 候选形态**（matched 前置、父节点排其后）：臂 A ${aD1.main}（${aD1.hits}/${aD1.total}）、" +
                "v1 ${v1D1.main}（${v1D1.hits}/${v1D1.total}）——父节点仍在序列里（供上层解释），但不再进判分窗口前 5",
        )
        appendLine(
            "- 结论（诊断段口径）：**生产口径下 FTS5 排序相对 v1 的 Recall 增益不成立**——" +
                "增益只在改变返回形态（matched 优先 / 后置父节点）后才出现；是否改返回形态属待裁项（D1）。",
        )
        appendLine()
        appendLine("## 11. 判读（按 §1 机械求值，判读线在出数前写死）")
        appendLine()
        val passed = chosen.main >= MAIN_THRESHOLD && chosen.chapterMin >= CHAPTER_MIN_THRESHOLD
        appendLine(
            "- chosenArm = **${chosen.name}**（可上生产臂：A / B / C-2stage / D；C-上界与 T 不参评，A' 为一致性对照不构成独立候选）",
        )
        appendLine("- 判读线：主集 Recall@5 ≥ $MAIN_THRESHOLD 且逐章最小 ≥ $CHAPTER_MIN_THRESHOLD")
        appendLine("- 实测：主集 ${chosen.main}、逐章最小 ${chosen.chapterMin} ⇒ **${if (passed) "过线" else "未过线"}**")
        val upper = arms.single { it.name == "C-upper" }
        appendLine(
            "- 参考（非判读对象）：臂 C-上界（章完全给对的天线口径）主集 ${upper.main}、逐章最小 ${upper.chapterMin} ⇒ " +
                "章定位不构成瓶颈方向；两段式实际推断正确的题数见 §8 汇总。",
        )
        if (passed) {
            appendLine("- 结论：词面栈够用（dense 不立项）；生产实现（WP-D）另按生产约束落地。")
        } else {
            appendLine(
                "- 结论：Stage-1 未达标 ⇒ dense 转立项（大档 Qwen3-Embedding-0.6B 量级，仍需用户另裁）。",
            )
        }
    }

    private fun verdictNotes(
        arms: List<Stage1LexicalLab.Arm>,
        chosen: Stage1LexicalLab.Arm,
        baseline: Measurement,
        scans: List<Stage1LexicalLab.Arm>,
    ): String = buildString {
        append("chosenArm=").append(chosen.name)
        append("（可上生产臂中主集最高；参评集 = A/B/C-2stage/D）")
        append("；判读数取自固定权威参数 k1=").append(K1).append(" b=").append(B)
        append("（FTS5 硬编码，不可调；判读线 主集≥").append(MAIN_THRESHOLD)
        append(" 且逐章≥").append(CHAPTER_MIN_THRESHOLD).append("）")
        append("；基线 = 预注册 v1 裸 B5 真 SQL 主集 ").append(BASELINE_MAIN).append("/MRR ").append(BASELINE_MRR)
        append("，本会话同构建实测 JVM 镜像 B 路 主集 ").append(baseline.main).append("（").append(baseline.hits)
        append("/").append(baseline.total).append("）/MRR ").append(baseline.mrr)
        append("；索引域 = 全量节点（含 TOPIC），按科/状态过滤写在 WHERE，故 bm25 的 N/avgdl/df 在整表域上算")
        append("；臂 B 的\"组间 AND\"落地为组覆盖排序键（本语料组=节点连通分量，覆盖∈{0,1}），硬 AND（取交集）不可用，差异已在报告 §7 注明")
        append("；臂 C-上界与臂 T 为非判读对照（不参评），A' 为 A 的一致性对照（同数即证 FTS5 排序口径被正确理解）")
        append("；扫描诊断（k1/b 变体、K 变体、去覆盖层、父节点前置）全部标注非判读数，见报告 §9")
        val a = arms.single { it.name == "A" }
        val prim = arms.single { it.name == "A'" }
        append("；A/A' 一致性：主集 ").append(a.hits).append("/").append(a.total)
        append(" vs ").append(prim.hits).append("/").append(prim.total)
        append("，MRR ").append(a.mrr).append(" vs ").append(prim.mrr)
        append("，逐题名次与 top5 序列相等 = ").append(ftsVsCustomAgree(arms))
        // 判分口径说明（2026-09-23 口径对照）：臂 A 的判分数是 matched-only 口径，生产 store 是
        // parents+matched ⇒ 必须把"生产口径下 A 的实际值"与基线放在同一句里，免得把 0.6556 当成生产成绩。
        val scanParentPrefix = scans.single { it.name == "scan-A-parentprefix" }
        append("；判分口径说明：臂 A 的 ").append(f4(a.main)).append("/").append(f4(a.mrr))
        append(" 是 **matched-only 口径**（无父节点前置），**不是生产判分口径**；生产口径（store 的 ")
        append("parents+matched 取前 5，RoomKnowledgeBaseStore.kt:125-133）下臂 A = ")
        append(f4(scanParentPrefix.main)).append("/").append(f4(scanParentPrefix.chapterMin))
        append("/").append(f4(scanParentPrefix.mrr)).append("（scan-A-parentprefix），基线 v1 生产口径 = ")
        append(BASELINE_MAIN).append("（").append(baseline.hits).append("/").append(baseline.total)
        append("）/MRR ").append(BASELINE_MRR)
        append("；四组口径对照（v1 × A，生产形 × matched-only）见报告 §10（诊断段，不改 §1 判读）")
    }

    /** 报告文本用的 4 位小数（与 §3 基线段同一格式）。 */
    private fun f4(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    companion object {
        const val SCORED_TOP_K = Stage1LexicalLab.SCORED_TOP_K

        /** 判读线与判分口径的**常量镜像**：只用于报告/备注文本，判读由编排脚本机械求值。 */
        const val MAIN_THRESHOLD = 0.75
        const val CHAPTER_MIN_THRESHOLD = 0.60

        /** 非判读数：判读配置固定 k1=1.2 / b=0.75。 */
        const val K1 = Stage1LexicalLab.K1
        const val B = Stage1LexicalLab.B
        const val WIDE_RECALL_K = Stage1LexicalLab.WIDE_RECALL_K

        const val MISS_PROBE = RetrievalBenchmark.MISS_PROBE_LIMIT
        const val BASELINE_MAIN = Stage1LexicalLab.BASELINE_MAIN
        const val BASELINE_MRR = Stage1LexicalLab.BASELINE_MRR

        const val UNICODE61 = "unicode61"
        const val TRIGRAM = "trigram"

        /** §10 口径对照的两条候选排序与三种返回形态（渲染、断言与备注共用同一份字面量）。 */
        const val V1_ROUTE = "v1 COUNT(DISTINCT feature)（裸 B 路 SQL 排序镜像）"
        const val A_ROUTE = "FTS5 bm25（臂 A）"
        const val SHAPE_PRODUCTION = "生产形 parents+matched"
        const val SHAPE_MATCHED_ONLY = "matched-only（非生产口径·诊断）"
        const val SHAPE_D1 = "D1 候选 matched 前置"

        const val ROUTE_A = "stage1-A-fts5-bm25"
        const val ROUTE_APRIM = "stage1-Aprim-custom-bm25-sql"
        const val ROUTE_B = "stage1-B-alias-expansion"
        const val ROUTE_C_UPPER = "stage1-C-upper-chapter-oracle"
        const val ROUTE_C_2STAGE = "stage1-C-2stage-majority-chapter"
        const val ROUTE_D = "stage1-D-A+B+C2stage"
        const val ROUTE_T = "stage1-T-trigram"
    }
}
