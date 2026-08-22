package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationBucket
import com.tingyun.smartmistakebook.core.model.CalibrationReport
import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import kotlin.math.ln

/**
 * Computes a [CalibrationReport] over resolved prediction/outcome pairs
 * (audit section 6.3 / PR-07). Ten equal-width score buckets; Brier score,
 * log loss, ECE, and maximum bucket deviation are computed exactly.
 */
object CalibrationReportBuilder {

    private const val BUCKET_COUNT = 10

    fun build(
        modelVersion: LearningModelVersion,
        resolved: List<CalibrationInput>,
        totalPredictions: Int,
        generatedAtEpochMillis: Long,
    ): CalibrationReport {
        require(resolved.all { it.predictedScore in 0.0..1.0 }) {
            "Predicted scores must be probabilities in 0..1"
        }
        val buckets = (0 until BUCKET_COUNT).map { index ->
            val low = index / BUCKET_COUNT.toDouble()
            val high = (index + 1) / BUCKET_COUNT.toDouble()
            val inBucket = resolved.filter { pair ->
                pair.predictedScore >= low && pair.predictedScore < high
            }
            val count = inBucket.size
            val meanPredicted = if (count == 0) {
                0.0
            } else {
                inBucket.sumOf { it.predictedScore } / count
            }
            val positiveRate = if (count == 0) {
                0.0
            } else {
                inBucket.count { it.wasIndependentCorrect }.toDouble() / count
            }
            val brier = if (count == 0) {
                0.0
            } else {
                inBucket.sumOf { pair ->
                    val actual = if (pair.wasIndependentCorrect) 1.0 else 0.0
                    val error = pair.predictedScore - actual
                    error * error
                } / count
            }
            val logLoss = if (count == 0) {
                null
            } else {
                -inBucket.sumOf { pair ->
                    val p = pair.predictedScore.coerceIn(1e-10, 1.0 - 1e-10)
                    if (pair.wasIndependentCorrect) ln(p) else ln(1.0 - p)
                } / count
            }
            CalibrationBucket(
                bucketId = "bucket-$index",
                modelVersion = modelVersion,
                scoreRangeLow = low,
                scoreRangeHigh = high,
                predictionCount = count,
                meanPredictedScore = meanPredicted,
                actualPositiveRate = positiveRate,
                brierContribution = brier,
                logLossContribution = logLoss,
            )
        }
        val nonEmptyBuckets = buckets.filter { it.predictionCount > 0 }
        val totalCount = nonEmptyBuckets.sumOf { it.predictionCount }
        val overallBrier = if (totalCount == 0) {
            0.0
        } else {
            nonEmptyBuckets.sumOf { it.brierContribution * it.predictionCount } / totalCount
        }
        val overallLogLoss = nonEmptyBuckets
            .filter { it.logLossContribution != null }
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.logLossContribution!! * it.predictionCount }
            ?.div(totalCount.coerceAtLeast(1))
        val ece = nonEmptyBuckets.sumOf { bucket ->
            val weight = bucket.predictionCount / totalCount.coerceAtLeast(1).toDouble()
            weight * kotlin.math.abs(bucket.meanPredictedScore - bucket.actualPositiveRate)
        }
        val maxDeviation = nonEmptyBuckets.maxOfOrNull { bucket ->
            kotlin.math.abs(bucket.meanPredictedScore - bucket.actualPositiveRate)
        } ?: 0.0
        return CalibrationReport(
            modelVersion = modelVersion,
            totalPredictions = totalPredictions,
            resolvedPredictions = resolved.size,
            overallBrierScore = overallBrier,
            overallLogLoss = overallLogLoss,
            expectedCalibrationError = ece,
            maximumCalibrationDeviation = maxDeviation,
            buckets = buckets,
            generatedAtEpochMillis = generatedAtEpochMillis,
        )
    }
}

/** Neutral prediction/outcome pair; callers map their storage types onto this. */
data class CalibrationInput(
    val predictedScore: Double,
    val conservativeScore: Double = 0.0,
    val wasIndependentCorrect: Boolean,
)