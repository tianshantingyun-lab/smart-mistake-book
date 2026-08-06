package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.database.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.database.CancelTutorEvidenceRequestCommand
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.database.PrepareTutorEvidenceRequestCommand
import com.tingyun.smartmistakebook.core.database.TutorConversationSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorTurnReadResult
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt

internal class LegacyTutorConversationSessionAdapter(
    private val boundScope: SessionScope,
    private val legacy: TutorConversationSessionDatabasePort,
) : TutorConversationSessionPort {
    override suspend fun read(
        query: TutorConversationSessionReadQuery,
    ): TutorConversationSessionReadResult {
        query.scope.requireBoundTo(boundScope)
        return when (query) {
            is TutorConversationSessionReadQuery.Conversation -> {
                query.conversationId.requireSessionIdentifier("Tutor conversation id")
                require(query.generation > 0) {
                    "Tutor conversation generation must be positive"
                }
                TutorConversationSessionReadResult.Conversation(
                    legacy.openTutorConversation(
                        learnerId = boundScope.learnerId,
                        conversationId = query.conversationId,
                        conversationGeneration = query.generation,
                    )?.toSession(boundScope),
                )
            }

            is TutorConversationSessionReadQuery.LatestActive ->
                TutorConversationSessionReadResult.Conversation(
                    legacy.latestActiveTutorConversation(boundScope.learnerId)
                        ?.toSession(boundScope),
                )

            is TutorConversationSessionReadQuery.LatestActiveInNamespace -> {
                query.conversationIdPrefix.requireSessionIdentifier(
                    "Tutor conversation namespace",
                )
                TutorConversationSessionReadResult.Conversation(
                    legacy.latestActiveTutorConversationInNamespace(
                        learnerId = boundScope.learnerId,
                        conversationIdPrefix = query.conversationIdPrefix,
                    )?.toSession(boundScope),
                )
            }

            is TutorConversationSessionReadQuery.Turn -> {
                query.turnReceiptId.requireSessionIdentifier("Tutor turn receipt id")
                val receipt =
                    when (
                        val result =
                            legacy.openTutorTurn(
                                learnerId = boundScope.learnerId,
                                turnReceiptId = query.turnReceiptId,
                            )
                    ) {
                        is TutorTurnReadResult.Found -> result.receipt.toSession(boundScope)
                        TutorTurnReadResult.NotFound -> null
                    }
                TutorConversationSessionReadResult.Turn(receipt)
            }

            is TutorConversationSessionReadQuery.Evidence -> {
                query.evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
                TutorConversationSessionReadResult.Evidence(
                    legacy.openTutorEvidenceRequest(
                        learnerId = boundScope.learnerId,
                        evidenceRequestId = query.evidenceRequestId,
                    )?.toSession(boundScope),
                )
            }
        }
    }

    override suspend fun mutate(
        command: TutorConversationSessionMutation,
    ): TutorConversationSessionMutationResult {
        command.scope.requireBoundTo(boundScope)
        return when (command) {
            is TutorConversationSessionMutation.CreateConversation ->
                createConversation(command)
            is TutorConversationSessionMutation.ArchiveConversation ->
                archiveConversation(command)
            is TutorConversationSessionMutation.AllocateTurn ->
                allocateTurn(command)
            is TutorConversationSessionMutation.PrepareEvidence ->
                prepareEvidence(command)
            is TutorConversationSessionMutation.FinalizeEvidence ->
                finalizeEvidence(command)
        }
    }

    private suspend fun createConversation(
        command: TutorConversationSessionMutation.CreateConversation,
    ): TutorConversationSessionMutationResult {
        command.conversationId.requireSessionIdentifier("Tutor conversation id")
        require(command.generation > 0) { "Tutor conversation generation must be positive" }
        val result =
            legacy.createTutorConversation(
                CreateTutorConversationCommand(
                    conversationId = command.conversationId,
                    learnerId = boundScope.learnerId,
                    generation = command.generation,
                    idempotencyKey = command.operation.idempotencyKey,
                    payloadFingerprint = command.operation.payloadFingerprint,
                ),
            )
        val snapshot = result.conversation.toSession(boundScope)
        return TutorConversationSessionMutationResult.Conversation(
            receipt =
                receipt(
                    command.operation,
                    if (result.created) {
                        SessionMutationDisposition.APPLIED
                    } else {
                        SessionMutationDisposition.DUPLICATE
                    },
                    snapshot.version,
                    result.conversation.createdAtEpochMillis,
                ),
            snapshot = snapshot,
        )
    }

    private suspend fun archiveConversation(
        command: TutorConversationSessionMutation.ArchiveConversation,
    ): TutorConversationSessionMutationResult {
        command.conversationId.requireSessionIdentifier("Tutor conversation id")
        require(command.generation > 0) { "Tutor conversation generation must be positive" }
        val current =
            legacy.openTutorConversation(
                boundScope.learnerId,
                command.conversationId,
                command.generation,
            )?.toSession(boundScope)
                ?: return missingConversationResult(command.operation)
        if (current.version != command.expectedVersion) {
            return TutorConversationSessionMutationResult.Conversation(
                receipt =
                    receipt(
                        command.operation,
                        SessionMutationDisposition.RELOAD_REQUIRED,
                        current.version,
                        current.archivedAtEpochMillis ?: current.createdAtEpochMillis,
                    ),
                snapshot = current,
            )
        }
        val result =
            legacy.archiveTutorConversation(
                ArchiveTutorConversationCommand(
                    learnerId = boundScope.learnerId,
                    conversationId = command.conversationId,
                    conversationGeneration = command.generation,
                    expectedStateVersion = current.version.sequence,
                    idempotencyKey = command.operation.idempotencyKey,
                    payloadFingerprint = command.operation.payloadFingerprint,
                ),
            )
        val snapshot = result.conversation.toSession(boundScope)
        return TutorConversationSessionMutationResult.Conversation(
            receipt =
                receipt(
                    command.operation,
                    if (result.archived) {
                        SessionMutationDisposition.APPLIED
                    } else {
                        SessionMutationDisposition.DUPLICATE
                    },
                    snapshot.version,
                    snapshot.archivedAtEpochMillis ?: snapshot.createdAtEpochMillis,
                ),
            snapshot = snapshot,
        )
    }

    private suspend fun allocateTurn(
        command: TutorConversationSessionMutation.AllocateTurn,
    ): TutorConversationSessionMutationResult {
        command.requireValid()
        val current =
            legacy.openTutorConversation(
                boundScope.learnerId,
                command.conversationId,
                command.conversationGeneration,
            )?.toSession(boundScope)
                ?: return missingTurnResult(command.operation, command.occurredAtEpochMillis)
        if (current.version != command.expectedConversationVersion) {
            return TutorConversationSessionMutationResult.Turn(
                receipt =
                    receipt(
                        command.operation,
                        SessionMutationDisposition.RELOAD_REQUIRED,
                        current.version,
                        command.occurredAtEpochMillis,
                    ),
                conversation = current,
                turn = null,
            )
        }
        val result =
            legacy.allocateTutorTurn(
                AllocateTutorTurnCommand(
                    turnReceiptId = command.turnReceiptId,
                    learnerId = boundScope.learnerId,
                    conversationId = command.conversationId,
                    conversationGeneration = command.conversationGeneration,
                    expectedConversationStateVersion = current.version.sequence,
                    expectedTurnOrdinal = command.expectedTurnOrdinal,
                    clientTurnId = command.clientTurnId,
                    payloadFingerprint = command.operation.payloadFingerprint,
                    subject = command.subject,
                    problemAnchorId = command.sessionAnchorId,
                    requestVersion = command.operation.requestVersion,
                    explanationMode = command.explanationMode,
                    modeVersion = command.modeVersion,
                    directiveFingerprint = command.directiveFingerprint,
                    studentMessageFingerprint = command.studentMessageFingerprint,
                    studentMessageSummary = command.studentMessageSummary,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            )
        val conversation = result.conversation.toSession(boundScope)
        return TutorConversationSessionMutationResult.Turn(
            receipt =
                receipt(
                    command.operation,
                    if (result.created) {
                        SessionMutationDisposition.APPLIED
                    } else {
                        SessionMutationDisposition.DUPLICATE
                    },
                    conversation.version,
                    command.occurredAtEpochMillis,
                ),
            conversation = conversation,
            turn = result.receipt.toSession(boundScope),
        )
    }

    private suspend fun prepareEvidence(
        command: TutorConversationSessionMutation.PrepareEvidence,
    ): TutorConversationSessionMutationResult {
        command.requireValid()
        val result =
            legacy.prepareTutorEvidenceRequest(
                PrepareTutorEvidenceRequestCommand(
                    evidenceRequestId = command.evidenceRequestId,
                    learnerId = boundScope.learnerId,
                    conversationId = command.conversationId,
                    conversationGeneration = command.conversationGeneration,
                    conversationStateVersion = command.conversationVersion.sequence,
                    turnReceiptId = command.turnReceiptId,
                    turnOrdinal = command.turnOrdinal,
                    subject = command.subject,
                    problemAnchorId = command.sessionAnchorId,
                    kind = command.kind,
                    requestVersion = command.operation.requestVersion,
                    explanationMode = command.explanationMode,
                    modeVersion = command.modeVersion,
                    directiveFingerprint = command.directiveFingerprint,
                    idempotencyKey = command.operation.idempotencyKey,
                    payloadFingerprint = command.operation.payloadFingerprint,
                ),
            )
        val snapshot = result.request.toSession(boundScope)
        return TutorConversationSessionMutationResult.Evidence(
            receipt =
                receipt(
                    command.operation,
                    if (result.created) {
                        SessionMutationDisposition.APPLIED
                    } else {
                        SessionMutationDisposition.DUPLICATE
                    },
                    snapshot.version,
                    snapshot.createdAtEpochMillis,
                ),
            snapshot = snapshot,
        )
    }

    private suspend fun finalizeEvidence(
        command: TutorConversationSessionMutation.FinalizeEvidence,
    ): TutorConversationSessionMutationResult {
        command.requireValid()
        check(command.terminalStatus == TutorEvidenceRequestStatus.CANCELLED) {
            "Legacy tutor learning-fact submission is disabled"
        }
        val result =
            legacy.cancelTutorEvidenceRequest(
                CancelTutorEvidenceRequestCommand(
                    learnerId = boundScope.learnerId,
                    conversationId = command.conversationId,
                    conversationGeneration = command.conversationGeneration,
                    conversationStateVersion = command.conversationVersion.sequence,
                    turnReceiptId = command.turnReceiptId,
                    turnOrdinal = command.turnOrdinal,
                    subject = command.subject,
                    problemAnchorId = command.sessionAnchorId,
                    evidenceRequestId = command.evidenceRequestId,
                    expectedEvidenceStateVersion = command.expectedEvidenceVersion.sequence,
                    kind = command.kind,
                    requestVersion = command.operation.requestVersion,
                    explanationMode = command.explanationMode,
                    modeVersion = command.modeVersion,
                    directiveFingerprint = command.directiveFingerprint,
                    idempotencyKey = command.operation.idempotencyKey,
                    payloadFingerprint = command.operation.payloadFingerprint,
                ),
            )
        val snapshot = result.request.toSession(boundScope)
        return TutorConversationSessionMutationResult.Evidence(
            receipt =
                receipt(
                    command.operation,
                    if (result.replayed) {
                        SessionMutationDisposition.DUPLICATE
                    } else {
                        SessionMutationDisposition.APPLIED
                    },
                    snapshot.version,
                    snapshot.resolvedAtEpochMillis ?: snapshot.createdAtEpochMillis,
            ),
            snapshot = snapshot,
        )
    }
}

private fun TutorConversationSessionMutation.AllocateTurn.requireValid() {
    conversationId.requireSessionIdentifier("Tutor conversation id")
    require(conversationGeneration > 0) { "Tutor conversation generation must be positive" }
    require(expectedTurnOrdinal > 0) { "Expected tutor turn ordinal must be positive" }
    turnReceiptId.requireSessionIdentifier("Tutor turn receipt id")
    clientTurnId.requireSessionIdentifier("Tutor client turn id")
    sessionAnchorId?.requireSessionIdentifier("Tutor session anchor id")
    require(modeVersion >= 0) { "Tutor mode version must not be negative" }
    directiveFingerprint.requireSessionFingerprint("Tutor directive fingerprint")
    studentMessageFingerprint.requireSessionFingerprint("Tutor message fingerprint")
    require(
        studentMessageSummary.isNotBlank() &&
            studentMessageSummary.length <= MAX_TUTOR_SESSION_SUMMARY_CHARS,
    ) { "Tutor message summary is outside its session budget" }
    require(occurredAtEpochMillis >= 0) { "Tutor turn time must not be negative" }
}

private fun TutorConversationSessionMutation.PrepareEvidence.requireValid() {
    evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
    conversationId.requireSessionIdentifier("Tutor conversation id")
    require(conversationGeneration > 0 && turnOrdinal > 0) {
        "Tutor conversation generation and turn ordinal must be positive"
    }
    turnReceiptId.requireSessionIdentifier("Tutor turn receipt id")
    sessionAnchorId.requireSessionIdentifier("Tutor evidence session anchor id")
    require(modeVersion >= 0) { "Tutor mode version must not be negative" }
    directiveFingerprint.requireSessionFingerprint("Tutor directive fingerprint")
}

private fun TutorConversationSessionMutation.FinalizeEvidence.requireValid() {
    evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
    conversationId.requireSessionIdentifier("Tutor conversation id")
    require(conversationGeneration > 0 && turnOrdinal > 0) {
        "Tutor conversation generation and turn ordinal must be positive"
    }
    turnReceiptId.requireSessionIdentifier("Tutor turn receipt id")
    sessionAnchorId.requireSessionIdentifier("Tutor evidence session anchor id")
    require(modeVersion >= 0) { "Tutor mode version must not be negative" }
    directiveFingerprint.requireSessionFingerprint("Tutor directive fingerprint")
    require(terminalStatus.isTerminal) { "Tutor evidence finalization must be terminal" }
    require(terminalStatus == TutorEvidenceRequestStatus.CANCELLED) {
        "Legacy tutor session finalization supports cancellation only"
    }
}

private fun TutorConversation.toSession(scope: SessionScope): TutorConversationSessionSnapshot {
    require(learnerScopeId == scope.learnerId) {
        "Legacy tutor conversation belongs to another learner"
    }
    val fingerprint =
        CanonicalSha256("tutor-conversation-session-state-v1")
            .field("conversationId", conversationId)
            .field("learnerId", learnerScopeId)
            .field("generation", generation)
            .field("status", status.name)
            .field("stateVersion", stateVersion)
            .field("createdAtEpochMillis", createdAtEpochMillis)
            .nullableField("archivedAtEpochMillis", archivedAtEpochMillis?.toString())
            .finish()
    return TutorConversationSessionSnapshot(
        scope = scope,
        conversationId = conversationId,
        generation = generation,
        status = status,
        version = SessionVersion(stateVersion, fingerprint),
        createdAtEpochMillis = createdAtEpochMillis,
        archivedAtEpochMillis = archivedAtEpochMillis,
    )
}

private fun TutorTurnReceipt.toSession(scope: SessionScope): TutorTurnSessionReceipt {
    val fingerprint =
        CanonicalSha256("tutor-turn-session-receipt-v1")
            .field("turnReceiptId", turnReceiptId)
            .field("conversationId", conversationId)
            .field("conversationGeneration", conversationGeneration)
            .field("conversationStateVersion", conversationStateVersion)
            .field("turnOrdinal", turnOrdinal)
            .field("requestVersion", requestVersion)
            .field("modeVersion", modeVersion)
            .field("directiveFingerprint", directiveFingerprint)
            .field("studentMessageFingerprint", studentMessageFingerprint)
            .finish()
    return TutorTurnSessionReceipt(
        scope = scope,
        turnReceiptId = turnReceiptId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationVersion = SessionVersion(conversationStateVersion, fingerprint),
        turnOrdinal = turnOrdinal,
        subject = subject,
        sessionAnchorId = problemAnchorId,
        requestVersion = requestVersion,
        modeVersion = modeVersion,
        explanationMode = explanationMode,
        directiveFingerprint = directiveFingerprint,
        studentMessageFingerprint = studentMessageFingerprint,
        studentMessageSummary = studentMessageSummary,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )
}

private fun TutorEvidenceRequest.toSession(scope: SessionScope): TutorEvidenceSessionSnapshot {
    val conversationFingerprint =
        CanonicalSha256("tutor-evidence-conversation-head-v1")
            .field("conversationId", conversationId)
            .field("generation", conversationGeneration)
            .field("stateVersion", conversationStateVersion)
            .finish()
    val evidenceFingerprint =
        CanonicalSha256("tutor-evidence-session-state-v1")
            .field("evidenceRequestId", evidenceRequestId)
            .field("conversationId", conversationId)
            .field("conversationGeneration", conversationGeneration)
            .field("turnReceiptId", turnReceiptId)
            .field("status", status.name)
            .field("stateVersion", stateVersion)
            .nullableField("terminalReceiptId", terminalReceiptId)
            .finish()
    return TutorEvidenceSessionSnapshot(
        scope = scope,
        evidenceRequestId = evidenceRequestId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationVersion =
            SessionVersion(conversationStateVersion, conversationFingerprint),
        turnReceiptId = turnReceiptId,
        turnOrdinal = turnOrdinal,
        subject = subject,
        sessionAnchorId = problemAnchorId,
        kind = kind,
        requestVersion = requestVersion,
        modeVersion = modeVersion,
        directiveFingerprint = directiveFingerprint,
        status = status,
        version = SessionVersion(stateVersion, evidenceFingerprint),
        createdAtEpochMillis = createdAtEpochMillis,
        resolvedAtEpochMillis = resolvedAtEpochMillis,
        terminalReceiptId = terminalReceiptId,
    )
}

private fun receipt(
    operation: SessionOperationIdentity,
    disposition: SessionMutationDisposition,
    version: SessionVersion?,
    occurredAtEpochMillis: Long,
) = SessionMutationReceipt(
    operation = operation,
    disposition = disposition,
    currentVersion = version,
    recordedAtEpochMillis = occurredAtEpochMillis,
)

private fun missingConversationResult(
    operation: SessionOperationIdentity,
) = TutorConversationSessionMutationResult.Conversation(
    receipt =
        receipt(
            operation,
            SessionMutationDisposition.NOT_FOUND,
            null,
            occurredAtEpochMillis = 0,
        ),
    snapshot = null,
)

private fun missingTurnResult(
    operation: SessionOperationIdentity,
    occurredAtEpochMillis: Long,
) = TutorConversationSessionMutationResult.Turn(
    receipt =
        receipt(
            operation,
            SessionMutationDisposition.NOT_FOUND,
            null,
            occurredAtEpochMillis,
        ),
    conversation = null,
    turn = null,
)
