package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeResearchTest {
    @Test
    fun queryContainsOnlyAggregatedKnowledgeGapData() {
        val query = query()

        assertEquals(SubjectKind.MATH, query.subject)
        assertEquals("导数与单调性的局部判定", query.query)
        assertFalse(query.toString().contains("problem", ignoreCase = true))
        assertFalse(query.toString().contains("student", ignoreCase = true))
    }

    @Test
    fun queryIdentityMustMatchItsNormalizedKnowledgeGap() {
        val valid = query()

        assertTrue(
            runCatching {
                valid.copy(groundingKey = "a".repeat(64))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                valid.copy(query = "不相同的待校准知识")
            }.isFailure,
        )
    }

    @Test
    fun candidateRequiresAuditableHttpsLocation() {
        listOf(
            "http://example.edu/source.pdf",
            "https://user:secret@example.edu/source.pdf",
            "https://127.0.0.1/source.pdf",
            "https://example.edu/source.pdf#page=2",
            "https://example.edu:8443/source.pdf",
        ).forEach { unsafeUri ->
            assertTrue(runCatching { candidate(uri = unsafeUri) }.isFailure)
        }

        assertEquals(
            "https://example.edu/a/source.pdf",
            candidate(uri = "HTTPS://EXAMPLE.EDU/a/./source.pdf").canonicalSourceUri,
        )
    }

    @Test
    fun officialSourcesAreVerifiedFirstAndDuplicateUrisAreRemoved() = runBlocking {
        val queued = mutableListOf<KnowledgeResearchReviewBundle>()
        val coordinator = KnowledgeResearchCoordinator(
            searchGateway = KnowledgeResearchSearchGateway {
                listOf(
                    candidate(
                        uri = "https://publisher.example/reference.pdf",
                        sourceType = KnowledgeSourceType.MANUAL_RESEARCH,
                        rank = 0,
                    ),
                    candidate(
                        uri = "https://www.moe.gov.cn/standard.pdf",
                        sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD,
                        rank = 3,
                    ),
                    candidate(
                        uri = "https://WWW.MOE.GOV.CN/standard.pdf",
                        sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD,
                        rank = 4,
                    ),
                )
            },
            sourceVerifier = KnowledgeResearchSourceVerifier { source ->
                verified(source, 1L)
            },
            reviewQueue = KnowledgeResearchReviewQueue(queued::add),
            clock = { 10L },
        )

        val outcome = coordinator.research(query())

        assertTrue(outcome is KnowledgeResearchOutcome.AwaitingIndependentReview)
        assertEquals(
            listOf(
                "https://www.moe.gov.cn/standard.pdf",
                "https://publisher.example/reference.pdf",
            ),
            queued.single().sources.map { it.candidate.canonicalSourceUri },
        )
        assertEquals(1, queued.size)
        assertEquals(2, queued.single().sources.size)
    }

    @Test
    fun untrustedSourceCannotSelfDeclareOfficialOrLicensedAuthority() = runBlocking {
        val queued = mutableListOf<KnowledgeResearchReviewBundle>()
        val coordinator = KnowledgeResearchCoordinator(
            searchGateway = KnowledgeResearchSearchGateway {
                listOf(
                    candidate(
                        uri = "https://attacker.example/standard.pdf",
                        sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD,
                        rank = 0,
                    ),
                )
            },
            sourceVerifier = KnowledgeResearchSourceVerifier { source -> verified(source, 1L) },
            reviewQueue = KnowledgeResearchReviewQueue(queued::add),
            clock = { 2L },
        )

        coordinator.research(query())

        val normalized = queued.single().sources.single().candidate
        assertEquals(KnowledgeSourceType.MANUAL_RESEARCH, normalized.sourceType)
        assertEquals(KnowledgeSourceLicenseStatus.REFERENCE_ONLY, normalized.licenseStatus)
    }

    @Test
    fun licensedMaterialNeedsAnExplicitDocumentGrant() {
        val candidate = candidate(
            uri = "https://licensed.example/textbook.pdf",
            sourceType = KnowledgeSourceType.TEXTBOOK,
            licenseStatus = KnowledgeSourceLicenseStatus.LICENSED,
        )

        assertEquals(
            candidate,
            ConservativeKnowledgeResearchSourcePolicy(
                licenseGrants = setOf(
                    KnowledgeResearchLicenseGrant(
                        sourceUriPrefix = candidate.sourceUri,
                        sourceType = KnowledgeSourceType.TEXTBOOK,
                    ),
                ),
            ).normalize(candidate),
        )
        val withoutGrant = ConservativeKnowledgeResearchSourcePolicy().normalize(candidate)
        assertEquals(KnowledgeSourceType.MANUAL_RESEARCH, withoutGrant.sourceType)
        assertEquals(KnowledgeSourceLicenseStatus.REFERENCE_ONLY, withoutGrant.licenseStatus)
    }

    @Test
    fun documentGrantDoesNotAuthorizeAnotherBookOnTheSameHost() {
        val policy = ConservativeKnowledgeResearchSourcePolicy(
            licenseGrants = setOf(
                KnowledgeResearchLicenseGrant(
                    sourceUriPrefix = "https://licensed.example/books/math-v1.pdf",
                    sourceType = KnowledgeSourceType.TEXTBOOK,
                ),
            ),
        )
        val otherBook = candidate(
            uri = "https://licensed.example/books/physics-v2.pdf",
            sourceType = KnowledgeSourceType.TEXTBOOK,
            licenseStatus = KnowledgeSourceLicenseStatus.LICENSED,
        )

        val normalized = policy.normalize(otherBook)

        assertEquals(KnowledgeSourceType.MANUAL_RESEARCH, normalized.sourceType)
        assertEquals(KnowledgeSourceLicenseStatus.REFERENCE_ONLY, normalized.licenseStatus)
    }

    @Test
    fun subtreeGrantIsPathBoundedAndMustBeExplicit() {
        val policy = ConservativeKnowledgeResearchSourcePolicy(
            licenseGrants = setOf(
                KnowledgeResearchLicenseGrant(
                    sourceUriPrefix = "https://licensed.example/open-series/",
                    sourceType = KnowledgeSourceType.AUTHORIZED_EDUCATION_MATERIAL,
                    includePathDescendants = true,
                ),
            ),
        )
        val included = candidate(
            uri = "https://licensed.example/open-series/math/chapter-1.html",
            sourceType = KnowledgeSourceType.AUTHORIZED_EDUCATION_MATERIAL,
            licenseStatus = KnowledgeSourceLicenseStatus.LICENSED,
        )
        val excluded = included.copy(
            sourceUri = "https://licensed.example/private-series/math/chapter-1.html",
        )

        assertEquals(included, policy.normalize(included))
        val normalizedExcluded = policy.normalize(excluded)
        assertEquals(KnowledgeSourceType.MANUAL_RESEARCH, normalizedExcluded.sourceType)
        assertEquals(
            KnowledgeSourceLicenseStatus.REFERENCE_ONLY,
            normalizedExcluded.licenseStatus,
        )
    }

    @Test
    fun coordinatorCapsNetworkWorkAndKeepsPartialVerificationSuccess() = runBlocking {
        var verificationCount = 0
        val coordinator = KnowledgeResearchCoordinator(
            searchGateway = KnowledgeResearchSearchGateway {
                (0 until 20).map { index ->
                    candidate(
                        uri = "https://source$index.example/document.pdf",
                        rank = index,
                    )
                }
            },
            sourceVerifier = KnowledgeResearchSourceVerifier { source ->
                verificationCount += 1
                if (source.searchRank == 1) error("Source is unavailable")
                verified(source, source.searchRank.toLong())
            },
            reviewQueue = KnowledgeResearchReviewQueue {},
            clock = { 20L },
        )

        val outcome = coordinator.research(query())

        assertEquals(KnowledgeResearchCoordinator.MAX_VERIFIED_SOURCES, verificationCount)
        outcome as KnowledgeResearchOutcome.AwaitingIndependentReview
        assertEquals(1, outcome.failedVerificationCount)
        assertEquals(8, outcome.deferredCandidateCount)
        assertEquals(3, outcome.bundle.sources.size)
    }

    @Test
    fun coordinatorBoundsConcurrentSourceDownloads() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val coordinator = KnowledgeResearchCoordinator(
            searchGateway = KnowledgeResearchSearchGateway {
                (0 until KnowledgeResearchCoordinator.MAX_VERIFIED_SOURCES).map { index ->
                    candidate(
                        uri = "https://source$index.example/document.pdf",
                        rank = index,
                    )
                }
            },
            sourceVerifier = KnowledgeResearchSourceVerifier { source ->
                val nowActive = active.incrementAndGet()
                peak.updateAndGet { current -> maxOf(current, nowActive) }
                try {
                    delay(10)
                    verified(source, source.searchRank.toLong())
                } finally {
                    active.decrementAndGet()
                }
            },
            reviewQueue = KnowledgeResearchReviewQueue {},
            clock = { 20L },
        )

        coordinator.research(query())

        assertTrue(peak.get() <= KnowledgeResearchCoordinator.MAX_PARALLEL_SOURCE_VERIFICATIONS)
        assertTrue(peak.get() > 1)
    }

    @Test
    fun failedVerificationNeverQueuesAReviewBundle() = runBlocking {
        var enqueueCount = 0
        val coordinator = KnowledgeResearchCoordinator(
            searchGateway = KnowledgeResearchSearchGateway {
                listOf(candidate(uri = "https://source.example/document.pdf"))
            },
            sourceVerifier = KnowledgeResearchSourceVerifier { error("Fingerprint mismatch") },
            reviewQueue = KnowledgeResearchReviewQueue { enqueueCount += 1 },
        )

        val outcome = coordinator.research(query())

        assertEquals(
            KnowledgeResearchOutcome.NoReviewableSources(
                searchedCandidateCount = 1,
                failedVerificationCount = 1,
                deferredCandidateCount = 0,
            ),
            outcome,
        )
        assertEquals(0, enqueueCount)
    }

    @Test
    fun reviewBundleIdentityIsStableAcrossSearchOrder() {
        val first = verified(
            candidate(uri = "https://one.example/source.pdf"),
            verifiedAt = 1L,
        )
        val second = verified(
            candidate(uri = "https://two.example/source.pdf"),
            verifiedAt = 2L,
        )

        assertEquals(
            KnowledgeResearchFingerprint.reviewBundleId(query(), listOf(first, second)),
            KnowledgeResearchFingerprint.reviewBundleId(query(), listOf(second, first)),
        )
    }

    @Test
    fun verificationMetadataRejectsUnsupportedOrOversizedContent() {
        val candidate = candidate(uri = "https://source.example/document.pdf")
        assertTrue(
            runCatching {
                verified(candidate, contentType = "application/zip")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                verified(
                    candidate,
                    contentLength = VerifiedKnowledgeResearchSource.MAX_RESEARCH_SOURCE_BYTES + 1,
                )
            }.isFailure,
        )
    }

    private fun query(): KnowledgeResearchQuery {
        val query = "导数与单调性的局部判定"
        val expectedParent = "导数"
        return KnowledgeResearchQuery(
            groundingKey = KnowledgeGroundingFingerprint.of(
                subject = SubjectKind.MATH,
                expectedParentKnowledgeDisplayName = expectedParent,
                query = query,
            ),
            subject = SubjectKind.MATH,
            query = query,
            expectedParentKnowledgeDisplayName = expectedParent,
            relatedQuestionCount = 3,
        )
    }

    private fun candidate(
        uri: String,
        sourceType: KnowledgeSourceType = KnowledgeSourceType.MANUAL_RESEARCH,
        licenseStatus: KnowledgeSourceLicenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY,
        rank: Int = 0,
    ) = KnowledgeResearchCandidate(
        sourceUri = uri,
        title = "可核验资料",
        publisher = "资料发布方",
        sourceType = sourceType,
        licenseStatus = licenseStatus,
        searchRank = rank,
    )

    private fun verified(
        candidate: KnowledgeResearchCandidate,
        verifiedAt: Long = 1L,
        contentType: String = "application/pdf",
        contentLength: Long = 1_024L,
    ) = VerifiedKnowledgeResearchSource(
        candidate = candidate,
        contentType = contentType,
        contentLengthBytes = contentLength,
        contentFingerprint = candidate.canonicalSourceUri.hashCode().toUInt().toString(16)
            .padStart(64, '0')
            .takeLast(64),
        verifiedAtEpochMillis = verifiedAt,
    )
}
