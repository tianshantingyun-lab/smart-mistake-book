package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseKnowledgeRole
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.util.Collections

/** Model-evaluated open answers are audit/review material, never automatic mastery evidence. */
enum class LearnerMasteryOpenResponseAdmissionMode {
    REVIEW_ONLY,
}

/**
 * Host-owned context for attaching one independently evaluated open response to an already
 * persisted, direction-free pending candidate.
 *
 * This command carries the full host-verified catalog scope but intentionally has no confidence,
 * evidence weight, direction, event, projection, SQL, answer body, or chat body. The model may
 * qualify references inside that exact scope; only local policy can decide what knowledge changes.
 */
class LearnerMasteryOpenResponseWeakCandidateCommand(
    val learnerId: String,
    val subject: SubjectKind,
    val sourceFactId: String,
    val reviewCaseId: String,
    val scopeFingerprint: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val questionFingerprint: String,
    /** Claim-scoped opaque HMAC; never a public digest of the student's answer. */
    val responseBinding: String,
    val evidenceRequestId: String,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val modeVersion: Long,
    val requestVersion: Long,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val modelTaskRequestId: String,
    val modelResponseSchemaVersion: Int,
    val evaluatorRequestVersion: Long,
    val candidateIdempotencyKey: String,
    val revisionOfCandidateIdempotencyKey: String?,
    val evidenceFingerprint: String,
    val modelOutputFingerprint: String,
    val modelVersion: String,
    val outcome: OpenResponseEvaluationOutcome,
    authorizedKnowledgeScope: List<LearnerMasteryOpenResponseKnowledgeScopeEntry>,
    val occurredAtEpochMillis: Long,
    /**
     * Host-owned and absent from all model protocols and tool inputs. It is deliberately excluded
     * from the legacy command fingerprint: promotion requires another non-model command instead
     * of reinterpreting an already persisted semantic-review receipt.
     */
    val admissionMode: LearnerMasteryOpenResponseAdmissionMode =
        LearnerMasteryOpenResponseAdmissionMode.REVIEW_ONLY,
) {
    val authorizedKnowledgeScope: List<LearnerMasteryOpenResponseKnowledgeScopeEntry> =
        Collections.unmodifiableList(
            authorizedKnowledgeScope.sortedBy(
                LearnerMasteryOpenResponseKnowledgeScopeEntry::refFingerprint,
            ),
        )

    init {
        requireMasteryIdentity(learnerId, "Open-response learner id")
        requireSpecificSubject(subject)
        requireMasteryOpaqueReference(sourceFactId, "Open-response source-fact id")
        requireMasteryOpaqueReference(reviewCaseId, "Open-response review-case id")
        requireMasteryFingerprint(scopeFingerprint, "Open-response scope fingerprint")
        requireMasteryOpaqueReference(conversationId, "Open-response conversation id")
        require(conversationGeneration >= 0L) {
            "Open-response conversation generation must not be negative"
        }
        require(conversationStateVersion >= 0L) {
            "Open-response conversation state version must not be negative"
        }
        requireMasteryOpaqueReference(questionDocumentId, "Open-response question document id")
        require(questionRevisionNumber > 0) {
            "Open-response question revision must be positive"
        }
        requireMasteryFingerprint(questionFingerprint, "Open-response question fingerprint")
        requireMasteryFingerprint(responseBinding, "Open-response response binding")
        requireMasteryOpaqueReference(evidenceRequestId, "Open-response evidence request id")
        requireMasteryOpaqueReference(turnReferenceId, "Open-response turn reference id")
        require(turnOrdinal >= 0) {
            "Open-response turn ordinal must not be negative"
        }
        require(turnGeneration >= 0L) {
            "Open-response turn generation must not be negative"
        }
        require(modeVersion >= 0L) {
            "Open-response mode version must not be negative"
        }
        require(requestVersion >= 0L) {
            "Open-response request version must not be negative"
        }
        require(attemptOrdinal in 1..MAX_ATTEMPT_ORDINAL) {
            "Open-response attempt ordinal is outside the supported range"
        }
        require(hintCount in 0..MAX_HINT_COUNT) {
            "Open-response hint count is outside the supported range"
        }
        requireMasteryOpaqueReference(modelTaskRequestId, "Open-response model task request id")
        require(modelResponseSchemaVersion > 0) {
            "Open-response model response schema version must be positive"
        }
        require(evaluatorRequestVersion == requestVersion) {
            "Open-response evaluator request version must match the current request"
        }
        requireMasteryFingerprint(
            candidateIdempotencyKey,
            "Open-response candidate idempotency key",
        )
        revisionOfCandidateIdempotencyKey?.let { fingerprint ->
            requireMasteryFingerprint(
                fingerprint,
                "Open-response revised candidate idempotency key",
            )
            require(fingerprint != candidateIdempotencyKey) {
                "Open-response candidate cannot revise itself"
            }
        }
        requireMasteryFingerprint(evidenceFingerprint, "Open-response evidence fingerprint")
        requireMasteryFingerprint(
            modelOutputFingerprint,
            "Open-response model-output fingerprint",
        )
        requireMasteryVersion(modelVersion, "Open-response evaluator model version")
        require(outcome != OpenResponseEvaluationOutcome.UNSCORABLE) {
            "Unscorable output must remain a pending fact, not a weak candidate admission"
        }
        require(occurredAtEpochMillis >= 0L) {
            "Open-response occurrence time must not be negative"
        }
        require(this.authorizedKnowledgeScope.size <= MAX_KNOWLEDGE_SCOPE) {
            "Open-response authorized knowledge scope exceeds its budget"
        }
        require(
            this.authorizedKnowledgeScope
                .map(LearnerMasteryOpenResponseKnowledgeScopeEntry::refFingerprint)
                .distinct()
                .size == this.authorizedKnowledgeScope.size,
        ) {
            "Open-response authorized knowledge references must be unique"
        }
        require(
            this.authorizedKnowledgeScope
                .map { it.knowledgeNode.canonicalFingerprint }
                .distinct()
                .size == this.authorizedKnowledgeScope.size,
        ) {
            "Open-response authorized knowledge nodes must be unique"
        }
        require(this.authorizedKnowledgeScope.all { it.knowledgeNode.subject == subject }) {
            "Open-response authorized knowledge crossed its subject boundary"
        }
        require(
            this.authorizedKnowledgeScope
                .map { it.manifestFingerprint to it.activationGeneration }
                .distinct()
                .size <= 1,
        ) {
            "Open-response authorized knowledge must use one catalog activation"
        }
        require(
            this.authorizedKnowledgeScope.all { entry ->
                entry.refFingerprint ==
                    OpenResponseEvaluationTaskFingerprints.knowledgeScopeReference(
                        questionFingerprint = questionFingerprint,
                        knowledgeNodeReferenceFingerprint =
                            entry.knowledgeNode.canonicalFingerprint,
                        knowledgeManifestFingerprint = entry.manifestFingerprint,
                        knowledgeActivationGeneration = entry.activationGeneration,
                    )
            },
        ) {
            "Open-response authorized knowledge mapping is not canonical"
        }
        require(scopeFingerprint == derivedScopeFingerprint) {
            "Open-response scope fingerprint does not match its host-owned fields"
        }
    }

    internal val derivedScopeFingerprint: String
        get() =
            CanonicalSha256("current-open-response-learning-scope-v1")
                .field("learnerId", learnerId)
                .field("conversationId", conversationId)
                .field("conversationGeneration", conversationGeneration)
                .field("conversationStateVersion", conversationStateVersion)
                .field("questionDocumentId", questionDocumentId)
                .field("questionRevisionNumber", questionRevisionNumber)
                .field("subject", subject.name)
                .field("questionFingerprint", questionFingerprint)
                .field("responseBinding", responseBinding)
                .field("evidenceRequestId", evidenceRequestId)
                .field("modeVersion", modeVersion)
                .field("turnReferenceId", turnReferenceId)
                .field("turnOrdinal", turnOrdinal)
                .field("turnGeneration", turnGeneration)
                .field("attemptOrdinal", attemptOrdinal)
                .field("hintCount", hintCount)
                .field("answerWasRevealed", answerWasRevealed)
                .field("requestVersion", requestVersion)
                .finish()

    internal val logicalAttemptFingerprint: String
        get() =
            CanonicalSha256("learner-mastery-open-response-logical-attempt-v1")
                .field("learnerId", learnerId)
                .field("subject", subject.name)
                .field("sourceFactId", sourceFactId)
                .field("scopeFingerprint", scopeFingerprint)
                .field("evidenceRequestId", evidenceRequestId)
                .field("modelTaskRequestId", modelTaskRequestId)
                .field("evaluatorRequestVersion", evaluatorRequestVersion)
                .finish()

    internal val canonicalFingerprint: String
        get() =
            CanonicalSha256("learner-mastery-open-response-weak-candidate-v1")
                .field("logicalAttemptFingerprint", logicalAttemptFingerprint)
                .field("reviewCaseId", reviewCaseId)
                .field("conversationId", conversationId)
                .field("conversationGeneration", conversationGeneration)
                .field("conversationStateVersion", conversationStateVersion)
                .field("questionDocumentId", questionDocumentId)
                .field("questionRevisionNumber", questionRevisionNumber)
                .field("questionFingerprint", questionFingerprint)
                .field("responseBinding", responseBinding)
                .field("turnReferenceId", turnReferenceId)
                .field("turnOrdinal", turnOrdinal)
                .field("turnGeneration", turnGeneration)
                .field("modeVersion", modeVersion)
                .field("requestVersion", requestVersion)
                .field("attemptOrdinal", attemptOrdinal)
                .field("hintCount", hintCount)
                .field("answerWasRevealed", answerWasRevealed)
                .field("modelResponseSchemaVersion", modelResponseSchemaVersion)
                .field("candidateIdempotencyKey", candidateIdempotencyKey)
                .nullableField(
                    "revisionOfCandidateIdempotencyKey",
                    revisionOfCandidateIdempotencyKey,
                )
                .field("evidenceFingerprint", evidenceFingerprint)
                .field("modelOutputFingerprint", modelOutputFingerprint)
                .field("modelVersion", modelVersion)
                .field("outcome", outcome.name)
                .field("authorizedKnowledgeCount", authorizedKnowledgeScope.size)
                .also { digest ->
                    authorizedKnowledgeScope.forEachIndexed { index, entry ->
                        digest.field(
                            "authorizedKnowledge[$index]",
                            entry.canonicalFingerprint,
                        )
                    }
                }
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .finish()

    private companion object {
        const val MAX_ATTEMPT_ORDINAL = 17
        const val MAX_HINT_COUNT = 32
        const val MAX_KNOWLEDGE_SCOPE = 24
    }
}

