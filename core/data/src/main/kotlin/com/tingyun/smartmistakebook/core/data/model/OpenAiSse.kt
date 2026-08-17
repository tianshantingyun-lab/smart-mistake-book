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
