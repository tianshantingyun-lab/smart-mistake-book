package com.tingyun.smartmistakebook.core.model

/**
 * Durable boundary for one chat. The id is application-generated and opaque; chat content lives
 * in turn-scoped records and is never inferred from another conversation.
 */
enum class TutorConversationStatus {
    ACTIVE,
    ARCHIVED,
    ;

    fun canTransitionTo(next: TutorConversationStatus): Boolean =
        this == ACTIVE && next == ARCHIVED
}

data class TutorConversation(
    val conversationId: String,
    val learnerScopeId: String,
    val generation: Long,
    val status: TutorConversationStatus,
    val createdAtEpochMillis: Long,
    val archivedAtEpochMillis: Long?,
    val stateVersion: Long,
) {
    init {
        conversationId.requireLearningMemoryId("Tutor conversation id")
        learnerScopeId.requireLearningMemoryId("Tutor learner scope id")
        require(generation > 0) { "Tutor conversation generation must be positive" }
        require(createdAtEpochMillis >= 0) {
            "Tutor conversation creation time must not be negative"
        }
        archivedAtEpochMillis?.let {
            require(it >= createdAtEpochMillis) {
                "Tutor conversation archive time must not precede creation"
            }
        }
        require(
            (status == TutorConversationStatus.ARCHIVED) == (archivedAtEpochMillis != null),
        ) { "Only archived tutor conversations require an archive time" }
        require(stateVersion >= 0) { "Tutor conversation state version must not be negative" }
    }
}

/**
 * Immutable receipt for the student-authored part of a turn. Only a bounded summary and digest
 * cross this boundary; the raw message remains in the conversation store.
 */
data class TutorTurnReceipt(
    val turnReceiptId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val problemAnchorId: String?,
    val requestVersion: Long,
    val modeVersion: Long,
    val explanationMode: TutorExplanationMode,
    val directiveFingerprint: String,
    val studentMessageFingerprint: String,
    val studentMessageSummary: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        turnReceiptId.requireLearningMemoryId("Tutor turn receipt id")
        conversationId.requireLearningMemoryId("Tutor turn conversation id")
        require(conversationGeneration > 0) {
            "Tutor turn conversation generation must be positive"
        }
        require(conversationStateVersion >= 0) {
            "Tutor turn conversation state version must not be negative"
        }
        require(turnOrdinal > 0) { "Tutor turn ordinal must be positive" }
        problemAnchorId?.requireLearningMemoryId("Tutor turn problem anchor id")
        require(problemAnchorId == null || subject != SubjectKind.GENERAL) {
            "An anchored tutor turn must have a specific subject"
        }
        require(requestVersion >= 0) { "Tutor turn request version must not be negative" }
        require(modeVersion >= 0) { "Tutor turn mode version must not be negative" }
        directiveFingerprint.requireSha256("Tutor turn directive fingerprint")
        studentMessageFingerprint.requireSha256("Tutor turn message fingerprint")
        studentMessageSummary.requireLearningMemorySummary(
            "Tutor turn message summary",
            MAX_SUMMARY_CHARS,
        )
        require(occurredAtEpochMillis >= 0) { "Tutor turn time must not be negative" }
    }

    companion object {
        const val MAX_SUMMARY_CHARS = 512
    }
}

enum class TutorEvidenceRequestKind {
    CHOICE,
    FREE_RESPONSE,
    VISUAL_TARGET,
    SPECIFIC_STUCK,
}

/**
 * A request is settled through one local compare-and-set. Terminal states cannot transition,
 * preventing a late submit from racing a cancellation into a second learning fact.
 */
enum class TutorEvidenceRequestStatus {
    PENDING,
    SUBMITTED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this != PENDING

    fun canTransitionTo(next: TutorEvidenceRequestStatus): Boolean =
        this == PENDING && next.isTerminal
}

