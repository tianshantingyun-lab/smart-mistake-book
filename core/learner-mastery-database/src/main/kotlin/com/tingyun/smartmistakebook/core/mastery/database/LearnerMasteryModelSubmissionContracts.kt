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

internal enum class ModelSubmissionCandidateClaim {
    FIRST,
    IDEMPOTENT_RETRY,
    LOGICAL_ATTEMPT_CONFLICT,
}

internal enum class ModelSubmissionTerminalReason {
    ADMITTED,
    DUPLICATE,
    MALFORMED_SUBMISSION,
    LOGICAL_ATTEMPT_CONFLICT,
    PERMISSION_EPOCH_REVOKED,
    PERMISSION_SCOPE_MISMATCH,
    CANDIDATE_IDEMPOTENCY_CONFLICT,
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
}

internal data class ModelSubmissionAttemptReceipt(
    val receiptFingerprint: String,
) {
    init {
        requireMasteryFingerprint(
            receiptFingerprint,
            "Model submission attempt receipt fingerprint",
        )
    }
}

internal sealed interface ModelSubmissionAttemptOutcome {
    data class Terminal(
        val receipt: ModelSubmissionAttemptReceipt,
    ) : ModelSubmissionAttemptOutcome

    object RebuildPending : ModelSubmissionAttemptOutcome
}

/**
 * Complete host-attested scope for one model write permission epoch.
 *
 * Only bounded identifiers, fingerprints, and monotonic versions cross this boundary. The model
 * cannot supply this object, and no prompt, answer text, or image content is retained in it.
 */
class LearnerMasteryModelRequestScope(
    val subject: SubjectKind,
    val sourceFactId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationState: String,
    val conversationStateVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val problemRevisionFingerprint: String?,
    val problemDocumentFingerprint: String?,
    val problemFingerprint: String?,
    val problemFingerprintVersion: String?,
    val bindingSetVersion: Long?,
    val knowledgeManifestFingerprint: String?,
    val knowledgeActivationGeneration: Long?,
    val sourcePolicyVersion: String,
    val projectionPolicyVersion: String,
    val modelVersion: String,
    val requestVersion: String,
    val modeVersion: String,
    val learningWritePermissionVersion: String,
) {
    init {
        requireSpecificSubject(subject)
        requireMasteryIdentity(sourceFactId, "Source fact id")
        requireMasteryIdentity(conversationId, "Conversation id")
        require(conversationGeneration >= 0L) {
            "Conversation generation must be non-negative"
        }
        requireMasteryOpaqueReference(conversationState, "Conversation state")
        require(conversationStateVersion >= 0L) {
            "Conversation state version must be non-negative"
        }
        requireMasteryOpaqueReference(turnReferenceId, "Tutor turn reference")
        require(turnOrdinal >= 0) { "Tutor turn ordinal must be non-negative" }
        problemRevisionFingerprint?.let {
            requireMasteryFingerprint(it, "Problem revision fingerprint")
        }
        problemDocumentFingerprint?.let {
            requireMasteryFingerprint(it, "Problem document fingerprint")
        }
        problemFingerprint?.let {
            requireMasteryFingerprint(it, "Ephemeral problem fingerprint")
        }
        problemFingerprintVersion?.let {
            requireMasteryVersion(it, "Problem fingerprint version")
        }
        bindingSetVersion?.let {
            require(it > 0L) { "Problem binding-set version must be positive" }
        }
        knowledgeManifestFingerprint?.let {
            requireMasteryFingerprint(it, "Knowledge manifest fingerprint")
        }
        knowledgeActivationGeneration?.let {
            require(it > 0L) { "Knowledge activation generation must be positive" }
        }
        requireMasteryVersion(sourcePolicyVersion, "Source policy version")
        requireMasteryVersion(projectionPolicyVersion, "Projection policy version")
        requireMasteryVersion(modelVersion, "Attribution model version")
        requireMasteryOpaqueReference(requestVersion, "Model request version")
        requireMasteryOpaqueReference(modeVersion, "Tutor mode version")
        requireMasteryOpaqueReference(
            learningWritePermissionVersion,
            "Learning-write permission version",
        )
        val savedProblem = problemRevisionFingerprint != null
        val ephemeralProblem = problemFingerprint != null
        require(savedProblem.xor(ephemeralProblem)) {
            "A model request must bind exactly one saved or ephemeral problem identity"
        }
        if (savedProblem) {
            require(
                problemDocumentFingerprint != null &&
                    bindingSetVersion != null &&
                    problemFingerprintVersion == null &&
                    knowledgeManifestFingerprint == null &&
                    knowledgeActivationGeneration == null,
            ) {
                "A saved-problem model request requires revision, document, and binding versions only"
            }
        } else {
            require(
                problemDocumentFingerprint == null &&
                    bindingSetVersion == null &&
                    problemFingerprintVersion != null &&
                    knowledgeManifestFingerprint != null &&
                    knowledgeActivationGeneration != null,
            ) {
                "An ephemeral-problem model request requires fingerprint and knowledge activation only"
            }
        }
    }
}

