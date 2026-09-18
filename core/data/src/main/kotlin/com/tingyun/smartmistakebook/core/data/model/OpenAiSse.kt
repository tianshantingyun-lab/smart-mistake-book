package com.tingyun.smartmistakebook.core.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object OpenAiSse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun eventDataBlocks(raw: String): List<String> {
        val blocks = ArrayList<String>()
        val current = StringBuilder()
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                flush(current, blocks)
                continue
            }
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trimStart()
            if (current.isNotEmpty()) current.append('\n')
            current.append(payload)
        }
        flush(current, blocks)
        return blocks.filter { it != "[DONE]" && it.isNotBlank() }
    }

    fun deltaContent(payload: String): String? {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val delta = first["delta"] as? JsonObject ?: return null
        return (delta["content"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * The chain-of-thought a reasoning model streams as its own delta. Providers spell the field
     * differently (`reasoning` here, `reasoning_content` elsewhere); both mean the same
     * student-visible thinking that the tutor shows while the answer is still forming.
     */
    fun deltaReasoning(payload: String): String? {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val delta = first["delta"] as? JsonObject ?: return null
        return (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
            ?: (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * Incremental delta bodies in transport order. Each emission is the raw content that arrived in
     * one SSE frame; a frame never contributes the [DONE] terminator or a blank body. Malformed
     * frames are skipped so a stray byte cannot poison an otherwise well-formed stream.
     *
     * A [DONE] terminator frame ends the stream: any bytes that follow it (e.g. a trailing chunk
     * that raced with the terminal event) are ignored.
     */
    fun deltaChunks(raw: String): Sequence<String> = sequence {
        for (block in OpenAiSse.eventDataBlocksTerminated(raw)) {
            if (block == DONE_BLOCK) break
            val content = deltaContent(block)
            if (!content.isNullOrBlank()) {
                yield(content)
            }
        }
    }

    internal fun eventDataBlocksTerminated(raw: String): Sequence<String> = sequence {
        val current = StringBuilder()
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                val block = current.toString()
                if (current.isNotEmpty()) {
                    yield(block)
                    current.clear()
                }
                continue
            }
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trimStart()
            if (current.isNotEmpty()) current.append('\n')
            current.append(payload)
        }
        if (current.isNotEmpty()) yield(current.toString())
    }

    internal const val DONE_BLOCK = "[DONE]"

    fun reconstructedChatCompletion(rawSse: String): String {
        val content = eventDataBlocks(rawSse)
            .mapNotNull(::deltaContent)
            .joinToString("")
        if (content.isBlank()) throw InvalidModelResponseException()
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "message",
                                    buildJsonObject { put("content", content) },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun flush(current: StringBuilder, blocks: MutableList<String>) {
        if (current.isEmpty()) return
        blocks.add(current.toString())
        current.clear()
    }
}
