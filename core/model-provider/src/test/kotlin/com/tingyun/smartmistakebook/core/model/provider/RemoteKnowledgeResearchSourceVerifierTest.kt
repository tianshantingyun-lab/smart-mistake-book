package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.KnowledgeResearchCandidate
import com.tingyun.smartmistakebook.core.domain.VerifiedKnowledgeResearchSource
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKnowledgeResearchSourceVerifierTest {
    @Test
    fun verificationHashesAllowedContentAndClearsTransientBytes() = runBlocking {
        val body = "authoritative source".encodeToByteArray()
        val expectedFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(body.copyOf())
            .joinToString("") { byte -> "%02x".format(byte) }
        val verifier = RemoteKnowledgeResearchSourceVerifier(
            transport = KnowledgeResearchHttpTransport {
                KnowledgeResearchHttpResponse(
                    statusCode = 200,
                    contentType = "application/pdf; charset=binary",
                    body = body,
                )
            },
            clock = { 123L },
        )

        val verified = verifier.verify(candidate())

        assertEquals("application/pdf", verified.contentType)
        assertEquals(expectedFingerprint, verified.contentFingerprint)
        assertEquals(123L, verified.verifiedAtEpochMillis)
        assertTrue(body.all { it == 0.toByte() })
    }

    @Test
    fun redirectOrPartialResponseIsNeverAcceptedAsEvidence() = runBlocking {
        listOf(206, 301, 302, 307, 308, 404).forEach { status ->
            val body = byteArrayOf(1)
            val verifier = RemoteKnowledgeResearchSourceVerifier(
                transport = KnowledgeResearchHttpTransport {
                    KnowledgeResearchHttpResponse(
                        statusCode = status,
                        contentType = "application/pdf",
                        body = body,
                    )
                },
            )

            assertTrue(runCatching { verifier.verify(candidate()) }.isFailure)
            assertTrue(body.all { it == 0.toByte() })
        }
    }

    @Test
    fun unsupportedOrOversizedResponseIsRejectedAndCleared() = runBlocking {
        val unsupported = byteArrayOf(1, 2, 3)
        val unsupportedVerifier = RemoteKnowledgeResearchSourceVerifier(
            transport = KnowledgeResearchHttpTransport {
                KnowledgeResearchHttpResponse(200, "application/zip", unsupported)
            },
        )
        assertTrue(runCatching { unsupportedVerifier.verify(candidate()) }.isFailure)
        assertTrue(unsupported.all { it == 0.toByte() })

        val oversized = ByteArray(
            VerifiedKnowledgeResearchSource.MAX_RESEARCH_SOURCE_BYTES.toInt() + 1,
        )
        val oversizedVerifier = RemoteKnowledgeResearchSourceVerifier(
            transport = KnowledgeResearchHttpTransport {
                KnowledgeResearchHttpResponse(200, "application/pdf", oversized)
            },
        )
        assertTrue(runCatching { oversizedVerifier.verify(candidate()) }.isFailure)
        assertTrue(oversized.all { it == 0.toByte() })
    }

    @Test
    fun emptyResponseIsRejected() = runBlocking {
        val verifier = RemoteKnowledgeResearchSourceVerifier(
            transport = KnowledgeResearchHttpTransport {
                KnowledgeResearchHttpResponse(200, "text/html", byteArrayOf())
            },
        )

        assertTrue(runCatching { verifier.verify(candidate()) }.isFailure)
    }

    private fun candidate() = KnowledgeResearchCandidate(
        sourceUri = "https://www.moe.gov.cn/source.pdf",
        title = "课程标准",
        publisher = "教育部",
        sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD,
        licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL,
        searchRank = 0,
    )
}
