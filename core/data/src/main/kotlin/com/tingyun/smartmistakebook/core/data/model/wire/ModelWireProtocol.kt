package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelTaskAdapters
import com.tingyun.smartmistakebook.core.data.model.parseObject
import com.tingyun.smartmistakebook.core.data.model.unwrapJsonFence
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

    /**
     * 能力探测的请求体（spec §3.4：请求按协议构造、判定断言共用 [ModelProbeSpec] 的令牌）。
     * 探测是"最小请求"，与任务请求无关，所以不在这里走 [requestBody]。
     */
    fun probeRequestBody(modelId: String, probe: ModelProbeKind): String

    /** 能力探测：从探测响应体里取出模型回复的文本（协议信封差异的唯一去处）。 */
    fun probeResponseText(body: String): String?
}

/** 按配置协议取实现；未实现的协议显式失败，不静默降级。 */
internal fun protocolFor(protocol: ModelProviderProtocol): ModelWireProtocol = when (protocol) {
    ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsProtocol
    ModelProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesProtocol
    else -> error("Protocol $protocol is not implemented yet (P3+)")
}

/**
 * 把模型的文本回复解析成任务输出（spec §3.1）：模型可能返回裸 JSON 或带 markdown 围栏，
 * 两者都经 [unwrapJsonFence] 归一，再交给协议无关的任务解析器。
 */
internal fun parseTaskOutputFromText(
    text: String,
    input: ModelTaskInput,
    modelVersion: String,
): ModelTaskOutput = OpenAiModelTaskAdapters.parse(
    parseObject(text.unwrapJsonFence()),
    input,
    modelVersion,
)
