package com.tingyun.smartmistakebook.core.domain

import kotlin.math.pow

/**
 * CMRR-style desired-retention recommendation (研究 2026-09-09 §5; the objective
 * follows fsrs-rs `simulation.rs`'s `CMRRTargetFn`: minimize cost per memorized
 * card). For each candidate retention target it simulates every card forward
 * over a fixed horizon, accumulating the expected retrievability ("memorized")
 * and the number of reviews ("cost"), and returns the target with the lowest
 * cost per memorized card.
 *
 * This is a **simplified** simulator, not a port of fsrs-rs's deck scheduler:
 * it models only the long-run review stream of the cards the learner already
 * has (no new-card introduction, no learning steps, no per-rating cost table).
 * It therefore reports an *experimental* recommendation, and returns null
 * whenever the data is too thin to be meaningful.
 *
 * 消灭的失败：此前只有一个手动的保持率滑杆，学生没有任何基于自己真实记忆
 * 状态的取值参考。
 */
object OptimalRetention {
    /** Fewer cards than this and the simulation is noise. */
    const val MIN_CARDS = 20
    const val HORIZON_DAYS = 365
    const val MIN_DESIRED_RETENTION = 0.70
    const val MAX_DESIRED_RETENTION = 0.95
    const val RETENTION_STEP = 0.01

    /** One card's current memory state; retrievability is derived from it. */
    data class Card(val stabilityDays: Double, val difficulty: Double) {
        init {
            require(stabilityDays > 0.0) { "Card stability must be positive" }
            require(difficulty.isFinite()) { "Card difficulty must be finite" }
        }
    }

    data class Point(
        val desiredRetention: Double,
        val memorized: Double,
        val reviewCount: Int,
    ) {
        /** The CMRR objective: lower is better. */
        val costPerMemorized: Double
            get() = if (memorized <= 0.0) Double.POSITIVE_INFINITY else reviewCount / memorized
    }

    data class Recommendation(
        val desiredRetention: Double,
        val curve: List<Point>,
    )

    /**
     * @return the recommendation and the full simulated curve, or null when
     *         fewer than [MIN_CARDS] cards carry a memory state.
     */
    fun recommend(
        cards: List<Card>,
        parameters: DoubleArray = FsrsScheduleMath.DEFAULT_PARAMETERS,
        horizonDays: Int = HORIZON_DAYS,
    ): Recommendation? {
        require(horizonDays > 0) { "Simulation horizon must be positive" }
        FsrsScheduleMath.requireValid(parameters)
        if (cards.size < MIN_CARDS) return null
        val curve = generateSequence(MIN_DESIRED_RETENTION) { it + RETENTION_STEP }
            .takeWhile { it <= MAX_DESIRED_RETENTION + 1e-9 }
            .map { retention -> simulate(cards, parameters, retention, horizonDays) }
            .toList()
        val best = curve.minByOrNull(Point::costPerMemorized) ?: return null
        if (!best.costPerMemorized.isFinite()) return null
        return Recommendation(best.desiredRetention, curve)
    }

    private fun simulate(
        cards: List<Card>,
        parameters: DoubleArray,
        desiredRetention: Double,
        horizonDays: Int,
    ): Point {
        var memorized = 0.0
        var reviews = 0
        val horizon = horizonDays.toDouble()
        cards.forEach { card ->
            var stability = card.stabilityDays
            var elapsed = 0.0
            while (elapsed < horizon) {
                val interval = FsrsScheduleMath
                    .intervalDays(stability, desiredRetention)
                    .toDouble()
                    .coerceAtLeast(1.0)
                val span = minOf(interval, horizon - elapsed)
                memorized += averageRetention(span, stability) * span
                reviews += 1
                val retrievabilityAtReview = FsrsScheduleMath.retention(interval, stability)
                stability = FsrsScheduleMath.nextRecallStability(
                    difficulty = card.difficulty,
                    stability = stability,
                    retrievability = retrievabilityAtReview,
                    rating = FsrsRating.GOOD,
                    parameters = parameters,
                )
                elapsed += interval
            }
        }
        return Point(desiredRetention, memorized, reviews)
    }

    /**
     * Mean of R(t,S) = (1 + FACTOR·t/S)^DECAY over `[0, span]`, from the closed
     * form of the integral (DECAY + 1 is never zero for the FSRS-6 default).
     */
    private fun averageRetention(span: Double, stabilityDays: Double): Double {
        if (span <= 0.0) return 0.0
        val decay = -FsrsScheduleMath.DEFAULT_PARAMETERS[20]
        val factor = FsrsScheduleMath.factor(decay)
        val exponent = decay + 1.0
        val scaled = 1.0 + factor * span / stabilityDays
        val integral = (scaled.pow(exponent) - 1.0) * stabilityDays / (factor * exponent)
        return (integral / span).coerceIn(0.0, 1.0)
    }
}
