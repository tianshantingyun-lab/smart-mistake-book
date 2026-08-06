package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt

/**
 * Starts one new, isolated chat generation. Every identifier is application-generated and opaque;
 * none is a database row id or a value chosen by a model.
 */
data class CreateTutorConversationCommand(
    val learnerScopeId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val clientIdempotencyKey: String,
    val payloadFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        conversationId.requireTutorMemoryId("Conversation")
        require(conversationGeneration > 0) { "Conversation generation must be positive" }
        clientIdempotencyKey.requireTutorMemoryId("Conversation idempotency key")
        payloadFingerprint.requireTutorMemoryFingerprint("Conversation payload fingerprint")
        require(occurredAtEpochMillis >= 0) {
            "Conversation creation time must not be negative"
        }
    }
}

data class OpenTutorConversationCommand(
    val learnerScopeId: String,
    val conversationId: String,
    val conversationGeneration: Long,
) {
    init {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        conversationId.requireTutorMemoryId("Conversation")
        require(conversationGeneration > 0) { "Conversation generation must be positive" }
    }
}

data class ArchiveTutorConversationCommand(
    val learnerScopeId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val expectedConversationStateVersion: Long,
    val clientIdempotencyKey: String,
    val payloadFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        conversationId.requireTutorMemoryId("Conversation")
        require(conversationGeneration > 0) { "Conversation generation must be positive" }
        require(expectedConversationStateVersion >= 0) {
            "Expected conversation state version must not be negative"
        }
        clientIdempotencyKey.requireTutorMemoryId("Archive idempotency key")
        payloadFingerprint.requireTutorMemoryFingerprint("Archive payload fingerprint")
        require(occurredAtEpochMillis >= 0) { "Archive time must not be negative" }
    }
}

sealed interface CreateTutorConversationResult {
    val conversation: TutorConversation
    val created: Boolean

    data class Created(
        override val conversation: TutorConversation,
    ) : CreateTutorConversationResult {
        override val created: Boolean = true

        init {
            require(conversation.status == TutorConversationStatus.ACTIVE) {
                "A newly created conversation must be active"
            }
        }
    }

    /** The same scoped idempotency key and payload already created this exact generation. */
    data class Replayed(
        override val conversation: TutorConversation,
    ) : CreateTutorConversationResult {
        override val created: Boolean = false
    }
}

sealed interface OpenTutorConversationResult {
    data class Opened(val conversation: TutorConversation) : OpenTutorConversationResult

    /**
     * Missing and out-of-learner-scope conversations deliberately share one result so a caller
     * cannot use this read to discover another learner's conversation.
     */
    data object NotFound : OpenTutorConversationResult
}

sealed interface OpenTutorTurnResult {
    data class Found(val receipt: TutorTurnReceipt) : OpenTutorTurnResult

    /**
     * Missing and out-of-learner-scope receipts deliberately share one result so a caller cannot
     * discover another learner's turn.
     */
    data object NotFound : OpenTutorTurnResult
}

sealed interface OpenTutorEvidenceResult {
    data class Found(val request: TutorEvidenceRequest) : OpenTutorEvidenceResult

    /**
     * Missing and out-of-learner-scope requests deliberately share one result so an exact-id read
     * cannot be used to discover another learner's durable evidence.
     */
    data object NotFound : OpenTutorEvidenceResult
}

sealed interface ArchiveTutorConversationResult {
    val conversation: TutorConversation
    val archived: Boolean

    data class Archived(
        override val conversation: TutorConversation,
    ) : ArchiveTutorConversationResult {
        override val archived: Boolean = true

        init {
            conversation.requireArchivedConversation()
        }
    }

    data class Replayed(
        override val conversation: TutorConversation,
    ) : ArchiveTutorConversationResult {
        override val archived: Boolean = false

        init {
            conversation.requireArchivedConversation()
        }
    }
}

/**
 * Allocates exactly one ordinal in an active chat. The raw student message stays in chat storage;
 * only its bounded summary and digest are copied into the durable learning receipt.
 */
