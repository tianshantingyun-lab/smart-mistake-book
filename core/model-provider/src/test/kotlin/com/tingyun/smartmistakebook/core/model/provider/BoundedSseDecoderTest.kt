package com.tingyun.smartmistakebook.core.model.provider

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedSseDecoderTest {
    @Test
    fun dataLinesInOneEventAreJoinedWithNewlines() = runBlocking {
        val decoded = mutableListOf<String>()
        val body = "event: message\r\ndata: first\r\ndata: second\r\n\r\ndata: [DONE]\r\n\r\n"

        BoundedSseDecoder(maxBytes = 1_024).decode(body.utf8Stream(), decoded::add)

        assertEquals(listOf("first\nsecond"), decoded)
    }

    @Test
    fun doneStopsBeforeTrailingEvents() = runBlocking {
        val decoded = mutableListOf<String>()
        val body = "data: before\n\ndata: [DONE]\n\ndata: after\n\n"

        BoundedSseDecoder(maxBytes = 1_024).decode(body.utf8Stream(), decoded::add)

        assertEquals(listOf("before"), decoded)
    }

    @Test
    fun carriageReturnOnlyLinesDispatchEvents() = runBlocking {
        val decoded = mutableListOf<String>()
        val body = "data: first\rdata: second\r\rdata: [DONE]\r\r"

        BoundedSseDecoder(maxBytes = 1_024).decode(body.utf8Stream(), decoded::add)

        assertEquals(listOf("first\nsecond"), decoded)
    }

    @Test
    fun exactlyOneLeadingUtf8BomIsIgnored() = runBlocking {
        val decoded = mutableListOf<String>()
        val body = "\uFEFFdata: first\n\ndata: [DONE]\n\n"

        BoundedSseDecoder(maxBytes = 1_024).decode(body.utf8Stream(), decoded::add)

        assertEquals(listOf("first"), decoded)
    }

    @Test
    fun mixedLineEndingsAndLeadingBomWorkAcrossReadBoundaries() = runBlocking {
        val decoded = mutableListOf<String>()
        val body = "\uFEFFdata: first\r\ndata: second\r\rdata: [DONE]\n\n"

        BoundedSseDecoder(maxBytes = 1_024).decode(
            body.oneByteAtATimeUtf8Stream(),
            decoded::add,
        )

        assertEquals(listOf("first\nsecond"), decoded)
    }

    @Test
    fun duplicateOrNonInitialBomIsRejected() {
        listOf(
            "\uFEFF\uFEFFdata: first\n\ndata: [DONE]\n\n",
            "data: first\n\n\uFEFFdata: [DONE]\n\n",
        ).forEach { body ->
            assertThrows(InvalidModelResponseException::class.java) {
                runBlocking {
                    BoundedSseDecoder(maxBytes = 1_024).decode(body.utf8Stream()) {}
                }
            }
        }
    }

    @Test
    fun invalidUtf8IsRejected() {
        val body = byteArrayOf(
            'd'.code.toByte(),
            'a'.code.toByte(),
            't'.code.toByte(),
            'a'.code.toByte(),
            ':'.code.toByte(),
            ' '.code.toByte(),
            0xC3.toByte(),
            0x28,
            '\n'.code.toByte(),
            '\n'.code.toByte(),
        )

        assertThrows(InvalidModelResponseException::class.java) {
            runBlocking {
                BoundedSseDecoder(maxBytes = 1_024).decode(ByteArrayInputStream(body)) {}
            }
        }
    }

    @Test
    fun cumulativeByteLimitIsEnforced() {
        val body = "data: ${"x".repeat(64)}\n\ndata: [DONE]\n\n"

        assertThrows(InvalidModelResponseException::class.java) {
            runBlocking {
                BoundedSseDecoder(maxBytes = 32).decode(body.utf8Stream()) {}
            }
        }
    }

    @Test
    fun endOfStreamBeforeDoneIsRejected() {
        val body = "data: unfinished\n\n"

        assertThrows(InvalidModelResponseException::class.java) {
            runBlocking {
                BoundedSseDecoder(maxBytes = 1_024).decode(body.utf8Stream()) {}
            }
        }
    }

    @Test
    fun eventLimitIsEnforced() {
        val body = "data: one\n\ndata: two\n\ndata: [DONE]\n\n"

        assertThrows(InvalidModelResponseException::class.java) {
            runBlocking {
                BoundedSseDecoder(
                    maxBytes = 1_024,
                    maxEvents = 1,
                ).decode(body.utf8Stream()) {}
            }
        }
    }

    private fun String.utf8Stream() =
        ByteArrayInputStream(toByteArray(StandardCharsets.UTF_8))

    private fun String.oneByteAtATimeUtf8Stream(): InputStream {
        val bytes = toByteArray(StandardCharsets.UTF_8)
        var index = 0
        return object : InputStream() {
            override fun read(): Int =
                if (index < bytes.size) bytes[index++].toInt() and 0xff else -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index >= bytes.size) return -1
                buffer[offset] = bytes[index++]
                return 1
            }
        }
    }
}
