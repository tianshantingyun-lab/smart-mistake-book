package com.tingyun.smartmistakebook.core.data.knowledge.dense

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * 随包分发的稠密向量资产（`.vec` v1）的端侧读取器。
 *
 * 格式的**权威定义**在 `tools/dense_build/dense_asset.py`（写、读、CI 门共用同一份）；
 * 这里是端侧的第二个读实现，因此：
 * - 头字段**逐个校验**（magic/version/dtype/dim/count + 字节数精确对账），
 * - 并按旁车记录的 sha256 校验身份（"这份资产是不是离线出数用的那一份"）。
 *
 * **内存形状**（任务书：不整表解成对象）：一个 `ByteArray`（28,932×512 int8 = 14.8MB）
 * + 一个 `FloatArray`（28,932 scales），**不建 28,932 个向量对象**；节点分在扫描时按
 * "同一节点的向量在矩阵里连续"（打包侧的布局约定）就地取 max。
 */
internal class DenseVectorAsset private constructor(
    val sha256: String,
    val dim: Int,
    val count: Int,
    private val nodeIdAt: Array<String>,
    private val nodeStart: IntArray,
    private val nodeEnd: IntArray,
    private val matrix: ByteArray,
    private val scales: FloatArray,
) {
    /** 资产里的节点数（= 3,572 条原子节点；同一节点的向量行连续）。 */
    val nodeCount: Int get() = nodeIdAt.size

    fun nodeIdAt(index: Int): String = nodeIdAt[index]

    /** 单行向量与查询向量的余弦（行已 L2 归一化 ⇒ 点积即余弦；还原 = int8 × per-vector scale）。 */
    fun cosine(query: FloatArray, row: Int): Float {
        require(query.size == dim) { "query dim ${query.size} != asset dim $dim" }
        val base = row * dim
        var accumulator = 0f
        for (index in 0 until dim) {
            accumulator += matrix[base + index] * query[index]
        }
        return accumulator * scales[row]
    }

    /**
     * 该科**全部有向量的原子节点**的节点分（= 节点全部向量 cosine 的 max）。
     *
     * 返回的键集同时就是融合时稠密腿的 min-max 域（与离线判分器的 `domain_by_subject`
     * 同口径：整科原子节点，而不是"召回集里的节点"——口径见 spec §2.4 与本文件注释）。
     */
    fun nodeScores(query: FloatArray, subjectKey: String): Map<String, Float> {
        require(query.size == dim) { "query dim ${query.size} != asset dim $dim" }
        val scores = HashMap<String, Float>(1024)
        for (node in 0 until nodeIdAt.size) {
            val nodeId = nodeIdAt[node]
            if (subjectKeyOf(nodeId) != subjectKey) continue
            var best = Float.NEGATIVE_INFINITY
            for (row in nodeStart[node] until nodeEnd[node]) {
                val value = cosine(query, row)
                if (value > best) best = value
            }
            scores[nodeId] = best
        }
        return scores
    }

    companion object {
        const val MAGIC = "SMBV"
        const val VERSION = 1
        const val DTYPE_INT8_PER_VECTOR_SCALE = 1
        const val HEADER_BYTES = 24

        /**
         * 解析 + 校验。`expectedSha256` 非空时**必须**相等（身份校验：这份字节是不是
         * 离线出数/旁车记录的那一份），否则抛错——调用方按"资产不可用"回退。
         */
        fun read(bytes: ByteArray, expectedSha256: String? = null): DenseVectorAsset {
            if (bytes.size < HEADER_BYTES) error("dense asset is shorter than its header (${bytes.size} B)")
            val actualSha = if (expectedSha256 != null) sha256(bytes) else ""
            if (expectedSha256 != null && actualSha != expectedSha256) {
                error("dense asset sha256 mismatch: $actualSha != $expectedSha256")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }.decodeToString()
            if (magic != MAGIC) error("dense asset magic mismatch: $magic")
            val version = buffer.int
            if (version != VERSION) error("dense asset version $version is not supported")
            val dim = buffer.int
            val count = buffer.int
            val dtype = buffer.int
            val idsBytes = buffer.int
            if (dtype != DTYPE_INT8_PER_VECTOR_SCALE) error("dense asset dtype code $dtype is not supported")
            if (dim <= 0 || count <= 0) error("dense asset shape is invalid: dim=$dim count=$count")
            val expectedBytes = HEADER_BYTES + idsBytes + count * dim + count * 4
            if (bytes.size != expectedBytes) {
                error("dense asset length ${bytes.size} != layout $expectedBytes")
            }
            val ids = ArrayList<String>(count)
            val matrixStart = HEADER_BYTES + idsBytes
            repeat(count) {
                val length = buffer.int
                require(length > 0 && buffer.position() + length <= matrixStart) {
                    "dense asset id block is corrupt"
                }
                val raw = ByteArray(length)
                buffer.get(raw)
                ids.add(raw.decodeToString())
            }
            require(buffer.position() == matrixStart) { "dense asset id block size mismatch" }
            val matrix = ByteArray(count * dim)
            buffer.get(matrix)
            val scales = FloatArray(count)
            for (row in 0 until count) scales[row] = buffer.float

            val nodeIds = ArrayList<String>(4096)
            val nodeStarts = ArrayList<Int>(4096)
            val nodeEnds = ArrayList<Int>(4096)
            val closedNodes = HashSet<String>(8192)
            var index = 0
            while (index < ids.size) {
                val nodeId = ids[index]
                require(subjectKeyOf(nodeId) != null) {
                    "dense asset row $index is not an atomic node id: $nodeId"
                }
                // 同节点向量必须连续（打包侧布局约定）：不连续会让"节点分 = max"只算到一段，
                // 静默改变排序——这里直接判资产不可用。
                require(nodeId !in closedNodes) { "dense asset rows for $nodeId are not contiguous" }
                var end = index + 1
                while (end < ids.size && ids[end] == nodeId) end++
                closedNodes.add(nodeId)
                nodeIds.add(nodeId)
                nodeStarts.add(index)
                nodeEnds.add(end)
                index = end
            }
            return DenseVectorAsset(
                sha256 = actualSha,
                dim = dim,
                count = count,
                nodeIdAt = nodeIds.toTypedArray(),
                nodeStart = nodeStarts.toIntArray(),
                nodeEnd = nodeEnds.toIntArray(),
                matrix = matrix,
                scales = scales,
            )
        }

        /** `kb:<taxonomy>:<subjectKey>:atomic:<slug>` → `<subjectKey>`（非原子节点 → null）。 */
        fun subjectKeyOf(nodeId: String): String? {
            val parts = nodeId.split(':')
            if (parts.size < 5) return null
            if (parts[0] != "kb" || parts[3] != "atomic") return null
            return parts[2].takeIf { it.isNotEmpty() }
        }

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return buildString(digest.size * 2) {
                for (byte in digest) append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
