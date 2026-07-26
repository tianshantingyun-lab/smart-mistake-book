package com.tingyun.smartmistakebook.core.data.model

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class BoundedSseDecoder(
    private val maxBytes: Int,
    private val maxEvents: Int = MAX_SSE_EVENTS,
) {
    init {
        require(maxBytes > 0) { "SSE byte limit must be positive" }
        require(maxEvents > 0) { "SSE event limit must be positive" }
    }

    suspend fun decode(
        stream: InputStream,
        onData: suspend (String) -> Unit,
    ) {
        val line = ByteArrayOutputStream()
        val dataLines = mutableListOf<String>()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        var eventCount = 0

        suspend fun dispatch(): Boolean {
            if (dataLines.isEmpty()) return false
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            if (data == DONE_MARKER) return true
            eventCount += 1
            if (eventCount > maxEvents) throw InvalidModelResponseException()
            onData(data)
            return false
        }

        suspend fun consumeLine(): Boolean {
            val bytes = line.toByteArray()
            line.reset()
            val contentLength = if (bytes.lastOrNull() == CARRIAGE_RETURN) {
                bytes.size - 1
            } else {
                bytes.size
            }
            val decoded = decodeUtf8(bytes, contentLength)
            if (decoded.isEmpty()) return dispatch()
            when {
                decoded == DATA_FIELD -> dataLines += ""
                decoded.startsWith(DATA_FIELD_PREFIX) -> {
                    dataLines += decoded.substring(DATA_FIELD_PREFIX.length).removePrefix(" ")
                }
            }
            return false
        }

        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            for (index in 0 until read) {
                totalBytes += 1
                if (totalBytes > maxBytes) throw InvalidModelResponseException()
                if (buffer[index] == LINE_FEED) {
                    if (consumeLine()) return
                } else {
                    line.write(buffer[index].toInt())
                }
            }
        }

        if (line.size() > 0 && consumeLine()) return
        if (dispatch()) return
        throw InvalidModelResponseException()
    }

    private fun decodeUtf8(
        bytes: ByteArray,
        length: Int,
    ): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
            .toString()
    } catch (_: Exception) {
        throw InvalidModelResponseException()
    }

    private companion object {
        const val DATA_FIELD = "data"
        const val DATA_FIELD_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val MAX_SSE_EVENTS = 4_096
        const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
        const val LINE_FEED: Byte = '\n'.code.toByte()
    }
}
