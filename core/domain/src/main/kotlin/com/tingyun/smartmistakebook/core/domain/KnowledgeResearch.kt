package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchReviewFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchSourceFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class KnowledgeResearchQuery(
    val groundingKey: String,
    val subject: SubjectKind,
    val query: String,
    val expectedParentKnowledgeDisplayName: String,
    val relatedQuestionCount: Int,
) {
    init {
        query.requireResearchText("Research query", MAX_QUERY_CHARS)
        expectedParentKnowledgeDisplayName.requireResearchText(
            "Expected parent knowledge",
            MAX_PARENT_NAME_CHARS,
        )
        require(KnowledgeGroundingFingerprint.isValid(groundingKey)) {
            "Research key is invalid"
        }
        require(
            groundingKey == KnowledgeGroundingFingerprint.of(
                subject = subject,
                expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
                query = query,
            ),
        ) {
            "Research key does not match the normalized subject, parent, and query"
        }
        require(relatedQuestionCount in 1..MAX_RELATED_QUESTION_COUNT) {
            "Related question count is outside the supported range"
        }
    }

    companion object {
        const val MAX_QUERY_CHARS = 160
        const val MAX_PARENT_NAME_CHARS = 96
        const val MAX_RELATED_QUESTION_COUNT = 100_000
    }
}

data class KnowledgeResearchCandidate(
    val sourceUri: String,
    val title: String,
    val publisher: String?,
    val sourceType: KnowledgeSourceType,
    val licenseStatus: KnowledgeSourceLicenseStatus,
    val searchRank: Int,
) {
    val canonicalSourceUri: String = canonicalKnowledgeResearchUri(sourceUri)

    init {
        title.requireResearchText("Research source title", MAX_TITLE_CHARS)
        publisher?.requireResearchText("Research source publisher", MAX_PUBLISHER_CHARS)
        require(searchRank in 0 until MAX_SEARCH_RESULTS) {
            "Research source rank is outside the supported range"
        }
    }

    companion object {
        const val MAX_TITLE_CHARS = 240
        const val MAX_PUBLISHER_CHARS = 160
        const val MAX_SEARCH_RESULTS = 64
    }
}

data class VerifiedKnowledgeResearchSource(
    val candidate: KnowledgeResearchCandidate,
    val contentType: String,
    val contentLengthBytes: Long,
    val contentFingerprint: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        contentType.requireResearchText("Research source content type", MAX_CONTENT_TYPE_CHARS)
        require(contentType.lowercase() in ALLOWED_RESEARCH_CONTENT_TYPES) {
            "Research source content type is not allowed"
        }
        require(contentLengthBytes in 1..MAX_RESEARCH_SOURCE_BYTES) {
            "Research source size is outside the supported range"
        }
        require(
            contentFingerprint.length == SHA_256_HEX_CHARS &&
                contentFingerprint.all(Char::isLowerHexDigit),
        ) {
            "Research source fingerprint must be SHA-256"
        }
        require(verifiedAtEpochMillis >= 0) { "Research source verification time must be non-negative" }
    }

    companion object {
        const val MAX_RESEARCH_SOURCE_BYTES = 16L * 1_024L * 1_024L
        const val MAX_CONTENT_TYPE_CHARS = 96
        val ALLOWED_RESEARCH_CONTENT_TYPES = setOf(
            "application/pdf",
            "application/xhtml+xml",
            "text/html",
            "text/plain",
        )
    }
}

data class KnowledgeResearchReviewBundle(
    val bundleId: String,
    val query: KnowledgeResearchQuery,
    val sources: List<VerifiedKnowledgeResearchSource>,
    val workflowVersion: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(bundleId == KnowledgeResearchFingerprint.reviewBundleId(query, sources)) {
            "Research review bundle id does not match its contents"
        }
        require(sources.isNotEmpty()) { "Research review bundle needs at least one source" }
        require(sources.size <= KnowledgeResearchCoordinator.MAX_VERIFIED_SOURCES) {
            "Research review bundle contains too many sources"
        }
        require(sources.distinctBy { it.candidate.canonicalSourceUri }.size == sources.size) {
            "Research review bundle contains duplicate sources"
        }
        workflowVersion.requireResearchText("Research workflow version", MAX_WORKFLOW_VERSION_CHARS)
        require(createdAtEpochMillis >= sources.maxOf { it.verifiedAtEpochMillis }) {
            "Research review bundle cannot predate source verification"
        }
    }

    companion object {
        const val MAX_WORKFLOW_VERSION_CHARS = 64
    }
}

