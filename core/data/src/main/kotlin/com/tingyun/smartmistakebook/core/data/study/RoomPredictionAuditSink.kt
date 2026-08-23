package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.port.StudentModelPredictionRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.PredictionAuditSink
import com.tingyun.smartmistakebook.core.domain.RecallPredictionAudit

/**
 * Room-backed prediction audit sink (acceptance audit §6.3). Every operation
 * degrades silently: shadow audit failures must never reach user-visible flows.
 */
internal class RoomPredictionAuditSink(
    private val database: StudyDatabasePort,
) : PredictionAuditSink {

    override suspend fun record(prediction: RecallPredictionAudit) {
        runCatching {
            database.recordStudentModelPredictions(
                listOf(
                    StudentModelPredictionRecord(
                        predictionId = prediction.prediction.predictionId,
                        modelId = HLR_MODEL_ID,
                        modelVersion = HLR_MODEL_VERSION,
                        algorithmHash = HLR_ALGORITHM_HASH,
                        practiceUnitId = prediction.practiceUnitId,
                        knowledgeNodeId = prediction.knowledgeNodeId,
                        featureFingerprint = prediction.prediction.featureFingerprint,
                        predictedScore = prediction.prediction.probability,
                        conservativeScore = prediction.prediction.conservativeScore,
                        predictionWindowStartEpochMillis = prediction.windowStartEpochMillis,
                        predictionWindowEndEpochMillis = prediction.windowEndEpochMillis,
                        predictedAtEpochMillis = prediction.prediction.generatedAtEpochMillis,
                    ),
                ),
            )
        }
    }

    override suspend fun resolveOutcome(
        practiceUnitId: String,
        wasIndependentCorrect: Boolean,
        observedAtEpochMillis: Long,
        responseLatencyMs: Long?,
        hintCount: Int,
    ): Int = runCatching {
        database.resolveStudentModelPredictions(
            practiceUnitId = practiceUnitId,
            wasIndependentCorrect = wasIndependentCorrect,
            observedAtEpochMillis = observedAtEpochMillis,
            responseLatencyMs = responseLatencyMs,
            hintCount = hintCount,
        )
    }.getOrDefault(0)

    private companion object {
        const val HLR_MODEL_ID = "hlr-shadow-v1"
        const val HLR_MODEL_VERSION = "0.1.0-experimental"
        const val HLR_ALGORITHM_HASH = "hlr-recall-v1"
    }
}
