package com.tingyun.smartmistakebook.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiSseDeltaChunksTest {

    @Test
    fun yieldsOrderedDeltaBodiesAcrossChunks() {
        val raw = """
            data: {"choices":[{"delta":{"content":"{\"ok\":"}}]}

            data: {"choices":[{"delta":{"content":"true}"}}]}

            data: [DONE]

        """.trimIndent()

        val chunks = OpenAiSse.deltaChunks(raw).toList()
        assertEquals(listOf("{\"ok\":", "true}"), chunks)
    }

    @Test
    fun skipsEmptyAndWhitespaceDeltaBodies() {
        val raw = """
            data: {"choices":[{"delta":{"content":""}}]}

            data: {"choices":[{"delta":{"content":"  "}}]}

            data: {"choices":[{"delta":{"content":"real"}}]}

            data: [DONE]

        """.trimIndent()

        val chunks = OpenAiSse.deltaChunks(raw).toList()
        assertEquals(listOf("real"), chunks)
    }

    @Test
    fun skipsMalformedDeltaFrameAndKeepsGoodFrames() {
        val raw = """
            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: not-json

            data: {"choices":[{"delta":{"content":"tail"}}]}

            data: [DONE]

        """.trimIndent()

        assertEquals(listOf("ok", "tail"), OpenAiSse.deltaChunks(raw).toList())
    }

    @Test
    fun parsesContentFromARealSingleLineDeltaFrame() {
        val raw = """
            data: {"choices":[{"delta":{"content":"{\"ok\":true}"}}]}

            data: [DONE]

        """.trimIndent()

        assertEquals(listOf("{\"ok\":true}"), OpenAiSse.deltaChunks(raw).toList())
    }

    @Test
    fun terminatesOnDoneEvenIfTrailingBytesFollow() {
        val raw = """
            data: {"choices":[{"delta":{"content":"answer"}}]}

            data: [DONE]

            data: {"choices":[{"delta":{"content":"tail"}}]}

        """.trimIndent()

        assertEquals(listOf("answer"), OpenAiSse.deltaChunks(raw).toList())
    }
}
