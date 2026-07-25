package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestStartResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTester
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerificationWriteResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import java.io.IOException
import java.util.Arrays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Creates the explicit, synthetic capability check used by the settings screen. */
object ConfiguredModelCapabilityTesterFactory {
    fun create(configurationStore: ModelConfigurationStore): ModelCapabilityTester =
        OpenAiCompatibleModelCapabilityTester(configurationStore)
}

internal class OpenAiCompatibleModelCapabilityTester(
    private val configurationStore: ModelConfigurationStore,
    private val transport: ModelHttpTransport = OkHttpModelTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val probeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
) : ModelCapabilityTester {
    override suspend fun testSavedConfiguration(): ModelCapabilityTestResult {
        val credential = when (val read = configurationStore.readCredential()) {
            is ModelCredentialReadResult.Available -> read
            ModelCredentialReadResult.Missing -> return ModelCapabilityTestResult.NotConfigured
            ModelCredentialReadResult.Unavailable ->
                return ModelCapabilityTestResult.StorageUnavailable
        }
        return credential.apiKey.use { apiKey ->
            val testStartSequence = when (
                val started = configurationStore.beginCapabilityTest(credential.configuration)
            ) {
                is ModelCapabilityTestStartResult.Started -> started.sequence
                ModelCapabilityTestStartResult.ConfigurationChanged ->
                    return@use ModelCapabilityTestResult.ConfigurationChanged
                ModelCapabilityTestStartResult.StorageUnavailable ->
                    return@use ModelCapabilityTestResult.StorageUnavailable
            }
            val keyChars = apiKey.copyChars()
            try {
                val structured = runProbe(
                    baseUrl = credential.configuration.baseUrl,
                    apiKey = keyChars,
                    requestBody = structuredOutputProbe(credential.configuration.modelId),
                    accepts = ::acceptsStructuredOutput,
                )
                if (structured == ProbeOutcome.AUTHENTICATION_FAILED) {
                    return@use recordAuthenticationFailure(
                        configuration = credential.configuration,
                        testStartSequence = testStartSequence,
                    )
                }
                structured.failureResult()?.let { return@use it }

                val image = runProbe(
                    baseUrl = credential.configuration.baseUrl,
                    apiKey = keyChars,
                    requestBody = imageInputProbe(credential.configuration.modelId),
                    accepts = ::acceptsImageInput,
                )
                if (image == ProbeOutcome.AUTHENTICATION_FAILED) {
                    return@use recordAuthenticationFailure(
                        configuration = credential.configuration,
                        testStartSequence = testStartSequence,
                    )
                }
                image.failureResult()?.let { return@use it }

                val verification = ModelCapabilityVerification(
                    provider = credential.configuration.provider,
                    baseUrl = credential.configuration.baseUrl,
                    modelId = credential.configuration.modelId,
                    configurationVersion = credential.configuration.configurationVersion,
                    configurationUpdatedAtEpochMillis =
                        credential.configuration.updatedAtEpochMillis,
                    supportsImageInput = image == ProbeOutcome.PASSED,
                    supportsStructuredOutput = structured == ProbeOutcome.PASSED,
                    testedAtEpochMillis = clock().coerceAtLeast(1L),
                    testStartSequence = testStartSequence,
                )
                if (!verification.matches(configurationStore.configuration.first())) {
                    return@use ModelCapabilityTestResult.ConfigurationChanged
                }
                when (configurationStore.recordCapabilityVerification(verification)) {
                    ModelCapabilityVerificationWriteResult.SAVED ->
                        ModelCapabilityTestResult.Completed(verification)
                    ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED ->
                        ModelCapabilityTestResult.ConfigurationChanged
                    ModelCapabilityVerificationWriteResult.STORAGE_UNAVAILABLE ->
                        ModelCapabilityTestResult.StorageUnavailable
                }
            } finally {
                Arrays.fill(keyChars, '\u0000')
            }
        }
    }

    private suspend fun recordAuthenticationFailure(
        configuration: ModelConfigurationSnapshot,
        testStartSequence: Long,
    ): ModelCapabilityTestResult {
        val failedVerification = ModelCapabilityVerification(
            provider = configuration.provider,
            baseUrl = configuration.baseUrl,
            modelId = configuration.modelId,
            configurationVersion = configuration.configurationVersion,
            configurationUpdatedAtEpochMillis = configuration.updatedAtEpochMillis,
            supportsImageInput = false,
            supportsStructuredOutput = false,
            testedAtEpochMillis = clock().coerceAtLeast(1L),
            testStartSequence = testStartSequence,
        )
        return when (configurationStore.recordCapabilityVerification(failedVerification)) {
            ModelCapabilityVerificationWriteResult.SAVED ->
                ModelCapabilityTestResult.AuthenticationFailed
            ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED ->
                ModelCapabilityTestResult.ConfigurationChanged
            ModelCapabilityVerificationWriteResult.STORAGE_UNAVAILABLE ->
                ModelCapabilityTestResult.StorageUnavailable
        }
    }

    private suspend fun runProbe(
        baseUrl: String,
        apiKey: CharArray,
        requestBody: String,
        accepts: (String) -> Boolean,
    ): ProbeOutcome = try {
        val response = withTimeoutOrNull(probeTimeoutMillis) {
            transport.post(baseUrl, apiKey, requestBody, beforeEnqueue = {})
        }
        if (response == null) {
            ProbeOutcome.CONNECTION_FAILED
        } else when (response.statusCode) {
            in 200..299 -> if (accepts(response.body)) {
                ProbeOutcome.PASSED
            } else {
                ProbeOutcome.UNSUPPORTED
            }
            400, 415, 422 -> ProbeOutcome.UNSUPPORTED
            401, 403 -> ProbeOutcome.AUTHENTICATION_FAILED
            408, 425, 429, in 500..599 -> ProbeOutcome.PROVIDER_UNAVAILABLE
            else -> ProbeOutcome.PROVIDER_UNAVAILABLE
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: UnsafeModelEndpointException) {
        ProbeOutcome.INVALID_SERVICE_ADDRESS
    } catch (_: IOException) {
        ProbeOutcome.CONNECTION_FAILED
    } catch (_: Exception) {
        ProbeOutcome.PROVIDER_UNAVAILABLE
    }

    private fun ProbeOutcome.failureResult(): ModelCapabilityTestResult? = when (this) {
        ProbeOutcome.PASSED,
        ProbeOutcome.UNSUPPORTED,
        -> null
        ProbeOutcome.AUTHENTICATION_FAILED -> ModelCapabilityTestResult.AuthenticationFailed
        ProbeOutcome.CONNECTION_FAILED -> ModelCapabilityTestResult.ConnectionFailed
        ProbeOutcome.PROVIDER_UNAVAILABLE -> ModelCapabilityTestResult.ProviderUnavailable
        ProbeOutcome.INVALID_SERVICE_ADDRESS -> ModelCapabilityTestResult.InvalidServiceAddress
    }

    private fun acceptsStructuredOutput(body: String): Boolean {
        val content = responseContent(body) ?: return false
        val payload = runCatching { JSON.parseToJsonElement(content).jsonObject }.getOrNull()
            ?: return false
        return payload[STRUCTURED_TOKEN_FIELD]?.jsonPrimitive?.contentOrNull == STRUCTURED_TOKEN
    }

    private fun acceptsImageInput(body: String): Boolean {
        val content = responseContent(body) ?: return false
        return content.uppercase().filter { it.isLetter() } == IMAGE_RESPONSE_TOKEN
    }

    private fun responseContent(body: String): String? = runCatching {
        JSON.parseToJsonElement(body).jsonObject["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

    private fun structuredOutputProbe(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("temperature", 0)
        put("max_tokens", 32)
        put("response_format", buildJsonObject { put("type", "json_object") })
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", "Return only one JSON object and no surrounding text.")
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "Synthetic compatibility check. Return exactly " +
                                "{\"$STRUCTURED_TOKEN_FIELD\":\"$STRUCTURED_TOKEN\"}.",
                        )
                    },
                )
            },
        )
    }.toString()

    private fun imageInputProbe(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("temperature", 0)
        put("max_tokens", 24)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put(
                                            "text",
                                            "This is a synthetic image check, not a real question. " +
                                                "Name the four quadrant colors clockwise from the " +
                                                "top left. Reply with four English color names only.",
                                        )
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            buildJsonObject {
                                                put(
                                                    "url",
                                                    "data:image/png;base64,$SYNTHETIC_IMAGE_BASE64",
                                                )
                                                put("detail", "low")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private enum class ProbeOutcome {
        PASSED,
        UNSUPPORTED,
        AUTHENTICATION_FAILED,
        CONNECTION_FAILED,
        PROVIDER_UNAVAILABLE,
        INVALID_SERVICE_ADDRESS,
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = false }
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 30_000L
        const val STRUCTURED_TOKEN_FIELD = "capability_check"
        const val STRUCTURED_TOKEN = "SMART_MISTAKE_BOOK_STRUCTURED_V1"
        const val IMAGE_RESPONSE_TOKEN = "REDGREENBLUEYELLOW"
        const val SYNTHETIC_IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAALklEQVR42u3NsQkAIAADsP7/" +
                "dL2hg4gQyJ40maSXCQQCgeBN0M08CAQCgeCH4ABESfs9IcagTQAAAABJRU5ErkJggg=="
    }
}
