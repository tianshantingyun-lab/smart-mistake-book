package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * **金标集 × 双路线的 JVM 测量台**（R3 评测同构，档 1 跑）。
 *
 * 题面来源 = 冻结金标集 `tools/kb_coverage/tables/golden_queries_v1.json`（sha256 封存，
 * 本测试每次运行都**对磁盘上的 .sha256 复核完整性**——题面被改过就红）。
 *
 * **测量台性质，不是守门人**：本测试**不断言任何阈值**（Recall/MRR 都不钉）——
 * 判据是 D12 预注册的（主集 ≥0.90 且逐章 ≥0.80 ⇒ 去截断后生产同构 B 路成立，
 * 不上向量；否则开同层 dense 兜底议题），记录在
 * `docs/kb-vector-topic-decision.md`，由档 2 真 SQL 出数后**一次性**判定。
 * 把阈值挪进本测试等于提前把"测量台"改成"门"，预注册就失效了。
 *
 * 两路数（同一次构建、同一份包、同一套题面，才有可比性）：
 * - A 路精排（参考上界）：[KnowledgeContextRetriever.select] 于按科隔离的原子节点全集
 *   （与 [FourSubjectRetrievalBenchmarkTest] 守门同一取法——D-04：口径不动）；
 * - B 路（生产 v1 形状的 JVM 镜像：裸 B 路 limit=5，无 A 路精排）：
 *   [RetrievalBenchmark.BRouteMirrorIndex].recall（生产 SQL 倒排的逐条镜像，索引内容
 *   跟随当前抽取规则 = v1 截断）→ top-5 判分，与 `GoldenRetrievalInstrumentedTest`
 *   的真 SQL 判分目标同构（真 SQL 以仪表化为准；B512→A 统一路由实验 2026-09-22 实测
 *   净伤害、当日回滚，KD-24）。
 *
 * 产出 `build/golden-jvm-metrics.txt`（主集 + 逐章 + 逐题明细，两路并排）。
 *
 * **重跑注意**（与 StagingPack 基准同因）：金标集与包都是任务输入之外的文件，
 * 改它们不会让本任务失效——要最新数就带 `--rerun`：
 * `./gradlew :core:data:testDebugUnitTest --tests "*GoldenRetrievalJvmTest*" --rerun`
 */
class GoldenRetrievalJvmTest {

    private val pack by lazy {
        BundledKnowledgePackResources.load().single { it.packId == "moe-2025-four-subjects-v1" }
    }

