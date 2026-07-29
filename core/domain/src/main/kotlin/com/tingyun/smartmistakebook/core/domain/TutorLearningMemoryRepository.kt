package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
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

sealed interface TutorLearningEvidenceTerminal {
    /**
     * A trusted local fact only. It contains no attribution, weight, SQL command, or model-selected
     * database identity.
     */
    data class Submitted(
        val sourceFact: LearningObservationSourceFact,
        val anchors: TutorLearningEvidenceAnchorFingerprints,
    ) : TutorLearningEvidenceTerminal

    /** Cancellation deliberately has no source-fact field and therefore cannot affect mastery. */
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
        val fact = submitted.sourceFact
        require(fact.learnerScopeId == learnerScopeId) {
            "Evidence source fact belongs to a different learner scope"
        }
        require(fact.conversationId == conversationId) {
            "Evidence source fact belongs to a different conversation"
        }
        require(fact.conversationGeneration == conversationGeneration) {
            "Evidence source fact belongs to a different conversation generation"
        }
        require(fact.turnReceiptId == turnReceiptId) {
            "Evidence source fact belongs to a different turn"
        }
        require(fact.evidenceRequestId == evidenceRequestId) {
            "Evidence source fact belongs to a different request"
        }
        require(fact.anchorId == problemAnchorId && fact.subject == subject) {
            "Evidence source fact belongs to a different problem scope"
        }
        require(fact.source == kind.expectedLearningObservationSource()) {
            "Evidence source-fact type does not match the prepared request"
        }
        require(fact.occurredAtEpochMillis <= occurredAtEpochMillis) {
            "Evidence source fact cannot occur after finalization"
        }
        require(submitted.anchors.directiveFingerprint == directiveFingerprint) {
            "Evidence directive fingerprint does not match the prepared request"
        }
    }
}

sealed interface FinalizeTutorEvidenceResult {
    val request: TutorEvidenceRequest
    val sourceFact: LearningObservationSourceFact?

    data class Submitted(
        override val request: TutorEvidenceRequest,
        override val sourceFact: LearningObservationSourceFact,
    ) : FinalizeTutorEvidenceResult {
        init {
            requireSubmittedResult(request, sourceFact)
        }
    }

    data class Cancelled(
        override val request: TutorEvidenceRequest,
    ) : FinalizeTutorEvidenceResult {
        override val sourceFact: LearningObservationSourceFact? = null

        init {
            require(request.status == TutorEvidenceRequestStatus.CANCELLED) {
                "A cancelled result must contain a cancelled request"
            }
        }
    }

    /** Exact same idempotency key and payload; no terminal fact is created a second time. */
    data class Replayed(
        override val request: TutorEvidenceRequest,
        override val sourceFact: LearningObservationSourceFact?,
    ) : FinalizeTutorEvidenceResult {
        init {
            require(request.status.isTerminal) {
                "A finalization replay must contain a terminal request"
            }
            when (request.status) {
                TutorEvidenceRequestStatus.SUBMITTED ->
                    requireSubmittedResult(request, requireNotNull(sourceFact))

                TutorEvidenceRequestStatus.CANCELLED -> require(sourceFact == null) {
                    "A cancelled finalization replay cannot contain a source fact"
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

    /** Opens an exact receipt only when it belongs to [learnerScopeId]. */
    suspend fun openTurn(
        learnerScopeId: String,
        turnReceiptId: String,
    ): OpenTutorTurnResult {
        learnerScopeId.requireTutorMemoryId("Learner scope")
        turnReceiptId.requireTutorMemoryId("Turn receipt")
        return OpenTutorTurnResult.NotFound
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

private fun TutorEvidenceRequestKind.expectedLearningObservationSource():
    LearningObservationSource = when (this) {
    TutorEvidenceRequestKind.CHOICE -> LearningObservationSource.TUTOR_CHOICE
    TutorEvidenceRequestKind.FREE_RESPONSE -> LearningObservationSource.TUTOR_FREE_RESPONSE
    TutorEvidenceRequestKind.VISUAL_TARGET -> LearningObservationSource.TUTOR_VISUAL_TARGET
    TutorEvidenceRequestKind.SPECIFIC_STUCK -> LearningObservationSource.TUTOR_SPECIFIC_STUCK
}

private fun requireSubmittedResult(
    request: TutorEvidenceRequest,
    fact: LearningObservationSourceFact,
) {
    require(request.status == TutorEvidenceRequestStatus.SUBMITTED) {
        "A submitted result must contain a submitted request"
    }
    require(request.terminalSourceFactId == fact.sourceFactId) {
        "Submitted evidence request and source fact do not match"
    }
    require(request.conversationId == fact.conversationId) {
        "Submitted source fact belongs to a different conversation"
    }
    require(request.conversationGeneration == fact.conversationGeneration) {
        "Submitted source fact belongs to a different conversation generation"
    }
    require(request.turnReceiptId == fact.turnReceiptId) {
        "Submitted source fact belongs to a different turn"
    }
    require(request.evidenceRequestId == fact.evidenceRequestId) {
        "Submitted source fact belongs to a different evidence request"
    }
    require(request.problemAnchorId == fact.anchorId && request.subject == fact.subject) {
        "Submitted source fact belongs to a different problem scope"
    }
    require(request.kind.expectedLearningObservationSource() == fact.source) {
        "Submitted source-fact type does not match the evidence request"
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
