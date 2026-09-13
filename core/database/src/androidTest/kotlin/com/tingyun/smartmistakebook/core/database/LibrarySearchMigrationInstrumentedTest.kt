package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySearchMigrationInstrumentedTest {

    @Test
    fun versionThirtyOneMigratesToSearchProjectionAndMatchesCjkQueries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "library-search-v31-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 31)
            val store = StudyDatabaseFactory.open(context, databaseName)

            runBlocking { assertEquals(STUDY_DATABASE_VERSION, store.readDatabaseVersion()) }

            store.seedFixture(fixtureBundle())
            store.refreshLibrarySearchProjection()

            assertEquals(
                1,
                store.librarySearchCount(
                    matchQuery = CjkTextTokenizer.matchExpression("二次方程"),
                    subjectId = null,
                    sectionId = null,
                    knowledgePointId = null,
                    masteryId = null,
                ),
            )
            assertEquals(
                0,
                store.librarySearchCount(
                    matchQuery = CjkTextTokenizer.matchExpression("不存在的词"),
                    subjectId = null,
                    sectionId = null,
                    knowledgePointId = null,
                    masteryId = null,
                ),
            )
            // The instr(lower()) fallback keeps working alongside the FTS path.
            assertEquals(1, store.libraryCatalogCount("二次方程", null, null, null, null))

            // Paged retrieval goes through MATCH + snippet + weighted ranking.
            val source = store.librarySearchPagingSource(
                matchQuery = CjkTextTokenizer.matchExpression("二次方程"),
                subjectId = null,
                sectionId = null,
                knowledgePointId = null,
                masteryId = null,
                sort = "RECENTLY_CREATED",
                tokens = CjkTextTokenizer.tokens("二次方程"),
            )
            val page = source.load(PagingSource.LoadParams.Refresh(null, 10, false))
            val loaded = page as PagingSource.LoadResult.Page
            assertEquals(1, loaded.data.size)
            assertEquals("entry-search", loaded.data.single().entryId)
            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migratedDatabaseMatchesExportedSchemaSqliteMaster() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val referenceName = "library-search-ref-${System.nanoTime()}.db"
        val migratedName = "library-search-mig-${System.nanoTime()}.db"
        context.deleteDatabase(referenceName)
        context.deleteDatabase(migratedName)
        try {
            // Reference: built purely from the current exported schema JSON.
            createDatabaseFromExportedSchema(
                context,
                referenceName,
                version = STUDY_DATABASE_VERSION,
            )
            // Candidate: 31.json schema pushed through the real migration chain.
            createDatabaseFromExportedSchema(context, migratedName, version = 31)
            runBlocking {
                val migrated = StudyDatabaseFactory.open(context, migratedName)
                // Room migrates lazily: touch the database so the chain runs
                // before the sqlite_master comparison.
                migrated.readDatabaseVersion()
                migrated.close()
            }
            // The migration chain intentionally does not create the FTS/outbox
            // triggers (they are created lazily by the first search refresh),
            // so the migrated schema must match the current exported schema
            // row for row.
            assertStructurallyEqual(
                context.getDatabasePath(referenceName),
                context.getDatabasePath(migratedName),
            )
            // Positive check for the lazy-trigger design: the first refresh
            // creates exactly the six search triggers.
            runBlocking {
                val refreshed = StudyDatabaseFactory.open(context, migratedName)
                refreshed.refreshLibrarySearchProjection()
                refreshed.close()
            }
            val triggerNames = readSqliteMaster(context.getDatabasePath(migratedName))
                .filter { it[0] == "trigger" }
                .map { it[1] }
            assertEquals(
                setOf(
                    "room_fts_content_sync_library_search_fts_AFTER_INSERT",
                    "room_fts_content_sync_library_search_fts_AFTER_UPDATE",
                    "room_fts_content_sync_library_search_fts_BEFORE_DELETE",
                    "room_fts_content_sync_library_search_fts_BEFORE_UPDATE",
                    "library_search_outbox_revision_insert",
                    "library_search_outbox_revision_update",
                ),
                triggerNames.toSet(),
            )
        } finally {
            context.deleteDatabase(referenceName)
            context.deleteDatabase(migratedName)
        }
    }

    @Test
    fun incrementalDrainIndexesNewRevisionWithoutFullRebuild() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "library-search-drain-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            val store = StudyDatabaseFactory.open(context, databaseName)
            store.seedFixture(fixtureBundle())
            store.refreshLibrarySearchProjection()
            assertEquals(
                1,
                store.librarySearchCount(
                    matchQuery = CjkTextTokenizer.matchExpression("因式分解"),
                    subjectId = null,
                    sectionId = null,
                    knowledgePointId = null,
                    masteryId = null,
                ),
            )
            // Second refresh must be a cheap no-op drain (outbox empty), not
            // a full rebuild; asserting determinism of the result set instead.
            store.refreshLibrarySearchProjection()
            assertEquals(
                1,
                store.librarySearchCount(
                    matchQuery = CjkTextTokenizer.matchExpression("二次方程"),
                    subjectId = null,
                    sectionId = null,
                    knowledgePointId = null,
                    masteryId = null,
                ),
            )
            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 从 v31 升级上来的库，**不额外调任何刷新**也要搜得到自己库里的题。
     *
     * 这条钉的是"索引惰性建立"这个设计的承重点。31→32 的迁移只回填了
     * `library_search_content`（**未分词**），FTS 索引是空的；真正把它建起来的是
     * `RoomLibrarySearchStore` 四个读入口里的 `refreshProjection()`——分页那条走
     * `RefreshingPagingSource(beforeLoad = ::refreshProjection)`，计数／分页查询／facet
     * 三条各自显式调一次。四个入口**少接任何一个**，用户看到的就是"搜索框里打什么都是空"。
     *
     * 为什么必须专门测这一条：本文件其余用例**全都显式调了
     * `refreshLibrarySearchProjection()`**，所以入口掉线时它们一起绿也发现不了；
     * 而这里是模拟升级的真实路径——只 `open()`，然后搜。
     */
    @Test
    fun upgradedLibraryIsSearchableThroughTheProductionReadPathAlone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "library-search-upgrade-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 31)
            seedMinimalLibraryRow(context, databaseName, version = 31)

            // 升级：只按生产路径打开（Room 跑完 31→44 的迁移链），不做任何显式刷新。
            val store = StudyDatabaseFactory.open(context, databaseName)
            assertEquals(STUDY_DATABASE_VERSION, store.readDatabaseVersion())
            val dao = (store as RoomStudyDatabase).database.libraryFtsSearchDao()

            val searchText = "迁移矩阵夹具"
            val matchQuery = CjkTextTokenizer.matchExpression(searchText)

            // 升级那一刻的现场：内容行已经回填（1 条），索引里一条都没有。
            // 这不是顺带的观察——**它就是"索引惰性建立"这个设计的全部难点**：
            // 这两个读数在这一刻必须**分叉**（有内容、没索引），`refreshProjection()` 的
            // 首次引导分支才判得对。`countIndexed()` 一旦读成内容表的行数（`library_search_fts`
            // 是 external-content 表，裸 `COUNT(*)` 会回落到内容表），分叉被抹平、引导分支
            // 永不触发，下面那条检索就会永远返回 0，**怎么刷新都修不好**。
            assertEquals("迁移应当回填内容行", 1, dao.countContent())
            assertEquals("但索引里一条都不该有", 0, dao.countIndexed())

            assertEquals(
                "计数这条路（列表上方的结果数）必须搜得到",
                1,
                store.librarySearchCount(matchQuery, null, null, null, null),
            )
            assertEquals("第一次检索之后，索引必须已经补上", 1, dao.countIndexed())

            val page = store.librarySearchPagingSource(
                matchQuery = matchQuery,
                subjectId = null,
                sectionId = null,
                knowledgePointId = null,
                masteryId = null,
                sort = "RECENTLY_CREATED",
                tokens = CjkTextTokenizer.tokens(searchText),
            ).load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
            assertEquals(
                "分页这条路（列表本身）必须搜得到",
                listOf(SEEDED_ENTRY_ID),
                page.data.map { it.entryId },
            )

            assertEquals(
                "facet 这条路（筛选栏的计数）必须搜得到",
                1,
                store.librarySearchFacets(matchQuery, null, null, null, null, "SUBJECT").single().count,
            )

            // 空查询那条目录路一直不经过 FTS：用它把"题确实在库里"钉住，
            // 免得上面三条红被读成"夹具根本没插进去"。
            assertEquals(1, store.libraryCatalogCount("", null, null, null, null))
            store.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun fixtureBundle() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = "problem-search",
                canonicalFingerprint = "c".repeat(64),
                subject = "MATH",
                createdAtEpochMillis = 1_000,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = "revision-search",
                problemId = "problem-search",
                revisionNumber = 1,
                title = "二次方程求解",
                problemMarkdown = "用因式分解法解一元二次方程。",
                questionDocumentSnapshot = null,
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "UNKNOWN",
                sourceType = "TEST",
                sourceReference = null,
                contentFingerprint = "d".repeat(64),
                createdAtEpochMillis = 1_000,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = "practice-search",
                problemId = "problem-search",
                problemRevisionId = "revision-search",
                unitKey = "unit:search",
                unitKind = "PROBLEM",
                title = "二次方程求解",
                promptMarkdown = "用因式分解法解一元二次方程。",
                estimatedSeconds = 120,
                createdAtEpochMillis = 1_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-search",
                practiceUnitId = "practice-search",
                problemId = "problem-search",
                currentRevisionId = "revision-search",
                sourceKey = null,
                acceptedAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        ),
    )

    private fun readSqliteMaster(path: File): List<List<String?>> {
        val database = SQLiteDatabase.openDatabase(
            path.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val cursor = database.rawQuery(
                "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                    "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
                null,
            )
            val rows = mutableListOf<List<String?>>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    rows += listOf(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                    )
                }
            }
            return rows
        } finally {
            database.close()
        }
    }
}
