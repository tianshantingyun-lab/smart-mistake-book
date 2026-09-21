package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写权限按题锚：**没锚住的调用仍然被拒**。
 *
 * 缺陷现场（改造前）：写工具的准入写成"输入类型是 Respond"。在轮次级绑定之前那句碰巧等价于
 * "有题"；绑定下移到轮次之后就不等价了——Respond 也可以是无题轮（声明缺失、候选不在菜单内、
 * 锚词核不过），而那时题面只是会话带进来的上下文，不是学生这一轮在说的题。写进去的学习证据
 * 因此没有主人。
 *
 * 判据落在**每一次调用**上，由 `TutorRoundQuestionBindingPolicy.callIsAnchoredToRoundQuestion`
 * 判定：模型声明（`TutorToolCall.boundQuestion`）经两条本地校验通过，**或**模型没复述时回退到
 * 本轮请求侧已知的题锚（`TutorRespondInput.knownRoundQuestion`，学生显式添加的题 / 上一轮已校验的
 * 绑定）。原生 tool_calls 路由的标准形态 content=null，整轮的信封声明无处可放，逐次调用对象与请求侧
 * 已知锚都是两条路由都能表达的落点；两者都没有（真的无题轮）仍然被拒——本文件断言的是这张表本身
 * （参数为 false 即拒），回退来源的判定另有 `TutorRoundQuestionBindingPolicyTest` 与
 * `TutorToolRoundGateTest` 钉住。
 *
 * 同一张表还挡下"产出只被题轮披露集合覆盖"的读工具：无题轮的披露集合
 * （`TUTOR_LOBBY_DISCLOSURE`：仅学生消息 + 会话上下文）装不下掌握度明细与学科知识库，
 * `TutorLobbyTasks.ALLOWED_LOCAL_CAPABILITIES` 早已为掌握度读取写下同一条理由。
 */
class TutorToolGateTest {

    @Test
    fun `a write call without an anchored question is refused`() {
        TUTOR_WRITE_TOOLS.forEach { tool ->
            assertFalse(
                "$tool 会落库；没有题锚就没有题目上下文，必须被拒",
                tutorRoundToolAvailable(
                    tool = tool,
                    callIsAnchoredToRoundQuestion = false,
                    roundDisclosesQuestionEvidence = true,
                ),
            )
        }
        assertEquals(
            setOf(TutorToolName.MASTERY_UPDATE, TutorToolName.NOTEBOOK_WRITE),
            TUTOR_WRITE_TOOLS,
        )
    }

    @Test
    fun `an anchored write call is allowed in a question round`() {
        TUTOR_WRITE_TOOLS.forEach { tool ->
            assertTrue(
                tutorRoundToolAvailable(
                    tool = tool,
                    callIsAnchoredToRoundQuestion = true,
                    roundDisclosesQuestionEvidence = true,
                ),
            )
        }
    }

    @Test
    fun `reads whose output only a question round discloses need a question round`() {
        assertEquals(
            setOf(TutorToolName.MASTERY_READ, TutorToolName.KNOWLEDGE_READ),
            TUTOR_QUESTION_ROUND_ONLY_READS,
        )
        TUTOR_QUESTION_ROUND_ONLY_READS.forEach { tool ->
            assertFalse(
                tutorRoundToolAvailable(tool, callIsAnchoredToRoundQuestion = false, roundDisclosesQuestionEvidence = false),
            )
            assertTrue(
                tutorRoundToolAvailable(tool, callIsAnchoredToRoundQuestion = false, roundDisclosesQuestionEvidence = true),
            )
        }
    }

    @Test
    fun `the notebook read stays available in a no-question round`() {
        // 大厅一直声明并使用它：收紧到"必须有题"会把既有的无题轮能力一起拿走。
        // 它的产出（错题本条目标题）按大厅契约属于会话上下文——同一条能力边界注释里
        // 只把掌握度读取列为不可覆盖（TutorLobbyTasks.ALLOWED_LOCAL_CAPABILITIES）。
        assertTrue(
            tutorRoundToolAvailable(
                tool = TutorToolName.NOTEBOOK_READ,
                callIsAnchoredToRoundQuestion = false,
                roundDisclosesQuestionEvidence = false,
            ),
        )
    }

    @Test
    fun `every declared tool is classified by the gate`() {
        // 声明集是页面级的，可用性是调用/轮次级：每个声明的工具都必须能回答"这一轮能不能用"，
        // 而不是漏在两张表之外被默认放行。
        val gated = TUTOR_WRITE_TOOLS + TUTOR_QUESTION_ROUND_ONLY_READS
        assertEquals(
            setOf(TutorToolName.NOTEBOOK_READ),
            TUTOR_TOOL_DECLARATIONS - gated,
        )
    }
}
