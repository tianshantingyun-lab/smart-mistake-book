package com.tingyun.smartmistakebook.core.domain

/**
 * Personalized expected-duration model.
 *
 * Bucketed by `student × subject` (spec batch-intake-spec §6 L1 decision):
 * the audit originally called for a four-dimension model
 * (student × subject × itemType × difficulty), but itemType and difficulty
 * drift between record time (the item's state when attempted) and query time
 * (the item's current state when planned) — a drifting key would orphan every
 * recorded sample and the model would never personalize. Solving duration is
 * driven mainly by the learner and the subject, so those are the bucket.
 *
 * Real durations are non-negative and skewed, so we model `log(duration)`
 * and keep EMA and percentile buckets.
 *
 * This is a pure, deterministic, thread-safe value model. It stores no
 * per-learner identity beyond the bucket key so it can be applied by the
 * review planner without database access.
 */
class LogDurationModel {
    private val bucketStats = mutableMapOf<DurationBucketKey, DurationBucketStats>()

    /** Observable sample count (test/telemetry seam). */
    fun totalSamples(): Int = bucketStats.values.sumOf(DurationBucketStats::sampleCount)

    /**
     * Record an observed duration for a learner/subject bucket. The itemType
     * and difficulty parameters are accepted for call-site stability but do
     * NOT participate in the bucket key (see [DurationBucketKey]).
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
        val key = DurationBucketKey(learnerId, subjectId)
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
        val stats = bucketStats[DurationBucketKey(learnerId, subjectId)]
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
        return bucketStats[DurationBucketKey(learnerId, subjectId)]
            ?.p80Seconds()
            ?: expectedSeconds(learnerId, subjectId, itemType, difficulty)
    }

    /**
     * Three-layer expected duration for a NEW (never-attempted) item
     * (spec `batch-intake-spec.md` §2): L1 personalized bucket estimate when
     * the learner's bucket has enough real samples, L2 semantic tier baseline
     * when the model judged a difficulty tier, L3 global prior. The model
     * never supplies a numeric duration — only the tier.
     */
    fun expectedSecondsForNew(
        learnerId: String,
        subjectId: String?,
        itemType: String?,
        difficulty: Double,
        tierBaselineSeconds: Int?,
    ): Double {
        val stats = bucketStats[DurationBucketKey(learnerId, subjectId)]
        if (stats != null && stats.sampleCount() >= PERSONALIZED_MIN_SAMPLES) {
            return stats.estimateSeconds()
        }
        return tierBaselineSeconds?.toDouble() ?: GLOBAL_PRIOR_SECONDS
    }

    companion object {
        /** Global prior (seconds) used before any per-bucket sample exists. */
        const val GLOBAL_PRIOR_SECONDS = 60.0

        /**
         * Cold-start baselines per model-judged difficulty tier (spec
         * `batch-intake-spec.md` §2 L2): the model supplies only the semantic
         * tier (steps / computation / knowledge points / sub-questions), the
         * numeric seconds are local constants pending calibration.
         */
        const val TIER_BASELINE_EASY_SECONDS = 90
        const val TIER_BASELINE_MEDIUM_SECONDS = 180
        const val TIER_BASELINE_HARD_SECONDS = 300

        /** Per-bucket sample count at which the personalized estimate (L1) takes over. */
        const val PERSONALIZED_MIN_SAMPLES = 5

        /** EMA smoothing factor: weight of the newest sample. */
        private const val EMA_ALPHA = 0.2
    }

    /**
     * Identifies a duration-distribution bucket. Deliberately ONLY
     * (learner, subject) — spec `batch-intake-spec.md` §6 L1: the difficulty
     * and item-type dimensions drift between record time (prior) and query
     * time (current), which would make recorded samples never match the
     * queries; solving duration is driven mainly by the learner and subject.
     * The [record]/[expectedSeconds] APIs keep the extra parameters for
     * call-site stability but they do not participate in the bucket key.
     */
    private data class DurationBucketKey(
        val learnerId: String,
        val subjectId: String?,
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

        fun sampleCount(): Int = sampleCount

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
