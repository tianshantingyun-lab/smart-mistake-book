package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlinx.coroutines.sync.Mutex

/**
 * The only two authorities that persist terminal cutover state.
 *
 * Knowledge remains the third business authority, but contributes a freshly verified activation
 * witness instead of duplicating the student/mastery fence protocol.
 */
internal enum class FencedAuthority {
    STUDENT_MISTAKES,
    LEARNER_MASTERY,
}

/**
 * A fresh, canonical proof that a target authority still contains the exact immutable import.
 *
 * Implementations must separately read the current legacy migration-source snapshot and their
 * immutable target import ledger on every call, then return evidence only when the target proves
 * exact coverage of that source snapshot. A late legacy write therefore returns null until it is
 * imported. The coordinator calls this while its writer exclusion is held; adapters must compare
 * canonical values in memory and must not use cross-database SQL or `ATTACH`.
 */
internal data class ImmutableAuthorityImportEvidence(
    val authority: FencedAuthority,
    val cutoverGeneration: Long,
    val migratedRecordCount: Long,
    val sourceCheckpoint: String,
    val legacyPrefixReceiptFingerprint: String,
    val sourceFingerprint: String,
    val destinationFingerprint: String,
    val evidenceFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L) {
            "Cutover generation must be positive"
        }
        require(migratedRecordCount >= 0L) {
            "Imported record count must not be negative"
        }
        require(sourceCheckpoint.isValidCutoverText(maxLength = 512)) {
            "Import checkpoint is invalid"
        }
        require(legacyPrefixReceiptFingerprint.isSha256()) {
            "Legacy prefix receipt fingerprint must be lowercase SHA-256"
        }
        require(sourceFingerprint.isSha256()) {
            "Import source fingerprint must be lowercase SHA-256"
        }
        require(destinationFingerprint.isSha256()) {
            "Import destination fingerprint must be lowercase SHA-256"
        }
        require(evidenceFingerprint.isSha256()) {
            "Import evidence fingerprint must be lowercase SHA-256"
        }
    }

    fun hasValidFingerprint(): Boolean =
        evidenceFingerprint ==
            computeFingerprint(
                authority = authority,
                cutoverGeneration = cutoverGeneration,
                migratedRecordCount = migratedRecordCount,
                sourceCheckpoint = sourceCheckpoint,
                legacyPrefixReceiptFingerprint = legacyPrefixReceiptFingerprint,
                sourceFingerprint = sourceFingerprint,
                destinationFingerprint = destinationFingerprint,
            )

    companion object {
        fun create(
            authority: FencedAuthority,
            cutoverGeneration: Long,
            migratedRecordCount: Long,
            sourceCheckpoint: String,
            legacyPrefixReceiptFingerprint: String,
            sourceFingerprint: String,
            destinationFingerprint: String,
        ): ImmutableAuthorityImportEvidence =
            ImmutableAuthorityImportEvidence(
                authority = authority,
                cutoverGeneration = cutoverGeneration,
                migratedRecordCount = migratedRecordCount,
                sourceCheckpoint = sourceCheckpoint,
                legacyPrefixReceiptFingerprint = legacyPrefixReceiptFingerprint,
                sourceFingerprint = sourceFingerprint,
                destinationFingerprint = destinationFingerprint,
                evidenceFingerprint =
                    computeFingerprint(
                        authority = authority,
                        cutoverGeneration = cutoverGeneration,
                        migratedRecordCount = migratedRecordCount,
                        sourceCheckpoint = sourceCheckpoint,
                        legacyPrefixReceiptFingerprint = legacyPrefixReceiptFingerprint,
                        sourceFingerprint = sourceFingerprint,
                        destinationFingerprint = destinationFingerprint,
                    ),
            )

        private fun computeFingerprint(
            authority: FencedAuthority,
            cutoverGeneration: Long,
            migratedRecordCount: Long,
            sourceCheckpoint: String,
            legacyPrefixReceiptFingerprint: String,
            sourceFingerprint: String,
            destinationFingerprint: String,
        ): String =
            CanonicalSha256("immutable-authority-import-evidence-v1")
                .field("authority", authority.name)
                .field("cutoverGeneration", cutoverGeneration)
                .field("migratedRecordCount", migratedRecordCount)
                .field("sourceCheckpoint", sourceCheckpoint)
                .field(
                    "legacyPrefixReceiptFingerprint",
                    legacyPrefixReceiptFingerprint,
                )
                .field("sourceFingerprint", sourceFingerprint)
                .field("destinationFingerprint", destinationFingerprint)
                .finish()
    }
}

