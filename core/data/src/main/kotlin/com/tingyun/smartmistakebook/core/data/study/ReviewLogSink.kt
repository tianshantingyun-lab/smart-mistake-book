package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.ReviewLogEntry
import com.tingyun.smartmistakebook.core.database.ReviewLogSampleRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.AttentionSignal
import com.tingyun.smartmistakebook.core.domain.FsrsEvidenceRatingMapper
import com.tingyun.smartmistakebook.core.domain.FsrsRating
import com.tingyun.smartmistakebook.core.domain.ReviewSample
import com.tingyun.smartmistakebook.core.domain.SourceCalibration
import com.tingyun.smartmistakebook.core.domain.SchedulingEvaluationHarness
import com.tingyun.smartmistakebook.core.domain.TimeBucket
import com.tingyun.smartmistakebook.core.domain.TimeBucketSplit
import com.tingyun.smartmistakebook.core.domain.TimeOfDayCalibrator
import com.tingyun.smartmistakebook.core.domain.TimeOfDayObservation
import com.tingyun.smartmistakebook.core.domain.TimeOfDayProfile
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * Owns everything review_log: silent evidence collection (spec
 * mastery-scheduling §2.15), the subjective signal discounts (§2.12/§2.14),
 * avoidance statistics (§6) and the analysis views feeding the evaluation
 * harness and the reminder suggestion. Extracted from
 * [RoomBackedStudyExperienceRepository] so the repository stays an
 * orchestrator; collection is decoupled from scheduling and must never break
 * the user-visible flow.
 */
