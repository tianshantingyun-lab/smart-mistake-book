package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalReadPort
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryAuthorityVerifiedAttestation
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryBindingsReconciledAttestation
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverAttestationProgress
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverDestinationAttestation
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverDestinationAttestationPorts
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverDestinationBinding
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverDestinationStage
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverVerificationCursor
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryFactsImportReceiptReference
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryProjectionsRebuiltAttestation
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.bindVerifiedLearnerMasteryFactsImportReceipt
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryCutoverControlOwner
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryCutoverDestinationAttestations
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Current-policy owner for mastery stages 10–12.
 *
 * Every pass freshly proves stage nine against the legacy source and the immutable mastery import
 * ledger, then asks the destination owner to scan its current canonical layout. Old attestations
 * whose canonical policy is no longer current therefore fail verification instead of being
 * translated or re-signed from historical fields.
 */
internal class ProductionLearnerMasteryTerminalAuthorityAttestationOwner(
    context: Context,
    private val layout: ThreeAuthorityDatabaseLayout,
    private val legacySource: LegacyAuthorityMigrationSourcePort,
    private val journal: LegacyAuthorityCutoverJournalReadPort,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val canonicalContext = context.applicationContext ?: context
    private val destination: LearnerMasteryCutoverDestinationAttestationPorts =
        openLearnerMasteryCutoverDestinationAttestations(canonicalContext)
    private val closed = AtomicBoolean(false)
    private var boundState: BoundState? = null
    private var bindingsAttestation: LearnerMasteryBindingsReconciledAttestation? = null
    private var projectionsAttestation: LearnerMasteryProjectionsRebuiltAttestation? = null

    fun operations(): List<TerminalAuthorityStageOperation> =
        listOf(
            operation(
                ThreeAuthorityCutoverStage.MASTERY_BINDINGS_RECONCILED,
                ::attestBindings,
            ),
            operation(
                ThreeAuthorityCutoverStage.MASTERY_PROJECTIONS_REBUILT,
                ::attestProjections,
            ),
            operation(
                ThreeAuthorityCutoverStage.MASTERY_AUTHORITY_VERIFIED,
                ::attestAuthority,
            ),
        )

    private fun operation(
        stage: ThreeAuthorityCutoverStage,
        attest: suspend (TerminalAuthorityCutoverBinding) -> TerminalAuthorityStageSnapshot,
    ): TerminalAuthorityStageOperation =
        object : TerminalAuthorityStageOperation {
            override val stage: ThreeAuthorityCutoverStage = stage

            override suspend fun migrate(
                binding: TerminalAuthorityCutoverBinding,
                predecessor: AuthorityStageReceipt,
            ): TerminalAuthorityStageSnapshot = attest(binding)

            override suspend fun verify(
                binding: TerminalAuthorityCutoverBinding,
            ): TerminalAuthorityStageSnapshot = attest(binding)
        }

    private suspend fun attestBindings(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val state = requireCurrentBinding(binding)
        val attestation = verifyBindings(state.binding)
        requireCurrentAttestation(
            attestation,
            LearnerMasteryCutoverDestinationStage.MASTERY_BINDINGS_RECONCILED,
            state,
        )
        bindingsAttestation = attestation
        return attestation.toTerminalSnapshot()
    }

    private suspend fun attestProjections(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val state = requireCurrentBinding(binding)
        val attestation = verifyProjections(state.binding)
        requireCurrentAttestation(
            attestation,
            LearnerMasteryCutoverDestinationStage.MASTERY_PROJECTIONS_REBUILT,
            state,
        )
        projectionsAttestation = attestation
        return attestation.toTerminalSnapshot()
    }

    private suspend fun attestAuthority(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val state = requireCurrentBinding(binding)
        val bindings = verifyBindings(state.binding)
        val projections = verifyProjections(state.binding)
        requireCurrentAttestation(
            bindings,
            LearnerMasteryCutoverDestinationStage.MASTERY_BINDINGS_RECONCILED,
            state,
        )
        requireCurrentAttestation(
            projections,
            LearnerMasteryCutoverDestinationStage.MASTERY_PROJECTIONS_REBUILT,
            state,
        )
        bindingsAttestation = bindings
        projectionsAttestation = projections
        val attestation =
            destination.authorityVerified.attest(
                state.binding,
                state.receiptReference,
                bindings,
                projections,
            )
        requireCurrentAttestation(
            attestation,
            LearnerMasteryCutoverDestinationStage.MASTERY_AUTHORITY_VERIFIED,
            state,
        )
        check(
            attestation.bindingsReconciledAttestationFingerprint ==
                bindings.attestationFingerprint &&
                attestation.projectionsRebuiltAttestationFingerprint ==
                projections.attestationFingerprint,
        ) {
            "Mastery authority attestation does not bind its fresh prerequisite scans"
        }
        return attestation.toTerminalSnapshot()
    }

    private suspend fun requireCurrentBinding(
        requested: TerminalAuthorityCutoverBinding,
    ): BoundState {
        check(!closed.get()) { "Mastery terminal attestation owner is closed" }
        val verified = readVerifiedStageNine(requested)
        val current = boundState
        if (current != null) {
            check(current.importReceipt == verified.importReceipt) {
                "Mastery facts-import receipt changed during destination attestation"
            }
            current.requireMatches(verified.evidence, requested)
            return current
        }

        val reference =
            bindVerifiedLearnerMasteryFactsImportReceipt(
                LOCAL_LEARNER_ID,
                requested.cutoverGeneration,
                requested.legacyPrefixReceiptFingerprint,
                verified.evidence.migratedRecordCount,
                verified.evidence.sourceFingerprint,
                verified.evidence.sourceCheckpoint,
                verified.evidence.destinationFingerprint,
                verified.importReceipt.receiptFingerprint,
            )
        val destinationBinding = destination.binding.bind(reference)
        val issued =
            BoundState(
                importReceipt = verified.importReceipt,
                evidence = verified.evidence,
                receiptReference = reference,
                binding = destinationBinding,
            ).also { state -> state.requireMatches(verified.evidence, requested) }
        check(boundState == null) { "Mastery terminal binding was issued twice" }
        boundState = issued
        return issued
    }

    private suspend fun readVerifiedStageNine(
        binding: TerminalAuthorityCutoverBinding,
    ): VerifiedStageNine {
        val receipts = journal.readOrderedAuthorityReceipts()
        requireValidAuthorityCutoverJournalPrefix(layout, receipts)
        val importReceipt =
            checkNotNull(receipts.getOrNull(MASTERY_IMPORT_STAGE_INDEX)) {
                "Mastery destination attestation requires a durable stage-nine import"
            }
        check(importReceipt.stage == ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED)

        val target =
            openLearnerMasteryCutoverControlOwner(
                canonicalContext,
                LOCAL_LEARNER_ID,
            )
        val adapter =
            LearnerMasteryAuthorityCutoverControlAdapter(
                target = target,
                legacySource = legacySource,
                learnerId = LOCAL_LEARNER_ID,
                legacyPrefixReceiptFingerprint =
                    binding.legacyPrefixReceiptFingerprint,
                cutoverGeneration = binding.cutoverGeneration,
            )
        val evidence =
            try {
                checkNotNull(adapter.reverifyImmutableImportEvidence()) {
                    "Mastery immutable import cannot be freshly verified"
                }
            } finally {
                adapter.close()
            }
        importReceipt.requireBoundMasteryImport(binding, evidence)
        return VerifiedStageNine(importReceipt, evidence)
    }

    private suspend fun verifyBindings(
        binding: LearnerMasteryCutoverDestinationBinding,
    ): LearnerMasteryBindingsReconciledAttestation {
        var cursor: LearnerMasteryCutoverVerificationCursor? = null
        val seen = cursorIdentitySet()
        repeat(MAX_ATTESTATION_PAGES) {
            when (
                val progress =
                    destination.bindingsReconciled.verifyBindingsNext(
                        binding = binding,
                        cursor = cursor,
                        limit = ATTESTATION_PAGE_SIZE,
                    )
            ) {
                is LearnerMasteryCutoverAttestationProgress.Continue -> {
                    progress.cursor.requireFreshProgress(
                        expectedStage =
                            LearnerMasteryCutoverDestinationStage
                                .MASTERY_BINDINGS_RECONCILED,
                        previous = cursor,
                        seen = seen,
                    )
                    cursor = progress.cursor
                }

                is LearnerMasteryCutoverAttestationProgress.Verified ->
                    return progress.attestation
            }
        }
        error("Mastery binding verification exceeded its bounded page budget")
    }

    private suspend fun verifyProjections(
        binding: LearnerMasteryCutoverDestinationBinding,
    ): LearnerMasteryProjectionsRebuiltAttestation {
        var cursor: LearnerMasteryCutoverVerificationCursor? = null
        val seen = cursorIdentitySet()
        repeat(MAX_ATTESTATION_PAGES) {
            when (
                val progress =
                    destination.projectionsRebuilt.verifyProjectionsNext(
                        binding = binding,
                        cursor = cursor,
                        limit = ATTESTATION_PAGE_SIZE,
                    )
            ) {
                is LearnerMasteryCutoverAttestationProgress.Continue -> {
                    progress.cursor.requireFreshProgress(
                        expectedStage =
                            LearnerMasteryCutoverDestinationStage
                                .MASTERY_PROJECTIONS_REBUILT,
                        previous = cursor,
                        seen = seen,
                    )
                    cursor = progress.cursor
                }

                is LearnerMasteryCutoverAttestationProgress.Verified ->
                    return progress.attestation
            }
        }
        error("Mastery projection verification exceeded its bounded page budget")
    }

    private fun requireCurrentAttestation(
        attestation: LearnerMasteryCutoverDestinationAttestation,
        stage: LearnerMasteryCutoverDestinationStage,
        state: BoundState,
    ) {
        val now = clock().coerceAtLeast(0L)
        check(attestation.stage == stage)
        check(attestation.learnerId == LOCAL_LEARNER_ID)
        check(attestation.cutoverGeneration == state.evidence.cutoverGeneration)
        check(
            attestation.legacyPrefixFingerprint ==
                state.evidence.legacyPrefixReceiptFingerprint,
        )
        check(
            attestation.factsImportReceiptFingerprint ==
                state.importReceipt.receiptFingerprint &&
                attestation.factsImportSourceCheckpoint == state.evidence.sourceCheckpoint &&
                attestation.factsImportDestinationFingerprint ==
                state.evidence.destinationFingerprint,
        )
        check(attestation.verifiedRecordCount >= 0L)
        check(attestation.destinationSnapshotFingerprint.matches(SHA_256))
        check(attestation.verificationFingerprint.matches(SHA_256))
        check(attestation.attestationFingerprint.matches(SHA_256))
        check(attestation.issuedAtEpochMillis <= now + MAX_CLOCK_SKEW_MILLIS)
        check(now - attestation.issuedAtEpochMillis <= MAX_ATTESTATION_AGE_MILLIS)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            boundState = null
            bindingsAttestation = null
            projectionsAttestation = null
            destination.close()
        }
    }
}

