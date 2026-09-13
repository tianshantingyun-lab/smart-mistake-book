package com.tingyun.smartmistakebook.core.domain

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared scheduling-evaluation data model (spec mastery-scheduling §2.20/B0):
 * raw review evidence replayed under two competing models - the FSRS-6 power
 * law and the legacy exponential baseline - scored by binary cross-entropy
 * log-loss. The collection path (review_log) stays decoupled from
 * scheduling; this harness is the only consumer that replays it.
 */
data class ReviewSample(
    val practiceUnitId: String,
    val reviewedAtEpochMillis: Long,
    val rating: FsrsRating,
    val durationMs: Long = 0,
    /** ATTEMPT / SELF_REPORT / VISUAL — source calibration key (spec §2.5). */
    val sourceKind: String = ATTEMPT_KIND,
    /** Planner reason snapshot carried onto the attempt (spec §6 calibration). */
    val plannedReason: String? = null,
    /**
     * Calendar-day delta from the prior review, captured at collection time from the learner-local
     * study day. Null when unknown (legacy samples); the replay then falls back to a wall-clock
     * floor over [reviewedAtEpochMillis].
     */
    val deltaTDays: Double? = null,
) {
    val isCorrect: Boolean get() = rating != FsrsRating.AGAIN

    /**
     * Elapsed calendar days from [lastReviewedAt] to this sample: the
     * collected learner-local delta when present, otherwise a wall-clock day
     * floor. Shared by every replay/optimizer path so the delta_t semantics
     * stay identical across models (spec §2.20 parity).
     */
    fun elapsedDaysSince(lastReviewedAt: Long): Double = deltaTDays ?: (
        (reviewedAtEpochMillis - lastReviewedAt).toDouble() / DAY_MILLIS
        ).coerceAtLeast(0.0)

    companion object {
        const val ATTEMPT_KIND = "ATTEMPT"

        /**
         * 讲题判定通道的 source kind。与 ATTEMPT 分开落库，且在校准达标前**不参与**
         * FSRS 参数拟合（[SchedulingEvaluationHarness.evaluate] / [FsrsParameterOptimizer.optimize]
         * 显式排除）——Anki 官方口径：四键评分即拟合信号，把一种新评分混进去会让所有间隔
         * 被系统性拉偏。校准走 [SchedulingEvaluationHarness.calibrateSources] 单独一档。
         */
        const val MODEL_JUDGED_KIND = "MODEL_JUDGED"
        private const val DAY_MILLIS = 86_400_000.0
    }
}

/**
 * Per-source calibration (spec §2.5): for every subjective positive report
 * (self-report/rating graded Hard or better), find the next real attempt on
 * the same card and compare its realized recall. A source whose realized
 * recall sits well below the real-attempt baseline is over-claiming and the
 * static mapping (subjective Easy cap) should be revisited - by human
 * decision, never auto-rewritten.
 */
data class SourceCalibration(
    val sourceKind: String,
    val positiveReportCount: Int,
    val nextAttemptCount: Int,
    val realizedRecallRate: Double,
    val attemptBaselineRecallRate: Double,
) {
    /** Minimum paired outcomes before any calibration suggestion is valid. */
    val hasSufficientPairs: Boolean get() = nextAttemptCount >= MIN_PAIRED_OUTCOMES

    /**
     * Suggested static adjustment: downgrade when the source's realized
     * recall underperforms the attempt baseline by more than the margin.
     */
    val suggestsDowngrade: Boolean
        get() = hasSufficientPairs && realizedRecallRate <= attemptBaselineRecallRate - DOWNGRADE_MARGIN

    companion object {
        const val MIN_PAIRED_OUTCOMES = 30
        const val DOWNGRADE_MARGIN = 0.15
    }
}

data class ModelEvaluation(
    val modelName: String,
    val logLoss: Double,
    val sampleCount: Int,
) {
    val isValidModel: Boolean get() = logLoss.isFinite()
}

