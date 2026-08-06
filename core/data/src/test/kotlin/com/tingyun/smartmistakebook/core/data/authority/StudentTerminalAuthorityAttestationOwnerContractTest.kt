package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentAuthorityVerifiedAttestationPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverAttestationProgress
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationAttestation
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationAttestationPorts
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationBinding
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationStage
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverVerificationCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentDocumentImportReceiptReference
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentIndexesRebuiltAttestation
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentIndexesRebuiltAttestationPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOutboxReconciledAttestation
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOutboxReconciledAttestationPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StudentTerminalAuthorityAttestationOwnerContractTest {
    @Test
    fun journalSnapshotCopiesOwnerProofFieldsWithoutRecomputation() {
        val binding = studentBinding()
        val attestation =
            FakeStudentAttestation(
                stage =
                    StudentCutoverDestinationStage
                        .STUDENT_OUTBOX_RECONCILED,
                binding = binding,
                verifiedRecordCount = 37L,
                destinationSnapshotFingerprint = sha("destination"),
                verificationFingerprint = sha("verification"),
                issuedAtEpochMillis = 1_000L,
                attestationFingerprint = sha("attestation"),
            )

        val snapshot =
            snapshotFromStudentOwnerAttestation(
                attestation = attestation,
                expectedStage =
                    StudentCutoverDestinationStage
                        .STUDENT_OUTBOX_RECONCILED,
                binding = binding,
                nowEpochMillis = 1_000L,
            )

        assertEquals(attestation.verifiedRecordCount, snapshot.migratedRecordCount)
        assertEquals(attestation.verificationFingerprint, snapshot.sourceCheckpoint)
        assertEquals(
            attestation.destinationSnapshotFingerprint,
            snapshot.destinationFingerprint,
        )
    }

    @Test
    fun expiredFutureForeignAndWrongStageProofsFailClosed() {
        val binding = studentBinding()
        val valid =
            FakeStudentAttestation(
                stage =
                    StudentCutoverDestinationStage
                        .STUDENT_INDEXES_REBUILT,
                binding = binding,
                issuedAtEpochMillis = 1_000_000L,
            )
        val failures =
            listOf(
                valid.copy(issuedAtEpochMillis = 699_999L),
                valid.copy(issuedAtEpochMillis = 1_030_001L),
                valid.copy(legacyPrefixFingerprint = sha("foreign-prefix")),
                valid.copy(
                    stage =
                        StudentCutoverDestinationStage
                            .STUDENT_OUTBOX_RECONCILED,
                ),
            )

        failures.forEach { attestation ->
            assertThrows(IllegalStateException::class.java) {
                snapshotFromStudentOwnerAttestation(
                    attestation = attestation,
                    expectedStage =
                        StudentCutoverDestinationStage
                            .STUDENT_INDEXES_REBUILT,
                    binding = binding,
                    nowEpochMillis = 1_000_000L,
                )
            }
        }
    }

    @Test
    fun stageFiveBindingRejectsWrongKindDestinationPredecessorAndPrefixBeforeOwnerBind() {
        val layout = ThreeAuthorityDatabaseLayout()
        val binding =
            TerminalAuthorityCutoverBinding(
                cutoverGeneration = 7L,
                legacyPrefixReceiptFingerprint = sha("prefix"),
            )
        var binderCalls = 0
        val owner =
            ProductionStudentTerminalAuthorityAttestationOwner(
                layout = layout,
                ports = ThrowingStudentAttestationPorts,
                receiptBinder =
                    StudentDocumentImportReceiptReferenceBinder {
                            _,
                            _,
                            _,
                            _,
                            _,
                            _,
                        ->
                        binderCalls += 1
                        error("The owner binder must not receive invalid stage-five evidence")
                    },
                clock = { 1_000L },
            )
        val validCheckpoint =
            "generation:7:prefix:${binding.legacyPrefixReceiptFingerprint}:source:source"
        val invalidReceipts =
            listOf(
                receipt(
                    stage =
                        ThreeAuthorityCutoverStage
                            .STUDENT_OUTBOX_RECONCILED,
                    target = layout.studentMistakes,
                    previous = binding.legacyPrefixReceiptFingerprint,
                    checkpoint = validCheckpoint,
                ),
                receipt(
                    stage =
                        ThreeAuthorityCutoverStage
                            .STUDENT_DOCUMENTS_IMPORTED,
                    target = layout.learnerMastery,
                    previous = binding.legacyPrefixReceiptFingerprint,
                    checkpoint = validCheckpoint,
                ),
                receipt(
                    stage =
                        ThreeAuthorityCutoverStage
                            .STUDENT_DOCUMENTS_IMPORTED,
                    target = layout.studentMistakes,
                    previous = sha("wrong-predecessor"),
                    checkpoint = validCheckpoint,
                ),
                receipt(
                    stage =
                        ThreeAuthorityCutoverStage
                            .STUDENT_DOCUMENTS_IMPORTED,
                    target = layout.studentMistakes,
                    previous = binding.legacyPrefixReceiptFingerprint,
                    checkpoint = "source-only",
                ),
            )

        invalidReceipts.forEach { invalid ->
            assertThrows(IllegalStateException::class.java) {
                owner.bindDocumentImportReceipt(binding, invalid)
            }
        }
        assertEquals(0, binderCalls)
    }

    private data class FakeStudentAttestation(
        override val stage: StudentCutoverDestinationStage,
        private val binding: StudentCutoverDestinationBinding,
        override val verifiedRecordCount: Long = 1L,
        override val destinationSnapshotFingerprint: String = sha("destination"),
        override val verificationFingerprint: String = sha("verification"),
        override val issuedAtEpochMillis: Long,
        override val attestationFingerprint: String = sha("attestation"),
        override val cutoverGeneration: Long = binding.cutoverGeneration,
        override val legacyPrefixFingerprint: String =
            binding.legacyPrefixFingerprint,
        override val studentDestinationFingerprint: String =
            binding.studentDestinationFingerprint,
        override val studentSchemaVersion: Int = binding.studentSchemaVersion,
        override val attestationPolicyVersion: String =
            binding.attestationPolicyVersion,
    ) : StudentCutoverDestinationAttestation

    private object ThrowingStudentAttestationPorts :
        StudentCutoverDestinationAttestationPorts {
        override val outboxReconciled: StudentOutboxReconciledAttestationPort =
            object : StudentOutboxReconciledAttestationPort {
                override suspend fun verifyOutboxNext(
                    binding: StudentCutoverDestinationBinding,
                    cursor: StudentCutoverVerificationCursor?,
                    limit: Int,
                ): StudentCutoverAttestationProgress<StudentOutboxReconciledAttestation> =
                    error("Unexpected outbox scan")
            }
        override val indexesRebuilt: StudentIndexesRebuiltAttestationPort =
            object : StudentIndexesRebuiltAttestationPort {
                override suspend fun verifyIndexesNext(
                    binding: StudentCutoverDestinationBinding,
                    cursor: StudentCutoverVerificationCursor?,
                    limit: Int,
                ): StudentCutoverAttestationProgress<StudentIndexesRebuiltAttestation> =
                    error("Unexpected index scan")
            }
        override val authorityVerified: StudentAuthorityVerifiedAttestationPort =
            object : StudentAuthorityVerifiedAttestationPort {
                override suspend fun attest(
                    binding: StudentCutoverDestinationBinding,
                    documentImportReceipt: StudentDocumentImportReceiptReference,
                    outboxReconciled: StudentOutboxReconciledAttestation,
                    indexesRebuilt: StudentIndexesRebuiltAttestation,
                ) = error("Unexpected authority attestation")
            }

        override fun close() = Unit
    }

    private companion object {
        fun studentBinding(): StudentCutoverDestinationBinding =
            StudentCutoverDestinationBinding(
                cutoverGeneration = 7L,
                legacyPrefixFingerprint = sha("prefix"),
                studentDestinationFingerprint = sha("student-destination"),
                studentSchemaVersion = 12,
                attestationPolicyVersion =
                    "student-cutover-destination-attestation-v1",
            )

        fun receipt(
            stage: ThreeAuthorityCutoverStage,
            target: String,
            previous: String,
            checkpoint: String,
        ): AuthorityStageReceipt =
            AuthorityStageReceipt.create(
                stage = stage,
                targetDatabaseName = target,
                migratedRecordCount = 1L,
                sourceCheckpoint = checkpoint,
                destinationFingerprint = sha("stage-five-destination"),
                completedAtEpochMillis = 1_000L,
                previousReceiptFingerprint = previous,
            )

        fun sha(value: String): String =
            CanonicalSha256(
                "student-terminal-authority-attestation-owner-contract-test",
            )
                .field("value", value)
                .finish()
    }
}
