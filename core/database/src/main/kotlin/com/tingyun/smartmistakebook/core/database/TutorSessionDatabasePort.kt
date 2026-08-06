package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode

/**
 * Session-only tutor persistence. It deliberately has no submitted-evidence or learning-fact API.
 */
interface TutorConversationSessionDatabasePort {
    suspend fun createTutorConversation(
        command: CreateTutorConversationCommand,
    ): TutorConversationWriteResult

    suspend fun openTutorConversation(
        learnerId: String,
        conversationId: String,
        conversationGeneration: Long,
    ): TutorConversation?

    suspend fun latestActiveTutorConversation(
        learnerId: String,
    ): TutorConversation?

    suspend fun latestActiveTutorConversationInNamespace(
        learnerId: String,
        conversationIdPrefix: String,
    ): TutorConversation?

    suspend fun archiveTutorConversation(
        command: ArchiveTutorConversationCommand,
    ): TutorConversationArchiveWriteResult

    suspend fun allocateTutorTurn(
        command: AllocateTutorTurnCommand,
    ): TutorTurnAllocationResult

    suspend fun openTutorTurn(
        learnerId: String,
        turnReceiptId: String,
    ): TutorTurnReadResult

    suspend fun openTutorEvidenceRequest(
        learnerId: String,
        evidenceRequestId: String,
    ): TutorEvidenceRequest?

    suspend fun prepareTutorEvidenceRequest(
        command: PrepareTutorEvidenceRequestCommand,
    ): TutorEvidencePreparationResult

    suspend fun cancelTutorEvidenceRequest(
        command: CancelTutorEvidenceRequestCommand,
    ): TutorEvidenceCancellationResult
}

data class CancelTutorEvidenceRequestCommand(
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val evidenceRequestId: String,
    val expectedEvidenceStateVersion: Long,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val directiveFingerprint: String,
    val idempotencyKey: String,
    val payloadFingerprint: String,
) {
    init {
        requireTutorSessionOpaque(learnerId, "learnerId")
        requireTutorSessionOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0)
        require(conversationStateVersion >= 0)
        requireTutorSessionOpaque(turnReceiptId, "turnReceiptId")
        require(turnOrdinal > 0)
        requireTutorSessionOpaque(problemAnchorId, "problemAnchorId")
        requireTutorSessionOpaque(evidenceRequestId, "evidenceRequestId")
        require(expectedEvidenceStateVersion >= 0)
        require(requestVersion >= 0 && modeVersion >= 0)
        requireTutorSessionFingerprint(directiveFingerprint, "directiveFingerprint")
        requireTutorSessionOpaque(idempotencyKey, "idempotencyKey")
        requireTutorSessionFingerprint(payloadFingerprint, "payloadFingerprint")
    }
}

data class TutorEvidenceCancellationResult(
    val replayed: Boolean,
    val request: TutorEvidenceRequest,
)

enum class TutorLearningEvidenceSessionRecordState {
    PENDING_MASTERY,
    MASTERY_ACKNOWLEDGED,
}

data class BeginTutorLearningEvidenceSessionIntentCommand(
    val learnerId: String,
    val evidenceRequestId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val sessionAnchorId: String,
    val evidenceKind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val idempotencyKey: String,
    val candidateFingerprint: String,
) {
    init {
        requireTutorSessionOpaque(learnerId, "learnerId")
        requireTutorSessionOpaque(evidenceRequestId, "evidenceRequestId")
        requireTutorSessionOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0)
        require(conversationStateVersion >= 0)
        requireTutorSessionOpaque(turnReceiptId, "turnReceiptId")
        require(turnOrdinal > 0)
        requireTutorSessionOpaque(sessionAnchorId, "sessionAnchorId")
        require(requestVersion >= 0 && modeVersion >= 0)
        requireTutorSessionOpaque(idempotencyKey, "idempotencyKey")
        requireTutorSessionFingerprint(candidateFingerprint, "candidateFingerprint")
    }
}

