package com.tingyun.smartmistakebook.core.data.knowledge.dense

import java.io.File

/**
 * Stage-3 端侧 JVM 测试的 fixture 读取件。
 *
 * fixture 由 `tools/dense_build/gen_*.py` 生成（Python 参考侧），**入库跟踪**：
 * 端侧不许"自己和自己对"——没有 Python 侧的期望值，对拍就只是自证。
 */
internal object DenseTestFixtures {

    /** classpath 资源（测试资源 + main 资源在同一 classpath 上）。 */
    fun bytes(path: String): ByteArray {
        val stream = DenseTestFixtures::class.java.classLoader?.getResourceAsStream(path)
            ?: error("missing test resource $path")
        return stream.use { it.readBytes() }
    }

    fun text(path: String): String = bytes(path).decodeToString()

    fun lines(path: String): List<String> = text(path).split('\n')

    /** 制表符分隔 + `#` 注释；返回每个数据行的列。 */
    fun rows(path: String): List<List<String>> = lines(path)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.split('\t') }

    /** fixture 里的文本转义（生成侧：`\\`、`\t`、`\n`、`\r`）。 */
    fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\') {
                out.append(char)
                index++
                continue
            }
            require(index + 1 < value.length) { "dangling escape in fixture value" }
            when (val escaped = value[index + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> error("unknown escape \\$escaped in fixture value")
            }
            index += 2
        }
        return out.toString()
    }

    /** 仓库根：从测试工作目录（模块目录）上溯找 `settings.gradle.kts`。 */
    fun repoRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile) return candidate
            candidate = candidate.parentFile
        }
        error("repository root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }
}
