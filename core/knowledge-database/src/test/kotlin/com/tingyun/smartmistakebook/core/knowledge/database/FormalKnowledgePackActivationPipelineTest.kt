package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalKnowledgePackActivationPipelineTest {
    @Test
    fun completeNineSubjectCandidatePassesIndependentReviewAndSignatureGate() {
        val fixture = fixture()

        FormalKnowledgePackActivationPolicy.requireActivatable(
            pack = fixture.pack,
            expectedContentFingerprint = PACK_FINGERPRINT,
            activationGeneration = ACTIVATION_GENERATION,
            proof = fixture.proof,
            trustedSigningKeys = listOf(TRUSTED_KEY),
        )

        assertTrue(fixture.candidate.governanceFingerprint.matches(SHA_256))
        assertTrue(fixture.review.reviewFingerprint.matches(SHA_256))
    }

    @Test
    fun coverageGateUsesActualContentNotDeclaredCounts() {
        val complete = completePack()
        val math = SubjectKind.MATH
        val invalidPacks =
            listOf(
                rebuild(
                    complete,
                    relations = complete.relations.filterNot { relation -> relation.subject == math },
                ),
                rebuild(
                    complete,
                    teachingMaterials =
                        complete.teachingMaterials.filterNot { material ->
                            material.subject == math &&
                                material.materialType ==
                                KnowledgeTeachingMaterialType.METHOD_MODEL
                        },
                    teachingMaterialBindings =
                        complete.teachingMaterialBindings.filterNot { binding ->
                            binding.materialId == materialId(math, "method")
                        },
                ),
                rebuild(
                    complete,
                    teachingMaterials =
                        complete.teachingMaterials.filterNot { material ->
                            material.subject == math &&
                                material.materialType ==
                                KnowledgeTeachingMaterialType.WORKED_EXAMPLE
                        },
                    teachingMaterialBindings =
                        complete.teachingMaterialBindings.filterNot { binding ->
                            binding.materialId == materialId(math, "example")
                        },
                ),
                rebuild(
                    complete,
                    nodes =
                        complete.nodes.map { node ->
                            if (node.knowledgeNodeId == rootNodeId(math)) {
                                node.copy(aliases = listOf(atomicName(math)))
                            } else {
                                node
                            }
                        },
                ),
            )

        invalidPacks.forEach { pack ->
            val fixture = fixture(pack)
            assertThrows(IllegalArgumentException::class.java) {
                FormalKnowledgePackActivationPolicy.requireActivatable(
                    pack = pack,
                    expectedContentFingerprint = PACK_FINGERPRINT,
                    activationGeneration = ACTIVATION_GENERATION,
                    proof = fixture.proof,
                    trustedSigningKeys = listOf(TRUSTED_KEY),
                )
            }
        }
    }

    @Test
    fun formalReleaseRejectsLegacyCoverageProofAndSinglePointPlaceholders() {
        val pack = completePack()
        val legacyCandidate = candidate(pack).copy(coverageProofV2 = null)
        val legacyReview = review(legacyCandidate)
        val legacyProof = proof(legacyCandidate, legacyReview)
        assertThrows(IllegalArgumentException::class.java) {
            BuildVariantTrustedKnowledgePackDefinition(
                pack = pack,
                generation = ACTIVATION_GENERATION,
                productionCutoverEligible = true,
                purpose = BuiltInKnowledgePackPurpose.FORMAL_HIGH_SCHOOL_RELEASE,
                formalActivationProof = legacyProof,
            )
        }

        val placeholderPack =
            rebuild(
                pack,
                nodes =
                    pack.nodes.filterNot { node ->
                        node.knowledgeNodeId == secondaryAtomicNodeId(node.subject)
                    },
            )
        val placeholder = fixture(placeholderPack)
        assertThrows(IllegalArgumentException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = placeholderPack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = placeholder.proof,
                trustedSigningKeys = listOf(TRUSTED_KEY),
            )
        }
    }

    @Test
    fun subjectAndTextbookEditionMappingsCoverAllNineSubjectsExactly() {
        val pack = completePack()
        val mappings = mappings().map { mapping ->
            if (mapping.subject == SubjectKind.MATH) {
                mapping.copy(
                    textbookEditionSourceIds =
                        listOf(textbookSourceId(SubjectKind.PHYSICS)),
                )
            } else {
                mapping
            }
        }
        val fixture = fixture(pack = pack, subjectMappings = mappings)

        assertThrows(IllegalArgumentException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = fixture.proof,
                trustedSigningKeys = listOf(TRUSTED_KEY),
            )
        }
    }

    @Test
    fun exactSourceProvenanceAndLicenseEvidenceFailClosed() {
        val pack = completePack()
        val provenance = provenance(pack).toMutableList()
        val first = provenance.first()
        provenance[0] =
            first.copy(
                admissionEvidence =
                    first.admissionEvidence.copy(
                        evidenceOrigin = KnowledgeSourceEvidenceOrigin.MODEL_DECLARATION,
                    ),
            )
        val fixture = fixture(pack = pack, sourceProvenance = provenance)

        assertThrows(IllegalArgumentException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = fixture.proof,
                trustedSigningKeys = listOf(TRUSTED_KEY),
            )
        }
    }

    @Test
    fun producerCannotSelfReviewAndQuestionBankAttestationCannotPass() {
        val pack = completePack()
        val candidate = candidate(pack)
        val selfReview =
            review(
                candidate = candidate,
                reviewerOrganizationId = PRODUCER_ID,
            )
        val selfReviewedProof = proof(candidate, selfReview)
        assertThrows(IllegalArgumentException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = selfReviewedProof,
                trustedSigningKeys = listOf(TRUSTED_KEY),
            )
        }

        val unsafeReview =
            review(
                candidate = candidate,
                contentPolicyAttestation =
                    FormalKnowledgeContentPolicyAttestation
                        .THIRD_PARTY_QUESTION_BANK_CONTENT_PRESENT,
            )
        val unsafeProof = proof(candidate, unsafeReview)
        assertThrows(IllegalArgumentException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = unsafeProof,
                trustedSigningKeys = listOf(TRUSTED_KEY),
            )
        }
    }

    @Test
    fun unknownKeyAndTamperedSignatureNeverActivate() {
        val fixture = fixture()
        assertThrows(SecurityException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = fixture.pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = fixture.proof,
                trustedSigningKeys = emptyList(),
            )
        }

        val signature = fixture.proof.signedActivation.signatureHex
        val tampered =
            fixture.proof.signedActivation.copy(
                signatureHex =
                    (if (signature.first() == '0') "1" else "0") + signature.drop(1),
            )
        val tamperedProof =
            FormalKnowledgePackActivationProof(
                candidate = fixture.candidate,
                independentReview = fixture.review,
                signedActivation = tampered,
            )
        assertThrows(SecurityException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = fixture.pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = tamperedProof,
                trustedSigningKeys = listOf(TRUSTED_KEY),
            )
        }
    }

    @Test
    fun expiredRevokedAndEmptySigningKeysFailClosed() {
        val fixture = fixture()
        val expired =
            TRUSTED_KEY.copy(
                validUntilEpochMillis = SIGNED_AT - 1L,
            )
        assertThrows(SecurityException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = fixture.pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = fixture.proof,
                trustedSigningKeys = listOf(expired),
            )
        }

        val revoked =
            TRUSTED_KEY.copy(
                revokedAtEpochMillis = SIGNED_AT,
                revocationEvidenceFingerprint = "f".repeat(64),
            )
        assertThrows(SecurityException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = fixture.pack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = fixture.proof,
                trustedSigningKeys = listOf(revoked),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            TRUSTED_KEY.copy(x509PublicKeyHex = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TRUSTED_KEY.copy(
                revokedAtEpochMillis = SIGNED_AT,
                revocationEvidenceFingerprint = null,
            )
        }
    }

    @Test
    fun signedReviewCannotAuthorizeDifferentReviewedContent() {
        val fixture = fixture()
        val changedPack =
            rebuild(
                fixture.pack,
                nodes =
                    fixture.pack.nodes.map { node ->
                        if (node == fixture.pack.nodes.first()) {
                            node.copy(displayName = "${node.displayName} changed")
                        } else {
                            node
                        }
                    },
            )

        assertThrows(IllegalArgumentException::class.java) {
            FormalKnowledgePackActivationPolicy.requireActivatable(
                pack = changedPack,
                expectedContentFingerprint = PACK_FINGERPRINT,
                activationGeneration = ACTIVATION_GENERATION,
                proof = fixture.proof,
                trustedSigningKeys = listOf(TRUSTED_KEY),
            )
        }
    }

    @Test
    fun formalRegistryRequiresProofAndDebugFixtureCanNeverQualifyForProduction() {
        val pack = completePack()
        assertThrows(IllegalArgumentException::class.java) {
            BuildVariantTrustedKnowledgePackDefinition(
                pack = pack,
                generation = ACTIVATION_GENERATION,
                productionCutoverEligible = true,
                purpose = BuiltInKnowledgePackPurpose.FORMAL_HIGH_SCHOOL_RELEASE,
                formalActivationProof = null,
            )
        }
        val debugPack = ReviewedKnowledgePack(
            metadata = pack.metadata.copy(packId = "debug.boundary-fixture"),
            nodes = pack.nodes,
            sources = pack.sources,
            nodeSourceBindings = pack.nodeSourceBindings,
            relations = pack.relations,
            teachingMaterials = pack.teachingMaterials,
            teachingMaterialBindings = pack.teachingMaterialBindings,
        )
        val debugDefinition =
            BuildVariantTrustedKnowledgePackDefinition(
                pack = debugPack,
                generation = ACTIVATION_GENERATION,
                productionCutoverEligible = false,
                purpose = BuiltInKnowledgePackPurpose.DEBUG_BOUNDARY_FIXTURE,
                formalActivationProof = null,
            )
        assertFalse(debugDefinition.productionCutoverEligible)
        assertThrows(IllegalArgumentException::class.java) {
            BuildVariantTrustedKnowledgePackDefinition(
                pack = debugPack,
                generation = ACTIVATION_GENERATION,
                productionCutoverEligible = true,
                purpose = BuiltInKnowledgePackPurpose.DEBUG_BOUNDARY_FIXTURE,
                formalActivationProof = null,
            )
        }
    }

    @Test
    fun formalGovernanceSurfaceContainsNoLearnerOrMistakeAuthority() {
        val governedTypes =
            listOf(
                FormalKnowledgeSourceProvenance::class.java,
                FormalKnowledgeSubjectMapping::class.java,
                FormalKnowledgeSubjectCoverageProofV2::class.java,
                FormalKnowledgePackCoverageProofV2::class.java,
                FormalKnowledgePackCandidate::class.java,
                FormalKnowledgePackIndependentReview::class.java,
                SignedFormalKnowledgePackActivation::class.java,
            )
        val forbidden =
            listOf(
                "student",
                "learner",
                "mistake",
                "mastery",
                "attempt",
                "conversation",
                "chat",
            )

        governedTypes.forEach { type ->
            val publicSurface =
                buildString {
                    type.declaredFields.forEach { field ->
                        append(field.name).append(':').append(field.type.simpleName).append('\n')
                    }
                    type.declaredMethods.forEach { method ->
                        append(method.name).append(':').append(method.returnType.simpleName)
                            .append('\n')
                    }
                }.lowercase()
            forbidden.forEach { token ->
                assertFalse("${type.simpleName} leaks '$token'", token in publicSurface)
            }
        }
    }

    private fun fixture(
        pack: ReviewedKnowledgePack = completePack(),
        sourceProvenance: List<FormalKnowledgeSourceProvenance> = provenance(pack),
        subjectMappings: List<FormalKnowledgeSubjectMapping> = mappings(),
        coverageProofV2: FormalKnowledgePackCoverageProofV2 =
            derivedCoverageProofV2(pack, sourceProvenance),
    ): Fixture {
        val candidate =
            candidate(
                pack = pack,
                sourceProvenance = sourceProvenance,
                subjectMappings = subjectMappings,
                coverageProofV2 = coverageProofV2,
            )
        val review = review(candidate)
        return Fixture(
            pack = pack,
            candidate = candidate,
            review = review,
            proof = proof(candidate, review),
        )
    }

    private fun candidate(
        pack: ReviewedKnowledgePack,
        sourceProvenance: List<FormalKnowledgeSourceProvenance> = provenance(pack),
        subjectMappings: List<FormalKnowledgeSubjectMapping> = mappings(),
        coverageProofV2: FormalKnowledgePackCoverageProofV2 =
            derivedCoverageProofV2(pack, sourceProvenance),
    ): FormalKnowledgePackCandidate =
        FormalKnowledgePackCandidate(
            candidateId = "candidate.formal.fixture.v1",
            producerOrganizationId = PRODUCER_ID,
            preparedAtEpochMillis = CANDIDATE_AT,
            packId = pack.metadata.packId,
            knowledgePackVersion = pack.metadata.knowledgePackVersion,
            taxonomyVersion = pack.metadata.taxonomyVersion,
            searchIndexVersion = pack.metadata.searchIndexVersion,
            contentFingerprint =
                FormalKnowledgePackActivationPolicy.reviewedContentFingerprint(pack),
            coverageFingerprint =
                FormalKnowledgePackActivationPolicy.coverageFingerprint(pack),
            sourceProvenance = sourceProvenance,
            subjectMappings = subjectMappings,
            coverageProofV2 = coverageProofV2,
        )

    private fun review(
        candidate: FormalKnowledgePackCandidate,
        reviewerOrganizationId: String = REVIEWER_ID,
        contentPolicyAttestation: FormalKnowledgeContentPolicyAttestation =
            FormalKnowledgeContentPolicyAttestation
                .ORIGINAL_OR_REVIEWED_SYNTHESIS_WITHOUT_QUESTION_BANK,
    ): FormalKnowledgePackIndependentReview =
        FormalKnowledgePackIndependentReview(
            reviewRecordId = "review.formal.fixture.v1",
            candidateGovernanceFingerprint = candidate.governanceFingerprint,
            candidateContentFingerprint = candidate.contentFingerprint,
            producerOrganizationId = candidate.producerOrganizationId,
            reviewerOrganizationId = reviewerOrganizationId,
            reviewedAtEpochMillis = REVIEW_AT,
            decision = FormalKnowledgePackReviewDecision.APPROVED,
            contentPolicyAttestation = contentPolicyAttestation,
            coverageFingerprint = candidate.coverageFingerprint,
            coverageProofFingerprint = candidate.coverageProofV2?.proofFingerprint,
        )

    private fun proof(
        candidate: FormalKnowledgePackCandidate,
        review: FormalKnowledgePackIndependentReview,
    ): FormalKnowledgePackActivationProof {
        val unsigned =
            SignedFormalKnowledgePackActivation(
                packId = candidate.packId,
                knowledgePackVersion = candidate.knowledgePackVersion,
                taxonomyVersion = candidate.taxonomyVersion,
                searchIndexVersion = candidate.searchIndexVersion,
                contentFingerprint = candidate.contentFingerprint,
                candidateGovernanceFingerprint = candidate.governanceFingerprint,
                independentReviewFingerprint = review.reviewFingerprint,
                activationGeneration = ACTIVATION_GENERATION,
                signedAtEpochMillis = SIGNED_AT,
                signingKeyId = SIGNING_KEY_ID,
                algorithm = FormalKnowledgeSignatureAlgorithm.SHA256_WITH_RSA_2048,
                signatureHex = "00",
                coverageProofFingerprint = candidate.coverageProofV2?.proofFingerprint,
            )
        val signer = Signature.getInstance(unsigned.algorithm.jcaName)
        signer.initSign(TEST_KEY_PAIR.private)
        signer.update(unsigned.canonicalPayload())
        return FormalKnowledgePackActivationProof(
            candidate = candidate,
            independentReview = review,
            signedActivation = unsigned.copy(signatureHex = signer.sign().toLowerHex()),
        )
    }

    private fun completePack(): ReviewedKnowledgePack {
        val subjects = formalSubjects()
        val nodes =
            subjects.flatMap { subject ->
                listOf(
                    ReviewedKnowledgeNode(
                        knowledgeNodeId = rootNodeId(subject),
                        stableCode = rootStableCode(subject),
                        subject = subject,
                        displayName = "${subject.name} structure",
                        canonicalName = "${subject.name} structure",
                        kind = KnowledgeNodeKind.TOPIC,
                        granularity = KnowledgeNodeGranularity.TOPIC,
                        aliases = emptyList(),
                        boundaryMarkdown = "Reviewed fixture hierarchy.",
                        verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                        parentKnowledgeNodeId = null,
                        reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
                    ),
                    ReviewedKnowledgeNode(
                        knowledgeNodeId = atomicNodeId(subject),
                        stableCode = stableNamespace(subject) + "knowledge.sample",
                        subject = subject,
                        displayName = atomicName(subject),
                        canonicalName = atomicName(subject),
                        kind = KnowledgeNodeKind.CONCEPT,
                        granularity = KnowledgeNodeGranularity.ATOMIC,
                        aliases = listOf("${subject.name} reviewed alias"),
                        boundaryMarkdown = "Reviewed fixture boundary.",
                        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
                        parentKnowledgeNodeId = rootNodeId(subject),
                        reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
                    ),
                    ReviewedKnowledgeNode(
                        knowledgeNodeId = secondaryAtomicNodeId(subject),
                        stableCode = stableNamespace(subject) + "knowledge.secondary",
                        subject = subject,
                        displayName = secondaryAtomicName(subject),
                        canonicalName = secondaryAtomicName(subject),
                        kind = KnowledgeNodeKind.PROCEDURE,
                        granularity = KnowledgeNodeGranularity.ATOMIC,
                        aliases = listOf("${subject.name} secondary reviewed alias"),
                        boundaryMarkdown = "Second reviewed fixture boundary.",
                        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
                        parentKnowledgeNodeId = rootNodeId(subject),
                        reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
                    ),
                )
            }
        val sources =
            subjects.flatMap { subject ->
                listOf(
                    source(
                        subject = subject,
                        id = curriculumSourceId(subject),
                        type = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD,
                        title = "${subject.name} curriculum fixture",
                        publisher = "Official fixture publisher",
                        edition = "2020-fixture",
                        licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL,
                        licenseExpression = "PUBLIC-OFFICIAL-METADATA",
                    ),
                    source(
                        subject = subject,
                        id = textbookSourceId(subject),
                        type = KnowledgeSourceType.TEXTBOOK,
                        title = "${subject.name} textbook fixture",
                        publisher = "Textbook fixture publisher",
                        edition = "2026-fixture",
                        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY,
                        licenseExpression = "REFERENCE-ONLY-REVIEWED-SYNTHESIS",
                    ),
                )
            }
        val bindings =
            subjects.flatMap { subject ->
                listOf(
                    ReviewedKnowledgeNodeSourceBinding(
                        knowledgeNodeId = rootNodeId(subject),
                        sourceId = curriculumSourceId(subject),
                        sourceLocator = "fixture/curriculum/${subject.name}",
                        derivationNote = "Reviewed fixture mapping.",
                        reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
                    ),
                    ReviewedKnowledgeNodeSourceBinding(
                        knowledgeNodeId = atomicNodeId(subject),
                        sourceId = textbookSourceId(subject),
                        sourceLocator = "fixture/textbook/${subject.name}",
                        derivationNote = "Reviewed fixture synthesis.",
                        reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
                    ),
                    ReviewedKnowledgeNodeSourceBinding(
                        knowledgeNodeId = secondaryAtomicNodeId(subject),
                        sourceId = textbookSourceId(subject),
                        sourceLocator = "fixture/textbook/${subject.name}/secondary",
                        derivationNote = "Second reviewed fixture synthesis.",
                        reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
                    ),
                )
            }
        val relations =
            subjects.map { subject ->
                ReviewedKnowledgeRelation(
                    relationId = "fixture.${subject.name.lowercase()}.prerequisite",
                    subject = subject,
                    fromKnowledgeNodeId = rootNodeId(subject),
                    toKnowledgeNodeId = atomicNodeId(subject),
                    relationType = "PREREQUISITE_OF",
                    sourceId = curriculumSourceId(subject),
                    sourceLocator = "fixture/relation/${subject.name}",
                    reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
                )
            }
        val materials =
            subjects.flatMap { subject ->
                listOf(
                    material(
                        subject = subject,
                        suffix = "method",
                        type = KnowledgeTeachingMaterialType.METHOD_MODEL,
                    ),
                    material(
                        subject = subject,
                        suffix = "example",
                        type = KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
                    ),
                )
            }
        val materialBindings =
            materials.map { material ->
                ReviewedKnowledgeTeachingMaterialBinding(
                    materialId = material.materialId,
                    knowledgeNodeId = atomicNodeId(material.subject),
                    role = KnowledgeMaterialNodeRole.PRIMARY,
                )
            }
        return ReviewedKnowledgePack(
            metadata =
                ReviewedKnowledgePackMetadata(
                    packId = PACK_ID,
                    knowledgePackVersion = PACK_VERSION,
                    taxonomyVersion = TAXONOMY_VERSION,
                    searchIndexVersion = SEARCH_VERSION,
                    builtAtEpochMillis = CONTENT_REVIEWED_AT,
                ),
            nodes = nodes,
            sources = sources,
            nodeSourceBindings = bindings,
            relations = relations,
            teachingMaterials = materials,
            teachingMaterialBindings = materialBindings,
        )
    }

    private fun source(
        subject: SubjectKind,
        id: String,
        type: KnowledgeSourceType,
        title: String,
        publisher: String,
        edition: String,
        licenseStatus: KnowledgeSourceLicenseStatus,
        licenseExpression: String,
    ): ReviewedKnowledgeSource =
        ReviewedKnowledgeSource(
            sourceId = id,
            subject = subject,
            sourceType = type,
            title = title,
            publisher = publisher,
            edition = edition,
            sourceUri = "https://example.invalid/$id",
            licenseStatus = licenseStatus,
            contentUsePolicy = KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY,
            contentFingerprint =
                (subject.ordinal * 2 + if (type == KnowledgeSourceType.TEXTBOOK) 2 else 1)
                    .toString(16)
                    .padStart(64, '0'),
            licenseExpression = licenseExpression,
            licenseUri = "https://example.invalid/$id/license",
            attributionText = "Reviewed fixture attribution.",
            reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
        )

    private fun material(
        subject: SubjectKind,
        suffix: String,
        type: KnowledgeTeachingMaterialType,
    ): ReviewedKnowledgeTeachingMaterial =
        ReviewedKnowledgeTeachingMaterial(
            materialId = materialId(subject, suffix),
            stableCode = stableNamespace(subject) + "material.$suffix",
            subject = subject,
            materialType = type,
            title = "${subject.name} $suffix fixture",
            summaryMarkdown = "Reviewed fixture summary.",
            applicabilityMarkdown = "Reviewed fixture applicability.",
            contentMarkdown = "Reviewed original fixture structure.",
            boundaryMarkdown = "Not an assessment item.",
            derivationKind = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS,
            sourceId = textbookSourceId(subject),
            sourceLocator = "fixture/material/${subject.name}/$suffix",
            contentFingerprint =
                (subject.ordinal * 2 + if (suffix == "method") 31 else 32)
                    .toString(16)
                    .padStart(64, '0'),
            reviewedAtEpochMillis = CONTENT_REVIEWED_AT,
        )

    private fun provenance(
        pack: ReviewedKnowledgePack,
    ): List<FormalKnowledgeSourceProvenance> =
        pack.sources.map { source ->
            val licenseClass =
                when (source.licenseStatus) {
                    KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL ->
                        KnowledgeSourceLicenseClass.PUBLIC_OFFICIAL_METADATA
                    KnowledgeSourceLicenseStatus.REFERENCE_ONLY ->
                        KnowledgeSourceLicenseClass.REFERENCE_ONLY
                    KnowledgeSourceLicenseStatus.LICENSED ->
                        KnowledgeSourceLicenseClass.PERMISSIVE
                }
            val version = requireNotNull(source.edition)
            FormalKnowledgeSourceProvenance(
                admissionEvidence =
                    KnowledgeSourceAdmissionEvidence(
                        sourceId = source.sourceId,
                        sourceVersion = version,
                        sourceRecordFingerprint =
                            FormalKnowledgePackActivationPolicy
                                .canonicalSourceRecordFingerprint(source, version),
                        canonicalLocator = requireNotNull(source.sourceUri),
                        publisher = requireNotNull(source.publisher),
                        resourceTitle = source.title,
                        licenseClass = licenseClass,
                        licenseExpression = source.licenseExpression,
                        licenseEvidenceLocator = source.licenseUri,
                        licenseEvidenceFingerprint =
                            (source.subject.ordinal + 101)
                                .toString(16)
                                .padStart(64, '0'),
                        evidenceOrigin = KnowledgeSourceEvidenceOrigin.HUMAN_VERIFIED,
                        automationPermission =
                            KnowledgeSourceAutomationPermission.METADATA_ONLY,
                        reviewStatus = KnowledgeArtifactReviewStatus.HUMAN_REVIEWED,
                        reviewRecordId = "review.source.${source.sourceId}",
                        reviewedAtEpochMillis = SOURCE_EVIDENCE_REVIEWED_AT,
                    ),
                sourceContentFingerprint = source.contentFingerprint,
                retrievedAtEpochMillis = SOURCE_RETRIEVED_AT,
                editionEvidenceLocator =
                    if (source.sourceType == KnowledgeSourceType.TEXTBOOK) {
                        "${source.sourceUri}/edition"
                    } else {
                        null
                    },
                editionEvidenceFingerprint =
                    if (source.sourceType == KnowledgeSourceType.TEXTBOOK) {
                        (source.subject.ordinal + 201).toString(16).padStart(64, '0')
                    } else {
                        null
                    },
            )
        }

    private fun mappings(): List<FormalKnowledgeSubjectMapping> =
        formalSubjects().map { subject ->
            FormalKnowledgeSubjectMapping(
                subject = subject,
                officialStandardCode = standardCode(subject),
                rootNodeStableCode = rootStableCode(subject),
                stableCodeNamespace = stableNamespace(subject),
                curriculumSourceId = curriculumSourceId(subject),
                textbookEditionSourceIds = listOf(textbookSourceId(subject)),
            )
        }

    private fun derivedCoverageProofV2(
        pack: ReviewedKnowledgePack,
        sourceProvenance: List<FormalKnowledgeSourceProvenance>,
    ): FormalKnowledgePackCoverageProofV2 =
        FormalKnowledgePackActivationPolicy.deriveCoverageProofV2(
            pack = pack,
            coverageLedgerId = "coverage-ledger.fixture.v2",
            targetBaselineId = "target-baseline.fixture.v2",
            coverageLedgerFingerprint = COVERAGE_LEDGER_FINGERPRINT,
            coverageReviewRecordId = "review.coverage.fixture.v2",
            coverageReviewedAtEpochMillis = CONTENT_REVIEWED_AT,
            humanReviewSummaryFingerprint = HUMAN_REVIEW_SUMMARY_FINGERPRINT,
            sourceLicenseReviewSummaryFingerprint =
                FormalKnowledgePackActivationPolicy
                    .sourceLicenseReviewSummaryFingerprint(sourceProvenance),
        )

    private fun rebuild(
        original: ReviewedKnowledgePack,
        nodes: List<ReviewedKnowledgeNode> = original.nodes,
        relations: List<ReviewedKnowledgeRelation> = original.relations,
        teachingMaterials: List<ReviewedKnowledgeTeachingMaterial> =
            original.teachingMaterials,
        teachingMaterialBindings: List<ReviewedKnowledgeTeachingMaterialBinding> =
            original.teachingMaterialBindings,
    ): ReviewedKnowledgePack =
        ReviewedKnowledgePack(
            metadata = original.metadata,
            nodes = nodes,
            sources = original.sources,
            nodeSourceBindings = original.nodeSourceBindings,
            relations = relations,
            teachingMaterials = teachingMaterials,
            teachingMaterialBindings = teachingMaterialBindings,
        )

    private data class Fixture(
        val pack: ReviewedKnowledgePack,
        val candidate: FormalKnowledgePackCandidate,
        val review: FormalKnowledgePackIndependentReview,
        val proof: FormalKnowledgePackActivationProof,
    )

    private companion object {
        const val PACK_ID = "formal.fixture.v1"
        const val PACK_VERSION = "formal-content-v1"
        const val TAXONOMY_VERSION = "formal-taxonomy-v1"
        const val SEARCH_VERSION = "formal-search-v1"
        const val PACK_FINGERPRINT =
            "abababababababababababababababababababababababababababababababab"
        const val COVERAGE_LEDGER_FINGERPRINT =
            "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
        const val HUMAN_REVIEW_SUMMARY_FINGERPRINT =
            "efefefefefefefefefefefefefefefefefefefefefefefefefefefefefefefef"
        const val PRODUCER_ID = "organization.corpus-producer"
        const val REVIEWER_ID = "organization.independent-reviewer"
        const val SIGNING_KEY_ID = "formal-release-key-fixture"
        const val SOURCE_RETRIEVED_AT = 100L
        const val SOURCE_EVIDENCE_REVIEWED_AT = 110L
        const val CONTENT_REVIEWED_AT = 120L
        const val CANDIDATE_AT = 200L
        const val REVIEW_AT = 300L
        const val SIGNED_AT = 400L
        const val ACTIVATION_GENERATION = 901L

        val SHA_256 = Regex("[0-9a-f]{64}")
        val TEST_KEY_PAIR: KeyPair by lazy {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2_048) }.generateKeyPair()
        }
        val TRUSTED_KEY: FormalKnowledgePackSigningKey by lazy {
            FormalKnowledgePackSigningKey(
                keyId = SIGNING_KEY_ID,
                algorithm = FormalKnowledgeSignatureAlgorithm.SHA256_WITH_RSA_2048,
                x509PublicKeyHex = TEST_KEY_PAIR.public.encoded.toLowerHex(),
                validFromEpochMillis = 0L,
                validUntilEpochMillis = 1_000L,
            )
        }

        fun formalSubjects(): List<SubjectKind> =
            enumValues<SubjectKind>().filter { subject -> subject != SubjectKind.GENERAL }

        fun stableNamespace(subject: SubjectKind): String =
            "hs.${subject.name.lowercase()}."

        fun rootStableCode(subject: SubjectKind): String = stableNamespace(subject) + "root"

        fun rootNodeId(subject: SubjectKind): String =
            "node.${subject.name.lowercase()}.root"

        fun atomicNodeId(subject: SubjectKind): String =
            "node.${subject.name.lowercase()}.sample"

        fun atomicName(subject: SubjectKind): String = "${subject.name} reviewed knowledge"

        fun secondaryAtomicNodeId(subject: SubjectKind): String =
            "node.${subject.name.lowercase()}.secondary"

        fun secondaryAtomicName(subject: SubjectKind): String =
            "${subject.name} secondary reviewed knowledge"

        fun curriculumSourceId(subject: SubjectKind): String =
            "source.${subject.name.lowercase()}.curriculum"

        fun textbookSourceId(subject: SubjectKind): String =
            "source.${subject.name.lowercase()}.textbook"

        fun materialId(
            subject: SubjectKind,
            suffix: String,
        ): String = "material.${subject.name.lowercase()}.$suffix"

        fun standardCode(subject: SubjectKind): String =
            when (subject) {
                SubjectKind.CHINESE -> "SB0101"
                SubjectKind.ENGLISH -> "SB0102"
                SubjectKind.MATH -> "SB0201"
                SubjectKind.HISTORY -> "SB0307"
                SubjectKind.GEOGRAPHY -> "SB0308"
                SubjectKind.POLITICS -> "SB0310"
                SubjectKind.PHYSICS -> "SB0401"
                SubjectKind.CHEMISTRY -> "SB0402"
                SubjectKind.BIOLOGY -> "SB0403"
                SubjectKind.GENERAL -> error("Formal fixture requires a specific subject")
            }
    }
}
