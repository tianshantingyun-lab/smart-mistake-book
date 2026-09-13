package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ForgettingCurveTest {
    private var now = 0L

    /** 固定 UTC：这些夹具的时刻都落在整日边界上，两种口径在 UTC 下逐位相同。 */
    private val zone = ZoneOffset.UTC
    private val curve = ForgettingCurve(EpochMillisClock { now })

    @Test
    fun `retention is ninety percent after one stability interval`() {
        val state = memory(stabilityDays = 4.0, lastReviewedAt = DAY_MILLIS)
        now = DAY_MILLIS * 5

        assertEquals(0.9, curve.retentionNow(state, zone), 1e-9)
    }

    @Test
    fun `time rollback is clamped instead of increasing retention beyond one`() {
        val state = memory(stabilityDays = 2.0, lastReviewedAt = DAY_MILLIS * 5)
        now = DAY_MILLIS

        assertEquals(1.0, curve.retentionNow(state, zone), 0.0)
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

    @Test
    fun `fsrs power law retention is ninety percent at one stability interval`() {
        val fsrsCurve = ForgettingCurve(
            EpochMillisClock { now },
            algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW,
        )
        val state = memory(stabilityDays = 4.0, lastReviewedAt = DAY_MILLIS)
        now = DAY_MILLIS * 5

        assertEquals(0.9, fsrsCurve.retentionNow(state, zone), 1e-6)
    }

    @Test
    fun `fsrs power law retention decays monotonically and counts whole local days`() {
        val fsrsCurve = ForgettingCurve(
            EpochMillisClock { now },
            algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW,
        )
        val state = memory(stabilityDays = 3.0, lastReviewedAt = 0L)
        now = DAY_MILLIS / 2

        // FSRS-6 的 t 是整天数（口径见 ReviewCalendar）：半天之后仍落在同一个本地日，
        // 所以还是第 0 天，保持率为一。**跨午夜的半天**才是分道扬镳的那一格，
        // 由 FsrsConventionTest 专门钉住。
        assertEquals(1.0, fsrsCurve.retentionNow(state, zone), 0.0)
        now = DAY_MILLIS * 30
        val later = fsrsCurve.retentionNow(state, zone)
        now = DAY_MILLIS * 300
        val muchLater = fsrsCurve.retentionNow(state, zone)

        assertTrue(later > muchLater)
        assertTrue(muchLater > 0.0)
    }

    @Test
    fun `fsrs interval inverse at default retention equals stability`() {
        val stabilityDays = 13.7
        val reviewedAt = DAY_MILLIS * 2
        val dueAt = ForgettingCurve(
            algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW,
        ).reviewAtTargetRetention(
            reviewedAtEpochMillis = reviewedAt,
            stabilityDays = stabilityDays,
        )

        assertEquals(reviewedAt + (DAY_MILLIS * kotlin.math.round(stabilityDays)).toLong(), dueAt)
    }

    private fun memory(stabilityDays: Double, lastReviewedAt: Long) = ProblemMemoryState(
        practiceUnitId = "unit-1",
        stabilityDays = stabilityDays,
        difficulty = 5.5,
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
