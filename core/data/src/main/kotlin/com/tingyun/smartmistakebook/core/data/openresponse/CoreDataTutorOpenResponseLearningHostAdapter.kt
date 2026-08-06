package com.tingyun.smartmistakebook.core.data.openresponse

import com.tingyun.smartmistakebook.core.data.authority.LearnerBoundLearningEvidencePort
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationInputPolicy
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Collections
import java.util.function.LongSupplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Feature-neutral request used by the app assembly when implementing
 * `feature:tutor`'s `TutorOpenResponseLearningHostPort`.
 *
 * core:data intentionally does not depend on feature:tutor: feature:tutor already depends on
 * core:data, so implementing the feature interface in this module would create a Gradle cycle.
 */
internal class CoreDataTutorOpenResponseContextRequest(
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    questionDocument: QuestionDocument,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val evidenceRequestId: String,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
) {
    val questionDocument: QuestionDocument =
        questionDocument.copy(
            blocks = Collections.unmodifiableList(questionDocument.blocks.toList()),
        )
    val questionFingerprint: String =
        OpenResponseEvaluationTaskFingerprints.question(this.questionDocument)
    val canonicalFingerprint: String =
        CanonicalSha256("core-data-tutor-open-response-context-request-v1")
            .field("conversationId", conversationId)
            .field("conversationGeneration", conversationGeneration)
            .field("conversationStateVersion", conversationStateVersion)
            .field("questionDocumentId", questionDocumentId)
            .field("questionRevisionNumber", questionRevisionNumber)
            .field("subject", subject.name)
            .field("questionFingerprint", questionFingerprint)
            .field("explanationMode", explanationMode.name)
            .field("modeVersion", modeVersion)
            .field("turnReferenceId", turnReferenceId)
            .field("turnOrdinal", turnOrdinal)
            .field("turnGeneration", turnGeneration)
            .field("evidenceRequestId", evidenceRequestId)
            .field("attemptOrdinal", attemptOrdinal)
            .field("hintCount", hintCount)
            .field("answerWasRevealed", answerWasRevealed)
            .field("requestVersion", requestVersion)
            .finish()

    init {
        requireHostIdentity(conversationId, "Conversation id")
        requireHostIdentity(questionDocumentId, "Question document id")
        requireHostIdentity(turnReferenceId, "Turn reference id")
        requireHostIdentity(evidenceRequestId, "Evidence request id")
        require(conversationGeneration >= 0L)
        require(conversationStateVersion >= 0L)
        require(questionDocumentId == this.questionDocument.id)
        require(questionRevisionNumber > 0)
        require(subject != SubjectKind.GENERAL)
        require(modeVersion >= 0L)
        require(turnOrdinal >= 0)
        require(turnGeneration >= 0L)
        require(attemptOrdinal in 1..17)
        require(hintCount in 0..32)
        require(requestVersion >= 0L)
    }
}

/**
 * Exact problem authority returned only after the session owner has checked current state.
 *
 * The verified references are process-local capabilities. They are retained inside core:data and
 * never copied into the feature context.
 */
class CurrentTutorOpenResponseContextAuthorization(
    val learnerId: String,
    val requestFingerprint: String,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val attributionPolicyVersion: String,
    val responsePolicyVersion: String,
    val rubricCanonicalFingerprint: String,
    verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof>,
    teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    val evaluator: OpenResponseEvaluatorKind,
    val evaluatorPolicyFingerprint: String,
    val prohibitedEvaluatorExecutionFingerprint: String? = null,
    val expiresAtEpochMillis: Long,
) {
    internal val verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof> =
        Collections.unmodifiableList(verifiedKnowledgeProofs.toList())
    internal val teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference> =
        Collections.unmodifiableList(teachingReferences.toList())

    internal val canonicalFingerprint: String =
        CanonicalSha256("current-tutor-open-response-context-authorization-v1")
            .field("learnerId", learnerId)
            .field("requestFingerprint", requestFingerprint)
            .field("presentationFingerprint", presentationFingerprint)
            .field("problemFingerprint", problemFingerprint)
            .field("problemFamilyFingerprint", problemFamilyFingerprint)
            .field("attributionPolicyVersion", attributionPolicyVersion)
            .field("responsePolicyVersion", responsePolicyVersion)
            .field("rubricCanonicalFingerprint", rubricCanonicalFingerprint)
            .field(
                "knowledgeProofs",
                this.verifiedKnowledgeProofs.joinToString("|") { proof ->
                    "${proof.ref.canonicalFingerprint}:${proof.manifestFingerprint}:" +
                        proof.activationGeneration
                },
            )
            .field(
                "teachingReferences",
                this.teachingReferences.joinToString("|") { reference ->
                    "${reference.proof.ref.canonicalFingerprint}:${reference.label}:" +
                        reference.constraint.name
                },
            )
            .field("evaluator", evaluator.name)
            .field("evaluatorPolicyFingerprint", evaluatorPolicyFingerprint)
            .nullableField(
                "prohibitedEvaluatorExecutionFingerprint",
                prohibitedEvaluatorExecutionFingerprint,
            )
            .field("expiresAtEpochMillis", expiresAtEpochMillis)
            .finish()

    init {
        requireHostIdentity(learnerId, "Learner id")
        requireHostFingerprint(requestFingerprint, "Context request fingerprint")
        requireHostFingerprint(presentationFingerprint, "Presentation fingerprint")
        requireHostFingerprint(problemFingerprint, "Problem fingerprint")
        requireHostFingerprint(problemFamilyFingerprint, "Problem-family fingerprint")
        requireHostVersion(attributionPolicyVersion, "Attribution policy")
        requireHostVersion(responsePolicyVersion, "Response policy")
        requireHostFingerprint(rubricCanonicalFingerprint, "Rubric fingerprint")
        requireHostFingerprint(evaluatorPolicyFingerprint, "Evaluator policy fingerprint")
        prohibitedEvaluatorExecutionFingerprint?.let { fingerprint ->
            requireHostFingerprint(fingerprint, "Prohibited evaluator execution fingerprint")
        }
        require(expiresAtEpochMillis >= 0L)
        require(this.verifiedKnowledgeProofs.size <= MAX_KNOWLEDGE_REFERENCES)
        require(this.teachingReferences.size <= MAX_KNOWLEDGE_REFERENCES)
        require(
            this.verifiedKnowledgeProofs
                .map { it.ref.canonicalFingerprint }
                .distinct()
                .size == this.verifiedKnowledgeProofs.size,
        )
        require(
            this.teachingReferences
                .map { it.proof.ref.canonicalFingerprint }
                .distinct()
                .size == this.teachingReferences.size,
        )
    }

    private companion object {
        const val MAX_KNOWLEDGE_REFERENCES = 24
    }
}

