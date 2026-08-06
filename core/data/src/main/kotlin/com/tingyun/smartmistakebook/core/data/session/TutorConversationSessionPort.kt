package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode

internal data class TutorConversationSessionSnapshot(
    val scope: SessionScope,
    val conversationId: String,
    val generation: Long,
    val status: TutorConversationStatus,
    val version: SessionVersion,
    val createdAtEpochMillis: Long,
    val archivedAtEpochMillis: Long?,
) {
    init {
        conversationId.requireSessionIdentifier("Tutor conversation id")
        require(generation > 0) { "Tutor conversation generation must be positive" }
        require(createdAtEpochMillis >= 0) {
            "Tutor conversation creation time must not be negative"
        }
        require(archivedAtEpochMillis == null || archivedAtEpochMillis >= createdAtEpochMillis) {
            "Tutor conversation archive time is invalid"
        }
    }
}

internal data class TutorTurnSessionReceipt(
    val scope: SessionScope,
    val turnReceiptId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationVersion: SessionVersion,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val sessionAnchorId: String?,
    val requestVersion: Long,
    val modeVersion: Long,
    val explanationMode: TutorExplanationMode,
    val directiveFingerprint: String,
    val studentMessageFingerprint: String,
    val studentMessageSummary: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        turnReceiptId.requireSessionIdentifier("Tutor turn receipt id")
        conversationId.requireSessionIdentifier("Tutor conversation id")
        require(conversationGeneration > 0) {
            "Tutor conversation generation must be positive"
        }
        require(turnOrdinal > 0) { "Tutor turn ordinal must be positive" }
        sessionAnchorId?.requireSessionIdentifier("Tutor turn session anchor id")
        require(requestVersion >= 0 && modeVersion >= 0) {
            "Tutor turn request and mode versions must not be negative"
        }
        directiveFingerprint.requireSessionFingerprint("Tutor directive fingerprint")
        studentMessageFingerprint.requireSessionFingerprint("Tutor message fingerprint")
        require(
            studentMessageSummary.isNotBlank() &&
                studentMessageSummary.length <= MAX_TUTOR_SESSION_SUMMARY_CHARS,
        ) { "Tutor message summary is outside its session budget" }
        require(occurredAtEpochMillis >= 0) { "Tutor turn time must not be negative" }
    }
}

internal data class TutorEvidenceSessionSnapshot(
    val scope: SessionScope,
    val evidenceRequestId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationVersion: SessionVersion,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val sessionAnchorId: String,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val directiveFingerprint: String,
    val status: TutorEvidenceRequestStatus,
    val version: SessionVersion,
    val createdAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long?,
    val terminalReceiptId: String?,
) {
    init {
        evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
        conversationId.requireSessionIdentifier("Tutor conversation id")
        require(conversationGeneration > 0) {
            "Tutor conversation generation must be positive"
        }
        turnReceiptId.requireSessionIdentifier("Tutor turn receipt id")
        require(turnOrdinal > 0) { "Tutor turn ordinal must be positive" }
        sessionAnchorId.requireSessionIdentifier("Tutor evidence session anchor id")
        require(requestVersion >= 0 && modeVersion >= 0) {
            "Tutor evidence request and mode versions must not be negative"
        }
        directiveFingerprint.requireSessionFingerprint("Tutor directive fingerprint")
        require(createdAtEpochMillis >= 0) {
            "Tutor evidence creation time must not be negative"
        }
        require(resolvedAtEpochMillis == null || resolvedAtEpochMillis >= createdAtEpochMillis) {
            "Tutor evidence resolution time is invalid"
        }
        terminalReceiptId?.requireSessionIdentifier("Tutor terminal receipt id")
    }
}

internal sealed interface TutorConversationSessionReadQuery {
    val scope: SessionScope

    data class Conversation(
        override val scope: SessionScope,
        val conversationId: String,
        val generation: Long,
    ) : TutorConversationSessionReadQuery

    data class LatestActive(
        override val scope: SessionScope,
    ) : TutorConversationSessionReadQuery

