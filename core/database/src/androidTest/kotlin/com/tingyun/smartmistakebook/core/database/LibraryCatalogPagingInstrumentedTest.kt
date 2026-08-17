package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryCatalogPagingInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun fiftyThousandRowsPageSearchAndFacetCountsInSql() = runBlocking {
        val count = 50_000
        store.seedFixture(
            StudySeedBundle(
                problems = List(count) { index ->
                    ProblemSeedRecord(
                        problemId = "problem-$index",
                        canonicalFingerprint = index.toString(16).padStart(64, '0'),
                        subject = if (index % 2 == 0) "MATH" else "PHYSICS",
                        createdAtEpochMillis = index + 1L,
                    )
                },
                revisions = List(count) { index ->
                    ProblemRevisionSeedRecord(
                        revisionId = "revision-$index",
                        problemId = "problem-$index",
                        revisionNumber = 1,
                        title = "分页题目 ${index + 1}",
                        problemMarkdown = if (index % 100 == 0) {
                            "独特检索词$index"
                        } else {
                            "普通题面 $index"
                        },
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
                        practiceUnitId = "practice-$index",
                        problemId = "problem-$index",
                        problemRevisionId = "revision-$index",
                        unitKey = "whole-problem",
                        unitKind = "WHOLE_PROBLEM",
                        title = "分页题目 ${index + 1}",
                        promptMarkdown = if (index % 100 == 0) "独特检索词$index" else "普通题面",
                        estimatedSeconds = 180,
                        createdAtEpochMillis = index + 1L,
                    )
                },
                errorBookEntries = List(count) { index ->
                    ErrorBookEntrySeedRecord(
                        entryId = "entry-$index",
                        practiceUnitId = "practice-$index",
                        problemId = "problem-$index",
                        currentRevisionId = "revision-$index",
                        sourceKey = null,
                        acceptedAtEpochMillis = index + 1L,
                        updatedAtEpochMillis = index + 1L,
                    )
                },
            ),
        )
        val dao = store.database.libraryQueryDao()

        assertEquals(count, dao.count("", null, null, null, null))

        val lastPage = dao.page(
            searchText = "",
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "RECENTLY_CREATED",
            offset = count - 10,
            limit = 10,
        )
        assertEquals(10, lastPage.size)
        assertTrue(lastPage.first().entryId == "entry-9")

        val searchHits = dao.page(
            searchText = "独特检索词",
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "RECENTLY_CREATED",
            offset = 0,
            limit = 1_000,
        )
        assertEquals(count / 100, searchHits.size)
        assertTrue(searchHits.all { it.problemMarkdown.startsWith("独特检索词") })

        val subjectFacets = dao.subjectFacets(
            searchText = "",
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
        )
        assertEquals(setOf("MATH", "PHYSICS"), subjectFacets.map { it.id }.toSet())
        assertEquals(count, subjectFacets.sumOf { it.count })
    }
}
