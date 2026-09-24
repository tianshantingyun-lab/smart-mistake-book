package com.tingyun.smartmistakebook.core.data.knowledge

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.data.knowledge.dense.DenseRecallAssembly
import com.tingyun.smartmistakebook.core.database.DenseRecallCandidate
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * **金标集 × 真 SQL（档 2 仪表化）**——R3 评测同构的落地端。
 *
 * 与 JVM 测量台（`GoldenRetrievalJvmTest`）的关系：JVM 侧的 B 路是 SQL 排序的镜像，
 * **真 SQL 以本测试为准**。两条数对照，差值即"镜像与真库的口径漂移"，应当可见且小。
 *
 * 数据链（每一步都可复核）：
 * 1. 题面 = 冻结金标集。androidTest assets 里带的是**逐字节副本**（`main` 构建期由
 *    `:core:data:syncGoldenQueryAssets` 从 `tools/kb_coverage/tables/` 拷入，且接在
 *    `merge*AndroidTestAssets` 之前——不再靠手工同步）；本测试运行时对 assets 内的
 *    `.sha256` 封存复核完整性，并把算出的 hex 打进日志，与仓库根封存值人工交叉核对；
 *    仓库侧那份由 `GoldenRetrievalJvmTest`（档 1，每次 JVM 跑都验 sha256，并逐字节比对
 *    仓库份与副本）钉住。
 * 2. 包数据 = 真实 `BundledKnowledgeBaseInstaller.install`（随包 50MB JSON → Room，
 *    与生产同一入口）。
 * 3. 索引 = v1 截断规则（`KnowledgeSearchFeatureExtractor.INDEX_VERSION=1`；2026-09-22
 *    的 v2 去截断实验实测净伤害、当日回滚，见 `docs/kb-vector-topic-decision.md` §3.2）：
 *    全新库无版本锚点，首次按科召回时整科建索引（预热轮吸收，不计 p95）。
 * 4. 判分 = 生产 KNOWLEDGE_READ 的 v1 形状：`readSubjectKnowledgeRecallCandidates`
 *    （limit=5，裸 B 路，无 A 路精排）的 `matched + parents` 返回形态直接取前 5
 *    （**D1 落地后**（2026-09-24）：matched 在前、父 topic 随后，仍全部返回供上层解释；
 *    首测（§1.1）钉的是 D1 之前的旧形态 `parents + matched`，其真 SQL 值 0.5444 见下）；
 *    命中 = 预期 slug 的原子节点在列。
 *    本测试出的是**生产形状的 v1 基线数**（v1 索引 × 裸 B5 = 回滚后的生产形状）；
 *    D1 之前的旧生产形真 SQL 值 = 0.5444 / MRR 0.1511（历史记账，见回归地板段）；
 *    v2 实验的两组数（v2 索引 × 裸 B5 首测 0.5222、v2 索引 × B512→A 0.3444/p95 663ms）
 *    封存于同一文档 §3/§3.1，与上面各组数并列对照。
 *
 * **测量台 + 一条性能门 + 两条回归地板**：Recall/MRR 的质量**判据**仍是 D12 预注册的
 * （`docs/kb-vector-topic-decision.md`，出数后一次性判定，本测试不参与）；这里额外钉的
 * 两条是**回归地板**（主集 Recall@5 ≥ [RECALL_MAIN_FLOOR]、MRR ≥ [MRR_MAIN_FLOOR]），
 * 即"不得比 v1 生产形状基线更差"的告示牌，**不是质量目标**，也不得用来改判预注册结论。
 * 另一条硬断言是 p95 预算 150ms×CI 系数（沿用
 * [KnowledgeContextRetrievalInstrumentedTest] 的门与系数来源）。
 *
 * **诊断产物**：逐章分布（命中/总数 + MISS 数）与逐题 MISS 清单
 * （`query` + `expectedSlug` + `rank|absent`）打进 System.out（logcat 的 `System.out` 标签）。
 * `rank=N` ⇒ 预期节点在候选里、被更强的候选挤到第 N（排序问题）；`absent` ⇒ 放宽窗口
 * （本路线返回序列前 [MISS_PROBE_LIMIT] 名）内根本不存在（索引/特征问题）。
 * 窗口口径与 JVM 镜像逐字相同、清单格式也一致，两侧 MISS **集合**可逐题对照
 * （2026-09-23 实测对称差 0）；**名次**只在本侧窗口内解释——父节点随后块的条数与序不同
 * （镜像按包内出现序、真 SQL 按 rowid 序），同样的题可差 1~3 位。
 *
 * 说明：任务书写的"in-memory Room"在本模块不可达——`openInMemory` 是
 * `:core:database` 的 internal，而金标集与安装器都在 `:core:data`；本测试因此沿用
 * 参照测试（KnowledgeContextRetrievalInstrumentedTest）的具名文件库 + 跑完即删，
 * 真 SQL/真索引/真安装一点不减。
 */
