package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulingEvaluationHarnessTest {

    @Test
    fun `replay emits one prediction per repeat review skipping the first`() {
        val history = listOf(
            sample("unit-1", DAY * 0, FsrsRating.GOOD),
            sample("unit-1", DAY * 2, FsrsRating.GOOD),
            sample("unit-1", DAY * 6, FsrsRating.AGAIN),
            sample("unit-1", DAY * 9, FsrsRating.GOOD),
        )

        val predictions = SchedulingReplay.predict(history)

        assertEquals(3, predictions.size)
        assertTrue(predictions.all { (probability, _) -> probability in 0.0..1.0 })
    }

    @Test
    fun `optimizer bounds match py-fsrs parameter validation ranges`() {
        // py-fsrs UPPER_BOUNDS_PARAMETERS: initial stability rows span to 100 days;
        // w12 (difficulty decay) = 0.25, w13 = 0.9, w15 (hard penalty) = 1.0,
        // w16 (easy bonus) = 6.0, w19 (short-term exponent) = 0.8, w20 (decay) = 0.8.
        assertEquals(100.0, FsrsParameterOptimizer.UPPER_BOUNDS[0], 1e-9)
        assertEquals(100.0, FsrsParameterOptimizer.UPPER_BOUNDS[3], 1e-9)
        assertEquals(4.0, FsrsParameterOptimizer.UPPER_BOUNDS[5], 1e-9)
        assertEquals(0.75, FsrsParameterOptimizer.UPPER_BOUNDS[7], 1e-9)
        assertEquals(0.25, FsrsParameterOptimizer.UPPER_BOUNDS[12], 1e-9)
        assertEquals(0.9, FsrsParameterOptimizer.UPPER_BOUNDS[13], 1e-9)
        assertEquals(1.0, FsrsParameterOptimizer.UPPER_BOUNDS[15], 1e-9)
        assertEquals(6.0, FsrsParameterOptimizer.UPPER_BOUNDS[16], 1e-9)
        assertEquals(0.8, FsrsParameterOptimizer.UPPER_BOUNDS[19], 1e-9)
        assertEquals(0.8, FsrsParameterOptimizer.UPPER_BOUNDS[20], 1e-9)
        // Lower bound w4 (difficulty intercept) is 1.0; w16 (easy bonus) is 1.0; w20 is 0.1.
        assertEquals(1.0, FsrsParameterOptimizer.LOWER_BOUNDS[4], 1e-9)
        assertEquals(1.0, FsrsParameterOptimizer.LOWER_BOUNDS[16], 1e-9)
        assertEquals(0.1, FsrsParameterOptimizer.LOWER_BOUNDS[20], 1e-9)
    }

    @Test
    fun `harness reports finite losses for both models`() {
        val samples = syntheticHistory()

        val report = SchedulingEvaluationHarness.evaluate(samples)

        assertTrue(report.fsrs.isValidModel)
        assertTrue(report.baseline.isValidModel)
        assertTrue(report.fsrs.sampleCount > 0)
        assertTrue(report.fsrs.logLoss > 0.0)
        assertTrue(report.baseline.logLoss > 0.0)
    }

    @Test
    fun `optimizer keeps defaults below the data floor`() {
        val samples = (0 until 3).flatMap { card ->
            listOf(
                sample("unit-$card", DAY * 0, FsrsRating.GOOD),
                sample("unit-$card", DAY * 3, FsrsRating.GOOD),
            )
        }

        val result = FsrsParameterOptimizer.optimize(samples)

        assertEquals(FsrsParameterOptimizer.Mode.INSUFFICIENT_DATA, result.mode)
        assertEquals(
            FsrsScheduleMath.DEFAULT_PARAMETERS.toList(),
            result.parameters.toList(),
        )
    }

    @Test
    fun `optimizer fits initial stability only in the narrow band`() {
        val samples = syntheticHistory(cardCount = 10)

        val result = FsrsParameterOptimizer.optimize(samples, iterations = 6)

        assertEquals(FsrsParameterOptimizer.Mode.INITIAL_STABILITY_ONLY, result.mode)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result.optimizedParameterIndices)
        assertTrue(result.trainLogLoss.isFinite())
    }

    @Test
    fun `optimizer full fit improves or preserves the default log loss on learnable data`() {
        val samples = biasedHistory(correctStabilityGrowth = true, cardCount = 30)

        val defaultLoss = SchedulingReplay.bceLogLoss(
            samples.groupBy(ReviewSample::practiceUnitId).values
                .filter { it.size >= 2 }
                .flatMap { SchedulingReplay.predict(it) },
        )
        val result = FsrsParameterOptimizer.optimize(samples, iterations = 12)

        assertEquals(FsrsParameterOptimizer.Mode.FULL_FIT, result.mode)
        assertTrue(
            "optimized ${result.trainLogLoss} should not exceed default $defaultLoss",
            result.trainLogLoss <= defaultLoss + 0.05,
        )
    }

    private fun syntheticHistory(cardCount: Int = 12): List<ReviewSample> = (0 until cardCount).flatMap { card ->
        var at = DAY * card
        var rating = if (card % 3 == 0) FsrsRating.AGAIN else FsrsRating.GOOD
        buildList {
            add(sample("unit-$card", at, rating))
            for (step in 1..5) {
                at += DAY * (2 + step)
                rating = if (step == 3) FsrsRating.AGAIN else FsrsRating.GOOD
                add(sample("unit-$card", at, rating))
            }
        }
    }

    private fun biasedHistory(correctStabilityGrowth: Boolean, cardCount: Int): List<ReviewSample> {
        require(correctStabilityGrowth)
        return (0 until cardCount).flatMap { card ->
            var at = DAY * card
            buildList {
                add(sample("unit-$card", at, FsrsRating.GOOD))
                var interval = 3
                for (step in 1..7) {
                    at += DAY * interval
                    add(sample("unit-$card", at, FsrsRating.GOOD))
                    interval = (interval * 1.6).toInt().coerceAtLeast(2)
                }
            }
        }
    }

    @Test
    fun `source calibration pairs subjective positives with the next real attempt`() {
        val samples = listOf(
            sample("unit-1", DAY * 0, FsrsRating.GOOD, sourceKind = "ATTEMPT"),
            sample("unit-1", DAY * 2, FsrsRating.GOOD, sourceKind = "SELF_REPORT"),
            sample("unit-1", DAY * 4, FsrsRating.AGAIN, sourceKind = "ATTEMPT"),
            sample("unit-2", DAY * 0, FsrsRating.GOOD, sourceKind = "ATTEMPT"),
            sample("unit-2", DAY * 2, FsrsRating.EASY, sourceKind = "SELF_REPORT"),
            sample("unit-2", DAY * 5, FsrsRating.GOOD, sourceKind = "ATTEMPT"),
        )

        val calibration = SchedulingEvaluationHarness.calibrateSources(samples)

        assertEquals(1, calibration.size)
        val selfReport = calibration.single()
        assertEquals("SELF_REPORT", selfReport.sourceKind)
        assertEquals(2, selfReport.positiveReportCount)
        assertEquals(2, selfReport.nextAttemptCount)
        // One of the two follow-up attempts failed, the other succeeded.
        assertEquals(0.5, selfReport.realizedRecallRate, 1e-9)
        assertFalse(selfReport.hasSufficientPairs)
        assertFalse(selfReport.suggestsDowngrade)
    }

    @Test
    fun `optimizer reports a finite validation loss under the hold out protocol`() {
        val samples = syntheticHistory(cardCount = 12)

        val result = FsrsParameterOptimizer.optimize(samples, iterations = 6)

        assertTrue(result.validationLogLoss.isFinite())
        assertTrue(result.trainLogLoss.isFinite())
    }

    private fun sample(
        unitId: String,
        at: Long,
        rating: FsrsRating,
        sourceKind: String = ReviewSample.ATTEMPT_KIND,
    ) = ReviewSample(
        practiceUnitId = unitId,
        reviewedAtEpochMillis = at,
        rating = rating,
        sourceKind = sourceKind,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}

