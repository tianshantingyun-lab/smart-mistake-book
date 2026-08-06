package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Collections
import kotlin.jvm.JvmSynthetic

/**
 * The production owner Interface for one learner in [LEARNER_MASTERY_DATABASE_NAME].
 *
 * The learner scope is fixed when the runtime is opened. Callers submit observation facts and
 * stable evidence references; the Module derives evidence authority, outcome, numeric mass,
 * projection state and policy versions locally.
 */
internal interface LearnerMasteryAuthority : AutoCloseable {
    val learnerId: String

    val relay: LearnerMasteryRelayCapability

    val localContextReader: LocalMasteryContextReader

    val displayReader: LearnerMasteryDisplayReader

    val evidenceReviewCapability: LearnerMasteryEvidenceReviewCapability

    val evidenceCorrectionCapability: LearnerMasteryEvidenceCorrectionCapability

    suspend fun recordObservation(
        command: RecordTrustedLearningObservationCommand,
    ): TrustedLearningObservationResult

    suspend fun enqueuePendingOpenResponse(
        facts: PendingOpenResponseFacts,
    ): PendingOpenResponseResult

    fun authorizeEphemeralKnowledge(
        proof: VerifiedKnowledgeReferenceProof,
    ): VerifiedEphemeralKnowledgeEvidence

    fun scopedModelAccess(
        subject: SubjectKind,
        modelVersion: String,
        leaseGate: LearnerMasteryModelAccessLeaseGate,
    ): LearnerMasteryModelAccess

    suspend fun queryDigest(
        subject: SubjectKind,
        focusLimit: Int = DEFAULT_DIGEST_FOCUS_LIMIT,
    ): SubjectMasteryDigest

    suspend fun queryTimeline(
        subject: SubjectKind,
        sinceEpochMillis: Long = 0L,
        dayLimit: Int = DEFAULT_TIMELINE_DAY_LIMIT,
    ): List<SubjectMasteryTimelineEntry>

    suspend fun migrateLegacyFacts(
        batch: LegacyMasteryFactMigrationBatch,
    ): LegacyMasteryFactMigrationResult

    suspend fun eraseAllLearnerData(): LearnerMasteryEraseResult

    companion object {
        const val DEFAULT_DIGEST_FOCUS_LIMIT = LearnerMasteryReader.DEFAULT_DIGEST_FOCUS_LIMIT
        const val MAX_DIGEST_FOCUS_LIMIT = LearnerMasteryReader.MAX_DIGEST_FOCUS_LIMIT
        const val DEFAULT_TIMELINE_DAY_LIMIT = LearnerMasteryReader.DEFAULT_TIMELINE_DAY_LIMIT
        const val MAX_TIMELINE_DAY_LIMIT = LearnerMasteryReader.MAX_TIMELINE_DAY_LIMIT
        const val DEFAULT_RELAY_BATCH_SIZE =
            LearnerMasteryRelayCapability.DEFAULT_RELAY_BATCH_SIZE
        const val MAX_RELAY_BATCH_SIZE = LearnerMasteryRelayCapability.MAX_RELAY_BATCH_SIZE
    }
}

enum class TrustedLearningObservationSource {
    IMPORTED_MISTAKE,
    TUTOR_CHOICE,
    TUTOR_FREE_RESPONSE,
    TUTOR_VISUAL_TARGET,
    TUTOR_SPECIFIC_STUCK,
    SAVED_PROBLEM_REVIEW,
}

enum class TrustedLearningResponseForm {
    MULTIPLE_CHOICE,
    FREE_RESPONSE,
    VISUAL_TARGET,
    STUCK_REPORT,
    IMPORTED_RECORD,
}

/**
 * Describes how a local Adapter verified the submitted facts. Numeric trust remains local policy.
 */
enum class TrustedLearningVerification {
    DEVICE_OBSERVED,
    DETERMINISTIC_RUBRIC,
    MODEL_REVIEWED,
    SELF_REPORTED,
}

