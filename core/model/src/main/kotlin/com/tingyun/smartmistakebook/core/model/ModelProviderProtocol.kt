package com.tingyun.smartmistakebook.core.model

/** 模型 Provider 的线上协议族（spec 2026-09-08-multi-protocol §3.1）。 */
enum class ModelProviderProtocol(val wireId: String) {
    OPENAI_CHAT_COMPLETIONS("openai-chat-completions-v1"),
    OPENAI_RESPONSES("openai-responses-v1"),
    ANTHROPIC_MESSAGES("anthropic-messages-v1"),
    GEMINI_GENERATE_CONTENT("gemini-generate-content-v1beta"),
    ;

    companion object {
        val DEFAULT: ModelProviderProtocol = OPENAI_CHAT_COMPLETIONS
    }
}
