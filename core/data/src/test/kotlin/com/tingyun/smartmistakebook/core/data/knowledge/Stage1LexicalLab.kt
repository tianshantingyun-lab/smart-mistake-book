package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **Stage-1 词面检索实验台**（WP-C）——把 WP-A 预注册的臂跑在**冻结金标 90 条**上出数。
 *
 * 规则与判读线全部来自 `docs/kb-lexical-stage1-experiments.md`（出数前写死），本文件只做**执行**：
 *
 * - **共用口径**（§2）：题面 = 冻结金标集（sha256 复核在测试侧）；分词 = 生产提取器
 *   [KnowledgeSearchFeatureExtractor]（索引侧 `fromNode`、查询侧 `fromQuestion`），**不另写一份**；
 *   命中定义 = 前 5 名里存在 `knowledgeNodeId` 以 `:atomic:<expectedSlug>` 结尾；
 *   候选过滤 = 按科隔离 + 可信状态（[RetrievalBenchmark.TRUSTED_VERIFICATION_STATUSES]）。
 * - **固定权威参数**（§3）：FTS5 `bm25()` 的 k1=1.2 / b=0.75 是 SQLite **硬编码**，不可调；
 *   本文件所有判读数都取这套参数。任何变体只出现在测试侧"扫描诊断"段并**标注非判读数**。
 * - **判读线不在这里**（主集 ≥0.75 且逐章 ≥0.60）：本文件只出数、落盘，判读由编排脚本按
 *   `build/stage1-verdict.json` 机械求值。
 *
 * ## 索引域口径（A/A' 一致性的前提，写在这里免得后来者改错）
 *
 * FTS5 的 `bm25()` 里 **N、avgdl、每个词的 df 都是"整张 fts5 表"上的量**（读的是
 * `xRowCount` / `xColumnTotalSize` / `xQueryPhrase`，与 WHERE 里挂的其他过滤条件无关）。
 * 所以：索引里放**全量节点**（含 TOPIC，与生产候选池一致），按科/状态/章过滤只写在 WHERE；
 * 臂 A' 要跟 A 逐题一致，它的 `documents` 也必须装全量节点、`metadata.n`/`avgdl` 与 df
 * 也必须在全量域上算——**不是**在"按科过滤后的子集"上算。这一条是 A' 复算时最容易写错的地方。
 *
 * ## 臂清单（名字与 §2 一一对应）
 *
 * | 名 | 路由名 | 口径 | 参评 |
 * |---|---|---|---|
 * | A | `stage1-A-fts5-bm25` | FTS5 内存表 + `bm25()` 升序取前 5 | ✅ 生产候选 |
 * | A' | `stage1-Aprim-custom-bm25-sql` | 自写 df+BM25 SQL（documents/postings/metadata），与 A **容差 0** | ⚪ 一致性对照 |
 * | B | `stage1-B-alias-expansion` | 别名词典查询扩展（并查集 + 泛称剔除 + 组内 OR/组间 AND），叠加在 A 的排序上 | ✅ 生产候选 |
 * | C-上界 | `stage1-C-upper-chapter-oracle` | 按金标 chapter 过滤候选后重排（**天线口径**） | ❌ 不参评 |
 * | C-两段 | `stage1-C-2stage-majority-chapter` | 宽召回（K=64）→ 章多数推断 → 章内重排 | ✅ 生产候选 |
 * | D | `stage1-D-A+B+C2stage` | A+B + 两段式章门控 | ✅ 生产候选 |
 * | T | `stage1-T-trigram` | 同索引内容、只换 `tokenize='trigram'` | ❌ 只作对照 |
 */
internal object Stage1LexicalLab {

    // ------------------------------------------------------------------
    // 固定权威参数（§3）：判读数只取这一套
    // ------------------------------------------------------------------

    /** FTS5 `bm25()` 的 k1（SQLite 硬编码，不可调）。 */
    const val K1 = 1.2

    /** FTS5 `bm25()` 的 b（SQLite 硬编码，不可调）。 */
    const val B = 0.75

    /** 臂 C-两段式的**固定**宽召回宽度（臂定义里的 K；变体只进扫描诊断段）。 */
    const val WIDE_RECALL_K = 64

    /** 判分窗口（§2 命中定义：返回的前 5 名）——与 [RetrievalBenchmark] 判分口径同一常量。 */
    const val SCORED_TOP_K = 5

    /** 别名词典：同一 surface 出现在 ≥ 这么多节点、且跨 ≥ 这么多主题时判为泛称并剔除。 */
    const val GENERIC_MIN_NODES = 3
    const val GENERIC_MIN_TOPICS = 2

    /** 别名词典：单组扩展的表面形式上界（资源保护；触发时会记进报告）。 */
    const val MAX_SURFACES_PER_GROUP = 256

    private const val UNICODE61 = "unicode61"
    private const val TRIGRAM = "trigram"

    /** FTS5 `MATCH` 里只允许"字母/数字构成的特征"——与生产提取器的切分规则同一口径。 */
    private val SEARCH_FEATURE = Regex("^[\\p{L}\\p{N}]+$")

    private val JSON = Json { prettyPrint = true; encodeDefaults = true }

    // ------------------------------------------------------------------
    // 语料：节点 + 特征 + 章 + 候选池（一次构建，A/A'/各臂共用同一份）
    // ------------------------------------------------------------------

