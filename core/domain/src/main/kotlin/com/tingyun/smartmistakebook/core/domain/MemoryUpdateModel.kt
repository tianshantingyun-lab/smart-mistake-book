package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState

/** Result of applying one evidence event to a card's memory state. */
data class MemoryUpdateResult(
    val stabilityDays: Double,
    val difficulty: Double,
    val nextReviewAtEpochMillis: Long,
    /**
     * True when the update is a same-day (short-term) or otherwise sub-day
     * refresh: the next review lands on the legacy ten-minute cadence instead
     * of the interval inverse.
     */
    val shortTermReview: Boolean,
)

/**
 * Pluggable card-memory update mathematics (spec mastery-scheduling §2.20
 * kill switch): the FSRS-6 model and the audited legacy exponential model can
 * be swapped without touching the projector or the ledger.
 */
interface MemoryUpdateModel {
    val algorithmId: String

    fun updateMemory(
        previous: ProblemMemoryState?,
        rating: FsrsRating,
        outcome: ProblemMemoryOutcome,
        weight: Double,
        occurredAtEpochMillis: Long,
        effectiveAttemptAtEpochMillis: Long,
        /**
         * Calendar-day delta between this review and the previous one (learner-local epoch day
         * difference), not a 24-hour wall-clock floor. FSRS uses it as delta_t; a cross-midnight
         * review under 24 hours apart is still a new day.
         */
        elapsedCalendarDays: Double,
    ): MemoryUpdateResult
}

/**
 * FSRS-6 updates (spec §2.2-2.4, §2.15): power-law retrievability, R-dependent
 * stability, mean-reverting difficulty on the 1..10 domain, same-day reviews
 * on the short-term branch, and whole-day interval scheduling.
 */
class FsrsMemoryUpdateModel(
    private val parameters: DoubleArray = FsrsScheduleMath.DEFAULT_PARAMETERS,
    val desiredRetention: Double = DEFAULT_DESIRED_RETENTION,
) : MemoryUpdateModel {

    override val algorithmId: String = ALGORITHM_ID

    /**
     * 这组参数的衰减（`-w20`），**与 [desiredRetention] 同级**：两者都是"要按同一组参数
     * 算间隔"所必需的东西，所以排期侧任何拿这个模型算间隔的地方都必须用这里的值，
     * 而不是 `FsrsScheduleMath` 的出厂默认（F-01：那正是把拟合参数冻住的那一步）。
     */
    internal val decay: Double get() = -parameters[20]

    init {
        FsrsScheduleMath.requireValid(parameters)
        require(desiredRetention in 0.7..0.97) {
            "Desired retention must be within the supported 0.7..0.97 range"
        }
    }

    override fun updateMemory(
        previous: ProblemMemoryState?,
        rating: FsrsRating,
        outcome: ProblemMemoryOutcome,
        weight: Double,
        occurredAtEpochMillis: Long,
        effectiveAttemptAtEpochMillis: Long,
        elapsedCalendarDays: Double,
    ): MemoryUpdateResult {
        val stability: Double
        val difficulty: Double
        if (previous == null) {
            stability = FsrsScheduleMath.initialStability(rating, parameters)
            difficulty = FsrsScheduleMath.clampDifficulty(
                FsrsScheduleMath.initialDifficulty(rating, parameters),
            )
        } else {
            val elapsedDays = elapsedCalendarDays.coerceAtLeast(0.0)
            stability = if (elapsedDays < 1.0) {
                FsrsScheduleMath.shortTermStability(previous.stabilityDays, rating, parameters)
            } else if (rating == FsrsRating.AGAIN) {
                FsrsScheduleMath.nextForgetStability(
                    difficulty = previous.difficulty,
                    stability = previous.stabilityDays,
                    retrievability = FsrsScheduleMath.retention(elapsedDays, previous.stabilityDays, decay),
                    parameters = parameters,
                )
            } else {
                FsrsScheduleMath.nextRecallStability(
                    difficulty = previous.difficulty,
                    stability = previous.stabilityDays,
                    retrievability = FsrsScheduleMath.retention(elapsedDays, previous.stabilityDays, decay),
                    rating = rating,
                    parameters = parameters,
                )
            }
            // Difficulty updates on every review, including same-day ones.
            difficulty = FsrsScheduleMath.nextDifficulty(previous.difficulty, rating, parameters)
        }
        val intervalDays = FsrsScheduleMath
            .intervalDays(stability, desiredRetention, decay)
            .coerceAtLeast(1)
        val nextReviewAt = addDays(effectiveAttemptAtEpochMillis, intervalDays.toLong())
        return MemoryUpdateResult(
            stabilityDays = stability,
            difficulty = difficulty,
            nextReviewAtEpochMillis = nextReviewAt,
            // Learning steps are disabled (spec §2.15): every interval is at
            // least one whole day even for same-day reviews.
            shortTermReview = false,
        )
    }

    private fun addDays(epochMillis: Long, days: Long): Long {
        val increment = days * DAY_MILLIS.toLong()
        return if (Long.MAX_VALUE - epochMillis < increment) Long.MAX_VALUE else epochMillis + increment
    }

    companion object {
        const val ALGORITHM_ID = "fsrs6"
        const val DEFAULT_DESIRED_RETENTION = 0.9
        private const val DAY_MILLIS = 86_400_000.0
    }
}

