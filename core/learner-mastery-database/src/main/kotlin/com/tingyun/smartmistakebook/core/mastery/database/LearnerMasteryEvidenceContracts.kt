package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal enum class MasteryEvidenceSourceKind {
    IMPORTED_MISTAKE,
    TUTOR_CHOICE,
    TUTOR_FREE_RESPONSE,
    TUTOR_VISUAL_TARGET,
    TUTOR_SPECIFIC_STUCK,
    SAVED_PROBLEM_REVIEW,
}

internal enum class MasteryEvidenceContextKind {
    SAVED_MISTAKE,
    EPHEMERAL_TUTOR_PROBLEM,
}

internal enum class MasteryEvidenceAuthority {
    LOCAL_VERIFIED,
    DETERMINISTIC_RUBRIC,
    MODEL_REVIEWED,
    SELF_REPORTED,
}

internal enum class MasteryCandidateOrigin {
    MODEL_SCOPED,
    TRUSTED_LOCAL,
}

internal enum class ObservedLearningOutcome {
    CORRECT,
    INCORRECT,
    STUCK,
    VIEWED_ONLY,
    PENDING_REVIEW,
}

internal enum class ObservedAssistance {
    INDEPENDENT,
    ONE_HINT,
    MULTIPLE_HINTS,
    ANSWER_REVEALED,
    UNKNOWN,
}

internal enum class ObservedRetryState {
    FIRST_ATTEMPT,
    ONE_RETRY,
    MULTIPLE_RETRIES,
}

internal data class TrustedReviewAttemptContext(
    val reviewSessionId: String,
    val reviewQueueItemId: String,
    val submissionId: String,
) {
    init {
        requireMasteryIdentity(reviewSessionId, "Review session id")
        requireMasteryIdentity(reviewQueueItemId, "Review queue item id")
        requireMasteryIdentity(submissionId, "Review submission id")
    }

    internal val canonicalFingerprint: String
        get() = CanonicalSha256("learner-mastery-review-attempt-context-v1")
            .field("reviewSessionId", reviewSessionId)
            .field("reviewQueueItemId", reviewQueueItemId)
            .field("submissionId", submissionId)
            .finish()
}

/**
 * Immutable fact from a trusted local source adapter.
 *
 * [presentationId] and [presentationFingerprint] identify one explicit learning episode. Multiple
 * observations from that episode share the pair so the local policy can cap their total effect per
 * knowledge point; adapters must not synthesize a new pair merely to bypass that cap.
 *
 * There is deliberately no numeric weight or mastery field. A model must not be registered as a
 * trusted source adapter; it can only propose the semantic attribution in
 * [IngestLearningObservationCandidateCommand].
 */