internal class ModelSubmissionAttemptScope(
    val learnerId: String,
    val permission: LearnerMasteryModelRequestScope,
) {
    val subject: SubjectKind
        get() = permission.subject
    val sourceFactId: String
        get() = permission.sourceFactId
    val modelVersion: String
        get() = permission.modelVersion
    val requestVersion: String
        get() = permission.requestVersion
    val modeVersion: String
        get() = permission.modeVersion

    val logicalRequestFingerprint: String
    val requestGenerationFingerprint: String

    init {
        requireMasteryIdentity(learnerId, "Learner id")
        logicalRequestFingerprint =
            CanonicalSha256("learner-mastery-model-logical-request-v2")
                .field("learnerId", learnerId)
                .field("subject", subject.name)
                .field("sourceFactId", sourceFactId)
                .field("modelVersion", modelVersion)
                .field("requestVersion", requestVersion)
                .field("conversationId", permission.conversationId)
                .field("conversationGeneration", permission.conversationGeneration)
                .field("turnReferenceId", permission.turnReferenceId)
                .field("turnOrdinal", permission.turnOrdinal)
                .nullableField("problemRevision", permission.problemRevisionFingerprint)
                .nullableField("problemFingerprint", permission.problemFingerprint)
                .finish()
        requestGenerationFingerprint =
            CanonicalSha256("learner-mastery-model-request-generation-v2")
                .field("logicalRequest", logicalRequestFingerprint)
                .field("learnerId", learnerId)
                .field("subject", subject.name)
                .field("sourceFactId", sourceFactId)
                .field("modelVersion", modelVersion)
                .field("requestVersion", requestVersion)
                .field("conversationId", permission.conversationId)
                .field("conversationGeneration", permission.conversationGeneration)
                .field("conversationState", permission.conversationState)
                .field("conversationStateVersion", permission.conversationStateVersion)
                .field("turnReferenceId", permission.turnReferenceId)
                .field("turnOrdinal", permission.turnOrdinal)
                .nullableField("problemRevision", permission.problemRevisionFingerprint)
                .nullableField("problemDocument", permission.problemDocumentFingerprint)
                .nullableField("problemFingerprint", permission.problemFingerprint)
                .nullableField(
                    "problemFingerprintVersion",
                    permission.problemFingerprintVersion,
                )
                .nullableLongField("bindingSetVersion", permission.bindingSetVersion)
                .nullableField("knowledgeManifest", permission.knowledgeManifestFingerprint)
                .nullableLongField(
                    "knowledgeActivationGeneration",
                    permission.knowledgeActivationGeneration,
                )
                .field("sourcePolicyVersion", permission.sourcePolicyVersion)
                .field("projectionPolicyVersion", permission.projectionPolicyVersion)
                .field("modeVersion", modeVersion)
                .field(
                    "learningWritePermissionVersion",
                    permission.learningWritePermissionVersion,
                )
                .finish()
    }

    fun proposalFingerprint(semanticProposalFingerprint: String): String {
        requireMasteryFingerprint(
            semanticProposalFingerprint,
            "Semantic model proposal fingerprint",
        )
        return CanonicalSha256("learner-mastery-model-submission-proposal-v2")
            .field("requestGeneration", requestGenerationFingerprint)
            .field("learnerId", learnerId)
            .field("subject", subject.name)
            .field("sourceFactId", sourceFactId)
            .field("modelVersion", modelVersion)
            .field("requestVersion", requestVersion)
            .field("modeVersion", modeVersion)
            .field("semanticProposal", semanticProposalFingerprint)
            .finish()
    }
}

