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
 * The EMA is the decay-weighted share of the node's evidence whose calibration
 * support still stands. The numerator decays each supported observation by
 * 2^(−age/7d) while the denominator keeps the raw evidence weights, so age
 * cannot cancel out: stale evidence pulls the smoothed score down even when
 * every observation is still nominally supported. (Before 2026-09-09 both the
 * numerator and the denominator decayed, which made the ratio identically 1
 * for uniformly-supported nodes and silently halved every weakness signal —
 * see the audit note on `MasterySmoothing`.)
 */
object MasterySmoothing {
    const val HALF_LIFE_DAYS = 7.0
    const val POINT_ESTIMATE_WEIGHT = 0.5

    fun smoothedMasteryScore(state: KnowledgeMasteryState, atEpochMillis: Long): Double {
        val conservative = state.conservativeMasteryScore
        val observations = state.independentCorrectObservations
        if (observations.isEmpty()) return conservative
        var totalWeight = 0.0
        var supportedDecayedWeight = 0.0
        for (observation in observations) {
            val ageDays = (atEpochMillis - observation.occurredAtEpochMillis)
                .coerceAtLeast(0)
                .toDouble() / DAY_MILLIS
            totalWeight += observation.evidenceWeight
            if (observation.calibrationSupportAt(atEpochMillis) == CalibrationSupport.SUPPORTED) {
                supportedDecayedWeight +=
                    observation.evidenceWeight * 2.0.pow(-ageDays / HALF_LIFE_DAYS)
            }
        }
        if (totalWeight <= 0.0) return conservative
        val ema = (supportedDecayedWeight / totalWeight).coerceIn(0.0, 1.0)
        return (POINT_ESTIMATE_WEIGHT * conservative + (1.0 - POINT_ESTIMATE_WEIGHT) * ema)
            .coerceIn(0.0, 1.0)
    }

    private const val DAY_MILLIS = 86_400_000.0
}