/**
 * Full verified local catalog identity behind one opaque evaluator reference.
 *
 * It intentionally contains no prompt/answer/body, display name, confidence, numeric weight,
 * SQL, or executable payload. [evaluationRole] is qualitative model output only.
 */
class LearnerMasteryOpenResponseKnowledgeScopeEntry(
    val refFingerprint: String,
    val knowledgeNode: KnowledgeNodeRef,
    val manifestFingerprint: String,
    val activationGeneration: Long,
    val evaluationRole: OpenResponseKnowledgeRole?,
) {
    init {
        requireMasteryFingerprint(refFingerprint, "Open-response knowledge-scope reference")
        requireMasteryFingerprint(manifestFingerprint, "Open-response knowledge manifest")
        require(activationGeneration > 0L) {
            "Open-response knowledge activation generation must be positive"
        }
    }

    internal val canonicalFingerprint: String
        get() =
            CanonicalSha256("learner-mastery-open-response-knowledge-scope-entry-v1")
                .field("refFingerprint", refFingerprint)
                .field("knowledgeNode", knowledgeNode.canonicalFingerprint)
                .field("manifestFingerprint", manifestFingerprint)
                .field("activationGeneration", activationGeneration)
                .nullableField("evaluationRole", evaluationRole?.name)
                .finish()
}

