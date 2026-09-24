package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Stage-3 小档闭环的第一步（词面腿侧）**：把**生产词面栈的每查询 × 每节点分数**导出成 TSV。
 *
 * ## 为什么不是拿 Stage-2 那份 TSV
 *
 * Stage-2 的词面腿是实验台的 **FTS5 `-bm25()`**（`build/stage2-dense-offline-lexical.tsv`）。
 * 生产跑的不是 FTS5，是 v1 的 SQL 倒排：
 * `ORDER BY COUNT(DISTINCT feature.search_feature) DESC, ATOMIC 优先, canonical_name ASC,
 * knowledge_node_id ASC`（`ProblemOrganizationDao.searchSubjectKnowledgeRecallCandidates`）。
 * **两条腿不同源**（分数定义、排序键、并列行为都不同）。端侧要复现的是生产，所以本阶段
 * 融合的输入必须是**生产那条腿**——否则"设备期望值"对的是另一个检索器。
 *
 * ## 分数怎么来的（唯一实现，不重造）
 *
 * 取 [RetrievalBenchmark.BRouteMirrorIndex.matchedScored] —— 生产 SQL 排序的 JVM 镜像里
 * **同一份** `COUNT(DISTINCT feature)` 计数与排序。本文件**不自己算计数、不自己排序**：
 * 导出侧另写一遍就等于多出一份会悄悄漂移的第二实现（这与 Stage-2 导出的纪律同源）。
 *
 * ## 导出形状（`build/production-lexical-leg.tsv`）
 *
 * ```
 * query_id\tnode_id\tmatch_count
 * ```
 *
 * - `query_id` = 冻结金标 JSON 数组里的 0 起始下标（[RetrievalBenchmark.loadGoldenCases] 保序）；
 *   配套 `build/production-lexical-leg-queries.tsv` 记 `query_id → subject / query 的 sha256 /
 *   expectedSlug / chapter`，让下标与题面的对应关系可被机器复核（不靠互信）。
 * - `node_id` = `knowledgeNodeId`（命中定义要求以 `:atomic:<expectedSlug>` 结尾）。
 * - `match_count` = **`.count { it in queryFeatures }` 于去重特征集之上** = SQL 的
 *   `COUNT(DISTINCT feature.search_feature)`，**越大越优**（与 FTS5 的负分 bm25 相反，
 *   融合侧不需要取反）。只落 `count > 0` 的行 = SQL 里 INNER JOIN 的命中集；
 *   `count = 0` 的节点在该查询下**无分**（融合时给 0，不参与 min-max）。
 * - 行序 = 生产排序原样（`matchedScored` 的顺序），**读回即用、不再重排**（`Row` 只有 count，
 *   重排会丢掉 `canonical_name` 这一层并列键；见下方回读段的说明）。
 *
 * ## 断言（只断言"这份数据可信"，不断言质量阈值）
 *
 * 1. 金标 sha256 = 封存值（题面未被改过）+ 90 条 / 4 科 / 10 章 × 9 条；
 * 2. 每行都指向本查询的**同科**节点、且在**可信状态过滤集**内（导出行不能绕过生产 WHERE）；
 * 3. **回读 TSV 重算生产 v1（D1 形 = matched 优先）**：主集 58/90（0.6444444444444445）、
 *    MRR 0.5637037037037038、逐章 10 值、逐题 MISS（集合 + rank，256 窗口）与
 *    `build/golden-jvm-metrics.txt` 的 `B-route-mirror` 段逐行相同——导出的分数就是生产
 *    词面腿的分数，"设备期望值的词面输入 = 生产原样"因此可证。
 *
 * 复算：`./gradlew.bat :core:data:testDebugUnitTest --tests "*ProductionLexicalLegExportTest*" --rerun`
 */
class ProductionLexicalLegExportTest {

