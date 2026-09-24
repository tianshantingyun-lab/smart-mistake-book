package com.tingyun.smartmistakebook.core.data.knowledge.dense

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向量资产（`.vec`）的端侧读取与数值口径对拍。
 *
 * 期望值全部来自 Python 参考侧（`tools/dense_build/gen_dense_device_fixture.py`，用
 * `dense_asset.py` 的权威布局/量化函数与真实资产字节算出）：
 * - `[asset]`：头字段 + sha256；
 * - `[quantize]`：`quantize_int8` 的 (int8, scale) 期望值（含 `max=0` 退化）；
 * - `[dot]`：`scale · Σ int8·query` 的期望余弦；
 * - `[node]`：4 条金标查询（每科 1 条）在**该科全部原子节点**上的节点分（= 向量 cosine 的 max）
 *   ——端侧扫描 + 取 max + 学科过滤整条链路一次对齐。
 */
class DenseVectorAssetTest {

    private val assetRows = DenseTestFixtures.rows(SCAN_FIXTURE)
    private val assetHeader: Map<String, String> = assetRows.first { it[0] == "[asset]" }
        .drop(1)
        .associate { entry ->
            val (key, value) = entry.split('=', limit = 2)
            key to value
        }

    @Test
    fun `real asset header and sha match the python reference`() {
        val bytes = DenseTestFixtures.bytes(VECTOR_RESOURCE)
        val expectedSha = assetHeader.getValue("sha256")
        assertEquals("资产 sha256 变了（资产过期或 fixture 过期）", expectedSha, DenseVectorAsset.sha256(bytes))
        val asset = DenseVectorAsset.read(bytes, expectedSha256 = expectedSha)
        assertEquals(assetHeader.getValue("magic"), DenseVectorAsset.MAGIC)
        assertEquals(assetHeader.getValue("version").toInt(), DenseVectorAsset.VERSION)
        assertEquals(assetHeader.getValue("dtypeCode").toInt(), DenseVectorAsset.DTYPE_INT8_PER_VECTOR_SCALE)
        assertEquals(assetHeader.getValue("dim").toInt(), asset.dim)
        assertEquals(assetHeader.getValue("count").toInt(), asset.count)
        // 头 + ids + 矩阵 + scale 的字节账（对不上就说明头被改过或文件被截断）
        val expectedBytes = DenseVectorAsset.HEADER_BYTES +
            assetHeader.getValue("idsBytesLength").toInt() + asset.dim * asset.count + asset.count * 4
        assertEquals(assetHeader.getValue("expectedBytes").toInt(), expectedBytes)
        assertEquals(bytes.size, expectedBytes)
        assertEquals(3572, asset.nodeCount)
    }

    @Test
    fun `asset with a foreign sha is rejected`() {
        val bytes = DenseTestFixtures.bytes(VECTOR_RESOURCE)
        val wrong = "0".repeat(64)
        val failure = runCatching { DenseVectorAsset.read(bytes, expectedSha256 = wrong) }.exceptionOrNull()
        assertTrue("sha 不符必须判资产不可用：$failure", failure != null)
    }

    @Test
    fun `quantize and dequantize follow the packing rule used by the asset`() {
        val quantizeRows = assetRows.filter { it[0] == "[quantize]" }
        assertEquals("quantize 用例数变了", 4, quantizeRows.size)
        for (row in quantizeRows) {
            val label = row[1]
            val input = row[2].split(' ').map(String::toFloat).toFloatArray()
            val expectedQuantized = row[3].split(' ').map { it.toFloat().toInt().toByte() }
            val expectedScale = row[4].toFloat()
            val quantized = DenseVectorQuantizer.quantize(input)
            assertEquals("$label scale", expectedScale, quantized.scale, 0f)
            assertEquals("$label int8", expectedQuantized, quantized.values.toList())
            val restored = DenseVectorQuantizer.dequantize(quantized)
            for (index in input.indices) {
                val tolerance = if (expectedScale > 0f) expectedScale else 1f
                assertEquals("$label 还原 $index", input[index], restored[index], tolerance / 2f + 1e-6f)
            }
        }
    }

