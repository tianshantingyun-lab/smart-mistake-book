package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §6 per-planned-reason recalibration table (advisory, >=200-sample gate). */
class PlannedReasonCalibrationTest {

    @Test
    fun `groups samples by planner reason against the overall baseline`() {
        val samples = listOf(
            sample("unit-1", 0, reason = "DUE_RECALL_RISK", correct = true),
            sample("unit-1", DAY, reason = "DUE_RECALL_RISK", correct = false),
            sample("unit-2", DAY * 2, reason = "WEAK_KNOWLEDGE", correct = true),
        )

        val report = SchedulingEvaluationHarness.calibratePlannedReasons(samples)

        assertEquals(2, report.size)
        val due = report.single { it.plannedReason == "DUE_RECALL_RISK" }
        assertEquals(2, due.sampleCount)
        assertEquals(0.5, due.realizedRecallRate, 1e-9)
        val weak = report.single { it.plannedReason == "WEAK_KNOWLEDGE" }
        assertEquals(1.0, weak.realizedRecallRate, 1e-9)
        assertEquals(2.0 / 3.0, report.first().overallRecallRate, 1e-9)
    }

    @Test
    fun `samples without a planned reason are excluded`() {
        val report = SchedulingEvaluationHarness.calibratePlannedReasons(
            listOf(sample("unit-1", 0, reason = null, correct = true)),
        )

        assertTrue(report.isEmpty())
    }

    @Test
    fun `two hundred samples open the recalibration gate`() {
        val below = PlannedReasonCalibration("WEAK_KNOWLEDGE", 199, 0.5, 0.5)
        val at = PlannedReasonCalibration("WEAK_KNOWLEDGE", 200, 0.5, 0.5)

        assertFalse(below.hasSufficientSamples)
        assertTrue(at.hasSufficientSamples)
    }

    private fun sample(unitId: String, at: Long, reason: String?, correct: Boolean) = ReviewSample(
        practiceUnitId = unitId,
        reviewedAtEpochMillis = at,
        rating = if (correct) FsrsRating.GOOD else FsrsRating.AGAIN,
        sourceKind = "ATTEMPT",
        plannedReason = reason,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