    @Test
    fun exportProductionV1LexicalScoresAsFusionInput() {
        val repoRoot = locateRepoRoot() ?: error("找不到仓库根（settings.gradle.kts）")

        // ---- 题面：冻结金标（sha256 复核对封存值 + 对硬编码常量） ----
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
        val mirror = RetrievalBenchmark.BRouteMirrorIndex(pack.nodes)
        val trusted = RetrievalBenchmark.TRUSTED_VERIFICATION_STATUSES

        // ---- 导出：分数与排序都取自生产镜像的同一份实现（不重算） ----
        val tsv = StringBuilder("# query_id\tnode_id\tmatch_count\n")
        val queryTsv = StringBuilder("# query_id\tsubject\tquery_sha256\texpectedSlug\tchapter\n")
        var rowCount = 0
        var emptyFeatureQueries = 0
        var maxCount = 0
        cases.forEachIndexed { queryId, case ->
            val queryFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(case.query)
            if (queryFeatures.isEmpty()) {
                // 生产此时不走倒排（readSubjectKnowledgeNodes 的回退顺序，无分数可言）；
                // 本套金标实测不触发，保留计数免得口径出现第二套行为却无人发现。
                emptyFeatureQueries++
            }
            val scored = mirror.matchedScored(case.subject, queryFeatures)
            scored.forEach { entry ->
                val node = entry.first
                val count = entry.second
                assertTrue(
                    "导出行的 subject 必须等于查询的科：query_id=$queryId node=${node.knowledgeNodeId}",
                    node.subject == case.subject,
                )
                assertTrue(
                    "导出行的状态必须在可信过滤集内：query_id=$queryId node=${node.knowledgeNodeId}",
                    node.verificationStatus in trusted,
                )
                assertTrue(
                    "导出行的计数必须 > 0（= SQL 的 INNER JOIN 命中集）：query_id=$queryId node=${node.knowledgeNodeId}",
                    count > 0,
                )
                if (count > maxCount) maxCount = count
                tsv.append(queryId).append('\t').append(node.knowledgeNodeId).append('\t').append(count).append('\n')
            }
            rowCount += scored.size
            queryTsv.append(queryId).append('\t').append(case.subject).append('\t')
                .append(RetrievalBenchmark.sha256Hex(case.query.toByteArray(Charsets.UTF_8)))
                .append('\t').append(case.expectedSlug).append('\t').append(case.chapter).append('\n')
        }

        val tsvFile = File(repoRoot, TSV_RELATIVE_PATH)
        tsvFile.parentFile.mkdirs()
        tsvFile.writeText(tsv.toString(), Charsets.UTF_8)
        val queryFile = File(repoRoot, QUERY_TSV_RELATIVE_PATH)
        queryFile.writeText(queryTsv.toString(), Charsets.UTF_8)

        assertTrue("导出行数为 0", rowCount > 0)
        assertEquals("空特征查询应走回退分支（本套金标实测应为 0 条）", 0, emptyFeatureQueries)
        assertTrue("每题的命中数应低于节点总数（计数没有退化成'整表'）", maxCount < pack.nodes.size)

        // ---- 回读 TSV，重算生产 v1（D1 形 = matched 优先） ----
        // **行序即权威**：TSV 按 `matchedScored` 的顺序落盘，回读时**不再重排**。
        // 这不是图省事：生产的并列键是 `COUNT(DESC) → ATOMIC 优先 → canonical_name ASC →
        // knowledge_node_id ASC`，而 TSV 只有 count——按 (count, node_id) 重排会在
        // canonical_name 与 node_id 序不一致的并列处给出**不同名次**（本测试首版实测差 1 位：
        // 「工业上制金属钠…」rank 54 vs 55）。要复现生产序列，除了行序没有别的信息可用。
        val exported = readTsv(tsvFile)
        assertEquals("回读行数应等于写出行数", rowCount, exported.size)
        // 行序不变量：每题的 count 必须单调不增（= 文件真按生产分数序落盘，没被重排过）。
        exported.groupBy { it.queryId }.forEach { (queryId, rows) ->
            rows.zipWithNext().forEach { pair ->
                assertTrue(
                    "TSV 行序不是分数降序：query_id=$queryId ${pair.first.nodeId}(${pair.first.count}) → " +
                        "${pair.second.nodeId}(${pair.second.count})",
                    pair.first.count >= pair.second.count,
                )
            }
        }
        val byQuery = exported.groupBy { it.queryId }
        val derived = cases.mapIndexed { caseIndex, case ->
            val ordered = byQuery[caseIndex] ?: emptyList()
            val probeRank = ordered.take(RetrievalBenchmark.MISS_PROBE_LIMIT)
                .indexOfFirst { it.nodeId.endsWith(ATOMIC_SUFFIX + case.expectedSlug) }
                .let { if (it < 0) 0 else it + 1 }
            case to probeRank
        }
        val top5Ranks = derived.map { entry ->
            val probeRank = entry.second
            if (probeRank in 1..SCORED_TOP_K) probeRank else 0
        }
        val hits = top5Ranks.count { it > 0 }
        val main = hits.toDouble() / cases.size
        val mrr = top5Ranks.sumOf { if (it > 0) 1.0 / it else 0.0 } / cases.size
        val chapterGroups = cases.indices.groupBy { cases[it].chapter }
        val chapterMin = chapterGroups.values.minOf { idxs ->
            idxs.count { top5Ranks[it] > 0 }.toDouble() / idxs.size
        }
        val chapterLines = chapterGroups.toList().map { group ->
            val chapter = group.first
            val idxs = group.second
            val chapterHits = idxs.count { top5Ranks[it] > 0 }
            "  " + chapter + " = " + chapterHits + "/" + idxs.size + " = " +
                (chapterHits.toDouble() / idxs.size) + " (MISS " + (idxs.size - chapterHits) + ")"
        }
        val missLines = derived.filter { entry -> entry.second == 0 || entry.second > SCORED_TOP_K }
            .map { entry ->
                val case = entry.first
                val probeRank = entry.second
                "  MISS [" + case.subject + "] " + case.query +
                    " | expectedSlug=" + case.expectedSlug +
                    " | chapter=" + case.chapter +
                    " | rank=" + (if (probeRank > 0) probeRank.toString() else "absent")
            }

        assertEquals("回读重算的生产 v1(D1 形) 主集命中数应为 58/90", 58, hits)
        assertEquals("回读重算的生产 v1(D1 形) 主集 Recall@5 应为 0.6444444444444445", V1_D1_MAIN, main, 0.0)
        assertEquals("回读重算的生产 v1(D1 形) MRR 应为 0.5637037037037038", V1_D1_MRR, mrr, 0.0)

        // ---- 与 build/golden-jvm-metrics.txt 的 B-route-mirror 段逐行对账（含逐题 MISS 集合与 rank） ----
        val metricsFile = File(repoRoot, "build/golden-jvm-metrics.txt")
        assertTrue("JVM 金标指标文件缺失：${metricsFile.path}（先跑 GoldenRetrievalJvmTest）", metricsFile.isFile)
        val bSection = section(metricsFile.readText(Charsets.UTF_8), "== $B_ROUTE ==")
            ?: error("指标文件缺少 B 路线段 `== $B_ROUTE ==`：${metricsFile.path}")
        assertTrue(
            "回读重算的主集行不在 golden-jvm-metrics.txt 的 B 段里：Recall@5(主集)=$main ($hits/90)",
            bSection.any { it.contains("Recall@5(主集)=$main") && it.contains("($hits/${cases.size})") },
        )
        val recordedChapterLines = bSection.filter { it.startsWith("  ") && it.contains(" = ") && it.contains("(MISS ") }
        assertEquals("逐章行数应为 10", 10, chapterLines.size)
        chapterLines.forEach { line ->
            assertTrue("回读重算的逐章行不在 B 段里：$line", recordedChapterLines.contains(line))
        }
        val recordedMisses = bSection.filter { it.trimStart().startsWith("MISS [") }.map { it.trim() }
        assertEquals("回读重算的 MISS 条数应与 B 段相同", recordedMisses.size, missLines.size)
        missLines.forEach { line ->
            assertTrue(
                "回读重算的 MISS 行不在 B 段里（题面/MISS 集合/rank 有差异）：$line",
                recordedMisses.contains(line.trim()),
            )
        }

        println("production-lexical-leg: 文件=${tsvFile.path}")
        println("production-lexical-leg: 行数=$rowCount 查询数=${cases.size} 空特征查询=$emptyFeatureQueries 单题最大命中数=$maxCount")
        println("production-lexical-leg: 回读重算生产 v1(D1 形) 主集=$main ($hits/${cases.size}) MRR=$mrr 逐章最小=$chapterMin")
        println("production-lexical-leg: 与 golden-jvm-metrics.txt B 段对账：逐章 ${chapterLines.size} 条、MISS ${missLines.size} 条全部一致")
        println("production-lexical-leg: 配套查询表=${queryFile.path}")
    }