fun interface KnowledgeResearchSearchGateway {
    suspend fun search(query: KnowledgeResearchQuery): List<KnowledgeResearchCandidate>
}

fun interface KnowledgeResearchSourceVerifier {
    suspend fun verify(candidate: KnowledgeResearchCandidate): VerifiedKnowledgeResearchSource
}

fun interface KnowledgeResearchReviewQueue {
    suspend fun enqueue(bundle: KnowledgeResearchReviewBundle)
}

fun interface KnowledgeResearchSourcePolicy {
    fun normalize(candidate: KnowledgeResearchCandidate): KnowledgeResearchCandidate
}

/**
 * A locally reviewed grant for one document or one explicit URI subtree.
 *
 * Host-wide grants are intentionally impossible: providers may mix books, editions, authors, and
 * licenses on the same host.
 */
data class KnowledgeResearchLicenseGrant(
    val sourceUriPrefix: String,
    val sourceType: KnowledgeSourceType,
    val includePathDescendants: Boolean = false,
) {
    private val canonicalPrefix = canonicalKnowledgeResearchUri(sourceUriPrefix)
    private val prefixUri = URI(canonicalPrefix)

    init {
        require(sourceType in LICENSED_RESEARCH_SOURCE_TYPES) {
            "A knowledge research license grant must target a licensable source type"
        }
        if (includePathDescendants) {
            require(prefixUri.query == null && prefixUri.path.endsWith('/')) {
                "A descendant license grant needs a query-free URI ending in a slash"
            }
        }
    }

    internal fun matches(candidate: KnowledgeResearchCandidate): Boolean {
        if (candidate.sourceType != sourceType) return false
        if (!includePathDescendants) return candidate.canonicalSourceUri == canonicalPrefix
        val candidateUri = URI(candidate.canonicalSourceUri)
        return candidateUri.host.equals(prefixUri.host, ignoreCase = true) &&
            candidateUri.path.startsWith(prefixUri.path)
    }
}

class ConservativeKnowledgeResearchSourcePolicy(
    private val licenseGrants: Set<KnowledgeResearchLicenseGrant> = emptySet(),
) : KnowledgeResearchSourcePolicy {
    override fun normalize(candidate: KnowledgeResearchCandidate): KnowledgeResearchCandidate {
        val host = URI(candidate.canonicalSourceUri).host.lowercase()
        val isVerifiedOfficialClaim =
            candidate.sourceType == KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD &&
                host.isChineseGovernmentHost()
        val isVerifiedLicensedClaim =
            candidate.licenseStatus == KnowledgeSourceLicenseStatus.LICENSED &&
                licenseGrants.any { grant -> grant.matches(candidate) }
        return when {
            isVerifiedOfficialClaim -> candidate.copy(
                licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL,
            )

            isVerifiedLicensedClaim -> candidate
            else -> candidate.copy(
                sourceType = KnowledgeSourceType.MANUAL_RESEARCH,
                licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY,
            )
        }
    }

}

sealed interface KnowledgeResearchOutcome {
    data class AwaitingIndependentReview(
        val bundle: KnowledgeResearchReviewBundle,
        val failedVerificationCount: Int,
        val deferredCandidateCount: Int,
    ) : KnowledgeResearchOutcome

    data class NoReviewableSources(
        val searchedCandidateCount: Int,
        val failedVerificationCount: Int,
        val deferredCandidateCount: Int,
    ) : KnowledgeResearchOutcome
}

