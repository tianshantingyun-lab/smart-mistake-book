package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.data.model.FakeModelConfigurationStore
import com.tingyun.smartmistakebook.core.data.model.ImageChannelFactory
import com.tingyun.smartmistakebook.core.data.model.ImageGenerationChannel
import com.tingyun.smartmistakebook.core.data.model.ImageGenerationRequest
import com.tingyun.smartmistakebook.core.data.model.ImageRedrawRequest
import com.tingyun.smartmistakebook.core.data.model.ImageRedrawResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
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
 *
 * 闸门本身的三条件由 `ImageCredentialGateTest` 覆盖（去手写与配图共用同一个
 * `resolveImageCredential`）。这里补的是**这条通道确实走那个闸门**——
 * 尤其是撤回出网（「配置模型即同意」之下＝删除配置）时它**不能**再去构造 channel
 * （那一步就会出网）。
 */
class ConfiguredCleanImageGeneratorTest {

    @Test
    fun declinesWhenTheBuildCannotEgressAndNeverTouchesTheNetwork() = runBlocking {
        var channelCalled = false
        val generator = generator(
            networkRequestsAllowed = false,
            channelFactory = ChannelFactory { _, _ ->
                channelCalled = true
                FakeRedrawChannel("cleaned-bytes", "image/png")
            },
        )

        assertNull(generator.generateClean(bytes("a"), "image/jpeg"))
        assertTrue(
            "拒绝必须在构造 channel 之前发生——channel 一建就是要出网的",
            !channelCalled,
        )
    }

    @Test
    fun declinesOnceTheConfigurationIsGoneAndNeverTouchesTheNetwork() = runBlocking {
        // 撤回出网 = 删除配置（审计 S-2 在新口径下的载体）。比下面那条"没配凭据"更强：
        // 它同时钉住**拒绝发生在构造 channel 之前**——channel 一建就是要出网的。
        var channelCalled = false
        val generator = generator(
            credentialAvailable = false,
            channelFactory = ChannelFactory { _, _ ->
                channelCalled = true
                FakeRedrawChannel("cleaned-bytes", "image/png")
            },
        )

        assertNull(generator.generateClean(bytes("a"), "image/jpeg"))
        assertTrue("配置删了就必须拒绝，且不能碰网络", !channelCalled)
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
            capability = FakeModelConfigurationStore.configuredCapability(supportsImageInput = false),
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

        assertEquals(FakeModelConfigurationStore.BASE_URL, capturedBase)
        assertEquals("Bearer ${FakeModelConfigurationStore.TEST_API_KEY}", capturedAuthorization)
        assertArrayEquals(bytes("cleaned-bytes"), result!!.bytes)
        assertEquals("image/png", result.mimeType)
    }

    // -----------------------------------------------------------------

    private fun generator(
        credentialAvailable: Boolean = true,
        capability: ModelCapabilityVerification? = FakeModelConfigurationStore.configuredCapability(),
        networkRequestsAllowed: Boolean = true,
        channelFactory: ImageChannelFactory = ImageChannelFactory { _, _ ->
            FakeRedrawChannel("default", "image/png")
        },
    ) = ConfiguredCleanImageGenerator(
        configurationStore = FakeModelConfigurationStore(
            snapshot = FakeModelConfigurationStore.configuration(capability = capability),
            credentialAvailable = credentialAvailable,
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
    ) : ImageChannelFactory {
        override suspend fun create(
            baseUrl: String,
            authorization: String,
        ): ImageGenerationChannel = factory(baseUrl, authorization)
    }

    private fun bytes(value: String) = value.toByteArray()
}
