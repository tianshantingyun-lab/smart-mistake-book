package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.KnowledgeResearchCandidate
import com.tingyun.smartmistakebook.core.domain.KnowledgeResearchSourceVerifier
import com.tingyun.smartmistakebook.core.domain.VerifiedKnowledgeResearchSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

fun createRemoteKnowledgeResearchSourceVerifier(): KnowledgeResearchSourceVerifier =
    RemoteKnowledgeResearchSourceVerifier(OkHttpKnowledgeResearchTransport())

internal class RemoteKnowledgeResearchSourceVerifier(
    private val transport: KnowledgeResearchHttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) : KnowledgeResearchSourceVerifier {
    override suspend fun verify(
        candidate: KnowledgeResearchCandidate,
    ): VerifiedKnowledgeResearchSource {
        val response = transport.get(candidate.canonicalSourceUri)
        try {
        require(response.statusCode == HTTP_OK) {
                "Research source did not return a complete document"
            }
            require(response.body.isNotEmpty()) { "Research source is empty" }
            require(
                response.body.size.toLong() <=
                    VerifiedKnowledgeResearchSource.MAX_RESEARCH_SOURCE_BYTES,
            ) {
                "Research source exceeds the verification size limit"
            }
            val contentType = response.contentType.substringBefore(';').trim().lowercase()
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(response.body)
                .joinToString("") { byte -> "%02x".format(byte) }
            return VerifiedKnowledgeResearchSource(
                candidate = candidate,
                contentType = contentType,
                contentLengthBytes = response.body.size.toLong(),
                contentFingerprint = fingerprint,
                verifiedAtEpochMillis = clock(),
            )
        } finally {
            Arrays.fill(response.body, 0.toByte())
        }
    }

}

internal data class KnowledgeResearchHttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: ByteArray,
)

internal fun interface KnowledgeResearchHttpTransport {
    suspend fun get(sourceUri: String): KnowledgeResearchHttpResponse
}

private class OkHttpKnowledgeResearchTransport : KnowledgeResearchHttpTransport {
    override suspend fun get(sourceUri: String): KnowledgeResearchHttpResponse {
        val endpoint = PublicHttpEndpoint.resolve(sourceUri, requireDefaultPort = true)
        val client = OkHttpClient.Builder()
            .dns(PinnedPublicDns(endpoint.host, endpoint.addresses))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Accept", ACCEPTED_RESEARCH_CONTENT)
            .header("User-Agent", RESEARCH_USER_AGENT)
            .get()
            .build()
        return client.newCall(request).awaitBoundedResearchResponse()
    }
}

private suspend fun Call.awaitBoundedResearchResponse(): KnowledgeResearchHttpResponse {
    val result = CompletableDeferred<KnowledgeResearchHttpResponse>()
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code != HTTP_OK) {
                            throw KnowledgeResearchHttpStatusException(it.code)
                        }
                        val declaredLength = it.body.contentLength()
                        if (
                            declaredLength >
                            VerifiedKnowledgeResearchSource.MAX_RESEARCH_SOURCE_BYTES
                        ) {
                            throw KnowledgeResearchSourceTooLargeException()
                        }
                        val bytes = it.body.byteStream().readResearchDocumentWithinLimit()
                        result.complete(
                            KnowledgeResearchHttpResponse(
                                statusCode = it.code,
                                contentType = it.header("Content-Type").orEmpty(),
                                body = bytes,
                            ),
                        )
                    }
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        },
    )
    return try {
        result.await()
    } finally {
        if (!result.isCompleted) cancel()
    }
}

private fun java.io.InputStream.readResearchDocumentWithinLimit(): ByteArray {
    val limit = VerifiedKnowledgeResearchSource.MAX_RESEARCH_SOURCE_BYTES.toInt()
    val output = ClearingByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    try {
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw KnowledgeResearchSourceTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    } finally {
        Arrays.fill(buffer, 0.toByte())
        output.clear()
    }
}

private class ClearingByteArrayOutputStream(
    initialSize: Int,
) : ByteArrayOutputStream(initialSize) {
    fun clear() {
        Arrays.fill(buf, 0, count, 0.toByte())
        reset()
    }
}

internal class KnowledgeResearchSourceTooLargeException : IllegalArgumentException()

internal class KnowledgeResearchHttpStatusException(
    val statusCode: Int,
) : IllegalArgumentException("Research source returned HTTP $statusCode")

private const val HTTP_OK = 200
private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 30L
private const val CALL_TIMEOUT_SECONDS = 45L
private const val ACCEPTED_RESEARCH_CONTENT =
    "application/pdf, text/html;q=0.9, application/xhtml+xml;q=0.9, text/plain;q=0.8"
private const val RESEARCH_USER_AGENT = "SmartMistakeBook-KnowledgeResearch/1.0"
