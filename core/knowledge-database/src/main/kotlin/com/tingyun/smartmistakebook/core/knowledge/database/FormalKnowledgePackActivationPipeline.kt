package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAKey
import java.security.spec.X509EncodedKeySpec
import java.util.Collections

/**
 * Build-time provenance captured before a source can support a formal release.
 *
 * Candidate records never enter [HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME]. Only a complete, reviewed,
 * signed pack may be installed in that runtime database.
 */
data class FormalKnowledgeSourceProvenance(
    val admissionEvidence: KnowledgeSourceAdmissionEvidence,
    val sourceContentFingerprint: String,
    val retrievedAtEpochMillis: Long,
    val editionEvidenceLocator: String?,
    val editionEvidenceFingerprint: String?,
) {
    init {
        require(FORMAL_SHA_256.matches(sourceContentFingerprint)) {
            "Source content fingerprint must be lowercase SHA-256"
        }
        require(retrievedAtEpochMillis >= 0L) {
            "Source retrieval time must not be negative"
        }
        editionEvidenceLocator?.requireFormalText("Edition evidence locator")
        require(
            editionEvidenceFingerprint == null ||
                FORMAL_SHA_256.matches(editionEvidenceFingerprint),
        ) {
            "Edition evidence fingerprint must be lowercase SHA-256"
        }
    }
}

/**
 * One subject's reviewed curriculum and textbook coordinate system.
 *
 * Stable-code namespaces deliberately do not contain a pack version: versioned pack releases must
 * preserve durable knowledge identities.
 */
data class FormalKnowledgeSubjectMapping(
    val subject: SubjectKind,
    val officialStandardCode: String,
    val rootNodeStableCode: String,
    val stableCodeNamespace: String,
    val curriculumSourceId: String,
    val textbookEditionSourceIds: List<String>,
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Formal knowledge mappings require a specific subject"
        }
        require(FORMAL_STANDARD_CODE.matches(officialStandardCode)) {
            "Official subject-standard code is invalid"
        }
        rootNodeStableCode.requireFormalId("Root stable code")
        stableCodeNamespace.requireFormalId("Stable-code namespace")
        require(
            stableCodeNamespace.endsWith('.') ||
                stableCodeNamespace.endsWith(':'),
        ) {
            "Stable-code namespace must end with a supported separator"
        }
        curriculumSourceId.requireFormalId("Curriculum source id")
        require(
            textbookEditionSourceIds.isNotEmpty() &&
                textbookEditionSourceIds.distinct().size == textbookEditionSourceIds.size,
        ) {
            "Every subject requires distinct textbook-edition mappings"
        }
        textbookEditionSourceIds.forEach { sourceId ->
            sourceId.requireFormalId("Textbook-edition source id")
        }
    }
}

/**
 * Deterministic summary of one reviewed coverage surface.
 *
 * The count is useful for diagnostics, while the fingerprint commits the exact sorted records.
 * A zero count is representable so a candidate can be inspected, but the formal release gate
 * rejects zero relation, method, example, or textbook-mapping coverage.
 */
data class FormalKnowledgeCoverageSummary(
    val itemCount: Int,
    val itemFingerprint: String,
) {
    init {
        require(itemCount >= 0) { "Coverage-summary item count must not be negative" }
        require(FORMAL_SHA_256.matches(itemFingerprint)) {
            "Coverage-summary fingerprint must be lowercase SHA-256"
        }
    }
}

/**
 * The reviewed ledger expectation for one subject.
 *
 * Stable-code sets are expectations from the reviewed ledger, not counts declared by the pack.
 * Runtime validation requires them to equal the actual topic/atomic stable-code sets.
 */
data class FormalKnowledgeSubjectCoverageProofV2(
    val subject: SubjectKind,
    val expectedModuleStableCodes: List<String>,
    val expectedKnowledgePointStableCodes: List<String>,
    val relationCoverage: FormalKnowledgeCoverageSummary,
    val methodCoverage: FormalKnowledgeCoverageSummary,
    val workedExampleCoverage: FormalKnowledgeCoverageSummary,
    val textbookMappingCoverage: FormalKnowledgeCoverageSummary,
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Formal coverage requires a specific subject"
        }
        require(
            expectedModuleStableCodes.isNotEmpty() &&
                expectedModuleStableCodes.distinct().size == expectedModuleStableCodes.size,
        ) {
            "Formal coverage requires distinct reviewed curriculum modules"
        }
        require(
            expectedKnowledgePointStableCodes.isNotEmpty() &&
                expectedKnowledgePointStableCodes.distinct().size ==
                expectedKnowledgePointStableCodes.size,
        ) {
            "Formal coverage requires distinct reviewed knowledge points"
        }
        expectedModuleStableCodes.forEach { stableCode ->
            stableCode.requireFormalId("Reviewed curriculum-module stable code")
        }
        expectedKnowledgePointStableCodes.forEach { stableCode ->
            stableCode.requireFormalId("Reviewed knowledge-point stable code")
        }
        require(
            expectedModuleStableCodes.toSet()
                .intersect(expectedKnowledgePointStableCodes.toSet())
                .isEmpty(),
        ) {
            "Curriculum modules and atomic knowledge points require distinct stable codes"
        }
    }

    internal fun immutableCopy(): FormalKnowledgeSubjectCoverageProofV2 =
        copy(
            expectedModuleStableCodes =
                Collections.unmodifiableList(expectedModuleStableCodes.sorted()),
            expectedKnowledgePointStableCodes =
                Collections.unmodifiableList(expectedKnowledgePointStableCodes.sorted()),
        )

    internal fun appendCanonical(writer: FormalCanonicalWriter) {
        writer.field(subject.name)
        expectedModuleStableCodes.sorted().forEach(writer::field)
        expectedKnowledgePointStableCodes.sorted().forEach(writer::field)
        writer.appendCoverageSummary(relationCoverage)
        writer.appendCoverageSummary(methodCoverage)
        writer.appendCoverageSummary(workedExampleCoverage)
        writer.appendCoverageSummary(textbookMappingCoverage)
    }
}

/**
 * Versioned, reviewed coverage ledger proof for a formal release.
 *
 * The proof binds the complete expected subject scope, exact runtime coverage summaries, reviewed
 * ledger identity, human-review evidence, and source-license review. It carries no learner,
 * mistake, question-bank, or model-generated content authority.
 */
