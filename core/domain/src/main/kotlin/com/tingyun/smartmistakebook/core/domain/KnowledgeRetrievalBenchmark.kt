package com.tingyun.smartmistakebook.core.domain

import kotlinx.serialization.Serializable

/**
 * Benchmark suite for evaluating knowledge retrieval quality.
 *
 * Measures:
 * - Recall@K: fraction of relevant knowledge points in top-K results
 * - MRR: mean reciprocal rank of first relevant result
 * - nDCG: normalized discounted cumulative gain
 * - Error mapping rate: fraction of queries that map to wrong knowledge
 * - Grounding coverage: fraction of queries with at least one relevant result
 */
class KnowledgeRetrievalBenchmark(
    private val retriever: KnowledgeRetriever,
) {
    /**
     * Run the full benchmark suite against the provided query set.
     */
    fun runBenchmark(queries: List<BenchmarkQuery>): BenchmarkResult {
        val perQueryResults = queries.map { query ->
            evaluateQuery(query)
        }

        val recallAt1 = perQueryResults.map { it.recallAt1 }.average()
        val recallAt3 = perQueryResults.map { it.recallAt3 }.average()
        val recallAt5 = perQueryResults.map { it.recallAt5 }.average()
        val mrr = perQueryResults.map { it.reciprocalRank }.average()
        val nDCG = perQueryResults.map { it.nDCG }.average()
        val groundingCoverage = perQueryResults.count { it.hasRelevantResult }.toDouble() /
            perQueryResults.size
        val errorMappingRate = perQueryResults.count { it.isErrorMapping }.toDouble() /
            perQueryResults.size

        return BenchmarkResult(
            totalQueries = queries.size,
            recallAt1 = recallAt1,
            recallAt3 = recallAt3,
            recallAt5 = recallAt5,
            meanReciprocalRank = mrr,
            nDCG = nDCG,
            groundingCoverage = groundingCoverage,
            errorMappingRate = errorMappingRate,
            perQueryResults = perQueryResults,
        )
    }

    /**
     * Evaluate a single benchmark query.
     */
    private fun evaluateQuery(query: BenchmarkQuery): QueryResult {
        val retrieved = retriever.retrieve(
            queryText = query.queryText,
            subjectId = query.subjectId,
            limit = 10,
        )

        val retrievedIds = retrieved.map { it.knowledgeNodeId }
        val relevantIds = query.relevantKnowledgePointIds.toSet()

        // Compute metrics
        val top1 = if (retrievedIds.isNotEmpty()) retrievedIds[0] in relevantIds else false
        val top3 = retrievedIds.take(3).count { it in relevantIds }
        val top5 = retrievedIds.take(5).count { it in relevantIds }

        val recallAt1 = if (relevantIds.isNotEmpty()) {
            if (top1) 1.0 else 0.0
        } else {
            0.0
        }

        val recallAt3 = if (relevantIds.isNotEmpty()) {
            top3.toDouble() / relevantIds.size.coerceAtLeast(1)
        } else {
            0.0
        }

        val recallAt5 = if (relevantIds.isNotEmpty()) {
            top5.toDouble() / relevantIds.size.coerceAtLeast(1)
        } else {
            0.0
        }

        // Reciprocal rank of first relevant result
        val firstRelevantIndex = retrievedIds.indexOfFirst { it in relevantIds }
        val reciprocalRank = if (firstRelevantIndex >= 0) {
            1.0 / (firstRelevantIndex + 1)
        } else {
            0.0
        }

        // nDCG computation
        val nDCG = computeNDCG(retrievedIds, relevantIds)

        return QueryResult(
            queryId = query.queryId,
            queryText = query.queryText,
            retrievedIds = retrievedIds,
            relevantIds = relevantIds.toList(),
            recallAt1 = recallAt1,
            recallAt3 = recallAt3,
            recallAt5 = recallAt5,
            reciprocalRank = reciprocalRank,
            nDCG = nDCG,
            hasRelevantResult = retrievedIds.any { it in relevantIds },
            isErrorMapping = checkErrorMapping(retrievedIds, query),
        )
    }

    /**
     * Compute normalized Discounted Cumulative Gain.
     */
    private fun computeNDCG(retrieved: List<String>, relevant: Set<String>): Double {
        if (relevant.isEmpty()) return 0.0

        // DCG
        val dcG = retrieved.take(relevant.size).mapIndexed { index, id ->
            val relevance = if (id in relevant) 1.0 else 0.0
            relevance / kotlin.math.log2(index + 2.0)
        }.sum()

        // Ideal DCG
        val idealDCG = relevant.size.toDouble().let { n ->
            (1..n.toInt()).sumOf { 1.0 / kotlin.math.log2(it + 1.0) }
        }

        return if (idealDCG > 0) dcG / idealDCG else 0.0
    }

    /**
     * Check if this is an error mapping (retrieved results don't match
     * the expected knowledge point category).
     */
    private fun checkErrorMapping(retrieved: List<String>, query: BenchmarkQuery): Boolean {
        if (retrieved.isEmpty()) return false
        // Error mapping: top result is not in relevant set and query has specific category
        val topResult = retrieved[0]
        return topResult !in query.relevantKnowledgePointIds &&
            query.expectedCategory != null
    }

    companion object {
        /**
         * Generate a standard benchmark query set for testing.
         */
        fun defaultBenchmarkQueries(): List<BenchmarkQuery> = listOf(
            // Mathematics queries
            BenchmarkQuery(
                queryId = "math-001",
                queryText = "求解一元二次方程 x² + 5x + 6 = 0",
                relevantKnowledgePointIds = setOf("kp-quadratic-formula", "kp-factoring"),
                subjectId = "math",
                expectedCategory = "algebra",
            ),
            BenchmarkQuery(
                queryId = "math-002",
                queryText = "证明三角形内角和为180度",
                relevantKnowledgePointIds = setOf("kp-triangle-angles", "kp-parallel-lines"),
                subjectId = "math",
                expectedCategory = "geometry",
            ),
            // Physics queries
            BenchmarkQuery(
                queryId = "physics-001",
                queryText = "计算物体自由落体3秒后的速度",
                relevantKnowledgePointIds = setOf("kp-free-fall", "kp-gravity"),
                subjectId = "physics",
                expectedCategory = "mechanics",
            ),
            // Chemistry queries
            BenchmarkQuery(
                queryId = "chem-001",
                queryText = "写出碳酸钠与盐酸反应的化学方程式",
                relevantKnowledgePointIds = setOf("kp-acid-base-reaction", "kp-carbonate"),
                subjectId = "chemistry",
                expectedCategory = "inorganic",
            ),
            // Ambiguous queries (should test robustness)
            BenchmarkQuery(
                queryId = "ambiguous-001",
                queryText = "如何证明",
                relevantKnowledgePointIds = emptySet(),
                subjectId = null,
                expectedCategory = null,
            ),
            // Negative examples (should not match)
            BenchmarkQuery(
                queryId = "negative-001",
                queryText = "Python编程入门",
                relevantKnowledgePointIds = emptySet(),
                subjectId = "math",
                expectedCategory = "programming",
            ),
        )
    }
}

