package com.tingyun.smartmistakebook.core.data.model

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class OpenAiImageGenerationChannelTest {

    private lateinit var server: MockWebServer
    private lateinit var channel: OpenAiImageGenerationChannel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        channel = OpenAiImageGenerationChannel(
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
            client = okhttp3.OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts a clean-redraw edit request and decodes the returned image`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(
            MockResponse().setBody(
                """{"created":1,"data":[{"b64_json":"$b64","mime_type":"image/png"}]}""",
            ),
        )

        val result = channel.redrawClean(
            ImageRedrawRequest(
                photoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                photoMimeType = "image/png",
                instruction = "去除手写笔迹，保留印刷题面，重绘干净。",
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/images/edits", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue(bodyText.contains("gpt-image-2"))
        assertTrue(bodyText.contains("去除手写笔迹"))
        assertTrue(bodyText.contains("quality"))
        assertTrue(result.imageBytes.contentEquals(png))
        assertEquals("image/png", result.mimeType)
    }

    @Test
    fun `surfaces http failures as an exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = runCatching {
            channel.redrawClean(
                ImageRedrawRequest(
                    photoBytes = byteArrayOf(1),
                    photoMimeType = "image/png",
                    instruction = "清理",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is ImageGenerationException)
        assertTrue(error?.message?.contains("500") == true)
    }

    @Test
    fun `rejects an empty image payload`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"created":1,"data":[]}"""))

        val error = runCatching {
            channel.redrawClean(
                ImageRedrawRequest(
                    photoBytes = byteArrayOf(1),
                    photoMimeType = "image/png",
                    instruction = "清理",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is ImageGenerationException)
    }
}