/**
 * Session-owned authority. A conforming implementation reads current conversation, question,
 * mode, and turn state before issuing the authorization, and rechecks the same state in
 * [isCurrent].
 */
internal interface CurrentTutorOpenResponseContextOwner {
    val learnerId: String

    suspend fun authorizeCurrent(
        request: CoreDataTutorOpenResponseContextRequest,
        notAfterEpochMillis: Long,
    ): CurrentTutorOpenResponseContextAuthorization?

    fun isCurrent(authorization: CurrentTutorOpenResponseContextAuthorization): Boolean

    /**
     * Rechecks an authorization after a trusted Host has durably claimed its answer action.
     * Implementations must still enforce the exact presentation, mode, write authority and
     * revocation state, but must not reject only because the issuance deadline elapsed later.
     */
    fun isClaimedCurrent(
        authorization: CurrentTutorOpenResponseContextAuthorization,
    ): Boolean = isCurrent(authorization)

    /**
     * Atomically rechecks persisted Tutor authority and durably consumes this candidate scope.
     */
    suspend fun consumeCurrentAuthorization(
        authorization: CurrentTutorOpenResponseContextAuthorization,
        scope: CurrentOpenResponseLearningScope,
        candidateIdempotencyKey: String,
    ): CurrentTutorOpenResponseAuthorizationConsumeDisposition

    /** Durable consume for an answer action that was claimed before its issuance deadline. */
    suspend fun consumeClaimedAuthorization(
        authorization: CurrentTutorOpenResponseContextAuthorization,
        scope: CurrentOpenResponseLearningScope,
        candidateIdempotencyKey: String,
    ): CurrentTutorOpenResponseAuthorizationConsumeDisposition =
        consumeCurrentAuthorization(authorization, scope, candidateIdempotencyKey)
}

enum class CurrentTutorOpenResponseAuthorizationConsumeDisposition {
    CONSUMED,
    DUPLICATE,
    NOT_CURRENT,
    REJECTED,
}

/**
 * Opaque core:data context returned to the app's feature adapter.
 *
 * Only the presentation/problem fingerprints are added by the trusted host. All session facts are
 * copied exactly from the feature request.
 */
internal class CoreDataTutorOpenResponseLearningContext internal constructor(
    val learnerId: String,
    val request: CoreDataTutorOpenResponseContextRequest,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val expiresAtEpochMillis: Long,
    internal val issuanceToken: String,
) {
    /**
     * Same canonical identity used by feature:tutor. The app wrapper must compare this with the
     * feature lease before forwarding an admitted response.
     */
    val featureContextFingerprint: String =
        CanonicalSha256("feature-tutor-open-response-learning-context-v1")
            .field("learnerId", learnerId)
            .field("conversationId", request.conversationId)
            .field("conversationGeneration", request.conversationGeneration)
            .field("conversationStateVersion", request.conversationStateVersion)
            .field("questionDocumentId", request.questionDocumentId)
            .field("questionRevisionNumber", request.questionRevisionNumber)
            .field("subject", request.subject.name)
            .field("questionFingerprint", request.questionFingerprint)
            .field("presentationFingerprint", presentationFingerprint)
            .field("problemFingerprint", problemFingerprint)
            .field("problemFamilyFingerprint", problemFamilyFingerprint)
            .field("explanationMode", request.explanationMode.name)
            .field("modeVersion", request.modeVersion)
            .field("turnReferenceId", request.turnReferenceId)
            .field("turnOrdinal", request.turnOrdinal)
            .field("turnGeneration", request.turnGeneration)
            .field("evidenceRequestId", request.evidenceRequestId)
            .field("attemptOrdinal", request.attemptOrdinal)
            .field("hintCount", request.hintCount)
            .field("answerWasRevealed", request.answerWasRevealed)
            .field("requestVersion", request.requestVersion)
            .finish()
}

