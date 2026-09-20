package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 答案暴露按绑定：**无题轮永不产生暴露记录**。
 *
 * 暴露记录是"学生看过这道题的答案"的证据。它此前只要求五项身份精确匹配（会话 / 修订 /
 * 题面 id / 轮次号 / 圈序号）——那些都只说明"这一轮属于这个会话"，不说明"这一轮真的在说某一道
 * 题"。按轮次绑定之后，Respond 也可以是无题轮（声明缺失、候选不在菜单内、锚词核不过），此时
 * 题面只是会话带进来的上下文。锚不到题的暴露记录没有主人：任何按题查询都会把它错认成"学生看过
 * **这道**题的答案"。
 *
 * 这里同时钉住"既有身份断言一条都没放宽"：五项里有任何一项对不上都仍然拒绝。
 */
class TutorSolutionExposureAuthorityTest {

    @Test
    fun `a no-question round can never expose the answer`() {
        val input = respondInput()
        val output = respondOutput(boundQuestion = null)

        assertFalse(
            "无题轮：声明了 solutionRevealed、学生也明确索要，仍然不得产生暴露",
            output.canExposeSolutionFor(input, requiresRoundQuestionBinding = true),
        )
    }

    @Test
    fun `a bound round with the full identity match and an explicit request may expose`() {
        val input = respondInput()
        val output = respondOutput(boundQuestion = binding())

        assertTrue(output.canExposeSolutionFor(input, requiresRoundQuestionBinding = true))
    }

    @Test
    fun `a legacy reply keeps the pre-binding exposure semantics`() {
        // 旧 schema 行里没有"有没有题"这一维：它的输出没有 boundQuestion 字段（恒 null）。
        // 那些**真的展示过完整解答并已记为暴露**的历史回复，升级后必须仍然被认作已暴露——
        // 否则持久化的曝光行会被候选键过滤掉，正文被换成"还没有完整看到"，会话记忆一起回退。
        val output = respondOutput(boundQuestion = null)

        assertTrue(
            output.canExposeSolutionFor(respondInput(), requiresRoundQuestionBinding = false),
        )
        // 同一份输出在新行上就是无题轮：同一份判据，只在"绑定是否适用"上分岔。
        assertFalse(
            output.canExposeSolutionFor(respondInput(), requiresRoundQuestionBinding = true),
        )
    }

    @Test
    fun `a legacy reply still needs every identity field and an explicit request`() {
        val mismatches = listOf(
            respondOutput(boundQuestion = null).copy(sessionId = "other-session"),
            respondOutput(boundQuestion = null).copy(responseOrdinal = 2),
            respondOutput(boundQuestion = null).copy(solutionRevealed = false),
        )

        mismatches.forEach { output ->
            assertFalse(
                "身份不匹配时必须拒绝：$output",
                output.canExposeSolutionFor(respondInput(), requiresRoundQuestionBinding = false),
            )
        }
        assertFalse(
            respondOutput(boundQuestion = null)
                .canExposeSolutionFor(
                    respondInput().copy(studentMessage = "这一步为什么成立"),
                    requiresRoundQuestionBinding = false,
                ),
        )
    }

    @Test
    fun `a declaration alone is not enough when the student never asked for the answer`() {
        val input = respondInput().copy(studentMessage = "这一步为什么成立")
        val output = respondOutput(boundQuestion = binding())

        assertFalse(output.canExposeSolutionFor(input, requiresRoundQuestionBinding = true))
    }

    @Test
    fun `a bound round still requires every identity field to match`() {
        val binding = binding()
        val mismatches = listOf(
            respondOutput(boundQuestion = binding).copy(sessionId = "other-session"),
            respondOutput(boundQuestion = binding).copy(draftRevisionNumber = 3),
            respondOutput(boundQuestion = binding).copy(questionDocumentId = "other-question"),
            respondOutput(boundQuestion = binding).copy(responseOrdinal = 2),
            respondOutput(boundQuestion = binding).copy(cycleOrdinal = 2),
            respondOutput(boundQuestion = binding).copy(turnOrdinal = 2),
            respondOutput(boundQuestion = binding).copy(solutionRevealed = false),
        )

        mismatches.forEach { output ->
            assertFalse("身份不匹配时必须拒绝：$output", output.canExposeSolutionFor(respondInput(), requiresRoundQuestionBinding = true))
        }
    }

    @Test
    fun `a binding for another question does not authorize this round`() {
        // 绑的是另一道题：暴露记录的题面 id 会对不上本轮题面 —— 身份断言在这里起作用。
        val output = respondOutput(
            boundQuestion = TutorRoundQuestionDeclaration(
                problemId = "other-problem",
                problemRevisionId = "other-revision",
                anchorTerms = listOf("这道题"),
            ),
        )

        assertTrue(
            "绑定本身非空即可通过本函数（它只判「有没有题」）；题面是否同一道由 questionDocumentId 断言把关",
            output.canExposeSolutionFor(respondInput(), requiresRoundQuestionBinding = true),
        )
        assertFalse(
            output.copy(questionDocumentId = "other-question")
                .canExposeSolutionFor(respondInput(), requiresRoundQuestionBinding = true),
        )
    }

    private fun binding() = TutorRoundQuestionDeclaration(
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        anchorTerms = listOf("这道题"),
    )

    private fun respondInput() = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 2,
        subject = "MATH",
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        studentMessage = "请告诉我答案，这道题我不会",
    )

    private fun respondOutput(
        boundQuestion: TutorRoundQuestionDeclaration?,
    ) = TutorRespondOutput(
        sessionId = "session-1",
        draftRevisionNumber = 2,
        questionDocumentId = "question-1",
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        messageMarkdown = "完整解答：先把二次项系数化为 1。",
        solutionRevealed = true,
        boundQuestion = boundQuestion,
        modelVersion = "model-v1",
    )
}
