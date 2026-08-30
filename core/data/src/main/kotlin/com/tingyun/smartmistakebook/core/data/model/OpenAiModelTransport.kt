package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.MODEL_EXTERNAL_TRANSPORT_REQUEST_LIMIT_BYTES
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal data class ModelHttpResponse(
    val statusCode: Int,
    val body: String,
    /**
     * Raw incremental delta bodies when the provider streamed a tutor text task. When present, the
     * gateway emits progressive [ModelGatewayEvent.Progress] events before the terminal [Completed].
     * Null for non-streaming transports and for SSE surfaces the caller did not request.
     */
    val streamChunks: List<String>? = null,
)

internal fun interface ModelHttpTransport {
    suspend fun post(
        baseUrl: String,
        apiKey: CharArray,
        requestBody: String,
        beforeEnqueue: suspend () -> Unit,
    ): ModelHttpResponse
}

internal class UnsafeModelEndpointException : IllegalArgumentException()

internal fun InetAddress.isPubliclyRoutable(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return false
    }
    return when (this) {
        is Inet4Address -> address.toIntOctets().let { octets ->
            val first = octets[0]
            val second = octets[1]
            val third = octets[2]
            when {
                first == 0 || first >= 224 -> false
                first == 100 && second in 64..127 -> false
                first == 192 && second == 0 -> false
                first == 192 && second == 88 && third == 99 -> false
                first == 198 && second in 18..19 -> false
                first == 198 && second == 51 && third == 100 -> false
                first == 203 && second == 0 && third == 113 -> false
                else -> true
            }
        }
        is Inet6Address -> {
            val bytes = address
            val first = bytes[0].toInt() and 0xff
            val isGlobalUnicast = first in 0x20..0x3f
            val isDocumentation = first == 0x20 &&
                (bytes[1].toInt() and 0xff) == 0x01 &&
                (bytes[2].toInt() and 0xff) == 0x0d &&
                (bytes[3].toInt() and 0xff) == 0xb8
            isGlobalUnicast && !isDocumentation
        }
        else -> false
    }
}

internal class OkHttpModelTransport : ModelHttpTransport {
    override suspend fun post(
        baseUrl: String,
        apiKey: CharArray,
        requestBody: String,
        beforeEnqueue: suspend () -> Unit,
    ): ModelHttpResponse {
        require(
            requestBody.toByteArray(StandardCharsets.UTF_8).size.toLong() <=
                MODEL_EXTERNAL_TRANSPORT_REQUEST_LIMIT_BYTES,
        ) {
            "Model request exceeds the upload budget"
        }
        val endpoint = PublicModelEndpoint.resolve(baseUrl)
        val client = OkHttpClient.Builder()
            .dns(FixedDns(endpoint.host, endpoint.addresses))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val stream = requestBody.contains("\"stream\":true")
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Authorization", "Bearer ${String(apiKey)}")
            .header("Accept", if (stream) SSE_ACCEPT else JSON_MEDIA_TYPE.toString())
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        return if (stream) {
            call.awaitBoundedSseResponse(beforeEnqueue)
        } else {
            call.awaitBoundedResponse(beforeEnqueue)
        }
    }
}

private data class PublicModelEndpoint(
    val url: HttpUrl,
    val host: String,
    val addresses: List<InetAddress>,
) {
    companion object {
        suspend fun resolve(baseUrl: String): PublicModelEndpoint = withContext(Dispatchers.IO) {
            val base = baseUrl.toHttpUrlOrNull()
                ?: throw UnsafeModelEndpointException()
            if (base.scheme != "https" || base.username.isNotEmpty() || base.password.isNotEmpty()) {
                throw UnsafeModelEndpointException()
            }
            val addresses = InetAddress.getAllByName(base.host).toList()
            if (addresses.isEmpty() || addresses.any { !it.isPubliclyRoutable() }) {
                throw UnsafeModelEndpointException()
            }
            val endpoint = base.newBuilder()
                .addPathSegment("chat")
                .addPathSegment("completions")
                .build()
            PublicModelEndpoint(endpoint, base.host, addresses.distinctBy { it.hostAddress })
        }
    }
}

