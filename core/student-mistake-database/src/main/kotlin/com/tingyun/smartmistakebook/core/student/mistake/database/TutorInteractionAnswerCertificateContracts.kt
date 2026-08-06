package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.util.IdentityHashMap

/** Interaction forms whose answer can be checked locally without model judgement. */
enum class TutorInteractionAnswerKind {
    CHOICE,
    VISUAL_TARGET,
}

/** Exact visible presentation for which an answer certificate may be used. */
data class TutorInteractionAnswerPresentation(
    val problemRevision: StudentProblemRevisionRef,
    val questionDocumentFingerprint: String,
    val sessionId: String,
    val modelTaskRequestId: String,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val interactionKind: TutorInteractionAnswerKind,
    val presentationCanonicalFingerprint: String,
    val allowedAnswerIds: Set<String>,
    val certificateSchemaVersion: String,
    val admissionPolicyVersion: String,
) {
    init {
        requireSha256(questionDocumentFingerprint, "Tutor certificate question fingerprint")
        require(questionDocumentFingerprint == problemRevision.documentCanonicalFingerprint) {
            "Tutor certificate question fingerprint must match the problem revision"
        }
        sessionId.requireStoreText("Tutor certificate session id", MAX_ID_CHARS)
        modelTaskRequestId.requireStoreText(
            "Tutor certificate model-task request id",
            MAX_ID_CHARS,
        )
        require(cycleOrdinal > 0) { "Tutor certificate cycle must be positive" }
        require(turnOrdinal > 0) { "Tutor certificate turn must be positive" }
        require(modeVersion >= 0) { "Tutor certificate mode version must not be negative" }
        require(learningWritePermissionVersion >= 0) {
            "Tutor certificate learning-write permission version must not be negative"
        }
        requireSha256(
            presentationCanonicalFingerprint,
            "Tutor certificate presentation fingerprint",
        )
        require(allowedAnswerIds.all(String::isTutorCertificateAnswerId)) {
            "Tutor certificate contains an invalid answer id"
        }
        when (interactionKind) {
            TutorInteractionAnswerKind.CHOICE ->
                require(allowedAnswerIds.size in 2..4) {
                    "Tutor certificate choice presentation must contain two to four choices"
                }
            TutorInteractionAnswerKind.VISUAL_TARGET ->
                require(allowedAnswerIds.size in 1..MAX_TUTOR_CERTIFICATE_VISUAL_TARGETS) {
                    "Tutor certificate visual presentation has an invalid target count"
                }
        }
        certificateSchemaVersion.requireStoreText(
            "Tutor certificate schema version",
            MAX_VERSION_CHARS,
        )
        admissionPolicyVersion.requireStoreText(
            "Tutor certificate admission policy version",
            MAX_VERSION_CHARS,
        )
    }

    internal val allowedAnswerIdsWire: String = encodeCanonicalSet(allowedAnswerIds)

    internal val familyCanonicalFingerprint: String =
        CanonicalSha256("student-tutor-answer-certificate-family-v1")
            .field("learnerId", problemRevision.problem.learnerId)
            .field("problemId", problemRevision.problem.problemId)
            .field("problemRevisionId", problemRevision.revisionId)
            .field("questionDocumentFingerprint", questionDocumentFingerprint)
            .field("sessionId", sessionId)
            .field("cycleOrdinal", cycleOrdinal)
            .field("turnOrdinal", turnOrdinal)
            .field("modeVersion", modeVersion)
            .field("learningWritePermissionVersion", learningWritePermissionVersion)
            .field("interactionKind", interactionKind.name)
            .field("presentationCanonicalFingerprint", presentationCanonicalFingerprint)
            .field("allowedAnswerIds", allowedAnswerIdsWire)
            .field("certificateSchemaVersion", certificateSchemaVersion)
            .field("admissionPolicyVersion", admissionPolicyVersion)
            .finish()

    internal val answerFreeCanonicalFingerprint: String =
        CanonicalSha256("student-tutor-answer-certificate-presentation-v1")
            .field("family", familyCanonicalFingerprint)
            .field("modelTaskRequestId", modelTaskRequestId)
            .finish()
}

sealed interface TutorInteractionAnswerResponse {
    val answerId: String

    data class Choice(
        override val answerId: String,
    ) : TutorInteractionAnswerResponse {
        init {
            require(answerId.isTutorCertificateAnswerId()) {
                "Tutor certificate choice response is invalid"
            }
        }
    }