internal class IngestLearningSourceFactCommand(
    val sourceFactId: String,
    val learnerId: String,
    val subject: SubjectKind,
    val sourceKind: MasteryEvidenceSourceKind,
    val sourceReferenceId: String,
    val presentationId: String,
    val presentationFingerprint: String,
    val problemFamilyFingerprint: String,
    val evidenceContextKind: MasteryEvidenceContextKind,
    val ephemeralProblemFingerprint: String?,
    val tutorTurnReferenceId: String?,
    val submissionEvidenceFingerprint: String?,
    val attributionModelVersion: String?,
    val authorizedProblemBindingsFingerprint: String?,
    val authorizedKnowledgeRefsFingerprint: String?,
    val knowledgeManifestFingerprint: String?,
    val knowledgeActivationGeneration: Long?,
    val authorityAttemptFingerprint: String? = null,
    val authoritySubmissionFingerprint: String? = null,
    val authorityPresentationFingerprint: String? = null,
    val authorityProblemFamilyFingerprint: String? = null,
    val authorityIdentityVersion: String? = null,
    val problemRevision: StudentProblemRevisionRef?,
    val reviewAttemptContext: TrustedReviewAttemptContext? = null,
    val responseForm: TrustedLearningResponseForm,
    val independentlyAnswered: Boolean,
    val hintCount: Int,
    val answerRevealed: Boolean,
    val elapsedDurationMillis: Long?,
    val verificationKind: TrustedLearningVerification,
    val outcome: ObservedLearningOutcome,
    val assistance: ObservedAssistance,
    val retryState: ObservedRetryState,
    val authority: MasteryEvidenceAuthority,
    val sourcePayloadCanonicalFingerprint: String,
    val occurredAtEpochMillis: Long,
    val attestedAtEpochMillis: Long,
    val idempotencyKey: String,
    val sourcePolicyVersion: String = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
) {
    init {
        requireMasteryIdentity(sourceFactId, "Source fact id")
        requireMasteryIdentity(learnerId, "Learner id")
        requireSpecificSubject(subject)
        requireMasteryIdentity(sourceReferenceId, "Source reference id")
        requireMasteryIdentity(presentationId, "Evidence presentation id")
        requireMasteryFingerprint(presentationFingerprint, "Presentation fingerprint")
        requireMasteryFingerprint(problemFamilyFingerprint, "Problem-family fingerprint")
        require(hintCount in 0..32) {
            "Source-fact hint count is outside the supported range"
        }
        require(elapsedDurationMillis == null || elapsedDurationMillis >= 0L) {
            "Source-fact elapsed duration must not be negative"
        }
        requireMasteryFingerprint(
            sourcePayloadCanonicalFingerprint,
            "Source payload fingerprint",
        )
        require(occurredAtEpochMillis >= 0) {
            "Source-fact occurrence time must not be negative"
        }
        require(attestedAtEpochMillis >= occurredAtEpochMillis) {
            "Source-fact attestation must not precede the observation"
        }
        requireMasteryIdentity(idempotencyKey, "Source-fact idempotency key")
        requireMasteryVersion(sourcePolicyVersion, "Source policy version")
        val authorityIdentityParts =
            listOf(
                authorityAttemptFingerprint,
                authoritySubmissionFingerprint,
                authorityPresentationFingerprint,
                authorityProblemFamilyFingerprint,
                authorityIdentityVersion,
            )
        require(
            authorityIdentityParts.all { it == null } ||
                authorityIdentityParts.all { it != null },
        ) {
            "Authority evidence identity must be complete or absent for a legacy fact"
        }
        authorityAttemptFingerprint?.let {
            requireMasteryFingerprint(it, "Authority attempt fingerprint")
            requireMasteryFingerprint(
                requireNotNull(authoritySubmissionFingerprint),
                "Authority submission fingerprint",
            )
            requireMasteryFingerprint(
                requireNotNull(authorityPresentationFingerprint),
                "Authority presentation fingerprint",
            )
            requireMasteryFingerprint(
                requireNotNull(authorityProblemFamilyFingerprint),
                "Authority problem-family fingerprint",
            )
            requireMasteryVersion(
                requireNotNull(authorityIdentityVersion),
                "Authority evidence identity version",
            )
        }
        problemRevision?.let { revision ->
            require(revision.problem.learnerId == learnerId) {
                "Problem revision and source fact must belong to the same learner"
            }
            require(revision.problem.subject == subject) {
                "Problem revision and source fact must belong to the same subject"
            }
        }
        require(
            (sourceKind == MasteryEvidenceSourceKind.SAVED_PROBLEM_REVIEW) ==
                (reviewAttemptContext != null),
        ) {
            "Only saved-problem review facts may carry review queue context"
        }
        require(reviewAttemptContext == null || problemRevision != null) {
            "Review attempt context requires an immutable problem revision"
        }
        when (evidenceContextKind) {
            MasteryEvidenceContextKind.SAVED_MISTAKE -> {
                require(problemRevision != null) {
                    "Saved-mistake evidence requires an immutable problem revision"
                }
                require(
                    ephemeralProblemFingerprint == null &&
                        tutorTurnReferenceId == null &&
                        submissionEvidenceFingerprint == null &&
                        attributionModelVersion == null &&
                        authorizedKnowledgeRefsFingerprint == null &&
                        knowledgeManifestFingerprint == null &&
                        knowledgeActivationGeneration == null,
                ) {
                    "Saved-mistake evidence cannot carry ephemeral tutor proof"
                }
                if (authorizedProblemBindingsFingerprint == null) {
                    require(
                        sourceKind == MasteryEvidenceSourceKind.SAVED_PROBLEM_REVIEW &&
                            reviewAttemptContext != null,
                    ) {
                        "Saved-mistake evidence requires authorized problem bindings"
                    }
                } else {
                    requireMasteryFingerprint(
                        authorizedProblemBindingsFingerprint,
                        "Authorized problem bindings fingerprint",
                    )
                }
            }
            MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM -> {
                require(
                    problemRevision == null &&
                        reviewAttemptContext == null &&
                        authorizedProblemBindingsFingerprint == null,
                ) {
                    "Ephemeral tutor evidence cannot reference a saved mistake"
                }
                requireMasteryFingerprint(
                    requireNotNull(ephemeralProblemFingerprint),
                    "Ephemeral problem fingerprint",
                )
                requireMasteryIdentity(
                    requireNotNull(tutorTurnReferenceId),
                    "Tutor turn reference id",
                )
                requireMasteryFingerprint(
                    requireNotNull(submissionEvidenceFingerprint),
                    "Submission evidence fingerprint",
                )
                requireMasteryVersion(
                    requireNotNull(attributionModelVersion),
                    "Attribution model version",
                )
                requireMasteryFingerprint(
                    requireNotNull(authorizedKnowledgeRefsFingerprint),
                    "Authorized knowledge references fingerprint",
                )
                if (knowledgeManifestFingerprint == null) {
                    require(knowledgeActivationGeneration == null) {
                        "Knowledge activation generation requires a manifest fingerprint"
                    }
                    require(
                        authorizedKnowledgeRefsFingerprint ==
                            fingerprintAuthorizedKnowledgeRefs(emptyList()),
                    ) {
                        "Only unattributed ephemeral evidence may omit catalog proof"
                    }
                } else {
                    requireMasteryFingerprint(
                        knowledgeManifestFingerprint,
                        "Knowledge manifest fingerprint",
                    )
                    require((knowledgeActivationGeneration ?: 0L) > 0L) {
                        "Knowledge activation generation must be positive"
                    }
                }
            }
        }
    }

    internal val canonicalFingerprint: String
        get() {
            val digest = CanonicalSha256("learner-mastery-source-fact-v1")
                .field("sourceFactId", sourceFactId)
                .field("learnerId", learnerId)
                .field("subject", subject.name)
                .field("sourceKind", sourceKind.name)
                .field("sourceReferenceId", sourceReferenceId)
                .field("presentationId", presentationId)
                .field("presentationFingerprint", presentationFingerprint)
                .field("problemFamilyFingerprint", problemFamilyFingerprint)
                .field("evidenceContextKind", evidenceContextKind.name)
                .nullableField("ephemeralProblemFingerprint", ephemeralProblemFingerprint)
                .nullableField("tutorTurnReferenceId", tutorTurnReferenceId)
                .nullableField(
                    "submissionEvidenceFingerprint",
                    submissionEvidenceFingerprint,
                )
                .nullableField("attributionModelVersion", attributionModelVersion)
                .nullableField(
                    "authorizedProblemBindingsFingerprint",
                    authorizedProblemBindingsFingerprint,
                )
                .nullableField(
                    "authorizedKnowledgeRefsFingerprint",
                    authorizedKnowledgeRefsFingerprint,
                )
                .nullableField("knowledgeManifestFingerprint", knowledgeManifestFingerprint)
                .nullableField(
                    "knowledgeActivationGeneration",
                    knowledgeActivationGeneration?.toString(),
                )
                .nullableField("authorityAttemptFingerprint", authorityAttemptFingerprint)
                .nullableField(
                    "authoritySubmissionFingerprint",
                    authoritySubmissionFingerprint,
                )
                .nullableField(
                    "authorityPresentationFingerprint",
                    authorityPresentationFingerprint,
                )
                .nullableField(
                    "authorityProblemFamilyFingerprint",
                    authorityProblemFamilyFingerprint,
                )
                .nullableField("authorityIdentityVersion", authorityIdentityVersion)
                .nullableField("problemRevision", problemRevision?.canonicalFingerprint)
                .nullableField("reviewAttemptContext", reviewAttemptContext?.canonicalFingerprint)
                .field("responseForm", responseForm.name)
                .field("independentlyAnswered", independentlyAnswered)
                .field("hintCount", hintCount)
                .field("answerRevealed", answerRevealed)
                .nullableField("elapsedDurationMillis", elapsedDurationMillis?.toString())
                .field("verificationKind", verificationKind.name)
                .field("outcome", outcome.name)
                .field("assistance", assistance.name)
                .field("retryState", retryState.name)
                .field("authority", authority.name)
                .field("sourcePayloadFingerprint", sourcePayloadCanonicalFingerprint)
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .field("attestedAtEpochMillis", attestedAtEpochMillis)
                .field("sourcePolicyVersion", sourcePolicyVersion)
            return digest.finish()
        }
}