data class TrustedReviewAttemptEvidence(
    val reviewSessionId: String,
    val reviewQueueItemId: String,
    val submissionId: String,
) {
    init {
        requireMasteryOpaqueReference(reviewSessionId, "Review session id")
        requireMasteryOpaqueReference(reviewQueueItemId, "Review queue-item id")
        requireMasteryOpaqueReference(submissionId, "Review submission id")
    }

    internal val canonicalFingerprint: String
        get() = CanonicalSha256("learner-mastery-review-attempt-evidence-v1")
            .field("reviewSessionId", reviewSessionId)
            .field("reviewQueueItemId", reviewQueueItemId)
            .field("submissionId", submissionId)
            .finish()
}

sealed interface TrustedLearningContext {
    val problemFamilyFingerprint: String

    val canonicalFingerprint: String
}

class SavedMistakeLearningContext(
    val problemRevision: StudentProblemRevisionRef,
    override val problemFamilyFingerprint: String,
    val reviewAttempt: TrustedReviewAttemptEvidence? = null,
    knowledgeEvidenceBindings: List<ProblemKnowledgeBindingRef> = emptyList(),
) : TrustedLearningContext {
    val knowledgeEvidenceBindings: List<ProblemKnowledgeBindingRef> =
        Collections.unmodifiableList(
            knowledgeEvidenceBindings
                .sortedBy(ProblemKnowledgeBindingRef::canonicalFingerprint)
                .toList(),
        )

    init {
        requireMasteryFingerprint(problemFamilyFingerprint, "Problem-family fingerprint")
        require(this.knowledgeEvidenceBindings.size <= MAX_KNOWLEDGE_EVIDENCE_BINDINGS) {
            "Knowledge evidence exceeds the supported binding budget"
        }
        require(
            this.knowledgeEvidenceBindings
                .map(ProblemKnowledgeBindingRef::canonicalFingerprint)
                .distinct()
                .size == this.knowledgeEvidenceBindings.size,
        ) {
            "Knowledge evidence bindings must be unique"
        }
        require(
            this.knowledgeEvidenceBindings
                .map { it.knowledgeNode.canonicalFingerprint }
                .distinct()
                .size == this.knowledgeEvidenceBindings.size,
        ) {
            "Knowledge evidence may bind each knowledge node at most once"
        }
        require(
            this.knowledgeEvidenceBindings.all { binding ->
                binding.problemRevision == problemRevision
            },
        ) {
            "Knowledge evidence must reference the saved problem revision"
        }
    }

    override val canonicalFingerprint: String
        get() {
            val digest = CanonicalSha256("learner-mastery-saved-mistake-context-v1")
                .field("problemRevision", problemRevision.canonicalFingerprint)
                .field("problemFamilyFingerprint", problemFamilyFingerprint)
                .nullableField("reviewAttempt", reviewAttempt?.canonicalFingerprint)
                .field("bindingCount", knowledgeEvidenceBindings.size)
            knowledgeEvidenceBindings.forEachIndexed { index, binding ->
                digest.field("binding[$index]", binding.canonicalFingerprint)
            }
            return digest.finish()
        }

    private companion object {
        const val MAX_KNOWLEDGE_EVIDENCE_BINDINGS = 32
    }
}

/**
 * An uncollected tutor problem retains only fingerprints, bounded turn/submission references,
 * model metadata and verified knowledge references. It carries no problem or chat content.
 */
