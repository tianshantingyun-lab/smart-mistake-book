package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.lang.reflect.Modifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ThreeAuthorityCutoverProofCoordinatorTest {
    @Test
    fun cleanCutoverPersistsTwoFencesAndTwoMatchingReceipts() = runBlocking {
        val fixture = Fixture()

        val proof = fixture.coordinator().recoverOrVerify()

        assertEquals(CUTOVER_GENERATION, proof.cutoverGeneration)
        assertTrue(proof.proofFingerprint.isTestSha256())
        assertNotNull(fixture.student.fence)
        assertNotNull(fixture.mastery.fence)
        assertNotNull(fixture.student.receipt)
        assertNotNull(fixture.mastery.receipt)
        assertEquals(
            fixture.student.fence!!.cutoverIntentFingerprint,
            fixture.mastery.fence!!.cutoverIntentFingerprint,
        )
        assertEquals(
            fixture.student.receipt!!.cutoverIntentFingerprint,
            fixture.mastery.receipt!!.cutoverIntentFingerprint,
        )
        assertEquals(4, fixture.crashes.durableWriteCount)
        assertEquals(2, fixture.student.evidenceReadCount)
        assertEquals(2, fixture.mastery.evidenceReadCount)
        assertEquals(2, fixture.knowledge.readCount)
    }

    @Test
    fun everyDurableCrashPointRecoversByExactReplay() {
        (1..4).forEach { crashPoint ->
            val fixture = Fixture()
            fixture.crashes.crashAfterDurableWrite = crashPoint

            assertSimulatedCrash("crash point $crashPoint") {
                runBlocking { fixture.coordinator().recoverOrVerify() }
            }
            assertTrue(
                "a durable crash point must permanently close legacy writes",
                runBlocking {
                    fixture.legacyGate().readLegacyWriteFenceState()
                } != LegacyWriteFenceState.UNFENCED,
            )
            fixture.crashes.crashAfterDurableWrite = null

            val proof = runBlocking { fixture.coordinator().recoverOrVerify() }

            assertEquals(CUTOVER_GENERATION, proof.cutoverGeneration)
            assertEquals(4, fixture.crashes.durableWriteCount)
            assertNotNull(fixture.student.fence)
            assertNotNull(fixture.mastery.fence)
            assertNotNull(fixture.student.receipt)
            assertNotNull(fixture.mastery.receipt)
        }
    }

    @Test
    fun crashAfterBothReceiptsBeforeProofReturnRecoversWithoutNewWrites() {
        val fixture = Fixture()
        fixture.knowledge.failOnRead = 2

        assertSimulatedCrash("final witness re-verification") {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertNotNull(fixture.student.receipt)
        assertNotNull(fixture.mastery.receipt)
        val durableWrites = fixture.crashes.durableWriteCount
        fixture.knowledge.failOnRead = null

        runBlocking { fixture.coordinator().recoverOrVerify() }

        assertEquals(durableWrites, fixture.crashes.durableWriteCount)
    }

    @Test
    fun completedStartupReverifiesAllThreeAuthoritiesAndRecomputesGlobalProof() = runBlocking {
        val fixture = Fixture()
        val first = fixture.coordinator().recoverOrVerify()
        val writesAfterFirstStartup = fixture.crashes.durableWriteCount
        fixture.knowledge.witness =
            knowledgeWitness(
                activationGeneration = 12L,
                manifestFingerprint = sha("knowledge-manifest-12"),
            )

        val second = fixture.coordinator().recoverOrVerify()

        assertNotEquals(first.proofFingerprint, second.proofFingerprint)
        assertEquals(writesAfterFirstStartup, fixture.crashes.durableWriteCount)
        assertEquals(4, fixture.student.evidenceReadCount)
        assertEquals(4, fixture.mastery.evidenceReadCount)
        assertEquals(4, fixture.knowledge.readCount)
    }

    @Test
    fun legacyWriterGateIsIndependentAndUsesOrSemantics() = runBlocking {
        val fixture = Fixture()
        val expected = fixture.expectedFences()

        assertEquals(
            LegacyWriteFenceState.UNFENCED,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )

        fixture.student.fence = expected.first
        assertEquals(
            LegacyWriteFenceState.FENCED,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )

        fixture.student.fence = null
        fixture.mastery.fence = expected.second
        assertEquals(
            LegacyWriteFenceState.FENCED,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )

        fixture.student.fence = expected.first
        assertEquals(
            LegacyWriteFenceState.FENCED,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )
        assertEquals(0, fixture.student.evidenceReadCount)
        assertEquals(0, fixture.mastery.evidenceReadCount)
        assertEquals(0, fixture.knowledge.readCount)
    }

    @Test
    fun legacyWriterLeaseAndFirstFenceAreMutuallyExclusive() = runBlocking {
        val fixture = Fixture()
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writerControl = fixture.legacyGate()
        val independentTerminalControl = fixture.newLegacyGate()
        val writer =
            async(start = CoroutineStart.UNDISPATCHED) {
                writerControl.runLegacyMigrationWriterIfUnfenced {
                    writerEntered.complete(Unit)
                    releaseWriter.await()
                }
            }
        writerEntered.await()

        val terminal =
            async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator(independentTerminalControl).recoverOrVerify()
            }

        assertFalse(terminal.isCompleted)
        assertEquals(null, fixture.student.fence)
        assertEquals(null, fixture.mastery.fence)
        releaseWriter.complete(Unit)
        assertEquals(LegacyWriteFenceState.UNFENCED, writer.await())
        terminal.await()

        var reopenedLegacyWriter = false
        val state =
            fixture.legacyGate().runLegacyMigrationWriterIfUnfenced {
                reopenedLegacyWriter = true
            }
        assertEquals(LegacyWriteFenceState.FENCED, state)
        assertFalse(reopenedLegacyWriter)
    }

    @Test
    fun legacyWriterGateReportsConflictingGenerationOrTamper() = runBlocking {
        val fixture = Fixture()
        val expected = fixture.expectedFences()
        fixture.student.fence = expected.first
        fixture.mastery.fence =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.LEARNER_MASTERY,
                cutoverGeneration = CUTOVER_GENERATION + 1L,
                studentImportEvidenceFingerprint =
                    fixture.student.evidence!!.evidenceFingerprint,
                masteryImportEvidenceFingerprint =
                    fixture.mastery.evidence!!.evidenceFingerprint,
            )

        assertEquals(
            LegacyWriteFenceState.CONFLICT,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )

        fixture.mastery.fence = expected.second.copy(fenceFingerprint = sha("tampered"))
        assertEquals(
            LegacyWriteFenceState.CONFLICT,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )
    }

    @Test
    fun missingEitherFreshImportFailsBeforeFenceWrite() {
        val fixtures =
            listOf(
                Fixture().also { it.student.evidence = null },
                Fixture().also { it.mastery.evidence = null },
            )

        fixtures.forEach { fixture ->
            assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
                runBlocking { fixture.coordinator().recoverOrVerify() }
            }
            assertEquals(0, fixture.crashes.durableWriteCount)
            assertEquals(
                LegacyWriteFenceState.UNFENCED,
                runBlocking {
                    fixture.legacyGate().readLegacyWriteFenceState()
                },
            )
        }
    }

    @Test
    fun missingKnowledgeFailsBeforeAnyIrreversibleFenceWrite() {
        val fixture = Fixture()
        fixture.knowledge.witness = null

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }

        assertEquals(null, fixture.student.fence)
        assertEquals(null, fixture.mastery.fence)
        assertEquals(null, fixture.student.receipt)
        assertEquals(null, fixture.mastery.receipt)
        assertEquals(0, fixture.crashes.durableWriteCount)
        assertEquals(
            LegacyWriteFenceState.UNFENCED,
            runBlocking {
                fixture.legacyGate().readLegacyWriteFenceState()
            },
        )
    }

    @Test
    fun lateLegacyWriteInvalidatesImportEvidenceBeforeTheFirstFence() = runBlocking {
        val fixture = Fixture()

        assertEquals(
            LegacyWriteFenceState.UNFENCED,
            fixture.legacyGate().runLegacyMigrationWriterIfUnfenced {
                fixture.student.importCoversCurrentLegacySource = false
                fixture.mastery.importCoversCurrentLegacySource = false
            },
        )
        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(0, fixture.crashes.durableWriteCount)
        assertEquals(
            LegacyWriteFenceState.UNFENCED,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )

        fixture.student.evidence =
            importEvidence(
                authority = FencedAuthority.STUDENT_MISTAKES,
                destinationFingerprint = sha("student-after-late-import"),
            )
        fixture.mastery.evidence =
            importEvidence(
                authority = FencedAuthority.LEARNER_MASTERY,
                destinationFingerprint = sha("mastery-after-late-import"),
            )
        fixture.student.importCoversCurrentLegacySource = true
        fixture.mastery.importCoversCurrentLegacySource = true

        fixture.coordinator().recoverOrVerify()

        assertEquals(
            LegacyWriteFenceState.FENCED,
            fixture.legacyGate().readLegacyWriteFenceState(),
        )
    }

    @Test
    fun studentAndMasteryImportsMustUseTheSameGeneration() {
        val fixture = Fixture()
        fixture.mastery.evidence =
            importEvidence(
                authority = FencedAuthority.LEARNER_MASTERY,
                generation = CUTOVER_GENERATION + 1L,
            )

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(0, fixture.crashes.durableWriteCount)
    }

    @Test
    fun conflictingExistingFenceFailsClosedWithoutWritingTheMissingSide() {
        val fixture = Fixture()
        fixture.student.fence =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.STUDENT_MISTAKES,
                cutoverGeneration = CUTOVER_GENERATION,
                studentImportEvidenceFingerprint = sha("other-student-import"),
                masteryImportEvidenceFingerprint =
                    fixture.mastery.evidence!!.evidenceFingerprint,
            )

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(LegacyWriteFenceState.FENCED, runBlocking {
            fixture.legacyGate().readLegacyWriteFenceState()
        })
        assertEquals(null, fixture.mastery.fence)
        assertEquals(0, fixture.crashes.durableWriteCount)
    }

    @Test
    fun tamperedFenceOrReceiptFailsClosedBeforeRepair() {
        val tamperedFenceFixture = Fixture()
        val fences = tamperedFenceFixture.expectedFences()
        tamperedFenceFixture.student.fence =
            fences.first.copy(fenceFingerprint = sha("wrong-fence"))

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { tamperedFenceFixture.coordinator().recoverOrVerify() }
        }
        assertEquals(null, tamperedFenceFixture.mastery.fence)

        val tamperedReceiptFixture = Fixture()
        val receiptFences = tamperedReceiptFixture.expectedFences()
        tamperedReceiptFixture.student.fence = receiptFences.first
        tamperedReceiptFixture.student.receipt =
            AuthorityCutoverCompletionReceipt
                .create(receiptFences.first)
                .copy(receiptFingerprint = sha("wrong-receipt"))

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { tamperedReceiptFixture.coordinator().recoverOrVerify() }
        }
        assertEquals(null, tamperedReceiptFixture.mastery.fence)
    }

    @Test
    fun receiptWithoutItsFenceFailsClosed() {
        val fixture = Fixture()
        fixture.student.receipt =
            AuthorityCutoverCompletionReceipt.create(fixture.expectedFences().first)

        assertEquals(
            LegacyWriteFenceState.CONFLICT,
            runBlocking {
                fixture.legacyGate().readLegacyWriteFenceState()
            },
        )
        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(0, fixture.crashes.durableWriteCount)
    }

    @Test
    fun receiptBoundToAnotherFenceFailsClosed() {
        val fixture = Fixture()
        val expected = fixture.expectedFences()
        val otherFence =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.STUDENT_MISTAKES,
                cutoverGeneration = CUTOVER_GENERATION,
                studentImportEvidenceFingerprint = sha("other-student-import"),
                masteryImportEvidenceFingerprint =
                    fixture.mastery.evidence!!.evidenceFingerprint,
            )
        fixture.student.fence = expected.first
        fixture.student.receipt = AuthorityCutoverCompletionReceipt.create(otherFence)

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(0, fixture.crashes.durableWriteCount)
    }

    @Test
    fun changedOrTamperedImportEvidenceInvalidatesCompletedState() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator().recoverOrVerify()
        val writes = fixture.crashes.durableWriteCount
        fixture.student.evidence =
            importEvidence(
                authority = FencedAuthority.STUDENT_MISTAKES,
                destinationFingerprint = sha("changed-student-destination"),
            )

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(writes, fixture.crashes.durableWriteCount)

        fixture.student.evidence =
            fixture.student.evidence!!.copy(evidenceFingerprint = sha("tampered-evidence"))
        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        Unit
    }

    @Test
    fun missingOrTamperedKnowledgeWitnessInvalidatesCompletedState() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator().recoverOrVerify()
        val writes = fixture.crashes.durableWriteCount
        fixture.knowledge.witness = null

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(writes, fixture.crashes.durableWriteCount)

        fixture.knowledge.witness =
            knowledgeWitness().copy(witnessFingerprint = sha("tampered-witness"))
        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        Unit
    }

    @Test
    fun stateThatWasNotActuallyPersistedCannotProduceProof() {
        val fixture = Fixture()
        fixture.mastery.dropFenceWrites = true

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertEquals(null, fixture.mastery.fence)
        assertEquals(null, fixture.student.receipt)
        assertEquals(null, fixture.mastery.receipt)
    }

    @Test
    fun inputChangeDuringRecoveryCannotProduceProof() {
        val fixture = Fixture()
        val initial = fixture.knowledge.witness
        val replacement =
            knowledgeWitness(
                activationGeneration = 2L,
                manifestFingerprint = sha("replacement-manifest"),
            )
        fixture.knowledge.onRead = { read ->
            if (read == 1) initial else replacement
        }

        assertThrows(ThreeAuthorityCutoverIntegrityException::class.java) {
            runBlocking { fixture.coordinator().recoverOrVerify() }
        }
        assertNotNull(fixture.student.receipt)
        assertNotNull(fixture.mastery.receipt)
    }

    @Test
    fun canonicalArtifactsBindTheirAuthorityAndEveryControlInput() {
        val student = importEvidence(FencedAuthority.STUDENT_MISTAKES)
        val mastery = importEvidence(FencedAuthority.LEARNER_MASTERY)
        val studentFence =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.STUDENT_MISTAKES,
                cutoverGeneration = CUTOVER_GENERATION,
                studentImportEvidenceFingerprint = student.evidenceFingerprint,
                masteryImportEvidenceFingerprint = mastery.evidenceFingerprint,
            )
        val masteryFence =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.LEARNER_MASTERY,
                cutoverGeneration = CUTOVER_GENERATION,
                studentImportEvidenceFingerprint = student.evidenceFingerprint,
                masteryImportEvidenceFingerprint = mastery.evidenceFingerprint,
            )

        assertTrue(student.hasValidFingerprint())
        assertTrue(mastery.hasValidFingerprint())
        assertTrue(studentFence.hasValidFingerprint())
        assertTrue(masteryFence.hasValidFingerprint())
        assertNotEquals(student.evidenceFingerprint, mastery.evidenceFingerprint)
        assertNotEquals(studentFence.fenceFingerprint, masteryFence.fenceFingerprint)
        assertTrue(AuthorityCutoverCompletionReceipt.create(studentFence).hasValidFingerprint())
        assertTrue(AuthorityCutoverCompletionReceipt.create(masteryFence).hasValidFingerprint())
    }

    @Test
    fun verifiedFenceIsOpaqueAndTerminalCoordinatorHasNoLegacyJournalDependency() {
        assertTrue(Modifier.isFinal(VerifiedThreeAuthorityFence::class.java.modifiers))
        assertTrue(
            VerifiedThreeAuthorityFence::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all {
                Modifier.isPrivate(it.modifiers)
                },
        )
        assertFalse(
            VerifiedThreeAuthorityFence::class.java.methods.any {
                it.name == "copy"
            },
        )
        assertFalse(
            VerifiedThreeAuthorityFence.Owner::class.java.declaredMethods.any { method ->
                Modifier.isPublic(method.modifiers)
            },
        )
        assertFalse(
            ThreeAuthorityCutoverProofCoordinator::class.java.declaredConstructors
                .flatMap { it.parameterTypes.asList() }
                .any { AuthorityCutoverJournal::class.java.isAssignableFrom(it) },
        )
    }

    private class Fixture {
        val crashes = CrashController()
        val student =
            StudentMemoryPort(
                evidence = importEvidence(FencedAuthority.STUDENT_MISTAKES),
                crashes = crashes,
            )
        val mastery =
            MasteryMemoryPort(
                evidence = importEvidence(FencedAuthority.LEARNER_MASTERY),
                crashes = crashes,
            )
        val knowledge = MemoryKnowledgeWitnessReader(knowledgeWitness())
        private val legacyWriteFenceControl =
            ThreeAuthorityLegacyWriteFenceControl.create(
                student = student,
                mastery = mastery,
            )

        fun coordinator(
            control: ThreeAuthorityLegacyWriteFenceControl =
                legacyWriteFenceControl,
        ) =
            ThreeAuthorityCutoverProofCoordinator(
                legacyWriteFenceControl = control,
                knowledge = knowledge,
            )

        fun legacyGate() = legacyWriteFenceControl

        fun newLegacyGate() =
            ThreeAuthorityLegacyWriteFenceControl.create(
                student = student,
                mastery = mastery,
            )

        fun expectedFences(): Pair<AuthorityCutoverFence, AuthorityCutoverFence> {
            val studentEvidence = requireNotNull(student.evidence)
            val masteryEvidence = requireNotNull(mastery.evidence)
            return AuthorityCutoverFence.create(
                authority = FencedAuthority.STUDENT_MISTAKES,
                cutoverGeneration = studentEvidence.cutoverGeneration,
                studentImportEvidenceFingerprint = studentEvidence.evidenceFingerprint,
                masteryImportEvidenceFingerprint = masteryEvidence.evidenceFingerprint,
            ) to
                AuthorityCutoverFence.create(
                    authority = FencedAuthority.LEARNER_MASTERY,
                    cutoverGeneration = masteryEvidence.cutoverGeneration,
                    studentImportEvidenceFingerprint =
                        studentEvidence.evidenceFingerprint,
                    masteryImportEvidenceFingerprint =
                        masteryEvidence.evidenceFingerprint,
                )
        }
    }

    private abstract class MemoryControlPort(
        var evidence: ImmutableAuthorityImportEvidence?,
        private val crashes: CrashController,
    ) : AuthorityCutoverControlPort {
        var fence: AuthorityCutoverFence? = null
        var receipt: AuthorityCutoverCompletionReceipt? = null
        var evidenceReadCount: Int = 0
        var dropFenceWrites: Boolean = false
        var importCoversCurrentLegacySource: Boolean = true

        override suspend fun readCutoverFence(): AuthorityCutoverFence? = fence

        override suspend fun appendCutoverFenceIfAbsent(
            candidate: AuthorityCutoverFence,
        ): AuthorityCutoverFence {
            fence?.let { return it }
            if (dropFenceWrites) return candidate
            fence = candidate
            crashes.afterDurableWrite()
            return candidate
        }

        override suspend fun readCompletionReceipt(): AuthorityCutoverCompletionReceipt? =
            receipt

        override suspend fun appendCompletionReceiptIfAbsent(
            candidate: AuthorityCutoverCompletionReceipt,
        ): AuthorityCutoverCompletionReceipt {
            receipt?.let { return it }
            receipt = candidate
            crashes.afterDurableWrite()
            return candidate
        }

        override suspend fun reverifyImmutableImportEvidence():
            ImmutableAuthorityImportEvidence? {
            evidenceReadCount += 1
            return evidence.takeIf { importCoversCurrentLegacySource }
        }
    }

    private class StudentMemoryPort(
        evidence: ImmutableAuthorityImportEvidence?,
        crashes: CrashController,
    ) : MemoryControlPort(evidence, crashes), StudentAuthorityCutoverControlPort

    private class MasteryMemoryPort(
        evidence: ImmutableAuthorityImportEvidence?,
        crashes: CrashController,
    ) : MemoryControlPort(evidence, crashes), LearnerMasteryCutoverControlPort

    private class MemoryKnowledgeWitnessReader(
        var witness: KnowledgeActivationWitness?,
    ) : KnowledgeActivationWitnessReader {
        var readCount: Int = 0
        var failOnRead: Int? = null
        var onRead: ((Int) -> KnowledgeActivationWitness?)? = null

        override suspend fun readCurrentActivationWitness(): KnowledgeActivationWitness? {
            readCount += 1
            if (readCount == failOnRead) throw SimulatedCrash()
            return onRead?.invoke(readCount) ?: witness
        }
    }

    private class CrashController {
        var crashAfterDurableWrite: Int? = null
        var durableWriteCount: Int = 0

        fun afterDurableWrite() {
            durableWriteCount += 1
            if (durableWriteCount == crashAfterDurableWrite) {
                throw SimulatedCrash()
            }
        }
    }

    private class SimulatedCrash : RuntimeException()

    private companion object {
        const val CUTOVER_GENERATION = 7L

        fun importEvidence(
            authority: FencedAuthority,
            generation: Long = CUTOVER_GENERATION,
            destinationFingerprint: String =
                sha("destination-${authority.name}-$generation"),
        ): ImmutableAuthorityImportEvidence =
            ImmutableAuthorityImportEvidence.create(
                authority = authority,
                cutoverGeneration = generation,
                migratedRecordCount =
                    when (authority) {
                        FencedAuthority.STUDENT_MISTAKES -> 17L
                        FencedAuthority.LEARNER_MASTERY -> 29L
                    },
                sourceCheckpoint = "checkpoint-${authority.name}-$generation",
                legacyPrefixReceiptFingerprint =
                    sha("prefix-${authority.name}-$generation"),
                sourceFingerprint = sha("source-${authority.name}-$generation"),
                destinationFingerprint = destinationFingerprint,
            )

        fun knowledgeWitness(
            activationGeneration: Long = 11L,
            manifestFingerprint: String = sha("knowledge-manifest-11"),
        ): KnowledgeActivationWitness =
            KnowledgeActivationWitness.create(
                activationGeneration = activationGeneration,
                activatedAtEpochMillis = 5_000L + activationGeneration,
                packId = "high-school-reviewed",
                knowledgePackVersion = "2026.07",
                taxonomyVersion = "cn-high-school-v1",
                manifestFingerprint = manifestFingerprint,
            )

        fun sha(value: String): String =
            CanonicalSha256("three-authority-terminal-proof-test")
                .field("value", value)
                .finish()

        fun String.isTestSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

        fun assertSimulatedCrash(
            label: String,
            block: () -> Unit,
        ) {
            try {
                block()
                fail("Expected simulated crash at $label")
            } catch (_: SimulatedCrash) {
                // Expected crash after the selected durable write.
            }
        }
    }
}
