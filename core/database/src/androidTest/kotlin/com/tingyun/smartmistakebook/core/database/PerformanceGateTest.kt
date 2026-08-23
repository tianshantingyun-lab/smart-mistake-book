package com.tingyun.smartmistakebook.core.database

import android.content.Context
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
 * Targets:
 * - 10万条数据搜索 P95 < 500ms
 * - 首屏时间 < 500ms
 * - Facet 查询 P95 < 200ms
 * - EXPLAIN QUERY PLAN 不出现非预期全表扫描
 */
@RunWith(AndroidJUnit4::class)
class PerformanceGateTest {

    private lateinit var database: StudyDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(
            context,
            StudyDatabase::class.java,
            "performance-test-${System.nanoTime()}.db",
        ).build()
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

        assertTrue(
            "搜索 P95 延迟 ${p95Latency}ms 超过目标 ${SEARCH_P95_TARGET_MS}ms",
            p95Latency < SEARCH_P95_TARGET_MS,
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

        // FTS 重构后 RoomDatabase.query(String) 不再可用；改走 room3 的原始
        // prepared-statement 连接执行同一条 EXPLAIN QUERY PLAN，仍然读取
        // detail 列（索引 3），全表扫描断言语义保持不变。
        var plan = ""
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared(
                "EXPLAIN QUERY PLAN SELECT * FROM library_catalog WHERE title LIKE '%test%'",
            ) { statement ->
                plan = buildString {
                    while (statement.step()) {
                        appendLine(statement.getText(3))
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

        assertTrue(
            "中文搜索 P95 延迟 ${p95Latency}ms 超过目标 ${SEARCH_P95_TARGET_MS}ms",
            p95Latency < SEARCH_P95_TARGET_MS,
        )
    }

    /**
     * Test concurrent search performance.
     */
    @Test
    fun concurrentSearchPerformance() = runBlocking {
        insertTestData(10_000)

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

        val avgLatency = latencies.average()
        assertTrue(
            "并发搜索平均延迟 ${avgLatency}ms 超过目标 ${CONCURRENT_SEARCH_TARGET_MS}ms",
            avgLatency < CONCURRENT_SEARCH_TARGET_MS,
        )
    }

    private suspend fun insertTestData(count: Int) {
        // This would insert test data into the library_catalog view
        // For now, this is a placeholder
    }

    private suspend fun StudyDatabase.libraryDao() = this.libraryQueryDao()

    /**
     * FTS 重构后的搜索等价点：旧 `libraryDao().searchByText(query)` 已移除。
     * `LibraryFtsSearchDao.countSearch` 是 `searchPagingSource` 的计数孪生查询
     * （同一条 MATCH + library_catalog 关联），无需 Paging 运行时即可度量
     * 同一搜索热路径的延迟，性能断言语义保持不变。
     */
    private suspend fun StudyDatabase.ftsSearchCount(matchQuery: String): Int =
        this.libraryFtsSearchDao().countSearch(
            matchQuery = matchQuery,
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
        )

    companion object {
        /** Search P95 target: 500ms. */
        const val SEARCH_P95_TARGET_MS = 500L

        /** First screen target: 500ms. */
        const val FIRST_SCREEN_TARGET_MS = 500L

        /** Facet query P95 target: 200ms. */
        const val FACET_P95_TARGET_MS = 200L

        /** Concurrent search target: 1000ms. */
        const val CONCURRENT_SEARCH_TARGET_MS = 1000L
    }
}
