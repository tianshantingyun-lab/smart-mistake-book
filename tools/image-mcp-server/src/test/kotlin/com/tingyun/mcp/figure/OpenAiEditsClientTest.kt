package com.tingyun.mcp.figure

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenAiEditsClientTest {

    private val DUMMY_KEY = "test-key"

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiEditsClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        // MockWebServer 本地假 key，非真实凭据：仅用于断言 Authorization 头透传
        client = OpenAiEditsClient(
            apiKey = DUMMY_KEY,
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
            client = OkHttpClient(),
        )
    }

    @AfterEach
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

        val result = client.redrawClean(
            sourceImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            mimeType = "image/jpeg",
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/images/edits", recorded.path)
        assertEquals("Bearer $DUMMY_KEY", recorded.getHeader("Authorization"))
        val boundary = recorded.getHeader("Content-Type")
            ?.substringAfter("boundary=")
            ?.removeSurrounding("\"")
        assertTrue(boundary != null && boundary.isNotEmpty(), "multipart boundary missing")
        val rawBody = recorded.body.readByteArray()
        val parts = splitMultipartParts(rawBody, boundary!!)
        assertTrue(
            parts.map { it.name }.containsAll(listOf("model", "images[]", "prompt", "quality")),
            "raw(${rawBody.size}) boundary=$boundary names=${parts.map { it.name }}",
        )
        // The binary image part must not corrupt text-part decoding, so assert per-part.
        val partsByName = parts.associate { part ->
            part.name to part.valueUtf8
        }
        assertEquals("gpt-image-2", partsByName["model"])
        assertTrue(partsByName.containsKey("prompt"), "keys=${partsByName.keys}")
        assertTrue(partsByName.containsKey("quality"))
        assertTrue(partsByName.containsKey("size"))
        assertTrue(partsByName.containsKey("output_format"))
        assertTrue(result.image.contentEquals(png))
        assertEquals("image/png", result.mimeType)
        assertEquals("gpt-image-2", result.model)
    }

    @Test
    fun `surfaces http failures as an exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = runCatching {
            client.redrawClean(sourceImage = byteArrayOf(1), mimeType = "image/png")
        }.exceptionOrNull()

        assertTrue(error is EditsApiException)
        assertTrue(error?.message?.contains("500") == true)
    }

    @Test
    fun `rejects an empty image payload`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"created":1,"data":[]}"""))

        val error = runCatching {
            client.redrawClean(sourceImage = byteArrayOf(1), mimeType = "image/png")
        }.exceptionOrNull()

        assertTrue(error is EditsApiException)
    }

    @Test
    fun `generates a text-to-image figure via the generations endpoint`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 8, 7)
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(
            MockResponse().setBody("""{"created":1,"data":[{"b64_json":"$b64","mime_type":"image/png"}]}"""),
        )

        val result = client.generate(instruction = "数轴标注导数正负区间")

        val recorded = server.takeRequest()
        assertEquals("/v1/images/generations", recorded.path)
        assertEquals("Bearer $DUMMY_KEY", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("gpt-image-2"))
        assertTrue(body.contains("数轴标注导数正负区间"))
        assertTrue(result.image.contentEquals(png))
        assertEquals("image/png", result.mimeType)
    }

    @Test
    fun `generates a seeded figure via the edits endpoint when a source image is given`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 5, 6)
        val b64 = Base64.getEncoder().encodeToString(png)
        server.enqueue(
            MockResponse().setBody("""{"created":1,"data":[{"b64_json":"$b64","mime_type":"image/png"}]}"""),
        )

        client.generate(
            instruction = "添加辅助线并重绘此题图",
            sourceImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            sourceMimeType = "image/png",
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/images/edits", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("辅助线"))
    }

    @Test
    fun `generate requires source image and mime type together`() = runBlocking {
        val error = runCatching {
            client.generate(
                instruction = "画辅助线",
                sourceImage = byteArrayOf(1),
                sourceMimeType = null,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    /** Splits a multipart body (raw bytes) into its parts, decoding each value separately. */
    private fun splitMultipartParts(body: ByteArray, boundary: String): List<MultipartPart> {
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val sections = mutableListOf<ByteArray>()
        var start = indexOf(body, delimiter, 0) + delimiter.size
        while (start >= 0) {
            val next = indexOf(body, delimiter, start)
            if (next < 0) break
            sections.add(body.copyOfRange(start, next))
            start = next + delimiter.size
        }
        return sections.mapNotNull { section ->
            val headerEnd = indexOfBytes(section, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            if (headerEnd < 0) return@mapNotNull null
            val headers = section.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
            val value = section.copyOfRange(headerEnd + 4, section.size)
                .trimTrailingCrlf()
            val name = Regex("name=\"([^\"]+)\"").find(headers)?.groupValues?.get(1) ?: return@mapNotNull null
            MultipartPart(name = name, value = value)
        }
    }

    private fun ByteArray.trimTrailingCrlf(): ByteArray {
        var end = size
        while (end >= 2 && this[end - 2] == '\r'.code.toByte() && this[end - 1] == '\n'.code.toByte()) {
            end -= 2
        }
        if (end >= 1 && this[end - 1] == '\n'.code.toByte()) end -= 1
        return copyOfRange(0, end)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        outer@ for (i in fromIndex..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int = indexOf(haystack, needle, 0)

    private data class MultipartPart(val name: String, val value: ByteArray) {
        val valueUtf8: String get() = value.toString(Charsets.UTF_8)
    }

}