internal enum class MasteryInboundDisposition {
    APPLIED,
    DUPLICATE,
    CONFLICT,
}

internal enum class MigrationCheckpointWriteDisposition {
    STORED,
    DUPLICATE,
    CONFLICT,
}

internal enum class LearningSourceFactIngestStatus {
    STORED,
    STORED_INERT,
    DUPLICATE,
    CONFLICT,
}

internal data class LearningSourceFactIngestResult(
    val sourceFactId: String,
    val status: LearningSourceFactIngestStatus,
)

internal enum class PendingOpenResponsePersistenceStatus {
    QUEUED,
    DUPLICATE,
    CONFLICT,
}

internal data class PendingOpenResponsePersistenceResult(
    val sourceFactId: String,
    val reviewCaseId: String?,
    val status: PendingOpenResponsePersistenceStatus,
) {
    init {
        require(
            (status == PendingOpenResponsePersistenceStatus.QUEUED ||
                status == PendingOpenResponsePersistenceStatus.DUPLICATE) ==
                (reviewCaseId != null),
        ) {
            "Only persisted pending responses may expose a review-case id"
        }
    }
}

enum class MasteryCandidateConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

enum class MasteryAttributionRole {
    PRIMARY,
    SUPPORTING,
    CONTEXT,
}

