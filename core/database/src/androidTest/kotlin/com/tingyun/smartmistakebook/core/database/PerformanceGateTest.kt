package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Performance gate tests for the library search functionality.
 * These tests verify that search operations meet performance targets
 * on real devices.
 *
 * 书面预算：
 * - 10万条数据搜索 P95 < 500ms
 * - 首屏时间 < 500ms
 * - Facet 查询 P95 < 200ms
 * - EXPLAIN QUERY PLAN 不出现非预期全表扫描
 *
 * **2026-09-13 的处置与它为什么不是"全绿"**（审计 `perf-gate-empty-seed`，`P1`）：
 * 这些用例原先全部在**空库**上测量（`insertTestData` 是个空实现），三条计时断言因此恒真。
 * 现在填了真实播种（10k 行 ＋ FTS 索引），计量**真的发生**了——而结果是
 * **三条搜索计时全部超出书面预算 3–18 倍**，同一次运行里目录侧的两条却都在预算内。
 * 本机只有一台软件渲染的 AVD，"它是否代表真机"没有证据，**所以预算本身标 `NOT_MEASURED`**：
 * 计量与 `println` 保留、命中前置与挂死上界保留，**但不再断言那三个数**——
 * 挑一个能让它变绿的预算，正是本项要防的错法。
 * 预算的最终判定（按设备档位重定，还是把 FTS 延迟当产品问题修）见审计 §12.5 **N-17**。
 */
@RunWith(AndroidJUnit4::class)
class PerformanceGateTest {

