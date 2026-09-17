package com.tingyun.smartmistakebook.core.data.knowledge

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实评测: 用 moe-2025-four-subjects-v1 打包节点(1950 atomic)，按科隔离候选（模拟生产
 * readSubjectKnowledgeRecallCandidates），验证 Recall@5 / Precision@5 / MRR。
 * expected 用"名称子串"近似（题面→节点语义匹配用关键词即可）。
 *
 * 题面与判分口径在 [RetrievalBenchmark]，与 `StagingPackRetrievalBenchmarkTest` 共用同一份。
 */
class FourSubjectRetrievalBenchmarkTest {

    private val pack by lazy {
        BundledKnowledgePackResources.load().single { it.packId == "moe-2025-four-subjects-v1" }
    }

    private val candidates by lazy { RetrievalBenchmark.atomicNodes(pack.nodes) }

    @Test
    fun `real pack retrieval recall@5 precision@5 mrr per subject`() {
        val result = RetrievalBenchmark.run(candidates)
        println(RetrievalBenchmark.report("四科真实检索评测 (按科隔离)", result))
        java.io.File("build/benchmark-metrics.txt").writeText(RetrievalBenchmark.metricsFile(result))

        assertTrue("Recall@5 must be >= 0.85, got ${result.recallAt5}", result.recallAt5 >= 0.85)
        assertTrue("cross-subject must be 0, got ${result.crossSubjectErrors}", result.crossSubjectErrors == 0)
        assertTrue("MRR must be >= 0.7, got ${result.mrr}", result.mrr >= 0.7)
    }
}
