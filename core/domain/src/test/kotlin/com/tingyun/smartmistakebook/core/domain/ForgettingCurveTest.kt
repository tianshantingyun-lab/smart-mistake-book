package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import org.junit.Assert.assertEquals
import org.junit.Test

class ForgettingCurveTest {
    private var now = 0L
    private val curve = ForgettingCurve(EpochMillisClock { now })

    @Test
    fun `retention is ninety percent after one stability interval`() {
        val state = memory(stabilityDays = 4.0, lastReviewedAt = DAY_MILLIS)
        now = DAY_MILLIS * 5

        assertEquals(0.9, curve.retentionNow(state), 1e-9)
    }

    @Test
    fun `time rollback is clamped instead of increasing retention beyond one`() {
        val state = memory(stabilityDays = 2.0, lastReviewedAt = DAY_MILLIS * 5)
        now = DAY_MILLIS

        assertEquals(1.0, curve.retentionNow(state), 0.0)
    }

    @Test
    fun `target date uses the same stability to retention contract`() {
        val reviewedAt = DAY_MILLIS * 3
        val dueAt = curve.reviewAtTargetRetention(
            reviewedAtEpochMillis = reviewedAt,
            stabilityDays = 7.5,
        )

        assertEquals(reviewedAt + (DAY_MILLIS * 7.5).toLong(), dueAt)
    }

    private fun memory(stabilityDays: Double, lastReviewedAt: Long) = ProblemMemoryState(
        practiceUnitId = "unit-1",
        stabilityDays = stabilityDays,
        difficulty = 0.5,
        lastReviewedAtEpochMillis = lastReviewedAt,
        nextReviewAtEpochMillis = lastReviewedAt,
        lastAttemptId = "attempt-1",
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 1,
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
