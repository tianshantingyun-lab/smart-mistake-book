package com.tingyun.smartmistakebook.core.data.knowledge.dense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 融合口径（spec §2.4）与离线判分器逐条对拍。
 *
 * 期望值由 `tools/dense_build/gen_dense_device_fixture.py` **直接调用**
 * `stage3_expectation.minmax` / `best_first` 现算（不是重写一遍公式），用例覆盖：
 * 两腿正常、min-max 上界=下界（全等分）、单元素域、整条腿缺失、部分节点缺失、
 * 全并列（按 node_id 升序）、负分、空域、域内两腿皆无分。
 */
class DenseFusionTest {

    private val rows = DenseTestFixtures.rows(FIXTURE)

    @Test
    fun `frozen alpha is half`() {
        assertEquals("spec §2.4 的 α 写死为 0.5，不许在判官上调参", 0.5, DenseFusion.ALPHA, 0.0)
    }

    @Test
    fun `every fusion case matches the reference order and normalisation`() {
        val cases = rows.filter { it[0] == "[fusion]" }
        assertEquals("融合用例数变了", 10, cases.size)
        for (row in cases) {
            val label = row[1]
            val domain = if (row[2].isEmpty()) emptyList() else row[2].split(',')
            val denseRaw = parseScores(row[3])
            val lexicalRaw = parseScores(row[4])
            val denseNorm = parseScores(row[5])
            val lexicalNorm = parseScores(row[6])
            val expectedOrder = if (row[7].isEmpty()) emptyList() else row[7].split(',')
            assertEquals("$label dense min-max", denseNorm, DenseFusion.minMax(denseRaw))
            assertEquals("$label lexical min-max", lexicalNorm, DenseFusion.minMax(lexicalRaw))
            assertEquals(
                "$label order",
                expectedOrder,
                DenseFusion.order(domain, denseRaw, lexicalRaw),
            )
        }
    }

    @Test
    fun `missing legs never participate in min-max and score as zero`() {
        // 稠密腿整条缺失：词面腿独占 ⇒ 次序 = 词面腿次序（且稠密腿不产生 NaN/异常）。
        assertEquals(
            listOf("a", "b"),
            DenseFusion.order(listOf("b", "a"), emptyMap(), mapOf("a" to 5.0, "b" to 1.0)),
        )
        // 域内两腿皆无分：全体 0 分 ⇒ 按 node_id 升序。
        assertEquals(
            listOf("a", "x"),
            DenseFusion.order(listOf("x", "a"), emptyMap(), emptyMap()),
        )
        assertTrue(DenseFusion.minMax(emptyMap()).isEmpty())
    }

    private fun parseScores(value: String): Map<String, Double> {
        if (value.isEmpty()) return emptyMap()
        return value.split(';').associate { entry ->
            val (nodeId, score) = entry.split('=', limit = 2)
            nodeId to score.toDouble()
        }
    }

    private companion object {
        const val FIXTURE = "dense/dense-fusion-reference.txt"
    }
}
