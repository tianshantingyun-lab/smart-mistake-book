package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ReviewReason
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §5 KC→question propagation (user rule): a negative update on one
 * knowledge node raises the scheduling weight of every question bound to it
 * — continuously, not as a mechanical gate.
 */
class KnowledgeMasteryDropPropagationTest {

    private val now = 30L * DAY

    @Test
    fun `negative recent evidence below threshold yields drop pressure and reason`() {
        val planner = ReviewPlanner()
        val memory = memory(dueOffsetDays = 3)
        val snapshot = snapshot(
            memories = listOf(memory),
            mastery = mastery(conservative = 0.3, lastDirection = LearningEvidenceDirection.NEGATIVE.name),
        )
        val candidate = candidate("unit-1")

        val plan = planner.plan(request(snapshot, listOf(candidate), now))

        val item = plan.queueItems.single()
        assertTrue(ReviewReason.KC_MASTERY_DROP in item.reasons)
    }

    @Test
    fun `deeper drop produces strictly higher scheduling weight`() {
        val planner = ReviewPlanner()
        val mild = score(planner, conservative = 0.55)
        val severe = score(planner, conservative = 0.1)

        assertTrue("severe=$severe mild=$mild", severe > mild)
    }

    @Test
    fun `not-yet-due question still enters the queue on a mastery drop`() {
        val planner = ReviewPlanner()
        val memory = memory(dueOffsetDays = 20)
        val snapshot = snapshot(
            memories = listOf(memory),
            mastery = mastery(conservative = 0.2, lastDirection = LearningEvidenceDirection.NEGATIVE.name),
        )

        val plan = planner.plan(request(snapshot, listOf(candidate("unit-1")), now))

        assertTrue(plan.queueItems.isNotEmpty())
        val item = plan.queueItems.single()
        assertTrue(ReviewReason.KC_MASTERY_DROP in item.reasons)
    }

    @Test
    fun `positive recent evidence produces no drop pressure`() {
        val planner = ReviewPlanner()
        val memory = memory(dueOffsetDays = 20)
        val snapshot = snapshot(
            memories = listOf(memory),
            mastery = mastery(conservative = 0.2, lastDirection = LearningEvidenceDirection.POSITIVE.name),
        )

        val plan = planner.plan(request(snapshot, listOf(candidate("unit-1")), now))

        assertTrue(plan.queueItems.isEmpty())
    }

    private fun score(planner: ReviewPlanner, conservative: Double): Double {
        val memory = memory(dueOffsetDays = 3)
        val snapshot = snapshot(
            memories = listOf(memory),
            mastery = mastery(conservative = conservative, lastDirection = LearningEvidenceDirection.NEGATIVE.name),
        )
        val plan = planner.plan(request(snapshot, listOf(candidate("unit-1")), now))
        return plan.queueItems.single().priorityScore
    }

    private fun memory(dueOffsetDays: Long) = ProblemMemoryStateFactory.create(
        practiceUnitId = "unit-1",
        lastReviewedAt = now - 5 * DAY,
        nextReviewAt = now + dueOffsetDays * DAY,
    )

    private fun mastery(conservative: Double, lastDirection: String) = KnowledgeMasteryState(
        knowledgeNodeId = "kc-a",
        masteryScore = conservative + 0.1,
        conservativeMasteryScore = conservative,
        evidenceMass = 2.0,
        status = MasteryStatus.LEARNING,
        calibrationSupport = com.tingyun.smartmistakebook.core.model.CalibrationSupport.SUPPORTED,
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 4,
        lastEvidenceAtEpochMillis = now - DAY,
        lastEvidenceDirection = lastDirection,
        // A supported independent observation keeps the calibration channel
        // quiet so the test isolates the KC_MASTERY_DROP term.
        independentCorrectObservations = listOf(
            com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation(
                itemFamilyId = "family-seed",
                studyDayEpochDay = (now - DAY) / DAY,
                occurredAtEpochMillis = now - DAY,
                evidenceWeight = 1.0,
                calibration = com.tingyun.smartmistakebook.core.model.CalibrationSnapshot(
                    com.tingyun.smartmistakebook.core.model.CalibrationSupport.SUPPORTED,
                    "calibration-source",
                    "calibration-v1",
                    0,
                    Long.MAX_VALUE / 2,
                ),
            ),
        ),
    )

    private fun snapshot(
        memories: List<com.tingyun.smartmistakebook.core.model.ProblemMemoryState>,
        mastery: com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState,
    ) = LearnerSnapshot(
        learnerId = "learner-1",
        problemMemoryStates = memories.associateBy { it.practiceUnitId },
        knowledgeMasteryStates = mapOf(mastery.knowledgeNodeId to mastery),
        checkpoint = ProjectionCheckpoint(4, LearningProjector.VERSION, now),
        generatedAtEpochMillis = now,
    )

    private fun candidate(unitId: String) = ReviewCandidate(
        practiceUnitId = unitId,
        knowledgeNodeIds = setOf("kc-a"),
        itemFamilyId = "family-$unitId",
        sourceBundleId = null,
        difficulty = 5.5,
        estimatedDurationSeconds = 60,
    )

    private fun request(
        snapshot: LearnerSnapshot,
        candidates: List<ReviewCandidate>,
        now: Long,
    ) = ReviewPlanningRequest(
        learnerSnapshot = snapshot,
        candidates = candidates,
        localDayEpochDay = 10,
        timeZoneId = "Asia/Shanghai",
        timeBudgetSeconds = 600,
        planningAtEpochMillis = now,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}

/** Local factory keeps the bulky ProblemMemoryState constructor out of test bodies. */
private object ProblemMemoryStateFactory {
    fun create(practiceUnitId: String, lastReviewedAt: Long, nextReviewAt: Long) =
        com.tingyun.smartmistakebook.core.model.ProblemMemoryState(
            practiceUnitId = practiceUnitId,
            stabilityDays = 3.0,
            difficulty = 5.5,
            lastReviewedAtEpochMillis = lastReviewedAt,
            nextReviewAtEpochMillis = nextReviewAt,
            lapseCount = 1,
            lastAttemptId = "attempt-$practiceUnitId",
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 4,
        )
}
