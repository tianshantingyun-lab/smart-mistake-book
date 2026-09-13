package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `ModelConfigurationStore` 替身，供图片出网相关测试共用（原本内嵌在
 * `ConfiguredCleanImageGeneratorTest` 里，抽出来是为了不让第二份拷贝与它漂移）。
 *
 * `save` / `rotateApiKey` / `clear` 直接 `error(...)`：图片通道**不该**写配置，
 * 一旦有人接上，测试会立刻炸而不是静默通过。
 */
internal class FakeModelConfigurationStore(
    val snapshot: ModelConfigurationSnapshot = configuration(),
    var credentialAvailable: Boolean = true,
) : ModelConfigurationStore {
    private val state = MutableStateFlow(snapshot)

    override val configuration: Flow<ModelConfigurationSnapshot> = state

    override suspend fun save(
        update: ModelConfigurationUpdate,
        apiKey: ModelApiKey,
    ): ModelConfigurationMutationResult = error("image channels must not save configuration")

    override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
        error("image channels must not rotate the api key")

    override suspend fun readCredential(): ModelCredentialReadResult =
        if (credentialAvailable) {
            ModelCredentialReadResult.Available(
                state.value,
                ModelApiKey.from(TEST_API_KEY.toCharArray()),
            )
        } else {
            ModelCredentialReadResult.Missing
        }

    override suspend fun clear(): ModelConfigurationMutationResult =
        error("image channels must not clear configuration")

    companion object {
        const val BASE_URL = "https://provider.test/v1"
        const val MODEL_ID = "gpt-image-2"
        const val CONFIGURATION_VERSION = "test-config-v1"
        const val TEST_API_KEY = "test-secret"

        fun configuration(
            capability: ModelCapabilityVerification? = configuredCapability(),
        ) = ModelConfigurationSnapshot(
            provider = "provider",
            baseUrl = BASE_URL,
            modelId = MODEL_ID,
            isConfigured = true,
            updatedAtEpochMillis = 1,
            configurationVersion = CONFIGURATION_VERSION,
            capabilityVerification = capability,
        )

        fun configuredCapability(
            supportsImageInput: Boolean = true,
        ) = ModelCapabilityVerification(
            provider = "provider",
            baseUrl = BASE_URL,
            modelId = MODEL_ID,
            configurationVersion = CONFIGURATION_VERSION,
            configurationUpdatedAtEpochMillis = 1,
            supportsImageInput = supportsImageInput,
            supportsStructuredOutput = true,
            testedAtEpochMillis = 2,
        )
    }
}
