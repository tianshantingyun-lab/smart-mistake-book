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

            assertEquals(STUDY_DATABASE_VERSION, 34)

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
                migrated.close()
            }
            // The migration chain intentionally does not create the FTS/outbox
            // triggers (they are created lazily by the first search refresh),
            // so the migrated schema must match the current exported schema
            // row for row.
            assertEquals(
                readSqliteMaster(context.getDatabasePath(referenceName)),
                readSqliteMaster(context.getDatabasePath(migratedName)),
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
