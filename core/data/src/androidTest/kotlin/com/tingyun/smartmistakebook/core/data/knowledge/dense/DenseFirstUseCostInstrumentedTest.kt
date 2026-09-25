package com.tingyun.smartmistakebook.core.data.knowledge.dense

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.DenseRecallCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * **稠密腿"首次用到才付"的一次性开销**（懒加载的代价落在哪一轮）+ **单条编码延迟（Stage-5 硬线探针）**。
 *
 * 装配（`DenseRecallAssembly`）刻意把三件重物都做成 lazy：tokenizer（词表 110KB）、
 * 向量资产（17MB）、模型（62MB + LiteRT 解释器）。所以 App 冷启动**不**碰它们，
 * 代价落在**第一次检索**上。本测试把这三段分别计时，供"启动不被拖垮"的说法有实证：
 *
 * - `openEncoder`：mmap 模型 + 建 `Interpreter` + 张量分配（一次性）；
 * - `firstOrder`：从零装配（生产装配点 `DenseRecallAssembly.reranker`）+ 首次编码（含资产加载与整科扫描）；
 * - `secondOrder`：同一装配复用 —— 稳态单次检索的稠密腿成本。
 *
 * 只打印不断言墙钟（设备噪声大）；断言的是**"复用的第二次必须显著快于第一次"**
 * 这条结构性事实（懒加载真的在复用，而不是每次都重来）。
 *
 * **Stage-5 追加的一段（2026-09-25，WP3 延迟探针）**：同一查询串**重复编码 N=10 次**，
 * 报 p50/p95 与**稳态 p50（排除首次）**，并打印本次所用模型件的**运行期身份**
 * （assets 内该文件的字节数与 sha256）——换了件却没人对拍时，"量的是哪一档"只有这行能回答。
 * 判据写死：单条编码 p50 ≤ [LATENCY_LINE_MS]ms（= Stage-3 现役实测 71ms 的 3 倍，2 线程同口径）。
 *
 * **本测试不把这条线写成断言**：这条线上量的是"当前随包那一档"，把某一档的数冻结成断言，
 * 换档后就会变成假信号（要么假绿、要么误红）；判定留在 WP3 报告里，测试只出数与身份。
 */
@RunWith(AndroidJUnit4::class)
class DenseFirstUseCostInstrumentedTest {

    @Test
    fun firstRetrievalPaysTheOneOffCostAndLaterCallsReuseIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vocabLines = javaClass.classLoader!!
            .getResourceAsStream(DenseRecallAssembly.VOCAB_ASSET_PATH)!!
            .use { it.readBytes().decodeToString() }
            .split('\n')
        val tokenizer = DenseTokenizer(DenseVocab.fromLines(vocabLines))

        val openStarted = SystemClock.elapsedRealtimeNanos()
        val encoder = LiteRtDenseQueryEncoder.openFromAssets(
            context = context,
            assetPath = DenseRecallAssembly.MODEL_ASSET_PATH,
            tokenizer = tokenizer,
        )
        val openMillis = elapsedMillis(openStarted)

        val reranker = DenseRecallAssembly.reranker(context)
            ?: error("稠密腿装配返回 null（ENABLED=false 或装配失败）")
        val candidates = listOf(
            DenseRecallCandidate("kb:probe:atomic:甲", 3),
            DenseRecallCandidate("kb:probe:atomic:乙", 2),
            DenseRecallCandidate("kb:probe:atomic:丙", 1),
        )
        val firstStarted = SystemClock.elapsedRealtimeNanos()
        reranker.order("BIOLOGY", PROBE_QUERY, candidates)
        val firstMillis = elapsedMillis(firstStarted)
        val secondStarted = SystemClock.elapsedRealtimeNanos()
        reranker.order("BIOLOGY", PROBE_QUERY, candidates)
        val secondMillis = elapsedMillis(secondStarted)

