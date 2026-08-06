"""Minimal compile-only Kotlin API used to parse generated release sources."""

MODEL_STUB = r"""
package com.tingyun.smartmistakebook.core.model

enum class SubjectKind {
    CHINESE, MATH, ENGLISH, PHYSICS, CHEMISTRY, BIOLOGY, POLITICS, HISTORY, GEOGRAPHY, GENERAL
}
enum class KnowledgeMaterialDerivationKind { REVIEWED_SYNTHESIS }
enum class KnowledgeMaterialNodeRole { PRIMARY, SUPPORTING, PREREQUISITE }
enum class KnowledgeNodeGranularity { TOPIC, ATOMIC }
enum class KnowledgeNodeKind {
    TOPIC, CONCEPT, PROCEDURE, REASONING, REPRESENTATION, EXPERIMENT, EXPRESSION
}
enum class KnowledgeNodeVerificationStatus { CURATED, SOURCE_GROUNDED }
enum class KnowledgeSourceContentUsePolicy {
    REVIEWED_SYNTHESIS_ONLY, EXCERPT_ALLOWED, ADAPTATION_ALLOWED
}
enum class KnowledgeSourceLicenseStatus { PUBLIC_OFFICIAL, LICENSED, REFERENCE_ONLY }
enum class KnowledgeSourceType {
    OFFICIAL_CURRICULUM_STANDARD, TEXTBOOK, AUTHORIZED_EDUCATION_MATERIAL, MANUAL_RESEARCH
}
enum class KnowledgeTeachingMaterialType {
    CONCEPT_EXPLANATION, METHOD_MODEL, WORKED_EXAMPLE, COMPLETE_SOLUTION,
    DERIVATION, MISCONCEPTION_GUIDE, REPRESENTATION_GUIDE
}
"""