/**
 * A live witness for the currently active, physically verified knowledge package.
 *
 * Its generation is the knowledge activation sequence, not the student/mastery cutover generation.
 * This lets a later valid knowledge package replace an older package without rewriting either
 * append-only cutover fence.
 */
internal data class KnowledgeActivationWitness(
    val activationGeneration: Long,
    val activatedAtEpochMillis: Long,
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestFingerprint: String,
    val witnessFingerprint: String,
) {
    init {
        require(activationGeneration > 0L) {
            "Knowledge activation generation must be positive"
        }
        require(activatedAtEpochMillis >= 0L) {
            "Knowledge activation time must not be negative"
        }
        require(packId.isValidCutoverText()) {
            "Knowledge pack id is invalid"
        }
        require(knowledgePackVersion.isValidCutoverText()) {
            "Knowledge pack version is invalid"
        }
        require(taxonomyVersion.isValidCutoverText()) {
            "Knowledge taxonomy version is invalid"
        }
        require(manifestFingerprint.isSha256()) {
            "Knowledge manifest fingerprint must be lowercase SHA-256"
        }
        require(witnessFingerprint.isSha256()) {
            "Knowledge activation witness fingerprint must be lowercase SHA-256"
        }
    }

    fun hasValidFingerprint(): Boolean =
        witnessFingerprint ==
            computeFingerprint(
                activationGeneration = activationGeneration,
                activatedAtEpochMillis = activatedAtEpochMillis,
                packId = packId,
                knowledgePackVersion = knowledgePackVersion,
                taxonomyVersion = taxonomyVersion,
                manifestFingerprint = manifestFingerprint,
            )

    companion object {
        fun create(
            activationGeneration: Long,
            activatedAtEpochMillis: Long,
            packId: String,
            knowledgePackVersion: String,
            taxonomyVersion: String,
            manifestFingerprint: String,
        ): KnowledgeActivationWitness =
            KnowledgeActivationWitness(
                activationGeneration = activationGeneration,
                activatedAtEpochMillis = activatedAtEpochMillis,
                packId = packId,
                knowledgePackVersion = knowledgePackVersion,
                taxonomyVersion = taxonomyVersion,
                manifestFingerprint = manifestFingerprint,
                witnessFingerprint =
                    computeFingerprint(
                        activationGeneration = activationGeneration,
                        activatedAtEpochMillis = activatedAtEpochMillis,
                        packId = packId,
                        knowledgePackVersion = knowledgePackVersion,
                        taxonomyVersion = taxonomyVersion,
                        manifestFingerprint = manifestFingerprint,
                    ),
            )

        private fun computeFingerprint(
            activationGeneration: Long,
            activatedAtEpochMillis: Long,
            packId: String,
            knowledgePackVersion: String,
            taxonomyVersion: String,
            manifestFingerprint: String,
        ): String =
            CanonicalSha256("knowledge-activation-witness-v1")
                .field("activationGeneration", activationGeneration)
                .field("activatedAtEpochMillis", activatedAtEpochMillis)
                .field("packId", packId)
                .field("knowledgePackVersion", knowledgePackVersion)
                .field("taxonomyVersion", taxonomyVersion)
                .field("manifestFingerprint", manifestFingerprint)
                .finish()
    }
}

/**
 * The durable, deterministic fence stored inside one target authority.
 *
 * There is intentionally no timestamp: every recovery proposes the exact same bytes. A port must
 * atomically append the candidate if absent, or return the already durable record without updating
 * it.
 */
