package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ProblemMemoryState

/**
 * Student-model contracts for the prediction audit loop (acceptance audit
 * §3.3 / §6.4). The planner only consumes predictions; feature extraction,
 * model versioning, and persistence live behind these interfaces.
 */

/** Identity of a student-model generation used for audit scoping. */
data class StudentModelVersion(
    val modelId: String,
    val version: String,
    val algorithmHash: String,
) {
    init {
        require(modelId.isNotBlank()) { "Student model id must not be blank" }
        require(version.isNotBlank()) { "Student model version must not be blank" }
        require(algorithmHash.isNotBlank()) { "Student model algorithm hash must not be blank" }
    }
}

/** Everything a recall prediction needs for one practice unit at one instant. */
data class RecallPredictionInput(
    val practiceUnitId: String,
    val features: HLRFeatures,
    val deltaSeconds: Double,
    val nowEpochMillis: Long,
) {
    init {
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(deltaSeconds >= 0) { "Prediction delta must not be negative" }
        require(nowEpochMillis >= 0) { "Prediction time must not be negative" }
    }
}

/** A single recall prediction (audit §6.4). */
data class RecallPrediction(
    val predictionId: String,
    val modelVersion: String,
    val probability: Double,
    val horizonMillis: Long,
    val conservativeScore: Double,
    val featureFingerprint: String,
    val generatedAtEpochMillis: Long,
) {
    init {
        require(predictionId.isNotBlank()) { "Prediction id must not be blank" }
        require(probability.isFinite() && probability in 0.0..1.0) {
            "Predicted probability must be in 0..1"
        }
        require(conservativeScore.isFinite() && conservativeScore in 0.0..1.0) {
            "Conservative score must be in 0..1"
        }
        require(conservativeScore <= probability) {
            "Conservative score must not exceed the predicted probability"
        }
        require(horizonMillis > 0) { "Prediction horizon must be positive" }
    }
}

/** Evidence projection request: how strongly the memory is supported right now. */
data class EvidenceProjectionInput(
    val practiceUnitId: String,
    val memory: ProblemMemoryState,
    val nowEpochMillis: Long,
)

/** Neutral evidence projection consumed by callers that must not touch raw probabilities. */
data class EvidenceProjection(
    val practiceUnitId: String,
    val estimatedRetention: Double,
    val halfLifeSeconds: Double,
    val projectedAtEpochMillis: Long,
)

/** Audit record combining a prediction with the window it is judged against. */
data class RecallPredictionAudit(
    val prediction: RecallPrediction,
    val practiceUnitId: String,
    val knowledgeNodeId: String?,
    val windowStartEpochMillis: Long,
    val windowEndEpochMillis: Long,
) {
    init {
        require(practiceUnitId.isNotBlank()) { "Audit practice unit id must not be blank" }
        require(windowEndEpochMillis > windowStartEpochMillis) {
            "Audit prediction window must be non-empty"
        }
    }
}

/**
 * The student-model surface (audit §6.4). Implementations own their version
 * identity and never mutate user-visible scheduling.
 */
interface StudentModel {
    val version: StudentModelVersion

    fun predictRecall(input: RecallPredictionInput): RecallPrediction

    fun projectEvidence(input: EvidenceProjectionInput): EvidenceProjection
}

/** Prediction generation entry point (audit §3.3). */
interface RecallPredictionService {
    fun predict(input: RecallPredictionInput): RecallPrediction
}

/**
 * Abstraction over prediction persistence: writing shadow predictions and
 * backfilling real outcomes (audit §6.3). Implementations must never throw
 * into user-visible flows.
 */
interface PredictionAuditSink {
    suspend fun record(prediction: RecallPredictionAudit)

    /**
     * Backfill the real outcome for every pending prediction covering
     * [observedAtEpochMillis] on [practiceUnitId].
     * @return number of predictions resolved.
     */
    suspend fun resolveOutcome(
        practiceUnitId: String,
        wasIndependentCorrect: Boolean,
        observedAtEpochMillis: Long,
        responseLatencyMs: Long? = null,
        hintCount: Int = 0,
    ): Int
}
