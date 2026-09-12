package com.tingyun.smartmistakebook

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTester
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
import com.tingyun.smartmistakebook.core.model.NetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CapabilityScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun syntheticTestRunsOnlyAfterTapAndShowsTheByokBoundary() {
        val configuration = configuration()
        val store = FakeStore(configuration)
        var testCalls = 0
        val verification = verification(configuration)
        val tester = ModelCapabilityTester {
            testCalls += 1
            store.state.value = configuration.copy(capabilityVerification = verification)
            ModelCapabilityTestResult.Completed(verification)
        }
        composeRule.setContent {
            MaterialTheme {
                CapabilityScreen(
                    capabilities = AppCapabilitySnapshot(
                        networkMode = NetworkMode.LOCAL_FIRST,
                        cameraCaptureAvailable = true,
                        trustedOcrAvailable = false,
                        tutorTeachingEnabled = true,
                        remoteModelConfigured = true,
                    ),
                    configurationStore = store,
                    capabilityTester = tester,
                    onBack = {},
                )
            }
        }

        composeRule.waitForIdle()
        assertEquals(0, testCalls)
        composeRule.onNodeWithTag("capability_direct_connection_notice").assertExists()
        composeRule.onNodeWithTag("capability_secret_notice").assertExists()
        composeRule.onNodeWithText(
            "能力测试只使用内置合成样例，不发送真实题目或学习记录。",
        ).assertExists()
        captureCurrentScreen()

        composeRule.onNodeWithTag("capability_test").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { testCalls == 1 }
        composeRule.onNodeWithText("测试通过：图片读取和讲解都可用。")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun savingLocksTheWholeFormUntilTheSubmittedKeyIsHandled() {
        val initial = configuration()
        val store = AsyncFakeStore(initial)
        setCapabilityContent(store = store)

        composeRule.onNodeWithTag("capability_api_key")
            .performScrollTo()
            .performTextInput("temporary-secret")
        composeRule.onNodeWithTag("capability_save").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { store.saveStarted.isCompleted }

        assertFormIsLocked()
        composeRule.onNodeWithText("正在保存").assertExists()

        store.saveResult.complete(ModelConfigurationMutationResult.Success(initial))
        composeRule.onNodeWithText("配置已安全保存在本机；请继续测试图片与讲解能力。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("capability_save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun testResultIsRemovedAsSoonAsTheSavedFormIsEdited() {
        val initial = configuration()
        val store = AsyncFakeStore(initial)
        val result = CompletableDeferred<ModelCapabilityTestResult>()
        val started = CompletableDeferred<Unit>()
        setCapabilityContent(
            store = store,
            tester = ModelCapabilityTester {
                started.complete(Unit)
                result.await()
            },
        )

        composeRule.onNodeWithTag("capability_test").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { started.isCompleted }
        assertFormIsLocked()
        composeRule.onNodeWithText("正在测试").assertExists()

        result.complete(ModelCapabilityTestResult.Completed(verification(initial)))
        composeRule.onNodeWithText("测试通过：图片读取和讲解都可用。")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("capability_provider")
            .performScrollTo()
            .performTextReplacement("另一服务商")
        composeRule.onNodeWithText("测试通过：图片读取和讲解都可用。")
            .assertDoesNotExist()
    }

    @Test
    fun clearingHasItsOwnProgressCopyAndLocksTheWholeForm() {
        val store = AsyncFakeStore(configuration())
        setCapabilityContent(store = store)

        composeRule.onNodeWithTag("capability_clear").performScrollTo().performClick()
        // 清除不可撤销，先过确认框；这一步本身就是"按钮不再直清"的守卫证据。
        composeRule.onNodeWithTag("capability_clear_confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { store.clearStarted.isCompleted }

        assertFormIsLocked()
        composeRule.onNodeWithText("正在清除").assertExists()
        composeRule.onNodeWithText("正在保存").assertDoesNotExist()

        store.clearResult.complete(
            ModelConfigurationMutationResult.Success(ModelConfigurationSnapshot()),
        )
        composeRule.onNodeWithText("本机配置与密钥已清除。")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun cancellingTheClearConfirmationKeepsTheConfiguration() {
        // 这条是确认框**非空洞**的那一半：若有人把守卫去掉改回"点击即清"，点完
        // `capability_clear` 就会立刻开始清除，这里对"未开始清除"的断言会立刻变红。
        val store = AsyncFakeStore(configuration())
        setCapabilityContent(store = store)

        composeRule.onNodeWithTag("capability_clear").performScrollTo().performClick()
        composeRule.onNodeWithTag("capability_clear_cancel").performClick()

        composeRule.onNodeWithText("取消").assertDoesNotExist()
        assertFalse("取消后不得开始清除", store.clearStarted.isCompleted)
        // 表单没被锁住，说明没有进入 CLEARING。
        composeRule.onNodeWithTag("capability_provider").performScrollTo().assertIsEnabled()
    }

    @Test
    fun protocolPickerShowsTheSavedProtocolAndSavesTheSelection() {
        val saved = java.util.concurrent.atomic.AtomicReference<ModelConfigurationUpdate?>(null)
        val store = ProtocolRecordingStore(
            ModelConfigurationSnapshot(
                provider = "兼容服务",
                baseUrl = "https://api.example.com/v1",
                modelId = "vision-model",
                protocol = ModelProviderProtocol.ANTHROPIC_MESSAGES,
                isConfigured = true,
                updatedAtEpochMillis = 1_000L,
                configurationVersion = "configuration-v1",
            ),
            saved,
        )
        setCapabilityContent(store)

        // 已保存的协议呈选中态。
        composeRule.onNodeWithTag("capability_protocol_ANTHROPIC_MESSAGES")
            .performScrollTo()
            .assertIsSelected()

        composeRule.onNodeWithTag("capability_protocol_GEMINI_GENERATE_CONTENT")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("capability_api_key").performScrollTo().performTextInput("secret")
        composeRule.onNodeWithTag("capability_save").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(
            ModelProviderProtocol.GEMINI_GENERATE_CONTENT,
            saved.get()?.protocol,
        )
    }

    private class ProtocolRecordingStore(
        initial: ModelConfigurationSnapshot,
        private val saved: java.util.concurrent.atomic.AtomicReference<ModelConfigurationUpdate?>,
    ) : ModelConfigurationStore {
        private val state = MutableStateFlow(initial)
        override val configuration: Flow<ModelConfigurationSnapshot> = state

        override suspend fun save(
            update: ModelConfigurationUpdate,
            apiKey: ModelApiKey,
        ): ModelConfigurationMutationResult {
            saved.set(update)
            return ModelConfigurationMutationResult.Success(
                ModelConfigurationSnapshot(
                    provider = update.provider,
                    baseUrl = update.baseUrl,
                    modelId = update.modelId,
                    protocol = update.protocol,
                    isConfigured = true,
                    updatedAtEpochMillis = 2_000L,
                    configurationVersion = "configuration-v2",
                ),
            )
        }

        override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
            error("Not used")

        override suspend fun readCredential(): ModelCredentialReadResult = error("Not used")

        override suspend fun clear(): ModelConfigurationMutationResult = error("Not used")
    }

    private fun setCapabilityContent(
        store: ModelConfigurationStore,
        tester: ModelCapabilityTester? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CapabilityScreen(
                    capabilities = AppCapabilitySnapshot(
                        networkMode = NetworkMode.LOCAL_FIRST,
                        cameraCaptureAvailable = true,
                        trustedOcrAvailable = false,
                        tutorTeachingEnabled = true,
                        remoteModelConfigured = true,
                    ),
                    configurationStore = store,
                    capabilityTester = tester,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertFormIsLocked() {
        listOf(
            "capability_provider",
            "capability_model_id",
            "capability_base_url",
            "capability_api_key",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
    }

    private fun captureCurrentScreen() {
        val resolver = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val displayName = "capability-config-current.png"
        val relativePath = "Pictures/SmartMistakeBookQA/"
        resolver.delete(
            collection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
        )
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val saved = checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        check(saved) { "Capability screenshot could not be encoded" }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    private fun configuration() = ModelConfigurationSnapshot(
        provider = "兼容服务",
        baseUrl = "https://api.example.com/v1",
        modelId = "vision-model",
        isConfigured = true,
        updatedAtEpochMillis = 1_000L,
        configurationVersion = "configuration-v1",
    )

    private fun verification(configuration: ModelConfigurationSnapshot) =
        ModelCapabilityVerification(
            provider = configuration.provider,
            baseUrl = configuration.baseUrl,
            modelId = configuration.modelId,
            configurationVersion = configuration.configurationVersion,
            configurationUpdatedAtEpochMillis = configuration.updatedAtEpochMillis,
            supportsImageInput = true,
            supportsStructuredOutput = true,
            testedAtEpochMillis = 2_000L,
        )

    private class FakeStore(initial: ModelConfigurationSnapshot) : ModelConfigurationStore {
        val state = MutableStateFlow(initial)
        override val configuration: Flow<ModelConfigurationSnapshot> = state

        override suspend fun save(
            update: ModelConfigurationUpdate,
            apiKey: ModelApiKey,
        ): ModelConfigurationMutationResult = error("Not used")

        override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
            error("Not used")

        override suspend fun readCredential(): ModelCredentialReadResult = error("Not used")

        override suspend fun clear(): ModelConfigurationMutationResult = error("Not used")
    }

    private class AsyncFakeStore(initial: ModelConfigurationSnapshot) : ModelConfigurationStore {
        private val state = MutableStateFlow(initial)
        override val configuration: Flow<ModelConfigurationSnapshot> = state
        val saveStarted = CompletableDeferred<Unit>()
        val saveResult = CompletableDeferred<ModelConfigurationMutationResult>()
        val clearStarted = CompletableDeferred<Unit>()
        val clearResult = CompletableDeferred<ModelConfigurationMutationResult>()

        override suspend fun save(
            update: ModelConfigurationUpdate,
            apiKey: ModelApiKey,
        ): ModelConfigurationMutationResult {
            saveStarted.complete(Unit)
            return saveResult.await()
        }

        override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
            error("Not used")

        override suspend fun readCredential(): ModelCredentialReadResult = error("Not used")

        override suspend fun clear(): ModelConfigurationMutationResult {
            clearStarted.complete(Unit)
            return clearResult.await()
        }
    }
}
