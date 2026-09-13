package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulingEvaluationHarnessTest {

    @Test
    fun `replay prefers the collected calendar day delta over a wall clock floor`() {
        // Two reviews 30 wall-clock minutes apart but on consecutive learner-local days: the
        // collected delta_t_days=1 must drive the long-run branch, not the same-day short-term one.
        val history = listOf(
            sample("unit-1", DAY * 0, FsrsRating.GOOD, deltaTDays = null), // first, no prior
            sample("unit-1", DAY * 0 + 30 * 60_000L, FsrsRating.GOOD, deltaTDays = 1.0),
        )

        val predictions = SchedulingReplay.predict(history)

        // A 30-minute gap under a wall-clock floor would be same-day (0 predictions); the
        // collected calendar-day delta of 1.0 must yield exactly one long-run prediction.
        assertEquals(1, predictions.size)
    }

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

    /**
     * 审计 S-10：**同一批数据上再拟一次，不得把现行参数换掉**。
     *
     * 这是"写回只会让留出损失变小"最锋利的一格：夹具先拟一次得到 `fitted`，再以 `fitted`
     * 作为现行参数拟第二次。同一批样本、同一套过程、同样的迭代次数 ⇒ 第二次不可能更好，
     * 因此必须原样返回 incumbent，并把 `adopted` 置为 false。
     *
     * 修复前这里必然失败：写回条件是"比**出厂默认**好"，而 `fit` 的择优基准就是出厂默认，
     * 于是第二次拟合照旧被采纳——`parameters` 变成第二次那组，虽然它与 incumbent 几乎重合，
     * **但没有任何东西保证这一点**（真实场景里两批数据不同，差距就是实打实的退步）。
     */
    @Test
    fun `a refit on the same data never replaces the parameters it started from`() {
        val samples = biasedHistory(correctStabilityGrowth = true, cardCount = 30)
        val fitted = FsrsParameterOptimizer.optimize(samples, iterations = 12)
        assertEquals(FsrsParameterOptimizer.Mode.FULL_FIT, fitted.mode)

        val second = FsrsParameterOptimizer.optimize(
            samples = samples,
            incumbent = fitted.parameters,
            iterations = 12,
        )

        assertFalse(
            "第二次拟合没有在留出尾段上更优，就不该被采纳",
            second.adopted,
        )
        assertEquals(
            "被拒时 mode 仍然如实描述这次尝试的范围（拟合了多少 ≠ 采不采纳）",
            fitted.mode,
            second.mode,
        )
        assertEquals(
            "被拒时 `optimizedParameterIndices` 同样描述这次尝试，而不是被清空",
            fitted.optimizedParameterIndices,
            second.optimizedParameterIndices,
        )
        assertArrayEquals(fitted.parameters, second.parameters, 1e-12)
        assertEquals(fitted.validationLogLoss, second.validationLogLoss, 1e-12)
    }

    @Test
    fun `w15 w16 unlock contract matches spec section 2_11b`() {
        // Spec §2.11b: unlock hard/easy penalty coefficients only at >=5000 samples
        // and only when validation loss improves by more than 2%.
        assertEquals(5_000, FsrsParameterOptimizer.UNLOCK_W15_W16_MIN_SAMPLES)
        assertEquals(0.02, FsrsParameterOptimizer.UNLOCK_W15_W16_GAIN_MARGIN, 1e-9)
        // 研究 2026-09-09 §8: the HARD bucket must itself be populated.
        assertEquals(100, FsrsParameterOptimizer.MIN_HARD_SAMPLES_FOR_W15)
    }

    @Test
    fun `easy bonus is pinned neutral and never fitted`() {
        // 研究 2026-09-09 §7: schedulingRatingFor never returns EASY, so w16 can
        // neither apply at runtime nor be identified from the review log.
        assertEquals(1.0, FsrsScheduleMath.DEFAULT_PARAMETERS[16], 1e-12)
        assertEquals(
            1.0,
            FsrsScheduleMath.nextRecallStability(
                difficulty = 5.0,
                stability = 10.0,
                retrievability = 0.9,
                rating = FsrsRating.EASY,
            ) / FsrsScheduleMath.nextRecallStability(
                difficulty = 5.0,
                stability = 10.0,
                retrievability = 0.9,
                rating = FsrsRating.GOOD,
            ),
            1e-9,
        )
        val samples = syntheticHistory(cardCount = 30)
        val result = FsrsParameterOptimizer.optimize(samples, iterations = 6)
        assertTrue(16 !in result.optimizedParameterIndices)
    }

    @Test
    fun `optimizer below the w15 w16 floor never fits those coefficients`() {
        val samples = syntheticHistory(cardCount = 30)  // well under 5000

        val result = FsrsParameterOptimizer.optimize(samples, iterations = 6)

        assertEquals(FsrsParameterOptimizer.Mode.FULL_FIT, result.mode)
        // w15 (hard penalty) and w16 (easy bonus) must stay out of the fitted set below the floor.
        assertTrue(15 !in result.optimizedParameterIndices)
        assertTrue(16 !in result.optimizedParameterIndices)
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

    @Test
    fun `legacy baseline skips same day repeats so both models score the same prediction pairs`() {
        // A history whose every long-run prediction is preceded by a same-day
        // repeat. The legacy baseline must skip the same-day review (it
        // carries no long-run retention signal) instead of emitting a
        // near-certainty pair, so the FSRS and baseline log-losses are
        // computed over identical prediction sets (spec §2.20 parity).
        val history = listOf(
            sample("unit-1", DAY * 0, FsrsRating.GOOD, deltaTDays = null),
            sample("unit-1", DAY * 0 + 30 * 60_000L, FsrsRating.GOOD, deltaTDays = 0.0),
            sample("unit-1", DAY * 3, FsrsRating.GOOD, deltaTDays = 3.0),
        )

        val fsrsPredictions = SchedulingReplay.predict(history)
        val legacyPredictions = SchedulingEvaluationHarness.legacyPredictionsForTest(history)

        assertEquals("same-day repeats must not create legacy prediction pairs", fsrsPredictions.size, legacyPredictions.size)
    }

    @Test
    fun `optimizer thresholds count predictable samples not raw rows`() {
        // Thirty-six cards × (first sample + two same-day repeats + one
        // cross-day review): 144 raw rows cross the 64-row FULL_FIT floor, but
        // only the final review per card is predictable — 36 predictable
        // samples still sit in the 8..63 INITIAL_STABILITY_ONLY band. Judging
        // the band on raw rows would wrongly claim FULL_FIT and fit 16
        // parameters to 36 data points.
        val samples = (0 until 36).flatMap { card ->
            listOf(
                sample("unit-$card", DAY * card, FsrsRating.GOOD, deltaTDays = null),
                sample("unit-$card", DAY * card + 1_000L, FsrsRating.GOOD, deltaTDays = 0.0),
                sample("unit-$card", DAY * card + 2_000L, FsrsRating.GOOD, deltaTDays = 0.0),
                sample("unit-$card", DAY * (card + 3), FsrsRating.GOOD, deltaTDays = 3.0),
            )
        }

        val result = FsrsParameterOptimizer.optimize(samples, iterations = 4)

        assertEquals(FsrsParameterOptimizer.Mode.INITIAL_STABILITY_ONLY, result.mode)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result.optimizedParameterIndices)
        assertTrue(result.trainLogLoss.isFinite())
    }

    @Test
    fun `optimizer reports insufficient data when no cross day prediction exists`() {
        // Every review is on the same calendar day as its predecessor: there
        // is no long-run prediction to fit, so the optimizer must report
        // insufficient data instead of fitting noise (raw rows would exceed
        // the 8-sample floor here).
        val samples = (0 until 10).flatMap { card ->
            listOf(
                sample("unit-$card", DAY * card, FsrsRating.GOOD, deltaTDays = null),
                sample("unit-$card", DAY * card + 60_000L, FsrsRating.AGAIN, deltaTDays = 0.0),
            )
        }

        val result = FsrsParameterOptimizer.optimize(samples, iterations = 4)

        assertEquals(FsrsParameterOptimizer.Mode.INSUFFICIENT_DATA, result.mode)
        assertEquals(
            FsrsScheduleMath.DEFAULT_PARAMETERS.toList(),
            result.parameters.toList(),
        )
    }

    private fun sample(
        unitId: String,
        at: Long,
        rating: FsrsRating,
        sourceKind: String = ReviewSample.ATTEMPT_KIND,
        deltaTDays: Double? = null,
    ) = ReviewSample(
        practiceUnitId = unitId,
        reviewedAtEpochMillis = at,
        rating = rating,
        sourceKind = sourceKind,
        deltaTDays = deltaTDays,
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
