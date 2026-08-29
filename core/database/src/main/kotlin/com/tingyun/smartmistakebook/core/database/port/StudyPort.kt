package com.tingyun.smartmistakebook.core.database.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Read-only port for the learning ledger head sequence.
 */
interface LearningLedgerPort {
    fun observeLearningLedgerHead(learnerId: String): Flow<Long> = flowOf(0L)
}

/**
 * Port for the student-model prediction audit loop (PR-07).
 */
interface PredictionAuditPort {
    /** Persist shadow predictions produced during review planning (audit PR-07). */
    suspend fun recordStudentModelPredictions(predictions: List<StudentModelPredictionRecord>) = Unit

    /**
     * Resolve every unresolved prediction for [practiceUnitId] whose window
     * contains [observedAtEpochMillis] with the real attempt outcome.
     * @return number of predictions resolved.
     */
    suspend fun resolveStudentModelPredictions(
        practiceUnitId: String,
        wasIndependentCorrect: Boolean,
        observedAtEpochMillis: Long,
        responseLatencyMs: Long? = null,
        hintCount: Int = 0,
    ): Int = 0

    /** Resolved prediction/outcome pairs for offline calibration. */
    suspend fun readResolvedStudentModelPredictions(
        modelId: String,
        modelVersion: String,
    ): List<ResolvedStudentModelPredictionRecord> = emptyList()

    /** Last observed latency (ms) for a practice unit, used as HLR latency feature. */
    suspend fun findLastPredictionLatencyMs(practiceUnitId: String): Long? = null
}

/**
 * Port for the visual-interaction audit trail (PR-11).
 */
interface VisualInteractionPort {
    /** Persist one locally judged visual-interaction attempt (audit PR-11). */
    suspend fun recordVisualInteractionAttempt(
        attempt: VisualInteractionAttemptRecord,
    ) = Unit

    /** Visual-interaction attempts recorded for one problem revision. */
    suspend fun readVisualInteractionAttempts(
        problemRevisionId: String,
    ): List<VisualInteractionAttemptRecord> = emptyList()

    /**
     * Accepted knowledge bindings for one practice unit. Judged visual
     * evidence may only enter the mastery ledger through a confirmed
     * binding; an empty list keeps the evidence audit-only.
     */
    suspend fun readPracticeUnitKnowledgeBindings(
        practiceUnitId: String,
    ): List<PracticeUnitKnowledgeBindingRecord> = emptyList()

    /** Diagnostic: the SQLite PRAGMA user_version of the opened database. */
    suspend fun readDatabaseVersion(): Int = 0

    /**
     * Pseudo-KC fallback (spec mastery-scheduling §3.4): idempotently ensures
     * the subject-scoped pseudo knowledge node (`pseudo:<subject>`) and a
     * practice-unit binding for this exact revision/taxonomy pair exist, so
     * an unbound question can carry mastery evidence through the standard
     * attribution path. Returns the binding record to attribute against.
     */
    suspend fun ensurePseudoKnowledgeBinding(
        practiceUnitId: String,
        problemRevisionId: String,
        taxonomyVersion: String,
        subject: String,
        acceptedAtEpochMillis: Long,
    ): PracticeUnitKnowledgeBindingRecord? = null
}

/** Port-level prediction record for the student-model audit loop (PR-07). */
data class StudentModelPredictionRecord(
    val predictionId: String,
    val modelId: String,
    val modelVersion: String,
    val algorithmHash: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String?,
    val featureFingerprint: String,
    val predictedScore: Double,
    val conservativeScore: Double,
    val predictionWindowStartEpochMillis: Long,
    val predictionWindowEndEpochMillis: Long,
    val predictedAtEpochMillis: Long,
)

/** Resolved prediction with its real outcome, ready for calibration. */
data class ResolvedStudentModelPredictionRecord(
    val predictionId: String,
    val modelId: String,
    val modelVersion: String,
    val algorithmHash: String,
    val predictedScore: Double,
    val conservativeScore: Double,
    val wasIndependentCorrect: Boolean,
    val observedAtEpochMillis: Long,
)

/** Port-level record for the visual-interaction audit trail (PR-11). */
data class VisualInteractionAttemptRecord(
    val attemptId: String,
    val problemRevisionId: String,
    val actionKind: String,
    val actionPayload: String,
    val feasible: Boolean,
    val feedback: String,
    val attemptedAtEpochMillis: Long,
)

/** Port-level record of one accepted practice-unit/knowledge binding. */
data class PracticeUnitKnowledgeBindingRecord(
    val bindingId: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val basisRevisionId: String,
    val taxonomyVersion: String,
    val acceptedAtEpochMillis: Long,
)