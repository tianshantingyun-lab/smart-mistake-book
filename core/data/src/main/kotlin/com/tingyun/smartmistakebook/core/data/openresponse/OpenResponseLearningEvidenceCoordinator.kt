package com.tingyun.smartmistakebook.core.data.openresponse

import com.tingyun.smartmistakebook.core.data.authority.CurrentOpenResponseEvaluationBinding
import com.tingyun.smartmistakebook.core.data.authority.CurrentOpenResponseEvaluationScopeAuthorization
import com.tingyun.smartmistakebook.core.data.authority.LearnerBoundLearningEvidencePort
import com.tingyun.smartmistakebook.core.data.authority.OrdinaryStudyResponse
import com.tingyun.smartmistakebook.core.data.authority.ProductionOpenResponseEvaluationTaskProducerFactory
import com.tingyun.smartmistakebook.core.data.authority.UnsavedStudyEvidenceCommand
import com.tingyun.smartmistakebook.core.data.authority.UnsavedStudyEvidenceWriteResult
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationModelTaskProtocol
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationInputPolicy
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.OpenResponseKnowledgeEffect
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationCandidateOutput
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exact host-owned identity for one answer that is still eligible to become learning evidence.
 *
 * It intentionally contains fingerprints instead of the answer body. The answer body remains in
 * the Tutor source-of-truth and is disclosed only through the separately authorized model task.
 */
data class CurrentOpenResponseLearningScope(
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    val questionFingerprint: String,
    /** Claim-bound keyed value; never a public digest of the student's answer. */
    val responseBinding: String,
    val evidenceRequestId: String,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
) {
    val canonicalFingerprint: String =
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

    init {
        requireOpenResponseIdentity(learnerId, "Learner id")
        requireOpenResponseIdentity(conversationId, "Conversation id")
        requireOpenResponseIdentity(questionDocumentId, "Question document id")
        requireOpenResponseIdentity(evidenceRequestId, "Evidence request id")
        requireOpenResponseIdentity(turnReferenceId, "Turn reference id")
        require(conversationGeneration >= 0L)
        require(conversationStateVersion >= 0L)
        require(questionRevisionNumber > 0)
        require(subject != SubjectKind.GENERAL)
        requireOpenResponseFingerprint(questionFingerprint, "Question fingerprint")
        requireOpenResponseFingerprint(responseBinding, "Response binding")
        require(modeVersion >= 0L)
        require(turnOrdinal >= 0)
        require(turnGeneration >= 0L)
        require(attemptOrdinal in 1..MAX_ATTEMPT_ORDINAL)
        require(hintCount in 0..MAX_HINT_COUNT)
        require(requestVersion >= 0L)
    }

    private companion object {
        const val MAX_ATTEMPT_ORDINAL = 17
        const val MAX_HINT_COUNT = 32
    }
}

fun interface CurrentOpenResponseLearningScopeAuthorization {
    /**
     * Returns the stable generation of the exact current Tutor scope.
     *
     * The generation changes before a question, mode, request, turn, or conversation state is
     * replaced or revoked. Rechecking the same live scope must return the same value.
     */
    fun currentEpoch(expected: CurrentOpenResponseLearningScope): Long?

    fun isCurrent(expected: CurrentOpenResponseLearningScope): Boolean =
        currentEpoch(expected) != null
}

/**
 * One explicit free-response submission. The constructor snapshots all list inputs.
 *
 * Model output cannot supply attempts, hints, answer exposure, source identity, or knowledge
 * authority. Those facts come only from this host-owned command.
 */
class OpenResponseLearningSubmission(
    val scope: CurrentOpenResponseLearningScope,
    val submissionId: String,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val attributionPolicyVersion: String,
    val responsePolicyVersion: String,
    val rubricCanonicalFingerprint: String,
    val currentAnswer: String,
    verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof>,
    teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    val evaluator: OpenResponseEvaluatorKind,
    val evaluatorPolicyFingerprint: String,
    /** Prevents the tutor-producing provider/model epoch from reviewing its own open answer. */
    val prohibitedEvaluatorExecutionFingerprint: String? = null,
    val elapsedDurationMillis: Long?,
    val occurredAtEpochMillis: Long,
    val evaluationTimeoutMillis: Long = DEFAULT_EVALUATION_TIMEOUT_MILLIS,
    val revisionOfCandidateIdempotencyKey: String? = null,
) {
    val verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof> =
        Collections.unmodifiableList(verifiedKnowledgeProofs.toList())
    val teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference> =
        Collections.unmodifiableList(teachingReferences.toList())

    init {
        requireOpenResponseIdentity(submissionId, "Submission id")
        requireOpenResponseFingerprint(presentationFingerprint, "Presentation fingerprint")
        requireOpenResponseFingerprint(problemFingerprint, "Problem fingerprint")
        requireOpenResponseFingerprint(problemFamilyFingerprint, "Problem-family fingerprint")
        requireOpenResponseVersion(attributionPolicyVersion, "Attribution policy")
        requireOpenResponseVersion(responsePolicyVersion, "Response policy")
        requireOpenResponseFingerprint(rubricCanonicalFingerprint, "Rubric fingerprint")
        require(currentAnswer.isNotBlank()) { "Current answer must not be blank" }
        requireOpenResponseFingerprint(evaluatorPolicyFingerprint, "Evaluator policy")
        prohibitedEvaluatorExecutionFingerprint?.let { fingerprint ->
            requireOpenResponseFingerprint(fingerprint, "Prohibited evaluator execution")
        }
        require(
            elapsedDurationMillis == null ||
                elapsedDurationMillis in 0L..MAX_ELAPSED_DURATION_MILLIS,
        )
        require(occurredAtEpochMillis >= 0L)
        require(evaluationTimeoutMillis in 1L..MAX_EVALUATION_TIMEOUT_MILLIS)
        revisionOfCandidateIdempotencyKey?.let {
            requireOpenResponseFingerprint(it, "Revised candidate idempotency key")
        }
        require(verifiedKnowledgeProofs.size <= MAX_KNOWLEDGE_REFERENCES)
        require(teachingReferences.size <= MAX_KNOWLEDGE_REFERENCES)
        require(verifiedKnowledgeProofs.all { it.ref.subject == scope.subject })
        require(teachingReferences.all { it.proof.ref.subject == scope.subject })
        val verifiedScope = verifiedKnowledgeProofs.map(::openResponseKnowledgeProofIdentity)
        val teachingScope =
            teachingReferences.map { reference ->
                openResponseKnowledgeProofIdentity(reference.proof)
            }
        require(verifiedScope.distinct().size == verifiedScope.size) {
            "Open-response verified knowledge scope must not contain duplicates"
        }
        require(teachingScope.distinct().size == teachingScope.size) {
            "Open-response teaching scope must not contain duplicates"
        }
        require(verifiedScope.toSet() == teachingScope.toSet()) {
            "Open-response evaluation and persisted knowledge scopes must match exactly"
        }
        require(
            verifiedKnowledgeProofs
                .map { proof -> proof.manifestFingerprint to proof.activationGeneration }
                .distinct()
                .size <= 1,
        ) {
            "Open-response knowledge scope must use one activated catalog generation"
        }
    }

    private companion object {
        const val MAX_KNOWLEDGE_REFERENCES = 24
        const val MAX_ELAPSED_DURATION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val MAX_EVALUATION_TIMEOUT_MILLIS = 5L * 60L * 1_000L
        const val DEFAULT_EVALUATION_TIMEOUT_MILLIS = 90_000L

    }
}

