package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.io.Closeable
import java.util.Collections
import java.util.WeakHashMap

/** The learner-mastery-owned terminal stages in the twelve-stage authority cutover. */
enum class LearnerMasteryCutoverDestinationStage {
    MASTERY_BINDINGS_RECONCILED,
    MASTERY_PROJECTIONS_REBUILT,
    MASTERY_AUTHORITY_VERIFIED,
}

/**
 * Opaque reference to the independently journaled stage-nine import receipt.
 *
 * Only the core:data split-package bridge can bind this reference. The learner store never
 * reconstructs a stage-nine receipt from a count or a caller-provided digest.
 */
class LearnerMasteryFactsImportReceiptReference private constructor(
    val learnerId: String,
    val cutoverGeneration: Long,
    val legacyPrefixFingerprint: String,
    val migratedRecordCount: Long,
    val sourceGeneration: String,
    val sourceCheckpoint: String,
    val destinationFingerprint: String,
    val receiptFingerprint: String,
    internal val referenceFingerprint: String,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
        require(cutoverGeneration > 0L) { "Mastery cutover generation must be positive" }
        requireMasteryFingerprint(
            legacyPrefixFingerprint,
            "Legacy cutover-prefix fingerprint",
        )
        require(migratedRecordCount >= 0L) {
            "Mastery facts-import count must not be negative"
        }
        requireMasteryVersion(sourceGeneration, "Mastery facts-import source generation")
        requireBoundedCutoverText(
            sourceCheckpoint,
            "Mastery facts-import source checkpoint",
            MAX_CHECKPOINT_CHARS,
        )
        requireMasteryFingerprint(
            destinationFingerprint,
            "Mastery facts-import destination fingerprint",
        )
        requireMasteryFingerprint(
            receiptFingerprint,
            "Mastery facts-import receipt fingerprint",
        )
        requireMasteryFingerprint(
            referenceFingerprint,
            "Mastery facts-import reference fingerprint",
        )
    }

    internal fun requireOwnerIssued() {
        LearnerMasteryCutoverIssuedArtifactRegistry.requireIssued(this)
        require(
            referenceFingerprint ==
                factsImportReferenceFingerprint(
                    learnerId = learnerId,
                    cutoverGeneration = cutoverGeneration,
                    legacyPrefixFingerprint = legacyPrefixFingerprint,
                    migratedRecordCount = migratedRecordCount,
                    sourceGeneration = sourceGeneration,
                    sourceCheckpoint = sourceCheckpoint,
                    destinationFingerprint = destinationFingerprint,
                    receiptFingerprint = receiptFingerprint,
                ),
        ) {
            "Mastery facts-import reference no longer matches its owner binding"
        }
    }

    internal companion object {
        fun issue(
            ownerKey: LearnerMasteryOwnerKey,
            learnerId: String,
            cutoverGeneration: Long,
            legacyPrefixFingerprint: String,
            migratedRecordCount: Long,
            sourceGeneration: String,
            sourceCheckpoint: String,
            destinationFingerprint: String,
            receiptFingerprint: String,
        ): LearnerMasteryFactsImportReceiptReference {
            requireLearnerMasteryCutoverOwner(ownerKey)
            return LearnerMasteryCutoverIssuedArtifactRegistry.register(
                LearnerMasteryFactsImportReceiptReference(
                    learnerId = learnerId,
                    cutoverGeneration = cutoverGeneration,
                    legacyPrefixFingerprint = legacyPrefixFingerprint,
                    migratedRecordCount = migratedRecordCount,
                    sourceGeneration = sourceGeneration,
                    sourceCheckpoint = sourceCheckpoint,
                    destinationFingerprint = destinationFingerprint,
                    receiptFingerprint = receiptFingerprint,
                    referenceFingerprint =
                        factsImportReferenceFingerprint(
                            learnerId = learnerId,
                            cutoverGeneration = cutoverGeneration,
                            legacyPrefixFingerprint = legacyPrefixFingerprint,
                            migratedRecordCount = migratedRecordCount,
                            sourceGeneration = sourceGeneration,
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
 * Exact destination scope captured from the learner store itself.
 *
 * The constructor is private and the binding port reads the active generation and all version
 * identities from the destination. A caller therefore cannot turn a hand-written summary into an
 * attestation scope.
 */
class LearnerMasteryCutoverDestinationBinding private constructor(
    val learnerId: String,
    val cutoverGeneration: Long,
    val legacyPrefixFingerprint: String,
    val factsImportReceiptFingerprint: String,
    val factsImportReferenceFingerprint: String,
    val factsImportSourceGeneration: String,
    val factsImportSourceCheckpoint: String,
    val factsImportDestinationFingerprint: String,
    val destinationIdentityFingerprint: String,
    val masterySchemaVersion: Int,
    val sourcePolicyVersion: String,
    val admissionPolicyVersion: String,
    val calibrationVersion: String,
    val projectionPolicyVersion: String,
    val projectionGenerationId: Long,
    val attestationPolicyVersion: String,
    internal val bindingFingerprint: String,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
        require(cutoverGeneration > 0L) { "Mastery cutover generation must be positive" }
        requireMasteryFingerprint(
            legacyPrefixFingerprint,
            "Legacy cutover-prefix fingerprint",
        )
        requireMasteryFingerprint(
            factsImportReceiptFingerprint,
            "Mastery facts-import receipt fingerprint",
        )
        requireMasteryFingerprint(
            factsImportReferenceFingerprint,
            "Mastery facts-import reference fingerprint",
        )
        requireMasteryVersion(
            factsImportSourceGeneration,
            "Mastery facts-import source generation",
        )
        requireBoundedCutoverText(
            factsImportSourceCheckpoint,
            "Mastery facts-import source checkpoint",
            MAX_CHECKPOINT_CHARS,
        )
        requireMasteryFingerprint(
            factsImportDestinationFingerprint,
            "Mastery facts-import destination fingerprint",
        )
        requireMasteryFingerprint(
            destinationIdentityFingerprint,
            "Mastery destination identity fingerprint",
        )
        require(masterySchemaVersion > 0) { "Mastery schema version must be positive" }
        listOf(
            "Mastery source-policy version" to sourcePolicyVersion,
            "Mastery admission-policy version" to admissionPolicyVersion,
            "Mastery calibration version" to calibrationVersion,
            "Mastery projection-policy version" to projectionPolicyVersion,
            "Mastery attestation-policy version" to attestationPolicyVersion,
        ).forEach { (label, value) ->
            requireBoundedCutoverText(value, label, MAX_VERSION_CHARS)
        }
        require(projectionGenerationId > 0L) {
            "Mastery projection generation must be positive"
        }
        requireMasteryFingerprint(bindingFingerprint, "Mastery cutover binding fingerprint")
    }

    internal fun requireOwnerIssued() {
        LearnerMasteryCutoverIssuedArtifactRegistry.requireIssued(this)
        require(
            bindingFingerprint ==
                computeBindingFingerprint(
                    learnerId = learnerId,
                    cutoverGeneration = cutoverGeneration,
                    legacyPrefixFingerprint = legacyPrefixFingerprint,
                    factsImportReceiptFingerprint = factsImportReceiptFingerprint,
                    factsImportReferenceFingerprint = factsImportReferenceFingerprint,
                    factsImportSourceGeneration = factsImportSourceGeneration,
                    factsImportSourceCheckpoint = factsImportSourceCheckpoint,
                    factsImportDestinationFingerprint =
                        factsImportDestinationFingerprint,
                    destinationIdentityFingerprint = destinationIdentityFingerprint,
                    masterySchemaVersion = masterySchemaVersion,
                    sourcePolicyVersion = sourcePolicyVersion,
                    admissionPolicyVersion = admissionPolicyVersion,
                    calibrationVersion = calibrationVersion,
                    projectionPolicyVersion = projectionPolicyVersion,
                    projectionGenerationId = projectionGenerationId,
                    attestationPolicyVersion = attestationPolicyVersion,
                ),
        ) {
            "Mastery cutover binding no longer matches its owner-issued scope"
        }
    }

    internal fun matches(
        receipt: LearnerMasteryFactsImportReceiptReference,
    ): Boolean =
        runCatching {
            requireOwnerIssued()
            receipt.requireOwnerIssued()
        }.isSuccess &&
            learnerId == receipt.learnerId &&
            cutoverGeneration == receipt.cutoverGeneration &&
            legacyPrefixFingerprint == receipt.legacyPrefixFingerprint &&
            factsImportReceiptFingerprint == receipt.receiptFingerprint &&
            factsImportReferenceFingerprint == receipt.referenceFingerprint &&
            factsImportSourceGeneration == receipt.sourceGeneration &&
            factsImportSourceCheckpoint == receipt.sourceCheckpoint &&
            factsImportDestinationFingerprint == receipt.destinationFingerprint

    internal companion object {
        fun issue(
            ownerKey: LearnerMasteryOwnerKey,
            receipt: LearnerMasteryFactsImportReceiptReference,
            destinationIdentityFingerprint: String,
            projectionGenerationId: Long,
        ): LearnerMasteryCutoverDestinationBinding {
            requireLearnerMasteryCutoverOwner(ownerKey)
            receipt.requireOwnerIssued()
            val fields =
                BindingFields(
                    learnerId = receipt.learnerId,
                    cutoverGeneration = receipt.cutoverGeneration,
                    legacyPrefixFingerprint = receipt.legacyPrefixFingerprint,
                    factsImportReceiptFingerprint = receipt.receiptFingerprint,
                    factsImportReferenceFingerprint = receipt.referenceFingerprint,
                    factsImportSourceGeneration = receipt.sourceGeneration,
                    factsImportSourceCheckpoint = receipt.sourceCheckpoint,
                    factsImportDestinationFingerprint =
                        receipt.destinationFingerprint,
                    destinationIdentityFingerprint = destinationIdentityFingerprint,
                    masterySchemaVersion = LEARNER_MASTERY_DATABASE_VERSION,
                    sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
                    admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
                    calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
                    projectionPolicyVersion =
                        LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                    projectionGenerationId = projectionGenerationId,
                    attestationPolicyVersion =
                        LEARNER_MASTERY_CUTOVER_ATTESTATION_POLICY_VERSION,
                )
            return LearnerMasteryCutoverIssuedArtifactRegistry.register(
                LearnerMasteryCutoverDestinationBinding(
                    learnerId = fields.learnerId,
                    cutoverGeneration = fields.cutoverGeneration,
                    legacyPrefixFingerprint = fields.legacyPrefixFingerprint,
                    factsImportReceiptFingerprint =
                        fields.factsImportReceiptFingerprint,
                    factsImportReferenceFingerprint =
                        fields.factsImportReferenceFingerprint,
                    factsImportSourceGeneration = fields.factsImportSourceGeneration,
                    factsImportSourceCheckpoint = fields.factsImportSourceCheckpoint,
                    factsImportDestinationFingerprint =
                        fields.factsImportDestinationFingerprint,
                    destinationIdentityFingerprint =
                        fields.destinationIdentityFingerprint,
                    masterySchemaVersion = fields.masterySchemaVersion,
                    sourcePolicyVersion = fields.sourcePolicyVersion,
                    admissionPolicyVersion = fields.admissionPolicyVersion,
                    calibrationVersion = fields.calibrationVersion,
                    projectionPolicyVersion = fields.projectionPolicyVersion,
                    projectionGenerationId = fields.projectionGenerationId,
                    attestationPolicyVersion = fields.attestationPolicyVersion,
                    bindingFingerprint = fields.fingerprint(),
                ),
            )
        }
    }
}

/** Process-local continuation for a bounded, deterministic destination scan. */
class LearnerMasteryCutoverVerificationCursor private constructor(
    val stage: LearnerMasteryCutoverDestinationStage,
    val verifiedRecordCount: Long,
    internal val bindingFingerprint: String,
    internal val phase: LearnerMasteryCutoverVerificationPhase,
    internal val afterExclusive: String?,
    internal val rollingFingerprint: String,
    internal val initialSnapshotFingerprint: String,
    internal val startedAtEpochMillis: Long,
) {
    init {
        require(verifiedRecordCount >= 0L) {
            "Verified mastery destination count must not be negative"
        }
        requireMasteryFingerprint(bindingFingerprint, "Mastery cursor binding fingerprint")
        requireMasteryFingerprint(rollingFingerprint, "Mastery cursor rolling fingerprint")
        requireMasteryFingerprint(
            initialSnapshotFingerprint,
            "Mastery cursor snapshot fingerprint",
        )
        require(startedAtEpochMillis >= 0L) {
            "Mastery destination scan time must not be negative"
        }
    }

    internal companion object {
        fun issue(
            ownerKey: LearnerMasteryOwnerKey,
            stage: LearnerMasteryCutoverDestinationStage,
            verifiedRecordCount: Long,
            bindingFingerprint: String,
            phase: LearnerMasteryCutoverVerificationPhase,
            afterExclusive: String?,
            rollingFingerprint: String,
            initialSnapshotFingerprint: String,
            startedAtEpochMillis: Long,
        ): LearnerMasteryCutoverVerificationCursor {
            requireLearnerMasteryCutoverOwner(ownerKey)
            return LearnerMasteryCutoverIssuedArtifactRegistry.register(
                LearnerMasteryCutoverVerificationCursor(
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

        fun requireIssued(cursor: LearnerMasteryCutoverVerificationCursor) {
            LearnerMasteryCutoverIssuedArtifactRegistry.requireIssued(cursor)
        }
    }
}

sealed interface LearnerMasteryCutoverAttestationProgress<out T> {
    class Continue internal constructor(
        val cursor: LearnerMasteryCutoverVerificationCursor,
    ) : LearnerMasteryCutoverAttestationProgress<Nothing>

    class Verified<T> internal constructor(
        val attestation: T,
    ) : LearnerMasteryCutoverAttestationProgress<T>
}

interface LearnerMasteryCutoverDestinationAttestation {
    val stage: LearnerMasteryCutoverDestinationStage
    val learnerId: String
    val cutoverGeneration: Long
    val legacyPrefixFingerprint: String
    val factsImportReceiptFingerprint: String
    val factsImportSourceCheckpoint: String
    val factsImportDestinationFingerprint: String
    val destinationIdentityFingerprint: String
    val masterySchemaVersion: Int
    val sourcePolicyVersion: String
    val admissionPolicyVersion: String
    val calibrationVersion: String
    val projectionPolicyVersion: String
    val projectionGenerationId: Long
    val attestationPolicyVersion: String
    val verifiedRecordCount: Long
    val destinationSnapshotFingerprint: String
    val verificationFingerprint: String
    val issuedAtEpochMillis: Long
    val attestationFingerprint: String
}

class LearnerMasteryBindingsReconciledAttestation private constructor(
    private val binding: LearnerMasteryCutoverDestinationBinding,
    override val verifiedRecordCount: Long,
    override val destinationSnapshotFingerprint: String,
    override val verificationFingerprint: String,
    val immutableLedgerWatermarkFingerprint: String,
    override val issuedAtEpochMillis: Long,
    override val attestationFingerprint: String,
) : LearnerMasteryCutoverDestinationAttestation {
    override val stage =
        LearnerMasteryCutoverDestinationStage.MASTERY_BINDINGS_RECONCILED
    override val learnerId get() = binding.learnerId
    override val cutoverGeneration get() = binding.cutoverGeneration
    override val legacyPrefixFingerprint get() = binding.legacyPrefixFingerprint
    override val factsImportReceiptFingerprint get() = binding.factsImportReceiptFingerprint
    override val factsImportSourceCheckpoint get() = binding.factsImportSourceCheckpoint
    override val factsImportDestinationFingerprint
        get() = binding.factsImportDestinationFingerprint
    override val destinationIdentityFingerprint
        get() = binding.destinationIdentityFingerprint
    override val masterySchemaVersion get() = binding.masterySchemaVersion
    override val sourcePolicyVersion get() = binding.sourcePolicyVersion
    override val admissionPolicyVersion get() = binding.admissionPolicyVersion
    override val calibrationVersion get() = binding.calibrationVersion
    override val projectionPolicyVersion get() = binding.projectionPolicyVersion
    override val projectionGenerationId get() = binding.projectionGenerationId
    override val attestationPolicyVersion get() = binding.attestationPolicyVersion

    internal fun matches(scope: LearnerMasteryCutoverDestinationBinding): Boolean =
        hasValidFingerprint() && binding === scope

    internal fun hasValidFingerprint(): Boolean =
        LearnerMasteryCutoverIssuedArtifactRegistry.isIssued(this) &&
            runCatching { binding.requireOwnerIssued() }.isSuccess &&
            attestationFingerprint ==
            computeAttestationFingerprint(
                stage = stage,
                bindingFingerprint = binding.bindingFingerprint,
                verifiedRecordCount = verifiedRecordCount,
                destinationSnapshotFingerprint = destinationSnapshotFingerprint,
                verificationFingerprint = verificationFingerprint,
                issuedAtEpochMillis = issuedAtEpochMillis,
                prerequisiteFingerprints =
                    listOf(immutableLedgerWatermarkFingerprint),
            )

    internal companion object {
        fun issue(
            ownerKey: LearnerMasteryOwnerKey,
            binding: LearnerMasteryCutoverDestinationBinding,
            verifiedRecordCount: Long,
            destinationSnapshotFingerprint: String,
            verificationFingerprint: String,
            immutableLedgerWatermarkFingerprint: String,
            issuedAtEpochMillis: Long,
        ): LearnerMasteryBindingsReconciledAttestation {
            requireLearnerMasteryCutoverOwner(ownerKey)
            binding.requireOwnerIssued()
            val fingerprint =
                computeAttestationFingerprint(
                    stage =
                        LearnerMasteryCutoverDestinationStage
                            .MASTERY_BINDINGS_RECONCILED,
                    bindingFingerprint = binding.bindingFingerprint,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    prerequisiteFingerprints =
                        listOf(immutableLedgerWatermarkFingerprint),
                )
            return LearnerMasteryCutoverIssuedArtifactRegistry.register(
                LearnerMasteryBindingsReconciledAttestation(
                    binding = binding,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    immutableLedgerWatermarkFingerprint =
                        immutableLedgerWatermarkFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    attestationFingerprint = fingerprint,
                ),
            )
        }
    }
}

class LearnerMasteryProjectionsRebuiltAttestation private constructor(
    private val binding: LearnerMasteryCutoverDestinationBinding,
    override val verifiedRecordCount: Long,
    override val destinationSnapshotFingerprint: String,
    override val verificationFingerprint: String,
    val activeProjectionGenerationFingerprint: String,
    override val issuedAtEpochMillis: Long,
    override val attestationFingerprint: String,
) : LearnerMasteryCutoverDestinationAttestation {
    override val stage =
        LearnerMasteryCutoverDestinationStage.MASTERY_PROJECTIONS_REBUILT
    override val learnerId get() = binding.learnerId
    override val cutoverGeneration get() = binding.cutoverGeneration
    override val legacyPrefixFingerprint get() = binding.legacyPrefixFingerprint
    override val factsImportReceiptFingerprint get() = binding.factsImportReceiptFingerprint
    override val factsImportSourceCheckpoint get() = binding.factsImportSourceCheckpoint
    override val factsImportDestinationFingerprint
        get() = binding.factsImportDestinationFingerprint
    override val destinationIdentityFingerprint
        get() = binding.destinationIdentityFingerprint
    override val masterySchemaVersion get() = binding.masterySchemaVersion
    override val sourcePolicyVersion get() = binding.sourcePolicyVersion
    override val admissionPolicyVersion get() = binding.admissionPolicyVersion
    override val calibrationVersion get() = binding.calibrationVersion
    override val projectionPolicyVersion get() = binding.projectionPolicyVersion
    override val projectionGenerationId get() = binding.projectionGenerationId
    override val attestationPolicyVersion get() = binding.attestationPolicyVersion

    internal fun matches(scope: LearnerMasteryCutoverDestinationBinding): Boolean =
        hasValidFingerprint() && binding === scope

    internal fun hasValidFingerprint(): Boolean =
        LearnerMasteryCutoverIssuedArtifactRegistry.isIssued(this) &&
            runCatching { binding.requireOwnerIssued() }.isSuccess &&
            attestationFingerprint ==
            computeAttestationFingerprint(
                stage = stage,
                bindingFingerprint = binding.bindingFingerprint,
                verifiedRecordCount = verifiedRecordCount,
                destinationSnapshotFingerprint = destinationSnapshotFingerprint,
                verificationFingerprint = verificationFingerprint,
                issuedAtEpochMillis = issuedAtEpochMillis,
                prerequisiteFingerprints =
                    listOf(activeProjectionGenerationFingerprint),
            )

    internal companion object {
        fun issue(
            ownerKey: LearnerMasteryOwnerKey,
            binding: LearnerMasteryCutoverDestinationBinding,
            verifiedRecordCount: Long,
            destinationSnapshotFingerprint: String,
            verificationFingerprint: String,
            activeProjectionGenerationFingerprint: String,
            issuedAtEpochMillis: Long,
        ): LearnerMasteryProjectionsRebuiltAttestation {
            requireLearnerMasteryCutoverOwner(ownerKey)
            binding.requireOwnerIssued()
            val fingerprint =
                computeAttestationFingerprint(
                    stage =
                        LearnerMasteryCutoverDestinationStage
                            .MASTERY_PROJECTIONS_REBUILT,
                    bindingFingerprint = binding.bindingFingerprint,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    prerequisiteFingerprints =
                        listOf(activeProjectionGenerationFingerprint),
                )
            return LearnerMasteryCutoverIssuedArtifactRegistry.register(
                LearnerMasteryProjectionsRebuiltAttestation(
                    binding = binding,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    activeProjectionGenerationFingerprint =
                        activeProjectionGenerationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    attestationFingerprint = fingerprint,
                ),
            )
        }
    }
}

class LearnerMasteryAuthorityVerifiedAttestation private constructor(
    private val binding: LearnerMasteryCutoverDestinationBinding,
    override val verifiedRecordCount: Long,
    override val destinationSnapshotFingerprint: String,
    override val verificationFingerprint: String,
    override val issuedAtEpochMillis: Long,
    val bindingsReconciledAttestationFingerprint: String,
    val projectionsRebuiltAttestationFingerprint: String,
    override val attestationFingerprint: String,
) : LearnerMasteryCutoverDestinationAttestation {
    override val stage =
        LearnerMasteryCutoverDestinationStage.MASTERY_AUTHORITY_VERIFIED
    override val learnerId get() = binding.learnerId
    override val cutoverGeneration get() = binding.cutoverGeneration
    override val legacyPrefixFingerprint get() = binding.legacyPrefixFingerprint
    override val factsImportReceiptFingerprint get() = binding.factsImportReceiptFingerprint
    override val factsImportSourceCheckpoint get() = binding.factsImportSourceCheckpoint
    override val factsImportDestinationFingerprint
        get() = binding.factsImportDestinationFingerprint
    override val destinationIdentityFingerprint
        get() = binding.destinationIdentityFingerprint
    override val masterySchemaVersion get() = binding.masterySchemaVersion
    override val sourcePolicyVersion get() = binding.sourcePolicyVersion
    override val admissionPolicyVersion get() = binding.admissionPolicyVersion
    override val calibrationVersion get() = binding.calibrationVersion
    override val projectionPolicyVersion get() = binding.projectionPolicyVersion
    override val projectionGenerationId get() = binding.projectionGenerationId
    override val attestationPolicyVersion get() = binding.attestationPolicyVersion

    internal fun hasValidFingerprint(): Boolean =
        LearnerMasteryCutoverIssuedArtifactRegistry.isIssued(this) &&
            runCatching { binding.requireOwnerIssued() }.isSuccess &&
            attestationFingerprint ==
            computeAttestationFingerprint(
                stage = stage,
                bindingFingerprint = binding.bindingFingerprint,
                verifiedRecordCount = verifiedRecordCount,
                destinationSnapshotFingerprint = destinationSnapshotFingerprint,
                verificationFingerprint = verificationFingerprint,
                issuedAtEpochMillis = issuedAtEpochMillis,
                prerequisiteFingerprints =
                    listOf(
                        factsImportReceiptFingerprint,
                        bindingsReconciledAttestationFingerprint,
                        projectionsRebuiltAttestationFingerprint,
                    ),
            )

    internal companion object {
        fun issue(
            ownerKey: LearnerMasteryOwnerKey,
            binding: LearnerMasteryCutoverDestinationBinding,
            verifiedRecordCount: Long,
            destinationSnapshotFingerprint: String,
            verificationFingerprint: String,
            issuedAtEpochMillis: Long,
            bindingsReconciledAttestationFingerprint: String,
            projectionsRebuiltAttestationFingerprint: String,
        ): LearnerMasteryAuthorityVerifiedAttestation {
            requireLearnerMasteryCutoverOwner(ownerKey)
            binding.requireOwnerIssued()
            val prerequisites =
                listOf(
                    binding.factsImportReceiptFingerprint,
                    bindingsReconciledAttestationFingerprint,
                    projectionsRebuiltAttestationFingerprint,
                )
            val fingerprint =
                computeAttestationFingerprint(
                    stage =
                        LearnerMasteryCutoverDestinationStage
                            .MASTERY_AUTHORITY_VERIFIED,
                    bindingFingerprint = binding.bindingFingerprint,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    prerequisiteFingerprints = prerequisites,
                )
            return LearnerMasteryCutoverIssuedArtifactRegistry.register(
                LearnerMasteryAuthorityVerifiedAttestation(
                    binding = binding,
                    verifiedRecordCount = verifiedRecordCount,
                    destinationSnapshotFingerprint =
                        destinationSnapshotFingerprint,
                    verificationFingerprint = verificationFingerprint,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    bindingsReconciledAttestationFingerprint =
                        bindingsReconciledAttestationFingerprint,
                    projectionsRebuiltAttestationFingerprint =
                        projectionsRebuiltAttestationFingerprint,
                    attestationFingerprint = fingerprint,
                ),
            )
        }
    }
}

interface LearnerMasteryCutoverDestinationBindingPort {
    suspend fun bind(
        factsImportReceipt: LearnerMasteryFactsImportReceiptReference,
    ): LearnerMasteryCutoverDestinationBinding
}

interface LearnerMasteryBindingsReconciledAttestationPort {
    suspend fun verifyBindingsNext(
        binding: LearnerMasteryCutoverDestinationBinding,
        cursor: LearnerMasteryCutoverVerificationCursor? = null,
        limit: Int,
    ): LearnerMasteryCutoverAttestationProgress<LearnerMasteryBindingsReconciledAttestation>
}

interface LearnerMasteryProjectionsRebuiltAttestationPort {
    suspend fun verifyProjectionsNext(
        binding: LearnerMasteryCutoverDestinationBinding,
        cursor: LearnerMasteryCutoverVerificationCursor? = null,
        limit: Int,
    ): LearnerMasteryCutoverAttestationProgress<LearnerMasteryProjectionsRebuiltAttestation>
}

interface LearnerMasteryAuthorityVerifiedAttestationPort {
    suspend fun attest(
        binding: LearnerMasteryCutoverDestinationBinding,
        factsImportReceipt: LearnerMasteryFactsImportReceiptReference,
        bindingsReconciled: LearnerMasteryBindingsReconciledAttestation,
        projectionsRebuilt: LearnerMasteryProjectionsRebuiltAttestation,
    ): LearnerMasteryAuthorityVerifiedAttestation
}

/** Owner-opened, read-only proof bundle. It exposes neither Room nor a mutable store handle. */
interface LearnerMasteryCutoverDestinationAttestationPorts : Closeable {
    val binding: LearnerMasteryCutoverDestinationBindingPort
    val bindingsReconciled: LearnerMasteryBindingsReconciledAttestationPort
    val projectionsRebuilt: LearnerMasteryProjectionsRebuiltAttestationPort
    val authorityVerified: LearnerMasteryAuthorityVerifiedAttestationPort
}

internal enum class LearnerMasteryCutoverVerificationPhase {
    SOURCE_FACTS,
    CANDIDATES,
    ATTRIBUTIONS,
    PROBLEM_BINDINGS,
    SUPERSESSIONS,
    MIGRATION_LEDGER,
    PROJECTION_GENERATION,
    PROJECTIONS,
    SUBJECT_DIGESTS,
    PRESENTATION_BUDGETS,
    PROBLEM_FAMILY_BUDGETS,
    SCHEMA_OBJECTS,
}

private data class BindingFields(
    val learnerId: String,
    val cutoverGeneration: Long,
    val legacyPrefixFingerprint: String,
    val factsImportReceiptFingerprint: String,
    val factsImportReferenceFingerprint: String,
    val factsImportSourceGeneration: String,
    val factsImportSourceCheckpoint: String,
    val factsImportDestinationFingerprint: String,
    val destinationIdentityFingerprint: String,
    val masterySchemaVersion: Int,
    val sourcePolicyVersion: String,
    val admissionPolicyVersion: String,
    val calibrationVersion: String,
    val projectionPolicyVersion: String,
    val projectionGenerationId: Long,
    val attestationPolicyVersion: String,
) {
    fun fingerprint(): String =
        computeBindingFingerprint(
            learnerId = learnerId,
            cutoverGeneration = cutoverGeneration,
            legacyPrefixFingerprint = legacyPrefixFingerprint,
            factsImportReceiptFingerprint = factsImportReceiptFingerprint,
            factsImportReferenceFingerprint = factsImportReferenceFingerprint,
            factsImportSourceGeneration = factsImportSourceGeneration,
            factsImportSourceCheckpoint = factsImportSourceCheckpoint,
            factsImportDestinationFingerprint = factsImportDestinationFingerprint,
            destinationIdentityFingerprint = destinationIdentityFingerprint,
            masterySchemaVersion = masterySchemaVersion,
            sourcePolicyVersion = sourcePolicyVersion,
            admissionPolicyVersion = admissionPolicyVersion,
            calibrationVersion = calibrationVersion,
            projectionPolicyVersion = projectionPolicyVersion,
            projectionGenerationId = projectionGenerationId,
            attestationPolicyVersion = attestationPolicyVersion,
        )
}

private fun computeBindingFingerprint(
    learnerId: String,
    cutoverGeneration: Long,
    legacyPrefixFingerprint: String,
    factsImportReceiptFingerprint: String,
    factsImportReferenceFingerprint: String,
    factsImportSourceGeneration: String,
    factsImportSourceCheckpoint: String,
    factsImportDestinationFingerprint: String,
    destinationIdentityFingerprint: String,
    masterySchemaVersion: Int,
    sourcePolicyVersion: String,
    admissionPolicyVersion: String,
    calibrationVersion: String,
    projectionPolicyVersion: String,
    projectionGenerationId: Long,
    attestationPolicyVersion: String,
): String =
    CanonicalSha256(BINDING_FINGERPRINT_DOMAIN)
        .field("learnerId", learnerId)
        .field("cutoverGeneration", cutoverGeneration)
        .field("legacyPrefixFingerprint", legacyPrefixFingerprint)
        .field("factsImportReceiptFingerprint", factsImportReceiptFingerprint)
        .field("factsImportReferenceFingerprint", factsImportReferenceFingerprint)
        .field("factsImportSourceGeneration", factsImportSourceGeneration)
        .field("factsImportSourceCheckpoint", factsImportSourceCheckpoint)
        .field(
            "factsImportDestinationFingerprint",
            factsImportDestinationFingerprint,
        )
        .field("destinationIdentityFingerprint", destinationIdentityFingerprint)
        .field("masterySchemaVersion", masterySchemaVersion)
        .field("sourcePolicyVersion", sourcePolicyVersion)
        .field("admissionPolicyVersion", admissionPolicyVersion)
        .field("calibrationVersion", calibrationVersion)
        .field("projectionPolicyVersion", projectionPolicyVersion)
        .field("projectionGenerationId", projectionGenerationId)
        .field("attestationPolicyVersion", attestationPolicyVersion)
        .finish()

private fun factsImportReferenceFingerprint(
    learnerId: String,
    cutoverGeneration: Long,
    legacyPrefixFingerprint: String,
    migratedRecordCount: Long,
    sourceGeneration: String,
    sourceCheckpoint: String,
    destinationFingerprint: String,
    receiptFingerprint: String,
): String =
    CanonicalSha256(FACTS_IMPORT_REFERENCE_FINGERPRINT_DOMAIN)
        .field("learnerId", learnerId)
        .field("cutoverGeneration", cutoverGeneration)
        .field("legacyPrefixFingerprint", legacyPrefixFingerprint)
        .field("migratedRecordCount", migratedRecordCount)
        .field("sourceGeneration", sourceGeneration)
        .field("sourceCheckpoint", sourceCheckpoint)
        .field("destinationFingerprint", destinationFingerprint)
        .field("receiptFingerprint", receiptFingerprint)
        .finish()

private fun computeAttestationFingerprint(
    stage: LearnerMasteryCutoverDestinationStage,
    bindingFingerprint: String,
    verifiedRecordCount: Long,
    destinationSnapshotFingerprint: String,
    verificationFingerprint: String,
    issuedAtEpochMillis: Long,
    prerequisiteFingerprints: List<String>,
): String {
    require(verifiedRecordCount >= 0L) {
        "Verified mastery destination count must not be negative"
    }
    requireMasteryFingerprint(bindingFingerprint, "Mastery binding fingerprint")
    requireMasteryFingerprint(
        destinationSnapshotFingerprint,
        "Mastery destination snapshot fingerprint",
    )
    requireMasteryFingerprint(
        verificationFingerprint,
        "Mastery destination verification fingerprint",
    )
    require(issuedAtEpochMillis >= 0L) {
        "Mastery destination attestation time must not be negative"
    }
    val digest =
        CanonicalSha256(ATTESTATION_FINGERPRINT_DOMAIN)
            .field("stage", stage.name)
            .field("bindingFingerprint", bindingFingerprint)
            .field("verifiedRecordCount", verifiedRecordCount)
            .field("destinationSnapshotFingerprint", destinationSnapshotFingerprint)
            .field("verificationFingerprint", verificationFingerprint)
            .field("issuedAtEpochMillis", issuedAtEpochMillis)
            .field("prerequisiteCount", prerequisiteFingerprints.size)
    prerequisiteFingerprints.forEachIndexed { index, fingerprint ->
        requireMasteryFingerprint(
            fingerprint,
            "Mastery authority prerequisite fingerprint",
        )
        digest.field("prerequisite[$index]", fingerprint)
    }
    return digest.finish()
}

private fun requireLearnerMasteryCutoverOwner(ownerKey: LearnerMasteryOwnerKey) {
    check(ownerKey === LearnerMasteryOwnerKey.INSTANCE) {
        "Mastery destination attestation requires the database owner key"
    }
}

private object LearnerMasteryCutoverIssuedArtifactRegistry {
    private val issuedArtifacts =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun <T : Any> register(artifact: T): T {
        check(issuedArtifacts.put(artifact, true) == null) {
            "Mastery cutover artifact was already issued"
        }
        return artifact
    }

    fun isIssued(artifact: Any): Boolean = issuedArtifacts[artifact] == true

    fun requireIssued(artifact: Any) {
        require(isIssued(artifact)) {
            "Mastery cutover artifact was not issued by its database owner"
        }
    }
}

private fun requireBoundedCutoverText(
    value: String,
    label: String,
    maxChars: Int,
) {
    require(value.isNotBlank() && value.length <= maxChars && value.none(Char::isISOControl)) {
        "$label is invalid"
    }
}

internal const val LEARNER_MASTERY_CUTOVER_ATTESTATION_POLICY_VERSION =
    "learner-mastery-cutover-destination-attestation-v1"
private const val BINDING_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-attestation-binding-v1"
private const val FACTS_IMPORT_REFERENCE_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-facts-import-reference-v1"
private const val ATTESTATION_FINGERPRINT_DOMAIN =
    "learner-mastery-cutover-destination-attestation-v1"
private const val MAX_VERSION_CHARS = 128
private const val MAX_CHECKPOINT_CHARS = 512
