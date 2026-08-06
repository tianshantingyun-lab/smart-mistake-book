package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.io.Closeable
import java.util.Collections
import java.util.WeakHashMap

/** The three student-owned post-import stages in the twelve-stage cutover. */
enum class StudentCutoverDestinationStage {
    STUDENT_OUTBOX_RECONCILED,
    STUDENT_INDEXES_REBUILT,
    STUDENT_AUTHORITY_VERIFIED,
}

/**
 * Exact cutover scope carried by every student destination proof and resume cursor.
 *
 * The destination fingerprint is supplied by the already completed document-import stage. This
 * package binds to it; it never guesses that stage receipt from an import digest.
 */
data class StudentCutoverDestinationBinding(
    val cutoverGeneration: Long,
    val legacyPrefixFingerprint: String,
    val studentDestinationFingerprint: String,
    val studentSchemaVersion: Int,
    val attestationPolicyVersion: String,
) {
    init {
        require(cutoverGeneration > 0L) {
            "Student cutover generation must be positive"
        }
        requireSha256(legacyPrefixFingerprint, "Legacy cutover-prefix fingerprint")
        requireSha256(studentDestinationFingerprint, "Student destination fingerprint")
        require(studentSchemaVersion > 0) {
            "Student schema version must be positive"
        }
        attestationPolicyVersion.requireStoreText(
            "Student attestation-policy version",
            MAX_CUTOVER_VERSION_CHARS,
        )
    }

    internal val canonicalFingerprint: String
        get() =
            CanonicalSha256(BINDING_FINGERPRINT_DOMAIN)
                .field("cutoverGeneration", cutoverGeneration)
                .field("legacyPrefixFingerprint", legacyPrefixFingerprint)
                .field("studentDestinationFingerprint", studentDestinationFingerprint)
                .field("studentSchemaVersion", studentSchemaVersion)
                .field("attestationPolicyVersion", attestationPolicyVersion)
                .finish()
}

/**
 * Required reference to the independently completed stage-five document-import receipt.
 *
 * [receiptFingerprint] is deliberately supplied by the core:data owner bridge and never derived
 * here. This opaque reference has no public constructor and is accepted only in the process where
 * that bridge bound it. Consequently a stage-eight proof cannot stand in for, recreate, or
 * silently skip stage five.
 */
class StudentDocumentImportReceiptReference private constructor(
    val cutoverGeneration: Long,
    val legacyPrefixFingerprint: String,
    val migratedRecordCount: Long,
    val sourceCheckpoint: String,
    val destinationFingerprint: String,
    val receiptFingerprint: String,
    private val referenceFingerprint: String,
) {
    init {
        require(cutoverGeneration > 0L) {
            "Student document-import generation must be positive"
        }
        requireSha256(legacyPrefixFingerprint, "Document-import legacy-prefix fingerprint")
        require(migratedRecordCount >= 0L) {
            "Student document-import count must not be negative"
        }
        sourceCheckpoint.requireStoreText(
            "Student document-import checkpoint",
            MAX_CHECKPOINT_CHARS,
        )
        requireSha256(destinationFingerprint, "Document-import destination fingerprint")
        requireSha256(receiptFingerprint, "Document-import receipt fingerprint")
        requireSha256(referenceFingerprint, "Document-import reference fingerprint")
    }

    internal fun requireOwnerIssued() {
        StudentCutoverIssuedArtifactRegistry.requireIssued(this)
        require(
            referenceFingerprint ==
                computeDocumentImportReferenceFingerprint(
                    cutoverGeneration = cutoverGeneration,
                    legacyPrefixFingerprint = legacyPrefixFingerprint,
                    migratedRecordCount = migratedRecordCount,
                    sourceCheckpoint = sourceCheckpoint,
                    destinationFingerprint = destinationFingerprint,
                    receiptFingerprint = receiptFingerprint,
                ),
        ) {
            "Student document-import reference no longer matches its owner binding"
        }
    }

    internal companion object {
        fun issue(
            ownerKey: StudentMistakeOwnerKey,
            cutoverGeneration: Long,
            legacyPrefixFingerprint: String,
            migratedRecordCount: Long,
            sourceCheckpoint: String,
            destinationFingerprint: String,
            receiptFingerprint: String,
        ): StudentDocumentImportReceiptReference {
            requireStudentCutoverOwner(ownerKey)
            return StudentCutoverIssuedArtifactRegistry.register(
                StudentDocumentImportReceiptReference(
                    cutoverGeneration = cutoverGeneration,
                    legacyPrefixFingerprint = legacyPrefixFingerprint,
                    migratedRecordCount = migratedRecordCount,
                    sourceCheckpoint = sourceCheckpoint,
                    destinationFingerprint = destinationFingerprint,
                    receiptFingerprint = receiptFingerprint,
                    referenceFingerprint =
                        computeDocumentImportReferenceFingerprint(
                            cutoverGeneration = cutoverGeneration,
                            legacyPrefixFingerprint = legacyPrefixFingerprint,
                            migratedRecordCount = migratedRecordCount,
                            sourceCheckpoint = sourceCheckpoint,
                            destinationFingerprint = destinationFingerprint,
                            receiptFingerprint = receiptFingerprint,
                        ),
                ),
            )
        }
    }
}

