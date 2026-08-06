package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal enum class TutorInteractionAnswerCertificateProvenanceKind {
    TRUSTED_SCORING_KEY,
    INDEPENDENT_PROVIDER_ATTESTATION,
    CERTIFIED_TEACHER_ATTESTATION,
    USER_EXPLICIT_ANSWER_CONFIRMATION,
    LOCAL_DETERMINISTIC_PROOF,
}

/**
 * Owner-bound proof that a non-tutor authority verified an answer source.
 *
 * There is deliberately no generic factory and no tutor/organization-model provenance kind.
 * Production ingress will have to verify one of these exact receipt types before this value can
 * be constructed by the package-private owner bridge.
 */
internal class VerifiedTutorInteractionAnswerProvenance private constructor(
    val kind: TutorInteractionAnswerCertificateProvenanceKind,
    val receiptId: String,
    val receiptCanonicalFingerprint: String,
    val receiptSchemaVersion: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        receiptId.requireStoreText("Tutor certificate provenance receipt id", MAX_ID_CHARS)
        requireSha256(
            receiptCanonicalFingerprint,
            "Tutor certificate provenance receipt fingerprint",
        )
        receiptSchemaVersion.requireStoreText(
            "Tutor certificate provenance receipt schema",
            MAX_VERSION_CHARS,
        )
        require(verifiedAtEpochMillis >= 0L)
    }

    internal val ownerCanonicalFingerprint: String =
        CanonicalSha256("student-tutor-answer-provenance-receipt-v1")
            .field("kind", kind.name)
            .field("receiptId", receiptId)
            .field("receiptCanonicalFingerprint", receiptCanonicalFingerprint)
            .field("receiptSchemaVersion", receiptSchemaVersion)
            .field("verifiedAtEpochMillis", verifiedAtEpochMillis)
            .finish()

    companion object {
        internal fun trustedScoringKey(
            receiptId: String,
            receiptCanonicalFingerprint: String,
            receiptSchemaVersion: String,
            verifiedAtEpochMillis: Long,
            ownerKey: StudentMistakeOwnerKey,
        ): VerifiedTutorInteractionAnswerProvenance =
            create(
                TutorInteractionAnswerCertificateProvenanceKind.TRUSTED_SCORING_KEY,
                receiptId,
                receiptCanonicalFingerprint,
                receiptSchemaVersion,
                verifiedAtEpochMillis,
                ownerKey,
            )

        internal fun independentProviderAttestation(
            receiptId: String,
            receiptCanonicalFingerprint: String,
            receiptSchemaVersion: String,
            verifiedAtEpochMillis: Long,
            ownerKey: StudentMistakeOwnerKey,
        ): VerifiedTutorInteractionAnswerProvenance =
            create(
                TutorInteractionAnswerCertificateProvenanceKind.INDEPENDENT_PROVIDER_ATTESTATION,
                receiptId,
                receiptCanonicalFingerprint,
                receiptSchemaVersion,
                verifiedAtEpochMillis,
                ownerKey,
            )

        internal fun certifiedTeacherAttestation(
            receiptId: String,
            receiptCanonicalFingerprint: String,
            receiptSchemaVersion: String,
            verifiedAtEpochMillis: Long,
            ownerKey: StudentMistakeOwnerKey,
        ): VerifiedTutorInteractionAnswerProvenance =
            create(
                TutorInteractionAnswerCertificateProvenanceKind.CERTIFIED_TEACHER_ATTESTATION,
                receiptId,
                receiptCanonicalFingerprint,
                receiptSchemaVersion,
                verifiedAtEpochMillis,
                ownerKey,
            )

        internal fun userExplicitAnswerConfirmation(
            receiptId: String,
            receiptCanonicalFingerprint: String,
            receiptSchemaVersion: String,
            verifiedAtEpochMillis: Long,
            ownerKey: StudentMistakeOwnerKey,
        ): VerifiedTutorInteractionAnswerProvenance =
            create(
                TutorInteractionAnswerCertificateProvenanceKind.USER_EXPLICIT_ANSWER_CONFIRMATION,
                receiptId,
                receiptCanonicalFingerprint,
                receiptSchemaVersion,
                verifiedAtEpochMillis,
                ownerKey,
            )

        internal fun localDeterministicProof(
            receiptId: String,
            receiptCanonicalFingerprint: String,
            receiptSchemaVersion: String,
            verifiedAtEpochMillis: Long,
            ownerKey: StudentMistakeOwnerKey,
        ): VerifiedTutorInteractionAnswerProvenance =
            create(
                TutorInteractionAnswerCertificateProvenanceKind.LOCAL_DETERMINISTIC_PROOF,
                receiptId,
                receiptCanonicalFingerprint,
                receiptSchemaVersion,
                verifiedAtEpochMillis,
                ownerKey,
            )

        private fun create(
            kind: TutorInteractionAnswerCertificateProvenanceKind,
            receiptId: String,
            receiptCanonicalFingerprint: String,
            receiptSchemaVersion: String,
            verifiedAtEpochMillis: Long,
            ownerKey: StudentMistakeOwnerKey,
        ): VerifiedTutorInteractionAnswerProvenance {
            check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
                "Tutor certificate provenance requires the student-mistake owner"
            }
            return VerifiedTutorInteractionAnswerProvenance(
                kind,
                receiptId,
                receiptCanonicalFingerprint,
                receiptSchemaVersion,
                verifiedAtEpochMillis,
            )
        }
    }
}

