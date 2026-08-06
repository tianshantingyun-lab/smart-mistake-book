package com.tingyun.smartmistakebook.core.mastery.database

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

class LearnerMasteryCutoverDestinationAttestationContractTest {
    @Test
    fun ownerReadsScopeAndIssuesBoundedStageTenProof() = runBlocking {
        val source =
            FakeSource(
                counts =
                    mapOf(
                        LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS to 5,
                        LearnerMasteryCutoverVerificationPhase.CANDIDATES to 5,
                        LearnerMasteryCutoverVerificationPhase.ATTRIBUTIONS to 9,
                        LearnerMasteryCutoverVerificationPhase.PROBLEM_BINDINGS to 3,
                        LearnerMasteryCutoverVerificationPhase.SUPERSESSIONS to 2,
                        LearnerMasteryCutoverVerificationPhase.MIGRATION_LEDGER to 5,
                    ),
            )
        val engine = engine(source, AtomicLong(4_000L))
        val binding = engine.binding.bind(FACTS_IMPORT_RECEIPT)

        val first =
            engine.bindingsReconciled.verifyBindingsNext(
                binding = binding,
                limit = 2,
            ) as LearnerMasteryCutoverAttestationProgress.Continue
        assertEquals(2L, first.cursor.verifiedRecordCount)
        assertEquals(
            LearnerMasteryCutoverDestinationStage.MASTERY_BINDINGS_RECONCILED,
            first.cursor.stage,
        )

        val attestation = drainBindings(engine, binding, first.cursor, limit = 2)
        assertEquals(29L, attestation.verifiedRecordCount)
        assertEquals(4_000L, attestation.issuedAtEpochMillis)
        assertEquals(EVENT_BINDING_FINGERPRINT, attestation.destinationSnapshotFingerprint)
        assertEquals(
            IMMUTABLE_WATERMARK_FINGERPRINT,
            attestation.immutableLedgerWatermarkFingerprint,
        )
        assertTrue(attestation.hasValidFingerprint())
        assertEquals(7L, binding.cutoverGeneration)
        assertEquals(13L, binding.projectionGenerationId)
        assertTrue(source.pageLimits.all { it <= 3 })
    }

    @Test
    fun rollingFingerprintBindsContentAndRestartedScanIsDeterministic() = runBlocking {
        val firstSource =
            FakeSource(
                counts =
                    mapOf(
                        LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS to 4,
                    ),
                recordNamespace = "first",
            )
        val firstEngine = engine(firstSource, AtomicLong(5_000L))
        val firstBinding = firstEngine.binding.bind(FACTS_IMPORT_RECEIPT)
        val first = drainBindings(firstEngine, firstBinding, null, 2)

        val restartedEngine =
            engine(
                FakeSource(
                    counts =
                        mapOf(
                            LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS to 4,
                        ),
                    recordNamespace = "first",
                ),
                AtomicLong(5_000L),
            )
        val restartedBinding = restartedEngine.binding.bind(FACTS_IMPORT_RECEIPT)
        val restarted = drainBindings(restartedEngine, restartedBinding, null, 2)
        assertEquals(first.verificationFingerprint, restarted.verificationFingerprint)

        val changedEngine =
            engine(
                FakeSource(
                    counts =
                        mapOf(
                            LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS to 4,
                        ),
                    recordNamespace = "changed",
                ),
                AtomicLong(5_000L),
            )
        val changedBinding = changedEngine.binding.bind(FACTS_IMPORT_RECEIPT)
        val changed = drainBindings(changedEngine, changedBinding, null, 2)
        assertNotEquals(first.verificationFingerprint, changed.verificationFingerprint)
    }

    @Test
    fun hundredThousandEventsStayWithinTheRequestedPageBound() = runBlocking {
        val source =
            FakeSource(
                counts =
                    mapOf(
                        LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS to 100_000,
                    ),
            )
        val engine = engine(source)
        val binding = engine.binding.bind(FACTS_IMPORT_RECEIPT)

        val attestation = drainBindings(engine, binding, null, limit = 256)

        assertEquals(100_000L, attestation.verifiedRecordCount)
        assertTrue(source.pageLimits.isNotEmpty())
        assertTrue(source.pageLimits.all { it <= 257 })
        assertTrue(source.maximumRowsReturned <= 257)
    }

