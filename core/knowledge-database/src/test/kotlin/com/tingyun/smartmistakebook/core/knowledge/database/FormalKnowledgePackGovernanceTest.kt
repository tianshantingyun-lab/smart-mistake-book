package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalKnowledgePackGovernanceTest {
    @Test
    fun unknownModelDeclaredAndAutomationForbiddenSourcesFailClosed() {
        val reviewed = admissible()

        listOf(
            reviewed.copy(licenseClass = KnowledgeSourceLicenseClass.UNKNOWN),
            reviewed.copy(evidenceOrigin = KnowledgeSourceEvidenceOrigin.MODEL_DECLARATION),
            reviewed.copy(automationPermission = KnowledgeSourceAutomationPermission.FORBIDDEN),
            reviewed.copy(automationPermission = KnowledgeSourceAutomationPermission.UNKNOWN),
            reviewed.copy(licenseEvidenceFingerprint = null),
            reviewed.copy(reviewStatus = KnowledgeArtifactReviewStatus.PENDING),
        ).forEach { candidate ->
            assertEquals(
                KnowledgeSourceAdmissionRoute.BLOCKED,
                KnowledgeSourceAdmissionPolicy.evaluate(candidate),
            )
            assertThrows(IllegalStateException::class.java) {
                KnowledgeSourceAdmissionPolicy.requireCoreAutoAdmission(candidate)
            }
        }
    }

    @Test
    fun nonCommercialAndNoDerivativesSourcesNeverEnterCoreAutomatically() {
        val reviewed = admissible()

        listOf(
            KnowledgeSourceLicenseClass.NON_COMMERCIAL,
            KnowledgeSourceLicenseClass.NO_DERIVATIVES,
            KnowledgeSourceLicenseClass.REFERENCE_ONLY,
        ).forEach { licenseClass ->
            assertEquals(
                KnowledgeSourceAdmissionRoute.HUMAN_REFERENCE_ONLY,
                KnowledgeSourceAdmissionPolicy.evaluate(
                    reviewed.copy(licenseClass = licenseClass),
                ),
            )
        }
        assertEquals(
            KnowledgeSourceAdmissionRoute.SEPARATE_SHARE_ALIKE_PACK,
            KnowledgeSourceAdmissionPolicy.evaluate(
                reviewed.copy(licenseClass = KnowledgeSourceLicenseClass.SHARE_ALIKE),
            ),
        )
    }

    @Test
    fun onlyCompleteHumanVerifiedPermissiveEvidenceEntersCoreAutomatically() {
        assertEquals(
            KnowledgeSourceAdmissionRoute.ALLOW_CORE_AUTO,
            KnowledgeSourceAdmissionPolicy.evaluate(admissible()),
        )
        assertEquals(
            KnowledgeSourceAdmissionRoute.ALLOW_METADATA_ONLY,
            KnowledgeSourceAdmissionPolicy.evaluate(
                admissible().copy(
                    licenseClass = KnowledgeSourceLicenseClass.PUBLIC_OFFICIAL_METADATA,
                    automationPermission = KnowledgeSourceAutomationPermission.METADATA_ONLY,
                ),
            ),
        )
    }

    @Test
    fun formalSkeletonNamesAllNineSubjectsWithoutClaimingCoverage() {
        val manifest = FormalHighSchoolKnowledgeSkeletonManifest

        assertFalse(manifest.PRODUCTION_CUTOVER_ELIGIBLE)
        assertEquals(
            setOf(
                "CHINESE:SB0101",
                "ENGLISH:SB0102",
                "MATH:SB0201",
                "HISTORY:SB0307",
                "GEOGRAPHY:SB0308",
                "POLITICS:SB0310",
                "PHYSICS:SB0401",
                "CHEMISTRY:SB0402",
                "BIOLOGY:SB0403",
            ),
            manifest.subjects.mapTo(mutableSetOf()) {
                "${it.subject.name}:${it.officialStandardCode}"
            },
        )
        assertEquals(9, manifest.subjects.size)
        assertTrue(manifest.subjects.none { it.subject == SubjectKind.GENERAL })
        assertTrue(
            manifest.subjects.all {
                    it.coverage == FormalKnowledgeCoverage.SKELETON_ONLY &&
                    it.completeness == FormalKnowledgeCompleteness.INCOMPLETE &&
                    it.declaredReviewedKnowledgeNodeCount == 0 &&
                    it.rootNodeTrace.contentFingerprint.matches(SHA_256) &&
                    it.rootNodeTrace.reviewStatus == KnowledgeArtifactReviewStatus.PENDING &&
                    it.rootNodeTrace.reviewRecordId == null &&
                    it.rootNodeTrace.reviewedAtEpochMillis == null &&
                    it.officialSource.canonicalLocator ==
                    FormalHighSchoolKnowledgeSkeletonManifest.OFFICIAL_NOTICE_LOCATOR &&
                    it.officialSource.licenseEvidenceLocator == null &&
                    it.officialSource.licenseEvidenceFingerprint == null &&
                    it.officialSource.reviewStatus == KnowledgeArtifactReviewStatus.PENDING &&
                    it.officialSource.evidenceOrigin ==
                    KnowledgeSourceEvidenceOrigin.MODEL_DECLARATION &&
                    KnowledgeSourceAdmissionPolicy.evaluate(it.officialSource) ==
                    KnowledgeSourceAdmissionRoute.BLOCKED
            },
        )
        assertTrue(manifest.contentFingerprint.matches(SHA_256))
        manifest.subjects.forEach {
            assertThrows(IllegalStateException::class.java) {
                KnowledgeSourceAdmissionPolicy.requireCoreAutoAdmission(it.officialSource)
            }
        }
    }

    @Test
    fun pendingArtifactCannotCarryInventedHumanReviewMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeArtifactTrace(
                artifactId = "knowledge.pending",
                artifactVersion = "2026-v1",
                contentFingerprint = "3".repeat(64),
                reviewStatus = KnowledgeArtifactReviewStatus.PENDING,
                reviewRecordId = "review.not-real",
                reviewedAtEpochMillis = 1L,
            )
        }
    }

    @Test
    fun governanceTypesCannotCarryStudentMistakeOrMasteryPayloads() {
        val governedTypes =
            listOf(
                KnowledgeArtifactTrace::class.java,
                KnowledgeSourceAdmissionEvidence::class.java,
                FormalHighSchoolSubjectSkeleton::class.java,
            )
        val forbidden =
            listOf(
                "student",
                "learner",
                "problem",
                "question",
                "answer",
                "mistake",
                "mastery",
                "attempt",
                "conversation",
                "chat",
            )

        governedTypes.forEach { type ->
            val surface =
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
                assertFalse("${type.simpleName} leaks '$token'", token in surface)
            }
        }
        assertNotEquals(
            FormalHighSchoolKnowledgeSkeletonManifest.subjects.first().rootNodeTrace
                .contentFingerprint,
            FormalHighSchoolKnowledgeSkeletonManifest.subjects.first().officialSource
                .sourceRecordFingerprint,
        )
    }

    private fun admissible(): KnowledgeSourceAdmissionEvidence =
        KnowledgeSourceAdmissionEvidence(
            sourceId = "source.permissive",
            sourceVersion = "2026-v1",
            sourceRecordFingerprint = "1".repeat(64),
            canonicalLocator = "https://example.invalid/resource",
            publisher = "Example publisher",
            resourceTitle = "Example resource",
            licenseClass = KnowledgeSourceLicenseClass.PERMISSIVE,
            licenseExpression = "CC-BY-4.0",
            licenseEvidenceLocator = "https://example.invalid/license",
            licenseEvidenceFingerprint = "2".repeat(64),
            evidenceOrigin = KnowledgeSourceEvidenceOrigin.HUMAN_VERIFIED,
            automationPermission = KnowledgeSourceAutomationPermission.ALLOWED,
            reviewStatus = KnowledgeArtifactReviewStatus.HUMAN_REVIEWED,
            reviewRecordId = "review.2026-v1",
            reviewedAtEpochMillis = 1L,
        )

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
