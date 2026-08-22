package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.model.EventTimeTrust
import com.tingyun.smartmistakebook.core.model.ProviderProbeSnapshot
import com.tingyun.smartmistakebook.core.model.StudentModelPrediction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tutor Provider Matrix that manages multiple AI providers with:
 * - Timeout handling
 * - Rate limiting
 * - Stream interruption recovery
 * - Cancellation propagation
 * - Capability snapshot management
 * - Automatic fallback between providers
 */
class TutorProviderMatrix(
    private val providers: List<TutorProvider>,
    private val scope: CoroutineScope,
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val capabilitySnapshots = ConcurrentHashMap<String, ProviderProbeSnapshot>()

    /**
     * Send a text request to the best available provider.
     * Falls back to other providers on failure.
     */
    suspend fun sendTextRequest(
        requestId: String,
        prompt: String,
        modelId: String? = null,
        timeoutMs: Long = 30_000,
    ): TutorProviderResponse {
        val selectedProviders = selectProviders(
            requireVision = false,
            requireStreaming = true,
            preferredModelId = modelId,
        )

        var lastError: Exception? = null
        for (provider in selectedProviders) {
            try {
                val rateLimiter = rateLimiters.getOrPut(provider.id) {
                    RateLimiter(
                        maxRequestsPerMinute = provider.rateLimitPerMinute,
                        maxTokensPerMinute = provider.tokenLimitPerMinute,
                    )
                }
                rateLimiter.acquire()

                val response = withTimeout(timeoutMs) {
                    provider.sendTextRequest(requestId, prompt)
                }

                // Update capability snapshot on success
                updateCapabilitySnapshot(provider, success = true)

                return response
            } catch (e: TimeoutCancellationException) {
                lastError = e
                updateCapabilitySnapshot(provider, success = false, error = "timeout")
            } catch (e: IOException) {
                lastError = e
                updateCapabilitySnapshot(provider, success = false, error = "io_error")
            } catch (e: Exception) {
                lastError = e
                updateCapabilitySnapshot(provider, success = false, error = e.message)
            }
        }

        throw TutorProviderException(
            "所有 Provider 都失败",
            lastError,
        )
    }

    /**
     * Send a vision request with image attachments.
     */
    suspend fun sendVisionRequest(
        requestId: String,
        prompt: String,
        images: List<ByteArray>,
        modelId: String? = null,
        timeoutMs: Long = 60_000,
    ): TutorProviderResponse {
        val selectedProviders = selectProviders(
            requireVision = true,
            requireStreaming = true,
            preferredModelId = modelId,
        )

        var lastError: Exception? = null
        for (provider in selectedProviders) {
            try {
                val rateLimiter = rateLimiters.getOrPut(provider.id) {
                    RateLimiter(
                        maxRequestsPerMinute = provider.rateLimitPerMinute,
                        maxTokensPerMinute = provider.tokenLimitPerMinute,
                    )
                }
                rateLimiter.acquire()

                val response = withTimeout(timeoutMs) {
                    provider.sendVisionRequest(requestId, prompt, images)
                }

                updateCapabilitySnapshot(provider, success = true)
                return response
            } catch (e: Exception) {
                lastError = e
                updateCapabilitySnapshot(provider, success = false, error = e.message)
            }
        }

        throw TutorProviderException(
            "所有视觉 Provider 都失败",
            lastError,
        )
    }

    /**
     * Send a streaming text request.
     */
    fun sendStreamingRequest(
        requestId: String,
        prompt: String,
        modelId: String? = null,
    ): Flow<TutorStreamChunk> = flow {
        val selectedProviders = selectProviders(
            requireVision = false,
            requireStreaming = true,
            preferredModelId = modelId,
        )

        var lastError: Exception? = null
        for (provider in selectedProviders) {
            try {
                val rateLimiter = rateLimiters.getOrPut(provider.id) {
                    RateLimiter(
                        maxRequestsPerMinute = provider.rateLimitPerMinute,
                        maxTokensPerMinute = provider.tokenLimitPerMinute,
                    )
                }
                rateLimiter.acquire()

                provider.sendStreamingRequest(requestId, prompt).collect { chunk ->
                    emit(chunk)
                }

                updateCapabilitySnapshot(provider, success = true)
                return@flow
            } catch (e: Exception) {
                lastError = e
                updateCapabilitySnapshot(provider, success = false, error = e.message)
            }
        }

        throw TutorProviderException(
            "所有流式 Provider 都失败",
            lastError,
        )
    }

    /**
     * Cancel an active request.
     */
    fun cancelRequest(requestId: String) {
        activeJobs.remove(requestId)?.cancel()
    }

    /**
     * Get the capability snapshot for a provider.
     */
    fun getCapabilitySnapshot(providerId: String): ProviderProbeSnapshot? {
        return capabilitySnapshots[providerId]
    }

    /**
     * Get all provider capability snapshots.
     */
    fun getAllCapabilitySnapshots(): Map<String, ProviderProbeSnapshot> {
        return capabilitySnapshots.toMap()
    }

    private fun selectProviders(
        requireVision: Boolean,
        requireStreaming: Boolean,
        preferredModelId: String?,
    ): List<TutorProvider> {
        return providers.filter { provider ->
            val capability = capabilitySnapshots[provider.id]
            if (requireVision && capability?.supportsVision != true) return@filter false
            if (requireStreaming && capability?.supportsSseStreaming != true) return@filter false
            if (preferredModelId != null && provider.modelId != preferredModelId) return@filter false
            true
        }.sortedByDescending { provider ->
            // Prefer providers with successful track records
            val snapshot = capabilitySnapshots[provider.id]
            if (snapshot?.supportsSseStreaming == true) 2 else 1
        }
    }

    private fun updateCapabilitySnapshot(
        provider: TutorProvider,
        success: Boolean,
        error: String? = null,
    ) {
        val current = capabilitySnapshots[provider.id]
        val newSnapshot = if (current != null) {
            current.copy(
                supportsSseStreaming = success || current.supportsSseStreaming,
                testedAtEpochMillis = System.currentTimeMillis(),
                errorMessage = if (success) null else error,
            )
        } else {
            ProviderProbeSnapshot(
                providerBaseUrl = provider.baseUrl,
                modelId = provider.modelId,
                configVersion = provider.configVersion,
                supportsText = true,
                supportsVision = provider.supportsVision,
                supportsStructuredOutput = true,
                supportsSseStreaming = success,
                testedAtEpochMillis = System.currentTimeMillis(),
                protocolFingerprint = provider.protocolFingerprint,
                errorMessage = if (success) null else error,
            )
        }
        capabilitySnapshots[provider.id] = newSnapshot
    }
}

