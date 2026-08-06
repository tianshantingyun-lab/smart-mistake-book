package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.util.function.LongSupplier

internal data class LearnerMasteryCutoverVerificationRecord(
    val stableKey: String,
    val canonicalFingerprint: String,
) {
    init {
        require(
            stableKey.isNotBlank() &&
                stableKey.length <= MAX_VERIFICATION_KEY_CHARS &&
                stableKey.none { it.isISOControl() },
        ) {
            "Mastery destination verification key is invalid"
        }
        requireMasteryFingerprint(
            canonicalFingerprint,
            "Mastery destination verification record fingerprint",
        )
    }
}

internal data class LearnerMasteryCutoverDestinationSnapshot(
    val destinationIdentityFingerprint: String,
    val ledgerDestinationFingerprint: String,
    val ledgerMigratedRecordCount: Long,
    val eventBindingFingerprint: String,
    val projectionFingerprint: String,
    val immutableLedgerWatermarkFingerprint: String,
    val activeProjectionGenerationFingerprint: String,
    val activeProjectionGenerationId: Long,
    val eventBindingsConsistent: Boolean,
    val projectionsConsistent: Boolean,
    val cutoverFencePresent: Boolean,
    val completionReceiptPresent: Boolean,
) {
    init {
        listOf(
            "Mastery destination identity fingerprint" to
                destinationIdentityFingerprint,
            "Mastery migration-ledger fingerprint" to ledgerDestinationFingerprint,
            "Mastery event/binding snapshot fingerprint" to eventBindingFingerprint,
            "Mastery projection snapshot fingerprint" to projectionFingerprint,
            "Mastery immutable-ledger watermark fingerprint" to
                immutableLedgerWatermarkFingerprint,
            "Mastery active projection-generation fingerprint" to
                activeProjectionGenerationFingerprint,
        ).forEach { (label, fingerprint) ->
            requireMasteryFingerprint(fingerprint, label)
        }
        require(ledgerMigratedRecordCount >= 0L) {
            "Mastery migration-ledger count must not be negative"
        }
        require(activeProjectionGenerationId >= 0L) {
            "Mastery projection generation must not be negative"
        }
    }
}

/**
 * Private, read-only destination model. Implementations must return strictly ordered bounded
 * pages; no caller-supplied summary is accepted as proof material.
 */
internal interface LearnerMasteryCutoverDestinationReadSource : AutoCloseable {
    suspend fun readSnapshot(
        learnerId: String,
        sourceGeneration: String,
    ): LearnerMasteryCutoverDestinationSnapshot

