package com.tingyun.smartmistakebook.core.data.mistake

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeCatalogProjectionStoreInstrumentedTest {
    @Test
    fun byteBoundedPageReturnsAnAuthenticatedContinuationWithoutDroppingRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(STUDENT_MISTAKE_CATALOG_PROJECTION_DATABASE_NAME)
        val store = StudentMistakeCatalogProjectionDatabaseFactory.open(context)
        try {
            val revision = revision()
            val generation =
                store.beginBuilding(
                    learnerFingerprint = LEARNER_FINGERPRINT,
                    revision = revision,
                    nowEpochMillis = 1L,
                )
            val entries = List(10) { index -> largeEntry(generation.generationId, index) }
            store.appendBatch(generation, entries.take(6), indexedEntryCount = 6L, nowEpochMillis = 2L)
            store.appendBatch(generation, entries.drop(6), indexedEntryCount = 10L, nowEpochMillis = 3L)
            assertTrue(store.promote(generation, nowEpochMillis = 4L))
            val ready = generation.copy(indexedEntryCount = 10L)

            val first =
                requireNotNull(
                    store.readPage(
                        generation = ready,
                        learnerFingerprint = LEARNER_FINGERPRINT,
                        revision = revision,
                        filter = StudentMistakeCatalogFilter(),
                        cursor = null,
                        limit = MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE,
                    ),
                )
            val second =
                requireNotNull(
                    store.readPage(
                        generation = ready,
                        learnerFingerprint = LEARNER_FINGERPRINT,
                        revision = revision,
                        filter = StudentMistakeCatalogFilter(),
                        cursor = requireNotNull(first.nextCursor),
                        limit = MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE,
                    ),
                )

            assertTrue(first.rows.size in 1..9)
            assertEquals(null, second.nextCursor)
            assertEquals(
                10,
                (first.rows + second.rows).map { it.entryId }.distinct().size,
            )
        } finally {
            store.close()
            context.deleteDatabase(STUDENT_MISTAKE_CATALOG_PROJECTION_DATABASE_NAME)
        }
    }

    @Test
    fun facetSnapshotCannotMixRowsAcrossConcurrentPromotion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database =
            Room.inMemoryDatabaseBuilder(
                context,
                StudentMistakeCatalogProjectionRoomDatabase::class.java,
            ).setDriver(AndroidSQLiteDriver())
                .build()
        try {
            database.useConnection(isReadOnly = false) { Unit }
            val dao = database.projectionDao()
            repeat(20) { iteration ->
                val oldGeneration = "old-$iteration"
                val newGeneration = "new-$iteration"
                stageReadyGeneration(dao, oldGeneration, iteration * 2L)
                stageBuildingGeneration(dao, newGeneration, iteration * 2L + 1L)

                val start = CompletableDeferred<Unit>()
                val facetRead =
                    async(Dispatchers.IO) {
                        start.await()
                        readFacetRows(dao, oldGeneration)
                    }
                val promotion =
                    async(Dispatchers.IO) {
                        start.await()
                        dao.promoteGeneration(newGeneration, LEARNER_FINGERPRINT, 100L)
                    }
                start.complete(Unit)
                val rows = facetRead.await()
                assertTrue(promotion.await())

                if (rows.totalCount == 0L) {
                    assertTrue(rows.subjects.isEmpty())
                    assertTrue(rows.sections.isEmpty())
                    assertTrue(rows.knowledge.isEmpty())
                    assertTrue(rows.mastery.isEmpty())
                } else {
                    assertEquals(1L, rows.totalCount)
                    assertEquals(1L, rows.subjects.single().problemCount)
                    assertEquals(1L, rows.sections.single().problemCount)
                    assertEquals(1L, rows.mastery.single().problemCount)
                }
            }
        } finally {
            database.close()
        }
    }

    private suspend fun stageReadyGeneration(
        dao: StudentMistakeCatalogProjectionDao,
        generationId: String,
        changedAtEpochMillis: Long,
    ) {
        stageBuildingGeneration(dao, generationId, changedAtEpochMillis)
        assertTrue(dao.promoteGeneration(generationId, LEARNER_FINGERPRINT, 50L))
    }

    private suspend fun stageBuildingGeneration(
        dao: StudentMistakeCatalogProjectionDao,
        generationId: String,
        changedAtEpochMillis: Long,
    ) {
        dao.beginGeneration(generation(generationId))
        val entry = entry(generationId, changedAtEpochMillis)
        dao.appendBatch(
            generationId = generationId,
            batch =
                listOf(
                    DerivedStudentMistakeCatalogBatchEntry(
                        entry = entry,
                        sectionStableIds = listOf("section"),
                        knowledgeNodes = emptyList(),
                    ),
                ),
            indexedEntryCount = 1L,
            updatedAtEpochMillis = changedAtEpochMillis,
        )
    }

    private suspend fun readFacetRows(
        dao: StudentMistakeCatalogProjectionDao,
        generationId: String,
    ): DerivedStudentMistakeCatalogFacetRows {
        val query =
            DerivedStudentMistakeCatalogFilterQuery(
                ftsMatchExpression = null,
                normalizedSearchText = null,
                subject = null,
                favoriteOnly = false,
                masteryFilterDisabled = true,
                masteryStatuses = emptyList(),
                sectionStableId = null,
                knowledgeSubject = null,
                knowledgeNodeId = null,
                knowledgeTaxonomyVersion = null,
                knowledgePackVersion = null,
            )
        return dao.readFacetRows(
            totalCountQuery = DerivedStudentMistakeCatalogSql.count(query, generationId),
            subjectQuery = DerivedStudentMistakeCatalogSql.subjectFacets(query, generationId),
            sectionQuery = DerivedStudentMistakeCatalogSql.sectionFacets(query, generationId),
            knowledgeQuery = DerivedStudentMistakeCatalogSql.knowledgeFacets(query, generationId),
            masteryQuery = DerivedStudentMistakeCatalogSql.masteryFacets(query, generationId),
        )
    }

    private fun generation(generationId: String): DerivedStudentMistakeCatalogGenerationEntity =
        DerivedStudentMistakeCatalogGenerationEntity(
            generationId = generationId,
            learnerFingerprint = LEARNER_FINGERPRINT,
            state = DerivedStudentMistakeCatalogGenerationState.BUILDING.name,
            revisionFingerprint = "a".repeat(64),
            studentChangeVersion = 1L,
            masteryLedgerSequence = 1L,
            masteryAsOfEpochMillis = 1L,
            knowledgeActivationGeneration = 1L,
            knowledgeManifestFingerprint = "b".repeat(64),
            knowledgeTaxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
            cursorAuthenticationKey = "key",
            indexedEntryCount = 0L,
            updatedAtEpochMillis = 0L,
        )

    private fun entry(
        generationId: String,
        changedAtEpochMillis: Long,
    ): DerivedStudentMistakeCatalogEntryEntity =
        DerivedStudentMistakeCatalogEntryEntity(
            generationId = generationId,
            entryId = "entry-$generationId",
            problemId = "problem-$generationId",
            problemRevisionId = "revision-$generationId",
            practiceUnitId = "unit-$generationId",
            subject = SubjectKind.MATH.name,
            title = "title",
            practiceUnitTitle = "unit",
            sectionStableIdsWire = ProjectionWireCodec.encodeStrings(listOf("section")),
            knowledgeNodesWire = "",
            knowledgeDisplayNamesWire = "",
            masteryStatus = MasteryStatus.UNKNOWN.name,
            favorite = false,
            changedAtEpochMillis = changedAtEpochMillis,
            normalizedSearchText = "title",
            tokenizedSearchText = "title",
        )

    private fun largeEntry(
        generationId: String,
        index: Int,
    ): DerivedStudentMistakeCatalogBatchEntry {
        val suffix = index.toString().padStart(2, '0')
        val sections =
            List(MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM) { sectionIndex ->
                "section-$suffix-$sectionIndex-${"题".repeat(140)}"
            }
        return DerivedStudentMistakeCatalogBatchEntry(
            entry =
                DerivedStudentMistakeCatalogEntryEntity(
                    generationId = generationId,
                    entryId = "large-entry-$suffix",
                    problemId = "large-problem-$suffix",
                    problemRevisionId = "large-revision-$suffix",
                    practiceUnitId = "large-unit-$suffix",
                    subject = SubjectKind.MATH.name,
                    title = "title-$suffix",
                    practiceUnitTitle = "unit",
                    sectionStableIdsWire = ProjectionWireCodec.encodeStrings(sections),
                    knowledgeNodesWire = "",
                    knowledgeDisplayNamesWire = "",
                    masteryStatus = MasteryStatus.UNKNOWN.name,
                    favorite = false,
                    changedAtEpochMillis = (100 - index).toLong(),
                    normalizedSearchText = "title-$suffix",
                    tokenizedSearchText = "title-$suffix",
                ),
            sectionStableIds = sections,
            knowledgeNodes = emptyList(),
        )
    }

    private fun revision(): StudentMistakeCatalogRevision =
        StudentMistakeCatalogRevision(
            studentChangeVersion = 1L,
            masteryLedgerSequence = 1L,
            masteryAsOfEpochMillis = 1L,
            knowledgeActivationGeneration = 1L,
            knowledgeManifestFingerprint = "b".repeat(64),
            knowledgeTaxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )

    private companion object {
        const val LEARNER_FINGERPRINT =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