data class SchedulingEvaluationReport(
    val fsrs: ModelEvaluation,
    val baseline: ModelEvaluation,
    val evaluationSampleCount: Int,
) {
    /**
     * Spec §2.20 go/no-go gate: FSRS-6 must beat the exponential baseline on
     * the same replayed evidence before it may stay enabled.
     */
    val fsrsBeatsBaseline: Boolean
        get() = fsrs.isValidModel && baseline.isValidModel &&
            evaluationSampleCount >= MIN_EVALUATION_SAMPLES &&
            fsrs.logLoss < baseline.logLoss

    companion object {
        const val MIN_EVALUATION_SAMPLES = 200
    }
}

/** Replay one card's ordered samples and emit (predicted R, actual) pairs. */
object SchedulingReplay {

    fun predict(
        history: List<ReviewSample>,
        parameters: DoubleArray = FsrsScheduleMath.DEFAULT_PARAMETERS,
        desiredRetention: Double = FsrsMemoryUpdateModel.DEFAULT_DESIRED_RETENTION,
    ): List<Pair<Double, Boolean>> {
        require(history.isNotEmpty()) { "Replay requires a non-empty review history" }
        val ordered = history.sortedBy(ReviewSample::reviewedAtEpochMillis)
        var stability = 0.0
        var difficulty = 0.0
        var hasState = false
        var lastReviewedAt = 0L
        val predictions = mutableListOf<Pair<Double, Boolean>>()
        for (sample in ordered) {
            if (!hasState) {
                stability = FsrsScheduleMath.initialStability(sample.rating, parameters)
                difficulty = FsrsScheduleMath.clampDifficulty(
                    FsrsScheduleMath.initialDifficulty(sample.rating, parameters),
                )
                hasState = true
                lastReviewedAt = sample.reviewedAtEpochMillis
                continue
            }
            val elapsedDays = sample.elapsedDaysSince(lastReviewedAt)
            if (elapsedDays < 1.0) {
                // Same-day repeats carry no long-run prediction (spec §2.15).
                stability = FsrsScheduleMath.shortTermStability(stability, sample.rating, parameters)
                difficulty = FsrsScheduleMath.nextDifficulty(difficulty, sample.rating, parameters)
                lastReviewedAt = sample.reviewedAtEpochMillis
                continue
            }
            val retrievability = FsrsScheduleMath.retention(elapsedDays, stability)
            predictions += retrievability to sample.isCorrect
            stability = if (sample.rating == FsrsRating.AGAIN) {
                FsrsScheduleMath.nextForgetStability(difficulty, stability, retrievability, parameters)
            } else {
                FsrsScheduleMath.nextRecallStability(
                    difficulty,
                    stability,
                    retrievability,
                    sample.rating,
                    parameters,
                )
            }
            difficulty = FsrsScheduleMath.nextDifficulty(difficulty, sample.rating, parameters)
            lastReviewedAt = sample.reviewedAtEpochMillis
        }
        return predictions
    }

    fun bceLogLoss(predictions: List<Pair<Double, Boolean>>): Double {
        if (predictions.isEmpty()) return Double.NaN
        var total = 0.0
        for ((probability, correct) in predictions) {
            val clamped = min(max(probability, 1e-6), 1.0 - 1e-6)
            total += if (correct) -ln(clamped) else -ln(1.0 - clamped)
        }
        return total / predictions.size
    }

}

/**
 * 可参与评估/拟合的复习样本：排除讲题判定行，直到 `calibrateSources` 的配对结果证明
 * 它可以进（见 [ReviewSample.MODEL_JUDGED_KIND] 的说明）。调用方的"有没有数据"判断
 * 必须用同一口径，否则只做过讲题判定复习的学习者会在报告路径上被空集绊倒。
 */
fun fittableReviewSamples(samples: List<ReviewSample>): List<ReviewSample> =
    samples.filterNot { it.sourceKind == ReviewSample.MODEL_JUDGED_KIND }

