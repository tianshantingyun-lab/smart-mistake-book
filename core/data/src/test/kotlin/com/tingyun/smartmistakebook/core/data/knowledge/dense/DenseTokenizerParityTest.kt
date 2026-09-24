package com.tingyun.smartmistakebook.core.data.knowledge.dense

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端侧 WordPiece 分词器与 Python 参考的**逐 token id 对拍**（spec §3.2 的硬要求：
 * "不一致即停"）。
 *
 * fixture（`dense/tokenizer-parity-cases.txt`、`-stages.txt`）由
 * `tools/dense_build/gen_tokenizer_fixture.py` 用离线链同一个 `BertTokenizer` 生成：
 * 90 条金标查询（带查询前缀）+ 200 条节点文本抽样（canonicalName/alias）+ 边界探针。
 *
 * 断言口径：
 * - **全量比对，不许抽样**：fixture 每一行都要逐 id 相等，失败信息带 caseId 与首个差异位置；
 * - fixture 自带的条数分档也断言（少了/被截断的 fixture 必须红，不能"跑了个更小的集"）。
 */
class DenseTokenizerParityTest {

    private val vocabLines = DenseTestFixtures.text(VOCAB_RESOURCE).split('\n')
    private val tokenizer = DenseTokenizer(DenseVocab.fromLines(vocabLines))