class KnowledgeResearchCoordinator(
    private val searchGateway: KnowledgeResearchSearchGateway,
    private val sourceVerifier: KnowledgeResearchSourceVerifier,
    private val reviewQueue: KnowledgeResearchReviewQueue,
    private val sourcePolicy: KnowledgeResearchSourcePolicy =
        ConservativeKnowledgeResearchSourcePolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun research(query: KnowledgeResearchQuery): KnowledgeResearchOutcome {
        val candidates = searchGateway.search(query)
            .take(KnowledgeResearchCandidate.MAX_SEARCH_RESULTS)
            .map(sourcePolicy::normalize)
            .sortedWith(
                compareBy<KnowledgeResearchCandidate> { it.sourceType.researchPriority() }
                    .thenBy(KnowledgeResearchCandidate::searchRank)
                    .thenBy(KnowledgeResearchCandidate::canonicalSourceUri),
            )
            .distinctBy(KnowledgeResearchCandidate::canonicalSourceUri)
            .take(MAX_SEARCH_CANDIDATES)
        if (candidates.isEmpty()) {
            return KnowledgeResearchOutcome.NoReviewableSources(
                searchedCandidateCount = 0,
                failedVerificationCount = 0,
                deferredCandidateCount = 0,
            )
        }

        val verificationResults = candidates
            .take(MAX_VERIFIED_SOURCES)
            .chunked(MAX_PARALLEL_SOURCE_VERIFICATIONS)
            .flatMap { batch ->
                coroutineScope {
                    batch.map { candidate ->
                        async { runCatching { sourceVerifier.verify(candidate) } }
                    }.awaitAll()
                }
            }
        val verified = verificationResults.mapNotNull(Result<VerifiedKnowledgeResearchSource>::getOrNull)
        val failedCount = verificationResults.count(Result<VerifiedKnowledgeResearchSource>::isFailure)
        val deferredCount = candidates.size - verificationResults.size
        if (verified.isEmpty()) {
            return KnowledgeResearchOutcome.NoReviewableSources(
                searchedCandidateCount = candidates.size,
                failedVerificationCount = failedCount,
                deferredCandidateCount = deferredCount,
            )
        }

        val now = clock()
        val bundle = KnowledgeResearchReviewBundle(
            bundleId = KnowledgeResearchFingerprint.reviewBundleId(query, verified),
            query = query,
            sources = verified,
            workflowVersion = WORKFLOW_VERSION,
            createdAtEpochMillis = now,
        )
        reviewQueue.enqueue(bundle)
        return KnowledgeResearchOutcome.AwaitingIndependentReview(
            bundle = bundle,
            failedVerificationCount = failedCount,
            deferredCandidateCount = deferredCount,
        )
    }

    companion object {
        const val MAX_SEARCH_CANDIDATES = 12
        const val MAX_VERIFIED_SOURCES = 4
        const val MAX_PARALLEL_SOURCE_VERIFICATIONS = 2
        const val WORKFLOW_VERSION = "knowledge-research-v1"
    }
}

object KnowledgeResearchFingerprint {
    fun reviewBundleId(
        query: KnowledgeResearchQuery,
        sources: List<VerifiedKnowledgeResearchSource>,
    ): String = KnowledgeResearchReviewFingerprint.of(
        groundingKey = query.groundingKey,
        subject = query.subject.name,
        sources = sources.map { source ->
            KnowledgeResearchSourceFingerprint(
                canonicalSourceUri = source.candidate.canonicalSourceUri,
                contentFingerprint = source.contentFingerprint,
            )
        },
    )
}

private fun KnowledgeSourceType.researchPriority(): Int = when (this) {
    KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD -> 0
    KnowledgeSourceType.TEXTBOOK -> 1
    KnowledgeSourceType.AUTHORIZED_EDUCATION_MATERIAL -> 2
    KnowledgeSourceType.MANUAL_RESEARCH -> 3
}

private fun canonicalKnowledgeResearchUri(raw: String): String {
    raw.requireResearchText("Research source URI", MAX_SOURCE_URI_CHARS)
    val uri = runCatching { URI(raw) }.getOrElse {
        throw IllegalArgumentException("Research source URI is invalid")
    }
    require(
        uri.isAbsolute &&
            !uri.isOpaque &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.port in setOf(-1, 443),
    ) {
        "Research source URI must be a credential-free HTTPS URL"
    }
    require(uri.host.any(Char::isLetter)) { "Research source URI must use a named host" }
    val normalized = URI(
        "https",
        null,
        uri.host.lowercase(),
        -1,
        uri.path.ifEmpty { "/" },
        uri.query,
        null,
    ).normalize()
    return normalized.toASCIIString()
}

private fun String.requireResearchText(label: String, maxChars: Int) {
    require(isNotBlank()) { "$label must not be blank" }
    require(length <= maxChars) { "$label exceeds its character limit" }
    require(none { it.code < 0x20 && it != '\t' }) { "$label contains control characters" }
}

private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

private fun String.isChineseGovernmentHost(): Boolean =
    this == "gov.cn" || endsWith(".gov.cn")

private val LICENSED_RESEARCH_SOURCE_TYPES = setOf(
    KnowledgeSourceType.TEXTBOOK,
    KnowledgeSourceType.AUTHORIZED_EDUCATION_MATERIAL,
)

private const val SHA_256_HEX_CHARS = 64
private const val MAX_SOURCE_URI_CHARS = 2_048
