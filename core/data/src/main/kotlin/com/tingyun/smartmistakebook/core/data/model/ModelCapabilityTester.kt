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
        return content.uppercase().filter { it.isLetterOrDigit() } == IMAGE_RESPONSE_TOKEN
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
        const val IMAGE_RESPONSE_TOKEN = "Q7M2"
        const val SYNTHETIC_IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAUAAAACACAYAAAB6D7CqAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAtUSURB" +
                "VHhe7Z3dcdRKEEadDFnw4BSIgRR454kMyIAIiMAJkAAJkAAB+Fb7lm7p9o6+7p4Zre3ROVXfC6vVrn7mqKclLw/PAAAX5cH/AwDAVfhPgE9PT89fv34lhJCl" +
                "Y667EaC98PDwQAghS8dchwAJIZcMAiSEXDYpAT4+Pt7MnQkh5L3FXFYW4H4hAID3inIbAgSApVFuQ4AAsDTKbQgQAJZGuQ0BAsDSKLchQABYGuU2BAgAS6Pc" +
                "hgABYGmU2xAgACyNchsCBIClUW5DgACwNMptCBAAlka5DQECwNIotyFAAFga5TYECABLo9yGAAFgaZTbECAALI1yGwIEgKVRbkOAALA0ym0IEACWRrkNAQLA" +
                "0ii3IUAAWBrlNgQIAEuj3IYAAWBplNsQIAAsjXIbAgSApVFuQ4AAsDTKbQgQAJZGuQ0BAsDSKLchQABYGuU2BHhnfv369fzjx4/n79+/P3/+/Pn506dP/4v9" +
                "m71my9iyADCGchsCPJm/f/8+//z580Vs+/1biYnRhGjrWhnbT/6CkIm9b4Q/f/7crDObUeyYPj09vVz0vnz5crP+b9++vbzGxbAf5TYEeBI2qOyE9jIbja3T" +
                "1j3C79+/bwbaPWKDWWED3W9vJnZxGcEE6teZTS8mvZ6Loh1/O36QR7kNAZ5A70CuxD6jF6sm/PruEZOgYmS/jTByoaqyXXz8eqoxea4+I5iFchsCnIidkB8/" +
                "frw5Wc+KfVZPNbCiAEemiB8+fLhZXzYVRrbvKD3H/2ootyHASdiJ6E/Oe6U6CFYUYG9FbO0Ev65KsoxUmVGqx/9qKLchwAm8pvy2VAbBigKM1n2E3Vzy66ok" +
                "w0iPMRumw8cotyHAQUYriJnJSnBFAVp6JNBzI2KfiHudH9G+vTLKbQhwkN6e3/a8n8lon+35QL98JvZdMhJYVYB2Z7WKX0c1EWdOfX1G+qAro9yGAAfoGbA2" +
                "HcpIant+0L8/SqYX9loCjJ7X69mf+0SP2XhmtC4Udgz98ir2/U3i+4th5QJrsoVblNsQYCfVqU3vYwv2nmpFGD0n+BoCjORnjArQZFFhtP9nUWTXb+JS50Z2" +
                "PRa4RbkNAXZSmdrMuDLP/DwbbH7qPZKoSslWZhkBRs/QRfLfo9aVfTRGoda/JWoLbGT2jSXbB74Sym0IsIPK1CaSUYWKBCsiGCGqTip/pZEZ5NEymUpzw793" +
                "n+y+VvhlW7ELSBb/3lYq67sKym0IsINo0G+xKkJNbarYurKVSaYXOErUQ6tufyS3bbtUxZm94Jgo/Hv3yR7jI6J9Y7H9UyHTCkGAtyi3IcAO1ADcp+euZET2" +
                "xki1H1YlI+PqYMwK0KbU/t+3ZKUSfVYkyC1H2LH3y/pUqmMj+s6W6j6/AsptCLBI9uZHdiD2EIlny5nT4Kgayfb99mQGuC0TySXTB1MXMdu2UQHa++27WrYf" +
                "g/DHrVqlZ/YPArxFuQ0BFslWYDaFOovMQLBU+mEVIgFVp74bme2yZaIebLTvM+8fFWCESbp6gcr0JavrvALKbQiwiJp+3etEzPSXLD1VWMQZU9+NrACNqIJT" +
                "RAK3/Xu2AHuI9vu9v897QbkNARbJPNpwjxPRf14r2UcsKkQXgEg+iooAo2pIEW2D8dYEmLnoZW8AXQ3lNgRYxJ90rZwhHs9riDjT/xypfCsCjFoRqgpV1eN2" +
                "7N6aAKOeq0Vt85VRbkOARfxJ18oZU09PVMVsmUkk3WpT31MRYCTjo+9SeZ9/rZV7EE3ZLfe46L5XlNsQYBF/4rVyNPhmkpGFpedmRItMRTT6WZlt2u9b1RM7" +
                "EkKlcvSvtXI2mamvhervGOU2BFggIwHLWxLgrIFxdvVnZLZp/zlRH7Al5Og9e/xrrZxJVK1uGem7XgHlNgRY4KoCzGx3SzZVMtu037fRX2u0HkSvVI3+9VbO" +
                "wvan6lXuM2Pfr4xyGwIskBGBZTUB3qP6MzLbtP+saHroe7FRReW3w7/eyhnY98zKryV5+D/KbQiwwBUFGEnDMqsCyWyT37f+9X38nwNG/T8vE/96K7OJpL6P" +
                "Fzy0UW5DgAUyMrD4QXoGGVlYRh5LMaKe2cxnzzLb5Pdt9HjIXs6VZQ3/eiszqchv5n5fHeU2BFjEn4it+EF6BhlZWEaI/mTMMlph7slsk9+3UR9w/+eA/rV9" +
                "fLUYLb9lFsjvPJTbEGARfzK2co+7clE1s2WEaMo4+wcfegQYtSU2WUSCaU0n/TKtzCDznN+W1vcEjXIbAiySaU63qonZZL6Hv6tZJfoML6NRMgL0fTrDL7PP" +
                "JumoUqyud8so0UVmn7N+3GJ1lNsQYJFs5eX7STPJTE0tI1OlTL9ztL/oyQiwNeWO7lLb94yOW2tb/DKtjFCRX0vQkEO5DQEWyQxSy5lX6+yUKfpZKEVUMc2e" +
                "/hqZfdsSYPS+3m3xy7XSS0V+md83hGOU2xBgkajntKXaB7Tejg2KTOUY3ZndMjJwounvGb2oSGSWlgCzx+QoR5WyX66VHrLys2OQOR9Ao9yGADvwJ+pRWtOq" +
                "Fn4A24A8em92+mvpJTP9bYlolF4BGn65So6qdb9cK1Wy8rNpPfKbg3IbAuwgW4EdVRaeox5WS4QZSVQ+u0VmkJ5BZtuOBBhVrCp+H2/45VqpEN2J3jJy7OAW" +
                "5TYE2EG2B2eJmte++mvFxGDVQHYAWUamv5HgR+8uHzEiwOzPg/kc9f8Mv2wrWbLHDvnNR7kNAXai/qjeR8noqPrzsc/LVjmjgoq2bfbjLxsjAqxclPZRvVq/" +
                "bCsZsj9soL4L9KPchgA7yUwT9zmSoN2hzAyOSo4kkeG1+n/GiAArvdF91J1yv2wrGaKK2tL7H0lBjHIbAhygKi5VOZkgMwMlyugUKlNJnTVQRwRoRJVrK0cX" +
                "JiNzfCMy+9Ni390q99Ec3dC5MsptCHCAbF9nHzvRbaAfDTz7956BvGVUThkJnUXms5UAey4gChOKX94nYuRY9kRdZK+KchsCHCR6yDbK/uo9OliUHLJEg95e" +
                "P4tRAVbbElHPLdoXFkX1+8wIArxFuQ0BTqCn8pidWQ/NRtO+Mx6A3hgVYKZ/uU8ki1EBjl7QehJt0xVRbkOAk3gLErSM9oD8+nzOHGCjAjQq0onWNSLAzONN" +
                "Z+TM4/NeUW5DgBPpfRZtdmzgRoP7CL8un+i5xhFmCDD60YN9IkYE+FoXRAR4i3IbApxM9q7fPWIDuCKsTNUSCWiEGQLM9mQzvcwRAVYq0ZlBgLcotyHAE7Be" +
                "3FupBi2ZwW6sIMDsnfmMKHoFWO1Fzkxmu66GchsCPBEbCHZCzqoGbFqVrXD2yfYFVxCg4d/TSmY9vQLM7MezggBvUW5DgHfCKhM7Oa1HlRWiDUCrJP001sQa" +
                "3a3dov7W1WOVqw1elRl3mo+w7fKf55P5fNvX/n0+GXrXk9mPZ+Xohx2ujHIbAnxFjgZ8lkzFlK3+AFZFuQ0BvnOsSjmqBivVH8CqKLchwEVoVYNUfwDabQhw" +
                "Iaz3tN10ofoD+BflNgS4KJmbBQBXQLkNAQLA0ii3IUAAWBrlNgQIAEuj3IYAAWBplNsQIAAsjXIbAgSApVFuQ4AAsDTKbQgQAJZGuQ0BAsDSKLchQABYGuU2" +
                "BAgAS6PchgABYGmU2xAgACyNchsCBIClUW5DgACwNMptCBAAlka5DQECwNIotyFAAFga5TYECABLo9yGAAFgaZTbECAALI1yGwIEgKVRbkOAALA0ym0IEACW" +
                "RrkNAQLA0ii3IUAAWBrlNgQIAEuj3IYAAWBplNsQIAAsjXIbAgSApVFuQ4AAsDTKbYcCfHx8fPk3Qgh5zzGXlQVICCErBgESQi4bBEgIuWyaAnx6erqZOxNC" +
                "yGox190IEADgaiBAALgs/wAJrDxrlPIboQAAAABJRU5ErkJggg=="
    }
}
