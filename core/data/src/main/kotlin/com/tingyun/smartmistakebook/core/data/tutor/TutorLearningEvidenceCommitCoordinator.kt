package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.data.session.requireSessionFingerprint
import com.tingyun.smartmistakebook.core.data.session.requireSessionIdentifier
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAssistance
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCurrentSessionReference
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceOutcome
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Collections

/**
 * A tutor learning candidate qualified by trusted local code.
 *
 * This is the semantic input to the learner-mastery owner. It deliberately contains no legacy
 * learning-fact entity, SQL identity, projection value, weight, or raw answer body.
 */
internal data class TutorLearningEvidenceCandidate(
    val learnerId: String,
    val evidenceRequestId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val sessionAnchorId: String,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val directiveFingerprint: String,
    val idempotencyKey: String,
    val payloadFingerprint: String,
    val questionFingerprint: String,
    val problemRevisionFingerprint: String,
    val problemFingerprintVersion: String,
    val turnFingerprint: String,
    val responseFingerprint: String,
    val outcome: TutorLearningEvidenceOutcome,
    val occurredAtEpochMillis: Long,
    val attestedAtEpochMillis: Long,
    val producerVersion: String,
    val currentSessionReference: TutorLearningEvidenceCurrentSessionReference,
    val attemptOrdinal: Int,
    val retryCount: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val independentlyAnswered: Boolean,
    val assistance: TutorLearningEvidenceAssistance,
) {
    init {
        learnerId.requireSessionIdentifier("Tutor evidence learner id")
        evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
        conversationId.requireSessionIdentifier("Tutor evidence conversation id")
        require(conversationGeneration > 0) {
            "Tutor evidence conversation generation must be positive"
        }
        require(conversationStateVersion >= 0) {
            "Tutor evidence conversation version must not be negative"
        }
        turnReceiptId.requireSessionIdentifier("Tutor evidence turn receipt id")
        require(turnOrdinal > 0) { "Tutor evidence turn ordinal must be positive" }
        sessionAnchorId.requireSessionIdentifier("Tutor evidence session anchor id")
        require(requestVersion >= 0 && modeVersion >= 0) {
            "Tutor evidence request and mode versions must not be negative"
        }
        directiveFingerprint.requireSessionFingerprint("Tutor evidence directive fingerprint")
        idempotencyKey.requireSessionIdentifier("Tutor evidence idempotency key")
        payloadFingerprint.requireSessionFingerprint("Tutor evidence caller payload fingerprint")
        questionFingerprint.requireSessionFingerprint("Tutor evidence question fingerprint")
        problemRevisionFingerprint.requireSessionFingerprint(
            "Tutor evidence problem revision fingerprint",
        )
        problemFingerprintVersion.requireSessionIdentifier(
            "Tutor evidence problem fingerprint version",
        )
        turnFingerprint.requireSessionFingerprint("Tutor evidence turn fingerprint")
        responseFingerprint.requireSessionFingerprint("Tutor evidence response fingerprint")
        require(occurredAtEpochMillis >= 0) {
            "Tutor evidence occurrence time must not be negative"
        }
        require(attestedAtEpochMillis >= occurredAtEpochMillis) {
            "Tutor evidence attestation cannot predate the evidence"
        }
        producerVersion.requireSessionIdentifier("Tutor evidence producer version")
        require(attemptOrdinal in 1..MAX_TUTOR_LEARNING_ATTEMPT_ORDINAL) {
            "Tutor evidence attempt ordinal is outside the supported range"
        }
        require(retryCount in 0..MAX_TUTOR_LEARNING_RETRY_COUNT) {
            "Tutor evidence retry count is outside the supported range"
        }
        require(retryCount == attemptOrdinal - 1) {
            "Tutor evidence retry count must describe the exact attempt ordinal"
        }
        require(hintCount in 0..MAX_TUTOR_LEARNING_HINT_COUNT) {
            "Tutor evidence hint count is outside the supported range"
        }
        require(!independentlyAnswered || retryCount == 0) {
            "A retried tutor answer cannot be independently answered"
        }
        require(!independentlyAnswered || hintCount == 0) {
            "A hinted tutor answer cannot be independently answered"
        }
        require(!independentlyAnswered || !answerWasRevealed) {
            "A revealed tutor answer cannot be independently answered"
        }
        require(assistance == derivedAssistance()) {
            "Tutor evidence assistance differs from its behavior facts"
        }
        require(outcome != TutorLearningEvidenceOutcome.CORRECT || !answerWasRevealed) {
            "Revealed correctness must remain assisted"
        }
    }

    fun canonicalFingerprint(): String =
        CanonicalSha256(CANDIDATE_FINGERPRINT_DOMAIN)
            .field("learnerId", learnerId)
            .field("evidenceRequestId", evidenceRequestId)
            .field("conversationId", conversationId)
            .field("conversationGeneration", conversationGeneration)
            .field("conversationStateVersion", conversationStateVersion)
            .field("turnReceiptId", turnReceiptId)
            .field("turnOrdinal", turnOrdinal)
            .field("subject", subject.name)
            .field("sessionAnchorId", sessionAnchorId)
            .field("kind", kind.name)
            .field("requestVersion", requestVersion)
            .field("modeVersion", modeVersion)
            .field("directiveFingerprint", directiveFingerprint)
            .field("idempotencyKey", idempotencyKey)
            .field("callerPayloadFingerprint", payloadFingerprint)
            .field("questionFingerprint", questionFingerprint)
            .field("problemRevisionFingerprint", problemRevisionFingerprint)
            .field("problemFingerprintVersion", problemFingerprintVersion)
            .field("turnFingerprint", turnFingerprint)
            .field("responseFingerprint", responseFingerprint)
            .field("outcome", outcome.name)
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("attestedAtEpochMillis", attestedAtEpochMillis)
            .field("producerVersion", producerVersion)
            .field(
                "sessionAuthorizationFingerprint",
                currentSessionReference.authorizationFingerprint,
            )
            .field(
                "learningWritePermissionVersion",
                currentSessionReference.learningWritePermissionVersion,
            )
            .field("attemptOrdinal", attemptOrdinal)
            .field("retryCount", retryCount)
            .field("hintCount", hintCount)
            .field("answerWasRevealed", answerWasRevealed)
            .field("independentlyAnswered", independentlyAnswered)
            .field("assistance", assistance.name)
            .finish()

    fun toSessionIntent(): TutorLearningEvidenceSessionIntent =
        TutorLearningEvidenceSessionIntent(
            scope =
                TutorLearningEvidenceSessionScope(
                    learnerId = learnerId,
                    conversationId = conversationId,
                    conversationGeneration = conversationGeneration,
                    turnReceiptId = turnReceiptId,
                ),
            evidenceRequestId = evidenceRequestId,
            conversationStateVersion = conversationStateVersion,
            turnOrdinal = turnOrdinal,
            subject = subject,
            sessionAnchorId = sessionAnchorId,
            kind = kind,
            requestVersion = requestVersion,
            modeVersion = modeVersion,
            idempotencyKey = idempotencyKey,
            candidateFingerprint = canonicalFingerprint(),
            state = TutorLearningEvidenceSessionState.PENDING_MASTERY,
        )

    private fun derivedAssistance(): TutorLearningEvidenceAssistance =
        when {
            answerWasRevealed -> TutorLearningEvidenceAssistance.ANSWER_REVEALED
            independentlyAnswered -> TutorLearningEvidenceAssistance.INDEPENDENT
            hintCount == 1 -> TutorLearningEvidenceAssistance.ONE_HINT
            hintCount > 1 -> TutorLearningEvidenceAssistance.MULTIPLE_HINTS
            else -> TutorLearningEvidenceAssistance.UNKNOWN
        }
}

