package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.io.Closeable

/**
 * The learner-mastery authority's durable terminal fence.
 *
 * The wire-compatible fields mirror the coordinator contract without depending on core:data.
 * Timestamps are intentionally absent so recovery always proposes the same bytes.
 */
data class LearnerMasteryAuthorityCutoverFence(
    val cutoverGeneration: Long,
    val studentImportEvidenceFingerprint: String,
    val masteryImportEvidenceFingerprint: String,
    val cutoverIntentFingerprint: String,
    val fenceFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L) { "Cutover generation must be positive" }
        requireMasteryFingerprint(
            studentImportEvidenceFingerprint,
            "Student import evidence fingerprint",
        )
        requireMasteryFingerprint(
            masteryImportEvidenceFingerprint,
            "Mastery import evidence fingerprint",
        )
        requireMasteryFingerprint(cutoverIntentFingerprint, "Cutover intent fingerprint")
        requireMasteryFingerprint(fenceFingerprint, "Mastery cutover fence fingerprint")
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
                cutoverGeneration = cutoverGeneration,
                studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
                cutoverIntentFingerprint = cutoverIntentFingerprint,
            )

    companion object {
        fun create(
            cutoverGeneration: Long,
            studentImportEvidenceFingerprint: String,
            masteryImportEvidenceFingerprint: String,
        ): LearnerMasteryAuthorityCutoverFence {
            val intentFingerprint =
                computeIntentFingerprint(
                    cutoverGeneration = cutoverGeneration,
                    studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                    masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
                )
            return LearnerMasteryAuthorityCutoverFence(
                cutoverGeneration = cutoverGeneration,
                studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
                cutoverIntentFingerprint = intentFingerprint,
                fenceFingerprint =
                    computeFenceFingerprint(
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
            CanonicalSha256(CUTOVER_INTENT_FINGERPRINT_DOMAIN)
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
            cutoverGeneration: Long,
            studentImportEvidenceFingerprint: String,
            masteryImportEvidenceFingerprint: String,
            cutoverIntentFingerprint: String,
        ): String =
            CanonicalSha256(AUTHORITY_CUTOVER_FENCE_FINGERPRINT_DOMAIN)
                .field("authority", LEARNER_MASTERY_CUTOVER_AUTHORITY)
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

/**
 * A freshly recomputed digest of the device's only legacy learner migration ledger.
 *
 * [destinationCanonicalFingerprint] is derived from every physical checkpoint row, not from
 * projection or subject-digest caches.
 */
data class LearnerMasteryImmutableMigrationLedgerDigest(
    val learnerId: String,
    val sourceGeneration: String,
    val migratedObservationCount: Long,
    val terminalBatchSequence: Long,
    val terminalBatchFingerprint: String,
    val batchReceiptCount: Int,
    val destinationCanonicalFingerprint: String,
    val destinationLedgerVersion: Int = 1,
    val destinationCanonicalLayoutVersion: Int =
        if (destinationLedgerVersion >= RAW_SNAPSHOT_DESTINATION_LEDGER_VERSION) {
            RAW_SNAPSHOT_DESTINATION_CANONICAL_LAYOUT_VERSION
        } else {
            1
        },
    val rawSnapshotCount: Long = 0L,
    val terminalSourcePageCanonicalFingerprint: String? = null,
    val terminalCursor: LearnerMasteryLegacyMigrationCursor? = null,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
        requireMasteryVersion(sourceGeneration, "Legacy source generation")
        require(migratedObservationCount >= 0L) {
            "Migrated observation count must not be negative"
        }
        require(terminalBatchSequence > 0L) {
            "Terminal migration batch sequence must be positive"
        }
        requireMasteryFingerprint(
            terminalBatchFingerprint,
            "Terminal migration batch fingerprint",
        )
        require(batchReceiptCount > 0) {
            "Completed migration ledger must contain a terminal batch receipt"
        }
        requireMasteryFingerprint(
            destinationCanonicalFingerprint,
            "Migration ledger canonical digest",
        )
        require(destinationLedgerVersion > 0) {
            "Migration destination ledger version must be positive"
        }
        require(destinationCanonicalLayoutVersion > 0) {
            "Migration destination canonical layout version must be positive"
        }
        require(rawSnapshotCount >= 0L) {
            "Raw legacy snapshot count must not be negative"
        }
        terminalSourcePageCanonicalFingerprint?.let { fingerprint ->
            requireMasteryFingerprint(
                fingerprint,
                "Terminal legacy source-page fingerprint",
            )
        }
        if (destinationLedgerVersion >= RAW_SNAPSHOT_DESTINATION_LEDGER_VERSION) {
            require(rawSnapshotCount == migratedObservationCount) {
                "Raw snapshot ledger count must equal the migrated observation count"
            }
            require(terminalSourcePageCanonicalFingerprint != null) {
                "Raw snapshot ledger requires its terminal source-page fingerprint"
            }
            require((rawSnapshotCount == 0L) == (terminalCursor == null)) {
                "Raw snapshot ledger cursor does not match its record count"
            }
        }
    }
}

/**
 * Deterministic terminal receipt bound to both the durable fence and the physical migration ledger.
 *
 * [receiptFingerprint] retains the cross-authority v1 wire contract. The additional
 * [ledgerBindingFingerprint] prevents that receipt from being detached from the freshly
 * recomputable mastery ledger named by [learnerId] and [sourceGeneration]. This version's global
 * singleton is safe because its owner capability accepts only the product's fixed local learner;
 * supporting multiple learner profiles requires a future authority-wide learner-set manifest.
 */
data class LearnerMasteryAuthorityCutoverCompletionReceipt(
    val cutoverGeneration: Long,
    val cutoverIntentFingerprint: String,
    val authorityFenceFingerprint: String,
    val learnerId: String,
    val sourceGeneration: String,
    val migrationLedgerCanonicalDigest: String,
    val ledgerBindingFingerprint: String,
    val receiptFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L) { "Cutover generation must be positive" }
        requireMasteryFingerprint(cutoverIntentFingerprint, "Cutover intent fingerprint")
        requireMasteryFingerprint(authorityFenceFingerprint, "Authority fence fingerprint")
        requireMasteryIdentity(learnerId, "Learner id")
        requireMasteryVersion(sourceGeneration, "Legacy source generation")
        requireMasteryFingerprint(
            migrationLedgerCanonicalDigest,
            "Migration ledger canonical digest",
        )
        requireMasteryFingerprint(
            ledgerBindingFingerprint,
            "Mastery cutover ledger-binding fingerprint",
        )
        requireMasteryFingerprint(receiptFingerprint, "Mastery cutover receipt fingerprint")
    }

    fun hasValidFingerprint(): Boolean =
        receiptFingerprint ==
            computeReceiptFingerprint(
                cutoverGeneration = cutoverGeneration,
                cutoverIntentFingerprint = cutoverIntentFingerprint,
                authorityFenceFingerprint = authorityFenceFingerprint,
            ) &&
            ledgerBindingFingerprint ==
            computeLedgerBindingFingerprint(
                cutoverGeneration = cutoverGeneration,
                cutoverIntentFingerprint = cutoverIntentFingerprint,
                authorityFenceFingerprint = authorityFenceFingerprint,
                learnerId = learnerId,
                sourceGeneration = sourceGeneration,
                migrationLedgerCanonicalDigest = migrationLedgerCanonicalDigest,
                receiptFingerprint = receiptFingerprint,
            )

    companion object {
        fun create(
            fence: LearnerMasteryAuthorityCutoverFence,
            ledger: LearnerMasteryImmutableMigrationLedgerDigest,
        ): LearnerMasteryAuthorityCutoverCompletionReceipt {
            val receiptFingerprint =
                computeReceiptFingerprint(
                    cutoverGeneration = fence.cutoverGeneration,
                    cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                    authorityFenceFingerprint = fence.fenceFingerprint,
                )
            return LearnerMasteryAuthorityCutoverCompletionReceipt(
                cutoverGeneration = fence.cutoverGeneration,
                cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                authorityFenceFingerprint = fence.fenceFingerprint,
                learnerId = ledger.learnerId,
                sourceGeneration = ledger.sourceGeneration,
                migrationLedgerCanonicalDigest = ledger.destinationCanonicalFingerprint,
                ledgerBindingFingerprint =
                    computeLedgerBindingFingerprint(
                        cutoverGeneration = fence.cutoverGeneration,
                        cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                        authorityFenceFingerprint = fence.fenceFingerprint,
                        learnerId = ledger.learnerId,
                        sourceGeneration = ledger.sourceGeneration,
                        migrationLedgerCanonicalDigest =
                            ledger.destinationCanonicalFingerprint,
                        receiptFingerprint = receiptFingerprint,
                    ),
                receiptFingerprint = receiptFingerprint,
            )
        }

        private fun computeReceiptFingerprint(
            cutoverGeneration: Long,
            cutoverIntentFingerprint: String,
            authorityFenceFingerprint: String,
        ): String =
            CanonicalSha256(AUTHORITY_CUTOVER_RECEIPT_FINGERPRINT_DOMAIN)
                .field("authority", LEARNER_MASTERY_CUTOVER_AUTHORITY)
                .field("cutoverGeneration", cutoverGeneration)
                .field("cutoverIntentFingerprint", cutoverIntentFingerprint)
                .field("authorityFenceFingerprint", authorityFenceFingerprint)
                .finish()

        private fun computeLedgerBindingFingerprint(
            cutoverGeneration: Long,
            cutoverIntentFingerprint: String,
            authorityFenceFingerprint: String,
            learnerId: String,
            sourceGeneration: String,
            migrationLedgerCanonicalDigest: String,
            receiptFingerprint: String,
        ): String =
            CanonicalSha256(LEDGER_BINDING_FINGERPRINT_DOMAIN)
                .field("authority", LEARNER_MASTERY_CUTOVER_AUTHORITY)
                .field("cutoverGeneration", cutoverGeneration)
                .field("cutoverIntentFingerprint", cutoverIntentFingerprint)
                .field("authorityFenceFingerprint", authorityFenceFingerprint)
                .field("learnerId", learnerId)
                .field("sourceGeneration", sourceGeneration)
                .field(
                    "migrationLedgerCanonicalDigest",
                    migrationLedgerCanonicalDigest,
                )
                .field("receiptFingerprint", receiptFingerprint)
                .finish()
    }
}

