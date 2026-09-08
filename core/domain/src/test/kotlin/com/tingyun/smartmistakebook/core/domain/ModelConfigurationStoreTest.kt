package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模型配置的协议字段（spec 2026-09-08-multi-protocol §3.2）：快照与更新都携带所选协议，
 * 缺省为 OpenAI 兼容——老配置（没有该字段）零迁移，仍走原有协议。
 */
class ModelConfigurationStoreTest {

    @Test
    fun snapshotDefaultsToOpenAiCompatible() {
        assertEquals(
            ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelConfigurationSnapshot().protocol,
        )
        assertEquals(ModelProviderProtocol.DEFAULT, ModelConfigurationSnapshot().protocol)
    }

    @Test
    fun snapshotCarriesTheSelectedProtocol() {
        assertEquals(
            ModelProviderProtocol.ANTHROPIC_MESSAGES,
            ModelConfigurationSnapshot(protocol = ModelProviderProtocol.ANTHROPIC_MESSAGES).protocol,
        )
    }

    @Test
    fun updateDefaultsToOpenAiCompatibleAndCarriesTheSelectedProtocol() {
        assertEquals(
            ModelProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelConfigurationUpdate(
                provider = "provider-1",
                baseUrl = "https://api.example.com",
                modelId = "model-1",
            ).protocol,
        )
        assertEquals(
            ModelProviderProtocol.GEMINI_GENERATE_CONTENT,
            ModelConfigurationUpdate(
                provider = "provider-1",
                baseUrl = "https://api.example.com",
                modelId = "model-1",
                protocol = ModelProviderProtocol.GEMINI_GENERATE_CONTENT,
            ).protocol,
        )
    }
}