data class FormalKnowledgePackCoverageProofV2(
    val coverageLedgerId: String,
    val targetBaselineId: String,
    val coverageLedgerFingerprint: String,
    val coverageReviewRecordId: String,
    val coverageReviewedAtEpochMillis: Long,
    val humanReviewSummaryFingerprint: String,
    val sourceLicenseReviewSummaryFingerprint: String,
    val subjects: List<FormalKnowledgeSubjectCoverageProofV2>,
) {
    val schemaVersion: Int = SCHEMA_VERSION

    init {
        coverageLedgerId.requireFormalId("Coverage-ledger id")
        targetBaselineId.requireFormalId("Coverage target-baseline id")
        require(FORMAL_SHA_256.matches(coverageLedgerFingerprint)) {
            "Coverage-ledger fingerprint must be lowercase SHA-256"
        }
        coverageReviewRecordId.requireFormalId("Coverage review-record id")
        require(coverageReviewedAtEpochMillis >= 0L) {
            "Coverage review time must not be negative"
        }
        require(FORMAL_SHA_256.matches(humanReviewSummaryFingerprint)) {
            "Human-review summary fingerprint must be lowercase SHA-256"
        }
        require(FORMAL_SHA_256.matches(sourceLicenseReviewSummaryFingerprint)) {
            "Source-license review summary fingerprint must be lowercase SHA-256"
        }
        require(
            subjects.map(FormalKnowledgeSubjectCoverageProofV2::subject).toSet() ==
                formalSubjects().toSet() &&
                subjects.distinctBy(FormalKnowledgeSubjectCoverageProofV2::subject).size ==
                subjects.size,
        ) {
            "Coverage proof v2 requires exactly one reviewed ledger entry for all nine subjects"
        }
    }

    val proofFingerprint: String
        get() =
            formalSha256(
                canonicalFormalText {
                    field(PROOF_DOMAIN)
                    field(schemaVersion)
                    field(coverageLedgerId)
                    field(targetBaselineId)
                    field(coverageLedgerFingerprint)
                    field(coverageReviewRecordId)
                    field(coverageReviewedAtEpochMillis)
                    field(humanReviewSummaryFingerprint)
                    field(sourceLicenseReviewSummaryFingerprint)
                    subjects.sortedBy { subject -> subject.subject.name }.forEach { subject ->
                        subject.appendCanonical(this)
                    }
                },
            )

    internal fun immutableCopy(): FormalKnowledgePackCoverageProofV2 =
        copy(
            subjects =
                Collections.unmodifiableList(
                    subjects
                        .map(FormalKnowledgeSubjectCoverageProofV2::immutableCopy)
                        .sortedBy { subject -> subject.subject.name },
                ),
        )

    companion object {
        const val SCHEMA_VERSION = 2
        private const val PROOF_DOMAIN = "formal-knowledge-coverage-proof-v2"
    }
}

/**
 * Immutable candidate-stage manifest. It binds provenance and mappings to the complete canonical
 * pack digest, but it is not an activation capability.
 */
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
    val coverageProofV2: FormalKnowledgePackCoverageProofV2? = null,
) {
    init {
        candidateId.requireFormalId("Candidate id")
        producerOrganizationId.requireFormalId("Producer organization id")
        require(preparedAtEpochMillis >= 0L) {
            "Candidate preparation time must not be negative"
        }
        packId.requireFormalId("Candidate pack id")
        knowledgePackVersion.requireFormalId("Candidate pack version")
        taxonomyVersion.requireFormalId("Candidate taxonomy version")
        searchIndexVersion.requireFormalId("Candidate search-index version")
        require(FORMAL_SHA_256.matches(contentFingerprint)) {
            "Candidate content fingerprint must be lowercase SHA-256"
        }
        require(FORMAL_SHA_256.matches(coverageFingerprint)) {
            "Candidate coverage fingerprint must be lowercase SHA-256"
        }
        require(
            sourceProvenance.isNotEmpty() &&
                sourceProvenance
                    .map { provenance -> provenance.admissionEvidence.sourceId }
                    .distinct()
                    .size == sourceProvenance.size,
        ) {
            "Candidate source provenance must contain distinct sources"
        }
        require(
            subjectMappings.isNotEmpty() &&
                subjectMappings.map(FormalKnowledgeSubjectMapping::subject).distinct().size ==
                subjectMappings.size,
        ) {
            "Candidate subject mappings must be distinct"
        }
    }

    val governanceFingerprint: String
        get() = formalSha256(canonicalGovernanceText())

    internal fun immutableCopy(): FormalKnowledgePackCandidate =
        copy(
            sourceProvenance =
                Collections.unmodifiableList(
                    sourceProvenance
                        .map(FormalKnowledgeSourceProvenance::copy)
                        .sortedBy { provenance -> provenance.admissionEvidence.sourceId },
                ),
            subjectMappings =
                Collections.unmodifiableList(
                    subjectMappings
                        .map { mapping ->
                            mapping.copy(
                                textbookEditionSourceIds =
                                    Collections.unmodifiableList(
                                        mapping.textbookEditionSourceIds.sorted(),
                                    ),
                            )
                        }
                        .sortedBy { mapping -> mapping.subject.name },
                ),
            coverageProofV2 = coverageProofV2?.immutableCopy(),
        )

    private fun canonicalGovernanceText(): String =
        canonicalFormalText {
            field(candidateId)
            field(producerOrganizationId)
            field(preparedAtEpochMillis)
            field(packId)
            field(knowledgePackVersion)
            field(taxonomyVersion)
            field(searchIndexVersion)
            field(contentFingerprint)
            field(coverageFingerprint)
            sourceProvenance
                .sortedBy { provenance -> provenance.admissionEvidence.sourceId }
                .forEach { provenance ->
                    val evidence = provenance.admissionEvidence
                    field(evidence.sourceId)
                    field(evidence.sourceVersion)
                    field(evidence.sourceRecordFingerprint)
                    field(evidence.canonicalLocator)
                    field(evidence.publisher)
                    field(evidence.resourceTitle)
                    field(evidence.licenseClass.name)
                    field(evidence.licenseExpression)
                    field(evidence.licenseEvidenceLocator)
                    field(evidence.licenseEvidenceFingerprint)
                    field(evidence.evidenceOrigin.name)
                    field(evidence.automationPermission.name)
                    field(evidence.reviewStatus.name)
                    field(evidence.reviewRecordId)
                    field(evidence.reviewedAtEpochMillis)
                    field(provenance.sourceContentFingerprint)
                    field(provenance.retrievedAtEpochMillis)
                    field(provenance.editionEvidenceLocator)
                    field(provenance.editionEvidenceFingerprint)
                }
            subjectMappings.sortedBy { mapping -> mapping.subject.name }.forEach { mapping ->
                field(mapping.subject.name)
                field(mapping.officialStandardCode)
                field(mapping.rootNodeStableCode)
                field(mapping.stableCodeNamespace)
                field(mapping.curriculumSourceId)
                mapping.textbookEditionSourceIds.sorted().forEach(::field)
            }
            coverageProofV2?.let { coverageProof ->
                field("coverage-proof-v2")
                field(coverageProof.proofFingerprint)
            }
        }
}

enum class FormalKnowledgePackReviewDecision {
    APPROVED,
    REJECTED,
}

