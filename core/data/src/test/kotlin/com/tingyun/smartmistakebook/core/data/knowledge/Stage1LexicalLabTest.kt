package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Stage-1 词面检索实验台（WP-C）的档 1 入口**：跑齐 WP-A 预注册的臂、落盘产物、断言
 * 产物与口径的完整性。
 *
 * 这个测试**不断言质量阈值**（判读线在编排脚本里机械求值，见
 * `docs/kb-lexical-stage1-experiments.md` §1）。它断言的是"这份数据是可信的"：
 *
 * 1. 题面 = 冻结金标（sha256 复核）+ 每条预期节点在包内真实存在；
 * 2. 章映射可用（臂 C 的门控前提）：每条金标的 `chapter` 能由预期节点的 topic 链推出；
 * 3. 实验环境成立：FTS5 可建表、`trigram` 可用、`ln()` 可用、索引篇数 = 节点数、
 *    分词保真对账通过（否则 A' 的 len/二值 tf 不成立）；
 * 4. **A ↔ A' 容差 0**（§2 验收硬项）：主集、MRR、逐题名次、逐题 top-5 序列全同；
 * 5. 产物契约：逐臂指标文件、报告、判读 JSON 落盘且**回读校验**（JSON 字段与内存中的
 *    计算结果逐项相等；报告里 A/A' 逐题日志行数 = 90）。
 *
 * 复算：`./gradlew :core:data:testDebugUnitTest --tests "*Stage1LexicalLabTest*" --rerun`
 * （金标集/包都是任务输入之外的文件，改它们不会让任务失效）。
 */
class Stage1LexicalLabTest {

