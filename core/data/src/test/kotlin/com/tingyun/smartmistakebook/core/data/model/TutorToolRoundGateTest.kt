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
import com.tingyun.smartmistakebook.core.model.disclosesQuestionCandidates
import com.tingyun.smartmistakebook.core.model.tutorToolAuthorization
import kotlinx.coroutines.runBlocking
import com.tingyun.smartmistakebook.core.domain.TUTOR_TOOL_DECLARATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具环**逐次准入**的接线测试（2026-09-21 裁定 ADR 0001 / D6/D7 改写）。
 *
 * 本文件此前钉住的是"无题轮写工具被拒"（F1/F2）：写工具准入看"这一次调用有没有锚住本轮
 * 的题"。**那条判据已被用户裁定废除**——无题轮不再结构性拒写，写不写由模型语义判定
 * （系统提示词教会），代码零场景分叉；唯一剩下的逐次结构性拒是 MASTERY_UPDATE 的
 * **代号白名单**（本会话已披露集合，D5）：非法/编造/未披露代号在到达执行器之前被拒。
 *
 * 因此本文件的断言语义整体翻转（用例保留、不改删）：
 * - 原来"无题锚 → 拒、不触达执行器"的用例 → 现在"无题锚 → 放行到执行器"（统一本地门
 *   MasteryWriteGate 在 runner 里做引文锚/置信度/冷却/配额）；
 * - 新增编造代号用例钉住替代性的结构性拒（白名单）。
 *
 * 仓库是否**调用**了这个单元，另由仪器用例
 * `RoomModelTaskT6MasteryInstrumentedTest` 端到端钉住（需要设备，由设备阶段实跑）。
 */
class TutorToolRoundGateTest {

    private val disclosed = setOf("K1", "K2")