internal data class AuthorityCutoverFence(
    val authority: FencedAuthority,
    val cutoverGeneration: Long,
    val studentImportEvidenceFingerprint: String,
    val masteryImportEvidenceFingerprint: String,
    val cutoverIntentFingerprint: String,
    val fenceFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L)
        require(studentImportEvidenceFingerprint.isSha256())
        require(masteryImportEvidenceFingerprint.isSha256())
        require(cutoverIntentFingerprint.isSha256())
        require(fenceFingerprint.isSha256())
    }

    fun hasValidFingerprint(): Boolean =
        cutoverIntentFingerprint ==
            computeIntentFingerprint(
                cutoverGeneration = cutoverGeneration,
                studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
            ) &&
            fenceFingerprint ==
            computeFenceFingerprint(
                authority = authority,
                cutoverGeneration = cutoverGeneration,
                studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
                cutoverIntentFingerprint = cutoverIntentFingerprint,
            )

    companion object {
        fun create(
            authority: FencedAuthority,
            cutoverGeneration: Long,
            studentImportEvidenceFingerprint: String,
            masteryImportEvidenceFingerprint: String,
        ): AuthorityCutoverFence {
            val intentFingerprint =
                computeIntentFingerprint(
                    cutoverGeneration = cutoverGeneration,
                    studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                    masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
                )
            return AuthorityCutoverFence(
                authority = authority,
                cutoverGeneration = cutoverGeneration,
                studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
                cutoverIntentFingerprint = intentFingerprint,
                fenceFingerprint =
                    computeFenceFingerprint(
                        authority = authority,
                        cutoverGeneration = cutoverGeneration,
                        studentImportEvidenceFingerprint =
                            studentImportEvidenceFingerprint,
                        masteryImportEvidenceFingerprint =
                            masteryImportEvidenceFingerprint,
                        cutoverIntentFingerprint = intentFingerprint,
                    ),
            )
        }

        private fun computeIntentFingerprint(
            cutoverGeneration: Long,
            studentImportEvidenceFingerprint: String,
            masteryImportEvidenceFingerprint: String,
        ): String =
            CanonicalSha256("three-authority-cutover-intent-v1")
                .field("cutoverGeneration", cutoverGeneration)
                .field(
                    "studentImportEvidenceFingerprint",
                    studentImportEvidenceFingerprint,
                )
                .field(
                    "masteryImportEvidenceFingerprint",
                    masteryImportEvidenceFingerprint,
                )
                .finish()

        private fun computeFenceFingerprint(
            authority: FencedAuthority,
            cutoverGeneration: Long,
            studentImportEvidenceFingerprint: String,
            masteryImportEvidenceFingerprint: String,
            cutoverIntentFingerprint: String,
        ): String =
            CanonicalSha256("authority-cutover-fence-v1")
                .field("authority", authority.name)
                .field("cutoverGeneration", cutoverGeneration)
                .field(
                    "studentImportEvidenceFingerprint",
                    studentImportEvidenceFingerprint,
                )
                .field(
                    "masteryImportEvidenceFingerprint",
                    masteryImportEvidenceFingerprint,
                )
                .field("cutoverIntentFingerprint", cutoverIntentFingerprint)
                .finish()
    }
}

/** Deterministic terminal receipt stored beside its authority's append-only fence. */
internal data class AuthorityCutoverCompletionReceipt(
    val authority: FencedAuthority,
    val cutoverGeneration: Long,
    val cutoverIntentFingerprint: String,
    val authorityFenceFingerprint: String,
    val receiptFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L)
        require(cutoverIntentFingerprint.isSha256())
        require(authorityFenceFingerprint.isSha256())
        require(receiptFingerprint.isSha256())
    }

    fun hasValidFingerprint(): Boolean =
        receiptFingerprint ==
            computeFingerprint(
                authority = authority,
                cutoverGeneration = cutoverGeneration,
                cutoverIntentFingerprint = cutoverIntentFingerprint,
                authorityFenceFingerprint = authorityFenceFingerprint,
            )

    companion object {
        fun create(fence: AuthorityCutoverFence): AuthorityCutoverCompletionReceipt =
            AuthorityCutoverCompletionReceipt(
                authority = fence.authority,
                cutoverGeneration = fence.cutoverGeneration,
                cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                authorityFenceFingerprint = fence.fenceFingerprint,
                receiptFingerprint =
                    computeFingerprint(
                        authority = fence.authority,
                        cutoverGeneration = fence.cutoverGeneration,
                        cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                        authorityFenceFingerprint = fence.fenceFingerprint,
                    ),
            )

        private fun computeFingerprint(
            authority: FencedAuthority,
            cutoverGeneration: Long,
            cutoverIntentFingerprint: String,
            authorityFenceFingerprint: String,
        ): String =
            CanonicalSha256("authority-cutover-completion-receipt-v1")
                .field("authority", authority.name)
                .field("cutoverGeneration", cutoverGeneration)
                .field("cutoverIntentFingerprint", cutoverIntentFingerprint)
                .field("authorityFenceFingerprint", authorityFenceFingerprint)
                .finish()
    }
}