/**
 * A semantic candidate paired with proofs resolved from the locally held current-session binding.
 * Proofs never cross the domain boundary and an empty authorization cannot reach mastery.
 */
internal class AuthorizedTutorLearningEvidenceCandidate(
    val candidate: TutorLearningEvidenceCandidate,
    knowledgeReferenceProofs: List<VerifiedKnowledgeReferenceProof>,
) {
    val knowledgeReferenceProofs: List<VerifiedKnowledgeReferenceProof> =
        Collections.unmodifiableList(
            knowledgeReferenceProofs.sortedBy { it.publicIdentityFingerprint() },
        )

    init {
        require(this.knowledgeReferenceProofs.isNotEmpty()) {
            "Tutor learning evidence requires verified knowledge references"
        }
        require(this.knowledgeReferenceProofs.size <= MAX_TUTOR_KNOWLEDGE_REFERENCE_PROOFS) {
            "Tutor learning evidence exceeds the verified knowledge-reference limit"
        }
        require(
            this.knowledgeReferenceProofs
                .map { it.ref.canonicalFingerprint }
                .distinct()
                .size == this.knowledgeReferenceProofs.size,
        ) {
            "Tutor learning evidence contains duplicate knowledge references"
        }
        require(
            this.knowledgeReferenceProofs
                .map { it.manifestFingerprint to it.activationGeneration }
                .distinct()
                .size == 1,
        ) {
            "Tutor learning evidence proofs must share one activated catalog generation"
        }
    }

    fun authorizedFingerprint(): String {
        val digest =
            CanonicalSha256(AUTHORIZED_CANDIDATE_FINGERPRINT_DOMAIN)
                .field("candidate", candidate.canonicalFingerprint())
                .field("knowledgeProofCount", knowledgeReferenceProofs.size)
        knowledgeReferenceProofs.forEachIndexed { index, proof ->
            digest.field("knowledgeProof[$index]", proof.publicIdentityFingerprint())
        }
        return digest.finish()
    }
}