data class TutorEvidenceRequest(
    val evidenceRequestId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val explanationMode: TutorExplanationMode,
    val directiveFingerprint: String,
    val status: TutorEvidenceRequestStatus,
    val stateVersion: Long,
    val createdAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long?,
    val terminalReceiptId: String?,
) {
    init {
        evidenceRequestId.requireLearningMemoryId("Tutor evidence request id")
        conversationId.requireLearningMemoryId("Tutor evidence conversation id")
        require(conversationGeneration > 0) {
            "Tutor evidence conversation generation must be positive"
        }
        require(conversationStateVersion >= 0) {
            "Tutor evidence conversation state version must not be negative"
        }
        turnReceiptId.requireLearningMemoryId("Tutor evidence turn receipt id")
        require(turnOrdinal > 0) { "Tutor evidence turn ordinal must be positive" }
        require(subject != SubjectKind.GENERAL) {
            "Tutor evidence requests require a specific subject"
        }
        problemAnchorId.requireLearningMemoryId("Tutor evidence problem anchor id")
        require(requestVersion >= 0) { "Tutor evidence request version must not be negative" }
        require(modeVersion >= 0) { "Tutor evidence mode version must not be negative" }
        require(explanationMode == TutorExplanationMode.GUIDED) {
            "Tutor evidence requests may only be created for guided turns"
        }
        directiveFingerprint.requireSha256("Tutor evidence directive fingerprint")
        require(stateVersion >= 0) { "Tutor evidence state version must not be negative" }
        require(createdAtEpochMillis >= 0) {
            "Tutor evidence request creation time must not be negative"
        }
        resolvedAtEpochMillis?.let {
            require(it >= createdAtEpochMillis) {
                "Tutor evidence resolution must not precede its request"
            }
        }
        terminalReceiptId?.requireLearningMemoryId("Tutor evidence terminal receipt id")
        when (status) {
            TutorEvidenceRequestStatus.PENDING -> require(
                resolvedAtEpochMillis == null && terminalReceiptId == null,
            ) { "Pending tutor evidence cannot contain a terminal resolution" }

            TutorEvidenceRequestStatus.SUBMITTED -> require(resolvedAtEpochMillis != null) {
                "Submitted tutor evidence requires a resolution time"
            }

            TutorEvidenceRequestStatus.CANCELLED,
            -> require(resolvedAtEpochMillis != null && terminalReceiptId == null) {
                "Non-submitted tutor evidence must resolve without a mastery receipt"
            }
        }
    }

    fun matches(turnReceipt: TutorTurnReceipt): Boolean =
        turnReceiptId == turnReceipt.turnReceiptId &&
            turnOrdinal == turnReceipt.turnOrdinal &&
            conversationId == turnReceipt.conversationId &&
            conversationGeneration == turnReceipt.conversationGeneration &&
            conversationStateVersion == turnReceipt.conversationStateVersion &&
            subject == turnReceipt.subject &&
            problemAnchorId == turnReceipt.problemAnchorId &&
            requestVersion == turnReceipt.requestVersion &&
            modeVersion == turnReceipt.modeVersion &&
            explanationMode == turnReceipt.explanationMode &&
            directiveFingerprint == turnReceipt.directiveFingerprint
}

/**
 * Privacy-minimal identity for a real problem that was not necessarily collected. Deliberately
 * excludes question text, answers, image locations, collection metadata, and storage row ids.
 */
data class LearningProblemAnchor(
    val anchorId: String,
    val learnerScopeId: String,
    val subject: SubjectKind,
    val questionFingerprint: String,
    val revisionFingerprint: String,
    val fingerprintVersion: String,
    val createdAtEpochMillis: Long,
) {
    init {
        anchorId.requireLearningMemoryId("Learning problem anchor id")
        learnerScopeId.requireLearningMemoryId("Learning problem anchor learner scope id")
        require(subject != SubjectKind.GENERAL) {
            "Learning problem anchors require a specific subject"
        }
        questionFingerprint.requireSha256("Learning question fingerprint")
        revisionFingerprint.requireSha256("Learning problem revision fingerprint")
        fingerprintVersion.requireLearningMemoryId("Learning problem fingerprint version")
        require(createdAtEpochMillis >= 0) {
            "Learning problem anchor creation time must not be negative"
        }
    }
}

/**
 * Immutable, pre-attribution fact. It records what happened without granting a model database
 * access or claiming which knowledge point changed.
 */
enum class LearningObservationFactKind {
    VERIFIED_CORRECT_RESPONSE,
    VERIFIED_INCORRECT_RESPONSE,
    MODEL_EVALUATED_CORRECT_RESPONSE,
    MODEL_EVALUATED_INCORRECT_RESPONSE,
    MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE,
    OPEN_RESPONSE_SUBMITTED,
    SPECIFIC_STUCK,
    IMPORTED_VISIBLE_ERROR,
}

