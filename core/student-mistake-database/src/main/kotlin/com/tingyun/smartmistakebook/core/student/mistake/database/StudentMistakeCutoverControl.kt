package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.io.Closeable

/**
 * The student authority's durable terminal fence.
 *
 * This mirrors the canonical wire contract owned by core:data without depending on core:data.
 * There is no timestamp because recovery must propose the same bytes.
 */
data class StudentMistakeAuthorityCutoverFence(
    val cutoverGeneration: Long,
    val studentImportEvidenceFingerprint: String,
    val masteryImportEvidenceFingerprint: String,
    val cutoverIntentFingerprint: String,
    val fenceFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L) { "Cutover generation must be positive" }
        requireSha256(
            studentImportEvidenceFingerprint,
            "Student import evidence fingerprint",
        )
        requireSha256(
            masteryImportEvidenceFingerprint,
            "Mastery import evidence fingerprint",
        )
        requireSha256(cutoverIntentFingerprint, "Cutover intent fingerprint")
        requireSha256(fenceFingerprint, "Student cutover fence fingerprint")
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
        ): StudentMistakeAuthorityCutoverFence {
            val intentFingerprint =
                computeIntentFingerprint(
                    cutoverGeneration = cutoverGeneration,
                    studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
                    masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
                )
            return StudentMistakeAuthorityCutoverFence(
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
                .field("authority", STUDENT_MISTAKE_CUTOVER_AUTHORITY)
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

/** Deterministic terminal receipt bound to the student authority's durable fence. */
data class StudentMistakeAuthorityCutoverCompletionReceipt(
    val cutoverGeneration: Long,
    val cutoverIntentFingerprint: String,
    val authorityFenceFingerprint: String,
    val receiptFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L) { "Cutover generation must be positive" }
        requireSha256(cutoverIntentFingerprint, "Cutover intent fingerprint")
        requireSha256(authorityFenceFingerprint, "Authority fence fingerprint")
        requireSha256(receiptFingerprint, "Student cutover receipt fingerprint")
    }

    fun hasValidFingerprint(): Boolean =
        receiptFingerprint ==
            computeFingerprint(
                cutoverGeneration = cutoverGeneration,
                cutoverIntentFingerprint = cutoverIntentFingerprint,
                authorityFenceFingerprint = authorityFenceFingerprint,
            )

    companion object {
        fun create(
            fence: StudentMistakeAuthorityCutoverFence,
        ): StudentMistakeAuthorityCutoverCompletionReceipt =
            StudentMistakeAuthorityCutoverCompletionReceipt(
                cutoverGeneration = fence.cutoverGeneration,
                cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                authorityFenceFingerprint = fence.fenceFingerprint,
                receiptFingerprint =
                    computeFingerprint(
                        cutoverGeneration = fence.cutoverGeneration,
                        cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                        authorityFenceFingerprint = fence.fenceFingerprint,
                    ),
            )

        private fun computeFingerprint(
            cutoverGeneration: Long,
            cutoverIntentFingerprint: String,
            authorityFenceFingerprint: String,
        ): String =
            CanonicalSha256(AUTHORITY_CUTOVER_RECEIPT_FINGERPRINT_DOMAIN)
                .field("authority", STUDENT_MISTAKE_CUTOVER_AUTHORITY)
                .field("cutoverGeneration", cutoverGeneration)
                .field("cutoverIntentFingerprint", cutoverIntentFingerprint)
                .field("authorityFenceFingerprint", authorityFenceFingerprint)
                .finish()
    }
}

/**
 * A freshly verified digest of one completed, immutable target migration ledger.
 *
 * [destinationCanonicalFingerprint] is recomputed from the immutable imported business rows on
 * every read, then checked against their append-only destination ledger and page-receipt chain. It
 * is never loaded from a cached terminal receipt.
 */
