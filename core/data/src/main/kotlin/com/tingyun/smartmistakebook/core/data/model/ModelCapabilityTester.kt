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
import kotlinx.serialization.json.JsonPrimitive
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

                // Route A 原生 tools 能力探测：仅当端点已证明结构化输出时才探测
                // function calling（json_object 端点未必 tools 兼容）。探测失败不阻断
                // 主配置验证——只是把 supportsFunctionCalling 置 false（Route A 保持关）。
                val functionCalling = if (structured == ProbeOutcome.PASSED) {
                    runProbe(
                        baseUrl = credential.configuration.baseUrl,
                        apiKey = keyChars,
                        requestBody = toolsProbe(credential.configuration.modelId),
                        accepts = ::acceptsTools,
                    ) == ProbeOutcome.PASSED
                } else {
                    false
                }

                val verification = ModelCapabilityVerification(
                    provider = credential.configuration.provider,
                    baseUrl = credential.configuration.baseUrl,
                    modelId = credential.configuration.modelId,
                    configurationVersion = credential.configuration.configurationVersion,
                    configurationUpdatedAtEpochMillis =
                        credential.configuration.updatedAtEpochMillis,
                    supportsImageInput = image == ProbeOutcome.PASSED,
                    supportsStructuredOutput = structured == ProbeOutcome.PASSED,
                    supportsFunctionCalling = functionCalling,
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
        val probeLabel = if (requestBody.contains("capability_check")) "structured" else
            if (requestBody.contains("Synthetic") && requestBody.contains("image_url")) "image" else "tools"
        if (response == null) {
            android.util.Log.w("ModelCapabilityTester", "$probeLabel probe: TIMEOUT")
            ProbeOutcome.CONNECTION_FAILED
        } else {
            android.util.Log.w(
                "ModelCapabilityTester",
                "$probeLabel probe status=${response.statusCode} body=${response.body.take(160)}",
            )
            when (response.statusCode) {
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
        return content.uppercase().filter { it.isLetterOrDigit() } == IMAGE_RESPONSE_TOKEN
    }

    private fun acceptsTools(body: String): Boolean {
        // tools 能力 = 端点接受 tools 请求且模型实际发起 tool_call（而非忽略 tools、
        // 照常回普通文本）。二者都满足才证明原生工具往返可用。
        return runCatching {
            val envelope = JSON.parseToJsonElement(body).jsonObject
            val message = envelope["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject ?: return false
            val toolCalls = message["tool_calls"]?.jsonArray
            if (toolCalls != null && toolCalls.isNotEmpty()) return true
            val content = message["content"]?.jsonPrimitive?.contentOrNull
            content?.uppercase()?.filter { it.isLetterOrDigit() } == TOOLS_TOKEN
        }.getOrDefault(false)
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
        // Reasoning models spend tokens on chain-of-thought before the final JSON;
        // a 32-token cap gets consumed by reasoning and the probe sees an empty
        // message. Raise the cap so the token check is reachable.
        put("max_tokens", 512)
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

    private fun toolsProbe(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("temperature", 0)
        // Reasoning models burn tokens on chain-of-thought before issuing a
        // tool call; 32 tokens gets truncated before the call is emitted.
        put("max_tokens", 512)
        put(
            "tools",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", "synthetic_compat_check")
                                put("description", "Synthetic compatibility check")
                                put(
                                    "parameters",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put(
                                                    "token",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                        put(
                                                            "description",
                                                            "Echo the code you were asked to return",
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                        put("required", buildJsonArray { add(JsonPrimitive("token")) })
                                        put("additionalProperties", JsonPrimitive(false))
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "Synthetic tools check. Call synthetic_compat_check with " +
                                "token = \"$TOOLS_TOKEN\".",
                        )
                    },
                )
            },
        )
    }.toString()

    private fun imageInputProbe(modelId: String): String = buildJsonObject {
        put("model", modelId)
        put("temperature", 0)
        // Same reasoning-budget fix as the structured probe: leave room for
        // chain-of-thought so the visual code reply is not truncated to empty.
        put("max_tokens", 512)
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
                                                "Read the four-character code printed inside the " +
                                                "border. Reply with that code only.",
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
        const val TOOLS_TOKEN = "SMART_MISTAKE_BOOK_TOOLS_V1"
        const val IMAGE_RESPONSE_TOKEN = "Q7M2"
        // A small legal PNG rendered with the code "Q7M2". Earlier constant
        // concatenated a corrupted payload that providers reject as an
        // unsupported image (400), which failed the image probe and made every
        // capable model look "incompatible". This one is verified valid and the
        // model reads the four-character code from it.
        const val SYNTHETIC_IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAKAAAABACAIAAAAS6ev4AAADIUlEQVR4nO3dP0vrUBjH8dPqUhAjxTr4BiwWpFoFNTUEghQRl4J/BgWnvglxFXwJUqouOohbOxhxcVBxySBKHIqLIlgsUSi6aJ47HAiS63S5pseH32fpOUkKR76cNkOKMSISwFe83QuAn9UpX2KxWHvXAf+d/GzGDmYOgZnrDM1xz/Xbhb5tsYOZQ2DmEJg5BGYOgZlDYOYQmDkEZg6BmUNg5hCYOQRmDoGZQ2DmEJg5BGZOocA7Ozu5XG5iYmJ0dHRvb08IsbS0ZJqmaZqTk5O9vb3yskQisbCwELxreXk5kUjI8fb29tTUVDabPT4+jn79iiKir09xUJscHR3puu55HhF5nqfr+unpaXC2XC6vr6/LsaZpQ0NDHx8fROT7/vj4uKZpRNRoNAzD+Pz8dF03nU5H/ycoIlz226PRsyzr/Pw8mJ6dnc3MzMix7/vZbPbp6UlONU1bXV29uLggIsdxSqWSDOy67sHBARG1Wq1UKhXx+tURSqnKR7TrusPDw8F0ZGTk5uZGjqvV6tjYWF9fX3C2UCjYti2EsG27UCjIg+l0en5+XghxeHg4NzcX3dIV92326PX397+/vwfTt7e3ZDIpx4Zh3N7eBqc0TWs2m/l8noimp6dfX1/lDpbq9Xomk2k0GhGtWz2hlKrs4Ewm4zhOMHUcZ3BwUAhxeXnZ09MzMDDw9eJkMhmPx+/v74UQ3d3dwfFWq7W4uFipVFKpVFQLV9632aN3cnKi6/rLywsReZ6Xz+drtRoRFYvFr3dbRCT368bGxsrKyubmZnDE9/1isbi/vx/52tUSShl+LrpdLMt6eHiwLKujo8N1XSHE3d1dvV5/fHw0DOPv62dnZ9fW1q6uroIju7u7tm03m82tra2urq5arRbd6hUWk82Dp6VJjQffn5+fr6+vTdNs90J+n1BKRQPDPwulVOUmC34IAjOHwMwhMHMIzBwCM4fAzCEwcwjMHAIzh8DMITBzCMwcAjOHwMwhMHMIzBwCM4fAzCEwcwjMHAIzh8DMITBzCMwcAjOHwMyFf12I/5DFDHYwcwjMXAy/F+UNO5i5P+gYTZ49ANt4AAAAAElFTkSuQmCC"
    }
}
