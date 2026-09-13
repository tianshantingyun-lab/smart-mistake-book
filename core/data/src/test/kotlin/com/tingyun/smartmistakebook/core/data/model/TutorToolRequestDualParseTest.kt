package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.MISSING_TOOL_CONFIDENCE
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorToolRequestDualParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun lobbyInput() = TutorLobbyInput(
        conversationId = "conv-1",
        messageOrdinal = 1,
        studentMessage = "帮我看看错题本里有没有二次函数",
        priorMessages = emptyList(),
    )

    private fun respondInput() = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "数学",
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        studentMessage = "这一步怎么来的？",
        priorMessages = emptyList(),
        requestedMove = null,
    )

    private fun toolRequestPayload() = json.parseToJsonElement("""
        {"intentDecision":{"intent":"MISTAKE_NOTEBOOK_LOOKUP","confidence":0.9,
          "explicitActionRequest":true,"memoryPreference":"UNCHANGED",
          "requestedLocalCapability":"READ_MISTAKE_NOTEBOOK","lookupTerms":["二次函数"]},
         "toolRequests":[{"tool":"NOTEBOOK_READ","terms":["二次函数"],"rationale":"学生想找二次函数错题"}]}
    """.trimIndent()).jsonObject

    private fun finalAnswerPayload() = json.parseToJsonElement("""
        {"intentDecision":{"intent":"MISTAKE_NOTEBOOK_LOOKUP","confidence":0.9,
          "explicitActionRequest":true,"memoryPreference":"UNCHANGED",
          "requestedLocalCapability":"READ_MISTAKE_NOTEBOOK","lookupTerms":["二次函数"]},
         "messageMarkdown":"错题本里有 2 道二次函数相关错题。"}
    """.trimIndent()).jsonObject

    private fun respondFinalAnswerPayload() = json.parseToJsonElement("""
        {"intentDecision":{"intent":"MISTAKE_NOTEBOOK_LOOKUP","confidence":0.9,
          "explicitActionRequest":true,"memoryPreference":"UNCHANGED",
          "requestedLocalCapability":"READ_MISTAKE_NOTEBOOK","lookupTerms":["二次函数"]},
         "messageMarkdown":"错题本里有 2 道二次函数相关错题。",
         "solutionRevealed":false}
    """.trimIndent()).jsonObject

    @Test
    fun toolRequestPayloadParsesToToolRequestsOutput() {
        val output = OpenAiModelTaskAdapters.parse(
            toolRequestPayload(), lobbyInput(), "test-model-v1",
        )
        assertTrue("应解析为工具申请轮", output is TutorToolRequestsOutput)
        val round = output as TutorToolRequestsOutput
        assertEquals(TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP, round.intentDecision.intent)
        assertEquals(1, round.calls.size)
        assertEquals(TutorToolName.NOTEBOOK_READ, round.calls[0].tool)
        assertEquals(listOf("二次函数"), round.calls[0].terms)
    }

    @Test
    fun finalAnswerPayloadParsesToLobbyOutput() {
        val output = OpenAiModelTaskAdapters.parse(
            finalAnswerPayload(), lobbyInput(), "test-model-v1",
        )
        assertTrue("无 toolRequests 应解析为终答", output is TutorLobbyOutput)
    }

    @Test
    fun respondToolRequestPayloadParsesToToolRequestsOutput() {
        val output = OpenAiModelTaskAdapters.parse(
            toolRequestPayload(), respondInput(), "test-model-v1",
        )
        assertTrue("含 toolRequests 的 Respond payload 应解析为工具申请轮", output is TutorToolRequestsOutput)
        val round = output as TutorToolRequestsOutput
        assertEquals(TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP, round.intentDecision.intent)
        assertEquals(1, round.calls.size)
        assertEquals(TutorToolName.NOTEBOOK_READ, round.calls[0].tool)
        assertEquals(listOf("二次函数"), round.calls[0].terms)
    }

    @Test
    fun respondFinalAnswerPayloadParsesToRespondOutput() {
        val output = OpenAiModelTaskAdapters.parse(
            respondFinalAnswerPayload(), respondInput(), "test-model-v1",
        )
        assertTrue("无 toolRequests 的 Respond payload 应解析为终答", output is TutorRespondOutput)
        val respond = output as TutorRespondOutput
        assertEquals("错题本里有 2 道二次函数相关错题。", respond.messageMarkdown)
        assertEquals("question-1", respond.questionDocumentId)
        assertEquals(false, respond.solutionRevealed)
    }

    @Test
    fun masteryUpdateToolRequestParsesSemanticFields() {
        val payload = json.parseToJsonElement("""
            {"intentDecision":{"intent":"CURRENT_QUESTION_HELP","confidence":0.95,
              "explicitActionRequest":false,"memoryPreference":"UNCHANGED",
              "requestedLocalCapability":"NONE","lookupTerms":[]},
             "toolRequests":[{"tool":"MASTERY_UPDATE","terms":["knowledge-node-1"],
               "rationale":"学生说现在理解了配方法，且刚独立做对一道同类题。",
               "direction":"POSITIVE","understanding":"MASTERED","difficultyTier":"MEDIUM","confidence":0.85}]}
        """.trimIndent()).jsonObject

        val output = OpenAiModelTaskAdapters.parse(payload, respondInput(), "test-model-v1")
        assertTrue(output is TutorToolRequestsOutput)
        val round = output as TutorToolRequestsOutput
        assertEquals(1, round.calls.size)
        val call = round.calls[0]
        assertEquals(TutorToolName.MASTERY_UPDATE, call.tool)
        assertEquals(listOf("knowledge-node-1"), call.terms)
        assertEquals(com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection.POSITIVE, call.direction)
        assertEquals(com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier.MASTERED, call.understanding)
        assertEquals(com.tingyun.smartmistakebook.core.model.TutorDifficultyTier.MEDIUM, call.difficultyTier)
        assertEquals(0.85, call.confidence, 1e-9)
    }

    /**
     * 审计 §5.4.10（批 1 第 1.5 项）：结构化 `toolRequests` 路径上，provider 漏发
     * `confidence` 时解析侧必须留一个**过不了门**的值。`0.8` 恰好越过 `0.7`，
     * 于是"模型没判过置信度"被当成"模型很有把握"——这是模式 E。
     */
    @Test
    fun masteryUpdateWithoutConfidenceParsesBelowTheEvidenceGate() {
        val payload = json.parseToJsonElement("""
            {"intentDecision":{"intent":"CURRENT_QUESTION_HELP","confidence":0.95,
              "explicitActionRequest":false,"memoryPreference":"UNCHANGED",
              "requestedLocalCapability":"NONE","lookupTerms":[]},
             "toolRequests":[{"tool":"MASTERY_UPDATE","terms":["knowledge-node-1"],
               "rationale":"学生说现在理解了配方法。",
               "direction":"POSITIVE","understanding":"UNCERTAIN"}]}
        """.trimIndent()).jsonObject

        val output = OpenAiModelTaskAdapters.parse(payload, respondInput(), "test-model-v1")
        assertTrue(output is TutorToolRequestsOutput)
        val call = (output as TutorToolRequestsOutput).calls.single()
        assertEquals(MISSING_TOOL_CONFIDENCE, call.confidence, 0.0)
        assertTrue(
            "缺省置信度必须低于证据门，实得 ${call.confidence}",
            call.confidence < MasteryWriteGate.EVIDENCE_CONFIDENCE_THRESHOLD,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun masteryUpdateWithoutDirectionIsRejectedByModelContract() {
        // MASTERY_UPDATE 缺 direction/understanding 触发 TutorToolCall.init require——
        // 模型协议违规，契约层 fail-fast（绝不让无方向证据进 gate）。
        val payload = json.parseToJsonElement("""
            {"intentDecision":{"intent":"CURRENT_QUESTION_HELP","confidence":0.95,
              "explicitActionRequest":false,"memoryPreference":"UNCHANGED",
              "requestedLocalCapability":"NONE","lookupTerms":[]},
             "toolRequests":[{"tool":"MASTERY_UPDATE","terms":["knowledge-node-1"],
               "rationale":"学生说现在理解了。"}]}
        """.trimIndent()).jsonObject

        OpenAiModelTaskAdapters.parse(payload, respondInput(), "test-model-v1")
    }
}
