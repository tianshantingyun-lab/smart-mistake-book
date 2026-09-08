package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import okhttp3.HttpUrl

/**
 * 一个协议族要提供的**全部协议相关行为**。任务 prompt 与 JSON 解析不在此层
 * （见 OpenAiModelTaskAdapters）：协议实现只负责"信封"。
 */
internal interface ModelWireProtocol {
    val protocol: ModelProviderProtocol
    /** 是否支持原生 tools 往返（仅 OpenAI 兼容为 true；新协议走 Route B）。 */
    val supportsNativeTools: Boolean
    /** 是否有 json_object 信封（无则靠 prompt 约束 JSON）。 */
    val supportsJsonObjectEnvelope: Boolean

    fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl

    fun headers(apiKey: CharArray, stream: Boolean): List<Pair<String, String>>

    fun requestBody(
        modelId: String,
        input: ModelTaskInput,
        images: List<ApprovedImage>,
        stream: Boolean,
        enableNativeTools: Boolean,
    ): String

    fun parseCompletion(body: String, input: ModelTaskInput, modelVersion: String): ModelTaskOutput

    /** SSE 单帧的文本增量；非文本帧返回 null。 */
    fun streamDelta(payload: String): String?

    /** 把整段 SSE 重建成一次完整响应体（供终态解析）。 */
    fun reconstructedBody(rawSse: String): String
}

/** 按配置协议取实现；未实现的协议在 P2+ 前显式失败。 */
internal fun protocolFor(protocol: ModelProviderProtocol): ModelWireProtocol = when (protocol) {
    ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsProtocol
    else -> error("Protocol $protocol is not implemented yet (P2+)")
}