enum class MasteryAttributionCertainty {
    DIRECT,
    INFERRED,
}

data class ProposedKnowledgeAttribution(
    val knowledgeNode: KnowledgeNodeRef,
    val problemBinding: ProblemKnowledgeBindingRef?,
    val role: MasteryAttributionRole,
    val certainty: MasteryAttributionCertainty,
) {
    init {
        problemBinding?.let { binding ->
            require(binding.knowledgeNode == knowledgeNode) {
                "Problem binding and proposed knowledge node must match exactly"
            }
        }
    }

    internal val canonicalFingerprint: String
        get() = CanonicalSha256("learner-mastery-attribution-proposal-v1")
            .field("knowledgeNode", knowledgeNode.canonicalFingerprint)
            .nullableField("problemBinding", problemBinding?.canonicalFingerprint)
            .field("role", role.name)
            .field("certainty", certainty.name)
            .finish()
}

/**
 * Semantic model proposal. Numeric evidence mass, posterior values, status and timestamps are
 * intentionally absent and are computed by a fixed local policy.
 */
internal class IngestLearningObservationCandidateCommand(
    val candidateId: String,
    val learnerId: String,
    val subject: SubjectKind,
    val sourceFactId: String,
    proposedAttributions: List<ProposedKnowledgeAttribution>,
    val confidence: MasteryCandidateConfidence,
    val modelVersion: String,
    val idempotencyKey: String,
    val proposedAtEpochMillis: Long,
    val requestedPolicyVersion: String = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
    val candidateOrigin: MasteryCandidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED,
) {
    val proposedAttributions: List<ProposedKnowledgeAttribution> =
        Collections.unmodifiableList(proposedAttributions.toList())

    init {
        requireMasteryIdentity(candidateId, "Observation candidate id")
        requireMasteryIdentity(learnerId, "Learner id")
        requireSpecificSubject(subject)
        requireMasteryIdentity(sourceFactId, "Source fact id")
        require(proposedAttributions.size <= MAX_ATTRIBUTIONS) {
            "Observation candidate exceeds the attribution budget"
        }
        require(
            proposedAttributions
                .map { it.knowledgeNode.canonicalFingerprint }
                .distinct()
                .size == proposedAttributions.size,
        ) {
            "Observation candidate may attribute each knowledge node at most once"
        }
        requireMasteryVersion(modelVersion, "Attribution model version")
        requireMasteryIdentity(idempotencyKey, "Candidate idempotency key")
        require(proposedAtEpochMillis >= 0) {
            "Candidate proposal time must not be negative"
        }
        requireMasteryVersion(requestedPolicyVersion, "Projection policy version")
    }

    internal val canonicalAttributions: List<ProposedKnowledgeAttribution>
        get() = proposedAttributions.sortedBy { it.knowledgeNode.canonicalFingerprint }

    internal val canonicalFingerprint: String
        get() {
            val digest = CanonicalSha256("learner-mastery-candidate-v1")
                .field("candidateId", candidateId)
                .field("learnerId", learnerId)
                .field("subject", subject.name)
                .field("sourceFactId", sourceFactId)
                .field("confidence", confidence.name)
                .field("modelVersion", modelVersion)
                .field("requestedPolicyVersion", requestedPolicyVersion)
                .field("attributionCount", canonicalAttributions.size)
            canonicalAttributions.forEachIndexed { index, attribution ->
                digest.field("attribution[$index]", attribution.canonicalFingerprint)
            }
            return digest.finish()
        }

    private companion object {
        const val MAX_ATTRIBUTIONS = 32
    }
}