/**
 * Generic append-only target control surface. It deliberately exposes neither database handles nor
 * update/delete/reset operations.
 */
internal interface AuthorityCutoverControlPort : AutoCloseable {
    suspend fun readCutoverFence(): AuthorityCutoverFence?

    suspend fun appendCutoverFenceIfAbsent(
        candidate: AuthorityCutoverFence,
    ): AuthorityCutoverFence

    suspend fun readCompletionReceipt(): AuthorityCutoverCompletionReceipt?

    suspend fun appendCompletionReceiptIfAbsent(
        candidate: AuthorityCutoverCompletionReceipt,
    ): AuthorityCutoverCompletionReceipt

    /**
     * Freshly proves that this target's immutable import exactly covers the current legacy source.
     *
     * A cached receipt, a target-only hash, or independently valid source/destination hashes without
     * a proven coverage relationship are insufficient.
     */
    suspend fun reverifyImmutableImportEvidence(): ImmutableAuthorityImportEvidence?

    override fun close() = Unit
}

internal interface StudentAuthorityCutoverControlPort : AuthorityCutoverControlPort

internal interface LearnerMasteryCutoverControlPort : AuthorityCutoverControlPort

internal interface KnowledgeActivationWitnessReader {
    /**
     * Re-reads activation metadata and the active knowledge database, returning null unless they
     * still form one verified current activation.
     */
    suspend fun readCurrentActivationWitness(): KnowledgeActivationWitness?
}

internal enum class LegacyWriteFenceState {
    UNFENCED,
    FENCED,
    CONFLICT,
}

/**
 * The independent pre-writer OR gate.
 *
 * [readLegacyWriteFenceState] is diagnostic and never authorizes a later writer open. Bootstrap
 * must use [runLegacyMigrationWriterIfUnfenced], which holds the process-wide lifecycle exclusion
 * for the writable handle's complete lifetime. A fence on either target permanently closes that
 * path. The application is single-process; a future multi-process runtime must wrap this same
 * control surface in an operating-system lock shared by all processes.
 */
internal class ThreeAuthorityLegacyWriteFenceControl private constructor(
    private val student: StudentAuthorityCutoverControlPort,
    private val mastery: LearnerMasteryCutoverControlPort,
) {
    /**
     * A point-in-time diagnostic read. Code that constructs a writable migration handle must use
     * [runLegacyMigrationWriterIfUnfenced] so the handle cannot race the first durable fence.
     */
    suspend fun readLegacyWriteFenceState(): LegacyWriteFenceState =
        withLifecycleExclusion {
            readLegacyWriteFenceStateWithoutLock()
        }

    /**
     * Runs one short-lived legacy migration writer only while both targets remain unfenced.
     *
     * The block must construct, use, and close its writable handle before returning. The terminal
     * coordinator shares this control instance and cannot append its first fence until the block
     * has finished.
     */
    suspend fun runLegacyMigrationWriterIfUnfenced(
        writer: suspend () -> Unit,
    ): LegacyWriteFenceState =
        withLifecycleExclusion {
            val state = readLegacyWriteFenceStateWithoutLock()
            if (state == LegacyWriteFenceState.UNFENCED) {
                writer()
            }
            state
        }

    internal suspend fun <T> runTerminalCutoverExclusively(
        terminalCutover: suspend (
            StudentAuthorityCutoverControlPort,
            LearnerMasteryCutoverControlPort,
            VerifiedThreeAuthorityFence.RecoveryPermit,
        ) -> T,
    ): T =
        withLifecycleExclusion {
            VerifiedThreeAuthorityFence.Owner.beginAuditedRecovery().use { recoveryPermit ->
                terminalCutover(student, mastery, recoveryPermit)
            }
        }

    private suspend fun readLegacyWriteFenceStateWithoutLock(): LegacyWriteFenceState {
        val studentState =
            LegacyGateAuthorityState(
                fence = student.readCutoverFence(),
                receipt = student.readCompletionReceipt(),
            )
        val masteryState =
            LegacyGateAuthorityState(
                fence = mastery.readCutoverFence(),
                receipt = mastery.readCompletionReceipt(),
            )
        if (!studentState.hasAnyArtifact && !masteryState.hasAnyArtifact) {
            return LegacyWriteFenceState.UNFENCED
        }
        if (
            !studentState.isValidFor(FencedAuthority.STUDENT_MISTAKES) ||
            !masteryState.isValidFor(FencedAuthority.LEARNER_MASTERY)
        ) {
            return LegacyWriteFenceState.CONFLICT
        }
        val studentFence = studentState.fence
        val masteryFence = masteryState.fence
        if (studentFence == null) {
            return LegacyWriteFenceState.FENCED
        }
        if (masteryFence == null) {
            return LegacyWriteFenceState.FENCED
        }
        return if (
            studentFence.cutoverGeneration == masteryFence.cutoverGeneration &&
            studentFence.cutoverIntentFingerprint == masteryFence.cutoverIntentFingerprint
        ) {
            LegacyWriteFenceState.FENCED
        } else {
            LegacyWriteFenceState.CONFLICT
        }
    }

    private suspend fun <T> withLifecycleExclusion(block: suspend () -> T): T {
        processLifecycleMutex.lock()
        return try {
            block()
        } finally {
            processLifecycleMutex.unlock()
        }
    }

    companion object {
        private val processLifecycleMutex = Mutex()

        fun create(
            student: StudentAuthorityCutoverControlPort,
            mastery: LearnerMasteryCutoverControlPort,
        ): ThreeAuthorityLegacyWriteFenceControl =
            ThreeAuthorityLegacyWriteFenceControl(
                student = student,
                mastery = mastery,
            )
    }
}