/**
 * Backtest harness (spec §2.20): replays the real review ledger under FSRS-6
 * and under the audited exponential baseline with a chronological
 * time-series split, and reports both log-losses plus the go/no-go gate.
 */
object SchedulingEvaluationHarness {

    /**
     * Source calibration table (spec §2.5/A2): realized recall of the next
     * real attempt after each subjective positive report, per source kind,
 * against the real-attempt baseline.
     */
    fun calibrateSources(samples: List<ReviewSample>): List<SourceCalibration> {
        val subjectiveKinds = samples.map(ReviewSample::sourceKind)
            .filterNot { it == ReviewSample.ATTEMPT_KIND }
            .toSortedSet()
        if (subjectiveKinds.isEmpty()) return emptyList()
        val perCard = samples.groupBy(ReviewSample::practiceUnitId)
            .mapValues { (_, history) -> history.sortedBy(ReviewSample::reviewedAtEpochMillis) }
        val attemptBaseline = perCard.values
            .flatMap { history -> history.filter { it.sourceKind == ReviewSample.ATTEMPT_KIND } }
        val attemptBaselineRate = rate(attemptBaseline)
        return subjectiveKinds.map { sourceKind ->
            var positiveReports = 0
            var paired = 0
            var correctNext = 0
            perCard.values.forEach { history ->
                history.forEachIndexed { index, sample ->
                    if (sample.sourceKind != sourceKind || sample.rating < FsrsRating.HARD) {
                        return@forEachIndexed
                    }
                    positiveReports += 1
                    val nextAttempt = history.drop(index + 1)
                        .firstOrNull { it.sourceKind == ReviewSample.ATTEMPT_KIND } ?: return@forEachIndexed
                    paired += 1
                    if (nextAttempt.isCorrect) correctNext += 1
                }
            }
            SourceCalibration(
                sourceKind = sourceKind,
                positiveReportCount = positiveReports,
                nextAttemptCount = paired,
                realizedRecallRate = if (paired == 0) Double.NaN else correctNext.toDouble() / paired,
                attemptBaselineRecallRate = attemptBaselineRate,
            )
        }
    }

    private fun rate(samples: List<ReviewSample>): Double =
        if (samples.isEmpty()) Double.NaN else samples.count(ReviewSample::isCorrect).toDouble() / samples.size

    /**
     * Per-planned-reason realized recall (spec §6 weight recalibration):
     * groups collected samples by the planner reason that scheduled them and
     * reports each group's recall against the overall baseline. Advisory
     * only - weight constants change by human decision at the >=200-sample
     * threshold, never automatically.
     */
    fun calibratePlannedReasons(samples: List<ReviewSample>): List<PlannedReasonCalibration> {
        val withReason = samples.mapNotNull { sample ->
            sample.plannedReason?.let { reason -> sample to reason }
        }
        if (withReason.isEmpty()) return emptyList()
        val overall = rate(withReason.map { it.first })
        return withReason.groupBy { it.second }
            .map { (reason, rows) ->
                PlannedReasonCalibration(
                    plannedReason = reason,
                    sampleCount = rows.size,
                    realizedRecallRate = rate(rows.map { it.first }),
                    overallRecallRate = overall,
                )
            }
            .sortedByDescending(PlannedReasonCalibration::sampleCount)
    }

