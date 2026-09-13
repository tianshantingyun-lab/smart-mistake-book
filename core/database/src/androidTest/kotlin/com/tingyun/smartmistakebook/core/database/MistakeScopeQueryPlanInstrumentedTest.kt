package com.tingyun.smartmistakebook.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.dao.MISTAKE_CATALOG_SQL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 审计 N-19：定向取一道题 KC 范围的那条查询，包在目录 SQL 的**派生表**里——两者共用同一份
 * 文本（`MISTAKE_CATALOG_SQL`），所以范围不可能与目录分叉。这条用例管的是另一半：**它到底省不省**。
 *
 * 若 SQLite 不把 `practice_unit_id = ?` 下推进派生表，那它就是把整份目录（每行还带 5 个相关
 * 子查询）先物化再筛——代价比原来的整读**更高**，那时这个"优化"就是自欺。
 *
 * 断言取"计划里不出现 `SCAN error_book_entry`"，并把计划文本原样放进失败信息：将来若重新规划，
 * 这条用例会把 SQLite 自己的说法交出来，而不是只说一句"红了"。
 */
@RunWith(AndroidJUnit4::class)
class MistakeScopeQueryPlanInstrumentedTest {

    @Test
    fun theTargetedScopeQueryDoesNotScanTheWholeCatalogue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "mistake-scope-plan-${System.nanoTime()}.db"
        val databasePath = context.getDatabasePath(databaseName).path
        val store = StudyDatabaseFactory.open(context, databaseName)
        // 先把 schema 建出来（这次读本身不需要任何数据行）。
        store.countMistakes()

        val plan = mutableListOf<String>()
        SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery(SCOPED_QUERY, arrayOf("unit-probe")).use { cursor ->
                while (cursor.moveToNext()) {
                    plan += cursor.getString(cursor.columnCount - 1)
                }
            }
        }

        assertTrue(
            "计划是空的 ⇒ 下面那条断言在证明空气（EXPLAIN 没跑到，或游标没读到行）",
            plan.isNotEmpty(),
        )
        assertFalse(
            "计划里出现了整表扫描，这个定向查询没有省下任何代价：$plan",
            plan.any { step -> step.contains("SCAN error_book_entry") },
        )
        assertTrue(
            "定向查询应当按 practice_unit_id 走索引搜索，而不是靠别的方式碰巧少读：$plan",
            plan.any { step -> step.contains("SEARCH") },
        )
    }

    private companion object {
        /**
         * 逐字复刻 `ProblemDao.knowledgeNodeIdsForPracticeUnit` 的 SQL（同一个常量、
         * 同一个外层），只把绑定参数交给 `rawQuery` 的选择参数。
         */
        val SCOPED_QUERY =
            "EXPLAIN QUERY PLAN SELECT catalog.knowledge_node_ids " +
                "FROM ($MISTAKE_CATALOG_SQL) AS catalog " +
                "WHERE catalog.practice_unit_id = ?"
    }
}
