package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讲题会话客观作答记录（研究 `tutor-evidence-gate-research.md` §3.2：
 * 冲突时行为证据胜出，口头声明降级为观察记录）。
 *
 * 消灭的失败：模型在同一会话里对着学生刚答错的检查题判 POSITIVE，本地不做任何
 * 交叉核对，口头声明直接压过行为证据写入掌握度。
 */
class TutorSessionObjectiveEvidenceTest {

    @Test
    fun `a wrong check answer contradicts a positive claim`() {
        val record = tutorSessionObjectiveRecord(listOf(true, false, true))

        assertTrue(record.contradictsPositiveClaim)
        assertEquals(3, record.answeredCount)
        assertEquals(2, record.correctCount)
        assertEquals(1, record.incorrectCount)
    }

    @Test
    fun `all-correct answers do not contradict a positive claim`() {
        val record = tutorSessionObjectiveRecord(listOf(true, true))

        assertFalse(record.contradictsPositiveClaim)
        assertEquals(0, record.incorrectCount)
    }

    @Test
    fun `a session without objective answers cannot contradict anything`() {
        // 学生没答过检查题 → 本地没有行为证据，既不能佐证也不能推翻：
        // 这是"佐证缺位"，不是"冲突"，语义不同，不得混为一谈。
        val record = tutorSessionObjectiveRecord(emptyList())

        assertFalse(record.contradictsPositiveClaim)
        assertEquals(0, record.answeredCount)
    }

    @Test
    fun `a session of nothing but wrong answers still contradicts`() {
        val record = tutorSessionObjectiveRecord(listOf(false, false))

        assertTrue(record.contradictsPositiveClaim)
        assertEquals(0, record.correctCount)
    }
}