enum class FormalKnowledgeContentPolicyAttestation {
    ORIGINAL_OR_REVIEWED_SYNTHESIS_WITHOUT_QUESTION_BANK,
    THIRD_PARTY_QUESTION_BANK_CONTENT_PRESENT,
    UNKNOWN,
}

/**
 * Independent second-stage review. The review digest is included in the later signature.
 */
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
    val coverageProofFingerprint: String? = null,
) {
    init {
        reviewRecordId.requireFormalId("Independent review record id")
        require(FORMAL_SHA_256.matches(candidateGovernanceFingerprint)) {
            "Candidate governance fingerprint must be lowercase SHA-256"
        }
        require(FORMAL_SHA_256.matches(candidateContentFingerprint)) {
            "Candidate content fingerprint must be lowercase SHA-256"
        }
        producerOrganizationId.requireFormalId("Producer organization id")
        reviewerOrganizationId.requireFormalId("Reviewer organization id")
        require(reviewedAtEpochMillis >= 0L) {
            "Independent review time must not be negative"
        }
        require(FORMAL_SHA_256.matches(coverageFingerprint)) {
            "Review coverage fingerprint must be lowercase SHA-256"
        }
        require(
            coverageProofFingerprint == null ||
                FORMAL_SHA_256.matches(coverageProofFingerprint),
        ) {
            "Review coverage-proof fingerprint must be lowercase SHA-256"
        }
    }

    val reviewFingerprint: String
        get() =
            formalSha256(
                canonicalFormalText {
                    field(reviewRecordId)
                    field(candidateGovernanceFingerprint)
                    field(candidateContentFingerprint)
                    field(producerOrganizationId)
                    field(reviewerOrganizationId)
                    field(reviewedAtEpochMillis)
                    field(decision.name)
                    field(contentPolicyAttestation.name)
                    field(coverageFingerprint)
                    coverageProofFingerprint?.let { fingerprint ->
                        field("coverage-proof-v2")
                        field(fingerprint)
                    }
                },
            )
}

enum class FormalKnowledgeSignatureAlgorithm(
    val jcaName: String,
) {
    SHA256_WITH_RSA_2048("SHA256withRSA"),
}

/** Offline-generated compile-time trust root; the checked-in release default contains no keys. */
data class FormalKnowledgePackSigningKey(
    val keyId: String,
    val algorithm: FormalKnowledgeSignatureAlgorithm,
    val x509PublicKeyHex: String,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
    val revocationEvidenceFingerprint: String? = null,
) {
    init {
        keyId.requireFormalId("Signing key id")
        require(
            x509PublicKeyHex.length % 2 == 0 &&
                FORMAL_HEX.matches(x509PublicKeyHex),
        ) {
            "Signing public key must be lowercase hexadecimal X.509 data"
        }
        require(validFromEpochMillis >= 0L && validUntilEpochMillis >= validFromEpochMillis) {
            "Signing-key validity interval is invalid"
        }
        require(
            (revokedAtEpochMillis == null) ==
                (revocationEvidenceFingerprint == null),
        ) {
            "Signing-key revocation requires both time and reviewed evidence"
        }
        require(revokedAtEpochMillis == null || revokedAtEpochMillis >= 0L) {
            "Signing-key revocation time must not be negative"
        }
        require(
            revocationEvidenceFingerprint == null ||
                FORMAL_SHA_256.matches(revocationEvidenceFingerprint),
        ) {
            "Signing-key revocation evidence must be lowercase SHA-256"
        }
    }
}

/**
 * Final-stage signature. It has no private-key operation and cannot create trust at runtime.
 */
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
    val coverageProofFingerprint: String? = null,
) {
    init {
        packId.requireFormalId("Signed pack id")
        knowledgePackVersion.requireFormalId("Signed pack version")
        taxonomyVersion.requireFormalId("Signed taxonomy version")
        searchIndexVersion.requireFormalId("Signed search-index version")
        require(FORMAL_SHA_256.matches(contentFingerprint)) {
            "Signed content fingerprint must be lowercase SHA-256"
        }
        require(FORMAL_SHA_256.matches(candidateGovernanceFingerprint)) {
            "Signed candidate fingerprint must be lowercase SHA-256"
        }
        require(FORMAL_SHA_256.matches(independentReviewFingerprint)) {
            "Signed review fingerprint must be lowercase SHA-256"
        }
        require(activationGeneration > 0L) {
            "Signed activation generation must be positive"
        }
        require(signedAtEpochMillis >= 0L) {
            "Signature time must not be negative"
        }
        signingKeyId.requireFormalId("Signing key id")
        require(signatureHex.length % 2 == 0 && FORMAL_HEX.matches(signatureHex)) {
            "Activation signature must be lowercase hexadecimal"
        }
        require(
            coverageProofFingerprint == null ||
                FORMAL_SHA_256.matches(coverageProofFingerprint),
        ) {
            "Signed coverage-proof fingerprint must be lowercase SHA-256"
        }
    }

    internal fun canonicalPayload(): ByteArray =
        canonicalFormalText {
            field(packId)
            field(knowledgePackVersion)
            field(taxonomyVersion)
            field(searchIndexVersion)
            field(contentFingerprint)
            field(candidateGovernanceFingerprint)
            field(independentReviewFingerprint)
            field(activationGeneration)
            field(signedAtEpochMillis)
            field(signingKeyId)
            field(algorithm.name)
            coverageProofFingerprint?.let { fingerprint ->
                field("coverage-proof-v2")
                field(fingerprint)
            }
        }.toByteArray(StandardCharsets.UTF_8)
}

class FormalKnowledgePackActivationProof(
    candidate: FormalKnowledgePackCandidate,
    val independentReview: FormalKnowledgePackIndependentReview,
    val signedActivation: SignedFormalKnowledgePackActivation,
) {
    val candidate: FormalKnowledgePackCandidate = candidate.immutableCopy()
}

/**
 * Formal-release gate. It validates actual pack content rather than trusting declared counts.
 */
internal object FormalKnowledgePackActivationPolicy {
    fun coverageFingerprint(pack: ReviewedKnowledgePack): String {
        val specificSubjects = formalSubjects()
        return formalSha256(
            canonicalFormalText {
                specificSubjects.forEach { subject ->
                    val subjectNodes = pack.nodes.filter { node -> node.subject == subject }
                    val subjectRelations =
                        pack.relations.filter { relation -> relation.subject == subject }
                    val subjectMaterials =
                        pack.teachingMaterials.filter { material -> material.subject == subject }
                    field(subject.name)
                    field(subjectNodes.count { node -> node.kind == KnowledgeNodeKind.TOPIC })
                    field(
                        subjectNodes.count { node ->
                            node.granularity == KnowledgeNodeGranularity.ATOMIC
                        },
                    )
                    field(subjectNodes.sumOf { node -> node.aliases.size })
                    field(subjectRelations.size)
                    field(
                        subjectMaterials.count { material ->
                            material.materialType == KnowledgeTeachingMaterialType.METHOD_MODEL
                        },
                    )
                    field(
                        subjectMaterials.count { material ->
                            material.materialType == KnowledgeTeachingMaterialType.WORKED_EXAMPLE
                        },
                    )
                    subjectNodes.sortedBy { node -> node.stableCode }.forEach { node ->
                        field(node.stableCode)
                        node.aliases.sorted().forEach(::field)
                    }
                    subjectRelations.sortedBy { relation -> relation.relationId }.forEach {
                        relation ->
                        field(relation.relationId)
                        field(relation.relationType)
                    }
                    subjectMaterials.sortedBy { material -> material.stableCode }.forEach {
                        material ->
                        field(material.stableCode)
                        field(material.materialType.name)
                    }
                }
            },
        )
    }