    data class VisualTarget(
        override val answerId: String,
    ) : TutorInteractionAnswerResponse {
        init {
            require(answerId.isTutorCertificateAnswerId()) {
                "Tutor certificate visual-target response is invalid"
            }
        }
    }
}

/** Answer-free proof that the owner admitted one exact presentation. */
data class TutorInteractionAnswerAdmissionReceipt internal constructor(
    val certificateId: String,
    val presentation: TutorInteractionAnswerPresentation,
    val statusGeneration: Long,
    val issuedAtEpochMillis: Long,
    val notAfterEpochMillis: Long,
    val admissionCanonicalFingerprint: String,
) {
    init {
        certificateId.requireStoreText("Tutor certificate id", MAX_ID_CHARS)
        require(statusGeneration > 0L)
        require(issuedAtEpochMillis >= 0L)
        require(notAfterEpochMillis >= issuedAtEpochMillis)
        requireSha256(admissionCanonicalFingerprint, "Tutor certificate admission receipt")
    }
}

/** Process-local capability for one exact, short-lived certified presentation. */
class TutorInteractionAnswerLease internal constructor(
    val certificateId: String,
    val presentation: TutorInteractionAnswerPresentation,
    val certificateStatusGeneration: Long,
    val issuedAtEpochMillis: Long,
    val notAfterEpochMillis: Long,
    val canonicalFingerprint: String,
    internal val receiptId: String,
) {
    init {
        certificateId.requireStoreText("Tutor certificate id", MAX_ID_CHARS)
        require(certificateStatusGeneration > 0L)
        require(issuedAtEpochMillis >= 0L)
        require(notAfterEpochMillis >= issuedAtEpochMillis)
        requireSha256(canonicalFingerprint, "Tutor certificate lease fingerprint")
        receiptId.requireStoreText("Tutor certificate lease receipt id", MAX_ID_CHARS)
    }
}

data class TutorInteractionAnswerEvaluationReceipt(
    val responseWasCorrect: Boolean,
    val duplicate: Boolean,
    val evaluatedAtEpochMillis: Long,
    val canonicalFingerprint: String,
) {
    init {
        require(evaluatedAtEpochMillis >= 0L)
        requireSha256(canonicalFingerprint, "Tutor certificate evaluation receipt")
    }
}

sealed interface TutorInteractionAnswerEvaluationResult {
    data class Recorded(
        val receipt: TutorInteractionAnswerEvaluationReceipt,
    ) : TutorInteractionAnswerEvaluationResult

    data object Conflict : TutorInteractionAnswerEvaluationResult

    data object ReloadRequired : TutorInteractionAnswerEvaluationResult

    data object StorageUnavailable : TutorInteractionAnswerEvaluationResult
}

interface LearnerBoundTutorInteractionAnswerCertificatePort {
    val learnerId: String

    /** Returns no correct-answer material, provenance receipt, or owner fingerprint. */
    suspend fun readActiveAdmission(
        presentation: TutorInteractionAnswerPresentation,
    ): TutorInteractionAnswerAdmissionReceipt?

    /** Issues a short-lived exact lease only while the latest certificate status is ADMITTED. */
    suspend fun issueLease(
        presentation: TutorInteractionAnswerPresentation,
    ): TutorInteractionAnswerLease?

    /** Compares a constrained response inside the owner and returns only the resulting fact. */
    suspend fun evaluate(
        lease: TutorInteractionAnswerLease,
        response: TutorInteractionAnswerResponse,
    ): TutorInteractionAnswerEvaluationResult
}

