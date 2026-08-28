package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

class FsrsScheduleMathTest {

    @Test
    fun `power law retention is ninety percent at one stability interval`() {
        assertEquals(
            0.9,
            FsrsScheduleMath.retention(elapsedDays = 10.0, stabilityDays = 10.0),
            1e-9,
        )
    }

    @Test
    fun `power law retention decays monotonically toward zero`() {
        val oneDay = FsrsScheduleMath.retention(1.0, 10.0)
        val tenDays = FsrsScheduleMath.retention(10.0, 10.0)
        val hundredDays = FsrsScheduleMath.retention(100.0, 10.0)

        assertTrue(oneDay > tenDays)
        assertTrue(tenDays > hundredDays)
        assertTrue(hundredDays > 0.0)
    }

    @Test
    fun `interval inverse at default retention reproduces stability`() {
        for (stability in listOf(0.212, 2.3065, 30.0, 365.0)) {
            assertEquals(
                stability,
                FsrsScheduleMath.intervalDays(stability, 0.9).toDouble(),
                1.0,
            )
        }
    }

    @Test
    fun `initial stability uses the grade parameter row`() {
        assertEquals(0.212, FsrsScheduleMath.initialStability(FsrsRating.AGAIN), 1e-9)
        assertEquals(1.2931, FsrsScheduleMath.initialStability(FsrsRating.HARD), 1e-9)
        assertEquals(2.3065, FsrsScheduleMath.initialStability(FsrsRating.GOOD), 1e-9)
        assertEquals(8.2956, FsrsScheduleMath.initialStability(FsrsRating.EASY), 1e-9)
    }

    @Test
    fun `initial difficulty follows w4 minus exponential of w5`() {
        assertEquals(6.4133, FsrsScheduleMath.initialDifficulty(FsrsRating.AGAIN), 1e-6)
        assertEquals(5.1122, FsrsScheduleMath.initialDifficulty(FsrsRating.HARD), 1e-4)
        assertEquals(2.1181, FsrsScheduleMath.initialDifficulty(FsrsRating.GOOD), 1e-4)
        // Easy lands below the floor and must clamp to one.
        assertEquals(1.0, FsrsScheduleMath.clampDifficulty(FsrsScheduleMath.initialDifficulty(FsrsRating.EASY)), 1e-9)
    }

    @Test
    fun `same day success never lowers stability`() {
        for (stability in listOf(0.5, 2.0, 30.0, 365.0)) {
            for (rating in listOf(FsrsRating.HARD, FsrsRating.GOOD, FsrsRating.EASY)) {
                val next = FsrsScheduleMath.shortTermStability(stability, rating)
                assertTrue(
                    "stability $stability rating $rating dropped to $next",
                    next >= stability,
                )
            }
        }
    }

    @Test
    fun `same day again decays stability`() {
        val next = FsrsScheduleMath.shortTermStability(10.0, FsrsRating.AGAIN)
        assertTrue(next < 10.0)
    }

    @Test
    fun `cross day success grows with retrievability drop`() {
        val atNinety = FsrsScheduleMath.nextRecallStability(
            difficulty = 5.0,
            stability = 10.0,
            retrievability = 0.9,
            rating = FsrsRating.GOOD,
        )
        val atFifty = FsrsScheduleMath.nextRecallStability(
            difficulty = 5.0,
            stability = 10.0,
            retrievability = 0.5,
            rating = FsrsRating.GOOD,
        )

        assertTrue(atNinety > 10.0)
        assertTrue(atFifty > atNinety)
    }

    @Test
    fun `hard penalty and easy bonus scale the growth increment`() {
        val good = FsrsScheduleMath.nextRecallStability(5.0, 10.0, 0.8, FsrsRating.GOOD)
        val hard = FsrsScheduleMath.nextRecallStability(5.0, 10.0, 0.8, FsrsRating.HARD)
        val easy = FsrsScheduleMath.nextRecallStability(5.0, 10.0, 0.8, FsrsRating.EASY)

        assertTrue(hard < good)
        assertTrue(good < easy)
    }

