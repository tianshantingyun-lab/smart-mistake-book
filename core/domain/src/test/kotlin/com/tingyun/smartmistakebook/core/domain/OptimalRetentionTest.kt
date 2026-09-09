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
    fun `strong graduated cards justify higher retention than weak cards`() {
        // With the lapse force, a lapse destroys accumulated stability and
        // re-mastering a previously stable card costs more reviews than
        // re-learning a weak one, so a graduated deck is scheduled at a higher
        // target than a weak deck. (Under the pre-lapse model the strong deck
        // collapsed to the retention floor, so this relationship was inverted.)
        val weak = requireNotNull(OptimalRetention.recommend(cards(40, stabilityDays = 2.0)))
        val strong = requireNotNull(OptimalRetention.recommend(cards(40, stabilityDays = 200.0)))

        assertTrue(
            "weak=${weak.desiredRetention} strong=${strong.desiredRetention}",
            strong.desiredRetention > weak.desiredRetention,
        )
    }

    @Test
    fun `graduated deck does not collapse to the retention floor`() {
        // Regression (2026-09-10): without the lapse force the cost/memorized
        // curve was monotone decreasing and a mature deck's "optimum" was the
        // lowest supported retention (0.70 = highest-forgetting regime). The
        // lapse force must restore an interior minimum for a graduated deck.
        val graduated = requireNotNull(
            OptimalRetention.recommend(cards(40, stabilityDays = 60.0)),
        )
        assertTrue(
            "graduated deck recommends ${graduated.desiredRetention} (at the floor)",
            graduated.desiredRetention > OptimalRetention.MIN_DESIRED_RETENTION,
        )
    }
}
