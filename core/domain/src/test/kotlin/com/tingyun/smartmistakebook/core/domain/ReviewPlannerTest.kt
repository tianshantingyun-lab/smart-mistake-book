package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ReviewReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPlannerTest {
    private val now = 10 * DAY_MILLIS
    private val planner = ReviewPlanner()

    @Test
    fun `plan obeys time budget and removes same family and same source duplicates`() {
        val snapshot = snapshot(
            memories = listOf(
                memory("unit-a", dueOffsetDays = -3),
                memory("unit-b", dueOffsetDays = -2),
                memory("unit-c", dueOffsetDays = -1),
                memory("unit-d", dueOffsetDays = -1),
            ),
        )
        val candidates = listOf(
            candidate("unit-a", "family-1", "source-1", 0.5, 300),
            candidate("unit-b", "family-1", "source-2", 0.2, 300),
            candidate("unit-c", "family-3", "source-1", 0.8, 300),
            candidate("unit-d", "family-4", "source-4", 0.8, 300),
        )

        val plan = planner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = snapshot,
                candidates = candidates,
                localDayEpochDay = 10,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 600,
                planningAtEpochMillis = now,
            ),
        )

        assertTrue(plan.totalEstimatedDurationSeconds <= 600)
        assertEquals(plan.queueItems.size, plan.queueItems.map { it.itemFamilyId }.distinct().size)
        assertEquals(
            plan.queueItems.size,
            plan.queueItems.mapNotNull { it.sourceBundleId }.distinct().size,
        )
    }

    @Test
    fun `equal priority candidates are mixed by difficulty deterministically`() {
        val memories = listOf("easy", "medium", "hard").map { memory(it, dueOffsetDays = -1) }
        val request = ReviewPlanningRequest(
            learnerSnapshot = snapshot(memories),
            candidates = listOf(
                candidate("hard", "family-h", "source-h", 0.9, 60),
                candidate("easy", "family-e", "source-e", 0.2, 60),
                candidate("medium", "family-m", "source-m", 0.5, 60),
            ),
            localDayEpochDay = 10,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 180,
            planningAtEpochMillis = now,
        )

        val first = planner.plan(request)
        val second = planner.plan(request)

        assertEquals(first, second)
        assertEquals(listOf("medium", "easy", "hard"), first.queueItems.map { it.practiceUnitId })
    }

    @Test
    fun `planning time and candidate metadata cannot reuse an immutable plan identity`() {
        val baseRequest = ReviewPlanningRequest(
            learnerSnapshot = snapshot(listOf(memory("unit-a", dueOffsetDays = -1))),
            candidates = listOf(candidate("unit-a", "family-a", "source-a", 0.5, 60)),
            localDayEpochDay = 10,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 60,
            planningAtEpochMillis = now,
        )

        val base = planner.plan(baseRequest)
        val later = planner.plan(baseRequest.copy(planningAtEpochMillis = now + 1))
        val changedDifficulty = planner.plan(
            baseRequest.copy(
                candidates = listOf(baseRequest.candidates.single().copy(difficulty = 0.55)),
            ),
        )

        assertTrue(base.planId != later.planId)
        assertTrue(base.planId != changedDifficulty.planId)
        assertEquals(base, planner.plan(baseRequest))
    }

    @Test
    fun `queue explains overdue risk and weak knowledge`() {
        val plan = planner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = snapshot(listOf(memory("unit-a", dueOffsetDays = -2))),
                candidates = listOf(candidate("unit-a", "family-a", null, 0.5, 60)),
                localDayEpochDay = 10,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 60,
                planningAtEpochMillis = now,
            ),
        )

        val reasons = plan.queueItems.single().reasons
        assertTrue(ReviewReason.DUE_RECALL_RISK in reasons)
        assertTrue(ReviewReason.WEAK_KNOWLEDGE in reasons)
    }

    @Test
    fun `repeatedly captured mistake is prioritized and changes plan identity`() {
        val snapshot = snapshot(
            listOf(
                memory("unit-a", dueOffsetDays = -1),
                memory("unit-z", dueOffsetDays = -1),
            ),
        )
        val baseRequest = ReviewPlanningRequest(
            learnerSnapshot = snapshot,
            candidates = listOf(
                candidate("unit-a", "family-a", "source-a", 0.5, 60),
                candidate("unit-z", "family-z", "source-z", 0.5, 60),
            ),
            localDayEpochDay = 10,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 120,
            planningAtEpochMillis = now,
        )

        val base = planner.plan(baseRequest)
        val repeated = planner.plan(
            baseRequest.copy(
                candidates = baseRequest.candidates.map { candidate ->
                    if (candidate.practiceUnitId == "unit-z") {
                        candidate.copy(repeatMistakePriority = 0.5)
                    } else {
                        candidate
                    }
                },
            ),
        )

        assertEquals("unit-a", base.queueItems.first().practiceUnitId)
        assertEquals("unit-z", repeated.queueItems.first().practiceUnitId)
        assertTrue(ReviewReason.REPEATED_MISTAKE in repeated.queueItems.first().reasons)
        assertTrue(base.planFingerprint != repeated.planFingerprint)
    }

    @Test
    fun `older unscheduled mistake outranks a newly arrived equal candidate`() {
        val old = candidate("unit-z", "family-z", "source-z", 0.5, 60).copy(
            eligibleSinceEpochMillis = 0,
        )
        val recent = candidate("unit-a", "family-a", "source-a", 0.5, 60).copy(
            eligibleSinceEpochMillis = now - DAY_MILLIS,
        )

        val plan = planner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = snapshot(emptyList()),
                candidates = listOf(recent, old),
                localDayEpochDay = 10,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 60,
                planningAtEpochMillis = now,
            ),
        )

        assertEquals("unit-z", plan.queueItems.single().practiceUnitId)
        assertTrue(ReviewReason.LONG_WAITING in plan.queueItems.single().reasons)
    }

    @Test
    fun `five thousand item backlog rotates without exceeding the daily budget`() {
        val simulationStart = 6_000 * DAY_MILLIS
        val calibration = CalibrationSnapshot(
            CalibrationSupport.SUPPORTED,
            "backlog-simulation",
            "calibration-v1",
            0,
            simulationStart + 30 * DAY_MILLIS,
        )
        val currentWeakMastery = snapshot(emptyList()).knowledgeMasteryStates.getValue("kc-a").copy(
            lastEvidenceAtEpochMillis = simulationStart,
            independentCorrectObservations = listOf(
                IndependentCorrectObservation(
                    "backlog-family",
                    1,
                    simulationStart,
                    evidenceWeight = 1.0,
                    calibration = calibration,
                ),
            ),
        )
        val candidates = (0 until 5_000).map { index ->
            candidate(
                unitId = "unit-${index.toString().padStart(4, '0')}",
                familyId = "family-$index",
                sourceId = "source-$index",
                difficulty = 0.5,
                seconds = 60,
            ).copy(
                eligibleSinceEpochMillis =
                    simulationStart - (5_000L - index) * DAY_MILLIS,
            )
        }
        val memories = linkedMapOf<String, ProblemMemoryState>()
        val seen = linkedSetOf<String>()

        repeat(10) { day ->
            val planningAt = simulationStart + day * DAY_MILLIS
            val baseSnapshot = snapshot(emptyList()).copy(
                problemMemoryStates = memories.toMap(),
                knowledgeMasteryStates = mapOf("kc-a" to currentWeakMastery),
            )
            val plan = planner.plan(
                ReviewPlanningRequest(
                    learnerSnapshot = baseSnapshot,
                    candidates = candidates,
                    localDayEpochDay = 6_000L + day,
                    timeZoneId = "Asia/Shanghai",
                    timeBudgetSeconds = 900,
                    planningAtEpochMillis = planningAt,
                ),
            )
            val selectedIds = plan.queueItems.map { it.practiceUnitId }

            assertEquals(900, plan.totalEstimatedDurationSeconds)
            assertEquals(15, selectedIds.size)
            assertTrue(selectedIds.none(seen::contains))
            seen += selectedIds
            selectedIds.forEach { practiceUnitId ->
                memories[practiceUnitId] = ProblemMemoryState(
                    practiceUnitId = practiceUnitId,
                    stabilityDays = 30.0,
                    difficulty = 0.5,
                    lastReviewedAtEpochMillis = planningAt,
                    nextReviewAtEpochMillis = planningAt + 30 * DAY_MILLIS,
                    lapseCount = 0,
                    lastAttemptId = "attempt-$practiceUnitId-$day",
                    projectorVersion = LearningProjector.VERSION,
                    checkpointSequence = 4,
                )
            }
        }

        assertEquals(150, seen.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `planner refuses a stale snapshot instead of mixing checkpoints`() {
        planner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = snapshot(listOf(memory("unit-a", dueOffsetDays = -1)))
                    .copy(freshness = LearnerSnapshotFreshness.STALE),
                candidates = listOf(candidate("unit-a", "family-a", null, 0.5, 60)),
                localDayEpochDay = 10,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 60,
                planningAtEpochMillis = now,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `planner refuses a decision time before the projected snapshot`() {
        ReviewPlanningRequest(
            learnerSnapshot = snapshot(listOf(memory("unit-a", dueOffsetDays = -1))),
            candidates = listOf(candidate("unit-a", "family-a", null, 0.5, 60)),
            localDayEpochDay = 10,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 60,
            planningAtEpochMillis = now - 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `planner refuses a decision time before the correction watermark`() {
        ReviewPlanningRequest(
            learnerSnapshot = snapshot(listOf(memory("unit-a", dueOffsetDays = -1))).copy(
                correctionWatermarkEpochMillis = now + 1,
            ),
            candidates = listOf(candidate("unit-a", "family-a", null, 0.5, 60)),
            localDayEpochDay = 10,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 60,
            planningAtEpochMillis = now,
        )
    }

    @Test
    fun `missing and conflicted knowledge are scored as conservative calibration risk`() {
        val base = snapshot(listOf(memory("unit-a", dueOffsetDays = -1)))
        val conflicted = base.knowledgeMasteryStates.getValue("kc-a").copy(
            status = MasteryStatus.CONFLICTED,
            conflictSinceSequence = 3,
        )
        val plan = planner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = base.copy(
                    knowledgeMasteryStates = mapOf("kc-a" to conflicted),
                ),
                candidates = listOf(
                    candidate("unit-a", "family-a", null, 0.5, 60).copy(
                        knowledgeNodeIds = setOf("kc-a", "kc-missing"),
                    ),
                ),
                localDayEpochDay = 10,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 60,
                planningAtEpochMillis = now,
            ),
        )

        val reasons = plan.queueItems.single().reasons
        assertTrue(ReviewReason.MISSING_KNOWLEDGE_EVIDENCE in reasons)
        assertTrue(ReviewReason.CONFLICTED_KNOWLEDGE in reasons)
        assertTrue(ReviewReason.CALIBRATION_CHECK in reasons)
    }

    @Test
    fun `clock rollback is exposed as conservative review risk`() {
        val futureMemory = memory("unit-a", dueOffsetDays = 2).copy(
            lastReviewedAtEpochMillis = now + DAY_MILLIS,
        )
        val plan = planner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = snapshot(listOf(futureMemory)),
                candidates = listOf(candidate("unit-a", "family-a", null, 0.5, 60)),
                localDayEpochDay = 10,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 60,
                planningAtEpochMillis = now,
            ),
        )

        assertTrue(ReviewReason.CLOCK_ANOMALY in plan.queueItems.single().reasons)
    }

    @Test
    fun `expired calibration is conservative even when the mastery estimate is high`() {
        val base = snapshot(listOf(memory("unit-a", dueOffsetDays = 2)))
        val expiredCalibration = CalibrationSnapshot(
            CalibrationSupport.SUPPORTED,
            "calibration-source",
            "calibration-v1",
            0,
            now - 1,
        )
        val highButExpired = base.knowledgeMasteryStates.getValue("kc-a").copy(
            probabilityIndependentCorrect = 0.99,
            lowerBoundIndependentCorrect = 0.98,
            lastEvidenceAtEpochMillis = now - 1,
            independentCorrectObservations = listOf(
                IndependentCorrectObservation(
                    "family-a",
                    9,
                    now - 1,
                    evidenceWeight = 1.0,
                    calibration = expiredCalibration,
                ),
            ),
        )
        val plan = planner.plan(
            ReviewPlanningRequest(
                learnerSnapshot = base.copy(
                    knowledgeMasteryStates = mapOf("kc-a" to highButExpired),
                ),
                candidates = listOf(candidate("unit-a", "family-a", null, 0.5, 60)),
                localDayEpochDay = 10,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 60,
                planningAtEpochMillis = now,
            ),
        )

        assertTrue(ReviewReason.CALIBRATION_CHECK in plan.queueItems.single().reasons)
        assertTrue(ReviewReason.WEAK_KNOWLEDGE in plan.queueItems.single().reasons)
    }

    private fun snapshot(memories: List<ProblemMemoryState>): LearnerSnapshot {
        val mastery = KnowledgeMasteryState(
            knowledgeNodeId = "kc-a",
            probabilityIndependentCorrect = 0.4,
            lowerBoundIndependentCorrect = 0.2,
            evidenceMass = 2.0,
            status = MasteryStatus.LEARNING,
            calibrationSupport = CalibrationSupport.SUPPORTED,
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 4,
        )
        return LearnerSnapshot(
            learnerId = "learner-1",
            problemMemoryStates = memories.associateBy(ProblemMemoryState::practiceUnitId),
            knowledgeMasteryStates = mapOf("kc-a" to mastery),
            checkpoint = ProjectionCheckpoint(4, LearningProjector.VERSION, now),
            generatedAtEpochMillis = now,
        )
    }

    private fun memory(unitId: String, dueOffsetDays: Long) = ProblemMemoryState(
        practiceUnitId = unitId,
        stabilityDays = 1.0,
        difficulty = 0.5,
        lastReviewedAtEpochMillis = now - 5 * DAY_MILLIS,
        nextReviewAtEpochMillis = now + dueOffsetDays * DAY_MILLIS,
        lapseCount = 1,
        lastAttemptId = "attempt-$unitId",
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 4,
    )

    private fun candidate(
        unitId: String,
        familyId: String,
        sourceId: String?,
        difficulty: Double,
        seconds: Int,
    ) = ReviewCandidate(
        practiceUnitId = unitId,
        knowledgeNodeIds = setOf("kc-a"),
        itemFamilyId = familyId,
        sourceBundleId = sourceId,
        difficulty = difficulty,
        estimatedDurationSeconds = seconds,
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