internal class ReviewLogSink(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val clock: java.time.Clock,
    private val studyZoneId: ZoneId,
) {
    suspend fun record(
        practiceUnitId: String,
        evidence: LearningEvidence,
        occurredAtEpochMillis: Long,
        durationSeconds: Int,
        studyDay: StudyDayContext,
        sourceKind: String,
        sourceId: String,
        priorMemory: ProblemMemoryState?,
        schedulingEligible: Boolean = true,
        scrollUpCount: Int = 0,
        editCount: Int = 0,
        interruptionCount: Int = 0,
        awayMillis: Long = 0,
        plannedReason: String? = null,
    ) {
        try {
            val deltaDays = if (priorMemory == null || priorMemory.lastReviewedAtEpochMillis <= 0) {
                0.0
            } else {
                // Calendar-day delta (learner-local), matching FSRS delta_t semantics: a review
                // crossing local midnight is a new study day even under 24 wall-clock hours.
                (studyDay.epochDay - priorMemory.lastReviewedEpochDay)
                    .coerceAtLeast(0)
                    .toDouble()
            }
            val rating = FsrsEvidenceRatingMapper.reportedRatingFor(evidence.reason, evidence.weight)
            database.recordReviewLogEntries(
                listOf(
                    ReviewLogEntry(
                        learnerId = learnerId,
                        practiceUnitId = practiceUnitId,
                        rating = rating.ordinal + 1,
                        deltaTDays = deltaDays,
                        durationMs = durationSeconds * 1000L,
                        reviewedAtEpochMillis = occurredAtEpochMillis,
                        sourceKind = sourceKind,
                        sourceId = sourceId,
                        evidenceWeight = evidence.weight,
                        schedulingEligible = schedulingEligible,
                        timeBucket = bucketNameAt(occurredAtEpochMillis),
                        scrollUpCount = scrollUpCount,
                        editCount = editCount,
                        interruptionCount = interruptionCount,
                        awayMillis = awayMillis,
                        plannedReason = plannedReason,
                        recordedAtEpochMillis = clock.millis(),
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Review-log collection is decoupled from scheduling (spec §2.15):
            // it must never break the user-visible flow - but stays diagnosable.
            android.util.Log.w("ReviewLogSink", "review_log write failed", failure)
        }
        profileComputed = false
    }

    suspend fun reviewSamples(): List<ReviewSample> =
        database.readReviewLogSamples(learnerId, REVIEW_LOG_SAMPLE_LIMIT)
            .map { row ->
                ReviewSample(
                    practiceUnitId = row.practiceUnitId,
                    reviewedAtEpochMillis = row.reviewedAtEpochMillis,
                    rating = ratingForOrdinal(row.rating),
                    durationMs = row.durationMs,
                    sourceKind = row.sourceKind,
                    plannedReason = row.plannedReason,
                    deltaTDays = row.deltaTDays,
                )
            }

    /**
     * Avoidance units (spec §6 / D'Mello 2013): cards switched away from at
     * least twice per attempt while graded poorly, twice within the recent
     * window - a difficulty or aversion marker that steers re-teaching.
     */
    suspend fun avoidancePracticeUnitIds(): Set<String> {
        val now = clock.millis()
        return database.readReviewLogSamples(learnerId, REVIEW_LOG_SAMPLE_LIMIT)
            .asSequence()
            .filter { now - it.reviewedAtEpochMillis in 0..AVOIDANCE_LOOKBACK_MILLIS }
            .filter {
                it.interruptionCount >= AttentionSignal.AVOIDANCE_SWITCH_THRESHOLD &&
                    it.rating <= AttentionSignal.AVOIDANCE_MAX_RATING
            }
            .groupBy(ReviewLogSampleRecord::practiceUnitId)
            .filterValues { rows -> rows.size >= AVOIDANCE_MIN_OCCURRENCES }
            .keys
    }

    /**
     * Subjective evidence factor (spec §2.14 + §2.12): attention switches and
     * away-time (Craik 1996), the personal time-of-day multiplier (May &
     * Hasher 1998; >=30 samples per bucket, cold start neutral) and the
     * response-time guess discount (Meyer 2010 via the RT baseline) all only
     * ever shrink the weight of a subjective report.
     */
    suspend fun subjectiveSignalFactor(
        occurredAtEpochMillis: Long,
        durationSeconds: Int,
        interruptionCount: Int,
        awayMillis: Long,
        isCorrect: Boolean,
    ): Double {
        val attention = AttentionSignal.attentionFactor(interruptionCount, awayMillis)
        val profile = timeOfDayProfile()
        val timeOfDay = profile
            ?.multiplierFor(bucketSplit.bucketFor(localHourAt(occurredAtEpochMillis)))
            ?: 1.0
        val rtDiscount = profile
            ?.let { TimeOfDayCalibrator.correctedWeight(1.0, isCorrect, durationSeconds * 1000L, it) }
            ?: 1.0
        return (attention * timeOfDay * rtDiscount).coerceIn(0.0, 1.0)
    }

    /** RT guess discount alone, for real-attempt evidence (no time-of-day term). */
    suspend fun responseTimeDiscount(isCorrect: Boolean, durationMs: Long): Double =
        timeOfDayProfile()
            ?.let { TimeOfDayCalibrator.correctedWeight(1.0, isCorrect, durationMs, it) }
            ?: 1.0

    suspend fun suggestedReminderMinute(): Int? {
        val observations = observations()
        if (observations.isEmpty()) return null
        val profile = TimeOfDayCalibrator.profile(observations)
        val split = TimeBucketSplit()
        val peak = TimeBucket.entries
            .filter { (profile.samplesPerBucket[it] ?: 0) >= TimeOfDayCalibrator.MIN_BUCKET_SAMPLES }
            .maxByOrNull { profile.multiplierFor(it) } ?: return null
        return split.midpointMinute(peak)
    }

    suspend fun sourceCalibrations(): List<SourceCalibration> =
        SchedulingEvaluationHarness.calibrateSources(reviewSamples())

    // Calibration depends only on review_log rows, whose sole writer is
    // record() below - cache the profile and invalidate on write so the
    // per-submission path stays O(1) instead of re-reading the full log.
    private var cachedProfile: TimeOfDayProfile? = null
    private var profileComputed = false

    private suspend fun timeOfDayProfile(): TimeOfDayProfile? {
        if (profileComputed) return cachedProfile
        val samples = database.readReviewLogSamples(learnerId, REVIEW_LOG_SAMPLE_LIMIT)
        val observations = samples.mapNotNull(::toObservation)
        cachedProfile = if (observations.isEmpty()) null else TimeOfDayCalibrator.profile(observations)
        profileComputed = true
        return cachedProfile
    }

    private suspend fun observations(): List<TimeOfDayObservation> =
        database.readReviewLogSamples(learnerId, REVIEW_LOG_SAMPLE_LIMIT)
            .mapNotNull(::toObservation)

    private fun toObservation(row: ReviewLogSampleRecord): TimeOfDayObservation? {
        val bucket = runCatching { TimeBucket.valueOf(row.timeBucket) }.getOrNull()
            ?: return null
        return TimeOfDayObservation(
            bucket = bucket,
            isCorrect = row.rating > 1,
            durationMs = row.durationMs,
        )
    }

    private fun bucketNameAt(epochMillis: Long): String =
        bucketSplit.bucketFor(localHourAt(epochMillis)).name

    private fun localHourAt(epochMillis: Long): Int =
        ((epochMillis + studyZoneId.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds * 1000L) /
            3_600_000L).mod(24L).toInt()

    private fun ratingForOrdinal(rating: Int): FsrsRating = FsrsRating.entries[
        (rating - 1).coerceIn(0, FsrsRating.entries.size - 1)
    ]

    private val bucketSplit = TimeBucketSplit()

    companion object {
        const val SOURCE_KIND_ATTEMPT = "ATTEMPT"
        const val SOURCE_KIND_SELF_REPORT = "SELF_REPORT"
        const val SOURCE_KIND_VISUAL = "VISUAL"

        private const val AVOIDANCE_LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val AVOIDANCE_MIN_OCCURRENCES = 2
        private const val REVIEW_LOG_SAMPLE_LIMIT = 100_000
    }
}
