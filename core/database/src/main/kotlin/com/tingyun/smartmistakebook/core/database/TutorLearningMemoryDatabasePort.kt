package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.LearningProblemAnchor
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt

class TutorConversationConflictException(conversationId: String) :
    IllegalStateException("Tutor conversation $conversationId has conflicting durable state")

class TutorTurnConflictException(clientTurnId: String) :
    IllegalStateException("Tutor turn $clientTurnId has conflicting durable state")

class TutorEvidenceConflictException(evidenceRequestId: String) :
    IllegalStateException("Tutor evidence request $evidenceRequestId has conflicting durable state")

class TutorMemoryScopeConflictException(message: String) : IllegalStateException(message)

data class CreateTutorConversationCommand(
    val conversationId: String,
    val learnerId: String,
    val generation: Long = 1,
    val idempotencyKey: String,
    val payloadFingerprint: String,
) {
    init {
        requireOpaque(conversationId, "conversationId")
        requireOpaque(learnerId, "learnerId")
        require(generation > 0)
        requireOpaque(idempotencyKey, "idempotencyKey")
        requireFingerprint(payloadFingerprint, "payloadFingerprint")
    }
}

data class TutorConversationWriteResult(
    val created: Boolean,
    val conversation: TutorConversation,
)

data class TutorConversationArchiveWriteResult(
    val archived: Boolean,
    val conversation: TutorConversation,
)

data class ArchiveTutorConversationCommand(
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val expectedStateVersion: Long,
    val idempotencyKey: String,
    val payloadFingerprint: String,
) {
    init {
        requireOpaque(learnerId, "learnerId")
        requireOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0 && expectedStateVersion >= 0)
        requireOpaque(idempotencyKey, "idempotencyKey")
        requireFingerprint(payloadFingerprint, "payloadFingerprint")
    }
}

data class AllocateTutorTurnCommand(
    val turnReceiptId: String,
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val expectedConversationStateVersion: Long,
    val expectedTurnOrdinal: Int,
    val clientTurnId: String,
    val payloadFingerprint: String,
    val subject: SubjectKind,
    val problemAnchorId: String?,
    val requestVersion: Long,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val directiveFingerprint: String,
    val studentMessageFingerprint: String,
    val studentMessageSummary: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        requireOpaque(turnReceiptId, "turnReceiptId")
        requireOpaque(learnerId, "learnerId")
        requireOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0 && expectedConversationStateVersion >= 0)
        require(expectedTurnOrdinal > 0)
        requireOpaque(clientTurnId, "clientTurnId")
        requireFingerprint(payloadFingerprint, "payloadFingerprint")
        require(problemAnchorId == null || subject != SubjectKind.GENERAL)
        problemAnchorId?.let { requireOpaque(it, "problemAnchorId") }
        require(requestVersion >= 0 && modeVersion >= 0)
        requireFingerprint(directiveFingerprint, "directiveFingerprint")
        requireFingerprint(studentMessageFingerprint, "studentMessageFingerprint")
        requireBoundedSummary(studentMessageSummary, "studentMessageSummary")
        require(occurredAtEpochMillis >= 0)
    }
}

data class TutorTurnAllocationResult(
    val created: Boolean,
    val conversation: TutorConversation,
    val receipt: TutorTurnReceipt,
)

sealed interface TutorTurnReadResult {
    data class Found(val receipt: TutorTurnReceipt) : TutorTurnReadResult

    data object NotFound : TutorTurnReadResult
}

data class PrepareTutorEvidenceRequestCommand(
    val evidenceRequestId: String,
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val turnReceiptId: String,
    val turnOrdinal: Int,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val kind: TutorEvidenceRequestKind,
    val requestVersion: Long,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val directiveFingerprint: String,
    val idempotencyKey: String,
    val payloadFingerprint: String,
) {
    init {
        requireOpaque(evidenceRequestId, "evidenceRequestId")
        requireOpaque(learnerId, "learnerId")
        requireOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0 && conversationStateVersion >= 0)
        requireOpaque(turnReceiptId, "turnReceiptId")
        require(turnOrdinal > 0 && subject != SubjectKind.GENERAL)
        requireOpaque(problemAnchorId, "problemAnchorId")
        require(requestVersion >= 0 && modeVersion >= 0)
        require(explanationMode == TutorExplanationMode.GUIDED)
        requireFingerprint(directiveFingerprint, "directiveFingerprint")
        requireOpaque(idempotencyKey, "idempotencyKey")
        requireFingerprint(payloadFingerprint, "payloadFingerprint")
    }
}

data class TutorEvidencePreparationResult(
    val created: Boolean,
    val request: TutorEvidenceRequest,
)

