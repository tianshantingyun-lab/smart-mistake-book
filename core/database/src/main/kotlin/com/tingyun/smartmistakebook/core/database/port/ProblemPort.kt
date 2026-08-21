package com.tingyun.smartmistakebook.core.database.port

import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for problem operations. Each business module should depend
 * only on this minimal interface rather than the full database.
 */
interface ProblemReadPort {
    /**
     * Observe all active problems.
     */
    fun observeProblems(): Flow<List<ProblemSummary>>

    /**
     * Get a problem by ID.
     */
    suspend fun getProblem(problemId: String): ProblemSummary?

    /**
     * Get problem count.
     */
    suspend fun countProblems(): Int

    /**
     * Check if a problem exists.
     */
    suspend fun problemExists(problemId: String): Boolean
}

/**
 * Read-only port for problem revision operations.
 */
interface ProblemRevisionReadPort {
    /**
     * Get a specific revision of a problem.
     */
    suspend fun getRevision(problemRevisionId: String): ProblemRevisionSummary?

    /**
     * Get all revisions for a problem.
     */
    suspend fun getRevisions(problemId: String): List<ProblemRevisionSummary>

    /**
     * Get the latest revision for a problem.
     */
    suspend fun getLatestRevision(problemId: String): ProblemRevisionSummary?

    /**
     * Get revision count for a problem.
     */
    suspend fun countRevisions(problemId: String): Int
}

/**
 * Summary of a problem for read operations.
 */
data class ProblemSummary(
    val problemId: String,
    val subject: String,
    val createdAtEpochMillis: Long,
    val archivedAtEpochMillis: Long? = null,
)

/**
 * Summary of a problem revision for read operations.
 */
data class ProblemRevisionSummary(
    val problemRevisionId: String,
    val problemId: String,
    val revisionNumber: Int,
    val markdown: String,
    val createdAtEpochMillis: Long,
)
