package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolRequestsOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route A（OpenAI 原生 tools 协议）双轨适配层测试（spec model-intent-routing §3 wire）。
 *
 * 双轨 = 响应驱动：请求带原生 `tools` schema 时，provider 支持则回 `assistant.tool_calls`，
 * 不支持则忽略 tools 字段、照常在 json_object 信封里吐 JSON——两条路在 parseResponse 分轨。
 * Route A 只替换"模型如何表达工具申请"这一段 wire；授权/执行/回填/多轮由 repository
 * 既有工具环接管（TutorToolRequestsOutput 对两路同形）。能力门控默认关（enableNativeTools=false
 * = Route B 现状）；本测试验证协议层就绪后两路都正确。
 */
class OpenAiNativeToolsProtocolTest {

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
        toolDeclarations = listOf(
            TutorToolName.KNOWLEDGE_READ,
            TutorToolName.NOTEBOOK_READ,
            TutorToolName.MASTERY_READ,
            TutorToolName.MASTERY_UPDATE,
        ),
    )

    private fun lobbyInput() = TutorLobbyInput(
        conversationId = "conv-1",
        messageOrdinal = 1,
        studentMessage = "帮我看看错题本里有没有二次函数",
        priorMessages = emptyList(),
        toolDeclarations = listOf(TutorToolName.NOTEBOOK_READ),
    )

    private fun toolCallEnvelope(nativeBody: String): String = """
        {"choices":[{"message":$nativeBody}]}
    """.trimIndent()

    @Test
    fun nativeToolsAdvertisedOnlyWhenEnabledAndDeclared() {
        // enableNativeTools=true + Respond 声明工具 → 请求含 tools schema
        val withTools = OpenAiModelProtocol.requestBody(
            modelId = "test-model",
            input = respondInput(),
            images = emptyList(),
            enableNativeTools = true,
        )
        assertTrue("应附原生 tools 声明", withTools.contains("\"tools\""))
        assertTrue("tools 应含 MASTERY_UPDATE 的 function schema", withTools.contains("\"name\":\"MASTERY_UPDATE\""))
        assertTrue("schema 应含 direction 枚举", withTools.contains("\"direction\""))
        assertTrue("schema 应 strict（additionalProperties:false）", withTools.contains("\"additionalProperties\":false"))

        // enableNativeTools=false → 现状（Route B），无 tools 字段
        val noTools = OpenAiModelProtocol.requestBody(
            modelId = "test-model",
            input = respondInput(),
            images = emptyList(),
        )
        assertFalse("默认不附原生 tools（Route B 现状）", noTools.contains("\"tools\""))
    }

    @Test
    fun masteryUpdateSchemaIsStrictWithMandatorySemanticFields() {
        // model 层 TutorToolCall 契约强制 MASTERY_UPDATE 必有 direction+understanding，
        // 故 strict schema（additionalProperties:false）的 required 必须覆盖全部 5 个字段，
        // 否则真实 strict provider 会判 schema 非法。
        val withTools = OpenAiModelProtocol.requestBody(
            modelId = "test-model",
            input = respondInput(),
            images = emptyList(),
            enableNativeTools = true,
        )
        // 该 body 含 4 个工具 schema，须切到 MASTERY_UPDATE 那个 function 块再取 required
        val masteryBlock = withTools.substringAfter("\"name\":\"MASTERY_UPDATE\"")
        val required = Regex("\"required\":\\[([^\\]]*)\\]").find(masteryBlock)?.groupValues?.get(1)
            ?: throw AssertionError("MASTERY_UPDATE schema 无 required 数组")
        assertTrue(
            "MASTERY_UPDATE strict schema 的 required 应含 direction+understanding+confidence（实得：$required）",
            required.contains("\"direction\"") &&
                required.contains("\"understanding\"") &&
                required.contains("\"confidence\"") &&
                required.contains("\"terms\"") &&
                required.contains("\"rationale\""),
        )
    }

    @Test
    fun readToolSchemaStaysMinimalRequired() {
        // 仅声明读工具（NOTEBOOK_READ）时 strict required 不应被 T6 语义字段污染
        val input = respondInput().copy(
            toolDeclarations = listOf(TutorToolName.NOTEBOOK_READ),
        )
        val withTools = OpenAiModelProtocol.requestBody(
            modelId = "test-model",
            input = input,
            images = emptyList(),
            enableNativeTools = true,
        )
        val required = Regex("\"required\":\\[([^\\]]*)\\]").find(withTools)?.groupValues?.get(1)
            ?: throw AssertionError("无 required 数组")
        assertTrue(
            "读工具 strict required 应仅为 terms+rationale（实得：$required）",
            required.contains("\"terms\"") && required.contains("\"rationale\"") && !required.contains("direction"),
        )
    }

    @Test
    fun lobbyWithNoNativeToolsEnabledStaysRouteB() {
        val body = OpenAiModelProtocol.requestBody(
            modelId = "test-model",
            input = lobbyInput(),
            images = emptyList(),
        )
        assertFalse(body.contains("\"tools\""))
    }

    @Test
    fun nativeToolCallsResponseParsesToToolRequestsOutput() {
        val envelope = toolCallEnvelope(
            """
            {"role":"assistant",
             "content":"{\"intentDecision\":{\"intent\":\"CURRENT_QUESTION_HELP\",\"confidence\":0.9,\"explicitActionRequest\":false,\"memoryPreference\":\"UNCHANGED\",\"requestedLocalCapability\":\"NONE\"}}",
             "tool_calls":[{"id":"call_1","type":"function",
               "function":{"name":"MASTERY_UPDATE",
                 "arguments":"{\"terms\":[\"knowledge-node-1\"],\"rationale\":\"学生明确说理解了配方法。\",\"direction\":\"POSITIVE\",\"understanding\":\"CONFIDENT\",\"confidence\":0.85}"}}]}
            """.trimIndent(),
        )
        val output = OpenAiModelProtocol.parseResponse(
            responseBody = envelope,
            input = respondInput(),
            modelVersion = "test-model-v1",
        )
        assertTrue("原生 tool_calls 应解析为工具申请轮", output is TutorToolRequestsOutput)
        val round = output as TutorToolRequestsOutput
        assertEquals(
            com.tingyun.smartmistakebook.core.model.TutorMessageIntent.CURRENT_QUESTION_HELP,
            round.intentDecision.intent,
        )
        assertEquals(1, round.calls.size)
        val call = round.calls[0]
        assertEquals(TutorToolName.MASTERY_UPDATE, call.tool)
        assertEquals(listOf("knowledge-node-1"), call.terms)
        assertEquals("学生明确说理解了配方法。", call.rationale)
        assertEquals(com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection.POSITIVE, call.direction)
        assertEquals(com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier.CONFIDENT, call.understanding)
        assertEquals(0.85, call.confidence, 1e-9)
    }

    @Test
    fun readToolNativeCallParsesTermsOnly() {
        val envelope = toolCallEnvelope(
            """
            {"role":"assistant",
             "content":"{\"intentDecision\":{\"intent\":\"MISTAKE_NOTEBOOK_LOOKUP\",\"confidence\":0.9,\"explicitActionRequest\":true,\"memoryPreference\":\"UNCHANGED\",\"requestedLocalCapability\":\"READ_MISTAKE_NOTEBOOK\",\"lookupTerms\":[\"二次函数\"]}}",
             "tool_calls":[{"id":"call_2","type":"function",
               "function":{"name":"NOTEBOOK_READ","arguments":"{\"terms\":[\"二次函数\"],\"rationale\":\"学生想找二次函数错题\"}"}}]}
            """.trimIndent(),
        )
        val output = OpenAiModelProtocol.parseResponse(
            responseBody = envelope,
            input = lobbyInput(),
            modelVersion = "test-model-v1",
        )
        assertTrue(output is TutorToolRequestsOutput)
        val round = output as TutorToolRequestsOutput
        assertEquals(
            com.tingyun.smartmistakebook.core.model.TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
            round.intentDecision.intent,
        )
        assertEquals(TutorToolName.NOTEBOOK_READ, round.calls.single().tool)
        assertEquals(listOf("二次函数"), round.calls.single().terms)
    }

    @Test
    fun nativeToolCallsWithoutIntentEnvelopeRejected() {
        val envelope = toolCallEnvelope(
            """
            {"role":"assistant","content":null,
             "tool_calls":[{"id":"call_3","type":"function",
               "function":{"name":"NOTEBOOK_READ","arguments":"{\"terms\":[\"二次函数\"],\"rationale\":\"学生想找二次函数错题\"}"}}]}
            """.trimIndent(),
        )
        try {
            OpenAiModelProtocol.parseResponse(
                responseBody = envelope,
                input = lobbyInput(),
                modelVersion = "test-model-v1",
            )
            assertTrue("缺 intentDecision 信封应契约拒", false)
        } catch (expected: com.tingyun.smartmistakebook.core.data.model.InvalidModelResponseException) {
            // 契约拒：工具轮必须携带本轮 intentDecision（授权矩阵依它门控，round 2 需重判）
        }
    }

    @Test
    fun contentResponseStillParsesAsRouteB() {
        // provider 忽略 tools、在 json_object 信封里回 content → 回落 Route B 正常解析
        val envelope = """
            {"choices":[{"message":{"role":"assistant",
              "content":"{\"intentDecision\":{\"intent\":\"CURRENT_QUESTION_HELP\",\"confidence\":0.9,\"explicitActionRequest\":false,\"memoryPreference\":\"UNCHANGED\",\"requestedLocalCapability\":\"NONE\",\"lookupTerms\":[]},\"messageMarkdown\":\"很好，我们看配方法。\",\"solutionRevealed\":false}"}}]}
        """.trimIndent()
        val output = OpenAiModelProtocol.parseResponse(
            responseBody = envelope,
            input = respondInput(),
            modelVersion = "test-model-v1",
        )
        assertTrue("无 tool_calls 时应回落 Route B 终答", output is TutorRespondOutput)
    }
}