    /** TSV 一行：查询下标 + 节点 id + `COUNT(DISTINCT feature)`。 */
    private data class Row(val queryId: Int, val nodeId: String, val count: Int)

    private fun readTsv(file: File): List<Row> = file.readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "TSV 列数应为 3：$line" }
            Row(parts[0].toInt(), parts[1], parts[2].toInt())
        }

    /** 取指标文件里 `== 段名 ==` 起始的那一段正文（直到下一个段头）。 */
    private fun section(text: String, header: String): List<String>? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith(header) }
        if (start < 0) return null
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("== ") }
        return if (end < 0) rest else rest.take(end)
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
        const val TSV_RELATIVE_PATH = "build/production-lexical-leg.tsv"
        const val QUERY_TSV_RELATIVE_PATH = "build/production-lexical-leg-queries.tsv"

        /** Stage-2 规格 §1.2 的封存值（本测试复核，不改）。 */
        const val FROZEN_GOLDEN_SHA256 = "7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39"

        /** 生产 v1（裸 B 路 limit=5、D1 形 = matched 优先）的判读数：`build/golden-jvm-metrics.txt` 的 B 段。 */
        const val B_ROUTE = "B-route-mirror(v1-bare-B5, matched-first)"
        const val V1_D1_MAIN = 0.6444444444444445
        const val V1_D1_MRR = 0.5637037037037038

        /** 判分窗口（与生产 return 形态同口径：D1 形下前 5 名全在 matched 侧）。 */
        const val SCORED_TOP_K = 5
        const val ATOMIC_SUFFIX = ":atomic:"
    }
}