enum class LearningObservationDisposition {
    ADMITTED,
    DUPLICATE,
    INERT,
    CONFLICT,
}

enum class LearningObservationInertReason {
    MISSING_SOURCE_PROOF,
    MISSING_LEARNING_EPISODE,
    UNSUPPORTED_SOURCE_POLICY,
    UNSUPPORTED_PROJECTION_POLICY,
    LOW_CONFIDENCE,
    UNVERIFIABLE_SOURCE,
    MODEL_ONLY_OPEN_RESPONSE,
    ANSWER_ALREADY_REVEALED,
    NO_LEARNING_OUTCOME,
    NO_ATTRIBUTION,
    UNLOCALIZED_MULTI_KNOWLEDGE_NEGATIVE,
    LEARNER_MISMATCH,
    SUBJECT_MISMATCH,
    PROBLEM_BINDING_REQUIRED,
    PROBLEM_BINDING_MISMATCH,
    PROBLEM_BINDING_NOT_AUTHORIZED,
    VERIFIED_KNOWLEDGE_REQUIRED,
    WEAK_CONFLICT_REQUIRES_REVIEW,
    PRESENTATION_EVIDENCE_BUDGET_EXHAUSTED,
    DIRECTIONAL_BUDGET_REBUILD_PENDING,
    CALIBRATION_BINDING_CONFLICT,
    IDEMPOTENCY_CONFLICT,
}