class EphemeralTutorProblemLearningContext(
    val problemFingerprint: String,
    override val problemFamilyFingerprint: String,
    val tutorTurnReferenceId: String,
    val submissionEvidenceFingerprint: String,
    val attributionModelVersion: String,
    verifiedKnowledgeEvidence: List<VerifiedEphemeralKnowledgeEvidence>,
) : TrustedLearningContext {
    val verifiedKnowledgeEvidence: List<VerifiedEphemeralKnowledgeEvidence> =
        Collections.unmodifiableList(
            verifiedKnowledgeEvidence
                .sortedBy { it.knowledgeNode.canonicalFingerprint }
                .toList(),
        )

    init {
        requireMasteryFingerprint(problemFingerprint, "Ephemeral problem fingerprint")
        requireMasteryFingerprint(problemFamilyFingerprint, "Problem-family fingerprint")
        requireMasteryOpaqueReference(tutorTurnReferenceId, "Tutor turn reference id")
        requireMasteryFingerprint(
            submissionEvidenceFingerprint,
            "Submission evidence fingerprint",
        )
        requireMasteryVersion(attributionModelVersion, "Attribution model version")
        require(this.verifiedKnowledgeEvidence.size <= MAX_VERIFIED_KNOWLEDGE_EVIDENCE) {
            "Verified knowledge evidence exceeds the supported budget"
        }
        require(
            this.verifiedKnowledgeEvidence
                .map { it.knowledgeNode.canonicalFingerprint }
                .distinct()
                .size == this.verifiedKnowledgeEvidence.size,
        ) {
            "Verified knowledge evidence must contain unique node references"
        }
        require(
            this.verifiedKnowledgeEvidence
                .map { it.manifestFingerprint to it.activationGeneration }
                .distinct()
                .size <= 1,
        ) {
            "Verified knowledge evidence must come from one activated catalog generation"
        }
    }

    override val canonicalFingerprint: String
        get() {
            val digest = CanonicalSha256("learner-mastery-ephemeral-tutor-context-v1")
                .field("problemFingerprint", problemFingerprint)
                .field("problemFamilyFingerprint", problemFamilyFingerprint)
                .field("tutorTurnReferenceId", tutorTurnReferenceId)
                .field("submissionEvidenceFingerprint", submissionEvidenceFingerprint)
                .field("attributionModelVersion", attributionModelVersion)
                .field("verifiedKnowledgeCount", verifiedKnowledgeEvidence.size)
            verifiedKnowledgeEvidence.forEachIndexed { index, evidence ->
                digest.field("verifiedKnowledge[$index]", evidence.canonicalFingerprint)
            }
            return digest.finish()
        }

    private companion object {
        const val MAX_VERIFIED_KNOWLEDGE_EVIDENCE = 32
    }
}

/**
 * Opaque, runtime-bound evidence produced from a narrow activated-catalog reference proof.
 */
class VerifiedEphemeralKnowledgeEvidence private constructor(
    val knowledgeNode: KnowledgeNodeRef,
    val manifestFingerprint: String,
    val activationGeneration: Long,
    internal val runtimeBindingId: String,
) {
    init {
        requireMasteryFingerprint(manifestFingerprint, "Knowledge manifest fingerprint")
        require(activationGeneration > 0L) {
            "Knowledge activation generation must be positive"
        }
        requireMasteryIdentity(runtimeBindingId, "Authority runtime binding id")
    }

    internal val canonicalFingerprint: String
        get() = CanonicalSha256("learner-mastery-verified-ephemeral-knowledge-v1")
            .field("knowledgeNode", knowledgeNode.canonicalFingerprint)
            .field("manifestFingerprint", manifestFingerprint)
            .field("activationGeneration", activationGeneration)
            .finish()

    internal companion object {
        fun create(
            knowledgeNode: KnowledgeNodeRef,
            manifestFingerprint: String,
            activationGeneration: Long,
            runtimeBindingId: String,
        ): VerifiedEphemeralKnowledgeEvidence =
            VerifiedEphemeralKnowledgeEvidence(
                knowledgeNode = knowledgeNode,
                manifestFingerprint = manifestFingerprint,
                activationGeneration = activationGeneration,
                runtimeBindingId = runtimeBindingId,
            )
    }
}