    /**
     * Canonical fingerprint of the reviewed runtime content before Room derives its local search
     * rows. The offline compiler can reproduce this without Android or a private signing key.
     */
    fun reviewedContentFingerprint(pack: ReviewedKnowledgePack): String =
        formalSha256(
            canonicalFormalText {
                field(REVIEWED_CONTENT_DOMAIN)
                pack.metadata.let { metadata ->
                    field(metadata.packId)
                    field(metadata.knowledgePackVersion)
                    field(metadata.taxonomyVersion)
                    field(metadata.searchIndexVersion)
                    field(metadata.builtAtEpochMillis)
                }
                pack.nodes.sortedBy(ReviewedKnowledgeNode::knowledgeNodeId).forEach { node ->
                    field(node.knowledgeNodeId)
                    field(node.stableCode)
                    field(node.subject.name)
                    field(node.displayName)
                    field(node.canonicalName)
                    field(node.kind.name)
                    field(node.granularity.name)
                    node.aliases.sorted().forEach(::field)
                    field(node.boundaryMarkdown)
                    field(node.verificationStatus.name)
                    field(node.parentKnowledgeNodeId)
                    field(node.reviewedAtEpochMillis)
                }
                pack.sources.sortedBy(ReviewedKnowledgeSource::sourceId).forEach { source ->
                    field(source.sourceId)
                    field(source.subject.name)
                    field(source.sourceType.name)
                    field(source.title)
                    field(source.publisher)
                    field(source.edition)
                    field(source.sourceUri)
                    field(source.licenseStatus.name)
                    field(source.contentUsePolicy.name)
                    field(source.contentFingerprint)
                    field(source.licenseExpression)
                    field(source.licenseUri)
                    field(source.attributionText)
                    field(source.reviewedAtEpochMillis)
                }
                pack.nodeSourceBindings
                    .sortedWith(
                        compareBy(
                            ReviewedKnowledgeNodeSourceBinding::knowledgeNodeId,
                            ReviewedKnowledgeNodeSourceBinding::sourceId,
                            ReviewedKnowledgeNodeSourceBinding::sourceLocator,
                        ),
                    ).forEach { binding ->
                        field(binding.knowledgeNodeId)
                        field(binding.sourceId)
                        field(binding.sourceLocator)
                        field(binding.derivationNote)
                        field(binding.reviewedAtEpochMillis)
                    }
                pack.relations.sortedBy(ReviewedKnowledgeRelation::relationId).forEach { relation ->
                    field(relation.relationId)
                    field(relation.subject.name)
                    field(relation.fromKnowledgeNodeId)
                    field(relation.toKnowledgeNodeId)
                    field(relation.relationType)
                    field(relation.sourceId)
                    field(relation.sourceLocator)
                    field(relation.reviewedAtEpochMillis)
                }
                pack.teachingMaterials
                    .sortedBy(ReviewedKnowledgeTeachingMaterial::materialId)
                    .forEach { material ->
                        field(material.materialId)
                        field(material.stableCode)
                        field(material.subject.name)
                        field(material.materialType.name)
                        field(material.title)
                        field(material.summaryMarkdown)
                        field(material.applicabilityMarkdown)
                        field(material.contentMarkdown)
                        field(material.boundaryMarkdown)
                        field(material.derivationKind.name)
                        field(material.sourceId)
                        field(material.sourceLocator)
                        field(material.contentFingerprint)
                        field(material.reviewedAtEpochMillis)
                    }
                pack.teachingMaterialBindings
                    .sortedWith(
                        compareBy(
                            ReviewedKnowledgeTeachingMaterialBinding::materialId,
                            ReviewedKnowledgeTeachingMaterialBinding::knowledgeNodeId,
                        ),
                    ).forEach { binding ->
                        field(binding.materialId)
                        field(binding.knowledgeNodeId)
                        field(binding.role.name)
                    }
            },
        )

    fun sourceLicenseReviewSummaryFingerprint(
        sourceProvenance: Collection<FormalKnowledgeSourceProvenance>,
    ): String =
        formalSha256(
            canonicalFormalText {
                field(SOURCE_LICENSE_REVIEW_DOMAIN)
                sourceProvenance
                    .sortedBy { provenance -> provenance.admissionEvidence.sourceId }
                    .forEach { provenance ->
                        val evidence = provenance.admissionEvidence
                        field(evidence.sourceId)
                        field(evidence.sourceVersion)
                        field(evidence.sourceRecordFingerprint)
                        field(evidence.canonicalLocator)
                        field(evidence.publisher)
                        field(evidence.resourceTitle)
                        field(evidence.licenseClass.name)
                        field(evidence.licenseExpression)
                        field(evidence.licenseEvidenceLocator)
                        field(evidence.licenseEvidenceFingerprint)
                        field(evidence.evidenceOrigin.name)
                        field(evidence.automationPermission.name)
                        field(evidence.reviewStatus.name)
                        field(evidence.reviewRecordId)
                        field(evidence.reviewedAtEpochMillis)
                        field(provenance.sourceContentFingerprint)
                        field(provenance.retrievedAtEpochMillis)
                        field(provenance.editionEvidenceLocator)
                        field(provenance.editionEvidenceFingerprint)
                    }
            },
        )