class LearningObservationTerminalReceipt private constructor(
    val candidateId: String,
    val candidateCanonicalFingerprint: String,
    val disposition: LearningObservationDisposition,
    val inertReason: LearningObservationInertReason?,
    val receiptFingerprint: String,
) {
    init {
        requireMasteryIdentity(candidateId, "Observation candidate id")
        requireMasteryFingerprint(
            candidateCanonicalFingerprint,
            "Observation candidate fingerprint",
        )
        requireMasteryFingerprint(receiptFingerprint, "Admission receipt fingerprint")
        require(
            disposition == LearningObservationDisposition.ADMITTED ||
                disposition == LearningObservationDisposition.INERT,
        ) {
            "A terminal admission receipt must describe an admitted or inert candidate"
        }
        require(
            (disposition == LearningObservationDisposition.INERT) == (inertReason != null),
        ) {
            "Only an inert terminal receipt may expose an inert reason"
        }
    }

    internal companion object {
        fun create(
            candidateId: String,
            candidateCanonicalFingerprint: String,
            disposition: LearningObservationDisposition,
            inertReason: LearningObservationInertReason?,
            receiptFingerprint: String,
        ): LearningObservationTerminalReceipt =
            LearningObservationTerminalReceipt(
                candidateId = candidateId,
                candidateCanonicalFingerprint = candidateCanonicalFingerprint,
                disposition = disposition,
                inertReason = inertReason,
                receiptFingerprint = receiptFingerprint,
            )
    }
}

data class LearningObservationIngestResult(
    val candidateId: String,
    val disposition: LearningObservationDisposition,
    val inertReason: LearningObservationInertReason? = null,
    internal val terminalReceipt: LearningObservationTerminalReceipt? = null,
) {
    val retryable: Boolean
        get() = inertReason == LearningObservationInertReason.DIRECTIONAL_BUDGET_REBUILD_PENDING

    init {
        require(
            (disposition == LearningObservationDisposition.INERT ||
                disposition == LearningObservationDisposition.CONFLICT) ==
                (inertReason != null),
        ) {
            "Only inert or conflicting observations may expose an inert reason"
        }
        terminalReceipt?.let { receipt ->
            require(receipt.candidateId == candidateId) {
                "Admission receipt belongs to another observation candidate"
            }
            when (disposition) {
                LearningObservationDisposition.ADMITTED ->
                    require(receipt.disposition == LearningObservationDisposition.ADMITTED)
                LearningObservationDisposition.INERT ->
                    require(
                        receipt.disposition == LearningObservationDisposition.INERT &&
                            receipt.inertReason == inertReason,
                    )
                LearningObservationDisposition.DUPLICATE -> Unit
                LearningObservationDisposition.CONFLICT ->
                    error("A conflicting candidate cannot expose an admission receipt")
            }
        }
    }
}

enum class LearningEvidenceReviewDecision {
    ACCEPT,
    REJECT,
}

/**
 * A reviewer can attest a decision, but it still cannot supply a weight, direction, or mastery
 * value. Independent model review is accepted only through this host-owned capability.
 */
enum class LearningEvidenceReviewAuthority {
    TRUSTED_LOCAL_RULE,
    INDEPENDENT_MODEL_REVIEW,
}

class ResolveLearningEvidenceReviewCommand(
    val reviewCaseId: String,
    val decision: LearningEvidenceReviewDecision,
    val authority: LearningEvidenceReviewAuthority,
    val reviewerVersion: String,
    val reviewEvidenceFingerprint: String,
    val idempotencyKey: String,
    val decidedAtEpochMillis: Long,
) {
    init {
        requireMasteryOpaqueReference(reviewCaseId, "Evidence review case id")
        requireMasteryVersion(reviewerVersion, "Evidence reviewer version")
        requireMasteryFingerprint(reviewEvidenceFingerprint, "Review evidence fingerprint")
        requireMasteryOpaqueReference(idempotencyKey, "Evidence review idempotency key")
        require(decidedAtEpochMillis >= 0L) {
            "Evidence review decision time must not be negative"
        }
    }
}

enum class LearningEvidenceReviewWriteDisposition {
    APPLIED,
    DUPLICATE,
    REBUILD_PENDING,
    CONFLICT,
    NOT_FOUND,
}

data class LearningEvidenceReviewWriteResult(
    val reviewCaseId: String,
    val disposition: LearningEvidenceReviewWriteDisposition,
)

data class PendingLearningEvidenceReview(
    val reviewCaseId: String,
    val subject: SubjectKind,
    val sourceFactId: String,
    val candidateId: String,
    val createdAtEpochMillis: Long,
)

