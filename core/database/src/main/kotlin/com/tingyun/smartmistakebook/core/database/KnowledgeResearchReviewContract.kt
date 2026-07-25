package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchReviewFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchSourceFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.net.URI

internal object KnowledgeResearchReviewContract {
    private val subjects = enumValues<SubjectKind>().map(SubjectKind::name).toSet()
    private val sourceTypes = enumValues<KnowledgeSourceType>().map(KnowledgeSourceType::name).toSet()
    private val licenseStatuses = enumValues<KnowledgeSourceLicenseStatus>()
        .map(KnowledgeSourceLicenseStatus::name)
        .toSet()
    private val contentTypes = setOf(
        "application/pdf",
        "application/xhtml+xml",
        "text/html",
        "text/plain",
    )

    fun validate(bundle: KnowledgeResearchReviewBundleRecord) {
        lowerSha256(bundle.bundleId, "bundleId")
        requireReview(bundle.subject in subjects) { "subject is unknown" }
        text(bundle.query, "query", 160)
        text(
            bundle.expectedParentKnowledgeDisplayName,
            "expectedParentKnowledgeDisplayName",
            96,
        )
        requireReview(KnowledgeGroundingFingerprint.isValid(bundle.groundingKey)) {
            "groundingKey is invalid"
        }
        requireReview(
            bundle.groundingKey == KnowledgeGroundingFingerprint.of(
                subject = SubjectKind.valueOf(bundle.subject),
                expectedParentKnowledgeDisplayName = bundle.expectedParentKnowledgeDisplayName,
                query = bundle.query,
            ),
        ) { "groundingKey does not match its normalized subject, parent, and query" }
        requireReview(bundle.relatedQuestionCount in 1..100_000) {
            "relatedQuestionCount is outside the supported range"
        }
        text(bundle.workflowVersion, "workflowVersion", 64)
        requireReview(
            bundle.status == StudyDbValue.KnowledgeResearchReviewStatus.PENDING_REVIEW,
        ) { "New research bundles must be pending review" }
        requireReview(
            bundle.reviewerReference == null &&
                bundle.decisionNote == null &&
                bundle.reviewedAtEpochMillis == null &&
                bundle.appliedPackFingerprint == null &&
                bundle.appliedAtEpochMillis == null,
        ) { "New research bundles cannot contain a review decision" }
        requireReview(bundle.createdAtEpochMillis >= 0) {
            "createdAtEpochMillis must be non-negative"
        }
        requireReview(bundle.updatedAtEpochMillis == bundle.createdAtEpochMillis) {
            "New research bundles cannot be pre-updated"
        }
        requireReview(bundle.sources.size in 1..MAX_SOURCES) {
            "A research bundle must contain 1..$MAX_SOURCES sources"
        }
        requireReview(
            bundle.sources.map(KnowledgeResearchReviewSourceRecord::sourceOrdinal) ==
                bundle.sources.indices.toList(),
        ) { "Research source ordinals must be contiguous and ordered" }
        requireReview(bundle.sources.distinctBy { it.canonicalSourceUri }.size == bundle.sources.size) {
            "Research source URIs must be unique inside a bundle"
        }
        bundle.sources.forEach(::validateSource)
        requireReview(
            bundle.createdAtEpochMillis >= bundle.sources.maxOf { it.verifiedAtEpochMillis },
        ) { "A research bundle cannot predate source verification" }
        requireReview(bundle.bundleId == expectedBundleId(bundle)) {
            "bundleId does not match the immutable research payload"
        }
    }

    fun validateDecision(
        command: DecideKnowledgeResearchReviewBundleCommand,
        bundle: KnowledgeResearchReviewBundleRecord,
    ) {
        lowerSha256(command.bundleId, "decision.bundleId")
        requireReview(command.bundleId == bundle.bundleId) {
            "Review decision targets a different bundle"
        }
        requireReview(command.decisionStatus in decisionStatuses) {
            "Review decision status is not allowed"
        }
        text(command.reviewerReference, "decision.reviewerReference", 160)
        text(command.decisionNote, "decision.decisionNote", 2_000)
        requireReview(command.decidedAtEpochMillis >= bundle.createdAtEpochMillis) {
            "Review decision cannot predate the bundle"
        }
    }

    private fun validateSource(source: KnowledgeResearchReviewSourceRecord) {
        val uri = runCatching { URI(source.canonicalSourceUri) }.getOrNull()
        requireReview(
            uri != null &&
                uri.isAbsolute &&
                !uri.isOpaque &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.port == -1 &&
                uri.host.any(Char::isLetter),
        ) { "Research source URI must be canonical HTTPS" }
        text(source.title, "source.title", 240)
        source.publisher?.let { text(it, "source.publisher", 160) }
        requireReview(source.sourceType in sourceTypes) { "source.sourceType is unknown" }
        requireReview(source.licenseStatus in licenseStatuses) {
            "source.licenseStatus is unknown"
        }
        requireReview(source.searchRank in 0 until 64) {
            "source.searchRank is outside the supported range"
        }
        requireReview(source.contentType in contentTypes) { "source.contentType is not allowed" }
        requireReview(source.contentLengthBytes in 1..MAX_SOURCE_BYTES) {
            "source.contentLengthBytes is outside the supported range"
        }
        lowerSha256(source.contentFingerprint, "source.contentFingerprint")
        requireReview(source.verifiedAtEpochMillis >= 0) {
            "source.verifiedAtEpochMillis must be non-negative"
        }
    }

    private fun expectedBundleId(bundle: KnowledgeResearchReviewBundleRecord): String =
        KnowledgeResearchReviewFingerprint.of(
            groundingKey = bundle.groundingKey,
            subject = bundle.subject,
            sources = bundle.sources.map { source ->
                KnowledgeResearchSourceFingerprint(
                    canonicalSourceUri = source.canonicalSourceUri,
                    contentFingerprint = source.contentFingerprint,
                )
            },
        )

    private fun lowerSha256(value: String, label: String) {
        requireReview(value.length == SHA_256_CHARS && value.all { it.isLowerHexDigit() }) {
            "$label must be a lowercase SHA-256 digest"
        }
    }

    private fun text(value: String, label: String, maxChars: Int) {
        requireReview(value.isNotBlank()) { "$label must not be blank" }
        requireReview(value.length <= maxChars) { "$label exceeds its character limit" }
        requireReview(value.none { it.code < 0x20 && it != '\t' }) {
            "$label contains control characters"
        }
    }

    private fun requireReview(value: Boolean, message: () -> String) {
        if (!value) throw DatabaseContractViolationException(message())
    }

    private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

    private const val SHA_256_CHARS = 64
    private const val MAX_SOURCES = 4
    private const val MAX_SOURCE_BYTES = 16L * 1_024L * 1_024L
    private val decisionStatuses = setOf(
        StudyDbValue.KnowledgeResearchReviewStatus.APPROVED,
        StudyDbValue.KnowledgeResearchReviewStatus.REJECTED,
    )
}