data class AcknowledgeTutorLearningEvidenceSessionCommand(
    val learnerId: String,
    val evidenceRequestId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val turnReceiptId: String,
    val candidateFingerprint: String,
    val expectedStateVersion: Long,
    val masteryReceiptId: String,
    val masteryReceiptFingerprint: String,
) {
    init {
        requireTutorSessionOpaque(learnerId, "learnerId")
        requireTutorSessionOpaque(evidenceRequestId, "evidenceRequestId")
        requireTutorSessionOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0)
        requireTutorSessionOpaque(turnReceiptId, "turnReceiptId")
        requireTutorSessionFingerprint(candidateFingerprint, "candidateFingerprint")
        require(expectedStateVersion >= 0)
        requireTutorSessionOpaque(masteryReceiptId, "masteryReceiptId")
        requireTutorSessionFingerprint(masteryReceiptFingerprint, "masteryReceiptFingerprint")
    }
}

data class TutorLearningEvidenceSessionRecord(
    val learnerId: String,
    val evidenceRequestId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val sessionAnchorId: String,
    val evidenceKind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val modeVersion: Long,
    val idempotencyKey: String,
    val candidateFingerprint: String,
    val state: TutorLearningEvidenceSessionRecordState,
    val masteryReceiptId: String?,
    val masteryReceiptFingerprint: String?,
    val stateVersion: Long,
    val intentCreatedAtEpochMillis: Long,
    val acknowledgedAtEpochMillis: Long?,
) {
    init {
        requireTutorSessionOpaque(learnerId, "learnerId")
        requireTutorSessionOpaque(evidenceRequestId, "evidenceRequestId")
        requireTutorSessionOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0)
        require(conversationStateVersion >= 0)
        requireTutorSessionOpaque(turnReceiptId, "turnReceiptId")
        require(turnOrdinal > 0)
        requireTutorSessionOpaque(sessionAnchorId, "sessionAnchorId")
        require(requestVersion >= 0 && modeVersion >= 0)
        requireTutorSessionOpaque(idempotencyKey, "idempotencyKey")
        requireTutorSessionFingerprint(candidateFingerprint, "candidateFingerprint")
        require(stateVersion >= 0)
        require(intentCreatedAtEpochMillis >= 0)
        when (state) {
            TutorLearningEvidenceSessionRecordState.PENDING_MASTERY -> {
                require(masteryReceiptId == null)
                require(masteryReceiptFingerprint == null)
                require(acknowledgedAtEpochMillis == null)
                require(stateVersion == 0L)
            }

            TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED -> {
                requireTutorSessionOpaque(requireNotNull(masteryReceiptId), "masteryReceiptId")
                requireTutorSessionFingerprint(
                    requireNotNull(masteryReceiptFingerprint),
                    "masteryReceiptFingerprint",
                )
                require(requireNotNull(acknowledgedAtEpochMillis) >= intentCreatedAtEpochMillis)
                require(stateVersion == 1L)
            }
        }
    }
}

data class TutorLearningEvidenceSessionAcknowledgeDatabaseResult(
    val replayed: Boolean,
    val record: TutorLearningEvidenceSessionRecord,
)

interface TutorLearningEvidenceSessionDatabasePort {
    suspend fun beginTutorLearningEvidenceSessionIntent(
        command: BeginTutorLearningEvidenceSessionIntentCommand,
    ): TutorLearningEvidenceSessionRecord

    suspend fun acknowledgeTutorLearningEvidenceSession(
        command: AcknowledgeTutorLearningEvidenceSessionCommand,
    ): TutorLearningEvidenceSessionAcknowledgeDatabaseResult
}

class TutorLearningEvidenceSessionConflictException(
    message: String,
) : IllegalStateException(message)

private fun requireTutorSessionFingerprint(
    value: String,
    name: String,
) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$name must be a lowercase SHA-256 fingerprint"
    }
}

private fun requireTutorSessionOpaque(
    value: String,
    name: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) {
        "$name must be a trimmed bounded opaque identifier"
    }
}