internal fun interface ModelSubmissionCommitAuthorization {
    fun authorizeCommit()
}

internal class ModelSubmissionPermissionEpochRevokedException :
    IllegalStateException("Model mastery permission epoch is no longer current")

internal class ModelSubmissionAttempt(
    val scope: ModelSubmissionAttemptScope,
    val modeVersion: String,
    val proposalFingerprint: String,
    val claim: ModelSubmissionCandidateClaim,
    private val commitAuthorization: ModelSubmissionCommitAuthorization,
    private val onPermissionEpochRollback: () -> Unit,
    private val onFinished: () -> Unit,
) {
    private val finished = AtomicBoolean(false)

    init {
        requireMasteryOpaqueReference(modeVersion, "Tutor mode version")
        requireMasteryFingerprint(proposalFingerprint, "Model proposal fingerprint")
    }

    fun authorizeCommit() {
        commitAuthorization.authorizeCommit()
    }

    fun rollbackPermissionEpochClaim() {
        onPermissionEpochRollback()
    }

    fun finish() {
        if (finished.compareAndSet(false, true)) {
            onFinished()
        }
    }
}

class ModelKnowledgeMasteryDigestItem(
    val knowledgeNode: KnowledgeNodeRef,
    val state: KnowledgeMasteryState,
    val trend: KnowledgeMasteryTrend,
)

class ModelSubjectMasteryDigest(
    val subject: SubjectKind,
    items: List<ModelKnowledgeMasteryDigestItem>,
) {
    val items: List<ModelKnowledgeMasteryDigestItem> =
        Collections.unmodifiableList(items.toList())

    init {
        requireSpecificSubject(subject)
        require(this.items.size <= LearnerMasteryReader.MAX_DIGEST_FOCUS_LIMIT) {
            "Model mastery digest exceeds the knowledge-point budget"
        }
        require(this.items.all { item -> item.knowledgeNode.subject == subject }) {
            "Model mastery digest crossed its subject scope"
        }
    }
}

/**
 * A subject-scoped, bounded model digest. The scope is fixed when the capability is created, so
 * the model cannot select another learner or subject and cannot access the analytics timeline.
 */
interface SubjectMasteryDigestReader {
    suspend fun queryDigest(
        focusLimit: Int = LearnerMasteryReader.DEFAULT_DIGEST_FOCUS_LIMIT,
    ): ModelSubjectMasteryDigest
}

/**
 * Learner-bound read capability for local orchestration.
 *
 * This capability is deliberately separate from [LearnerMasteryModelAccess]. Requests cannot
 * select a learner, and results expose only categorical mastery context.
 */
interface LocalMasteryContextReader {
    suspend fun queryContext(
        request: LocalMasteryContextRequest,
    ): LocalMasteryContext
}

class LearnerMasteryModelAccess private constructor(
    val candidateSink: LearnerMasteryCandidateSink,
    val digestReader: SubjectMasteryDigestReader,
) {
    internal companion object {
        fun create(
            candidateSink: LearnerMasteryCandidateSink,
            digestReader: SubjectMasteryDigestReader,
        ): LearnerMasteryModelAccess =
            LearnerMasteryModelAccess(
                candidateSink = candidateSink,
                digestReader = digestReader,
            )
    }
}

/**
 * Host-owned request/mode epoch.
 *
 * The production provider owns exactly one instance for its entire lifetime. Activating a newer
 * request or explanation mode invalidates every older generation, even when a caller forgets to
 * close its lease. A submission permit is held until the Room transaction and any rollback audit
 * finish, so revocation drains old writes before returning. This is intentionally a single-process
 * host gate; neither the gate nor its generations cross the module boundary.
 */