/**
 * Bounded local observation facts.
 *
 * Correctness, stuck/viewed state, help, retries, timing and immutable references are observations.
 * No caller-controlled evidence authority, outcome enum, numeric mass, mastery score or policy
 * version is present.
 */
class LearningObservationFacts(
    val observationId: String,
    val subject: SubjectKind,
    val source: TrustedLearningObservationSource,
    val sourceReferenceId: String,
    val presentationFingerprint: String,
    val context: TrustedLearningContext,
    val responseForm: TrustedLearningResponseForm,
    val answerWasCorrect: Boolean?,
    val learnerReportedStuck: Boolean,
    val answerWasViewed: Boolean,
    val independentlyAnswered: Boolean,
    val hintCount: Int,
    val answerRevealed: Boolean,
    val retryCount: Int,
    val elapsedDurationMillis: Long?,
    val verification: TrustedLearningVerification,
    val evidenceCanonicalFingerprint: String,
    val occurredAtEpochMillis: Long,
    val attestedAtEpochMillis: Long,
) {
    init {
        requireMasteryOpaqueReference(observationId, "Observation id")
        requireSpecificSubject(subject)
        requireMasteryOpaqueReference(sourceReferenceId, "Source reference id")
        requireMasteryFingerprint(presentationFingerprint, "Presentation fingerprint")
        requireMasteryFingerprint(evidenceCanonicalFingerprint, "Evidence fingerprint")
        require(occurredAtEpochMillis >= 0L) {
            "Observation time must not be negative"
        }
        require(attestedAtEpochMillis >= occurredAtEpochMillis) {
            "Observation attestation must not precede the observation"
        }
        require(hintCount in 0..MAX_HINT_COUNT) {
            "Hint count is outside the supported range"
        }
        require(retryCount in 0..MAX_RETRY_COUNT) {
            "Retry count is outside the supported range"
        }
        require(
            elapsedDurationMillis == null ||
                elapsedDurationMillis in 0L..MAX_ELAPSED_DURATION_MILLIS,
        ) {
            "Elapsed duration is outside the supported range"
        }
        require(
            listOf(
                answerWasCorrect != null,
                learnerReportedStuck,
                answerWasViewed,
            ).count { it } == 1,
        ) {
            "Observation must carry exactly one response fact"
        }
        require(!independentlyAnswered || (hintCount == 0 && !answerRevealed)) {
            "An independently answered observation cannot include help or an answer reveal"
        }
        require(
            (responseForm == TrustedLearningResponseForm.STUCK_REPORT) ==
                learnerReportedStuck,
        ) {
            "A stuck-report response form must carry exactly a stuck observation"
        }
        require(
            (responseForm == TrustedLearningResponseForm.IMPORTED_RECORD) ==
                (source == TrustedLearningObservationSource.IMPORTED_MISTAKE),
        ) {
            "Imported response form and imported observation source must agree"
        }
        when (context) {
            is SavedMistakeLearningContext -> {
                require(
                    (source == TrustedLearningObservationSource.SAVED_PROBLEM_REVIEW) ==
                        (context.reviewAttempt != null),
                ) {
                    "Saved review source and review attempt evidence must agree"
                }
                require(context.problemRevision.problem.subject == subject) {
                    "Saved problem context is outside the observation subject"
                }
            }
            is EphemeralTutorProblemLearningContext -> {
                require(
                    source != TrustedLearningObservationSource.SAVED_PROBLEM_REVIEW &&
                        source != TrustedLearningObservationSource.IMPORTED_MISTAKE,
                ) {
                    "An ephemeral tutor problem requires a tutor observation source"
                }
                require(
                    context.verifiedKnowledgeEvidence.all {
                        it.knowledgeNode.subject == subject
                    },
                ) {
                    "Verified ephemeral knowledge is outside the observation subject"
                }
            }
        }
    }

    internal val canonicalFingerprint: String
        get() {
            val digest = CanonicalSha256("learner-mastery-trusted-observation-v1")
                .field("observationId", observationId)
                .field("subject", subject.name)
                .field("source", source.name)
                .field("sourceReferenceId", sourceReferenceId)
                .field("presentationFingerprint", presentationFingerprint)
                .field("context", context.canonicalFingerprint)
                .field("responseForm", responseForm.name)
                .nullableField("answerWasCorrect", answerWasCorrect?.toString())
                .field("learnerReportedStuck", learnerReportedStuck)
                .field("answerWasViewed", answerWasViewed)
                .field("independentlyAnswered", independentlyAnswered)
                .field("hintCount", hintCount)
                .field("answerRevealed", answerRevealed)
                .field("retryCount", retryCount)
                .nullableField("elapsedDurationMillis", elapsedDurationMillis?.toString())
                .field("verification", verification.name)
                .field("evidenceFingerprint", evidenceCanonicalFingerprint)
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .field("attestedAtEpochMillis", attestedAtEpochMillis)
            return digest.finish()
        }

    private companion object {
        const val MAX_HINT_COUNT = 32
        const val MAX_RETRY_COUNT = 16
        const val MAX_ELAPSED_DURATION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

/**
 * Source-compatible Kotlin alias for callers that still prepare the old command name.
 *
 * The runtime treats this value only as untrusted bounded facts. The authority issues a separate,
 * private-constructor [AuthorityIssuedLearningObservation] before trusted policy is reachable.
 */
typealias RecordTrustedLearningObservationCommand = LearningObservationFacts

enum class TrustedLearningObservationDisposition {
    ADMITTED,
    FACT_STORED,
    DUPLICATE,
    INERT,
    CONFLICT,
}

/**
 * Opaque proof of the immutable terminal decision persisted for one observation candidate.
 *
 * A duplicate reports the original terminal decision through this receipt. Callers therefore do
 * not have to guess whether the duplicate originally admitted evidence or merely stored an inert
 * candidate.
 */
class TrustedLearningObservationTerminalReceipt private constructor(
    val candidateId: String,
    val candidateCanonicalFingerprint: String,
    val disposition: TrustedLearningObservationDisposition,
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
            disposition == TrustedLearningObservationDisposition.ADMITTED ||
                disposition == TrustedLearningObservationDisposition.INERT,
        ) {
            "A terminal observation receipt must describe an admitted or inert candidate"
        }
        require(
            (disposition == TrustedLearningObservationDisposition.INERT) == (inertReason != null),
        ) {
            "Only an inert terminal observation receipt may expose an inert reason"
        }
    }

    internal companion object {
        fun create(
            candidateId: String,
            candidateCanonicalFingerprint: String,
            disposition: TrustedLearningObservationDisposition,
            inertReason: LearningObservationInertReason?,
            receiptFingerprint: String,
        ): TrustedLearningObservationTerminalReceipt =
            TrustedLearningObservationTerminalReceipt(
                candidateId = candidateId,
                candidateCanonicalFingerprint = candidateCanonicalFingerprint,
                disposition = disposition,
                inertReason = inertReason,
                receiptFingerprint = receiptFingerprint,
            )
    }
}