data class AllocateTutorTurnCommand(
    val learnerScopeId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val expectedConversationStateVersion: Long,
    val expectedTurnOrdinal: Int,
    val turnReceiptId: String,
    val subject: SubjectKind,
    val problemAnchorId: String?,
    val requestVersion: Long,
    val modeVersion: Long,
    val mode: TutorExplanationMode,
    val directiveFingerprint: String,
    val studentMessageFingerprint: String,
    val studentMessageSummary: String,
    val clientIdempotencyKey: String,
    val payloadFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        conversationId.requireTutorMemoryId("Conversation")
        require(conversationGeneration > 0) { "Conversation generation must be positive" }
        require(expectedConversationStateVersion >= 0) {
            "Expected conversation state version must not be negative"
        }
        require(expectedTurnOrdinal > 0) { "Expected turn ordinal must be positive" }
        turnReceiptId.requireTutorMemoryId("Turn receipt")
        problemAnchorId?.requireTutorMemoryId("Problem anchor")
        require(problemAnchorId == null || subject != SubjectKind.GENERAL) {
            "An anchored tutor turn must use a specific subject"
        }
        require(requestVersion >= 0) { "Turn request version must not be negative" }
        require(modeVersion >= 0) { "Turn mode version must not be negative" }
        directiveFingerprint.requireTutorMemoryFingerprint("Turn directive fingerprint")
        studentMessageFingerprint.requireTutorMemoryFingerprint("Student message fingerprint")
        studentMessageSummary.requireTutorMemorySummary("Student message summary")
        clientIdempotencyKey.requireTutorMemoryId("Turn idempotency key")
        payloadFingerprint.requireTutorMemoryFingerprint("Turn payload fingerprint")
        require(occurredAtEpochMillis >= 0) { "Turn time must not be negative" }
    }
}

sealed interface AllocateTutorTurnResult {
    val conversation: TutorConversation
    val receipt: TutorTurnReceipt
    val created: Boolean

    data class Created(
        override val conversation: TutorConversation,
        override val receipt: TutorTurnReceipt,
    ) : AllocateTutorTurnResult {
        override val created: Boolean = true

        init {
            require(conversation.status == TutorConversationStatus.ACTIVE) {
                "A new turn cannot be allocated in an archived conversation"
            }
            require(conversation.stateVersion == receipt.conversationStateVersion) {
                "A new turn receipt must use the committed conversation version"
            }
            requireReceiptBelongsToConversation(conversation, receipt)
        }
    }

    data class Replayed(
        override val conversation: TutorConversation,
        override val receipt: TutorTurnReceipt,
    ) : AllocateTutorTurnResult {
        override val created: Boolean = false

        init {
            require(conversation.stateVersion >= receipt.conversationStateVersion) {
                "A replay cannot return a conversation older than its turn receipt"
            }
            requireReceiptBelongsToConversation(conversation, receipt)
        }
    }
}

/**
 * Reserves one potential learning observation. A request may be prepared only for an anchored
 * real problem. DIRECT-mode stuck points use a separate locally-authored fact path because they
 * are not answers to a guided evidence request.
 */
data class PrepareTutorEvidenceCommand(
    val learnerScopeId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val expectedConversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val evidenceRequestId: String,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val mode: TutorExplanationMode,
    val directiveFingerprint: String,
    val clientIdempotencyKey: String,
    val payloadFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        requireExactEvidenceScope(
            learnerScopeId = learnerScopeId,
            conversationId = conversationId,
            conversationGeneration = conversationGeneration,
            conversationStateVersion = expectedConversationStateVersion,
            turnReceiptId = turnReceiptId,
            turnOrdinal = turnOrdinal,
            subject = subject,
            problemAnchorId = problemAnchorId,
            evidenceRequestId = evidenceRequestId,
            requestVersion = requestVersion,
            modeVersion = modeVersion,
            mode = mode,
            kind = kind,
            directiveFingerprint = directiveFingerprint,
        )
        clientIdempotencyKey.requireTutorMemoryId("Evidence preparation idempotency key")
        payloadFingerprint.requireTutorMemoryFingerprint("Evidence preparation payload fingerprint")
        require(occurredAtEpochMillis >= 0) {
            "Evidence preparation time must not be negative"
        }
    }
}

