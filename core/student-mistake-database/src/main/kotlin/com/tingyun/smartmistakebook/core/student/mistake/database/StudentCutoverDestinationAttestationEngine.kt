package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.io.Closeable
import java.util.function.LongSupplier

internal data class StudentCutoverVerificationRecord(
    val stableKey: String,
    val canonicalFingerprint: String,
) {
    init {
        stableKey.requireStoreText(
            "Destination verification key",
            MAX_VERIFICATION_KEY_CHARS,
        )
        requireSha256(canonicalFingerprint, "Destination verification record fingerprint")
    }
}

internal data class StudentCutoverDestinationSnapshot(
    val journalFingerprint: String,
    val indexFingerprint: String,
    val journalConsistent: Boolean,
    val indexesConsistent: Boolean,
    val cutoverFencePresent: Boolean,
    val completionReceiptPresent: Boolean,
) {
    init {
        requireSha256(journalFingerprint, "Student journal snapshot fingerprint")
        requireSha256(indexFingerprint, "Student index snapshot fingerprint")
    }
}

/**
 * Private read model used by the owner. Every page is strictly bounded and canonically ordered.
 */
internal interface StudentCutoverDestinationReadSource : Closeable {
    suspend fun readSnapshot(): StudentCutoverDestinationSnapshot

    suspend fun readPage(
        phase: StudentCutoverVerificationPhase,
        afterExclusive: String?,
        limit: Int,
    ): List<StudentCutoverVerificationRecord>
}