internal sealed interface TutorInteractionCorrectAnswerRule {
    val correctAnswerIds: Set<String>
    val canonicalFingerprint: String

    data class Choice(
        val correctAnswerId: String,
    ) : TutorInteractionCorrectAnswerRule {
        override val correctAnswerIds: Set<String> = setOf(correctAnswerId)
        override val canonicalFingerprint: String =
            CanonicalSha256("student-tutor-answer-certificate-choice-rule-v1")
                .field("correctAnswerId", correctAnswerId)
                .finish()
    }

    data class VisualTarget(
        override val correctAnswerIds: Set<String>,
    ) : TutorInteractionCorrectAnswerRule {
        override val canonicalFingerprint: String =
            CanonicalSha256("student-tutor-answer-certificate-visual-rule-v1")
                .field("correctAnswerIds", encodeCanonicalSet(correctAnswerIds))
                .finish()
    }
}

internal data class AdmitTutorInteractionAnswerCertificateCommand(
    val idempotencyKey: String,
    val presentation: TutorInteractionAnswerPresentation,
    val correctRule: TutorInteractionCorrectAnswerRule,
    val provenance: VerifiedTutorInteractionAnswerProvenance,
    val issuedAtEpochMillis: Long,
    val notAfterEpochMillis: Long,
) {
    init {
        idempotencyKey.requireStoreText(
            "Tutor certificate admission idempotency key",
            MAX_ID_CHARS,
        )
        require(issuedAtEpochMillis >= 0L)
        require(notAfterEpochMillis > issuedAtEpochMillis)
        require(notAfterEpochMillis - issuedAtEpochMillis <= TUTOR_CERTIFICATE_MAX_VALIDITY_MILLIS) {
            "Tutor certificate validity exceeds the fresh-admission window"
        }
        require(provenance.verifiedAtEpochMillis <= issuedAtEpochMillis) {
            "Tutor certificate provenance cannot be verified after admission"
        }
        require(correctRule.correctAnswerIds.isNotEmpty())
        require(correctRule.correctAnswerIds.all(presentation.allowedAnswerIds::contains)) {
            "Tutor certificate correct answers must belong to the visible presentation"
        }
        when (presentation.interactionKind) {
            TutorInteractionAnswerKind.CHOICE -> {
                require(correctRule is TutorInteractionCorrectAnswerRule.Choice)
                require(correctRule.correctAnswerIds.size == 1)
            }
            TutorInteractionAnswerKind.VISUAL_TARGET ->
                require(correctRule is TutorInteractionCorrectAnswerRule.VisualTarget)
        }
    }

    val certificateId: String =
        "tutor-answer-certificate:${
            CanonicalSha256("student-tutor-answer-certificate-id-v1")
                .field("learnerId", presentation.problemRevision.problem.learnerId)
                .field("idempotencyKey", idempotencyKey)
                .finish()
        }"

    val ownerCanonicalFingerprint: String =
        CanonicalSha256("student-tutor-answer-certificate-owner-v1")
            .field("certificateId", certificateId)
            .field("idempotencyKey", idempotencyKey)
            .field("presentation", presentation.answerFreeCanonicalFingerprint)
            .field("correctRule", correctRule.canonicalFingerprint)
            .field("provenance", provenance.ownerCanonicalFingerprint)
            .field("issuedAtEpochMillis", issuedAtEpochMillis)
            .field("notAfterEpochMillis", notAfterEpochMillis)
            .finish()

    /** Does not commit to the correct rule or provenance receipt, so it cannot be brute-forced. */
    val admissionReceiptCanonicalFingerprint: String =
        CanonicalSha256("student-tutor-answer-certificate-admission-receipt-v1")
            .field("certificateId", certificateId)
            .field("presentation", presentation.answerFreeCanonicalFingerprint)
            .field("provenanceKind", provenance.kind.name)
            .field("statusGeneration", 1L)
            .field("issuedAtEpochMillis", issuedAtEpochMillis)
            .field("notAfterEpochMillis", notAfterEpochMillis)
            .finish()
}

