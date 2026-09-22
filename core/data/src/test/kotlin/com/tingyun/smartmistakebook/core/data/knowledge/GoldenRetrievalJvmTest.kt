package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **金标集 × 双路线的 JVM 测量台**（R3 评测同构，档 1 跑）。
 *
 * 题面来源 = 冻结金标集 `tools/kb_coverage/tables/golden_queries_v1.json`（sha256 封存，
 * 本测试每次运行都**对磁盘上的 .sha256 复核完整性**——题面被改过就红）。
 *
 * **测量台性质，不是守门人**：本测试**不断言任何质量阈值**（Recall/MRR 都不钉）——
 * 判据是 D12 预注册的（主集 ≥0.90 且逐章 ≥0.80 ⇒ 去截断后生产同构 B 路成立，
 * 不上向量；否则开同层 dense 兜底议题），记录在
 * `docs/kb-vector-topic-decision.md`，由档 2 真 SQL 出数后**一次性**判定。
 * 把阈值挪进本测试等于提前把"测量台"改成"门"，预注册就失效了。
 *
 * 本测试**断言的是产物与题面的完整性**，不是分数：
 * 1. 题面 = 冻结金标集，且 sha256 封存值与磁盘内容一致；
 * 2. assets 副本与仓库权威源逐字节相同（`goldenAssetsMirrorMatchesRepoCopyByteForByte`，
 *    挡住"副本过期"——源重新冻结而副本没跟上的那一类失败）；
 * 3. 落盘的 `build/golden-jvm-metrics.txt` 里，逐题 MISS 清单（query + expectedSlug +
 *    rank|absent）与逐章分布必须与判分结果逐条对齐（`assertMissLedgerMatchesScores`）。
 * 这三条都可以红，但红了不改变任何分数结论。
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
        val actualSha256 = RetrievalBenchmark.sha256Hex(goldenFile.readBytes())
        assertEquals("金标集 sha256 与封存值不符（题面被改过？）", expectedSha256, actualSha256)

        val cases = RetrievalBenchmark.loadGoldenCases(goldenFile)
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

        // MISS 诊断（**判分口径不动**：a/b 的 top-5 分数仍来自上面 limit=5 的调用；
        // 这里只对未命中的题放宽深度，回答"排到第几 / 到底在不在"——D-01 的两种病因
        // （被更强候选挤下去 vs 索引里根本没有）靠这一个数分开。
        // 与仪表化侧取同一深度（RetrievalBenchmark.MISS_PROBE_LIMIT），两侧清单才可逐行对照。
        val aMisses = RetrievalBenchmark.goldenMisses(a) { case, depth ->
            KnowledgeContextRetriever.select(
                candidates = atomicCandidates.filter { it.subject == case.subject },
                questionText = case.query,
                limit = depth,
            )
        }
        val bMisses = RetrievalBenchmark.goldenMisses(b) { case, depth ->
            index.recall(case.subject, case.query, limit = depth)
        }

        // 结构性不变量（不是质量门）：两路判分行数一致且与题面逐行对齐。
        assertEquals("两路判分行数必须一致", cases.size, a.scores.size)
        assertEquals("两路判分行数必须一致", cases.size, b.scores.size)
        cases.zip(b.scores).forEach { (case, score) ->
            assertEquals("B 路判分行错位", case.query, score.case.query)
        }

        println(reportGolden(a))
        println(reportGolden(b))
        println(reportGoldenMisses(a, aMisses))
        println(reportGoldenMisses(b, bMisses))
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
                missesByRoute = mapOf(a.route to aMisses, b.route to bMisses),
            ),
        )
        // 产物契约：落盘的 MISS 清单与逐章分布必须与判分结果逐条对齐（清单被删/少写当场红）。
        assertMissLedgerMatchesScores(metricsFile, listOf(a to aMisses, b to bMisses))
        println(
            "golden-jvm: A Recall@5=${a.recallAt5} MRR=${a.mrr}; " +
                "B Recall@5=${b.recallAt5} MRR=${b.mrr}; MISS A=${aMisses.size} B=${bMisses.size}; " +
                "指标已写 build/golden-jvm-metrics.txt（测量台：不断言质量阈值，预注册判据另行判定）",
        )
    }

    /** 金标集逐路报告（控制台）。 */
    private fun reportGolden(result: RetrievalBenchmark.GoldenRouteResult): String = buildString {
        appendLine("=== 金标集 ${result.route}（测量台，不断言质量阈值）===")
        appendLine("Recall@5(主集) = ${result.recallAt5} (${result.hits}/${result.total})")
        appendLine("MRR = ${result.mrr}")
        result.byChapter.forEach { (chapter, value) -> appendLine("逐章 $chapter = $value") }
    }

    /**
     * MISS 诊断报告（控制台 + 落盘同一份渲染）：逐章分布 + 逐题清单。
     * 两侧（JVM 与仪表化）同一格式，日志可逐行对照。
     */
    private fun reportGoldenMisses(
        result: RetrievalBenchmark.GoldenRouteResult,
        misses: List<RetrievalBenchmark.GoldenMiss>,
    ): String = buildString {
        appendLine("=== ${result.route} 逐章分布（命中/总数 = Recall@5, MISS 数）===")
        RetrievalBenchmark.goldenChapterLines(result).forEach { appendLine(it) }
        appendLine(
            "=== ${result.route} MISS 清单（${misses.size}/${result.total} 例；" +
                "rank = 预期节点在本路线返回序列放宽窗口（前 ${RetrievalBenchmark.MISS_PROBE_LIMIT} 名）" +
                "内的名次，absent = 该窗口内不存在）===",
        )
        RetrievalBenchmark.goldenMissLines(misses).forEach { appendLine(it) }
    }

    /**
     * **产物契约断言**（不是质量门）：落盘的 Miss 清单与逐章分布必须与判分结果逐条对齐。
     *
     * 它抓的失败很具体：MISS 段被删掉 / 少写几行 / 写成了另一条路线的清单 / 逐章分布
     * 与判分口径脱节——这些都会让"看产物做诊断"的人得到错的分布，而分数照样是绿的。
     * 分数阈值不在这里（预注册判据见 `docs/kb-vector-topic-decision.md`）。
     */
    private fun assertMissLedgerMatchesScores(
        metricsFile: File,
        routes: List<Pair<RetrievalBenchmark.GoldenRouteResult, List<RetrievalBenchmark.GoldenMiss>>>,
    ) {
        assertTrue("指标文件没落盘：${metricsFile.path}", metricsFile.isFile)
        val sections = mutableMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        metricsFile.readLines().forEach { line ->
            val header = SECTION_HEADER.find(line)?.groupValues?.get(1)
            if (header != null) {
                current = sections.getOrPut(header) { mutableListOf() }
            } else {
                current?.add(line)
            }
        }
        routes.forEach { (result, misses) ->
            val body = sections[result.route]
                ?: error("指标文件缺少路线段 `== ${result.route} ==`：${metricsFile.path}")
            val missLines = body.filter { it.trimStart().startsWith("MISS [") }
            assertEquals(
                "路线 ${result.route}：MISS 清单条数应等于该路线未命中题数",
                result.scores.count { !it.hit },
                missLines.size,
            )
            assertEquals(
                "路线 ${result.route}：MISS 清单与判分结果不一致（逐题 query/expectedSlug/rank|absent 必须逐行对齐）",
                RetrievalBenchmark.goldenMissLines(misses).map { it.trim() },
                missLines.map { it.trim() },
            )
            result.scores.filterNot { it.hit }.forEach { score ->
                assertTrue(
                    "路线 ${result.route}：MISS 行未带 expectedSlug=" + score.case.expectedSlug,
                    missLines.any { it.contains("expectedSlug=" + score.case.expectedSlug) },
                )
            }
            assertEquals(
                "路线 ${result.route}：逐章分布与判分结果不一致",
                RetrievalBenchmark.goldenChapterLines(result),
                body.filter { it.startsWith("  ") && it.contains(" = ") },
            )
        }
    }

    /**
     * **副本漂移断言**（任务 1 的防线）：冻结金标集的仓库权威源与 androidTest assets 副本
     * 必须逐字节相同。
     *
     * 它盯的失败是"副本过期"：题面源被重新冻结而副本没跟上时，仪表化评的是旧题面——
     * 而运行时那条 sha 断言只复核**副本自身**的封存值，副本整体过期时它照样绿。
     * 构建期的 `:core:data:syncGoldenQueryAssets` 负责把副本刷成最新（接在
     * `merge*AndroidTestAssets` 之前），本测试负责在"同步没跑 / 被绕过 / 副本被手工改过"
     * 时当场红。两份文件都查：题面本体与它的 sha256 封存值。
     */
    @Test
    fun goldenAssetsMirrorMatchesRepoCopyByteForByte() {
        val repoRoot = locateRepoRoot() ?: error("找不到仓库根（settings.gradle.kts）")
        GOLDEN_FILE_NAMES.forEach { name ->
            val repoFile = File(repoRoot, "$GOLDEN_REPO_DIR/$name")
            val assetFile = File(repoRoot, "$GOLDEN_ASSET_DIR/$name")
            assertTrue("仓库权威源缺失：${repoFile.path}", repoFile.isFile)
            assertTrue(
                "androidTest assets 副本缺失：${assetFile.path}" +
                    "（跑 ./gradlew :core:data:syncGoldenQueryAssets 生成）",
                assetFile.isFile,
            )
            val repoBytes = repoFile.readBytes()
            val assetBytes = assetFile.readBytes()
            assertTrue(
                "金标集副本与仓库权威源不是逐字节相同（副本过期或被改）：\n" +
                    "  权威源: ${repoFile.path} ${repoBytes.size}B sha256=${RetrievalBenchmark.sha256Hex(repoBytes)}\n" +
                    "  副本:   ${assetFile.path} ${assetBytes.size}B sha256=${RetrievalBenchmark.sha256Hex(assetBytes)}\n" +
                    "  修复:   ./gradlew :core:data:syncGoldenQueryAssets",
                repoBytes.contentEquals(assetBytes),
            )
        }
        println("golden-jvm-drift: 副本与权威源逐字节一致（$GOLDEN_ASSET_DIR ← $GOLDEN_REPO_DIR）")
    }

    private fun nodeExists(nodes: List<KnowledgeNodeSeedRecord>, subject: String, slug: String): Boolean =
        nodes.any {
            it.subject == subject &&
                it.granularity == KnowledgeNodeGranularity.ATOMIC.name &&
                it.knowledgeNodeId.endsWith(":atomic:$slug")
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
            File(it, GOLDEN_REPO_DIR + "/" + GOLDEN_FILE_NAMES.first())
        }

    private companion object {
        /** 金标集权威源（D12 判官冻结）与 androidTest assets 副本的位置，两侧都相对仓库根。 */
        const val GOLDEN_REPO_DIR = "tools/kb_coverage/tables"
        const val GOLDEN_ASSET_DIR = "core/data/src/androidTest/assets/golden"
        val GOLDEN_FILE_NAMES = listOf("golden_queries_v1.json", "golden_queries_v1.json.sha256")

        /** 指标文件的路线分段头（`== 路线 ==`）。 */
        val SECTION_HEADER = Regex("^== (.+) ==$")
    }
}
