package com.tingyun.smartmistakebook.core.data.tutor

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
import com.tingyun.smartmistakebook.core.domain.OpenTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceReceipt
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.mastery.database.RecordTrustedLearningObservationCommand
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationResult
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof

internal fun interface TutorMasteryObservationSink {
    suspend fun record(
        command: RecordTrustedLearningObservationCommand,
    ): TrustedLearningObservationResult
}

/**
 * Resolves proofs from the locally held current-session/saved binding. A model or domain caller
 * never receives this capability and cannot supply proofs through the submission payload.
 */
internal fun interface TutorLearningEvidenceCurrentSessionProofSource {
    suspend fun resolve(
        candidate: TutorLearningEvidenceCandidate,
    ): List<VerifiedKnowledgeReferenceProof>?
}

/**
 * Keeps conversation/session coordination in the legacy store while committing submitted
 * semantic evidence through the learner-mastery owner.
 *
 * A submitted finalization never invokes the legacy repository finalizer. The durable session
 * capability is mandatory, so no construction path can fall back to legacy facts.
 */
internal class LearnerMasteryTutorLearningMemoryRepository(
    private val delegate: TutorLearningMemoryRepository,
    private val boundLearnerId: String,
    observationSink: TutorMasteryObservationSink,
    evidenceSession: TutorLearningEvidenceSessionPort,
    currentSessionProofSource: TutorLearningEvidenceCurrentSessionProofSource,
    knowledgeEvidenceAuthorizer: TutorKnowledgeEvidenceAuthorizer,
    private val productionOwnerIsCurrent: () -> Boolean,
) : TutorLearningMemoryRepository by delegate {
    private val evidenceCommitCoordinator =
        TutorLearningEvidenceCommitCoordinator(
            session = evidenceSession,
            candidateAuthorizer =
                TutorLearningEvidenceCandidateAuthorizer { candidate ->
                    val proofs =
                        checkNotNull(currentSessionProofSource.resolve(candidate)) {
                            "Tutor learning evidence has no authorized knowledge binding"
                        }
                    AuthorizedTutorLearningEvidenceCandidate(
                        candidate = candidate,
                        knowledgeReferenceProofs = proofs,
                    )
                },
            mastery =
                LearnerMasteryTutorLearningEvidenceOwner(
                    sink = observationSink,
                    knowledgeEvidenceAuthorizer = knowledgeEvidenceAuthorizer,
                ),
        )

    override suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): CreateTutorConversationResult {
        requireBoundLearner(command.learnerScopeId)
        return delegate.createConversation(command)
    }

    override suspend fun openConversation(
        command: OpenTutorConversationCommand,
    ): OpenTutorConversationResult {
        requireBoundLearner(command.learnerScopeId)
        return delegate.openConversation(command)
    }

    override suspend fun latestActiveConversation(
        learnerScopeId: String,
    ): TutorConversation? {
        requireBoundLearner(learnerScopeId)
        return delegate.latestActiveConversation(learnerScopeId)
    }

    override suspend fun latestActiveConversationInNamespace(
        learnerScopeId: String,
        conversationIdPrefix: String,
    ): TutorConversation? {
        requireBoundLearner(learnerScopeId)
        return delegate.latestActiveConversationInNamespace(
            learnerScopeId = learnerScopeId,
            conversationIdPrefix = conversationIdPrefix,
        )
    }

    override suspend fun openTurn(
        learnerScopeId: String,
        turnReceiptId: String,
    ): OpenTutorTurnResult {
        requireBoundLearner(learnerScopeId)
        return delegate.openTurn(learnerScopeId, turnReceiptId)
    }

    override suspend fun openEvidenceRequest(
        learnerScopeId: String,
        evidenceRequestId: String,
    ): OpenTutorEvidenceResult {
        requireBoundLearner(learnerScopeId)
        return delegate.openEvidenceRequest(learnerScopeId, evidenceRequestId)
    }

    override suspend fun archiveConversation(
        command: ArchiveTutorConversationCommand,
    ): ArchiveTutorConversationResult {
        requireBoundLearner(command.learnerScopeId)
        return delegate.archiveConversation(command)
    }

    override suspend fun allocateTurn(
        command: AllocateTutorTurnCommand,
    ): AllocateTutorTurnResult {
        requireBoundLearner(command.learnerScopeId)
        return delegate.allocateTurn(command)
    }

    override suspend fun prepareEvidenceRequest(
        command: PrepareTutorEvidenceCommand,
    ): PrepareTutorEvidenceResult {
        requireBoundLearner(command.learnerScopeId)
        return delegate.prepareEvidenceRequest(command)
    }

    override suspend fun finalizeEvidence(
        command: FinalizeTutorEvidenceCommand,
    ): FinalizeTutorEvidenceResult {
        requireBoundLearner(command.learnerScopeId)
        if (command.terminal is TutorLearningEvidenceTerminal.Cancelled) {
            // Cancellation is session coordination only and cannot create a learning fact.
            return delegate.finalizeEvidence(command)
        }
        val request =
            when (
                val opened =
                    delegate.openEvidenceRequest(
                        learnerScopeId = command.learnerScopeId,
                        evidenceRequestId = command.evidenceRequestId,
                    )
            ) {
                is OpenTutorEvidenceResult.Found -> opened.request
                OpenTutorEvidenceResult.NotFound ->
                    error("Tutor evidence request is outside the bound session scope")
            }
        val expectedAcknowledgedStateVersion =
            when (request.status) {
                TutorEvidenceRequestStatus.PENDING -> {
                    check(request.stateVersion == command.expectedEvidenceStateVersion) {
                        "Tutor evidence request version changed before mastery commit"
                    }
                    request.stateVersion + 1
                }

                TutorEvidenceRequestStatus.SUBMITTED -> {
                    check(request.stateVersion == command.expectedEvidenceStateVersion + 1) {
                        "Tutor evidence request version changed after mastery commit"
                    }
                    request.stateVersion
                }

                TutorEvidenceRequestStatus.CANCELLED ->
                    error("Cancelled tutor evidence cannot be committed to mastery")
            }
        val committed = evidenceCommitCoordinator.commit(command.toLearningEvidenceCandidate())
        val sessionReceipt = committed.receipt
        check(sessionReceipt.evidenceStateVersion == expectedAcknowledgedStateVersion) {
            "Tutor evidence session acknowledgement advanced an unexpected version"
        }
        val masteryReceipt =
            TutorLearningEvidenceReceipt(
                receiptId = sessionReceipt.masteryReceiptId,
                receiptFingerprint = sessionReceipt.masteryReceiptFingerprint,
            )
        val submitted =
            request.copy(
                status = TutorEvidenceRequestStatus.SUBMITTED,
                stateVersion = sessionReceipt.evidenceStateVersion,
                resolvedAtEpochMillis = sessionReceipt.resolvedAtEpochMillis,
                terminalReceiptId = masteryReceipt.receiptId,
            )
        return when (committed) {
            is TutorLearningEvidenceCommitResult.Committed ->
                FinalizeTutorEvidenceResult.Submitted(submitted, masteryReceipt)

            is TutorLearningEvidenceCommitResult.Replayed ->
                FinalizeTutorEvidenceResult.Replayed(submitted, masteryReceipt)
        }
    }

    private fun requireBoundLearner(learnerScopeId: String) {
        check(productionOwnerIsCurrent()) {
            "Tutor learning memory production owner is closed"
        }
        require(learnerScopeId == boundLearnerId) {
            "Tutor learning memory is outside the bound learner scope"
        }
    }
}

