package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.student.mistake.database.StudentAuthorityVerifiedAttestation
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverAttestationProgress
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationAttestation
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationAttestationPorts
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationBinding
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverDestinationStage
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCutoverVerificationCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentDocumentImportReceiptReference
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentIndexesRebuiltAttestation
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOutboxReconciledAttestation
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The student-owner capability consumed by the terminal writer.
 *
 * It exposes only journal-safe snapshots copied directly from owner-issued attestations. It never
 * exposes Room, a DAO, or a way for the writer to manufacture a successful student stage.
 */
internal interface StudentTerminalAuthorityAttestationOwner : Closeable {
    fun bindDocumentImportReceipt(
        binding: TerminalAuthorityCutoverBinding,
        receipt: AuthorityStageReceipt,
    )

    suspend fun attestOutbox(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot

    suspend fun attestIndexes(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot

    suspend fun attestAuthority(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot
}

internal fun interface StudentDocumentImportReceiptReferenceBinder {
    fun bind(
        cutoverGeneration: Long,
        legacyPrefixFingerprint: String,
        migratedRecordCount: Long,
        sourceCheckpoint: String,
        destinationFingerprint: String,
        receiptFingerprint: String,
    ): StudentDocumentImportReceiptReference
}

/**
 * Production adapter for stages 6-8.
 *
 * Cursors are intentionally process-local. A scan resumes page by page while this process is
 * alive. If the process dies before the journal append, no partial success exists and the next
 * process starts the bounded scan again from a null cursor.
 */
internal class ProductionStudentTerminalAuthorityAttestationOwner(
    private val layout: ThreeAuthorityDatabaseLayout,
    private val ports: StudentCutoverDestinationAttestationPorts,
    private val receiptBinder: StudentDocumentImportReceiptReferenceBinder,
    private val clock: () -> Long,
) : StudentTerminalAuthorityAttestationOwner {
    private var documentImport: BoundDocumentImport? = null
    private var outboxAttestation: StudentOutboxReconciledAttestation? = null
    private var indexesAttestation: StudentIndexesRebuiltAttestation? = null
    private var closed = false

    override fun bindDocumentImportReceipt(
        binding: TerminalAuthorityCutoverBinding,
        receipt: AuthorityStageReceipt,
    ) {
        checkOpen()
        requireExactDocumentImportReceipt(
            layout = layout,
            binding = binding,
            receipt = receipt,
        )
        val existing = documentImport
        if (existing != null) {
            check(
                existing.binding.cutoverGeneration == binding.cutoverGeneration &&
                    existing.binding.legacyPrefixFingerprint ==
                    binding.legacyPrefixReceiptFingerprint &&
                    existing.receipt.receiptFingerprint == receipt.receiptFingerprint,
            ) {
                "Student document-import owner cannot be rebound to a different cutover"
            }
            return
        }

        val studentBinding =
            StudentCutoverDestinationBinding(
                cutoverGeneration = binding.cutoverGeneration,
                legacyPrefixFingerprint = binding.legacyPrefixReceiptFingerprint,
                studentDestinationFingerprint = receipt.destinationFingerprint,
                studentSchemaVersion = STUDENT_DESTINATION_SCHEMA_VERSION,
                attestationPolicyVersion = STUDENT_ATTESTATION_POLICY_VERSION,
            )
        val reference =
            receiptBinder.bind(
                cutoverGeneration = binding.cutoverGeneration,
                legacyPrefixFingerprint = binding.legacyPrefixReceiptFingerprint,
                migratedRecordCount = receipt.migratedRecordCount,
                sourceCheckpoint = receipt.sourceCheckpoint,
                destinationFingerprint = receipt.destinationFingerprint,
                receiptFingerprint = receipt.receiptFingerprint,
            )
        check(
            reference.cutoverGeneration == binding.cutoverGeneration &&
                reference.legacyPrefixFingerprint ==
                binding.legacyPrefixReceiptFingerprint &&
                reference.migratedRecordCount == receipt.migratedRecordCount &&
                reference.sourceCheckpoint == receipt.sourceCheckpoint &&
                reference.destinationFingerprint == receipt.destinationFingerprint &&
                reference.receiptFingerprint == receipt.receiptFingerprint,
        ) {
            "Student owner returned a document-import reference for different evidence"
        }
        documentImport =
            BoundDocumentImport(
                binding = studentBinding,
                receipt = receipt,
                reference = reference,
            )
        outboxAttestation = null
        indexesAttestation = null
    }

    override suspend fun attestOutbox(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val bound = requireBound(binding)
        var cursor: StudentCutoverVerificationCursor? = null
        val seenCursors =
            Collections.newSetFromMap(
                IdentityHashMap<StudentCutoverVerificationCursor, Boolean>(),
            )
        repeat(MAX_STUDENT_ATTESTATION_PAGES) {
            when (
                val progress =
                    ports.outboxReconciled.verifyOutboxNext(
                        binding = bound.binding,
                        cursor = cursor,
                        limit = STUDENT_ATTESTATION_PAGE_SIZE,
                    )
            ) {
                is StudentCutoverAttestationProgress.Continue -> {
                    val next = progress.cursor
                    check(
                        next.stage ==
                            StudentCutoverDestinationStage.STUDENT_OUTBOX_RECONCILED,
                    ) {
                        "Student outbox owner returned a foreign resume cursor"
                    }
                    check(seenCursors.add(next)) {
                        "Student outbox owner repeated a resume cursor"
                    }
                    cursor = next
                }

                is StudentCutoverAttestationProgress.Verified -> {
                    val attestation = progress.attestation
                    val snapshot =
                        snapshotFromStudentOwnerAttestation(
                            attestation = attestation,
                            expectedStage =
                                StudentCutoverDestinationStage
                                    .STUDENT_OUTBOX_RECONCILED,
                            binding = bound.binding,
                            nowEpochMillis = currentTime(),
                        )
                    outboxAttestation = attestation
                    return snapshot
                }
            }
        }
        error("Student outbox verification exceeded its bounded page budget")
    }

    override suspend fun attestIndexes(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val bound = requireBound(binding)
        var cursor: StudentCutoverVerificationCursor? = null
        val seenCursors =
            Collections.newSetFromMap(
                IdentityHashMap<StudentCutoverVerificationCursor, Boolean>(),
            )
        repeat(MAX_STUDENT_ATTESTATION_PAGES) {
            when (
                val progress =
                    ports.indexesRebuilt.verifyIndexesNext(
                        binding = bound.binding,
                        cursor = cursor,
                        limit = STUDENT_ATTESTATION_PAGE_SIZE,
                    )
            ) {
                is StudentCutoverAttestationProgress.Continue -> {
                    val next = progress.cursor
                    check(
                        next.stage ==
                            StudentCutoverDestinationStage.STUDENT_INDEXES_REBUILT,
                    ) {
                        "Student index owner returned a foreign resume cursor"
                    }
                    check(seenCursors.add(next)) {
                        "Student index owner repeated a resume cursor"
                    }
                    cursor = next
                }

                is StudentCutoverAttestationProgress.Verified -> {
                    val attestation = progress.attestation
                    val snapshot =
                        snapshotFromStudentOwnerAttestation(
                            attestation = attestation,
                            expectedStage =
                                StudentCutoverDestinationStage
                                    .STUDENT_INDEXES_REBUILT,
                            binding = bound.binding,
                            nowEpochMillis = currentTime(),
                        )
                    indexesAttestation = attestation
                    return snapshot
                }
            }
        }
        error("Student index verification exceeded its bounded page budget")
    }

    override suspend fun attestAuthority(
        binding: TerminalAuthorityCutoverBinding,
    ): TerminalAuthorityStageSnapshot {
        val bound = requireBound(binding)
        val outbox =
            checkNotNull(outboxAttestation) {
                "Student authority verification requires a fresh outbox attestation"
            }
        val indexes =
            checkNotNull(indexesAttestation) {
                "Student authority verification requires a fresh index attestation"
            }
        val now = currentTime()
        requireFreshStudentOwnerAttestation(outbox, bound.binding, now)
        requireFreshStudentOwnerAttestation(indexes, bound.binding, now)
        val attestation =
            ports.authorityVerified.attest(
                binding = bound.binding,
                documentImportReceipt = bound.reference,
                outboxReconciled = outbox,
                indexesRebuilt = indexes,
            )
        return snapshotFromStudentOwnerAttestation(
            attestation = attestation,
            expectedStage =
                StudentCutoverDestinationStage.STUDENT_AUTHORITY_VERIFIED,
            binding = bound.binding,
            nowEpochMillis = currentTime(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        documentImport = null
        outboxAttestation = null
        indexesAttestation = null
        ports.close()
    }

    private fun requireBound(
        binding: TerminalAuthorityCutoverBinding,
    ): BoundDocumentImport {
        checkOpen()
        val bound =
            checkNotNull(documentImport) {
                "Student destination attestation requires the verified stage-five receipt"
            }
        check(
            bound.binding.cutoverGeneration == binding.cutoverGeneration &&
                bound.binding.legacyPrefixFingerprint ==
                binding.legacyPrefixReceiptFingerprint,
        ) {
            "Student destination attestation is bound to a different cutover"
        }
        return bound
    }

    private fun currentTime(): Long = clock().coerceAtLeast(0L)

    private fun checkOpen() {
        check(!closed) {
            "Student destination attestation owner is closed"
        }
    }
}

/**
 * Converts only exact, fresh owner fields into the legacy journal's stable three-field payload.
 *
 * The writer does not hash, wrap, or synthesize any of these fields.
 */
internal fun snapshotFromStudentOwnerAttestation(
    attestation: StudentCutoverDestinationAttestation,
    expectedStage: StudentCutoverDestinationStage,
    binding: StudentCutoverDestinationBinding,
    nowEpochMillis: Long,
): TerminalAuthorityStageSnapshot {
    check(attestation.stage == expectedStage) {
        "Student owner returned an attestation for the wrong stage"
    }
    requireFreshStudentOwnerAttestation(attestation, binding, nowEpochMillis)
    return TerminalAuthorityStageSnapshot(
        migratedRecordCount = attestation.verifiedRecordCount,
        sourceCheckpoint = attestation.verificationFingerprint,
        destinationFingerprint = attestation.destinationSnapshotFingerprint,
    )
}

private fun requireFreshStudentOwnerAttestation(
    attestation: StudentCutoverDestinationAttestation,
    binding: StudentCutoverDestinationBinding,
    nowEpochMillis: Long,
) {
    check(
        attestation.cutoverGeneration == binding.cutoverGeneration &&
            attestation.legacyPrefixFingerprint == binding.legacyPrefixFingerprint &&
            attestation.studentDestinationFingerprint ==
            binding.studentDestinationFingerprint &&
            attestation.studentSchemaVersion == binding.studentSchemaVersion &&
            attestation.attestationPolicyVersion ==
            binding.attestationPolicyVersion,
    ) {
        "Student owner attestation does not match the active cutover binding"
    }
    check(attestation.verifiedRecordCount >= 0L) {
        "Student owner attestation has an invalid record count"
    }
    check(attestation.destinationSnapshotFingerprint.matches(STUDENT_SHA_256)) {
        "Student owner attestation has an invalid destination fingerprint"
    }
    check(attestation.verificationFingerprint.matches(STUDENT_SHA_256)) {
        "Student owner attestation has an invalid verification fingerprint"
    }
    check(attestation.attestationFingerprint.matches(STUDENT_SHA_256)) {
        "Student owner attestation has an invalid proof fingerprint"
    }
    val now = nowEpochMillis.coerceAtLeast(0L)
    val latestAcceptedIssueTime =
        saturatingAdd(now, STUDENT_ATTESTATION_CLOCK_SKEW_MILLIS)
    val earliestAcceptedIssueTime =
        (now - STUDENT_ATTESTATION_MAX_AGE_MILLIS).coerceAtLeast(0L)
    check(
        attestation.issuedAtEpochMillis in
            earliestAcceptedIssueTime..latestAcceptedIssueTime,
    ) {
        "Student owner attestation is expired or issued in the future"
    }
}

private fun requireExactDocumentImportReceipt(
    layout: ThreeAuthorityDatabaseLayout,
    binding: TerminalAuthorityCutoverBinding,
    receipt: AuthorityStageReceipt,
) {
    check(receipt.stage == ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED) {
        "Student owner binding requires the stage-five document-import receipt"
    }
    check(
        receipt.targetDatabaseName ==
            layout.cutoverStorageNameFor(CutoverStorageTarget.STUDENT_MISTAKES),
    ) {
        "Student document-import receipt targets the wrong database"
    }
    check(receipt.hasValidFingerprint()) {
        "Student document-import receipt fingerprint is invalid"
    }
    check(
        receipt.previousReceiptFingerprint ==
            binding.legacyPrefixReceiptFingerprint,
    ) {
        "Student document-import receipt is not the legacy-prefix successor"
    }
    val expectedCheckpointPrefix =
        "generation:${binding.cutoverGeneration}:" +
            "prefix:${binding.legacyPrefixReceiptFingerprint}:source:"
    check(
        receipt.sourceCheckpoint.startsWith(expectedCheckpointPrefix) &&
            receipt.sourceCheckpoint.length > expectedCheckpointPrefix.length,
    ) {
        "Student document-import receipt does not carry the exact cutover prefix"
    }
}

private fun saturatingAdd(
    value: Long,
    increment: Long,
): Long =
    if (value > Long.MAX_VALUE - increment) {
        Long.MAX_VALUE
    } else {
        value + increment
    }

private data class BoundDocumentImport(
    val binding: StudentCutoverDestinationBinding,
    val receipt: AuthorityStageReceipt,
    val reference: StudentDocumentImportReceiptReference,
)

private const val STUDENT_DESTINATION_SCHEMA_VERSION = 12
private const val STUDENT_ATTESTATION_POLICY_VERSION =
    "student-cutover-destination-attestation-v1"
private const val STUDENT_ATTESTATION_PAGE_SIZE = 256
private const val MAX_STUDENT_ATTESTATION_PAGES = 100_000
private const val STUDENT_ATTESTATION_MAX_AGE_MILLIS = 5L * 60L * 1_000L
private const val STUDENT_ATTESTATION_CLOCK_SKEW_MILLIS = 30L * 1_000L
private val STUDENT_SHA_256 = Regex("[0-9a-f]{64}")