    suspend fun readPage(
        phase: LearnerMasteryCutoverVerificationPhase,
        learnerId: String,
        sourceGeneration: String,
        projectionGenerationId: Long,
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverVerificationRecord>
}

internal class LearnerMasteryCutoverDestinationAttestationEngine(
    private val source: LearnerMasteryCutoverDestinationReadSource,
    private val nowEpochMillis: LongSupplier,
    private val ownerKey: LearnerMasteryOwnerKey,
) : LearnerMasteryCutoverDestinationAttestationPorts,
    LearnerMasteryCutoverDestinationBindingPort,
    LearnerMasteryBindingsReconciledAttestationPort,
    LearnerMasteryProjectionsRebuiltAttestationPort,
    LearnerMasteryAuthorityVerifiedAttestationPort {
    override val binding: LearnerMasteryCutoverDestinationBindingPort
        get() = this
    override val bindingsReconciled: LearnerMasteryBindingsReconciledAttestationPort
        get() = this
    override val projectionsRebuilt: LearnerMasteryProjectionsRebuiltAttestationPort
        get() = this
    override val authorityVerified: LearnerMasteryAuthorityVerifiedAttestationPort
        get() = this

    init {
        check(ownerKey === LearnerMasteryOwnerKey.INSTANCE) {
            "Mastery destination attestation engine requires the database owner key"
        }
    }

    override suspend fun bind(
        factsImportReceipt: LearnerMasteryFactsImportReceiptReference,
    ): LearnerMasteryCutoverDestinationBinding {
        factsImportReceipt.requireOwnerIssued()
        val snapshot =
            source.readSnapshot(
                learnerId = factsImportReceipt.learnerId,
                sourceGeneration = factsImportReceipt.sourceGeneration,
            )
        requireExactFactsImport(snapshot, factsImportReceipt)
        check(!snapshot.cutoverFencePresent && !snapshot.completionReceiptPresent) {
            "Mastery destination scope must be captured before its terminal fence"
        }
        check(snapshot.activeProjectionGenerationId > 0L) {
            "Mastery destination has no active projection generation"
        }
        return LearnerMasteryCutoverDestinationBinding.issue(
            ownerKey = ownerKey,
            receipt = factsImportReceipt,
            destinationIdentityFingerprint =
                snapshot.destinationIdentityFingerprint,
            projectionGenerationId = snapshot.activeProjectionGenerationId,
        )
    }

    override suspend fun verifyBindingsNext(
        binding: LearnerMasteryCutoverDestinationBinding,
        cursor: LearnerMasteryCutoverVerificationCursor?,
        limit: Int,
    ): LearnerMasteryCutoverAttestationProgress<LearnerMasteryBindingsReconciledAttestation> {
        requireCurrentScope(binding)
        val state =
            resolveState(
                binding = binding,
                stage =
                    LearnerMasteryCutoverDestinationStage
                        .MASTERY_BINDINGS_RECONCILED,
                firstPhase = LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS,
                allowedPhases = BINDING_PHASES,
                cursor = cursor,
                limit = limit,
                snapshotSelector =
                    LearnerMasteryCutoverDestinationSnapshot::eventBindingFingerprint,
            )
        return when (
            val page =
                verifyPage(
                    binding = binding,
                    stage =
                        LearnerMasteryCutoverDestinationStage
                            .MASTERY_BINDINGS_RECONCILED,
                    state = state,
                    limit = limit,
                    phases = BINDING_PHASES,
                    snapshotSelector =
                        LearnerMasteryCutoverDestinationSnapshot
                            ::eventBindingFingerprint,
                )
        ) {
            is VerifiedPage.Continue ->
                LearnerMasteryCutoverAttestationProgress.Continue(page.cursor)

            is VerifiedPage.Complete ->
                LearnerMasteryCutoverAttestationProgress.Verified(
                    LearnerMasteryBindingsReconciledAttestation.issue(
                        ownerKey = ownerKey,
                        binding = binding,
                        verifiedRecordCount = page.verifiedRecordCount,
                        destinationSnapshotFingerprint = page.snapshotFingerprint,
                        verificationFingerprint = page.verificationFingerprint,
                        immutableLedgerWatermarkFingerprint =
                            page.finalSnapshot
                                .immutableLedgerWatermarkFingerprint,
                        issuedAtEpochMillis = currentTime(),
                    ),
                )
        }
    }

    override suspend fun verifyProjectionsNext(
        binding: LearnerMasteryCutoverDestinationBinding,
        cursor: LearnerMasteryCutoverVerificationCursor?,
        limit: Int,
    ): LearnerMasteryCutoverAttestationProgress<LearnerMasteryProjectionsRebuiltAttestation> {
        requireCurrentScope(binding)
        val state =
            resolveState(
                binding = binding,
                stage =
                    LearnerMasteryCutoverDestinationStage
                        .MASTERY_PROJECTIONS_REBUILT,
                firstPhase =
                    LearnerMasteryCutoverVerificationPhase.PROJECTION_GENERATION,
                allowedPhases = PROJECTION_PHASES,
                cursor = cursor,
                limit = limit,
                snapshotSelector =
                    LearnerMasteryCutoverDestinationSnapshot::projectionFingerprint,
            )
        return when (
            val page =
                verifyPage(
                    binding = binding,
                    stage =
                        LearnerMasteryCutoverDestinationStage
                            .MASTERY_PROJECTIONS_REBUILT,
                    state = state,
                    limit = limit,
                    phases = PROJECTION_PHASES,
                    snapshotSelector =
                        LearnerMasteryCutoverDestinationSnapshot
                            ::projectionFingerprint,
                )
        ) {
            is VerifiedPage.Continue ->
                LearnerMasteryCutoverAttestationProgress.Continue(page.cursor)

            is VerifiedPage.Complete ->
                LearnerMasteryCutoverAttestationProgress.Verified(
                    LearnerMasteryProjectionsRebuiltAttestation.issue(
                        ownerKey = ownerKey,
                        binding = binding,
                        verifiedRecordCount = page.verifiedRecordCount,
                        destinationSnapshotFingerprint = page.snapshotFingerprint,
                        verificationFingerprint = page.verificationFingerprint,
                        activeProjectionGenerationFingerprint =
                            page.finalSnapshot
                                .activeProjectionGenerationFingerprint,
                        issuedAtEpochMillis = currentTime(),
                    ),
                )
        }
    }

    override suspend fun attest(
        binding: LearnerMasteryCutoverDestinationBinding,
        factsImportReceipt: LearnerMasteryFactsImportReceiptReference,
        bindingsReconciled: LearnerMasteryBindingsReconciledAttestation,
        projectionsRebuilt: LearnerMasteryProjectionsRebuiltAttestation,
    ): LearnerMasteryAuthorityVerifiedAttestation {
        requireCurrentScope(binding)
        factsImportReceipt.requireOwnerIssued()
        require(binding.matches(factsImportReceipt)) {
            "Mastery facts-import receipt is outside the destination binding"
        }
        require(bindingsReconciled.matches(binding)) {
            "Mastery binding attestation is outside the destination binding"
        }
        require(projectionsRebuilt.matches(binding)) {
            "Mastery projection attestation is outside the destination binding"
        }

        val snapshot = readAndRequireReadySnapshot(binding)
        check(!snapshot.cutoverFencePresent && !snapshot.completionReceiptPresent) {
            "Mastery authority must be verified before its terminal fence"
        }
        check(
            snapshot.eventBindingFingerprint ==
                bindingsReconciled.destinationSnapshotFingerprint &&
                snapshot.immutableLedgerWatermarkFingerprint ==
                bindingsReconciled.immutableLedgerWatermarkFingerprint
        ) {
            "Mastery event ledger changed after binding reconciliation"
        }
        check(
            snapshot.projectionFingerprint ==
                projectionsRebuilt.destinationSnapshotFingerprint &&
                snapshot.activeProjectionGenerationFingerprint ==
                projectionsRebuilt.activeProjectionGenerationFingerprint
        ) {
            "Mastery derived state changed after projection verification"
        }
        val verifiedRecordCount =
            checkedSum(
                factsImportReceipt.migratedRecordCount,
                bindingsReconciled.verifiedRecordCount,
                projectionsRebuilt.verifiedRecordCount,
            )
        val preFenceFingerprint =
            CanonicalSha256(PRE_FENCE_SNAPSHOT_DOMAIN)
                .field("bindingFingerprint", binding.bindingFingerprint)
                .field(
                    "factsImportReceiptFingerprint",
                    factsImportReceipt.receiptFingerprint,
                )
                .field(
                    "factsImportSourceCheckpoint",
                    factsImportReceipt.sourceCheckpoint,
                )
                .field(
                    "immutableLedgerWatermarkFingerprint",
                    snapshot.immutableLedgerWatermarkFingerprint,
                )
                .field(
                    "eventBindingFingerprint",
                    snapshot.eventBindingFingerprint,
                )
                .field("projectionFingerprint", snapshot.projectionFingerprint)
                .field(
                    "activeProjectionGenerationFingerprint",
                    snapshot.activeProjectionGenerationFingerprint,
                )
                .field("cutoverFencePresent", snapshot.cutoverFencePresent)
                .field(
                    "completionReceiptPresent",
                    snapshot.completionReceiptPresent,
                )
                .finish()
        val authorityVerificationFingerprint =
            CanonicalSha256(AUTHORITY_VERIFICATION_FINGERPRINT_DOMAIN)
                .field("bindingFingerprint", binding.bindingFingerprint)
                .field(
                    "factsImportReferenceFingerprint",
                    factsImportReceipt.referenceFingerprint,
                )
                .field(
                    "bindingsVerificationFingerprint",
                    bindingsReconciled.verificationFingerprint,
                )
                .field(
                    "projectionsVerificationFingerprint",
                    projectionsRebuilt.verificationFingerprint,
                )
                .field("preFenceFingerprint", preFenceFingerprint)
                .finish()
        return LearnerMasteryAuthorityVerifiedAttestation.issue(
            ownerKey = ownerKey,
            binding = binding,
            verifiedRecordCount = verifiedRecordCount,
            destinationSnapshotFingerprint = preFenceFingerprint,
            verificationFingerprint = authorityVerificationFingerprint,
            issuedAtEpochMillis = currentTime(),
            bindingsReconciledAttestationFingerprint =
                bindingsReconciled.attestationFingerprint,
            projectionsRebuiltAttestationFingerprint =
                projectionsRebuilt.attestationFingerprint,
        )
    }

    override fun close() {
        source.close()
    }

    private suspend fun resolveState(
        binding: LearnerMasteryCutoverDestinationBinding,
        stage: LearnerMasteryCutoverDestinationStage,
        firstPhase: LearnerMasteryCutoverVerificationPhase,
        allowedPhases: List<LearnerMasteryCutoverVerificationPhase>,
        cursor: LearnerMasteryCutoverVerificationCursor?,
        limit: Int,
        snapshotSelector: (LearnerMasteryCutoverDestinationSnapshot) -> String,
    ): VerificationState {
        require(limit in 1..MAX_ATTESTATION_PAGE_SIZE) {
            "Mastery destination verification page size is outside the supported range"
        }
        if (cursor == null) {
            val snapshot = readAndRequireReadySnapshot(binding, stage)
            return VerificationState(
                phase = firstPhase,
                afterExclusive = null,
                rollingFingerprint = initialRollingFingerprint(binding, stage),
                verifiedRecordCount = 0L,
                initialSnapshotFingerprint = snapshotSelector(snapshot),
                startedAtEpochMillis = currentTime(),
            )
        }
        LearnerMasteryCutoverVerificationCursor.requireIssued(cursor)
        require(cursor.stage == stage) {
            "Mastery destination cursor belongs to another stage"
        }
        require(cursor.bindingFingerprint == binding.bindingFingerprint) {
            "Mastery destination cursor belongs to another cutover binding"
        }
        require(cursor.phase in allowedPhases) {
            "Mastery destination cursor has an invalid verification phase"
        }
        require(cursor.verifiedRecordCount <= MAX_VERIFIED_RECORDS) {
            "Mastery destination cursor exceeds its record budget"
        }
        return VerificationState(
            phase = cursor.phase,
            afterExclusive = cursor.afterExclusive,
            rollingFingerprint = cursor.rollingFingerprint,
            verifiedRecordCount = cursor.verifiedRecordCount,
            initialSnapshotFingerprint = cursor.initialSnapshotFingerprint,
            startedAtEpochMillis = cursor.startedAtEpochMillis,
        )
    }

    private suspend fun verifyPage(
        binding: LearnerMasteryCutoverDestinationBinding,
        stage: LearnerMasteryCutoverDestinationStage,
        state: VerificationState,
        limit: Int,
        phases: List<LearnerMasteryCutoverVerificationPhase>,
        snapshotSelector: (LearnerMasteryCutoverDestinationSnapshot) -> String,
    ): VerifiedPage {
        val rows =
            source.readPage(
                phase = state.phase,
                learnerId = binding.learnerId,
                sourceGeneration = binding.factsImportSourceGeneration,
                projectionGenerationId = binding.projectionGenerationId,
                afterExclusive = state.afterExclusive,
                limit = limit + 1,
            )
        check(rows.size <= limit + 1) {
            "Mastery destination source exceeded the requested page bound"
        }
        check(
            rows.zipWithNext().all { (left, right) ->
                left.stableKey < right.stableKey
            } &&
                rows.firstOrNull()?.stableKey?.let { first ->
                    state.afterExclusive == null || first > state.afterExclusive
                } != false,
        ) {
            "Mastery destination source returned a non-canonical page"
        }
        val admitted = rows.take(limit)
        var rollingFingerprint = state.rollingFingerprint
        admitted.forEach { row ->
            rollingFingerprint =
                CanonicalSha256(ROLLING_FINGERPRINT_DOMAIN)
                    .field("previous", rollingFingerprint)
                    .field("phase", state.phase.name)
                    .field("stableKey", row.stableKey)
                    .field("recordFingerprint", row.canonicalFingerprint)
                    .finish()
        }
        val verifiedRecordCount =
            try {
                Math.addExact(state.verifiedRecordCount, admitted.size.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalStateException(
                    "Mastery destination verification count overflowed",
                )
            }
        check(verifiedRecordCount <= MAX_VERIFIED_RECORDS) {
            "Mastery destination verification exceeds its record budget"
        }
        if (rows.size > limit) {
            return VerifiedPage.Continue(
                issueCursor(
                    binding = binding,
                    stage = stage,
                    state = state,
                    phase = state.phase,
                    afterExclusive = admitted.last().stableKey,
                    rollingFingerprint = rollingFingerprint,
                    verifiedRecordCount = verifiedRecordCount,
                ),
            )
        }
        val phaseIndex = phases.indexOf(state.phase)
        check(phaseIndex >= 0) { "Mastery destination verification phase is invalid" }
        val nextPhase = phases.getOrNull(phaseIndex + 1)
        if (nextPhase != null) {
            return VerifiedPage.Continue(
                issueCursor(
                    binding = binding,
                    stage = stage,
                    state = state,
                    phase = nextPhase,
                    afterExclusive = null,
                    rollingFingerprint = rollingFingerprint,
                    verifiedRecordCount = verifiedRecordCount,
                ),
            )
        }
        val finalSnapshot = readAndRequireReadySnapshot(binding, stage)
        val finalFingerprint = snapshotSelector(finalSnapshot)
        check(finalFingerprint == state.initialSnapshotFingerprint) {
            "Mastery destination changed during bounded verification"
        }
        return VerifiedPage.Complete(
            verifiedRecordCount = verifiedRecordCount,
            snapshotFingerprint = finalFingerprint,
            verificationFingerprint = rollingFingerprint,
            finalSnapshot = finalSnapshot,
        )
    }

    private suspend fun readAndRequireReadySnapshot(
        binding: LearnerMasteryCutoverDestinationBinding,
        stage: LearnerMasteryCutoverDestinationStage =
            LearnerMasteryCutoverDestinationStage.MASTERY_AUTHORITY_VERIFIED,
    ): LearnerMasteryCutoverDestinationSnapshot {
        val snapshot =
            source.readSnapshot(
                learnerId = binding.learnerId,
                sourceGeneration = binding.factsImportSourceGeneration,
            )
        requireSnapshotMatchesBinding(snapshot, binding)
        check(
            when (stage) {
                LearnerMasteryCutoverDestinationStage.MASTERY_BINDINGS_RECONCILED ->
                    snapshot.eventBindingsConsistent

                LearnerMasteryCutoverDestinationStage.MASTERY_PROJECTIONS_REBUILT ->
                    snapshot.projectionsConsistent

                LearnerMasteryCutoverDestinationStage.MASTERY_AUTHORITY_VERIFIED ->
                    snapshot.eventBindingsConsistent && snapshot.projectionsConsistent
            },
        ) {
            "Mastery destination is not ready for ${stage.name}"
        }
        return snapshot
    }

    private fun requireSnapshotMatchesBinding(
        snapshot: LearnerMasteryCutoverDestinationSnapshot,
        binding: LearnerMasteryCutoverDestinationBinding,
    ) {
        check(
            snapshot.destinationIdentityFingerprint ==
                binding.destinationIdentityFingerprint
        ) {
            "Mastery destination identity changed after binding"
        }
        check(
            snapshot.ledgerDestinationFingerprint ==
                binding.factsImportDestinationFingerprint
        ) {
            "Mastery migration ledger changed after stage nine"
        }
        check(snapshot.activeProjectionGenerationId == binding.projectionGenerationId) {
            "Mastery active projection generation changed after binding"
        }
    }

    private fun requireExactFactsImport(
        snapshot: LearnerMasteryCutoverDestinationSnapshot,
        receipt: LearnerMasteryFactsImportReceiptReference,
    ) {
        check(
            snapshot.ledgerMigratedRecordCount == receipt.migratedRecordCount &&
                snapshot.ledgerDestinationFingerprint ==
                receipt.destinationFingerprint
        ) {
            "Mastery facts-import receipt does not match the physical migration ledger"
        }
    }

    private fun requireCurrentScope(binding: LearnerMasteryCutoverDestinationBinding) {
        binding.requireOwnerIssued()
        require(binding.masterySchemaVersion == LEARNER_MASTERY_DATABASE_VERSION) {
            "Mastery destination binding uses another schema version"
        }
        require(binding.sourcePolicyVersion == LEARNER_MASTERY_SOURCE_POLICY_VERSION) {
            "Mastery destination binding uses another source policy"
        }
        require(
            binding.admissionPolicyVersion ==
                LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
        ) {
            "Mastery destination binding uses another admission policy"
        }
        require(binding.calibrationVersion == LEARNER_MASTERY_CALIBRATION_VERSION) {
            "Mastery destination binding uses another calibration"
        }
        require(
            binding.projectionPolicyVersion ==
                LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        ) {
            "Mastery destination binding uses another projection policy"
        }
        require(
            binding.attestationPolicyVersion ==
                LEARNER_MASTERY_CUTOVER_ATTESTATION_POLICY_VERSION,
        ) {
            "Mastery destination binding uses another attestation policy"
        }
    }

    private fun issueCursor(
        binding: LearnerMasteryCutoverDestinationBinding,
        stage: LearnerMasteryCutoverDestinationStage,
        state: VerificationState,
        phase: LearnerMasteryCutoverVerificationPhase,
        afterExclusive: String?,
        rollingFingerprint: String,
        verifiedRecordCount: Long,
    ): LearnerMasteryCutoverVerificationCursor =
        LearnerMasteryCutoverVerificationCursor.issue(
            ownerKey = ownerKey,
            stage = stage,
            verifiedRecordCount = verifiedRecordCount,
            bindingFingerprint = binding.bindingFingerprint,
            phase = phase,
            afterExclusive = afterExclusive,
            rollingFingerprint = rollingFingerprint,
            initialSnapshotFingerprint = state.initialSnapshotFingerprint,
            startedAtEpochMillis = state.startedAtEpochMillis,
        )

    private fun currentTime(): Long =
        nowEpochMillis.getAsLong().also { now ->
            check(now >= 0L) {
                "Mastery destination attestation time must not be negative"
            }
        }
}

private data class VerificationState(
    val phase: LearnerMasteryCutoverVerificationPhase,
    val afterExclusive: String?,
    val rollingFingerprint: String,
    val verifiedRecordCount: Long,
    val initialSnapshotFingerprint: String,
    val startedAtEpochMillis: Long,
)

private sealed interface VerifiedPage {
    data class Continue(
        val cursor: LearnerMasteryCutoverVerificationCursor,
    ) : VerifiedPage

    data class Complete(
        val verifiedRecordCount: Long,
        val snapshotFingerprint: String,
        val verificationFingerprint: String,
        val finalSnapshot: LearnerMasteryCutoverDestinationSnapshot,
    ) : VerifiedPage
}

private fun initialRollingFingerprint(
    binding: LearnerMasteryCutoverDestinationBinding,
    stage: LearnerMasteryCutoverDestinationStage,
): String =
    CanonicalSha256(ROLLING_FINGERPRINT_DOMAIN)
        .field("bindingFingerprint", binding.bindingFingerprint)
        .field("stage", stage.name)
        .finish()

private fun checkedSum(
    first: Long,
    second: Long,
    third: Long,
): Long =
    try {
        Math.addExact(first, Math.addExact(second, third))
    } catch (_: ArithmeticException) {
        throw IllegalStateException("Mastery authority verification count overflowed")
    }

private val BINDING_PHASES =
    listOf(
        LearnerMasteryCutoverVerificationPhase.SOURCE_FACTS,
        LearnerMasteryCutoverVerificationPhase.CANDIDATES,
        LearnerMasteryCutoverVerificationPhase.ATTRIBUTIONS,
        LearnerMasteryCutoverVerificationPhase.PROBLEM_BINDINGS,
        LearnerMasteryCutoverVerificationPhase.SUPERSESSIONS,
        LearnerMasteryCutoverVerificationPhase.MIGRATION_LEDGER,
    )
private val PROJECTION_PHASES =
    listOf(
        LearnerMasteryCutoverVerificationPhase.PROJECTION_GENERATION,
        LearnerMasteryCutoverVerificationPhase.PROJECTIONS,
        LearnerMasteryCutoverVerificationPhase.SUBJECT_DIGESTS,
        LearnerMasteryCutoverVerificationPhase.PRESENTATION_BUDGETS,
        LearnerMasteryCutoverVerificationPhase.PROBLEM_FAMILY_BUDGETS,
        LearnerMasteryCutoverVerificationPhase.SCHEMA_OBJECTS,
    )
private const val MAX_ATTESTATION_PAGE_SIZE = 256
private const val MAX_VERIFIED_RECORDS = 10_000_000L
private const val MAX_VERIFICATION_KEY_CHARS = 1024
private const val ROLLING_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-verification-rolling-v1"
private const val PRE_FENCE_SNAPSHOT_DOMAIN =
    "learner-mastery-cutover-pre-fence-snapshot-v1"
private const val AUTHORITY_VERIFICATION_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-authority-verification-v1"