data class TrustedLearningObservationResult(
    val observationId: String,
    val disposition: TrustedLearningObservationDisposition,
    val inertReason: LearningObservationInertReason? = null,
    val terminalReceipt: TrustedLearningObservationTerminalReceipt? = null,
) {
    init {
        terminalReceipt?.let { receipt ->
            when (disposition) {
                TrustedLearningObservationDisposition.ADMITTED ->
                    require(receipt.disposition == TrustedLearningObservationDisposition.ADMITTED)
                TrustedLearningObservationDisposition.INERT ->
                    require(
                        receipt.disposition == TrustedLearningObservationDisposition.INERT &&
                            receipt.inertReason == inertReason,
                    )
                TrustedLearningObservationDisposition.DUPLICATE -> Unit
                TrustedLearningObservationDisposition.FACT_STORED,
                TrustedLearningObservationDisposition.CONFLICT,
                -> error("A non-terminal observation result cannot expose an admission receipt")
            }
        }
    }
}

enum class LearnerMasteryInboundDisposition {
    APPLIED,
    DUPLICATE,
    CONFLICT,
}

/** Learner-bound relay owner capability kept private by the core:data authority runtime. */
interface LearnerMasteryRelayCapability {
    val learnerId: String

    suspend fun accept(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): LearnerMasteryInboundDisposition

