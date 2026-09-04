package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
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
}