data class StudentMistakeImmutableMigrationLedgerDigest(
    val migrationId: String,
    val sourceDatabaseCanonicalFingerprint: String,
    val migratedRecordCount: Long,
    val checkpointCanonicalFingerprint: String,
    val terminalSourcePageCanonicalFingerprint: String,
    val pageReceiptCount: Int,
    val destinationCanonicalFingerprint: String,
    val destinationLedgerVersion: Int = 0,
    val immutableImportSnapshotCount: Long = 0,
    val legacySemanticSnapshotCount: Long = 0,
) {
    init {
        migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
        requireSha256(
            sourceDatabaseCanonicalFingerprint,
            "Migration source database fingerprint",
        )
        require(migratedRecordCount >= 0L) {
            "Migrated record count must not be negative"
        }
        requireSha256(checkpointCanonicalFingerprint, "Migration checkpoint fingerprint")
        requireSha256(
            terminalSourcePageCanonicalFingerprint,
            "Terminal source-page fingerprint",
        )
        require(pageReceiptCount > 0) {
            "Completed migration ledger must contain a terminal page receipt"
        }
        requireSha256(
            destinationCanonicalFingerprint,
            "Migration destination fingerprint",
        )
        require(destinationLedgerVersion >= 0) {
            "Migration destination-ledger version must not be negative"
        }
        require(immutableImportSnapshotCount >= 0L) {
            "Migration import-snapshot count must not be negative"
        }
        require(legacySemanticSnapshotCount in 0..immutableImportSnapshotCount) {
            "Migration legacy-semantic snapshot count is invalid"
        }
        require(
            destinationLedgerVersion < STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION ||
                immutableImportSnapshotCount == migratedRecordCount,
        ) {
            "Exact migration ledger must cover every imported revision"
        }
    }
}

/** Exact legacy row that must be independently checked before terminal cutover may continue. */
data class StudentMistakeDestinationReattestationChallenge(
    val migrationId: String,
    val revisionId: String,
    val legacyDestinationRecordCanonicalFingerprint: String,
    val replacementDestinationRecordCanonicalFingerprint: String,
    val canonicalPolicyVersion: Int,
) {
    init {
        migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
        revisionId.requireStoreText("Revision id", MAX_ID_CHARS)
        requireSha256(
            legacyDestinationRecordCanonicalFingerprint,
            "Legacy migration destination-record fingerprint",
        )
        requireSha256(
            replacementDestinationRecordCanonicalFingerprint,
            "Replacement migration destination-record fingerprint",
        )
        require(canonicalPolicyVersion == STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION) {
            "Destination reattestation must use the current canonical policy"
        }
    }
}

/** Append-only owner proof for one independently reverified legacy destination record. */
data class StudentMistakeDestinationReattestationReceipt(
    val migrationId: String,
    val revisionId: String,
    val legacyDestinationRecordCanonicalFingerprint: String,
    val replacementDestinationRecordCanonicalFingerprint: String,
    val canonicalPolicyVersion: Int,
    val issuerKeyId: String,
    val issuerVersion: String,
    val issuedAtEpochMillis: Long,
    val receiptCanonicalFingerprint: String,
) {
    init {
        migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
        revisionId.requireStoreText("Revision id", MAX_ID_CHARS)
        requireSha256(
            legacyDestinationRecordCanonicalFingerprint,
            "Legacy migration destination-record fingerprint",
        )
        requireSha256(
            replacementDestinationRecordCanonicalFingerprint,
            "Replacement migration destination-record fingerprint",
        )
        require(canonicalPolicyVersion == STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION) {
            "Destination reattestation must use the current canonical policy"
        }
        issuerKeyId.requireStoreText("Destination reattestation issuer key id", MAX_ID_CHARS)
        issuerVersion.requireStoreText("Destination reattestation issuer version", MAX_ID_CHARS)
        require(issuedAtEpochMillis >= 0L) {
            "Destination reattestation issue time must not be negative"
        }
        requireSha256(receiptCanonicalFingerprint, "Destination reattestation receipt fingerprint")
    }

    fun hasValidFingerprint(): Boolean =
        receiptCanonicalFingerprint ==
            computeFingerprint(
                migrationId = migrationId,
                revisionId = revisionId,
                legacyDestinationRecordCanonicalFingerprint =
                    legacyDestinationRecordCanonicalFingerprint,
                replacementDestinationRecordCanonicalFingerprint =
                    replacementDestinationRecordCanonicalFingerprint,
                canonicalPolicyVersion = canonicalPolicyVersion,
                issuerKeyId = issuerKeyId,
                issuerVersion = issuerVersion,
                issuedAtEpochMillis = issuedAtEpochMillis,
            )

    fun matches(challenge: StudentMistakeDestinationReattestationChallenge): Boolean =
        migrationId == challenge.migrationId &&
            revisionId == challenge.revisionId &&
            legacyDestinationRecordCanonicalFingerprint ==
            challenge.legacyDestinationRecordCanonicalFingerprint &&
            replacementDestinationRecordCanonicalFingerprint ==
            challenge.replacementDestinationRecordCanonicalFingerprint &&
            canonicalPolicyVersion == challenge.canonicalPolicyVersion

    companion object {
        fun create(
            challenge: StudentMistakeDestinationReattestationChallenge,
            issuerKeyId: String,
            issuerVersion: String,
            issuedAtEpochMillis: Long,
        ): StudentMistakeDestinationReattestationReceipt =
            StudentMistakeDestinationReattestationReceipt(
                migrationId = challenge.migrationId,
                revisionId = challenge.revisionId,
                legacyDestinationRecordCanonicalFingerprint =
                    challenge.legacyDestinationRecordCanonicalFingerprint,
                replacementDestinationRecordCanonicalFingerprint =
                    challenge.replacementDestinationRecordCanonicalFingerprint,
                canonicalPolicyVersion = challenge.canonicalPolicyVersion,
                issuerKeyId = issuerKeyId,
                issuerVersion = issuerVersion,
                issuedAtEpochMillis = issuedAtEpochMillis,
                receiptCanonicalFingerprint =
                    computeFingerprint(
                        migrationId = challenge.migrationId,
                        revisionId = challenge.revisionId,
                        legacyDestinationRecordCanonicalFingerprint =
                            challenge.legacyDestinationRecordCanonicalFingerprint,
                        replacementDestinationRecordCanonicalFingerprint =
                            challenge.replacementDestinationRecordCanonicalFingerprint,
                        canonicalPolicyVersion = challenge.canonicalPolicyVersion,
                        issuerKeyId = issuerKeyId,
                        issuerVersion = issuerVersion,
                        issuedAtEpochMillis = issuedAtEpochMillis,
                    ),
            )

        private fun computeFingerprint(
            migrationId: String,
            revisionId: String,
            legacyDestinationRecordCanonicalFingerprint: String,
            replacementDestinationRecordCanonicalFingerprint: String,
            canonicalPolicyVersion: Int,
            issuerKeyId: String,
            issuerVersion: String,
            issuedAtEpochMillis: Long,
        ): String =
            CanonicalSha256(DESTINATION_REATTESTATION_RECEIPT_FINGERPRINT_DOMAIN)
                .field("migrationId", migrationId)
                .field("revisionId", revisionId)
                .field(
                    "legacyDestinationRecordCanonicalFingerprint",
                    legacyDestinationRecordCanonicalFingerprint,
                )
                .field(
                    "replacementDestinationRecordCanonicalFingerprint",
                    replacementDestinationRecordCanonicalFingerprint,
                )
                .field("canonicalPolicyVersion", canonicalPolicyVersion)
                .field("issuerKeyId", issuerKeyId)
                .field("issuerVersion", issuerVersion)
                .field("issuedAtEpochMillis", issuedAtEpochMillis)
                .finish()
    }
}