    @Test
    fun cursorCannotCrossStageOrOwnerCapturedDestinationSnapshot() = runBlocking {
        val source =
            FakeSource(
                counts =
                    mapOf(
                        LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS to 2,
                    ),
            )
        val engine = engine(source)
        val binding = engine.binding.bind(FACTS_IMPORT_RECEIPT)
        val cursor =
            (
                engine.bindingsReconciled.verifyBindingsNext(
                    binding = binding,
                    limit = 1,
                ) as LearnerMasteryCutoverAttestationProgress.Continue
            ).cursor

        assertIllegalArgument {
            runBlocking {
                engine.projectionsRebuilt.verifyProjectionsNext(
                    binding = binding,
                    cursor = cursor,
                    limit = 1,
                )
            }
        }

        source.eventBindingFingerprint = fingerprint("changed-event-binding")
        assertIllegalState {
            runBlocking {
                drainBindings(engine, binding, cursor, 1)
            }
        }
    }

    @Test
    fun pendingConflictOrUnactivatedShadowFailsClosed() = runBlocking {
        val source = FakeSource()
        val engine = engine(source)
        val binding = engine.binding.bind(FACTS_IMPORT_RECEIPT)

        source.eventBindingsConsistent = false
        assertIllegalState {
            runBlocking {
                engine.bindingsReconciled.verifyBindingsNext(binding, limit = 8)
            }
        }

        source.eventBindingsConsistent = true
        source.projectionsConsistent = false
        assertIllegalState {
            runBlocking {
                engine.projectionsRebuilt.verifyProjectionsNext(binding, limit = 8)
            }
        }
    }

    @Test
    fun stageTwelveRequiresExactStageNineTenAndElevenArtifacts() = runBlocking {
        val source =
            FakeSource(
                counts =
                    mapOf(
                        LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS to 2,
                        LearnerMasteryCutoverVerificationPhase.PROJECTIONS to 3,
                        LearnerMasteryCutoverVerificationPhase.SCHEMA_OBJECTS to 2,
                    ),
            )
        val engine = engine(source)
        val binding = engine.binding.bind(FACTS_IMPORT_RECEIPT)
        val bindings = drainBindings(engine, binding, null, 2)
        val projections = drainProjections(engine, binding, null, 2)

        val authority =
            engine.authorityVerified.attest(
                binding = binding,
                factsImportReceipt = FACTS_IMPORT_RECEIPT,
                bindingsReconciled = bindings,
                projectionsRebuilt = projections,
            )

        assertTrue(authority.hasValidFingerprint())
        assertEquals(
            FACTS_IMPORT_RECEIPT.receiptFingerprint,
            authority.factsImportReceiptFingerprint,
        )
        assertEquals(
            bindings.attestationFingerprint,
            authority.bindingsReconciledAttestationFingerprint,
        )
        assertEquals(
            projections.attestationFingerprint,
            authority.projectionsRebuiltAttestationFingerprint,
        )
        assertEquals(12L, authority.verifiedRecordCount)

        val otherReceipt =
            factsImportReceipt(
                receiptFingerprint = fingerprint("other-stage-nine-receipt"),
            )
        assertIllegalArgument {
            runBlocking {
                engine.authorityVerified.attest(
                    binding = binding,
                    factsImportReceipt = otherReceipt,
                    bindingsReconciled = bindings,
                    projectionsRebuilt = projections,
                )
            }
        }

        source.cutoverFencePresent = true
        assertIllegalState {
            runBlocking {
                engine.authorityVerified.attest(
                    binding = binding,
                    factsImportReceipt = FACTS_IMPORT_RECEIPT,
                    bindingsReconciled = bindings,
                    projectionsRebuilt = projections,
                )
            }
        }
    }