internal sealed interface CoreDataTutorOpenResponseAdmission {
    val canonicalFingerprint: String

    class GuidedFreeResponse(
        val evidenceRequestId: String,
    ) : CoreDataTutorOpenResponseAdmission {
        override val canonicalFingerprint: String =
            CanonicalSha256("core-data-guided-open-response-admission-v1")
                .field("evidenceRequestId", evidenceRequestId)
                .finish()

        init {
            requireHostIdentity(evidenceRequestId, "Guided evidence request id")
        }
    }

    class DirectSpecificCurrentQuestionGap(
        val intentFingerprint: String,
    ) : CoreDataTutorOpenResponseAdmission {
        override val canonicalFingerprint: String =
            CanonicalSha256("core-data-direct-open-response-admission-v1")
                .field("intentFingerprint", intentFingerprint)
                .finish()

        init {
            requireHostFingerprint(intentFingerprint, "Direct intent fingerprint")
        }
    }
}

internal class CoreDataTutorOpenResponseAdmittedSubmission(
    val context: CoreDataTutorOpenResponseLearningContext,
    val sourceSubmissionId: String,
    val sourceMessageId: String,
    val admission: CoreDataTutorOpenResponseAdmission,
    val currentAnswer: String,
    val responseBinding: String,
    val elapsedDurationMillis: Long?,
    val occurredAtEpochMillis: Long,
) {
    internal val stableSubmissionId: String =
        "tutor-open-response:${
            CanonicalSha256("core-data-tutor-open-response-submission-v1")
                .field("context", context.featureContextFingerprint)
                .field("sourceMessageId", sourceMessageId)
                .field("admission", admission.canonicalFingerprint)
                .field("responseBinding", responseBinding)
                .finish()
        }"

    init {
        requireHostIdentity(sourceSubmissionId, "Source submission id")
        requireHostIdentity(sourceMessageId, "Source message id")
        requireHostFingerprint(responseBinding, "Response binding")
        OpenResponseEvaluationInputPolicy.requireValidCurrentAnswer(currentAnswer)
        require(
            elapsedDurationMillis == null ||
                elapsedDurationMillis in 0L..MAX_ELAPSED_DURATION_MILLIS,
        )
        require(occurredAtEpochMillis >= 0L)
        when (admission) {
            is CoreDataTutorOpenResponseAdmission.GuidedFreeResponse -> {
                require(context.request.explanationMode == TutorExplanationMode.GUIDED)
                require(admission.evidenceRequestId == context.request.evidenceRequestId)
            }
            is CoreDataTutorOpenResponseAdmission.DirectSpecificCurrentQuestionGap ->
                require(context.request.explanationMode == TutorExplanationMode.DIRECT)
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = OpenResponseEvaluationInputPolicy.MAX_CURRENT_ANSWER_CHARS
        const val MAX_ELAPSED_DURATION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

/**
 * App-owned live feature lease projected into core:data. No cached Boolean is accepted.
 */
internal class CoreDataTutorOpenResponseSubmissionLease private constructor(
    val featureContextFingerprint: String,
    val responseBinding: String,
    private val isCurrentBlock: () -> Boolean,
    internal val acceptedDurableClaim: Boolean,
) {
    constructor(
        featureContextFingerprint: String,
        responseBinding: String,
        isCurrentBlock: () -> Boolean,
    ) : this(
        featureContextFingerprint = featureContextFingerprint,
        responseBinding = responseBinding,
        isCurrentBlock = isCurrentBlock,
        acceptedDurableClaim = false,
    )

    init {
        requireHostFingerprint(featureContextFingerprint, "Feature context fingerprint")
        requireHostFingerprint(responseBinding, "Response binding")
    }

    fun isCurrent(): Boolean =
        try {
            isCurrentBlock()
        } catch (_: Exception) {
            false
        }

    internal companion object {
        fun afterDurableClaim(
            featureContextFingerprint: String,
            responseBinding: String,
            isCurrentBlock: () -> Boolean,
        ): CoreDataTutorOpenResponseSubmissionLease =
            CoreDataTutorOpenResponseSubmissionLease(
                featureContextFingerprint = featureContextFingerprint,
                responseBinding = responseBinding,
                isCurrentBlock = isCurrentBlock,
                acceptedDurableClaim = true,
            )
    }
}

internal enum class CoreDataTutorOpenResponseLearningReceipt {
    PENDING,
    DUPLICATE,
    RETAINED_FOR_RETRY,
    REJECTED,
    NOT_CURRENT,
}

internal data class CoreDataTutorOpenResponseCandidateCommitReceipt(
    val candidateIdempotencyKey: String,
    val receiptFingerprint: String,
) {
    init {
        requireHostFingerprint(candidateIdempotencyKey, "Candidate idempotency key")
        requireHostFingerprint(receiptFingerprint, "Candidate receipt fingerprint")
    }
}

internal data class CoreDataTutorOpenResponseSubmissionResult(
    val disposition: CoreDataTutorOpenResponseLearningReceipt,
    val candidateCommitReceipt: CoreDataTutorOpenResponseCandidateCommitReceipt?,
) {
    init {
        require(
            (disposition == CoreDataTutorOpenResponseLearningReceipt.PENDING ||
                disposition == CoreDataTutorOpenResponseLearningReceipt.DUPLICATE) ==
                (candidateCommitReceipt != null),
        ) { "Only a durable candidate commit may complete a free-response submission" }
    }
}

internal enum class CoreDataTutorOpenResponseLearningAssemblyUnavailableReason {
    LEARNER_WEAK_CANDIDATE_OWNER_UNAVAILABLE,
    MISSING_CURRENT_CONTEXT_OWNER,
    INCONSISTENT_LEARNER_SCOPE,
}

internal sealed interface CoreDataTutorOpenResponseLearningAssemblyResult {
    class Available(
        val host: CoreDataTutorOpenResponseLearningHostAdapter,
    ) : CoreDataTutorOpenResponseLearningAssemblyResult

    data class Unavailable(
        val reason: CoreDataTutorOpenResponseLearningAssemblyUnavailableReason,
    ) : CoreDataTutorOpenResponseLearningAssemblyResult
}

/**
 * The only open-response learning capability that may cross the production publication boundary.
 *
 * Keeping this as a narrow interface prevents application code from depending on assembly or
 * storage-owner details while preserving the host's current-session and learner-bound checks.
 */
internal interface CoreDataTutorOpenResponseLearningHostPort : AutoCloseable {
    val learnerId: String

    suspend fun issueContext(
        request: CoreDataTutorOpenResponseContextRequest,
    ): CoreDataTutorOpenResponseLearningContext?

    suspend fun submitAdmitted(
        submission: CoreDataTutorOpenResponseAdmittedSubmission,
        lease: CoreDataTutorOpenResponseSubmissionLease,
    ): CoreDataTutorOpenResponseLearningReceipt

    suspend fun submitAdmittedWithReceipt(
        submission: CoreDataTutorOpenResponseAdmittedSubmission,
        lease: CoreDataTutorOpenResponseSubmissionLease,
    ): CoreDataTutorOpenResponseSubmissionResult {
        val legacyDisposition = submitAdmitted(submission, lease)
        return CoreDataTutorOpenResponseSubmissionResult(
            disposition =
                if (
                    legacyDisposition == CoreDataTutorOpenResponseLearningReceipt.PENDING ||
                    legacyDisposition == CoreDataTutorOpenResponseLearningReceipt.DUPLICATE
                ) {
                    CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY
                } else {
                    legacyDisposition
                },
            candidateCommitReceipt = null,
        )
    }

    fun revoke()
}

/**
 * Core half of TutorOpenResponseLearningHostPort.
 *
 * Its only durable path is OpenResponseLearningEvidenceCoordinator:
 * source fact -> independent model evaluation -> weak candidate. It cannot write a mastery
 * projection and accepts no knowledge identifiers or weights from feature code.
 */
internal class CoreDataTutorOpenResponseLearningHostAdapter private constructor(
    override val learnerId: String,
    private val contextOwner: CurrentTutorOpenResponseContextOwner,
    private val evidence: LearnerBoundLearningEvidencePort,
    evaluator: IndependentOpenResponseEvaluator,
    candidateOwner: LearnerBoundOpenResponseWeakCandidateOwner,
    private val nowEpochMillis: LongSupplier,
    private val contextLifetimeMillis: Long,
    private val contextIssueTimeoutMillis: Long,
    private val submissionTimeoutMillis: Long,
    private val closeCandidateOwner: () -> Unit,
) : CoreDataTutorOpenResponseLearningHostPort {
    private val stateLock = Any()
    private var issueGeneration = 0L
    private var currentIssued: IssuedContext? = null
    private var closed = false
    private val currentScope = CurrentScopeGate()
    private val persistentlyCurrentCandidateOwner =
        object : LearnerBoundOpenResponseWeakCandidateOwner {
            override val learnerId: String = candidateOwner.learnerId

            override suspend fun findCommitted(
                query: OpenResponseWeakCandidateReceiptQuery,
            ): OpenResponseWeakCandidateCommitReceipt? {
                if (
                    query.learnerId != learnerId ||
                    !currentScope.isCurrentFingerprint(query.scopeFingerprint)
                ) {
                    return null
                }
                return candidateOwner.findCommitted(query)
                    ?.takeIf { receipt ->
                        receipt.query == query &&
                            currentScope.isCurrentFingerprint(query.scopeFingerprint)
                    }
            }

            override suspend fun submit(
                proposal: OpenResponseWeakCandidateProposal,
                authorization: OpenResponseWeakCandidateSubmissionAuthorization,
            ): OpenResponseWeakCandidateCommitResult {
                val persistedEpoch = currentScope.consumeCurrent(
                    expected = proposal.scope,
                    candidateIdempotencyKey = proposal.candidateIdempotencyKey,
                ) ?: return OpenResponseWeakCandidateCommitResult(
                    disposition = OpenResponseWeakCandidateDisposition.REJECTED,
                    receipt = null,
                )
                authorization.markPersistedCurrent(persistedEpoch)
                return candidateOwner.submit(proposal, authorization)
            }
        }
    private val coordinator =
        OpenResponseLearningEvidenceCoordinator(
            evidence = evidence,
            evaluator = evaluator,
            candidateOwner = persistentlyCurrentCandidateOwner,
            currentScope = currentScope,
        )

    override suspend fun issueContext(
        request: CoreDataTutorOpenResponseContextRequest,
    ): CoreDataTutorOpenResponseLearningContext? {
        val ticket =
            synchronized(stateLock) {
                if (closed) {
                    null
                } else {
                    issueGeneration += 1L
                    currentIssued = null
                    issueGeneration
                }
            } ?: return null
        currentScope.clear()
        val now = nowEpochMillis.getAsLong()
        val notAfter = safeDeadline(now, contextLifetimeMillis)
        val authorization =
            try {
                withTimeoutOrNull(contextIssueTimeoutMillis) {
                    contextOwner.authorizeCurrent(request, notAfter)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
        if (
            authorization.learnerId != learnerId ||
            authorization.requestFingerprint != request.canonicalFingerprint ||
            authorization.expiresAtEpochMillis !in (now + 1L)..notAfter ||
            !ownerSaysCurrent(authorization)
        ) {
            return null
        }
        val issuanceToken =
            CanonicalSha256("core-data-tutor-open-response-issuance-v1")
                .field("request", request.canonicalFingerprint)
                .field("authorization", authorization.canonicalFingerprint)
                .field("issueGeneration", ticket)
                .finish()
        val context =
            CoreDataTutorOpenResponseLearningContext(
                learnerId = learnerId,
                request = request,
                presentationFingerprint = authorization.presentationFingerprint,
                problemFingerprint = authorization.problemFingerprint,
                problemFamilyFingerprint = authorization.problemFamilyFingerprint,
                expiresAtEpochMillis = authorization.expiresAtEpochMillis,
                issuanceToken = issuanceToken,
            )
        if (
            nowEpochMillis.getAsLong() >= authorization.expiresAtEpochMillis ||
            !ownerSaysCurrent(authorization)
        ) {
            return null
        }
        return synchronized(stateLock) {
            if (closed || issueGeneration != ticket) {
                null
            } else {
                currentIssued =
                    IssuedContext(
                        issueGeneration = ticket,
                        context = context,
                        authorization = authorization,
                    )
                context
            }
        }
    }

    override suspend fun submitAdmitted(
        submission: CoreDataTutorOpenResponseAdmittedSubmission,
        lease: CoreDataTutorOpenResponseSubmissionLease,
    ): CoreDataTutorOpenResponseLearningReceipt =
        submitAdmittedWithReceipt(submission, lease).disposition

    override suspend fun submitAdmittedWithReceipt(
        submission: CoreDataTutorOpenResponseAdmittedSubmission,
        lease: CoreDataTutorOpenResponseSubmissionLease,
    ): CoreDataTutorOpenResponseSubmissionResult {
        val issued = currentIssuedFor(submission, lease)
            ?: return CoreDataTutorOpenResponseSubmissionResult(
                CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
                null,
            )
        val scope = submission.toScope()
        val activeToken =
            currentScope.activate(
                issued = issued,
                scope = scope,
                lease = lease,
            ) ?: return CoreDataTutorOpenResponseSubmissionResult(
                CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
                null,
            )
        return try {
            val result =
                withTimeoutOrNull(submissionTimeoutMillis) {
                    coordinator.evaluate(
                        submission = submission.toCoordinatorSubmission(scope, issued),
                        questionDocument = submission.context.request.questionDocument,
                    )
                } ?: return CoreDataTutorOpenResponseSubmissionResult(
                    CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY,
                    null,
                )
            result.toHostSubmissionResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CoreDataTutorOpenResponseSubmissionResult(
                CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY,
                null,
            )
        } finally {
            currentScope.clear(activeToken)
        }
    }

    override fun revoke() {
        synchronized(stateLock) {
            if (closed) return
            issueGeneration += 1L
            currentIssued = null
        }
        currentScope.clear()
    }

    override fun close() {
        val shouldClose =
            synchronized(stateLock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    issueGeneration += 1L
                    currentIssued = null
                    true
                }
            }
        if (!shouldClose) return
        currentScope.clear()
        try {
            closeCandidateOwner()
        } catch (_: Exception) {
            // Closing is best effort; all host leases were already revoked above.
        }
    }

    private fun currentIssuedFor(
        submission: CoreDataTutorOpenResponseAdmittedSubmission,
        lease: CoreDataTutorOpenResponseSubmissionLease,
    ): IssuedContext? {
        val issued = synchronized(stateLock) { currentIssued } ?: return null
        val context = submission.context
        if (
            issued.context !== context ||
            issued.context.issuanceToken != context.issuanceToken ||
            context.expiresAtEpochMillis <= nowEpochMillis.getAsLong() ||
            lease.featureContextFingerprint != context.featureContextFingerprint ||
            lease.responseBinding != submission.responseBinding ||
            !lease.isCurrent() ||
            !ownerSaysCurrent(issued.authorization)
        ) {
            return null
        }
        return synchronized(stateLock) {
            issued.takeIf {
                !closed &&
                    currentIssued === issued &&
                    issued.issueGeneration == issueGeneration
            }
        }
    }

    private fun ownerSaysCurrent(
        authorization: CurrentTutorOpenResponseContextAuthorization,
    ): Boolean =
        try {
            contextOwner.isCurrent(authorization)
        } catch (_: Exception) {
            false
        }

    private inner class CurrentScopeGate : CurrentOpenResponseLearningScopeAuthorization {
        private val lock = Any()
        private var active: ActiveScope? = null
        private var generation = 0L

        fun activate(
            issued: IssuedContext,
            scope: CurrentOpenResponseLearningScope,
            lease: CoreDataTutorOpenResponseSubmissionLease,
        ): Long? =
            synchronized(lock) {
                if (
                    !isIssuedCurrent(issued) ||
                    !lease.isCurrent() ||
                    lease.featureContextFingerprint != issued.context.featureContextFingerprint ||
                    lease.responseBinding != scope.responseBinding
                ) {
                    null
                } else {
                    generation += 1L
                    active =
                        ActiveScope(
                            generation = generation,
                            issued = issued,
                            scope = scope,
                            lease = lease,
                        )
                    generation
                }
            }

        override fun currentEpoch(expected: CurrentOpenResponseLearningScope): Long? =
            synchronized(lock) { active }
                ?.takeIf { value ->
                    value.scope == expected &&
                        value.lease.isCurrent() &&
                        value.lease.responseBinding == expected.responseBinding &&
                        isActiveScopeCurrent(value)
                }
                ?.generation

        fun isCurrentFingerprint(scopeFingerprint: String): Boolean =
            synchronized(lock) { active }
                ?.takeIf { value ->
                    value.scope.canonicalFingerprint == scopeFingerprint &&
                        value.lease.isCurrent() &&
                        isActiveScopeCurrent(value)
                } != null

        suspend fun consumeCurrent(
            expected: CurrentOpenResponseLearningScope,
            candidateIdempotencyKey: String,
        ): Long? {
            val value = synchronized(lock) { active }
                ?.takeIf { activeScope ->
                    activeScope.scope == expected &&
                        activeScope.lease.responseBinding == expected.responseBinding
                } ?: return null
            if (!value.lease.isCurrent() || !isActiveScopeCurrent(value)) return null
            val disposition = if (value.lease.acceptedDurableClaim) {
                contextOwner.consumeClaimedAuthorization(
                    authorization = value.issued.authorization,
                    scope = expected,
                    candidateIdempotencyKey = candidateIdempotencyKey,
                )
            } else {
                contextOwner.consumeCurrentAuthorization(
                    authorization = value.issued.authorization,
                    scope = expected,
                    candidateIdempotencyKey = candidateIdempotencyKey,
                )
            }
            if (
                disposition != CurrentTutorOpenResponseAuthorizationConsumeDisposition.CONSUMED &&
                disposition != CurrentTutorOpenResponseAuthorizationConsumeDisposition.DUPLICATE
            ) {
                return null
            }
            val stillLocallyCurrent =
                synchronized(lock) { active === value } &&
                    value.lease.isCurrent() &&
                    isActiveScopeLocallyCurrent(value)
            return value.generation.takeIf { stillLocallyCurrent }
        }

        private fun isActiveScopeCurrent(value: ActiveScope): Boolean =
            if (value.lease.acceptedDurableClaim) {
                isIssuedClaimedCurrent(value.issued)
            } else {
                isIssuedCurrent(value.issued)
            }

        private fun isActiveScopeLocallyCurrent(value: ActiveScope): Boolean =
            if (value.lease.acceptedDurableClaim) {
                isIssuedLocallyCurrentAfterClaim(value.issued)
            } else {
                isIssuedLocallyCurrent(value.issued)
            }

        fun clear(expectedGeneration: Long? = null) {
            synchronized(lock) {
                if (
                    expectedGeneration == null ||
                    active?.generation == expectedGeneration
                ) {
                    active = null
                    generation += 1L
                }
            }
        }
    }

    private fun isIssuedCurrent(issued: IssuedContext): Boolean {
        return isIssuedLocallyCurrent(issued) && ownerSaysCurrent(issued.authorization)
    }

    private fun isIssuedClaimedCurrent(issued: IssuedContext): Boolean =
        isIssuedLocallyCurrentAfterClaim(issued) &&
            try {
                contextOwner.isClaimedCurrent(issued.authorization)
            } catch (_: Exception) {
                false
            }

    private fun isIssuedLocallyCurrent(issued: IssuedContext): Boolean {
        return isIssuedLocallyCurrentAfterClaim(issued) &&
            nowEpochMillis.getAsLong() < issued.context.expiresAtEpochMillis
    }


    private fun isIssuedLocallyCurrentAfterClaim(issued: IssuedContext): Boolean =
        synchronized(stateLock) {
            !closed &&
                currentIssued === issued &&
                issueGeneration == issued.issueGeneration
        }

    internal data class IssuedContext(
        val issueGeneration: Long,
        val context: CoreDataTutorOpenResponseLearningContext,
        val authorization: CurrentTutorOpenResponseContextAuthorization,
    )

    private data class ActiveScope(
        val generation: Long,
        val issued: IssuedContext,
        val scope: CurrentOpenResponseLearningScope,
        val lease: CoreDataTutorOpenResponseSubmissionLease,
    )

    companion object {
        fun assemble(
            evidence: LearnerBoundLearningEvidencePort,
            evaluator: IndependentOpenResponseEvaluator,
            candidateOwner: LearnerBoundOpenResponseWeakCandidateOwner,
            contextOwner: CurrentTutorOpenResponseContextOwner?,
            nowEpochMillis: LongSupplier,
            contextLifetimeMillis: Long = DEFAULT_CONTEXT_LIFETIME_MILLIS,
            contextIssueTimeoutMillis: Long = DEFAULT_CONTEXT_ISSUE_TIMEOUT_MILLIS,
            submissionTimeoutMillis: Long = DEFAULT_SUBMISSION_TIMEOUT_MILLIS,
        ): CoreDataTutorOpenResponseLearningAssemblyResult {
            if (contextOwner == null) {
                return CoreDataTutorOpenResponseLearningAssemblyResult.Unavailable(
                    CoreDataTutorOpenResponseLearningAssemblyUnavailableReason
                        .MISSING_CURRENT_CONTEXT_OWNER,
                )
            }
            if (
                evidence.learnerId != candidateOwner.learnerId ||
                evidence.learnerId != contextOwner.learnerId
            ) {
                return CoreDataTutorOpenResponseLearningAssemblyResult.Unavailable(
                    CoreDataTutorOpenResponseLearningAssemblyUnavailableReason
                        .INCONSISTENT_LEARNER_SCOPE,
                )
            }
            require(contextLifetimeMillis in 1L..MAX_CONTEXT_LIFETIME_MILLIS)
            require(contextIssueTimeoutMillis in 1L..MAX_CONTEXT_ISSUE_TIMEOUT_MILLIS)
            require(submissionTimeoutMillis in 1L..MAX_SUBMISSION_TIMEOUT_MILLIS)
            return CoreDataTutorOpenResponseLearningAssemblyResult.Available(
                CoreDataTutorOpenResponseLearningHostAdapter(
                    learnerId = evidence.learnerId,
                    contextOwner = contextOwner,
                    evidence = evidence,
                    evaluator = evaluator,
                    candidateOwner = candidateOwner,
                    nowEpochMillis = nowEpochMillis,
                    contextLifetimeMillis = contextLifetimeMillis,
                    contextIssueTimeoutMillis = contextIssueTimeoutMillis,
                    submissionTimeoutMillis = submissionTimeoutMillis,
                    closeCandidateOwner = {
                        (candidateOwner as? AutoCloseable)?.close()
                    },
                ),
            )
        }

        private const val DEFAULT_CONTEXT_LIFETIME_MILLIS = 2L * 60L * 1_000L
        private const val DEFAULT_CONTEXT_ISSUE_TIMEOUT_MILLIS = 5_000L
        private const val DEFAULT_SUBMISSION_TIMEOUT_MILLIS = 2L * 60L * 1_000L
        private const val MAX_CONTEXT_LIFETIME_MILLIS = 5L * 60L * 1_000L
        private const val MAX_CONTEXT_ISSUE_TIMEOUT_MILLIS = 30_000L
        private const val MAX_SUBMISSION_TIMEOUT_MILLIS = 5L * 60L * 1_000L
    }
}

private fun CoreDataTutorOpenResponseAdmittedSubmission.toScope():
    CurrentOpenResponseLearningScope =
    CurrentOpenResponseLearningScope(
        learnerId = context.learnerId,
        conversationId = context.request.conversationId,
        conversationGeneration = context.request.conversationGeneration,
        conversationStateVersion = context.request.conversationStateVersion,
        questionDocumentId = context.request.questionDocumentId,
        questionRevisionNumber = context.request.questionRevisionNumber,
        subject = context.request.subject,
        questionFingerprint = context.request.questionFingerprint,
        responseBinding = responseBinding,
        evidenceRequestId = context.request.evidenceRequestId,
        modeVersion = context.request.modeVersion,
        turnReferenceId = context.request.turnReferenceId,
        turnOrdinal = context.request.turnOrdinal,
        turnGeneration = context.request.turnGeneration,
        attemptOrdinal = context.request.attemptOrdinal,
        hintCount = context.request.hintCount,
        answerWasRevealed = context.request.answerWasRevealed,
        requestVersion = context.request.requestVersion,
    )

private fun CoreDataTutorOpenResponseAdmittedSubmission.toCoordinatorSubmission(
    scope: CurrentOpenResponseLearningScope,
    issued: CoreDataTutorOpenResponseLearningHostAdapter.IssuedContext,
): OpenResponseLearningSubmission {
    val authorization = issued.authorization
    return OpenResponseLearningSubmission(
        scope = scope,
        submissionId = stableSubmissionId,
        presentationFingerprint = context.presentationFingerprint,
        problemFingerprint = context.problemFingerprint,
        problemFamilyFingerprint = context.problemFamilyFingerprint,
        attributionPolicyVersion = authorization.attributionPolicyVersion,
        responsePolicyVersion = authorization.responsePolicyVersion,
        rubricCanonicalFingerprint = authorization.rubricCanonicalFingerprint,
        currentAnswer = currentAnswer,
        verifiedKnowledgeProofs = authorization.verifiedKnowledgeProofs,
        teachingReferences = authorization.teachingReferences,
        evaluator = authorization.evaluator,
        evaluatorPolicyFingerprint = authorization.evaluatorPolicyFingerprint,
        prohibitedEvaluatorExecutionFingerprint =
            authorization.prohibitedEvaluatorExecutionFingerprint,
        elapsedDurationMillis = elapsedDurationMillis,
        occurredAtEpochMillis = occurredAtEpochMillis,
        evaluationTimeoutMillis =
            MAX_COORDINATOR_EVALUATION_TIMEOUT_MILLIS,
    )
}

private fun OpenResponseLearningResult.toHostSubmissionResult():
    CoreDataTutorOpenResponseSubmissionResult =
    when (this) {
        OpenResponseLearningResult.StaleBeforeSourceFact ->
            hostSubmissionResult(CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT)
        is OpenResponseLearningResult.SourceFactUnavailable ->
            hostSubmissionResult(CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY)
        is OpenResponseLearningResult.SourceFactRejected ->
            hostSubmissionResult(CoreDataTutorOpenResponseLearningReceipt.REJECTED)
        is OpenResponseLearningResult.PendingEvaluation ->
            when (reason) {
                PendingOpenResponseReason.STALE_SCOPE ->
                    hostSubmissionResult(CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT)
                PendingOpenResponseReason.OWNER_REJECTED ->
                    hostSubmissionResult(CoreDataTutorOpenResponseLearningReceipt.REJECTED)
                PendingOpenResponseReason.TIMEOUT,
                PendingOpenResponseReason.PROVIDER_UNAVAILABLE,
                PendingOpenResponseReason.INVALID_MODEL_OUTPUT,
                PendingOpenResponseReason.UNSCORABLE,
                PendingOpenResponseReason.OWNER_CONFLICT,
                PendingOpenResponseReason.OWNER_UNAVAILABLE,
                -> hostSubmissionResult(
                    CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY,
                )
            }
        is OpenResponseLearningResult.WeakCandidateQueued ->
            CoreDataTutorOpenResponseSubmissionResult(
                disposition =
                    if (disposition == OpenResponseWeakCandidateDisposition.DUPLICATE) {
                        CoreDataTutorOpenResponseLearningReceipt.DUPLICATE
                    } else {
                        CoreDataTutorOpenResponseLearningReceipt.PENDING
                    },
                candidateCommitReceipt =
                    CoreDataTutorOpenResponseCandidateCommitReceipt(
                        candidateIdempotencyKey = candidateIdempotencyKey,
                        receiptFingerprint = candidateReceiptFingerprint,
                    ),
            )
    }

private fun hostSubmissionResult(
    disposition: CoreDataTutorOpenResponseLearningReceipt,
) = CoreDataTutorOpenResponseSubmissionResult(disposition, null)

private fun safeDeadline(
    nowEpochMillis: Long,
    lifetimeMillis: Long,
): Long {
    require(nowEpochMillis >= 0L)
    return if (Long.MAX_VALUE - nowEpochMillis < lifetimeMillis) {
        Long.MAX_VALUE
    } else {
        nowEpochMillis + lifetimeMillis
    }
}

private fun requireHostIdentity(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_HOST_IDENTITY_CHARS &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value"
    }
}

private fun requireHostVersion(
    value: String,
    label: String,
) {
    requireHostIdentity(value, label)
    require(HOST_VERSION_PATTERN.matches(value)) {
        "$label contains unsupported characters"
    }
}

private fun requireHostFingerprint(
    value: String,
    label: String,
) {
    require(HOST_FINGERPRINT_PATTERN.matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private const val MAX_COORDINATOR_EVALUATION_TIMEOUT_MILLIS = 90_000L
private const val MAX_HOST_IDENTITY_CHARS = 256
private val HOST_VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")
private val HOST_FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