/**
 * The audited pre-FSRS projection mathematics (projection-v4), preserved as
 * the kill-switch baseline. Difficulty updates are rescaled from the old
 * 0..1 constants onto the 1..10 domain without changing their shape.
 */
class LegacyExponentialMemoryUpdateModel(
    private val forgettingCurve: ForgettingCurve = ForgettingCurve(),
) : MemoryUpdateModel {

    override val algorithmId: String = ALGORITHM_ID

    override fun updateMemory(
        previous: ProblemMemoryState?,
        rating: FsrsRating,
        outcome: ProblemMemoryOutcome,
        weight: Double,
        occurredAtEpochMillis: Long,
        effectiveAttemptAtEpochMillis: Long,
        elapsedCalendarDays: Double,
    ): MemoryUpdateResult {
        val currentStability = previous?.stabilityDays ?: INITIAL_STABILITY_DAYS
        val currentDifficulty = previous?.difficulty ?: INITIAL_DIFFICULTY
        val stability = when (outcome) {
            ProblemMemoryOutcome.INDEPENDENT_RECALL ->
                currentStability * (1.0 + 1.6 * weight) + 0.25 * weight
            ProblemMemoryOutcome.ASSISTED_RECALL ->
                currentStability * (1.0 + 0.6 * weight) + 0.1 * weight
            ProblemMemoryOutcome.RETRIEVAL_FAILURE -> currentStability * (0.7 - 0.25 * weight)
            ProblemMemoryOutcome.ANSWER_REVEALED -> currentStability * ANSWER_REVEAL_STABILITY_FACTOR
        }.coerceIn(MIN_STABILITY_DAYS, MAX_STABILITY_DAYS)
        val difficulty01 = when (outcome) {
            ProblemMemoryOutcome.INDEPENDENT_RECALL -> to01(currentDifficulty) - 0.08 * weight
            ProblemMemoryOutcome.ASSISTED_RECALL -> to01(currentDifficulty) - 0.03 * weight
            ProblemMemoryOutcome.RETRIEVAL_FAILURE -> to01(currentDifficulty) + 0.12 * weight
            ProblemMemoryOutcome.ANSWER_REVEALED -> to01(currentDifficulty) + 0.12
        }.coerceIn(0.0, 1.0)
        val shortTermReview = outcome == ProblemMemoryOutcome.ANSWER_REVEALED ||
            occurredAtEpochMillis < effectiveAttemptAtEpochMillis
        val nextReviewAt = if (shortTermReview) {
            val increment = SHORT_REVIEW_MILLIS
            if (Long.MAX_VALUE - effectiveAttemptAtEpochMillis < increment) {
                Long.MAX_VALUE
            } else {
                effectiveAttemptAtEpochMillis + increment
            }
        } else {
            forgettingCurve.reviewAtTargetRetention(effectiveAttemptAtEpochMillis, stability)
        }
        return MemoryUpdateResult(
            stabilityDays = stability,
            difficulty = from01(difficulty01),
            nextReviewAtEpochMillis = nextReviewAt,
            shortTermReview = shortTermReview,
        )
    }

    private fun to01(difficulty: Double): Double = (difficulty - 1.0) / 9.0

    private fun from01(difficulty01: Double): Double = 1.0 + 9.0 * difficulty01

    companion object {
        const val ALGORITHM_ID = "legacy-exponential"
        private const val INITIAL_STABILITY_DAYS = 0.5
        private const val MIN_STABILITY_DAYS = 0.25
        private const val MAX_STABILITY_DAYS = 3_650.0
        private const val INITIAL_DIFFICULTY = 5.5
        private const val ANSWER_REVEAL_STABILITY_FACTOR = 0.45
        private const val SHORT_REVIEW_MILLIS = 10L * 60_000L
    }
}
