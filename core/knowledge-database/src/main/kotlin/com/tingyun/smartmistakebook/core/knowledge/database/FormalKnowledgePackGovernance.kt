package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/**
 * Build-time disposition for one source. This is evidence classification, not an installation
 * capability: the fixed trusted-pack registry remains the only runtime mutation authority.
 */
enum class KnowledgeSourceAdmissionRoute {
    ALLOW_CORE_AUTO,
    ALLOW_METADATA_ONLY,
    HUMAN_REFERENCE_ONLY,
    SEPARATE_SHARE_ALIKE_PACK,
    BLOCKED,
}

enum class KnowledgeSourceLicenseClass {
    PERMISSIVE,
    PUBLIC_OFFICIAL_METADATA,
    SHARE_ALIKE,
    NON_COMMERCIAL,
    NO_DERIVATIVES,
    REFERENCE_ONLY,
    UNKNOWN,
    BLOCKED,
}

enum class KnowledgeSourceEvidenceOrigin {
    HUMAN_VERIFIED,
    MODEL_DECLARATION,
    UNKNOWN,
}

enum class KnowledgeSourceAutomationPermission {
    ALLOWED,
    METADATA_ONLY,
    FORBIDDEN,
    UNKNOWN,
}

enum class KnowledgeArtifactReviewStatus {
    HUMAN_REVIEWED,
    PENDING,
    REJECTED,
}

/**
 * Versioned evidence used before a source can be admitted to a reviewed pack.
 *
 * Nullable evidence fields are intentional: incomplete candidates can be recorded, but the policy
 * below rejects them. Model output is never accepted as license evidence.
 */
data class KnowledgeSourceAdmissionEvidence(
    val sourceId: String,
    val sourceVersion: String,
    val sourceRecordFingerprint: String,
    val canonicalLocator: String,
    val publisher: String,
    val resourceTitle: String,
    val licenseClass: KnowledgeSourceLicenseClass,
    val licenseExpression: String?,
    val licenseEvidenceLocator: String?,
    val licenseEvidenceFingerprint: String?,
    val evidenceOrigin: KnowledgeSourceEvidenceOrigin,
    val automationPermission: KnowledgeSourceAutomationPermission,
    val reviewStatus: KnowledgeArtifactReviewStatus,
    val reviewRecordId: String?,
    val reviewedAtEpochMillis: Long?,
) {
    init {
        sourceId.requireGovernanceId("Source id")
        sourceVersion.requireGovernanceId("Source version")
        require(GOVERNANCE_SHA_256.matches(sourceRecordFingerprint)) {
            "Source record fingerprint must be lowercase SHA-256"
        }
        canonicalLocator.requireGovernanceText("Source canonical locator")
        publisher.requireGovernanceText("Source publisher")
        resourceTitle.requireGovernanceText("Source resource title")
        licenseExpression?.requireGovernanceText("License expression")
        licenseEvidenceLocator?.requireGovernanceText("License evidence locator")
        require(
            licenseEvidenceFingerprint == null ||
                GOVERNANCE_SHA_256.matches(licenseEvidenceFingerprint),
        ) {
            "License evidence fingerprint must be lowercase SHA-256"
        }
        reviewRecordId?.requireGovernanceId("Review record id")
        require(reviewedAtEpochMillis == null || reviewedAtEpochMillis >= 0L) {
            "Source review time must not be negative"
        }
    }
}

/**
 * Fail-closed source gate for pack production.
 *
 * Unknown evidence, model-declared licensing, rejected/pending review, and unknown or forbidden
 * automation never enter an automatic route. NC/ND material remains human-reference-only; it is
 * not copied into the core pack. Share-alike material requires a separately governed pack.
 */
object KnowledgeSourceAdmissionPolicy {
    fun evaluate(evidence: KnowledgeSourceAdmissionEvidence): KnowledgeSourceAdmissionRoute {
        if (
            evidence.evidenceOrigin != KnowledgeSourceEvidenceOrigin.HUMAN_VERIFIED ||
            evidence.reviewStatus != KnowledgeArtifactReviewStatus.HUMAN_REVIEWED ||
            evidence.reviewRecordId.isNullOrBlank() ||
            evidence.reviewedAtEpochMillis == null ||
            evidence.licenseExpression.isNullOrBlank() ||
            evidence.licenseEvidenceLocator.isNullOrBlank() ||
            evidence.licenseEvidenceFingerprint == null
        ) {
            return KnowledgeSourceAdmissionRoute.BLOCKED
        }
        if (
            evidence.automationPermission == KnowledgeSourceAutomationPermission.FORBIDDEN ||
            evidence.automationPermission == KnowledgeSourceAutomationPermission.UNKNOWN
        ) {
            return KnowledgeSourceAdmissionRoute.BLOCKED
        }
        return when (evidence.licenseClass) {
            KnowledgeSourceLicenseClass.PERMISSIVE ->
                if (evidence.automationPermission == KnowledgeSourceAutomationPermission.ALLOWED) {
                    KnowledgeSourceAdmissionRoute.ALLOW_CORE_AUTO
                } else {
                    KnowledgeSourceAdmissionRoute.ALLOW_METADATA_ONLY
                }
            KnowledgeSourceLicenseClass.PUBLIC_OFFICIAL_METADATA ->
                KnowledgeSourceAdmissionRoute.ALLOW_METADATA_ONLY
            KnowledgeSourceLicenseClass.SHARE_ALIKE ->
                KnowledgeSourceAdmissionRoute.SEPARATE_SHARE_ALIKE_PACK
            KnowledgeSourceLicenseClass.NON_COMMERCIAL,
            KnowledgeSourceLicenseClass.NO_DERIVATIVES,
            KnowledgeSourceLicenseClass.REFERENCE_ONLY,
            -> KnowledgeSourceAdmissionRoute.HUMAN_REFERENCE_ONLY
            KnowledgeSourceLicenseClass.UNKNOWN,
            KnowledgeSourceLicenseClass.BLOCKED,
            -> KnowledgeSourceAdmissionRoute.BLOCKED
        }
    }