private data class VerifiedStageNine(
    val importReceipt: AuthorityStageReceipt,
    val evidence: ImmutableAuthorityImportEvidence,
)

private data class BoundState(
    val importReceipt: AuthorityStageReceipt,
    val evidence: ImmutableAuthorityImportEvidence,
    val receiptReference: LearnerMasteryFactsImportReceiptReference,
    val binding: LearnerMasteryCutoverDestinationBinding,
) {
    fun requireMatches(
        currentEvidence: ImmutableAuthorityImportEvidence,
        requested: TerminalAuthorityCutoverBinding,
    ) {
        check(evidence == currentEvidence) {
            "Mastery immutable import evidence changed during terminal attestation"
        }
        check(binding.learnerId == LOCAL_LEARNER_ID)
        check(binding.cutoverGeneration == requested.cutoverGeneration)
        check(binding.legacyPrefixFingerprint == requested.legacyPrefixReceiptFingerprint)
        check(binding.factsImportReceiptFingerprint == importReceipt.receiptFingerprint)
        check(binding.factsImportSourceGeneration == evidence.sourceFingerprint)
        check(binding.factsImportSourceCheckpoint == evidence.sourceCheckpoint)
        check(binding.factsImportDestinationFingerprint == evidence.destinationFingerprint)
    }
}