/**
 * The evaluator output after host validation. This is still only a weak, revisable candidate.
 *
 * There is deliberately no score, confidence, weight, SQL, projection flag, or database handle.
 */
data class OpenResponseWeakCandidateProposal(
    val learnerId: String,
    val sourceFactId: String,
    val reviewCaseId: String,
    val scope: CurrentOpenResponseLearningScope,
    val modelTaskRequestId: String,
    val evaluatorRequestVersion: Long,
    val candidateIdempotencyKey: String,
    val revisionOfCandidateIdempotencyKey: String?,
    val evidenceFingerprint: String,
    val modelOutputFingerprint: String,
    val modelVersion: String,
    val outcome: OpenResponseEvaluationOutcome,
    val knowledgeEffects: List<OpenResponseKnowledgeEffect>,
    val authorizedKnowledgeScope: List<OpenResponseAuthorizedKnowledgeScopeEntry>,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(learnerId == scope.learnerId)
        requireOpenResponseIdentity(sourceFactId, "Source fact id")
        requireOpenResponseIdentity(reviewCaseId, "Review case id")
        requireOpenResponseIdentity(modelTaskRequestId, "Model task request id")
        require(evaluatorRequestVersion == scope.requestVersion)
        requireOpenResponseFingerprint(candidateIdempotencyKey, "Candidate idempotency key")
        requireOpenResponseFingerprint(evidenceFingerprint, "Candidate evidence")
        requireOpenResponseFingerprint(modelOutputFingerprint, "Candidate model output")
        requireOpenResponseIdentity(modelVersion, "Evaluator model version")
        require(outcome != OpenResponseEvaluationOutcome.UNSCORABLE)
        require(knowledgeEffects.size <= MAX_KNOWLEDGE_EFFECTS)
        require(knowledgeEffects.map(OpenResponseKnowledgeEffect::refFingerprint).distinct().size ==
            knowledgeEffects.size)
        require(authorizedKnowledgeScope.size <= MAX_KNOWLEDGE_EFFECTS)
        require(
            authorizedKnowledgeScope
                .map(OpenResponseAuthorizedKnowledgeScopeEntry::refFingerprint)
                .distinct()
                .size == authorizedKnowledgeScope.size,
        )
        require(authorizedKnowledgeScope.all { it.knowledgeNode.subject == scope.subject })
        require(
            authorizedKnowledgeScope
                .map { entry -> entry.manifestFingerprint to entry.activationGeneration }
                .distinct()
                .size <= 1,
        )
        val admittedRefs =
            authorizedKnowledgeScope
                .mapTo(hashSetOf(), OpenResponseAuthorizedKnowledgeScopeEntry::refFingerprint)
        require(knowledgeEffects.all { it.refFingerprint in admittedRefs }) {
            "Open-response evaluation used knowledge outside the persisted authorized scope"
        }
        require(attemptOrdinal in 1..17)
        require(hintCount in 0..32)
        require(occurredAtEpochMillis >= 0L)
    }

    private companion object {
        const val MAX_KNOWLEDGE_EFFECTS = 24
    }
}

/**
 * Content-free, host-snapshotted catalog identity for one evaluator reference.
 *
 * There is deliberately no answer, question body, teaching label, display name, numeric weight,
 * confidence, SQL, or database handle in this structure.
 */
data class OpenResponseAuthorizedKnowledgeScopeEntry(
    val refFingerprint: String,
    val knowledgeNode: KnowledgeNodeRef,
    val manifestFingerprint: String,
    val activationGeneration: Long,
) {
    init {
        requireOpenResponseFingerprint(refFingerprint, "Authorized knowledge scope reference")
        requireOpenResponseFingerprint(manifestFingerprint, "Authorized knowledge manifest")
        require(activationGeneration > 0L)
    }
}

/**
 * Capability passed to the learner owner with the candidate. A conforming owner must call
 * [requireCurrent] inside the same serialized mutation that records the pending candidate.
 */