internal fun interface TutorLearningEvidenceCandidateAuthorizer {
    /** Resolves locally held proofs only after a durable pending intent exists. */
    suspend fun authorize(
        candidate: TutorLearningEvidenceCandidate,
    ): AuthorizedTutorLearningEvidenceCandidate
}

internal data class TutorLearningEvidenceSessionScope(
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val turnReceiptId: String,
) {
    init {
        learnerId.requireSessionIdentifier("Tutor evidence session learner id")
        conversationId.requireSessionIdentifier("Tutor evidence session conversation id")
        require(conversationGeneration > 0) {
            "Tutor evidence session conversation generation must be positive"
        }
        turnReceiptId.requireSessionIdentifier("Tutor evidence session turn receipt id")
    }
}

internal enum class TutorLearningEvidenceSessionState {
    PENDING_MASTERY,
    MASTERY_ACKNOWLEDGED,
}

/**
 * Durable session-side finalization intent.
 *
 * The normalized candidate fingerprint binds the semantic payload without persisting it in the
 * session database.
 */
internal data class TutorLearningEvidenceSessionIntent(
    val scope: TutorLearningEvidenceSessionScope,
    val evidenceRequestId: String,
    val conversationStateVersion: Long,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val sessionAnchorId: String,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val idempotencyKey: String,
    val candidateFingerprint: String,
    val state: TutorLearningEvidenceSessionState,
) {
    init {
        evidenceRequestId.requireSessionIdentifier("Tutor evidence session request id")
        require(conversationStateVersion >= 0) {
            "Tutor evidence session conversation version must not be negative"
        }
        require(turnOrdinal > 0) { "Tutor evidence session turn ordinal must be positive" }
        sessionAnchorId.requireSessionIdentifier("Tutor evidence session anchor id")
        require(requestVersion >= 0 && modeVersion >= 0) {
            "Tutor evidence session request and mode versions must not be negative"
        }
        idempotencyKey.requireSessionIdentifier("Tutor evidence session idempotency key")
        candidateFingerprint.requireSessionFingerprint(
            "Tutor evidence normalized candidate fingerprint",
        )
        require(state == TutorLearningEvidenceSessionState.PENDING_MASTERY) {
            "A finalization intent must remain pending until mastery acknowledges it"
        }
    }
}

internal data class TutorLearningEvidenceMasteryReceipt(
    val receiptId: String,
    val receiptFingerprint: String,
    val candidateFingerprint: String,
) {
    init {
        receiptId.requireSessionIdentifier("Tutor mastery receipt id")
        receiptFingerprint.requireSessionFingerprint("Tutor mastery receipt fingerprint")
        candidateFingerprint.requireSessionFingerprint("Tutor mastery candidate fingerprint")
    }
}

internal fun interface TutorLearningEvidenceMasteryOwner {
    /**
     * Atomically and idempotently generates and stores the learner-mastery source fact/event.
     */
    suspend fun commit(
        candidate: AuthorizedTutorLearningEvidenceCandidate,
    ): TutorLearningEvidenceMasteryReceipt
}