@RunWith(AndroidJUnit4::class)
class GoldenRetrievalInstrumentedTest {
    @Test
    fun goldenSetProductionRouteScoresMainAndPerChapterWithP95Budget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "golden-retrieval-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        // **融合路由必须真的生效**：生产在 `SmartMistakeBookApplication` 里这样装配
        // （`StudyDatabaseFactory.open(this, denseRerank = DenseRecallAssembly.reranker(this))`）。
        // 不装配时 store 走纯词面回退，本测试会安静地量到旧基线（2026-09-24 实测：主集
        // 0.6444 / p95 18ms）——那就是"测了另一个路由"。装配 + 下面那条"腿是活的"断言，
        // 一起把这种静默回退堵掉。
        val denseRerank = DenseRecallAssembly.reranker(context)
        val activeReranker = requireNotNull(denseRerank) {
            "稠密腿装配返回 null（ENABLED=false 或装配失败）——本测试测的是融合路由，不能静默降级"
        }
        val store = StudyDatabaseFactory.open(context, databaseName, denseRerank = activeReranker)
        try {
            BundledKnowledgeBaseInstaller.install(store)

            val cases = loadGoldenFromAssets(context)
            assertTrue("金标集条数低于 D12 下限 80：${cases.size}", cases.size >= 80)
            assertTrue("金标集条数超过 D12 上限 100：${cases.size}", cases.size <= 100)

            // 稠密腿"是活的"探测：拿第一条题的真候选跑一次重排，返回 null 说明编码/资产/模型
            // 有一步在设备上失败了（那是设计内的静默回退，但本测试要测的是**融合**路由，
            // 静默回退必须当场红，不能把旧基线的数当成新路由的数）。
            val probe = cases.first()
            val probeCandidates = store.readSubjectKnowledgeRecallCandidates(
                subject = probe.subject,
                searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(probe.query),
                limit = SCORED_TOP_K,
            ).map { DenseRecallCandidate(it.knowledgeNodeId, 1) }
            assertTrue("探测用候选为空，无法判断稠密腿是否可用", probeCandidates.isNotEmpty())
            assertNotNull(
                "稠密腿在设备上不可用（编码/资产/模型任一步失败都会静默回退到纯词面）——" +
                    "本测试测的是融合路由，回退时必须红",
                activeReranker.order(probe.subject, probe.query, probeCandidates),
            )

            // 预热：每科一次生产路由（首次触发该科的整科索引构建——一次性成本，不入 p95 统计）。
            cases.map { it.subject }.distinct().forEach { subject ->
                val warmupFeatures = KnowledgeSearchFeatureExtractor.fromQuestion("预热召回")
                store.readSubjectKnowledgeRecallCandidates(
                    subject = subject,
                    searchFeatures = warmupFeatures,
                    limit = SCORED_TOP_K,
                    // 带 queryText：让稠密腿的**一次性**加载（模型 62MB + 资产 17MB）落在预热轮里，
                    // 而不是混进 p95 的样本（预热轮不参与判分）。
                    queryText = cases.first { it.subject == subject }.query,
                )
            }

            val elapsedMillis = mutableListOf<Long>()
            val perCase = cases.map { case ->
                var hits = 0
                var bestRank = 0
                repeat(SAMPLES_PER_QUERY) {
                    // 生产 KNOWLEDGE_READ 的 v1 形状：裸 B 路 limit=5（无 A 路精排；
                    // B512→A 统一路由 2026-09-22 实测净伤害后已回滚，见 KD-24）。
                    // p95 计的是这条生产路由本身，与工具执行器实跑同构。
                    val started = SystemClock.elapsedRealtimeNanos()
                    val recall = store.readSubjectKnowledgeRecallCandidates(
                        subject = case.subject,
                        searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(case.query),
                        // 候选深度 = 512（生产 `MAX_KNOWLEDGE_RECALL_CANDIDATES`，也是
                        // `build/stage3-device-expectation.json` 的参考口径）：稠密腿只能**重排**
                        // 候选域里的成员，域只有 5 条时融合再准也拉不进新节点（实测：limit=5
                        // 时主集恒为 0.6444、只有 MRR 动；512 宽召回 + 融合才是 +8.9pp 的那条路）。
                        limit = RECALL_DEPTH,
                        // 生产三个调用点都传 queryText（RoomTutorKnowledgeContextLoader /
                        // RoomMistakeOrganizationRepository / RoomTutorToolRunner）；不传 ⇒
                        // store 按契约走词面次序，本测试就不是"融合路由"了。
                        queryText = case.query,
                    )
                    elapsedMillis += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                    assertTrue(
                        "${case.subject} 召回出现跨科节点（披露边界被破坏）",
                        recall.all { it.subject == case.subject },
                    )
                    // 判分口径与首测钉定（§1.1）：store 的返回形态**直接取前 5**。D1 落地后
                    // （2026-09-24）形态 = `matched + parents`：matched 在前、父 topic 随后
                    // （父节点仍全部返回供上层解释，但不再挤占判分窗口）。
                    val candidates = recall.take(SCORED_TOP_K)
                    val rank = candidates.indexOfFirst {
                        it.knowledgeNodeId.endsWith(ATOMIC_SUFFIX + case.expectedSlug)
                    }.let { if (it < 0) 0 else it + 1 }
                    if (rank > 0) hits += 1
                    bestRank = if (bestRank == 0) rank else minOf(bestRank, rank)
                }
                GoldenCaseScore(
                    subject = case.subject,
                    chapter = case.chapter,
                    query = case.query,
                    expectedSlug = case.expectedSlug,
                    hitInAllSamples = hits == SAMPLES_PER_QUERY,
                    hitInAnySample = hits > 0,
                    bestRank = bestRank,
                )
            }

            val p95 = elapsedMillis.percentile95()
            val p50 = elapsedMillis.percentile(50)
            val hitAllCount = perCase.count { it.hitInAllSamples }
            val hitAnyCount = perCase.count { it.hitInAnySample }
            val recallMainAll = hitAllCount.toDouble() / perCase.size
            val recallMainAny = hitAnyCount.toDouble() / perCase.size
            val mrrMain = perCase.map { if (it.bestRank > 0) 1.0 / it.bestRank else 0.0 }.average()
            val chapterStats = perCase.groupBy { it.chapter }.map { entry ->
                Triple(entry.key, entry.value.count { s -> s.hitInAllSamples }, entry.value.size)
            }
            // MISS 诊断（**判分口径不动**：上面的 top-5 分数仍来自裸 B 路 limit=5 的那次调用）。
            // 只对未命中的题放宽深度再召一次，回答"排到第几 / 到底在不在"——D-01 的两种病因
            // 靠这一个数分开：rank=N 是排序问题（IDF/长度归一化能救），absent 是索引/特征问题。
            val misses = perCase.filter { s -> !s.hitInAnySample }.map { s ->
                val probe = store.readSubjectKnowledgeRecallCandidates(
                    subject = s.subject,
                    searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(s.query),
                    limit = RECALL_DEPTH,
                    queryText = s.query,
                )
                // `take` 不能省：store 返回"matched 优先 + 父节点随后"，序列可长过深度，
                // 不截断就会报出超过声明深度的名次（2026-09-23 实测过一次 rank=284>256）。
                val probeRank = probe.take(MISS_PROBE_LIMIT).indexOfFirst {
                    it.knowledgeNodeId.endsWith(ATOMIC_SUFFIX + s.expectedSlug)
                }.let { if (it < 0) 0 else it + 1 }
                GoldenMiss(
                    subject = s.subject,
                    chapter = s.chapter,
                    query = s.query,
                    expectedSlug = s.expectedSlug,
                    probeRank = probeRank,
                )
            }
            // 先落全部数、最后断言——断言红了数也不丢（第一版断言在输出前，p95 一红指标全失）。
            // 输出用**纯字符串拼接**（不依赖 `$it.属性` 模板插值）：本环境实测过插值渲染异常，
            // 拼接形式在 dex 里是确定的字节码，排除这一类干扰。
            println("=== golden-instrumented (真 SQL，档 2) ===")
            println(
                "route=B-bare->top5(v1生产形状, matched-first) indexVersion=" +
                    KnowledgeSearchFeatureExtractor.INDEX_VERSION +
                    " cases=" + perCase.size + " samplesPerQuery=" + SAMPLES_PER_QUERY +
                    " p95=" + p95 + "ms p50=" + p50 + "ms max=" + elapsedMillis.max() +
                    " budget=" + RECALL_P95_BUDGET_MILLIS + "ms " +
                    "overBudgetSamples=" + elapsedMillis.count { s -> s >= RECALL_P95_BUDGET_MILLIS },
            )
            println("主集 Recall@5(全样本命中) = " + recallMainAll + " (" + hitAllCount + "/" + perCase.size + ")")
            println("主集 Recall@5(任一样本命中) = " + recallMainAny)
            println("MRR(最优名次) = " + mrrMain)
            println("逐章分布（命中/总数 = Recall@5, MISS 数）:")
            chapterStats.forEach { (chapter, hitCount, size) ->
                println(
                    "  " + chapter + " = " + hitCount + "/" + size + " = " +
                        hitCount.toDouble() / size + " (MISS " + (size - hitCount) + ")",
                )
            }
            println(
                "MISS 清单（" + misses.size + "/" + perCase.size + " 例；rank = 预期节点在本路线" +
                    "返回序列放宽窗口（前 " + MISS_PROBE_LIMIT + " 名）内的名次，absent = 该窗口内不存在）:",
            )
            if (misses.isEmpty()) {
                println("  （无 MISS：本路线 top-5 全命中）")
            } else {
                misses.forEach { miss ->
                    println(
                        "  MISS [" + miss.subject + "] " + miss.query +
                            " | expectedSlug=" + miss.expectedSlug +
                            " | chapter=" + miss.chapter +
                            " | rank=" + (if (miss.probeRank > 0) miss.probeRank.toString() else "absent"),
                    )
                }
            }
            // 回归地板（**下界，不是质量目标**）：基线 = v1 生产形状（D1 落地后 = matched 优先）
            // 真 SQL 实测 0.6444 / 0.5637（2026-09-24 复跑；D1 之前旧生产形 0.5444 / 0.1511 为历史记账）。
            // 地板故意贴紧基线下沿：这是"不得更差"的告示牌，任何真实退化都该立刻红，
            // 不是达标线；D12 预注册判据（主集 ≥0.75 且逐章 ≥0.60）另行一次性判定，
            // 与本地板互不影响。基线提升后同步抬高并记录旧值（旧地板：0.54 / 0.15）。
            assertTrue(
                "主集 Recall@5(全样本命中) = " + recallMainAll + " 低于回归地板 " + RECALL_MAIN_FLOOR +
                    "（v1 生产形状基线 0.6444；这是不得更差的下界，不是质量目标）",
                recallMainAll >= RECALL_MAIN_FLOOR,
            )
            assertTrue(
                "主集 MRR = " + mrrMain + " 低于回归地板 " + MRR_MAIN_FLOOR +
                    "（v1 生产形状基线 0.5637；这是不得更差的下界，不是质量目标）",
                mrrMain >= MRR_MAIN_FLOOR,
            )
            assertTrue(
                "real-SQL recall p95 was ${p95}ms (budget ${RECALL_P95_BUDGET_MILLIS}ms); " +
                    "n=${elapsedMillis.size} p50=${p50}ms max=${elapsedMillis.max()}",
                p95 < RECALL_P95_BUDGET_MILLIS,
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private data class GoldenCase(
        val subject: String,
        val chapter: String,
        val query: String,
        val expectedSlug: String,
    )

    private data class GoldenCaseScore(
        val subject: String,
        val chapter: String,
        val query: String,
        val expectedSlug: String,
        val hitInAllSamples: Boolean,
        val hitInAnySample: Boolean,
        val bestRank: Int,
    )

    /**
     * 一条未命中 top-5 的题的**放宽诊断**（判分口径不动，只回答"再放宽能看到第几"）。
     * 与 JVM 侧 `RetrievalBenchmark.GoldenMiss` 同名同义，日志格式也一致，两侧可逐行对照。
     */
    private data class GoldenMiss(
        val subject: String,
        val chapter: String,
        val query: String,
        val expectedSlug: String,
        /** 放宽窗口（返回序列前 [MISS_PROBE_LIMIT] 名）内预期节点的 1 起始名次；0 = absent。 */
        val probeRank: Int,
    )

    /**
     * assets 副本 + 封存复核。sha256 对不上 = 副本与封存脱节（构建期拷错/被改）⇒ 红。
     * 算出的 hex 打日志，与仓库根 `golden_queries_v1.json.sha256` 交叉核对。
     */
    private fun loadGoldenFromAssets(context: android.content.Context): List<GoldenCase> {
        val json = context.assets.open(ASSET_JSON).use { it.bufferedReader().readText() }
        val seal = context.assets.open(ASSET_SHA256).use { it.bufferedReader().readText().trim() }
        val actual = sha256Hex(json.toByteArray(Charsets.UTF_8))
        assertEquals("金标集 assets 副本与 sha256 封存不符", seal, actual)
        println("golden-assets sha256=$actual（应与仓库根封存一致）")
        val array = Json.parseToJsonElement(json).jsonArray
        return array.map { element ->
            val value = element.jsonObject
            GoldenCase(
                subject = value.stringField("subject"),
                chapter = value.stringField("chapter"),
                query = value.stringField("query"),
                expectedSlug = value.stringField("expectedSlug"),
            )
        }
    }

    private fun JsonObject.stringField(name: String): String {
        val primitive = this[name] as? JsonPrimitive
            ?: error("金标集字段 $name 不是字符串")
        val content = primitive.contentOrNull
            ?: error("金标集字段 $name 不是字符串")
        assertTrue("金标集字段 $name 为空", content.isNotBlank())
        return content
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun List<Long>.percentile(percent: Int): Long {
        require(isNotEmpty())
        val sorted = sorted()
        val index = ((sorted.size * percent + 99) / 100 - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun List<Long>.percentile95(): Long = percentile(95)

    private companion object {
        const val ASSET_JSON = "golden/golden_queries_v1.json"
        const val ASSET_SHA256 = "golden/golden_queries_v1.json.sha256"
        const val SCORED_TOP_K = 5

        /**
         * 候选深度 = 生产 `MAX_KNOWLEDGE_RECALL_CANDIDATES`（512）。判分窗口仍是 D1 形态的
         * 前 [SCORED_TOP_K] 名；深度决定的是**稠密腿能重排多大的域**——参考数
         * （`build/stage3-device-expectation.json`，主集 0.7333）就是在 512 宽召回上算的。
         */
        const val RECALL_DEPTH = 512
        const val SAMPLES_PER_QUERY = 5
        const val ATOMIC_SUFFIX = ":atomic:"

        /**
         * MISS 诊断的**放宽深度**——不是判分口径（判分固定 top-5）。
         * 名次口径 = 候选放宽到本深度后，**返回序列（matched 优先 + 父节点随后）前 256 名**的窗口内位置。
         * 与 JVM 镜像的 `RetrievalBenchmark.MISS_PROBE_LIMIT` 同值同口径：两侧 MISS 集合逐题
         * 可对照；名次因父节点随后块的序不同（镜像按包内序、真 SQL 按 rowid 序）可差 1~3 位，
         * 只在本侧窗口内解释。
         */
        const val MISS_PROBE_LIMIT = 256

        /**
         * 回归地板（**下界，不是质量目标**）：v1 生产形状（v1 截断索引 × 裸 B5）真 SQL 基线
         * 主集 Recall@5 = 0.6444、MRR = 0.5637（**D1 落地后**（2026-09-24）的 matched 优先形态，
         * 本测试实测）。地板贴紧基线下沿是有意的：它是"不得更差"的告示牌，任何真实退化都该当场红。
         * 基线提升后同步抬高并记录旧值 —— 旧值（D1 之前的 parents 前置形态）：
         * 真 SQL 基线主集 0.5444 / MRR 0.1511 ⇒ 旧地板 0.54 / 0.15。
         * 与 D12 预注册判据（主集 ≥0.75 且逐章 ≥0.60）互不影响，不得用本地板改判预注册结论。
         */
        const val RECALL_MAIN_FLOOR = 0.64
        const val MRR_MAIN_FLOOR = 0.56

        /**
         * 与参照测试同源的门：150ms×CI 系数（CI 的 runner 模拟器慢 ~2-3x，
         * 系数经 instrumentation 参数 ciSlowRunner 传入——runner 的环境变量进不了设备进程）。
         */
        val CI_MULTIPLIER: Long = run {
            val fromArgs = androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("ciSlowRunner")
            if (fromArgs != null || System.getenv("CI") != null) 4L else 1L
        }
        val RECALL_P95_BUDGET_MILLIS = 150L * CI_MULTIPLIER
    }
}