    suspend fun readPending(
        nowEpochMillis: Long,
        limit: Int = DEFAULT_RELAY_BATCH_SIZE,
    ): List<LearnerMasteryOutboxDelivery>

    suspend fun markDelivered(
        message: LearnerMasteryOutboxDelivery,
        deliveredAtEpochMillis: Long,
    )

    companion object {
        const val DEFAULT_RELAY_BATCH_SIZE = 64
        const val MAX_RELAY_BATCH_SIZE = 256
    }
}

fun interface LearnerMasteryObservationSink {
    suspend fun record(
        facts: LearningObservationFacts,
    ): TrustedLearningObservationResult
}

/**
 * Direction-free evidence for an observed open response that still requires independent review.
 *
 * The response body is retained by its operational owner; mastery stores only canonical
 * fingerprints and runtime-authorized knowledge references. No correctness, direction, evidence
 * mass, or score can be supplied through this contract.
 */
class PendingOpenResponseFacts(
    val sourceFactId: String,
    val submissionId: String,
    val subject: SubjectKind,
    val presentationFingerprint: String,
    val context: EphemeralTutorProblemLearningContext,
    val responseCanonicalFingerprint: String,
    val responsePolicyVersion: String,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val elapsedDurationMillis: Long?,
    val occurredAtEpochMillis: Long,
    val attestedAtEpochMillis: Long,
) {
    init {
        requireMasteryOpaqueReference(sourceFactId, "Pending open-response source-fact id")
        requireMasteryOpaqueReference(submissionId, "Pending open-response submission id")
        requireSpecificSubject(subject)
        requireMasteryFingerprint(
            presentationFingerprint,
            "Pending open-response presentation fingerprint",
        )
        requireMasteryFingerprint(
            responseCanonicalFingerprint,
            "Pending open-response fingerprint",
        )
        requireMasteryVersion(
            responsePolicyVersion,
            "Pending open-response policy version",
        )
        require(attemptOrdinal in 1..MAX_ATTEMPT_ORDINAL) {
            "Pending open-response attempt ordinal is outside the supported range"
        }
        require(hintCount in 0..MAX_HINT_COUNT) {
            "Pending open-response hint count is outside the supported range"
        }
        require(
            elapsedDurationMillis == null ||
                elapsedDurationMillis in 0L..MAX_ELAPSED_DURATION_MILLIS,
        ) {
            "Pending open-response elapsed duration is outside the supported range"
        }
        require(occurredAtEpochMillis >= 0L) {
            "Pending open-response time must not be negative"
        }
        require(attestedAtEpochMillis >= occurredAtEpochMillis) {
            "Pending open-response attestation must not precede the response"
        }
        require(context.verifiedKnowledgeEvidence.all { it.knowledgeNode.subject == subject }) {
            "Pending open-response knowledge evidence is outside the response subject"
        }
    }

    internal val canonicalFingerprint: String
        get() = CanonicalSha256("learner-mastery-pending-open-response-v1")
            .field("sourceFactId", sourceFactId)
            .field("submissionId", submissionId)
            .field("subject", subject.name)
            .field("presentationFingerprint", presentationFingerprint)
            .field("context", context.canonicalFingerprint)
            .field("responseCanonicalFingerprint", responseCanonicalFingerprint)
            .field("responsePolicyVersion", responsePolicyVersion)
            .field("attemptOrdinal", attemptOrdinal)
            .field("hintCount", hintCount)
            .field("answerWasRevealed", answerWasRevealed)
            .nullableField("elapsedDurationMillis", elapsedDurationMillis?.toString())
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("attestedAtEpochMillis", attestedAtEpochMillis)
            .finish()

    private companion object {
        const val MAX_ATTEMPT_ORDINAL = 17
        const val MAX_HINT_COUNT = 32
        const val MAX_ELAPSED_DURATION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

enum class PendingOpenResponseDisposition {
    QUEUED,
    DUPLICATE,
    CONFLICT,
    REJECTED,
}

data class PendingOpenResponseResult(
    val sourceFactId: String,
    val reviewCaseId: String?,
    val disposition: PendingOpenResponseDisposition,
) {
    init {
        require(
            (disposition == PendingOpenResponseDisposition.QUEUED ||
                disposition == PendingOpenResponseDisposition.DUPLICATE) ==
                (reviewCaseId != null),
        ) {
            "Only queued pending responses may expose a review-case id"
        }
    }
}

fun interface LearnerMasteryPendingOpenResponseSink {
    suspend fun enqueue(
        facts: PendingOpenResponseFacts,
    ): PendingOpenResponseResult
}

interface LearnerMasteryReader {
    suspend fun queryDigest(
        subject: SubjectKind,
        focusLimit: Int = DEFAULT_DIGEST_FOCUS_LIMIT,
    ): SubjectMasteryDigest

    suspend fun queryTimeline(
        subject: SubjectKind,
        sinceEpochMillis: Long = 0L,
        dayLimit: Int = DEFAULT_TIMELINE_DAY_LIMIT,
    ): List<SubjectMasteryTimelineEntry>

    companion object {
        const val DEFAULT_DIGEST_FOCUS_LIMIT = 12
        const val MAX_DIGEST_FOCUS_LIMIT = 64
        const val DEFAULT_TIMELINE_DAY_LIMIT = 30
        const val MAX_TIMELINE_DAY_LIMIT = 180
    }
}

fun interface LearnerMasteryKnowledgeEvidenceAuthorizer {
    fun authorize(
        proof: VerifiedKnowledgeReferenceProof,
    ): VerifiedEphemeralKnowledgeEvidence
}

/**
 * Host-only lease factory retained by core:data.
 *
 * A model adapter receives only [LearnerMasteryModelAccessLease.access], never this provider or
 * the lease itself. One provider owns one request gate for its whole lifetime; opening a newer
 * lease or explicitly invalidating the current lease revokes and drains late writes. The complete
 * request scope is a host snapshot, not model output; the former unscoped overload intentionally
 * does not exist.
 */
interface LearnerMasteryModelAccessProvider {
    fun openLease(
        scope: LearnerMasteryModelRequestScope,
    ): LearnerMasteryModelAccessLease

    fun invalidateCurrentLease()
}

@JvmSynthetic
internal fun assembleLearnerMasteryRuntimeCapabilities(
    authority: LearnerMasteryAuthority,
    databaseOwnerSeal: Any? = null,
): LearnerMasteryRuntimeCapabilities {
    databaseOwnerSeal?.let(CoreDataLearnerMasteryOwnerBridge::requireOpenResponseOwnerSeal)
    val observationSink =
        LearnerMasteryObservationSink { facts ->
            authority.recordObservation(facts)
        }
    val pendingOpenResponseSink =
        LearnerMasteryPendingOpenResponseSink { facts ->
            authority.enqueuePendingOpenResponse(facts)
        }
    val reader =
        object : LearnerMasteryReader {
            override suspend fun queryDigest(
                subject: SubjectKind,
                focusLimit: Int,
            ): SubjectMasteryDigest = authority.queryDigest(subject, focusLimit)

            override suspend fun queryTimeline(
                subject: SubjectKind,
                sinceEpochMillis: Long,
                dayLimit: Int,
            ): List<SubjectMasteryTimelineEntry> =
                authority.queryTimeline(subject, sinceEpochMillis, dayLimit)
        }
    val knowledgeEvidenceAuthorizer =
        LearnerMasteryKnowledgeEvidenceAuthorizer { proof ->
            authority.authorizeEphemeralKnowledge(proof)
        }
    val eraseCapability =
        LearnerMasteryEraseCapability {
            authority.eraseAllLearnerData()
        }
    val modelRequestGate = LearnerMasteryModelRequestGate.create()
    val modelAccessProvider =
        object : LearnerMasteryModelAccessProvider {
            override fun openLease(
                scope: LearnerMasteryModelRequestScope,
            ): LearnerMasteryModelAccessLease {
                val attemptScope =
                    ModelSubmissionAttemptScope(
                        learnerId = authority.learnerId,
                        permission = scope,
                    )
                val gate =
                    LearnerMasteryModelAccessLeaseGate(
                        requestGeneration =
                            modelRequestGate.activate(
                                scope = attemptScope,
                                modeVersion = scope.modeVersion,
                            ),
                    )
                return LearnerMasteryModelAccessLease.create(
                    access =
                        authority.scopedModelAccess(
                            subject = scope.subject,
                            modelVersion = scope.modelVersion,
                            leaseGate = gate,
                        ),
                    gate = gate,
                )
            }

            override fun invalidateCurrentLease() {
                modelRequestGate.invalidate()
            }
        }
    return LearnerMasteryRuntimeCapabilities.create(
        authority.learnerId,
        observationSink,
        pendingOpenResponseSink,
        reader,
        authority.displayReader,
        authority.evidenceReviewCapability,
        authority.evidenceCorrectionCapability,
        authority.localContextReader,
        knowledgeEvidenceAuthorizer,
        eraseCapability,
        modelAccessProvider,
        Runnable { modelRequestGate.terminate() },
        authority.relay,
        (authority as? RoomLearnerMasteryAuthority)?.outboxAuthenticityVerifier,
        authority,
        databaseOwnerSeal,
    )
}

internal class LegacyMasteryFactMigrationBatch(
    val sourceGeneration: String,
    val batchSequence: Long,
    observations: List<RecordTrustedLearningObservationCommand>,
    val finalBatch: Boolean,
) {
    val observations: List<RecordTrustedLearningObservationCommand> =
        Collections.unmodifiableList(observations.toList())

    init {
        requireMasteryVersion(sourceGeneration, "Legacy source generation")
        require(batchSequence > 0L) {
            "Legacy migration batch sequence must be positive"
        }
        require(this.observations.size <= MAX_OBSERVATIONS) {
            "Legacy migration batch exceeds the observation budget"
        }
        require(this.observations.map { it.observationId }.distinct().size == observations.size) {
            "Legacy migration batch contains duplicate observation ids"
        }
    }

    internal val canonicalFingerprint: String
        get() {
            val digest = CanonicalSha256("learner-mastery-legacy-fact-batch-v1")
                .field("sourceGeneration", sourceGeneration)
                .field("batchSequence", batchSequence)
                .field("finalBatch", finalBatch)
                .field("observationCount", observations.size)
            observations.forEachIndexed { index, observation ->
                digest.field("observation[$index]", observation.canonicalFingerprint)
            }
            return digest.finish()
        }

    private companion object {
        const val MAX_OBSERVATIONS = 256
    }
}

internal enum class LegacyMasteryFactMigrationDisposition {
    IMPORTED,
    DUPLICATE,
    CONFLICT,
    OUT_OF_ORDER,
}

internal data class LegacyMasteryFactMigrationResult(
    val sourceGeneration: String,
    val batchSequence: Long,
    val disposition: LegacyMasteryFactMigrationDisposition,
    val observationCount: Int,
    val projectionRebuiltFromFacts: Boolean,
)

enum class MasteryEvidenceQuality {
    HIGH,
    MEDIUM,
    LOW,
    UNVERIFIABLE,
}