    @Test
    fun `every fixture case matches the Python reference token ids`() {
        val cases = parseCases()
        assertEquals(
            "fixture 分档条数变了（fixture 被改过或生成脚本漂移）",
            mapOf("query" to 90, "surface" to 200, "edge" to 18, "stage" to 25),
            cases.groupingBy { it.kind }.eachCount(),
        )
        val failures = mutableListOf<String>()
        for (case in cases) {
            val actual = tokenizer.encode(case.text).toList()
            if (actual != case.ids) {
                failures += describe(case, actual)
            }
        }
        assertTrue(
            "分词器与参考不一致 ${failures.size}/${cases.size} 条：\n" + failures.take(5).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** 逐阶段中间值（归一化 / 切段）：出偏差时用来定位是哪一步错，而不是只看最终 id。 */
    @Test
    fun `every stage probe matches the reference normalizer and pre-tokenizer`() {
        val rows = DenseTestFixtures.rows(STAGES_RESOURCE)
        assertEquals("stage fixture 行数变了", 68, rows.size)
        val failures = mutableListOf<String>()
        for (row in rows) {
            val caseId = row[0]
            val text = DenseTestFixtures.unescape(row[1])
            val expectedNormalized = DenseTestFixtures.unescape(row[2])
            val expectedSplits = if (row[3].isEmpty()) {
                emptyList()
            } else {
                row[3].split('\u0001').map(DenseTestFixtures::unescape)
            }
            val normalized = tokenizer.normalize(text)
            val splits = tokenizer.preTokenize(normalized)
            if (normalized != expectedNormalized) {
                failures += "$caseId normalize: expected ${expectedNormalized.toCodePointList()} got ${normalized.toCodePointList()}"
            }
            if (splits != expectedSplits) {
                failures += "$caseId split: expected $expectedSplits got $splits"
            }
        }
        assertTrue("逐阶段不一致 ${failures.size}/${rows.size} 条：\n" + failures.take(5).joinToString("\n"), failures.isEmpty())
    }

    /** 边界（参考实现没有定义空串；端侧只承诺不崩，且纯空白与参考行为一致）。 */
    @Test
    fun `blank input encodes to the bare special pair without failing`() {
        assertEquals(listOf(101, 102), tokenizer.encode("").toList())
        assertEquals(listOf(101, 102), tokenizer.encode("   ").toList())
        assertEquals(listOf(101, 102), tokenizer.encode("\u3000\u00a0").toList())
    }

    @Test
    fun `truncation keeps the special token budget and the tail separator`() {
        val ids = tokenizer.encode("力".repeat(2000))
        assertEquals(DENSE_MAX_SEQUENCE_LENGTH, ids.size)
        assertEquals(101, ids.first())
        assertEquals(102, ids.last())
        assertEquals("截断后只允许一个 [SEP]", 1, ids.count { it == 102 })
    }

    @Test
    fun `encodePadded pads on the right with the pad id and refuses too small a window`() {
        val ids = tokenizer.encode("合力范围")
        val padded = tokenizer.encodePadded("合力范围", DENSE_MAX_SEQUENCE_LENGTH)
        assertEquals(DENSE_MAX_SEQUENCE_LENGTH, padded.size)
        assertEquals(ids.toList(), padded.take(ids.size))
        assertTrue("PAD 位必须是 pad id", padded.drop(ids.size).all { it == tokenizer.padId })
        val mask = tokenizer.attentionMask(padded)
        assertEquals(ids.size.toLong(), mask.sum())
        assertEquals(0L, mask.last())
        val failure = runCatching { tokenizer.encodePadded("合力范围", ids.size - 1) }.exceptionOrNull()
        assertTrue("定长窗口小于编码长度必须抛错而不是静默截断", failure is IllegalArgumentException)
    }

    /** 词表字节身份：这里 = 随包资产 == `tools/dense_build/vocab` 的冻结副本（漂移即红）。 */
    @Test
    fun `shipped vocab is byte identical to the frozen copy and matches its recorded sha`() {
        val shipped = DenseTestFixtures.bytes(VOCAB_RESOURCE)
        assertEquals(FROZEN_VOCAB_SHA256, DenseVectorAsset.sha256(shipped))
        val frozen = File(DenseTestFixtures.repoRoot(), "tools/dense_build/vocab/bge-small-zh-v1.5-vocab.txt")
        assertTrue("冻结词表不在位：$frozen", frozen.isFile)
        assertTrue("随包词表与冻结副本不一致", shipped.contentEquals(frozen.readBytes()))
        assertEquals(21128, vocabLines.size - 1)
    }

    private data class Case(val kind: String, val id: String, val text: String, val ids: List<Int>, val tokens: List<String>)

    private fun parseCases(): List<Case> = DenseTestFixtures.rows(CASES_RESOURCE).map { row ->
        assertEquals("fixture 列数应为 6", 6, row.size)
        Case(
            kind = row[0],
            id = row[1],
            text = DenseTestFixtures.unescape(row[3]),
            ids = row[4].split(' ').map(String::toInt),
            tokens = row[5].split('\u0001').map(DenseTestFixtures::unescape),
        )
    }

    private fun describe(case: Case, actual: List<Int>): String {
        val firstDifference = case.ids.indices.firstOrNull { it >= actual.size || actual[it] != case.ids[it] } ?: actual.size
        val actualTokens = actual.map { token -> vocabLines.getOrNull(token)?.removeSuffix("\r") ?: "?" }
        return buildString {
            append(case.id).append(" (").append(case.kind).append("): ")
            append("first diff at index ").append(firstDifference)
            append("; expected ").append(case.ids.size).append(" ids got ").append(actual.size)
            append("; expected tokens=").append(case.tokens.take(12))
            append("; actual tokens=").append(actualTokens.take(12))
        }
    }

    private fun String.toCodePointList(): List<String> =
        codePoints().toArray().map { String(Character.toChars(it)) }

    private companion object {
        const val CASES_RESOURCE = "dense/tokenizer-parity-cases.txt"
        const val STAGES_RESOURCE = "dense/tokenizer-parity-stages.txt"
        const val VOCAB_RESOURCE = "knowledge/dense/bge-small-zh-v1.5-vocab.txt"
        /** 词表 sha256：`tools/dense_build/model-manifest.json` 与 `.vec.json` 旁车的记录值。 */
        const val FROZEN_VOCAB_SHA256 = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
    }
}
