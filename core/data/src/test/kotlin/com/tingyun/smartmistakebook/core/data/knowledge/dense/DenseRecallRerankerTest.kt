package com.tingyun.smartmistakebook.core.data.knowledge.dense

import com.tingyun.smartmistakebook.core.database.DenseRecallCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端侧稠密腿的两件事，一个文件两段：
 *
 * 1. **静默回退**（任务书第 5 条）：模型/资产缺失、加载失败、推理抛错 → `order` 返回 `null`
 *    （存储层据此保留纯词面结果，绝不崩、绝不返回空）。用假加载失败注入，不碰 LiteRT
 *    ——模型推理是 Android-only，JVM 测试不许依赖它。
 * 2. **端到端次序对拍**：假编码器喂 Python 侧记录的查询向量，其余全走真实实现
 *    （真资产 28,932 条 → 学科扫描 → 节点 max → α=0.5 融合 → 排序），与
 *    `tools/dense_build/stage3_device_sim.py` 的**端侧算法期望次序**逐条比对。
 *    这一段覆盖的是"除了模型本身之外"的整条链路。
 */
class DenseRecallRerankerTest {

    private val candidates = listOf(
        DenseRecallCandidate("kb:moe-2025-four-subjects-v1:physics:atomic:合力范围", 3),
        DenseRecallCandidate("kb:moe-2025-four-subjects-v1:physics:atomic:矢量和标量", 1),
    )

    private val assetBytes: ByteArray by lazy { DenseTestFixtures.bytes(VECTOR_RESOURCE) }
    private val asset: DenseVectorAsset by lazy {
        DenseVectorAsset.read(assetBytes, expectedSha256 = DenseVectorAsset.sha256(assetBytes))
    }

    @Test
    fun `model load failure falls back to the lexical order`() = runBlocking {
        val reranker = OnDeviceDenseRecallReranker(
            tokenizer = { error("tokenizer must not be needed when the model is missing") },
            asset = { asset },
            encoder = { throw IllegalStateException("dense model asset is missing: dense/x.tflite") },
        )
        assertNull(reranker.order("PHYSICS", "合力范围", candidates))
    }

    @Test
    fun `tokenizer or asset failure falls back as well`() = runBlocking {
        // 真实装配里编码器会先取词表（LiteRtDenseQueryEncoder.openFromAssets(..., tokenizer =
        // tokenizer())），这里照同一条路径注入失败，确保"词表缺失"也走回退而不是崩。
        val tokenizerFailure = OnDeviceDenseRecallReranker(
            tokenizer = { throw IllegalStateException("vocab missing") },
            asset = { asset },
            encoder = { throw IllegalStateException("never reached: tokenizer failed") },
        )
        assertNull(tokenizerFailure.order("PHYSICS", "合力范围", candidates))

        val assetFailure = OnDeviceDenseRecallReranker(
            tokenizer = { error("unused") },
            asset = { throw IllegalStateException("asset sha mismatch") },
            encoder = { object : DenseQueryEncoder { override fun encode(text: String) = zeroQuery() } },
        )
        assertNull(assetFailure.order("PHYSICS", "合力范围", candidates))
    }

    @Test
    fun `empty candidate set short circuits without touching the encoder`() = runBlocking {
        var encoderTouched = false
        val reranker = OnDeviceDenseRecallReranker(
            tokenizer = { error("unused") },
            asset = { error("unused") },
            encoder = {
                encoderTouched = true
                object : DenseQueryEncoder { override fun encode(text: String) = zeroQuery() }
            },
        )
        assertNull(reranker.order("PHYSICS", "合力范围", emptyList()))
        assertTrue("空候选集不该触发模型加载", !encoderTouched)
    }

    /**
     * 假编码器产出的查询向量：**维度必须与真实资产一致**（换件后资产 768 维，写死 512 会让
     * `DenseVectorAsset.cosine` 的 `require(query.size == dim)` 抛错 ⇒ 稠密腿被判不可用，
     * 测试断言的是"回退"而不是它想测的那件事）。所以维度从资产自身取。
     */
    private fun zeroQuery(): FloatArray = FloatArray(asset.dim)