internal enum class OpenResponseDedicatedDecisionDisposition {
    ACCEPTED,
    RETAINED_FOR_REVIEW,
}

internal enum class OpenResponseDedicatedDecisionReason {
    MODEL_SEMANTIC_REVIEW_REQUIRES_NON_MODEL_CONFIRMATION,
    ANSWER_REVEALED,
    NO_SUPPORTED_KNOWLEDGE,
    GAP_NOT_UNIQUELY_LOCATED,
    ASSISTANCE_METADATA_MISMATCH,
    ASSISTANCE_LIMIT_EXCEEDED,
    OUTCOME_NOT_ADMISSIBLE,
    STABLE_MASTERY_CONFLICT,
    PRIOR_ACCEPTED_REVISION,
    DIRECTIONAL_BUDGET_REBUILD_PENDING,
    REPEATED_EVIDENCE_BUDGET_EXHAUSTED,
}

internal val CURRENT_OPEN_RESPONSE_RUNTIME_DISPOSITION =
    OpenResponseDedicatedDecisionDisposition.RETAINED_FOR_REVIEW

internal val CURRENT_OPEN_RESPONSE_RUNTIME_REASON =
    OpenResponseDedicatedDecisionReason.MODEL_SEMANTIC_REVIEW_REQUIRES_NON_MODEL_CONFIRMATION

