package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

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
 * Forgetting curve with two coexisting algorithms (spec mastery-scheduling
 * §2.20 kill switch): the audited exponential baseline and the FSRS-6 power
 * law. Method signatures are unchanged; callers choose the algorithm once.
 */
class ForgettingCurve(
    private val clock: EpochMillisClock = SystemEpochMillisClock,
    private val stabilityRetention: Double = DEFAULT_STABILITY_RETENTION,
    private val algorithm: ForgettingCurveAlgorithm = ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL,
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
        val probability = when (algorithm) {
            ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL ->
                exp(ln(stabilityRetention) * elapsedMillis / DAY_MILLIS / state.stabilityDays)
            ForgettingCurveAlgorithm.FSRS6_POWER_LAW -> {
                // py-fsrs floors elapsed wall-clock time to whole days (the
                // scheduling delta_t itself uses learner-local calendar days).
                val elapsedDays = floor(elapsedMillis.toDouble() / DAY_MILLIS).coerceAtLeast(0.0)
                FsrsScheduleMath.retention(elapsedDays, state.stabilityDays)
            }
        }.coerceIn(0.0, 1.0)
        return RetentionEstimate(
            probability = probability,
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
        val intervalMillis = when (algorithm) {
            ForgettingCurveAlgorithm.LEGACY_EXPONENTIAL -> {
                val intervalDays = stabilityDays * ln(targetRetention) / ln(stabilityRetention)
                (intervalDays * DAY_MILLIS).toLong().coerceAtLeast(0)
            }
            ForgettingCurveAlgorithm.FSRS6_POWER_LAW -> {
                val intervalDays = FsrsScheduleMath.intervalDays(stabilityDays, targetRetention)
                intervalDays * DAY_MILLIS.toLong()
            }
        }
        return if (Long.MAX_VALUE - reviewedAtEpochMillis < intervalMillis) {
            Long.MAX_VALUE
        } else {
            reviewedAtEpochMillis + intervalMillis
        }
    }

    companion object {
        const val VERSION = "forgetting-curve-v3"
        const val DEFAULT_STABILITY_RETENTION = 0.9
        private const val DAY_MILLIS = 86_400_000.0
    }
}

enum class ForgettingCurveAlgorithm {
    LEGACY_EXPONENTIAL,
    FSRS6_POWER_LAW,
}
