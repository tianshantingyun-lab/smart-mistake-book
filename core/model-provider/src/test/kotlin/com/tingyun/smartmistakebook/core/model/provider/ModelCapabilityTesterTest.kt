package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestStartResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerificationWriteResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityTesterTest {
    @Test
    fun userTriggeredTestUsesTwoSyntheticProbesAndPersistsBothResults() = runBlocking {
        val store = FakeConfigurationStore(configuration())
        val requestBodies = mutableListOf<String>()
        val tester = OpenAiCompatibleModelCapabilityTester(
            configurationStore = store,
            transport = modelTransport { _, _, body ->
                requestBodies += body
                if ("image_url" in body) {
                    ModelHttpResponse(200, envelope("Q7M2"))
                } else {
                    ModelHttpResponse(
                        200,
                        envelope("{\"capability_check\":\"SMART_MISTAKE_BOOK_STRUCTURED_V1\"}"),
                    )
                }
            },
            clock = { 2_000L },
            probeTimeoutMillis = 1_000L,
        )

        val result = tester.testSavedConfiguration()

        val completed = result as ModelCapabilityTestResult.Completed
        assertTrue(completed.verification.supportsImageInput)
        assertTrue(completed.verification.supportsStructuredOutput)
        assertEquals(2, requestBodies.size)
        assertTrue(requestBodies.any { "image_url" in it })
        assertTrue(requestBodies.any { "response_format" in it })
        requestBodies.forEach { body ->
            assertFalse("questionDocument" in body)
            assertFalse("learningEvidence" in body)
            assertFalse("studentMessage" in body)
        }
        assertEquals(completed.verification, store.state.value.capabilityVerification)
    }

    @Test
    fun imageProbeStillRunsWhenStructuredOutputIsUnsupported() = runBlocking {
        val store = FakeConfigurationStore(configuration())
        var imageProbeCalls = 0
        val tester = OpenAiCompatibleModelCapabilityTester(
            configurationStore = store,
            transport = modelTransport { _, _, body ->
                if ("image_url" in body) {
                    imageProbeCalls += 1
                    ModelHttpResponse(200, envelope("Q7M2"))
                } else {
                    ModelHttpResponse(400, "")
                }
            },
            clock = { 2_000L },
            probeTimeoutMillis = 1_000L,
        )

        val completed = tester.testSavedConfiguration() as ModelCapabilityTestResult.Completed

        assertEquals(1, imageProbeCalls)
        assertTrue(completed.verification.supportsImageInput)
        assertFalse(completed.verification.supportsStructuredOutput)
    }

    @Test
    fun imageProbeRejectsAnIncorrectImageCode() = runBlocking {
        val store = FakeConfigurationStore(configuration())
        val tester = OpenAiCompatibleModelCapabilityTester(
            configurationStore = store,
            transport = modelTransport { _, _, body ->
                if ("image_url" in body) {
                    ModelHttpResponse(200, envelope("Q7N2"))
                } else {
                    ModelHttpResponse(
                        200,
                        envelope("{\"capability_check\":\"SMART_MISTAKE_BOOK_STRUCTURED_V1\"}"),
                    )
                }
            },
            clock = { 2_000L },
            probeTimeoutMillis = 1_000L,
        )

        val completed = tester.testSavedConfiguration() as ModelCapabilityTestResult.Completed

        assertFalse(completed.verification.supportsImageInput)
        assertTrue(completed.verification.supportsStructuredOutput)
    }

    @Test
    fun resultIsNotPersistedWhenConfigurationChangesDuringTheTest() = runBlocking {
        val store = FakeConfigurationStore(configuration())
        var calls = 0
        val tester = OpenAiCompatibleModelCapabilityTester(
            configurationStore = store,
            transport = modelTransport { _, _, body ->
                calls += 1
                if (calls == 2) {
                    store.state.value = configuration().copy(
                        configurationVersion = "configuration-v2",
                        updatedAtEpochMillis = 3_000L,
                    )
                }
                if ("image_url" in body) {
                    ModelHttpResponse(200, envelope("Q7M2"))
                } else {
                    ModelHttpResponse(
                        200,
                        envelope("{\"capability_check\":\"SMART_MISTAKE_BOOK_STRUCTURED_V1\"}"),
                    )
                }
            },
            clock = { 2_000L },
            probeTimeoutMillis = 1_000L,
        )

        assertEquals(
            ModelCapabilityTestResult.ConfigurationChanged,
            tester.testSavedConfiguration(),
        )
        assertEquals(null, store.state.value.capabilityVerification)
    }

    @Test
    fun authenticationFailureRevokesAPreviouslyReadyConfiguration() = runBlocking {
        val configuration = configuration()
        val priorVerification = ModelCapabilityVerification(
            provider = configuration.provider,
            baseUrl = configuration.baseUrl,
            modelId = configuration.modelId,
            configurationVersion = configuration.configurationVersion,
            configurationUpdatedAtEpochMillis = configuration.updatedAtEpochMillis,
            supportsImageInput = true,
            supportsStructuredOutput = true,
            testedAtEpochMillis = 1_500L,
        )
        val store = FakeConfigurationStore(
            configuration.copy(capabilityVerification = priorVerification),
        )
        val tester = OpenAiCompatibleModelCapabilityTester(
            configurationStore = store,
            transport = modelTransport { _, _, _ -> ModelHttpResponse(401, "") },
            clock = { 2_000L },
            probeTimeoutMillis = 1_000L,
        )

        assertEquals(
            ModelCapabilityTestResult.AuthenticationFailed,
            tester.testSavedConfiguration(),
        )
        val revoked = requireNotNull(store.state.value.capabilityVerification)
        assertFalse(revoked.supportsImageInput)
        assertFalse(revoked.supportsStructuredOutput)
        assertEquals(2_000L, revoked.testedAtEpochMillis)
    }

    @Test
    fun olderSuccessCannotOverwriteNewerAuthenticationFailure() = runBlocking {
        val store = FakeConfigurationStore(configuration())
        val olderProbeStarted = CompletableDeferred<Unit>()
        val releaseOlderProbe = CompletableDeferred<Unit>()
        val olderTester = OpenAiCompatibleModelCapabilityTester(
            configurationStore = store,
            transport = modelTransport { _, _, body ->
                if (!olderProbeStarted.isCompleted) {
                    olderProbeStarted.complete(Unit)
                    releaseOlderProbe.await()
                }
                if ("image_url" in body) {
                    ModelHttpResponse(200, envelope("Q7M2"))
                } else {
                    ModelHttpResponse(
                        200,
                        envelope("{\"capability_check\":\"SMART_MISTAKE_BOOK_STRUCTURED_V1\"}"),
                    )
                }
            },
            clock = { 3_000L },
            probeTimeoutMillis = 5_000L,
        )
        val newerTester = OpenAiCompatibleModelCapabilityTester(
            configurationStore = store,
            transport = modelTransport { _, _, _ -> ModelHttpResponse(401, "") },
            clock = { 2_000L },
            probeTimeoutMillis = 1_000L,
        )

        coroutineScope {
            val olderResult = async { olderTester.testSavedConfiguration() }
            olderProbeStarted.await()
            val newerResult = async { newerTester.testSavedConfiguration() }

            assertEquals(ModelCapabilityTestResult.AuthenticationFailed, newerResult.await())
            releaseOlderProbe.complete(Unit)
            assertEquals(ModelCapabilityTestResult.ConfigurationChanged, olderResult.await())
        }

        val retained = requireNotNull(store.state.value.capabilityVerification)
        assertFalse(retained.supportsImageInput)
        assertFalse(retained.supportsStructuredOutput)
        assertEquals(2_000L, retained.testedAtEpochMillis)
        assertEquals(2L, retained.testStartSequence)
    }

    private fun modelTransport(
        post: suspend (String, CharArray, String) -> ModelHttpResponse,
    ): ModelHttpTransport = ModelHttpTransport { baseUrl, apiKey, requestBody, beforeEnqueue ->
        beforeEnqueue()
        post(baseUrl, apiKey, requestBody)
    }

    private fun configuration() = ModelConfigurationSnapshot(
        provider = "兼容服务",
        baseUrl = "https://api.example.com/v1",
        modelId = "vision-model",
        isConfigured = true,
        updatedAtEpochMillis = 1_000L,
        configurationVersion = "configuration-v1",
    )

    private fun envelope(content: String): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "message",
                            buildJsonObject { put("content", content) },
                        )
                    },
                )
            },
        )
    }.toString()

    private class FakeConfigurationStore(initial: ModelConfigurationSnapshot) :
        ModelConfigurationStore {
        val state = MutableStateFlow(initial)
        private val capabilityTestLock = Mutex()
        private var latestCapabilityTestSequence = 0L
        override val configuration: Flow<ModelConfigurationSnapshot> = state

        override suspend fun save(
            update: ModelConfigurationUpdate,
            apiKey: ModelApiKey,
        ): ModelConfigurationMutationResult = error("Not used")

        override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
            error("Not used")

        override suspend fun readCredential(): ModelCredentialReadResult =
            ModelCredentialReadResult.Available(
                state.value,
                ModelApiKey.from("test-secret".toCharArray()),
            )

        override suspend fun beginCapabilityTest(
            configuration: ModelConfigurationSnapshot,
        ): ModelCapabilityTestStartResult = capabilityTestLock.withLock {
            if (!configuration.isSameGenerationAs(state.value)) {
                return@withLock ModelCapabilityTestStartResult.ConfigurationChanged
            }
            latestCapabilityTestSequence += 1L
            ModelCapabilityTestStartResult.Started(latestCapabilityTestSequence)
        }

        override suspend fun recordCapabilityVerification(
            verification: ModelCapabilityVerification,
        ): ModelCapabilityVerificationWriteResult = capabilityTestLock.withLock {
            if (!verification.matches(state.value) ||
                verification.testStartSequence != latestCapabilityTestSequence
            ) {
                return@withLock ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED
            }
            state.value = state.value.copy(capabilityVerification = verification)
            ModelCapabilityVerificationWriteResult.SAVED
        }

        override suspend fun clear(): ModelConfigurationMutationResult = error("Not used")

        private fun ModelConfigurationSnapshot.isSameGenerationAs(
            other: ModelConfigurationSnapshot,
        ): Boolean = isConfigured && other.isConfigured &&
            provider == other.provider &&
            baseUrl == other.baseUrl &&
            modelId == other.modelId &&
            configurationVersion == other.configurationVersion &&
            updatedAtEpochMillis == other.updatedAtEpochMillis
    }
}