/**
 * Opaque, process-local continuation for a bounded destination verification.
 *
 * It has no public constructor or copy operation. A cursor can only be resumed against its exact
 * stage and [StudentCutoverDestinationBinding].
 */
class StudentCutoverVerificationCursor private constructor(
    val stage: StudentCutoverDestinationStage,
    val verifiedRecordCount: Long,
    internal val bindingFingerprint: String,
    internal val phase: StudentCutoverVerificationPhase,
    internal val afterExclusive: String?,
    internal val rollingFingerprint: String,
    internal val initialSnapshotFingerprint: String,
    internal val startedAtEpochMillis: Long,
) {
    init {
        require(verifiedRecordCount >= 0L) {
            "Verified student destination count must not be negative"
        }
        requireSha256(bindingFingerprint, "Cursor binding fingerprint")
        requireSha256(rollingFingerprint, "Cursor rolling fingerprint")
        requireSha256(initialSnapshotFingerprint, "Cursor snapshot fingerprint")
        require(startedAtEpochMillis >= 0L) {
            "Student destination scan time must not be negative"
        }
    }

    internal companion object {
        fun issue(
            ownerKey: StudentMistakeOwnerKey,
            stage: StudentCutoverDestinationStage,
            verifiedRecordCount: Long,
            bindingFingerprint: String,
            phase: StudentCutoverVerificationPhase,
            afterExclusive: String?,
            rollingFingerprint: String,
            initialSnapshotFingerprint: String,
            startedAtEpochMillis: Long,
        ): StudentCutoverVerificationCursor {
            requireStudentCutoverOwner(ownerKey)
            return StudentCutoverIssuedArtifactRegistry.register(
                StudentCutoverVerificationCursor(
                    stage = stage,
                    verifiedRecordCount = verifiedRecordCount,
                    bindingFingerprint = bindingFingerprint,
                    phase = phase,
                    afterExclusive = afterExclusive,
                    rollingFingerprint = rollingFingerprint,
                    initialSnapshotFingerprint = initialSnapshotFingerprint,
                    startedAtEpochMillis = startedAtEpochMillis,
                ),
            )
        }

        fun requireIssued(cursor: StudentCutoverVerificationCursor) {
            StudentCutoverIssuedArtifactRegistry.requireIssued(cursor)
        }
    }
}

sealed interface StudentCutoverAttestationProgress<out T> {
    class Continue internal constructor(
        val cursor: StudentCutoverVerificationCursor,
    ) : StudentCutoverAttestationProgress<Nothing>

    class Verified<T> internal constructor(
        val attestation: T,
    ) : StudentCutoverAttestationProgress<T>
}

/** Public, persistence-free shape shared by the three owner-issued attestations. */
interface StudentCutoverDestinationAttestation {
    val stage: StudentCutoverDestinationStage
    val cutoverGeneration: Long
    val legacyPrefixFingerprint: String
    val studentDestinationFingerprint: String
    val studentSchemaVersion: Int
    val attestationPolicyVersion: String
    val verifiedRecordCount: Long
    val destinationSnapshotFingerprint: String
    val verificationFingerprint: String
    val issuedAtEpochMillis: Long
    val attestationFingerprint: String
}