    fun evaluate(
        samples: List<ReviewSample>,
        parameters: DoubleArray = FsrsScheduleMath.DEFAULT_PARAMETERS,
        trainFraction: Double = 0.7,
    ): SchedulingEvaluationReport {
        val fittable = fittableReviewSamples(samples)
        require(fittable.isNotEmpty()) { "Evaluation requires review samples" }
        require(trainFraction in 0.1..0.9) { "Train fraction must be within 0.1..0.9" }
        val perCard = fittable.groupBy(ReviewSample::practiceUnitId)
            .map { (_, history) -> history.sortedBy(ReviewSample::reviewedAtEpochMillis) }
            .filter { it.size >= 2 }
        require(perCard.isNotEmpty()) { "Evaluation requires at least one card with two reviews" }

        val cutoff = quantile(
            fittable.map(ReviewSample::reviewedAtEpochMillis).sorted(),
            trainFraction,
        )
        val fsrsTrain = mutableListOf<Pair<Double, Boolean>>()
        val fsrsTest = mutableListOf<Pair<Double, Boolean>>()
        val legacyTrain = mutableListOf<Pair<Double, Boolean>>()
        val legacyTest = mutableListOf<Pair<Double, Boolean>>()
        perCard.forEach { history ->
            val predictions = SchedulingReplay.predict(history, parameters)
            predictions.forEachIndexed { index, pair ->
                val sample = history[index + 1]
                if (sample.reviewedAtEpochMillis <= cutoff) fsrsTrain += pair else fsrsTest += pair
            }
            legacy(history).forEachIndexed { index, pair ->
                val sample = history[index + 1]
                if (sample.reviewedAtEpochMillis <= cutoff) legacyTrain += pair else legacyTest += pair
            }
        }
        val evaluationSamples = fsrsTest.size
        return SchedulingEvaluationReport(
            fsrs = ModelEvaluation(
                "fsrs6",
                if (fsrsTest.isEmpty()) SchedulingReplay.bceLogLoss(fsrsTrain) else SchedulingReplay.bceLogLoss(fsrsTest),
                if (fsrsTest.isEmpty()) fsrsTrain.size else evaluationSamples,
            ),
            baseline = ModelEvaluation(
                "legacy-exponential",
                if (legacyTest.isEmpty()) SchedulingReplay.bceLogLoss(legacyTrain) else SchedulingReplay.bceLogLoss(legacyTest),
                if (legacyTest.isEmpty()) legacyTrain.size else evaluationSamples,
            ),
            evaluationSampleCount = evaluationSamples,
        )
    }

    /**
     * The audited pre-FSRS model: exponential retention with the ad-hoc
     * stability multipliers, fed ratings mapped back to outcomes.
     *
     * The replay mirrors [SchedulingReplay.predict] so both models score the
     * exact same prediction pairs (spec §2.20 parity): the first sample only
     * seeds state, and a same-day repeat (elapsed < 1 day) carries no
     * long-run retention signal — it advances state without emitting a
     * prediction pair.
     */
    internal fun legacyPredictionsForTest(history: List<ReviewSample>): List<Pair<Double, Boolean>> =
        legacy(history)

    private fun legacy(history: List<ReviewSample>): List<Pair<Double, Boolean>> {
        val ordered = history.sortedBy(ReviewSample::reviewedAtEpochMillis)
        var stability = 0.5
        var hasState = false
        var lastReviewedAt = 0L
        val predictions = mutableListOf<Pair<Double, Boolean>>()
        for (sample in ordered) {
            if (!hasState) {
                hasState = true
                lastReviewedAt = sample.reviewedAtEpochMillis
                continue
            }
            val elapsedDays = sample.elapsedDaysSince(lastReviewedAt)
            if (elapsedDays < 1.0) {
                // Same-day repeats carry no long-run prediction (spec §2.15);
                // advance state exactly like the FSRS replay does so the two
                // models evaluate identical prediction sets.
                stability = if (sample.isCorrect) {
                    stability * 2.6 + 0.25
                } else {
                    (stability * 0.45).coerceAtLeast(0.25)
                }
                lastReviewedAt = sample.reviewedAtEpochMillis
                continue
            }
            val probability = pow(0.9, elapsedDays / stability)
            predictions += probability to sample.isCorrect
            stability = if (sample.isCorrect) {
                stability * 2.6 + 0.25
            } else {
                (stability * 0.45).coerceAtLeast(0.25)
            }
            lastReviewedAt = sample.reviewedAtEpochMillis
        }
        return predictions
    }

    private fun quantile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun pow(base: Double, exponent: Double): Double = Math.pow(base, exponent)

}

