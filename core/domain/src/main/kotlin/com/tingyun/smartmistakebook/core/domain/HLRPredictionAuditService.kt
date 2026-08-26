package com.tingyun.smartmistakebook.core.domain

import kotlin.math.ln
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState

/**
 * HLR-backed student model that produces shadow recall predictions during
 * review planning (acceptance audit §3.3 / §6.3 / §6.4). The predictions are
 * never fed back into scheduling; they exist only so that later real outcomes
 * can backfill [com.tingyun.smartmistakebook.core.model.PredictionOutcome]s
 * and drive calibration.
 */
class HLRPredictionAuditService(
    private val predictor: HalfLifeRegressionPredictor = HalfLifeRegressionPredictor(),
    private val forgettingCurve: ForgettingCurve = ForgettingCurve(),
    private val predictionWindowMillis: Long = DEFAULT_PREDICTION_WINDOW_MILLIS,
    private val conservativeCoefficient: Double = DEFAULT_CONSERVATIVE_COEFFICIENT,
) : StudentModel, RecallPredictionService {

    init {
        require(predictionWindowMillis > 0) { "Prediction window must be positive" }
        require(
            conservativeCoefficient.isFinite() && conservativeCoefficient in 0.0..1.0,
        ) { "Conservative coefficient must be in 0..1" }
    }

    /** Versioned identity shared with [HLRShadowModeManager] so audit rows stay joinable. */
    val modelVersion: LearningModelVersion = LearningModelVersion(
        modelId = MODEL_ID,
        version = MODEL_VERSION_STRING,
        algorithmHash = ALGORITHM_HASH,
    )

    override val version: StudentModelVersion = StudentModelVersion(
        modelId = MODEL_ID,
        version = MODEL_VERSION_STRING,
        algorithmHash = ALGORITHM_HASH,
    )

    override fun predict(input: RecallPredictionInput): RecallPrediction = predictRecall(input)

    override fun predictRecall(input: RecallPredictionInput): RecallPrediction {
        val probability = predictor.predict(input.features, input.deltaSeconds)
        return RecallPrediction(
            predictionId = "shadow-${input.practiceUnitId}-${input.nowEpochMillis}",
            modelVersion = "${version.modelId}:${version.version}",
            probability = probability,
            horizonMillis = predictionWindowMillis,
            conservativeScore = probability * conservativeCoefficient,
            featureFingerprint = input.features.toString().hashCode().toString(),
            generatedAtEpochMillis = input.nowEpochMillis,
        )
    }

    override fun projectEvidence(input: EvidenceProjectionInput): EvidenceProjection {
        val estimate = forgettingCurve.estimateAt(input.memory, input.nowEpochMillis)
        return EvidenceProjection(
            practiceUnitId = input.practiceUnitId,
            estimatedRetention = estimate.probability,
            halfLifeSeconds = predictor.computeHalfLife(
                extractHlrFeaturesForShadow(
                    memory = input.memory,
                    mastery = null,
                    difficulty = input.memory.difficulty,
                    nowEpochMillis = input.nowEpochMillis,
                ),
            ),
            projectedAtEpochMillis = input.nowEpochMillis,
        )
    }

    /**
     * Shadow predictions for every scored candidate that has a memory state,
     * mirroring the fields the planner previously assembled inline. Returns an
     * empty list when nothing can be predicted; callers persist via a
     * [PredictionAuditSink].
     */
    fun planPredictions(
        request: ReviewPlanningRequest,
        scoredPracticeUnitIds: List<String>,
        lastResponseLatenciesSeconds: Map<String, Int?> = emptyMap(),
    ): List<RecallPredictionAudit> {
        val now = request.planningAtEpochMillis
        val snapshot = request.learnerSnapshot
        val candidatesByUnit = request.candidates.associateBy(ReviewCandidate::practiceUnitId)
        return scoredPracticeUnitIds.mapNotNull { practiceUnitId ->
            val candidate = candidatesByUnit[practiceUnitId] ?: return@mapNotNull null
            val memory = snapshot.problemMemoryStates[practiceUnitId] ?: return@mapNotNull null
            val mastery = candidate.knowledgeNodeIds
                .mapNotNull(snapshot.knowledgeMasteryStates::get)
                .firstOrNull()
            val prediction = predictRecall(
                RecallPredictionInput(
                    practiceUnitId = practiceUnitId,
                    features = extractHlrFeaturesForShadow(
                        memory = memory,
                        mastery = mastery,
                        difficulty = candidate.difficulty,
                        nowEpochMillis = now,
                        lastResponseDurationSeconds = lastResponseLatenciesSeconds[practiceUnitId],
                    ),
                    deltaSeconds = (now - memory.lastReviewedAtEpochMillis) / 1000.0,
                    nowEpochMillis = now,
                ),
            )
            RecallPredictionAudit(
                prediction = prediction,
                practiceUnitId = practiceUnitId,
                knowledgeNodeId = candidate.knowledgeNodeIds.firstOrNull(),
                windowStartEpochMillis = now,
                windowEndEpochMillis = now + predictionWindowMillis,
            )
        }
    }

    companion object {
        const val MODEL_ID = "hlr-shadow-v1"
        const val MODEL_VERSION_STRING = "0.1.0-experimental"
        const val ALGORITHM_HASH = "hlr-recall-v1"
        const val DEFAULT_PREDICTION_WINDOW_MILLIS = 7L * 86_400_000L
        const val DEFAULT_CONSERVATIVE_COEFFICIENT = 0.9
    }
}

/** Extract HLR features from the current memory/mastery state. */
internal fun extractHlrFeaturesForShadow(
    memory: ProblemMemoryState,
    mastery: KnowledgeMasteryState?,
    difficulty: Double,
    nowEpochMillis: Long,
    lastResponseDurationSeconds: Int? = null,
): HLRFeatures {
    val daysSinceFirstSeen = (nowEpochMillis -
        (memory.lastReviewedAtEpochMillis - memory.stabilityDays * 86_400_000L))
        .toDouble() / 86_400_000.0
    val timeBetweenReviewsDays = memory.stabilityDays
    val consecutiveCorrectStreak =
        (memory.independentCorrectCount - memory.lapseCount).coerceAtLeast(0).toDouble()
    return HLRFeatures(
        independentCorrectCount = memory.independentCorrectCount.toDouble(),
        assistedCorrectCount = memory.assistedCorrectCount.toDouble(),
        lapseCount = memory.lapseCount.toDouble(),
        answerRevealCount = memory.answerRevealCount.toDouble(),
        evidenceMass = mastery?.evidenceMass ?: 0.0,
        difficulty = difficulty,
        timeBetweenReviewsDays = timeBetweenReviewsDays,
        daysSinceFirstSeen = daysSinceFirstSeen.coerceAtLeast(0.0),
        consecutiveCorrectStreak = consecutiveCorrectStreak,
        // Log-normalized against a 5-minute cap so slow handwriting tops out
        // near 1.0 while instant recalls sit near 0 (QA item B1).
        lastResponseLatencyNormalized = lastResponseDurationSeconds
            ?.let { seconds ->
                val clamped = seconds.coerceIn(0, 300)
                ln(1.0 + clamped) / ln(301.0)
            }
            ?: 0.0,
    )
}