sealed interface PrepareTutorEvidenceResult {
    val request: TutorEvidenceRequest
    val created: Boolean

    data class Created(
        override val request: TutorEvidenceRequest,
    ) : PrepareTutorEvidenceResult {
        override val created: Boolean = true

        init {
            require(request.status == TutorEvidenceRequestStatus.PENDING) {
                "A new evidence request must be pending"
            }
        }
    }

    /**
     * A late replay may observe the already-finalized request. It never reopens or replaces that
     * terminal state.
     */
    data class Replayed(
        override val request: TutorEvidenceRequest,
    ) : PrepareTutorEvidenceResult {
        override val created: Boolean = false
    }
}

data class TutorLearningEvidenceAnchorFingerprints(
    val questionFingerprint: String,
    val problemRevisionFingerprint: String,
    val fingerprintVersion: String,
    val turnFingerprint: String,
    val directiveFingerprint: String,
) {
    init {
        questionFingerprint.requireTutorMemoryFingerprint("Evidence question fingerprint")
        problemRevisionFingerprint.requireTutorMemoryFingerprint(
            "Evidence problem-revision fingerprint",
        )
        fingerprintVersion.requireTutorMemoryId("Evidence fingerprint version")
        turnFingerprint.requireTutorMemoryFingerprint("Evidence turn fingerprint")
        directiveFingerprint.requireTutorMemoryFingerprint("Evidence directive fingerprint")
    }
}

enum class TutorLearningEvidenceCancellationReason {
    USER_CANCELLED,
    GUIDANCE_DISABLED,
    TURN_SUPERSEDED,
    CONVERSATION_ARCHIVED,
    POLICY_REJECTED,
}

enum class TutorLearningEvidenceOutcome {
    CORRECT,
    INCORRECT,
    ASSISTED_CORRECT,
    SPECIFIC_STUCK,
}

/** Opaque reference to the locally held current-session authorization used for this submission. */
data class TutorLearningEvidenceCurrentSessionReference(
    val authorizationFingerprint: String,
    val learningWritePermissionVersion: Long,
) {
    init {
        authorizationFingerprint.requireTutorMemoryFingerprint(
            "Tutor learning current-session authorization fingerprint",
        )
        require(learningWritePermissionVersion >= 0) {
            "Tutor learning write-permission version must not be negative"
        }
    }
}

/** Bounded assistance behavior; this is an observed fact, not a mastery classification. */
enum class TutorLearningEvidenceAssistance {
    INDEPENDENT,
    ONE_HINT,
    MULTIPLE_HINTS,
    ANSWER_REVEALED,
    UNKNOWN,
}

/**
 * Trusted local semantic evidence submitted to the learner-mastery owner.
 *
 * The candidate contains bounded behavior facts and an opaque current-session reference. It is
 * neither a persisted learning fact nor a session-store entity, and it deliberately carries no
 * proof, source classification, mastery value, data-layer type, or raw answer body.
 */
