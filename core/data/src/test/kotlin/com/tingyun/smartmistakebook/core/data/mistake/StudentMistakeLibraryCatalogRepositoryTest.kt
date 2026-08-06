package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayKnowledgeItem
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayPageResult
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayRevision
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentMistakeLibraryPort
import com.tingyun.smartmistakebook.core.student.mistake.database.MAX_LIBRARY_PAGE_SIZE
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryId
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryCollectionSummary
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryDetail
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryFacets
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageRequest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeSearchIndexStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeLibraryCatalogRepositoryTest {
    @Test
    fun stableProjectionUsesOnlyStudentOwnedIdentityAndReviewedDisplayNames() = runBlocking {
        val knowledge = knowledgeRef()
        val student =
            studentItem(
                title = "函数错题",
                stemPreview = "求函数在给定点的值。",
                knowledgeNodes = listOf(knowledge),
            )

        val result =
            readStableStudentCatalog(
                library = FakeLibraryPort(listOf(student)),
                expectedLearnerId = LEARNER_ID,
                knowledgeDisplayResolver = { refs ->
                    assertEquals(listOf(knowledge), refs)
                    mapOf(knowledge to "函数值")
                },
            )

        val entry =
            (result as StudentMistakeLibraryCatalogState.Verified).entries.single()
        assertEquals(ENTRY_ID, entry.entryId)
        assertEquals(PROBLEM_ID, entry.problemId)
        assertEquals(REVISION_ID, entry.problemRevisionId)
        assertEquals(PRACTICE_UNIT_ID, entry.practiceUnitId)
        assertEquals(SubjectKind.MATH.name, entry.subject)
        assertEquals("函数错题", entry.title)
        assertEquals("求函数在给定点的值。", entry.problemMarkdown)
        assertEquals(listOf("函数题"), entry.chapterLabels)
        assertEquals(listOf("函数值"), entry.knowledgeLabels)
        assertEquals(MasteryStatus.UNKNOWN, entry.masteryStatus)
        assertNull(entry.sourceKey)
        assertNull(entry.nextReviewAtEpochMillis)
        assertNull(entry.retrievability)
    }

    @Test
    fun masteryProjectionReadsExactRefsAfterCompletingStudentPagination() = runBlocking {
        val firstKnowledge = knowledgeRef("math.function.a")
        val secondKnowledge = knowledgeRef("math.function.b")
        val continuation = cursor("problem-2")
        val library =
            FakeLibraryPort(
                pages =
                    listOf(
                        page(
                            listOf(
                                studentItem(
                                    entryId = "entry-2",
                                    problemId = "problem-2",
                                    revisionId = "revision-2",
                                    practiceUnitId = "practice-2",
                                    knowledgeNodes = listOf(secondKnowledge),
                                ),
                            ),
                            continuation,
                        ),
                        page(
                            listOf(studentItem(knowledgeNodes = listOf(firstKnowledge))),
                            null,
                        ),
                    ),
            )
        val masteryRequests = mutableListOf<List<KnowledgeNodeRef>>()

        val result =
            readStableStudentCatalog(
                library = library,
                expectedLearnerId = LEARNER_ID,
                masteryDisplayRevision = MASTERY_REVISION,
                masteryRead =
                    LearnerBoundStudentMistakeMasteryRead { request ->
                        assertEquals(2, library.pageRequests.size)
                        assertEquals(MASTERY_REVISION, request.expectedRevision)
                        assertEquals(SubjectKind.MATH, request.subject)
                        masteryRequests += request.orderedKnowledgeNodes
                        currentMasteryPage(
                            items =
                                request.orderedKnowledgeNodes.map { reference ->
                                    val state =
                                        if (reference == firstKnowledge) {
                                            KnowledgeMasteryState.STEADY
                                        } else {
                                            KnowledgeMasteryState.NEEDS_REINFORCEMENT
                                        }
                                    masteryItem(reference, state)
                                },
                        )
                    },
            ) as StudentMistakeLibraryCatalogState.Verified

        assertEquals(listOf(firstKnowledge, secondKnowledge), masteryRequests.single())
        assertEquals(
            listOf(MasteryStatus.STALE, MasteryStatus.MASTERED),
            result.entries.map { it.masteryStatus },
        )
        assertEquals(listOf("entry-2", ENTRY_ID), result.entries.map { it.entryId })
    }

    @Test
    fun missingOrConflictingMasteryDataSafelyProjectsUnknown() = runBlocking {
        val requested = knowledgeRef()
        val readers =
            listOf(
                LearnerBoundStudentMistakeMasteryRead {
                    LearnerMasteryDisplayPageResult.RevisionChanged(
                        currentRevision = MASTERY_REVISION.copy(ledgerSequence = 8L),
                    )
                },
                LearnerBoundStudentMistakeMasteryRead { request ->
                    currentMasteryPage(
                        revision = MASTERY_REVISION.copy(ledgerSequence = 8L),
                        items =
                            request.orderedKnowledgeNodes.map { reference ->
                                masteryItem(reference, KnowledgeMasteryState.STEADY)
                            },
                    )
                },
                LearnerBoundStudentMistakeMasteryRead {
                    currentMasteryPage(
                        items =
                            listOf(
                                masteryItem(
                                    knowledgeRef("unrequested"),
                                    KnowledgeMasteryState.STEADY,
                                ),
                            ),
                    )
                },
                LearnerBoundStudentMistakeMasteryRead {
                    currentMasteryPage(items = emptyList())
                },
                LearnerBoundStudentMistakeMasteryRead {
                    error("mastery reader unavailable")
                },
            )

        readers.forEach { reader ->
            val result =
                readStableStudentCatalog(
                    library =
                        FakeLibraryPort(
                            listOf(studentItem(knowledgeNodes = listOf(requested))),
                        ),
                    expectedLearnerId = LEARNER_ID,
                    masteryDisplayRevision = MASTERY_REVISION,
                    masteryRead = reader,
                ) as StudentMistakeLibraryCatalogState.Verified

            assertEquals(MasteryStatus.UNKNOWN, result.entries.single().masteryStatus)
        }
    }

    @Test
    fun deferredMasteryFactoryKeepsTheReaderLearnerBoundAndRevisionFixed() = runBlocking {
        val knowledge = knowledgeRef()
        val repository =
            StudentMistakeLibraryCatalogRepositoryFactory.createDeferredWithMastery(
                expectedLearnerId = LEARNER_ID,
                applicationScope = this,
                masteryDisplayRevision = MASTERY_REVISION,
                libraryProvider = {
                    FakeLibraryPort(
                        listOf(studentItem(knowledgeNodes = listOf(knowledge))),
                    )
                },
                masteryRead =
                    LearnerBoundStudentMistakeMasteryRead { request ->
                        assertEquals(MASTERY_REVISION, request.expectedRevision)
                        assertEquals(listOf(knowledge), request.orderedKnowledgeNodes)
                        currentMasteryPage(
                            items =
                                listOf(
                                    masteryItem(knowledge, KnowledgeMasteryState.STEADY),
                                ),
                        )
                    },
            )

        try {
            val verified =
                withTimeout(5_000L) {
                    repository.state.first { state ->
                        state is StudentMistakeLibraryCatalogState.Verified
                    }
                } as StudentMistakeLibraryCatalogState.Verified
            assertEquals(MasteryStatus.MASTERED, verified.entries.single().masteryStatus)
        } finally {
            repository.close()
        }
    }

    @Test
    fun deferredRevisionProviderDoesNotBlockConstructionAndRunsOnce() = runBlocking {
        val knowledge = knowledgeRef()
        val revisionGate = CompletableDeferred<LearnerMasteryDisplayRevision>()
        var revisionReads = 0
        val library =
            FakeLibraryPort(
                pages =
                    listOf(
                        page(listOf(studentItem(knowledgeNodes = listOf(knowledge))), null),
                        page(listOf(studentItem(knowledgeNodes = listOf(knowledge))), null),
                    ),
                observedChangeVersions = flowOf(0L, 0L),
            )
        val repository =
            StudentMistakeLibraryCatalogRepositoryFactory.createDeferredWithMastery(
                expectedLearnerId = LEARNER_ID,
                applicationScope = this,
                masteryDisplayRevisionProvider = {
                    revisionReads += 1
                    revisionGate.await()
                },
                libraryProvider = { library },
                masteryRead =
                    LearnerBoundStudentMistakeMasteryRead { request ->
                        currentMasteryPage(
                            revision = request.expectedRevision,
                            items =
                                request.orderedKnowledgeNodes.map { reference ->
                                    masteryItem(reference, KnowledgeMasteryState.STEADY)
                                },
                        )
                    },
            )

        try {
            assertSame(
                StudentMistakeLibraryCatalogState.WaitingForParity,
                repository.state.value,
            )
            yield()
            assertEquals(1, revisionReads)
            assertSame(
                StudentMistakeLibraryCatalogState.WaitingForParity,
                repository.state.value,
            )

            revisionGate.complete(MASTERY_REVISION)
            withTimeout(5_000L) {
                while (
                    library.pageRequests.size < 2 ||
                    repository.state.value !is StudentMistakeLibraryCatalogState.Verified
                ) {
                    yield()
                }
            }

            assertEquals(1, revisionReads)
            assertEquals(2, library.pageRequests.size)
            val verified = repository.state.value as StudentMistakeLibraryCatalogState.Verified
            assertEquals(MasteryStatus.MASTERED, verified.entries.single().masteryStatus)
        } finally {
            repository.close()
        }
    }

    @Test
    fun revisionProviderFailureKeepsTheStudentCatalogAvailableWithUnknownMastery() = runBlocking {
        var masteryRead = false
        val repository =
            StudentMistakeLibraryCatalogRepositoryFactory.createDeferredWithMastery(
                expectedLearnerId = LEARNER_ID,
                applicationScope = this,
                masteryDisplayRevisionProvider = { error("revision unavailable") },
                libraryProvider = {
                    FakeLibraryPort(
                        listOf(studentItem(knowledgeNodes = listOf(knowledgeRef()))),
                    )
                },
                masteryRead =
                    LearnerBoundStudentMistakeMasteryRead {
                        masteryRead = true
                        currentMasteryPage(items = emptyList())
                    },
            )

        try {
            val verified =
                withTimeout(5_000L) {
                    repository.state.first { state ->
                        state is StudentMistakeLibraryCatalogState.Verified
                    }
                } as StudentMistakeLibraryCatalogState.Verified
            assertEquals(MasteryStatus.UNKNOWN, verified.entries.single().masteryStatus)
            assertTrue(!masteryRead)
        } finally {
            repository.close()
        }
    }

    @Test
    fun fixedMasteryRevisionAndReaderMustBeProvidedTogether() {
        val library = FakeLibraryPort(listOf(studentItem()))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                readStableStudentCatalog(
                    library = library,
                    expectedLearnerId = LEARNER_ID,
                    masteryDisplayRevision = MASTERY_REVISION,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                readStableStudentCatalog(
                    library = library,
                    expectedLearnerId = LEARNER_ID,
                    masteryRead =
                        LearnerBoundStudentMistakeMasteryRead {
                            currentMasteryPage(items = emptyList())
                        },
                )
            }
        }
    }

    @Test
    fun projectionNeverPublishesCrossLearnerOrDuplicateStudentRows() = runBlocking {
        val crossLearner =
            readStableStudentCatalog(
                library = FakeLibraryPort(listOf(studentItem(learnerId = "learner-2"))),
                expectedLearnerId = LEARNER_ID,
            )
        assertSame(StudentMistakeLibraryCatalogState.ParityBlocked, crossLearner)

        val crossSubjectKnowledge =
            readStableStudentCatalog(
                library =
                    FakeLibraryPort(
                        listOf(
                            studentItem(
                                knowledgeNodes =
                                    listOf(
                                        knowledgeRef(
                                            id = "physics.motion",
                                            subject = SubjectKind.PHYSICS,
                                        ),
                                    ),
                            ),
                        ),
                    ),
                expectedLearnerId = LEARNER_ID,
            )
        assertSame(
            StudentMistakeLibraryCatalogState.ParityBlocked,
            crossSubjectKnowledge,
        )

        val duplicate =
            readStableStudentCatalog(
                library =
                    FakeLibraryPort(
                        pages =
                            listOf(
                                page(listOf(studentItem()), cursor(PROBLEM_ID)),
                                page(listOf(studentItem()), null),
                            ),
                    ),
                expectedLearnerId = LEARNER_ID,
            )
        assertSame(StudentMistakeLibraryCatalogState.ParityBlocked, duplicate)
    }

    @Test
    fun completeMultiPageSnapshotPreservesStudentOrderAndUsesBoundedPages() = runBlocking {
        val continuation = cursor("problem-2")
        val library =
            FakeLibraryPort(
                pages =
                    listOf(
                        page(
                            listOf(
                                studentItem(
                                    entryId = "entry-2",
                                    problemId = "problem-2",
                                    revisionId = "revision-2",
                                    practiceUnitId = "practice-2",
                                ),
                            ),
                            continuation,
                        ),
                        page(listOf(studentItem()), null),
                    ),
            )

        val result =
            readStableStudentCatalog(
                library = library,
                expectedLearnerId = LEARNER_ID,
            ) as StudentMistakeLibraryCatalogState.Verified

        assertEquals(listOf("entry-2", ENTRY_ID), result.entries.map { it.entryId })
        assertEquals(listOf(null, continuation), library.pageRequests.map { it.cursor })
        assertTrue(library.pageRequests.all { it.limit == MAX_LIBRARY_PAGE_SIZE })
    }

    @Test
    fun unstableOrIncompleteSnapshotsFailClosed() = runBlocking {
        val repeated = cursor(PROBLEM_ID)
        val cases =
            listOf(
                FakeLibraryPort(
                    pages =
                        listOf(
                            page(listOf(studentItem()), repeated),
                            page(
                                listOf(
                                    studentItem(
                                        entryId = "entry-2",
                                        problemId = "problem-2",
                                        revisionId = "revision-2",
                                        practiceUnitId = "practice-2",
                                    ),
                                ),
                                repeated,
                            ),
                        ),
                ),
                FakeLibraryPort(pages = listOf(page(emptyList(), cursor("empty")))),
                FakeLibraryPort(
                    pages = listOf(StudentMistakeLibraryPageResult.ReloadRequired),
                ),
                FakeLibraryPort(
                    pages = listOf(StudentMistakeLibraryPageResult.Preparing(1L)),
                ),
                FakeLibraryPort(
                    items = listOf(studentItem()),
                    facetVersions = listOf(1L, 2L),
                ),
            )

        cases.forEach { library ->
            assertSame(
                StudentMistakeLibraryCatalogState.ParityBlocked,
                readStableStudentCatalog(
                    library = library,
                    expectedLearnerId = LEARNER_ID,
                ),
            )
        }
    }

    @Test
    fun observedVersionMismatchFailsBeforeReadingAnyPage() = runBlocking {
        val library =
            FakeLibraryPort(
                items = listOf(studentItem()),
                facetVersions = listOf(1L),
            )

        val result =
            readStableStudentCatalog(
                library = library,
                expectedLearnerId = LEARNER_ID,
                expectedStudentChangeVersion = 2L,
            )

        assertSame(StudentMistakeLibraryCatalogState.ParityBlocked, result)
        assertTrue(library.pageRequests.isEmpty())
    }

    @Test
    fun missingKnowledgeDisplayNameDoesNotExposeInternalNodeId() = runBlocking {
        val knowledge = knowledgeRef()
        val result =
            readStableStudentCatalog(
                library =
                    FakeLibraryPort(
                        listOf(studentItem(knowledgeNodes = listOf(knowledge))),
                    ),
                expectedLearnerId = LEARNER_ID,
                knowledgeDisplayResolver = { emptyMap() },
            ) as StudentMistakeLibraryCatalogState.Verified

        assertTrue(result.entries.single().knowledgeLabels.isEmpty())
        assertTrue(
            result.entries.single().toString().contains(knowledge.knowledgeNodeId).not(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun knowledgeResolverCannotInjectUnrequestedLabels() = runBlocking {
        readStableStudentCatalog(
            library = FakeLibraryPort(listOf(studentItem())),
            expectedLearnerId = LEARNER_ID,
            knowledgeDisplayResolver = {
                mapOf(knowledgeRef("unrequested") to "不应出现")
            },
        )
        Unit
    }

    private class FakeLibraryPort(
        items: List<StudentMistakeLibraryItem> = emptyList(),
        private val pages: List<StudentMistakeLibraryPageResult> =
            listOf(page(items, null)),
        private val facetVersions: List<Long> = listOf(0L),
        private val observedChangeVersions: Flow<Long> = flowOf(0L),
    ) : LearnerBoundStudentMistakeLibraryPort {
        private var pageIndex = 0
        private var facetIndex = 0
        val pageRequests = mutableListOf<StudentMistakeLibraryPageRequest>()

        override fun observeChangeVersion(): Flow<Long> = observedChangeVersions

        override suspend fun readPage(
            request: StudentMistakeLibraryPageRequest,
        ): StudentMistakeLibraryPageResult {
            pageRequests += request
            return pages.getOrElse(pageIndex++) {
                error("Unexpected extra library page request")
            }
        }

        override suspend fun readDetail(
            entryId: StudentMistakeEntryId,
        ): StudentMistakeLibraryDetail? = null

        override suspend fun readFacets(): StudentMistakeLibraryFacets =
            StudentMistakeLibraryFacets(
                changeVersion =
                    facetVersions[
                        facetIndex.coerceAtMost(facetVersions.lastIndex)
                    ].also { facetIndex += 1 },
                subjects = emptyList(),
                sections = emptyList(),
                knowledgeNodes = emptyList(),
            )

        override suspend fun prepareSearchIndex(
            maxDocuments: Int,
        ): StudentMistakeSearchIndexStatus = StudentMistakeSearchIndexStatus.Ready
    }

    private fun studentItem(
        entryId: String = ENTRY_ID,
        title: String? = "函数错题",
        stemPreview: String = "求函数在给定点的值。",
        learnerId: String = LEARNER_ID,
        problemId: String = PROBLEM_ID,
        practiceUnitId: String = PRACTICE_UNIT_ID,
        revisionId: String = REVISION_ID,
        subject: SubjectKind = SubjectKind.MATH,
        knowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    ): StudentMistakeLibraryItem =
        StudentMistakeLibraryItem(
            entryId = StudentMistakeEntryId(entryId),
            problemRevision =
                StudentProblemRevisionRef(
                    problem =
                        StudentProblemRef(
                            learnerId = learnerId,
                            subject = subject,
                            problemId = problemId,
                            practiceUnitId = practiceUnitId,
                        ),
                    revisionId = revisionId,
                    revisionNumber = 1,
                    documentCanonicalFingerprint = "a".repeat(64),
                ),
            subject = subject,
            title = title,
            stemPreview = stemPreview,
            practiceUnitTitle = "函数题",
            estimatedDurationSeconds = 120,
            collection =
                StudentMistakeLibraryCollectionSummary(
                    state = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    addedAtEpochMillis = 1_000L,
                    changedAtEpochMillis = 1_000L,
                ),
            sections = emptyList(),
            knowledgeNodes = knowledgeNodes,
        )

    private fun knowledgeRef(
        id: String = "math.function.value",
        subject: SubjectKind = SubjectKind.MATH,
    ): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = subject,
            knowledgeNodeId = id,
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "knowledge-v1",
        )

    private fun masteryItem(
        knowledgeNode: KnowledgeNodeRef,
        state: KnowledgeMasteryState,
        trend: KnowledgeMasteryTrend = KnowledgeMasteryTrend.STABLE,
    ): LearnerMasteryDisplayKnowledgeItem =
        LearnerMasteryDisplayKnowledgeItem(
            stableNodeIdentityFingerprint =
                CanonicalSha256("learner-mastery-stable-node-identity-v1")
                    .field("subject", knowledgeNode.subject.name)
                    .field("knowledgeNodeId", knowledgeNode.knowledgeNodeId)
                    .field("taxonomyVersion", knowledgeNode.taxonomyVersion)
                    .finish(),
            knowledgeNode = knowledgeNode,
            currentRecallState = state,
            trend = trend,
        )

    private fun currentMasteryPage(
        items: List<LearnerMasteryDisplayKnowledgeItem>,
        revision: LearnerMasteryDisplayRevision = MASTERY_REVISION,
    ): LearnerMasteryDisplayPageResult.Current =
        LearnerMasteryDisplayPageResult.Current(
            revision = revision,
            items = items,
            taxonomyVersions =
                items.map { item -> item.knowledgeNode.taxonomyVersion }.toSet(),
        )

    private fun cursor(problemId: String): StudentMistakeLibraryCursor {
        val constructor =
            StudentMistakeLibraryCursor::class.java.getDeclaredConstructor(
                java.lang.Long.TYPE,
                String::class.java,
                String::class.java,
                java.lang.Long.TYPE,
                String::class.java,
            )
        constructor.isAccessible = true
        return constructor.newInstance(
            0L,
            "1".repeat(64),
            "2".repeat(64),
            1_000L,
            problemId,
        )
    }

    private companion object {
        const val ENTRY_ID = "entry-1"
        const val LEARNER_ID = "learner-1"
        const val PROBLEM_ID = "problem-1"
        const val REVISION_ID = "revision-1"
        const val PRACTICE_UNIT_ID = "practice-1"
        val MASTERY_REVISION =
            LearnerMasteryDisplayRevision(
                ledgerSequence = 7L,
                asOfEpochMillis = 2_000L,
            )

        fun page(
            items: List<StudentMistakeLibraryItem>,
            cursor: StudentMistakeLibraryCursor?,
        ): StudentMistakeLibraryPageResult.Content =
            StudentMistakeLibraryPageResult.Content(
                items = items,
                nextCursor = cursor,
            )
    }
}
