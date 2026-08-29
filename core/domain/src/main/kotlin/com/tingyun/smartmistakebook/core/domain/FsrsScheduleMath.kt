package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import kotlin.math.exp
import kotlin.math.pow

/**
 * FSRS-6 scheduling math (spec mastery-scheduling §2). Every formula here is a
 * verbatim port of py-fsrs `fsrs/scheduler.py` (source-verified 2026-08-29):
 * power-law forgetting, stability/difficulty updates, and the integer-day
 * interval inverse. Fuzzing is deliberately not ported: projections must be
 * deterministic.
 */
object FsrsScheduleMath {
    /** py-fsrs DEFAULT_PARAMETERS, source-verified. Index 20 is the decay. */
    val DEFAULT_PARAMETERS: DoubleArray = doubleArrayOf(
        0.212,
        1.2931,
        2.3065,
        8.2956,
        6.4133,
        0.8334,
        3.0194,
        0.001,
        1.8722,
        0.1666,
        0.796,
        1.4835,
        0.0614,
        0.2629,
        1.6483,
        0.6014,
        1.8729,
        0.5425,
        0.0912,
        0.0658,
        0.1542,
    )

    const val PARAMETER_COUNT = 21
    const val STABILITY_MIN = 0.001
    const val STABILITY_MAX = 36_500.0
    const val DIFFICULTY_MIN = 1.0
    const val DIFFICULTY_MAX = 10.0
    const val INITIAL_STABILITY_MAX = 100.0
    const val MAXIMUM_INTERVAL_DAYS = 36_500

    fun requireValid(parameters: DoubleArray) {
        require(parameters.size == PARAMETER_COUNT) {
            "FSRS-6 requires exactly $PARAMETER_COUNT parameters"
        }
        require(parameters.all { it.isFinite() }) { "FSRS parameters must be finite" }
    }

    /** R(t,S) = (1 + FACTOR·t/S)^(−w20) with FACTOR = 0.9^(1/DECAY) − 1. */
    fun retention(elapsedDays: Double, stabilityDays: Double, decay: Double = -DEFAULT_PARAMETERS[20]): Double {
        require(stabilityDays > 0.0) { "Stability must be positive" }
        val factor = factor(decay)
        val clampedElapsed = elapsedDays.coerceAtLeast(0.0)
        return (1.0 + factor * clampedElapsed / stabilityDays).pow(decay).coerceIn(0.0, 1.0)
    }

    fun factor(decay: Double = -DEFAULT_PARAMETERS[20]): Double = 0.9.pow(1.0 / decay) - 1.0

    /**
     * Interval inverse I(r*, S) = (S/FACTOR)·(r*^(1/DECAY) − 1), rounded to a
     * whole day with the py-fsrs floors (at least one day, at most the
     * maximum interval).
     */
    fun intervalDays(stabilityDays: Double, desiredRetention: Double): Int {
        require(stabilityDays > 0.0) { "Stability must be positive" }
        require(desiredRetention in 0.0..1.0 && desiredRetention != 0.0 && desiredRetention != 1.0) {
            "Desired retention must be strictly between zero and one"
        }
        val decay = -DEFAULT_PARAMETERS[20]
        val raw = (stabilityDays / factor(decay)) * (desiredRetention.pow(1.0 / decay) - 1.0)
        return raw.toFixedDays()
    }

    private fun Double.toFixedDays(): Int {
        val whole = kotlin.math.round(this).toInt()
        return whole.coerceIn(1, MAXIMUM_INTERVAL_DAYS)
    }

    fun initialStability(rating: FsrsRating, parameters: DoubleArray = DEFAULT_PARAMETERS): Double =
        parameters[rating.ordinal].coerceIn(STABILITY_MIN, INITIAL_STABILITY_MAX)