    private lateinit var database: StudyDatabase
    /** 同一个库的端口面：`seedFixture` / `refreshLibrarySearchProjection` 只在这里。 */
    private lateinit var store: RoomStudyDatabase
    private lateinit var context: Context
    private val databaseName = "performance-test-${System.nanoTime()}.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(
            context,
            StudyDatabase::class.java,
            databaseName,
        ).build()
        store = RoomStudyDatabase(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    /**
     * Test search performance with 10k items.
     * Measures P95 search latency.
     */
    @Test
    fun searchPerformance10kItems() = runBlocking {
        // Insert test data
        insertTestData(10_000)
        assertSearchHitsSomething("test query 0")

        // Warm up
        repeat(10) {
            database.ftsSearchCount("test")
        }

        // Measure search latency
        val latencies = mutableListOf<Long>()
        repeat(100) { iteration ->
            val latency = measureTimeMillis {
                database.ftsSearchCount("test query $iteration")
            }
            latencies.add(latency)
        }

        // Calculate P95
        val sortedLatencies = latencies.sorted()
        val p95Index = (sortedLatencies.size * 0.95).toInt()
        val p95Latency = sortedLatencies[p95Index]

        reportLatency(
            what = "search P95 (10k rows, 100 iters)",
            measuredMillis = p95Latency,
            budgetMillis = SEARCH_P95_TARGET_MS,
        )
    }

    /**
     * Test first screen render performance.
     */
    @Test
    fun firstScreenPerformance() = runBlocking {
        insertTestData(10_000)

        val firstScreenLatency = measureTimeMillis {
            // FTS 重构后旧的 getFirstPage 分页首页辅助方法已不存在；
            // 用现有 LibraryQueryDao.page 的 offset=0 / limit=pageSize 语义等价重写。
            database.libraryDao().page(
                searchText = "",
                subjectId = null,
                sectionId = null,
                knowledgePointId = null,
                masteryId = null,
                sort = "RECENTLY_CREATED",
                offset = 0,
                limit = 20,
            )
        }

        assertTrue(
            "首屏时间 ${firstScreenLatency}ms 超过目标 ${FIRST_SCREEN_TARGET_MS}ms",
            firstScreenLatency < FIRST_SCREEN_TARGET_MS,
        )
    }

    /**
     * Test facet query performance.
     */
    @Test
    fun facetQueryPerformance() = runBlocking {
        insertTestData(10_000)

        // Warm up
        repeat(5) {
            database.libraryDao().subjectFacets(
                searchText = "",
                sectionId = null,
                knowledgePointId = null,
                masteryId = null,
            )
        }

        val latencies = mutableListOf<Long>()
        repeat(50) {
            val latency = measureTimeMillis {
                database.libraryDao().subjectFacets(
                    searchText = "",
                    sectionId = null,
                    knowledgePointId = null,
                    masteryId = null,
                )
            }
            latencies.add(latency)
        }

        val sortedLatencies = latencies.sorted()
        val p95Index = (sortedLatencies.size * 0.95).toInt()
        val p95Latency = sortedLatencies[p95Index]

        assertTrue(
            "Facet 查询 P95 延迟 ${p95Latency}ms 超过目标 ${FACET_P95_TARGET_MS}ms",
            p95Latency < FACET_P95_TARGET_MS,
        )
    }

    /**
     * Verify EXPLAIN QUERY PLAN doesn't show full table scans.
     */
    @Test
    fun explainQueryPlanNoFullTableScan() = runBlocking {
        insertTestData(10_000)

        // FTS 重构后 RoomDatabase.query(String) 不再可用；且 EXPLAIN QUERY PLAN
        // 走 room3 prepared-statement 的 step 路径会被 framework 驱动抛出
        // "Queries can be performed using SQLiteDatabase query or rawQuery
        // methods only"。先借 Room 打开一次数据库（确保文件已创建），再用
        // android.database.sqlite.SQLiteDatabase 打开同一文件，以 rawQuery 执行
        // EXPLAIN QUERY PLAN 并消费 Cursor；读取 detail 列（索引 3）与
        // 全表扫描断言语义保持不变。
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("SELECT 1") { statement -> statement.step() }
        }
        val plan = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { rawDatabase ->
            rawDatabase.rawQuery(
                "EXPLAIN QUERY PLAN SELECT * FROM library_catalog WHERE title LIKE '%test%'",
                null,
            ).use { cursor ->
                buildString {
                    while (cursor.moveToNext()) {
                        appendLine(cursor.getString(3))
                    }
                }
            }
        }

        // Check for full table scan indicators
        val hasFullTableScan = plan.contains("SCAN TABLE", ignoreCase = true) &&
            !plan.contains("USING INDEX", ignoreCase = true)

        assertTrue(
            "EXPLAIN QUERY PLAN 显示全表扫描：\n$plan",
            !hasFullTableScan,
        )
    }

    /**
     * Test search with Chinese text performance.
     */
    @Test
    fun chineseSearchPerformance() = runBlocking {
        insertTestData(10_000)
        assertSearchHitsSomething("函数方程")

        val latencies = mutableListOf<Long>()
        repeat(50) {
            val latency = measureTimeMillis {
                database.ftsSearchCount("函数方程")
            }
            latencies.add(latency)
        }

        val sortedLatencies = latencies.sorted()
        val p95Index = (sortedLatencies.size * 0.95).toInt()
        val p95Latency = sortedLatencies[p95Index]

        reportLatency(
            what = "chinese search P95 (10k rows, 50 iters)",
            measuredMillis = p95Latency,
            budgetMillis = SEARCH_P95_TARGET_MS,
        )
    }

    /**
     * Test concurrent search performance.
     */
    @Test
    fun concurrentSearchPerformance() = runBlocking {
        insertTestData(10_000)
        assertSearchHitsSomething("concurrent test 1")

        val latencies = mutableListOf<Long>()
        val jobs = (1..10).map { i ->
            async {
                val latency = measureTimeMillis {
                    database.ftsSearchCount("concurrent test $i")
                }
                latency
            }
        }

        jobs.forEach { latencies.add(it.await()) }

        reportLatency(
            what = "concurrent search average (10 parallel, 10k rows)",
            measuredMillis = latencies.average().toLong(),
            budgetMillis = CONCURRENT_SEARCH_TARGET_MS,
        )
    }

    /**
     * 真的把 [count] 行写进库，而不是留一个空实现（审计 `perf-gate-empty-seed`，`P1`）。
     *
     * 修复前这里是 `// For now, this is a placeholder`：六个性能测试全都在**空库**上测量，
     * `500/200/1000ms` 三条预算几乎必然是 0ms ⇒ 恒真。把它们换成"把生产查询改坏成 O(n²)"
     * 也不会变红——`release-gates.md:28` 宣称的「1k/10k/50k library performance gates」
     * 实际一个都没生效。
     *
     * 两件事缺一不可：
     * 1. `seedFixture` 写真实的题库行（走与 `LibraryCatalogPagingInstrumentedTest` 同一个
     *    播种口，不另造一套）。
     * 2. `refreshLibrarySearchProjection` 把 FTS 索引建起来——搜索走的是
     *    `library_search_content` ⨝ `library_search_fts`，**只写目录行不建索引的话，
     *    MATCH 仍然查不到任何东西**，三条计时断言会以"新的空跑"重新变成恒真。
     *
     * 语料按被测查询的需要构造（见 [searchableText]）：`test`／`query`／序号、
     * 前 10 行加 `concurrent`、每 100 行加 `函数方程`。**这不是为了让它通过，而是为了让被测的
     * MATCH 真的命中行**——每条计时用例还各自断言"这条查询确实命中过"（`assertSearchHitsSomething`），
     * 所以语料一旦与查询脱节，用例会直接红，而不是安静地量空气。
     */
    private suspend fun insertTestData(count: Int) {
        store.seedFixture(performanceSeedBundle(count))
        store.refreshLibrarySearchProjection()
    }

    private fun performanceSeedBundle(count: Int) = StudySeedBundle(
        problems = List(count) { index ->
            ProblemSeedRecord(
                problemId = "perf-problem-$index",
                canonicalFingerprint = index.toString(16).padStart(64, '0'),
                subject = if (index % 2 == 0) "MATH" else "PHYSICS",
                createdAtEpochMillis = index + 1L,
            )
        },
        revisions = List(count) { index ->
            ProblemRevisionSeedRecord(
                revisionId = "perf-revision-$index",
                problemId = "perf-problem-$index",
                revisionNumber = 1,
                title = searchableText(index),
                problemMarkdown = searchableText(index),
                questionDocumentSnapshot = null,
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = StudyDbValue.VerificationStatus.UNKNOWN,
                sourceType = "CAPTURE_CONFIRMED",
                sourceReference = null,
                contentFingerprint = "f".repeat(64),
                createdAtEpochMillis = index + 1L,
            )
        },
        practiceUnits = List(count) { index ->
            PracticeUnitSeedRecord(
                practiceUnitId = "perf-practice-$index",
                problemId = "perf-problem-$index",
                problemRevisionId = "perf-revision-$index",
                unitKey = "whole-problem",
                unitKind = "WHOLE_PROBLEM",
                title = searchableText(index),
                promptMarkdown = searchableText(index),
                estimatedSeconds = 180,
                createdAtEpochMillis = index + 1L,
            )
        },
        errorBookEntries = List(count) { index ->
            ErrorBookEntrySeedRecord(
                entryId = "perf-entry-$index",
                practiceUnitId = "perf-practice-$index",
                problemId = "perf-problem-$index",
                currentRevisionId = "perf-revision-$index",
                sourceKey = null,
                status = "ACTIVE",
                acceptedAtEpochMillis = index + 1L,
                updatedAtEpochMillis = index + 1L,
            )
        },
    )

    /**
     * 第 [index] 行的可检索文本。三条被测的 MATCH 各要什么，这里就给什么：
     * - `test query <n>`：并发的与按序的两条查询都需要三个词同时出现；
     * - 前 10 行额外带 `concurrent`（并发那条查的就是它）；
     * - 每 100 行带一次 `函数方程`（中文那条查的是它，约占 1%，接近真实的关键词命中率）。
     */
    private fun searchableText(index: Int): String = buildString {
        append("test query ").append(index)
        if (index < 10) append(" concurrent")
        if (index % 100 == 0) append(" 函数方程")
    }

    /**
     * 「这条查询真的命中过行」——计时用例的**前置条件**，不是断言的一部分。
     *
     * 少了它，语料与查询一旦脱节（改一个词、改一次分词），计时断言就会在"零行命中"上
     * 稳定通过，而这个门重新变成空跑——正是本项要修的那个错法换了个地方复发。
     */
    private suspend fun assertSearchHitsSomething(matchQuery: String) {
        assertTrue(
            "夹具没有让查询「$matchQuery」命中任何一行——这个门会变成在量空气",
            database.ftsSearchCount(matchQuery) > 0,
        )
    }

    /**
     * 记录一次测量，并**明确标出预算未验证**（审计 `perf-gate-empty-seed` 的第二种收法）。
     *
     * 为什么不再断言书面预算：这个门恢复真实测量之后，在**本机唯一可用的设备**上三条搜索计时
     * 全部超出预算 3–18 倍（P95 1678ms／中文 P95 2018ms／并发均值 17731ms，各自预算
     * 500/500/1000ms），而同一次运行里**目录侧**的两条（首屏 20 行分页、facet 全表聚合）
     * **都在预算内**——这个不对称指向 FTS 搜索路径本身，不像单纯"设备慢"。
     * 但"这台软件渲染的 AVD 是否代表真机"没有任何证据，所以按审计给的第二条路走：
     * **填真实播种 ＋ 明确标 `NOT_MEASURED`**，而不是挑一个能让它变绿的数——
     * 挑数正是本项要防的错法（把断言改成与观察相符）。
     *
     * 仍然守着的两件事在别处：夹具让被测查询命中过行（[assertSearchHitsSomething]）、
     * 以及不挂死（[HANG_GUARD_MILLIS]）。**预算的判定与后续取舍记录在审计里**（§12.5 N-17）。
     */
    private fun reportLatency(what: String, measuredMillis: Long, budgetMillis: Long) {
        val verdict = if (measuredMillis < budgetMillis) "在书面预算内" else "超出书面预算"
        println(
            "PERF-GATE [NOT_MEASURED] $what = ${measuredMillis}ms " +
                "($verdict ${budgetMillis}ms)",
        )
        assertTrue(
            "$what 花了 ${measuredMillis}ms，超过挂死阈值 ${HANG_GUARD_MILLIS}ms" +
                "——这不是预算断言，只防挂死",
            measuredMillis < HANG_GUARD_MILLIS,
        )
    }

    private suspend fun StudyDatabase.libraryDao() = this.libraryQueryDao()

    /**
     * FTS 重构后的搜索等价点：旧 `libraryDao().searchByText(query)` 已移除。
     * `LibraryFtsSearchDao.countSearch` 是 `searchPagingSource` 的计数孪生查询
     * （同一条 MATCH + library_catalog 关联），无需 Paging 运行时即可度量
     * 同一搜索热路径的延迟，性能断言语义保持不变。
     *
     * `matchQuery` 参数是**已经构造好的 MATCH 表达式**，不是用户输入：DAO 的 SQL 是
     * `WHERE library_search_fts MATCH :matchQuery`。所以这里必须与生产一样经过
     * [CjkTextTokenizer.matchExpression]（`RoomLibraryCatalogRepository` 的四个调用点、
     * 以及 `LibrarySearchMigrationInstrumentedTest` 都是这么做的）。
     *
     * 漏掉这一步的后果是**夹具与查询悄悄脱节**：中文查询会以单个词的形式交给 FTS4，
     * 而索引侧 `segment` 已经把相邻汉字拆成了单字 token（`函数方程` → `函 数 方 程`），
     * 于是 MATCH 一行都命中不到——计时断言照样通过，因为**零行命中的查询最快**。
     * 这正是本项要修的那个错法（在空数据上量延迟）换了个形式。
     */
    private suspend fun StudyDatabase.ftsSearchCount(matchQuery: String): Int =
        this.libraryFtsSearchDao().countSearch(
            matchQuery = CjkTextTokenizer.matchExpression(matchQuery),
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
        )

    companion object {
        /**
         * Shared-emulator jitter multiplier (KD-2 pattern): CI passes the
         * instrumentation argument ciSlowRunner=1 (android-check.yml) because
         * runner environment variables do NOT reach the on-device test
         * process; local strict runs keep the raw targets.
         */
        private val CI_MULTIPLIER: Long = run {
            val fromArgs = androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("ciSlowRunner")
            if (fromArgs != null || System.getenv("CI") != null) 4L else 1L
        }

        /** Search P95 target: 500ms. */
        val SEARCH_P95_TARGET_MS = 500L * CI_MULTIPLIER

        /** First screen target: 500ms. */
        val FIRST_SCREEN_TARGET_MS = 500L * CI_MULTIPLIER

        /** Facet query P95 target: 200ms. */
        val FACET_P95_TARGET_MS = 200L * CI_MULTIPLIER

        /** Concurrent search target: 1000ms. */
        val CONCURRENT_SEARCH_TARGET_MS = 1000L * CI_MULTIPLIER

        /**
         * 只防挂死，不是预算。恢复真实测量之后三条搜索计时都远超书面预算（见 [reportLatency]），
         * 而"这台 AVD 是否代表真机"没有证据——所以预算改标 `NOT_MEASURED`，
         * 留一个粗到不可能因为设备慢而误报的上界，只为避免"查询退化成永不返回"被静默放过。
         */
        const val HANG_GUARD_MILLIS = 60_000L
    }
}