data class LearningObservationSourceFact(
    val sourceFactId: String,
    val learnerScopeId: String,
    val source: LearningObservationSource,
    val factKind: LearningObservationFactKind,
    val anchorId: String,
    val subject: SubjectKind,
    val conversationGeneration: Long?,
    val conversationId: String?,
    val turnReceiptId: String?,
    val evidenceRequestId: String?,
    val responseFingerprint: String,
    val responseSummary: String,
    val occurredAtEpochMillis: Long,
    val sourceVersion: String,
) {
    init {
        sourceFactId.requireLearningMemoryId("Learning source-fact id")
        learnerScopeId.requireLearningMemoryId("Learning source-fact learner scope id")
        anchorId.requireLearningMemoryId("Learning source-fact anchor id")
        require(subject != SubjectKind.GENERAL) {
            "Learning source facts require a specific subject"
        }
        if (source in tutorSources) {
            require(
                conversationId != null &&
                    conversationGeneration != null &&
                    conversationGeneration > 0 &&
                    turnReceiptId != null,
            ) {
                "Tutor source facts require conversation generation and turn scope"
            }
            require(
                evidenceRequestId != null ||
                    source == LearningObservationSource.TUTOR_SPECIFIC_STUCK,
            ) {
                "Guided tutor source facts require evidence-request scope"
            }
        } else {
            require(
                conversationId == null &&
                    conversationGeneration == null &&
                    turnReceiptId == null &&
                    evidenceRequestId == null,
            ) {
                "Non-tutor source facts cannot inherit tutor conversation scope"
            }
        }
        require(factKind in source.allowedFactKinds()) {
            "Learning source-fact kind is not valid for its source"
        }
        conversationId?.requireLearningMemoryId("Learning source-fact conversation id")
        turnReceiptId?.requireLearningMemoryId("Learning source-fact turn receipt id")
        evidenceRequestId?.requireLearningMemoryId("Learning source-fact evidence request id")
        responseFingerprint.requireSha256("Learning source-fact response fingerprint")
        responseSummary.requireLearningMemorySummary(
            "Learning source-fact response summary",
            MAX_SUMMARY_CHARS,
        )
        require(occurredAtEpochMillis >= 0) {
            "Learning source-fact time must not be negative"
        }
        sourceVersion.requireLearningMemoryId("Learning source-fact version")
    }

    companion object {
        const val MAX_SUMMARY_CHARS = 512

        val tutorSources: Set<LearningObservationSource> = setOf(
            LearningObservationSource.TUTOR_CHOICE,
            LearningObservationSource.TUTOR_FREE_RESPONSE,
            LearningObservationSource.TUTOR_VISUAL_TARGET,
            LearningObservationSource.TUTOR_SPECIFIC_STUCK,
        )
    }
}

private fun LearningObservationSource.allowedFactKinds(): Set<LearningObservationFactKind> =
    when (this) {
        LearningObservationSource.IMPORTED_MISTAKE -> setOf(
            LearningObservationFactKind.IMPORTED_VISIBLE_ERROR,
        )

        LearningObservationSource.TUTOR_CHOICE,
        LearningObservationSource.TUTOR_VISUAL_TARGET,
        -> setOf(
            LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
            LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
            LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE,
        )

        LearningObservationSource.TUTOR_FREE_RESPONSE -> setOf(
            LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED,
        )

        LearningObservationSource.TUTOR_SPECIFIC_STUCK -> setOf(
            LearningObservationFactKind.SPECIFIC_STUCK,
        )

        LearningObservationSource.CAPTURED_REVIEW_RESPONSE -> setOf(
            LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE,
            LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE,
            LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED,
        )
    }

private fun String.requireLearningMemoryId(label: String) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= MAX_LEARNING_MEMORY_ID_CHARS &&
            none(Char::isISOControl),
    ) { "$label must be a trimmed non-blank opaque id of at most $MAX_LEARNING_MEMORY_ID_CHARS characters" }
}

private fun String.requireSha256(label: String) {
    require(length == SHA256_HEX_CHARS && all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label must be a lowercase SHA-256 value"
    }
}

private fun String.requireLearningMemorySummary(label: String, maxChars: Int) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= maxChars &&
            none { it.isISOControl() && it !in "\n\r\t" },
    ) { "$label must be trimmed, non-blank, and at most $maxChars characters" }
}

private const val MAX_LEARNING_MEMORY_ID_CHARS = 256
private const val SHA256_HEX_CHARS = 64
