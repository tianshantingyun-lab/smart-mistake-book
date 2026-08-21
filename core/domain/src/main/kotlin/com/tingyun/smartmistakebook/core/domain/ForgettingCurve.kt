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