data class TutorLearningEvidenceSubmission(
    val anchors: TutorLearningEvidenceAnchorFingerprints,
    val responseFingerprint: String,
    val outcome: TutorLearningEvidenceOutcome,
    val occurredAtEpochMillis: Long,
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
        responseFingerprint.requireTutorMemoryFingerprint("Evidence response fingerprint")
        require(occurredAtEpochMillis >= 0) {
            "Evidence occurrence time must not be negative"
        }
        producerVersion.requireTutorMemoryId("Evidence producer version")
        require(attemptOrdinal in 1..MAX_TUTOR_LEARNING_ATTEMPT_ORDINAL) {
            "Evidence attempt ordinal is outside the supported range"
        }
        require(retryCount in 0..MAX_TUTOR_LEARNING_RETRY_COUNT) {
            "Evidence retry count is outside the supported range"
        }
        require(retryCount == attemptOrdinal - 1) {
            "Evidence retry count must describe the exact attempt ordinal"
        }
        require(hintCount in 0..MAX_TUTOR_LEARNING_HINT_COUNT) {
            "Evidence hint count is outside the supported range"
        }
        require(!independentlyAnswered || retryCount == 0) {
            "A retried answer cannot be reported as independently answered"
        }
        require(!independentlyAnswered || hintCount == 0) {
            "A hinted answer cannot be reported as independently answered"
        }
        require(!independentlyAnswered || !answerWasRevealed) {
            "A revealed answer cannot be reported as independently answered"
        }
        require(assistance == derivedAssistance()) {
            "Evidence assistance must match its bounded behavior facts"
        }
        require(outcome != TutorLearningEvidenceOutcome.CORRECT || !answerWasRevealed) {
            "Revealed correctness must be reported as assisted correctness"
        }
        require(
            outcome != TutorLearningEvidenceOutcome.ASSISTED_CORRECT ||
                !independentlyAnswered,
        ) {
            "Assisted correctness cannot be reported as independently answered"
        }
    }

    /**
     * Source-compatible fail-closed boundary for callers that have not yet supplied current
     * permission and behavior facts. It deliberately never invents defaults.
     */
    @Deprecated(
        message = "Supply current-session authorization and exact behavior facts",
        level = DeprecationLevel.WARNING,
    )
    constructor(
        anchors: TutorLearningEvidenceAnchorFingerprints,
        responseFingerprint: String,
        outcome: TutorLearningEvidenceOutcome,
        occurredAtEpochMillis: Long,
        producerVersion: String,
    ) : this(
        anchors = anchors,
        responseFingerprint = responseFingerprint,
        outcome = outcome,
        occurredAtEpochMillis = occurredAtEpochMillis,
        producerVersion = producerVersion,
        currentSessionReference =
            error(
                "Tutor learning evidence requires a current-session authorization reference " +
                    "and exact behavior facts",
            ),
        attemptOrdinal = 1,
        retryCount = 0,
        hintCount = 0,
        answerWasRevealed = false,
        independentlyAnswered = false,
        assistance = TutorLearningEvidenceAssistance.UNKNOWN,
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

private const val MAX_TUTOR_LEARNING_HINT_COUNT = 32
private const val MAX_TUTOR_LEARNING_RETRY_COUNT = 16
private const val MAX_TUTOR_LEARNING_ATTEMPT_ORDINAL = MAX_TUTOR_LEARNING_RETRY_COUNT + 1

sealed interface TutorLearningEvidenceTerminal {
    data class Submitted(
        val evidence: TutorLearningEvidenceSubmission,
    ) : TutorLearningEvidenceTerminal

    /** Cancellation deliberately has no semantic candidate and therefore cannot affect mastery. */
    data class Cancelled(
        val reason: TutorLearningEvidenceCancellationReason,
    ) : TutorLearningEvidenceTerminal
}

/**
 * Settles one request with a compare-and-set from the exact PENDING version. Submission and
 * cancellation share this operation so they cannot both win after a mode switch or process race.
 */
data class FinalizeTutorEvidenceCommand(
    val learnerScopeId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val evidenceRequestId: String,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val mode: TutorExplanationMode,
    val directiveFingerprint: String,
    val expectedEvidenceStateVersion: Long,
    val expectedEvidenceStatus: TutorEvidenceRequestStatus =
        TutorEvidenceRequestStatus.PENDING,
    val terminal: TutorLearningEvidenceTerminal,
    val clientIdempotencyKey: String,
    val payloadFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        requireExactEvidenceScope(
            learnerScopeId = learnerScopeId,
            conversationId = conversationId,
            conversationGeneration = conversationGeneration,
            conversationStateVersion = conversationStateVersion,
            turnReceiptId = turnReceiptId,
            turnOrdinal = turnOrdinal,
            subject = subject,
            problemAnchorId = problemAnchorId,
            evidenceRequestId = evidenceRequestId,
            requestVersion = requestVersion,
            modeVersion = modeVersion,
            mode = mode,
            kind = kind,
            directiveFingerprint = directiveFingerprint,
        )
        require(expectedEvidenceStateVersion >= 0) {
            "Expected evidence state version must not be negative"
        }
        require(expectedEvidenceStatus == TutorEvidenceRequestStatus.PENDING) {
            "Evidence finalization must compare against PENDING"
        }
        clientIdempotencyKey.requireTutorMemoryId("Evidence finalization idempotency key")
        payloadFingerprint.requireTutorMemoryFingerprint("Evidence finalization payload fingerprint")
        require(occurredAtEpochMillis >= 0) {
            "Evidence finalization time must not be negative"
        }
        if (terminal is TutorLearningEvidenceTerminal.Submitted) {
            requireSubmissionMatchesScope(terminal)
        }
    }

    private fun requireSubmissionMatchesScope(
        submitted: TutorLearningEvidenceTerminal.Submitted,
    ) {
        require(submitted.evidence.occurredAtEpochMillis <= occurredAtEpochMillis) {
            "Evidence candidate cannot occur after finalization"
        }
        require(submitted.evidence.anchors.directiveFingerprint == directiveFingerprint) {
            "Evidence directive fingerprint does not match the prepared request"
        }
        require(kind.accepts(submitted.evidence.outcome)) {
            "Evidence outcome does not match the prepared request kind"
        }
    }
}