    /**
     * Deterministically derives the runtime-facing part of a reviewed ledger proof.
     *
     * This helper creates no authority. Only an independent review and a trusted offline signature
     * can make the resulting proof activatable.
     */
    fun deriveCoverageProofV2(
        pack: ReviewedKnowledgePack,
        coverageLedgerId: String,
        targetBaselineId: String,
        coverageLedgerFingerprint: String,
        coverageReviewRecordId: String,
        coverageReviewedAtEpochMillis: Long,
        humanReviewSummaryFingerprint: String,
        sourceLicenseReviewSummaryFingerprint: String,
    ): FormalKnowledgePackCoverageProofV2 {
        val nodesById = pack.nodes.associateBy(ReviewedKnowledgeNode::knowledgeNodeId)
        val textbookSourceIds =
            pack.sources
                .filter { source -> source.sourceType == KnowledgeSourceType.TEXTBOOK }
                .mapTo(mutableSetOf(), ReviewedKnowledgeSource::sourceId)
        val materialBindingsById =
            pack.teachingMaterialBindings.groupBy(
                ReviewedKnowledgeTeachingMaterialBinding::materialId,
            )
        val subjects =
            formalSubjects().map { subject ->
                val subjectNodes = pack.nodes.filter { node -> node.subject == subject }
                val relationRecords =
                    pack.relations
                        .filter { relation -> relation.subject == subject }
                        .map { relation ->
                            listOf(
                                relation.relationId,
                                nodesById[relation.fromKnowledgeNodeId]?.stableCode,
                                nodesById[relation.toKnowledgeNodeId]?.stableCode,
                                relation.relationType,
                                relation.sourceId,
                                relation.sourceLocator,
                            )
                        }
                val methodRecords =
                    materialCoverageRecords(
                        pack = pack,
                        subject = subject,
                        materialBindingsById = materialBindingsById,
                        nodesById = nodesById,
                    ) { material ->
                        material.materialType == KnowledgeTeachingMaterialType.METHOD_MODEL
                    }
                val exampleRecords =
                    materialCoverageRecords(
                        pack = pack,
                        subject = subject,
                        materialBindingsById = materialBindingsById,
                        nodesById = nodesById,
                    ) { material ->
                        material.materialType in
                            setOf(
                                KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
                                KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
                            )
                    }
                val textbookMappingRecords =
                    pack.nodeSourceBindings
                        .filter { binding ->
                            binding.sourceId in textbookSourceIds &&
                                nodesById[binding.knowledgeNodeId]?.subject == subject
                        }.map { binding ->
                            listOf(
                                nodesById[binding.knowledgeNodeId]?.stableCode,
                                binding.sourceId,
                                binding.sourceLocator,
                            )
                        }
                FormalKnowledgeSubjectCoverageProofV2(
                    subject = subject,
                    expectedModuleStableCodes =
                        subjectNodes
                            .filter { node -> node.kind == KnowledgeNodeKind.TOPIC }
                            .map(ReviewedKnowledgeNode::stableCode)
                            .sorted(),
                    expectedKnowledgePointStableCodes =
                        subjectNodes
                            .filter { node ->
                                node.granularity == KnowledgeNodeGranularity.ATOMIC
                            }.map(ReviewedKnowledgeNode::stableCode)
                            .sorted(),
                    relationCoverage =
                        coverageSummary(COVERAGE_RELATION_DOMAIN, subject, relationRecords),
                    methodCoverage =
                        coverageSummary(COVERAGE_METHOD_DOMAIN, subject, methodRecords),
                    workedExampleCoverage =
                        coverageSummary(COVERAGE_EXAMPLE_DOMAIN, subject, exampleRecords),
                    textbookMappingCoverage =
                        coverageSummary(
                            COVERAGE_TEXTBOOK_MAPPING_DOMAIN,
                            subject,
                            textbookMappingRecords,
                        ),
                )
            }
        return FormalKnowledgePackCoverageProofV2(
            coverageLedgerId = coverageLedgerId,
            targetBaselineId = targetBaselineId,
            coverageLedgerFingerprint = coverageLedgerFingerprint,
            coverageReviewRecordId = coverageReviewRecordId,
            coverageReviewedAtEpochMillis = coverageReviewedAtEpochMillis,
            humanReviewSummaryFingerprint = humanReviewSummaryFingerprint,
            sourceLicenseReviewSummaryFingerprint = sourceLicenseReviewSummaryFingerprint,
            subjects = subjects,
        )
    }

    fun canonicalSourceRecordFingerprint(
        source: ReviewedKnowledgeSource,
        sourceVersion: String,
    ): String =
        formalSha256(
            canonicalFormalText {
                field(source.sourceId)
                field(sourceVersion)
                field(source.subject.name)
                field(source.sourceType.name)
                field(source.title)
                field(source.publisher)
                field(source.edition)
                field(source.sourceUri)
                field(source.licenseStatus.name)
                field(source.contentUsePolicy.name)
                field(source.contentFingerprint)
                field(source.licenseExpression)
                field(source.licenseUri)
                field(source.attributionText)
                field(source.reviewedAtEpochMillis)
            },
        )

    private fun materialCoverageRecords(
        pack: ReviewedKnowledgePack,
        subject: SubjectKind,
        materialBindingsById:
            Map<String, List<ReviewedKnowledgeTeachingMaterialBinding>>,
        nodesById: Map<String, ReviewedKnowledgeNode>,
        include: (ReviewedKnowledgeTeachingMaterial) -> Boolean,
    ): List<List<Any?>> =
        pack.teachingMaterials
            .filter { material -> material.subject == subject && include(material) }
            .map { material ->
                buildList {
                    add(material.materialId)
                    add(material.stableCode)
                    add(material.materialType.name)
                    add(material.sourceId)
                    add(material.sourceLocator)
                    add(material.contentFingerprint)
                    materialBindingsById[material.materialId]
                        .orEmpty()
                        .mapNotNull { binding -> nodesById[binding.knowledgeNodeId]?.stableCode }
                        .sorted()
                        .forEach(::add)
                }
            }

    private fun coverageSummary(
        domain: String,
        subject: SubjectKind,
        records: List<List<Any?>>,
    ): FormalKnowledgeCoverageSummary {
        val canonicalRecords =
            records
                .map { values ->
                    canonicalFormalText {
                        values.forEach(::field)
                    }
                }.sorted()
        return FormalKnowledgeCoverageSummary(
            itemCount = canonicalRecords.size,
            itemFingerprint =
                formalSha256(
                    canonicalFormalText {
                        field(domain)
                        field(subject.name)
                        canonicalRecords.forEach(::field)
                    },
                ),
        )
    }