internal class LearnerMasteryModelRequestGate private constructor() {
    private val lock = Any()
    private val current = AtomicReference<Generation?>(null)
    private var retainedRequest: RequestGeneration? = null
    private val retiredRequestVersions = mutableSetOf<String>()
    private var terminated = false

    fun activate(
        scope: ModelSubmissionAttemptScope,
        modeVersion: String,
    ): Generation {
        requireMasteryOpaqueReference(modeVersion, "Tutor mode version")
        require(modeVersion == scope.modeVersion) {
            "Tutor mode version must match its host-attested permission scope"
        }
        return synchronized(lock) {
            check(!terminated) {
                "Model mastery host handoff is closed"
            }
            val previous = retainedRequest
            val request =
                if (previous?.scope?.requestVersion == scope.requestVersion) {
                    check(
                        previous.scope.logicalRequestFingerprint ==
                            scope.logicalRequestFingerprint,
                    ) {
                        "A model request version cannot change its bound source scope"
                    }
                    previous
                } else {
                    check(scope.requestVersion !in retiredRequestVersions) {
                        "A superseded model request cannot be reopened"
                    }
                    previous?.let { request ->
                        check(retiredRequestVersions.size < MAX_RETIRED_REQUEST_VERSIONS) {
                            "Model mastery request history budget is exhausted"
                        }
                        retiredRequestVersions += request.scope.requestVersion
                    }
                    RequestGeneration(scope)
                }
            current.get()?.revokeAndAwait()
            val generation =
                Generation.create(
                    owner = this,
                    request = request,
                    scope = scope,
                    modeVersion = modeVersion,
                )
            retainedRequest = request
            current.set(generation)
            generation
        }
    }

    fun invalidate() {
        synchronized(lock) {
            current.get()?.revokeAndAwait()
            current.set(null)
        }
    }

    fun terminate() {
        synchronized(lock) {
            terminated = true
            current.get()?.revoke()
            current.set(null)
            retainedRequest = null
            retiredRequestVersions.clear()
        }
        awaitSubmissionDrain()
    }

    internal fun isCurrent(generation: Generation): Boolean = current.get() === generation

    private val submissionsInFlight = AtomicInteger(0)
    private val submissionDrainMonitor = java.lang.Object()

    private fun submissionStarted() {
        submissionsInFlight.incrementAndGet()
    }

    private fun submissionFinished() {
        val remaining = submissionsInFlight.decrementAndGet()
        check(remaining >= 0) { "Model mastery submission permit underflow" }
        if (remaining == 0) {
            synchronized(submissionDrainMonitor) {
                submissionDrainMonitor.notifyAll()
            }
        }
    }

    private fun awaitSubmissionDrain() {
        synchronized(submissionDrainMonitor) {
            while (submissionsInFlight.get() != 0) {
                submissionDrainMonitor.wait()
            }
        }
    }