private data class LegacyGateAuthorityState(
    val fence: AuthorityCutoverFence?,
    val receipt: AuthorityCutoverCompletionReceipt?,
) {
    val hasAnyArtifact: Boolean
        get() = fence != null || receipt != null

    fun isValidFor(expectedAuthority: FencedAuthority): Boolean {
        if (fence == null) return receipt == null
        if (!fence.isValidFor(expectedAuthority)) return false
        return receipt == null ||
            (
                receipt.isValidFor(expectedAuthority) &&
                    receipt.cutoverGeneration == fence.cutoverGeneration &&
                    receipt.cutoverIntentFingerprint == fence.cutoverIntentFingerprint &&
                    receipt.authorityFenceFingerprint == fence.fenceFingerprint
            )
    }
}

internal class ThreeAuthorityCutoverIntegrityException(message: String) :
    IllegalStateException(message)

private suspend fun recoverOrVerifyThreeAuthorityFence(
    legacyWriteFenceControl: ThreeAuthorityLegacyWriteFenceControl,
    knowledge: KnowledgeActivationWitnessReader,
): VerifiedThreeAuthorityFence =
    legacyWriteFenceControl.runTerminalCutoverExclusively { student, mastery, recoveryPermit ->
        recoverThreeAuthorityFenceWithinLifecycleExclusion(
            student = student,
            mastery = mastery,
            knowledge = knowledge,
            recoveryPermit = recoveryPermit,
        )
    }

