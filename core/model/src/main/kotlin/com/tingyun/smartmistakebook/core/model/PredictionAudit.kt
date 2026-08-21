package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/**
 * Versioned learning model identity. Every prediction must record which model
 * produced it so that calibration and back-testing can be scoped to a model generation.
 */
@Serializable
data class LearningModelVersion(
    val modelId: String,
    val version: String,
    val algorithmHash: String,
    val trainingDataFingerprint: String? = null,
    val trainedAtEpochMillis: Long? = null,
) {
    init {
        require(modelId.isNotBlank()) { "Model id must not be blank" }
        require(version.isNotBlank()) { "Model version must not be blank" }
        require(algorithmHash.isNotBlank()) { "Algorithm hash must not be blank" }
    }
}

/**
 * A single prediction record produced by a learning model. Stored alongside
 * the observation so that later outcome comparison can compute calibration metrics.
 */
@Serializable
data class StudentModelPrediction(
    val predictionId: String,
    val modelVersion: LearningModelVersion,
    val practiceUnitId: String,
    val knowledgeNodeId: String?,
    val featureFingerprint: String,
    val predictedScore: Double,
    val conservativeScore: Double,
    val predictionWindowStartEpochMillis: Long,
    val predictionWindowEndEpochMillis: Long,
    val predictedAtEpochMillis: Long,
) {
    init {
        require(predictionId.isNotBlank()) { "Prediction id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(predictedScore.isFinite() && predictedScore in 0.0..1.0) {
            "Predicted score must be between 0 and 1"
        }
        require(conservativeScore.isFinite() && conservativeScore in 0.0..1.0) {
            "Conservative score must be between 0 and 1"
        }
        require(conservativeScore <= predictedScore) {
            "Conservative score must not exceed predicted score"
        }
        require(predictionWindowEndEpochMillis > predictionWindowStartEpochMillis) {
            "Prediction window must be non-empty"
        }
    }
}

/**
 * The actual observed outcome that resolves a prediction. Linked back by predictionId.
 */
@Serializable
data class PredictionOutcome(
    val predictionId: String,
    val observedAtEpochMillis: Long,
    val wasIndependentCorrect: Boolean,
    val responseLatencyMs: Long? = null,
    val hintCount: Int = 0,
) {
    init {
        require(predictionId.isNotBlank()) { "Prediction id must not be blank" }
        require(observedAtEpochMillis >= 0) { "Observation time must not be negative" }
    }
}

/**
 * A calibration bucket groups predictions with similar predicted scores.
 * Comparing the mean predicted score with the actual positive rate in each bucket
 * reveals model bias and calibration quality.
 */
@Serializable
data class CalibrationBucket(
    val bucketId: String,
    val modelVersion: LearningModelVersion,
    val scoreRangeLow: Double,
    val scoreRangeHigh: Double,
    val predictionCount: Int,
    val meanPredictedScore: Double,
    val actualPositiveRate: Double,
    val brierContribution: Double,
    val logLossContribution: Double? = null,
) {
    init {
        require(bucketId.isNotBlank()) { "Bucket id must not be blank" }
        require(scoreRangeLow >= 0.0 && scoreRangeLow <= 1.0) { "Low range must be 0..1" }
        require(scoreRangeHigh >= 0.0 && scoreRangeHigh <= 1.0) { "High range must be 0..1" }
        require(scoreRangeHigh > scoreRangeLow) { "High range must exceed low range" }
        require(predictionCount >= 0) { "Prediction count must not be negative" }
        require(meanPredictedScore.isFinite()) { "Mean predicted score must be finite" }
        require(actualPositiveRate.isFinite() && actualPositiveRate in 0.0..1.0) {
            "Actual positive rate must be between 0 and 1"
        }
    }
}

/**
 * Summary report of model calibration quality, computed over resolved predictions.
 */
@Serializable
data class CalibrationReport(
    val modelVersion: LearningModelVersion,
    val totalPredictions: Int,
    val resolvedPredictions: Int,
    val overallBrierScore: Double,
    val overallLogLoss: Double? = null,
    val expectedCalibrationError: Double,
    val maximumCalibrationDeviation: Double,
    val buckets: List<CalibrationBucket>,
    val generatedAtEpochMillis: Long,
) {
    init {
        require(totalPredictions >= 0) { "Total predictions must not be negative" }
        require(resolvedPredictions in 0..totalPredictions) {
            "Resolved predictions must not exceed total"
        }
    }
}