    class Generation private constructor(
        private val owner: LearnerMasteryModelRequestGate,
        private val request: RequestGeneration,
        val scope: ModelSubmissionAttemptScope,
        val modeVersion: String,
    ) {
        private val permissionEpochLock = Any()
        private var revoked = false
        private val submissionsInFlight = AtomicInteger(0)
        private val submissionDrainMonitor = java.lang.Object()

        internal fun isCurrent(): Boolean =
            synchronized(permissionEpochLock) {
                !revoked && owner.isCurrent(this)
            }

        internal fun claimCandidate(
            semanticProposalFingerprint: String,
            leaseCurrent: () -> Unit,
        ): ModelSubmissionAttempt {
            val proposalFingerprint = scope.proposalFingerprint(semanticProposalFingerprint)
            synchronized(permissionEpochLock) {
                authorizeCurrent(leaseCurrent)
                submissionsInFlight.incrementAndGet()
                owner.submissionStarted()
            }
            return try {
                val claim = request.claimCandidate(proposalFingerprint)
                ModelSubmissionAttempt(
                    scope = scope,
                    modeVersion = modeVersion,
                    proposalFingerprint = proposalFingerprint,
                    claim = claim,
                    commitAuthorization =
                        ModelSubmissionCommitAuthorization {
                            synchronized(permissionEpochLock) {
                                authorizeCurrent(leaseCurrent)
                            }
                        },
                    onPermissionEpochRollback = {
                        if (claim == ModelSubmissionCandidateClaim.FIRST) {
                            request.releaseCandidate(proposalFingerprint)
                        }
                    },
                    onFinished = ::submissionFinished,
                )
            } catch (error: Throwable) {
                submissionFinished()
                throw error
            }
        }

        internal fun acquireDigestRead() = request.acquireDigestRead()

        internal fun revoke() {
            synchronized(permissionEpochLock) {
                revoked = true
            }
        }

        internal fun revokeAndAwait() {
            revoke()
            synchronized(submissionDrainMonitor) {
                while (submissionsInFlight.get() != 0) {
                    submissionDrainMonitor.wait()
                }
            }
        }

        private fun submissionFinished() {
            val remaining = submissionsInFlight.decrementAndGet()
            check(remaining >= 0) { "Model mastery generation permit underflow" }
            owner.submissionFinished()
            if (remaining == 0) {
                synchronized(submissionDrainMonitor) {
                    submissionDrainMonitor.notifyAll()
                }
            }
        }

        private fun authorizeCurrent(leaseCurrent: () -> Unit) {
            if (revoked || !isCurrent()) {
                throw ModelSubmissionPermissionEpochRevokedException()
            }
            try {
                leaseCurrent()
            } catch (_: IllegalStateException) {
                throw ModelSubmissionPermissionEpochRevokedException()
            }
        }

        internal companion object {
            fun create(
                owner: LearnerMasteryModelRequestGate,
                request: RequestGeneration,
                scope: ModelSubmissionAttemptScope,
                modeVersion: String,
            ): Generation =
                Generation(
                    owner = owner,
                    request = request,
                    scope = scope,
                    modeVersion = modeVersion,
                )
        }
    }

    internal class RequestGeneration(
        val scope: ModelSubmissionAttemptScope,
    ) {
        private val candidateProposal = AtomicReference<String?>(null)
        private val digestReadsRemaining = AtomicInteger(MAX_DIGEST_READS)

        fun claimCandidate(proposalFingerprint: String): ModelSubmissionCandidateClaim {
            while (true) {
                val existing = candidateProposal.get()
                if (existing != null) {
                    return if (existing == proposalFingerprint) {
                        ModelSubmissionCandidateClaim.IDEMPOTENT_RETRY
                    } else {
                        ModelSubmissionCandidateClaim.LOGICAL_ATTEMPT_CONFLICT
                    }
                }
                if (candidateProposal.compareAndSet(null, proposalFingerprint)) {
                    return ModelSubmissionCandidateClaim.FIRST
                }
            }
        }

        fun releaseCandidate(proposalFingerprint: String) {
            candidateProposal.compareAndSet(proposalFingerprint, null)
        }

        fun acquireDigestRead() {
            while (true) {
                val remaining = digestReadsRemaining.get()
                check(remaining > 0) {
                    "Model mastery digest read budget is exhausted"
                }
                if (digestReadsRemaining.compareAndSet(remaining, remaining - 1)) {
                    return
                }
            }
        }
    }

    internal companion object {
        const val MAX_DIGEST_READS = 4
        private const val MAX_RETIRED_REQUEST_VERSIONS = 4_096

        fun create(): LearnerMasteryModelRequestGate = LearnerMasteryModelRequestGate()
    }
}

/**
 * Host-owned lifetime for one model request and explanation mode.
 *
 * Only the narrow [access] object is handed to a model adapter. The local host retains this lease
 * and its request gate. Closing the lease, activating a newer request/mode generation, exhausting
 * an operation budget, or exceeding the fixed lifetime all fail before store access.
 */
