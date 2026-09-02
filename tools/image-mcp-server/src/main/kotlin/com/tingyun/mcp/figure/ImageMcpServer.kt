package com.tingyun.mcp.figure

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * MCP figure server: exposes `redraw_clean_problem` — an image-to-image tool that
 * sends a photographed problem to gpt-image-2 (`/v1/images/edits`) and returns a
 * clean, handwriting-free redraw as base64. Images travel as base64 strings in the
 * JSON tool contract so any MCP client/host can call it.
 *
 * Env: OPENAI_API_KEY required.
 */
fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("OPENAI_API_KEY must be set")
        kotlin.system.exitProcess(2)
    }
    val edits = OpenAiEditsClient(apiKey = apiKey)

    val server = Server(
        Implementation(
            name = "figure-redraw",
            version = "0.1.0",
        ),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    )

    server.registerRedrawTool(edits)

    val transport = StdioServerTransport(
        input = System.`in`.asInput(),
        output = System.out.asSink().buffered(),
    ) { }

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}

private fun Server.registerRedrawTool(edits: OpenAiEditsClient) {
    addTool(
        name = "redraw_clean_problem",
        description = "把一道被拍摄的题目照片重绘成干净的题面图：保留印刷题面文字与图形，去除手写笔迹。输入原图 base64，返回干净图 base64。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("source_image_b64") {
                    put("type", "string")
                    put("description", "被拍摄题目照片的 base64 编码字节")
                }
                putJsonObject("source_mime_type") {
                    put("type", "string")
                    put("description", "原图 MIME 类型：image/jpeg、image/png 或 image/webp")
                }
            },
            required = listOf("source_image_b64", "source_mime_type"),
        ),
    ) { request ->
        val args = request.arguments ?: return@addTool errorResult("missing arguments")
        val sourceB64 = args["source_image_b64"]?.jsonPrimitive?.contentOrNull
        val mimeType = args["source_mime_type"]?.jsonPrimitive?.contentOrNull
        if (sourceB64 == null || mimeType == null) {
            return@addTool errorResult("source_image_b64 and source_mime_type are required")
        }
        val decoded = try {
            Base64.getDecoder().decode(sourceB64)
        } catch (failure: IllegalArgumentException) {
            return@addTool errorResult("source_image_b64 is not valid base64")
        }
        if (decoded.isEmpty() || decoded.size > MAX_INPUT_BYTES) {
            return@addTool errorResult("source image is empty or exceeds 20MB")
        }
        val result = try {
            edits.redrawClean(sourceImage = decoded, mimeType = mimeType)
        } catch (failure: Exception) {
            return@addTool errorResult("redraw failed: ${failure.message}")
        }
        CallToolResult(
            content = listOf(
                TextContent(
                    buildJsonObject {
                        put("status", "ok")
                        put("image_b64", Base64.getEncoder().encodeToString(result.image))
                        put("mime_type", result.mimeType)
                        put("model", result.model)
                    }.toString(),
                ),
            ),
        )
    }
}

private fun errorResult(message: String) = CallToolResult(
    content = listOf(TextContent(message)),
    isError = true,
)

private const val MAX_INPUT_BYTES = 20L * 1024L * 1024L
