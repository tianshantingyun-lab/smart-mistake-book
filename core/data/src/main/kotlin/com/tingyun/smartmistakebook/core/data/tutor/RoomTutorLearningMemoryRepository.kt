package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.AllocateTutorTurnCommand as DatabaseAllocateTurnCommand
import com.tingyun.smartmistakebook.core.database.ArchiveTutorConversationCommand as DatabaseArchiveConversationCommand
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationCommand as DatabaseCreateConversationCommand
import com.tingyun.smartmistakebook.core.database.FinalizeTutorEvidenceRequestCommand as DatabaseFinalizeEvidenceCommand
import com.tingyun.smartmistakebook.core.database.PrepareTutorEvidenceRequestCommand as DatabasePrepareEvidenceCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorConversationConflictException
import com.tingyun.smartmistakebook.core.database.TutorEvidenceConflictException
import com.tingyun.smartmistakebook.core.database.TutorEvidenceSubmission
import com.tingyun.smartmistakebook.core.database.TutorMemoryScopeConflictException
import com.tingyun.smartmistakebook.core.database.TutorTurnConflictException
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictException
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryOperation
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus

internal class RoomTutorLearningMemoryRepository(
    private val database: StudyDatabasePort,
) : TutorLearningMemoryRepository {
    override suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): CreateTutorConversationResult = mapDatabaseConflict(
        TutorLearningMemoryOperation.CREATE_CONVERSATION,
        TutorLearningMemoryConflictReason.IDEMPOTENCY_PAYLOAD_MISMATCH,
    ) {
        database.createTutorConversation(command.toDatabaseCommand()).let { result ->
            if (result.created) {
                CreateTutorConversationResult.Created(result.conversation)
            } else {
                CreateTutorConversationResult.Replayed(result.conversation)
            }
        }
    }

    override suspend fun openConversation(
        command: OpenTutorConversationCommand,
    ): OpenTutorConversationResult = mapDatabaseConflict(
        TutorLearningMemoryOperation.OPEN_CONVERSATION,
        TutorLearningMemoryConflictReason.NOT_FOUND_OR_OUT_OF_SCOPE,
    ) {
        database.openTutorConversation(
            learnerId = command.learnerScopeId,
            conversationId = command.conversationId,
            conversationGeneration = command.conversationGeneration,
        )?.let(OpenTutorConversationResult::Opened) ?: OpenTutorConversationResult.NotFound
    }

    override suspend fun latestActiveConversation(learnerScopeId: String) =
        requireLearnerScopeId(learnerScopeId).let {
            mapDatabaseConflict(
                TutorLearningMemoryOperation.OPEN_CONVERSATION,
                TutorLearningMemoryConflictReason.NOT_FOUND_OR_OUT_OF_SCOPE,
            ) {
                database.latestActiveTutorConversation(learnerScopeId)
            }
        }

    override suspend fun archiveConversation(
        command: ArchiveTutorConversationCommand,
    ): ArchiveTutorConversationResult = mapDatabaseConflict(
        TutorLearningMemoryOperation.ARCHIVE_CONVERSATION,
        TutorLearningMemoryConflictReason.STATE_VERSION_MISMATCH,
    ) {
        database.archiveTutorConversation(command.toDatabaseCommand()).let { result ->
            if (result.archived) {
                ArchiveTutorConversationResult.Archived(result.conversation)
            } else {
                ArchiveTutorConversationResult.Replayed(result.conversation)
            }
        }
    }

    override suspend fun allocateTurn(
        command: AllocateTutorTurnCommand,
    ): AllocateTutorTurnResult = mapDatabaseConflict(
        TutorLearningMemoryOperation.ALLOCATE_TURN,
        TutorLearningMemoryConflictReason.TURN_ORDINAL_MISMATCH,
    ) {
        database.allocateTutorTurn(command.toDatabaseCommand()).let { result ->
            if (result.created) {
                AllocateTutorTurnResult.Created(result.conversation, result.receipt)
            } else {
                AllocateTutorTurnResult.Replayed(result.conversation, result.receipt)
            }
        }
    }

    override suspend fun prepareEvidenceRequest(
        command: PrepareTutorEvidenceCommand,
    ): PrepareTutorEvidenceResult = mapDatabaseConflict(
        TutorLearningMemoryOperation.PREPARE_EVIDENCE,
        TutorLearningMemoryConflictReason.EVIDENCE_SCOPE_MISMATCH,
    ) {
        database.prepareTutorEvidenceRequest(command.toDatabaseCommand()).let { result ->
            if (result.created) {
                PrepareTutorEvidenceResult.Created(result.request)
            } else {
                PrepareTutorEvidenceResult.Replayed(result.request)
            }
        }
    }

    override suspend fun finalizeEvidence(
        command: FinalizeTutorEvidenceCommand,
    ): FinalizeTutorEvidenceResult = mapDatabaseConflict(
        TutorLearningMemoryOperation.FINALIZE_EVIDENCE,
        TutorLearningMemoryConflictReason.EVIDENCE_NOT_PENDING,
    ) {
        database.finalizeTutorEvidenceRequest(command.toDatabaseCommand()).let { result ->
            if (result.replayed) {
                FinalizeTutorEvidenceResult.Replayed(result.request, result.sourceFact)
            } else if (result.request.status == TutorEvidenceRequestStatus.SUBMITTED) {
                FinalizeTutorEvidenceResult.Submitted(
                    result.request,
                    checkNotNull(result.sourceFact),
                )
            } else if (result.request.status == TutorEvidenceRequestStatus.CANCELLED) {
                FinalizeTutorEvidenceResult.Cancelled(result.request)
            } else {
                throw conflict(
                    TutorLearningMemoryOperation.FINALIZE_EVIDENCE,
                    TutorLearningMemoryConflictReason.TERMINAL_OUTCOME_MISMATCH,
                )
            }
        }
    }

    private suspend fun <T> mapDatabaseConflict(
        operation: TutorLearningMemoryOperation,
        conflictReason: TutorLearningMemoryConflictReason,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (_: TutorMemoryScopeConflictException) {
        throw conflict(operation, TutorLearningMemoryConflictReason.NOT_FOUND_OR_OUT_OF_SCOPE)
    } catch (_: TutorConversationConflictException) {
        throw conflict(operation, conflictReason)
    } catch (_: TutorTurnConflictException) {
        throw conflict(operation, conflictReason)
    } catch (_: TutorEvidenceConflictException) {
        throw conflict(operation, conflictReason)
    }

    private fun conflict(
        operation: TutorLearningMemoryOperation,
        reason: TutorLearningMemoryConflictReason,
    ) = TutorLearningMemoryConflictException(operation, reason)
}

