package com.tingyun.smartmistakebook.core.domain

/**
 * Personalized expected-duration model.
 *
 * The audit requires not using fixed per-item-type constants, but a model of
 * `student × subject × itemType × difficulty`. Real durations are non-negative
 * and skewed, so we model `log(duration)` and keep EMA and percentile buckets.
 *
 * This is a pure, deterministic, thread-safe value model. It stores no
 * per-learner identity beyond the bucket key so it can be applied by the
 * review planner without database access.
 */
class LogDurationModel {
    private val bucketStats = mutableMapOf<DurationBucketKey, DurationBucketStats>()

    /**
     * Record an observed duration for a learner/subject/itemType/difficulty bucket.
     */
    fun record(
        learnerId: String,
        subjectId: String?,
        itemType: String?,
        difficulty: Double,
        durationSeconds: Double,
    ) {
        require(durationSeconds.isFinite() && durationSeconds > 0) {
            "Observed duration must be positive and finite"
        }
        val key = DurationBucketKey(learnerId, subjectId, itemType, difficulty)
        val stats = bucketStats.getOrPut(key) { DurationBucketStats() }
        stats.record(durationSeconds)
    }

    /**
     * Expected duration in seconds for the given bucket. Uses the bucket's
     * geometric-mean (from the EMA of log-duration) when it has samples,
     * falling back to a global prior otherwise.
     */
    fun expectedSeconds(
        learnerId: String,
        subjectId: String?,
        itemType: String?,
        difficulty: Double,
    ): Double {
        val stats = bucketStats[DurationBucketKey(learnerId, subjectId, itemType, difficulty)]
        return stats?.estimateSeconds() ?: GLOBAL_PRIOR_SECONDS
    }

    /**
     * P80 of observed durations for a bucket, used for the planner's
     * "completion time P80 <= budget * 1.2" acceptance gate.
     */
    fun p80Seconds(
        learnerId: String,
        subjectId: String?,
        itemType: String?,
        difficulty: Double,
    ): Double {
        return bucketStats[DurationBucketKey(learnerId, subjectId, itemType, difficulty)]
            ?.p80Seconds()
            ?: expectedSeconds(learnerId, subjectId, itemType, difficulty)
    }

    companion object {
        /** Global prior (seconds) used before any per-bucket sample exists. */
        const val GLOBAL_PRIOR_SECONDS = 60.0

        /** EMA smoothing factor: weight of the newest sample. */
        private const val EMA_ALPHA = 0.2
    }

    /** Identifies a duration-distribution bucket. */
    private data class DurationBucketKey(
        val learnerId: String,
        val subjectId: String?,
        val itemType: String?,
        val difficulty: Double,
    )

    /** Cumulative log-duration statistics for one bucket. */
    private class DurationBucketStats {
        private var logEma: Double? = null
        private var lastSampleLog: Double = 0.0
        private var sampleCount: Int = 0
        private val logSamples = ArrayDeque<Double>()

        fun record(durationSeconds: Double) {
            val logValue = kotlin.math.ln(durationSeconds)
            logEma = if (logEma == null) logValue else {
                EMA_ALPHA * logValue + (1.0 - EMA_ALPHA) * (logEma ?: logValue)
            }
            lastSampleLog = logValue
            sampleCount++
            logSamples.addLast(logValue)
            // Bound memory: keep at most P80_WINDOW log samples.
            while (logSamples.size > P80_WINDOW) logSamples.removeFirst()
        }

        fun estimateSeconds(): Double = (
            logEma ?: kotlin.math.ln(GLOBAL_PRIOR_SECONDS)
            ).let { kotlin.math.exp(it) }

        fun p80Seconds(): Double {
            if (logSamples.isEmpty()) return estimateSeconds()
            val sorted = logSamples.sorted()
            val idx = (sorted.size * 0.8).toInt().coerceAtMost(sorted.lastIndex)
            return kotlin.math.exp(sorted[idx])
        }

        private companion object {
            const val P80_WINDOW = 120
        }
    }
}
