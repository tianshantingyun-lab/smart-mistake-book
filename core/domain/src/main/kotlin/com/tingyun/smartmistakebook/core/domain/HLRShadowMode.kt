package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.EventTimeTrust
import com.tingyun.smartmistakebook.core.model.LearningModelVersion

/**
 * Manages HLR shadow mode: produces shadow predictions alongside production
 * scheduling without affecting user-facing behavior.
 *
 * Shadow predictions are stored for later comparison with actual outcomes,
 * enabling backtesting, calibration, and eventual promotion to production.
 */
class HLRShadowModeManager(
    private val predictor: HalfLifeRegressionPredictor = HalfLifeRegressionPredictor(),
    private val enabled: Boolean = false,
) {
    /**
     * Model version for shadow predictions. Must be distinct from the
     * production model version.
     */
    val shadowModelVersion = LearningModelVersion(
        modelId = "hlr-shadow-v1",
        version = "0.1.0-experimental",
        algorithmHash = "hlr-recall-v1",
        trainingDataFingerprint = null, // Not yet trained
    )

    /**
     * Produce a shadow prediction for a review candidate. Returns null
     * if shadow mode is disabled.
     */
    fun predictShadow(
        practiceUnitId: String,
        features: HLRFeatures,
        deltaSeconds: Double,
        timeTrust: EventTimeTrust = EventTimeTrust.TRUSTED,
    ): HLRShadowPrediction? {
        if (!enabled) return null

        val probability = predictor.predict(features, deltaSeconds)
        val halfLife = predictor.computeHalfLife(features)

        return HLRShadowPrediction(
            predictionId = "shadow-${practiceUnitId}-${System.currentTimeMillis()}",
            modelVersion = shadowModelVersion,
            practiceUnitId = practiceUnitId,
            features = features,
            predictedRecallProbability = probability,
            halfLifeSeconds = halfLife,
            predictedAtEpochMillis = System.currentTimeMillis(),
            timeTrust = timeTrust,
        )
    }

    /**
     * Extract HLR features from the current problem memory state and
     * knowledge mastery state.
     */
    fun extractFeatures(
        memory: com.tingyun.smartmistakebook.core.model.ProblemMemoryState?,
        mastery: com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState?,
        difficulty: Double = 0.5,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): HLRFeatures {
        if (memory == null) {
            return HLRFeatures(
                evidenceMass = mastery?.evidenceMass ?: 0.0,
                difficulty = difficulty,
            )
        }
        return extractHlrFeaturesForShadow(
            memory = memory,
            mastery = mastery,
            difficulty = difficulty,
            nowEpochMillis = nowEpochMillis,
        )
    }

    /**
     * Compare shadow predictions with actual outcomes for calibration.
     * This method is called when an actual recall event occurs.
     */
    fun evaluatePrediction(
        prediction: HLRShadowPrediction,
        wasRecalled: Boolean,
    ): HLREvaluationResult {
        val predicted = prediction.predictedRecallProbability
        val actual = if (wasRecalled) 1.0 else 0.0
        val brierScore = (predicted - actual) * (predicted - actual)
        val logLoss = if (wasRecalled) {
            -kotlin.math.ln(predicted.coerceAtLeast(1e-10))
        } else {
            -kotlin.math.ln((1.0 - predicted).coerceAtLeast(1e-10))
        }

        return HLREvaluationResult(
            predictionId = prediction.predictionId,
            predictedProbability = predicted,
            wasRecalled = wasRecalled,
            brierScore = brierScore,
            logLoss = logLoss,
            absoluteError = kotlin.math.abs(predicted - actual),
        )
    }
}

/**
 * Result of evaluating an HLR shadow prediction against actual outcome.
 */
data class HLREvaluationResult(
    val predictionId: String,
    val predictedProbability: Double,
    val wasRecalled: Boolean,
    val brierScore: Double,
    val logLoss: Double,
    val absoluteError: Double,
)