    /**
     * 金标评测的**语料与派生视图**，一次算好给所有臂共用：特征（生产提取器）、
     * 节点 id 索引、章映射、按科分组的可信候选池。
     *
     * 特征是**唯一一份**：A 与 A' 的索引内容必须逐字相同（否则两臂数不同就分不清是
     * 排序语义错了还是索引内容不同），所以两边都从这里取，不许各自再调 `fromNode`。
     */
    class Corpus(val nodes: List<KnowledgeNodeSeedRecord>) {
        val featuresByNodeId: Map<String, Set<String>> =
            nodes.associate { it.knowledgeNodeId to KnowledgeSearchFeatureExtractor.fromNode(it) }
        val nodeById: Map<String, KnowledgeNodeSeedRecord> =
            nodes.associateBy { it.knowledgeNodeId }

        val indexRows: Long = featuresByNodeId.values.sumOf { it.size.toLong() }
        val maxFeaturesPerNode: Int = featuresByNodeId.values.maxOfOrNull { it.size } ?: 0

        /** 节点 → 章（册·章·节：沿 topic 链取第 3 层全路径名，用 `·` 连接）。 */
        val chapterByNodeId: Map<String, String?> = nodes.associate { node ->
            node.knowledgeNodeId to chapterOf(node.knowledgeNodeId)
        }

        /** 按科分组的可信候选池（生产 B 路的过滤口径）。 */
        val trustedBySubject: Map<String, List<KnowledgeNodeSeedRecord>> =
            nodes.filter { it.verificationStatus in RetrievalBenchmark.TRUSTED_VERIFICATION_STATUSES }
                .groupBy { it.subject }

        /** 每个科里原子节点的章分布（金标章映射的覆盖面，报告里出数）。 */
        val chaptersBySubject: Map<String, List<String>> =
            trustedBySubject.mapValues { (_, list) ->
                list.mapNotNull { chapterByNodeId[it.knowledgeNodeId] }.distinct().sorted()
            }

        fun records(ids: List<String>): List<KnowledgeNodeSeedRecord> = ids.mapNotNull(nodeById::get)

        /** 取 id 的 1 起始名次；找不到返回 0。 */
        fun rankOf(ids: List<String>, nodeId: String): Int =
            ids.indexOfFirst { it == nodeId }.let { if (it < 0) 0 else it + 1 }

        private fun chapterOf(nodeId: String): String? {
            val names = ArrayList<String>(4)
            var cursor: KnowledgeNodeSeedRecord? = nodeById[nodeId]
            var guard = 0
            while (cursor != null && guard++ < 16) {
                names.add(cursor.canonicalName)
                cursor = cursor.parentKnowledgeNodeId?.let(nodeById::get)
            }
            names.reverse()
            return when {
                names.isEmpty() -> null
                // 金标 chapter = 册·章·节 的三段路径（沿链的第 3 层全路径名）。
                names.size >= 3 -> names.take(3).joinToString("·")
                else -> names.joinToString("·")
            }
        }
    }

    // ------------------------------------------------------------------
    // 臂 A / 臂 T：FTS5 内存索引（sqlite-jdbc）
    // ------------------------------------------------------------------

    /** 一条 FTS5 命中（节点 id + `bm25()` 分数；FTS5 的分数为负，越小越优）。 */
    data class Hit(val nodeId: String, val score: Double)

    /**
     * 臂 A / 臂 T 的内存 FTS5 索引。文档 = 逐节点一篇，正文 = `fromNode` 特征空格连接串；
     * `node_id` / `subject` / `status` / `chapter` 都是 `UNINDEXED` 列（只作 WHERE 过滤，
     * 不进倒排，也就不会改变 bm25 的文档长度）。
     */
    class Fts5Index(
        private val corpus: Corpus,
        private val tokenizer: String = UNICODE61,
    ) : AutoCloseable {

        private val connection: Connection =
            DriverManager.getConnection("jdbc:sqlite::memory:")

        val sqliteVersion: String
        val indexedDocs: Int
        val trigramAvailable: Boolean
        val mathFunctionsAvailable: Boolean

        init {
            // Gradle 的测试 worker 里 DriverManager 的服务自动发现不一定看得见驱动，
            // 显式注册一次，免得报"no suitable driver"这种与被测逻辑无关的错。
            Class.forName("org.sqlite.JDBC")
            connection.createStatement().use { statement ->
                sqliteVersion = statement.executeQuery("SELECT sqlite_version()").use { rs ->
                    rs.next(); rs.getString(1)
                }
                mathFunctionsAvailable = runCatching {
                    statement.executeQuery("SELECT ln(2.718281828459045)").use { rs ->
                        rs.next(); rs.getDouble(1)
                    }
                }.isSuccess
                statement.execute(
                    "CREATE VIRTUAL TABLE node_fts USING fts5(" +
                        "node_id UNINDEXED, subject UNINDEXED, status UNINDEXED, " +
                        "chapter UNINDEXED, features, tokenize='$tokenizer')",
                )
                trigramAvailable = runCatching {
                    statement.execute(
                        "CREATE VIRTUAL TABLE trigram_probe USING fts5(x, tokenize='$TRIGRAM')",
                    )
                }.isSuccess
            }
            prepared(
                "INSERT INTO node_fts(rowid, node_id, subject, status, chapter, features) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                corpus.nodes.forEachIndexed { index, node ->
                    val features = corpus.featuresByNodeId.getValue(node.knowledgeNodeId)
                    insert.setInt(1, index + 1)
                    insert.setString(2, node.knowledgeNodeId)
                    insert.setString(3, node.subject)
                    insert.setString(4, node.verificationStatus)
                    insert.setString(5, corpus.chapterByNodeId[node.knowledgeNodeId])
                    insert.setString(6, features.joinToString(" "))
                    insert.executeUpdate()
                }
            }
            indexedDocs = prepared("SELECT COUNT(*) FROM node_fts").use { count ->
                count.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            drop("DROP TABLE IF EXISTS trigram_probe")
        }

        /**
         * 一条查询：`MATCH` 表达式（值走绑定参数）+ 按科/状态/[章] 过滤，
         * `ORDER BY bm25() ASC, node_id ASC`。
         *
         * 次级键 `node_id ASC` 是为了**可复算**：分数相同的候选之间 SQLite 不保证顺序，
         * 加确定性次级键后同一份索引每次跑出的序列一致（它不改变主键判据）。
         * 传了 [coverage]（臂 B）时，**组覆盖数**成为第一排序键（组间 AND 的落地方式），
         * 同覆盖内仍按 `bm25()` 升序——顶层打分函数仍是 A 的 bm25。
         */
        fun search(
            subject: String,
            matchExpression: String,
            limit: Int,
            chapter: String? = null,
            coverage: Map<String, Int>? = null,
        ): List<Hit> {
            if (coverage != null) writeCoverage(coverage)
            val sql = buildString {
                // 不用表别名：FTS5 的 `MATCH` 与 `bm25()` 都以**表名**解析（别名在这里会被
                // 当成普通列名而报 "no such column"），所以下面一律写 `node_fts.*`。
                append("SELECT node_fts.node_id, bm25(node_fts) AS score FROM node_fts ")
                if (coverage != null) {
                    append("LEFT JOIN query_coverage qc ON qc.node_id = node_fts.node_id ")
                }
                append("WHERE node_fts MATCH ? AND node_fts.subject = ? AND node_fts.status IN (?, ?, ?) ")
                if (chapter != null) append("AND node_fts.chapter = ? ")
                append("ORDER BY ")
                if (coverage != null) append("COALESCE(qc.cov, 0) DESC, ")
                append("score ASC, node_fts.node_id ASC LIMIT ?")
            }
            return prepared(sql).use { statement ->
                var index = 1
                statement.setString(index++, matchExpression)
                statement.setString(index++, subject)
                RetrievalBenchmark.TRUSTED_VERIFICATION_STATUSES
                    .sorted() // 绑定顺序确定，SQL 文本与参数一一对应
                    .forEach { statement.setString(index++, it) }
                if (chapter != null) statement.setString(index++, chapter)
                statement.setInt(index, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(Hit(rs.getString(1), rs.getDouble(2)))
                    }
                }
            }
        }