private suspend fun recoverThreeAuthorityFenceWithinLifecycleExclusion(
    student: StudentAuthorityCutoverControlPort,
    mastery: LearnerMasteryCutoverControlPort,
    knowledge: KnowledgeActivationWitnessReader,
    recoveryPermit: VerifiedThreeAuthorityFence.RecoveryPermit,
): VerifiedThreeAuthorityFence {
            // Knowledge has no append-only fence of its own. Verify its live activation before
            // writing either irreversible student/mastery fence so a missing or invalid package
            // cannot strand the installation in a partially cut-over state.
            val initialKnowledge = readVerifiedKnowledgeWitness(knowledge)
            val initialImports = readVerifiedImports(student, mastery)
            val expected =
                ExpectedTerminalState.from(
                    studentEvidence = initialImports.studentEvidence,
                    masteryEvidence = initialImports.masteryEvidence,
                )

            val initialStudentState =
                student.readState(FencedAuthority.STUDENT_MISTAKES)
            val initialMasteryState =
                mastery.readState(FencedAuthority.LEARNER_MASTERY)
            validateCrossAuthorityState(initialStudentState, initialMasteryState)
            initialStudentState.requireCompatibleWith(expected.studentFence)
            initialMasteryState.requireCompatibleWith(expected.masteryFence)

            val studentFence =
                student.ensureFence(initialStudentState.fence, expected.studentFence)
            val masteryFence =
                mastery.ensureFence(initialMasteryState.fence, expected.masteryFence)
            val studentReceipt =
                student.ensureReceipt(
                    initialStudentState.receipt,
                    AuthorityCutoverCompletionReceipt.create(studentFence),
                )
            val masteryReceipt =
                mastery.ensureReceipt(
                    initialMasteryState.receipt,
                    AuthorityCutoverCompletionReceipt.create(masteryFence),
                )

            val finalImports = readVerifiedImports(student, mastery)
            val finalKnowledge = readVerifiedKnowledgeWitness(knowledge)
            requireCutoverIntegrity(finalImports == initialImports) {
                "Immutable import evidence changed during cutover recovery"
            }
            requireCutoverIntegrity(finalKnowledge == initialKnowledge) {
                "Authority evidence or knowledge activation changed during cutover recovery"
            }

            val finalStudentState =
                student.readState(FencedAuthority.STUDENT_MISTAKES)
            val finalMasteryState =
                mastery.readState(FencedAuthority.LEARNER_MASTERY)
            validateCrossAuthorityState(finalStudentState, finalMasteryState)
            finalStudentState.requireExact(
                fence = studentFence,
                receipt = studentReceipt,
            )
            finalMasteryState.requireExact(
                fence = masteryFence,
                receipt = masteryReceipt,
            )

            return VerifiedThreeAuthorityFence.Owner.issueAfterAuditedRecovery(
                recoveryPermit,
                expected.cutoverGeneration,
                    computeGlobalProofFingerprint(
                        studentFence = studentFence,
                        masteryFence = masteryFence,
                        studentReceipt = studentReceipt,
                        masteryReceipt = masteryReceipt,
                        studentEvidence = finalImports.studentEvidence,
                        masteryEvidence = finalImports.masteryEvidence,
                        knowledgeWitness = finalKnowledge,
                    ),
                finalKnowledge,
            )
}

/**
 * Terminal coordination is intentionally independent of [AuthorityCutoverJournal]. The legacy
 * journal owns only its migration prefix and can never manufacture this proof.
 */
internal class ThreeAuthorityCutoverProofCoordinator(
    private val legacyWriteFenceControl: ThreeAuthorityLegacyWriteFenceControl,
    private val knowledge: KnowledgeActivationWitnessReader,
) {
    suspend fun recoverOrVerify(): VerifiedThreeAuthorityFence =
        recoverOrVerifyThreeAuthorityFence(
            legacyWriteFenceControl = legacyWriteFenceControl,
            knowledge = knowledge,
        )
}

private data class VerifiedImportInputs(
    val studentEvidence: ImmutableAuthorityImportEvidence,
    val masteryEvidence: ImmutableAuthorityImportEvidence,
)

private data class ExpectedTerminalState(
    val cutoverGeneration: Long,
    val studentFence: AuthorityCutoverFence,
    val masteryFence: AuthorityCutoverFence,
) {
    companion object {
        fun from(
            studentEvidence: ImmutableAuthorityImportEvidence,
            masteryEvidence: ImmutableAuthorityImportEvidence,
        ): ExpectedTerminalState {
            requireCutoverIntegrity(
                studentEvidence.cutoverGeneration == masteryEvidence.cutoverGeneration,
            ) {
                "Student and mastery immutable imports belong to different cutover generations"
            }
            val generation = studentEvidence.cutoverGeneration
            return ExpectedTerminalState(
                cutoverGeneration = generation,
                studentFence =
                    AuthorityCutoverFence.create(
                        authority = FencedAuthority.STUDENT_MISTAKES,
                        cutoverGeneration = generation,
                        studentImportEvidenceFingerprint =
                            studentEvidence.evidenceFingerprint,
                        masteryImportEvidenceFingerprint =
                            masteryEvidence.evidenceFingerprint,
                    ),
                masteryFence =
                    AuthorityCutoverFence.create(
                        authority = FencedAuthority.LEARNER_MASTERY,
                        cutoverGeneration = generation,
                        studentImportEvidenceFingerprint =
                            studentEvidence.evidenceFingerprint,
                        masteryImportEvidenceFingerprint =
                            masteryEvidence.evidenceFingerprint,
                    ),
            )
        }
    }
}