class OpenResponseWeakCandidateSubmissionAuthorization internal constructor(
    val scopeFingerprint: String,
    private val scope: CurrentOpenResponseLearningScope,
    private val expectedScopeEpoch: Long,
    private val currentScope: CurrentOpenResponseLearningScopeAuthorization,
) {
    private val persistedScopeEpoch = AtomicLong(UNCLAIMED_SCOPE_EPOCH)

    init {
        require(expectedScopeEpoch >= 0L)
    }

    fun requireCurrent() {
        requireCurrentEpoch()
    }

    fun requireCurrentEpoch(): Long {
        if (persistedScopeEpoch.get() == expectedScopeEpoch) return expectedScopeEpoch
        val currentEpoch = currentScope.currentEpoch(scope)
        check(currentEpoch == expectedScopeEpoch) {
            "Open-response candidate no longer matches the current Tutor scope"
        }
        return expectedScopeEpoch
    }

    internal fun markPersistedCurrent(scopeEpoch: Long) {
        check(scopeEpoch == expectedScopeEpoch) {
            "Persisted open-response claim used another Tutor scope epoch"
        }
        val previous = persistedScopeEpoch.getAndSet(scopeEpoch)
        check(previous == UNCLAIMED_SCOPE_EPOCH || previous == scopeEpoch) {
            "Open-response candidate authorization was rebound"
        }
    }

    private companion object {
        const val UNCLAIMED_SCOPE_EPOCH = Long.MIN_VALUE
    }
}

enum class OpenResponseWeakCandidateDisposition {
    PENDING_CONFIRMATION,
    DUPLICATE,
    CONFLICT,
    REJECTED,
    STORAGE_UNAVAILABLE,
}

/** Content-free cross-store lookup key used after a crash between candidate commit and outbox ack. */
data class OpenResponseWeakCandidateReceiptQuery(
    val learnerId: String,
    val sourceFactId: String,
    val reviewCaseId: String,
    val scopeFingerprint: String,
    val candidateIdempotencyKey: String,
) {
    init {
        requireOpenResponseIdentity(learnerId, "Receipt learner id")
        requireOpenResponseIdentity(sourceFactId, "Receipt source fact id")
        requireOpenResponseIdentity(reviewCaseId, "Receipt review case id")
        requireOpenResponseFingerprint(scopeFingerprint, "Receipt scope")
        requireOpenResponseFingerprint(candidateIdempotencyKey, "Receipt candidate idempotency key")
    }
}

/** Narrow durable proof that the learner-mastery owner committed this exact candidate. */
data class OpenResponseWeakCandidateCommitReceipt(
    val query: OpenResponseWeakCandidateReceiptQuery,
    val receiptFingerprint: String,
) {
    init {
        requireOpenResponseFingerprint(receiptFingerprint, "Candidate commit receipt")
    }
}

data class OpenResponseWeakCandidateCommitResult(
    val disposition: OpenResponseWeakCandidateDisposition,
    val receipt: OpenResponseWeakCandidateCommitReceipt?,
) {
    init {
        val persisted =
            disposition == OpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION ||
                disposition == OpenResponseWeakCandidateDisposition.DUPLICATE
        require(persisted == (receipt != null)) {
            "Only a durable candidate commit may expose a receipt"
        }
    }
}

/**
 * Missing concrete bridge: learner-mastery must implement this port without exposing its runtime,
 * DAO, or projection sink to core:data.
 */
interface LearnerBoundOpenResponseWeakCandidateOwner {
    val learnerId: String

    suspend fun findCommitted(
        query: OpenResponseWeakCandidateReceiptQuery,
    ): OpenResponseWeakCandidateCommitReceipt?

    suspend fun submit(
        proposal: OpenResponseWeakCandidateProposal,
        authorization: OpenResponseWeakCandidateSubmissionAuthorization,
    ): OpenResponseWeakCandidateCommitResult
}

enum class PendingOpenResponseReason {
    STALE_SCOPE,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    INVALID_MODEL_OUTPUT,
    UNSCORABLE,
    OWNER_CONFLICT,
    OWNER_REJECTED,
    OWNER_UNAVAILABLE,
}

sealed interface OpenResponseLearningResult {
    data object StaleBeforeSourceFact : OpenResponseLearningResult

    data class SourceFactUnavailable(val sourceFactId: String) : OpenResponseLearningResult

    data class SourceFactRejected(val sourceFactId: String) : OpenResponseLearningResult

    data class PendingEvaluation(
        val sourceFactId: String,
        val reviewCaseId: String,
        val reason: PendingOpenResponseReason,
    ) : OpenResponseLearningResult

    data class WeakCandidateQueued(
        val sourceFactId: String,
        val reviewCaseId: String,
        val candidateIdempotencyKey: String,
        val candidateReceiptFingerprint: String,
        val disposition: OpenResponseWeakCandidateDisposition,
    ) : OpenResponseLearningResult
}