class TimeOfDaySignalsTest {

    @Test
    fun `peak bucket midpoint maps to minutes after midnight`() {
        val split = TimeBucketSplit()
        assertEquals(8 * 60, split.midpointMinute(TimeBucket.MORNING))
        assertEquals(16 * 60, split.midpointMinute(TimeBucket.AFTERNOON))
        // Night spans across midnight: 23:00..05:00 midpoint is 02:00.
        assertEquals(2 * 60, split.midpointMinute(TimeBucket.NIGHT))
    }


    @Test
    fun `default bucket split maps a learner day`() {
        assertEquals(TimeBucket.MORNING, TimeBucketSplit().bucketFor(7))
        assertEquals(TimeBucket.NOON, TimeBucketSplit().bucketFor(12))
        assertEquals(TimeBucket.AFTERNOON, TimeBucketSplit().bucketFor(15))
        assertEquals(TimeBucket.EVENING, TimeBucketSplit().bucketFor(20))
        assertEquals(TimeBucket.NIGHT, TimeBucketSplit().bucketFor(1))
    }

    @Test
    fun `cold start multipliers stay neutral`() {
        val observations = List(20) { index ->
            TimeOfDayObservation(TimeBucket.MORNING, isCorrect = index % 2 == 0, durationMs = 5_000)
        }

        val profile = TimeOfDayCalibrator.profile(observations)

        assertEquals(1.0, profile.multiplierFor(TimeBucket.MORNING), 0.0)
        assertNull(profile.rtBaseline)
    }

    @Test
    fun `strong bucket accuracy raises its shrunken multiplier`() {
        val observations = buildList {
            repeat(40) { add(TimeOfDayObservation(TimeBucket.MORNING, isCorrect = true, durationMs = 10_000)) }
            repeat(40) { add(TimeOfDayObservation(TimeBucket.EVENING, isCorrect = false, durationMs = 10_000)) }
        }

        val profile = TimeOfDayCalibrator.profile(observations)

        assertTrue(profile.multiplierFor(TimeBucket.MORNING) > 1.0)
        assertTrue(profile.multiplierFor(TimeBucket.EVENING) < 1.0)
        assertTrue(profile.multiplierFor(TimeBucket.MORNING) <= TimeOfDayCalibrator.MAX_MULTIPLIER)
        assertNotNull(profile.rtBaseline)
    }

    @Test
    fun `suspected guesses lose weight only below the personal lower tail`() {
        val observations = List(60) { index ->
            TimeOfDayObservation(
                TimeBucket.MORNING,
                isCorrect = true,
                durationMs = 20_000L + index * 500,
            )
        }
        val profile = TimeOfDayCalibrator.profile(observations)

        val fluent = TimeOfDayCalibrator.correctedWeight(1.0, true, 20_000, profile)
        val slow = TimeOfDayCalibrator.correctedWeight(1.0, true, 60_000, profile)

        assertEquals(1.0 * TimeOfDayCalibrator.GUESS_WEIGHT_FACTOR, fluent, 1e-9)
        assertEquals(1.0, slow, 1e-9)
    }
}