class StudentOutboxReconciledAttestation private constructor(
    override val cutoverGeneration: Long,
    override val legacyPrefixFingerprint: String,
    override val studentDestinationFingerprint: String,
    override val studentSchemaVersion: Int,
    override val attestationPolicyVersion: String,
    override val verifiedRecordCount: Long,
    override val destinationSnapshotFingerprint: String,
    override val verificationFingerprint: String,
    override val issuedAtEpochMillis: Long,
    override val attestationFingerprint: String,
) : StudentCutoverDestinationAttestation {
    override val stage: StudentCutoverDestinationStage =
        StudentCutoverDestinationStage.STUDENT_OUTBOX_RECONCILED

    internal fun matches(binding: StudentCutoverDestinationBinding): Boolean =
        hasValidFingerprint() &&
            cutoverGeneration == binding.cutoverGeneration &&
            legacyPrefixFingerprint == binding.legacyPrefixFingerprint &&
            studentDestinationFingerprint == binding.studentDestinationFingerprint &&
            studentSchemaVersion == binding.studentSchemaVersion &&
            attestationPolicyVersion == binding.attestationPolicyVersion

    internal fun hasValidFingerprint(): Boolean =
        StudentCutoverIssuedArtifactRegistry.isIssued(this) &&
            attestationFingerprint ==
            computeAttestationFingerprint(
                stage = stage,
                cutoverGeneration = cutoverGeneration,
                legacyPrefixFingerprint = legacyPrefixFingerprint,
                studentDestinationFingerprint = studentDestinationFingerprint,
                studentSchemaVersion = studentSchemaVersion,
                attestationPolicyVersion = attestationPolicyVersion,
                verifiedRecordCount = verifiedRecordCount,
                destinationSnapshotFingerprint = destinationSnapshotFingerprint,
                verificationFingerprint = verificationFingerprint,
                issuedAtEpochMillis = issuedAtEpochMillis,
                prerequisiteFingerprints = emptyList(),
            )

    internal companion object {
        fun issue(
            ownerKey: StudentMistakeOwnerKey,
            binding: StudentCutoverDestinationBinding,
            verifiedRecordCount: Long,
            destinationSnapshotFingerprint: String,
            verificationFingerprint: String,
            issuedAtEpochMillis: Long,
        ): StudentOutboxReconciledAttestation {
            requireStudentCutoverOwner(ownerKey)
            return StudentCutoverIssuedArtifactRegistry.register(
                StudentOutboxReconciledAttestation(
                    cutoverGeneration = binding.cutoverGeneration,
                    legacyPrefixFingerprint = binding.legacyPrefixFingerprint,
                    studentDestinationFingerprint =
                        binding.studentDestinationFingerprint,
                    studentSchemaVersion = binding.studentSchemaVersion,
                    attestationPolicyVersion = binding.attestationPolicyVersion,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    attestationFingerprint =
                        computeAttestationFingerprint(
                            stage =
                                StudentCutoverDestinationStage
                                    .STUDENT_OUTBOX_RECONCILED,
                            cutoverGeneration = binding.cutoverGeneration,
                            legacyPrefixFingerprint = binding.legacyPrefixFingerprint,
                            studentDestinationFingerprint =
                                binding.studentDestinationFingerprint,
                            studentSchemaVersion = binding.studentSchemaVersion,
                            attestationPolicyVersion =
                                binding.attestationPolicyVersion,
                            verifiedRecordCount = verifiedRecordCount,
                            destinationSnapshotFingerprint =
                                destinationSnapshotFingerprint,
                            verificationFingerprint = verificationFingerprint,
                            issuedAtEpochMillis = issuedAtEpochMillis,
                            prerequisiteFingerprints = emptyList(),
                        ),
                ),
            )
        }
    }
}