/**
 * Owner-only student cutover control surface.
 *
 * The factory that creates this port requires the package-private core:data owner key. The API
 * deliberately has no update, delete, reset, database-handle, or legacy-source operation.
 */
interface StudentMistakeCutoverControlPort : Closeable {
    suspend fun readCutoverFence(): StudentMistakeAuthorityCutoverFence?

    suspend fun appendCutoverFenceIfAbsent(
        candidate: StudentMistakeAuthorityCutoverFence,
    ): StudentMistakeAuthorityCutoverFence

    suspend fun readCompletionReceipt(): StudentMistakeAuthorityCutoverCompletionReceipt?

    suspend fun appendCompletionReceiptIfAbsent(
        candidate: StudentMistakeAuthorityCutoverCompletionReceipt,
    ): StudentMistakeAuthorityCutoverCompletionReceipt

    suspend fun readPendingDestinationReattestations(
        migrationId: String,
    ): List<StudentMistakeDestinationReattestationChallenge> = emptyList()

    suspend fun appendDestinationReattestationReceipt(
        candidate: StudentMistakeDestinationReattestationReceipt,
    ): StudentMistakeDestinationReattestationReceipt =
        throw UnsupportedOperationException("Destination reattestation is not supported")

    suspend fun recomputeCompletedMigrationLedger(
        migrationId: String,
    ): StudentMistakeImmutableMigrationLedgerDigest?
}

internal const val STUDENT_MISTAKE_CUTOVER_AUTHORITY = "STUDENT_MISTAKES"
private const val CUTOVER_INTENT_FINGERPRINT_DOMAIN = "three-authority-cutover-intent-v1"
private const val AUTHORITY_CUTOVER_FENCE_FINGERPRINT_DOMAIN = "authority-cutover-fence-v1"
private const val AUTHORITY_CUTOVER_RECEIPT_FINGERPRINT_DOMAIN =
    "authority-cutover-completion-receipt-v1"
private const val DESTINATION_REATTESTATION_RECEIPT_FINGERPRINT_DOMAIN =
    "student-mistake-destination-reattestation-receipt-v1"
