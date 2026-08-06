package com.tingyun.smartmistakebook.core.student.mistake.database

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentProblemOrganizationReviewAuthorityContractTest {
    @Test
    fun runtimeDoesNotExposeTheRawReviewIssuer() {
        val issuerClass = StudentProblemOrganizationReviewAuthority.Issuer::class.java
        val runtimePublicMethods =
            StudentMistakeRuntimeCapabilities::class.java.methods.filterNot {
                it.isSynthetic
            }

        assertTrue(
            runtimePublicMethods.none { method ->
                method.returnType == issuerClass ||
                    method.parameterTypes.any(issuerClass::equals)
            },
        )
        assertFalse(
            Modifier.isPublic(
                StudentMistakeRuntimeCapabilities::class.java
                    .getDeclaredMethod("organizationReviewIssuer")
                    .modifiers,
            ),
        )
        assertFalse(
            Modifier.isPublic(
                StudentMistakeRuntimeCapabilities::class.java
                    .declaredMethods
                    .single { it.name == "verifiesKnowledgeReference" }
                    .modifiers,
            ),
        )
        assertFalse(
            Modifier.isPublic(
                StudentProblemOrganizationReviewAuthority::class.java
                    .getDeclaredMethod("getIssuer")
                    .modifiers,
            ),
        )
        assertFalse(
            Modifier.isPublic(
                issuerClass.declaredMethods.single { it.name == "issue" }.modifiers,
            ),
        )
    }

    @Test
    fun proofSealCannotSurviveAnAuthorityProcessRestart() {
        val firstProcess =
            StudentProblemOrganizationReviewAuthority.create(
                "review-owner",
                "v1",
            )
        val proof = firstProcess.issuer.issueReviewedFixture()
        assertTrue(firstProcess.verifier.verifies(proof))
        assertTrue(proof.reviewedOutputCanonicalFingerprint == "c".repeat(64))
        assertTrue(
            proof.mappingPolicyVersion ==
                STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION,
        )
        assertTrue(proof.knowledgeManifestFingerprint == "d".repeat(64))
        assertTrue(proof.knowledgeActivationGeneration == 7L)
        assertTrue(proof.finalCommandCanonicalFingerprint == "c".repeat(64))

        val restartedProcess =
            StudentProblemOrganizationReviewAuthority.create(
                "review-owner",
                "v1",
            )
        val rotatedProcess =
            StudentProblemOrganizationReviewAuthority.create(
                "review-owner",
                "v2",
            )
        assertFalse(restartedProcess.verifier.verifies(proof))
        assertFalse(rotatedProcess.verifier.verifies(proof))
        assertTrue(
            StudentProblemOrganizationReviewAuthority.Proof::class.java
                .declaredConstructors
                .all { Modifier.isPrivate(it.modifiers) },
        )
        assertTrue(
            StudentProblemOrganizationReviewAuthority.Proof::class.java
                .declaredMethods
                .none { it.name == "copy" || it.name.startsWith("component") },
        )
    }

    @Test
    fun splitPackageCoordinatorReviewsBeforeItSigns() {
        val root = projectRoot()
        val bridge =
            File(
                root,
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "CoreDataStudentMistakeOwnerBridge.java",
            ).readText()
        val reviewCall = bridge.indexOf("requireLocallyReviewedOrganization(command, reviewedTask)")
        val exactMappingCall =
            bridge.indexOf(
                "ReviewedStudentProblemOrganizationMapping.requireExact(",
                reviewCall,
            )
        val issueCall = bridge.indexOf("organizationReviewIssuer().issue(", reviewCall)

        assertTrue(reviewCall >= 0)
        assertTrue(exactMappingCall > reviewCall)
        assertTrue(issueCall > reviewCall)
        assertTrue(issueCall > exactMappingCall)
        assertTrue(
            "ModelTaskCompletionValidator.INSTANCE.requireValid(" in bridge,
        )
        assertTrue("ModelTaskFingerprint.INSTANCE.of(request)" in bridge)
        assertTrue("ProblemOrganizationV3Input input" in bridge)

        val ownerAccess =
            File(
                root,
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/" +
                    "authority/StudentMistakeOwnerAccess.java",
            ).readText()
        assertTrue("reviewAndIssueStudentProblemOrganization" in ownerAccess)
        assertTrue("mapAndReviewStudentProblemOrganization" in ownerAccess)
        assertTrue("ModelTaskSnapshot reviewedTask" in ownerAccess)
        assertFalse("organizationReviewIssuer()" in ownerAccess)
    }

    private fun StudentProblemOrganizationReviewAuthority.Issuer.issueReviewedFixture():
        StudentProblemOrganizationReviewAuthority.Proof =
        issue(
            "learner-1",
            "problem-1",
            "revision-1",
            1,
            "a".repeat(64),
            "request-1",
            "b".repeat(64),
            1,
            "provider-1",
            "model-1",
            "model-version-1",
            "provider-config-1",
            6,
            3,
            "c".repeat(64),
            STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION,
            "d".repeat(64),
            7L,
            "c".repeat(64),
            StudentProblemOrganizationReviewSource.LOCAL_POLICY_ACCEPTED.name,
            "review-v1",
            "review-owner",
            "v1",
            100,
            200,
        )

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")
}
