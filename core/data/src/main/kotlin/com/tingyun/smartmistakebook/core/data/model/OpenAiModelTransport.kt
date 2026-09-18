package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.data.model.wire.ModelWireProtocol
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

/** 一次按协议族构造的线上请求：URL/头/体由协议实现给出，传输层只负责发。 */
internal class WireRequest(
    val url: HttpUrl,
    val headers: List<Pair<String, String>>,
    val body: String,
    val stream: Boolean,
    val protocol: ModelWireProtocol,
)

internal fun interface ModelHttpTransport {
    suspend fun post(request: WireRequest, beforeEnqueue: suspend () -> Unit): ModelHttpResponse
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
        request: WireRequest,
        beforeEnqueue: suspend () -> Unit,
    ): ModelHttpResponse {
        require(
            request.body.toByteArray(StandardCharsets.UTF_8).size.toLong() <=
                MODEL_EXTERNAL_TRANSPORT_REQUEST_LIMIT_BYTES,
        ) {
            "Model request exceeds the upload budget"
        }
        val endpoint = PublicModelEndpoint.resolve(request.url)
        val client = guardedModelClient(endpoint)
        val httpRequest = Request.Builder()
            .url(endpoint.url)
            .apply {
                request.headers.forEach { (name, value) -> header(name, value) }
            }
            .post(request.body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(httpRequest)
        return if (request.stream) {
            call.awaitBoundedSseResponse(request.protocol, beforeEnqueue)
        } else {
            call.awaitBoundedResponse(beforeEnqueue)
        }
    }
}

/**
 * A clean-redraw endpoint resolved through the same SSRF guard as the chat
 * gateway: the host is validated as HTTPS and publicly routable, and the client
 * pins DNS to the approved addresses with no proxy, no redirects. The image
 * channel composes `$baseUrl/images/edits` itself.
 */
internal data class GuardedEditsEndpoint(
    val baseUrl: String,
    val client: OkHttpClient,
)

/** Resolves the configured chat-service base to a guarded edits-capable client. */
internal suspend fun resolveGuardedEdits(baseUrl: String): GuardedEditsEndpoint =
    withContext(Dispatchers.IO) {
        val validated = resolvePublicService(baseUrl)
        GuardedEditsEndpoint(
            baseUrl = validated.base.toString().trimEnd('/'),
            client = guardedModelClient(validated.host, validated.addresses),
        )
    }

internal fun guardedModelClient(
    host: String,
    addresses: List<InetAddress>,
): OkHttpClient = OkHttpClient.Builder()
    .dns(FixedDns(host, addresses))
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

private fun guardedModelClient(endpoint: PublicModelEndpoint): OkHttpClient =
    guardedModelClient(endpoint.host, endpoint.addresses)

private data class PublicModelEndpoint(
    val url: HttpUrl,
    val host: String,
    val addresses: List<InetAddress>,
) {
    companion object {
        suspend fun resolve(url: HttpUrl): PublicModelEndpoint = withContext(Dispatchers.IO) {
            val validated = resolvePublicService(url.toString())
            PublicModelEndpoint(validated.base, validated.host, validated.addresses)
        }
    }
}

/**
 * Validates the configured service address against the SSRF policy (HTTPS, no
 * embedded credentials), resolves its host to addresses that are all publicly
 * routable, and returns the validated URL with its pinned address list.
 */
private data class ValidatedPublicService(
    val base: HttpUrl,
    val host: String,
    val addresses: List<InetAddress>,
)

private suspend fun resolvePublicService(baseUrl: String): ValidatedPublicService =
    withContext(Dispatchers.IO) {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw UnsafeModelEndpointException()
        if (base.scheme != "https" || base.username.isNotEmpty() || base.password.isNotEmpty()) {
            throw UnsafeModelEndpointException()
        }
        val addresses = InetAddress.getAllByName(base.host).toList()
        if (addresses.isEmpty() || addresses.any { !it.isPubliclyRoutable() }) {
            throw UnsafeModelEndpointException()
        }
        ValidatedPublicService(base, base.host, addresses.distinctBy { it.hostAddress })
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
                            val bytes = it.body.byteStream().readFully()
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
    protocol: ModelWireProtocol,
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
                            val raw = it.body.byteStream().readSse()
                            val body = if (it.code in 200..299) {
                                protocol.reconstructedBody(raw)
                            } else {
                                raw
                            }
                            val chunks = if (it.code in 200..299) {
                                sseChunksOf(protocol, raw)
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

/**
 * SSE 数据块按序经协议解析出文本增量（与 OpenAiSse.deltaChunks 语义一致：帧拆分与
 * [DONE] 终止在帧级单一来源，帧内解析委托协议，P2+ 换协议只换解析）。
 */
private fun sseChunksOf(protocol: ModelWireProtocol, rawSse: String): List<String> {
    val chunks = ArrayList<String>()
    for (block in OpenAiSse.eventDataBlocksTerminated(rawSse)) {
        if (block == OpenAiSse.DONE_BLOCK) break
        val content = protocol.streamDelta(block) ?: continue
        if (content.isNotBlank()) chunks.add(content)
    }
    return chunks
}

/**
 * Reads the SSE body up to its `[DONE]` terminator. There is deliberately no local byte
 * budget: the provider is the only thing that bounds a response, and reasoning models stream
 * far more than the app consumes (measured 3.2 MB for a 395-char answer, 98.8% reasoning),
 * so a local cap would fail tasks whose answers are small and valid.
 */
private fun java.io.InputStream.readSse(): String {
    val output = StringBuilder()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var lastScannedEnd = 0
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) break
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

/** Reads a non-streaming body to its end; the provider bounds the size, not the client. */
private fun java.io.InputStream.readFully(): ByteArray {
    val output = ByteArrayOutputStream(16 * 1024)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    } finally {
        Arrays.fill(buffer, 0.toByte())
    }
}

private fun ByteArray.toIntOctets(): IntArray = IntArray(size) { this[it].toInt() and 0xff }

/** 语义由协议族定义（OpenAiChatCompletionsProtocol.headers 复用，单一来源）。 */
internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
internal const val SSE_ACCEPT = "text/event-stream"
private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val READ_TIMEOUT_SECONDS = 90L
private const val WRITE_TIMEOUT_SECONDS = 45L
private const val CALL_TIMEOUT_SECONDS = 110L
