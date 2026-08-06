package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionSummary
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentMistakeLibraryPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeDetailQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryId
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryCollectionSummary
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryDetail
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryFacets
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageRequest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeSearchIndexStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentPracticeUnitKind
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemClassificationDimension
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemClassificationResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemClassificationStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemDocument
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemImageReference
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemLifecycleState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemRevisionHistoryItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemRevisionHistoryPage
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemRevisionHistoryQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeStoreMistakeDetailRepositoryTest {
    @Test
    fun mapsTypedDetailRevisionClassificationAndCompleteOriginalImage() = runBlocking {
        val document = capturedDocument()
        val revision = revision(document, revisionId = "revision-2", revisionNumber = 2)
        val detail = problemDocument(
            revision = revision,
            document = document,
            images = listOf(completeImage()),
        )
        val classification = classification(revision)
        val port = FakeStudentMistakeDetailReadPort(
            detail = detail,
            classifications = listOf(classification),
            history = listOf(historyItem(revision, committedAtEpochMillis = 7_000L)),
        )
        val repository = StudentMistakeStoreMistakeDetailRepository(port)

        val detailQuery = detailQuery(revision.problem)
        val states = repository.observeScoped(detailQuery).toList()

        assertSame(MistakeDetailState.Loading, states.first())
        val ready = states.last() as MistakeDetailState.Ready
        assertSame(document, ready.questionDocument)
        assertEquals(ENTRY_ID, ready.detail.identity.errorBookEntryId)
        assertEquals(PROBLEM_ID, ready.detail.identity.problemId)
        assertEquals("revision-2", ready.detail.identity.problemRevisionId)
        assertEquals(2, ready.detail.identity.revisionNumber)
        assertEquals("函数题", ready.detail.identity.title)
        assertEquals(SubjectKind.MATH.name, ready.detail.identity.subject)
        assertEquals(PRACTICE_UNIT_ID, ready.detail.identity.practiceUnitId)
        assertEquals("求函数值。", ready.detail.fallbackMarkdown)
        assertTrue(!ready.detail.fallbackMarkdown.contains("分类标签不得进入详情"))
        assertEquals(null, ready.detail.tutorConversation)

        val source = (ready.detail.source as MistakeSourceSet.Present).assets.single()
        assertEquals("image-1", source.sourceAssetId)
        assertEquals("5".repeat(64), source.contentSha256)
        assertEquals("image/jpeg", source.mimeType)
        assertEquals(42_000L, source.byteSize)
        assertEquals(1_200, source.width)
        assertEquals(800, source.height)
        assertEquals(7_000L, source.createdAtEpochMillis)
        assertEquals("STUDENT_MISTAKE_ORIGINAL", source.sourceType)
        assertEquals(
            MistakeSourceLocation.Available("content://student-mistakes/original-1"),
            source.location,
        )
        assertEquals(listOf(revision), port.classificationRequests)

        val history = repository.observeRevisionHistoryScoped(detailQuery).toList().single()
        assertEquals(1, history.size)
        assertEquals("revision-2", history.single().problemRevisionId)
        assertTrue(history.single().isCurrent)
        assertTrue(
            repository.readExactScoped(
                StudentMistakeRevisionDetailQuery(
                    learnerId = revision.problem.learnerId,
                    errorBookEntryId = ENTRY_ID,
                    revision = revision,
                ),
            ) is MistakeDetailState.Ready,
        )
    }

    @Test
    fun historicalExactReadFailsClosedWhenStoreOnlyExposesCurrentDocument() = runBlocking {
        val document = capturedDocument()
        val currentRevision = revision(document, revisionId = "revision-2", revisionNumber = 2)
        val historicalRevision = revision(document, revisionId = "revision-1", revisionNumber = 1)
        val port = FakeStudentMistakeDetailReadPort(
            detail = problemDocument(revision = currentRevision, document = document),
            history =
                listOf(
                    historyItem(currentRevision, 2_000L),
                    historyItem(historicalRevision, 1_000L),
                ),
        )
        val repository = StudentMistakeStoreMistakeDetailRepository(port)

        val result =
            repository.readExactScoped(
                StudentMistakeRevisionDetailQuery(
                    learnerId = historicalRevision.problem.learnerId,
                    errorBookEntryId = ENTRY_ID,
                    revision = historicalRevision,
                ),
            )

        assertSame(MistakeDetailState.NotFound, result)
        val history =
            repository.observeRevisionHistoryScoped(
                detailQuery(currentRevision.problem),
            ).toList().single()
        assertEquals(listOf("revision-2", "revision-1"), history.map { it.problemRevisionId })
        assertEquals(listOf(true, false), history.map { it.isCurrent })
    }

    @Test
    fun incompleteOriginalImageMetadataDoesNotFabricateSourceAsset() = runBlocking {
        val document = capturedDocument()
        val revision = revision(document, revisionId = "revision-1", revisionNumber = 1)
        val incompleteImage =
            completeImage().copy(
                widthPixels = null,
                heightPixels = null,
                byteSize = null,
            )
        val repository =
            StudentMistakeStoreMistakeDetailRepository(
                FakeStudentMistakeDetailReadPort(
                    detail =
                        problemDocument(
                            revision = revision,
                            document = document,
                            images = listOf(incompleteImage),
                        ),
                    history = listOf(historyItem(revision, 1_000L)),
                ),
            )

        val ready =
            repository.observeScoped(detailQuery(revision.problem))
                .toList()
                .last() as MistakeDetailState.Ready

        assertSame(MistakeSourceSet.Missing, ready.detail.source)
    }

    @Test
    fun classificationFromAnotherRevisionFailsClosedWithoutLeakingItsLabels() = runBlocking {
        val document = capturedDocument()
        val currentRevision = revision(document, revisionId = "revision-2", revisionNumber = 2)
        val foreignRevision = revision(document, revisionId = "revision-1", revisionNumber = 1)
        val repository =
            StudentMistakeStoreMistakeDetailRepository(
                FakeStudentMistakeDetailReadPort(
                    detail = problemDocument(revision = currentRevision, document = document),
                    classifications = listOf(classification(foreignRevision)),
                    history = listOf(historyItem(currentRevision, 2_000L)),
                ),
            )

        val state =
            repository.observeScoped(detailQuery(currentRevision.problem))
                .toList()
                .last()

        assertTrue(state is MistakeDetailState.CorruptSnapshot)
    }

    @Test
    fun legacyUntypedEntrypointsFailClosedWithoutReadingTheStore() = runBlocking {
        val document = capturedDocument()
        val revision = revision(document, revisionId = "revision-1", revisionNumber = 1)
        val port =
            FakeStudentMistakeDetailReadPort(
                detail = problemDocument(revision = revision, document = document),
            )
        val repository = StudentMistakeStoreMistakeDetailRepository(port)
        val key =
            MistakeRevisionKey(
                entryId = ENTRY_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = revision.revisionId,
            )

        assertEquals(
            listOf(MistakeDetailState.Loading, MistakeDetailState.NotFound),
            repository.observe(ENTRY_ID).toList(),
        )
        assertEquals(
            listOf(MistakeDetailState.Loading, MistakeDetailState.NotFound),
            repository.observeExact(key).toList(),
        )
        assertSame(MistakeDetailState.NotFound, repository.readExact(key))
        assertEquals(listOf(MistakeDetailState.NotFound), repository.readExact(listOf(key)))
        assertTrue(repository.observeRevisionHistory(ENTRY_ID).toList().single().isEmpty())
        assertTrue(port.detailRequests.isEmpty())
        assertTrue(port.classificationRequests.isEmpty())
        assertTrue(port.historyRequests.isEmpty())
    }

    @Test
    fun scopedEntrypointsRejectForeignLearnerDocumentBeforeDependentReads() = runBlocking {
        val document = capturedDocument()
        val ownerRevision =
            revision(
                document = document,
                revisionId = "revision-owner",
                revisionNumber = 1,
            )
        val foreignRevision =
            revision(
                document = document,
                revisionId = "revision-foreign",
                revisionNumber = 1,
                learnerId = "learner-2",
                problemId = "problem-foreign",
                practiceUnitId = "practice-unit-foreign",
            )
        val port =
            FakeStudentMistakeDetailReadPort(
                detail =
                    problemDocument(
                        revision = foreignRevision,
                        document = document,
                    ),
            )
        val repository = StudentMistakeStoreMistakeDetailRepository(port)
        val ownerDetailQuery = detailQuery(ownerRevision.problem)

        assertEquals(
            listOf(MistakeDetailState.Loading, MistakeDetailState.NotFound),
            repository.observeScoped(ownerDetailQuery).toList(),
        )
        assertSame(
            MistakeDetailState.NotFound,
            repository.readExactScoped(
                StudentMistakeRevisionDetailQuery(
                    learnerId = ownerRevision.problem.learnerId,
                    errorBookEntryId = ENTRY_ID,
                    revision = ownerRevision,
                ),
            ),
        )
        assertTrue(
            repository.observeRevisionHistoryScoped(ownerDetailQuery)
                .toList()
                .single()
                .isEmpty(),
        )
        assertTrue(port.classificationRequests.isEmpty())
        assertTrue(port.historyRequests.isEmpty())
    }

    @Test
    fun parityGatedCurrentDetailAndHistoryReadOnlyStudentStore() = runBlocking {
        val document = capturedDocument()
        val revision = revision(document, revisionId = "revision-current", revisionNumber = 2)
        val port =
            FakeStudentMistakeDetailReadPort(
                detail = problemDocument(revision = revision, document = document),
                history = listOf(historyItem(revision, 2_000L)),
            )
        val fallback = TrackingLegacyDetailRepository()
        val repository =
            ParityGatedStudentMistakeDetailRepository(
                learnerId = revision.problem.learnerId,
                student = StudentMistakeStoreMistakeDetailRepository(port),
                catalogState =
                    MutableStateFlow(
                        StudentMistakeLibraryCatalogState.Verified(
                            listOf(catalogEntry(revision)),
                        ),
                    ),
                transitionalHistoricalFallback = fallback,
            )

        val states = repository.observe(ENTRY_ID).take(2).toList()
        val history = repository.observeRevisionHistory(ENTRY_ID).take(1).toList().single()

        assertTrue(states.last() is MistakeDetailState.Ready)
        assertEquals(2, port.detailRequests.size)
        assertTrue(port.detailRequests.all { it.problem == revision.problem })
        assertEquals(listOf("revision-current"), history.map { it.problemRevisionId })
        assertEquals(0, fallback.currentObserveCount)
        assertEquals(0, fallback.historyObserveCount)
    }

    @Test
    fun explicitHistoricalRevisionUsesNamedTransitionalFallbackOnlyForKnownProblem() =
        runBlocking {
            val document = capturedDocument()
            val current = revision(document, revisionId = "revision-current", revisionNumber = 2)
            val fallback = TrackingLegacyDetailRepository()
            val repository =
                ParityGatedStudentMistakeDetailRepository(
                    learnerId = current.problem.learnerId,
                    student =
                        StudentMistakeStoreMistakeDetailRepository(
                            FakeStudentMistakeDetailReadPort(
                                detail = problemDocument(revision = current, document = document),
                            ),
                        ),
                    catalogState =
                        MutableStateFlow(
                            StudentMistakeLibraryCatalogState.Verified(
                                listOf(catalogEntry(current)),
                            ),
                        ),
                    transitionalHistoricalFallback = fallback,
                )
            val historical =
                MistakeRevisionKey(
                    entryId = ENTRY_ID,
                    problemId = current.problem.problemId,
                    problemRevisionId = "revision-old",
                )
            val foreign = historical.copy(problemId = "foreign-problem")

            repository.observeExact(historical).take(2).toList()
            val foreignStates = repository.observeExact(foreign).take(2).toList()

            assertEquals(listOf(historical), fallback.exactObserveRequests)
            assertEquals(
                listOf(MistakeDetailState.Loading, MistakeDetailState.NotFound),
                foreignStates,
            )
        }

    @Test
    fun pagedReadyStateReadsCurrentAndExactDirectlyFromLearnerBoundStudentAuthority() =
        runBlocking {
            val document = capturedDocument()
            val current = revision(document, revisionId = "revision-current", revisionNumber = 2)
            val studentPort =
                FakeStudentMistakeDetailReadPort(
                    detail = problemDocument(revision = current, document = document),
                    history = listOf(historyItem(current, 2_000L)),
                )
            val library = FakeLearnerBoundLibrary(libraryDetail(current))
            val fallback = TrackingLegacyDetailRepository()
            val repository =
                ParityGatedStudentMistakeDetailRepository(
                    learnerId = current.problem.learnerId,
                    student =
                        StudentMistakeStoreMistakeDetailRepository(
                            readPort = studentPort,
                            exactDetailLibrary = library,
                            expectedLearnerId = current.problem.learnerId,
                        ),
                    catalogState =
                        MutableStateFlow(
                            StudentMistakeLibraryCatalogState.Ready(
                                revision = catalogRevision(),
                                entryCount = 1L,
                            ),
                        ),
                    transitionalHistoricalFallback = fallback,
                )
            val currentKey =
                MistakeRevisionKey(
                    entryId = ENTRY_ID,
                    problemId = current.problem.problemId,
                    problemRevisionId = current.revisionId,
                )
            val historicalKey = currentKey.copy(problemRevisionId = "revision-old")

            val currentStates = repository.observe(ENTRY_ID).take(2).toList()
            val exactStates = repository.observeExact(currentKey).take(2).toList()
            val historicalStates = repository.observeExact(historicalKey).take(2).toList()
            val batch = repository.readExact(listOf(currentKey, historicalKey))
            val history = repository.observeRevisionHistory(ENTRY_ID).take(1).toList().single()

            assertTrue(currentStates.last() is MistakeDetailState.Ready)
            assertTrue(exactStates.last() is MistakeDetailState.Ready)
            assertSame(MistakeDetailState.NotFound, historicalStates.last())
            assertTrue(batch.first() is MistakeDetailState.Ready)
            assertSame(MistakeDetailState.NotFound, batch.last())
            assertEquals(listOf(current.revisionId), history.map { it.problemRevisionId })
            assertTrue(library.detailRequests.isNotEmpty())
            assertEquals(0, fallback.currentObserveCount)
            assertTrue(fallback.exactObserveRequests.isEmpty())
            assertTrue(fallback.batchReadRequests.isEmpty())
            assertEquals(0, fallback.historyObserveCount)
        }

    @Test
    fun missingParityKeepsLegacyReadWithoutClaimingCutover() = runBlocking {
        val document = capturedDocument()
        val revision = revision(document, revisionId = "revision-current", revisionNumber = 1)
        val fallback = TrackingLegacyDetailRepository()
        val repository =
            ParityGatedStudentMistakeDetailRepository(
                learnerId = revision.problem.learnerId,
                student =
                    StudentMistakeStoreMistakeDetailRepository(
                        FakeStudentMistakeDetailReadPort(
                            detail = problemDocument(revision = revision, document = document),
                        ),
                    ),
                catalogState =
                    MutableStateFlow(StudentMistakeLibraryCatalogState.WaitingForParity),
                transitionalHistoricalFallback = fallback,
            )

        repository.observe(ENTRY_ID).take(2).toList()

        assertEquals(1, fallback.currentObserveCount)
    }

    @Test
    fun alreadyOpenFlowSwitchesFromLegacyToStudentAndBackWhenParityChanges() = runBlocking {
        val document = capturedDocument()
        val revision = revision(document, revisionId = "revision-current", revisionNumber = 1)
        val port =
            FakeStudentMistakeDetailReadPort(
                detail = problemDocument(revision = revision, document = document),
            )
        val fallback = TrackingLegacyDetailRepository()
        val gate =
            MutableStateFlow<StudentMistakeLibraryCatalogState>(
                StudentMistakeLibraryCatalogState.WaitingForParity,
            )
        val repository =
            ParityGatedStudentMistakeDetailRepository(
                learnerId = revision.problem.learnerId,
                student = StudentMistakeStoreMistakeDetailRepository(port),
                catalogState = gate,
                transitionalHistoricalFallback = fallback,
            )
        val states = mutableListOf<MistakeDetailState>()
        val collection =
            launch {
                repository.observe(ENTRY_ID).take(6).toList(states)
            }

        awaitStateCount(states, 2)
        gate.value =
            StudentMistakeLibraryCatalogState.Verified(
                listOf(catalogEntry(revision)),
            )
        awaitStateCount(states, 4)
        gate.value = StudentMistakeLibraryCatalogState.ParityBlocked
        withTimeout(5_000L) { collection.join() }

        assertEquals(
            listOf(
                MistakeDetailState.Loading::class,
                MistakeDetailState.NotFound::class,
                MistakeDetailState.Loading::class,
                MistakeDetailState.Ready::class,
                MistakeDetailState.Loading::class,
                MistakeDetailState.NotFound::class,
            ),
            states.map { it::class },
        )
        assertEquals(2, fallback.currentObserveCount)
        assertEquals(1, port.detailRequests.size)
    }

    @Test
    fun allCurrentBatchUsesOnlyStudentSnapshotAndPreservesRequestedOrder() = runBlocking {
        val document = capturedDocument()
        val current = revision(document, revisionId = "revision-current", revisionNumber = 1)
        val port =
            FakeStudentMistakeDetailReadPort(
                detail = problemDocument(revision = current, document = document),
            )
        val fallback = TrackingLegacyDetailRepository()
        val repository =
            ParityGatedStudentMistakeDetailRepository(
                learnerId = current.problem.learnerId,
                student = StudentMistakeStoreMistakeDetailRepository(port),
                catalogState =
                    MutableStateFlow(
                        StudentMistakeLibraryCatalogState.Verified(
                            listOf(catalogEntry(current)),
                        ),
                    ),
                transitionalHistoricalFallback = fallback,
            )
        val key =
            MistakeRevisionKey(
                entryId = ENTRY_ID,
                problemId = current.problem.problemId,
                problemRevisionId = current.revisionId,
            )

        val states = repository.readExact(listOf(key, key))

        assertEquals(2, states.size)
        assertTrue(states.all { state -> state is MistakeDetailState.Ready })
        assertEquals(1, port.detailRequests.size)
        assertTrue(fallback.batchReadRequests.isEmpty())
        assertEquals(0, fallback.singleExactReadCount)
    }

    @Test
    fun batchWithAnyHistoricalRevisionUsesOneLegacySnapshotForTheWholeBatch() = runBlocking {
        val document = capturedDocument()
        val current = revision(document, revisionId = "revision-current", revisionNumber = 2)
        val port =
            FakeStudentMistakeDetailReadPort(
                detail = problemDocument(revision = current, document = document),
            )
        val fallback = TrackingLegacyDetailRepository()
        val repository =
            ParityGatedStudentMistakeDetailRepository(
                learnerId = current.problem.learnerId,
                student = StudentMistakeStoreMistakeDetailRepository(port),
                catalogState =
                    MutableStateFlow(
                        StudentMistakeLibraryCatalogState.Verified(
                            listOf(catalogEntry(current)),
                        ),
                    ),
                transitionalHistoricalFallback = fallback,
            )
        val keys =
            listOf(
                MistakeRevisionKey(
                    entryId = ENTRY_ID,
                    problemId = current.problem.problemId,
                    problemRevisionId = current.revisionId,
                ),
                MistakeRevisionKey(
                    entryId = ENTRY_ID,
                    problemId = current.problem.problemId,
                    problemRevisionId = "revision-old",
                ),
            )

        val states = repository.readExact(keys)

        assertEquals(2, states.size)
        assertEquals(listOf(keys), fallback.batchReadRequests)
        assertEquals(0, fallback.singleExactReadCount)
        assertTrue(port.detailRequests.isEmpty())
    }

    private class FakeLearnerBoundLibrary(
        private val detail: StudentMistakeLibraryDetail?,
    ) : LearnerBoundStudentMistakeLibraryPort {
        val detailRequests = mutableListOf<StudentMistakeEntryId>()

        override fun observeChangeVersion(): Flow<Long> = flowOf(1L)

        override suspend fun readPage(
            request: StudentMistakeLibraryPageRequest,
        ): StudentMistakeLibraryPageResult =
            StudentMistakeLibraryPageResult.Content(emptyList(), nextCursor = null)

        override suspend fun readDetail(
            entryId: StudentMistakeEntryId,
        ): StudentMistakeLibraryDetail? {
            detailRequests += entryId
            return detail
        }

        override suspend fun readFacets(): StudentMistakeLibraryFacets =
            StudentMistakeLibraryFacets(
                changeVersion = 1L,
                subjects = emptyList(),
                sections = emptyList(),
                knowledgeNodes = emptyList(),
            )

        override suspend fun prepareSearchIndex(
            maxDocuments: Int,
        ): StudentMistakeSearchIndexStatus = StudentMistakeSearchIndexStatus.Ready
    }

    private class TrackingLegacyDetailRepository : MistakeDetailRepository {
        var currentObserveCount: Int = 0
        var historyObserveCount: Int = 0
        var singleExactReadCount: Int = 0
        val exactObserveRequests = mutableListOf<MistakeRevisionKey>()
        val batchReadRequests = mutableListOf<List<MistakeRevisionKey>>()

        override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> {
            currentObserveCount += 1
            return flowOf(MistakeDetailState.Loading, MistakeDetailState.NotFound)
        }

        override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> {
            exactObserveRequests += key
            return flowOf(MistakeDetailState.Loading, MistakeDetailState.NotFound)
        }

        override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState {
            singleExactReadCount += 1
            return MistakeDetailState.NotFound
        }

        override suspend fun readExact(
            keys: List<MistakeRevisionKey>,
        ): List<MistakeDetailState> {
            batchReadRequests += keys
            return List(keys.size) { MistakeDetailState.NotFound }
        }

        override fun observeRevisionHistory(
            errorBookEntryId: String,
        ): Flow<List<MistakeRevisionSummary>> {
            historyObserveCount += 1
            return flowOf(emptyList())
        }
    }

    private class FakeStudentMistakeDetailReadPort(
        private val detail: StudentProblemDocument?,
        private val classifications: List<StudentProblemClassificationResult> = emptyList(),
        private val history: List<StudentProblemRevisionHistoryItem> = emptyList(),
    ) : StudentMistakeDetailReadPort {
        val detailRequests = mutableListOf<StudentMistakeDetailQuery>()
        val classificationRequests = mutableListOf<StudentProblemRevisionRef>()
        val historyRequests = mutableListOf<StudentProblemRevisionHistoryQuery>()

        override suspend fun readDetail(
            query: StudentMistakeDetailQuery,
        ): StudentProblemDocument? {
            detailRequests += query
            return detail
        }

        override suspend fun readCurrentClassifications(
            learnerId: String,
            revision: StudentProblemRevisionRef,
        ): List<StudentProblemClassificationResult> {
            if (revision.problem.learnerId != learnerId) return emptyList()
            classificationRequests += revision
            return classifications
        }

        override suspend fun readRevisionHistory(
            query: StudentProblemRevisionHistoryQuery,
        ): StudentProblemRevisionHistoryPage {
            historyRequests += query
            return if (query.cursor == null) {
                StudentProblemRevisionHistoryPage(
                    items = history.take(query.limit),
                    nextCursor = null,
                )
            } else {
                StudentProblemRevisionHistoryPage(emptyList(), null)
            }
        }
    }

    private fun catalogEntry(
        revision: StudentProblemRevisionRef,
    ): StudyCatalogEntry =
        StudyCatalogEntry(
            entryId = ENTRY_ID,
            problemId = revision.problem.problemId,
            problemRevisionId = revision.revisionId,
            practiceUnitId = revision.problem.practiceUnitId,
            subject = revision.problem.subject.name,
            title = "函数题",
            problemMarkdown = "求函数值。",
            sourceKey = null,
            isCuratedExample = false,
            masteryStatus = MasteryStatus.UNKNOWN,
            nextReviewAtEpochMillis = null,
            retrievability = null,
        )

    private fun catalogRevision() =
        StudentMistakeCatalogRevision(
            studentChangeVersion = 1L,
            masteryLedgerSequence = 1L,
            masteryAsOfEpochMillis = 1L,
            knowledgeActivationGeneration = 1L,
            knowledgeManifestFingerprint = "a".repeat(64),
            knowledgeTaxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )

    private fun libraryDetail(
        revision: StudentProblemRevisionRef,
    ): StudentMistakeLibraryDetail =
        StudentMistakeLibraryDetail(
            entryId = StudentMistakeEntryId(ENTRY_ID),
            problemRevision = revision,
            subject = revision.problem.subject,
            title = "函数题",
            stemMarkdown = "求函数值。",
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
            practiceUnitTitle = "函数题",
            estimatedDurationSeconds = 120,
            images = emptyList(),
            collection =
                StudentMistakeLibraryCollectionSummary(
                    state = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    addedAtEpochMillis = 1_000L,
                    changedAtEpochMillis = 2_000L,
                ),
            sections = emptyList(),
            knowledgeNodes = emptyList(),
        )

    private fun problemDocument(
        revision: StudentProblemRevisionRef,
        document: CapturedQuestionDocument,
        images: List<StudentProblemImageReference> = emptyList(),
    ) = StudentProblemDocument(
        revision = revision,
        title = "函数题",
        stemMarkdown = "求函数值。",
        practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
        practiceUnitTitle = "函数题",
        itemFamilyId = "family-function",
        estimatedDurationSeconds = 120,
        sourceBundleId = null,
        partIds = emptyList(),
        originalImages = images,
        lifecycleState = StudentProblemLifecycleState.ACTIVE,
        mistakeState = StudentMistakeEntryState.ACTIVE,
        favorite = false,
        errorBookEntryId = ENTRY_ID,
        capturedQuestionDocument = document,
    )

    private fun revision(
        document: CapturedQuestionDocument,
        revisionId: String,
        revisionNumber: Int,
        learnerId: String = "learner-1",
        problemId: String = PROBLEM_ID,
        practiceUnitId: String = PRACTICE_UNIT_ID,
    ) = StudentProblemRevisionRef(
        problem =
            StudentProblemRef(
                learnerId = learnerId,
                subject = SubjectKind.MATH,
                problemId = problemId,
                practiceUnitId = practiceUnitId,
            ),
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        documentCanonicalFingerprint = CapturedQuestionDocumentFingerprint.of(document),
    )

    private fun historyItem(
        revision: StudentProblemRevisionRef,
        committedAtEpochMillis: Long,
    ) = StudentProblemRevisionHistoryItem(
        revision = revision,
        title = "函数题",
        stemPreview = "求函数值。",
        committedAtEpochMillis = committedAtEpochMillis,
        hasCapturedQuestionDocument = true,
        hasSolutionAnalysis = false,
        errorAttributionCount = 0,
    )

    private fun classification(
        revision: StudentProblemRevisionRef,
    ) = StudentProblemClassificationResult(
        classificationId = "classification-${revision.revisionId}",
        problemRevision = revision,
        dimension = StudentProblemClassificationDimension.CURRICULUM_SECTION,
        labelId = "chapter-function",
        displayName = "分类标签不得进入详情",
        knowledgeNode = null,
        modelProviderId = "provider-test",
        modelId = "model-test",
        classifierVersion = "classifier-v1",
        resultCanonicalFingerprint = "6".repeat(64),
        status = StudentProblemClassificationStatus.ACCEPTED,
        recordedAtEpochMillis = 2_000L,
    )

    private fun completeImage() = StudentProblemImageReference(
        imageReferenceId = "image-1",
        localContentUri = "content://student-mistakes/original-1",
        contentCanonicalFingerprint = "5".repeat(64),
        mediaType = "image/jpeg",
        ordinal = 0,
        widthPixels = 1_200,
        heightPixels = 800,
        byteSize = 42_000L,
    )

    private fun capturedDocument() = CapturedQuestionDocument(
        document =
            QuestionDocument(
                id = "document-1",
                title = "函数题",
                blocks = listOf(ContentBlock.Paragraph("stem", "求函数值。")),
            ),
        blockEvidence =
            listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "image-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
    )

    private fun detailQuery(
        problem: StudentProblemRef,
    ) = StudentMistakeDetailQuery(
        learnerId = problem.learnerId,
        problem = problem,
        errorBookEntryId = ENTRY_ID,
    )

    private suspend fun awaitStateCount(
        states: List<MistakeDetailState>,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (states.size < expected) {
                delay(1L)
            }
        }
    }

    private companion object {
        const val ENTRY_ID = "entry-1"
        const val PROBLEM_ID = "problem-1"
        const val PRACTICE_UNIT_ID = "practice-unit-1"
    }
}
