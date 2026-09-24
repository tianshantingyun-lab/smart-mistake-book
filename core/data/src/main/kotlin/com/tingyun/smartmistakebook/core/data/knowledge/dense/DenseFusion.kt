package com.tingyun.smartmistakebook.core.data.knowledge.dense

/**
 * spec §2.4 的融合口径（**出数前写死**：α=0.5、域内 min-max、缺失腿给 0、并列按 node_id 升序）。
 *
 * 实现必须与离线判分器 `tools/dense_build/stage3_expectation.py` 的
 * `minmax()` / `best_first()` 同语义——那两处是权威实现，本文件是端侧移植；
 * 对拍用例由 `tools/dense_build/gen_dense_device_fixture.py` **调用那两个函数**现算生成
 * （`dense-fusion-reference.txt`），所以不是"两套口径互相印证"。
 *
 * 两条腿的 min-max **域**不同，这是参考实现的定义而不是近似（见 `stage3_expectation.build`）：
 * - 稠密腿：候选域内**有向量的节点**（端侧 = 该科全部原子节点，由 [DenseVectorAsset.nodeScores] 给出）；
 * - 词面腿：**有词面分的节点**（端侧 = SQL 召回集，其分数来自 `COUNT(DISTINCT feature)`）。
 * 某腿对某节点无分 ⇒ 该腿给 0，且**不参与**该腿的 min-max。
 *
 * 用 `Double`（不是 Float）做归一化与加权：域只有几百个节点，代价可忽略，而参考实现是
 * Python float（= Double），端侧用 Float 会在并列处改变名次——那正是"判官口径"最不能动的地方。
 */
internal object DenseFusion {
    /** 写死：spec §2.4 的 α（归一化分数融合权重）。 */
    const val ALPHA = 0.5

    /** 一条腿的域内 min-max：上界=下界（含单元素）⇒ 全体 0（与参考 `minmax` 同）。 */
    fun minMax(values: Map<String, Double>): Map<String, Double> {
        if (values.isEmpty()) return emptyMap()
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        for (value in values.values) {
            if (value < low) low = value
            if (value > high) high = value
        }
        if (high <= low) return values.keys.associateWith { 0.0 }
        val span = high - low
        return values.mapValues { (_, value) -> (value - low) / span }
    }

    /**
     * 融合后的定序：`score = α·dense_norm + (1-α)·lex_norm`，降序；并列按 [orderDomain]
     * 之外无信息可用，故按 node_id 升序（与参考 `best_first` 的 `(-score, node_id)` 等价）。
     *
     * @param orderDomain 要定序的节点（端侧 = 词面召回集的 node id，**成员与长度不变**）
     * @param denseScores 稠密腿原始分（其键集即稠密腿 min-max 域）
     * @param lexicalScores 词面腿原始分（其键集即词面腿 min-max 域）
     */
    fun order(
        orderDomain: List<String>,
        denseScores: Map<String, Double>,
        lexicalScores: Map<String, Double>,
        alpha: Double = ALPHA,
    ): List<String> {
        if (orderDomain.isEmpty()) return emptyList()
        val denseNorm = minMax(denseScores)
        val lexicalNorm = minMax(lexicalScores)
        return orderDomain.sortedWith(
            compareByDescending<String> { nodeId ->
                alpha * (denseNorm[nodeId] ?: 0.0) + (1.0 - alpha) * (lexicalNorm[nodeId] ?: 0.0)
            }.thenBy { it },
        )
    }
}

/**
 * 每向量对称 int8 量化（`scale = max|v| / 127`、`q = round(v / scale)` 截 ±127、`max = 0`
 * ⇒ `scale = 1.0`、`q = 0`）——与 `tools/dense_build/dense_asset.py::quantize_int8` 同一口径，
 * 也就是 `.vec` 资产里 28,932 条向量的生成口径。
 *
 * 用途：**查询侧可选的 int8 路**（[DenseQueryMode.INT8]）。默认走 fp32——离线参考的查询侧
 * 就是 fp32（`stage3_expectation.py` 的 `query_norm`），量化查询会引入额外偏差，只有实测
 * 允许时才开（见 `AndroidDenseRecallReranker` 的常量说明）。
 */
internal object DenseVectorQuantizer {
    const val MAX_QUANTIZED = 127

    data class Quantized(val values: ByteArray, val scale: Float)

    fun quantize(vector: FloatArray): Quantized {
        var peak = 0f
        for (value in vector) {
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
        }
        val scale = if (peak > 0f) peak / MAX_QUANTIZED else 1f
        val quantized = ByteArray(vector.size) { index ->
            val rounded = Math.rint((vector[index] / scale).toDouble()).toInt()
            rounded.coerceIn(-MAX_QUANTIZED, MAX_QUANTIZED).toByte()
        }
        return Quantized(quantized, scale)
    }

    fun dequantize(quantized: Quantized): FloatArray =
        FloatArray(quantized.values.size) { quantized.values[it] * quantized.scale }
}

/**
 * 查询向量的后处理（spec §3 的"取 CLS → L2 归一"）。
 *
 * 事实校正（2026-09-24 实测 `build/dense-model/bge-small-zh-v1.5-int8.onnx` 的计算图）：
 * **CLS 池化与 L2 归一都已经在图里**（`Gather(0)` → `ReduceL2` → `Clip` → `Expand` → `Div`，
 * 输出名 `sentence_embedding`，形状 `[batch, 512]`）。所以端侧只做一件事：把输出张量
 * 归一化（幂等，见 [normalizeOriginal]）。这一步保留的意义是"模型件换了导出形态也不会
 * 静默把未归一化的向量当余弦用"。
 */
internal object DenseQueryPostProcess {
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        if (sum <= 0.0) return vector.copyOf()
        val norm = Math.sqrt(sum).toFloat()
        if (norm <= 0f) return vector.copyOf()
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /** 便于日志/单测：向量是否已归一化（|‖v‖-1| ≤ 1e-3）。 */
    fun isNormalized(vector: FloatArray, tolerance: Float = 1e-3f): Boolean {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        return Math.abs(Math.sqrt(sum) - 1.0) <= tolerance
    }
}