class StudentIndexesRebuiltAttestation private constructor(
    override val cutoverGeneration: Long,
    override val legacyPrefixFingerprint: String,
    override val studentDestinationFingerprint: String,
    override val studentSchemaVersion: Int,
    override val attestationPolicyVersion: String,
    override val verifiedRecordCount: Long,
    override val destinationSnapshotFingerprint: String,
    override val verificationFingerprint: String,
    override val issuedAtEpochMillis: Long,
    override val attestationFingerprint: String,
) : StudentCutoverDestinationAttestation {
    override val stage: StudentCutoverDestinationStage =
        StudentCutoverDestinationStage.STUDENT_INDEXES_REBUILT

    internal fun matches(binding: StudentCutoverDestinationBinding): Boolean =
        hasValidFingerprint() &&
            cutoverGeneration == binding.cutoverGeneration &&
            legacyPrefixFingerprint == binding.legacyPrefixFingerprint &&
            studentDestinationFingerprint == binding.studentDestinationFingerprint &&
            studentSchemaVersion == binding.studentSchemaVersion &&
            attestationPolicyVersion == binding.attestationPolicyVersion

    internal fun hasValidFingerprint(): Boolean =
        StudentCutoverIssuedArtifactRegistry.isIssued(this) &&
            attestationFingerprint ==
            computeAttestationFingerprint(
                stage = stage,
                cutoverGeneration = cutoverGeneration,
                legacyPrefixFingerprint = legacyPrefixFingerprint,
                studentDestinationFingerprint = studentDestinationFingerprint,
                studentSchemaVersion = studentSchemaVersion,
                attestationPolicyVersion = attestationPolicyVersion,
                verifiedRecordCount = verifiedRecordCount,
                destinationSnapshotFingerprint = destinationSnapshotFingerprint,
                verificationFingerprint = verificationFingerprint,
                issuedAtEpochMillis = issuedAtEpochMillis,
                prerequisiteFingerprints = emptyList(),
            )

    internal companion object {
        fun issue(
            ownerKey: StudentMistakeOwnerKey,
            binding: StudentCutoverDestinationBinding,
            verifiedRecordCount: Long,
            destinationSnapshotFingerprint: String,
            verificationFingerprint: String,
            issuedAtEpochMillis: Long,
        ): StudentIndexesRebuiltAttestation {
            requireStudentCutoverOwner(ownerKey)
            return StudentCutoverIssuedArtifactRegistry.register(
                StudentIndexesRebuiltAttestation(
                    cutoverGeneration = binding.cutoverGeneration,
                    legacyPrefixFingerprint = binding.legacyPrefixFingerprint,
                    studentDestinationFingerprint =
                        binding.studentDestinationFingerprint,
                    studentSchemaVersion = binding.studentSchemaVersion,
                    attestationPolicyVersion = binding.attestationPolicyVersion,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    attestationFingerprint =
                        computeAttestationFingerprint(
                            stage =
                                StudentCutoverDestinationStage
                                    .STUDENT_INDEXES_REBUILT,
                            cutoverGeneration = binding.cutoverGeneration,
                            legacyPrefixFingerprint = binding.legacyPrefixFingerprint,
                            studentDestinationFingerprint =
                                binding.studentDestinationFingerprint,
                            studentSchemaVersion = binding.studentSchemaVersion,
                            attestationPolicyVersion =
                                binding.attestationPolicyVersion,
                            verifiedRecordCount = verifiedRecordCount,
                            destinationSnapshotFingerprint =
                                destinationSnapshotFingerprint,
                            verificationFingerprint = verificationFingerprint,
                            issuedAtEpochMillis = issuedAtEpochMillis,
                            prerequisiteFingerprints = emptyList(),
                        ),
                ),
            )
        }
    }
}