class LearnerMasteryModelAccessLease private constructor(
    val access: LearnerMasteryModelAccess,
    private val gate: LearnerMasteryModelAccessLeaseGate,
) : AutoCloseable {
    override fun close() {
        gate.revoke()
    }

    internal companion object {
        fun create(
            access: LearnerMasteryModelAccess,
            gate: LearnerMasteryModelAccessLeaseGate,
        ): LearnerMasteryModelAccessLease =
            LearnerMasteryModelAccessLease(
                access = access,
                gate = gate,
            )
    }
}

internal class LearnerMasteryModelAccessLeaseGate(
    private val requestGeneration: LearnerMasteryModelRequestGate.Generation,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val active = AtomicBoolean(true)
    private val openedAtNanos: Long
    val sourceFactId: String
        get() = requestGeneration.scope.sourceFactId

    init {
        openedAtNanos = nowNanos()
    }

    fun acquireCandidateSubmission(
        semanticProposalFingerprint: String,
    ): ModelSubmissionAttempt {
        ensureCurrent()
        val attempt =
            requestGeneration.claimCandidate(
                semanticProposalFingerprint = semanticProposalFingerprint,
                leaseCurrent = ::ensureLeaseCurrent,
            )
        return try {
            ensureCurrent()
            attempt
        } catch (error: Throwable) {
            attempt.rollbackPermissionEpochClaim()
            attempt.finish()
            throw error
        }
    }

    fun acquireDigestRead() {
        ensureCurrent()
        requestGeneration.acquireDigestRead()
        ensureCurrent()
    }

    fun revoke() {
        requestGeneration.revokeAndAwait()
        active.compareAndSet(true, false)
    }

    fun confirmCurrent() {
        ensureCurrent()
    }

    private fun ensureCurrent() {
        check(active.get()) {
            "Model mastery access is no longer current"
        }
        check(requestGeneration.isCurrent()) {
            "Model mastery request or mode is no longer current"
        }
        ensureLeaseCurrent()
    }

    private fun ensureLeaseCurrent() {
        check(active.get()) {
            "Model mastery access is no longer current"
        }
        val elapsedNanos = nowNanos() - openedAtNanos
        check(elapsedNanos in 0..MAX_LEASE_DURATION_NANOS) {
            "Model mastery access lease has expired"
        }
    }

    internal companion object {
        const val MAX_CANDIDATE_WRITES = 1
        const val MAX_DIGEST_READS = LearnerMasteryModelRequestGate.MAX_DIGEST_READS
        val MAX_LEASE_DURATION_NANOS: Long = TimeUnit.MINUTES.toNanos(2)
    }
}

/**
 * Bounded semantic proposal accepted from a model-facing adapter.
 *
 * Every proposed node must carry a problem binding already authorized by a trusted local source
 * fact. This deliberately fails closed until a separate catalog-resolution proof exists for
 * learning evidence that is not attached to a persisted problem revision.
 */
class SubmitLearningObservationCandidateCommand(
    proposedAttributions: List<ProposedKnowledgeAttribution>,
    val confidence: MasteryCandidateConfidence,
) {
    val proposedAttributions: List<ProposedKnowledgeAttribution> =
        Collections.unmodifiableList(proposedAttributions.toList())

    init {
        require(this.proposedAttributions.size <= MAX_ATTRIBUTIONS) {
            "Observation candidate exceeds the attribution budget"
        }
        require(
            this.proposedAttributions
                .map { it.knowledgeNode.canonicalFingerprint }
                .distinct()
                .size == this.proposedAttributions.size,
        ) {
            "Observation candidate may attribute each knowledge node at most once"
        }
        require(this.proposedAttributions.all { it.problemBinding != null }) {
            "Model attribution requires a locally authorized problem binding"
        }
    }

    internal val canonicalAttributions: List<ProposedKnowledgeAttribution>
        get() = proposedAttributions.sortedBy { it.knowledgeNode.canonicalFingerprint }

    internal val canonicalFingerprint: String
        get() {
            val digest = CanonicalSha256("learner-mastery-model-candidate-v2")
                .field("confidence", confidence.name)
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

