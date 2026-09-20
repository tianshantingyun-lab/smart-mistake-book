package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写权限按绑定：**无题轮申请写入仍被拒**。
 *
 * 缺陷现场：写工具的准入此前写成"输入类型是 Respond"。在轮次级绑定之前，那一句碰巧等价于
 * "有题"；绑定下移到轮次之后就不等价了——Respond 也可以是"这一轮不指任何一道"的无题轮
 * （声明缺失、候选不在菜单内、锚词核不过），而那时题面只是会话带进来的上下文，不是学生这一轮
 * 在说的题。写进去的学习证据因此没有主人。
 *
 * 同一张表还挡下"产出装不进本轮披露面"的读工具：无题轮的披露集合
 * （`TUTOR_LOBBY_DISCLOSURE`：仅学生消息 + 会话上下文）覆盖不到掌握度明细与学科知识库，
 * `TutorLobbyTasks.ALLOWED_LOCAL_CAPABILITIES` 早已为掌握度读取写下同一条理由。
 */
class TutorToolGateTest {

    @Test
    fun `a round without a bound question refuses every write tool`() {
        TUTOR_WRITE_TOOLS.forEach { tool ->
            assertFalse(
                "$tool 会落库，无题轮里没有题目锚点，必须被拒",
                tutorRoundToolAvailable(tool, roundHasBoundQuestion = false),
            )
        }
        assertEquals(
            setOf(TutorToolName.MASTERY_UPDATE, TutorToolName.NOTEBOOK_WRITE),
            TUTOR_WRITE_TOOLS,
        )
    }

    @Test
    fun `a round with a bound question allows the write tools`() {
        TUTOR_WRITE_TOOLS.forEach { tool ->
            assertTrue(tutorRoundToolAvailable(tool, roundHasBoundQuestion = true))
        }
    }

    @Test
    fun `tools whose output the no-question disclosure cannot cover need a bound question`() {
        assertEquals(
            setOf(TutorToolName.MASTERY_READ, TutorToolName.KNOWLEDGE_READ),
            TUTOR_QUESTION_BOUND_ONLY_READS,
        )
        TUTOR_QUESTION_BOUND_ONLY_READS.forEach { tool ->
            assertFalse(tutorRoundToolAvailable(tool, roundHasBoundQuestion = false))
            assertTrue(tutorRoundToolAvailable(tool, roundHasBoundQuestion = true))
        }
    }

    @Test
    fun `the notebook read stays available in a no-question round`() {
        // 大厅一直声明并使用它：收紧到"必须有题"会把既有的无题轮能力一起拿走。
        assertTrue(tutorRoundToolAvailable(TutorToolName.NOTEBOOK_READ, roundHasBoundQuestion = false))
    }

    @Test
    fun `every declared tool is classified by the gate`() {
        // 声明集是页面级的，可用性是轮次级的：每个声明的工具都必须能回答"无题轮能不能用"，
        // 而不是漏在两张表之外被默认放行。
        val gated = TUTOR_WRITE_TOOLS + TUTOR_QUESTION_BOUND_ONLY_READS
        assertEquals(
            setOf(TutorToolName.NOTEBOOK_READ),
            TUTOR_TOOL_DECLARATIONS - gated,
        )
    }
}