internal class StudentCutoverDestinationAttestationEngine(
    private val source: StudentCutoverDestinationReadSource,
    private val nowEpochMillis: LongSupplier,
    private val ownerKey: StudentMistakeOwnerKey,
) : StudentCutoverDestinationAttestationPorts,
    StudentOutboxReconciledAttestationPort,
    StudentIndexesRebuiltAttestationPort,
    StudentAuthorityVerifiedAttestationPort {
    override val outboxReconciled: StudentOutboxReconciledAttestationPort
        get() = this

    override val indexesRebuilt: StudentIndexesRebuiltAttestationPort
        get() = this

    override val authorityVerified: StudentAuthorityVerifiedAttestationPort
        get() = this

    init {
        check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
            "Student destination attestation engine requires the database owner key"
        }
    }

    override suspend fun verifyOutboxNext(
        binding: StudentCutoverDestinationBinding,
        cursor: StudentCutoverVerificationCursor?,
        limit: Int,
    ): StudentCutoverAttestationProgress<StudentOutboxReconciledAttestation> {
        requireCurrentPolicy(binding)
        val state =
            resolveState(
                binding = binding,
                stage = StudentCutoverDestinationStage.STUDENT_OUTBOX_RECONCILED,
                firstPhase = StudentCutoverVerificationPhase.OUTBOX,
                allowedPhases =
                    setOf(
                        StudentCutoverVerificationPhase.OUTBOX,
                        StudentCutoverVerificationPhase.INBOX,
                    ),
                cursor = cursor,
                limit = limit,
                snapshotSelector = StudentCutoverDestinationSnapshot::journalFingerprint,
            )
        return when (
            val page =
                verifyPage(
                    binding = binding,
                    stage =
                        StudentCutoverDestinationStage
                            .STUDENT_OUTBOX_RECONCILED,
                    state = state,
                    limit = limit,
                    nextPhase = {
                        when (it) {
                            StudentCutoverVerificationPhase.OUTBOX ->
                                StudentCutoverVerificationPhase.INBOX

                            StudentCutoverVerificationPhase.INBOX -> null
                            else -> error("Invalid student journal verification phase")
                        }
                    },
                    snapshotSelector =
                        StudentCutoverDestinationSnapshot::journalFingerprint,
                )
        ) {
            is VerifiedPage.Continue ->
                StudentCutoverAttestationProgress.Continue(page.cursor)

            is VerifiedPage.Complete ->
                StudentCutoverAttestationProgress.Verified(
                    StudentOutboxReconciledAttestation.issue(
                        ownerKey = ownerKey,
                        binding = binding,
                        verifiedRecordCount = page.verifiedRecordCount,
                        destinationSnapshotFingerprint = page.snapshotFingerprint,
                        verificationFingerprint = page.verificationFingerprint,
                        issuedAtEpochMillis = currentTime(),
                    ),
                )
        }
    }

    override suspend fun verifyIndexesNext(
        binding: StudentCutoverDestinationBinding,
        cursor: StudentCutoverVerificationCursor?,
        limit: Int,
    ): StudentCutoverAttestationProgress<StudentIndexesRebuiltAttestation> {
        requireCurrentPolicy(binding)
        val state =
            resolveState(
                binding = binding,
                stage = StudentCutoverDestinationStage.STUDENT_INDEXES_REBUILT,
                firstPhase = StudentCutoverVerificationPhase.SEARCH,
                allowedPhases =
                    setOf(
                        StudentCutoverVerificationPhase.SEARCH,
                        StudentCutoverVerificationPhase.CLASSIFICATION,
                        StudentCutoverVerificationPhase.IDENTITY,
                    ),
                cursor = cursor,
                limit = limit,
                snapshotSelector = StudentCutoverDestinationSnapshot::indexFingerprint,
            )
        return when (
            val page =
                verifyPage(
                    binding = binding,
                    stage = StudentCutoverDestinationStage.STUDENT_INDEXES_REBUILT,
                    state = state,
                    limit = limit,
                    nextPhase = {
                        when (it) {
                            StudentCutoverVerificationPhase.SEARCH ->
                                StudentCutoverVerificationPhase.CLASSIFICATION

                            StudentCutoverVerificationPhase.CLASSIFICATION ->
                                StudentCutoverVerificationPhase.IDENTITY

                            StudentCutoverVerificationPhase.IDENTITY -> null
                            else -> error("Invalid student index verification phase")
                        }
                    },
                    snapshotSelector =
                        StudentCutoverDestinationSnapshot::indexFingerprint,
                )
        ) {
            is VerifiedPage.Continue ->
                StudentCutoverAttestationProgress.Continue(page.cursor)

            is VerifiedPage.Complete ->
                StudentCutoverAttestationProgress.Verified(
                    StudentIndexesRebuiltAttestation.issue(
                        ownerKey = ownerKey,
                        binding = binding,
                        verifiedRecordCount = page.verifiedRecordCount,
                        destinationSnapshotFingerprint = page.snapshotFingerprint,
                        verificationFingerprint = page.verificationFingerprint,
                        issuedAtEpochMillis = currentTime(),
                    ),
                )
        }
    }

    override suspend fun attest(
        binding: StudentCutoverDestinationBinding,
        documentImportReceipt: StudentDocumentImportReceiptReference,
        outboxReconciled: StudentOutboxReconciledAttestation,
        indexesRebuilt: StudentIndexesRebuiltAttestation,
    ): StudentAuthorityVerifiedAttestation {
        requireCurrentPolicy(binding)
        documentImportReceipt.requireOwnerIssued()
        require(
            documentImportReceipt.cutoverGeneration == binding.cutoverGeneration &&
                documentImportReceipt.legacyPrefixFingerprint ==
                binding.legacyPrefixFingerprint &&
                documentImportReceipt.destinationFingerprint ==
                binding.studentDestinationFingerprint
        ) {
            "Document-import receipt is outside the student cutover binding"
        }
        require(outboxReconciled.matches(binding)) {
            "Outbox reconciliation attestation is outside the student cutover binding"
        }
        require(indexesRebuilt.matches(binding)) {
            "Index attestation is outside the student cutover binding"
        }
        val snapshot = source.readSnapshot()
        check(snapshot.journalConsistent && snapshot.indexesConsistent) {
            "Student destination is not internally consistent"
        }
        check(!snapshot.cutoverFencePresent && !snapshot.completionReceiptPresent) {
            "Student authority must be verified before its terminal fence"
        }
        check(
            snapshot.journalFingerprint ==
                outboxReconciled.destinationSnapshotFingerprint
        ) {
            "Student journal changed after outbox reconciliation"
        }
        check(
            snapshot.indexFingerprint ==
                indexesRebuilt.destinationSnapshotFingerprint
        ) {
            "Student indexes changed after index verification"
        }
        val verifiedRecordCount =
            try {
                Math.addExact(
                    documentImportReceipt.migratedRecordCount,
                    Math.addExact(
                        outboxReconciled.verifiedRecordCount,
                        indexesRebuilt.verifiedRecordCount,
                    ),
                )
            } catch (_: ArithmeticException) {
                throw IllegalStateException(
                    "Student authority verification count overflowed",
                )
            }
        val preFenceFingerprint =
            CanonicalSha256(PRE_FENCE_SNAPSHOT_DOMAIN)
                .field("bindingFingerprint", binding.canonicalFingerprint)
                .field(
                    "documentImportReceiptFingerprint",
                    documentImportReceipt.receiptFingerprint,
                )
                .field("documentImportCheckpoint", documentImportReceipt.sourceCheckpoint)
                .field("journalFingerprint", snapshot.journalFingerprint)
                .field("indexFingerprint", snapshot.indexFingerprint)
                .field("cutoverFencePresent", snapshot.cutoverFencePresent)
                .field("completionReceiptPresent", snapshot.completionReceiptPresent)
                .finish()
        val authorityVerificationFingerprint =
            CanonicalSha256(AUTHORITY_VERIFICATION_FINGERPRINT_DOMAIN)
                .field("bindingFingerprint", binding.canonicalFingerprint)
                .field(
                    "documentImportReceiptFingerprint",
                    documentImportReceipt.receiptFingerprint,
                )
                .field(
                    "outboxVerificationFingerprint",
                    outboxReconciled.verificationFingerprint,
                )
                .field(
                    "indexVerificationFingerprint",
                    indexesRebuilt.verificationFingerprint,
                )
                .field("preFenceFingerprint", preFenceFingerprint)
                .finish()
        return StudentAuthorityVerifiedAttestation.issue(
            ownerKey = ownerKey,
            binding = binding,
            verifiedRecordCount = verifiedRecordCount,
            destinationSnapshotFingerprint = preFenceFingerprint,
            verificationFingerprint = authorityVerificationFingerprint,
            issuedAtEpochMillis = currentTime(),
            documentImportReceiptFingerprint =
                documentImportReceipt.receiptFingerprint,
            outboxReconciledAttestationFingerprint =
                outboxReconciled.attestationFingerprint,
            indexesRebuiltAttestationFingerprint =
                indexesRebuilt.attestationFingerprint,
        )
    }

    override fun close() {
        source.close()
    }

    private suspend fun resolveState(
        binding: StudentCutoverDestinationBinding,
        stage: StudentCutoverDestinationStage,
        firstPhase: StudentCutoverVerificationPhase,
        allowedPhases: Set<StudentCutoverVerificationPhase>,
        cursor: StudentCutoverVerificationCursor?,
        limit: Int,
        snapshotSelector: (StudentCutoverDestinationSnapshot) -> String,
    ): VerificationState {
        require(limit in 1..MAX_ATTESTATION_PAGE_SIZE) {
            "Student destination verification page size is outside the supported range"
        }
        if (cursor == null) {
            val snapshot = source.readSnapshot()
            check(
                when (stage) {
                    StudentCutoverDestinationStage.STUDENT_OUTBOX_RECONCILED ->
                        snapshot.journalConsistent

                    StudentCutoverDestinationStage.STUDENT_INDEXES_REBUILT ->
                        snapshot.indexesConsistent

                    StudentCutoverDestinationStage.STUDENT_AUTHORITY_VERIFIED ->
                        snapshot.journalConsistent && snapshot.indexesConsistent
                },
            ) {
                "Student destination is not ready for ${stage.name}"
            }
            val snapshotFingerprint = snapshotSelector(snapshot)
            return VerificationState(
                phase = firstPhase,
                afterExclusive = null,
                rollingFingerprint =
                    initialRollingFingerprint(
                        binding = binding,
                        stage = stage,
                    ),
                verifiedRecordCount = 0L,
                initialSnapshotFingerprint = snapshotFingerprint,
                startedAtEpochMillis = currentTime(),
            )
        }
        StudentCutoverVerificationCursor.requireIssued(cursor)
        require(cursor.stage == stage) {
            "Student destination cursor belongs to another stage"
        }
        require(cursor.bindingFingerprint == binding.canonicalFingerprint) {
            "Student destination cursor belongs to another cutover binding"
        }
        require(cursor.phase in allowedPhases) {
            "Student destination cursor has an invalid verification phase"
        }
        require(cursor.verifiedRecordCount <= MAX_VERIFIED_RECORDS) {
            "Student destination cursor exceeds its record budget"
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
        binding: StudentCutoverDestinationBinding,
        stage: StudentCutoverDestinationStage,
        state: VerificationState,
        limit: Int,
        nextPhase: (StudentCutoverVerificationPhase) -> StudentCutoverVerificationPhase?,
        snapshotSelector: (StudentCutoverDestinationSnapshot) -> String,
    ): VerifiedPage {
        val rows =
            source.readPage(
                phase = state.phase,
                afterExclusive = state.afterExclusive,
                limit = limit + 1,
            )
        check(rows.size <= limit + 1) {
            "Student destination source exceeded the requested page bound"
        }
        check(
            rows.zipWithNext().all { (left, right) ->
                left.stableKey < right.stableKey
            } &&
                rows.firstOrNull()?.stableKey?.let { first ->
                    state.afterExclusive == null || first > state.afterExclusive
                } != false,
        ) {
            "Student destination source returned a non-canonical page"
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
            state.verifiedRecordCount + admitted.size
        check(
            verifiedRecordCount >= state.verifiedRecordCount &&
                verifiedRecordCount <= MAX_VERIFIED_RECORDS,
        ) {
            "Student destination verification exceeds its record budget"
        }
        if (rows.size > limit) {
            return VerifiedPage.Continue(
                cursor =
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
        val next = nextPhase(state.phase)
        if (next != null) {
            return VerifiedPage.Continue(
                cursor =
                    issueCursor(
                        binding = binding,
                        stage = stage,
                        state = state,
                        phase = next,
                        afterExclusive = null,
                        rollingFingerprint = rollingFingerprint,
                        verifiedRecordCount = verifiedRecordCount,
                    ),
            )
        }
        val finalState = source.readSnapshot()
        check(
            when (stage) {
                StudentCutoverDestinationStage.STUDENT_OUTBOX_RECONCILED ->
                    finalState.journalConsistent

                StudentCutoverDestinationStage.STUDENT_INDEXES_REBUILT ->
                    finalState.indexesConsistent

                StudentCutoverDestinationStage.STUDENT_AUTHORITY_VERIFIED ->
                    finalState.journalConsistent && finalState.indexesConsistent
            },
        ) {
            "Student destination became inconsistent during verification"
        }
        val finalSnapshot = snapshotSelector(finalState)
        check(finalSnapshot == state.initialSnapshotFingerprint) {
            "Student destination changed during bounded verification"
        }
        return VerifiedPage.Complete(
            verifiedRecordCount = verifiedRecordCount,
            snapshotFingerprint = finalSnapshot,
            verificationFingerprint = rollingFingerprint,
        )
    }

    private fun issueCursor(
        binding: StudentCutoverDestinationBinding,
        stage: StudentCutoverDestinationStage,
        state: VerificationState,
        phase: StudentCutoverVerificationPhase,
        afterExclusive: String?,
        rollingFingerprint: String,
        verifiedRecordCount: Long,
    ): StudentCutoverVerificationCursor =
        StudentCutoverVerificationCursor.issue(
            ownerKey = ownerKey,
            stage = stage,
            verifiedRecordCount = verifiedRecordCount,
            bindingFingerprint = binding.canonicalFingerprint,
            phase = phase,
            afterExclusive = afterExclusive,
            rollingFingerprint = rollingFingerprint,
            initialSnapshotFingerprint = state.initialSnapshotFingerprint,
            startedAtEpochMillis = state.startedAtEpochMillis,
        )

    private fun currentTime(): Long =
        nowEpochMillis.getAsLong().also { now ->
            check(now >= 0L) {
                "Student destination attestation time must not be negative"
            }
        }

    private fun requireCurrentPolicy(binding: StudentCutoverDestinationBinding) {
        require(binding.studentSchemaVersion == STUDENT_MISTAKE_SCHEMA_VERSION) {
            "Student destination binding uses another schema version"
        }
        require(
            binding.attestationPolicyVersion ==
                STUDENT_CUTOVER_ATTESTATION_POLICY_VERSION,
        ) {
            "Student destination binding uses another attestation policy"
        }
    }
}

private data class VerificationState(
    val phase: StudentCutoverVerificationPhase,
    val afterExclusive: String?,
    val rollingFingerprint: String,
    val verifiedRecordCount: Long,
    val initialSnapshotFingerprint: String,
    val startedAtEpochMillis: Long,
)

private sealed interface VerifiedPage {
    data class Continue(
        val cursor: StudentCutoverVerificationCursor,
    ) : VerifiedPage

    data class Complete(
        val verifiedRecordCount: Long,
        val snapshotFingerprint: String,
        val verificationFingerprint: String,
    ) : VerifiedPage
}

private fun initialRollingFingerprint(
    binding: StudentCutoverDestinationBinding,
    stage: StudentCutoverDestinationStage,
): String =
    CanonicalSha256(ROLLING_FINGERPRINT_DOMAIN)
        .field("bindingFingerprint", binding.canonicalFingerprint)
        .field("stage", stage.name)
        .finish()

internal const val STUDENT_MISTAKE_SCHEMA_VERSION = 14
internal const val STUDENT_CUTOVER_ATTESTATION_POLICY_VERSION =
    "student-cutover-destination-attestation-v1"
private const val MAX_ATTESTATION_PAGE_SIZE = 256
private const val MAX_VERIFIED_RECORDS = 10_000_000L
private const val MAX_VERIFICATION_KEY_CHARS = 768
private const val ROLLING_FINGERPRINT_DOMAIN =
    "student-cutover-verification-rolling-v1"
private const val PRE_FENCE_SNAPSHOT_DOMAIN = "student-cutover-pre-fence-snapshot-v1"
private const val AUTHORITY_VERIFICATION_FINGERPRINT_DOMAIN =
    "student-cutover-authority-verification-v1"