internal const val LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_POLICY_VERSION =
    "learner-mastery-open-response-dedicated-v1"
internal const val LEARNER_MASTERY_OPEN_RESPONSE_MAX_ADMITTED_HINTS = 1
internal const val LEARNER_MASTERY_OPEN_RESPONSE_MAX_ADMITTED_ATTEMPTS = 2
internal const val CURRENT_OPEN_RESPONSE_PROOF_CHAIN_VERSION = 1

enum class LearnerMasteryOpenResponseWeakCandidateDisposition {
    PENDING_CONFIRMATION,
    DUPLICATE,
    CONFLICT,
    REJECTED,
}

data class LearnerMasteryOpenResponseWeakCandidateResult(
    val disposition: LearnerMasteryOpenResponseWeakCandidateDisposition,
    val receiptFingerprint: String?,
) {
    init {
        require(
            (disposition ==
                LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION ||
                disposition == LearnerMasteryOpenResponseWeakCandidateDisposition.DUPLICATE) ==
                (receiptFingerprint != null),
        ) {
            "Only persisted open-response weak candidates expose a receipt"
        }
        receiptFingerprint?.let { fingerprint ->
            requireMasteryFingerprint(fingerprint, "Open-response admission receipt")
        }
    }
}

/**
 * Narrow learner-bound capability retained by core:data. It cannot read mastery or submit an
 * attributed candidate.
 */
interface LearnerMasteryOpenResponseWeakCandidateOwner : AutoCloseable {
    val learnerId: String

    suspend fun findCommitted(
        query: LearnerMasteryOpenResponseWeakCandidateReceiptQuery,
    ): LearnerMasteryOpenResponseWeakCandidateCommitReceipt?

    suspend fun submit(
        command: LearnerMasteryOpenResponseWeakCandidateCommand,
        authorization: LearnerMasteryOpenResponseWeakCandidateAuthorization,
    ): LearnerMasteryOpenResponseWeakCandidateResult
}

data class LearnerMasteryOpenResponseWeakCandidateReceiptQuery(
    val learnerId: String,
    val sourceFactId: String,
    val reviewCaseId: String,
    val scopeFingerprint: String,
    val candidateIdempotencyKey: String,
) {
    init {
        requireMasteryIdentity(learnerId, "Open-response receipt learner id")
        requireMasteryOpaqueReference(sourceFactId, "Open-response receipt source-fact id")
        requireMasteryOpaqueReference(reviewCaseId, "Open-response receipt review-case id")
        requireMasteryFingerprint(scopeFingerprint, "Open-response receipt scope")
        requireMasteryFingerprint(
            candidateIdempotencyKey,
            "Open-response receipt candidate idempotency key",
        )
    }
}