private data class AuthorityControlState(
    val fence: AuthorityCutoverFence?,
    val receipt: AuthorityCutoverCompletionReceipt?,
) {
    fun requireCompatibleWith(expectedFence: AuthorityCutoverFence) {
        fence?.let { persisted ->
            requireCutoverIntegrity(persisted == expectedFence) {
                "${expectedFence.authority.name} has a conflicting append-only cutover fence"
            }
        }
        receipt?.let { persisted ->
            val expectedReceipt = AuthorityCutoverCompletionReceipt.create(expectedFence)
            requireCutoverIntegrity(persisted == expectedReceipt) {
                "${expectedFence.authority.name} has a conflicting completion receipt"
            }
        }
    }

    fun requireExact(
        fence: AuthorityCutoverFence,
        receipt: AuthorityCutoverCompletionReceipt,
    ) {
        requireCutoverIntegrity(this.fence == fence) {
            "${fence.authority.name} cutover fence is missing or changed after append"
        }
        requireCutoverIntegrity(this.receipt == receipt) {
            "${fence.authority.name} completion receipt is missing or changed after append"
        }
    }
}

private suspend fun readVerifiedImports(
    student: StudentAuthorityCutoverControlPort,
    mastery: LearnerMasteryCutoverControlPort,
): VerifiedImportInputs {
    val studentEvidence =
        requireNotNullCutover(student.reverifyImmutableImportEvidence()) {
            "Student immutable import evidence is missing"
        }
    val masteryEvidence =
        requireNotNullCutover(mastery.reverifyImmutableImportEvidence()) {
            "Mastery immutable import evidence is missing"
        }

    requireCutoverIntegrity(
        studentEvidence.authority == FencedAuthority.STUDENT_MISTAKES,
    ) {
        "Student control port returned evidence for another authority"
    }
    requireCutoverIntegrity(studentEvidence.hasValidFingerprint()) {
        "Student immutable import evidence is tampered"
    }
    requireCutoverIntegrity(
        masteryEvidence.authority == FencedAuthority.LEARNER_MASTERY,
    ) {
        "Mastery control port returned evidence for another authority"
    }
    requireCutoverIntegrity(masteryEvidence.hasValidFingerprint()) {
        "Mastery immutable import evidence is tampered"
    }

    return VerifiedImportInputs(
        studentEvidence = studentEvidence,
        masteryEvidence = masteryEvidence,
    )
}

private suspend fun readVerifiedKnowledgeWitness(
    knowledge: KnowledgeActivationWitnessReader,
): KnowledgeActivationWitness {
    val witness =
        requireNotNullCutover(knowledge.readCurrentActivationWitness()) {
            "Current knowledge activation witness is missing"
        }
    requireCutoverIntegrity(witness.hasValidFingerprint()) {
        "Current knowledge activation witness is tampered"
    }
    return witness
}

private suspend fun AuthorityCutoverControlPort.readState(
    expectedAuthority: FencedAuthority,
): AuthorityControlState {
    val fence = readCutoverFence()
    val receipt = readCompletionReceipt()
    fence?.let {
        requireCutoverIntegrity(it.isValidFor(expectedAuthority)) {
            "$expectedAuthority cutover fence is invalid or tampered"
        }
    }
    receipt?.let {
        requireCutoverIntegrity(it.isValidFor(expectedAuthority)) {
            "$expectedAuthority completion receipt is invalid or tampered"
        }
        val boundFence =
            requireNotNullCutover(fence) {
                "$expectedAuthority has a completion receipt without its cutover fence"
            }
        requireCutoverIntegrity(
            it.cutoverGeneration == boundFence.cutoverGeneration &&
                it.cutoverIntentFingerprint == boundFence.cutoverIntentFingerprint &&
                it.authorityFenceFingerprint == boundFence.fenceFingerprint,
        ) {
            "$expectedAuthority completion receipt does not bind its durable fence"
        }
    }
    return AuthorityControlState(fence = fence, receipt = receipt)
}

private suspend fun AuthorityCutoverControlPort.ensureFence(
    existing: AuthorityCutoverFence?,
    candidate: AuthorityCutoverFence,
): AuthorityCutoverFence {
    if (existing != null) return existing
    val durable = appendCutoverFenceIfAbsent(candidate)
    requireCutoverIntegrity(durable == candidate) {
        "${candidate.authority.name} returned a conflicting append-only cutover fence"
    }
    requireCutoverIntegrity(readCutoverFence() == candidate) {
        "${candidate.authority.name} cutover fence was not durable after append"
    }
    return durable
}