    fun requireCoreAutoAdmission(evidence: KnowledgeSourceAdmissionEvidence) {
        check(evaluate(evidence) == KnowledgeSourceAdmissionRoute.ALLOW_CORE_AUTO) {
            "Source is not eligible for automatic core-pack admission"
        }
    }
}

/** Trace record shared by formal knowledge nodes and source metadata records. */
data class KnowledgeArtifactTrace(
    val artifactId: String,
    val artifactVersion: String,
    val contentFingerprint: String,
    val reviewStatus: KnowledgeArtifactReviewStatus,
    val reviewRecordId: String?,
    val reviewedAtEpochMillis: Long?,
) {
    init {
        artifactId.requireGovernanceId("Artifact id")
        artifactVersion.requireGovernanceId("Artifact version")
        require(GOVERNANCE_SHA_256.matches(contentFingerprint)) {
            "Artifact content fingerprint must be lowercase SHA-256"
        }
        if (reviewStatus == KnowledgeArtifactReviewStatus.HUMAN_REVIEWED) {
            val humanReviewId = reviewRecordId
            val humanReviewedAt = reviewedAtEpochMillis
            require(!humanReviewId.isNullOrBlank() && humanReviewedAt != null) {
                "Human-reviewed artifacts require a review record and time"
            }
            humanReviewId.requireGovernanceId("Artifact review record id")
            require(humanReviewedAt >= 0L) {
                "Artifact review time must not be negative"
            }
        } else {
            require(reviewRecordId == null && reviewedAtEpochMillis == null) {
                "Only human-reviewed artifacts may carry a review record or time"
            }
        }
    }
}

enum class FormalKnowledgeCoverage {
    SKELETON_ONLY,
}

enum class FormalKnowledgeCompleteness {
    INCOMPLETE,
}

data class FormalHighSchoolSubjectSkeleton(
    val subject: SubjectKind,
    val officialStandardCode: String,
    val displayName: String,
    val rootNodeStableCode: String,
    val normalizedScopeDescription: String,
    val coverage: FormalKnowledgeCoverage,
    val completeness: FormalKnowledgeCompleteness,
    val declaredReviewedKnowledgeNodeCount: Int,
    val rootNodeTrace: KnowledgeArtifactTrace,
    val officialSource: KnowledgeSourceAdmissionEvidence,
) {
    init {
        require(subject != SubjectKind.GENERAL) { "A formal subject must be specific" }
        require(OFFICIAL_STANDARD_CODE.matches(officialStandardCode)) {
            "Official subject-standard code is invalid"
        }
        displayName.requireGovernanceText("Subject display name")
        rootNodeStableCode.requireGovernanceId("Subject root-node stable code")
        normalizedScopeDescription.requireGovernanceText("Subject scope description")
        require(declaredReviewedKnowledgeNodeCount == 0) {
            "The skeleton must not claim reviewed knowledge nodes"
        }
        require(rootNodeTrace.artifactId == rootNodeStableCode) {
            "Subject root-node trace must bind the root stable code"
        }
        require(rootNodeTrace.reviewStatus == KnowledgeArtifactReviewStatus.PENDING) {
            "The generated subject skeleton must remain pending human review"
        }
        require(
            KnowledgeSourceAdmissionPolicy.evaluate(officialSource) ==
                KnowledgeSourceAdmissionRoute.BLOCKED,
        ) {
            "The pending curriculum locator must fail closed"
        }
    }
}

/**
 * Honest nine-subject foundation for the future formal pack.
 *
 * It stores only original short scope labels and an official Ministry of Education notice
 * locator. It contains no copied curriculum body and deliberately declares zero reviewed
 * knowledge nodes, so it can never authorize production cutover.
 */