/**
 * A benchmark query with expected results.
 */
@Serializable
data class BenchmarkQuery(
    val queryId: String,
    val queryText: String,
    val relevantKnowledgePointIds: Set<String>,
    val subjectId: String? = null,
    val expectedCategory: String? = null,
)

/**
 * Result of evaluating a single query.
 */
data class QueryResult(
    val queryId: String,
    val queryText: String,
    val retrievedIds: List<String>,
    val relevantIds: List<String>,
    val recallAt1: Double,
    val recallAt3: Double,
    val recallAt5: Double,
    val reciprocalRank: Double,
    val nDCG: Double,
    val hasRelevantResult: Boolean,
    val isErrorMapping: Boolean,
)

/**
 * Overall benchmark result.
 */
data class BenchmarkResult(
    val totalQueries: Int,
    val recallAt1: Double,
    val recallAt3: Double,
    val recallAt5: Double,
    val meanReciprocalRank: Double,
    val nDCG: Double,
    val groundingCoverage: Double,
    val errorMappingRate: Double,
    val perQueryResults: List<QueryResult>,
) {
    /**
     * Check if the benchmark meets quality gates.
     */
    fun meetsQualityGates(): Boolean = recallAt5 >= 0.7 &&
        meanReciprocalRank >= 0.5 &&
        nDCG >= 0.6 &&
        groundingCoverage >= 0.8 &&
        errorMappingRate <= 0.1
}

/**
 * Interface for knowledge retrieval (to be implemented by the actual retriever).
 */
interface KnowledgeRetriever {
    fun retrieve(
        queryText: String,
        subjectId: String?,
        limit: Int,
    ): List<KnowledgeRetrievalResult>
}

/**
 * A single knowledge retrieval result.
 */
data class KnowledgeRetrievalResult(
    val knowledgeNodeId: String,
    val score: Double,
    val matchedTerms: List<String>,
)
