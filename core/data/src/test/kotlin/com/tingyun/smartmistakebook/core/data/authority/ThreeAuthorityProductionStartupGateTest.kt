package com.tingyun.smartmistakebook.core.data.authority

import android.content.ContextWrapper
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ThreeAuthorityProductionStartupGateTest {
    @Test
    fun missingFormalKnowledgePackBlocksReadyAndLegacyWriter() = runBlocking {
        val fixture = Fixture()
        fixture.knowledge.witness = null

        assertBlocked(
            fixture.gate().recoverOrBlock(),
            ThreeAuthorityStartupBlockReason.MISSING_PRODUCTION_KNOWLEDGE,
        )
        val factory = RecordingWriterFactory()
        assertEquals(
            LegacyMigrationWriterRunResult.DENIED_NO_PRODUCTION_KNOWLEDGE,
            fixture.gate().runLegacyMigrationWriterIfPermitted(factory),
        )

        assertEquals(0, factory.openCount)
        assertNull(fixture.student.fence)
        assertNull(fixture.mastery.fence)
    }

    @Test
    fun tamperedKnowledgeWitnessFailsClosedBeforeAnyFenceWrite() = runBlocking {
        val fixture = Fixture()
        fixture.knowledge.witness =
            checkNotNull(fixture.knowledge.witness).copy(
                witnessFingerprint = sha("tampered-knowledge-witness"),
            )

        assertBlocked(
            fixture.gate().recoverOrBlock(),
            ThreeAuthorityStartupBlockReason.INVALID_PRODUCTION_KNOWLEDGE,
        )
        val factory = RecordingWriterFactory()
        assertEquals(
            LegacyMigrationWriterRunResult.DENIED_INVALID_PRODUCTION_KNOWLEDGE,
            fixture.gate().runLegacyMigrationWriterIfPermitted(factory),
        )

        assertEquals(0, factory.openCount)
        assertNull(fixture.student.fence)
        assertNull(fixture.mastery.fence)
    }

    @Test
    fun incompleteTerminalEvidenceNeverPublishesReady() = runBlocking {
        val fixture = Fixture()
        fixture.mastery.evidence = null

        assertBlocked(
            fixture.gate().recoverOrBlock(),
            ThreeAuthorityStartupBlockReason.TERMINAL_PROOF_INCOMPLETE_OR_INVALID,
        )

        assertNull(fixture.student.fence)
        assertNull(fixture.mastery.fence)
        assertNull(fixture.student.receipt)
        assertNull(fixture.mastery.receipt)
        assertEquals(0, fixture.barrierOwner.finalizeCount)
    }

    @Test
    fun completeFreshThreeAuthorityProofIsTheOnlyReadyPath() = runBlocking {
        val fixture = Fixture()

        val state = fixture.gate().recoverOrBlock()

        assertTrue(state is ThreeAuthorityProductionStartupState.Ready)
        assertTrue(fixture.student.fence != null)
        assertTrue(fixture.mastery.fence != null)
        assertTrue(fixture.student.receipt != null)
        assertTrue(fixture.mastery.receipt != null)
        assertEquals(LegacyWriteFenceState.FENCED, fixture.legacyGate.readLegacyWriteFenceState())
        assertEquals(1, fixture.barrierOwner.finalizeCount)
        assertNotNull(fixture.barrierOwner.proof)
    }

    @Test
    fun barrierFailureAfterTerminalFenceBlocksReady() = runBlocking {
        val fixture = Fixture()
        fixture.barrierOwner.failure =
            IllegalStateException("simulated durable barrier conflict")

        assertBlocked(
            fixture.gate().recoverOrBlock(),
            ThreeAuthorityStartupBlockReason.LEGACY_BUSINESS_WRITE_BARRIER_CONFLICT,
        )

        assertEquals(1, fixture.barrierOwner.finalizeCount)
        assertNotNull(fixture.student.fence)
        assertNotNull(fixture.mastery.fence)
        assertNotNull(fixture.student.receipt)
        assertNotNull(fixture.mastery.receipt)
    }

    @Test
    fun migrationOnlyGateCanNeverPublishReady() = runBlocking {
        val fixture = Fixture()
        val gate =
            ThreeAuthorityProductionStartupGate.migrationOnly(
                legacyWriteFenceControl = fixture.legacyGate,
                knowledge = fixture.knowledge,
            )

        assertBlocked(
            gate.recoverOrBlock(),
            ThreeAuthorityStartupBlockReason.LEGACY_BUSINESS_WRITE_BARRIER_CONFLICT,
        )
        assertNotNull(fixture.student.fence)
        assertNotNull(fixture.mastery.fence)
    }

    @Test
    fun eitherDurableFencePermanentlyDeniesLegacyWriter() = runBlocking {
        val fixture = Fixture()
        val (studentFence, _) = fixture.expectedFences()
        fixture.student.fence = studentFence
        val factory = RecordingWriterFactory()

        assertEquals(
            LegacyMigrationWriterRunResult.DENIED_FENCED,
            fixture.gate().runLegacyMigrationWriterIfPermitted(factory),
        )

        assertEquals(0, factory.openCount)
    }

    @Test
    fun conflictingFencesBlockReadyAndLegacyWriter() = runBlocking {
        val fixture = Fixture()
        val (studentFence, masteryFence) = fixture.expectedFences()
        fixture.student.fence = studentFence
        fixture.mastery.fence =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.LEARNER_MASTERY,
                cutoverGeneration = masteryFence.cutoverGeneration + 1L,
                studentImportEvidenceFingerprint = sha("conflicting-student-import"),
                masteryImportEvidenceFingerprint = sha("conflicting-mastery-import"),
            )
        val factory = RecordingWriterFactory()

        assertBlocked(
            fixture.gate().recoverOrBlock(),
            ThreeAuthorityStartupBlockReason.LEGACY_FENCE_CONFLICT,
        )
        assertEquals(
            LegacyMigrationWriterRunResult.DENIED_CONFLICT,
            fixture.gate().runLegacyMigrationWriterIfPermitted(factory),
        )

        assertEquals(0, factory.openCount)
    }

    @Test
    fun failingLegacyWriterIsClosedAndCannotPublishReady() = runBlocking {
        val fixture = Fixture()
        val writerFailure = IllegalStateException("simulated migration failure")
        val session = RecordingWriterSession(failure = writerFailure)
        val factory = RecordingWriterFactory(session)

        try {
            fixture.gate().runLegacyMigrationWriterIfPermitted(factory)
            fail("Expected the legacy migration failure")
        } catch (failure: IllegalStateException) {
            assertSame(writerFailure, failure)
        }

        assertEquals(1, factory.openCount)
        assertEquals(1, session.closeCount)
        assertNull(fixture.student.fence)
        assertNull(fixture.mastery.fence)
        assertNull(fixture.student.receipt)
        assertNull(fixture.mastery.receipt)
    }

    @Test
    fun successfulLegacyWriterClosesBeforeReturningAndDoesNotCreateReadyProof() = runBlocking {
        val fixture = Fixture()
        val session = RecordingWriterSession()
        val factory = RecordingWriterFactory(session)

        assertEquals(
            LegacyMigrationWriterRunResult.COMPLETED,
            fixture.gate().runLegacyMigrationWriterIfPermitted(factory),
        )

        assertEquals(1, session.migrateCount)
        assertEquals(1, session.closeCount)
        assertNull(fixture.student.fence)
        assertNull(fixture.mastery.fence)
    }

    @Test
    fun readyTokenExposesNoRawAuthorityEvidence() {
        assertTrue(
            ThreeAuthorityProductionStartupState.Ready::class.java.constructors
                .none { constructor -> !constructor.isSynthetic },
        )
        val forbidden =
            setOf(
                KnowledgeActivationWitness::class.java,
                AuthorityCutoverFence::class.java,
                AuthorityCutoverCompletionReceipt::class.java,
                VerifiedThreeAuthorityFence::class.java,
            )
        assertFalse(
            ThreeAuthorityProductionStartupState.Ready::class.java.declaredFields
                .any { field -> field.type in forbidden },
        )
        assertFalse(
            ThreeAuthorityProductionStartupState.Ready::class.java.declaredMethods
                .any { method ->
                    method.returnType in forbidden ||
                        method.parameterTypes.any { parameter -> parameter in forbidden }
                },
        )
    }

    @Test
    fun readyAndCurrentGenerationBindingAreIdentityBoundAndOneShot(): Unit = runBlocking {
        val fixture = Fixture()
        val ready =
            fixture.gate().recoverOrBlock()
                as ThreeAuthorityProductionStartupState.Ready
        val reverified = fixture.recoverVerifiedProof()

        val binding =
            CurrentGenerationFenceBinding.bind(
                ready = ready,
                reverifiedProof = reverified,
            )

        assertThrows(IllegalStateException::class.java) {
            CurrentGenerationFenceBinding.bind(
                ready = ready,
                reverifiedProof = reverified,
            )
        }
        binding.claimForRuntimeBinding()
        assertThrows(IllegalStateException::class.java) {
            binding.claimForRuntimeBinding()
        }
    }

    @Test
    fun reflectedValidLookingFenceAndRecoveryPermitCannotCreateReady() {
        val forgedFence =
            VerifiedThreeAuthorityFence::class.java.declaredConstructors
                .single { constructor -> constructor.parameterCount == 2 }
                .apply { isAccessible = true }
                .newInstance(77L, "0".repeat(64)) as VerifiedThreeAuthorityFence

        assertThrows(IllegalStateException::class.java) {
            ThreeAuthorityProductionStartupState.Ready.fromVerifiedProof(forgedFence)
        }

        val forgedPermit =
            VerifiedThreeAuthorityFence.RecoveryPermit::class.java.declaredConstructors
                .single { constructor -> constructor.parameterCount == 0 }
                .apply { isAccessible = true }
                .newInstance() as VerifiedThreeAuthorityFence.RecoveryPermit
        assertThrows(IllegalStateException::class.java) {
            VerifiedThreeAuthorityFence.Owner.issueAfterAuditedRecovery(
                forgedPermit,
                77L,
                "0".repeat(64),
                knowledgeWitness(),
            )
        }
    }

    @Test
    fun verifiedFenceHasExactlyOneConcurrentReadyClaim(): Unit = runBlocking {
        val proof = Fixture().recoverVerifiedProof()
        val start = CountDownLatch(1)
        val completed = CountDownLatch(8)
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        val issuedReady = AtomicReference<ThreeAuthorityProductionStartupState.Ready?>()
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                executor.execute {
                    try {
                        start.await()
                        val ready =
                            ThreeAuthorityProductionStartupState.Ready.fromVerifiedProof(proof)
                        issuedReady.set(ready)
                        successes.incrementAndGet()
                    } catch (_: IllegalStateException) {
                        failures.incrementAndGet()
                    } finally {
                        completed.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            issuedReady.get()?.revokeUnclaimedProof()
        }

        assertEquals(1, successes.get())
        assertEquals(7, failures.get())
        assertThrows(IllegalStateException::class.java) {
            ThreeAuthorityProductionStartupState.Ready.fromVerifiedProof(proof)
        }
    }

    @Test
    fun productionBindingAbiAcceptsOnlyOpaqueCurrentLocalLearnerRuntimeClaim() {
        val bindMethods =
            GenerationBoundLearningAuthorityRuntime::class.java.declaredClasses
                .flatMap { nested -> nested.declaredMethods.asList() }
                .filter { method -> method.name.startsWith("bind") }
        assertTrue(bindMethods.isNotEmpty())
        assertFalse(
            bindMethods.any { method ->
                method.parameterTypes.any { parameter ->
                    parameter == LocalLearningAuthorityRuntime::class.java ||
                        parameter == String::class.java
                }
            },
        )
        assertTrue(
            CurrentLocalLearnerRuntimeClaim::class.java.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )

        val forgedRuntimeClaim =
            CurrentLocalLearnerRuntimeClaim::class.java.declaredConstructors
                .single { constructor -> constructor.parameterCount == 0 }
                .apply { isAccessible = true }
                .newInstance() as CurrentLocalLearnerRuntimeClaim
        assertThrows(IllegalStateException::class.java) {
            CurrentLocalLearnerRuntimeClaim.Owner.claim(forgedRuntimeClaim)
        }
        forgedRuntimeClaim.close()
    }

    @Test
    fun currentGenerationReverificationRejectsKnowledgeAndContextSplicing(): Unit = runBlocking {
        val fixture = Fixture()
        val proof = fixture.recoverVerifiedProof()
        val canonicalContext = ContextWrapper(null)

        assertThrows(IllegalStateException::class.java) {
            CurrentGenerationReverification.Owner.issueFromAuditedOwners(
                canonicalContext,
                proof,
                knowledgeWitness("-other-pack"),
            )
        }

        val reverification =
            CurrentGenerationReverification.Owner.issueFromAuditedOwners(
                canonicalContext,
                proof,
                checkNotNull(fixture.knowledge.witness),
            )
        assertThrows(IllegalStateException::class.java) {
            CurrentGenerationReverification.Owner.claim(
                reverification,
                ContextWrapper(null),
            )
        }

        val secondProof = fixture.recoverVerifiedProof()
        val secondReverification =
            CurrentGenerationReverification.Owner.issueFromAuditedOwners(
                canonicalContext,
                secondProof,
                checkNotNull(fixture.knowledge.witness),
            )
        val state =
            CurrentGenerationReverification.Owner.claim(
                secondReverification,
                canonicalContext,
            )
        assertEquals(LOCAL_LEARNER_ID, state.learnerId)
        assertThrows(IllegalStateException::class.java) {
            CurrentGenerationReverification.Owner.claim(
                secondReverification,
                canonicalContext,
            )
        }
    }

    @Test
    fun reflectedReadyAndMismatchedCurrentProofCannotBind(): Unit = runBlocking {
        val forgedReady =
            ThreeAuthorityProductionStartupState.Ready::class.java
                .declaredConstructors
                .single { constructor -> constructor.parameterCount == 0 }
                .apply { isAccessible = true }
                .newInstance() as ThreeAuthorityProductionStartupState.Ready
        val forgedFixture = Fixture()
        val currentProof = forgedFixture.recoverVerifiedProof()

        assertThrows(IllegalStateException::class.java) {
            CurrentGenerationFenceBinding.bind(
                ready = forgedReady,
                reverifiedProof = currentProof,
            )
        }

        val fixture = Fixture()
        val ready =
            fixture.gate().recoverOrBlock()
                as ThreeAuthorityProductionStartupState.Ready
        fixture.knowledge.witness = knowledgeWitness("-replaced")
        val mismatchedProof = fixture.recoverVerifiedProof()

        assertThrows(IllegalStateException::class.java) {
            CurrentGenerationFenceBinding.bind(
                ready = ready,
                reverifiedProof = mismatchedProof,
            )
        }
    }

    @Test
    fun revokedReadyBindingAndReflectedBindingCannotBeClaimed(): Unit = runBlocking {
        val revokedReadyFixture = Fixture()
        val revokedReady =
            revokedReadyFixture.gate().recoverOrBlock()
                as ThreeAuthorityProductionStartupState.Ready
        val revokedReadyProof = revokedReadyFixture.recoverVerifiedProof()
        revokedReady.revokeUnclaimedProof()

        assertThrows(IllegalStateException::class.java) {
            CurrentGenerationFenceBinding.bind(
                ready = revokedReady,
                reverifiedProof = revokedReadyProof,
            )
        }

        val revokedBindingFixture = Fixture()
        val binding =
            CurrentGenerationFenceBinding.bind(
                ready =
                    revokedBindingFixture.gate().recoverOrBlock()
                        as ThreeAuthorityProductionStartupState.Ready,
                reverifiedProof = revokedBindingFixture.recoverVerifiedProof(),
            )
        binding.close()
        assertThrows(IllegalStateException::class.java) {
            binding.claimForRuntimeBinding()
        }

        val reflectedBinding =
            CurrentGenerationFenceBinding::class.java
                .declaredConstructors
                .single { constructor -> constructor.parameterCount == 0 }
                .apply { isAccessible = true }
                .newInstance() as CurrentGenerationFenceBinding
        assertThrows(IllegalStateException::class.java) {
            reflectedBinding.claimForRuntimeBinding()
        }

        val reflectedRuntimeBinding =
            GenerationBoundLearningAuthorityRuntime::class.java
                .declaredConstructors
                .single { constructor -> constructor.parameterCount == 0 }
                .apply { isAccessible = true }
                .newInstance() as GenerationBoundLearningAuthorityRuntime
        assertThrows(IllegalStateException::class.java) {
            reflectedRuntimeBinding.claimForCapabilityAssembly()
        }
    }

    private fun assertBlocked(
        state: ThreeAuthorityProductionStartupState,
        reason: ThreeAuthorityStartupBlockReason,
    ) {
        assertTrue(state is ThreeAuthorityProductionStartupState.Blocked)
        assertEquals(
            reason,
            (state as ThreeAuthorityProductionStartupState.Blocked).reason,
        )
    }

    private class Fixture {
        val student =
            StudentMemoryPort(importEvidence(FencedAuthority.STUDENT_MISTAKES))
        val mastery =
            MasteryMemoryPort(importEvidence(FencedAuthority.LEARNER_MASTERY))
        val knowledge = MemoryKnowledgeWitnessReader(knowledgeWitness())
        val legacyGate =
            ThreeAuthorityLegacyWriteFenceControl.create(
                student = student,
                mastery = mastery,
            )
        val barrierOwner =
            RecordingBarrierOwner {
                check(student.fence != null)
                check(mastery.fence != null)
                check(student.receipt != null)
                check(mastery.receipt != null)
            }

        fun gate(): ThreeAuthorityProductionStartupGate =
            ThreeAuthorityProductionStartupGate(
                legacyWriteFenceControl = legacyGate,
                knowledge = knowledge,
                terminalProofCoordinator =
                    ThreeAuthorityCutoverProofCoordinator(
                        legacyWriteFenceControl = legacyGate,
                        knowledge = knowledge,
                    ),
                legacyBusinessWriteBarrierOwner = barrierOwner,
            )

        suspend fun recoverVerifiedProof(): VerifiedThreeAuthorityFence =
            ThreeAuthorityCutoverProofCoordinator(
                legacyWriteFenceControl = legacyGate,
                knowledge = knowledge,
            ).recoverOrVerify()

        fun expectedFences(): Pair<AuthorityCutoverFence, AuthorityCutoverFence> {
            val studentEvidence = checkNotNull(student.evidence)
            val masteryEvidence = checkNotNull(mastery.evidence)
            return AuthorityCutoverFence.create(
                authority = FencedAuthority.STUDENT_MISTAKES,
                cutoverGeneration = studentEvidence.cutoverGeneration,
                studentImportEvidenceFingerprint = studentEvidence.evidenceFingerprint,
                masteryImportEvidenceFingerprint = masteryEvidence.evidenceFingerprint,
            ) to
                AuthorityCutoverFence.create(
                    authority = FencedAuthority.LEARNER_MASTERY,
                    cutoverGeneration = masteryEvidence.cutoverGeneration,
                    studentImportEvidenceFingerprint = studentEvidence.evidenceFingerprint,
                    masteryImportEvidenceFingerprint = masteryEvidence.evidenceFingerprint,
                )
        }
    }

    private abstract class MemoryControlPort(
        var evidence: ImmutableAuthorityImportEvidence?,
    ) : AuthorityCutoverControlPort {
        var fence: AuthorityCutoverFence? = null
        var receipt: AuthorityCutoverCompletionReceipt? = null

        override suspend fun readCutoverFence(): AuthorityCutoverFence? = fence

        override suspend fun appendCutoverFenceIfAbsent(
            candidate: AuthorityCutoverFence,
        ): AuthorityCutoverFence =
            fence ?: candidate.also { fence = it }

        override suspend fun readCompletionReceipt(): AuthorityCutoverCompletionReceipt? =
            receipt

        override suspend fun appendCompletionReceiptIfAbsent(
            candidate: AuthorityCutoverCompletionReceipt,
        ): AuthorityCutoverCompletionReceipt =
            receipt ?: candidate.also { receipt = it }

        override suspend fun reverifyImmutableImportEvidence():
            ImmutableAuthorityImportEvidence? = evidence
    }

    private class StudentMemoryPort(
        evidence: ImmutableAuthorityImportEvidence?,
    ) : MemoryControlPort(evidence), StudentAuthorityCutoverControlPort

    private class MasteryMemoryPort(
        evidence: ImmutableAuthorityImportEvidence?,
    ) : MemoryControlPort(evidence), LearnerMasteryCutoverControlPort

    private class MemoryKnowledgeWitnessReader(
        var witness: KnowledgeActivationWitness?,
    ) : KnowledgeActivationWitnessReader {
        override suspend fun readCurrentActivationWitness(): KnowledgeActivationWitness? =
            witness
    }

    private class RecordingWriterFactory(
        private val session: RecordingWriterSession = RecordingWriterSession(),
    ) : LegacyMigrationWriterSessionFactory {
        var openCount = 0
            private set

        override suspend fun open(): LegacyMigrationWriterSession {
            openCount += 1
            return session
        }
    }

    private class RecordingWriterSession(
        private val failure: Throwable? = null,
    ) : LegacyMigrationWriterSession {
        var migrateCount = 0
            private set
        var closeCount = 0
            private set

        override suspend fun migrate() {
            migrateCount += 1
            failure?.let { throw it }
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class RecordingBarrierOwner(
        private val requireTerminalFence: () -> Unit,
    ) : ThreeAuthorityLegacyBusinessWriteBarrierOwner {
        var failure: Throwable? = null
        var finalizeCount = 0
            private set
        var proof: VerifiedThreeAuthorityFence? = null
            private set

        override suspend fun finalizeAfterVerifiedFence(
            proof: VerifiedThreeAuthorityFence,
        ): ThreeAuthorityLegacyBarrierFinalization {
            finalizeCount += 1
            requireTerminalFence()
            failure?.let { throw it }
            this.proof = proof
            return ThreeAuthorityLegacyBarrierFinalization.ACTIVATED
        }
    }

    private companion object {
        const val CUTOVER_GENERATION = 7L

        fun importEvidence(
            authority: FencedAuthority,
        ): ImmutableAuthorityImportEvidence =
            ImmutableAuthorityImportEvidence.create(
                authority = authority,
                cutoverGeneration = CUTOVER_GENERATION,
                migratedRecordCount =
                    when (authority) {
                        FencedAuthority.STUDENT_MISTAKES -> 17L
                        FencedAuthority.LEARNER_MASTERY -> 29L
                    },
                sourceCheckpoint = "checkpoint-${authority.name}",
                legacyPrefixReceiptFingerprint = sha("prefix-${authority.name}"),
                sourceFingerprint = sha("source-${authority.name}"),
                destinationFingerprint = sha("destination-${authority.name}"),
            )

        fun knowledgeWitness(suffix: String = ""): KnowledgeActivationWitness =
            KnowledgeActivationWitness.create(
                activationGeneration = 11L,
                activatedAtEpochMillis = 5_011L,
                packId = "high-school-reviewed",
                knowledgePackVersion = "2026.07",
                taxonomyVersion = "cn-high-school-v1",
                manifestFingerprint = sha("knowledge-manifest$suffix"),
            )

        fun sha(value: String): String =
            CanonicalSha256("three-authority-production-startup-gate-test")
                .field("value", value)
                .finish()
    }
}