/**
 * Local FSRS-6 parameter optimizer (spec §2.11/B6): bounded Adam with
 * central-difference gradients over the replay log-loss, honoring the
 * fsrs-rs data-volume thresholds (fewer than 8 samples: keep defaults;
 * fewer than 64: fit only initial stability).
 */
object FsrsParameterOptimizer {

    /**
     * Per-index bounds matching py-fsrs `LOWER_BOUNDS_PARAMETERS` / `UPPER_BOUNDS_PARAMETERS`
     * (fsrs/scheduler.py) and fsrs-rs `parameter_clipper.rs`. The initial-stability rows (w0..w3)
     * span to INITIAL_STABILITY_MAX = 100 days, not 10.
     */
    internal val LOWER_BOUNDS = doubleArrayOf(
        0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0,
        0.001, 0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
    )
    internal val UPPER_BOUNDS = doubleArrayOf(
        100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8,
        3.5, 5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
    )

    data class Result(
        val parameters: DoubleArray,
        val mode: Mode,
        val trainLogLoss: Double,
        val validationLogLoss: Double,
        val sampleCount: Int,
        val optimizedParameterIndices: List<Int>,
    )

    enum class Mode { INSUFFICIENT_DATA, INITIAL_STABILITY_ONLY, FULL_FIT }

    fun optimize(
        samples: List<ReviewSample>,
        iterations: Int = DEFAULT_ITERATIONS,
    ): Result {
        // 讲题判定行是新的评分来源，校准达标前不进拟合器（口径见
        // ReviewSample.MODEL_JUDGED_KIND）：拟合参数会被一种偏离标尺的评分整体拉偏。
        // 过滤后为空不是调用错误——只做过讲题判定复习的学习者就该拿到"数据不足"，
        // 而不是异常；下面 predictableSampleCount=0 会走 INSUFFICIENT_DATA 分支。
        val fittingSamples = fittableReviewSamples(samples)
        require(samples.isNotEmpty()) { "Optimization requires review samples" }
        // The data-volume thresholds (fsrs-rs 8/64; spec §2.11b unlock floor)
        // exist to keep the fit honest relative to how many outcomes the
        // replay can actually score. First samples and same-day repeats never
        // produce a prediction pair, so raw row counts over-state the learnable
        // data; count the predictable (long-run) samples instead.
        val predictableSampleCount = predictableSampleCount(fittingSamples)
        if (predictableSampleCount < MIN_SAMPLES_FOR_FITTING) {
            return Result(
                FsrsScheduleMath.DEFAULT_PARAMETERS.copyOf(),
                Mode.INSUFFICIENT_DATA,
                Double.NaN,
                Double.NaN,
                predictableSampleCount,
                emptyList(),
            )
        }
        val baseIndices = if (predictableSampleCount < MIN_SAMPLES_FOR_FULL_FIT) {
            (0..5).toList()
        } else {
            (0..14).toList() + listOf(20)
        }

        // Chronological hold-out (srs-benchmark protocol): each card's FULL
        // history is replayed once and the prediction points are bucketed by
        // timestamp, so validation reviews benefit from the train-segment
        // memory instead of restarting from a cold card. Optimization
        // targets the validation tail so a winning parameter set generalizes
        // forward instead of memorizing the past.
        val cutoff = quantile(
            fittingSamples.map(ReviewSample::reviewedAtEpochMillis).sorted(),
            TRAIN_FRACTION,
        )
        val cards = fittingSamples.groupBy(ReviewSample::practiceUnitId)
            .values
            .filter { it.size >= 2 }
            .map { history -> history.sortedBy(ReviewSample::reviewedAtEpochMillis) }

        var (bestParams, bestValidationLoss) = fit(cards, cutoff, baseIndices, iterations)
        var bestTrainLoss = lossFor(cards, cutoff, bestParams, wantValidation = false)
        var fittedIndices = baseIndices

        // Spec §2.11b: unlock the hard-penalty coefficient (w15) only when the sample
        // volume reaches the unlock floor, enough HARD reviews exist to identify it,
        // AND doing so improves validation loss by more than the gain margin — a guard
        // against overfitting an extra parameter on insufficient data.
        //
        // w16 (easy bonus) is never fitted: the evidence mapping never grades a review
        // EASY, so its training set is empty and any fitted value is unidentifiable
        // (研究 2026-09-09 §7/§8). It stays pinned at 1.0.
        if (
            predictableSampleCount >= UNLOCK_W15_W16_MIN_SAMPLES &&
            fittingSamples.count { it.rating == FsrsRating.HARD } >= MIN_HARD_SAMPLES_FOR_W15
        ) {
            val extendedIndices = (baseIndices + 15).distinct()
            val (extendedParams, extendedValidationLoss) = fit(cards, cutoff, extendedIndices, iterations)
            val relativeGain = (bestValidationLoss - extendedValidationLoss) / bestValidationLoss
            if (bestValidationLoss.isFinite() && relativeGain > UNLOCK_W15_W16_GAIN_MARGIN) {
                bestParams = extendedParams
                bestValidationLoss = extendedValidationLoss
                bestTrainLoss = lossFor(cards, cutoff, bestParams, wantValidation = false)
                fittedIndices = extendedIndices
            }
        }

        val mode = if (predictableSampleCount < MIN_SAMPLES_FOR_FULL_FIT) {
            Mode.INITIAL_STABILITY_ONLY
        } else {
            Mode.FULL_FIT
        }
        return Result(
            bestParams,
            mode,
            bestTrainLoss,
            bestValidationLoss,
            predictableSampleCount,
            fittedIndices,
        )
    }