    /** D0(G) = w4 − e^(w5·(G−1)) + 1; py-fsrs clamps only for the initial state. */
    fun initialDifficulty(rating: FsrsRating, parameters: DoubleArray = DEFAULT_PARAMETERS): Double =
        parameters[4] - exp(parameters[5] * (rating.ordinal)) + 1.0

    fun clampDifficulty(difficulty: Double): Double = difficulty.coerceIn(DIFFICULTY_MIN, DIFFICULTY_MAX)

    fun clampStability(stability: Double): Double = stability.coerceIn(STABILITY_MIN, STABILITY_MAX)

    /**
     * Same-day reviews: S' = S·e^(w17·(G−3+w18))·S^(−w19); for G≥2 the
     * multiplier is floored at 1.0 (py-fsrs `_short_term_stability`).
     */
    fun shortTermStability(stability: Double, rating: FsrsRating, parameters: DoubleArray = DEFAULT_PARAMETERS): Double {
        val multiplier = exp(parameters[17] * (rating.ordinal - 3 + parameters[18])) *
            stability.pow(-parameters[19])
        val floored = if (rating == FsrsRating.AGAIN) multiplier else multiplier.coerceAtLeast(1.0)
        return clampStability(stability * floored)
    }

    /**
     * Cross-day success: S' = S·(1 + e^w8·(11−D)·S^(−w9)·(e^((1−R)·w10)−1)·HP·EB).
     */
    fun nextRecallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: FsrsRating,
        parameters: DoubleArray = DEFAULT_PARAMETERS,
    ): Double {
        val hardPenalty = if (rating == FsrsRating.HARD) parameters[15] else 1.0
        val easyBonus = if (rating == FsrsRating.EASY) parameters[16] else 1.0
        val grown = stability * (
            1.0 +
                exp(parameters[8]) *
                (11.0 - difficulty) *
                stability.pow(-parameters[9]) *
                (exp((1.0 - retrievability) * parameters[10]) - 1.0) *
                hardPenalty *
                easyBonus
            )
        return clampStability(grown)
    }

    /** Cross-day lapse: min(long-term, S/e^(w17·w18)). */
    fun nextForgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        parameters: DoubleArray = DEFAULT_PARAMETERS,
    ): Double {
        val longTerm = parameters[11] *
            difficulty.pow(-parameters[12]) *
            ((stability + 1.0).pow(parameters[13]) - 1.0) *
            exp((1.0 - retrievability) * parameters[14])
        val shortTerm = stability / exp(parameters[17] * parameters[18])
        return clampStability(minOf(longTerm, shortTerm))
    }

    /**
     * D' = w7·D0(Easy, unclamped) + (1−w7)·(D + (10−D)·(−w6·(G−3))/9),
     * clamped to 1..10 (py-fsrs `_next_difficulty`).
     */
    fun nextDifficulty(difficulty: Double, rating: FsrsRating, parameters: DoubleArray = DEFAULT_PARAMETERS): Double {
        val meanReversionTarget = initialDifficulty(FsrsRating.EASY, parameters)
        val delta = -(parameters[6] * (rating.ordinal - 3))
        val linearDamped = difficulty + (10.0 - difficulty) * delta / 9.0
        return clampDifficulty(parameters[7] * meanReversionTarget + (1.0 - parameters[7]) * linearDamped)
    }
}

enum class FsrsRating {
    AGAIN,
    HARD,
    GOOD,
    EASY,
}

/**
 * Evidence → FSRS rating mapping (spec §2.5). Keyed on (reason, weight) so
 * legacy reasons that serve multiple sources keep their distinct grades.
 *
 * Two consumers, two functions:
 * - [schedulingRatingFor] drives the FSRS update (LearningProjector). It
 *   applies two confidence refinements grounded in the research record:
 *   subjective "very easy" reports cap at Good (Dunlosky & Rawson 2012:
 *   86% of students over-estimate their learning, inflating stability), and
 *   a discounted independent-correct weight (attention/RT discounts) drops
 *   to Hard because a distracted correct answer is plausibly a guess.
 * - [reportedRatingFor] keeps the learner's own key verbatim for the
 *   review_log accounting column, so the honest report survives even when
 *   scheduling is conservative.
 */
