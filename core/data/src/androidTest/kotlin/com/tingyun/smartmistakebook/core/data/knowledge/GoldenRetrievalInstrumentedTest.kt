package com.tingyun.smartmistakebook.core.data.knowledge

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * 1. 题面 = 冻结金标集。androidTest assets 里带的是**逐字节副本**（构建时从
 *    `tools/kb_coverage/tables/` 拷入）；本测试运行时对 assets 内的 `.sha256` 封存复核
 *    完整性，并把算出的 hex 打进日志，与仓库根封存值人工交叉核对；
 *    仓库侧那份由 `GoldenRetrievalJvmTest`（档 1，每次 JVM 跑都验 sha256）钉住。
 * 2. 包数据 = 真实 `BundledKnowledgeBaseInstaller.install`（随包 50MB JSON → Room，
 *    与生产同一入口）。
 * 3. 索引 = v1 截断规则（`KnowledgeSearchFeatureExtractor.INDEX_VERSION=1`；2026-09-22
 *    的 v2 去截断实验实测净伤害、当日回滚，见 `docs/kb-vector-topic-decision.md` §3.2）：
 *    全新库无版本锚点，首次按科召回时整科建索引（预热轮吸收，不计 p95）。
 * 4. 判分 = 生产 KNOWLEDGE_READ 的 v1 形状：`readSubjectKnowledgeRecallCandidates`
 *    （limit=5，裸 B 路，无 A 路精排）的 `parents + matched` 返回形态直接取前 5（§1.1
 *    钉定的首测口径），命中 = 预期 slug 的原子节点在列。
 *    本测试出的是**缺失的 v1 基线数**（v1 索引 × 裸 B5 = 回滚后的生产形状）；
 *    v2 实验的两组数（v2 索引 × 裸 B5 首测 0.5222、v2 索引 × B512→A 0.3444/p95 663ms）
 *    封存于同一文档 §3/§3.1，三组数并列对照。
 *
 * **测量台 + 一条性能门**：Recall/MRR **不断言阈值**（D12 预注册判据
 * `docs/kb-vector-topic-decision.md`，出数后一次性判定）；唯一硬断言是 p95 预算
 * 150ms×CI 系数（沿用 [KnowledgeContextRetrievalInstrumentedTest] 的门与系数来源）。
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
        val store = StudyDatabaseFactory.open(context, databaseName)
        try {
            BundledKnowledgeBaseInstaller.install(store)

            val cases = loadGoldenFromAssets(context)
            assertTrue("金标集条数低于 D12 下限 80：${cases.size}", cases.size >= 80)
            assertTrue("金标集条数超过 D12 上限 100：${cases.size}", cases.size <= 100)

            // 预热：每科一次生产路由（首次触发该科的整科索引构建——一次性成本，不入 p95 统计）。
            cases.map { it.subject }.distinct().forEach { subject ->
                val warmupFeatures = KnowledgeSearchFeatureExtractor.fromQuestion("预热召回")
                store.readSubjectKnowledgeRecallCandidates(
                    subject = subject,
                    searchFeatures = warmupFeatures,
                    limit = SCORED_TOP_K,
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
                        limit = SCORED_TOP_K,
                    )
                    elapsedMillis += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                    assertTrue(
                        "${case.subject} 召回出现跨科节点（披露边界被破坏）",
                        recall.all { it.subject == case.subject },
                    )
                    // 判分口径与首测钉定（§1.1）：store 的 `parents + matched` 返回形态
                    // **直接取前 5**——父 topic 占席是裸 B 路生产形态的既有语义。
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
            // 先落全部数、最后断言——断言红了数也不丢（第一版断言在输出前，p95 一红指标全失）。
            // 输出用**纯字符串拼接**（不依赖 `$it.属性` 模板插值）：本环境实测过插值渲染异常，
            // 拼接形式在 dex 里是确定的字节码，排除这一类干扰。
            println("=== golden-instrumented (真 SQL，档 2) ===")
            println(
                "route=B-bare->top5(v1生产形状) indexVersion=" +
                    KnowledgeSearchFeatureExtractor.INDEX_VERSION +
                    " cases=" + perCase.size + " samplesPerQuery=" + SAMPLES_PER_QUERY +
                    " p95=" + p95 + "ms p50=" + p50 + "ms max=" + elapsedMillis.max() +
                    " budget=" + RECALL_P95_BUDGET_MILLIS + "ms " +
                    "overBudgetSamples=" + elapsedMillis.count { s -> s >= RECALL_P95_BUDGET_MILLIS },
            )
            println("主集 Recall@5(全样本命中) = " + recallMainAll + " (" + hitAllCount + "/" + perCase.size + ")")
            println("主集 Recall@5(任一样本命中) = " + recallMainAny)
            println("MRR(最优名次) = " + mrrMain)
            println("逐章 Recall@5(全样本命中):")
            chapterStats.forEach { (chapter, hitCount, size) ->
                println("  " + chapter + " = " + hitCount.toDouble() / size)
            }
            perCase.filter { s -> !s.hitInAnySample }.forEach { s ->
                println("MISS [" + s.subject + "] " + s.query + " (期望 " + s.expectedSlug + ", 章: " + s.chapter + ")")
            }
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
        const val SAMPLES_PER_QUERY = 5
        const val ATOMIC_SUFFIX = ":atomic:"

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