internal data class TutorLearningEvidenceSessionAcknowledgement(
    val scope: TutorLearningEvidenceSessionScope,
    val evidenceRequestId: String,
    val candidateFingerprint: String,
    val masteryReceiptId: String,
    val masteryReceiptFingerprint: String,
) {
    init {
        evidenceRequestId.requireSessionIdentifier("Tutor evidence acknowledgement request id")
        candidateFingerprint.requireSessionFingerprint(
            "Tutor evidence acknowledgement candidate fingerprint",
        )
        masteryReceiptId.requireSessionIdentifier("Tutor evidence mastery receipt id")
        masteryReceiptFingerprint.requireSessionFingerprint(
            "Tutor evidence mastery receipt fingerprint",
        )
    }
}

internal data class TutorLearningEvidenceSessionReceipt(
    val scope: TutorLearningEvidenceSessionScope,
    val evidenceRequestId: String,
    val candidateFingerprint: String,
    val masteryReceiptId: String,
    val masteryReceiptFingerprint: String,
    val evidenceStateVersion: Long,
    val createdAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long,
    val state: TutorLearningEvidenceSessionState =
        TutorLearningEvidenceSessionState.MASTERY_ACKNOWLEDGED,
) {
    init {
        evidenceRequestId.requireSessionIdentifier("Tutor evidence receipt request id")
        candidateFingerprint.requireSessionFingerprint(
            "Tutor evidence receipt candidate fingerprint",
        )
        masteryReceiptId.requireSessionIdentifier("Tutor evidence receipt mastery id")
        masteryReceiptFingerprint.requireSessionFingerprint(
            "Tutor evidence receipt mastery fingerprint",
        )
        require(evidenceStateVersion > 0) {
            "A final tutor evidence receipt must advance the evidence version"
        }
        require(createdAtEpochMillis >= 0 && resolvedAtEpochMillis >= createdAtEpochMillis) {
            "Tutor evidence receipt times are invalid"
        }
        require(state == TutorLearningEvidenceSessionState.MASTERY_ACKNOWLEDGED) {
            "A final session receipt must acknowledge mastery"
        }
    }
}

internal sealed interface TutorLearningEvidenceSessionBeginResult {
    data class Pending(
        val intent: TutorLearningEvidenceSessionIntent,
    ) : TutorLearningEvidenceSessionBeginResult

    data class Finalized(
        val receipt: TutorLearningEvidenceSessionReceipt,
    ) : TutorLearningEvidenceSessionBeginResult
}

internal sealed interface TutorLearningEvidenceSessionAcknowledgeResult {
    val receipt: TutorLearningEvidenceSessionReceipt

    data class Acknowledged(
        override val receipt: TutorLearningEvidenceSessionReceipt,
    ) : TutorLearningEvidenceSessionAcknowledgeResult

    data class Replayed(
        override val receipt: TutorLearningEvidenceSessionReceipt,
    ) : TutorLearningEvidenceSessionAcknowledgeResult
}

/**
 * The only session capability needed to commit tutor learning evidence.
 *
 * Implementations must durably persist [begin] before returning Pending and durably persist
 * [acknowledge] before returning. The two operations are local session-store transactions; no
 * cross-database transaction is implied or permitted.
 */
internal interface TutorLearningEvidenceSessionPort {
    suspend fun begin(
        intent: TutorLearningEvidenceSessionIntent,
    ): TutorLearningEvidenceSessionBeginResult

    suspend fun acknowledge(
        acknowledgement: TutorLearningEvidenceSessionAcknowledgement,
    ): TutorLearningEvidenceSessionAcknowledgeResult
}

internal sealed interface TutorLearningEvidenceCommitResult {
    val receipt: TutorLearningEvidenceSessionReceipt

    data class Committed(
        override val receipt: TutorLearningEvidenceSessionReceipt,
    ) : TutorLearningEvidenceCommitResult

    data class Replayed(
        override val receipt: TutorLearningEvidenceSessionReceipt,
    ) : TutorLearningEvidenceCommitResult
}

/**
 * Recoverable session-intent -> mastery-commit -> session-acknowledgement coordinator.
 */
