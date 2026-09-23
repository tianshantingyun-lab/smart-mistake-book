package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Stage-2 dense 离线验证的第一步（词面腿侧）**：把臂 A（FTS5 `bm25()`）的
 * **每查询 × 每节点分数**导出成 TSV，供 Python 侧做归一化分数融合 / RRF——
 * 词面腿的权威实现只有一份（`Stage1Experiment.retrieveA` 的同一套索引、同一套 MATCH、
 * 同一套 WHERE），Python 侧不重造 FTS5 口径（规格 §2.3，`docs/kb-stage2-dense-spec.md`）。
 *
 * **为什么另开一个测试而不是改臂定义**：本文件只**加导出**，不改 `Stage1Experiment` /
 * `Stage1LexicalLab` 的任何一行——Stage-1 的判读数（臂 A = 0.6556/0.5569）必须由同一份
 * 实现原样产出，导出的是它的**中间量**（分数），不是另算一遍。
 *
 * **导出形状**（`build/stage2-dense-offline-lexical.tsv`）：
 * ```
 * query_id\tnode_id\tbm25_score
 * ```
 * - `query_id` = 冻结金标 JSON 数组里的 0 起始下标（`loadGoldenCases` 保序）；配套文件
 *   `build/stage2-dense-offline-lexical-queries.tsv` 记录 `query_id → subject / query 的 sha256 /
 *   expectedSlug / chapter`，让下标与题面的对应关系可被机器复核（不靠互信）。
 * - `node_id` = `knowledgeNodeId`（命中定义要求以 `:atomic:<expectedSlug>` 结尾）。
 * - `bm25_score` = FTS5 `bm25()` 的**原样符号**（负分，越小越优；融合侧自行取 `-score`）。
 *   每行按 `ORDER BY score ASC, node_id ASC`（= 臂 A 的权威排序，含确定性次级键）落盘，读回即用。
 * - **LIMIT 取全语料篇数**：`bm25()` 的分数与 LIMIT 无关，取全量是为了拿到"该查询命中的
 *   每一个节点"的分数（融合与 MISS 名次都需要完整名单），不是改了排序口径。
 *
 * **断言（本测试只断言"这份数据是可信的"，不断言质量阈值）**：
 * 1. 金标 sha256 = 封存值（题面未被改过）+ 90 条 / 4 科 / 10 章 × 9 条；
 * 2. 每行都指向真实节点、且 `subject` 与 `verification_status` 都落在该查询的候选域里
 *    （导出的行不能绕过按科隔离 + 可信过滤）；
 * 3. **回读 TSV 重算臂 A**：主集 59/90（0.6555555555555556）、MRR 0.5568518518518518、
 *    逐章 10 值、以及**逐题 MISS（集合 + rank，256 窗口）**与 `build/stage1-metrics-A.txt`
 *    逐行相同——导出的分数就是臂 A 的分数，"融合臂的词面输入 = 臂 A 原样"因此可证。
 *
 * 复算：`./gradlew.bat :core:data:testDebugUnitTest --tests "*Stage2LexicalScoresExportTest*" --rerun`
 */
class Stage2LexicalScoresExportTest {

