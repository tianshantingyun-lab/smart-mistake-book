package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 载体字段校验（spec model-intent-routing §3.1）。默认空 = 工具协议关闭；只有显式声明才开启。
 * Kotlin data class.copy() 不触发 init，故负向断言一律走主构造器。
 */
class TutorToolCarrierValidationTest {

    private fun lobby() = TutorLobbyInput(
        conversationId = "conv-1",
        messageOrdinal = 1,
        studentMessage = "帮我看看错题本里有没有二次函数的题",
        priorMessages = emptyList(),
    )

    private fun lobbyCarrying(
        declarations: List<TutorToolName> = emptyList(),
        rounds: List<TutorToolRoundResult> = emptyList(),
    ) = TutorLobbyInput(
        conversationId = "conv-1",
        messageOrdinal = 1,
        studentMessage = "帮我看看错题本里有没有二次函数的题",
        priorMessages = emptyList(),
        toolDeclarations = declarations,
        toolRoundResults = rounds,
    )

    private fun oneRound() = TutorToolRoundResult(
        roundOrdinal = 1,
        outcomes = listOf(
            TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_READ,
                ok = true,
                summaryMarkdown = "错题本匹配 2 条。",
            ),
        ),
    )

    @Test
    fun lobbyDefaultsToEmptyCarrierAndStaysRoundTrippable() {
        val request = ModelTaskRequest(
            requestId = "tutor-lobby:carrier",
            input = lobby(),
            occurredAtEpochMillis = 1_000,
        )
        val decoded = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request))
        assertEquals(request, decoded)
        val input = decoded.input as TutorLobbyInput
        assertEquals(emptyList<TutorToolName>(), input.toolDeclarations)
        assertEquals(emptyList<TutorToolRoundResult>(), input.toolRoundResults)
    }

    @Test
    fun lobbyAcceptsDeclaredReadTools() {
        val input = lobbyCarrying(
            declarations = listOf(TutorToolName.NOTEBOOK_READ, TutorToolName.MASTERY_READ),
        )
        assertEquals(2, input.toolDeclarations.size)
        assertThrows(IllegalArgumentException::class.java) {
            // 6 > MAX_TOOL_DECLARATIONS = 5
            lobbyCarrying(declarations = List(6) { TutorToolName.NOTEBOOK_READ })
        }
    }

    @Test
    fun lobbyRejectsDuplicateOrRoundWithoutDeclarations() {
        assertThrows(IllegalArgumentException::class.java) {
            lobbyCarrying(
                declarations = listOf(TutorToolName.MASTERY_READ, TutorToolName.MASTERY_READ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            lobbyCarrying(rounds = listOf(oneRound())) // rounds 需要声明集非空
        }
    }
}