data class TutorEvidenceSubmission(
    val sourceFactId: String,
    val source: LearningObservationSource,
    val factKind: LearningObservationFactKind,
    val questionFingerprint: String,
    val revisionFingerprint: String,
    val fingerprintVersion: String,
    val responseFingerprint: String,
    val responseSummary: String,
    val occurredAtEpochMillis: Long,
    val sourceVersion: String,
) {
    init {
        requireOpaque(sourceFactId, "sourceFactId")
        require(source in LearningObservationSourceFact.tutorSources)
        requireFingerprint(questionFingerprint, "questionFingerprint")
        requireFingerprint(revisionFingerprint, "revisionFingerprint")
        requireOpaque(fingerprintVersion, "fingerprintVersion")
        requireFingerprint(responseFingerprint, "responseFingerprint")
        requireBoundedSummary(responseSummary, "responseSummary")
        require(occurredAtEpochMillis >= 0)
        requireOpaque(sourceVersion, "sourceVersion")
        LearningObservationSourceFact(
            sourceFactId = sourceFactId,
            learnerScopeId = "validation-only",
            source = source,
            factKind = factKind,
            anchorId = "validation-only",
            subject = SubjectKind.MATH,
            conversationId = "validation-only",
            conversationGeneration = 1,
            turnReceiptId = "validation-only",
            evidenceRequestId = "validation-only",
            responseFingerprint = responseFingerprint,
            responseSummary = responseSummary,
            occurredAtEpochMillis = occurredAtEpochMillis,
            sourceVersion = sourceVersion,
        )
    }
}

data class FinalizeTutorEvidenceRequestCommand(
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
    val terminalStatus: TutorEvidenceRequestStatus,
    val idempotencyKey: String,
    val payloadFingerprint: String,
    val submission: TutorEvidenceSubmission?,
) {
    init {
        requireOpaque(learnerId, "learnerId")
        requireOpaque(conversationId, "conversationId")
        require(conversationGeneration > 0 && conversationStateVersion >= 0)
        requireOpaque(turnReceiptId, "turnReceiptId")
        require(turnOrdinal > 0 && subject != SubjectKind.GENERAL)
        requireOpaque(problemAnchorId, "problemAnchorId")
        requireOpaque(evidenceRequestId, "evidenceRequestId")
        require(expectedEvidenceStateVersion >= 0 && requestVersion >= 0 && modeVersion >= 0)
        require(explanationMode == TutorExplanationMode.GUIDED)
        requireFingerprint(directiveFingerprint, "directiveFingerprint")
        require(terminalStatus.isTerminal)
        requireOpaque(idempotencyKey, "idempotencyKey")
        requireFingerprint(payloadFingerprint, "payloadFingerprint")
        require((terminalStatus == TutorEvidenceRequestStatus.SUBMITTED) == (submission != null))
    }
}

data class TutorEvidenceFinalizationResult(
    val replayed: Boolean,
    val request: TutorEvidenceRequest,
    val anchor: LearningProblemAnchor?,
    val sourceFact: LearningObservationSourceFact?,
)

interface TutorLearningMemoryDatabasePort {
    suspend fun createTutorConversation(
        command: CreateTutorConversationCommand,
    ): TutorConversationWriteResult = throw UnsupportedOperationException(
        "Tutor learning-memory conversations are not implemented",
    )

    suspend fun openTutorConversation(
        learnerId: String,
        conversationId: String,
        conversationGeneration: Long,
    ): TutorConversation? = throw UnsupportedOperationException(
        "Tutor learning-memory conversations are not implemented",
    )

    suspend fun latestActiveTutorConversation(
        learnerId: String,
    ): TutorConversation? = throw UnsupportedOperationException(
        "Tutor learning-memory conversations are not implemented",
    )

    suspend fun archiveTutorConversation(
        command: ArchiveTutorConversationCommand,
    ): TutorConversationArchiveWriteResult = throw UnsupportedOperationException(
        "Tutor learning-memory conversations are not implemented",
    )

    suspend fun allocateTutorTurn(
        command: AllocateTutorTurnCommand,
    ): TutorTurnAllocationResult = throw UnsupportedOperationException(
        "Tutor learning-memory turns are not implemented",
    )

    suspend fun openTutorTurn(
        learnerId: String,
        turnReceiptId: String,
    ): TutorTurnReadResult = throw UnsupportedOperationException(
        "Tutor learning-memory turns are not implemented",
    )

    suspend fun prepareTutorEvidenceRequest(
        command: PrepareTutorEvidenceRequestCommand,
    ): TutorEvidencePreparationResult = throw UnsupportedOperationException(
        "Tutor learning-memory evidence is not implemented",
    )

    suspend fun finalizeTutorEvidenceRequest(
        command: FinalizeTutorEvidenceRequestCommand,
    ): TutorEvidenceFinalizationResult = throw UnsupportedOperationException(
        "Tutor learning-memory evidence is not implemented",
    )
}

private fun requireOpaque(value: String, name: String) {
    require(value.isNotBlank() && value == value.trim() && value.length <= 256) {
        "$name must be a bounded opaque identifier"
    }
}

private fun requireFingerprint(value: String, name: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$name must be a lowercase SHA-256 fingerprint"
    }
}

private fun requireBoundedSummary(value: String, name: String) {
    require(value.isNotBlank() && value == value.trim() && value.length <= 512) {
        "$name must be a trimmed summary of at most 512 characters"
    }
}