    @Test
    fun exportArmAFts5ScoresAsFusionInput() {
        val repoRoot = locateRepoRoot() ?: error("找不到仓库根（settings.gradle.kts）")

        // ---- 题面：冻结金标（sha256 复核对封存值） ----
        val goldenFile = File(repoRoot, "$GOLDEN_REPO_DIR/$GOLDEN_FILE_NAME")
        val sealFile = File(goldenFile.parentFile, goldenFile.name + ".sha256")
        assertTrue("金标集缺失：${goldenFile.path}", goldenFile.isFile)
        assertTrue("金标集缺少 .sha256 封存文件：${sealFile.path}", sealFile.isFile)
        val goldenSha256 = RetrievalBenchmark.sha256Hex(goldenFile.readBytes())
        assertEquals(
            "金标集 sha256 与封存值不符（题面被改过？）",
            sealFile.readText(Charsets.UTF_8).trim(),
            goldenSha256,
        )
        assertEquals("金标 sha256 应等于 Stage-2 规格 §1.2 的封存值", FROZEN_GOLDEN_SHA256, goldenSha256)
        val cases = RetrievalBenchmark.loadGoldenCases(goldenFile)
        assertEquals("冻结金标集条数应为 90", 90, cases.size)
        assertEquals("冻结金标集章数应为 10（每章 9 条）", 10, cases.map { it.chapter }.distinct().size)
        assertEquals("每章条数应一致", 9, cases.groupBy { it.chapter }.values.map { it.size }.distinct().single())

        val pack = BundledKnowledgePackResources.load().single { it.packId == PACK_ID }
        val corpus = Stage1LexicalLab.Corpus(pack.nodes)
        val trusted = RetrievalBenchmark.TRUSTED_VERIFICATION_STATUSES

        // ---- 导出：与臂 A 同一套索引/同一套 MATCH/同一套 WHERE，只把 LIMIT 放到全语料 ----
        val tsv = StringBuilder("# query_id\tnode_id\tbm25_score\n")
        val queryTsv = StringBuilder("# query_id\tsubject\tquery_sha256\texpectedSlug\tchapter\n")
        var emptyFeatureQueries = 0
        var rowCount = 0
        Stage1LexicalLab.Fts5Index(corpus).use { fts ->
            assertEquals("索引篇数应等于节点数", pack.nodes.size, fts.indexedDocs)
            cases.forEachIndexed { index, case ->
                val terms = KnowledgeSearchFeatureExtractor.fromQuestion(case.query)
                if (terms.isEmpty()) emptyFeatureQueries++
                val hits = if (terms.isEmpty()) {
                    // 与臂 A 的空查询回退同源（本套金标实测不触发；保留分支免得口径出现第二套行为）。
                    RetrievalBenchmark.emptyQueryFallbackOrder(corpus.trustedBySubject[case.subject].orEmpty())
                        .map { Stage1LexicalLab.Hit(it.knowledgeNodeId, 0.0) }
                } else {
                    fts.search(
                        subject = case.subject,
                        matchExpression = Stage1LexicalLab.matchExpression(terms),
                        limit = corpus.nodes.size,
                    )
                }
                assertEquals(
                    "命中名单不能出现非本节点语料：query_id=$index",
                    0,
                    hits.count { corpus.nodeById[it.nodeId] == null },
                )
                hits.forEach { hit ->
                    val node = corpus.nodeById.getValue(hit.nodeId)
                    assertTrue(
                        "导出行的 subject 必须等于查询的科：query_id=$index node=${hit.nodeId}",
                        node.subject == case.subject,
                    )
                    assertTrue(
                        "导出行的状态必须在可信过滤集内：query_id=$index node=${hit.nodeId}",
                        node.verificationStatus in trusted,
                    )
                    assertTrue(
                        "bm25 分数应为非正（FTS5 原样符号）：query_id=$index score=${hit.score}",
                        hit.score <= 1e-12,
                    )
                }
                hits.forEach { hit -> tsv.append(index).append('\t').append(hit.nodeId).append('\t').append(hit.score).append('\n') }
                rowCount += hits.size
                queryTsv.append(index).append('\t').append(case.subject).append('\t')
                    .append(RetrievalBenchmark.sha256Hex(case.query.toByteArray(Charsets.UTF_8)))
                    .append('\t').append(case.expectedSlug).append('\t').append(case.chapter).append('\n')
            }
        }

        val tsvFile = File(repoRoot, TSV_RELATIVE_PATH)
        tsvFile.parentFile.mkdirs()
        tsvFile.writeText(tsv.toString(), Charsets.UTF_8)
        val queryFile = File(repoRoot, QUERY_TSV_RELATIVE_PATH)
        queryFile.writeText(queryTsv.toString(), Charsets.UTF_8)

        assertTrue("导出行数为 0", rowCount > 0)
        assertEquals(
            "空特征查询应走回退分支（本套金标实测应为 0 条）",
            0,
            emptyFeatureQueries,
        )

        // ---- 回读 TSV，重算臂 A（导出的分数必须能原样复现 Stage-1 判读数） ----
        val exported = readTsv(tsvFile)
        assertEquals("回读行数应等于写出行数", rowCount, exported.size)
        val byQuery = exported.groupBy { it.queryId }
        val derived = cases.mapIndexed { index, case ->
            val ranked = (byQuery[index] ?: emptyList())
                .sortedWith(compareBy({ it.score }, { it.nodeId }))
                .take(RetrievalBenchmark.MISS_PROBE_LIMIT)
            val rank = ranked.indexOfFirst { it.nodeId.endsWith(ATOMIC_SUFFIX + case.expectedSlug) }
                .let { if (it < 0) 0 else it + 1 }
            case to rank
        }
        val top5Ranks = derived.map { (_, probeRank) ->
            // top-5 判分只用前 5 名：rank ≤ 5 即命中，1/rank 进 MRR。
            if (probeRank in 1..Stage1LexicalLab.SCORED_TOP_K) probeRank else 0
        }
        val hits = top5Ranks.count { it > 0 }
        val main = hits.toDouble() / cases.size
        val mrr = top5Ranks.sumOf { if (it > 0) 1.0 / it else 0.0 } / cases.size
        val chapterLines = cases.indices.groupBy { cases[it].chapter }.toList().map { (chapter, idxs) ->
            val chapterHits = idxs.count { top5Ranks[it] > 0 }
            "  " + chapter + " = " + chapterHits + "/" + idxs.size + " = " +
                (chapterHits.toDouble() / idxs.size) + " (MISS " + (idxs.size - chapterHits) + ")"
        }
        val missLines = derived.filter { (_, rank) -> rank == 0 || rank > Stage1LexicalLab.SCORED_TOP_K }
            .map { (case, probeRank) ->
                "  MISS [" + case.subject + "] " + case.query +
                    " | expectedSlug=" + case.expectedSlug +
                    " | chapter=" + case.chapter +
                    " | rank=" + (if (probeRank > 0) probeRank.toString() else "absent")
            }

        assertEquals("回读重算的臂 A 主集命中数应为 59/90", 59, hits)
        assertEquals("回读重算的臂 A 主集 Recall@5 应为 0.6555555555555556", ARM_A_MAIN, main, 0.0)
        assertEquals("回读重算的臂 A MRR 应为 0.5568518518518518", ARM_A_MRR, mrr, 0.0)

        // ---- 与 build/stage1-metrics-A.txt 逐行对账（含逐题 MISS 的集合与 rank） ----
        val metricsFile = File(repoRoot, "build/stage1-metrics-A.txt")
        assertTrue("Stage-1 臂 A 指标文件缺失：${metricsFile.path}", metricsFile.isFile)
        val metricsText = metricsFile.readText(Charsets.UTF_8)
        metricsText.lines().filter { it.startsWith("  ") && it.contains(" = ") && it.contains("(MISS ") }
            .forEach { line ->
                assertTrue(
                    "回读重算的逐章行不在 stage1-metrics-A.txt 里：$line",
                    metricsText.contains(line),
                )
            }
        val recordedMisses = metricsText.lines().filter { it.trimStart().startsWith("MISS [") }.map { it.trim() }
        assertEquals("回读重算的 MISS 条数应与 stage1-metrics-A.txt 相同", recordedMisses.size, missLines.size)
        missLines.forEach { line ->
            assertTrue(
                "回读重算的 MISS 行不在 stage1-metrics-A.txt 里（题面/MISS 集合/rank 有差异）：$line",
                recordedMisses.contains(line.trim()),
            )
        }
        // 逐章行的数量（10 章）也对齐一次，防止"只对了 MISS、逐章没对上"。
        assertEquals("逐章行数应为 10", 10, chapterLines.size)

        println("stage2-lexical-export: 文件=${tsvFile.path}")
        println("stage2-lexical-export: 行数=$rowCount 查询数=${cases.size} 空洞查询=$emptyFeatureQueries")
        println("stage2-lexical-export: 回读重算臂 A 主集=$main ($hits/${cases.size}) MRR=$mrr")
        println("stage2-lexical-export: 与 stage1-metrics-A.txt 对账：逐章行 ${chapterLines.size} 条、MISS ${missLines.size} 条全部一致")
        println("stage2-lexical-export: 配套查询表=${queryFile.path}")
    }

