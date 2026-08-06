package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeCutoverControlContractTest {
    @Test
    fun canonicalArtifactsMatchTheCoreDataProtocol() {
        val studentEvidence = "a".repeat(64)
        val masteryEvidence = "b".repeat(64)
        val fence =
            StudentMistakeAuthorityCutoverFence.create(
                cutoverGeneration = 9,
                studentImportEvidenceFingerprint = studentEvidence,
                masteryImportEvidenceFingerprint = masteryEvidence,
            )
        val expectedIntent =
            CanonicalSha256("three-authority-cutover-intent-v1")
                .field("cutoverGeneration", 9L)
                .field("studentImportEvidenceFingerprint", studentEvidence)
                .field("masteryImportEvidenceFingerprint", masteryEvidence)
                .finish()
        val expectedFence =
            CanonicalSha256("authority-cutover-fence-v1")
                .field("authority", "STUDENT_MISTAKES")
                .field("cutoverGeneration", 9L)
                .field("studentImportEvidenceFingerprint", studentEvidence)
                .field("masteryImportEvidenceFingerprint", masteryEvidence)
                .field("cutoverIntentFingerprint", expectedIntent)
                .finish()

        assertEquals(expectedIntent, fence.cutoverIntentFingerprint)
        assertEquals(expectedFence, fence.fenceFingerprint)
        assertTrue(fence.hasValidFingerprint())

        val receipt = StudentMistakeAuthorityCutoverCompletionReceipt.create(fence)
        assertEquals(
            CanonicalSha256("authority-cutover-completion-receipt-v1")
                .field("authority", "STUDENT_MISTAKES")
                .field("cutoverGeneration", 9L)
                .field("cutoverIntentFingerprint", expectedIntent)
                .field("authorityFenceFingerprint", expectedFence)
                .finish(),
            receipt.receiptFingerprint,
        )
        assertTrue(receipt.hasValidFingerprint())
    }

    @Test
    fun canonicalArtifactsDetectTampering() {
        val fence =
            StudentMistakeAuthorityCutoverFence.create(
                cutoverGeneration = 1,
                studentImportEvidenceFingerprint = "a".repeat(64),
                masteryImportEvidenceFingerprint = "b".repeat(64),
            )
        assertFalse(
            fence.copy(fenceFingerprint = "c".repeat(64)).hasValidFingerprint(),
        )

        val receipt = StudentMistakeAuthorityCutoverCompletionReceipt.create(fence)
        assertFalse(
            receipt.copy(cutoverGeneration = 2).hasValidFingerprint(),
        )

        val challenge =
            StudentMistakeDestinationReattestationChallenge(
                migrationId = "migration-1",
                revisionId = "revision-1",
                legacyDestinationRecordCanonicalFingerprint = "d".repeat(64),
                replacementDestinationRecordCanonicalFingerprint = "e".repeat(64),
                canonicalPolicyVersion = 3,
            )
        val reattestation =
            StudentMistakeDestinationReattestationReceipt.create(
                challenge = challenge,
                issuerKeyId = "terminal-owner",
                issuerVersion = "v1",
                issuedAtEpochMillis = 10,
            )
        assertTrue(reattestation.hasValidFingerprint())
        assertFalse(
            reattestation
                .copy(replacementDestinationRecordCanonicalFingerprint = "f".repeat(64))
                .hasValidFingerprint(),
        )
    }

    @Test
    fun ownerSurfaceHasNoMutationOrDatabaseEscapeHatches() {
        assertEquals(
            setOf(
                "appendCompletionReceiptIfAbsent",
                "appendCutoverFenceIfAbsent",
                "appendDestinationReattestationReceipt",
                "readCompletionReceipt",
                "readCutoverFence",
                "readPendingDestinationReattestations",
                "recomputeCompletedMigrationLedger",
            ),
            StudentMistakeCutoverControlPort::class.java.methods
                .filter {
                    it.declaringClass == StudentMistakeCutoverControlPort::class.java &&
                        !it.isSynthetic
                }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val forbiddenFragments = setOf("update", "delete", "reset", "sqlite", "room", "legacy")
        StudentMistakeCutoverControlPort::class.java.declaredMethods.forEach { method ->
            val signature =
                buildString {
                    append(method.name)
                    append(method.returnType.name)
                    method.parameterTypes.forEach { append(it.name) }
                }
            forbiddenFragments.forEach { fragment ->
                assertFalse(signature.contains(fragment, ignoreCase = true))
            }
        }
        val reattestationAppend =
            StudentMistakeCutoverControlPort::class.java.declaredMethods
                .single { it.name == "appendDestinationReattestationReceipt" }
        assertEquals(
            StudentMistakeDestinationReattestationReceipt::class.java,
            reattestationAppend.parameterTypes.first(),
        )

        val productionFactory =
            StudentMistakeCutoverControlPortFactory::class.java.declaredMethods
                .single { method ->
                    method.name == "open" &&
                        method.parameterTypes.lastOrNull() == StudentMistakeOwnerKey::class.java
                }
        assertEquals(
            StudentMistakeCutoverControlPort::class.java,
            productionFactory.returnType,
        )
        val bridgeMethod =
            CoreDataStudentMistakeOwnerBridge::class.java.declaredMethods
                .single { it.name == "openCutoverControl" }
        assertFalse(Modifier.isPublic(bridgeMethod.modifiers))
    }
}