class StudentAuthorityVerifiedAttestation private constructor(
    override val cutoverGeneration: Long,
    override val legacyPrefixFingerprint: String,
    override val studentDestinationFingerprint: String,
    override val studentSchemaVersion: Int,
    override val attestationPolicyVersion: String,
    override val verifiedRecordCount: Long,
    override val destinationSnapshotFingerprint: String,
    override val verificationFingerprint: String,
    override val issuedAtEpochMillis: Long,
    val documentImportReceiptFingerprint: String,
    val outboxReconciledAttestationFingerprint: String,
    val indexesRebuiltAttestationFingerprint: String,
    override val attestationFingerprint: String,
) : StudentCutoverDestinationAttestation {
    override val stage: StudentCutoverDestinationStage =
        StudentCutoverDestinationStage.STUDENT_AUTHORITY_VERIFIED

    internal fun hasValidFingerprint(): Boolean =
        StudentCutoverIssuedArtifactRegistry.isIssued(this) &&
            attestationFingerprint ==
            computeAttestationFingerprint(
                stage = stage,
                cutoverGeneration = cutoverGeneration,
                legacyPrefixFingerprint = legacyPrefixFingerprint,
                studentDestinationFingerprint = studentDestinationFingerprint,
                studentSchemaVersion = studentSchemaVersion,
                attestationPolicyVersion = attestationPolicyVersion,
                verifiedRecordCount = verifiedRecordCount,
                destinationSnapshotFingerprint = destinationSnapshotFingerprint,
                verificationFingerprint = verificationFingerprint,
                issuedAtEpochMillis = issuedAtEpochMillis,
                prerequisiteFingerprints =
                    listOf(
                        documentImportReceiptFingerprint,
                        outboxReconciledAttestationFingerprint,
                        indexesRebuiltAttestationFingerprint,
                    ),
            )

    internal companion object {
        fun issue(
            ownerKey: StudentMistakeOwnerKey,
            binding: StudentCutoverDestinationBinding,
            verifiedRecordCount: Long,
            destinationSnapshotFingerprint: String,
            verificationFingerprint: String,
            issuedAtEpochMillis: Long,
            documentImportReceiptFingerprint: String,
            outboxReconciledAttestationFingerprint: String,
            indexesRebuiltAttestationFingerprint: String,
        ): StudentAuthorityVerifiedAttestation {
            requireStudentCutoverOwner(ownerKey)
            val prerequisites =
                listOf(
                    documentImportReceiptFingerprint,
                    outboxReconciledAttestationFingerprint,
                    indexesRebuiltAttestationFingerprint,
                ).onEach { fingerprint ->
                    requireSha256(fingerprint, "Student authority prerequisite fingerprint")
                }
            return StudentCutoverIssuedArtifactRegistry.register(
                StudentAuthorityVerifiedAttestation(
                    cutoverGeneration = binding.cutoverGeneration,
                    legacyPrefixFingerprint = binding.legacyPrefixFingerprint,
                    studentDestinationFingerprint =
                        binding.studentDestinationFingerprint,
                    studentSchemaVersion = binding.studentSchemaVersion,
                    attestationPolicyVersion = binding.attestationPolicyVersion,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    documentImportReceiptFingerprint =
                        documentImportReceiptFingerprint,
                    outboxReconciledAttestationFingerprint =
                        outboxReconciledAttestationFingerprint,
                    indexesRebuiltAttestationFingerprint =
                        indexesRebuiltAttestationFingerprint,
                    attestationFingerprint =
                        computeAttestationFingerprint(
                            stage =
                                StudentCutoverDestinationStage
                                    .STUDENT_AUTHORITY_VERIFIED,
                            cutoverGeneration = binding.cutoverGeneration,
                            legacyPrefixFingerprint = binding.legacyPrefixFingerprint,
                            studentDestinationFingerprint =
                                binding.studentDestinationFingerprint,
                            studentSchemaVersion = binding.studentSchemaVersion,
                            attestationPolicyVersion =
                                binding.attestationPolicyVersion,
                            verifiedRecordCount = verifiedRecordCount,
                            destinationSnapshotFingerprint =
                                destinationSnapshotFingerprint,
                            verificationFingerprint = verificationFingerprint,
                            issuedAtEpochMillis = issuedAtEpochMillis,
                            prerequisiteFingerprints = prerequisites,
                        ),
                ),
            )
        }
    }
}

interface StudentOutboxReconciledAttestationPort {
    suspend fun verifyOutboxNext(
        binding: StudentCutoverDestinationBinding,
        cursor: StudentCutoverVerificationCursor? = null,
        limit: Int,
    ): StudentCutoverAttestationProgress<StudentOutboxReconciledAttestation>
}

interface StudentIndexesRebuiltAttestationPort {
    suspend fun verifyIndexesNext(
        binding: StudentCutoverDestinationBinding,
        cursor: StudentCutoverVerificationCursor? = null,
        limit: Int,
    ): StudentCutoverAttestationProgress<StudentIndexesRebuiltAttestation>
}

interface StudentAuthorityVerifiedAttestationPort {
    suspend fun attest(
        binding: StudentCutoverDestinationBinding,
        documentImportReceipt: StudentDocumentImportReceiptReference,
        outboxReconciled: StudentOutboxReconciledAttestation,
        indexesRebuilt: StudentIndexesRebuiltAttestation,
    ): StudentAuthorityVerifiedAttestation
}

