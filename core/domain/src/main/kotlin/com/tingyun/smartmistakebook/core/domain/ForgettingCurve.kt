package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import kotlin.math.exp
import kotlin.math.ln

fun interface EpochMillisClock {
    fun nowEpochMillis(): Long
}

object SystemEpochMillisClock : EpochMillisClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

enum class ClockAnomaly {
    NONE,
    TIME_ROLLBACK,
}

data class RetentionEstimate(
    val probability: Double,
    val clockAnomaly: ClockAnomaly,
)

/**
 * Exponential forgetting curve where stability is defined as the interval at which
 * recall reaches [stabilityRetention] (90% by default).
 */
class ForgettingCurve(
    private val clock: EpochMillisClock = SystemEpochMillisClock,
    private val stabilityRetention: Double = DEFAULT_STABILITY_RETENTION,
) {
    init {
        require(stabilityRetention in 0.0..1.0 && stabilityRetention != 0.0 && stabilityRetention != 1.0) {
            "Stability retention must be strictly between zero and one"
        }
    }

    fun retentionNow(state: ProblemMemoryState): Double = retentionAt(
        state = state,
        atEpochMillis = clock.nowEpochMillis(),
    )

    fun retentionAt(state: ProblemMemoryState, atEpochMillis: Long): Double {
        return estimateAt(state, atEpochMillis).probability
    }

    fun estimateAt(state: ProblemMemoryState, atEpochMillis: Long): RetentionEstimate {
        require(atEpochMillis >= 0) { "Evaluation time must not be negative" }
        val rollback = atEpochMillis < state.lastReviewedAtEpochMillis
        val elapsedMillis = if (rollback) 0 else atEpochMillis - state.lastReviewedAtEpochMillis
        val elapsedDays = elapsedMillis.toDouble() / DAY_MILLIS
        return RetentionEstimate(
            probability = exp(ln(stabilityRetention) * elapsedDays / state.stabilityDays)
                .coerceIn(0.0, 1.0),
            clockAnomaly = if (rollback) ClockAnomaly.TIME_ROLLBACK else ClockAnomaly.NONE,
        )
    }

    fun reviewAtTargetRetention(
        reviewedAtEpochMillis: Long,
        stabilityDays: Double,
        targetRetention: Double = stabilityRetention,
    ): Long {
        require(reviewedAtEpochMillis >= 0) { "Review time must not be negative" }
        require(stabilityDays.isFinite() && stabilityDays > 0.0) {
            "Stability must be positive"
        }
        require(targetRetention in 0.0..1.0 && targetRetention != 0.0 && targetRetention != 1.0) {
            "Target retention must be strictly between zero and one"
        }
        val intervalDays = stabilityDays * ln(targetRetention) / ln(stabilityRetention)
        val intervalMillis = (intervalDays * DAY_MILLIS).toLong().coerceAtLeast(0)
        return if (Long.MAX_VALUE - reviewedAtEpochMillis < intervalMillis) {
            Long.MAX_VALUE
        } else {
            reviewedAtEpochMillis + intervalMillis
        }
    }

    companion object {
        const val VERSION = "forgetting-curve-v2"
        const val DEFAULT_STABILITY_RETENTION = 0.9
        private const val DAY_MILLIS = 86_400_000.0
    }
}

/**
 * Half-Life Regression (HLR) predictor.
 *
 * Model: P(recall at Δ) = 2^(-Δ/h)
 * where h (half-life) is predicted by: log2(h) = θᵀx
 *
 * This is a trainable alternative to the exponential forgetting curve.
 * Reference: Settles & Meeder, ACL 2016
 */
class HalfLifeRegressionPredictor(
    private val parameters: HLRParameters = HLRParameters.DEFAULT,
) {
    fun predictRecall(
        elapsedDays: Double,
        features: HLRFeatures,
    ): Double {
        val logHalfLife = parameters.bias +
            parameters.weightIndependentCorrect * features.independentCorrectCount +
            parameters.weightIndependentFailure * features.independentFailureCount +
            parameters.weightAssistedCorrect * features.assistedCorrectCount +
            parameters.weightReveal * features.revealCount +
            parameters.weightPreviousLag * features.previousLagHours +
            parameters.weightDifficulty * features.difficulty +
            parameters.weightLatency * features.latencyPercentile

        val halfLifeDays = 2.0.pow(logHalfLife).coerceIn(MIN_HALF_LIFE_DAYS, MAX_HALF_LIFE_DAYS)
        return (0.5).pow(elapsedDays / halfLifeDays).coerceIn(0.0, 1.0)
    }

    fun nextReviewDay(
        features: HLRFeatures,
        targetRecall: Double = 0.9,
    ): Double {
        val logHalfLife = parameters.bias +
            parameters.weightIndependentCorrect * features.independentCorrectCount +
            parameters.weightIndependentFailure * features.independentFailureCount +
            parameters.weightAssistedCorrect * features.assistedCorrectCount +
            parameters.weightReveal * features.revealCount +
            parameters.weightPreviousLag * features.previousLagHours +
            parameters.weightDifficulty * features.difficulty +
            parameters.weightLatency * features.latencyPercentile

        val halfLifeDays = 2.0.pow(logHalfLife).coerceIn(MIN_HALF_LIFE_DAYS, MAX_HALF_LIFE_DAYS)
        return halfLifeDays * ln(2.0) / ln(1.0 / targetRecall)
    }

    companion object {
        const val MIN_HALF_LIFE_DAYS = 0.01
        const val MAX_HALF_LIFE_DAYS = 3650.0
    }
}

data class HLRFeatures(
    val independentCorrectCount: Int,
    val independentFailureCount: Int,
    val assistedCorrectCount: Int,
    val revealCount: Int,
    val previousLagHours: Double,
    val difficulty: Double,
    val latencyPercentile: Double,
)

data class HLRParameters(
    val bias: Double,
    val weightIndependentCorrect: Double,
    val weightIndependentFailure: Double,
    val weightAssistedCorrect: Double,
    val weightReveal: Double,
    val weightPreviousLag: Double,
    val weightDifficulty: Double,
    val weightLatency: Double,
) {
    companion object {
        val DEFAULT = HLRParameters(
            bias = 3.5,
            weightIndependentCorrect = 0.4,
            weightIndependentFailure = -0.3,
            weightAssistedCorrect = 0.2,
            weightReveal = -0.1,
            weightPreviousLag = 0.15,
            weightDifficulty = -0.1,
            weightLatency = -0.05,
        )
    }
}