    @Test
    fun `cosine on a synthetic asset reproduces the python dot reference`() {
        val dotRows = assetRows.filter { it[0] == "[dot]" }
        assertEquals("dot 用例数变了", 3, dotRows.size)
        for (row in dotRows) {
            val label = row[1]
            val int8 = row[2].split(' ').map { it.toFloat().toInt().toByte() }.toByteArray()
            val query = row[3].split(' ').map(String::toFloat).toFloatArray()
            val scale = row[4].toFloat()
            val expected = row[5].toDouble()
            val asset = syntheticAsset(int8, scale, "+")
            assertEquals("$label cosine", expected, asset.cosine(query, 0).toDouble(), 1e-6)
        }
    }

    @Test
    fun `node scores for every subject match the python reference scan`() {
        val bytes = DenseTestFixtures.bytes(VECTOR_RESOURCE)
        val asset = DenseVectorAsset.read(bytes, expectedSha256 = assetHeader.getValue("sha256"))
        val scans = parseScans()
        assertEquals("scan 查询数变了（每科 1 条）", 4, scans.size)
        assertEquals("节点行总数变了", 3572, scans.sumOf { it.expected.size })
        for (scan in scans) {
            val scores = asset.nodeScores(scan.query, scan.subject.lowercase())
            assertEquals("scan#${scan.index} 学科=${scan.subject} 的节点数", scan.expected.size, scores.size)
            var worst = 0.0
            var worstNode = ""
            for ((nodeId, expectedScore) in scan.expected) {
                val actual = scores[nodeId]?.toDouble()
                assertTrue("scan#${scan.index} 缺节点 $nodeId", actual != null)
                val delta = Math.abs(actual!! - expectedScore)
                if (delta > worst) {
                    worst = delta
                    worstNode = nodeId
                }
            }
            assertTrue(
                "scan#${scan.index} 节点分偏差过大：worst=$worst @$worstNode（容差 1e-5；偏差来自累加顺序）",
                worst <= 1e-5,
            )
        }
    }

    private data class Scan(val index: Int, val subject: String, val query: FloatArray, val expected: Map<String, Double>)

    private fun parseScans(): List<Scan> {
        val scans = mutableListOf<Scan>()
        var pending: Scan? = null
        val expected = LinkedHashMap<String, Double>()
        for (row in assetRows) {
            when (row[0]) {
                "[scan]" -> {
                    pending?.let { scans += it.copy(expected = LinkedHashMap(expected)) }
                    expected.clear()
                    pending = Scan(
                        index = row[1].toInt(),
                        subject = row[2],
                        query = row[3].split(' ').map(String::toFloat).toFloatArray(),
                        expected = emptyMap(),
                    )
                }
                "[node]" -> expected[row[1]] = row[2].toDouble()
            }
        }
        pending?.let { scans += it.copy(expected = LinkedHashMap(expected)) }
        return scans
    }

    /** 用最小合法 `.vec` 串起"解析 → 点积"的真路径（而不是在测试里重写公式）。 */
    private fun syntheticAsset(int8: ByteArray, scale: Float, idSuffix: String): DenseVectorAsset {
        val nodeId = "kb:probe:physics:atomic:$idSuffix"
        val idBytes = nodeId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(
            DenseVectorAsset.HEADER_BYTES + 4 + idBytes.size + int8.size + 4,
        ).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("SMBV".toByteArray(Charsets.US_ASCII))
        buffer.putInt(DenseVectorAsset.VERSION)
        buffer.putInt(int8.size)
        buffer.putInt(1)
        buffer.putInt(DenseVectorAsset.DTYPE_INT8_PER_VECTOR_SCALE)
        buffer.putInt(4 + idBytes.size)
        buffer.putInt(idBytes.size)
        buffer.put(idBytes)
        buffer.put(int8)
        buffer.putFloat(scale)
        return DenseVectorAsset.read(buffer.array(), expectedSha256 = null)
    }

    private companion object {
        const val SCAN_FIXTURE = "dense/dense-scan-reference.txt"
        const val VECTOR_RESOURCE = "knowledge/dense/bge-small-zh-int8.vec"
    }
}