data class LearnerMasteryOpenResponseWeakCandidateCommitReceipt(
    val query: LearnerMasteryOpenResponseWeakCandidateReceiptQuery,
    val receiptFingerprint: String,
) {
    init {
        requireMasteryFingerprint(receiptFingerprint, "Open-response commit receipt")
    }
}

internal fun LearnerMasteryOpenResponseWeakCandidateCommand.toReceiptEntity(
    sourceFact: MasterySourceFactEntity,
    candidate: MasteryObservationCandidateEntity,
    reviewCase: MasteryEvidenceReviewCaseEntity,
    revisionOrdinal: Long,
    receivedAtEpochMillis: Long,
): MasteryOpenResponseWeakCandidateReceiptEntity {
    require(revisionOrdinal >= 0L) {
        "Open-response revision ordinal must not be negative"
    }
    val canonical = canonicalFingerprint
    return MasteryOpenResponseWeakCandidateReceiptEntity(
        receiptFingerprint =
            CanonicalSha256("learner-mastery-open-response-weak-candidate-receipt-v1")
                .field("candidate", candidate.canonicalFingerprint)
                .field("reviewCase", reviewCase.reviewCaseFingerprint)
                .field("command", canonical)
                .finish(),
        canonicalFingerprint = canonical,
        logicalAttemptFingerprint = logicalAttemptFingerprint,
        lineageParentFingerprint =
            revisionOfCandidateIdempotencyKey ?: logicalAttemptFingerprint,
        revisionOrdinal = revisionOrdinal,
        candidateId = candidate.candidateId,
        candidateCanonicalFingerprint = candidate.canonicalFingerprint,
        sourceFactId = sourceFactId,
        reviewCaseId = reviewCase.reviewCaseId,
        reviewCaseFingerprint = reviewCase.reviewCaseFingerprint,
        learnerId = learnerId,
        subject = subject.name,
        scopeFingerprint = scopeFingerprint,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        questionFingerprint = questionFingerprint,
        responseBinding = responseBinding,
        evidenceRequestId = evidenceRequestId,
        presentationFingerprint = sourceFact.presentationFingerprint,
        problemFingerprint = requireNotNull(sourceFact.ephemeralProblemFingerprint),
        problemFamilyFingerprint = requireNotNull(sourceFact.problemFamilyFingerprint),
        turnReferenceId = turnReferenceId,
        turnOrdinal = turnOrdinal,
        turnGeneration = turnGeneration,
        modeVersion = modeVersion,
        requestVersion = requestVersion,
        attemptOrdinal = attemptOrdinal,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        modelTaskRequestId = modelTaskRequestId,
        modelResponseSchemaVersion = modelResponseSchemaVersion,
        evaluatorRequestVersion = evaluatorRequestVersion,
        candidateIdempotencyKey = candidateIdempotencyKey,
        revisionOfCandidateIdempotencyKey = revisionOfCandidateIdempotencyKey,
        evidenceFingerprint = evidenceFingerprint,
        modelVersion = modelVersion,
        outcome = outcome.name,
        occurredAtEpochMillis = occurredAtEpochMillis,
        receivedAtEpochMillis = receivedAtEpochMillis,
        proofChainVersion = CURRENT_OPEN_RESPONSE_PROOF_CHAIN_VERSION,
    )
}