data class TutorLearningEvidenceReceipt(
    val receiptId: String,
    val receiptFingerprint: String,
) {
    init {
        receiptId.requireTutorMemoryId("Tutor learning evidence receipt id")
        receiptFingerprint.requireTutorMemoryFingerprint(
            "Tutor learning evidence receipt fingerprint",
        )
    }
}

sealed interface FinalizeTutorEvidenceResult {
    val request: TutorEvidenceRequest
    val receipt: TutorLearningEvidenceReceipt?

    data class Submitted(
        override val request: TutorEvidenceRequest,
        override val receipt: TutorLearningEvidenceReceipt,
    ) : FinalizeTutorEvidenceResult {
        init {
            requireSubmittedResult(request, receipt)
        }
    }

    data class Cancelled(
        override val request: TutorEvidenceRequest,
    ) : FinalizeTutorEvidenceResult {
        override val receipt: TutorLearningEvidenceReceipt? = null

        init {
            require(request.status == TutorEvidenceRequestStatus.CANCELLED) {
                "A cancelled result must contain a cancelled request"
            }
        }
    }

    /** Exact same idempotency key and payload; no mastery fact/event is created a second time. */
    data class Replayed(
        override val request: TutorEvidenceRequest,
        override val receipt: TutorLearningEvidenceReceipt?,
    ) : FinalizeTutorEvidenceResult {
        init {
            require(request.status.isTerminal) {
                "A finalization replay must contain a terminal request"
            }
            when (request.status) {
                TutorEvidenceRequestStatus.SUBMITTED ->
                    requireSubmittedResult(request, requireNotNull(receipt))

                TutorEvidenceRequestStatus.CANCELLED -> require(receipt == null) {
                    "A cancelled finalization replay cannot contain a mastery receipt"
                }

                TutorEvidenceRequestStatus.PENDING ->
                    error("A pending request cannot be a finalization replay")
            }
        }
    }
}

enum class TutorLearningMemoryOperation {
    CREATE_CONVERSATION,
    OPEN_CONVERSATION,
    OPEN_TURN,
    OPEN_EVIDENCE,
    ARCHIVE_CONVERSATION,
    ALLOCATE_TURN,
    PREPARE_EVIDENCE,
    FINALIZE_EVIDENCE,
}