internal class TutorLearningEvidenceCommitCoordinator(
    private val session: TutorLearningEvidenceSessionPort,
    private val candidateAuthorizer: TutorLearningEvidenceCandidateAuthorizer,
    private val mastery: TutorLearningEvidenceMasteryOwner,
) {
    suspend fun commit(
        candidate: TutorLearningEvidenceCandidate,
    ): TutorLearningEvidenceCommitResult {
        val requestedIntent = candidate.toSessionIntent()
        return when (val begun = session.begin(requestedIntent)) {
            is TutorLearningEvidenceSessionBeginResult.Finalized ->
                TutorLearningEvidenceCommitResult.Replayed(
                    begun.receipt.requireMatches(requestedIntent),
                )

            is TutorLearningEvidenceSessionBeginResult.Pending -> {
                begun.intent.requireMatches(requestedIntent)
                val authorizedCandidate = candidateAuthorizer.authorize(candidate)
                check(authorizedCandidate.candidate === candidate) {
                    "Tutor evidence authorizer replaced the semantic candidate"
                }
                val masteryReceipt = mastery.commit(authorizedCandidate)
                check(masteryReceipt.candidateFingerprint == requestedIntent.candidateFingerprint) {
                    "Learner-mastery receipt belongs to another tutor evidence candidate"
                }
                val acknowledgement =
                    TutorLearningEvidenceSessionAcknowledgement(
                        scope = requestedIntent.scope,
                        evidenceRequestId = requestedIntent.evidenceRequestId,
                        candidateFingerprint = requestedIntent.candidateFingerprint,
                        masteryReceiptId = masteryReceipt.receiptId,
                        masteryReceiptFingerprint = masteryReceipt.receiptFingerprint,
                    )
                when (val acknowledged = session.acknowledge(acknowledgement)) {
                    is TutorLearningEvidenceSessionAcknowledgeResult.Acknowledged ->
                        TutorLearningEvidenceCommitResult.Committed(
                            acknowledged.receipt.requireMatches(acknowledgement),
                        )

                    is TutorLearningEvidenceSessionAcknowledgeResult.Replayed ->
                        TutorLearningEvidenceCommitResult.Replayed(
                            acknowledged.receipt.requireMatches(acknowledgement),
                        )
                }
            }
        }
    }
}

private fun TutorLearningEvidenceSessionIntent.requireMatches(
    requested: TutorLearningEvidenceSessionIntent,
): TutorLearningEvidenceSessionIntent {
    check(this == requested) {
        "Durable tutor evidence intent differs from the requested scoped payload"
    }
    return this
}

private fun TutorLearningEvidenceSessionReceipt.requireMatches(
    requested: TutorLearningEvidenceSessionIntent,
): TutorLearningEvidenceSessionReceipt {
    check(
        scope == requested.scope &&
            evidenceRequestId == requested.evidenceRequestId &&
            candidateFingerprint == requested.candidateFingerprint,
    ) {
        "Durable tutor evidence receipt differs from the requested scoped payload"
    }
    return this
}

private fun TutorLearningEvidenceSessionReceipt.requireMatches(
    acknowledgement: TutorLearningEvidenceSessionAcknowledgement,
): TutorLearningEvidenceSessionReceipt {
    check(
        scope == acknowledgement.scope &&
            evidenceRequestId == acknowledgement.evidenceRequestId &&
            candidateFingerprint == acknowledgement.candidateFingerprint &&
            masteryReceiptId == acknowledgement.masteryReceiptId &&
            masteryReceiptFingerprint == acknowledgement.masteryReceiptFingerprint,
    ) {
        "Durable tutor evidence receipt differs from the mastery acknowledgement"
    }
    return this
}

private const val CANDIDATE_FINGERPRINT_DOMAIN =
    "core-data-tutor-learning-evidence-candidate-v1"
private const val AUTHORIZED_CANDIDATE_FINGERPRINT_DOMAIN =
    "core-data-tutor-learning-authorized-evidence-v1"
private const val KNOWLEDGE_PROOF_IDENTITY_FINGERPRINT_DOMAIN =
    "core-data-tutor-knowledge-proof-public-identity-v1"
private const val MAX_TUTOR_KNOWLEDGE_REFERENCE_PROOFS = 32
private const val MAX_TUTOR_LEARNING_HINT_COUNT = 32
private const val MAX_TUTOR_LEARNING_RETRY_COUNT = 16
private const val MAX_TUTOR_LEARNING_ATTEMPT_ORDINAL = MAX_TUTOR_LEARNING_RETRY_COUNT + 1

private fun VerifiedKnowledgeReferenceProof.publicIdentityFingerprint(): String =
    CanonicalSha256(KNOWLEDGE_PROOF_IDENTITY_FINGERPRINT_DOMAIN)
        .field("knowledgeNode", ref.canonicalFingerprint)
        .field("manifestFingerprint", manifestFingerprint)
        .field("activationGeneration", activationGeneration)
        .finish()