private suspend fun AuthorityCutoverControlPort.ensureReceipt(
    existing: AuthorityCutoverCompletionReceipt?,
    candidate: AuthorityCutoverCompletionReceipt,
): AuthorityCutoverCompletionReceipt {
    if (existing != null) return existing
    val durable = appendCompletionReceiptIfAbsent(candidate)
    requireCutoverIntegrity(durable == candidate) {
        "${candidate.authority.name} returned a conflicting completion receipt"
    }
    requireCutoverIntegrity(readCompletionReceipt() == candidate) {
        "${candidate.authority.name} completion receipt was not durable after append"
    }
    return durable
}

private fun validateCrossAuthorityState(
    student: AuthorityControlState,
    mastery: AuthorityControlState,
) {
    val studentFence = student.fence
    val masteryFence = mastery.fence
    if (studentFence != null && masteryFence != null) {
        requireCutoverIntegrity(
            studentFence.cutoverGeneration == masteryFence.cutoverGeneration &&
                studentFence.cutoverIntentFingerprint ==
                masteryFence.cutoverIntentFingerprint,
        ) {
            "Student and mastery cutover fences conflict"
        }
    }

    val studentReceipt = student.receipt
    val masteryReceipt = mastery.receipt
    if (studentReceipt != null && masteryReceipt != null) {
        requireCutoverIntegrity(
            studentReceipt.cutoverGeneration == masteryReceipt.cutoverGeneration &&
                studentReceipt.cutoverIntentFingerprint ==
                masteryReceipt.cutoverIntentFingerprint,
        ) {
            "Student and mastery completion receipts do not match"
        }
    }
}

private fun AuthorityCutoverFence.isValidFor(
    expectedAuthority: FencedAuthority,
): Boolean = authority == expectedAuthority && hasValidFingerprint()

private fun AuthorityCutoverCompletionReceipt.isValidFor(
    expectedAuthority: FencedAuthority,
): Boolean = authority == expectedAuthority && hasValidFingerprint()

private fun computeGlobalProofFingerprint(
    studentFence: AuthorityCutoverFence,
    masteryFence: AuthorityCutoverFence,
    studentReceipt: AuthorityCutoverCompletionReceipt,
    masteryReceipt: AuthorityCutoverCompletionReceipt,
    studentEvidence: ImmutableAuthorityImportEvidence,
    masteryEvidence: ImmutableAuthorityImportEvidence,
    knowledgeWitness: KnowledgeActivationWitness,
): String =
    CanonicalSha256("verified-three-authority-fence-v1")
        .field("cutoverGeneration", studentFence.cutoverGeneration)
        .field("studentDatabaseName", "student-mistakes.db")
        .field("masteryDatabaseName", "learner-mastery.db")
        .field("knowledgeDatabaseName", "high-school-knowledge.db")
        .field("studentFenceFingerprint", studentFence.fenceFingerprint)
        .field("masteryFenceFingerprint", masteryFence.fenceFingerprint)
        .field("studentReceiptFingerprint", studentReceipt.receiptFingerprint)
        .field("masteryReceiptFingerprint", masteryReceipt.receiptFingerprint)
        .field(
            "studentImportEvidenceFingerprint",
            studentEvidence.evidenceFingerprint,
        )
        .field(
            "masteryImportEvidenceFingerprint",
            masteryEvidence.evidenceFingerprint,
        )
        .field(
            "knowledgeActivationWitnessFingerprint",
            knowledgeWitness.witnessFingerprint,
        )
        .finish()

private inline fun requireCutoverIntegrity(
    condition: Boolean,
    lazyMessage: () -> String,
) {
    if (!condition) {
        throw ThreeAuthorityCutoverIntegrityException(lazyMessage())
    }
}

private inline fun <T : Any> requireNotNullCutover(
    value: T?,
    lazyMessage: () -> String,
): T =
    value ?: throw ThreeAuthorityCutoverIntegrityException(lazyMessage())

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

private fun String.isValidCutoverText(maxLength: Int = 256): Boolean =
    isNotBlank() &&
        length <= maxLength &&
        none { it.isISOControl() }