private fun AuthorityStageReceipt.requireBoundMasteryImport(
    binding: TerminalAuthorityCutoverBinding,
    evidence: ImmutableAuthorityImportEvidence,
) {
    check(stage == ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED)
    check(evidence.authority == FencedAuthority.LEARNER_MASTERY)
    check(evidence.hasValidFingerprint())
    check(evidence.cutoverGeneration == binding.cutoverGeneration)
    check(
        evidence.legacyPrefixReceiptFingerprint ==
            binding.legacyPrefixReceiptFingerprint,
    )
    check(migratedRecordCount == evidence.migratedRecordCount)
    val expectedCheckpoint =
        "generation:${binding.cutoverGeneration}:" +
            "prefix:${binding.legacyPrefixReceiptFingerprint}:" +
            "source:${evidence.sourceCheckpoint}"
    check(sourceCheckpoint == expectedCheckpoint) {
        "Mastery stage-nine receipt does not bind the freshly verified source"
    }
    val expectedDestination =
        CanonicalSha256("terminal-authority-stage-state-v1")
            .field("stage", stage.name)
            .field("cutoverGeneration", binding.cutoverGeneration)
            .field(
                "legacyPrefixReceiptFingerprint",
                binding.legacyPrefixReceiptFingerprint,
            )
            .field("migratedRecordCount", evidence.migratedRecordCount)
            .field("sourceCheckpoint", evidence.sourceCheckpoint)
            .field("rawDestinationFingerprint", evidence.destinationFingerprint)
            .finish()
    check(destinationFingerprint == expectedDestination) {
        "Mastery stage-nine receipt no longer matches current immutable import evidence"
    }
}