enum class TutorLearningMemoryConflictReason {
    NOT_FOUND_OR_OUT_OF_SCOPE,
    GENERATION_MISMATCH,
    CONVERSATION_ARCHIVED,
    STATE_VERSION_MISMATCH,
    TURN_ORDINAL_MISMATCH,
    TURN_SCOPE_MISMATCH,
    EVIDENCE_SCOPE_MISMATCH,
    EVIDENCE_NOT_PENDING,
    IDEMPOTENCY_PAYLOAD_MISMATCH,
    TERMINAL_OUTCOME_MISMATCH,
}

/**
 * Explicit optimistic-concurrency failure. The message intentionally contains neither entity ids
 * nor persistence details and is not suitable for direct display to a student.
 */
class TutorLearningMemoryConflictException(
    val operation: TutorLearningMemoryOperation,
    val reason: TutorLearningMemoryConflictReason,
) : IllegalStateException("Tutor learning-memory $operation failed: $reason")

/**
 * Trusted application persistence boundary. This is not a model-tool schema: model output cannot
 * invoke it directly, select persistence ids, or supply SQL. Implementations must verify every
 * scope from durable local records before mutating state.
 */
interface TutorLearningMemoryRepository {
    /**
     * Same scoped idempotency key plus same payload returns [CreateTutorConversationResult.Replayed].
     * A different payload for that key throws [TutorLearningMemoryConflictException].
     */
    suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): CreateTutorConversationResult

    suspend fun openConversation(
        command: OpenTutorConversationCommand,
    ): OpenTutorConversationResult

    /** Returns only this learner's most recently created active conversation, if one exists. */
    suspend fun latestActiveConversation(learnerScopeId: String): TutorConversation?

    /**
     * Safe compatibility default: never returns a conversation outside [conversationIdPrefix].
     * Implementations with indexed storage should override this to search inside the namespace.
     */
    suspend fun latestActiveConversationInNamespace(
        learnerScopeId: String,
        conversationIdPrefix: String,
    ): TutorConversation? {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        conversationIdPrefix.requireTutorMemoryId("Conversation namespace")
        return latestActiveConversation(learnerScopeId)
            ?.takeIf { it.conversationId.startsWith(conversationIdPrefix) }
    }

    /** Opens an exact receipt only when it belongs to [learnerScopeId]. */
    suspend fun openTurn(
        learnerScopeId: String,
        turnReceiptId: String,
    ): OpenTutorTurnResult {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        turnReceiptId.requireTutorMemoryId("Turn receipt")
        return OpenTutorTurnResult.NotFound
    }

    /** Opens exactly [evidenceRequestId] only when it belongs to [learnerScopeId]. */
    suspend fun openEvidenceRequest(
        learnerScopeId: String,
        evidenceRequestId: String,
    ): OpenTutorEvidenceResult {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        evidenceRequestId.requireTutorMemoryId("Evidence request")
        return OpenTutorEvidenceResult.NotFound
    }

    /** Archives the exact active generation using a conversation-version compare-and-set. */
    suspend fun archiveConversation(
        command: ArchiveTutorConversationCommand,
    ): ArchiveTutorConversationResult

    /** Allocates [AllocateTutorTurnCommand.expectedTurnOrdinal] atomically and at most once. */
    suspend fun allocateTurn(command: AllocateTutorTurnCommand): AllocateTutorTurnResult

    /**
     * Creates one PENDING request only after the turn, problem anchor, subject, mode, and directive
     * have all been verified in the same learner and conversation generation.
     */
    suspend fun prepareEvidenceRequest(
        command: PrepareTutorEvidenceCommand,
    ): PrepareTutorEvidenceResult

    /**
     * Atomically changes one exact PENDING request to SUBMITTED or CANCELLED. For SUBMITTED, the
     * source fact and terminal request update must commit together; neither may survive alone.
     */
    suspend fun finalizeEvidence(
        command: FinalizeTutorEvidenceCommand,
    ): FinalizeTutorEvidenceResult
}