object FsrsEvidenceRatingMapper {
    /** Four-button self-rating evidence weights (new review-UI channel). */
    const val RATING_EASY_WEIGHT = 0.9
    const val RATING_GOOD_WEIGHT = 0.8
    const val RATING_HARD_WEIGHT = 0.7
    const val RATING_AGAIN_WEIGHT = 1.0

    /**
     * Independent-correct weight below this ceiling grades as Hard: it means
     * a discount (attention/RT guess-slip correction) already touched the
     * evidence, so the answer is not a clean recall.
     */
    const val LOW_CONFIDENCE_CORRECT_CEILING = 0.85

    fun schedulingRatingFor(evidenceReason: LearningEvidenceReason, weight: Double): FsrsRating =
        when (evidenceReason) {
            LearningEvidenceReason.INDEPENDENT_CORRECT ->
                if (weight < LOW_CONFIDENCE_CORRECT_CEILING - WEIGHT_EPSILON) {
                    FsrsRating.HARD
                } else {
                    FsrsRating.GOOD
                }
            LearningEvidenceReason.SELF_REPORTED_RECALL ->
                // Subjective reports never earn the Easy bonus: stability
                // caps at Good while the reported key stays Easy in the log.
                if (weight >= RATING_HARD_WEIGHT - WEIGHT_EPSILON) {
                    FsrsRating.GOOD
                } else {
                    // Legacy detail-page self-report (0.35) also stays at
                    // grade 3 per spec §2.5.
                    FsrsRating.GOOD
                }
            else -> reportedRatingFor(evidenceReason, weight)
        }

    fun reportedRatingFor(evidenceReason: LearningEvidenceReason, weight: Double): FsrsRating =
        when (evidenceReason) {
            LearningEvidenceReason.INDEPENDENT_CORRECT -> FsrsRating.GOOD
            LearningEvidenceReason.CORRECT_AFTER_HINT -> FsrsRating.GOOD
            // Retry-correct covers both hint-channel (0.6) and legacy effort
            // self-report (0.25); only the effort tier is conservative enough
            // for Hard.
            LearningEvidenceReason.CORRECT_ON_RETRY -> if (weight <= 0.25 + WEIGHT_EPSILON) {
                FsrsRating.HARD
            } else {
                FsrsRating.GOOD
            }
            LearningEvidenceReason.SELF_REPORTED_RECALL -> when {
                weight >= RATING_EASY_WEIGHT - WEIGHT_EPSILON -> FsrsRating.EASY
                weight >= RATING_GOOD_WEIGHT - WEIGHT_EPSILON -> FsrsRating.GOOD
                weight >= RATING_HARD_WEIGHT - WEIGHT_EPSILON -> FsrsRating.HARD
                else -> FsrsRating.GOOD
            }
            LearningEvidenceReason.SELF_REPORTED_STUCK -> FsrsRating.AGAIN
            LearningEvidenceReason.VISUAL_INTERACTION_SATISFIED -> FsrsRating.HARD
            LearningEvidenceReason.VISUAL_INTERACTION_VIOLATED -> FsrsRating.AGAIN
            LearningEvidenceReason.INDEPENDENT_INCORRECT -> FsrsRating.AGAIN
            LearningEvidenceReason.INCORRECT_AFTER_HINT -> FsrsRating.AGAIN
            LearningEvidenceReason.INCORRECT_ON_RETRY -> FsrsRating.AGAIN
            LearningEvidenceReason.INCORRECT_AFTER_REVEAL -> FsrsRating.AGAIN
            LearningEvidenceReason.ANSWER_REVEALED -> FsrsRating.AGAIN
        }

    private const val WEIGHT_EPSILON = 1e-6
}
