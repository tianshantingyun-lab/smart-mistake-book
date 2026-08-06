package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ThreeAuthorityReviewPlannerTest {
    private val planner = ThreeAuthorityReviewPlanner()

    @Test
    fun fiveHundredImportedMistakesStillFitFifteenMinuteBudget() {
        val request =
            request(
                candidates =
                    List(500) { index ->
                        candidate(
                            index = index,
                            durationSeconds = 180,
                            waitingSinceEpochMillis = NOW - index * DAY_MILLIS,
                        )
                    },
                budgetSeconds = 15 * 60,
            )

        val plan = planner.plan(request)

        assertEquals(5, plan.items.size)
        assertEquals(15 * 60, plan.estimatedDurationSeconds)
        assertTrue(plan.items.all { item -> item.problemRevision in request.candidates.map { it.problemRevision } })
    }

    @Test
    fun oneRepresentativePerProblemSolutionAndPresentationFamily() {
        val candidates =
            listOf(
                candidate(1, problemFamilyId = "p-shared"),
                candidate(2, problemFamilyId = "p-shared"),
                candidate(3, solutionFamilyId = "s-shared"),
                candidate(4, solutionFamilyId = "s-shared"),
                candidate(5, presentationFamilyId = "v-shared"),
                candidate(6, presentationFamilyId = "v-shared"),
            )

        val plan = planner.plan(request(candidates = candidates))

        val selected = plan.items.map { it.candidateId }.toSet()
        assertEquals(3, selected.size)
        assertFalse("candidate-1" in selected && "candidate-2" in selected)
        assertFalse("candidate-3" in selected && "candidate-4" in selected)
        assertFalse("candidate-5" in selected && "candidate-6" in selected)
    }

    @Test
    fun weakAndWaveringKnowledgeOutranksStableKnowledge() {
        val weakNode = knowledge(1)
        val steadyNode = knowledge(2)
        val weak = candidate(1, knowledgeNodes = setOf(weakNode))
        val steady = candidate(2, knowledgeNodes = setOf(steadyNode))
        val signals =
            listOf(
                ReviewKnowledgeSignal(
                    knowledgeNode = weakNode,
                    recallState = ReviewRecallState.NEEDS_REINFORCEMENT,
                    trend = ReviewKnowledgeTrend.WAVERING,
                    recallDueAtEpochMillis = NOW,
                ),
                ReviewKnowledgeSignal(
                    knowledgeNode = steadyNode,
                    recallState = ReviewRecallState.STEADY,
                    trend = ReviewKnowledgeTrend.STABLE,
                    recallDueAtEpochMillis = NOW + DAY_MILLIS,
                ),
            )

        val plan =
            planner.plan(
                request(
                    candidates = listOf(steady, weak),
                    signals = signals,
                    budgetSeconds = 180,
                ),
            )

        assertEquals("candidate-1", plan.items.single().candidateId)
    }

    @Test
    fun planIsDeterministicAndBoundToMasteryProjectionVersion() {
        val candidates = List(8) { candidate(it) }
        val first = planner.plan(request(candidates = candidates))
        val replay = planner.plan(request(candidates = candidates.reversed()))
        val newer =
            planner.plan(
                request(
                    candidates = candidates,
                    masteryProjectionVersion = "projection-v2",
                ),
            )

        assertEquals(first, replay)
        assertFalse(first.canonicalFingerprint == newer.canonicalFingerprint)
    }

    @Test
    fun futureCandidateIsNotScheduledAndPlannerNeverCreatesAnotherQuestion() {
        val available = candidate(1)
        val future = candidate(2, availableAtEpochMillis = NOW + 1)

        val plan = planner.plan(request(candidates = listOf(available, future)))

        assertEquals(listOf(available.problemRevision), plan.items.map { it.problemRevision })
    }

    @Test
    fun questionLongerThanDailyBudgetStaysUnscheduledAndNeverOverflowsBudget() {
        val longQuestion = candidate(index = 1, durationSeconds = 30 * 60)

        val plan =
            planner.plan(
                request(
                    candidates = listOf(longQuestion),
                    budgetSeconds = 10 * 60,
                ),
            )

        assertTrue(plan.items.isEmpty())
        assertEquals(0, plan.estimatedDurationSeconds)
    }

    @Test
    fun nonFittingLongQuestionIsSkippedInFavorOfFittingQuestion() {
        val longQuestion = candidate(index = 1, durationSeconds = 30 * 60)
        val fittingQuestion = candidate(index = 2, durationSeconds = 5 * 60)

        val plan =
            planner.plan(
                request(
                    candidates = listOf(longQuestion, fittingQuestion),
                    budgetSeconds = 10 * 60,
                ),
            )

        assertEquals(listOf("candidate-2"), plan.items.map { it.candidateId })
        assertEquals(5 * 60, plan.estimatedDurationSeconds)
    }

    @Test
    fun answerRevealedQuestionOutranksOrdinaryQuestionForFastFollowUp() {
        val revealed = candidate(
            index = 0,
            durationSeconds = 60,
            priorityReasons = setOf(ReviewPriorityReason.ANSWER_REVEALED),
        )
        val ordinary = candidate(
            index = 1,
            durationSeconds = 60,
            priorityReasons = setOf(ReviewPriorityReason.NEWLY_SAVED),
        )

        val plan =
            planner.plan(
                request(
                    candidates = listOf(ordinary, revealed),
                    budgetSeconds = 120,
                    maxItemCount = 1,
                ),
            )

        assertEquals("candidate-0", plan.items.single().candidateId)
    }

    @Test
    fun crossSubjectKnowledgeReferenceIsRejected() {
        val physicsNode =
            knowledge(
                index = 1,
                subject = SubjectKind.PHYSICS,
            )

        assertThrows(IllegalArgumentException::class.java) {
            candidate(
                index = 1,
                knowledgeNodes = setOf(physicsNode),
            )
        }
    }

    @Test
    fun bulkImportBacklogsOfTenHundredFiveHundredThousandFiveThousandFitDailyBudget() {
        listOf(10, 100, 500, 1_000, 5_000).forEach { candidateCount ->
            val candidates =
                List(candidateCount) { index ->
                    candidate(
                        index = index,
                        durationSeconds = 180,
                        waitingSinceEpochMillis = NOW - (candidateCount - index).toLong() * DAY_MILLIS,
                    )
                }

            val plan =
                planner.plan(
                    request(
                        candidates = candidates,
                        budgetSeconds = 15 * 60,
                    ),
                )

            assertEquals("candidateCount=$candidateCount", 900, plan.estimatedDurationSeconds)
            assertEquals("candidateCount=$candidateCount", 5, plan.items.size)
            assertEquals(
                "candidateCount=$candidateCount",
                plan.items.size,
                plan.items.map { it.candidateId }.distinct().size,
            )
        }
    }

    @Test
    fun thirtyDayRotationDoesNotStarveOldestImportedBacklog() {
        val candidateCount = 1_000
        val candidates =
            List(candidateCount) { index ->
                candidate(
                    index = index,
                    durationSeconds = 180,
                    waitingSinceEpochMillis = NOW - (candidateCount - index).toLong() * DAY_MILLIS,
                )
            }
        val selected = linkedSetOf<String>()
        var remaining = candidates

        repeat(30) { day ->
            val plan =
                planner.plan(
                    request(
                        candidates = remaining,
                        budgetSeconds = 900,
                    ),
                )
            assertEquals(900, plan.estimatedDurationSeconds)
            assertEquals(5, plan.items.size)
            assertTrue(plan.items.all { it.candidateId !in selected })
            selected += plan.items.map { it.candidateId }
            remaining = remaining.filterNot { candidate -> candidate.candidateId in selected }
            assertEquals(candidateCount - selected.size, remaining.size)
        }

        val expectedOldest = candidates.take(150).map { it.candidateId }.toSet()
        assertTrue("Oldest imports were starved for 30 days", expectedOldest.all(selected::contains))
        assertEquals(150, selected.size)
    }

    @Test
    fun ninetyDayRotationEventuallyServesEveryImportedCandidate() {
        val candidateCount = 300
        val candidates =
            List(candidateCount) { index ->
                candidate(
                    index = index,
                    durationSeconds = 180,
                    waitingSinceEpochMillis = NOW - (candidateCount - index).toLong() * DAY_MILLIS,
                )
            }
        val selected = linkedSetOf<String>()
        var remaining = candidates
        var allSelectedDay: Int? = null

        repeat(90) { day ->
            val plan =
                planner.plan(
                    request(
                        candidates = remaining,
                        budgetSeconds = 900,
                    ),
                )
            if (remaining.isNotEmpty()) {
                assertEquals(900, plan.estimatedDurationSeconds)
                assertEquals(5, plan.items.size)
            }
            assertTrue(plan.items.all { it.candidateId !in selected })
            selected += plan.items.map { it.candidateId }
            remaining = remaining.filterNot { candidate -> candidate.candidateId in selected }
            if (selected.size == candidateCount && allSelectedDay == null) {
                allSelectedDay = day + 1
            }
        }

        assertEquals(candidateCount, selected.size)
        assertEquals(candidateCount, selected.toSet().size)
        assertEquals(
            "300 imported items must all be served within 60 daily slots",
            60,
            allSelectedDay,
        )
    }

    @Test
    fun homogeneousProblemFamilyNeverFloodsOneDailyPlan() {
        val candidates =
            List(100) { index ->
                candidate(
                    index = index,
                    problemFamilyId = "family-shared",
                    durationSeconds = 180,
                    waitingSinceEpochMillis = NOW - (100 - index).toLong() * DAY_MILLIS,
                )
            }
        val selected = linkedSetOf<String>()
        var remaining = candidates

        repeat(5) { day ->
            val plan =
                planner.plan(
                    request(
                        candidates = remaining,
                        budgetSeconds = 60 * 60,
                    ),
                )

            assertEquals(1, plan.items.size)
            assertTrue(plan.items.all { it.candidateId !in selected })
            selected += plan.items.map { it.candidateId }
            remaining = remaining.filterNot { candidate -> candidate.candidateId in selected }
        }

        assertEquals(5, selected.size)
    }

    @Test
    fun itemCountHardCapIsRespectedEvenWithLargeBudget() {
        val candidates =
            List(20) { index ->
                candidate(
                    index = index,
                    durationSeconds = 60,
                )
            }

        val plan =
            planner.plan(
                request(
                    candidates = candidates,
                    budgetSeconds = 60 * 60,
                    maxItemCount = 3,
                ),
            )

        assertEquals(3, plan.items.size)
        assertEquals(3, plan.maxItemCount)
        assertEquals(180, plan.estimatedDurationSeconds)
    }

    @Test
    fun pacingLevelsMapToExpectedBudgetsAndItemCaps() {
        assertEquals(10 * 60, ReviewPacingLevel.LIGHT.timeBudgetSeconds)
        assertEquals(4, ReviewPacingLevel.LIGHT.maxItemCount)
        assertEquals(15 * 60, ReviewPacingLevel.STANDARD.timeBudgetSeconds)
        assertEquals(5, ReviewPacingLevel.STANDARD.maxItemCount)
        assertEquals(25 * 60, ReviewPacingLevel.STRONG.timeBudgetSeconds)
        assertEquals(8, ReviewPacingLevel.STRONG.maxItemCount)
    }

    @Test
    fun itemCountBudgetChangesThePlanFingerprint() {
        val candidates = List(6) { candidate(it) }
        val standard = planner.plan(request(candidates = candidates, maxItemCount = 4))
        val stronger = planner.plan(request(candidates = candidates, maxItemCount = 5))

        assertFalse(standard.canonicalFingerprint == stronger.canonicalFingerprint)
        assertFalse(standard == stronger)
    }

    @Test
    fun examTargetBoostsOnlyThatSubjectAsExamApproaches() {
        val mathCandidate =
            candidate(
                index = 0,
                durationSeconds = 60,
                knowledgeNodes = setOf(knowledge(0)),
            )
        val physicsCandidate =
            candidate(
                index = 1,
                durationSeconds = 60,
                subject = SubjectKind.PHYSICS,
                knowledgeNodes = setOf(knowledge(1, SubjectKind.PHYSICS)),
            )

        val plan =
            planner.plan(
                request(
                    candidates = listOf(mathCandidate, physicsCandidate),
                    budgetSeconds = 120,
                    maxItemCount = 1,
                    examTarget =
                        ReviewExamTarget(
                            subject = SubjectKind.MATH,
                            examAtEpochMillis = NOW + 3L * DAY_MILLIS,
                        ),
                ),
            )

        assertEquals("candidate-0", plan.items.single().candidateId)
    }

    @Test
    fun examTargetBoundaryIsFourteenDaysAndDoesNotInventPriorityAfterCrossing() {
        val mathCandidate =
            candidate(
                index = 0,
                durationSeconds = 60,
                waitingSinceEpochMillis = NOW - DAY_MILLIS,
            )
        val physicsCandidate =
            candidate(
                index = 1,
                durationSeconds = 60,
                subject = SubjectKind.PHYSICS,
                knowledgeNodes = setOf(knowledge(1, SubjectKind.PHYSICS)),
                waitingSinceEpochMillis = NOW - 2L * DAY_MILLIS,
            )
        val candidates = listOf(mathCandidate, physicsCandidate)

        val withoutTarget =
            planner.plan(
                request(
                    candidates = candidates,
                    budgetSeconds = 120,
                    maxItemCount = 1,
                ),
            )
        val atFourteenDays =
            planner.plan(
                request(
                    candidates = candidates,
                    budgetSeconds = 120,
                    maxItemCount = 1,
                    examTarget =
                        ReviewExamTarget(
                            subject = SubjectKind.MATH,
                            examAtEpochMillis = NOW + 14L * DAY_MILLIS,
                        ),
                ),
            )
        val afterFourteenDays =
            planner.plan(
                request(
                    candidates = candidates,
                    budgetSeconds = 120,
                    maxItemCount = 1,
                    examTarget =
                        ReviewExamTarget(
                            subject = SubjectKind.MATH,
                            examAtEpochMillis = NOW + 15L * DAY_MILLIS,
                        ),
                ),
            )

        assertEquals("candidate-1", withoutTarget.items.single().candidateId)
        assertEquals("candidate-0", atFourteenDays.items.single().candidateId)
        assertEquals("candidate-1", afterFourteenDays.items.single().candidateId)
    }

    @Test
    fun absentOrDistantExamTargetDoesNotInventPriority() {
        val candidates =
            listOf(
                candidate(0, durationSeconds = 60),
                candidate(
                    1,
                    durationSeconds = 60,
                    subject = SubjectKind.PHYSICS,
                    knowledgeNodes = setOf(knowledge(1, SubjectKind.PHYSICS)),
                ),
            )
        val withoutTarget =
            planner.plan(
                request(
                    candidates = candidates,
                    budgetSeconds = 120,
                ),
            )
        val distantTarget =
            planner.plan(
                request(
                    candidates = candidates,
                    budgetSeconds = 120,
                    examTarget =
                        ReviewExamTarget(
                            subject = SubjectKind.MATH,
                            examAtEpochMillis = NOW + 30L * DAY_MILLIS,
                        ),
                ),
            )

        assertEquals(
            withoutTarget.items.map { it.candidateId },
            distantTarget.items.map { it.candidateId },
        )
    }

    private fun request(
        candidates: List<ThreeAuthorityReviewCandidate>,
        signals: List<ReviewKnowledgeSignal> =
            candidates.flatMap { candidate ->
                candidate.knowledgeNodes.map { node ->
                    ReviewKnowledgeSignal(
                        knowledgeNode = node,
                        recallState = ReviewRecallState.FAMILIARIZING,
                        trend = ReviewKnowledgeTrend.STABLE,
                        recallDueAtEpochMillis = NOW,
                    )
                }
            }.distinctBy { it.knowledgeNode.canonicalFingerprint },
        budgetSeconds: Int = 15 * 60,
        masteryProjectionVersion: String = "projection-v1",
        maxItemCount: Int = ThreeAuthorityReviewPlanningRequest.DEFAULT_MAX_ITEM_COUNT,
        examTarget: ReviewExamTarget? = null,
    ) = ThreeAuthorityReviewPlanningRequest(
        learnerId = LEARNER_ID,
        candidates = candidates,
        knowledgeSignals = signals,
        masteryProjectionVersion = masteryProjectionVersion,
        localDayEpochDay = 20_000L,
        timeZoneId = "Asia/Shanghai",
        timeBudgetSeconds = budgetSeconds,
        maxItemCount = maxItemCount,
        examTarget = examTarget,
        planningAtEpochMillis = NOW,
    )

    private fun candidate(
        index: Int,
        durationSeconds: Int = 180,
        knowledgeNodes: Set<KnowledgeNodeRef> = setOf(knowledge(index)),
        subject: SubjectKind = SubjectKind.MATH,
        problemFamilyId: String = "problem-family-$index",
        solutionFamilyId: String? = "solution-family-$index",
        presentationFamilyId: String? = "presentation-family-$index",
        availableAtEpochMillis: Long = NOW,
        waitingSinceEpochMillis: Long = NOW - DAY_MILLIS,
        priorityReasons: Set<ReviewPriorityReason> = setOf(ReviewPriorityReason.RECENT_ERROR),
    ) = ThreeAuthorityReviewCandidate(
        candidateId = "candidate-$index",
        problemRevision = revision(index, subject),
        knowledgeNodes = knowledgeNodes,
        problemFamilyId = problemFamilyId,
        solutionFamilyId = solutionFamilyId,
        presentationFamilyId = presentationFamilyId,
        estimatedDurationSeconds = durationSeconds,
        cognitiveLoad = ReviewCognitiveLoad.MODERATE,
        availableAtEpochMillis = availableAtEpochMillis,
        dueAtEpochMillis = NOW,
        waitingSinceEpochMillis = waitingSinceEpochMillis,
        priorityReasons = priorityReasons,
    )

    private fun revision(
        index: Int,
        subject: SubjectKind = SubjectKind.MATH,
    ): StudentProblemRevisionRef {
        val problem =
            StudentProblemRef(
                learnerId = LEARNER_ID,
                subject = subject,
                problemId = "problem-$index",
                practiceUnitId = "practice-$index",
            )
        return StudentProblemRevisionRef(
            problem = problem,
            revisionId = "revision-$index",
            revisionNumber = 1,
            documentCanonicalFingerprint = sha("document-$index"),
        )
    }

    private fun knowledge(
        index: Int,
        subject: SubjectKind = SubjectKind.MATH,
    ) = KnowledgeNodeRef(
        subject = subject,
        knowledgeNodeId = "knowledge-$index",
        taxonomyVersion = "taxonomy-v1",
        knowledgePackVersion = "pack-v1",
    )

    private fun sha(value: String): String =
        CanonicalSha256("three-authority-review-planner-test")
            .field("value", value)
            .finish()

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val NOW = 1_800_000_000_000L
        const val DAY_MILLIS = 86_400_000L
    }
}