/** Source fact -> independent evaluation -> controlled weak-candidate handoff. */
class OpenResponseLearningEvidenceCoordinator(
    private val evidence: LearnerBoundLearningEvidencePort,
    private val evaluator: IndependentOpenResponseEvaluator,
    private val candidateOwner: LearnerBoundOpenResponseWeakCandidateOwner,
    private val currentScope: CurrentOpenResponseLearningScopeAuthorization,
) {
    init {
        require(evidence.learnerId == candidateOwner.learnerId) {
            "Open-response authorities must share one learner scope"
        }
    }

    suspend fun evaluate(
        submission: OpenResponseLearningSubmission,
        questionDocument: QuestionDocument,
    ): OpenResponseLearningResult {
        require(submission.scope.learnerId == evidence.learnerId) {
            "Open-response submission crossed its learner boundary"
        }
        require(
            OpenResponseEvaluationTaskFingerprints.question(questionDocument) ==
                submission.scope.questionFingerprint,
        ) {
            "Question document changed after the open-response scope was captured"
        }
        OpenResponseEvaluationInputPolicy.requireValidCurrentAnswer(submission.currentAnswer)
        if (!currentScope.isCurrent(submission.scope)) {
            return OpenResponseLearningResult.StaleBeforeSourceFact
        }

        val pending =
            evidence.recordUnsavedStudy(
                submission.toPendingEvidenceCommand(),
            )
        val pendingIdentity =
            when (pending) {
                is UnsavedStudyEvidenceWriteResult.PendingAttributionQueued ->
                    PendingIdentity(pending.sourceFactId, pending.reviewCaseId)
                is UnsavedStudyEvidenceWriteResult.StorageUnavailable ->
                    return OpenResponseLearningResult.SourceFactUnavailable(pending.sourceFactId)
                is UnsavedStudyEvidenceWriteResult.Conflict ->
                    return OpenResponseLearningResult.SourceFactRejected(pending.sourceFactId)
                is UnsavedStudyEvidenceWriteResult.Rejected ->
                    return OpenResponseLearningResult.SourceFactRejected(pending.sourceFactId)
                is UnsavedStudyEvidenceWriteResult.Recorded ->
                    return OpenResponseLearningResult.SourceFactRejected(pending.sourceFactId)
            }

        val candidateScopeEpoch = currentScope.currentEpoch(submission.scope)
        if (candidateScopeEpoch == null) {
            return pendingIdentity.pending(PendingOpenResponseReason.STALE_SCOPE)
        }

        val receiptQuery =
            OpenResponseWeakCandidateReceiptQuery(
                learnerId = submission.scope.learnerId,
                sourceFactId = pendingIdentity.sourceFactId,
                reviewCaseId = pendingIdentity.reviewCaseId,
                scopeFingerprint = submission.scope.canonicalFingerprint,
                candidateIdempotencyKey =
                    OpenResponseEvaluationTaskFingerprints.operationId(
                        operationBinding = submission.scope.responseBinding,
                        questionFingerprint = submission.scope.questionFingerprint,
                        requestVersion = submission.scope.requestVersion,
                    ),
            )
        val priorCommit =
            try {
                candidateOwner.findCommitted(receiptQuery)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (priorCommit != null) {
            if (currentScope.currentEpoch(submission.scope) != candidateScopeEpoch) {
                return pendingIdentity.pending(PendingOpenResponseReason.STALE_SCOPE)
            }
            return OpenResponseLearningResult.WeakCandidateQueued(
                sourceFactId = pendingIdentity.sourceFactId,
                reviewCaseId = pendingIdentity.reviewCaseId,
                candidateIdempotencyKey = receiptQuery.candidateIdempotencyKey,
                candidateReceiptFingerprint = priorCommit.receiptFingerprint,
                disposition = OpenResponseWeakCandidateDisposition.DUPLICATE,
            )
        }

        val work =
            IndependentOpenResponseEvaluationWork(
                sourceFactId = pendingIdentity.sourceFactId,
                reviewCaseId = pendingIdentity.reviewCaseId,
                submission = submission,
                questionDocument = questionDocument,
                currentScope = currentScope,
            )
        val evaluation =
            withTimeoutOrNull(submission.evaluationTimeoutMillis) {
                evaluator.evaluate(work)
            } ?: return pendingIdentity.pending(PendingOpenResponseReason.TIMEOUT)

        val candidate =
            when (evaluation) {
                is IndependentOpenResponseEvaluationResult.Candidate -> evaluation
                IndependentOpenResponseEvaluationResult.Stale ->
                    return pendingIdentity.pending(PendingOpenResponseReason.STALE_SCOPE)
                IndependentOpenResponseEvaluationResult.ProviderUnavailable ->
                    return pendingIdentity.pending(PendingOpenResponseReason.PROVIDER_UNAVAILABLE)
                IndependentOpenResponseEvaluationResult.InvalidOutput ->
                    return pendingIdentity.pending(PendingOpenResponseReason.INVALID_MODEL_OUTPUT)
                IndependentOpenResponseEvaluationResult.Unscorable ->
                    return pendingIdentity.pending(PendingOpenResponseReason.UNSCORABLE)
            }
        if (currentScope.currentEpoch(submission.scope) != candidateScopeEpoch) {
            return pendingIdentity.pending(PendingOpenResponseReason.STALE_SCOPE)
        }

        val proposal =
            candidate.toProposal(
                pendingIdentity = pendingIdentity,
                submission = submission,
            )
        val authorization =
            OpenResponseWeakCandidateSubmissionAuthorization(
                scopeFingerprint = submission.scope.canonicalFingerprint,
                scope = submission.scope,
                expectedScopeEpoch = candidateScopeEpoch,
                currentScope = currentScope,
            )
        val commit =
            try {
                candidateOwner.submit(proposal, authorization)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenResponseWeakCandidateCommitResult(
                    disposition = OpenResponseWeakCandidateDisposition.STORAGE_UNAVAILABLE,
                    receipt = null,
                )
            }
        return when (commit.disposition) {
            OpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
            OpenResponseWeakCandidateDisposition.DUPLICATE,
            -> {
                val receipt = checkNotNull(commit.receipt) {
                    "Persisted candidate result omitted its durable receipt"
                }
                OpenResponseLearningResult.WeakCandidateQueued(
                    sourceFactId = pendingIdentity.sourceFactId,
                    reviewCaseId = pendingIdentity.reviewCaseId,
                    candidateIdempotencyKey = candidate.output.idempotencyKey,
                    candidateReceiptFingerprint = receipt.receiptFingerprint,
                    disposition = commit.disposition,
                )
            }
            OpenResponseWeakCandidateDisposition.CONFLICT ->
                pendingIdentity.pending(PendingOpenResponseReason.OWNER_CONFLICT)
            OpenResponseWeakCandidateDisposition.REJECTED ->
                pendingIdentity.pending(PendingOpenResponseReason.OWNER_REJECTED)
            OpenResponseWeakCandidateDisposition.STORAGE_UNAVAILABLE ->
                pendingIdentity.pending(PendingOpenResponseReason.OWNER_UNAVAILABLE)
        }
    }
}

fun interface IndependentOpenResponseEvaluator {
    suspend fun evaluate(
        work: IndependentOpenResponseEvaluationWork,
    ): IndependentOpenResponseEvaluationResult
}

class IndependentOpenResponseEvaluationWork internal constructor(
    val sourceFactId: String,
    val reviewCaseId: String,
    val submission: OpenResponseLearningSubmission,
    val questionDocument: QuestionDocument,
    internal val currentScope: CurrentOpenResponseLearningScopeAuthorization,
)

sealed interface IndependentOpenResponseEvaluationResult {
    data class Candidate(
        val modelTaskRequestId: String,
        val output: TutorOpenResponseEvaluationCandidateOutput,
    ) : IndependentOpenResponseEvaluationResult

    data object Stale : IndependentOpenResponseEvaluationResult

    data object ProviderUnavailable : IndependentOpenResponseEvaluationResult

    data object InvalidOutput : IndependentOpenResponseEvaluationResult

    data object Unscorable : IndependentOpenResponseEvaluationResult
}

/**
 * Production evaluator. The encrypted Host outbox owns recovery; the model request stays
 * process-local and its egress policy checks the live producer lease immediately before dispatch.
 */
class ModelTaskRepositoryOpenResponseEvaluator internal constructor(
    private val modelTasks: ModelTaskRepository,
    private val knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
    private val nowEpochMillis: LongSupplier,
    private val physicalTrustRegistry: OpenResponsePhysicalTrustRegistry,
) : IndependentOpenResponseEvaluator {
    constructor(
        modelTasks: ModelTaskRepository,
        knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
        nowEpochMillis: LongSupplier,
    ) : this(
        modelTasks = modelTasks,
        knowledgeReferenceVerifier = knowledgeReferenceVerifier,
        nowEpochMillis = nowEpochMillis,
        physicalTrustRegistry = EmptyOpenResponsePhysicalTrustRegistry,
    )

    override suspend fun evaluate(
        work: IndependentOpenResponseEvaluationWork,
    ): IndependentOpenResponseEvaluationResult {
        val submission = work.submission
        if (!work.currentScope.isCurrent(submission.scope)) {
            return IndependentOpenResponseEvaluationResult.Stale
        }
        val ephemeralAnswerFingerprint =
            OpenResponseEvaluationTaskFingerprints.answer(submission.currentAnswer)
        val provider =
            try {
                modelTasks.capabilities()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return IndependentOpenResponseEvaluationResult.ProviderUnavailable
            }
        val prohibitedExecution = submission.prohibitedEvaluatorExecutionFingerprint
        val evaluatorExecution = openResponseEvaluatorExecutionFingerprint(provider)
        if (!isIndependentOpenResponseEvaluator(prohibitedExecution, evaluatorExecution)) {
            return IndependentOpenResponseEvaluationResult.ProviderUnavailable
        }
        val physicalTrustWitness =
            physicalTrustRegistry.attest(
                tutorExecutionFingerprint = checkNotNull(prohibitedExecution),
                evaluatorExecutionFingerprint = checkNotNull(evaluatorExecution),
                evaluatorRepository = modelTasks,
            )
        if (
            physicalTrustWitness == null ||
            physicalTrustWitness.tutorExecutionFingerprint != prohibitedExecution ||
            physicalTrustWitness.evaluatorExecutionFingerprint != evaluatorExecution
        ) {
            return IndependentOpenResponseEvaluationResult.ProviderUnavailable
        }
        if (
            provider.executionLocation == ModelExecutionLocation.UNAVAILABLE ||
            !provider.supports(ModelTaskKind.TUTOR_EVALUATE)
        ) {
            return IndependentOpenResponseEvaluationResult.ProviderUnavailable
        }

        val producer =
            try {
                val now = nowEpochMillis.getAsLong()
                ProductionOpenResponseEvaluationTaskProducerFactory.create(
                    binding =
                        CurrentOpenResponseEvaluationBinding(
                            learnerId = submission.scope.learnerId,
                            conversationId = submission.scope.conversationId,
                            questionDocumentId = submission.scope.questionDocumentId,
                            questionRevisionNumber = submission.scope.questionRevisionNumber,
                            subject = submission.scope.subject,
                            questionDocument = work.questionDocument,
                            rubricCanonicalFingerprint = submission.rubricCanonicalFingerprint,
                            operationBinding = submission.scope.responseBinding,
                            currentAnswer = submission.currentAnswer,
                            teachingReferences = submission.teachingReferences,
                            evaluator = submission.evaluator,
                            evaluatorPolicyFingerprint = submission.evaluatorPolicyFingerprint,
                        ),
                    knowledgeReferenceVerifier = knowledgeReferenceVerifier,
                    expiresAtEpochMillis = now + AUTHORIZATION_LIFETIME_MILLIS,
                    nowEpochMillis = nowEpochMillis,
                    currentScopeAuthorization =
                        CurrentOpenResponseEvaluationScopeAuthorization { expected ->
                            expected.learnerId == submission.scope.learnerId &&
                                expected.conversationId == submission.scope.conversationId &&
                                expected.questionDocumentId ==
                                    submission.scope.questionDocumentId &&
                                expected.questionRevisionNumber ==
                                    submission.scope.questionRevisionNumber &&
                                expected.subject == submission.scope.subject &&
                                expected.questionFingerprint ==
                                    submission.scope.questionFingerprint &&
                                expected.answerFingerprint == ephemeralAnswerFingerprint &&
                                expected.operationBinding == submission.scope.responseBinding &&
                                expected.rubricCanonicalFingerprint ==
                                    submission.rubricCanonicalFingerprint &&
                                work.currentScope.isCurrent(submission.scope)
                        },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return if (work.currentScope.isCurrent(submission.scope)) {
                    IndependentOpenResponseEvaluationResult.InvalidOutput
                } else {
                    IndependentOpenResponseEvaluationResult.Stale
                }
            }

        return try {
            val input = producer.createTask(submission.scope.requestVersion)
            val requestId =
                modelRequestId(
                    work = work,
                    providerId = provider.providerId,
                    modelId = provider.modelId,
                    providerConfigurationVersion = provider.providerConfigurationVersion,
                )
            val now = nowEpochMillis.getAsLong()
            val manifest =
                if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
                    ModelEgressManifest(
                        authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                        subjectId = input.subjectId,
                        purpose = ModelEgressPurpose.TUTORING,
                        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_EVALUATE),
                        providerId = provider.providerId,
                        modelId = provider.modelId,
                        providerConfigurationVersion =
                            provider.providerConfigurationVersion,
                        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_EVALUATE,
                        approvedAtEpochMillis = now,
                        assets = emptyList(),
                        disclosedData = ModelEgressManifest.TUTOR_EVALUATE_DISCLOSURE,
                        prohibitedData =
                            ModelEgressDataClass.entries.toSet() -
                                ModelEgressManifest.TUTOR_EVALUATE_DISCLOSURE,
                    )
                } else {
                    null
                }
            val request =
                ModelTaskRequest(
                    requestId = requestId,
                    input = input,
                    occurredAtEpochMillis = now,
                    egressManifest = manifest,
                )
            val terminal =
                modelTasks.executeSensitiveEphemeral(request).firstOrNull { snapshot ->
                    snapshot.status == ModelTaskStatus.SUCCEEDED ||
                        snapshot.status == ModelTaskStatus.RETRYABLE_FAILURE ||
                        snapshot.status == ModelTaskStatus.PERMANENT_FAILURE ||
                        snapshot.status == ModelTaskStatus.CANCELLED
                }
            if (!work.currentScope.isCurrent(submission.scope)) {
                return IndependentOpenResponseEvaluationResult.Stale
            }
            if (terminal?.status != ModelTaskStatus.SUCCEEDED) {
                return IndependentOpenResponseEvaluationResult.ProviderUnavailable
            }
            val output =
                terminal.output as? TutorOpenResponseEvaluationCandidateOutput
                    ?: return IndependentOpenResponseEvaluationResult.InvalidOutput
            try {
                OpenResponseEvaluationModelTaskProtocol.requireValidCompletion(input, output)
            } catch (_: Exception) {
                return IndependentOpenResponseEvaluationResult.InvalidOutput
            }
            if (output.outcome == OpenResponseEvaluationOutcome.UNSCORABLE) {
                IndependentOpenResponseEvaluationResult.Unscorable
            } else {
                IndependentOpenResponseEvaluationResult.Candidate(
                    modelTaskRequestId = requestId,
                    output = output,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (work.currentScope.isCurrent(submission.scope)) {
                IndependentOpenResponseEvaluationResult.ProviderUnavailable
            } else {
                IndependentOpenResponseEvaluationResult.Stale
            }
        } finally {
            producer.revoke()
        }
    }

    private fun modelRequestId(
        work: IndependentOpenResponseEvaluationWork,
        providerId: String,
        modelId: String,
        providerConfigurationVersion: String,
    ): String =
        "open-response-eval:${
            CanonicalSha256("open-response-independent-model-request-v1")
                .field("sourceFactId", work.sourceFactId)
                .field("reviewCaseId", work.reviewCaseId)
                .field("scope", work.submission.scope.canonicalFingerprint)
                .field("providerId", providerId)
                .field("modelId", modelId)
                .field("providerConfigurationVersion", providerConfigurationVersion)
                .finish()
        }"

    private companion object {
        const val AUTHORIZATION_LIFETIME_MILLIS = 2L * 60L * 1_000L
    }
}

/**
 * Process-local host registry for physical model isolation.
 *
 * Provider/model strings and endpoint configuration never mint a witness. A production registry
 * must be provisioned by trusted host code and bind the exact repository instance to a separately
 * pinned backend. The current production assembly deliberately injects the empty registry.
 */
internal fun interface OpenResponsePhysicalTrustRegistry {
    fun attest(
        tutorExecutionFingerprint: String,
        evaluatorExecutionFingerprint: String,
        evaluatorRepository: ModelTaskRepository,
    ): OpenResponsePhysicalTrustWitness?
}

internal class OpenResponsePhysicalTrustWitness internal constructor(
    val tutorExecutionFingerprint: String,
    val evaluatorExecutionFingerprint: String,
    val tutorPhysicalBackendFingerprint: String,
    val evaluatorPhysicalBackendFingerprint: String,
    val registryGenerationFingerprint: String,
) {
    init {
        requireOpenResponseFingerprint(tutorExecutionFingerprint, "Tutor execution identity")
        requireOpenResponseFingerprint(evaluatorExecutionFingerprint, "Evaluator execution identity")
        requireOpenResponseFingerprint(tutorPhysicalBackendFingerprint, "Tutor physical backend")
        requireOpenResponseFingerprint(
            evaluatorPhysicalBackendFingerprint,
            "Evaluator physical backend",
        )
        requireOpenResponseFingerprint(registryGenerationFingerprint, "Physical trust registry")
        require(tutorPhysicalBackendFingerprint != evaluatorPhysicalBackendFingerprint) {
            "Open-response evaluator must use another physically pinned backend"
        }
    }
}

internal object EmptyOpenResponsePhysicalTrustRegistry : OpenResponsePhysicalTrustRegistry {
    override fun attest(
        tutorExecutionFingerprint: String,
        evaluatorExecutionFingerprint: String,
        evaluatorRepository: ModelTaskRepository,
    ): OpenResponsePhysicalTrustWitness? = null
}

/** Host-provisioned pin for one exact evaluator repository instance and physical backend. */
internal class HostPinnedOpenResponseEvaluatorExecution(
    val repository: ModelTaskRepository,
    val executionFingerprint: String,
    val physicalBackendFingerprint: String,
) {
    init {
        requireOpenResponseFingerprint(executionFingerprint, "Pinned evaluator execution")
        requireOpenResponseFingerprint(physicalBackendFingerprint, "Pinned evaluator backend")
    }
}

/**
 * Future-facing registry populated only from trusted host provisioning, never provider JSON,
 * runtime endpoint text, a model tool call, or the student's content.
 */
internal class HostPinnedOpenResponsePhysicalTrustRegistry(
    private val registryGenerationFingerprint: String,
    tutorPhysicalBackendsByExecution: Map<String, String>,
    evaluatorExecutions: List<HostPinnedOpenResponseEvaluatorExecution>,
) : OpenResponsePhysicalTrustRegistry {
    private val tutorPhysicalBackendsByExecution = tutorPhysicalBackendsByExecution.toMap()
    private val evaluatorExecutions = evaluatorExecutions.toList()

    init {
        requireOpenResponseFingerprint(registryGenerationFingerprint, "Physical trust registry")
        this.tutorPhysicalBackendsByExecution.forEach { (execution, backend) ->
            requireOpenResponseFingerprint(execution, "Pinned tutor execution")
            requireOpenResponseFingerprint(backend, "Pinned tutor backend")
        }
        require(
            this.evaluatorExecutions
                .map { pin -> pin.executionFingerprint to System.identityHashCode(pin.repository) }
                .distinct()
                .size == this.evaluatorExecutions.size,
        ) { "Physical evaluator pins must be unique" }
    }

    override fun attest(
        tutorExecutionFingerprint: String,
        evaluatorExecutionFingerprint: String,
        evaluatorRepository: ModelTaskRepository,
    ): OpenResponsePhysicalTrustWitness? {
        val tutorBackend =
            tutorPhysicalBackendsByExecution[tutorExecutionFingerprint] ?: return null
        val evaluatorPin =
            evaluatorExecutions.singleOrNull { pin ->
                pin.repository === evaluatorRepository &&
                    pin.executionFingerprint == evaluatorExecutionFingerprint
            } ?: return null
        if (tutorBackend == evaluatorPin.physicalBackendFingerprint) return null
        return OpenResponsePhysicalTrustWitness(
            tutorExecutionFingerprint = tutorExecutionFingerprint,
            evaluatorExecutionFingerprint = evaluatorExecutionFingerprint,
            tutorPhysicalBackendFingerprint = tutorBackend,
            evaluatorPhysicalBackendFingerprint = evaluatorPin.physicalBackendFingerprint,
            registryGenerationFingerprint = registryGenerationFingerprint,
        )
    }
}

internal fun openResponseEvaluatorExecutionFingerprint(
    provider: ProviderCapabilitySnapshot,
): String? {
    val providerIdentity = canonicalOpenResponseProviderIdentity(provider.providerId) ?: return null
    val modelFamily = canonicalOpenResponseModelFamily(provider.modelId) ?: return null
    val providerFingerprint =
        CanonicalSha256("open-response-evaluator-provider-identity-v3")
            .field("providerIdentity", providerIdentity)
            .finish()
            .take(OPEN_RESPONSE_EVALUATOR_COMPONENT_HEX_CHARS)
    val modelFamilyFingerprint =
        CanonicalSha256("open-response-evaluator-model-family-v3")
            .field("modelFamily", modelFamily)
            .finish()
            .take(OPEN_RESPONSE_EVALUATOR_COMPONENT_HEX_CHARS)
    return OPEN_RESPONSE_EVALUATOR_IDENTITY_PREFIX +
        providerFingerprint +
        modelFamilyFingerprint
}

private fun isIndependentOpenResponseEvaluator(
    prohibitedExecution: String?,
    evaluatorExecution: String?,
): Boolean {
    val prohibited = prohibitedExecution?.toOpenResponseEvaluatorIdentity() ?: return false
    val evaluator = evaluatorExecution?.toOpenResponseEvaluatorIdentity() ?: return false
    return prohibited.providerFingerprint != evaluator.providerFingerprint &&
        prohibited.modelFamilyFingerprint != evaluator.modelFamilyFingerprint
}

private fun String.toOpenResponseEvaluatorIdentity(): OpenResponseEvaluatorIdentity? {
    if (length != OPEN_RESPONSE_EVALUATOR_IDENTITY_HEX_CHARS) return null
    if (!startsWith(OPEN_RESPONSE_EVALUATOR_IDENTITY_PREFIX)) return null
    if (!all { it in '0'..'9' || it in 'a'..'f' }) return null
    val providerStart = OPEN_RESPONSE_EVALUATOR_IDENTITY_PREFIX.length
    val modelStart = providerStart + OPEN_RESPONSE_EVALUATOR_COMPONENT_HEX_CHARS
    return OpenResponseEvaluatorIdentity(
        providerFingerprint = substring(providerStart, modelStart),
        modelFamilyFingerprint = substring(modelStart),
    )
}

private fun canonicalOpenResponseProviderIdentity(raw: String): String? =
    canonicalOpenResponseIdentityText(raw)

private fun canonicalOpenResponseModelFamily(raw: String): String? {
    val normalized = canonicalOpenResponseIdentityText(raw) ?: return null
    val family = normalized.replace(OPEN_RESPONSE_MODEL_RELEASE_ALIAS_SUFFIX, "")
    return family.takeUnless { it.isBlank() || it in OPEN_RESPONSE_UNKNOWN_IDENTITIES }
}

private fun canonicalOpenResponseIdentityText(raw: String): String? {
    val normalized =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .lowercase(Locale.ROOT)
            .replace(OPEN_RESPONSE_IDENTITY_SEPARATOR, "-")
            .trim('-')
    return normalized.takeUnless { it.isBlank() || it in OPEN_RESPONSE_UNKNOWN_IDENTITIES }
}

private data class OpenResponseEvaluatorIdentity(
    val providerFingerprint: String,
    val modelFamilyFingerprint: String,
)

private const val OPEN_RESPONSE_EVALUATOR_IDENTITY_PREFIX = "4556414c49443300"
private const val OPEN_RESPONSE_EVALUATOR_COMPONENT_HEX_CHARS = 24
private const val OPEN_RESPONSE_EVALUATOR_IDENTITY_HEX_CHARS = 64
private val OPEN_RESPONSE_IDENTITY_SEPARATOR = Regex("""[\s_./:@]+""")
private val OPEN_RESPONSE_MODEL_RELEASE_ALIAS_SUFFIX = Regex(
    """(?:-(?:latest|stable|preview|release|v?[0-9]+(?:-[0-9]+){0,2}|[0-9]{8}))+$""",
)
private val OPEN_RESPONSE_UNKNOWN_IDENTITIES = setOf(
    "auto",
    "default",
    "n-a",
    "na",
    "none",
    "null",
    "unknown",
    "unspecified",
)

private data class PendingIdentity(
    val sourceFactId: String,
    val reviewCaseId: String,
) {
    fun pending(reason: PendingOpenResponseReason): OpenResponseLearningResult.PendingEvaluation =
        OpenResponseLearningResult.PendingEvaluation(
            sourceFactId = sourceFactId,
            reviewCaseId = reviewCaseId,
            reason = reason,
        )
}

private fun OpenResponseLearningSubmission.toPendingEvidenceCommand():
    UnsavedStudyEvidenceCommand =
    UnsavedStudyEvidenceCommand(
        submissionId = submissionId,
        subject = scope.subject,
        presentationFingerprint = presentationFingerprint,
        problemFingerprint = problemFingerprint,
        problemFamilyFingerprint = problemFamilyFingerprint,
        interactionReferenceId = scope.turnReferenceId,
        attributionPolicyVersion = attributionPolicyVersion,
        verifiedKnowledgeProofs = verifiedKnowledgeProofs,
        response =
            OrdinaryStudyResponse.UnverifiedFreeResponse(
                responseCanonicalFingerprint = scope.responseBinding,
                verificationPolicyVersion = responsePolicyVersion,
            ),
        attemptOrdinal = scope.attemptOrdinal,
        hintCount = scope.hintCount,
        answerWasRevealed = scope.answerWasRevealed,
        elapsedDurationMillis = elapsedDurationMillis,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

private fun IndependentOpenResponseEvaluationResult.Candidate.toProposal(
    pendingIdentity: PendingIdentity,
    submission: OpenResponseLearningSubmission,
): OpenResponseWeakCandidateProposal =
    OpenResponseWeakCandidateProposal(
        learnerId = submission.scope.learnerId,
        sourceFactId = pendingIdentity.sourceFactId,
        reviewCaseId = pendingIdentity.reviewCaseId,
        scope = submission.scope,
        modelTaskRequestId = modelTaskRequestId,
        evaluatorRequestVersion = output.requestVersion,
        candidateIdempotencyKey = output.idempotencyKey,
        revisionOfCandidateIdempotencyKey =
            submission.revisionOfCandidateIdempotencyKey,
        evidenceFingerprint = output.evidenceFingerprint,
        modelOutputFingerprint =
            OpenResponseEvaluationTaskFingerprints.candidateOutput(output),
        modelVersion = output.modelVersion,
        outcome = output.outcome,
        knowledgeEffects = output.knowledgeEffects,
        authorizedKnowledgeScope =
            submission.verifiedKnowledgeProofs
                .map { proof ->
                    OpenResponseAuthorizedKnowledgeScopeEntry(
                        refFingerprint =
                            OpenResponseEvaluationTaskFingerprints.knowledgeScopeReference(
                                questionFingerprint = submission.scope.questionFingerprint,
                                knowledgeNodeReferenceFingerprint =
                                    proof.ref.canonicalFingerprint,
                                knowledgeManifestFingerprint = proof.manifestFingerprint,
                                knowledgeActivationGeneration = proof.activationGeneration,
                            ),
                        knowledgeNode = proof.ref,
                        manifestFingerprint = proof.manifestFingerprint,
                        activationGeneration = proof.activationGeneration,
                    )
                }.sortedBy(OpenResponseAuthorizedKnowledgeScopeEntry::refFingerprint),
        attemptOrdinal = submission.scope.attemptOrdinal,
        hintCount = submission.scope.hintCount,
        answerWasRevealed = submission.scope.answerWasRevealed,
        occurredAtEpochMillis = submission.occurredAtEpochMillis,
    )

private fun openResponseKnowledgeProofIdentity(
    proof: VerifiedKnowledgeReferenceProof,
): String =
    CanonicalSha256("open-response-host-knowledge-proof-identity-v1")
        .field("knowledgeNodeReference", proof.ref.canonicalFingerprint)
        .field("knowledgeManifest", proof.manifestFingerprint)
        .field("knowledgeActivationGeneration", proof.activationGeneration)
        .finish()

private fun requireOpenResponseIdentity(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_IDENTITY_CHARS &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value"
    }
}

private fun requireOpenResponseVersion(
    value: String,
    label: String,
) {
    requireOpenResponseIdentity(value, label)
    require(value.length <= MAX_VERSION_CHARS && VERSION_PATTERN.matches(value)) {
        "$label contains unsupported characters"
    }
}

private fun requireOpenResponseFingerprint(
    value: String,
    label: String,
) {
    require(FINGERPRINT_PATTERN.matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private const val MAX_IDENTITY_CHARS = 256
private const val MAX_VERSION_CHARS = 128
private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:+-]*")
private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
