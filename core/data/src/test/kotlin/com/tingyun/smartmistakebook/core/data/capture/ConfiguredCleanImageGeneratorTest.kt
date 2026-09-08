package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.data.model.ImageGenerationChannel
import com.tingyun.smartmistakebook.core.data.model.ImageGenerationRequest
import com.tingyun.smartmistakebook.core.data.model.ImageRedrawRequest
import com.tingyun.smartmistakebook.core.data.model.ImageRedrawResult
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gating + channel-selection contract for the production clean-redraw generator.
 * The network POST itself is covered by OpenAiImageGenerationChannel's
 * MockWebServer tests; here a fake channel factory substitutes the network.
 */
class ConfiguredCleanImageGeneratorTest {

    @Test
    fun declinesWhenNetworkIsNotAllowed() = runBlocking {
        val generator = generator(networkRequestsAllowed = false)

        assertNull(generator.generateClean(bytes("a"), "image/jpeg"))
    }

    @Test
    fun declinesWhenNoCredentialIsConfigured() = runBlocking {
        val generator = generator(credentialAvailable = false)

        assertNull(generator.generateClean(bytes("a"), "image/jpeg"))
    }

    @Test
    fun declinesWhenCapabilityTestNeverRan() = runBlocking {
        val generator = generator(capability = null)

        assertNull(generator.generateClean(bytes("a"), "image/jpeg"))
    }

    @Test
    fun declinesWhenCapabilityTestDidNotVerifyImageInput() = runBlocking {
        val generator = generator(
            capability = configuredCapability().copy(supportsImageInput = false),
        )

        assertNull(generator.generateClean(bytes("a"), "image/jpeg"))
    }

    @Test
    fun declinesWhenTheRedrawFailsAndDoesNotThrow() = runBlocking {
        var channelCalled = false
        val generator = generator(
            channelFactory = ChannelFactory { _, _ ->
                channelCalled = true
                throw IllegalStateException("provider down")
            },
        )

        assertNull(generator.generateClean(bytes("a"), "image/jpeg"))
        assertTrue(channelCalled)
    }

    @Test
    fun passesConfiguredBaseAndBearerAndReturnsTheCleanResult() = runBlocking {
        var capturedBase: String? = null
        var capturedAuthorization: String? = null
        val generator = generator(
            channelFactory = ChannelFactory { base, authorization ->
                capturedBase = base
                capturedAuthorization = authorization
                FakeRedrawChannel(cleanBytes = "cleaned-bytes", cleanMime = "image/png")
            },
        )

        val result = generator.generateClean(bytes("source"), "image/jpeg")

        assertEquals("https://provider.test/v1", capturedBase)
        assertEquals("Bearer test-secret", capturedAuthorization)
        assertArrayEquals(bytes("cleaned-bytes"), result!!.bytes)
        assertEquals("image/png", result.mimeType)
    }

    // -----------------------------------------------------------------

    private fun generator(
        credentialAvailable: Boolean = true,
        capability: ModelCapabilityVerification? = configuredCapability(),
        networkRequestsAllowed: Boolean = true,
        channelFactory: ChannelFactory = ChannelFactory { _, _ ->
            FakeRedrawChannel("default", "image/png")
        },
    ) = ConfiguredCleanImageGenerator(
        configurationStore = FakeStore(
            credentialAvailable = credentialAvailable,
            snapshot = configuration(capability = capability),
        ),
        channelFactory = channelFactory,
        networkRequestsAllowed = networkRequestsAllowed,
    )

    private class FakeRedrawChannel(
        private val cleanBytes: String,
        private val cleanMime: String,
    ) : ImageGenerationChannel {
        override suspend fun redrawClean(request: ImageRedrawRequest): ImageRedrawResult =
            ImageRedrawResult(
                imageBytes = cleanBytes.toByteArray(),
                mimeType = cleanMime,
            )

        override suspend fun generate(request: ImageGenerationRequest): ImageRedrawResult =
            throw UnsupportedOperationException("generate not exercised by this fake")
    }

    private class ChannelFactory(
        private val factory: (String, String) -> ImageGenerationChannel,
    ) : ConfiguredCleanImageGenerator.ChannelFactory {
        override suspend fun create(
            baseUrl: String,
            authorization: String,
        ): ImageGenerationChannel = factory(baseUrl, authorization)
    }

    private class FakeStore(
        private val credentialAvailable: Boolean,
        snapshot: ModelConfigurationSnapshot,
    ) : ModelConfigurationStore {
        private val state = MutableStateFlow(snapshot)
        override val configuration: Flow<ModelConfigurationSnapshot> = state
        override suspend fun save(
            update: ModelConfigurationUpdate,
            apiKey: ModelApiKey,
        ): ModelConfigurationMutationResult = error("not used")
        override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
            error("not used")
        override suspend fun readCredential(): ModelCredentialReadResult =
            if (credentialAvailable) {
                ModelCredentialReadResult.Available(
                    state.value,
                    ModelApiKey.from("test-secret".toCharArray()),
                )
            } else {
                ModelCredentialReadResult.Missing
            }
        override suspend fun clear(): ModelConfigurationMutationResult = error("not used")
    }

    private fun bytes(value: String) = value.toByteArray()

    private companion object {
        const val BASE_URL = "https://provider.test/v1"
        const val MODEL_ID = "gpt-image-2"
        const val GENERATION = "test-config-v1"

        fun configuration(capability: ModelCapabilityVerification?) = ModelConfigurationSnapshot(
            provider = "provider",
            baseUrl = BASE_URL,
            modelId = MODEL_ID,
            isConfigured = true,
            updatedAtEpochMillis = 1,
            configurationVersion = GENERATION,
            capabilityVerification = capability,
        )

        fun configuredCapability() = ModelCapabilityVerification(
            provider = "provider",
            baseUrl = BASE_URL,
            modelId = MODEL_ID,
            configurationVersion = GENERATION,
            configurationUpdatedAtEpochMillis = 1,
            supportsImageInput = true,
            supportsStructuredOutput = true,
            testedAtEpochMillis = 2,
        )
    }
}
