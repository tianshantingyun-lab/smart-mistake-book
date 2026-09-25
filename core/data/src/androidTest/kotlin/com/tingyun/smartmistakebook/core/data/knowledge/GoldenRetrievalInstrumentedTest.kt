package com.tingyun.smartmistakebook.core.data.knowledge

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
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
 * **测量台 + 一组性能门 + 两条回归地板**：Recall/MRR 的质量**判据**仍是 D12 预注册的
 * （`docs/kb-vector-topic-decision.md`，出数后一次性判定，本测试不参与）；这里额外钉的
 * 两条是**回归地板**（主集 Recall@5 ≥ [RECALL_MAIN_FLOOR]、MRR ≥ [MRR_MAIN_FLOOR]），
 * 即"不得比 v1 生产形状基线更差"的告示牌，**不是质量目标**，也不得用来改判预注册结论。
 * 另一组硬断言是**墙钟门**，按"稠密腿是否在场"分流（用户 2026-09-25 裁定 Q1）：
 * 腿上 = 融合路由的新门（p50 ≤ [FUSED_P50_BUDGET_MILLIS] 且 p95 ≤ [FUSED_P95_BUDGET_MILLIS]），
 * 腿掉 = 纯词面旧门（p95 < [RECALL_P95_BUDGET_MILLIS]，150ms×CI 系数，沿用
 * [KnowledgeContextRetrievalInstrumentedTest] 的门与系数来源，**不撤**）。
 *
 * **诊断产物**：逐章分布（命中/总数 + MISS 数）与逐题 MISS 清单
 * （`query` + `expectedSlug` + `rank|absent`）打进 System.out（logcat 的 `System.out` 标签）。
 * `rank=N` ⇒ 预期节点在候选里、被更强的候选挤到第 N（排序问题）；`absent` ⇒ 放宽窗口
 * （本路线返回序列前 [MISS_PROBE_LIMIT] 名）内根本不存在（索引/特征问题）。
 * 逐题 MISS 清单**另落一份账本**（2026-09-25 加）：`build/golden-fused-misses.txt`，
 * 一行一题、与打印行逐字一致（同一渲染 [missLine]）——`println` 只活在 logcat 抓取里，
 * 日志一滚下一轮就没有稳定靶子可对照。仪表化进程跑在设备上，写不到仓库树（工作目录是
 * `/`，JVM 侧那套按 `settings.gradle.kts` 上溯的 `locateRepoRoot()` 在这里恒为 null），
 * 账本落在设备共享存储的 `Download/`（MediaStore）、测试把设备路径与取回命令打进日志
 * （见 [writeMissLedger]）；条数与逐行对齐由本测试内的产物契约断言钉住（见
 * [assertMissLedgerMatchesScores]）。
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
            val probeOrder = activeReranker.order(probe.subject, probe.query, probeCandidates)
            assertNotNull(
                "稠密腿在设备上不可用（编码/资产/模型任一步失败都会静默回退到纯词面）——" +
                    "本测试测的是融合路由，回退时必须红",
                probeOrder,
            )
            // 同一条探针的结果**顺带选墙钟门**（用户 2026-09-25 裁定 Q1，见 FUSED_* 常量）：
            // 腿活着 ⇒ 融合路由的新门；腿掉了 ⇒ 上面那行已经红了，且墙钟仍按**纯词面旧门**
            // （[RECALL_P95_BUDGET_MILLIS]，不撤）判，不许拿融合门宽松过关。
            val denseLegLive = probeOrder != null

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
            // 墙钟门**按腿是否在场分流**（用户 2026-09-25 裁定 Q1）：
            // - 腿上（本测试的正常形态）：融合路由的新门 = p50 ≤ FUSED_P50_BUDGET_MILLIS
            //   且 p95 ≤ FUSED_P95_BUDGET_MILLIS。**这是新路由的新门，不是放宽旧门**——
            //   旧的 150ms p95 门是给纯词面腿定的，融合路由多一次编码器前向，物理上不同量。
            // - 腿掉（纯词面回退路径）：旧门原样保留 = p95 < RECALL_P95_BUDGET_MILLIS
            //   （150ms×CI 系数）；旧门本来就没有 p50 项，这里也不加。
            val p95BudgetMillis = if (denseLegLive) FUSED_P95_BUDGET_MILLIS else RECALL_P95_BUDGET_MILLIS
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
                    " denseLegLive=" + denseLegLive +
                    " p95Budget=" + p95BudgetMillis + "ms p50Budget=" +
                    (if (denseLegLive) FUSED_P50_BUDGET_MILLIS else 0L) + "ms " +
                    "overBudgetSamples=" + elapsedMillis.count { s -> s >= p95BudgetMillis },
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
            // 逐题 MISS 行**先渲染一次**，打印与落盘共用（两侧逐字一致靠同一来源，不靠人眼对齐）。
            val missLines = misses.map { miss -> missLine(miss) }
            val missLedger = writeMissLedger(context, missLines)
            println(
                "MISS 清单（" + misses.size + "/" + perCase.size + " 例；rank = 预期节点在本路线" +
                    "返回序列放宽窗口（前 " + MISS_PROBE_LIMIT + " 名）内的名次，absent = 该窗口内不存在）:",
            )
            if (misses.isEmpty()) {
                println("  （无 MISS：本路线 top-5 全命中）")
            } else {
                missLines.forEach { println(it) }
            }
            // 产物契约断言（不是质量门）：条数 == 该次未命中数、逐行与判分结果对齐。
            assertMissLedgerMatchesScores(context, missLedger, misses)
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
                "real-SQL recall p95 was " + p95 + "ms (budget " + p95BudgetMillis + "ms, denseLegLive=" +
                    denseLegLive + ", fusedP95Budget=" + FUSED_P95_BUDGET_MILLIS +
                    "ms, lexicalP95Budget=" + RECALL_P95_BUDGET_MILLIS + "ms); n=" +
                    elapsedMillis.size + " p50=" + p50 + "ms max=" + elapsedMillis.max(),
                p95 < p95BudgetMillis,
            )
            // p50 门只属于融合路由（纯词面旧门没有 p50 项，回退路径不设）：融合路由的"慢"
            // 主要是编码器前向的固定开销 + 偶发抖动，p50 才是"这条路由常态快不快"的量。
            if (denseLegLive) {
                assertTrue(
                    "real-SQL recall p50 was " + p50 + "ms (fused budget " + FUSED_P50_BUDGET_MILLIS +
                        "ms, denseLegLive=true); n=" + elapsedMillis.size + " p95=" + p95 +
                        "ms max=" + elapsedMillis.max(),
                    p50 <= FUSED_P50_BUDGET_MILLIS,
                )
            }
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
     * 一条 MISS 的**唯一渲染**：logcat 打印与落盘账本共用同一个函数，两侧逐字一致由构造保证
     * （`  MISS [SUBJECT] <query> | expectedSlug=<slug> | chapter=<chapter> | rank=<N|absent>`，
     * 与 JVM 镜像 `RetrievalBenchmark.goldenMissLines` 同格式、可逐行对照）。
     */
    private fun missLine(miss: GoldenMiss): String =
        "  MISS [" + miss.subject + "] " + miss.query +
            " | expectedSlug=" + miss.expectedSlug +
            " | chapter=" + miss.chapter +
            " | rank=" + (if (miss.probeRank > 0) miss.probeRank.toString() else "absent")

    /**
     * MISS 账本**落盘**：逐题一行，无 MISS 时写空文件（"存在且 0 条"与"根本没落盘"必须可区分）。
     *
     * **它消灭的失败**：逐题 MISS 原先只有 `println`，只活在 logcat 抓取里
     * （`build/golden-run.log` / `build/stage3-recheck-logcat.txt` 一类）——日志一滚、或换个人跑，
     * 靶子就没有稳定账本，"改绑/补特征之后 MISS 是否真的少了"只能靠人眼比两份 logcat。
     *
     * **为什么落点不是仓库树**：仪表化进程跑在设备上（`connectedDebugAndroidTest`），
     * 工作目录是 `/`，JVM 侧的 `locateRepoRoot()`（从工作目录上溯找 `settings.gradle.kts`）
     * 在这里恒为 null——照搬它写 `build/…` 只会在设备上抛异常。
     *
     * **为什么也不用 app 私有外部目录**（`getExternalFilesDir(null)`，本仓
     * `SchemaDumpInstrumentedTest` 的取法）：`connectedDebugAndroidTest` 跑完会把测试 APK
     * 卸掉，`Android/data/<pkg>/` 整目录随之消失——2026-09-25 实测两次：账本写于 04:11:21
     * （logcat 有"账本已落盘 lines=23"），跑完 2 秒内该目录已不存在，事后补 `adb pull` 取不到。
     * 账本是给**下一轮**对照用的靶子，必须活过一次运行 ⇒ 落共享存储的 `Download/`（MediaStore：
     * 官方存储表里 app 私有外部存储"卸载即删"、媒体/文档"不随卸载删除"；本仓 PDF 导出用例
     * 已在同一台模拟器上走通这条写路径；实测跑完卸载 APK 后账本仍在）。
     *
     * **文件名带运行戳**（`golden-fused-misses-<epochMillis>.txt`）：上一轮 APK 卸载后，它留下的
     * MediaStore 条目成了无主行（`owner_package_name=NULL`），新一轮**既删不掉也覆盖不了**——
     * 2026-09-25 实测：带 `LIKE` 的 `resolver.delete` 命中 0 行，第二次运行按固定名落盘被
     * MediaProvider 改名成 `golden-fused-misses (1).txt`，于是按固定名取回读到的是**上一轮**的账本
     * （错靶子比没靶子更坏）。所以运行戳写进文件名，取回按**日志里那条命令给出的确切路径**：
     *
     * ```
     * adb pull <日志里的设备路径> build/golden-fused-misses.txt
     * # 或取最新一份：adb shell "ls -t /sdcard/Download/golden-fused-misses-*.txt | head -n1"
     * # 清理历史：  adb shell rm /sdcard/Download/golden-fused-misses-*.txt
     * ```
     */
    private fun writeMissLedger(context: android.content.Context, missLines: List<String>): Uri {
        require(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            "账本落盘走 MediaStore.Downloads，需要 API 29+（本机 " + android.os.Build.VERSION.SDK_INT + "）"
        }
        val deviceName = MISS_LEDGER_STEM + "-" + System.currentTimeMillis() + ".txt"
        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, deviceName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    // 写完才露面：运行中有人抢着取回时读不到半截账本（本仓 PDF 导出用例同法）。
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        ) { "MediaStore 没建出账本条目（Download/ 不可写？）——账本无从落盘" }
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            out.write(renderedLedger(missLines).toByteArray(Charsets.UTF_8))
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        println(
            "MISS 账本已落盘 = " + MISS_LEDGER_DEVICE_DIR + deviceName + " lines=" + missLines.size +
                "（取回：adb pull " + MISS_LEDGER_DEVICE_DIR + deviceName +
                " build/" + MISS_LEDGER_NAME + "）",
        )
        return uri
    }

    /** 账本文本：一行一题，末尾带换行；无 MISS 时为空串（空文件 ≠ 没落盘）。 */
    private fun renderedLedger(missLines: List<String>): String =
        if (missLines.isEmpty()) "" else missLines.joinToString("\n") + "\n"

    /**
     * **产物契约断言**（不是质量门；写法参照 `GoldenRetrievalJvmTest.assertMissLedgerMatchesScores`）。
     *
     * 读的是**落盘那份**（经 `ContentResolver` 回读，不是拿内存里的清单自己比自己），所以它抓的
     * 失败很具体：账本没落盘 / 少写几行 / 落盘内容与判分结果不一致 / 打印与落盘两套渲染漂移
     * ——这些都会让"按账本比 MISS 集合"的人拿到错的靶子，而分数照样是绿的。
     * 分数阈值不在这里（预注册判据见 `docs/kb-vector-topic-decision.md`）。
     */
    private fun assertMissLedgerMatchesScores(
        context: android.content.Context,
        ledger: Uri,
        misses: List<GoldenMiss>,
    ) {
        val landed = requireNotNull(context.contentResolver.openInputStream(ledger)) {
            "落盘账本读不回来：" + ledger
        }.use { it.bufferedReader().readLines() }
        val missLines = landed.filter { it.trimStart().startsWith(MISS_LINE_MARK) }
        assertEquals(
            "落盘 MISS 条数应等于该次未命中数",
            misses.size,
            missLines.size,
        )
        assertEquals(
            "落盘 MISS 清单与判分结果不一致（逐题 query/expectedSlug/rank|absent 必须逐行对齐）",
            misses.map { missLine(it).trim() },
            missLines.map { it.trim() },
        )
        misses.forEach { miss ->
            assertTrue(
                "落盘 MISS 行未带 expectedSlug=" + miss.expectedSlug,
                missLines.any { it.contains("expectedSlug=" + miss.expectedSlug) },
            )
        }
    }

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

        /** 取回时落在仓库根 `build/` 下的账本名（与 JVM 侧 `golden-jvm-metrics.txt` 同处）。 */
        const val MISS_LEDGER_NAME = "golden-fused-misses.txt"

        /** 设备侧文件名主干：实际名 = `<主干>-<运行戳>.txt`（为什么必须带运行戳见 [writeMissLedger]）。 */
        const val MISS_LEDGER_STEM = "golden-fused-misses"

        /** 设备侧目录（`/sdcard` 就是 MediaStore 那个 Download/ 集合的 adb 别名）。 */
        const val MISS_LEDGER_DEVICE_DIR = "/sdcard/Download/"

        /**
         * 账本里的 MISS 行标识：落盘条数即按它计数——与 JVM 侧
         * `assertMissLedgerMatchesScores` 用 `MISS [` 数条数是同一判据。
         */
        const val MISS_LINE_MARK = "MISS ["

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
         *
         * **只在稠密腿不在场（纯词面回退路径）时生效**（见 [FUSED_P95_BUDGET_MILLIS] 的分流）：
         * 这条门是给纯词面腿定的，用户 2026-09-25 裁定 Q1 明确"旧门不撤"——腿上走新门，
         * 腿掉回退到这条旧门，退化的那条腿不能借融合门宽松过关。
         */
        val CI_MULTIPLIER: Long = run {
            val fromArgs = androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("ciSlowRunner")
            if (fromArgs != null || System.getenv("CI") != null) 4L else 1L
        }
        val RECALL_P95_BUDGET_MILLIS = 150L * CI_MULTIPLIER

        /**
         * **融合路由（稠密腿在场）的墙钟门**（用户 2026-09-25 裁定 Q1）：
         * p50 ≤ [FUSED_P50_BUDGET_MILLIS] 且 p95 ≤ [FUSED_P95_BUDGET_MILLIS]，各自乘 [CI_MULTIPLIER]。
         *
         * **这是新路由的新门，不是放宽旧门**：融合路由 = 词面腿 + 编码器前向（+ 资产扫描），
         * 与纯词面腿不是同一件事；拿纯词面腿的 150ms 门套它，只会得到一条恒红的门
         * （下面实测数就是证据），那不是"守预算"，是"门测错了对象"。
         *
         * 依据（本仓设备实测记账，逐条可复核）：
         * - WP0 改动**前**、同一台主机上本测试已量到 p95 176ms（`build/wp0-golden-device5-logcat.txt`：
         *   p95=176ms p50=116ms；兄弟轮 `build/wp0-golden-device*-logcat.txt`：175/116、159/113、
         *   153/120、141/115）——即融合腿本身就常越 150ms，旧门在这台主机上是**恒红**的，
         *   不是"守预算"（overBudgetSamples 那一档同样越门）；
         * - Stage-3 健康主机记账 134–148ms（`build/golden-run.log` p95=134ms p50=113ms、
         *   `build/stage3-recheck-logcat.txt` p95=148ms p50=115ms）；
         * - 裁定 Q1 同时给出的本轮记账 p50 124ms / p95 176–190ms：其中 p95 上沿 190ms 与 p50 124ms
         *   比本仓现有 logcat 略高（现有最高 p95=176ms、p50=120ms），按裁定材料记账、待补一行 logcat；
         * - 主机对照：同主机 KCR（[KnowledgeContextRetrievalInstrumentedTest]）对照基准 +30%，
         *   即换主机的波动可达 1.3x。
         * 余量：p95 250ms ≈ 实测上沿 190ms × 1.3；p50 150ms ≈ 实测 124ms × 1.2。
         *
         * 分流规则（同一条探针结果，见测试内 `denseLegLive`）：腿上按本组门判；
         * 腿掉 ⇒ 探针行当场红，且墙钟退回 [RECALL_P95_BUDGET_MILLIS]（旧门不撤）。
         */
        val FUSED_P50_BUDGET_MILLIS = 150L * CI_MULTIPLIER
        val FUSED_P95_BUDGET_MILLIS = 250L * CI_MULTIPLIER
    }
}
