package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.EventTimeTrust
import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.serialization.Serializable

/** Number of features in the HLR model, including the intercept. */
const val HLR_FEATURE_COUNT = 11

/**
 * Half-Life Regression (HLR) predictor for spaced repetition.
 *
 * Based on the model: P(recall) = 2^(-Δ/h)
 * where h = 2^(θ·x) is the half-life computed from features x and parameters θ.
 *
 * This implementation is designed for shadow mode: it produces predictions
 * alongside the production scheduler without affecting user-facing behavior.
 * Parameters must be trained on real learning trajectories from this product
 * before being promoted to production.
 */
class HalfLifeRegressionPredictor(
    private val parameters: HLRParameters = HLRParameters.DEFAULT,
) {
    init {
        require(parameters.theta.size == HLR_FEATURE_COUNT) {
            "Theta size ${parameters.theta.size} must equal feature count $HLR_FEATURE_COUNT"
        }
    }

    /**
     * Predict the probability of recall at a given time delta from the last review.
     *
     * @param features The feature vector for this problem-learner pair.
     * @param deltaSeconds Seconds since the last review.
     * @return Predicted recall probability in [0, 1].
     */
    fun predict(features: HLRFeatures, deltaSeconds: Double): Double {
        require(deltaSeconds >= 0) { "Delta must not be negative" }
        require(features.toVector().all(Double::isFinite)) { "Features must all be finite" }
        val halfLifeSeconds = computeHalfLife(features)
        if (halfLifeSeconds <= 0) return 0.0
        return 2.0.pow(-deltaSeconds / halfLifeSeconds)
            .coerceIn(0.0, 1.0)
    }

    /**
     * Compute the half-life in seconds for the given features.
     */
    fun computeHalfLife(features: HLRFeatures): Double {
        require(features.toVector().all(Double::isFinite)) { "Features must all be finite" }
        val dotProduct = parameters.theta.zip(features.toVector())
            .sumOf { (theta, feature) -> theta * feature }
        return 2.0.pow(dotProduct)
    }

    /**
     * Solve for the interval that yields the target recall:
     * t = h × ln(1/P) / ln(2)
     */
    fun intervalForTargetRecall(features: HLRFeatures, targetRecall: Double): Double {
        require(targetRecall > 0.0 && targetRecall < 1.0) {
            "Target recall must be strictly between zero and one"
        }
        val halfLifeSeconds = computeHalfLife(features)
        return halfLifeSeconds * ln(1.0 / targetRecall) / ln(2.0)
    }

    companion object {
        /**
         * Feature names for interpretability and training.
         */
        val FEATURE_NAMES = listOf(
            "intercept",
            "independentCorrectCount",
            "assistedCorrectCount",
            "lapseCount",
            "answerRevealCount",
            "evidenceMass",
            "difficulty",
            "timeBetweenReviewsDays",
            "daysSinceFirstSeen",
            "consecutiveCorrectStreak",
            "lastResponseLatencyNormalized",
        )
    }
}

/**
 * Feature vector for HLR prediction. All features should be normalized
 * to approximately [0, 1] range for stable training.
 */
@Serializable
data class HLRFeatures(
    val independentCorrectCount: Double = 0.0,
    val assistedCorrectCount: Double = 0.0,
    val lapseCount: Double = 0.0,
    val answerRevealCount: Double = 0.0,
    val evidenceMass: Double = 0.0,
    val difficulty: Double = 0.5,
    val timeBetweenReviewsDays: Double = 0.0,
    val daysSinceFirstSeen: Double = 0.0,
    val consecutiveCorrectStreak: Double = 0.0,
    val lastResponseLatencyNormalized: Double = 0.0,
) {
    /**
     * Convert to a feature vector with intercept term.
     */
    fun toVector(): List<Double> = listOf(
        1.0, // intercept
        independentCorrectCount,
        assistedCorrectCount,
        lapseCount,
        answerRevealCount,
        evidenceMass,
        difficulty,
        timeBetweenReviewsDays,
        daysSinceFirstSeen,
        consecutiveCorrectStreak,
        lastResponseLatencyNormalized,
    )

    init {
        require(evidenceMass >= 0.0) { "Evidence mass must not be negative" }
        require(difficulty in 0.0..1.0) { "Difficulty must be in [0, 1]" }
        require(toVector().all(Double::isFinite)) { "Features must all be finite" }
    }
}

/**
 * HLR model parameters (theta vector). Default values are experimental
 * hypotheses and MUST be replaced with values trained on real data before
 * production use.
 */
@Serializable
data class HLRParameters(
    val theta: List<Double>,
) {
    init {
        require(theta.size == HLR_FEATURE_COUNT) {
            "Theta size ${theta.size} must equal feature count $HLR_FEATURE_COUNT"
        }
        require(theta.all(Double::isFinite)) { "Parameters must all be finite" }
    }

    companion object {
        /**
         * Default parameters (experimental, NOT trained on real data).
         * These are hand-tuned heuristics that serve as a starting point.
         */
        val DEFAULT = HLRParameters(
            theta = listOf(
                10.0,    // intercept (baseline half-life in log2 seconds)
                0.3,     // independentCorrectCount
                0.1,     // assistedCorrectCount
                -0.5,    // lapseCount
                -0.2,    // answerRevealCount
                0.2,     // evidenceMass
                -0.3,    // difficulty
                -0.1,    // timeBetweenReviewsDays
                -0.05,   // daysSinceFirstSeen
                0.15,    // consecutiveCorrectStreak
                -0.1,    // lastResponseLatencyNormalized
            ),
        )
    }
}

/**
 * Result of an HLR shadow prediction, stored for later comparison with
 * actual outcomes.
 */
@Serializable
data class HLRShadowPrediction(
    val predictionId: String,
    val modelVersion: LearningModelVersion,
    val practiceUnitId: String,
    val features: HLRFeatures,
    val predictedRecallProbability: Double,
    val halfLifeSeconds: Double,
    val predictedAtEpochMillis: Long,
    val timeTrust: EventTimeTrust,
)
