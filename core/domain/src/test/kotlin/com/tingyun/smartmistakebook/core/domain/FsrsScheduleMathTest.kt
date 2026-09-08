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
    fun `hard penalty scales the growth increment and the easy bonus is neutral`() {
        val good = FsrsScheduleMath.nextRecallStability(5.0, 10.0, 0.8, FsrsRating.GOOD)
        val hard = FsrsScheduleMath.nextRecallStability(5.0, 10.0, 0.8, FsrsRating.HARD)
        val easy = FsrsScheduleMath.nextRecallStability(5.0, 10.0, 0.8, FsrsRating.EASY)

        assertTrue(hard < good)
        // w16 is pinned at 1.0: the evidence mapping never grades a review EASY
        // (研究 2026-09-09 §7), so an EASY rating must not buy extra stability.
        assertEquals(good, easy, 1e-9)
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
        // py-fsrs `_next_difficulty` (rating is 1-based): arg1 = unclamped D0(Easy);
        // delta_difficulty = -w6*(rating-3); arg2 = D + (10-D)*delta/9;
        // next = w7*arg1 + (1-w7)*arg2, clamped to 1..10.
        // Official values for D=5.0 (computed from py-fsrs / fsrs-rs sources):
        assertEquals(8.3417623693, FsrsScheduleMath.nextDifficulty(5.0, FsrsRating.AGAIN), 1e-9)
        assertEquals(6.6659953693, FsrsScheduleMath.nextDifficulty(5.0, FsrsRating.HARD), 1e-9)
        assertEquals(4.9902283693, FsrsScheduleMath.nextDifficulty(5.0, FsrsRating.GOOD), 1e-9)
        assertEquals(3.3144613693, FsrsScheduleMath.nextDifficulty(5.0, FsrsRating.EASY), 1e-9)
    }

    @Test
    fun `short term stability matches py-fsrs composition`() {
        // py-fsrs `_short_term_stability` (rating 1-based): sinc = e^(w17*(rating-3+w18)) * S^-w19,
        // floored at 1.0 for Hard/Good/Easy. Official values for S=10.0:
        assertEquals(3.0512489356, FsrsScheduleMath.shortTermStability(10.0, FsrsRating.AGAIN), 1e-9)
        assertEquals(10.0, FsrsScheduleMath.shortTermStability(10.0, FsrsRating.HARD), 1e-9)
        assertEquals(10.0, FsrsScheduleMath.shortTermStability(10.0, FsrsRating.GOOD), 1e-9)
        assertEquals(15.5343079472, FsrsScheduleMath.shortTermStability(10.0, FsrsRating.EASY), 1e-9)
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
    fun `scheduling mapping caps subjective easy at good`() {
        val subjective = LearningEvidenceReason.SELF_REPORTED_RECALL

        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(subjective, 0.9),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(subjective, 0.35),
        )
        // Non-subjective reasons keep their reported grade.
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(LearningEvidenceReason.VISUAL_INTERACTION_SATISFIED, 0.25),
        )
    }

    @Test
    fun `the subjective cap is a ceiling not a floor`() {
        // Audit 2026-09-09: the old mapping returned Good for every subjective
        // report, lifting a "very effortful" (Hard) self-report to the same
        // stability gain as an ordinary one. Only Easy is capped down.
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(
                LearningEvidenceReason.SELF_REPORTED_RECALL,
                FsrsEvidenceRatingMapper.RATING_HARD_WEIGHT,
            ),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(
                LearningEvidenceReason.SELF_REPORTED_RECALL,
                FsrsEvidenceRatingMapper.RATING_GOOD_WEIGHT,
            ),
        )
    }

    @Test
    fun `assisted correct answers grade as hard`() {
        // Audit 2026-09-09: a correct answer that needed a hint or a retry is
        // assisted retrieval and must not earn the independent-recall gain.
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(
                LearningEvidenceReason.CORRECT_AFTER_HINT,
                0.6,
            ),
        )
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(
                LearningEvidenceReason.CORRECT_ON_RETRY,
                0.6,
            ),
        )
        // The verbatim report still lands in review_log.
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.reportedRatingFor(
                LearningEvidenceReason.CORRECT_AFTER_HINT,
                0.6,
            ),
        )
    }

    @Test
    fun `low confidence independent correct grades as hard`() {
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(LearningEvidenceReason.INDEPENDENT_CORRECT, 0.7),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.schedulingRatingFor(LearningEvidenceReason.INDEPENDENT_CORRECT, 1.0),
        )
        // The reported key stays verbatim regardless of the discount.
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.INDEPENDENT_CORRECT, 0.7),
        )
    }

    @Test
    fun `evidence ratings map per the migration table`() {
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.INDEPENDENT_CORRECT, 1.0),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.CORRECT_AFTER_HINT, 0.6),
        )
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.CORRECT_ON_RETRY, 0.25),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.CORRECT_ON_RETRY, 0.6),
        )
        assertEquals(
            FsrsRating.EASY,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.SELF_REPORTED_RECALL, 0.9),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.SELF_REPORTED_RECALL, 0.8),
        )
        assertEquals(
            FsrsRating.GOOD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.SELF_REPORTED_RECALL, 0.35),
        )
        assertEquals(
            FsrsRating.HARD,
            FsrsEvidenceRatingMapper.reportedRatingFor(LearningEvidenceReason.VISUAL_INTERACTION_SATISFIED, 0.25),
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
            assertEquals(FsrsRating.AGAIN, FsrsEvidenceRatingMapper.reportedRatingFor(negative, 0.5))
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