private fun LearnerMasteryCutoverVerificationCursor.requireFreshProgress(
    expectedStage: LearnerMasteryCutoverDestinationStage,
    previous: LearnerMasteryCutoverVerificationCursor?,
    seen: MutableSet<LearnerMasteryCutoverVerificationCursor>,
) {
    check(stage == expectedStage) { "Mastery destination scan returned a foreign cursor" }
    check(seen.add(this)) { "Mastery destination scan repeated a cursor identity" }
    check(previous == null || verifiedRecordCount >= previous.verifiedRecordCount) {
        "Mastery destination scan moved its verified count backwards"
    }
}

private fun cursorIdentitySet(): MutableSet<LearnerMasteryCutoverVerificationCursor> =
    Collections.newSetFromMap(
        IdentityHashMap<LearnerMasteryCutoverVerificationCursor, Boolean>(),
    )

private fun LearnerMasteryCutoverDestinationAttestation.toTerminalSnapshot():
    TerminalAuthorityStageSnapshot =
    TerminalAuthorityStageSnapshot(
        migratedRecordCount = verifiedRecordCount,
        sourceCheckpoint = verificationFingerprint,
        destinationFingerprint = destinationSnapshotFingerprint,
    )

private const val MASTERY_IMPORT_STAGE_INDEX = 8
private const val ATTESTATION_PAGE_SIZE = 256
private const val MAX_ATTESTATION_PAGES = 100_000
private const val MAX_ATTESTATION_AGE_MILLIS = 5L * 60L * 1_000L
private const val MAX_CLOCK_SKEW_MILLIS = 30L * 1_000L
private val SHA_256 = Regex("[0-9a-f]{64}")