    fun requireActivatable(
        pack: ReviewedKnowledgePack,
        expectedContentFingerprint: String,
        activationGeneration: Long,
        proof: FormalKnowledgePackActivationProof,
        trustedSigningKeys: Collection<FormalKnowledgePackSigningKey>,
    ) {
        require(FORMAL_SHA_256.matches(expectedContentFingerprint)) {
            "Expected formal-pack fingerprint must be lowercase SHA-256"
        }
        require(activationGeneration > 0L) {
            "Formal-pack activation generation must be positive"
        }
        val candidate = proof.candidate
        val coverageProof =
            requireNotNull(candidate.coverageProofV2) {
                "Formal releases require a reviewed coverage proof v2"
            }
        val metadata = pack.metadata
        require(candidate.packId == metadata.packId) { "Candidate pack id does not match content" }
        require(candidate.knowledgePackVersion == metadata.knowledgePackVersion) {
            "Candidate pack version does not match content"
        }
        require(candidate.taxonomyVersion == metadata.taxonomyVersion) {
            "Candidate taxonomy version does not match content"
        }
        require(candidate.searchIndexVersion == metadata.searchIndexVersion) {
            "Candidate search-index version does not match content"
        }
        val actualReviewedContentFingerprint = reviewedContentFingerprint(pack)
        require(candidate.contentFingerprint == actualReviewedContentFingerprint) {
            "Candidate is not bound to the canonical reviewed pack content"
        }
        require(candidate.preparedAtEpochMillis >= packLatestReviewTime(pack)) {
            "Candidate cannot predate the reviewed content it contains"
        }

        validateActualCoverage(pack, candidate)
        validateCoverageProofV2(pack, candidate, coverageProof)
        val actualCoverageFingerprint = coverageFingerprint(pack)
        require(candidate.coverageFingerprint == actualCoverageFingerprint) {
            "Candidate coverage audit does not match actual pack content"
        }

        val review = proof.independentReview
        require(review.candidateGovernanceFingerprint == candidate.governanceFingerprint) {
            "Independent review does not bind the candidate governance record"
        }
        require(review.candidateContentFingerprint == actualReviewedContentFingerprint) {
            "Independent review does not bind the canonical reviewed content"
        }
        require(review.producerOrganizationId == candidate.producerOrganizationId) {
            "Independent review producer does not match the candidate"
        }
        require(review.reviewerOrganizationId != candidate.producerOrganizationId) {
            "Formal packs require a genuinely independent reviewer"
        }
        require(review.reviewedAtEpochMillis >= candidate.preparedAtEpochMillis) {
            "Independent review cannot predate the candidate"
        }
        require(review.decision == FormalKnowledgePackReviewDecision.APPROVED) {
            "Formal pack has not passed independent review"
        }
        require(
            review.contentPolicyAttestation ==
                FormalKnowledgeContentPolicyAttestation
                    .ORIGINAL_OR_REVIEWED_SYNTHESIS_WITHOUT_QUESTION_BANK,
        ) {
            "Formal pack may not contain a third-party question bank"
        }
        require(review.coverageFingerprint == actualCoverageFingerprint) {
            "Independent review does not bind the actual coverage audit"
        }
        require(review.coverageProofFingerprint == coverageProof.proofFingerprint) {
            "Independent review does not bind coverage proof v2"
        }

        val signed = proof.signedActivation
        require(signed.packId == metadata.packId) { "Signed pack id does not match content" }
        require(signed.knowledgePackVersion == metadata.knowledgePackVersion) {
            "Signed pack version does not match content"
        }
        require(signed.taxonomyVersion == metadata.taxonomyVersion) {
            "Signed taxonomy version does not match content"
        }
        require(signed.searchIndexVersion == metadata.searchIndexVersion) {
            "Signed search-index version does not match content"
        }
        require(signed.contentFingerprint == actualReviewedContentFingerprint) {
            "Signature does not bind the canonical reviewed pack content"
        }
        require(signed.candidateGovernanceFingerprint == candidate.governanceFingerprint) {
            "Signature does not bind the candidate record"
        }
        require(signed.independentReviewFingerprint == review.reviewFingerprint) {
            "Signature does not bind the independent review"
        }
        require(signed.coverageProofFingerprint == coverageProof.proofFingerprint) {
            "Signature does not bind coverage proof v2"
        }
        require(signed.activationGeneration == activationGeneration) {
            "Signature does not bind the activation generation"
        }
        require(signed.signedAtEpochMillis >= review.reviewedAtEpochMillis) {
            "Activation signature cannot predate independent review"
        }

        verifySignature(signed, trustedSigningKeys)
    }

    private fun validateActualCoverage(
        pack: ReviewedKnowledgePack,
        candidate: FormalKnowledgePackCandidate,
    ) {
        val expectedSubjects = formalSubjects().toSet()
        val mappingsBySubject =
            candidate.subjectMappings.associateBy(FormalKnowledgeSubjectMapping::subject)
        require(mappingsBySubject.keys == expectedSubjects) {
            "Formal pack requires one curriculum/edition mapping for all nine subjects"
        }

        val sourcesById = pack.sources.associateBy(ReviewedKnowledgeSource::sourceId)
        val provenanceById =
            candidate.sourceProvenance.associateBy {
                provenance -> provenance.admissionEvidence.sourceId
            }
        require(provenanceById.keys == sourcesById.keys) {
            "Every formal-pack source requires exact provenance and license evidence"
        }
        pack.sources.forEach { source ->
            validateSourceProvenance(source, requireNotNull(provenanceById[source.sourceId]))
        }

        expectedSubjects.forEach { subject ->
            val mapping = requireNotNull(mappingsBySubject[subject])
            val nodes = pack.nodes.filter { node -> node.subject == subject }
            require(nodes.any { node -> node.kind == KnowledgeNodeKind.TOPIC }) {
                "Every subject requires reviewed topic structure"
            }
            require(nodes.any { node -> node.granularity == KnowledgeNodeGranularity.ATOMIC }) {
                "Every subject requires reviewed fine-grained knowledge"
            }
            val root =
                nodes.singleOrNull { node ->
                    node.stableCode == mapping.rootNodeStableCode &&
                        node.kind == KnowledgeNodeKind.TOPIC &&
                        node.parentKnowledgeNodeId == null
                }
            requireNotNull(root) {
                "Subject mapping requires one matching top-level root"
            }
            require(nodes.all { node -> node.stableCode.startsWith(mapping.stableCodeNamespace) }) {
                "Subject nodes must use the reviewed stable-code namespace"
            }
            validateAliasSurface(subject, nodes)

            val curriculum = requireNotNull(sourcesById[mapping.curriculumSourceId]) {
                "Subject mapping references a missing curriculum source"
            }
            require(
                curriculum.subject == subject &&
                    curriculum.sourceType ==
                    KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD,
            ) {
                "Subject mapping must bind an official curriculum source"
            }

            mapping.textbookEditionSourceIds.forEach { sourceId ->
                val textbook = requireNotNull(sourcesById[sourceId]) {
                    "Subject mapping references a missing textbook edition"
                }
                require(
                    textbook.subject == subject &&
                        textbook.sourceType == KnowledgeSourceType.TEXTBOOK &&
                        !textbook.publisher.isNullOrBlank() &&
                        !textbook.edition.isNullOrBlank(),
                ) {
                    "Textbook-edition mappings require exact publisher and edition metadata"
                }
                val provenance = requireNotNull(provenanceById[sourceId])
                require(
                    !provenance.editionEvidenceLocator.isNullOrBlank() &&
                        provenance.editionEvidenceFingerprint != null,
                ) {
                    "Textbook editions require reviewed edition evidence"
                }
            }
            require(
                mapping.textbookEditionSourceIds.toSet() ==
                    pack.sources
                        .filter { source ->
                            source.subject == subject &&
                                source.sourceType == KnowledgeSourceType.TEXTBOOK
                        }
                        .mapTo(mutableSetOf(), ReviewedKnowledgeSource::sourceId),
            ) {
                "Every bundled textbook edition must appear in its subject mapping"
            }

            require(pack.relations.any { relation -> relation.subject == subject }) {
                "Every subject requires reviewed knowledge relations"
            }
            val subjectMaterials =
                pack.teachingMaterials.filter { material -> material.subject == subject }
            require(
                subjectMaterials.any { material ->
                    material.materialType == KnowledgeTeachingMaterialType.METHOD_MODEL
                },
            ) {
                "Every subject requires at least one reviewed method model"
            }
            require(
                subjectMaterials.any { material ->
                    material.materialType == KnowledgeTeachingMaterialType.WORKED_EXAMPLE
                },
            ) {
                "Every subject requires at least one reviewed example structure"
            }
            require(
                subjectMaterials.all { material ->
                    material.stableCode.startsWith(mapping.stableCodeNamespace)
                },
            ) {
                "Teaching materials must use the subject's stable-code namespace"
            }
        }

        val provenanceRoutes =
            candidate.sourceProvenance.associate { provenance ->
                provenance.admissionEvidence.sourceId to
                    KnowledgeSourceAdmissionPolicy.evaluate(provenance.admissionEvidence)
            }
        pack.teachingMaterials.forEach { material ->
            val source = requireNotNull(sourcesById[material.sourceId])
            val route = requireNotNull(provenanceRoutes[material.sourceId])
            val copiesSourceExpression =
                material.derivationKind != KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS
            if (copiesSourceExpression) {
                require(route == KnowledgeSourceAdmissionRoute.ALLOW_CORE_AUTO) {
                    "Excerpted or adapted teaching material requires automatic-use permission"
                }
            } else {
                require(
                    route != KnowledgeSourceAdmissionRoute.BLOCKED &&
                        route != KnowledgeSourceAdmissionRoute.SEPARATE_SHARE_ALIKE_PACK,
                ) {
                    "Reviewed synthesis cannot use blocked or separately licensed sources"
                }
            }
            if (material.materialType == KnowledgeTeachingMaterialType.WORKED_EXAMPLE) {
                require(
                    material.derivationKind == KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS ||
                        source.contentUsePolicy !=
                        KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY,
                ) {
                    "Example structures must be reviewed synthesis unless reuse is licensed"
                }
            }
        }
    }