    /**
     * Number of review samples the replay can emit a prediction pair for: the
     * first review of each card only seeds state, and same-day repeats carry
     * no long-run retention signal. A sample qualifies when it has a prior
     * review at least one calendar day earlier.
     */
    internal fun predictableSampleCount(samples: List<ReviewSample>): Int {
        // 与 evaluate/optimize 同一口径：讲题判定行不算"可预测样本"，避免它们
        // 抬高解锁拟合的样本量门槛。
        val fittable = fittableReviewSamples(samples)
        var count = 0
        fittable.groupBy(ReviewSample::practiceUnitId)
            .values
            .forEach { history ->
                val ordered = history.sortedBy(ReviewSample::reviewedAtEpochMillis)
                if (ordered.size < 2) return@forEach
                var lastReviewedAt = ordered.first().reviewedAtEpochMillis
                for (sample in ordered.drop(1)) {
                    val elapsedDays = sample.elapsedDaysSince(lastReviewedAt)
                    if (elapsedDays >= 1.0) count += 1
                    lastReviewedAt = sample.reviewedAtEpochMillis
                }
            }
        return count
    }

    /**
     * Runs one bounded-Adam fit over [fittedIndices] and returns the best parameters plus their
     * validation log-loss. The default parameters seed every fit so each stage is independent.
     */
    private fun fit(
        cards: List<List<ReviewSample>>,
        cutoff: Long,
        fittedIndices: List<Int>,
        iterations: Int,
    ): Pair<DoubleArray, Double> {
        var parameters = FsrsScheduleMath.DEFAULT_PARAMETERS.copyOf()
        val firstMoment = DoubleArray(FsrsScheduleMath.PARAMETER_COUNT)
        val secondMoment = DoubleArray(FsrsScheduleMath.PARAMETER_COUNT)
        var bestLoss = lossFor(cards, cutoff, parameters, wantValidation = true)
        var best = parameters.copyOf()
        var stepsSinceImprovement = 0
        var step = 0
        for (iteration in 0 until iterations) {
            step += 1
            val gradient = DoubleArray(FsrsScheduleMath.PARAMETER_COUNT)
            for (index in fittedIndices) {
                val upper = parameters.copyOf().also { it[index] = it[index] + EPSILON }
                val lower = parameters.copyOf().also { it[index] = it[index] - EPSILON }
                gradient[index] = (lossFor(cards, cutoff, upper, wantValidation = false) -
                    lossFor(cards, cutoff, lower, wantValidation = false)) / (2 * EPSILON)
            }
            for (index in fittedIndices) {
                firstMoment[index] = BETA1 * firstMoment[index] + (1 - BETA1) * gradient[index]
                secondMoment[index] = BETA2 * secondMoment[index] + (1 - BETA2) * gradient[index] * gradient[index]
                val firstCorrection = firstMoment[index] / (1 - Math.pow(BETA1, step.toDouble()))
                val secondCorrection = secondMoment[index] / (1 - Math.pow(BETA2, step.toDouble()))
                parameters[index] -= LEARNING_RATE * firstCorrection / (sqrt(secondCorrection) + 1e-8)
                parameters[index] = parameters[index].coerceIn(LOWER_BOUNDS[index], UPPER_BOUNDS[index])
            }
            val currentLoss = lossFor(cards, cutoff, parameters, wantValidation = true)
            if (currentLoss < bestLoss - 1e-9) {
                bestLoss = currentLoss
                best = parameters.copyOf()
                stepsSinceImprovement = 0
            } else {
                stepsSinceImprovement += 1
                if (stepsSinceImprovement >= EARLY_STOP_PATIENCE) break
            }
        }
        return best to bestLoss
    }