private fun FinalizeTutorEvidenceCommand.toLearningEvidenceCandidate():
    TutorLearningEvidenceCandidate {
    val evidence = (terminal as TutorLearningEvidenceTerminal.Submitted).evidence
    return TutorLearningEvidenceCandidate(
        learnerId = learnerScopeId,
        evidenceRequestId = evidenceRequestId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        turnReceiptId = turnReceiptId,
        turnOrdinal = turnOrdinal,
        subject = subject,
        sessionAnchorId = problemAnchorId,
        kind = kind,
        requestVersion = requestVersion,
        modeVersion = modeVersion,
        directiveFingerprint = directiveFingerprint,
        idempotencyKey = clientIdempotencyKey,
        payloadFingerprint = payloadFingerprint,
        questionFingerprint = evidence.anchors.questionFingerprint,
        problemRevisionFingerprint = evidence.anchors.problemRevisionFingerprint,
        problemFingerprintVersion = evidence.anchors.fingerprintVersion,
        turnFingerprint = evidence.anchors.turnFingerprint,
        responseFingerprint = evidence.responseFingerprint,
        outcome = evidence.outcome,
        occurredAtEpochMillis = evidence.occurredAtEpochMillis,
        // The feature finalization clock may advance between crash replays. The candidate's
        // attestation is therefore bound to its immutable semantic occurrence time.
        attestedAtEpochMillis = evidence.occurredAtEpochMillis,
        producerVersion = evidence.producerVersion,
        currentSessionReference = evidence.currentSessionReference,
        attemptOrdinal = evidence.attemptOrdinal,
        retryCount = evidence.retryCount,
        hintCount = evidence.hintCount,
        answerWasRevealed = evidence.answerWasRevealed,
        independentlyAnswered = evidence.independentlyAnswered,
        assistance = evidence.assistance,
    )
}
