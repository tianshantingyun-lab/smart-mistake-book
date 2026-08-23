package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ReviewReason
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style tests for [ReviewPlannerV2] (audit §3.4 / §7.2). Uses
 * fixed-seed random generators so the runs are deterministic and replayable.
 *
 * With an empty [LogDurationModel] every candidate's modeled duration is the
 * global prior ([LogDurationModel.GLOBAL_PRIOR_SECONDS] = 60s), which keeps
 * the budget arithmetic in these tests exact.
 */
class ReviewPlannerV2Test {
    private val planner = ReviewPlannerV2()

    @Test
    fun `randomized plans never repeat practice units and never exceed the budget`() {
        val random = Random(42)
        repeat(25) { trial ->
            val count = 20 + random.nextInt(41)
            val candidates = (0 until count).map { index ->
                candidate(
                    unitId = "unit-$trial-$index",
                    familyId = "family-${random.nextInt(8)}",
                    sourceId = "source-${random.nextInt(6)}",
                    difficulty = random.nextDouble(),
                    seconds = 60,
                    subjectId = "subject-${random.nextInt(3)}",
                )
            }

            val plan = planner.plan(request(candidates, 900))

            val ids = plan.queueItems.map { it.practiceUnitId }
            assertEquals(ids.size, ids.distinct().size)
            assertTrue(plan.totalEstimatedDurationSeconds <= 900)
        }
    }

    @Test
    fun `same input and same model state replay to the same fingerprint`() {
        val candidates = (0 until 12).map { index ->
            candidate(
                unitId = "unit-$index",
                familyId = "family-$index",
                sourceId = "source-$index",
                difficulty = 0.5,
                seconds = 60,
                subjectId = "subject-${index % 3}",
            )
        }
        val reviewRequest = request(candidates, 600)

        val first = planner.plan(reviewRequest)
        val second = planner.plan(reviewRequest)

        assertEquals(first, second)
        assertEquals(first.planFingerprint, second.planFingerprint)
        assertEquals(first.planId, second.planId)

        val shifted = planner.plan(reviewRequest.copy(planningAtEpochMillis = now + 1))
        assertTrue(first.planFingerprint != shifted.planFingerprint)
    }

    @Test
    fun `budget fill median reaches at least 85 percent on synthetic data`() {
        val random = Random(7)
        val budget = 900
        val fills = (0 until 21).map { trial ->
            val count = 25 + random.nextInt(36)
            val candidates = (0 until count).map { index ->
                candidate(
                    unitId = "unit-$trial-$index",
                    familyId = "family-${index % 10}",
                    sourceId = "source-${index % 7}",
                    difficulty = random.nextDouble(),
                    seconds = 60,
                    subjectId = "subject-${random.nextInt(3)}",
                )
            }
            val plan = planner.plan(request(candidates, budget))
            plan.totalEstimatedDurationSeconds / budget.toDouble()
        }

        val median = fills.sorted()[fills.size / 2]
        assertTrue("median budget fill was $median", median >= 0.85)
        assertTrue(fills.all { it <= 1.0 })
    }

    @Test
    fun `every selected item explains why it was scheduled`() {
        val candidates = (0 until 8).map { index ->
            candidate(
                unitId = "unit-$index",
                familyId = "family-$index",
                sourceId = null,
                difficulty = 0.5,
                seconds = 60,
                subjectId = "subject-${index % 2}",
            )
        }

        val plan = planner.plan(request(candidates, 480))

        assertTrue(plan.queueItems.isNotEmpty())
        plan.queueItems.forEach { item ->
            assertTrue(item.reasons.isNotEmpty())
            assertTrue(ReviewReason.NEWLY_ADDED in item.reasons)
        }
    }

    @Test
    fun `same item family never appears in consecutive positions`() {
        val candidates = (0 until 12).map { index ->
            candidate(
                unitId = "unit-$index",
                familyId = "family-${index % 3}",
                sourceId = "source-$index",
                difficulty = 0.5,
                seconds = 60,
                subjectId = "subject-${index % 2}",
            )
        }

        val plan = planner.plan(request(candidates, 720))

        assertTrue(plan.queueItems.isNotEmpty())
        plan.queueItems.zipWithNext().forEach { (left, right) ->
            assertTrue(
                "adjacent items share family ${left.itemFamilyId}",
                left.itemFamilyId != right.itemFamilyId,
            )
        }
    }

    @Test
    fun `same subject never runs longer than three consecutive items`() {
        val singleSubject = (0 until 8).map { index ->
            candidate(
                unitId = "single-$index",
                familyId = "family-s$index",
                sourceId = null,
                difficulty = 0.5,
                seconds = 60,
                subjectId = "subject-single",
            )
        }
        val mixer = candidate(
            unitId = "mixer",
            familyId = "family-mixer",
            sourceId = null,
            difficulty = 0.5,
            seconds = 60,
            subjectId = "subject-other",
        )
        val candidates = singleSubject + mixer

        val plan = planner.plan(request(candidates, 900))

        val candidateById = candidates.associateBy(ReviewCandidate::practiceUnitId)
        val subjects = plan.queueItems.map {
            candidateById.getValue(it.practiceUnitId).subjectId
        }
        var run = 1
        var maxRun = 1
        subjects.zipWithNext().forEach { (left, right) ->
            run = if (left == right) run + 1 else 1
            maxRun = maxOf(maxRun, run)
        }
        assertTrue("subject run reached $maxRun", maxRun <= 3)
        // Eight same-subject candidates plus one mixer can schedule at most
        // 3 + 1 + 3 = 7 items under the run constraint.
        assertTrue(plan.queueItems.size <= 7)
        assertTrue(plan.queueItems.size >= 4)
    }

