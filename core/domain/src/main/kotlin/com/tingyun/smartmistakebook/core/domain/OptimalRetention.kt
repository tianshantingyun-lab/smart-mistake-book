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
 * It models the lapse force (研究 2026-09-10): each review succeeds with
 * probability R; on failure the expected cost includes [LAPSE_RECOVERY_REVIEWS]
 * relearning review(s) and the expected stability averages the grown and
 * collapsed branches. Without this force the cost/memorized curve is monotone
 * decreasing and the optimum degenerates to the retention floor, which would
 * push every learner toward the highest-forgetting regime.
 *
 * It therefore reports an *experimental* recommendation, and returns null
 * whenever the data is too thin to be meaningful. Card sets dominated by
 * still-learning items (very low stability) can still read at the floor — the
 * relearning force for such cards is under-modelled — so the readout is
 * trusted most for learners with a graduated review deck.
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

    /**
     * Extra expected reviews a forgotten card costs to recover (the relearning
     * encounter after an Again, mirroring Anki's default single relearning
     * step). This is the cost that makes low retention expensive; combined with
     * the stability collapse on lapse it restores the CMRR interior minimum.
     */
    const val LAPSE_RECOVERY_REVIEWS = 1

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
        val reviewCount: Double,
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
        var reviews = 0.0
        val horizon = horizonDays.toDouble()
        // 衰减取自**这一组参数**，与 `simulate` 已经在用的 `parameters` 同源（F-01 的第 ④ 处：
        // 同一个文件里 `nextRecallStability` 等已经正确转发了 `parameters`，只有这里没有）。
        val decay = -parameters[20]
        cards.forEach { card ->
            var stability = card.stabilityDays
            var elapsed = 0.0
            while (elapsed < horizon) {
                val interval = FsrsScheduleMath
                    .intervalDays(stability, desiredRetention, decay)
                    .toDouble()
                    .coerceAtLeast(1.0)
                val span = minOf(interval, horizon - elapsed)
                memorized += averageRetention(span, stability, decay) * span
                val retrievabilityAtReview = FsrsScheduleMath.retention(interval, stability, decay)
                // Lapse force (fsrs-rs CMRR): a review succeeds only with
                // probability R. The expected cost of the cycle includes the
                // relearning review(s) after a failure, and the expected next
                // stability averages the grown (Good) and collapsed (Again)
                // branches. Without this force the cost/memorized curve is
                // monotone decreasing and the "optimum" degenerates to the
                // lowest supported retention — the relearning arm that creates
                // the CMRR interior minimum is exactly what is missing.
                val failProbability = (1.0 - retrievabilityAtReview).coerceIn(0.0, 1.0)
                reviews += 1.0 + failProbability * LAPSE_RECOVERY_REVIEWS
                val grownStability = FsrsScheduleMath.nextRecallStability(
                    difficulty = card.difficulty,
                    stability = stability,
                    retrievability = retrievabilityAtReview,
                    rating = FsrsRating.GOOD,
                    parameters = parameters,
                )
                val lapseStability = FsrsScheduleMath.nextForgetStability(
                    difficulty = card.difficulty,
                    stability = stability,
                    retrievability = retrievabilityAtReview,
                    parameters = parameters,
                )
                stability = failProbability * lapseStability +
                    (1.0 - failProbability) * grownStability
                elapsed += interval
            }
        }
        return Point(desiredRetention, memorized, reviews)
    }

    /**
     * Mean of R(t,S) = (1 + FACTOR·t/S)^DECAY over `[0, span]`, from the closed
     * form of the integral (DECAY + 1 is never zero for the FSRS-6 default).
     *
     * [decay] 是**必填**的：这个函数的整条推导都带着它（连积分指数 `decay + 1` 都含它），
     * 留一个默认值只会让下一个调用方再次悄悄用默认衰减——F-01 的四处冻结里，这一处原先就是
     * 这样。调用方一律传 `-parameters[20]`。
     */
    private fun averageRetention(span: Double, stabilityDays: Double, decay: Double): Double {
        if (span <= 0.0) return 0.0
        val factor = FsrsScheduleMath.factor(decay)
        val exponent = decay + 1.0
        val scaled = 1.0 + factor * span / stabilityDays
        val integral = (scaled.pow(exponent) - 1.0) * stabilityDays / (factor * exponent)
        return (integral / span).coerceIn(0.0, 1.0)
    }
}