/** Owner-opened read-only capability bundle. It exposes no database or mutable operation. */
interface StudentCutoverDestinationAttestationPorts : Closeable {
    val outboxReconciled: StudentOutboxReconciledAttestationPort
    val indexesRebuilt: StudentIndexesRebuiltAttestationPort
    val authorityVerified: StudentAuthorityVerifiedAttestationPort
}

internal enum class StudentCutoverVerificationPhase {
    OUTBOX,
    INBOX,
    SEARCH,
    CLASSIFICATION,
    IDENTITY,
}

private fun computeAttestationFingerprint(
    stage: StudentCutoverDestinationStage,
    cutoverGeneration: Long,
    legacyPrefixFingerprint: String,
    studentDestinationFingerprint: String,
    studentSchemaVersion: Int,
    attestationPolicyVersion: String,
    verifiedRecordCount: Long,
    destinationSnapshotFingerprint: String,
    verificationFingerprint: String,
    issuedAtEpochMillis: Long,
    prerequisiteFingerprints: List<String>,
): String {
    require(verifiedRecordCount >= 0L) {
        "Verified student destination count must not be negative"
    }
    requireSha256(destinationSnapshotFingerprint, "Destination snapshot fingerprint")
    requireSha256(verificationFingerprint, "Destination verification fingerprint")
    require(issuedAtEpochMillis >= 0L) {
        "Student destination attestation time must not be negative"
    }
    val digest =
        CanonicalSha256(ATTESTATION_FINGERPRINT_DOMAIN)
            .field("stage", stage.name)
            .field("cutoverGeneration", cutoverGeneration)
            .field("legacyPrefixFingerprint", legacyPrefixFingerprint)
            .field("studentDestinationFingerprint", studentDestinationFingerprint)
            .field("studentSchemaVersion", studentSchemaVersion)
            .field("attestationPolicyVersion", attestationPolicyVersion)
            .field("verifiedRecordCount", verifiedRecordCount)
            .field("destinationSnapshotFingerprint", destinationSnapshotFingerprint)
            .field("verificationFingerprint", verificationFingerprint)
            .field("issuedAtEpochMillis", issuedAtEpochMillis)
            .field("prerequisiteCount", prerequisiteFingerprints.size)
    prerequisiteFingerprints.forEachIndexed { index, fingerprint ->
        requireSha256(fingerprint, "Student authority prerequisite fingerprint")
        digest.field("prerequisite[$index]", fingerprint)
    }
    return digest.finish()
}

private fun computeDocumentImportReferenceFingerprint(
    cutoverGeneration: Long,
    legacyPrefixFingerprint: String,
    migratedRecordCount: Long,
    sourceCheckpoint: String,
    destinationFingerprint: String,
    receiptFingerprint: String,
): String =
    CanonicalSha256(DOCUMENT_IMPORT_REFERENCE_FINGERPRINT_DOMAIN)
        .field("cutoverGeneration", cutoverGeneration)
        .field("legacyPrefixFingerprint", legacyPrefixFingerprint)
        .field("migratedRecordCount", migratedRecordCount)
        .field("sourceCheckpoint", sourceCheckpoint)
        .field("destinationFingerprint", destinationFingerprint)
        .field("receiptFingerprint", receiptFingerprint)
        .finish()

private fun requireStudentCutoverOwner(ownerKey: StudentMistakeOwnerKey) {
    check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
        "Student destination attestation requires the database owner key"
    }
}

private object StudentCutoverIssuedArtifactRegistry {
    private val issuedArtifacts =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun <T : Any> register(artifact: T): T {
        check(issuedArtifacts.put(artifact, true) == null) {
            "Student cutover artifact was already issued"
        }
        return artifact
    }

    fun isIssued(artifact: Any): Boolean =
        issuedArtifacts[artifact] == true

    fun requireIssued(artifact: Any) {
        require(isIssued(artifact)) {
            "Student cutover artifact was not issued by its database owner"
        }
    }
}

private const val BINDING_FINGERPRINT_DOMAIN = "student-cutover-attestation-binding-v1"
private const val DOCUMENT_IMPORT_REFERENCE_FINGERPRINT_DOMAIN =
    "student-cutover-document-import-reference-v1"
private const val ATTESTATION_FINGERPRINT_DOMAIN =
    "student-cutover-destination-attestation-v1"
private const val MAX_CUTOVER_VERSION_CHARS = 128
private const val MAX_CHECKPOINT_CHARS = 512
