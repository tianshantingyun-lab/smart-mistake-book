package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRoundQuestionDeclaration
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolOutcome
import kotlinx.coroutines.runBlocking
import com.tingyun.smartmistakebook.core.domain.TUTOR_TOOL_DECLARATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写工具门控的**接线**测试（复核意见二）。
 *
 * 此前唯一钉住"无题轮写工具被拒"的用例只测纯函数 `tutorRoundToolAvailable`，而真正的落地点
 * ——`RoomModelTaskRepository.execute()` 里那个 `|| !allowedForRound`——只有仪器化用例够得着，
 * 且那条例只验证正方向。于是把判定整条删掉，本机没有一条用例会转红。
 *
 * 现在工具环的一轮判定整体是 [tutorToolRoundOutcomes]，这里直接钉它：判定、拒绝、以及
 * "被拒的调用不会触达执行器"三件事一起断言。删掉其中的 `|| !available` 会让本文件的第一条
 * 用例转红（已实证）。
 */
class TutorToolRoundGateTest {

    @Test
    fun `a write call with no question anchor is refused and never reaches the runner`() = runBlocking {
        val ran = mutableListOf<TutorToolName>()

        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(masteryUpdateCall(anchor = null)),
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            input = respondInput(boundCandidate = candidate()),
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), outcomes.map(TutorToolOutcome::tool))
        assertFalse("没有题锚的写调用必须被拒", outcomes.single().ok)
        assertEquals("not_authorized", outcomes.single().errorKind)
        assertTrue("被拒的调用不得触达执行器", ran.isEmpty())
    }

    @Test
    fun `a write call anchored in the round menu reaches the runner`() = runBlocking {
        val ran = mutableListOf<TutorToolName>()

        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(
                masteryUpdateCall(
                    anchor = TutorRoundQuestionDeclaration(
                        problemId = BOUND_PROBLEM_ID,
                        problemRevisionId = BOUND_REVISION_ID,
                        anchorTerms = listOf("配方法"),
                    ),
                ),
            ),
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            input = respondInput(boundCandidate = candidate()),
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertTrue(outcomes.single().ok)
        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), ran)
    }

    @Test
    fun `an anchor pointing outside the round menu is refused`() = runBlocking {
        val ran = mutableListOf<TutorToolName>()

        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(
                masteryUpdateCall(
                    anchor = TutorRoundQuestionDeclaration(
                        problemId = "problem-outside",
                        problemRevisionId = "revision-outside",
                        anchorTerms = listOf("配方法"),
                    ),
                ),
            ),
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            input = respondInput(boundCandidate = candidate()),
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertFalse(outcomes.single().ok)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a no-question round refuses the reads whose output it cannot disclose`() = runBlocking {
        val ran = mutableListOf<TutorToolName>()

        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(
                TutorToolCall(
                    tool = TutorToolName.MASTERY_READ,
                    rationale = "看掌握情况",
                    terms = listOf("配方法"),
                ),
                TutorToolCall(
                    tool = TutorToolName.KNOWLEDGE_READ,
                    rationale = "看知识点",
                    terms = listOf("配方法"),
                ),
                TutorToolCall(
                    tool = TutorToolName.NOTEBOOK_READ,
                    rationale = "看错题本",
                    terms = listOf("二次函数"),
                ),
            ),
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            // 大厅轮次：无题、披露集合不含学习证据与知识库。
            input = TutorLobbyInput(
                conversationId = "conv-1",
                messageOrdinal = 1,
                studentMessage = "帮我看看错题本里有没有二次函数",
            ),
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertEquals(listOf(false, false, true), outcomes.map(TutorToolOutcome::ok))
        assertEquals(listOf(TutorToolName.NOTEBOOK_READ), ran)
    }

    @Test
    fun `an authorized tool outside the declared set is still refused`() = runBlocking {
        val ran = mutableListOf<TutorToolName>()

        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(
                masteryUpdateCall(
                    anchor = TutorRoundQuestionDeclaration(
                        problemId = BOUND_PROBLEM_ID,
                        problemRevisionId = BOUND_REVISION_ID,
                        anchorTerms = listOf("配方法"),
                    ),
                ),
            ),
            // 意图矩阵没放行写工具（例如本轮意图是查错题本）。
            authorizedTools = setOf(TutorToolName.NOTEBOOK_READ),
            input = respondInput(boundCandidate = candidate()),
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertFalse(outcomes.single().ok)
        assertTrue(ran.isEmpty())
    }

    private fun masteryUpdateCall(anchor: TutorRoundQuestionDeclaration?) = TutorToolCall(
        tool = TutorToolName.MASTERY_UPDATE,
        rationale = "学生说「我现在理解配方法这一步了」",
        terms = listOf("knowledge-node-peifang"),
        direction = TutorEvidenceDirection.POSITIVE,
        understanding = TutorUnderstandingTier.CONFIDENT,
        boundQuestion = anchor,
    )

    private fun ok(tool: TutorToolName) = TutorToolOutcome(
        tool = tool,
        ok = true,
        summaryMarkdown = "已执行",
        errorKind = null,
    )

    private fun candidate() = RelatedProblemCandidate(
        problemId = BOUND_PROBLEM_ID,
        problemRevisionId = BOUND_REVISION_ID,
        subject = SubjectKind.MATH,
        title = "配方法解一元二次方程",
        questionDocument = QuestionDocument(
            id = "question-peifang",
            blocks = listOf(ContentBlock.Paragraph("stem-peifang", "用配方法求函数的单调区间。")),
        ),
    )

    private fun respondInput(boundCandidate: RelatedProblemCandidate) = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "MATH",
        questionDocument = QuestionDocument(
            id = "question-current",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        studentMessage = "我现在理解配方法这一步了。",
        toolDeclarations = TUTOR_TOOL_DECLARATIONS.toList(),
        boundQuestionCandidates = listOf(boundCandidate),
    )

    private companion object {
        const val BOUND_PROBLEM_ID = "problem-peifang"
        const val BOUND_REVISION_ID = "revision-peifang"
    }
}