    @Test
    fun `forget stability is clamped below the pre lapse stability`() {
        val forget = FsrsScheduleMath.nextForgetStability(
            difficulty = 5.0,
            stability = 100.0,
            retrievability = 0.3,
        )
        val clampCeiling = 100.0 / exp(FsrsScheduleMath.DEFAULT_PARAMETERS[17] * FsrsScheduleMath.DEFAULT_PARAMETERS[18])

        assertTrue(forget <= clampCeiling + 1e-9)
        assertTrue(forget > 0.0)
    }

    @Test
    fun `difficulty mean reversion matches py-fsrs composition`() {
        // py-fsrs: arg1 = unclamped D0(Easy); arg2 = D + (10-D)*(-w6*(G-3))/9;
        // next = w7*arg1 + (1-w7)*arg2, clamped to 1..10.
        val w = FsrsScheduleMath.DEFAULT_PARAMETERS
        val difficulty = 5.0
        val arg1 = w[4] - exp(w[5] * 3.0) + 1.0
        val delta = -(w[6] * (FsrsRating.GOOD.ordinal - 3))
        val arg2 = difficulty + (10.0 - difficulty) * delta / 9.0
        val expected = (w[7] * arg1 + (1 - w[7]) * arg2)

        assertEquals(expected, FsrsScheduleMath.nextDifficulty(difficulty, FsrsRating.GOOD), 1e-9)
    }

    @Test
    fun `difficulty stays within one and ten for every rating`() {
        for (difficulty in listOf(1.0, 5.0, 10.0)) {
            for (rating in FsrsRating.entries) {
                val next = FsrsScheduleMath.nextDifficulty(difficulty, rating)
                assertTrue(next in 1.0..10.0)
            }
        }
    }

    @Test
    fun `evidence ratings map per the migration table`() {
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.INDEPENDENT_CORRECT, 1.0),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.CORRECT_AFTER_HINT, 0.6),
        )
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.CORRECT_ON_RETRY, 0.25),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.CORRECT_ON_RETRY, 0.6),
        )
        assertEquals(
            FsrsRating.EASY,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.SELF_REPORTED_RECALL, 0.9),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.SELF_REPORTED_RECALL, 0.8),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.SELF_REPORTED_RECALL, 0.35),
        )
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.ratingFor(LearningEvidenceReason.VISUAL_INTERACTION_SATISFIED, 0.25),
        )
        for (negative in listOf(
            LearningEvidenceReason.INDEPENDENT_INCORRECT,
            LearningEvidenceReason.INCORRECT_AFTER_HINT,
            LearningEvidenceReason.INCORRECT_ON_RETRY,
            LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
            LearningEvidenceReason.SELF_REPORTED_STUCK,
            LearningEvidenceReason.VISUAL_INTERACTION_VIOLATED,
            LearningEvidenceReason.ANSWER_REVEALED,
        )) {
            assertEquals(FsrsRating.AGAIN, FsrsEvidenceRatingMapper.ratingFor(negative, 0.5))
        }
    }

    @Test
    fun `invariants hold across a long synthetic review history`() {
        // Spec 2.1 invariants I1-I3: R(0)=1, R monotone, success never
        // lowers stability, failure never raises it above the clamp.
        var stability = FsrsScheduleMath.initialStability(FsrsRating.GOOD)
        var difficulty = FsrsScheduleMath.clampDifficulty(FsrsScheduleMath.initialDifficulty(FsrsRating.GOOD))
        assertTrue(stability > 0.0)
        for (day in 1..40) {
            val retrievability = FsrsScheduleMath.retention(day.toDouble(), stability)
            stability = if (day % 3 == 0) {
                FsrsScheduleMath.nextForgetStability(difficulty, stability, retrievability)
            } else {
                FsrsScheduleMath.nextRecallStability(difficulty, stability, retrievability, FsrsRating.GOOD)
            }
            difficulty = FsrsScheduleMath.nextDifficulty(difficulty, FsrsRating.GOOD)
            assertTrue(stability in FsrsScheduleMath.STABILITY_MIN..FsrsScheduleMath.STABILITY_MAX)
            assertTrue(difficulty in 1.0..10.0)
        }
    }

    @Test
    fun `power law matches the closed form factor`() {
        val decay = -FsrsScheduleMath.DEFAULT_PARAMETERS[20]
        val factor = FsrsScheduleMath.factor()
        val expected = (1.0 + factor * 20.0 / 10.0).pow(decay)

        assertEquals(expected, FsrsScheduleMath.retention(20.0, 10.0), 1e-12)
    }
}
