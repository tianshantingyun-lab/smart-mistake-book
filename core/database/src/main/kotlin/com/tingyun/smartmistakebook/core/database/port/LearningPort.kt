package com.tingyun.smartmistakebook.core.database.port

import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for learning event operations.
 */
interface LearningEventReadPort {
    /**
     * Observe learning events for a specific problem.
     */
    fun observeEvents(problemId: String): Flow<List<LearningEventSummary>>

    /**
     * Get learning events for a knowledge node.
     */
    suspend fun getEventsForKnowledgeNode(knowledgeNodeId: String): List<LearningEventSummary>

    /**
     * Get learning event count.
     */
    suspend fun countEvents(): Int

    /**
     * Get learning events in a time range.
     */
    suspend fun getEventsInRange(
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): List<LearningEventSummary>
}

/**
 * Read-only port for learning projection operations.
 */
interface LearningProjectionReadPort {
    /**
     * Get the current learner snapshot.
     */
    suspend fun getCurrentSnapshot(learnerId: String): com.tingyun.smartmistakebook.core.model.LearnerSnapshot?

    /**
     * Get the projection checkpoint.
     */
    suspend fun getCheckpoint(learnerId: String): ProjectionCheckpointSummary?

    /**
     * Get knowledge mastery state for a specific node.
     */
    suspend fun getMasteryState(
        learnerId: String,
        knowledgeNodeId: String,
    ): MasteryStateSummary?
}

/**
 * Summary of a learning event for read operations.
 */
data class LearningEventSummary(
    val eventId: String,
    val eventType: String,
    val problemId: String,
    val occurredAtEpochMillis: Long,
    val isCorrect: Boolean,
)

/**
 * Summary of a projection checkpoint.
 */
data class ProjectionCheckpointSummary(
    val lastSequence: Long,
    val projectorVersion: String,
    val projectedAtEpochMillis: Long,
)

/**
 * Summary of mastery state for read operations.
 */
data class MasteryStateSummary(
    val knowledgeNodeId: String,
    val probabilityIndependentCorrect: Double,
    val lowerBoundIndependentCorrect: Double,
    val evidenceMass: Double,
    val independentCorrectCount: Int,
    val lastEvidenceAtEpochMillis: Long?,
)