    @Test
    fun `a write call with no question anchor still reaches the runner`() = runBlocking {
        // 原 F1 用例（断言语义翻转）：没有题锚（声明缺失、请求侧无已知锚）的写调用，
        // 只要代号在白名单内，就放行到执行器——由 runner 的统一本地门裁决，轮次层不再拒。
        val ran = mutableListOf<TutorToolName>()

        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(masteryUpdateCall(anchor = null)),
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), outcomes.map(TutorToolOutcome::tool))
        assertTrue("无题轮的写调用不再被轮次层结构性拒", outcomes.single().ok)
        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), ran)
    }

    @Test
    fun `a write call anchored in the round menu reaches the runner`() = runBlocking {
        // 有题锚的写调用：照旧放行（场景差异不再影响这条路径）。
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
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertTrue(outcomes.single().ok)
        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), ran)
    }

    @Test
    fun `an anchor pointing outside the round menu no longer gates the write`() = runBlocking {
        // 原用例（断言语义翻转）：越界题锚声明此前触发拒写；裁定后题锚不是写准入事实，
        // 越界声明不影响放行（"说错了"的题锚由轮次绑定语义处理，不是这里）。
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
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertTrue("越界题锚不再结构性拒写", outcomes.single().ok)
        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), ran)
    }

    @Test
    fun `a no-question round no longer refuses the reads at the round gate`() = runBlocking {
        // 原用例（断言语义翻转，D7）：大厅轮次的 MASTERY_READ / KNOWLEDGE_READ 不再被
        // "披露面装不下"的场景门拒发——它们到达执行器，科目边界由 runner 失败关闭
        // （no_subject），产出形态与题内同一条 renderMasteryRead。
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
            disclosedKnowledgeCodes = emptySet(),
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertEquals(
            "轮次门不再按场景拒读工具",
            listOf(true, true, true),
            outcomes.map(TutorToolOutcome::ok),
        )
        assertEquals(
            listOf(TutorToolName.MASTERY_READ, TutorToolName.KNOWLEDGE_READ, TutorToolName.NOTEBOOK_READ),
            ran,
        )
    }

    @Test
    fun `an authorized tool outside the declared set is still refused`() = runBlocking {
        // 意图授权矩阵仍在（它取模型自己的意图语义，不是场景）：意图没放行写工具时拒。
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
            authorizedTools = setOf(TutorToolName.NOTEBOOK_READ),
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertFalse(outcomes.single().ok)
        assertEquals("not_authorized", outcomes.single().errorKind)
        assertTrue(ran.isEmpty())
    }

    /**
     * F1 现场（原生 tool_calls 路由 + 有题轮）：Route A 的标准形态是 content=null，整轮信封
     * 无处放题锚声明。裁定后这条用例钉住的是：有题轮的原生写调用不因"模型没复述题锚"被拒。
     */
    @Test
    fun `a native tool round in a question round no longer refuses the write`() = runBlocking {
        val known = candidate()
        val input = respondInput(boundCandidate = known, knownRoundQuestion = known)

        // 走真实解析层：原生 tool_calls，MASTERY_UPDATE 的 arguments 里没有题锚
        // （schema 已不带那三个字段），terms 是已披露代号。
        val round = OpenAiModelProtocol.parseResponse(
            responseBody = nativeMasteryUpdateWithoutAnchor(),
            input = input,
            modelVersion = "test-model-v1",
        ) as com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
        assertNull("解析层不得替模型补声明", round.calls.single().boundQuestion)

        val ran = mutableListOf<TutorToolName>()
        val outcomes = tutorToolRoundOutcomes(
            calls = round.calls,
            // 与生产同路：意图矩阵仍逐次裁决（原生无 content 时按 Respond 推导 CURRENT_QUESTION_HELP）。
            authorizedTools = tutorToolAuthorization(
                round.intentDecision,
                TUTOR_TOOL_DECLARATIONS,
            ).allowedTools,
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertTrue("有题轮的原生写调用不得因『模型没复述题锚』被拒", outcomes.single().ok)
        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), ran)
    }

    /**
     * 原负方向用例（断言语义翻转，D6）：请求侧也没有已知题锚——真的无题轮——写调用现在
     * **放行到执行器**；拒不拒由 runner 的统一本地门（引文锚/置信度/冷却/配额）与门后的
     * 数据边界决定，轮次层不再以"有没有题"为由拒。
     */
    @Test
    fun `a native tool round in a round with no question reaches the runner`() = runBlocking {
        val ran = mutableListOf<TutorToolName>()
        val input = respondInput(boundCandidate = candidate(), knownRoundQuestion = null)

        val round = OpenAiModelProtocol.parseResponse(
            responseBody = nativeMasteryUpdateWithoutAnchor(),
            input = input,
            modelVersion = "test-model-v1",
        ) as com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput

        val outcomes = tutorToolRoundOutcomes(
            calls = round.calls,
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertTrue("无题轮的写调用放行到执行器（统一门在 runner 内）", outcomes.single().ok)
        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), ran)
    }

    /** 说错了 ≠ 没说，但也不再是"拒写"的理由：越界声明的原生写调用照旧放行到执行器。 */
    @Test
    fun `an out-of-menu native declaration does not gate the write`() = runBlocking {
        val ran = mutableListOf<TutorToolName>()
        val known = candidate()
        val input = respondInput(boundCandidate = known, knownRoundQuestion = known)

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
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertTrue(outcomes.single().ok)
        assertEquals(listOf(TutorToolName.MASTERY_UPDATE), ran)
    }

    // ---- 代号白名单（D5）：取代题锚门的结构性拒 ----

    @Test
    fun `a fabricated code is structurally refused and never reaches the runner`() = runBlocking {
        // 验收必测的 JVM 接线面：terms[0] 不在本会话已披露集合 → 结构性拒（协议错误路径），
        // 不进执行器、不进门、不落任何证据行。
        val ran = mutableListOf<TutorToolName>()

        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(
                TutorToolCall(
                    tool = TutorToolName.MASTERY_UPDATE,
                    rationale = "学生说「我现在理解配方法这一步了」",
                    terms = listOf("K9"),
                    direction = TutorEvidenceDirection.POSITIVE,
                    understanding = TutorUnderstandingTier.CONFIDENT,
                ),
            ),
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            disclosedKnowledgeCodes = disclosed,
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertFalse(outcomes.single().ok)
        assertEquals("invalid_knowledge_code", outcomes.single().errorKind)
        assertTrue("编造代号不得触达执行器", ran.isEmpty())
    }

    @Test
    fun `a lobby round without any disclosed code refuses the write structurally`() = runBlocking {
        // 大厅没有科目上下文与预披露节点（映射表不含节点）：白名单为空，任何代号都是
        // 编造——结构性拒。模型被提示词教会先确认科目，而不是这里分叉场景。
        val ran = mutableListOf<TutorToolName>()
        val lobbyInput = TutorLobbyInput(
            conversationId = "conv-1",
            messageOrdinal = 1,
            studentMessage = "我掌握二次函数了",
        )
        // 大厅的意图矩阵仍逐次裁决；这里直接给"意图已放行"的最宽授权，钉的是白名单本身。
        val outcomes = tutorToolRoundOutcomes(
            calls = listOf(
                TutorToolCall(
                    tool = TutorToolName.MASTERY_UPDATE,
                    rationale = "学生说「二次函数我现在会了」",
                    terms = listOf("K1"),
                    direction = TutorEvidenceDirection.POSITIVE,
                    understanding = TutorUnderstandingTier.CONFIDENT,
                ),
            ),
            authorizedTools = TUTOR_TOOL_DECLARATIONS,
            disclosedKnowledgeCodes = emptySet(),
            runTool = { call, _ -> ran += call.tool; ok(call.tool) },
            consumeExtendedResult = {},
        )

        assertFalse("无披露集的大厅写调用结构性拒", outcomes.single().ok)
        assertEquals("invalid_knowledge_code", outcomes.single().errorKind)
        assertTrue(ran.isEmpty())
        // 输入类型不参与这条判定（D6 零场景分叉）：同样的调用形状在题内输入上行为一致——
        // 白名单为空就拒，非空就放行到执行器。
        assertFalse(lobbyInput.disclosesQuestionCandidates())
    }

    private fun masteryUpdateCall(anchor: TutorRoundQuestionDeclaration?) = TutorToolCall(
        tool = TutorToolName.MASTERY_UPDATE,
        rationale = "学生说「我现在理解配方法这一步了」",
        terms = listOf("K1"),
        direction = TutorEvidenceDirection.POSITIVE,
        understanding = TutorUnderstandingTier.CONFIDENT,
        boundQuestion = anchor,
    )

    /** 原生 tool_calls 里一个没复述题锚的 MASTERY_UPDATE（arguments 无题锚字段，terms 是代号）。 */
    private fun nativeMasteryUpdateWithoutAnchor(): String = """
        {"choices":[{"message":{"role":"assistant","content":null,
         "tool_calls":[{"id":"call_1","type":"function",
           "function":{"name":"MASTERY_UPDATE",
             "arguments":"{\"terms\":[\"K1\"],\"rationale\":\"学生说理解了\",\"direction\":\"POSITIVE\",\"understanding\":\"CONFIDENT\",\"confidence\":0.85}"}}]}}]}
    """.trimIndent()

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

    /**
     * @param knownRoundQuestion 本轮请求侧已知的题锚。按 `TutorRespondInput` 的构造契约，
     *   已知锚必须是本轮候选之一，所以夹具只允许传菜单里的那一条。
     */
    private fun respondInput(
        boundCandidate: RelatedProblemCandidate,
        knownRoundQuestion: RelatedProblemCandidate? = null,
    ) = TutorRespondInput(
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
        knownRoundQuestion = knownRoundQuestion,
    )

    private companion object {
        const val BOUND_PROBLEM_ID = "problem-peifang"
        const val BOUND_REVISION_ID = "revision-peifang"
    }
}
