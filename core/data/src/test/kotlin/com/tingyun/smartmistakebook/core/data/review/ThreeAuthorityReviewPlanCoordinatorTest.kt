package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlanningRequest
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContext
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextItem
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextRequest
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextSelection
import com.tingyun.smartmistakebook.core.mastery.database.MasteryEvidenceQuality
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.StoreStudentReviewQueueCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidate
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateWithKnowledge
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateWithKnowledgePage
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeAuthorityReviewPlanCoordinatorTest {
    @Test
    fun unreviewedFamilyLabelsFallBackToExactSavedProblemIdentity() = runBlocking {
        val firstRepresentative = candidate(index = 0, familyId = "shared-family")
        val duplicateRepresentative = candidate(index = 1, familyId = "shared-family")
        val independent = candidate(index = 2, familyId = "independent-family")
        val fixture =
            Fixture(
                candidates =
                    listOf(
                        firstRepresentative,
                        duplicateRepresentative,
                        independent,
                    ),
            )

        val plan =
            fixture.coordinator.planAndStore(
                request(timeBudgetSeconds = 2 * ITEM_DURATION_SECONDS),
            )

        val stored = fixture.storedCommands.single()
        val sourceRevisions =
            fixture.candidates
                .map { it.candidate.problemRevision }
                .toSet()
        assertEquals(2, plan.items.size)
        assertEquals(2 * ITEM_DURATION_SECONDS, plan.estimatedDurationSeconds)
        assertEquals(plan.canonicalFingerprint, stored.planCanonicalFingerprint)
        assertTrue(stored.items.all { it.problemRevision in sourceRevisions })
        assertTrue(
            stored.items.map { it.problemRevision }.containsAll(
                listOf(
                    firstRepresentative.candidate.problemRevision,
                    duplicateRepresentative.candidate.problemRevision,
                ),
            ),
        )
    }

    @Test
    fun reviewedProblemFamilyLabelsSelectOneRepresentativePerFamily() = runBlocking {
        val firstRepresentative =
            candidate(
                index = 0,
                familyId = "unreviewed-family",
                reviewedProblemFamilyId = "reviewed-shared-family",
            )
        val duplicateRepresentative =
            candidate(
                index = 1,
                familyId = "unreviewed-family",
                reviewedProblemFamilyId = "reviewed-shared-family",
            )
        val independent =
            candidate(
                index = 2,
                familyId = "unreviewed-family",
                reviewedProblemFamilyId = "reviewed-independent-family",
            )
        val fixture =
            Fixture(
                candidates =
                    listOf(
                        firstRepresentative,
                        duplicateRepresentative,
                        independent,
                    ),
            )

        val plan =
            fixture.coordinator.planAndStore(
                request(timeBudgetSeconds = 2 * ITEM_DURATION_SECONDS),
            )

        val stored = fixture.storedCommands.single()
        assertEquals(2, plan.items.size)
        assertEquals(2 * ITEM_DURATION_SECONDS, plan.estimatedDurationSeconds)
        assertTrue(
            stored.items.map { it.problemRevision }.containsAll(
                listOf(
                    firstRepresentative.candidate.problemRevision,
                    independent.candidate.problemRevision,
                ),
            ),
        )
        assertTrue(
            stored.items.none { it.problemRevision == duplicateRepresentative.candidate.problemRevision },
        )
    }

    @Test
    fun reviewedProblemFamilyWithoutExactDocumentBindingFallsBackToProblemId() = runBlocking {
        val first = candidate(0, reviewedProblemFamilyId = "reviewed-shared-family")
        val mismatched =
            candidate(1, reviewedProblemFamilyId = "reviewed-shared-family")
                .copy(
                    reviewedProblemFamilyBindingDocumentFingerprint =
                        sha("different-document"),
                )
        val fixture = Fixture(candidates = listOf(first, mismatched))

        val plan =
            fixture.coordinator.planAndStore(
                request(timeBudgetSeconds = 2 * ITEM_DURATION_SECONDS),
            )

        assertEquals(2, plan.items.size)
    }

    @Test
    fun exactSameDocumentWithoutReviewedFamilySelectsOneRepresentative() = runBlocking {
        val first =
            candidate(
                index = 0,
                documentFingerprint = sha("same-document"),
            )
        val duplicate =
            candidate(
                index = 1,
                documentFingerprint = sha("same-document"),
            )
        val fixture = Fixture(candidates = listOf(first, duplicate))

        val plan =
            fixture.coordinator.planAndStore(
                request(timeBudgetSeconds = 2 * ITEM_DURATION_SECONDS),
            )

        assertEquals(1, plan.items.size)
    }

    @Test
    fun readsCandidatesAndMasteryInBoundedSubjectBatches() = runBlocking {
        val mathCandidates =
            List(101) { index ->
                candidate(
                    index = index,
                    subject = SubjectKind.MATH,
                    knowledgeNodes = setOf(knowledge(index, SubjectKind.MATH)),
                )
            }
        val physicsCandidates =
            List(17) { offset ->
                val index = 101 + offset
                candidate(
                    index = index,
                    subject = SubjectKind.PHYSICS,
                    knowledgeNodes = setOf(knowledge(index, SubjectKind.PHYSICS)),
                )
            }
        val fixture = Fixture(candidates = mathCandidates + physicsCandidates)

        fixture.coordinator.planAndStore(request(timeBudgetSeconds = ITEM_DURATION_SECONDS))

        assertEquals(2, fixture.candidateQueries.size)
        assertEquals(9, fixture.masteryRequests.size)
        assertTrue(fixture.masteryRequests.all { it.fallbackLimit == 0 })
        assertTrue(
            fixture.masteryRequests.all {
                it.exactStableNodeFingerprints.size <=
                    LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT
            },
        )
        assertEquals(
            101,
            fixture.masteryRequests
                .filter { it.subject == SubjectKind.MATH }
                .sumOf { it.exactStableNodeFingerprints.size },
        )
        assertEquals(
            17,
            fixture.masteryRequests
                .filter { it.subject == SubjectKind.PHYSICS }
                .sumOf { it.exactStableNodeFingerprints.size },
        )
        assertEquals(
            118,
            fixture.masteryRequests
                .flatMap { it.exactStableNodeFingerprints }
                .distinct()
                .size,
        )
    }

    @Test
    fun exactWeakMasterySignalOutranksSteadySignal() = runBlocking {
        val steadyNode = knowledge(0, SubjectKind.MATH)
        val weakNode = knowledge(1, SubjectKind.MATH)
        val steady = candidate(index = 0, knowledgeNodes = setOf(steadyNode))
        val weak = candidate(index = 1, knowledgeNodes = setOf(weakNode))
        val masteryItems =
            listOf(
                masteryItem(
                    node = steadyNode,
                    currentState = KnowledgeMasteryState.STEADY,
                    trend = KnowledgeMasteryTrend.STABLE,
                ),
                masteryItem(
                    node = weakNode,
                    currentState = KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                    trend = KnowledgeMasteryTrend.WAVERING,
                ),
            ).associateBy(LocalMasteryContextItem::stableNodeIdentityFingerprint)
        val fixture =
            Fixture(
                candidates = listOf(steady, weak),
                masteryResponder = { query ->
                    LocalMasteryContext(
                        subject = query.subject,
                        items =
                            query.exactStableNodeFingerprints.mapNotNull(masteryItems::get),
                    )
                },
            )

        val plan =
            fixture.coordinator.planAndStore(
                request(
                    timeBudgetSeconds = ITEM_DURATION_SECONDS,
                    maxItemCount = 1,
                ),
            )

        assertEquals(weak.candidate.candidateId, plan.items.single().candidateId)
        assertEquals(0, fixture.masteryRequests.single().fallbackLimit)
    }

    @Test
    fun answerRevealedReasonCodeGetsFastFollowUpPriority() = runBlocking {
        val revealed = candidate(index = 0, reasonCodes = setOf("review-answer-revealed"))
        val ordinary = candidate(index = 1, reasonCodes = setOf("verified-review-response"))
        val fixture = Fixture(candidates = listOf(ordinary, revealed))

        val plan =
            fixture.coordinator.planAndStore(
                request(timeBudgetSeconds = ITEM_DURATION_SECONDS),
            )

        assertEquals(revealed.candidate.candidateId, plan.items.single().candidateId)
    }

    @Test
    fun candidateWithoutKnowledgeRemainsPendingAndCannotBlockTodaysQueue() = runBlocking {
        val source = candidate(index = 0, knowledgeNodes = emptySet())
        val fixture = Fixture(candidates = listOf(source))

        val result =
            fixture.coordinator.planAndStoreWithProfile(
                request(timeBudgetSeconds = ITEM_DURATION_SECONDS),
            )

        assertTrue(fixture.masteryRequests.isEmpty())
        assertTrue(result.plan.items.isEmpty())
        assertTrue(fixture.storedCommands.single().items.isEmpty())
        assertEquals(
            ReviewCandidateProfileSummary(
                readyCount = 0,
                pendingKnowledgeAttributionCount = 1,
                exactProblemIdentityOnlyCount = 0,
            ),
            result.candidateProfile,
        )
    }

    @Test
    fun fiveHundredMixedCandidatesStayTimeBoundedUnderUntrustedFamilySkew() = runBlocking {
        val candidates =
            List(500) { index ->
                candidate(
                    index = index,
                    familyId =
                        if (index < 320) {
                            "real-import-family"
                        } else {
                            "real-family-$index"
                        },
                    durationSeconds = 60,
                    knowledgeNodes =
                        if (index in 320 until 400) {
                            emptySet()
                        } else {
                            setOf(knowledge(index, SubjectKind.MATH))
                        },
                )
            }
        val fixture =
            Fixture(
                candidates = candidates,
                candidateLimit = RUNTIME_DAILY_REVIEW_CANDIDATE_LIMIT,
            )

        val result =
            fixture.coordinator.planAndStoreWithProfile(
                request(
                    timeBudgetSeconds = 15 * 60,
                    maxItemCount = 32,
                ),
            )

        assertEquals(500, result.candidateProfile.inspectedCount)
        assertEquals(80, result.candidateProfile.pendingKnowledgeAttributionCount)
        assertEquals(420, result.candidateProfile.exactProblemIdentityOnlyCount)
        assertEquals(15, result.plan.items.size)
        assertEquals(
            15,
            result.plan.items
                .map { item -> item.problemRevision.problem.problemId }
                .distinct()
                .size,
        )
        assertEquals(15 * 60, result.plan.estimatedDurationSeconds)
        assertTrue(result.plan.estimatedDurationSeconds <= result.plan.timeBudgetSeconds)
    }

    @Test
    fun pendingInspectionWindowCannotStarveTheNextReadySavedProblem() = runBlocking {
        val candidates =
            List(501) { index ->
                candidate(
                    index = index,
                    knowledgeNodes =
                        if (index < 500) {
                            emptySet()
                        } else {
                            setOf(knowledge(index, SubjectKind.MATH))
                        },
                )
            }
        val fixture =
            Fixture(
                candidates = candidates,
                candidateLimit = RUNTIME_DAILY_REVIEW_CANDIDATE_LIMIT,
            )

        val result =
            fixture.coordinator.planAndStoreWithProfile(
                request(timeBudgetSeconds = ITEM_DURATION_SECONDS),
            )

        assertEquals(500, result.candidateProfile.pendingKnowledgeAttributionCount)
        assertEquals(501, result.candidateProfile.inspectedCount)
        assertEquals(
            candidates.last().candidate.problemRevision,
            result.plan.items.single().problemRevision,
        )
    }

    @Test
    fun identicalRetryReusesPlanFingerprintAndQueueIdentity() = runBlocking {
        val fixture =
            Fixture(
                candidates =
                    listOf(
                        candidate(index = 0),
                        candidate(index = 1),
                    ),
            )
        val request = request(timeBudgetSeconds = ITEM_DURATION_SECONDS)

        val first = fixture.coordinator.planAndStore(request)
        val replay = fixture.coordinator.planAndStore(request)

        assertEquals(first, replay)
        assertEquals(first.canonicalFingerprint, replay.canonicalFingerprint)
        assertEquals(2, fixture.storedCommands.size)
        assertEquals(fixture.storedCommands[0], fixture.storedCommands[1])
        assertEquals(
            fixture.storedCommands[0].items.map { it.queueItemId },
            fixture.storedCommands[1].items.map { it.queueItemId },
        )
    }

    @Test
    fun cancellationFromMasteryPropagatesWithoutQueueWrite() {
        val node = knowledge(0, SubjectKind.MATH)
        val fixture =
            Fixture(
                candidates = listOf(candidate(index = 0, knowledgeNodes = setOf(node))),
                masteryResponder = {
                    throw CancellationException("mastery read cancelled")
                },
            )

        val failure =
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    fixture.coordinator.planAndStore(
                        request(timeBudgetSeconds = ITEM_DURATION_SECONDS),
                    )
                }
            }

        assertEquals("mastery read cancelled", failure.message)
        assertTrue(fixture.storedCommands.isEmpty())
    }

    @Test
    fun runtimeCandidateLimitReadsAndStoresAtMostFiveHundred() = runBlocking {
        val fixture =
            Fixture(
                candidates =
                    List(600) { index ->
                        candidate(
                            index = index,
                            durationSeconds = 1,
                        )
                    },
                candidateLimit = RUNTIME_DAILY_REVIEW_CANDIDATE_LIMIT,
            )

        val plan =
            fixture.coordinator.planAndStore(
                request(
                    timeBudgetSeconds =
                        LearnerBoundDailyReviewPlanPort.DEFAULT_TIME_BUDGET_SECONDS,
                    maxItemCount = ThreeAuthorityReviewPlanningRequest.MAX_ITEM_COUNT,
                ),
            )

        assertEquals(500, fixture.candidateQueries.sumOf { it.limit })
        assertEquals(500, plan.items.size)
        assertEquals(500, fixture.storedCommands.single().items.size)
    }

    @Test
    fun queueWriterFailurePropagatesWithoutReturningOrRecordingAPlan() {
        val writerFailure = IllegalStateException("student queue write failed")
        val fixture =
            Fixture(
                candidates = listOf(candidate(index = 0)),
                queueFailure = writerFailure,
            )

        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    fixture.coordinator.planAndStore(
                        request(timeBudgetSeconds = ITEM_DURATION_SECONDS),
                    )
                }
            }

        assertEquals(writerFailure, failure)
        assertEquals(1, fixture.queueWriteAttempts)
        assertTrue(fixture.storedCommands.isEmpty())
    }

    @Test
    fun storedQueueRespectsAuthorityItemCapAndDailySeconds() = runBlocking {
        val fixture =
            Fixture(
                candidates =
                    List(600) { index ->
                        candidate(
                            index = index,
                            durationSeconds = 1,
                        )
                    },
            )

        val plan =
            fixture.coordinator.planAndStore(
                request(
                    timeBudgetSeconds = 600,
                    maxItemCount = ThreeAuthorityReviewPlanningRequest.MAX_ITEM_COUNT,
                ),
            )

        val stored = fixture.storedCommands.single()
        assertEquals(512, plan.items.size)
        assertEquals(512, stored.items.size)
        assertEquals(512, plan.estimatedDurationSeconds)
        assertTrue(plan.estimatedDurationSeconds <= stored.timeBudgetSeconds)
    }

    @Test
    fun itemCountBudgetFlowsIntoStoredPlan() = runBlocking {
        val fixture =
            Fixture(
                candidates =
                    List(10) { index ->
                        candidate(
                            index = index,
                            durationSeconds = 60,
                        )
                    },
            )

        val plan =
            fixture.coordinator.planAndStore(
                request(
                    timeBudgetSeconds = 60 * 60,
                    maxItemCount = 3,
                ),
            )

        assertEquals(3, plan.items.size)
        assertEquals(3, plan.maxItemCount)
        assertEquals(180, plan.estimatedDurationSeconds)
    }

    private class Fixture(
        val candidates: List<StudentReviewCandidateWithKnowledge>,
        private val masteryResponder:
            suspend (LocalMasteryContextRequest) -> LocalMasteryContext =
            { query -> LocalMasteryContext(query.subject, emptyList()) },
        private val queueFailure: Throwable? = null,
        candidateLimit: Int = ThreeAuthorityReviewPlanningRequest.MAX_CANDIDATE_COUNT,
    ) {
        val candidateQueries = mutableListOf<StudentReviewCandidateQuery>()
        val masteryRequests = mutableListOf<LocalMasteryContextRequest>()
        val storedCommands = mutableListOf<StoreStudentReviewQueueCommand>()
        var queueWriteAttempts = 0
        val coordinator =
            ThreeAuthorityReviewPlanCoordinator(
                candidatePageReader = { query ->
                    candidateQueries += query
                    readPage(query)
                },
                queueWriter = { command ->
                    queueWriteAttempts += 1
                    queueFailure?.let { throw it }
                    storedCommands += command
                },
                masteryContextReader = { query ->
                    masteryRequests += query
                    masteryResponder(query)
                },
                candidateLimit = candidateLimit,
            )

        private fun readPage(
            query: StudentReviewCandidateQuery,
        ): StudentReviewCandidateWithKnowledgePage {
            val startIndex =
                query.cursor?.let { cursor ->
                    val cursorIndex =
                        candidates.indexOfFirst {
                            it.candidate.candidateId == cursor.candidateId
                        }
                    check(cursorIndex >= 0) { "Unknown test cursor" }
                    cursorIndex + 1
                } ?: 0
            val items = candidates.drop(startIndex).take(query.limit)
            val hasMore = startIndex + items.size < candidates.size
            val nextCursor =
                if (hasMore) {
                    val last = items.last().candidate
                    StudentReviewCandidateCursor(
                        availableAtEpochMillis = last.availableAtEpochMillis,
                        candidateId = last.candidateId,
                    )
                } else {
                    null
                }
            return StudentReviewCandidateWithKnowledgePage(
                items = items,
                nextCursor = nextCursor,
            )
        }
    }

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val NOW = 1_800_000_000_000L
        const val ITEM_DURATION_SECONDS = 120

        fun request(
            timeBudgetSeconds: Int,
            maxItemCount: Int = ThreeAuthorityReviewPlanningRequest.DEFAULT_MAX_ITEM_COUNT,
        ) = ThreeAuthorityDailyReviewRequest(
            learnerId = LEARNER_ID,
            localDayEpochDay = 20_000L,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = timeBudgetSeconds,
            maxItemCount = maxItemCount,
            planningAtEpochMillis = NOW,
        )

        fun candidate(
            index: Int,
            subject: SubjectKind = SubjectKind.MATH,
            familyId: String = "family-$index",
            reviewedProblemFamilyId: String? = null,
            documentFingerprint: String? = null,
            durationSeconds: Int = ITEM_DURATION_SECONDS,
            knowledgeNodes: Set<KnowledgeNodeRef>? = null,
            reasonCodes: Set<String> = setOf("recent-mistake"),
        ): StudentReviewCandidateWithKnowledge {
            val id = index.toString().padStart(4, '0')
            val revision =
                StudentProblemRevisionRef(
                    problem =
                        StudentProblemRef(
                            learnerId = LEARNER_ID,
                            subject = subject,
                            problemId = "problem-$id",
                            practiceUnitId = "practice-$id",
                        ),
                    revisionId = "revision-$id",
                    revisionNumber = 1,
                    documentCanonicalFingerprint =
                        documentFingerprint ?: sha("document-$id"),
                )
            return StudentReviewCandidateWithKnowledge(
                candidate =
                    StudentReviewCandidate(
                        candidateId = "candidate-$id",
                        problemRevision = revision,
                        reasonCodes = reasonCodes,
                        itemFamilyId = familyId,
                        estimatedDurationSeconds = durationSeconds,
                        availableAtEpochMillis = NOW - 1,
                        dueAtEpochMillis = NOW,
                        sourceEvidence = null,
                        candidateVersion = 1,
                        updatedAtEpochMillis = NOW,
                    ),
                acceptedKnowledgeNodes =
                    knowledgeNodes ?: setOf(knowledge(index, subject)),
                reviewedProblemFamilyId = reviewedProblemFamilyId,
                reviewedProblemFamilyBindingDocumentFingerprint =
                    reviewedProblemFamilyId?.let { revision.documentCanonicalFingerprint },
            )
        }

        fun knowledge(
            index: Int,
            subject: SubjectKind,
        ) = KnowledgeNodeRef(
            subject = subject,
            knowledgeNodeId = "knowledge-$index",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )

        fun masteryItem(
            node: KnowledgeNodeRef,
            currentState: KnowledgeMasteryState,
            trend: KnowledgeMasteryTrend,
        ): LocalMasteryContextItem =
            LocalMasteryContextItem(
                selection = LocalMasteryContextSelection.EXACT,
                stableNodeIdentityFingerprint = stableFingerprint(node),
                knowledgeNode = node,
                historicalState = currentState,
                currentRecallState = currentState,
                trend = trend,
                evidenceQuality = MasteryEvidenceQuality.LOW,
            )

        fun stableFingerprint(node: KnowledgeNodeRef): String =
            LocalMasteryContextRequest
                .fromKnowledgeNodes(
                    subject = node.subject,
                    exactKnowledgeNodes = listOf(node),
                    fallbackLimit = 0,
                ).exactStableNodeFingerprints
                .single()

        fun sha(value: String): String =
            CanonicalSha256("three-authority-review-coordinator-test")
                .field("value", value)
                .finish()
    }
}
