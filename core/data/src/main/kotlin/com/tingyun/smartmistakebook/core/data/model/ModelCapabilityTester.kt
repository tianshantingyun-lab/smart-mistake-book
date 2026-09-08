package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestStartResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTester
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerificationWriteResult
import com.tingyun.smartmistakebook.core.data.model.wire.ModelProbeKind
import com.tingyun.smartmistakebook.core.data.model.wire.ModelProbeSpec
import com.tingyun.smartmistakebook.core.data.model.wire.ModelWireProtocol
import com.tingyun.smartmistakebook.core.data.model.wire.protocolFor
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import com.tingyun.smartmistakebook.core.model.ModelProviderProtocol
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
                // 协议解析是穷尽 when：全部协议都有实现，不存在"未实现协议"的运行期分支。
                val protocol = protocolFor(credential.configuration.protocol)

                val structured = runProbe(
                    baseUrl = credential.configuration.baseUrl,
                    modelId = credential.configuration.modelId,
                    protocol = protocol,
                    apiKey = keyChars,
                    probe = ModelProbeKind.STRUCTURED,
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
                    modelId = credential.configuration.modelId,
                    protocol = protocol,
                    apiKey = keyChars,
                    probe = ModelProbeKind.IMAGE,
                    accepts = ::acceptsImageInput,
                )
                if (image == ProbeOutcome.AUTHENTICATION_FAILED) {
                    return@use recordAuthenticationFailure(
                        configuration = credential.configuration,
                        testStartSequence = testStartSequence,
                    )
                }
                image.failureResult()?.let { return@use it }

                // Route A 原生 tools 能力探测：只对声明支持原生工具的协议探测，且仅在
                // 端点已证明结构化输出之后（json_object 端点未必 tools 兼容）。探测失败
                // 不阻断主配置验证——只是把 supportsFunctionCalling 置 false（Route A 保持关）。
                val functionCalling = if (
                    protocol.supportsNativeTools && structured == ProbeOutcome.PASSED
                ) {
                    runProbe(
                        baseUrl = credential.configuration.baseUrl,
                        modelId = credential.configuration.modelId,
                        protocol = protocol,
                        apiKey = keyChars,
                        probe = ModelProbeKind.TOOLS,
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
        modelId: String,
        protocol: ModelWireProtocol,
        apiKey: CharArray,
        probe: ModelProbeKind,
        accepts: (ModelWireProtocol, String) -> Boolean,
    ): ProbeOutcome = try {
        val requestBody = protocol.probeRequestBody(modelId, probe)
        val response = withTimeoutOrNull(probeTimeoutMillis) {
            transport.post(
                WireRequest(
                    url = protocol.endpoint(
                        baseUrl = baseUrl.toHttpUrlOrNull() ?: throw UnsafeModelEndpointException(),
                        modelId = modelId,
                        stream = false,
                    ),
                    headers = protocol.headers(apiKey, stream = false),
                    body = requestBody,
                    stream = false,
                    protocol = protocol,
                ),
                beforeEnqueue = {},
            )
        }
        val probeLabel = probe.name.lowercase()
        if (response == null) {
            android.util.Log.w("ModelCapabilityTester", "$probeLabel probe: TIMEOUT")
            ProbeOutcome.CONNECTION_FAILED
        } else {
            android.util.Log.w(
                "ModelCapabilityTester",
                "$probeLabel probe status=${response.statusCode} body=${response.body.take(160)}",
            )
            when (response.statusCode) {
                in 200..299 -> if (accepts(protocol, response.body)) {
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

    /** 结构化探测通过 = 回复正文能解析成带令牌的 JSON 对象（信封差异由协议给出）。 */
    private fun acceptsStructuredOutput(protocol: ModelWireProtocol, body: String): Boolean {
        val content = protocol.probeResponseText(body) ?: return false
        val payload = runCatching { JSON.parseToJsonElement(content).jsonObject }.getOrNull()
            ?: return false
        return payload[ModelProbeSpec.STRUCTURED_TOKEN_FIELD]?.jsonPrimitive?.contentOrNull ==
            ModelProbeSpec.STRUCTURED_TOKEN
    }

    /** 图片探测通过 = 回复正文就是合成图里的四字符码。 */
    private fun acceptsImageInput(protocol: ModelWireProtocol, body: String): Boolean {
        val content = protocol.probeResponseText(body) ?: return false
        return content.uppercase().filter { it.isLetterOrDigit() } ==
            ModelProbeSpec.IMAGE_RESPONSE_TOKEN
    }

    /** 原生工具探测通过 = 端点接受 tools 请求且模型实际发起 tool_call（或回显令牌）。 */
    private fun acceptsTools(protocol: ModelWireProtocol, body: String): Boolean {
        // 只对声明原生工具的协议探测，因此这里按 OpenAI 信封断言。
        return runCatching {
            val envelope = JSON.parseToJsonElement(body).jsonObject
            val message = envelope["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject ?: return false
            val toolCalls = message["tool_calls"]?.jsonArray
            if (toolCalls != null && toolCalls.isNotEmpty()) return true
            val content = message["content"]?.jsonPrimitive?.contentOrNull
            content?.uppercase()?.filter { it.isLetterOrDigit() } == ModelProbeSpec.TOOLS_TOKEN
        }.getOrDefault(false)
    }

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
    }
}
