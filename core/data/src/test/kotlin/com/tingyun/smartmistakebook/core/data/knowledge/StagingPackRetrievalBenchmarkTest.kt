package com.tingyun.smartmistakebook.core.data.knowledge

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 暂存包的检索评测：题面与判分口径与 [FourSubjectRetrievalBenchmarkTest] **完全共用**
 * （[RetrievalBenchmark]），只是把包从"打进 APK 的成品包"换成 `tools/kb_build` 生成到
 * `build/kb-staging/` 的那份。
 *
 * **为什么按路径读文件，而不是把暂存包放进测试资源目录**：
 * `BundledKnowledgePackResources.readClasspathResource` 用的是 `getResourceAsStream`，
 * 只取 classpath 上**第一个**命中。在测试资源里再放一份同名资源，等于把"读到哪一份"
 * 交给 classpath 顺序决定；而且那份副本会过期——测的就不是刚生成的东西。按路径读，
 * 读到的一定是构建脚本刚写出来的那份。
 *
 * **这里刻意不断言阈值**：它是**测量台**，不是守门人。守成品包的是
 * `FourSubjectRetrievalBenchmarkTest`（CI 会跑），暂存包这份存在的意义是给出与它**可比**的
 * 一份数，让"别名放哪几档、边界过滤留什么"这些取舍有分数可对照。分数不够时该改的是包，
 * 不是把这里的门槛调低。
 *
 * CI 上 `build/kb-staging` 不存在（`tools/` 未被 git 跟踪、`build/` 被忽略），
 * 所以用 `assumeTrue` 跳过——不会让 CI 变红。
 *
 * **跑的时候必须带 `--rerun`**：暂存包是 Gradle 任务输入之外的**外部文件**，改它不会让
 * 这个任务失效。实测：同一 filter 原样重跑会 `UP-TO-DATE`，指标文件**不会被重写**，
 * 于是你读到的是上一次的旧数——换回旧包也不会发现。用
 * `./gradlew :core:data:testDebugUnitTest --tests "*StagingPackRetrievalBenchmarkTest*" --rerun`。
 */
class StagingPackRetrievalBenchmarkTest {

    private val packFile: File?
        get() {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile
            }
            return dir?.let { File(it, "build/kb-staging/moe-2025-four-subjects-v1.json") }
        }

    @Test
    fun `staging pack retrieval recall@5 precision@5 mrr per subject`() {
        val file = packFile
        assumeTrue(
            "未找到暂存包（先跑 PYTHONPATH=tools python -m kb_build.build --write）：$file",
            file != null && file.isFile,
        )

        // 走同一个解码器：顺带覆盖"绑定指向不存在的节点 ⇒ 整包解码失败"这条硬约束。
        val pack = ReviewedKnowledgePackJsonCodec.decode(file!!.readText(Charsets.UTF_8))
        val candidates = RetrievalBenchmark.atomicNodes(pack.nodes)
        val result = RetrievalBenchmark.run(candidates)
        println(RetrievalBenchmark.report("四科真实检索评测 (暂存包·按科隔离)", result))
        File("build/staging-benchmark-metrics.txt").writeText(RetrievalBenchmark.metricsFile(result))

        // 逐章覆盖的那一组题面（见 RetrievalBenchmark.chapterQueries）。同样是**测量**：
        // 它存在的意义是让"补了某一章"这件事在分数上看得见——原 20 条不覆盖这些章，
        // 所以补完分数不动、取舍只能凭感觉。阈值等这一组数稳定后再钉，不先猜一个。
        val chapterResult = RetrievalBenchmark.runChapterQueries(candidates)
        println(RetrievalBenchmark.report("四科真实检索评测·逐章覆盖 (暂存包)", chapterResult))
        File("build/staging-chapter-metrics.txt").writeText(RetrievalBenchmark.metricsFile(chapterResult))
    }
}
