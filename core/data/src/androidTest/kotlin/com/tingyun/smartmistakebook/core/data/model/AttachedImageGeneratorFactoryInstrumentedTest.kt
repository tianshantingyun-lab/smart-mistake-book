package com.tingyun.smartmistakebook.core.data.model

import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 审计 N-28：**配图这条出网通道没有证据证明它真的走共享闸门**。
 *
 * `AttachedImageGeneratorTest` 直接构造 `AttachedImageGenerator`，绕开了工厂与闸门；
 * 而从 `resolve` 的返回值上看，"被闸门拒绝"与"放行了但出网失败"**完全一样**——
 * 两者都只是少一张图（`AttachedImageGenerator.resolveOne` 把任何异常都吞成 null）。
 * 所以这里从**生产装配点** `AttachedImageGeneratorFactory.create` 出发，用 `channelFactory`
 * 这个缝观察"闸门有没有放行到构造 channel 那一步"——构造 channel 是这条链上第一件会出网的事。
 *
 * 用仪器化而不是 JVM：工厂第一步就要 `AndroidCanonicalAssetVault(context)`，需要真的
 * Context（这也正是这条通道此前只有"直接构造 generator"那半个测试的原因）。
 * 最后一条（同意与凭据齐备时 channel **必须**被构造）是这套断言的地基——
 * 没有它，"没有 channel"与"代码根本不构造 channel"就分不开。
 */
class AttachedImageGeneratorFactoryInstrumentedTest {

    private val processImage = AttachedImage(
        imageId = "process-1",
        kind = AttachedImageKind.GENERATE_PROCESS,
        description = "数轴标注导数符号区间",
    )

    @Test
    fun revokingConsentStopsTheUploadBeforeAChannelExists() = runBlocking {
        val channels = mutableListOf<String>()

        val resolve = resolver(
            consent = FakeModelAgentConsentStore(granted = false),
            channels = channels,
        )

        assertNull(resolve(processImage))
        assertEquals(
            "撤销同意后不得再构造 channel——channel 一建就是要出网的",
            emptyList<String>(),
            channels,
        )
    }

    @Test
    fun noConsentChannelAtAllStopsTheUpload() = runBlocking {
        val channels = mutableListOf<String>()

        assertNull(resolver(consent = null, channels = channels)(processImage))

        assertEquals(emptyList<String>(), channels)
    }

    @Test
    fun noConfiguredCredentialStopsTheUpload() = runBlocking {
        val channels = mutableListOf<String>()

        assertNull(resolver(credentialAvailable = false, channels = channels)(processImage))

        assertEquals(emptyList<String>(), channels)
    }

    @Test
    fun aCapabilityTestThatNeverRanStopsTheUpload() = runBlocking {
        val channels = mutableListOf<String>()

        assertNull(resolver(capability = null, channels = channels)(processImage))

        assertEquals(emptyList<String>(), channels)
    }

    @Test
    fun aCapabilityTestWithoutImageInputStopsTheUpload() = runBlocking {
        val channels = mutableListOf<String>()

        val withoutImageInput = FakeModelConfigurationStore.configuredCapability(
            supportsImageInput = false,
        )

        assertNull(resolver(capability = withoutImageInput, channels = channels)(processImage))

        assertEquals(emptyList<String>(), channels)
    }

    @Test
    fun noConfigurationStoreAtAllNeverReachesTheChannel() = runBlocking {
        val channels = mutableListOf<String>()

        assertNull(resolver(configurationStore = null, channels = channels)(processImage))

        assertEquals(emptyList<String>(), channels)
    }

    @Test
    fun consentAndCredentialTogetherDoOpenTheChannel() = runBlocking {
        val channels = mutableListOf<String>()

        val resolve = resolver(channels = channels)

        // 假 channel 的 generate 抛异常 ⇒ 这张图拿不到 URI；本条断言的是**放行**，不是成功。
        assertNull(resolve(processImage))
        assertEquals(
            "同意与凭据齐备时必须走到构造 channel 那一步，否则上面那批「没有 channel」是空的",
            listOf(FakeModelConfigurationStore.BASE_URL),
            channels,
        )
    }

    // -----------------------------------------------------------------

    private fun resolver(
        consent: FakeModelAgentConsentStore? = FakeModelAgentConsentStore(),
        credentialAvailable: Boolean = true,
        capability: ModelCapabilityVerification? = FakeModelConfigurationStore.configuredCapability(),
        configurationStore: ModelConfigurationStore? = FakeModelConfigurationStore(
            snapshot = FakeModelConfigurationStore.configuration(capability = capability),
            credentialAvailable = credentialAvailable,
        ),
        channels: MutableList<String> = mutableListOf(),
    ): suspend (AttachedImage) -> String? = AttachedImageGeneratorFactory.create(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        configurationStore = configurationStore,
        modelAgentConsentStore = consent,
        resolveCurrentSheetBytes = { null },
        channelFactory = ImageChannelFactory { baseUrl, _ ->
            channels += baseUrl
            ProviderDownChannel
        },
    )

    /** Never reaches the provider: constructing the channel is the event under test. */
    private object ProviderDownChannel : ImageGenerationChannel {
        override suspend fun redrawClean(request: ImageRedrawRequest): ImageRedrawResult =
            throw UnsupportedOperationException("the attached-figure path generates, it does not redraw")

        override suspend fun generate(request: ImageGenerationRequest): ImageRedrawResult =
            throw IllegalStateException("provider down")
    }
}
