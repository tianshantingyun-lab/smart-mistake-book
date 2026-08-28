package com.tingyun.smartmistakebook.core.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Time-of-day and response-time signals (spec mastery-scheduling §2.12,
 * §2.14). Constraints carried over from the research addendum:
 *
 * - Buckets describe the learner's own routine, not a universal clock;
 *   multipliers are personal, shrunk toward 1.0, and neutral until a bucket
 *   has at least [MIN_BUCKET_SAMPLES] observations (cold start must not
 *   inject small-sample noise).
 * - Signals only correct evidence weights (guess/slip posteriors); they
 *   never enter the forgetting curve itself (Benjamin 1998: fluency is a
 *   misleading metacognitive cue).
 */
enum class TimeBucket {
    MORNING,
    NOON,
    AFTERNOON,
    EVENING,
    NIGHT,
}

data class TimeBucketSplit(
    /** Hour at which morning starts (inclusive), learner-local. */
    val morningStartHour: Int = DEFAULT_MORNING_START,
    val noonStartHour: Int = DEFAULT_NOON_START,
    val afternoonStartHour: Int = DEFAULT_AFTERNOON_START,
    val eveningStartHour: Int = DEFAULT_EVENING_START,
    /** Hour at which night starts; night ends at morningStartHour. */
    val nightStartHour: Int = DEFAULT_NIGHT_START,
) {
    fun bucketFor(hourOfDay: Int): TimeBucket = when (hourOfDay) {
        in morningStartHour until noonStartHour -> TimeBucket.MORNING
        in noonStartHour until afternoonStartHour -> TimeBucket.NOON
        in afternoonStartHour until eveningStartHour -> TimeBucket.AFTERNOON
        in eveningStartHour until nightStartHour -> TimeBucket.EVENING
        else -> TimeBucket.NIGHT
    }

    companion object {
        const val DEFAULT_MORNING_START = 5
        const val DEFAULT_NOON_START = 11
        const val DEFAULT_AFTERNOON_START = 14
        const val DEFAULT_EVENING_START = 18
        const val DEFAULT_NIGHT_START = 23
    }
}

data class TimeOfDayObservation(
    val bucket: TimeBucket,
    val isCorrect: Boolean,
    val durationMs: Long = 0,
)

data class TimeOfDayProfile(
    val multipliers: Map<TimeBucket, Double>,
    val samplesPerBucket: Map<TimeBucket, Int>,
    /** Log-normal response-time baseline over correct answers; null before the sample floor. */
    val rtBaseline: RtBaseline?,
    val totalSamples: Int,
) {
    fun multiplierFor(bucket: TimeBucket): Double = multipliers[bucket] ?: 1.0
}

/**
 * Personal log-normal response-time baseline (van der Linden hierarchical
 * RT model). Latency percentiles distinguish fluent recalls from guesses.
 */
data class RtBaseline(
    val logMean: Double,
    val logStandardDeviation: Double,
    val sampleCount: Int,
) {
    /** Fraction of the baseline distribution at or above [durationMs]. */
    fun upperTailFraction(durationMs: Long): Double {
        if (logStandardDeviation <= 0.0) return 0.5
        val z = (ln((durationMs.coerceAtLeast(1)).toDouble()) - logMean) / logStandardDeviation
        return 1.0 - normalCdf(z)
    }

    fun lowerTailFraction(durationMs: Long): Double = 1.0 - upperTailFraction(durationMs)

    private fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / sqrt(2.0)))

    /** Abramowitz & Stegun 7.1.26 error-function approximation. */
    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = kotlin.math.abs(x)
        val t = 1.0 / (1.0 + P * ax)
        val y = 1.0 - (((((A5 * t + A4) * t) + A3) * t + A2) * t + A1) * t * exp(-ax * ax)
        return sign * y
    }

    private companion object {
        const val P = 0.3275911
        const val A1 = 0.254829592
        const val A2 = -0.284496736
        const val A3 = 1.421413741
        const val A4 = -1.453152027
        const val A5 = 1.061405429
    }
}

object TimeOfDayCalibrator {

    /**
     * personal_multiplier[b] = 1 + shrink·(observed[b] − 1) where
     * observed[b] = bucket accuracy / overall accuracy; neutral (1.0) below
     * the per-bucket sample floor.
     */
    fun profile(
        observations: List<TimeOfDayObservation>,
        split: TimeBucketSplit = TimeBucketSplit(),
    ): TimeOfDayProfile {
        require(observations.isNotEmpty()) { "Calibration requires observations" }
        val byBucket = observations.groupBy(TimeOfDayObservation::bucket)
        val overallAccuracy = observations.count(TimeOfDayObservation::isCorrect).toDouble() /
            observations.size
        val multipliers = mutableMapOf<TimeBucket, Double>()
        val sampleCounts = mutableMapOf<TimeBucket, Int>()
        for (bucket in TimeBucket.entries) {
            val bucketObservations = byBucket[bucket].orEmpty()
            sampleCounts[bucket] = bucketObservations.size
            multipliers[bucket] = if (bucketObservations.size < MIN_BUCKET_SAMPLES) {
                1.0
            } else {
                val bucketAccuracy = bucketObservations.count(TimeOfDayObservation::isCorrect)
                    .toDouble() / bucketObservations.size
                val observed = if (overallAccuracy <= 0.0) 1.0 else bucketAccuracy / overallAccuracy
                (1.0 + SHRINKAGE * (observed - 1.0)).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
            }
        }
        val correctDurations = observations
            .filter { it.isCorrect && it.durationMs > 0 }
            .map(TimeOfDayObservation::durationMs)
        val rtBaseline = if (correctDurations.size < MIN_BUCKET_SAMPLES) {
            null
        } else {
            val logs = correctDurations.map { ln(it.toDouble()) }
            val mean = logs.average()
            val variance = logs.sumOf { (it - mean) * (it - mean) } / (logs.size - 1).coerceAtLeast(1)
            RtBaseline(mean, sqrt(variance), correctDurations.size)
        }
        return TimeOfDayProfile(multipliers, sampleCounts, rtBaseline, observations.size)
    }

    /**
     * Guess/slip weight correction (spec §2.14): a correct answer answered
     * faster than the personal lower tail is a suspected guess and loses
     * weight; everything else keeps the base weight. Returns the base weight
     * unchanged before the sample floor is met.
     */
    fun correctedWeight(
        baseWeight: Double,
        isCorrect: Boolean,
        durationMs: Long,
        profile: TimeOfDayProfile?,
    ): Double {
        val baseline = profile?.rtBaseline ?: return baseWeight
        if (!isCorrect || durationMs <= 0) return baseWeight
        return if (baseline.lowerTailFraction(durationMs) < GUESS_TAIL_FRACTION) {
            baseWeight * GUESS_WEIGHT_FACTOR
        } else {
            baseWeight
        }
    }

    const val MIN_BUCKET_SAMPLES = 30
    const val SHRINKAGE = 0.5
    const val MIN_MULTIPLIER = 0.8
    const val MAX_MULTIPLIER = 1.25
    const val GUESS_TAIL_FRACTION = 0.1
    const val GUESS_WEIGHT_FACTOR = 0.8
}
