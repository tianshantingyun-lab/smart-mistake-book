package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.AcknowledgeTutorLearningEvidenceSessionCommand
import com.tingyun.smartmistakebook.core.database.BeginTutorLearningEvidenceSessionIntentCommand
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionRecord
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionRecordState

internal class RoomTutorLearningEvidenceSessionAdapter(
    private val database: TutorLearningEvidenceSessionDatabasePort,
) : TutorLearningEvidenceSessionPort {
    override suspend fun begin(
        intent: TutorLearningEvidenceSessionIntent,
    ): TutorLearningEvidenceSessionBeginResult {
        val durable =
            database.beginTutorLearningEvidenceSessionIntent(
                BeginTutorLearningEvidenceSessionIntentCommand(
                    learnerId = intent.scope.learnerId,
                    evidenceRequestId = intent.evidenceRequestId,
                    conversationId = intent.scope.conversationId,
                    conversationGeneration = intent.scope.conversationGeneration,
                    conversationStateVersion = intent.conversationStateVersion,
                    turnReceiptId = intent.scope.turnReceiptId,
                    turnOrdinal = intent.turnOrdinal,
                    subject = intent.subject,
                    sessionAnchorId = intent.sessionAnchorId,
                    evidenceKind = intent.kind,
                    requestVersion = intent.requestVersion,
                    modeVersion = intent.modeVersion,
                    idempotencyKey = intent.idempotencyKey,
                    candidateFingerprint = intent.candidateFingerprint,
                ),
            ).requireMatches(intent)
        return when (durable.state) {
            TutorLearningEvidenceSessionRecordState.PENDING_MASTERY ->
                TutorLearningEvidenceSessionBeginResult.Pending(durable.toIntent())

            TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED ->
                TutorLearningEvidenceSessionBeginResult.Finalized(durable.toReceipt())
        }
    }

    override suspend fun acknowledge(
        acknowledgement: TutorLearningEvidenceSessionAcknowledgement,
    ): TutorLearningEvidenceSessionAcknowledgeResult {
        val result =
            database.acknowledgeTutorLearningEvidenceSession(
                AcknowledgeTutorLearningEvidenceSessionCommand(
                    learnerId = acknowledgement.scope.learnerId,
                    evidenceRequestId = acknowledgement.evidenceRequestId,
                    conversationId = acknowledgement.scope.conversationId,
                    conversationGeneration = acknowledgement.scope.conversationGeneration,
                    turnReceiptId = acknowledgement.scope.turnReceiptId,
                    candidateFingerprint = acknowledgement.candidateFingerprint,
                    expectedStateVersion = PENDING_EVIDENCE_STATE_VERSION,
                    masteryReceiptId = acknowledgement.masteryReceiptId,
                    masteryReceiptFingerprint = acknowledgement.masteryReceiptFingerprint,
                ),
            )
        val receipt = result.record.requireMatches(acknowledgement).toReceipt()
        return if (result.replayed) {
            TutorLearningEvidenceSessionAcknowledgeResult.Replayed(receipt)
        } else {
            TutorLearningEvidenceSessionAcknowledgeResult.Acknowledged(receipt)
        }
    }
}

private fun TutorLearningEvidenceSessionRecord.requireMatches(
    requested: TutorLearningEvidenceSessionIntent,
): TutorLearningEvidenceSessionRecord {
    check(
        learnerId == requested.scope.learnerId &&
            evidenceRequestId == requested.evidenceRequestId &&
            conversationId == requested.scope.conversationId &&
            conversationGeneration == requested.scope.conversationGeneration &&
            conversationStateVersion == requested.conversationStateVersion &&
            turnReceiptId == requested.scope.turnReceiptId &&
            turnOrdinal == requested.turnOrdinal &&
            subject == requested.subject &&
            sessionAnchorId == requested.sessionAnchorId &&
            evidenceKind == requested.kind &&
            requestVersion == requested.requestVersion &&
            modeVersion == requested.modeVersion &&
            idempotencyKey == requested.idempotencyKey &&
            candidateFingerprint == requested.candidateFingerprint
    ) {
        "Durable tutor evidence intent differs from the requested scoped payload"
    }
    return this
}

private fun TutorLearningEvidenceSessionRecord.requireMatches(
    requested: TutorLearningEvidenceSessionAcknowledgement,
): TutorLearningEvidenceSessionRecord {
    check(
        learnerId == requested.scope.learnerId &&
            evidenceRequestId == requested.evidenceRequestId &&
            conversationId == requested.scope.conversationId &&
            conversationGeneration == requested.scope.conversationGeneration &&
            turnReceiptId == requested.scope.turnReceiptId &&
            candidateFingerprint == requested.candidateFingerprint &&
            masteryReceiptId == requested.masteryReceiptId &&
            masteryReceiptFingerprint == requested.masteryReceiptFingerprint &&
            state == TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED
    ) {
        "Durable tutor evidence acknowledgement differs from the mastery receipt"
    }
    return this
}

private fun TutorLearningEvidenceSessionRecord.toIntent() =
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
        kind = evidenceKind,
        requestVersion = requestVersion,
        modeVersion = modeVersion,
        idempotencyKey = idempotencyKey,
        candidateFingerprint = candidateFingerprint,
        state = TutorLearningEvidenceSessionState.PENDING_MASTERY,
    )

private fun TutorLearningEvidenceSessionRecord.toReceipt() =
    TutorLearningEvidenceSessionReceipt(
        scope =
            TutorLearningEvidenceSessionScope(
                learnerId = learnerId,
                conversationId = conversationId,
                conversationGeneration = conversationGeneration,
                turnReceiptId = turnReceiptId,
            ),
        evidenceRequestId = evidenceRequestId,
        candidateFingerprint = candidateFingerprint,
        masteryReceiptId = requireNotNull(masteryReceiptId),
        masteryReceiptFingerprint = requireNotNull(masteryReceiptFingerprint),
        evidenceStateVersion = stateVersion,
        createdAtEpochMillis = intentCreatedAtEpochMillis,
        resolvedAtEpochMillis = requireNotNull(acknowledgedAtEpochMillis),
    )

private const val PENDING_EVIDENCE_STATE_VERSION = 0L