/**
 * Interface for a tutor provider.
 */
interface TutorProvider {
    val id: String
    val baseUrl: String
    val modelId: String
    val configVersion: String
    val supportsVision: Boolean
    val rateLimitPerMinute: Int
    val tokenLimitPerMinute: Int
    val protocolFingerprint: String

    suspend fun sendTextRequest(
        requestId: String,
        prompt: String,
    ): TutorProviderResponse

    suspend fun sendVisionRequest(
        requestId: String,
        prompt: String,
        images: List<ByteArray>,
    ): TutorProviderResponse

    fun sendStreamingRequest(
        requestId: String,
        prompt: String,
    ): Flow<TutorStreamChunk>
}

/**
 * Response from a tutor provider.
 */
@Serializable
data class TutorProviderResponse(
    val requestId: String,
    val providerId: String,
    val content: String,
    val modelId: String,
    val tokensUsed: Int,
    val latencyMs: Long,
    val timestampEpochMillis: Long,
)

/**
 * A chunk from a streaming response.
 */
@Serializable
data class TutorStreamChunk(
    val requestId: String,
    val providerId: String,
    val content: String,
    val isComplete: Boolean,
    val tokensUsed: Int? = null,
)

/**
 * Rate limiter for a provider.
 */
class RateLimiter(
    private val maxRequestsPerMinute: Int,
    private val maxTokensPerMinute: Int,
) {
    private val requestCount = AtomicInteger(0)
    private val tokenCount = AtomicInteger(0)
    private var windowStart = System.currentTimeMillis()

    suspend fun acquire() {
        val now = System.currentTimeMillis()
        if (now - windowStart > 60_000) {
            requestCount.set(0)
            tokenCount.set(0)
            windowStart = now
        }

        if (requestCount.get() >= maxRequestsPerMinute) {
            val waitTime = 60_000 - (now - windowStart)
            if (waitTime > 0) {
                delay(waitTime)
                requestCount.set(0)
                tokenCount.set(0)
                windowStart = System.currentTimeMillis()
            }
        }

        requestCount.incrementAndGet()
    }

    fun recordTokens(count: Int) {
        tokenCount.addAndGet(count)
    }
}