    @Test
    fun stage1ArmsScoreFrozenGoldenSetAndWriteArtifacts() {
        val repoRoot = locateRepoRoot() ?: error("找不到仓库根（settings.gradle.kts）")

        // ---- 题面：冻结金标集（sha256 复核对封存值） ----
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
        val cases = RetrievalBenchmark.loadGoldenCases(goldenFile)
        assertEquals("冻结金标集条数应为 90", 90, cases.size)
        assertTrue("金标集应覆盖 4 科", cases.map { it.subject }.toSet().size == 4)
        assertEquals(
            "冻结金标集章数应为 10（每章 9 条）",
            listOf(9),
            cases.groupBy { it.chapter }.values.map { it.size }.distinct(),
        )

        val pack = BundledKnowledgePackResources.load().single { it.packId == PACK_ID }
        val missing = cases.filter { !nodeExists(pack.nodes, it.subject, it.expectedSlug) }
        assertTrue("金标集存在包内没有的预期节点：$missing", missing.isEmpty())

        val corpus = Stage1LexicalLab.Corpus(pack.nodes)

        // ---- 臂 C 的前提：章映射能从 topic 链推出来（90/90 与金标 chapter 一致） ----
        val chapterMismatch = cases.mapNotNull { case ->
            val nodeId = pack.nodes.first {
                it.subject == case.subject &&
                    it.granularity == KnowledgeNodeGranularity.ATOMIC.name &&
                    it.knowledgeNodeId.endsWith(":atomic:${case.expectedSlug}")
            }.knowledgeNodeId
            val derived = corpus.chapterByNodeId[nodeId]
            if (derived == case.chapter) {
                null
            } else {
                "${case.subject}/${case.expectedSlug}: 金标=${case.chapter} 推导=$derived"
            }
        }
        assertTrue(
            "臂 C 的章门控前提不成立：以下金标 chapter 推不出来（章映射与金标脱节）：$chapterMismatch",
            chapterMismatch.isEmpty(),
        )

        val goldenInfo = "file=${goldenFile.name} sha256=$goldenSha256 cases=${cases.size} chapters=${cases.map { it.chapter }.distinct().size}"

        Stage1Experiment(corpus, cases, repoRoot, PACK_ID, goldenInfo).use { experiment ->
            // ---- 实验环境成立性（不成立则整份数据无意义，直接红） ----
            assertTrue("sqlite 版本为空", experiment.ftsSqliteVersion.isNotBlank())
            assertTrue("xerial sqlite-jdbc 里没有 FTS5（建表失败）", experiment.ftsIndexedDocs > 0)
            assertTrue("xerial sqlite-jdbc 里没有 trigram tokenizer", experiment.trigramAvailable)
            assertTrue("SQLite 没有数学函数 ln()，臂 A' 的 df/BM25 SQL 无法成立", experiment.mathFunctionsAvailable)
            assertEquals("索引篇数应等于节点数", pack.nodes.size, experiment.ftsIndexedDocs)
            assertTrue(
                "bm25 公式校验不通过（FTS5 实打分 vs 按公式正演）：${experiment.formulaCheck}",
                experiment.formulaCheck.ok,
            )
            assertTrue(
                "语料级文档长度对账不通过（D=特征数 / tf 二值 前提失效）：${experiment.lengthCheck.mismatches}",
                experiment.lengthCheck.ok,
            )

            // ---- 出数：七条臂 + 扫描诊断 ----
            val mirror = RetrievalBenchmark.goldenBRoute(RetrievalBenchmark.BRouteMirrorIndex(pack.nodes), cases)
            val baseline = Stage1Experiment.Measurement(
                label = mirror.route,
                main = mirror.recallAt5,
                mrr = mirror.mrr,
                hits = mirror.hits,
                total = mirror.total,
            )
            var arms = experiment.runAll()
            println("A' 索引自检: " + experiment.customIndexSelfCheck())
            println("臂 A' 与 A 对账（主集/命中/MRR）：" + arms.joinToString(" | ") { "${it.name}=${it.hits}/${it.total} MRR=${it.mrr}" })
            val scans = experiment.scans()

            // ---- 验收硬项：A ↔ A' 容差 0 ----
            val a = arms.single { it.name == "A" }
            val prim = arms.single { it.name == "A'" }
            assertEquals("A 与 A' 的主集命中数必须一致（容差 0）", a.hits, prim.hits)
            assertEquals("A 与 A' 的 MRR 必须一致（容差 0）", a.mrr, prim.mrr, 0.0)
            assertEquals("A 与 A' 的逐题名次必须一致", a.ranks, prim.ranks)
            assertEquals("A 与 A' 的逐题 top-5 序列必须一致", a.topIds, prim.topIds)
            assertEquals("A 与 A' 的主集 Recall@5 必须逐位相同", a.main, prim.main, 0.0)

            // ---- 判读对象（机械求值，与编排脚本同一规则） ----
            val chosen = Stage1LexicalLab.chooseArm(arms)
            assertTrue(
                "chosenArm 必须来自可上生产的臂：${chosen.name}",
                arms.single { it.name == chosen.name }.productionCandidate,
            )
            assertTrue(
                "chosenArm 的主集必须是可上生产臂里的最高值",
                arms.filter { it.productionCandidate }.all { it.main <= chosen.main },
            )

            // ---- 落盘 + 回读校验 ----
            val artifacts = experiment.writeArtifacts(arms, scans, baseline)
            assertTrue("报告没落盘：${artifacts.reportFile.path}", artifacts.reportFile.isFile)
            assertTrue("判读 JSON 没落盘：${artifacts.verdictFile.path}", artifacts.verdictFile.isFile)
            arms.forEach { arm ->
                val metrics = File(repoRoot, arm.metricsFile)
                assertTrue("臂 ${arm.name} 的指标文件没落盘：${metrics.path}", metrics.isFile)
                val text = metrics.readText()
                assertTrue(
                    "臂 ${arm.name} 的指标文件缺主集行",
                    text.contains("Recall@5(主集)=${arm.main} (${arm.hits}/${arm.total})"),
                )
                val missLines = text.lines().count { it.trimStart().startsWith("MISS [") }
                assertEquals(
                    "臂 ${arm.name} 的 MISS 清单条数应等于该臂未命中题数",
                    arm.total - arm.hits,
                    missLines,
                )
            }

            val verdictJson = artifacts.verdictFile.readText()
            val verdict = VERDICT_JSON.decodeFromString<Stage1LexicalLab.Verdict>(verdictJson)
            // 判读输入必须与判分结果逐项相等（不许"报告写一套、JSON 写另一套"）。
            assertEquals("verdict.baselineMain 应等于预注册基线", Stage1LexicalLab.BASELINE_MAIN, verdict.baselineMain, 0.0)
            assertEquals("verdict.baselineMrr 应等于预注册基线", Stage1LexicalLab.BASELINE_MRR, verdict.baselineMrr, 0.0)
            assertEquals("verdict.chosenArm 与机械判读结果不一致", chosen.name, verdict.chosenArm)
            assertEquals("verdict.fts5VsCustomAgree 与实测不一致", true, verdict.fts5VsCustomAgree)
            assertEquals("verdict.arms 条数应等于臂数", arms.size, verdict.arms.size)
            arms.zip(verdict.arms).forEach { (arm, entry) ->
                assertEquals("verdict.arms 名字错位", arm.name, entry.name)
                assertEquals("verdict.arms[${arm.name}].route 不一致", arm.route, entry.route)
                assertEquals("verdict.arms[${arm.name}].main 不一致", arm.main, entry.main, 0.0)
                assertEquals("verdict.arms[${arm.name}].mrr 不一致", arm.mrr, entry.mrr, 0.0)
                assertEquals("verdict.arms[${arm.name}].chapterMin 不一致", arm.chapterMin, entry.chapterMin, 0.0)
                assertEquals("verdict.arms[${arm.name}].metricsFile 不一致", arm.metricsFile, entry.metricsFile)
                assertTrue("verdict.arms[${arm.name}].evidence 不能为空", entry.evidence.isNotBlank())
            }
            assertTrue("verdict 里没有路由名", JSON_FIELD_ROUTES.all { verdictJson.contains(it) })
            assertTrue("verdict.notes 不能为空", verdict.notes.isNotBlank())

            val report = artifacts.reportFile.readText()
            REPORT_REQUIRED_SECTIONS.forEach { section ->
                assertTrue("报告缺章节：$section", report.contains(section))
            }
            val agreementLines = report.lines().count { it.trimStart().startsWith("[") && it.contains("| A-top5=") }
            assertEquals("报告里的 A/A' 逐题日志行数应等于金标条数", cases.size, agreementLines)
            assertTrue("报告缺 A/A' 一致性结论行", report.contains("主集/MRR 一致（容差 0） = true"))
            assertTrue("报告缺 trigram 失配诊断", report.contains("trigram 对照："))

            // ---- §10 口径对照（诊断段）：六组数互相钉住，且不许"报告写一套、判分另一套" ----
            val paradigm = artifacts.paradigm
            val v1Prod = paradigm.single {
                it.route == Stage1Experiment.V1_ROUTE && it.shape == Stage1Experiment.SHAPE_PRODUCTION
            }
            val v1MatchedOnly = paradigm.single {
                it.route == Stage1Experiment.V1_ROUTE && it.shape == Stage1Experiment.SHAPE_MATCHED_ONLY
            }
            val aProd = paradigm.single {
                it.route == Stage1Experiment.A_ROUTE && it.shape == Stage1Experiment.SHAPE_PRODUCTION
            }
            val aMatchedOnly = paradigm.single {
                it.route == Stage1Experiment.A_ROUTE && it.shape == Stage1Experiment.SHAPE_MATCHED_ONLY
            }
            // (b) v1 生产形（**D1 落地后 = matched 优先**：`matched+parents`）= 本会话基线测量
            //     （同一镜像、两条独立计算路径）；同一张表里**旧生产形（parents 前置）按历史记账保留**，
            //     其值必须仍复现预注册基线（主集 0.5444 = 49/90、MRR 0.1511；容差 1e-4）。
            assertEquals("§10 的 v1 生产形态行主集应等于基线测量（同一镜像）", baseline.main, v1Prod.main, 0.0)
            assertEquals("§10 的 v1 生产形态行 MRR 应等于基线测量", baseline.mrr, v1Prod.mrr, 0.0)
            assertEquals("§10 的 v1 生产形态行命中数应等于基线测量", baseline.hits, v1Prod.hits)
            val v1LegacyProd = paradigm.single {
                it.route == Stage1Experiment.V1_ROUTE && it.shape == Stage1Experiment.SHAPE_LEGACY_PRODUCTION
            }
            assertEquals(
                "v1 旧生产形（parents 前置）主集应复现预注册基线 0.5444（历史记账，D1 前形状）",
                Stage1LexicalLab.BASELINE_MAIN, v1LegacyProd.main, 1e-4,
            )
            assertEquals(
                "v1 旧生产形（parents 前置）命中数应为预注册基线的 49/90（历史记账，D1 前形状）",
                49, v1LegacyProd.hits,
            )
            assertEquals(
                // 0.1517 = 本镜像（JVM 侧）历史 MRR 0.1516666…；真 SQL 的预注册 MRR 0.1511 由仪表化
                // 金标测试（GoldenRetrievalInstrumentedTest）锚定，两侧 MRR 的历史小差是镜像保真度事实。
                "v1 旧生产形（parents 前置）MRR 应复现镜像历史值 0.1517（历史记账，D1 前形状）",
                0.1517, v1LegacyProd.mrr, 1e-4,
            )
            // (c) 臂 A 两套数一致：matched-only 口径 = 臂 A 的判分数；旧生产形 = scan-A-parentprefix。
            assertEquals("§10 的 A matched-only 行应等于臂 A 的判分数（同一口径）", a.main, aMatchedOnly.main, 0.0)
            assertEquals("§10 的 A matched-only 行 MRR 应等于臂 A 的 MRR", a.mrr, aMatchedOnly.mrr, 0.0)
            assertEquals("§10 的 A matched-only 行命中数应等于臂 A 的命中数", a.hits, aMatchedOnly.hits)
            val parentPrefixScan = scans.single { it.name == "scan-A-parentprefix" }
            val aLegacyProd = paradigm.single {
                it.route == Stage1Experiment.A_ROUTE && it.shape == Stage1Experiment.SHAPE_LEGACY_PRODUCTION
            }
            assertEquals("§10 的 A 旧生产形行应等于 scan-A-parentprefix 主集", parentPrefixScan.main, aLegacyProd.main, 0.0)
            assertEquals("§10 的 A 旧生产形行应等于 scan-A-parentprefix MRR", parentPrefixScan.mrr, aLegacyProd.mrr, 0.0)
            assertEquals("§10 的 A 旧生产形行应等于 scan-A-parentprefix 命中数", parentPrefixScan.hits, aLegacyProd.hits)
            // (d) 生产形（D1 落地后 = matched 优先、父节点排其后）：判分窗口的前 5 全在 matched 侧，
            //     父节点不再挤占窗口 ⇒ 不得低于 matched-only，也不得低于旧生产形（D1 的收益方向）。
            assertTrue(
                "生产形（matched 优先）的命中数不应低于 matched-only（父节点排在 matched 之后）",
                aProd.hits >= aMatchedOnly.hits,
            )
            assertTrue(
                "生产形（matched 优先）的命中数不应低于旧生产形 parents 前置（D1 的收益方向）",
                aProd.hits >= aLegacyProd.hits,
            )
            // 产物契约：报告 §10 的表必须与判分结果同源（每格的路由×形态与主集数都要在表里）。
            val section10 = report.substringAfter("## 10. 口径对照").substringBefore("## 11. 判读")
            paradigm.forEach { cell ->
                assertTrue(
                    "报告 §10 缺该格：${cell.route} × ${cell.shape}",
                    section10.contains("| ${cell.route} | ${cell.shape} |"),
                )
                assertTrue("报告 §10 的表缺少该格主集数：${cell.main}", section10.contains(cell.main.toString()))
            }
            assertTrue(
                "报告 §10 必须写明 matched-only 口径不是生产判分口径",
                section10.contains("matched-only 口径不是生产判分口径"),
            )
            println("§10 口径对照（候选排序 × 返回形态；诊断段，不改 §1 判读）：")
            paradigm.forEach { cell ->
                println(
                    "  ${cell.route} × ${cell.shape} = 主集 ${cell.main}（${cell.hits}/${cell.total}）" +
                        " 逐章最小 ${cell.chapterMin} MRR ${cell.mrr}",
                )
            }
            println(
                "  生产形态对比（D1 落地后 = 新生产形 matched 优先）：A 新生产形=${aProd.hits}/${aProd.total}" +
                    " vs A matched-only=${aMatchedOnly.hits}/${aMatchedOnly.total}" +
                    " vs A 旧生产形=${aLegacyProd.hits}/${aLegacyProd.total}；" +
                    "v1 新生产形=${v1Prod.hits}/${v1Prod.total}" +
                    " vs v1 matched-only=${v1MatchedOnly.hits}/${v1MatchedOnly.total}" +
                    " vs v1 旧生产形=${v1LegacyProd.hits}/${v1LegacyProd.total}",
            )

            // ---- 逐字出数日志（Gradle 控制台留痕；与报告同一份数据） ----
            println("=== Stage-1 臂表（判读数：可上生产臂中主集最高者为 chosenArm）===")
            arms.forEach { arm ->
                println(
                    "arm=${arm.name} route=${arm.route} 参评=${arm.productionCandidate} " +
                        "主集=${arm.main} (${arm.hits}/${arm.total}) 逐章最小=${arm.chapterMin} MRR=${arm.mrr}",
                )
            }
            println(
                "基线（预注册）=${Stage1LexicalLab.BASELINE_MAIN}/${Stage1LexicalLab.BASELINE_MRR}；" +
                    "本会话同构建 JVM 镜像 B 路=${baseline.main}/${baseline.mrr}（${baseline.hits}/${baseline.total}）",
            )
            println(
                "环境: sqlite=${experiment.ftsSqliteVersion} FTS5=ok trigram=${experiment.trigramAvailable} " +
                    "ln()=${experiment.mathFunctionsAvailable} 索引篇数=${experiment.ftsIndexedDocs}；" +
                    "bm25 公式校验 maxDelta=${experiment.formulaCheck.maxDelta}（不套钳位=${experiment.formulaCheck.maxUnclampedDelta}，" +
                    "钳位被走到=${experiment.formulaCheck.clampExercised}）；" +
                    "文档长度对账 抽样=${experiment.lengthCheck.sampled} maxDelta=${experiment.lengthCheck.maxDelta}",
            )
            println("bm25 公式校验逐行（夹具真值 vs FTS5 实打分）：")
            experiment.formulaCheck.rows.forEach { println("  $it") }
            println("chosenArm=${chosen.name} 主集=${chosen.main} 逐章最小=${chosen.chapterMin} ⇒ " +
                "判读线 主集≥${Stage1Experiment.MAIN_THRESHOLD} 且逐章≥${Stage1Experiment.CHAPTER_MIN_THRESHOLD}：" +
                (chosen.main >= Stage1Experiment.MAIN_THRESHOLD && chosen.chapterMin >= Stage1Experiment.CHAPTER_MIN_THRESHOLD))
            println("扫描诊断（非判读数）：")
            scans.forEach { scan -> println("  scan=${scan.name} 主集=${scan.main} 逐章最小=${scan.chapterMin} MRR=${scan.mrr}") }
            println("=== A ↔ A' 一致性逐题日志（容差 0 判据）===")
            experiment.agreementLog.forEach(::println)
            println("=== 臂 B 词典扩展日志 ===")
            experiment.expansionLog.forEach(::println)
            println("=== 臂 C 章映射与推断日志（节选 12 条）===")
            experiment.chapterLog.take(12).forEach(::println)
            println("=== trigram 对照 ===")
            experiment.trigramLog.forEach(::println)
            println(
                "stage1-lab: 产物 = ${artifacts.reportFile.path} / ${artifacts.verdictFile.path}；" +
                    "逐臂指标 = build/stage1-metrics-*.txt",
            )
        }
    }

