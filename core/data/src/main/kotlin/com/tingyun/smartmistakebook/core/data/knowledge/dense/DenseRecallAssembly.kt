package com.tingyun.smartmistakebook.core.data.knowledge.dense

import android.content.Context
import android.util.Log
import com.tingyun.smartmistakebook.core.database.DenseRecallCandidate
import com.tingyun.smartmistakebook.core.database.DenseRecallReranker
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 端侧稠密腿的实现：查询文本 → 句向量 → 与资产里**同科**全部向量做整数点积 → 节点取 max
 * → 与词面分按 spec §2.4 融合 → 只重排词面召回集（成员不变）。
 *
 * 三个依赖都是"取用时才加载"的函数：任何一步失败（资产缺失/哈希不符/模型缺失/推理抛错）
 * 都收敛成 `null` = 不可用，调用方（`RoomKnowledgeBaseStore`）已有纯词面结果 ⇒ **绝不崩、
 * 绝不返回空**。加载顺序也是刻意的：**先模型后资产**——模型缺失（最可能的失败）时
 * 不必把 17MB 资产拉进内存。
 */
internal class OnDeviceDenseRecallReranker(
    private val tokenizer: () -> DenseTokenizer,
    private val asset: () -> DenseVectorAsset,
    private val encoder: () -> DenseQueryEncoder,
) : DenseRecallReranker {

    override suspend fun order(
        subject: String,
        queryText: String,
        candidates: List<DenseRecallCandidate>,
    ): List<DenseRecallCandidate>? = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext null
        try {
            val vector = encoder().encode(queryText)
            val vectors = asset()
            val subjectKey = subject.lowercase(Locale.ROOT)
            val denseScores = vectors.nodeScores(vector, subjectKey).mapValues { it.value.toDouble() }
            val lexicalScores = candidates.associate { it.knowledgeNodeId to it.lexicalScore.toDouble() }
            val orderedIds = DenseFusion.order(
                orderDomain = candidates.map(DenseRecallCandidate::knowledgeNodeId),
                denseScores = denseScores,
                lexicalScores = lexicalScores,
            )
            val byId = candidates.associateBy(DenseRecallCandidate::knowledgeNodeId)
            val reordered = orderedIds.map { byId.getValue(it) }
            Log.i(
                TAG,
                "dense rerank on subject=$subject candidates=${candidates.size} " +
                    "denseNodeScores=${denseScores.size}",
            )
            reordered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "dense leg unavailable for subject=$subject: $failure")
            null
        }
    }

    private companion object {
        const val TAG = "DenseRecall"
    }
}

/**
 * 端侧稠密腿的**装配点与总开关**（core:data 唯一一处生产装配；store 侧只认端口）。
 *
 * `ENABLED = false` 是一行回退：装配返回 `null` ⇒ 检索路径回到纯词面（Stage-1/2 行为）。
 */
object DenseRecallAssembly {
    /** 总开关。关掉 = 检索退回纯词面（不改任何其它代码）。 */
    const val ENABLED = true

    /**
     * 模型件路径（assets 内）：LiteRT `.tflite`（**随包分发**，体积与 sha256 见同目录 README
     * 与 `tools/dense_build/README.md` §7）。换件必须重跑真机对拍门（≥0.999）——缺件或哈希
     * 不符时这条腿是**静默回退**（返回纯词面）而不是报错，没有门就没人知道腿掉了。
     */
    const val MODEL_ASSET_PATH = "dense/bge-small-zh-v1.5-int8.tflite"

    /** 随包资产（classpath 资源，与 `.vec` 同目录）。 */
    const val VECTOR_ASSET_PATH = "knowledge/dense/bge-small-zh-int8.vec"
    const val VOCAB_ASSET_PATH = "knowledge/dense/bge-small-zh-v1.5-vocab.txt"

    /**
     * 资产身份（sha256），权威值在 `knowledge/dense/bge-small-zh-int8.vec.json` 旁车里
     * （`DenseAssetProvenanceTest` 钉住"这里的常量 == 旁车 == 实际文件"三处一致）。
     * 不匹配 ⇒ 判不可用：宁可没有稠密腿，也不用一份来路不明的向量表排序。
     */
    const val VECTOR_ASSET_SHA256 = "cdf93650b948a09179c893a8de285b0248917da420770570afb47d453f80cb5a"

    /** 生产装配：不可用（未启用）时返回 `null`。 */
    fun reranker(context: Context): DenseRecallReranker? {
        if (!ENABLED) return null
        val runtime = DenseRuntime(context.applicationContext)
        return OnDeviceDenseRecallReranker(
            tokenizer = runtime::tokenizer,
            asset = runtime::vectorAsset,
            encoder = runtime::encoder,
        )
    }

    /** 三件重物各自"首次用到才加载、之后复用"。 */
    private class DenseRuntime(private val context: Context) {
        private val tokenizerHolder = lazy { loadTokenizer() }
        private val assetHolder = lazy { loadVectorAsset() }
        private val encoderHolder = lazy { loadEncoder() }

        fun tokenizer(): DenseTokenizer = tokenizerHolder.value

        fun vectorAsset(): DenseVectorAsset = assetHolder.value

        fun encoder(): DenseQueryEncoder = encoderHolder.value

        private fun loadTokenizer(): DenseTokenizer =
            DenseTokenizer(DenseVocab.fromLines(readResourceText(VOCAB_ASSET_PATH).split('\n')))

        private fun loadVectorAsset(): DenseVectorAsset {
            val bytes = readResourceBytes(VECTOR_ASSET_PATH)
            return DenseVectorAsset.read(bytes, expectedSha256 = VECTOR_ASSET_SHA256)
        }

        private fun loadEncoder(): DenseQueryEncoder = LiteRtDenseQueryEncoder.openFromAssets(
            context = context,
            assetPath = MODEL_ASSET_PATH,
            tokenizer = tokenizer(),
        )

        private fun readResourceBytes(path: String): ByteArray {
            val stream = DenseRecallAssembly::class.java.classLoader?.getResourceAsStream(path)
                ?: error("bundled dense asset is missing: $path")
            return stream.use { it.readBytes() }
        }

        private fun readResourceText(path: String): String =
            readResourceBytes(path).decodeToString()
    }
}
