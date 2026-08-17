package com.tingyun.smartmistakebook.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSseTest {
    @Test
    fun concatenatesDeltaContentAndIgnoresDone() {
        val raw = """
            data: {"choices":[{"delta":{"content":"{\"ok\":"}}]}

            data: {"choices":[{"delta":{"content":"true}"}}]}

            data: [DONE]

        """.trimIndent()
        assertEquals(
            "{\"ok\":true}",
            OpenAiSse.eventDataBlocks(raw).mapNotNull(OpenAiSse::deltaContent).joinToString(""),
        )
        val reconstructed = OpenAiSse.reconstructedChatCompletion(raw)
        assertTrue(reconstructed.contains("content"))
        assertTrue(reconstructed.contains("choices"))
    }

    @Test
    fun blankStreamIsRejected() {
        assertThrows(InvalidModelResponseException::class.java) {
            OpenAiSse.reconstructedChatCompletion("data: [DONE]\n\n")
        }
    }
}