object FormalHighSchoolKnowledgeSkeletonManifest {
    const val MANIFEST_ID = "formal-high-school-skeleton-2026-v1"
    const val MANIFEST_VERSION = "2026-v1"
    const val OFFICIAL_NOTICE_TITLE =
        "教育部关于印发普通高中课程方案和语文等学科课程标准（2017年版2020年修订）的通知"
    const val OFFICIAL_NOTICE_LOCATOR =
        "https://www.moe.gov.cn/srcsite/A26/s8001/202006/t20200603_462199.html"
    const val PRODUCTION_CUTOVER_ELIGIBLE = false

    val subjects: List<FormalHighSchoolSubjectSkeleton> =
        Collections.unmodifiableList(
            listOf(
                subject(SubjectKind.CHINESE, "SB0101", "语文"),
                subject(SubjectKind.ENGLISH, "SB0102", "英语"),
                subject(SubjectKind.MATH, "SB0201", "数学"),
                subject(SubjectKind.HISTORY, "SB0307", "历史"),
                subject(SubjectKind.GEOGRAPHY, "SB0308", "地理"),
                subject(SubjectKind.POLITICS, "SB0310", "思想政治"),
                subject(SubjectKind.PHYSICS, "SB0401", "物理"),
                subject(SubjectKind.CHEMISTRY, "SB0402", "化学"),
                subject(SubjectKind.BIOLOGY, "SB0403", "生物学"),
            ),
        )

    val contentFingerprint: String =
        sha256(
            buildString {
                append(MANIFEST_ID).append('\u001f').append(MANIFEST_VERSION)
                subjects.forEach { entry ->
                    append('\u001e')
                    append(entry.subject.name).append('\u001f')
                    append(entry.officialStandardCode).append('\u001f')
                    append(entry.rootNodeTrace.contentFingerprint).append('\u001f')
                    append(entry.officialSource.sourceRecordFingerprint).append('\u001f')
                    append(entry.officialSource.canonicalLocator)
                }
            },
        )

    private fun subject(
        subject: SubjectKind,
        officialCode: String,
        displayName: String,
    ): FormalHighSchoolSubjectSkeleton {
        val rootCode = "moe.$officialCode.subject-root"
        val description = "用于组织高中${displayName}知识层级的根目录，不声明具体知识点已覆盖。"
        val sourceId = "moe.$officialCode.curriculum-standard"
        val evidenceLocator = "$OFFICIAL_NOTICE_LOCATOR#$officialCode"
        return FormalHighSchoolSubjectSkeleton(
            subject = subject,
            officialStandardCode = officialCode,
            displayName = displayName,
            rootNodeStableCode = rootCode,
            normalizedScopeDescription = description,
            coverage = FormalKnowledgeCoverage.SKELETON_ONLY,
            completeness = FormalKnowledgeCompleteness.INCOMPLETE,
            declaredReviewedKnowledgeNodeCount = 0,
            rootNodeTrace =
                KnowledgeArtifactTrace(
                    artifactId = rootCode,
                    artifactVersion = MANIFEST_VERSION,
                    contentFingerprint = sha256("$rootCode\u001f$description"),
                    reviewStatus = KnowledgeArtifactReviewStatus.PENDING,
                    reviewRecordId = null,
                    reviewedAtEpochMillis = null,
                ),
            officialSource =
                KnowledgeSourceAdmissionEvidence(
                    sourceId = sourceId,
                    sourceVersion = "2017-2020-revision",
                    sourceRecordFingerprint =
                        sha256("$sourceId\u001f$OFFICIAL_NOTICE_TITLE\u001f$evidenceLocator"),
                    canonicalLocator = OFFICIAL_NOTICE_LOCATOR,
                    publisher = "中华人民共和国教育部",
                    resourceTitle = OFFICIAL_NOTICE_TITLE,
                    licenseClass = KnowledgeSourceLicenseClass.UNKNOWN,
                    licenseExpression = null,
                    licenseEvidenceLocator = null,
                    licenseEvidenceFingerprint = null,
                    evidenceOrigin = KnowledgeSourceEvidenceOrigin.MODEL_DECLARATION,
                    automationPermission = KnowledgeSourceAutomationPermission.UNKNOWN,
                    reviewStatus = KnowledgeArtifactReviewStatus.PENDING,
                    reviewRecordId = null,
                    reviewedAtEpochMillis = null,
                ),
        )
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.requireGovernanceId(label: String) {
    require(isNotBlank() && length <= 160 && none(Char::isISOControl)) { "$label is invalid" }
}

private fun String.requireGovernanceText(label: String) {
    require(
        isNotBlank() &&
            length <= 4_096 &&
            none { character ->
                character.isISOControl() && character != '\n' && character != '\t'
            },
    ) {
        "$label is invalid"
    }
}

private val GOVERNANCE_SHA_256 = Regex("[0-9a-f]{64}")
private val OFFICIAL_STANDARD_CODE = Regex("SB\\d{4}")
