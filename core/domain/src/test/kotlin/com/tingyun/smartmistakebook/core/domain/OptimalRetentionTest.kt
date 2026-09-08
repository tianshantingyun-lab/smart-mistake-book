package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CMRR-style recommendation (研究 2026-09-09 §5): the simulated curve must
 * behave like a real workload/retention trade-off, and thin data must yield no
 * recommendation at all.
 */
class OptimalRetentionTest {

    private fun cards(count: Int, stabilityDays: Double = 30.0) =
        (0 until count).map { OptimalRetention.Card(stabilityDays, difficulty = 5.0) }

    @Test
    fun `thin data yields no recommendation`() {
        assertNull(OptimalRetention.recommend(cards(OptimalRetention.MIN_CARDS - 1)))
        assertTrue(OptimalRetention.recommend(cards(OptimalRetention.MIN_CARDS)) != null)
    }

    @Test
    fun `higher retention target costs more reviews and keeps more knowledge`() {
        val recommendation = requireNotNull(OptimalRetention.recommend(cards(40)))
        val low = recommendation.curve.first()
        val high = recommendation.curve.last()

        assertTrue("low=$low high=$high", high.reviewCount > low.reviewCount)
        assertTrue("low=$low high=$high", high.memorized > low.memorized)
        assertEquals(OptimalRetention.MIN_DESIRED_RETENTION, low.desiredRetention, 1e-9)
        assertEquals(OptimalRetention.MAX_DESIRED_RETENTION, high.desiredRetention, 1e-9)
    }

    @Test
    fun `recommendation sits inside the simulated range and minimizes cost per memorized`() {
        val recommendation = requireNotNull(OptimalRetention.recommend(cards(60)))
        val best = recommendation.curve.minByOrNull(OptimalRetention.Point::costPerMemorized)

        assertTrue(
            recommendation.desiredRetention in
                OptimalRetention.MIN_DESIRED_RETENTION..OptimalRetention.MAX_DESIRED_RETENTION,
        )
        assertEquals(requireNotNull(best).desiredRetention, recommendation.desiredRetention, 1e-9)
        recommendation.curve.forEach { point ->
            assertTrue(point.memorized > 0.0)
            assertTrue(point.reviewCount > 0)
        }
    }

    @Test
    fun `weaker cards push the recommendation higher than strong cards`() {
        val weak = requireNotNull(OptimalRetention.recommend(cards(40, stabilityDays = 2.0)))
        val strong = requireNotNull(OptimalRetention.recommend(cards(40, stabilityDays = 200.0)))

        assertTrue(
            "weak=${weak.desiredRetention} strong=${strong.desiredRetention}",
            weak.desiredRetention >= strong.desiredRetention,
        )
    }
}
