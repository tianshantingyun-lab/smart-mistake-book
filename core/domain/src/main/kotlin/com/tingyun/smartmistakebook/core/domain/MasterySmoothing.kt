package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import kotlin.math.pow

/**
 * Weakness-input smoothing (spec mastery-scheduling §2.18): the planner reads
 * the conservative mastery score through a seven-day half-life EMA over the
 * knowledge node's independent-correct observations, so a single strong or
 * weak day cannot swing the review queue.
 *
 * The EMA is the recency-weighted share of observations whose calibration
 * support still stands: each observation decays by 2^(−age/7d), unsupported
 * ones contribute to the denominator only. The reported score is a half-half
 * blend of the point estimate's conservative bound and that EMA.
 */
object MasterySmoothing {
    const val HALF_LIFE_DAYS = 7.0
    const val POINT_ESTIMATE_WEIGHT = 0.5

    fun smoothedMasteryScore(state: KnowledgeMasteryState, atEpochMillis: Long): Double {
        val conservative = state.conservativeMasteryScore
        val observations = state.independentCorrectObservations
        if (observations.isEmpty()) return conservative
        var totalWeight = 0.0
        var supportedWeight = 0.0
        for (observation in observations) {
            val ageDays = (atEpochMillis - observation.occurredAtEpochMillis)
                .coerceAtLeast(0)
                .toDouble() / DAY_MILLIS
            val decayed = observation.evidenceWeight * 2.0.pow(-ageDays / HALF_LIFE_DAYS)
            totalWeight += decayed
            if (observation.calibrationSupportAt(atEpochMillis) == CalibrationSupport.SUPPORTED) {
                supportedWeight += decayed
            }
        }
        if (totalWeight <= 0.0) return conservative
        val ema = (supportedWeight / totalWeight).coerceIn(0.0, 1.0)
        return (POINT_ESTIMATE_WEIGHT * conservative + (1.0 - POINT_ESTIMATE_WEIGHT) * ema)
            .coerceIn(0.0, 1.0)
    }

    private const val DAY_MILLIS = 86_400_000.0
}