    private fun quantile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /**
     * BCE over one bucket of the chronological split. Each card's full
     * history is replayed (so validation keeps train-segment memory) and a
     * prediction belongs to the bucket of the review it predicted.
     */
    private fun lossFor(
        cards: List<List<ReviewSample>>,
        cutoff: Long,
        parameters: DoubleArray,
        wantValidation: Boolean,
    ): Double {
        val predictions = cards.flatMap { history ->
            val reviewed = history.drop(1)
            SchedulingReplay.predict(history, parameters)
                .zip(reviewed) { pair, sample -> pair to sample }
                .filter { (_, sample) ->
                    (sample.reviewedAtEpochMillis > cutoff) == wantValidation
                }
                .map { (pair, _) -> pair }
        }
        val loss = SchedulingReplay.bceLogLoss(predictions)
        return if (loss.isNaN()) 10.0 else loss
    }

    const val MIN_SAMPLES_FOR_FITTING = 8
    const val MIN_SAMPLES_FOR_FULL_FIT = 64
    /** Spec §2.11b: unlock w15/w16 only at this sample volume. */
    const val UNLOCK_W15_W16_MIN_SAMPLES = 5_000
    /**
     * …and only when the HARD bucket itself is populated enough to identify the
     * coefficient (研究 2026-09-09 §8: a user who never rates Hard leaves w15
     * just as unidentifiable as w16 always is).
     */
    const val MIN_HARD_SAMPLES_FOR_W15 = 100
    /** Spec §2.11b: unlock w15/w16 only when validation loss improves by more than this fraction. */
    const val UNLOCK_W15_W16_GAIN_MARGIN = 0.02
    const val DEFAULT_ITERATIONS = 24
    const val TRAIN_FRACTION = 0.8
    const val EARLY_STOP_PATIENCE = 5
    private const val EPSILON = 1e-4
    private const val LEARNING_RATE = 2e-3
    private const val BETA1 = 0.9
    private const val BETA2 = 0.999
}

/**
 * Per-planned-reason realized recall (spec §6 weight recalibration):
 * advisory analysis; weight constants change by human decision at the
 * >=200-sample threshold, never automatically.
 */
data class PlannedReasonCalibration(
    val plannedReason: String,
    val sampleCount: Int,
    val realizedRecallRate: Double,
    val overallRecallRate: Double,
) {
    /** Spec §6: recalibration analysis activates at two hundred samples. */
    val hasSufficientSamples: Boolean get() = sampleCount >= MIN_SAMPLES

    companion object {
        const val MIN_SAMPLES = 200
    }
}