/**
 * Owner-only terminal-cutover surface for the device's fixed local learner.
 *
 * The factory requires the package-private core:data owner key. The port deliberately exposes no
 * database handle and no update, delete, reset, or legacy-source operation.
 */
interface LearnerMasteryCutoverControlPort : Closeable {
    suspend fun readCutoverFence(): LearnerMasteryAuthorityCutoverFence?

    suspend fun appendCutoverFenceIfAbsent(
        candidate: LearnerMasteryAuthorityCutoverFence,
    ): LearnerMasteryAuthorityCutoverFence

    suspend fun readCompletionReceipt(): LearnerMasteryAuthorityCutoverCompletionReceipt?

    suspend fun appendCompletionReceiptIfAbsent(
        candidate: LearnerMasteryAuthorityCutoverCompletionReceipt,
    ): LearnerMasteryAuthorityCutoverCompletionReceipt

    suspend fun recomputeCompletedMigrationLedger(
        sourceGeneration: String,
    ): LearnerMasteryImmutableMigrationLedgerDigest?
}

internal const val LEARNER_MASTERY_CUTOVER_AUTHORITY = "LEARNER_MASTERY"
private const val CUTOVER_INTENT_FINGERPRINT_DOMAIN = "three-authority-cutover-intent-v1"
private const val AUTHORITY_CUTOVER_FENCE_FINGERPRINT_DOMAIN = "authority-cutover-fence-v1"
private const val AUTHORITY_CUTOVER_RECEIPT_FINGERPRINT_DOMAIN =
    "authority-cutover-completion-receipt-v1"
private const val LEDGER_BINDING_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-ledger-binding-v1"
internal const val RAW_SNAPSHOT_DESTINATION_LEDGER_VERSION = 2
internal const val RAW_SNAPSHOT_DESTINATION_CANONICAL_LAYOUT_VERSION = 2