internal sealed interface AdmitTutorInteractionAnswerCertificateResult {
    data class Admitted(
        val receipt: TutorInteractionAnswerAdmissionReceipt,
    ) : AdmitTutorInteractionAnswerCertificateResult

    data class Duplicate(
        val receipt: TutorInteractionAnswerAdmissionReceipt,
    ) : AdmitTutorInteractionAnswerCertificateResult

    data object Conflict : AdmitTutorInteractionAnswerCertificateResult

    data object Unavailable : AdmitTutorInteractionAnswerCertificateResult
}

internal enum class TutorInteractionAnswerCertificateStatus {
    ADMITTED,
    SUPERSEDED,
    REVOKED,
    EXPIRED,
}

internal data class AppendTutorInteractionAnswerCertificateStatusCommand(
    val certificateId: String,
    val expectedStatusGeneration: Long,
    val status: TutorInteractionAnswerCertificateStatus,
    val reasonCanonicalFingerprint: String,
    val statusPolicyVersion: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        certificateId.requireStoreText("Tutor certificate id", MAX_ID_CHARS)
        require(expectedStatusGeneration > 0L)
        require(status != TutorInteractionAnswerCertificateStatus.ADMITTED) {
            "Only certificate admission may create ADMITTED status"
        }
        requireSha256(reasonCanonicalFingerprint, "Tutor certificate status reason")
        statusPolicyVersion.requireStoreText(
            "Tutor certificate status policy version",
            MAX_VERSION_CHARS,
        )
        require(occurredAtEpochMillis >= 0L)
    }

    val statusGeneration: Long = Math.addExact(expectedStatusGeneration, 1L)
    val statusEventId: String = "$certificateId:status:$statusGeneration"
    val canonicalFingerprint: String =
        CanonicalSha256("student-tutor-answer-certificate-status-v1")
            .field("statusEventId", statusEventId)
            .field("certificateId", certificateId)
            .field("statusGeneration", statusGeneration)
            .field("status", status.name)
            .field("reasonCanonicalFingerprint", reasonCanonicalFingerprint)
            .field("statusPolicyVersion", statusPolicyVersion)
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .finish()
}

internal sealed interface AppendTutorInteractionAnswerCertificateStatusResult {
    data object Appended : AppendTutorInteractionAnswerCertificateStatusResult
    data object Duplicate : AppendTutorInteractionAnswerCertificateStatusResult
    data object Conflict : AppendTutorInteractionAnswerCertificateStatusResult
    data object Unavailable : AppendTutorInteractionAnswerCertificateStatusResult
}

internal interface TutorInteractionAnswerCertificateAdmissionPort {
    suspend fun admit(
        command: AdmitTutorInteractionAnswerCertificateCommand,
    ): AdmitTutorInteractionAnswerCertificateResult

    suspend fun appendStatus(
        command: AppendTutorInteractionAnswerCertificateStatusCommand,
    ): AppendTutorInteractionAnswerCertificateStatusResult
}

internal data class PersistedTutorInteractionAnswerLease(
    val receiptId: String,
    val certificateId: String,
    val presentation: TutorInteractionAnswerPresentation,
    val certificateStatusGeneration: Long,
    val issuedAtEpochMillis: Long,
    val notAfterEpochMillis: Long,
    val canonicalFingerprint: String,
)

internal interface TutorInteractionAnswerCertificatePersistencePort {
    suspend fun readActiveAdmission(
        learnerId: String,
        presentation: TutorInteractionAnswerPresentation,
        nowEpochMillis: Long,
    ): TutorInteractionAnswerAdmissionReceipt?

    suspend fun issueLease(
        learnerId: String,
        presentation: TutorInteractionAnswerPresentation,
        receiptId: String,
        issuedAtEpochMillis: Long,
        requestedNotAfterEpochMillis: Long,
    ): PersistedTutorInteractionAnswerLease?

    suspend fun evaluate(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        response: TutorInteractionAnswerResponse,
        evaluatedAtEpochMillis: Long,
    ): TutorInteractionAnswerEvaluationResult
}