    private fun validateCoverageProofV2(
        pack: ReviewedKnowledgePack,
        candidate: FormalKnowledgePackCandidate,
        proof: FormalKnowledgePackCoverageProofV2,
    ) {
        require(proof.schemaVersion == FormalKnowledgePackCoverageProofV2.SCHEMA_VERSION) {
            "Formal releases require coverage proof schema v2"
        }
        require(proof.coverageReviewedAtEpochMillis <= candidate.preparedAtEpochMillis) {
            "Coverage-ledger review cannot postdate candidate preparation"
        }
        val actual =
            deriveCoverageProofV2(
                pack = pack,
                coverageLedgerId = proof.coverageLedgerId,
                targetBaselineId = proof.targetBaselineId,
                coverageLedgerFingerprint = proof.coverageLedgerFingerprint,
                coverageReviewRecordId = proof.coverageReviewRecordId,
                coverageReviewedAtEpochMillis = proof.coverageReviewedAtEpochMillis,
                humanReviewSummaryFingerprint = proof.humanReviewSummaryFingerprint,
                sourceLicenseReviewSummaryFingerprint =
                    proof.sourceLicenseReviewSummaryFingerprint,
            )
        require(actual.proofFingerprint == proof.proofFingerprint) {
            "Coverage proof does not match the actual reviewed pack"
        }

        val nodesById = pack.nodes.associateBy(ReviewedKnowledgeNode::knowledgeNodeId)
        val textbookSourceIds =
            pack.sources
                .filter { source -> source.sourceType == KnowledgeSourceType.TEXTBOOK }
                .mapTo(mutableSetOf(), ReviewedKnowledgeSource::sourceId)
        val textbookMappedAtomicStableCodes =
            pack.nodeSourceBindings
                .asSequence()
                .filter { binding -> binding.sourceId in textbookSourceIds }
                .mapNotNull { binding -> nodesById[binding.knowledgeNodeId] }
                .filter { node -> node.granularity == KnowledgeNodeGranularity.ATOMIC }
                .map(ReviewedKnowledgeNode::stableCode)
                .toSet()

        proof.subjects.forEach { subjectProof ->
            require(subjectProof.expectedModuleStableCodes.isNotEmpty()) {
                "${subjectProof.subject.name} lacks a reviewed curriculum-module scope"
            }
            require(subjectProof.expectedKnowledgePointStableCodes.size >= MIN_POINTS_PER_SUBJECT) {
                "${subjectProof.subject.name} is only a placeholder knowledge scope"
            }
            require(subjectProof.relationCoverage.itemCount > 0) {
                "${subjectProof.subject.name} lacks reviewed relation coverage"
            }
            require(subjectProof.methodCoverage.itemCount > 0) {
                "${subjectProof.subject.name} lacks reviewed method coverage"
            }
            require(subjectProof.workedExampleCoverage.itemCount > 0) {
                "${subjectProof.subject.name} lacks reviewed example coverage"
            }
            require(subjectProof.textbookMappingCoverage.itemCount > 0) {
                "${subjectProof.subject.name} lacks reviewed textbook mappings"
            }
            require(
                textbookMappedAtomicStableCodes.containsAll(
                    subjectProof.expectedKnowledgePointStableCodes,
                ),
            ) {
                "${subjectProof.subject.name} has atomic knowledge without reviewed textbook mapping"
            }
        }
    }

    private fun validateSourceProvenance(
        source: ReviewedKnowledgeSource,
        provenance: FormalKnowledgeSourceProvenance,
    ) {
        val evidence = provenance.admissionEvidence
        require(evidence.sourceId == source.sourceId) {
            "Source provenance id does not match pack content"
        }
        require(evidence.resourceTitle == source.title) {
            "Source provenance title does not match pack content"
        }
        require(evidence.publisher == source.publisher) {
            "Source provenance publisher does not match pack content"
        }
        require(evidence.sourceVersion == source.edition) {
            "Source provenance version does not match the reviewed edition"
        }
        require(evidence.canonicalLocator == source.sourceUri) {
            "Source provenance locator does not match pack content"
        }
        require(evidence.licenseExpression == source.licenseExpression) {
            "Source license expression does not match pack content"
        }
        require(evidence.licenseEvidenceLocator == source.licenseUri) {
            "Source license evidence does not match pack content"
        }
        require(provenance.sourceContentFingerprint == source.contentFingerprint) {
            "Source provenance does not bind the source content"
        }
        require(
            evidence.sourceRecordFingerprint ==
                canonicalSourceRecordFingerprint(source, evidence.sourceVersion),
        ) {
            "Source provenance fingerprint does not match canonical metadata"
        }
        require(
            evidence.reviewedAtEpochMillis != null &&
                evidence.reviewedAtEpochMillis >= provenance.retrievedAtEpochMillis &&
                source.reviewedAtEpochMillis >= evidence.reviewedAtEpochMillis,
        ) {
            "Source review chronology is invalid"
        }
        require(
            source.licenseStatus == evidence.licenseClass.expectedCatalogLicenseStatus(),
        ) {
            "Source license class does not match catalog license status"
        }
        val route = KnowledgeSourceAdmissionPolicy.evaluate(evidence)
        require(
            route != KnowledgeSourceAdmissionRoute.BLOCKED &&
                route != KnowledgeSourceAdmissionRoute.SEPARATE_SHARE_ALIKE_PACK,
        ) {
            "Formal core-pack source did not pass license admission"
        }
        if (
            route == KnowledgeSourceAdmissionRoute.HUMAN_REFERENCE_ONLY ||
            route == KnowledgeSourceAdmissionRoute.ALLOW_METADATA_ONLY
        ) {
            require(
                source.contentUsePolicy ==
                    KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY,
            ) {
                "Reference or metadata-only sources cannot authorize copied expression"
            }
        }
    }