    @Test
    fun `degenerate single candidate scenarios still fill the budget`() {
        val single = planner.plan(
            request(listOf(candidate("unit-a", "family-a", null, 0.5, 60)), 600),
        )
        assertEquals(listOf("unit-a"), single.queueItems.map { it.practiceUnitId })
        assertEquals(60, single.totalEstimatedDurationSeconds)

        val pair = planner.plan(
            request(
                listOf(
                    candidate("unit-a", "family-a", null, 0.5, 60),
                    candidate("unit-b", "family-b", null, 0.5, 60),
                ),
                600,
            ),
        )
        assertEquals(2, pair.queueItems.size)
        assertEquals(120, pair.totalEstimatedDurationSeconds)

        val empty = planner.plan(request(emptyList(), 600))
        assertTrue(empty.queueItems.isEmpty())
        assertEquals(0, empty.totalEstimatedDurationSeconds)
    }

    @Test
    fun `beam collapse still returns the best partial plan instead of an empty queue`() {
        // All candidates share one item family, so the hard "no consecutive
        // family" constraint allows at most one selection. Beam pruning can
        // then drop every expandable state; the planner must still return the
        // best partial plan instead of collapsing to an empty queue (the old
        // beam early-stop regression).
        val candidates = (0 until 5).map { index ->
            candidate(
                unitId = "unit-$index",
                familyId = "family-shared",
                sourceId = "source-$index",
                difficulty = 0.5,
                seconds = 60,
                subjectId = "subject-$index",
            )
        }

        val plan = planner.plan(request(candidates, 600))

        assertEquals(1, plan.queueItems.size)
        assertEquals("unit-0", plan.queueItems.single().practiceUnitId)
        assertEquals(60, plan.totalEstimatedDurationSeconds)
    }

    @Test
    fun `v2 is not worse than a first-fit greedy baseline on adversarial data`() {
        // Adversarial ordering: low-value candidates first, high-value ones
        // (exam priority) at the back. A first-fit baseline scanning in input
        // order fills the entire budget with low-value items.
        val lowValue = (0 until 10).map { index ->
            candidate(
                unitId = "low-$index",
                familyId = "family-low-$index",
                sourceId = null,
                difficulty = 0.5,
                seconds = 60,
                subjectId = "subject-${index % 3}",
            )
        }
        val highValue = (0 until 10).map { index ->
            candidate(
                unitId = "high-$index",
                familyId = "family-high-$index",
                sourceId = null,
                difficulty = 0.5,
                seconds = 60,
                subjectId = "subject-${index % 3}",
            ).copy(examPriority = 1.0)
        }
        val candidates = lowValue + highValue
        val budget = 600

        val plan = planner.plan(request(candidates, budget))

        // First-fit baseline: take candidates in input order while they fit.
        val baseline = mutableListOf<ReviewCandidate>()
        var remainingSeconds = budget
        for (item in candidates) {
            if (item.estimatedDurationSeconds <= remainingSeconds) {
                baseline += item
                remainingSeconds -= item.estimatedDurationSeconds
            }
        }
        val baselineHighCount = baseline.count { it.practiceUnitId.startsWith("high-") }
        val v2HighCount = plan.queueItems.count { it.practiceUnitId.startsWith("high-") }

        assertTrue(
            "V2 selected $v2HighCount high-value items, baseline $baselineHighCount",
            v2HighCount > baselineHighCount,
        )
        // V2 fills at least as much of the budget as the baseline.
        assertTrue(plan.totalEstimatedDurationSeconds >= budget - remainingSeconds)
        assertTrue(plan.totalEstimatedDurationSeconds <= budget)
    }

    private fun request(candidates: List<ReviewCandidate>, budget: Int) = ReviewPlanningRequest(
        learnerSnapshot = snapshot(),
        candidates = candidates,
        localDayEpochDay = 10,
        timeZoneId = "Asia/Shanghai",
        timeBudgetSeconds = budget,
        planningAtEpochMillis = now,
    )

    private fun snapshot(): LearnerSnapshot {
        val mastery = KnowledgeMasteryState(
            knowledgeNodeId = "kc-a",
            masteryScore = 0.4,
            conservativeMasteryScore = 0.2,
            evidenceMass = 2.0,
            status = MasteryStatus.LEARNING,
            calibrationSupport = CalibrationSupport.SUPPORTED,
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 4,
        )
        return LearnerSnapshot(
            learnerId = "learner-1",
            problemMemoryStates = emptyMap(),
            knowledgeMasteryStates = mapOf("kc-a" to mastery),
            checkpoint = ProjectionCheckpoint(4, LearningProjector.VERSION, now),
            generatedAtEpochMillis = now,
        )
    }

    private fun candidate(
        unitId: String,
        familyId: String,
        sourceId: String?,
        difficulty: Double,
        seconds: Int,
        subjectId: String? = null,
    ) = ReviewCandidate(
        practiceUnitId = unitId,
        knowledgeNodeIds = setOf("kc-a"),
        itemFamilyId = familyId,
        sourceBundleId = sourceId,
        subjectId = subjectId,
        difficulty = difficulty,
        estimatedDurationSeconds = seconds,
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        val now = 10 * DAY_MILLIS
    }
}