    @Test
    fun publicProofAbiIsReadOnlyAndArtifactsCannotBeConstructedOrCopied() =
        runBlocking {
            listOf(
                LearnerMasteryFactsImportReceiptReference::class.java,
                LearnerMasteryCutoverDestinationBinding::class.java,
                LearnerMasteryCutoverVerificationCursor::class.java,
                LearnerMasteryBindingsReconciledAttestation::class.java,
                LearnerMasteryProjectionsRebuiltAttestation::class.java,
                LearnerMasteryAuthorityVerifiedAttestation::class.java,
            ).forEach { type ->
                val sourceConstructors =
                    type.declaredConstructors.filterNot { constructor ->
                        constructor.parameterTypes.lastOrNull()?.name ==
                            "kotlin.jvm.internal.DefaultConstructorMarker"
                    }
                assertTrue(
                    "${type.simpleName} must have private constructors",
                    sourceConstructors.isNotEmpty() &&
                        sourceConstructors.all { Modifier.isPrivate(it.modifiers) },
                )
                assertTrue(
                    "${type.simpleName} may expose only compiler-synthetic constructor accessors",
                    type.declaredConstructors
                        .filterNot(sourceConstructors::contains)
                        .all { constructor ->
                            constructor.isSynthetic &&
                                constructor.parameterTypes.lastOrNull()?.name ==
                                    "kotlin.jvm.internal.DefaultConstructorMarker"
                        },
                )
                assertFalse(
                    "${type.simpleName} must have no data-class copy method",
                    type.declaredMethods.any { it.name.startsWith("copy") },
                )
            }

            val publicSurface =
                listOf(
                    LearnerMasteryCutoverDestinationBindingPort::class.java,
                    LearnerMasteryBindingsReconciledAttestationPort::class.java,
                    LearnerMasteryProjectionsRebuiltAttestationPort::class.java,
                    LearnerMasteryAuthorityVerifiedAttestationPort::class.java,
                    LearnerMasteryCutoverDestinationAttestationPorts::class.java,
                ).flatMap { type ->
                    type.methods.flatMap { method ->
                        listOf(method.returnType) + method.parameterTypes
                    }
                }.joinToString("\n") { it.simpleName.lowercase() }
            listOf("dao", "room", "sqlite", "sqlconnection", "writabledatabase").forEach {
                forbidden ->
                assertFalse("Public attestation ABI leaked $forbidden", forbidden in publicSurface)
            }

            val source = FakeSource()
            val engine = engine(source)
            val binding = engine.binding.bind(FACTS_IMPORT_RECEIPT)
            val issued = drainBindings(engine, binding, null, 8)
            val constructor =
                LearnerMasteryBindingsReconciledAttestation::class.java
                    .declaredConstructors
                    .single { candidate ->
                        candidate.parameterTypes.lastOrNull()?.name !=
                            "kotlin.jvm.internal.DefaultConstructorMarker"
                    }
            constructor.isAccessible = true
            val forged =
                constructor.newInstance(
                    binding,
                    issued.verifiedRecordCount,
                    issued.destinationSnapshotFingerprint,
                    issued.verificationFingerprint,
                    issued.immutableLedgerWatermarkFingerprint,
                    issued.issuedAtEpochMillis,
                    issued.attestationFingerprint,
                ) as LearnerMasteryBindingsReconciledAttestation
            assertFalse(forged.hasValidFingerprint())
        }

    private suspend fun drainBindings(
        engine: LearnerMasteryCutoverDestinationAttestationEngine,
        binding: LearnerMasteryCutoverDestinationBinding,
        cursor: LearnerMasteryCutoverVerificationCursor?,
        limit: Int,
    ): LearnerMasteryBindingsReconciledAttestation {
        var next = cursor
        repeat(2_000) {
            when (
                val result =
                    engine.bindingsReconciled.verifyBindingsNext(
                        binding = binding,
                        cursor = next,
                        limit = limit,
                    )
            ) {
                is LearnerMasteryCutoverAttestationProgress.Continue ->
                    next = result.cursor
                is LearnerMasteryCutoverAttestationProgress.Verified ->
                    return result.attestation
            }
        }
        error("Mastery binding verification did not terminate")
    }

    private suspend fun drainProjections(
        engine: LearnerMasteryCutoverDestinationAttestationEngine,
        binding: LearnerMasteryCutoverDestinationBinding,
        cursor: LearnerMasteryCutoverVerificationCursor?,
        limit: Int,
    ): LearnerMasteryProjectionsRebuiltAttestation {
        var next = cursor
        repeat(2_000) {
            when (
                val result =
                    engine.projectionsRebuilt.verifyProjectionsNext(
                        binding = binding,
                        cursor = next,
                        limit = limit,
                    )
            ) {
                is LearnerMasteryCutoverAttestationProgress.Continue ->
                    next = result.cursor
                is LearnerMasteryCutoverAttestationProgress.Verified ->
                    return result.attestation
            }
        }
        error("Mastery projection verification did not terminate")
    }

    private fun engine(
        source: FakeSource,
        now: AtomicLong = AtomicLong(5_000L),
    ): LearnerMasteryCutoverDestinationAttestationEngine =
        LearnerMasteryCutoverDestinationAttestationEngine(
            source = source,
            nowEpochMillis = LongSupplier(now::get),
            ownerKey = LearnerMasteryOwnerKey.INSTANCE,
        )