    private fun validateAliasSurface(
        subject: SubjectKind,
        nodes: List<ReviewedKnowledgeNode>,
    ) {
        val ownerByNormalizedName = mutableMapOf<String, String>()
        nodes.forEach { node ->
            (listOf(node.canonicalName, node.displayName) + node.aliases)
                .map(KnowledgeSearchNormalizer::normalizeFeature)
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { normalized ->
                    val previous = ownerByNormalizedName.putIfAbsent(normalized, node.knowledgeNodeId)
                    require(previous == null || previous == node.knowledgeNodeId) {
                        "Aliases must resolve unambiguously within ${subject.name}"
                    }
                }
        }
    }

    private fun verifySignature(
        signed: SignedFormalKnowledgePackActivation,
        trustedSigningKeys: Collection<FormalKnowledgePackSigningKey>,
    ) {
        val key =
            trustedSigningKeys.singleOrNull { candidate ->
                candidate.keyId == signed.signingKeyId &&
                    candidate.algorithm == signed.algorithm
            } ?: throw SecurityException("Activation signature key is not trusted")
        if (key.revokedAtEpochMillis != null) {
            throw SecurityException("Activation signature key is revoked")
        }
        if (
            signed.signedAtEpochMillis !in
            key.validFromEpochMillis..key.validUntilEpochMillis
        ) {
            throw SecurityException("Activation signature is outside the key validity interval")
        }
        val publicKey =
            KeyFactory
                .getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(key.x509PublicKeyHex.hexToBytes()))
        if ((publicKey as? RSAKey)?.modulus?.bitLength()?.let { bits -> bits < 2_048 } != false) {
            throw SecurityException("Formal-pack RSA key must contain at least 2048 bits")
        }
        val verifier = Signature.getInstance(signed.algorithm.jcaName)
        verifier.initVerify(publicKey)
        verifier.update(signed.canonicalPayload())
        if (!verifier.verify(signed.signatureHex.hexToBytes())) {
            throw SecurityException("Formal-pack activation signature is invalid")
        }
    }

    private const val MIN_POINTS_PER_SUBJECT = 2
    private const val REVIEWED_CONTENT_DOMAIN = "formal-reviewed-knowledge-pack-content-v2"
    private const val SOURCE_LICENSE_REVIEW_DOMAIN = "formal-source-license-review-summary-v2"
    private const val COVERAGE_RELATION_DOMAIN = "formal-subject-relation-coverage-v2"
    private const val COVERAGE_METHOD_DOMAIN = "formal-subject-method-coverage-v2"
    private const val COVERAGE_EXAMPLE_DOMAIN = "formal-subject-example-coverage-v2"
    private const val COVERAGE_TEXTBOOK_MAPPING_DOMAIN =
        "formal-subject-textbook-mapping-coverage-v2"
}

private fun packLatestReviewTime(pack: ReviewedKnowledgePack): Long =
    sequenceOf(
        pack.nodes.asSequence().map(ReviewedKnowledgeNode::reviewedAtEpochMillis),
        pack.sources.asSequence().map(ReviewedKnowledgeSource::reviewedAtEpochMillis),
        pack.nodeSourceBindings
            .asSequence()
            .map(ReviewedKnowledgeNodeSourceBinding::reviewedAtEpochMillis),
        pack.relations.asSequence().map(ReviewedKnowledgeRelation::reviewedAtEpochMillis),
        pack.teachingMaterials
            .asSequence()
            .map(ReviewedKnowledgeTeachingMaterial::reviewedAtEpochMillis),
    ).flatten().maxOrNull() ?: 0L

private fun KnowledgeSourceLicenseClass.expectedCatalogLicenseStatus():
    KnowledgeSourceLicenseStatus =
    when (this) {
        KnowledgeSourceLicenseClass.PERMISSIVE,
        KnowledgeSourceLicenseClass.SHARE_ALIKE,
        KnowledgeSourceLicenseClass.NON_COMMERCIAL,
        KnowledgeSourceLicenseClass.NO_DERIVATIVES,
        -> KnowledgeSourceLicenseStatus.LICENSED
        KnowledgeSourceLicenseClass.PUBLIC_OFFICIAL_METADATA ->
            KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL
        KnowledgeSourceLicenseClass.REFERENCE_ONLY ->
            KnowledgeSourceLicenseStatus.REFERENCE_ONLY
        KnowledgeSourceLicenseClass.UNKNOWN,
        KnowledgeSourceLicenseClass.BLOCKED,
        -> throw IllegalArgumentException("Unknown or blocked licenses cannot enter a formal pack")
    }

private fun formalSubjects(): List<SubjectKind> =
    enumValues<SubjectKind>().filter { subject -> subject != SubjectKind.GENERAL }

internal fun canonicalFormalText(block: FormalCanonicalWriter.() -> Unit): String =
    FormalCanonicalWriter().apply(block).toString()

internal class FormalCanonicalWriter {
    private val output = StringBuilder()

    fun field(value: Any?) {
        val text = value?.toString().orEmpty()
        output.append(text.length).append(':').append(text)
    }

    fun appendCoverageSummary(summary: FormalKnowledgeCoverageSummary) {
        field(summary.itemCount)
        field(summary.itemFingerprint)
    }

    override fun toString(): String = output.toString()
}

internal fun formalSha256(value: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && FORMAL_HEX.matches(this)) { "Hexadecimal value is invalid" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun String.requireFormalId(label: String) {
    require(isNotBlank() && length <= 160 && none(Char::isISOControl)) { "$label is invalid" }
}

private fun String.requireFormalText(label: String) {
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

private val FORMAL_SHA_256 = Regex("[0-9a-f]{64}")
private val FORMAL_HEX = Regex("[0-9a-f]+")
private val FORMAL_STANDARD_CODE = Regex("SB\\d{4}")
