package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayPageResult
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayRevision
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentMistakeLibraryPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryId
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeCurriculumSectionKey
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryCollectionSummary
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryDetail
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryFacets
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageRequest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibrarySection
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeSearchIndexStatus
import java.lang.reflect.Constructor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeCatalogStreamingProjectionTest {
    @Test
    fun streamsEveryBoundaryWithoutRetainingACompleteList() = runBlocking {
        listOf(0, 1, 30, 31, 20_000, 20_001).forEach { count ->
            val store = RecordingProjectionStore()
            val repository =
                StudentMistakeLibraryCatalogRepositoryFactory.createProjected(
                    expectedLearnerId = LEARNER_ID,
                    applicationScope = this,
                    projectionStoreProvider = { store },
                    libraryProvider = { GeneratedLibrary(count) },
                    masteryDisplayRevisions = MutableStateFlow(MASTERY_REVISION),
                    masteryRead =
                        LearnerBoundStudentMistakeMasteryRead { request ->
                            LearnerMasteryDisplayPageResult.Current(
                                revision = request.expectedRevision,
                                items = emptyList(),
                                taxonomyVersions = emptySet(),
                            )
                        },
                    knowledgeRevisions = MutableStateFlow(KNOWLEDGE_REVISION),
                    knowledgeDisplayResolver = { _, _ -> emptyMap() },
                    nowEpochMillis = { 100L },
                )
            try {
                val ready =
                    withTimeout(30_000L) {
                        repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
                    } as StudentMistakeLibraryCatalogState.Ready
                assertEquals(count.toLong(), ready.entryCount)
                assertEquals(count.toLong(), store.totalAppended)
                assertTrue(store.maximumBatchSize <= MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE)
                assertEquals(0, store.retainedEntryCount)
            } finally {
                repository.close()
            }
        }
    }

    @Test
    fun studentChangeDuringBuildDiscardsStagingInsteadOfPromoting() = runBlocking {
        val store = RecordingProjectionStore()
        val library = GeneratedLibrary(count = 31, changeStudentRevisionAfterFirstPage = true)
        val repository = projectedRepository(store, library)
        try {
            withTimeout(30_000L) {
                while (store.discardCount == 0) delay(1L)
            }
            assertTrue(repository.state.value is StudentMistakeLibraryCatalogState.WaitingForParity)
            assertEquals(1, store.beginCount)
            assertEquals(1, store.discardCount)
            assertEquals(0, store.promotionCount)
        } finally {
            repository.close()
        }
    }

    @Test
    fun masteryChangeDuringBuildDiscardsThenRebuildsAtOneFixedRevision() = runBlocking {
        val store = RecordingProjectionStore()
        val masteryRevisions = MutableStateFlow(MASTERY_REVISION)
        val repository =
            projectedRepository(
                store = store,
                library =
                    GeneratedLibrary(1) {
                        masteryRevisions.value = MASTERY_REVISION.copy(ledgerSequence = 12L)
                    },
                masteryRevisions = masteryRevisions,
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }
            assertEquals(2, store.beginCount)
            assertEquals(1, store.discardCount)
            assertEquals(1, store.promotionCount)
        } finally {
            repository.close()
        }
    }

    @Test
    fun knowledgeChangeDuringBuildDiscardsThenRebuildsAtOneFixedRevision() = runBlocking {
        val store = RecordingProjectionStore()
        val knowledgeRevisions = MutableStateFlow(KNOWLEDGE_REVISION)
        val repository =
            projectedRepository(
                store = store,
                library =
                    GeneratedLibrary(1) {
                        knowledgeRevisions.value =
                            KNOWLEDGE_REVISION.copy(activationGeneration = 4L)
                    },
                knowledgeRevisions = knowledgeRevisions,
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }
            assertEquals(2, store.beginCount)
            assertEquals(1, store.discardCount)
            assertEquals(1, store.promotionCount)
        } finally {
            repository.close()
        }
    }

    @Test
    fun independentMasteryAndKnowledgeInvalidationsRebuildWithoutAStudentChange() = runBlocking {
        val store = RecordingProjectionStore()
        val masteryRevisions = MutableStateFlow(MASTERY_REVISION)
        val knowledgeRevisions = MutableStateFlow(KNOWLEDGE_REVISION)
        val repository =
            projectedRepository(
                store = store,
                library = GeneratedLibrary(1),
                masteryRevisions = masteryRevisions,
                knowledgeRevisions = knowledgeRevisions,
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }

            masteryRevisions.value = MASTERY_REVISION.copy(ledgerSequence = 12L)
            withTimeout(5_000L) {
                repository.state.first { state ->
                    state is StudentMistakeLibraryCatalogState.Ready &&
                        state.revision.masteryLedgerSequence == 12L
                }
            }

            knowledgeRevisions.value = KNOWLEDGE_REVISION.copy(activationGeneration = 4L)
            withTimeout(5_000L) {
                repository.state.first { state ->
                    state is StudentMistakeLibraryCatalogState.Ready &&
                        state.revision.knowledgeActivationGeneration == 4L
                }
            }

            assertEquals(3, store.beginCount)
            assertEquals(3, store.promotionCount)
        } finally {
            repository.close()
        }
    }

    @Test
    fun knowledgeResolverReceivesTheExactRevisionThatTriggeredItsBuild() = runBlocking {
        val seenRevisions = mutableListOf<StudentMistakeKnowledgeCatalogRevision>()
        val store = RecordingProjectionStore()
        val repository =
            projectedRepository(
                store = store,
                library = GeneratedLibrary(count = 1, includeKnowledgeNode = true),
                knowledgeResolver = { expectedRevision, refs ->
                    seenRevisions += expectedRevision
                    refs.associateWith { "函数" }
                },
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }

            assertEquals(listOf(KNOWLEDGE_REVISION), seenRevisions)
        } finally {
            repository.close()
        }
    }

    @Test
    fun transientExactKnowledgeReadRetriesWithoutPublishingAnIncompleteGeneration() = runBlocking {
        var failuresRemaining = 1
        val store = RecordingProjectionStore()
        val repository =
            projectedRepository(
                store = store,
                library = GeneratedLibrary(count = 1, includeKnowledgeNode = true),
                knowledgeResolver = { _, refs ->
                    if (failuresRemaining-- > 0) error("transient knowledge read failure")
                    refs.associateWith { "函数" }
                },
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }

            assertEquals(2, store.beginCount)
            assertEquals(1, store.discardCount)
            assertEquals(1, store.promotionCount)
        } finally {
            repository.close()
        }
    }

    @Test
    fun closingAnOldGenerationCancelsLateProjectionBeforePromotion() = runBlocking {
        val resolverEntered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val store = RecordingProjectionStore()
        val repository =
            projectedRepository(
                store = store,
                library = GeneratedLibrary(count = 1, includeKnowledgeNode = true),
                knowledgeResolver = { _, _ ->
                    resolverEntered.complete(Unit)
                    neverReleased.await()
                    emptyMap()
                },
            )

        withTimeout(5_000L) { resolverEntered.await() }
        repository.close()
        withTimeout(5_000L) {
            while (store.discardCount == 0) delay(1L)
        }

        assertEquals(0, store.promotionCount)
        assertTrue(repository.state.value !is StudentMistakeLibraryCatalogState.Ready)
    }

    @Test
    fun transientProjectionStoreFailureRebuildsOnlyDerivedStoreAndRecovers() = runBlocking {
        val store = RecordingProjectionStore().apply { appendFailuresRemaining = 1 }
        val repository = projectedRepository(store, GeneratedLibrary(1))
        try {
            val ready =
                withTimeout(5_000L) {
                    repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
                } as StudentMistakeLibraryCatalogState.Ready

            assertEquals(1L, ready.entryCount)
            assertEquals(1, store.rebuildCount)
            assertEquals(1, store.promotionCount)
            assertTrue(repository.state.value !is StudentMistakeLibraryCatalogState.Unavailable)
        } finally {
            repository.close()
        }
    }

    @Test
    fun transientProjectionSessionOpenFailureStartsANewRecoverableRound() = runBlocking {
        var openAttempts = 0
        val store = RecordingProjectionStore()
        val repository =
            projectedRepository(
                store = store,
                library = GeneratedLibrary(1),
                projectionStoreProvider = {
                    if (openAttempts++ == 0) error("transient projection open failure")
                    store
                },
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }

            assertEquals(2, openAttempts)
            assertTrue(repository.state.value !is StudentMistakeLibraryCatalogState.Unavailable)
        } finally {
            repository.close()
        }
    }

    @Test
    fun oversizedAuthorityAssociationsFailClosedWithoutRetryingForever() = runBlocking {
        val store = RecordingProjectionStore()
        val repository =
            projectedRepository(
                store,
                GeneratedLibrary(
                    count = 1,
                    sectionsPerItem = MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM + 1,
                ),
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it === StudentMistakeLibraryCatalogState.ParityBlocked }
            }
            assertEquals(0, store.promotionCount)
            assertTrue(repository.state.value !is StudentMistakeLibraryCatalogState.Unavailable)
        } finally {
            repository.close()
        }
    }

    @Test
    fun regressedRevisionIsRejectedAndLaterMonotonicRevisionRecovers() = runBlocking {
        val store = RecordingProjectionStore()
        val masteryRevisions = MutableStateFlow(MASTERY_REVISION)
        val repository =
            projectedRepository(
                store = store,
                library = GeneratedLibrary(1),
                masteryRevisions = masteryRevisions,
            )
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }
            masteryRevisions.value = MASTERY_REVISION.copy(ledgerSequence = 12L)
            withTimeout(5_000L) {
                repository.state.first { state ->
                    state is StudentMistakeLibraryCatalogState.Ready &&
                        state.revision.masteryLedgerSequence == 12L
                }
            }

            masteryRevisions.value = MASTERY_REVISION
            withTimeout(5_000L) {
                repository.state.first { it === StudentMistakeLibraryCatalogState.ParityBlocked }
            }

            masteryRevisions.value = MASTERY_REVISION.copy(ledgerSequence = 13L)
            withTimeout(5_000L) {
                repository.state.first { state ->
                    state is StudentMistakeLibraryCatalogState.Ready &&
                        state.revision.masteryLedgerSequence == 13L
                }
            }
            Unit
        } finally {
            repository.close()
        }
    }

    @Test
    fun startupCleansBuildingAndReusesOnlyAnExactReadyRevision() = runBlocking {
        val store = RecordingProjectionStore()
        val library = GeneratedLibrary(1)
        val revision =
            StudentMistakeCatalogRevision(
                studentChangeVersion = STUDENT_REVISION,
                masteryLedgerSequence = MASTERY_REVISION.ledgerSequence,
                masteryAsOfEpochMillis = MASTERY_REVISION.asOfEpochMillis,
                knowledgeActivationGeneration = KNOWLEDGE_REVISION.activationGeneration,
                knowledgeManifestFingerprint = KNOWLEDGE_REVISION.manifestFingerprint,
                knowledgeTaxonomyVersion = KNOWLEDGE_REVISION.taxonomyVersion,
                knowledgePackVersion = KNOWLEDGE_REVISION.knowledgePackVersion,
            )
        store.seedReady(revision, indexedEntryCount = 20_001L)
        val repository = projectedRepository(store, library)
        try {
            val ready =
                withTimeout(5_000L) {
                    repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
                } as StudentMistakeLibraryCatalogState.Ready
            assertEquals(20_001L, ready.entryCount)
            assertEquals(1, store.cleanupCount)
            assertEquals(0, store.beginCount)
            assertEquals(0, library.pageReadCount)
        } finally {
            repository.close()
        }
    }

    @Test
    fun snapshotExportReturnsOneHundredAndRejectsOneHundredOneWithoutAModel() = runBlocking {
        listOf(100, 101).forEach { count ->
            val store = RecordingProjectionStore()
            val revision = exactRevision()
            store.seedReady(revision, indexedEntryCount = count.toLong())
            store.seedExportRows(count)
            val repository = projectedRepository(store, GeneratedLibrary(count))
            try {
                withTimeout(5_000L) {
                    repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
                }
                when (val result = repository.snapshotExport()) {
                    is StudentMistakeCatalogExportResult.Content -> {
                        assertEquals(100, count)
                        assertEquals(100, result.items.size)
                        assertEquals(0, result.modelCallCount)
                        assertEquals(0, result.modelTokenCount)
                    }

                    is StudentMistakeCatalogExportResult.TooMany -> assertEquals(101, count)
                    else -> error("Unexpected export result: $result")
                }
            } finally {
                repository.close()
            }
        }
    }

    @Test
    fun snapshotExportRejectsAnOversizedUtf8PayloadWithoutMaterializingMoreRows() = runBlocking {
        val store = RecordingProjectionStore()
        val revision = exactRevision()
        store.seedReady(revision, indexedEntryCount = 100L)
        store.seedExportRows(count = 100, title = "题".repeat(3_000))
        val repository = projectedRepository(store, GeneratedLibrary(100))
        try {
            withTimeout(5_000L) {
                repository.state.first { it is StudentMistakeLibraryCatalogState.Ready }
            }
            assertTrue(repository.snapshotExport() is StudentMistakeCatalogExportResult.PayloadTooLarge)
        } finally {
            repository.close()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.projectedRepository(
        store: RecordingProjectionStore,
        library: GeneratedLibrary,
        projectionStoreProvider:
            suspend () -> DiscardableStudentMistakeCatalogProjectionStore = { store },
        masteryRevisions: MutableStateFlow<LearnerMasteryDisplayRevision> =
            MutableStateFlow(MASTERY_REVISION),
        knowledgeRevisions: MutableStateFlow<StudentMistakeKnowledgeCatalogRevision> =
            MutableStateFlow(KNOWLEDGE_REVISION),
        knowledgeResolver:
            suspend (
                StudentMistakeKnowledgeCatalogRevision,
                List<KnowledgeNodeRef>,
            ) -> Map<KnowledgeNodeRef, String> = { _, _ -> emptyMap() },
    ): StudentMistakeLibraryCatalogRepository =
        StudentMistakeLibraryCatalogRepositoryFactory.createProjected(
            expectedLearnerId = LEARNER_ID,
            applicationScope = this,
            projectionStoreProvider = projectionStoreProvider,
            libraryProvider = { library },
            masteryDisplayRevisions = masteryRevisions,
            masteryRead =
                LearnerBoundStudentMistakeMasteryRead { request ->
                    LearnerMasteryDisplayPageResult.Current(
                        revision = request.expectedRevision,
                        items = emptyList(),
                        taxonomyVersions = emptySet(),
                    )
                },
            knowledgeRevisions = knowledgeRevisions,
            knowledgeDisplayResolver = knowledgeResolver,
            nowEpochMillis = { 100L },
        )

    private class GeneratedLibrary(
        private val count: Int,
        private val changeStudentRevisionAfterFirstPage: Boolean = false,
        private val sectionsPerItem: Int = 0,
        private val includeKnowledgeNode: Boolean = false,
        private val afterFirstPage: (() -> Unit)? = null,
    ) : LearnerBoundStudentMistakeLibraryPort {
        private val cursorOffsets = mutableMapOf<StudentMistakeLibraryCursor, Int>()
        private var currentStudentRevision = STUDENT_REVISION
        var pageReadCount = 0
            private set

        override fun observeChangeVersion(): Flow<Long> = flow { emit(currentStudentRevision) }

        override suspend fun readPage(
            request: StudentMistakeLibraryPageRequest,
        ): StudentMistakeLibraryPageResult {
            val start = request.cursor?.let(cursorOffsets::getValue) ?: 0
            val end = minOf(start + request.limit, count)
            val items = (start until end).map(::item)
            val next =
                if (end < count) {
                    opaqueStudentCursor(end).also { cursorOffsets[it] = end }
                } else {
                    null
                }
            pageReadCount += 1
            if (changeStudentRevisionAfterFirstPage && pageReadCount == 1) {
                currentStudentRevision += 1
            }
            if (pageReadCount == 1) afterFirstPage?.invoke()
            return StudentMistakeLibraryPageResult.Content(items, next)
        }

        override suspend fun readDetail(entryId: StudentMistakeEntryId): StudentMistakeLibraryDetail? =
            null

        override suspend fun readFacets(): StudentMistakeLibraryFacets =
            StudentMistakeLibraryFacets(STUDENT_REVISION, emptyList(), emptyList(), emptyList())

        override suspend fun prepareSearchIndex(maxDocuments: Int): StudentMistakeSearchIndexStatus =
            StudentMistakeSearchIndexStatus.Ready

        private fun item(index: Int): StudentMistakeLibraryItem {
            val suffix = index.toString().padStart(6, '0')
            val problem =
                StudentProblemRef(
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.MATH,
                    problemId = "problem-$suffix",
                    practiceUnitId = "unit-$suffix",
                )
            return StudentMistakeLibraryItem(
                entryId = StudentMistakeEntryId("entry-$suffix"),
                problemRevision =
                    StudentProblemRevisionRef(
                        problem = problem,
                        revisionId = "revision-$suffix",
                        revisionNumber = 1,
                        documentCanonicalFingerprint = "a".repeat(64),
                    ),
                subject = SubjectKind.MATH,
                title = "题目 $suffix",
                stemPreview = "函数题 $suffix",
                practiceUnitTitle = "函数",
                estimatedDurationSeconds = 60,
                collection =
                    StudentMistakeLibraryCollectionSummary(
                        state = StudentMistakeEntryState.ACTIVE,
                        favorite = false,
                        addedAtEpochMillis = index.toLong(),
                        changedAtEpochMillis = (count - index).toLong(),
                    ),
                sections =
                    List(sectionsPerItem) { sectionIndex ->
                        StudentMistakeLibrarySection(
                            StudentMistakeCurriculumSectionKey("section-$sectionIndex"),
                        )
                    },
                knowledgeNodes =
                    if (includeKnowledgeNode) {
                        listOf(
                            KnowledgeNodeRef(
                                subject = SubjectKind.MATH,
                                knowledgeNodeId = "node-$suffix",
                                taxonomyVersion = KNOWLEDGE_REVISION.taxonomyVersion,
                                knowledgePackVersion = KNOWLEDGE_REVISION.knowledgePackVersion,
                            ),
                        )
                    } else {
                        emptyList()
                    },
            )
        }
    }

    private class RecordingProjectionStore : DiscardableStudentMistakeCatalogProjectionStore {
        var totalAppended = 0L
        var maximumBatchSize = 0
        val retainedEntryCount: Int = 0
        var beginCount = 0
        var discardCount = 0
        var promotionCount = 0
        var cleanupCount = 0
        var rebuildCount = 0
        var appendFailuresRemaining = 0
        private var ready: DerivedStudentMistakeCatalogGeneration? = null
        private var exportRows: List<DerivedStudentMistakeCatalogEntryEntity> = emptyList()

        override suspend fun discardBuildingGenerations() {
            cleanupCount += 1
        }

        fun seedReady(revision: StudentMistakeCatalogRevision, indexedEntryCount: Long) {
            ready =
                DerivedStudentMistakeCatalogGeneration(
                    generationId = "ready-generation",
                    learnerFingerprint = LEARNER_ID.studentMistakeCatalogLearnerFingerprint(),
                    revision = revision,
                    cursorAuthenticationKey = "key",
                    indexedEntryCount = indexedEntryCount,
                )
        }

        fun seedExportRows(count: Int, title: String? = null) {
            exportRows =
                List(count) { index ->
                    DerivedStudentMistakeCatalogEntryEntity(
                        rowId = index + 1L,
                        generationId = "ready-generation",
                        entryId = "entry-$index",
                        problemId = "problem-$index",
                        problemRevisionId = "revision-$index",
                        practiceUnitId = "unit-$index",
                        subject = SubjectKind.MATH.name,
                        title = title ?: "题目 $index",
                        practiceUnitTitle = "函数",
                        sectionStableIdsWire = "",
                        knowledgeNodesWire = "",
                        knowledgeDisplayNamesWire = "",
                        masteryStatus = "UNKNOWN",
                        favorite = false,
                        changedAtEpochMillis = index.toLong(),
                        normalizedSearchText = "函数题 $index",
                        tokenizedSearchText = "函 数 题 $index",
                    )
                }
        }

        override suspend fun findReady(
            learnerFingerprint: String,
            revision: StudentMistakeCatalogRevision,
        ): DerivedStudentMistakeCatalogGeneration? =
            ready?.takeIf {
                it.learnerFingerprint == learnerFingerprint && it.revision == revision
            }

        override suspend fun beginBuilding(
            learnerFingerprint: String,
            revision: StudentMistakeCatalogRevision,
            nowEpochMillis: Long,
        ): DerivedStudentMistakeCatalogGeneration {
            totalAppended = 0L
            return DerivedStudentMistakeCatalogGeneration(
                generationId = "generation",
                learnerFingerprint = learnerFingerprint,
                revision = revision,
                cursorAuthenticationKey = "key",
                indexedEntryCount = 0,
            ).also { beginCount += 1 }
        }

        override suspend fun appendBatch(
            generation: DerivedStudentMistakeCatalogGeneration,
            batch: List<DerivedStudentMistakeCatalogBatchEntry>,
            indexedEntryCount: Long,
            nowEpochMillis: Long,
        ) {
            if (appendFailuresRemaining > 0) {
                appendFailuresRemaining -= 1
                error("transient derived store failure")
            }
            maximumBatchSize = maxOf(maximumBatchSize, batch.size)
            totalAppended += batch.size
            assertEquals(indexedEntryCount, totalAppended)
        }

        override suspend fun discard(generationId: String) {
            discardCount += 1
            totalAppended = 0L
        }

        override suspend fun promote(
            generation: DerivedStudentMistakeCatalogGeneration,
            nowEpochMillis: Long,
        ): Boolean {
            promotionCount += 1
            ready = generation.copy(indexedEntryCount = totalAppended)
            return true
        }

        override suspend fun readPage(
            generation: DerivedStudentMistakeCatalogGeneration,
            learnerFingerprint: String,
            revision: StudentMistakeCatalogRevision,
            filter: StudentMistakeCatalogFilter,
            cursor: StudentMistakeCatalogCursor?,
            limit: Int,
        ): DerivedStudentMistakeCatalogPage? = null

        override suspend fun readExportRows(
            generation: DerivedStudentMistakeCatalogGeneration,
            learnerFingerprint: String,
            revision: StudentMistakeCatalogRevision,
            filter: StudentMistakeCatalogFilter,
            limit: Int,
        ): List<DerivedStudentMistakeCatalogEntryEntity>? = exportRows.take(limit)

        override suspend fun readFacets(
            generation: DerivedStudentMistakeCatalogGeneration,
            learnerFingerprint: String,
            revision: StudentMistakeCatalogRevision,
            filter: StudentMistakeCatalogFilter,
        ): DerivedStudentMistakeCatalogFacets? = null

        override suspend fun rebuildAfterCorruption(): DiscardableStudentMistakeCatalogProjectionStore {
            rebuildCount += 1
            ready = null
            totalAppended = 0L
            return this
        }

        override fun close() = Unit
    }

    private companion object {
        const val LEARNER_ID = "learner-streaming"
        const val STUDENT_REVISION = 7L
        val MASTERY_REVISION = LearnerMasteryDisplayRevision(11L, 12L)
        val KNOWLEDGE_REVISION =
            StudentMistakeKnowledgeCatalogRevision(
                activationGeneration = 3L,
                manifestFingerprint = "b".repeat(64),
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )

        fun exactRevision(): StudentMistakeCatalogRevision =
            StudentMistakeCatalogRevision(
                studentChangeVersion = STUDENT_REVISION,
                masteryLedgerSequence = MASTERY_REVISION.ledgerSequence,
                masteryAsOfEpochMillis = MASTERY_REVISION.asOfEpochMillis,
                knowledgeActivationGeneration = KNOWLEDGE_REVISION.activationGeneration,
                knowledgeManifestFingerprint = KNOWLEDGE_REVISION.manifestFingerprint,
                knowledgeTaxonomyVersion = KNOWLEDGE_REVISION.taxonomyVersion,
                knowledgePackVersion = KNOWLEDGE_REVISION.knowledgePackVersion,
            )

        @Suppress("UNCHECKED_CAST")
        fun opaqueStudentCursor(offset: Int): StudentMistakeLibraryCursor {
            val constructor =
                StudentMistakeLibraryCursor::class.java.declaredConstructors.single()
                    as Constructor<StudentMistakeLibraryCursor>
            constructor.isAccessible = true
            return constructor.newInstance(
                STUDENT_REVISION,
                "c".repeat(64),
                "d".repeat(64),
                offset.toLong(),
                "problem-$offset",
            )
        }
    }
}
