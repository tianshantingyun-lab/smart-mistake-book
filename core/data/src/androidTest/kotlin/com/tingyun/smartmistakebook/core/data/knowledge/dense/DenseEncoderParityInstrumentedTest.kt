package com.tingyun.smartmistakebook.core.data.knowledge.dense

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * **端侧编码 vs Python 参考对拍（Stage-3 硬门）**。
 *
 * 任务书写死：同一批文本（90 条金标题面 + 抽样节点文本）在设备上编码，与 Python 参考向量
 * 比 cosine，**要求 ≥ [MIN_COSINE]**。这就是那条门的实现，判据全部来自 fixture 的封存值，
 * 不做任何本地放宽：
 *
 * - 文本与参考向量：`androidTest/assets/dense/encoder-parity-{cases.tsv,vectors.f32,json}`
 *   （生成脚本 `tools/dense_build/gen_device_parity_fixture.py`；参考向量 = 离线参考链
 *   自己的产物 `build/dense-model/{int8-queries,int8-docs}.npy`，逐行与文本对齐，
 *   生成时已做过"现场 ORT 重跑 vs 冻结 npy"的逐行对齐自检）；
 * - 端侧路径：`LiteRtDenseQueryEncoder.openFromAssets`（生产装配 `DenseRecallAssembly` 用的
 *   同一个入口）→ 分词（端侧 WordPiece）→ LiteRT 推理 → L2 归一；
 * - 判据：逐条 cosine ≥ [MIN_COSINE]，另打印分布（min/median/p95）与单条编码耗时。
 *
 * 失败形态也是判据的一部分：模型缺失/哈希不符/资产缺失时**不许静默回退**——本测试要求
 * 能真的把模型打开（`openFromAssets` 抛错即红），否则"对拍通过"就是假的。
 */
@RunWith(AndroidJUnit4::class)
class DenseEncoderParityInstrumentedTest {

    @Test
    fun deviceEncoderMatchesFrozenPythonReferenceVectors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases = loadCases(context)
        val references = loadReferences(context)
        val vectorsSeal = loadVectorSeal(context)
        // 参考向量的维度**取自 fixture**（`encoder-parity.json` 的 `dim`）：换件后 fixture 与
        // 端侧模型必须同维，写死 512/768 会在下一次换件时静默比错对象。
        val dim = loadFixtureDim(context)

        println("=== dense-encoder-parity (真机硬门) ===")
        println(
            "fixture: cases=" + cases.size + " vectorsFileSha256=" + vectorsSeal +
                " (assets 复核)",
        )
        assertEquals("fixture 条数与向量行数不符", cases.size, references.size)

        val vocabLines = readResourceText(DenseRecallAssembly.VOCAB_ASSET_PATH).split('\n')
        val tokenizer = DenseTokenizer(DenseVocab.fromLines(vocabLines))
        val encoder = LiteRtDenseQueryEncoder.openFromAssets(
            context = context,
            assetPath = DenseRecallAssembly.MODEL_ASSET_PATH,
            tokenizer = tokenizer,
        )

        // 诊断段（留在测试里：它是"模型缓冲区怎么喂给 LiteRT"这件事的唯一实证）。
        // 生产装配走 assets **mmap**（openFromAssets）；这里额外用**直接缓冲**跑前几条，
        // 两条路逐条比 cosine。2026-09-24 真机实测：mmap 路第 1 条 cos≈0.29、第 2 条
        // native SIGSEGV（tombstone 栈顶在 libLiteRt.so），直接缓冲路 290 条全过
        // ⇒ 问题在 mmap 那条路喂进去的模型缓冲区，不在模型文件、分词或算子。
        val directEncoder = run {
            val bytes = context.assets.open(DenseRecallAssembly.MODEL_ASSET_PATH).use { it.readBytes() }
            val direct = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            direct.put(bytes)
            direct.rewind()
            LiteRtDenseQueryEncoder.open(direct, tokenizer)
        }
        println("诊断段：mmap vs 直接缓冲（前 " + DIAGNOSTIC_CASES + " 条）")
        repeat(DIAGNOSTIC_CASES) { index ->
            val viaMmap = encoder.encode(cases[index].text)
            val viaDirect = directEncoder.encode(cases[index].text)
            println(
                "  diag#" + index + " mmapCos=" + cosine(viaMmap, references[index]) +
                    " directCos=" + cosine(viaDirect, references[index]) +
                    " mmapEqualsDirect=" + viaMmap.contentEquals(viaDirect),
            )
        }

        val elapsedMicros = mutableListOf<Long>()
        val allCosine = mutableListOf<Double>()
        val byKind = mutableMapOf<String, MutableList<Double>>()
        val failures = mutableListOf<String>()

        // 先整体分词一次：把"端侧分词结果"的范围先钉住（越界 id 会让 embedding Gather 越界读，
        // 表现就是 native SIGSEGV，而不是 Java 异常——所以这个范围必须可查）。
        var minId = Int.MAX_VALUE
        var maxId = Int.MIN_VALUE
        cases.forEach { case ->
            val ids = tokenizer.encodePadded(case.text, DENSE_MAX_SEQUENCE_LENGTH)
            ids.forEach { id ->
                if (id < minId) minId = id
                if (id > maxId) maxId = id
            }
        }
        println(
            "端侧分词 id 范围：min=" + minId + " max=" + maxId + " vocabSize=" + vocabLines.size,
        )
        assertTrue("端侧分词产生越界 id：max=" + maxId, maxId < vocabLines.size)
        assertTrue("端侧分词产生负 id：min=" + minId, minId >= 0)