interface LearnerMasteryEvidenceReviewCapability {
    suspend fun readPending(
        subject: SubjectKind,
        limit: Int = DEFAULT_LIMIT,
    ): List<PendingLearningEvidenceReview>

    suspend fun resolve(
        command: ResolveLearningEvidenceReviewCommand,
    ): LearningEvidenceReviewWriteResult

    companion object {
        const val DEFAULT_LIMIT = 32
        const val MAX_LIMIT = 128
    }
}

enum class LearningEvidenceCorrectionAuthority {
    TRUSTED_LOCAL_RULE,
    INDEPENDENT_MODEL_REVIEW,
}

/**
 * Host-authorized replacement of one already admitted observation.
 *
 * The command identifies the immutable source fact being corrected and supplies a new bounded
 * observation. It deliberately has no direction, evidence mass, mastery score, or projection
 * value; those remain fixed local policy.
 */
class CorrectLearningEvidenceCommand(
    val subject: SubjectKind,
    val originalSourceFactCanonicalFingerprint: String,
    val replacementObservation: LearningObservationFacts,
    val authority: LearningEvidenceCorrectionAuthority,
    val authorityVersion: String,
    val correctionEvidenceFingerprint: String,
    val idempotencyKey: String,
    val correctedAtEpochMillis: Long,
) {
    init {
        requireSpecificSubject(subject)
        require(replacementObservation.subject == subject) {
            "Evidence correction and replacement observation must share one subject"
        }
        requireMasteryFingerprint(
            originalSourceFactCanonicalFingerprint,
            "Original source-fact fingerprint",
        )
        requireMasteryVersion(authorityVersion, "Evidence correction authority version")
        requireMasteryFingerprint(
            correctionEvidenceFingerprint,
            "Evidence correction fingerprint",
        )
        requireMasteryOpaqueReference(idempotencyKey, "Evidence correction idempotency key")
        require(correctedAtEpochMillis >= 0L) {
            "Evidence correction time must not be negative"
        }
        require(correctedAtEpochMillis >= replacementObservation.attestedAtEpochMillis) {
            "Evidence correction cannot precede the replacement attestation"
        }
    }

    internal val canonicalFingerprint: String
        get() = CanonicalSha256("learner-mastery-evidence-correction-command-v1")
            .field("subject", subject.name)
            .field(
                "originalSourceFactFingerprint",
                originalSourceFactCanonicalFingerprint,
            )
            .field("replacementObservation", replacementObservation.canonicalFingerprint)
            .field("authority", authority.name)
            .field("authorityVersion", authorityVersion)
            .field("correctionEvidenceFingerprint", correctionEvidenceFingerprint)
            .field("correctedAtEpochMillis", correctedAtEpochMillis)
            .finish()
}

enum class LearningEvidenceCorrectionDisposition {
    APPLIED,
    DUPLICATE,
    REBUILD_PENDING,
    REJECTED,
}

data class LearningEvidenceCorrectionResult(
    val replacementObservationId: String,
    val replacementSourceFactCanonicalFingerprint: String?,
    val disposition: LearningEvidenceCorrectionDisposition,
) {
    init {
        if (
            disposition == LearningEvidenceCorrectionDisposition.REJECTED ||
            disposition == LearningEvidenceCorrectionDisposition.REBUILD_PENDING
        ) {
            require(replacementSourceFactCanonicalFingerprint == null) {
                "Rejected evidence correction must not expose a replacement ledger head"
            }
        } else {
            requireMasteryFingerprint(
                requireNotNull(replacementSourceFactCanonicalFingerprint),
                "Replacement source-fact fingerprint",
            )
        }
    }
}

/**
 * Learner-bound owner capability. It is intentionally separate from [LearnerMasteryModelAccess]:
 * a model may propose a review, but cannot supersede ledger evidence directly.
 */
fun interface LearnerMasteryEvidenceCorrectionCapability {
    suspend fun correct(
        command: CorrectLearningEvidenceCommand,
    ): LearningEvidenceCorrectionResult
}