    data class LatestActiveInNamespace(
        override val scope: SessionScope,
        val conversationIdPrefix: String,
    ) : TutorConversationSessionReadQuery

    data class Turn(
        override val scope: SessionScope,
        val turnReceiptId: String,
    ) : TutorConversationSessionReadQuery

    data class Evidence(
        override val scope: SessionScope,
        val evidenceRequestId: String,
    ) : TutorConversationSessionReadQuery
}

internal sealed interface TutorConversationSessionMutation {
    val scope: SessionScope
    val operation: SessionOperationIdentity

    data class CreateConversation(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        val conversationId: String,
        val generation: Long,
    ) : TutorConversationSessionMutation

    data class ArchiveConversation(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        val conversationId: String,
        val generation: Long,
        val expectedVersion: SessionVersion,
    ) : TutorConversationSessionMutation

    data class AllocateTurn(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        val conversationId: String,
        val conversationGeneration: Long,
        val expectedConversationVersion: SessionVersion,
        val expectedTurnOrdinal: Int,
        val turnReceiptId: String,
        val clientTurnId: String,
        val subject: SubjectKind,
        val sessionAnchorId: String?,
        val explanationMode: TutorExplanationMode,
        val modeVersion: Long,
        val directiveFingerprint: String,
        val studentMessageFingerprint: String,
        val studentMessageSummary: String,
        val occurredAtEpochMillis: Long,
    ) : TutorConversationSessionMutation

    data class PrepareEvidence(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        val evidenceRequestId: String,
        val conversationId: String,
        val conversationGeneration: Long,
        val conversationVersion: SessionVersion,
        val turnReceiptId: String,
        val turnOrdinal: Int,
        val subject: SubjectKind,
        val sessionAnchorId: String,
        val kind: TutorEvidenceRequestKind,
        val explanationMode: TutorExplanationMode,
        val modeVersion: Long,
        val directiveFingerprint: String,
    ) : TutorConversationSessionMutation

    data class FinalizeEvidence(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        val evidenceRequestId: String,
        val conversationId: String,
        val conversationGeneration: Long,
        val conversationVersion: SessionVersion,
        val turnReceiptId: String,
        val turnOrdinal: Int,
        val subject: SubjectKind,
        val sessionAnchorId: String,
        val kind: TutorEvidenceRequestKind,
        val explanationMode: TutorExplanationMode,
        val modeVersion: Long,
        val directiveFingerprint: String,
        val expectedEvidenceVersion: SessionVersion,
        val terminalStatus: TutorEvidenceRequestStatus,
    ) : TutorConversationSessionMutation
}

internal sealed interface TutorConversationSessionReadResult {
    data class Conversation(
        val snapshot: TutorConversationSessionSnapshot?,
    ) : TutorConversationSessionReadResult

    data class Turn(
        val receipt: TutorTurnSessionReceipt?,
    ) : TutorConversationSessionReadResult

    data class Evidence(
        val snapshot: TutorEvidenceSessionSnapshot?,
    ) : TutorConversationSessionReadResult
}

internal sealed interface TutorConversationSessionMutationResult {
    val receipt: SessionMutationReceipt

    data class Conversation(
        override val receipt: SessionMutationReceipt,
        val snapshot: TutorConversationSessionSnapshot?,
    ) : TutorConversationSessionMutationResult

    data class Turn(
        override val receipt: SessionMutationReceipt,
        val conversation: TutorConversationSessionSnapshot?,
        val turn: TutorTurnSessionReceipt?,
    ) : TutorConversationSessionMutationResult

    data class Evidence(
        override val receipt: SessionMutationReceipt,
        val snapshot: TutorEvidenceSessionSnapshot?,
    ) : TutorConversationSessionMutationResult
}

internal interface TutorConversationSessionPort {
    suspend fun read(
        query: TutorConversationSessionReadQuery,
    ): TutorConversationSessionReadResult

    suspend fun mutate(
        command: TutorConversationSessionMutation,
    ): TutorConversationSessionMutationResult
}

internal const val MAX_TUTOR_SESSION_SUMMARY_CHARS = 512