private fun TutorConversation.requireArchivedConversation() {
    require(status == TutorConversationStatus.ARCHIVED) {
        "An archive result must contain an archived conversation"
    }
}

private fun requireReceiptBelongsToConversation(
    conversation: TutorConversation,
    receipt: TutorTurnReceipt,
) {
    require(receipt.conversationId == conversation.conversationId) {
        "Turn receipt belongs to a different conversation"
    }
    require(receipt.conversationGeneration == conversation.generation) {
        "Turn receipt belongs to a different conversation generation"
    }
}

private fun requireExactEvidenceScope(
    learnerScopeId: String,
    conversationId: String,
    conversationGeneration: Long,
    conversationStateVersion: Long,
    turnReceiptId: String,
    turnOrdinal: Int,
    subject: SubjectKind,
    problemAnchorId: String,
    evidenceRequestId: String,
    requestVersion: Long,
    modeVersion: Long,
    mode: TutorExplanationMode,
    kind: TutorEvidenceRequestKind,
    directiveFingerprint: String,
) {
    learnerScopeId.requireTutorMemoryId("Learner scope")
    conversationId.requireTutorMemoryId("Conversation")
    require(conversationGeneration > 0) { "Conversation generation must be positive" }
    require(conversationStateVersion >= 0) {
        "Conversation state version must not be negative"
    }
    turnReceiptId.requireTutorMemoryId("Turn receipt")
    require(turnOrdinal > 0) { "Turn ordinal must be positive" }
    require(subject != SubjectKind.GENERAL) {
        "Learning evidence requires a specific subject"
    }
    problemAnchorId.requireTutorMemoryId("Problem anchor")
    evidenceRequestId.requireTutorMemoryId("Evidence request")
    require(requestVersion >= 0) { "Evidence request version must not be negative" }
    require(modeVersion >= 0) { "Evidence mode version must not be negative" }
    require(mode == TutorExplanationMode.GUIDED) {
        "Evidence requests may only be prepared for guided turns"
    }
    directiveFingerprint.requireTutorMemoryFingerprint("Evidence directive fingerprint")
}

private fun TutorEvidenceRequestKind.accepts(outcome: TutorLearningEvidenceOutcome): Boolean =
    when (this) {
        TutorEvidenceRequestKind.CHOICE,
        TutorEvidenceRequestKind.VISUAL_TARGET,
        -> outcome != TutorLearningEvidenceOutcome.SPECIFIC_STUCK

        TutorEvidenceRequestKind.SPECIFIC_STUCK ->
            outcome == TutorLearningEvidenceOutcome.SPECIFIC_STUCK

        TutorEvidenceRequestKind.FREE_RESPONSE -> false
    }

private fun requireSubmittedResult(
    request: TutorEvidenceRequest,
    receipt: TutorLearningEvidenceReceipt,
) {
    require(request.status == TutorEvidenceRequestStatus.SUBMITTED) {
        "A submitted result must contain a submitted request"
    }
    require(request.terminalReceiptId == receipt.receiptId) {
        "Submitted evidence request and mastery receipt do not match"
    }
}

private fun String.requireTutorMemoryId(label: String) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= MAX_TUTOR_MEMORY_ID_CHARS &&
            none(Char::isISOControl),
    ) { "$label must be a trimmed non-blank opaque id" }
}

private fun String.requireTutorMemoryFingerprint(label: String) {
    require(length == SHA256_HEX_CHARS && all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label must be a lowercase SHA-256 value"
    }
}

private fun String.requireTutorMemorySummary(label: String) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= MAX_TUTOR_MEMORY_SUMMARY_CHARS &&
            none { it.isISOControl() && it !in "\n\r\t" },
    ) { "$label must be trimmed, non-blank, and bounded" }
}

private const val MAX_TUTOR_MEMORY_ID_CHARS = 256
private const val MAX_TUTOR_MEMORY_SUMMARY_CHARS = 512
private const val SHA256_HEX_CHARS = 64