    @Test
    fun goldenSetScoresOnBothRoutesAndWritesMetrics() {
        val goldenFile = locateGoldenFile() ?: error("找不到冻结金标集 tools/kb_coverage/tables/golden_queries_v1.json")
        val sealFile = File(goldenFile.parentFile, goldenFile.name + ".sha256")
        assertTrue("金标集缺少 .sha256 封存文件：$sealFile", sealFile.isFile)
        val expectedSha256 = sealFile.readText(Charsets.UTF_8).trim()
        val actualSha256 = goldenFile.readBytes().sha256Hex()
        assertEquals("金标集 sha256 与封存值不符（题面被改过？）", expectedSha256, actualSha256)

        val cases = parseGolden(goldenFile)
        assertTrue("金标集条数低于 D12 下限 80：${cases.size}", cases.size >= 80)
        assertTrue("金标集条数超过 D12 上限 100：${cases.size}", cases.size <= 100)

        // 机械一致性：预期 slug 必须真实存在于同科原子节点里（防"题面指了个不存在的点"）。
        val missing = cases.filterNot { case ->
            nodeExists(pack.nodes, case.subject, case.expectedSlug)
        }
        assertTrue("金标集存在包内没有的预期节点：$missing", missing.isEmpty())
        assertTrue("金标集应覆盖 ≥4 科", cases.map { it.subject }.toSet().size >= 4)

        val index = RetrievalBenchmark.BRouteMirrorIndex(pack.nodes)
        val atomicCandidates = RetrievalBenchmark.atomicNodes(pack.nodes)
        val a = RetrievalBenchmark.goldenARoute(atomicCandidates, cases)
        val b = RetrievalBenchmark.goldenBRoute(index, cases)

        // 结构性不变量（不是质量门）：两路判分行数一致且与题面逐行对齐。
        assertEquals("两路判分行数必须一致", cases.size, a.scores.size)
        assertEquals("两路判分行数必须一致", cases.size, b.scores.size)
        cases.zip(b.scores).forEach { (case, score) ->
            assertEquals("B 路判分行错位", case.query, score.case.query)
        }

        println(reportGolden(a))
        println(reportGolden(b))
        val indexInfo = "indexVersion=${KnowledgeSearchFeatureExtractor.INDEX_VERSION} " +
            "totalFeatureRows=${index.totalFeatureRows} maxFeaturesPerNode=${index.maxFeaturesPerNode}"
        val goldenInfo = "file=${goldenFile.name} sha256=$actualSha256 cases=${cases.size} " +
            "chapters=${a.byChapter.size}"
        // 落仓库根 build/（与 tools/kb_staging 等跨模块产物同处），不是模块 build/——
        // 单测工作目录是模块目录，相对路径会落错地方。
        val repoRoot = locateRepoRoot()
        val metricsFile = File(repoRoot, "build/golden-jvm-metrics.txt")
        metricsFile.parentFile?.mkdirs()
        metricsFile.writeText(
            RetrievalBenchmark.goldenMetricsFile(
                packId = pack.packId,
                nodeCount = pack.nodes.size,
                indexInfo = indexInfo,
                goldenInfo = goldenInfo,
                a = a,
                b = b,
            ),
        )
        println(
            "golden-jvm: A Recall@5=${a.recallAt5} MRR=${a.mrr}; " +
                "B Recall@5=${b.recallAt5} MRR=${b.mrr}; 指标已写 build/golden-jvm-metrics.txt（测量台，不断言阈值）",
        )
    }

    /** 金标集逐路报告（控制台）。 */
    private fun reportGolden(result: RetrievalBenchmark.GoldenRouteResult): String = buildString {
        appendLine("=== 金标集 ${result.route}（测量台，不断言阈值）===")
        appendLine("Recall@5(主集) = ${result.recallAt5} (${result.hits}/${result.total})")
        appendLine("MRR = ${result.mrr}")
        result.byChapter.forEach { (chapter, value) -> appendLine("逐章 $chapter = $value") }
    }

    private fun nodeExists(nodes: List<KnowledgeNodeSeedRecord>, subject: String, slug: String): Boolean =
        nodes.any {
            it.subject == subject &&
                it.granularity == KnowledgeNodeGranularity.ATOMIC.name &&
                it.knowledgeNodeId.endsWith(":atomic:$slug")
        }

    private fun parseGolden(file: File): List<RetrievalBenchmark.GoldenCase> =
        (json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonArray).map { element ->
            val value = element.jsonObject
            assertEquals("金标集字段漂移", setOf("query", "expectedSlug", "chapter", "subject"), value.keys.toSet())
            RetrievalBenchmark.GoldenCase(
                chapter = value.stringField("chapter"),
                expectedSlug = value.stringField("expectedSlug"),
                query = value.stringField("query"),
                subject = value.stringField("subject"),
            )
        }

    private fun JsonObject.stringField(name: String): String {
        val primitive = this[name] as? JsonPrimitive
            ?: error("金标集字段 $name 不是字符串")
        val content = primitive.contentOrNull
            ?: error("金标集字段 $name 不是字符串")
        assertTrue("金标集字段 $name 为空", content.isNotBlank())
        return content
    }

    /** 从工作目录逐级上找仓库根（与 StagingPack 基准同一取法）。 */
    private fun locateRepoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun locateGoldenFile(): File? =
        locateRepoRoot()?.let {
            File(it, "tools/kb_coverage/tables/golden_queries_v1.json")
        }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
    }
}