internal class TutorInteractionAnswerCertificateOwner(
    override val learnerId: String,
    private val persistence: TutorInteractionAnswerCertificatePersistencePort,
    private val nowEpochMillis: () -> Long,
    private val newLeaseReceiptId: () -> String,
    private val leaseValidityMillis: Long = TUTOR_CERTIFICATE_LEASE_VALIDITY_MILLIS,
) : LearnerBoundTutorInteractionAnswerCertificatePort {
    private val leaseAuthority = TutorInteractionAnswerLeaseAuthority()

    init {
        learnerId.requireStoreText("Tutor certificate learner id", MAX_ID_CHARS)
        require(leaseValidityMillis in 1..TUTOR_CERTIFICATE_MAX_VALIDITY_MILLIS)
    }

    override suspend fun readActiveAdmission(
        presentation: TutorInteractionAnswerPresentation,
    ): TutorInteractionAnswerAdmissionReceipt? {
        if (presentation.problemRevision.problem.learnerId != learnerId) return null
        return persistence.readActiveAdmission(learnerId, presentation, nowEpochMillis())
    }

    override suspend fun issueLease(
        presentation: TutorInteractionAnswerPresentation,
    ): TutorInteractionAnswerLease? {
        if (presentation.problemRevision.problem.learnerId != learnerId) return null
        val now = nowEpochMillis().also(::requireTutorCertificateTime)
        val requestedNotAfter = runCatching { Math.addExact(now, leaseValidityMillis) }.getOrNull()
            ?: return null
        val receiptId = newLeaseReceiptId().also {
            it.requireStoreText("Tutor certificate lease receipt id", MAX_ID_CHARS)
        }
        val persisted = persistence.issueLease(
            learnerId = learnerId,
            presentation = presentation,
            receiptId = receiptId,
            issuedAtEpochMillis = now,
            requestedNotAfterEpochMillis = requestedNotAfter,
        ) ?: return null
        return leaseAuthority.issue(persisted)
    }

    override suspend fun evaluate(
        lease: TutorInteractionAnswerLease,
        response: TutorInteractionAnswerResponse,
    ): TutorInteractionAnswerEvaluationResult {
        val receiptId = leaseAuthority.verify(lease)
            ?: return TutorInteractionAnswerEvaluationResult.ReloadRequired
        val result = runCatching {
            persistence.evaluate(
                learnerId = learnerId,
                leaseReceiptId = receiptId,
                leaseCanonicalFingerprint = lease.canonicalFingerprint,
                response = response,
                evaluatedAtEpochMillis = nowEpochMillis().also(::requireTutorCertificateTime),
            )
        }.getOrElse {
            TutorInteractionAnswerEvaluationResult.StorageUnavailable
        }
        if (result !is TutorInteractionAnswerEvaluationResult.Recorded || !result.receipt.duplicate) {
            leaseAuthority.revoke(lease)
        }
        return result
    }
}

private class TutorInteractionAnswerLeaseAuthority {
    private val issued = IdentityHashMap<TutorInteractionAnswerLease, String>()

    @Synchronized
    fun issue(value: PersistedTutorInteractionAnswerLease): TutorInteractionAnswerLease =
        TutorInteractionAnswerLease(
            certificateId = value.certificateId,
            presentation = value.presentation,
            certificateStatusGeneration = value.certificateStatusGeneration,
            issuedAtEpochMillis = value.issuedAtEpochMillis,
            notAfterEpochMillis = value.notAfterEpochMillis,
            canonicalFingerprint = value.canonicalFingerprint,
            receiptId = value.receiptId,
        ).also { lease -> check(issued.put(lease, value.receiptId) == null) }

    @Synchronized
    fun verify(lease: TutorInteractionAnswerLease): String? =
        issued[lease]?.takeIf(lease.receiptId::equals)

    @Synchronized
    fun revoke(lease: TutorInteractionAnswerLease) {
        issued.remove(lease)
    }
}

internal fun TutorInteractionAnswerResponse.matches(kind: TutorInteractionAnswerKind): Boolean =
    when (kind) {
        TutorInteractionAnswerKind.CHOICE -> this is TutorInteractionAnswerResponse.Choice
        TutorInteractionAnswerKind.VISUAL_TARGET ->
            this is TutorInteractionAnswerResponse.VisualTarget
    }

internal fun TutorInteractionAnswerResponse.canonicalFingerprint(
    kind: TutorInteractionAnswerKind,
): String? =
    takeIf { it.matches(kind) }?.let {
        CanonicalSha256("student-tutor-answer-certificate-response-v1")
            .field("interactionKind", kind.name)
            .field("answerId", answerId)
            .finish()
    }

private fun String.isTutorCertificateAnswerId(): Boolean =
    isNotBlank() && this == trim() && length <= MAX_TUTOR_CERTIFICATE_ANSWER_ID_CHARS &&
        none(Char::isISOControl)

private fun requireTutorCertificateTime(value: Long) {
    require(value >= 0L) { "Tutor certificate owner time must not be negative" }
}

internal const val TUTOR_CERTIFICATE_LEASE_VALIDITY_MILLIS = 2L * 60L * 1_000L
internal const val TUTOR_CERTIFICATE_MAX_VALIDITY_MILLIS = 2L * 60L * 1_000L
private const val MAX_TUTOR_CERTIFICATE_VISUAL_TARGETS = 64
private const val MAX_TUTOR_CERTIFICATE_ANSWER_ID_CHARS = 256
