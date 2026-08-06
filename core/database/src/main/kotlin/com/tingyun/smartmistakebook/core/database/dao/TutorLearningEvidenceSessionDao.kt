package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.AcknowledgeTutorLearningEvidenceSessionCommand
import com.tingyun.smartmistakebook.core.database.BeginTutorLearningEvidenceSessionIntentCommand
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionAcknowledgeDatabaseResult
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionConflictException
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionRecord
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionRecordState
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorLearningEvidenceFinalizationReceiptEntity
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus

private const val INSERT_CONFLICT = -1L

@Dao
internal abstract class TutorLearningEvidenceSessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIntent(
        entity: TutorLearningEvidenceFinalizationReceiptEntity,
    ): Long

    @Query(
        """
        SELECT *
        FROM tutor_learning_evidence_finalization_receipt
        WHERE evidence_request_id = :evidenceRequestId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findIntent(
        evidenceRequestId: String,
    ): TutorLearningEvidenceFinalizationReceiptEntity?

    @Query(
        """
        SELECT *
        FROM tutor_learning_evidence_finalization_receipt
        WHERE learner_id = :learnerId
          AND conversation_id = :conversationId
          AND conversation_generation = :conversationGeneration
          AND idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun findIntentByIdempotencyScope(
        learnerId: String,
        conversationId: String,
        conversationGeneration: Long,
        idempotencyKey: String,
    ): TutorLearningEvidenceFinalizationReceiptEntity?

    @Query(
        """
        SELECT *
        FROM tutor_learning_evidence_finalization_receipt
        WHERE learner_id = :learnerId
          AND evidence_request_id = :evidenceRequestId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findIntentForLearner(
        learnerId: String,
        evidenceRequestId: String,
    ): TutorLearningEvidenceFinalizationReceiptEntity?

    @Query(
        """
        SELECT *
        FROM tutor_evidence_request
        WHERE evidence_request_id = :evidenceRequestId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findEvidenceRequest(
        evidenceRequestId: String,
    ): TutorEvidenceRequestEntity?

    @Query(
        """
        UPDATE tutor_learning_evidence_finalization_receipt
        SET state = :acknowledgedState,
            mastery_receipt_id = :masteryReceiptId,
            mastery_receipt_fingerprint = :masteryReceiptFingerprint,
            state_version = :acknowledgedStateVersion,
            acknowledged_at_epoch_millis = :acknowledgedAtEpochMillis
        WHERE evidence_request_id = :evidenceRequestId
          AND learner_id = :learnerId
          AND conversation_id = :conversationId
          AND conversation_generation = :conversationGeneration
          AND turn_receipt_id = :turnReceiptId
          AND candidate_fingerprint = :candidateFingerprint
          AND state = :pendingState
          AND state_version = :expectedStateVersion
        """,
    )
    protected abstract suspend fun acknowledgeIntentCas(
        evidenceRequestId: String,
        learnerId: String,
        conversationId: String,
        conversationGeneration: Long,
        turnReceiptId: String,
        candidateFingerprint: String,
        expectedStateVersion: Long,
        masteryReceiptId: String,
        masteryReceiptFingerprint: String,
        pendingState: String,
        acknowledgedState: String,
        acknowledgedStateVersion: Long,
        acknowledgedAtEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun begin(
        command: BeginTutorLearningEvidenceSessionIntentCommand,
        nowEpochMillis: Long,
    ): TutorLearningEvidenceSessionRecord {
        require(nowEpochMillis >= 0)
        val durable =
            findIntent(command.evidenceRequestId)
                ?: findIntentByIdempotencyScope(
                    learnerId = command.learnerId,
                    conversationId = command.conversationId,
                    conversationGeneration = command.conversationGeneration,
                    idempotencyKey = command.idempotencyKey,
                )
        if (durable != null) {
            durable.requireExact(command)
            return durable.toRecord()
        }

        findEvidenceRequest(command.evidenceRequestId)
            ?.requirePendingAndBoundTo(command)
            ?: throw conflict("Tutor evidence request is missing")

        val candidate = command.toEntity(nowEpochMillis)
        if (insertIntent(candidate) != INSERT_CONFLICT) {
            return candidate.toRecord()
        }

        val existing =
            findIntent(command.evidenceRequestId)
                ?: findIntentByIdempotencyScope(
                    learnerId = command.learnerId,
                    conversationId = command.conversationId,
                    conversationGeneration = command.conversationGeneration,
                    idempotencyKey = command.idempotencyKey,
                )
                ?: throw conflict("Tutor evidence intent conflicted without a durable row")
        existing.requireExact(command)
        return existing.toRecord()
    }

    open suspend fun openForLearner(
        learnerId: String,
        evidenceRequestId: String,
    ): TutorLearningEvidenceSessionRecord? =
        findIntentForLearner(
            learnerId = learnerId,
            evidenceRequestId = evidenceRequestId,
        )?.toRecord()

    @Transaction
    open suspend fun acknowledge(
        command: AcknowledgeTutorLearningEvidenceSessionCommand,
        nowEpochMillis: Long,
    ): TutorLearningEvidenceSessionAcknowledgeDatabaseResult {
        require(nowEpochMillis >= 0)
        val existing =
            findIntent(command.evidenceRequestId)
                ?.requireBoundTo(command)
                ?: throw conflict("Tutor evidence intent is missing")
        if (existing.state == TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED.name) {
            existing.requireExactAcknowledgement(command)
            return TutorLearningEvidenceSessionAcknowledgeDatabaseResult(
                replayed = true,
                record = existing.toRecord(),
            )
        }
        if (
            existing.state != TutorLearningEvidenceSessionRecordState.PENDING_MASTERY.name ||
            existing.stateVersion != command.expectedStateVersion ||
            nowEpochMillis < existing.intentCreatedAtEpochMillis
        ) {
            throw conflict("Tutor evidence intent is not acknowledgeable")
        }

        val updated =
            acknowledgeIntentCas(
                evidenceRequestId = command.evidenceRequestId,
                learnerId = command.learnerId,
                conversationId = command.conversationId,
                conversationGeneration = command.conversationGeneration,
                turnReceiptId = command.turnReceiptId,
                candidateFingerprint = command.candidateFingerprint,
                expectedStateVersion = command.expectedStateVersion,
                masteryReceiptId = command.masteryReceiptId,
                masteryReceiptFingerprint = command.masteryReceiptFingerprint,
                pendingState = TutorLearningEvidenceSessionRecordState.PENDING_MASTERY.name,
                acknowledgedState =
                    TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED.name,
                acknowledgedStateVersion = ACKNOWLEDGED_STATE_VERSION,
                acknowledgedAtEpochMillis = nowEpochMillis,
            )
        val durable =
            findIntent(command.evidenceRequestId)
                ?.requireBoundTo(command)
                ?: throw conflict("Tutor evidence acknowledgement disappeared")
        durable.requireExactAcknowledgement(command)
        return TutorLearningEvidenceSessionAcknowledgeDatabaseResult(
            replayed = updated == 0,
            record = durable.toRecord(),
        )
    }

    private fun conflict(message: String) =
        TutorLearningEvidenceSessionConflictException(message)

    private companion object {
        const val ACKNOWLEDGED_STATE_VERSION = 1L
    }
}

private fun TutorEvidenceRequestEntity.requirePendingAndBoundTo(
    command: BeginTutorLearningEvidenceSessionIntentCommand,
) {
    if (
        learnerId != command.learnerId ||
        conversationId != command.conversationId ||
        conversationGeneration != command.conversationGeneration ||
        conversationStateVersion != command.conversationStateVersion ||
        turnReceiptId != command.turnReceiptId ||
        turnOrdinal != command.turnOrdinal ||
        subject != command.subject.name ||
        problemAnchorId != command.sessionAnchorId ||
        kind != command.evidenceKind.name ||
        requestVersion != command.requestVersion ||
        modeVersion != command.modeVersion ||
        status != TutorEvidenceRequestStatus.PENDING.name
    ) {
        throw TutorLearningEvidenceSessionConflictException(
            "Tutor evidence request does not match the finalization intent",
        )
    }
}

private fun TutorLearningEvidenceFinalizationReceiptEntity.requireExact(
    command: BeginTutorLearningEvidenceSessionIntentCommand,
) {
    if (
        evidenceRequestId != command.evidenceRequestId ||
        learnerId != command.learnerId ||
        conversationId != command.conversationId ||
        conversationGeneration != command.conversationGeneration ||
        conversationStateVersion != command.conversationStateVersion ||
        turnReceiptId != command.turnReceiptId ||
        turnOrdinal != command.turnOrdinal ||
        subject != command.subject.name ||
        sessionAnchorId != command.sessionAnchorId ||
        evidenceKind != command.evidenceKind.name ||
        requestVersion != command.requestVersion ||
        modeVersion != command.modeVersion ||
        idempotencyKey != command.idempotencyKey ||
        candidateFingerprint != command.candidateFingerprint
    ) {
        throw TutorLearningEvidenceSessionConflictException(
            "Tutor evidence intent conflicts with its durable scope or payload",
        )
    }
}

private fun TutorLearningEvidenceFinalizationReceiptEntity.requireBoundTo(
    command: AcknowledgeTutorLearningEvidenceSessionCommand,
): TutorLearningEvidenceFinalizationReceiptEntity {
    if (
        evidenceRequestId != command.evidenceRequestId ||
        learnerId != command.learnerId ||
        conversationId != command.conversationId ||
        conversationGeneration != command.conversationGeneration ||
        turnReceiptId != command.turnReceiptId ||
        candidateFingerprint != command.candidateFingerprint
    ) {
        throw TutorLearningEvidenceSessionConflictException(
            "Tutor evidence acknowledgement is outside the durable intent scope",
        )
    }
    return this
}

private fun TutorLearningEvidenceFinalizationReceiptEntity.requireExactAcknowledgement(
    command: AcknowledgeTutorLearningEvidenceSessionCommand,
) {
    if (
        state != TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED.name ||
        stateVersion != 1L ||
        masteryReceiptId != command.masteryReceiptId ||
        masteryReceiptFingerprint != command.masteryReceiptFingerprint ||
        acknowledgedAtEpochMillis == null
    ) {
        throw TutorLearningEvidenceSessionConflictException(
            "Tutor evidence acknowledgement conflicts with the durable mastery receipt",
        )
    }
}

private fun BeginTutorLearningEvidenceSessionIntentCommand.toEntity(
    nowEpochMillis: Long,
) = TutorLearningEvidenceFinalizationReceiptEntity(
    evidenceRequestId = evidenceRequestId,
    learnerId = learnerId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnReceiptId = turnReceiptId,
    turnOrdinal = turnOrdinal,
    subject = subject.name,
    sessionAnchorId = sessionAnchorId,
    evidenceKind = evidenceKind.name,
    requestVersion = requestVersion,
    modeVersion = modeVersion,
    idempotencyKey = idempotencyKey,
    candidateFingerprint = candidateFingerprint,
    state = TutorLearningEvidenceSessionRecordState.PENDING_MASTERY.name,
    masteryReceiptId = null,
    masteryReceiptFingerprint = null,
    stateVersion = 0,
    intentCreatedAtEpochMillis = nowEpochMillis,
    acknowledgedAtEpochMillis = null,
)

private fun TutorLearningEvidenceFinalizationReceiptEntity.toRecord() =
    TutorLearningEvidenceSessionRecord(
        learnerId = learnerId,
        evidenceRequestId = evidenceRequestId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        turnReceiptId = turnReceiptId,
        turnOrdinal = turnOrdinal,
        subject = SubjectKind.valueOf(subject),
        sessionAnchorId = sessionAnchorId,
        evidenceKind = TutorEvidenceRequestKind.valueOf(evidenceKind),
        requestVersion = requestVersion,
        modeVersion = modeVersion,
        idempotencyKey = idempotencyKey,
        candidateFingerprint = candidateFingerprint,
        state = TutorLearningEvidenceSessionRecordState.valueOf(state),
        masteryReceiptId = masteryReceiptId,
        masteryReceiptFingerprint = masteryReceiptFingerprint,
        stateVersion = stateVersion,
        intentCreatedAtEpochMillis = intentCreatedAtEpochMillis,
        acknowledgedAtEpochMillis = acknowledgedAtEpochMillis,
    )