internal fun LearnerMasteryOpenResponseWeakCandidateCommand.toModelAttestationEntity(
    receipt: MasteryOpenResponseWeakCandidateReceiptEntity,
    candidate: MasteryObservationCandidateEntity,
    reviewCase: MasteryEvidenceReviewCaseEntity,
): MasteryOpenResponseModelEvaluationAttestationEntity {
    val fingerprint =
        CanonicalSha256("learner-mastery-open-response-model-attestation-v1")
            .field("receiptFingerprint", receipt.receiptFingerprint)
            .field("candidateId", candidate.candidateId)
            .field("candidateFingerprint", candidate.canonicalFingerprint)
            .field("sourceFactId", sourceFactId)
            .field("reviewCaseId", reviewCase.reviewCaseId)
            .field("learnerId", learnerId)
            .field("subject", subject.name)
            .field("scopeFingerprint", scopeFingerprint)
            .field("conversationId", conversationId)
            .field("conversationGeneration", conversationGeneration)
            .field("conversationStateVersion", conversationStateVersion)
            .field("questionDocumentId", questionDocumentId)
            .field("questionRevisionNumber", questionRevisionNumber)
            .field("questionFingerprint", questionFingerprint)
            .field("evidenceRequestId", evidenceRequestId)
            .field("turnReferenceId", turnReferenceId)
            .field("turnOrdinal", turnOrdinal)
            .field("turnGeneration", turnGeneration)
            .field("modeVersion", modeVersion)
            .field("requestVersion", requestVersion)
            .field("modelTaskRequestId", modelTaskRequestId)
            .field("modelResponseSchemaVersion", modelResponseSchemaVersion)
            .field("evaluatorRequestVersion", evaluatorRequestVersion)
            .field("candidateIdempotencyKey", candidateIdempotencyKey)
            .field("evidenceFingerprint", evidenceFingerprint)
            .field("modelOutputFingerprint", modelOutputFingerprint)
            .field("modelVersion", modelVersion)
            .field("outcome", outcome.name)
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("receivedAtEpochMillis", receipt.receivedAtEpochMillis)
            .finish()
    return MasteryOpenResponseModelEvaluationAttestationEntity(
        attestationFingerprint = fingerprint,
        receiptFingerprint = receipt.receiptFingerprint,
        candidateId = candidate.candidateId,
        candidateCanonicalFingerprint = candidate.canonicalFingerprint,
        sourceFactId = sourceFactId,
        reviewCaseId = reviewCase.reviewCaseId,
        learnerId = learnerId,
        subject = subject.name,
        scopeFingerprint = scopeFingerprint,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        questionFingerprint = questionFingerprint,
        evidenceRequestId = evidenceRequestId,
        turnReferenceId = turnReferenceId,
        turnOrdinal = turnOrdinal,
        turnGeneration = turnGeneration,
        modeVersion = modeVersion,
        requestVersion = requestVersion,
        modelTaskRequestId = modelTaskRequestId,
        modelResponseSchemaVersion = modelResponseSchemaVersion,
        evaluatorRequestVersion = evaluatorRequestVersion,
        candidateIdempotencyKey = candidateIdempotencyKey,
        evidenceFingerprint = evidenceFingerprint,
        modelOutputFingerprint = modelOutputFingerprint,
        modelVersion = modelVersion,
        outcome = outcome.name,
        occurredAtEpochMillis = occurredAtEpochMillis,
        receivedAtEpochMillis = receipt.receivedAtEpochMillis,
    )
}

internal fun LearnerMasteryOpenResponseWeakCandidateCommand.toKnowledgeScopeEntities(
    attestation: MasteryOpenResponseModelEvaluationAttestationEntity,
): List<MasteryOpenResponseEvaluationKnowledgeScopeEntity> =
    authorizedKnowledgeScope.mapIndexed { ordinal, entry ->
        val scopeFingerprint =
            CanonicalSha256("learner-mastery-open-response-persisted-knowledge-scope-v1")
                .field("attestationFingerprint", attestation.attestationFingerprint)
                .field("ordinal", ordinal)
                .field("entry", entry.canonicalFingerprint)
                .finish()
        MasteryOpenResponseEvaluationKnowledgeScopeEntity(
            attestationFingerprint = attestation.attestationFingerprint,
            ordinal = ordinal,
            refFingerprint = entry.refFingerprint,
            subject = entry.knowledgeNode.subject.name,
            knowledgeNodeId = entry.knowledgeNode.knowledgeNodeId,
            taxonomyVersion = entry.knowledgeNode.taxonomyVersion,
            knowledgePackVersion = entry.knowledgeNode.knowledgePackVersion,
            knowledgeNodeRefFingerprint = entry.knowledgeNode.canonicalFingerprint,
            manifestFingerprint = entry.manifestFingerprint,
            activationGeneration = entry.activationGeneration,
            evaluationRole = entry.evaluationRole?.name,
            scopeEntryFingerprint = scopeFingerprint,
        )
    }

