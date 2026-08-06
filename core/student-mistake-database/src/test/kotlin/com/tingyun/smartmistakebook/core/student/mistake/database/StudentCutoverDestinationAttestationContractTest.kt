package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.lang.reflect.Modifier
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StudentCutoverDestinationAttestationContractTest {
    @Test
    fun outboxAndInboxVerificationIsBoundedResumableAndOwnerIssued() = runBlocking {
        val source =
            FakeSource(
                rows =
                    mapOf(
                        StudentCutoverVerificationPhase.OUTBOX to
                            records("outbox", 5),
                        StudentCutoverVerificationPhase.INBOX to
                            records("inbox", 3),
                    ),
            )
        val now = AtomicLong(4_000)
        val engine = engine(source, now)

        val first =
            engine.outboxReconciled.verifyOutboxNext(
                binding = BINDING,
                limit = 2,
            ) as StudentCutoverAttestationProgress.Continue
        assertEquals(2L, first.cursor.verifiedRecordCount)
        assertEquals(
            StudentCutoverDestinationStage.STUDENT_OUTBOX_RECONCILED,
            first.cursor.stage,
        )

        val attestation = drainOutbox(engine, first.cursor, limit = 2)
        assertEquals(8L, attestation.verifiedRecordCount)
        assertEquals(4_000L, attestation.issuedAtEpochMillis)
        assertEquals(JOURNAL_FINGERPRINT, attestation.destinationSnapshotFingerprint)
        assertTrue(attestation.hasValidFingerprint())
        assertEquals(BINDING.cutoverGeneration, attestation.cutoverGeneration)
        assertEquals(
            BINDING.studentDestinationFingerprint,
            attestation.studentDestinationFingerprint,
        )
        assertTrue(source.pageLimits.all { it <= 3 })
    }

    @Test
    fun stageProofBindsTheExactPagedRecordSequenceNotOnlyItsCount() = runBlocking {
        val first =
            drainOutbox(
                engine(
                    FakeSource(
                        rows =
                            mapOf(
                                StudentCutoverVerificationPhase.OUTBOX to
                                    records("first", 2),
                            ),
                    ),
                ),
                cursor = null,
                limit = 1,
            )
        val second =
            drainOutbox(
                engine(
                    FakeSource(
                        rows =
                            mapOf(
                                StudentCutoverVerificationPhase.OUTBOX to
                                    records("second", 2),
                            ),
                    ),
                ),
                cursor = null,
                limit = 1,
            )

        assertEquals(first.verifiedRecordCount, second.verifiedRecordCount)
        assertEquals(
            first.destinationSnapshotFingerprint,
            second.destinationSnapshotFingerprint,
        )
        assertNotEquals(first.verificationFingerprint, second.verificationFingerprint)
        assertNotEquals(first.attestationFingerprint, second.attestationFingerprint)
    }

    @Test
    fun cursorCannotCrossStageGenerationPrefixOrDestination() = runBlocking {
        val engine =
            engine(
                FakeSource(
                    rows =
                        mapOf(
                            StudentCutoverVerificationPhase.OUTBOX to records("outbox", 2),
                        ),
                ),
            )
        val cursor =
            (
                engine.outboxReconciled.verifyOutboxNext(
                    binding = BINDING,
                    limit = 1,
                ) as StudentCutoverAttestationProgress.Continue
            ).cursor

        assertIllegalArgument {
            runBlocking {
                engine.indexesRebuilt.verifyIndexesNext(
                    binding = BINDING,
                    cursor = cursor,
                    limit = 1,
                )
            }
        }
        listOf(
            BINDING.copy(cutoverGeneration = 8),
            BINDING.copy(legacyPrefixFingerprint = fingerprint("other-prefix")),
            BINDING.copy(studentDestinationFingerprint = fingerprint("other-destination")),
        ).forEach { changed ->
            assertIllegalArgument {
                runBlocking {
                    engine.outboxReconciled.verifyOutboxNext(
                        binding = changed,
                        cursor = cursor,
                        limit = 1,
                    )
                }
            }
        }
    }

    @Test
    fun changedDestinationSnapshotInvalidatesAResumedScan() = runBlocking {
        val source =
            FakeSource(
                rows =
                    mapOf(
                        StudentCutoverVerificationPhase.OUTBOX to records("outbox", 2),
                    ),
            )
        val engine = engine(source)
        val cursor =
            (
                engine.outboxReconciled.verifyOutboxNext(
                    binding = BINDING,
                    limit = 1,
                ) as StudentCutoverAttestationProgress.Continue
            ).cursor
        source.journalFingerprint = fingerprint("changed-journal")

        assertIllegalState {
            runBlocking {
                drainOutbox(engine, cursor, limit = 1)
            }
        }
    }

    @Test
    fun authorityVerificationRequiresStageFiveReceiptAndExactSixSevenProofs() =
        runBlocking {
            val source =
                FakeSource(
                    rows =
                        mapOf(
                            StudentCutoverVerificationPhase.OUTBOX to records("outbox", 1),
                            StudentCutoverVerificationPhase.INBOX to records("inbox", 1),
                            StudentCutoverVerificationPhase.SEARCH to records("search", 2),
                            StudentCutoverVerificationPhase.CLASSIFICATION to
                                records("classification", 2),
                            StudentCutoverVerificationPhase.IDENTITY to
                                records("identity", 2),
                        ),
                )
            val engine = engine(source)
            val outbox = drainOutbox(engine, cursor = null, limit = 2)
            val indexes = drainIndexes(engine, cursor = null, limit = 2)

            val attestation =
                engine.authorityVerified.attest(
                    binding = BINDING,
                    documentImportReceipt = DOCUMENT_IMPORT_RECEIPT,
                    outboxReconciled = outbox,
                    indexesRebuilt = indexes,
                )

            assertTrue(attestation.hasValidFingerprint())
            assertEquals(
                DOCUMENT_IMPORT_RECEIPT.receiptFingerprint,
                attestation.documentImportReceiptFingerprint,
            )
            assertEquals(
                outbox.attestationFingerprint,
                attestation.outboxReconciledAttestationFingerprint,
            )
            assertEquals(
                indexes.attestationFingerprint,
                attestation.indexesRebuiltAttestationFingerprint,
            )
            assertEquals(13L, attestation.verifiedRecordCount)

            val anotherReceipt =
                documentImportReceipt(
                    receiptFingerprint = fingerprint("another-stage-five-receipt"),
                )
            val second =
                engine.authorityVerified.attest(
                    binding = BINDING,
                    documentImportReceipt = anotherReceipt,
                    outboxReconciled = outbox,
                    indexesRebuilt = indexes,
                )
            assertNotEquals(attestation.attestationFingerprint, second.attestationFingerprint)
        }

    @Test
    fun authorityVerificationFailsClosedForMismatchOrExistingFence() = runBlocking {
        val source = FakeSource()
        val engine = engine(source)
        val outbox = drainOutbox(engine, null, 4)
        val indexes = drainIndexes(engine, null, 4)

        assertIllegalArgument {
            runBlocking {
                engine.authorityVerified.attest(
                    binding = BINDING,
                    documentImportReceipt =
                        documentImportReceipt(
                            destinationFingerprint = fingerprint("wrong-destination"),
                        ),
                    outboxReconciled = outbox,
                    indexesRebuilt = indexes,
                )
            }
        }

        source.cutoverFencePresent = true
        assertIllegalState {
            runBlocking {
                engine.authorityVerified.attest(
                    binding = BINDING,
                    documentImportReceipt = DOCUMENT_IMPORT_RECEIPT,
                    outboxReconciled = outbox,
                    indexesRebuilt = indexes,
                )
            }
        }
    }

    @Test
    fun publicAbiExposesNoMutableDatabaseSurfaceAndArtifactsCannotBeConstructed() {
        listOf(
            StudentDocumentImportReceiptReference::class.java,
            StudentCutoverVerificationCursor::class.java,
            StudentOutboxReconciledAttestation::class.java,
            StudentIndexesRebuiltAttestation::class.java,
            StudentAuthorityVerifiedAttestation::class.java,
        ).forEach { type ->
            assertTrue(
                "${type.simpleName} must have no callable primary constructor",
                type.declaredConstructors
                    .filterNot { it.isSynthetic }
                    .all { Modifier.isPrivate(it.modifiers) },
            )
            assertFalse(
                "${type.simpleName} must have no data-class copy method",
                type.declaredMethods.any { it.name.startsWith("copy") },
            )
        }

        val authorityMethods =
            StudentAuthorityVerifiedAttestationPort::class.java.methods
                .filterNot { it.declaringClass == Any::class.java }
        assertEquals(listOf("attest"), authorityMethods.map { it.name }.distinct())
        assertTrue(
            authorityMethods.single().parameterTypes.any {
                it == StudentDocumentImportReceiptReference::class.java
            },
        )
        val publicSurface =
            listOf(
                StudentOutboxReconciledAttestationPort::class.java,
                StudentIndexesRebuiltAttestationPort::class.java,
                StudentAuthorityVerifiedAttestationPort::class.java,
                StudentCutoverDestinationAttestationPorts::class.java,
            ).flatMap { type ->
                type.methods.flatMap { method ->
                    listOf(method.returnType) + method.parameterTypes
                }
            }.joinToString("\n") { it.simpleName.lowercase() }
        listOf("dao", "room", "sqlite", "sqlconnection", "writabledatabase").forEach {
            forbidden ->
            assertFalse("Public attestation ABI leaked $forbidden", forbidden in publicSurface)
        }
    }

    @Test
    fun reflectedValueCloneIsNotAnOwnerIssuedAttestation() = runBlocking {
        val engine = engine(FakeSource())
        val issued = drainOutbox(engine, cursor = null, limit = 8)
        val constructor =
            StudentOutboxReconciledAttestation::class.java.declaredConstructors
                .single { !it.isSynthetic }
        constructor.isAccessible = true
        val forged =
            constructor.newInstance(
                issued.cutoverGeneration,
                issued.legacyPrefixFingerprint,
                issued.studentDestinationFingerprint,
                issued.studentSchemaVersion,
                issued.attestationPolicyVersion,
                issued.verifiedRecordCount,
                issued.destinationSnapshotFingerprint,
                issued.verificationFingerprint,
                issued.issuedAtEpochMillis,
                issued.attestationFingerprint,
            ) as StudentOutboxReconciledAttestation

        assertFalse(forged.hasValidFingerprint())

        val receiptConstructor =
            StudentDocumentImportReceiptReference::class.java
                .declaredConstructors
                .single { !it.isSynthetic }
        receiptConstructor.isAccessible = true
        val forgedReceipt =
            receiptConstructor.newInstance(
                DOCUMENT_IMPORT_RECEIPT.cutoverGeneration,
                DOCUMENT_IMPORT_RECEIPT.legacyPrefixFingerprint,
                DOCUMENT_IMPORT_RECEIPT.migratedRecordCount,
                DOCUMENT_IMPORT_RECEIPT.sourceCheckpoint,
                DOCUMENT_IMPORT_RECEIPT.destinationFingerprint,
                DOCUMENT_IMPORT_RECEIPT.receiptFingerprint,
                fingerprint("forged-reference"),
            ) as StudentDocumentImportReceiptReference
        val indexes = drainIndexes(engine, cursor = null, limit = 8)
        assertIllegalArgument {
            runBlocking {
                engine.authorityVerified.attest(
                    binding = BINDING,
                    documentImportReceipt = forgedReceipt,
                    outboxReconciled = issued,
                    indexesRebuilt = indexes,
                )
            }
        }
    }

    @Test
    fun staleSchemaOrPolicyCannotProduceAnyProof() {
        val engine = engine(FakeSource())
        listOf(
            BINDING.copy(studentSchemaVersion = STUDENT_MISTAKE_SCHEMA_VERSION - 1),
            BINDING.copy(attestationPolicyVersion = "student-cutover-attestation-v0"),
        ).forEach { changed ->
            assertIllegalArgument {
                runBlocking {
                    engine.outboxReconciled.verifyOutboxNext(
                        binding = changed,
                        limit = 16,
                    )
                }
            }
        }
    }

    private suspend fun drainOutbox(
        engine: StudentCutoverDestinationAttestationEngine,
        cursor: StudentCutoverVerificationCursor?,
        limit: Int,
    ): StudentOutboxReconciledAttestation {
        var next = cursor
        repeat(32) {
            when (
                val result =
                    engine.outboxReconciled.verifyOutboxNext(
                        binding = BINDING,
                        cursor = next,
                        limit = limit,
                    )
            ) {
                is StudentCutoverAttestationProgress.Continue -> next = result.cursor
                is StudentCutoverAttestationProgress.Verified -> return result.attestation
            }
        }
        error("Outbox verification did not terminate")
    }

    private suspend fun drainIndexes(
        engine: StudentCutoverDestinationAttestationEngine,
        cursor: StudentCutoverVerificationCursor?,
        limit: Int,
    ): StudentIndexesRebuiltAttestation {
        var next = cursor
        repeat(32) {
            when (
                val result =
                    engine.indexesRebuilt.verifyIndexesNext(
                        binding = BINDING,
                        cursor = next,
                        limit = limit,
                    )
            ) {
                is StudentCutoverAttestationProgress.Continue -> next = result.cursor
                is StudentCutoverAttestationProgress.Verified -> return result.attestation
            }
        }
        error("Index verification did not terminate")
    }

    private fun engine(
        source: FakeSource,
        now: AtomicLong = AtomicLong(5_000),
    ): StudentCutoverDestinationAttestationEngine =
        StudentCutoverDestinationAttestationEngine(
            source = source,
            nowEpochMillis = LongSupplier(now::get),
            ownerKey = StudentMistakeOwnerKey.INSTANCE,
        )

    private class FakeSource(
        rows: Map<StudentCutoverVerificationPhase, List<StudentCutoverVerificationRecord>> =
            emptyMap(),
    ) : StudentCutoverDestinationReadSource {
        private val rows =
            EnumMap<StudentCutoverVerificationPhase, List<StudentCutoverVerificationRecord>>(
                StudentCutoverVerificationPhase::class.java,
            ).apply {
                StudentCutoverVerificationPhase.entries.forEach { phase ->
                    put(phase, rows[phase].orEmpty().sortedBy { it.stableKey })
                }
            }
        val pageLimits = mutableListOf<Int>()
        var journalFingerprint: String = JOURNAL_FINGERPRINT
        var indexFingerprint: String = INDEX_FINGERPRINT
        var journalConsistent: Boolean = true
        var indexesConsistent: Boolean = true
        var cutoverFencePresent: Boolean = false
        var completionReceiptPresent: Boolean = false

        override suspend fun readSnapshot(): StudentCutoverDestinationSnapshot =
            StudentCutoverDestinationSnapshot(
                journalFingerprint = journalFingerprint,
                indexFingerprint = indexFingerprint,
                journalConsistent = journalConsistent,
                indexesConsistent = indexesConsistent,
                cutoverFencePresent = cutoverFencePresent,
                completionReceiptPresent = completionReceiptPresent,
            )

        override suspend fun readPage(
            phase: StudentCutoverVerificationPhase,
            afterExclusive: String?,
            limit: Int,
        ): List<StudentCutoverVerificationRecord> {
            pageLimits += limit
            return rows.getValue(phase)
                .asSequence()
                .filter { afterExclusive == null || it.stableKey > afterExclusive }
                .take(limit)
                .toList()
        }

        override fun close() = Unit
    }

    private fun records(
        prefix: String,
        count: Int,
    ): List<StudentCutoverVerificationRecord> =
        List(count) { index ->
            StudentCutoverVerificationRecord(
                stableKey = "$prefix-${index.toString().padStart(3, '0')}",
                canonicalFingerprint = fingerprint("$prefix-$index"),
            )
        }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun assertIllegalState(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            Unit
        }
    }

    companion object {
        private val BINDING =
            StudentCutoverDestinationBinding(
                cutoverGeneration = 7,
                legacyPrefixFingerprint = fingerprint("legacy-prefix"),
                studentDestinationFingerprint = fingerprint("student-destination"),
                studentSchemaVersion = STUDENT_MISTAKE_SCHEMA_VERSION,
                attestationPolicyVersion =
                    STUDENT_CUTOVER_ATTESTATION_POLICY_VERSION,
            )
        private val DOCUMENT_IMPORT_RECEIPT =
            documentImportReceipt()
        private val JOURNAL_FINGERPRINT = fingerprint("journal")
        private val INDEX_FINGERPRINT = fingerprint("indexes")

        private fun fingerprint(value: String): String =
            CanonicalSha256("student-cutover-attestation-contract-test")
                .field("value", value)
                .finish()

        private fun documentImportReceipt(
            destinationFingerprint: String = BINDING.studentDestinationFingerprint,
            receiptFingerprint: String = fingerprint("stage-five-receipt"),
        ): StudentDocumentImportReceiptReference =
            StudentDocumentImportReceiptReference.issue(
                ownerKey = StudentMistakeOwnerKey.INSTANCE,
                cutoverGeneration = BINDING.cutoverGeneration,
                legacyPrefixFingerprint = BINDING.legacyPrefixFingerprint,
                migratedRecordCount = 5,
                sourceCheckpoint = "stage-five-checkpoint",
                destinationFingerprint = destinationFingerprint,
                receiptFingerprint = receiptFingerprint,
            )
    }
}