        cases.forEachIndexed { index, case ->
            val started = SystemClock.elapsedRealtimeNanos()
            val produced = encoder.encode(case.text)
            elapsedMicros += (SystemClock.elapsedRealtimeNanos() - started) / 1_000
            assertEquals("编码维度不符", dim, produced.size)
            val cosine = cosine(produced, references[index])
            allCosine += cosine
            byKind.getOrPut(case.kind) { mutableListOf() } += cosine
            if (cosine < MIN_COSINE) {
                failures += "  [" + case.kind + " " + case.caseId + "] cosine=" + cosine +
                    " text=" + case.text.take(40) + " meta=" + case.meta
            }
            // 逐条打点：native 崩溃是进程级的、没有异常堆栈，这行是唯一能回答
            // "崩在第几条"的东西（现场排查用，代价是一行 logcat/case）。
            println("  case#" + index + " " + case.caseId + " cos=" + cosine)
        }

        val all = byKind.values.flatten()
        println(
            "cosine（端侧 vs Python 参考）：n=" + all.size +
                " min=" + all.min() + " median=" + percentile(all, 50) +
                " p95=" + percentile(all, 95) + " mean=" + all.average(),
        )
        byKind.forEach { (kind, values) ->
            println(
                "  " + kind + ": n=" + values.size + " min=" + values.min() +
                    " median=" + percentile(values, 50),
            )
        }
        println(
            "单条编码耗时：p50=" + percentileLong(elapsedMicros, 50) + "us p95=" +
                percentileLong(elapsedMicros, 95) + "us max=" + elapsedMicros.max() +
                "us（含分词 + LiteRT 推理 + L2 归一；n=" + elapsedMicros.size + "）",
        )
        println("判据：逐条 cosine ≥ " + MIN_COSINE + "；不达标的条数 = " + failures.size)
        failures.take(10).forEach { println(it) }

        assertTrue(
            "端侧编码与 Python 参考的 cosine 低于 " + MIN_COSINE + "：" + failures.size + "/" +
                all.size + " 条不达标，最差 " + (all.min()) + "\n" + failures.take(10).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private data class ParityCase(
        val caseId: String,
        val kind: String,
        val meta: String,
        val text: String,
    )

    private fun loadCases(context: Context): List<ParityCase> {
        val text = context.assets.open(ASSET_CASES).use { it.bufferedReader().readText() }
        return text.split('\n').filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            assertEquals("fixture 行格式不符", 4, fields.size)
            ParityCase(
                caseId = fields[0],
                kind = fields[1],
                text = unescape(fields[2]),
                meta = fields[3],
            )
        }
    }

    private fun loadReferences(context: Context): List<FloatArray> = context.assets
        .open(ASSET_VECTORS).use { stream ->
            val bytes = stream.readBytes()
            assertEquals(
                "向量文件字节数与 sha256 封存不符",
                loadVectorSeal(context),
                sha256Hex(bytes),
            )
            val dim = loadFixtureDim(context)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rowBytes = dim * Float.SIZE_BYTES
            assertEquals("向量文件不是 " + dim + " 维整数行", 0, bytes.size % rowBytes)
            List(bytes.size / rowBytes) {
                FloatArray(dim) { buffer.getFloat() }
            }
        }

    private fun loadFixtureDim(context: Context): Int {
        val json = context.assets.open(ASSET_META).use { it.bufferedReader().readText() }
        return Json.parseToJsonElement(json).jsonObject["dim"]!!.jsonPrimitive.int
    }

    private fun loadVectorSeal(context: Context): String {
        val json = context.assets.open(ASSET_META).use { it.bufferedReader().readText() }
        return Json.parseToJsonElement(json).jsonObject["vectorsFile"]!!.jsonObject["sha256"]!!
            .jsonPrimitive.content
    }

    private fun readResourceText(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: error("bundled dense resource is missing: $path")
        return stream.use { it.readBytes().decodeToString() }
    }

    /** fixture 的转义口径与生成脚本一致：`\\`、`\t`、`\n`、`\r`。 */
    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                out.append(
                    when (value[index + 1]) {
                        '\\' -> '\\'
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> value[index + 1]
                    },
                )
                index += 2
            } else {
                out.append(character)
                index += 1
            }
        }
        return out.toString()
    }

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index].toDouble() * right[index].toDouble()
            leftNorm += left[index].toDouble() * left[index].toDouble()
            rightNorm += right[index].toDouble() * right[index].toDouble()
        }
        return dot / (kotlin.math.sqrt(leftNorm) * kotlin.math.sqrt(rightNorm) + 1e-12)
    }

    private fun percentile(values: List<Double>, percent: Int): Double =
        values.sorted()[(((values.size - 1) * percent) / 100).coerceIn(values.indices)]

    private fun percentileLong(values: List<Long>, percent: Int): Long =
        values.sorted()[(((values.size - 1) * percent) / 100).coerceIn(values.indices)]

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ASSET_CASES = "dense/encoder-parity-cases.tsv"
        const val ASSET_VECTORS = "dense/encoder-parity-vectors.f32"
        const val ASSET_META = "dense/encoder-parity.json"
        const val DIAGNOSTIC_CASES = 5
        /** 任务书写死的硬门：逐条 cosine ≥ 0.999（不因本机实测好看而下调）。 */
        const val MIN_COSINE = 0.999
    }
}