private class FixedDns(
    private val approvedHost: String,
    private val approvedAddresses: List<InetAddress>,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(approvedHost, ignoreCase = true)) {
            throw UnknownHostException("Unexpected model endpoint host")
        }
        return approvedAddresses
    }
}

internal suspend fun Call.awaitBoundedResponse(
    beforeEnqueue: suspend () -> Unit,
): ModelHttpResponse {
    beforeEnqueue()
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val declaredLength = it.body.contentLength()
                            if (declaredLength > MAX_RESPONSE_BYTES) {
                                throw InvalidModelResponseException()
                            }
                            val bytes = it.body.byteStream().readAtMost(MAX_RESPONSE_BYTES)
                            val body = bytes.toString(StandardCharsets.UTF_8)
                            Arrays.fill(bytes, 0.toByte())
                            if (continuation.isActive) {
                                continuation.resume(ModelHttpResponse(it.code, body))
                            }
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(failure)
                        }
                    }
                }
            },
        )
    }
}

internal suspend fun Call.awaitBoundedSseResponse(
    beforeEnqueue: suspend () -> Unit,
): ModelHttpResponse {
    beforeEnqueue()
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val declaredLength = it.body.contentLength()
                            if (declaredLength > MAX_RESPONSE_BYTES) {
                                throw InvalidModelResponseException()
                            }
                            val raw = it.body.byteStream().readSseAtMost(MAX_RESPONSE_BYTES)
                            val body = if (it.code in 200..299) {
                                OpenAiSse.reconstructedChatCompletion(raw)
                            } else {
                                raw
                            }
                            val chunks = if (it.code in 200..299) {
                                OpenAiSse.deltaChunks(raw).toList()
                            } else {
                                null
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    ModelHttpResponse(
                                        statusCode = it.code,
                                        body = body,
                                        streamChunks = chunks?.takeIf { it.isNotEmpty() },
                                    ),
                                )
                            }
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(failure)
                        }
                    }
                }
            },
        )
    }
}

private fun java.io.InputStream.readSseAtMost(maxBytes: Int): String {
    val output = StringBuilder()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    var lastScannedEnd = 0
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw InvalidModelResponseException()
            output.append(String(buffer, 0, read, StandardCharsets.UTF_8))
            // Only the tail can contain the terminator once a chunk has been appended; scanning the
            // whole accumulated buffer on every chunk makes the read quadratic for long streams.
            // Keep a one-terminator overlap so a [DONE] split across two chunks is still caught.
            if (containsDoneTerminatorAfter(output, lastScannedEnd)) break
            lastScannedEnd = output.length
        }
        return output.toString()
    } finally {
        Arrays.fill(buffer, 0.toByte())
    }
}

/**
 * True when the accumulated SSE text contains `data: [DONE]` at or after [scannedThrough], allowing
 * an overlap so the terminator is detected even when its bytes straddle two read chunks. The window
 * is just the terminator length, so the scan stays linear in the number of chunks.
 */
internal fun containsDoneTerminatorAfter(accumulated: CharSequence, scannedThrough: Int): Boolean {
    val windowStart = (scannedThrough - SSE_DONE_TERMINATOR.length).coerceAtLeast(0)
    return accumulated.substring(windowStart).contains(SSE_DONE_TERMINATOR)
}

private const val SSE_DONE_TERMINATOR = "data: [DONE]"

private fun java.io.InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw InvalidModelResponseException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    } finally {
        Arrays.fill(buffer, 0.toByte())
    }
}

private fun ByteArray.toIntOctets(): IntArray = IntArray(size) { this[it].toInt() and 0xff }

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val SSE_ACCEPT = "text/event-stream"
private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val READ_TIMEOUT_SECONDS = 90L
private const val WRITE_TIMEOUT_SECONDS = 45L
private const val CALL_TIMEOUT_SECONDS = 110L
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
