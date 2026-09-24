package com.tingyun.smartmistakebook.core.data.knowledge.dense

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.DenseRecallCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **稠密腿"首次用到才付"的一次性开销**（懒加载的代价落在哪一轮）。
 *
 * 装配（`DenseRecallAssembly`）刻意把三件重物都做成 lazy：tokenizer（词表 110KB）、
 * 向量资产（17MB）、模型（62MB + LiteRT 解释器）。所以 App 冷启动**不**碰它们，
 * 代价落在**第一次检索**上。本测试把这三段分别计时，供"启动不被拖垮"的说法有实证：
 *
 * - `openEncoder`：mmap 模型 + 建 `Interpreter` + 张量分配（一次性）；
 * - `firstOrder`：从零装配（生产装配点 `DenseRecallAssembly.reranker`）+ 首次
 *   编码（含资产加载与整科扫描）；
 * - `secondOrder`：同一装配复用 —— 稳态单次检索的稠密腿成本。
 *
 * 只打印不断言墙钟（设备噪声大）；断言的是**"复用的第二次必须显著快于第一次"**
 * 这条结构性事实（懒加载真的在复用，而不是每次都重来）。
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
        LiteRtDenseQueryEncoder.openFromAssets(
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
        reranker.order("BIOLOGY", "两株相对性状的豌豆杂交，只长出4粒种子且全是圆粒，能说圆粒一定是显性吗？", candidates)
        val firstMillis = elapsedMillis(firstStarted)
        val secondStarted = SystemClock.elapsedRealtimeNanos()
        reranker.order("BIOLOGY", "两株相对性状的豌豆杂交，只长出4粒种子且全是圆粒，能说圆粒一定是显性吗？", candidates)
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
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000
}