object TutorLearningMemoryRepositoryFactory {
    fun create(database: StudyDatabasePort): TutorLearningMemoryRepository =
        RoomTutorLearningMemoryRepository(database)
}

private fun CreateTutorConversationCommand.toDatabaseCommand() = DatabaseCreateConversationCommand(
    conversationId = conversationId,
    learnerId = learnerScopeId,
    generation = conversationGeneration,
    idempotencyKey = clientIdempotencyKey,
    payloadFingerprint = payloadFingerprint,
)

private fun ArchiveTutorConversationCommand.toDatabaseCommand() = DatabaseArchiveConversationCommand(
    learnerId = learnerScopeId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    expectedStateVersion = expectedConversationStateVersion,
    idempotencyKey = clientIdempotencyKey,
    payloadFingerprint = payloadFingerprint,
)

private fun AllocateTutorTurnCommand.toDatabaseCommand() = DatabaseAllocateTurnCommand(
    turnReceiptId = turnReceiptId,
    learnerId = learnerScopeId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    expectedConversationStateVersion = expectedConversationStateVersion,
    expectedTurnOrdinal = expectedTurnOrdinal,
    clientTurnId = clientIdempotencyKey,
    payloadFingerprint = payloadFingerprint,
    subject = subject,
    problemAnchorId = problemAnchorId,
    requestVersion = requestVersion,
    explanationMode = mode,
    modeVersion = modeVersion,
    directiveFingerprint = directiveFingerprint,
    studentMessageFingerprint = studentMessageFingerprint,
    studentMessageSummary = studentMessageSummary,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun PrepareTutorEvidenceCommand.toDatabaseCommand() = DatabasePrepareEvidenceCommand(
    evidenceRequestId = evidenceRequestId,
    learnerId = learnerScopeId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = expectedConversationStateVersion,
    turnReceiptId = turnReceiptId,
    turnOrdinal = turnOrdinal,
    subject = subject,
    problemAnchorId = problemAnchorId,
    kind = kind,
    requestVersion = requestVersion,
    explanationMode = mode,
    modeVersion = modeVersion,
    directiveFingerprint = directiveFingerprint,
    idempotencyKey = clientIdempotencyKey,
    payloadFingerprint = payloadFingerprint,
)

private fun FinalizeTutorEvidenceCommand.toDatabaseCommand() = DatabaseFinalizeEvidenceCommand(
    learnerId = learnerScopeId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnReceiptId = turnReceiptId,
    turnOrdinal = turnOrdinal,
    subject = subject,
    problemAnchorId = problemAnchorId,
    evidenceRequestId = evidenceRequestId,
    expectedEvidenceStateVersion = expectedEvidenceStateVersion,
    kind = kind,
    requestVersion = requestVersion,
    explanationMode = mode,
    modeVersion = modeVersion,
    directiveFingerprint = directiveFingerprint,
    terminalStatus = when (terminal) {
        is TutorLearningEvidenceTerminal.Submitted -> TutorEvidenceRequestStatus.SUBMITTED
        is TutorLearningEvidenceTerminal.Cancelled -> TutorEvidenceRequestStatus.CANCELLED
    },
    idempotencyKey = clientIdempotencyKey,
    payloadFingerprint = payloadFingerprint,
    submission = (terminal as? TutorLearningEvidenceTerminal.Submitted)?.toDatabaseSubmission(),
)

private fun TutorLearningEvidenceTerminal.Submitted.toDatabaseSubmission() = TutorEvidenceSubmission(
    sourceFactId = sourceFact.sourceFactId,
    source = sourceFact.source,
    factKind = sourceFact.factKind,
    questionFingerprint = anchors.questionFingerprint,
    revisionFingerprint = anchors.problemRevisionFingerprint,
    fingerprintVersion = anchors.fingerprintVersion,
    responseFingerprint = sourceFact.responseFingerprint,
    responseSummary = sourceFact.responseSummary,
    occurredAtEpochMillis = sourceFact.occurredAtEpochMillis,
    sourceVersion = sourceFact.sourceVersion,
)

private fun requireLearnerScopeId(learnerScopeId: String) {
    require(
        learnerScopeId.isNotBlank() &&
            learnerScopeId == learnerScopeId.trim() &&
            learnerScopeId.length <= 256 &&
            learnerScopeId.none(Char::isISOControl),
    ) { "Learner scope must be a trimmed non-blank opaque id" }
}