        /** 臂 B 的组覆盖：node_id → 命中的组数（只装这次查询命中的组，表很小）。 */
        private fun writeCoverage(coverage: Map<String, Int>) {
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS query_coverage(node_id TEXT PRIMARY KEY, cov INTEGER)",
                )
                statement.execute("DELETE FROM query_coverage")
            }
            prepared("INSERT OR REPLACE INTO query_coverage(node_id, cov) VALUES (?, ?)").use { insert ->
                coverage.entries.sortedBy { it.key }.forEach { (nodeId, cov) ->
                    insert.setString(1, nodeId)
                    insert.setInt(2, cov)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }

        fun queryPlan(subject: String, matchExpression: String, limit: Int): List<String> =
            prepared(
                "EXPLAIN QUERY PLAN SELECT node_fts.node_id, bm25(node_fts) AS score FROM node_fts " +
                    "WHERE node_fts MATCH ? AND node_fts.subject = ? AND node_fts.status IN (?, ?, ?) " +
                    "ORDER BY score ASC, node_fts.node_id ASC LIMIT ?",
            ).use { statement ->
                statement.setString(1, matchExpression)
                statement.setString(2, subject)
                RetrievalBenchmark.TRUSTED_VERIFICATION_STATUSES.sorted().forEachIndexed { i, status ->
                    statement.setString(3 + i, status)
                }
                statement.setInt(6, limit)
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(4)) }
                }
            }

        /**
         * **语料级文档长度对账**（臂 A' 的 `len = 特征数` / `tf 二值` 是否成立）。
         *
         * bm25 的长度项只依赖 `D / avgdl`（整体缩放不改变分数），所以要验证的不变量是：
         * **每篇的 D 与全表 avgdl 之比 = 该节点特征数与平均特征数之比**。
         *
         * 做法：取**只出现在一篇文档里**的特征（唯一特征），它的 idf 只由 `(N, nHit=1)`
         * 决定、与文档长度无关；于是可以用 FTS5 实际返回的 `bm25()` 分数，与"按
         * `D = 特征数`、`avgdl = Σ特征数 / N` 正演算出来的分数"逐位比对。正演对得上，
         * 就说明语料里没有"一个特征被切成多个词条"这类长度失真。
         *
         * 说明：这里**不用 `fts5vocab`**——它的 `doc` 列在本 SQLite 上实测不是文档 rowid
         * （按它分组的规模与语料结构不符），语义没吃透就不拿它当判据；改用 FTS5 自身的
         * 打分当基准，见 `docs/kb-lexical-stage1-experiments.md` §3 的"镜像保真先行"。
         */
        fun verifyDocumentLengths(sampleLimit: Int = 12): LengthCheck {
            val docsByTerm = HashMap<String, MutableSet<String>>()
            corpus.featuresByNodeId.forEach { (nodeId, features) ->
                features.forEach { term -> docsByTerm.getOrPut(term) { linkedSetOf() }.add(nodeId) }
            }
            val uniqueTerms = docsByTerm.entries
                .filter { it.value.size == 1 }
                .map { (term, nodeIds) -> term to nodeIds.first() }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .distinctBy { it.second }
                .take(sampleLimit)
            require(uniqueTerms.isNotEmpty()) { "语料里没有唯一特征，无法对账文档长度" }
            val nodeCount = corpus.nodes.size
            val avgdl = corpus.indexRows.toDouble() / nodeCount
            val idfUnique = kotlin.math.ln((nodeCount - 1 + 0.5) / (1 + 0.5))
            val mismatches = ArrayList<String>()
            var maxDelta = 0.0
            uniqueTerms.forEach { (term, nodeId) ->
                val expectedD = corpus.featuresByNodeId.getValue(nodeId).size
                val predicted = -1.0 * idfUnique * (K1 + 1.0) /
                    (1.0 + K1 * (1.0 - B + B * expectedD / avgdl))
                val actual = prepared(
                    "SELECT node_id, bm25(node_fts) AS score FROM node_fts WHERE node_fts MATCH ?",
                ).use { statement ->
                    statement.setString(1, "\"$term\"")
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            rs.getString(1) to rs.getDouble(2)
                        }
                    }
                }
                if (actual == null) {
                    mismatches.add("唯一特征 `$term` 在索引里查不到（应当是 $nodeId）")
                } else {
                    val delta = kotlin.math.abs(actual.second - predicted)
                    if (delta > maxDelta) maxDelta = delta
                    if (actual.first != nodeId || delta > 1e-9) {
                        mismatches.add(
                            "特征 `$term`：期望 $nodeId D=$expectedD 预测分=$predicted，" +
                                "实测 ${actual.first} 分=${actual.second}（差 $delta）",
                        )
                    }
                }
            }
            return LengthCheck(sampled = uniqueTerms.size, mismatches = mismatches, maxDelta = maxDelta)
        }

        /** 语料里有多少特征长度 < 3（trigram 按 3-gram 建索引，这类查询词注定失配）。 */
        fun shortFeatures(terms: Collection<String>): Int = terms.count { it.length < 3 }

        override fun close() = connection.close()

        private fun prepared(sql: String) = connection.prepareStatement(sql)

        private fun drop(sql: String) = connection.createStatement().use { it.execute(sql) }
    }

    /** 语料级文档长度对账结果。 */
    data class LengthCheck(val sampled: Int, val mismatches: List<String>, val maxDelta: Double) {
        val ok: Boolean get() = mismatches.isEmpty()
    }

    /** bm25 公式校验结果（FTS5 实际分数 vs 按公式正演）。 */
    data class FormulaCheck(
        val rows: List<String>,
        val maxDelta: Double,
        /** 不套 idf 钳位时的最大偏差——用来证明"钳位这一支确实被练到了"。 */
        val maxUnclampedDelta: Double,
        /** 夹具里是否至少有一行"套不套钳位结果不同"（钳位分支真的被走到）。 */
        val clampExercised: Boolean,
        val docsInFixture: Int,
        val avgdlInFixture: Double,
    ) {
        val ok: Boolean get() = maxDelta < 1e-9 && clampExercised && maxUnclampedDelta > maxDelta
    }

    /**
     * **bm25 公式校验**（固定夹具，真值已知）：一张 4 篇文档的小表，含
     * **词频 > 1**（`alpha alpha`）与**出现在半数以上文档**的词（`delta` 在 3/4 篇里，
     * FTS5 会把它的 idf 钳到 1e-6）。用 FTS5 的实际 `bm25()` 分数，与按官方实现
     * （`ext/fts5/fts5_aux.c` 的 `fts5Bm25Function` / `fts5Bm25GetData`）正演的分数比对。
     *
     * 它盯的失败很具体：公式抄错（N 的域、avgdl 的算法、`D` 是词条数还是别的、tf 的定义、
     * idf 钳位漏掉）——这些错了，臂 A' 与 A 的"容差 0"就不可能成立，而且错在哪一层看不出来。
     */
    fun verifyBm25Formula(): FormulaCheck {
        val docs = listOf(
            "alpha beta gamma",
            "alpha alpha delta",
            "beta delta",
            "gamma delta epsilon zeta",
        )
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE VIRTUAL TABLE fx USING fts5(id UNINDEXED, txt)")
            }
            connection.prepareStatement("INSERT INTO fx(id, txt) VALUES (?, ?)").use { insert ->
                docs.forEachIndexed { index, text ->
                    insert.setString(1, "d${index + 1}")
                    insert.setString(2, text)
                    insert.executeUpdate()
                }
            }
            val n = docs.size
            val avgdl = docs.sumOf { it.split(" ").size }.toDouble() / n
            // 查询里同时含：词频 >1 的词（alpha）、df 过半被钳位的词（alpha/gamma，df=2/4）、
            // 以及 idf 为正的词（epsilon，df=1/4）——钳位那一支必须真的被走到。
            val queryTerms = listOf("alpha", "gamma", "epsilon")
            val query = queryTerms.joinToString(" OR ")
            val scores = connection.prepareStatement(
                "SELECT id, bm25(fx) AS score FROM fx WHERE fx MATCH ? ORDER BY score ASC, id ASC",
            ).use { statement ->
                statement.setString(1, query)
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getDouble(2)) }
                }
            }
            fun idf(term: String, clamp: Boolean): Double {
                val nHit = docs.count { term in it.split(" ") }
                val raw = kotlin.math.ln((n - nHit + 0.5) / (nHit + 0.5))
                return if (clamp && raw <= 0.0) 1e-6 else raw
            }
            fun predicted(id: String, clamp: Boolean): Double {
                val text = docs[id.removePrefix("d").toInt() - 1]
                val tokens = text.split(" ")
                val d = tokens.size.toDouble()
                val total = queryTerms.sumOf { term ->
                    val tf = tokens.count { it == term }.toDouble()
                    idf(term, clamp) * (tf * (K1 + 1.0)) /
                        (tf + K1 * (1.0 - B + B * d / avgdl))
                }
                return -1.0 * total
            }
            val rows = scores.map { (id, actual) ->
                val clamped = predicted(id, clamp = true)
                val unclamped = predicted(id, clamp = false)
                "$id actual=$actual formula=$clamped（不套钳位=$unclamped）"
            }
            val maxDelta = scores.maxOf { (id, actual) -> kotlin.math.abs(actual - predicted(id, true)) }
            val maxUnclampedDelta = scores.maxOf { (id, actual) -> kotlin.math.abs(actual - predicted(id, false)) }
            return FormulaCheck(
                rows = rows,
                maxDelta = maxDelta,
                maxUnclampedDelta = maxUnclampedDelta,
                clampExercised = scores.any { (id, _) -> predicted(id, true) != predicted(id, false) },
                docsInFixture = n,
                avgdlInFixture = avgdl,
            )
        }
    }

    // ------------------------------------------------------------------
    // 臂 A'：自写 df + BM25 SQL（documents / postings / metadata）
    // ------------------------------------------------------------------

    /**
     * 臂 A'：**不借 FTS5 的排序**，自己用 `documents(node_id, len)` + `postings(term, node_id, tf)`
     * + `metadata(n, avgdl)` 三张表复算 BM25，逐条对齐 FTS5 的 `bm25()` 实现：
     *
     * ```
     * idf(t) = ln((N - df(t) + 0.5) / (df(t) + 0.5))，若 ≤ 0 取 1e-6     // FTS5 的钳位
     * score  = Σ_t idf(t) * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * len / avgdl))
     * 排序    = score 升序（FTS5 返回 -score，故升序即最优），同分按 node_id 升序
     * ```
     *
     * 三个容易写错、写错就不一致的语义点（对照 `ext/fts5/fts5_aux.c` 的
     * `fts5Bm25Function` / `fts5Bm25GetData`）：
     * 1. **N / avgdl / df 都在全量域上算**（FTS5 读的是整张表），不按科缩域；
     * 2. **idf ≤ 0 钳到 1e-6**（词出现在半数以上文档时）；
     * 3. **len 是词条数**（= 特征数，由 [Fts5Index.verifyTokenization] 对账），tf 二值。
     *
     * k1/b 存在 `metadata` 表里而不是写死在 SQL 文本里：判读数用 [K1]/[B]，
     * 扫描诊断可以换一份 metadata（改的只是数据，不是语义）。
     */
    class CustomBm25Index(
        corpus: Corpus,
        k1: Double = K1,
        b: Double = B,
    ) : AutoCloseable {

        private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

        init {
            Class.forName("org.sqlite.JDBC")
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE documents(node_id TEXT PRIMARY KEY, subject TEXT, status TEXT, len INTEGER)")
                statement.execute("CREATE TABLE postings(term TEXT, node_id TEXT, tf INTEGER)")
                statement.execute("CREATE TABLE metadata(id INTEGER PRIMARY KEY, n INTEGER, avgdl REAL, k1 REAL, b REAL)")
                statement.execute("CREATE INDEX postings_term ON postings(term)")
                statement.execute("CREATE TABLE query_terms(term TEXT PRIMARY KEY)")
            }
            var totalLen = 0L
            prepared("INSERT INTO documents(node_id, subject, status, len) VALUES (?, ?, ?, ?)").use { insert ->
                corpus.nodes.forEach { node ->
                    val len = corpus.featuresByNodeId.getValue(node.knowledgeNodeId).size
                    totalLen += len
                    insert.setString(1, node.knowledgeNodeId)
                    insert.setString(2, node.subject)
                    insert.setString(3, node.verificationStatus)
                    insert.setInt(4, len)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            prepared("INSERT INTO postings(term, node_id, tf) VALUES (?, ?, 1)").use { insert ->
                corpus.nodes.forEach { node ->
                    corpus.featuresByNodeId.getValue(node.knowledgeNodeId).forEach { term ->
                        insert.setString(1, term)
                        insert.setString(2, node.knowledgeNodeId)
                        insert.addBatch()
                    }
                }
                insert.executeBatch()
            }
            val n = corpus.nodes.size
            prepared("INSERT INTO metadata(id, n, avgdl, k1, b) VALUES (0, ?, ?, ?, ?)").use { insert ->
                insert.setInt(1, n)
                insert.setDouble(2, totalLen.toDouble() / n)
                insert.setDouble(3, k1)
                insert.setDouble(4, b)
                insert.executeUpdate()
            }
        }

        val docCount: Int = prepared("SELECT n FROM metadata WHERE id = 0").use { statement ->
            statement.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

        /** 自检：postings 行数应等于 Σ 特征数（索引内容与 FTS5 侧逐字同源的证据）。 */
        val postingCount: Long = prepared("SELECT COUNT(*) FROM postings").use { statement ->
            statement.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

        /** 自检：query terms 在 postings 里的命中情况（A' 一条查询到底看到了什么）。 */
        fun debugQuery(subject: String, terms: Collection<String>): String {
            val counts = terms.take(3).joinToString(", ") { term ->
                prepared("SELECT COUNT(*) FROM postings WHERE term = ?").use { statement ->
                    statement.setString(1, term)
                    statement.executeQuery().use { rs -> rs.next(); "$term=" + rs.getLong(1) }
                }
            }
            val docs = prepared("SELECT COUNT(*) FROM documents WHERE subject = ?").use { statement ->
                statement.setString(1, subject)
                statement.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            val termsInTable = prepared("SELECT COUNT(*) FROM query_terms").use { statement ->
                statement.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            val rows = search(subject, terms, 5)
            return "subject=$subject documents=$docs postings命中($counts) query_terms=$termsInTable 返回=${rows.size} " +
                rows.take(3).joinToString("; ") { "${it.nodeId}@${it.score}" }
        }

        /**
         * 与 [Fts5Index.search] 同口径的一条查询：query terms 走**绑定参数**装进
         * `query_terms` 临时表（不拼 SQL 值），候选过滤与排序键与 FTS5 一致。
         */
        fun search(subject: String, terms: Collection<String>, limit: Int): List<Hit> {
            if (terms.isEmpty()) return emptyList()
            connection.createStatement().use { it.execute("DELETE FROM query_terms") }
            prepared("INSERT OR IGNORE INTO query_terms(term) VALUES (?)").use { insert ->
                terms.sorted().forEach { term ->
                    insert.setString(1, term)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            val sql = buildString {
                // 符号约定与 FTS5 逐字一致：`bm25()` 返回 **负的** 分数，`ORDER BY score ASC` 即最优。
                // （初版这里写成 `+SUM(...)` 却仍按升序排，取到的是**最差**候选——A' 与 A 的
                // "容差 0" 当场把这类"符号/方向"语义错抓出来。）
                append("SELECT p.node_id AS node_id, -1.0 * SUM(")
                append("(CASE WHEN ln((m.n - df.df + 0.5) / (df.df + 0.5)) > 0.0 ")
                append("THEN ln((m.n - df.df + 0.5) / (df.df + 0.5)) ELSE 1e-6 END) * ")
                append("((p.tf * (m.k1 + 1.0)) / (p.tf + m.k1 * (1.0 - m.b + m.b * d.len * 1.0 / m.avgdl)))")
                append(") AS score ")
                append("FROM postings p ")
                append("JOIN documents d ON d.node_id = p.node_id ")
                append("JOIN (SELECT p2.term AS term, COUNT(*) AS df FROM postings p2 ")
                append("WHERE p2.term IN (SELECT term FROM query_terms) GROUP BY p2.term) df ")
                append("ON df.term = p.term ")
                append("CROSS JOIN metadata m ")
                append("WHERE p.term IN (SELECT term FROM query_terms) ")
                append("AND d.subject = ? AND d.status IN (?, ?, ?) ")
                append("GROUP BY p.node_id ORDER BY score ASC, p.node_id ASC LIMIT ?")
            }
            return prepared(sql).use { statement ->
                var index = 1
                statement.setString(index++, subject)
                RetrievalBenchmark.TRUSTED_VERIFICATION_STATUSES.sorted()
                    .forEach { statement.setString(index++, it) }
                statement.setInt(index, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(Hit(rs.getString(1), rs.getDouble(2)))
                    }
                }
            }
        }

        override fun close() = connection.close()

        private fun prepared(sql: String) = connection.prepareStatement(sql)
    }

    // ------------------------------------------------------------------
    // 臂 B：别名词典查询扩展（并查集 + 泛称剔除 + 组内 OR / 组间 AND）
    // ------------------------------------------------------------------

    /**
     * **别名词典**：从成品包里每个节点的 名称+别名 建 surface 表，再按"共现于同一节点"
     * 对**节点**做并查集（相同 surface 的节点并成一组，组的表面形式集合就是同义簇）；
     * 跨多主题且高频的 surface 判为**泛称**并剔除（如"第三章"、"综合复习"、"蛋白质"——
     * 它们在多章、多节点上重复，带不来区分度）。
     *
     * 查询侧用**正向最大匹配**（词典最长优先）把问题文本切成命中的 surface：这样
     * "氢氧化钠"不会被错认成"氧化钠"、"亚硫酸氢钠"不会被错认成"硫酸"——最大匹配是
     * 词典切分的标准做法，也是对"子串误命中"这一具体失败的处理。
     *
     * 扩展语义（§2 的"组内 OR、组间 AND"落地口径，报告里逐条写明）：
     * - **组内 OR**：命中 surface 所在组的**全部表面形式**都 OR 进 `MATCH`（同义簇互为替代），
     *   每个扩展词都过生产提取器 `fromQuestion`——扩展词与索引词同源，不另写一份分词；
     * - **组间 AND**：查询命中多个组时，"属于几个命中组"成为第一排序键（覆盖越多越前）。
     *   本语料里组是**节点连通分量**（一个节点只属一组），所以覆盖数 ∈ {0,1}：落在命中组
     *   里的候选整体排在只命中基础特征词的候选之前，同覆盖内仍是臂 A 的 `bm25()` 升序。
     *   硬的"必须同时满足所有命中组"在本结构下等于取交集（通常为空集），不可用——这一点
     *   在报告里如实标注为**落地口径与设想语义的差异**。
     */
    class AliasDictionary(corpus: Corpus) {

        /** surface → 携带该 surface 的节点集（已剔除泛称）。 */
        private val nodesBySurface: MutableMap<String, MutableSet<String>> = LinkedHashMap()

        /** 节点并查集：相同 surface 的节点并成一组；组根 → 该组的节点集 / 表面形式集。 */
        private val parent = HashMap<String, String>()
        private val nodesByGroup: MutableMap<String, MutableSet<String>> = LinkedHashMap()
        private val surfacesByGroup: MutableMap<String, MutableSet<String>> = LinkedHashMap()

        /** 被泛称剔除规则丢掉的 surface（报告里出数，证明这条规则真的在做事）。 */
        val droppedGenerics: List<String>

        /** 组规模超过 [MAX_SURFACES_PER_GROUP] 而被截断的组数（正常应为 0）。 */
        val truncatedGroups: Int

        private val maxSurfaceLength: Int

        init {
            nodesBySurfaceInit(corpus)
            val topicsBySurface = HashMap<String, MutableSet<String>>()
            nodesBySurface.forEach { (surface, nodeIds) ->
                topicsBySurface[surface] = nodeIds.mapNotNullTo(linkedSetOf()) { id ->
                    corpus.nodeById[id]?.parentKnowledgeNodeId
                }
            }
            val generics = nodesBySurface.filter { (surface, nodeIds) ->
                nodeIds.size >= GENERIC_MIN_NODES &&
                    (topicsBySurface[surface]?.size ?: 0) >= GENERIC_MIN_TOPICS
            }.keys
            droppedGenerics = generics.sorted()
            generics.forEach { nodesBySurface.remove(it) }

            unionNodesSharingSurface()
            truncatedGroups = surfacesByGroup.values.count { it.size > MAX_SURFACES_PER_GROUP }
            maxSurfaceLength = nodesBySurface.keys.maxOfOrNull { it.length } ?: 0
        }

        val surfaceCount: Int get() = nodesBySurface.size
        val groupCount: Int get() = nodesByGroup.size

        /** 问题文本里命中的 surface（正向最大匹配，泛称已被剔除）。 */
        fun matchSurfaces(questionText: String): List<String> {
            val text = questionText.lowercase(Locale.ROOT)
            val matched = ArrayList<String>(4)
            var index = 0
            while (index < text.length) {
                val maxLength = minOf(maxSurfaceLength, text.length - index)
                var consumed = 0
                for (length in maxLength downTo 2) {
                    val candidate = text.substring(index, index + length)
                    if (candidate in nodesBySurface) {
                        matched.add(candidate)
                        consumed = length
                        break
                    }
                }
                index += if (consumed > 0) consumed else 1
            }
            return matched
        }

        /**
         * 查询扩展：命中 surface 所在组里的全部表面形式（组内 OR），逐个过生产提取器
         * [KnowledgeSearchFeatureExtractor.fromQuestion]——扩展词与索引词同源。
         */
        fun expansion(questionText: String): Expansion {
            val matched = matchSurfaces(questionText)
            val groups = matched.mapNotNull { groupOfSurface(it) }.distinct()
            val terms = linkedSetOf<String>()
            groups.forEach { group ->
                surfacesByGroup[group].orEmpty().sorted().take(MAX_SURFACES_PER_GROUP)
                    .forEach { surface -> terms += KnowledgeSearchFeatureExtractor.fromQuestion(surface) }
            }
            return Expansion(
                matchedSurfaces = matched,
                groups = groups,
                terms = terms,
                nodesByGroup = groups.associateWith { nodesByGroup[it].orEmpty().sorted() },
            )
        }

        /** 命中 surface 的调试文本（报告逐题用）。 */
        fun describe(expansion: Expansion): String =
            if (expansion.matchedSurfaces.isEmpty()) {
                "无"
            } else {
                expansion.matchedSurfaces.joinToString(",") +
                    "（组 " + expansion.groups.size + "；扩展词 " + expansion.terms.size + " 个）"
            }

        private fun nodesBySurfaceInit(corpus: Corpus) {
            corpus.nodes.forEach { node ->
                surfacesOf(node).forEach { surface ->
                    nodesBySurface.getOrPut(surface) { linkedSetOf() }.add(node.knowledgeNodeId)
                }
            }
            corpus.nodes.forEach { parent.getOrPut(it.knowledgeNodeId) { it.knowledgeNodeId } }
        }

        private fun surfacesOf(node: KnowledgeNodeSeedRecord): List<String> =
            (listOf(node.canonicalName) + node.aliases)
                .map { it.lowercase(Locale.ROOT).trim() }
                .filter { it.length >= 2 }

        /** 相同 surface 的节点并成一组（并查集），再物化每组的节点集与表面形式集。 */
        private fun unionNodesSharingSurface() {
            nodesBySurface.forEach { (_, nodeIds) ->
                val head = nodeIds.first()
                nodeIds.drop(1).forEach { union(head, it) }
            }
            nodesBySurface.forEach { (surface, nodeIds) ->
                val group = find(nodeIds.first())
                nodesByGroup.getOrPut(group) { linkedSetOf() }.addAll(nodeIds)
                surfacesByGroup.getOrPut(group) { linkedSetOf() }.add(surface)
            }
        }

        private fun find(x: String): String {
            var root = x
            while (parent.getOrPut(root) { root } != root) root = parent.getValue(root)
            var cursor = x
            while (parent.getOrPut(cursor) { cursor } != cursor) {
                val next = parent.getValue(cursor)
                parent[cursor] = root
                cursor = next
            }
            return root
        }

        private fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        private fun groupOfSurface(surface: String): String? =
            nodesBySurface[surface]?.firstOrNull()?.let { find(it) }
    }

    /** 一次查询扩展的结果。 */
    data class Expansion(
        val matchedSurfaces: List<String>,
        val groups: List<String>,
        val terms: Set<String>,
        /** 组根 → 该组节点（组间覆盖排序用；也落进 FTS5 侧的 `query_coverage` 表）。 */
        val nodesByGroup: Map<String, List<String>>,
    ) {
        /** 节点 → 命中组数（本语料下 ∈ {0,1}；见 [AliasDictionary] 的口径说明）。 */
        fun coverage(): Map<String, Int> {
            val coverage = HashMap<String, Int>()
            nodesByGroup.values.forEach { nodeIds ->
                nodeIds.forEach { nodeId -> coverage[nodeId] = (coverage[nodeId] ?: 0) + 1 }
            }
            return coverage
        }
    }

    // ------------------------------------------------------------------
    // 臂清单与落盘
    // ------------------------------------------------------------------

    /** 一条臂的判分结果 + 落盘信息。 */
    data class Arm(
        val name: String,
        val route: String,
        /** 是否参评（"可上生产的臂"）；C-上界与 T 不参评。 */
        val productionCandidate: Boolean,
        val evidence: String,
        val result: RetrievalBenchmark.GoldenRouteResult,
        val misses: List<RetrievalBenchmark.GoldenMiss>,
        val metricsFile: String,
        /** 每题的命中名次（1 起始，0 = 未命中 top-5）——A/A' 逐题对账用。 */
        val ranks: List<Int>,
        /** 每题的 top-5 节点 id（A/A' 逐题对账用）。 */
        val topIds: List<List<String>>,
    ) {
        val main: Double get() = result.recallAt5
        val mrr: Double get() = result.mrr
        val chapterMin: Double get() = result.byChapter.minOfOrNull { it.second } ?: 0.0
        val hits: Int get() = result.hits
        val total: Int get() = result.total
    }

    /** 取一条臂的判分结果（判分口径就是 [RetrievalBenchmark.scoreGolden]，不另立）。 */
    fun score(
        name: String,
        route: String,
        productionCandidate: Boolean,
        evidence: String,
        cases: List<RetrievalBenchmark.GoldenCase>,
        retrieve: (RetrievalBenchmark.GoldenCase, Int) -> List<KnowledgeNodeSeedRecord>,
    ): Arm {
        val result = RetrievalBenchmark.scoreGolden(route, cases) { case ->
            retrieve(case, SCORED_TOP_K)
        }
        val misses = RetrievalBenchmark.goldenMisses(result) { case, depth -> retrieve(case, depth) }
        return Arm(
            name = name,
            route = route,
            productionCandidate = productionCandidate,
            evidence = evidence,
            result = result,
            misses = misses,
            metricsFile = "build/stage1-metrics-$name.txt",
            ranks = result.scores.map { it.rank },
            topIds = result.scores.map { it.top3 },
        )
    }

    /** 单臂指标文件（与金标测量台同格式：主集 + 逐章 + MISS 清单）。 */
    fun metricsFileText(arm: Arm, packId: String, goldenInfo: String, indexInfo: String): String =
        buildString {
            appendLine("# stage1-metrics — ${arm.name}（臂定义见 docs/kb-lexical-stage1-experiments.md §2）")
            appendLine("# route=${arm.route} pack=$packId")
            appendLine("# golden: $goldenInfo")
            appendLine("# index: $indexInfo")
            appendLine("# k1=$K1 b=$B（FTS5 硬编码，判读数只取这套参数）")
            appendLine("")
            appendLine("== ${arm.route} ==")
            appendLine("Recall@5(主集)=${arm.main} (${arm.hits}/${arm.total})")
            appendLine("MRR=${arm.mrr}")
            appendLine("逐章 Recall@5:")
            RetrievalBenchmark.goldenChapterLines(arm.result).forEach(::appendLine)
            appendLine(
                "MISS 清单（${arm.misses.size} 例；rank = 预期节点在本路线返回序列放宽窗口" +
                    "（前 ${RetrievalBenchmark.MISS_PROBE_LIMIT} 名）内的名次，absent = 该窗口内不存在）:",
            )
            RetrievalBenchmark.goldenMissLines(arm.misses).forEach(::appendLine)
        }

    /** 判读对象：**参评臂里主集最高者**（同分先比 MRR，再按名字定序，保证机械可复算）。 */
    fun chooseArm(arms: List<Arm>): Arm = arms
        .filter { it.productionCandidate }
        .maxWith(compareBy({ it.main }, { it.mrr }, { it.name }))

    /**
     * 机器可读判读输入（`build/stage1-verdict.json`）。字段与 WP-C 验收一致；
     * 判读由编排脚本按 `main >= 0.75 && chapterMin >= 0.60` 求值，本文件不改判读线。
     */
    @Serializable
    data class Verdict(
        val baselineMain: Double,
        val baselineMrr: Double,
        val arms: List<VerdictArm>,
        val chosenArm: String,
        val fts5VsCustomAgree: Boolean,
        val notes: String,
    )

    @Serializable
    data class VerdictArm(
        val name: String,
        val route: String,
        val main: Double,
        val chapterMin: Double,
        val mrr: Double,
        val metricsFile: String,
        val evidence: String,
    )

    fun verdictOf(arms: List<Arm>, chosen: Arm, agree: Boolean, notes: String): Verdict = Verdict(
        // 预注册判读基线（docs/kb-lexical-stage1-experiments.md §1）：v1 裸 B5 真 SQL
        // 主集 0.5444（49/90）、MRR 0.1511。**不是**本轮某个臂的数。
        baselineMain = BASELINE_MAIN,
        baselineMrr = BASELINE_MRR,
        arms = arms.map {
            VerdictArm(
                name = it.name,
                route = it.route,
                main = it.main,
                chapterMin = it.chapterMin,
                mrr = it.mrr,
                metricsFile = it.metricsFile,
                evidence = it.evidence,
            )
        },
        chosenArm = chosen.name,
        fts5VsCustomAgree = agree,
        notes = notes,
    )

    fun verdictJson(verdict: Verdict): String = JSON.encodeToString(verdict)

    /** 预注册判读基线（§1）：v1 裸 B5（真 SQL）主集与 MRR。 */
    const val BASELINE_MAIN = 0.5444
    const val BASELINE_MRR = 0.1511

    /** 把 MATCH 表达式构造成"quoted OR"——特征只含字母/数字，引号包起来即无语法歧义。 */
    fun matchExpression(terms: Collection<String>): String {
        require(terms.isNotEmpty()) { "MATCH 表达式不能为空" }
        terms.forEach { term ->
            require(SEARCH_FEATURE.matches(term)) {
                "特征 `$term` 含非字母/数字字符——MATCH 表达式需要重新界定引号规则"
            }
        }
        return terms.joinToString(" OR ") { "\"$it\"" }
    }
}