    /** TSV 一行：查询下标 + 节点 id + FTS5 原样分数。 */
    private data class Row(val queryId: Int, val nodeId: String, val score: Double)

    private fun readTsv(file: File): List<Row> = file.readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "TSV 列数应为 3：$line" }
            Row(parts[0].toInt(), parts[1], parts[2].toDouble())
        }

    private fun locateRepoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    private companion object {
        const val PACK_ID = "moe-2025-four-subjects-v1"
        const val GOLDEN_REPO_DIR = "tools/kb_coverage/tables"
        const val GOLDEN_FILE_NAME = "golden_queries_v1.json"
        const val TSV_RELATIVE_PATH = "build/stage2-dense-offline-lexical.tsv"
        const val QUERY_TSV_RELATIVE_PATH = "build/stage2-dense-offline-lexical-queries.tsv"

        /** Stage-2 规格 §1.2 的封存值（本测试复核，不改）。 */
        const val FROZEN_GOLDEN_SHA256 = "7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39"

        /** Stage-1 判读数（`build/stage1-metrics-A.txt`）——回读重算必须逐位复现。 */
        const val ARM_A_MAIN = 0.6555555555555556
        const val ARM_A_MRR = 0.5568518518518518

        const val ATOMIC_SUFFIX = ":atomic:"
    }
}