    private fun nodeExists(nodes: List<KnowledgeNodeSeedRecord>, subject: String, slug: String): Boolean =
        nodes.any {
            it.subject == subject &&
                it.granularity == KnowledgeNodeGranularity.ATOMIC.name &&
                it.knowledgeNodeId.endsWith(":atomic:$slug")
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

        val VERDICT_JSON = Json { ignoreUnknownKeys = false }

        /**
         * 报告必须包含的章节（缺一节说明产物不完整）。
         *
         * §10 = 口径对照（2026-09-23 加入：候选排序 × 返回形态两维的诊断段），判读因此顺延为 §11
         * ——章节号变了，判读线与判分口径一字未动。
         */
        val REPORT_REQUIRED_SECTIONS = listOf(
            "## 3. 全臂表",
            "## 4. 逐章 Recall@5",
            "## 5. MISS 清单",
            "## 6. A ↔ A' 一致性证据",
            "## 9. 扫描诊断",
            "## 10. 口径对照",
            "## 11. 判读",
        )

        /** 判读 JSON 里每条臂的路由名都要出现（挡住"JSON 少写一条臂"）。 */
        val JSON_FIELD_ROUTES = listOf(
            Stage1Experiment.ROUTE_A,
            Stage1Experiment.ROUTE_APRIM,
            Stage1Experiment.ROUTE_B,
            Stage1Experiment.ROUTE_C_UPPER,
            Stage1Experiment.ROUTE_C_2STAGE,
            Stage1Experiment.ROUTE_D,
            Stage1Experiment.ROUTE_T,
        )
    }
}