DATABASE_STUB = r"""
package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.*

data class ReviewedKnowledgePackMetadata(
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val searchIndexVersion: String,
    val builtAtEpochMillis: Long,
)
data class ReviewedKnowledgeNode(
    val knowledgeNodeId: String,
    val stableCode: String,
    val subject: SubjectKind,
    val displayName: String,
    val canonicalName: String,
    val kind: KnowledgeNodeKind,
    val granularity: KnowledgeNodeGranularity,
    val aliases: List<String>,
    val boundaryMarkdown: String?,
    val verificationStatus: KnowledgeNodeVerificationStatus,
    val parentKnowledgeNodeId: String?,
    val reviewedAtEpochMillis: Long,
)
data class ReviewedKnowledgeSource(
    val sourceId: String,
    val subject: SubjectKind,
    val sourceType: KnowledgeSourceType,
    val title: String,
    val publisher: String?,
    val edition: String?,
    val sourceUri: String?,
    val licenseStatus: KnowledgeSourceLicenseStatus,
    val contentUsePolicy: KnowledgeSourceContentUsePolicy,
    val contentFingerprint: String,
    val licenseExpression: String?,
    val licenseUri: String?,
    val attributionText: String?,
    val reviewedAtEpochMillis: Long,
)
data class ReviewedKnowledgeNodeSourceBinding(
    val knowledgeNodeId: String,
    val sourceId: String,
    val sourceLocator: String,
    val derivationNote: String,
    val reviewedAtEpochMillis: Long,
)
data class ReviewedKnowledgeRelation(
    val relationId: String,
    val subject: SubjectKind,
    val fromKnowledgeNodeId: String,
    val toKnowledgeNodeId: String,
    val relationType: String,
    val sourceId: String,
    val sourceLocator: String,
    val reviewedAtEpochMillis: Long,
)
data class ReviewedKnowledgeTeachingMaterial(
    val materialId: String,
    val stableCode: String,
    val subject: SubjectKind,
    val materialType: KnowledgeTeachingMaterialType,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val derivationKind: KnowledgeMaterialDerivationKind,
    val sourceId: String,
    val sourceLocator: String,
    val contentFingerprint: String,
    val reviewedAtEpochMillis: Long,
)
data class ReviewedKnowledgeTeachingMaterialBinding(
    val materialId: String,
    val knowledgeNodeId: String,
    val role: KnowledgeMaterialNodeRole,
)
class ReviewedKnowledgePack(
    val metadata: ReviewedKnowledgePackMetadata,
    val nodes: List<ReviewedKnowledgeNode>,
    val sources: List<ReviewedKnowledgeSource>,
    val nodeSourceBindings: List<ReviewedKnowledgeNodeSourceBinding>,
    val relations: List<ReviewedKnowledgeRelation>,
    val teachingMaterials: List<ReviewedKnowledgeTeachingMaterial>,
    val teachingMaterialBindings: List<ReviewedKnowledgeTeachingMaterialBinding>,
)

enum class KnowledgeSourceLicenseClass {
    PERMISSIVE, PUBLIC_OFFICIAL_METADATA, NON_COMMERCIAL, NO_DERIVATIVES, REFERENCE_ONLY
}
enum class KnowledgeSourceEvidenceOrigin { HUMAN_VERIFIED }
enum class KnowledgeSourceAutomationPermission { ALLOWED, METADATA_ONLY }
enum class KnowledgeArtifactReviewStatus { HUMAN_REVIEWED }
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
)
data class FormalKnowledgeSourceProvenance(
    val admissionEvidence: KnowledgeSourceAdmissionEvidence,
    val sourceContentFingerprint: String,
    val retrievedAtEpochMillis: Long,
    val editionEvidenceLocator: String?,
    val editionEvidenceFingerprint: String?,
)
data class FormalKnowledgeSubjectMapping(
    val subject: SubjectKind,
    val officialStandardCode: String,
    val rootNodeStableCode: String,
    val stableCodeNamespace: String,
    val curriculumSourceId: String,
    val textbookEditionSourceIds: List<String>,
)
data class FormalKnowledgeCoverageSummary(
    val itemCount: Int,
    val itemFingerprint: String,
)
data class FormalKnowledgeSubjectCoverageProofV2(
    val subject: SubjectKind,
    val expectedModuleStableCodes: List<String>,
    val expectedKnowledgePointStableCodes: List<String>,
    val relationCoverage: FormalKnowledgeCoverageSummary,
    val methodCoverage: FormalKnowledgeCoverageSummary,
    val workedExampleCoverage: FormalKnowledgeCoverageSummary,
    val textbookMappingCoverage: FormalKnowledgeCoverageSummary,
)
data class FormalKnowledgePackCoverageProofV2(
    val coverageLedgerId: String,
    val targetBaselineId: String,
    val coverageLedgerFingerprint: String,
    val coverageReviewRecordId: String,
    val coverageReviewedAtEpochMillis: Long,
    val humanReviewSummaryFingerprint: String,
    val sourceLicenseReviewSummaryFingerprint: String,
    val subjects: List<FormalKnowledgeSubjectCoverageProofV2>,
)
data class FormalKnowledgePackCandidate(
    val candidateId: String,
    val producerOrganizationId: String,
    val preparedAtEpochMillis: Long,
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val searchIndexVersion: String,
    val contentFingerprint: String,
    val coverageFingerprint: String,
    val sourceProvenance: List<FormalKnowledgeSourceProvenance>,
    val subjectMappings: List<FormalKnowledgeSubjectMapping>,
    val coverageProofV2: FormalKnowledgePackCoverageProofV2?,
)
enum class FormalKnowledgePackReviewDecision { APPROVED }
enum class FormalKnowledgeContentPolicyAttestation {
    ORIGINAL_OR_REVIEWED_SYNTHESIS_WITHOUT_QUESTION_BANK
}
data class FormalKnowledgePackIndependentReview(
    val reviewRecordId: String,
    val candidateGovernanceFingerprint: String,
    val candidateContentFingerprint: String,
    val producerOrganizationId: String,
    val reviewerOrganizationId: String,
    val reviewedAtEpochMillis: Long,
    val decision: FormalKnowledgePackReviewDecision,
    val contentPolicyAttestation: FormalKnowledgeContentPolicyAttestation,
    val coverageFingerprint: String,
    val coverageProofFingerprint: String?,
)
enum class FormalKnowledgeSignatureAlgorithm { SHA256_WITH_RSA_2048 }
data class FormalKnowledgePackSigningKey(
    val keyId: String,
    val algorithm: FormalKnowledgeSignatureAlgorithm,
    val x509PublicKeyHex: String,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val revokedAtEpochMillis: Long?,
    val revocationEvidenceFingerprint: String?,
)
data class SignedFormalKnowledgePackActivation(
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val searchIndexVersion: String,
    val contentFingerprint: String,
    val candidateGovernanceFingerprint: String,
    val independentReviewFingerprint: String,
    val activationGeneration: Long,
    val signedAtEpochMillis: Long,
    val signingKeyId: String,
    val algorithm: FormalKnowledgeSignatureAlgorithm,
    val signatureHex: String,
    val coverageProofFingerprint: String?,
)
class FormalKnowledgePackActivationProof(
    val candidate: FormalKnowledgePackCandidate,
    val independentReview: FormalKnowledgePackIndependentReview,
    val signedActivation: SignedFormalKnowledgePackActivation,
)
enum class BuiltInKnowledgePackPurpose { FORMAL_HIGH_SCHOOL_RELEASE }
data class BuildVariantTrustedKnowledgePackDefinition(
    val pack: ReviewedKnowledgePack,
    val generation: Long,
    val productionCutoverEligible: Boolean,
    val purpose: BuiltInKnowledgePackPurpose,
    val formalActivationProof: FormalKnowledgePackActivationProof?,
)
"""


RUNTIME_DATABASE_STUB = (
    DATABASE_STUB.split("enum class KnowledgeSourceLicenseClass", maxsplit=1)[0]
    + r"""
object KnowledgeSearchNormalizer {
    fun normalizeFeature(value: String): String = value.trim().lowercase()
}
"""
)
