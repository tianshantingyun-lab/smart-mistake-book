package com.tingyun.smartmistakebook.core.data.model.wire

import com.tingyun.smartmistakebook.core.data.model.ApprovedImage
import com.tingyun.smartmistakebook.core.data.model.OpenAiModelProtocol
import com.tingyun.smartmistakebook.core.data.model.OpenAiSse
import com.tingyun.smartmistakebook.core.data.model.SSE_ACCEPT
import com.tingyun.smartmistakebook.core.data.model.JSON_MEDIA_TYPE
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import okhttp3.HttpUrl

/**
 * OpenAI Chat Completions 协议（spec §3.1）：现有行为的原样搬迁——本类只做委托，
 * 逻辑仍在 OpenAiModelProtocol/OpenAiSse，保证 P1 阶段行为逐字节不变。
 */
internal object OpenAiChatCompletionsProtocol : ModelWireProtocol {
    override val protocol = ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS
    override val supportsNativeTools = true
    override val supportsJsonObjectEnvelope = true

    override fun endpoint(baseUrl: HttpUrl, modelId: String, stream: Boolean): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegment("chat")
            .addPathSegment("completions")
            .build()

    override fun headers(apiKey: CharArray, stream: Boolean): List<Pair<String, String>> = listOf(
        "Authorization" to "Bearer ${String(apiKey)}",
        "Accept" to if (stream) SSE_ACCEPT else JSON_MEDIA_TYPE.toString(),
    )

    override fun requestBody(
        modelId: String,
        input: ModelTaskInput,
        images: List<ApprovedImage>,
        stream: Boolean,
        enableNativeTools: Boolean,
    ): String = OpenAiModelProtocol.requestBody(modelId, input, images, stream, enableNativeTools)

    override fun parseCompletion(body: String, input: ModelTaskInput, modelVersion: String): ModelTaskOutput =
        OpenAiModelProtocol.parseResponse(body, input, modelVersion)

    override fun streamDelta(payload: String): String? = OpenAiSse.deltaContent(payload)

    override fun reconstructedBody(rawSse: String): String = OpenAiSse.reconstructedChatCompletion(rawSse)
}
