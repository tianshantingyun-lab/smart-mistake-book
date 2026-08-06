package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeAuthorityReviewBulkSimulationTest {
    private val planner = ThreeAuthorityReviewPlanner()

    @Test
    fun bulkSimulationAcrossScalesServesEveryCandidateWithinFairHorizon() {
        val budgetSeconds = 50 * 180
        val maxItemCount = 50

        listOf(10, 100, 500, 1_000, 5_000).forEach { candidateCount ->
            val candidates = candidates(candidateCount)
            var remaining = candidates
            val selected = linkedSetOf<String>()
            val servedDay = linkedMapOf<String, Int>()
            val expectedHorizon = ceil(candidateCount.toDouble() / maxItemCount).toInt()

            var day = 0
            while (remaining.isNotEmpty()) {
                val planningAt = NOW + day * DAY_MILLIS
                val request =
                    request(
                        candidates = remaining,
                        budgetSeconds = budgetSeconds,
                        maxItemCount = maxItemCount,
                        planningAtEpochMillis = planningAt,
                    )
                val plan = planner.plan(request)
                val firstPlan = planner.plan(request)

                assertEquals("candidateCount=$candidateCount day=$day", firstPlan, plan)
                assertTrue(plan.estimatedDurationSeconds <= budgetSeconds)
                assertTrue(plan.items.size <= maxItemCount)
                assertTrue(plan.items.map { it.candidateId }.distinct().size == plan.items.size)
                assertTrue(plan.items.all { it.candidateId !in selected })

                selected += plan.items.map { it.candidateId }
                plan.items.forEach { item -> servedDay[item.candidateId] = day }
                remaining = remaining.filterNot { candidate -> candidate.candidateId in selected }
                day += 1
                assertTrue("Simulation should not run forever", day <= expectedHorizon + 5)
            }

            assertEquals("candidateCount=$candidateCount", candidateCount, selected.size)
            assertEquals("candidateCount=$candidateCount", candidateCount, selected.toSet().size)
            assertEquals(
                "candidateCount=$candidateCount all candidates served within the fair horizon",
                expectedHorizon,
                servedDay.values.max() + 1,
            )
        }
    }

    @Test
    fun homogeneousFamilyNeverFloodsAnyDailyPlanAtBulkScale() {
        listOf(10, 100, 500, 1_000).forEach { candidateCount ->
            val candidates =
                List(candidateCount) { index ->
                    candidate(
                        index = index,
                        problemFamilyId = "family-shared",
                        solutionFamilyId = null,
                        presentationFamilyId = null,
                    )
                }
            var remaining = candidates
            val selected = linkedSetOf<String>()

            while (remaining.isNotEmpty()) {
                val plan =
                    planner.plan(
                        request(
                            candidates = remaining,
                            budgetSeconds = 50 * 180,
                            maxItemCount = 50,
                        ),
                    )

                assertTrue(plan.items.isNotEmpty())
                assertEquals(
                    "candidateCount=$candidateCount one representative per family per day",
                    1,
                    plan.items.size,
                )
                assertTrue(plan.items.all { it.candidateId !in selected })
                selected += plan.items.map { it.candidateId }
                remaining = remaining.filterNot { candidate -> candidate.candidateId in selected }
            }

            assertEquals(candidateCount, selected.size)
        }
    }

    @Test
    fun highRiskCandidatesAreNotStarvedByRotationAtBulkScale() {
        val candidateCount = 5_000
        val candidates =
            List(candidateCount) { index ->
                candidate(
                    index = index,
                    priorityReasons =
                        if (index < 20) {
                            setOf(ReviewPriorityReason.ANSWER_REVEALED)
                        } else {
                            setOf(ReviewPriorityReason.NEWLY_SAVED)
                        },
                )
            }
        val highRiskIds = candidates.take(20).mapTo(linkedSetOf()) { it.candidateId }
        val firstPlan =
            planner.plan(
                request(
                    candidates = candidates,
                    budgetSeconds = 50 * 180,
                    maxItemCount = 50,
                ),
            )
        val firstDayIds = firstPlan.items.mapTo(hashSetOf()) { it.candidateId }

        assertTrue(highRiskIds.all(firstDayIds::contains))
    }

    private fun request(
        candidates: List<ThreeAuthorityReviewCandidate>,
        budgetSeconds: Int,
        maxItemCount: Int,
        planningAtEpochMillis: Long = NOW,
        localDayEpochDay: Long = 20_000L,
    ) = ThreeAuthorityReviewPlanningRequest(
        learnerId = LEARNER_ID,
        candidates = candidates,
        knowledgeSignals =
            candidates.flatMap { candidate ->
                candidate.knowledgeNodes.map { node ->
                    ReviewKnowledgeSignal(
                        knowledgeNode = node,
                        recallState = ReviewRecallState.FAMILIARIZING,
                        trend = ReviewKnowledgeTrend.STABLE,
                        recallDueAtEpochMillis = planningAtEpochMillis,
                    )
                }
            }.distinctBy { it.knowledgeNode.canonicalFingerprint },
        masteryProjectionVersion = "projection-v1",
        localDayEpochDay = localDayEpochDay,
        timeZoneId = "Asia/Shanghai",
        timeBudgetSeconds = budgetSeconds,
        maxItemCount = maxItemCount,
        planningAtEpochMillis = planningAtEpochMillis,
    )

    private fun candidates(count: Int): List<ThreeAuthorityReviewCandidate> =
        List(count) { index ->
            candidate(
                index = index,
                waitingSinceEpochMillis = NOW - (count - index).toLong() * DAY_MILLIS,
            )
        }

    private fun candidate(
        index: Int,
        problemFamilyId: String = "problem-family-$index",
        solutionFamilyId: String? = "solution-family-$index",
        presentationFamilyId: String? = "presentation-family-$index",
        waitingSinceEpochMillis: Long = NOW - DAY_MILLIS,
        priorityReasons: Set<ReviewPriorityReason> = setOf(ReviewPriorityReason.RECENT_ERROR),
    ) = ThreeAuthorityReviewCandidate(
        candidateId = "candidate-$index",
        problemRevision = revision(index),
        knowledgeNodes = setOf(knowledge(index)),
        problemFamilyId = problemFamilyId,
        solutionFamilyId = solutionFamilyId,
        presentationFamilyId = presentationFamilyId,
        estimatedDurationSeconds = 180,
        cognitiveLoad = ReviewCognitiveLoad.MODERATE,
        availableAtEpochMillis = NOW,
        dueAtEpochMillis = NOW,
        waitingSinceEpochMillis = waitingSinceEpochMillis,
        priorityReasons = priorityReasons,
    )

    private fun revision(index: Int): StudentProblemRevisionRef {
        val problem =
            StudentProblemRef(
                learnerId = LEARNER_ID,
                subject = SubjectKind.MATH,
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

    private fun knowledge(index: Int) =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "knowledge-$index",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )

    private fun sha(value: String): String =
        CanonicalSha256("three-authority-review-bulk-simulation")
            .field("value", value)
            .finish()

    private companion object {
        const val LEARNER_ID = "learner-bulk"
        const val NOW = 1_800_000_000_000L
        const val DAY_MILLIS = 86_400_000L
    }
}