/**
 * Exception for tutor provider errors.
 */
class TutorProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Concrete OpenAI-compatible provider implementation.
 * Supports both streaming (SSE) and non-streaming JSON responses.
 */
class OpenAiCompatibleProvider(
    override val id: String,
    override val baseUrl: String,
    override val modelId: String,
    override val configVersion: String = "1.0",
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    override val supportsVision: Boolean = false,
) : TutorProvider {
    override val rateLimitPerMinute: Int = 60
    override val tokenLimitPerMinute: Int = 100_000
    override val protocolFingerprint: String = "openai-compatible-v1"

    override suspend fun sendTextRequest(
        requestId: String,
        prompt: String,
    ): TutorProviderResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val requestBody = org.json.JSONObject().apply {
            put("model", modelId)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw TutorProviderException("空响应")

        if (!response.isSuccessful) {
            throw TutorProviderException("API 请求失败: ${response.code} - $responseBody")
        }

        val jsonResponse = org.json.JSONObject(responseBody)
        val content = jsonResponse.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            ?: throw TutorProviderException("无法解析响应内容")

        val usage = jsonResponse.optJSONObject("usage")
        val tokensUsed = usage?.optInt("total_tokens") ?: 0

        TutorProviderResponse(
            requestId = requestId,
            providerId = id,
            content = content,
            modelId = modelId,
            tokensUsed = tokensUsed,
            latencyMs = System.currentTimeMillis() - startTime,
            timestampEpochMillis = System.currentTimeMillis(),
        )
    }

    override suspend fun sendVisionRequest(
        requestId: String,
        prompt: String,
        images: List<ByteArray>,
    ): TutorProviderResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val requestBody = org.json.JSONObject().apply {
            put("model", modelId)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        images.forEach { image ->
                            put(org.json.JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", org.json.JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,${android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP)}")
                                })
                            })
                        }
                    })
                })
            })
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw TutorProviderException("空响应")

        if (!response.isSuccessful) {
            throw TutorProviderException("API 请求失败: ${response.code} - $responseBody")
        }

        val jsonResponse = org.json.JSONObject(responseBody)
        val content = jsonResponse.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            ?: throw TutorProviderException("无法解析响应内容")

        val usage = jsonResponse.optJSONObject("usage")
        val tokensUsed = usage?.optInt("total_tokens") ?: 0

        TutorProviderResponse(
            requestId = requestId,
            providerId = id,
            content = content,
            modelId = modelId,
            tokensUsed = tokensUsed,
            latencyMs = System.currentTimeMillis() - startTime,
            timestampEpochMillis = System.currentTimeMillis(),
        )
    }

    override fun sendStreamingRequest(
        requestId: String,
        prompt: String,
    ): Flow<TutorStreamChunk> = flow {
        val requestBody = org.json.JSONObject().apply {
            put("model", modelId)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("stream", true)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw TutorProviderException("流式请求失败: ${response.code}")
        }

        val responseBody = response.body ?: throw TutorProviderException("空响应体")
        val source = responseBody.source()
        var contentBuilder = StringBuilder()
        var tokensUsed = 0

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") {
                    emit(TutorStreamChunk(
                        requestId = requestId,
                        providerId = id,
                        content = contentBuilder.toString(),
                        isComplete = true,
                        tokensUsed = tokensUsed,
                    ))
                    break
                }

                try {
                    val jsonChunk = org.json.JSONObject(data)
                    val delta = jsonChunk.getJSONArray("choices")
                        .getJSONObject(0)
                        .optJSONObject("delta")
                        ?.optString("content")

                    if (delta != null) {
                        contentBuilder.append(delta)
                        emit(TutorStreamChunk(
                            requestId = requestId,
                            providerId = id,
                            content = delta,
                            isComplete = false,
                        ))
                    }
                } catch (_: Exception) {
                    // Skip malformed chunks
                }
            }
        }
    }
}