    @Test
    fun `a wrong subject never produces scores for another subject`() = runBlocking {
        val reranker = OnDeviceDenseRecallReranker(
            tokenizer = { error("unused") },
            asset = { asset },
            encoder = { object : DenseQueryEncoder { override fun encode(text: String) = zeroQuery() } },
        )
        // 全零查询向量：余弦全 0 ⇒ 稠密腿 min-max 退化为全 0（hi<=lo）⇒ 次序 = 词面次序。
        val ordered = reranker.order("PHYSICS", "任何查询", candidates)
        assertEquals(candidates, ordered)
    }

    @Test
    fun `real asset scan and fusion reproduce the python device order`() = runBlocking {
        val cases = parseCases()
        assertEquals("期望次序 fixture 的查询数变了（每科 1 条）", 4, cases.size)
        assertEquals(
            "候选集条数变了（fixture 重新生成过？逐条核实后再改这里）",
            // 2026-09-25 WP3：56 条材料改绑 + 别名跟着绑定走（25,360→25,359）后词面腿的每查询
            // 命中集变了 ⇒ case#36 209→212、case#63 248→249（已逐条核对：这四个数与
            // build/production-lexical-leg.tsv 同一查询的行数逐位相等）。
            mapOf(0 to 85, 9 to 455, 36 to 212, 63 to 249),
            cases.associate { it.index to it.candidates.size },
        )
        assertEquals("候选总数变了", 1001, cases.sumOf { it.candidates.size })
        for (case in cases) {
            // 假编码器：模型件不在仓库里（见 assets/dense/README.md），喂 Python 侧记录的查询向量。
            val encoder = object : DenseQueryEncoder {
                override fun encode(text: String): FloatArray = case.query
            }
            val reranker = OnDeviceDenseRecallReranker(
                tokenizer = { error("unused with the fake encoder") },
                asset = { asset },
                encoder = { encoder },
            )
            val ordered = reranker.order(case.subject, "查询文本由假编码器旁路", case.candidates)
            assertEquals("case#${case.index} 必须只重排、不改成员", case.candidates.size, ordered?.size)
            assertEquals(
                "case#${case.index}（${case.subject}）次序与 Python 端侧算法不一致",
                case.expectedOrder,
                ordered?.map(DenseRecallCandidate::knowledgeNodeId),
            )
            assertEquals(
                "case#${case.index} 成员集合必须与入参一致",
                case.candidates.map(DenseRecallCandidate::knowledgeNodeId).toSet(),
                ordered?.map(DenseRecallCandidate::knowledgeNodeId)?.toSet(),
            )
        }
    }

    private data class DeviceCase(
        val index: Int,
        val subject: String,
        val query: FloatArray,
        val candidates: List<DenseRecallCandidate>,
        val expectedOrder: List<String>,
    )

    private fun parseCases(): List<DeviceCase> {
        val cases = mutableListOf<DeviceCase>()
        var index = -1
        var subject = ""
        var query = FloatArray(0)
        var expected: List<String> = emptyList()
        val pending = mutableListOf<DenseRecallCandidate>()
        fun flush() {
            if (index < 0) return
            cases += DeviceCase(index, subject, query, pending.toList(), expected)
            pending.clear()
        }
        for (row in rowsOf()) {
            when (row[0]) {
                "[case]" -> {
                    flush()
                    index = row[1].toInt()
                    subject = row[2]
                    query = row[3].split(' ').map(String::toFloat).toFloatArray()
                    expected = emptyList()
                }
                "[candidate]" -> pending += DenseRecallCandidate(row[1], row[2].toInt())
                "[expected]" -> expected = row[1].split(',')
            }
        }
        flush()
        return cases
    }

    private fun rowsOf(): List<List<String>> {
        val raw = DenseTestFixtures.lines(DEVICE_ORDER_FIXTURE)
        return raw.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }
    }

    private companion object {
        const val VECTOR_RESOURCE = "knowledge/dense/bge-small-zh-int8.vec"
        const val DEVICE_ORDER_FIXTURE = "dense/dense-device-order-reference.txt"
    }
}