internal fun fingerprintOpenResponseSelectedScope(
    selectedScope: List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>,
): String {
    val ordered = selectedScope.sortedBy { it.knowledgeNodeRefFingerprint }
    val digest =
        CanonicalSha256("learner-mastery-open-response-selected-scope-v1")
            .field("selectedCount", ordered.size)
    ordered.forEachIndexed { index, entry ->
        digest.field("selectedScope[$index]", entry.scopeEntryFingerprint)
    }
    return digest.finish()
}

internal fun openResponseDedicatedEventId(attestationFingerprint: String): String =
    "mle:" +
        CanonicalSha256("learner-mastery-open-response-event-id-v1")
            .field("attestationFingerprint", attestationFingerprint)
            .finish()
            .take(48)

internal fun retainedOpenResponseDedicatedDecisionEntity(
    attestation: MasteryOpenResponseModelEvaluationAttestationEntity,
    receipt: MasteryOpenResponseWeakCandidateReceiptEntity,
    reviewCase: MasteryEvidenceReviewCaseEntity,
    reason: OpenResponseDedicatedDecisionReason,
    calibrationSnapshot: MasteryCalibrationSnapshotEntity,
    decidedAtEpochMillis: Long,
): MasteryOpenResponseDedicatedDecisionEntity {
    val disposition = OpenResponseDedicatedDecisionDisposition.RETAINED_FOR_REVIEW
    val fingerprint =
        CanonicalSha256("learner-mastery-open-response-dedicated-decision-v1")
            .field("attestationFingerprint", attestation.attestationFingerprint)
            .field("receiptFingerprint", receipt.receiptFingerprint)
            .field("reviewCaseId", reviewCase.reviewCaseId)
            .field("candidateId", attestation.candidateId)
            .field("sourceFactId", attestation.sourceFactId)
            .field("learnerId", attestation.learnerId)
            .field("subject", attestation.subject)
            .field("disposition", disposition.name)
            .nullableField("direction", null)
            .nullableField("localReason", reason.name)
            .nullableField("selectedScopeFingerprint", null)
            .field("selectedKnowledgeCount", 0)
            .field("localPolicyVersion", LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_POLICY_VERSION)
            .field("calibrationSnapshotFingerprint", calibrationSnapshot.snapshotFingerprint)
            .nullableField("acceptedEventId", null)
            .field("independentlyCompleted", false)
            .field("decidedAtEpochMillis", decidedAtEpochMillis)
            .finish()
    return MasteryOpenResponseDedicatedDecisionEntity(
        decisionFingerprint = fingerprint,
        attestationFingerprint = attestation.attestationFingerprint,
        receiptFingerprint = receipt.receiptFingerprint,
        reviewCaseId = reviewCase.reviewCaseId,
        candidateId = attestation.candidateId,
        sourceFactId = attestation.sourceFactId,
        learnerId = attestation.learnerId,
        subject = attestation.subject,
        disposition = disposition.name,
        direction = null,
        localReason = reason.name,
        selectedScopeFingerprint = null,
        selectedKnowledgeCount = 0,
        localPolicyVersion = LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_POLICY_VERSION,
        calibrationSnapshotFingerprint = calibrationSnapshot.snapshotFingerprint,
        acceptedEventId = null,
        independentlyCompleted = false,
        decidedAtEpochMillis = decidedAtEpochMillis,
    )
}