        println("=== dense-first-use (一次性开销) ===")
        println("openEncoder（mmap 模型 + Interpreter + 分配）=" + openMillis + "ms")
        println("firstOrder（装配 + 首次编码 + 整科扫描）=" + firstMillis + "ms")
        println("secondOrder（复用：稳态单次）=" + secondMillis + "ms")
        assertTrue(
            "第二次调用没有复用（first=" + firstMillis + "ms second=" + secondMillis + "ms）：" +
                "懒加载/复用这条结构被破坏了",
            secondMillis * 2 < firstMillis || firstMillis - secondMillis > 500,
        )

        // ---- Stage-5 延迟探针：同一查询串重复编码 N=10（口径 = `DenseEncoderParityInstrumentedTest`
        // 的"单条编码耗时"：`LiteRtDenseQueryEncoder.encode` → 端侧分词 + LiteRT 推理 + L2 归一，
        // 走生产同一入口 `openFromAssets`（assets mmap）、同一 2 线程设置）----
        val encodeMillis = mutableListOf<Long>()
        repeat(ENCODE_REPEATS) { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            encoder.encode(PROBE_QUERY)
            encodeMillis += elapsedMillis(started)
            println("  encode#" + (index + 1) + " = " + encodeMillis.last() + "ms")
        }
        val steadyMillis = encodeMillis.drop(1)
        val p50 = percentileLong(encodeMillis, 50)
        val p95 = percentileLong(encodeMillis, 95)
        val steadyP50 = percentileLong(steadyMillis, 50)
        val identity = modelAssetIdentity(context)

        println("=== dense-encode-latency (Stage-5 硬线探针) ===")
        println(
            "模型件：" + DenseRecallAssembly.MODEL_ASSET_PATH + " bytes=" + identity.bytes +
                " sha256=" + identity.sha256,
        )
        println(
            "机型=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " abi=" +
                Build.SUPPORTED_ABIS.first() + " 宿主报告核数=" +
                Runtime.getRuntime().availableProcessors() + " LiteRT线程数=" +
                LiteRtDenseQueryEncoder.DEFAULT_THREADS,
        )
        println("逐样本(ms)=" + encodeMillis.joinToString(","))
        println(
            "N=" + encodeMillis.size + " p50=" + p50 + "ms p95=" + p95 + "ms 稳态(排除首次,n=" +
                steadyMillis.size + ") p50=" + steadyP50 + "ms max=" + encodeMillis.max() + "ms",
        )
        println(
            "判据（Stage-5 写死，不由本测试放宽）：单条编码 p50 ≤ " + LATENCY_LINE_MS + "ms ⇒ 实测 p50=" +
                p50 + "ms " + (if (p50 <= LATENCY_LINE_MS) "过线" else "超线") + "（只出数，判定见 WP3 报告）",
        )
    }

    private data class AssetIdentity(val bytes: Long, val sha256: String)

    /**
     * 运行期读 assets 里模型件的字节数与 sha256。存在的理由：延迟数只有配上"量的哪一档"才有意义，
     * 而模型件是**静默回退**的（缺件/哈希不符时检索退回纯词面、不报错），旁证必须从被测进程里出。
     */
    private fun modelAssetIdentity(context: Context): AssetIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.assets.open(DenseRecallAssembly.MODEL_ASSET_PATH).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return AssetIdentity(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000

    /** 分位数口径与 `DenseEncoderParityInstrumentedTest` 一致：排序后取 `(n-1)*p/100`（不插值）。 */
    private fun percentileLong(values: List<Long>, percent: Int): Long =
        values.sorted()[(((values.size - 1) * percent) / 100).coerceIn(values.indices)]

    private companion object {
        /** Stage-5 硬线（任务书写死，本测试只打印不判定）：单条编码 p50 ≤ 213ms = 现役 71ms × 3。 */
        const val LATENCY_LINE_MS = 213

        /** 重复编码次数（同一查询串）。 */
        const val ENCODE_REPEATS = 10

        /** 三处调用用同一条查询串（与 firstOrder/secondOrder 对齐，见类注释）。 */
        const val PROBE_QUERY = "两株相对性状的豌豆杂交，只长出4粒种子且全是圆粒，能说圆粒一定是显性吗？"
    }
}