    private class FakeSource(
        counts: Map<LearnerMasteryCutoverVerificationPhase, Int> = emptyMap(),
        private val recordNamespace: String = "record",
    ) : LearnerMasteryCutoverDestinationReadSource {
        private val counts =
            EnumMap<LearnerMasteryCutoverVerificationPhase, Int>(
                LearnerMasteryCutoverVerificationPhase::class.java,
            ).apply {
                LearnerMasteryCutoverVerificationPhase.entries.forEach { phase ->
                    put(phase, counts[phase] ?: 0)
                }
            }
        val pageLimits = mutableListOf<Int>()
        var maximumRowsReturned = 0
        var eventBindingFingerprint = EVENT_BINDING_FINGERPRINT
        var projectionFingerprint = PROJECTION_FINGERPRINT
        var eventBindingsConsistent = true
        var projectionsConsistent = true
        var cutoverFencePresent = false
        var completionReceiptPresent = false

        override suspend fun readSnapshot(
            learnerId: String,
            sourceGeneration: String,
        ): LearnerMasteryCutoverDestinationSnapshot =
            LearnerMasteryCutoverDestinationSnapshot(
                destinationIdentityFingerprint = DESTINATION_IDENTITY_FINGERPRINT,
                ledgerDestinationFingerprint =
                    FACTS_IMPORT_RECEIPT.destinationFingerprint,
                ledgerMigratedRecordCount =
                    FACTS_IMPORT_RECEIPT.migratedRecordCount,
                eventBindingFingerprint = eventBindingFingerprint,
                projectionFingerprint = projectionFingerprint,
                immutableLedgerWatermarkFingerprint =
                    IMMUTABLE_WATERMARK_FINGERPRINT,
                activeProjectionGenerationFingerprint =
                    ACTIVE_GENERATION_FINGERPRINT,
                activeProjectionGenerationId = 13L,
                eventBindingsConsistent = eventBindingsConsistent,
                projectionsConsistent = projectionsConsistent,
                cutoverFencePresent = cutoverFencePresent,
                completionReceiptPresent = completionReceiptPresent,
            )

        override suspend fun readPage(
            phase: LearnerMasteryCutoverVerificationPhase,
            learnerId: String,
            sourceGeneration: String,
            projectionGenerationId: Long,
            afterExclusive: String?,
            limit: Int,
        ): List<LearnerMasteryCutoverVerificationRecord> {
            pageLimits += limit
            val count = counts.getValue(phase)
            val nextIndex =
                afterExclusive?.substringAfterLast('-')?.toInt()?.plus(1) ?: 0
            val returned =
                (nextIndex until minOf(count, nextIndex + limit)).map { index ->
                    val stableKey =
                        "${phase.name.lowercase()}-${index.toString().padStart(8, '0')}"
                    LearnerMasteryCutoverVerificationRecord(
                        stableKey = stableKey,
                        canonicalFingerprint =
                            fingerprint("$recordNamespace:${phase.name}:$index"),
                    )
                }
            maximumRowsReturned = maxOf(maximumRowsReturned, returned.size)
            return returned
        }

        override fun close() = Unit
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
        private val EVENT_BINDING_FINGERPRINT = fingerprint("event-bindings")
        private val PROJECTION_FINGERPRINT = fingerprint("projections")
        private val IMMUTABLE_WATERMARK_FINGERPRINT =
            fingerprint("immutable-watermark")
        private val ACTIVE_GENERATION_FINGERPRINT =
            fingerprint("active-generation")
        private val DESTINATION_IDENTITY_FINGERPRINT =
            fingerprint("destination-identity")
        private val FACTS_IMPORT_RECEIPT = factsImportReceipt()

        private fun factsImportReceipt(
            receiptFingerprint: String = fingerprint("stage-nine-receipt"),
        ): LearnerMasteryFactsImportReceiptReference =
            LearnerMasteryFactsImportReceiptReference.issue(
                ownerKey = LearnerMasteryOwnerKey.INSTANCE,
                learnerId = "learner:local",
                cutoverGeneration = 7L,
                legacyPrefixFingerprint = fingerprint("legacy-prefix"),
                migratedRecordCount = 5L,
                sourceGeneration = fingerprint("source-generation"),
                sourceCheckpoint = "stage-nine-source-checkpoint",
                destinationFingerprint = fingerprint("stage-nine-destination"),
                receiptFingerprint = receiptFingerprint,
            )

        private fun fingerprint(value: String): String =
            CanonicalSha256("learner-mastery-cutover-attestation-contract-test")
                .field("value", value)
                .finish()
    }
}
