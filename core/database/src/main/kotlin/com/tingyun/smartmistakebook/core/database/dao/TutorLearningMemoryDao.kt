package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.database.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.database.FinalizeTutorEvidenceRequestCommand
import com.tingyun.smartmistakebook.core.database.PrepareTutorEvidenceRequestCommand
import com.tingyun.smartmistakebook.core.database.TutorConversationConflictException
import com.tingyun.smartmistakebook.core.database.TutorConversationArchiveWriteResult
import com.tingyun.smartmistakebook.core.database.TutorConversationWriteResult
import com.tingyun.smartmistakebook.core.database.TutorEvidenceConflictException
import com.tingyun.smartmistakebook.core.database.TutorEvidenceFinalizationResult
import com.tingyun.smartmistakebook.core.database.TutorEvidencePreparationResult
import com.tingyun.smartmistakebook.core.database.TutorMemoryScopeConflictException
import com.tingyun.smartmistakebook.core.database.TutorTurnAllocationResult
import com.tingyun.smartmistakebook.core.database.TutorTurnConflictException
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningProblemAnchorEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnReceiptEntity
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.LearningProblemAnchor
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt

private const val INSERT_CONFLICT = -1L

@Dao
internal abstract class TutorLearningMemoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertConversation(entity: TutorConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTurn(entity: TutorTurnReceiptEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEvidenceRequest(
        entity: TutorEvidenceRequestEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAnchor(entity: LearningProblemAnchorEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSourceFact(
        entity: LearningObservationSourceFactEntity,
    ): Long

    @Query("SELECT * FROM tutor_conversation WHERE conversation_id = :conversationId LIMIT 1")
    protected abstract suspend fun findConversation(
        conversationId: String,
    ): TutorConversationEntity?

    @Query(
        """
        SELECT * FROM tutor_conversation
        WHERE learner_id = :learnerId AND create_idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun findConversationByCreateKey(
        learnerId: String,
        idempotencyKey: String,
    ): TutorConversationEntity?

    @Query(
        """
        SELECT * FROM tutor_conversation
        WHERE learner_id = :learnerId
          AND conversation_id = :conversationId
          AND generation = :conversationGeneration
        LIMIT 1
        """,
    )
    internal abstract suspend fun openConversation(
        learnerId: String,
        conversationId: String,
        conversationGeneration: Long,
    ): TutorConversationEntity?

    @Query(
        """
        SELECT * FROM tutor_conversation
        WHERE learner_id = :learnerId
          AND status = 'ACTIVE'
        ORDER BY created_at_epoch_millis DESC, conversation_id DESC
        LIMIT 1
        """,
    )
    internal abstract suspend fun latestActiveConversation(
        learnerId: String,
    ): TutorConversationEntity?

    @Query(
        """
        SELECT * FROM tutor_conversation
        WHERE learner_id = :learnerId
          AND status = 'ACTIVE'
          AND substr(conversation_id, 1, length(:conversationIdPrefix)) = :conversationIdPrefix
        ORDER BY created_at_epoch_millis DESC, conversation_id DESC
        LIMIT 1
        """,
    )
    internal abstract suspend fun latestActiveConversationInNamespace(
        learnerId: String,
        conversationIdPrefix: String,
    ): TutorConversationEntity?

    @Query(
        """
        UPDATE tutor_conversation
        SET status = :archivedStatus,
            archive_idempotency_key = :idempotencyKey,
            archive_payload_fingerprint = :payloadFingerprint,
            archived_at_epoch_millis = :nowEpochMillis,
            updated_at_epoch_millis = :nowEpochMillis,
            state_version = state_version + 1
        WHERE conversation_id = :conversationId
          AND learner_id = :learnerId
          AND generation = :generation
          AND status = :activeStatus
          AND state_version = :expectedStateVersion
        """,
    )
    protected abstract suspend fun archiveConversationCas(
        learnerId: String,
        conversationId: String,
        generation: Long,
        expectedStateVersion: Long,
        idempotencyKey: String,
        payloadFingerprint: String,
        nowEpochMillis: Long,
        activeStatus: String,
        archivedStatus: String,
    ): Int

    @Query(
        """
        UPDATE tutor_conversation
        SET next_turn_ordinal = :nextTurnOrdinal,
            state_version = state_version + 1,
            updated_at_epoch_millis = :nowEpochMillis
        WHERE conversation_id = :conversationId
          AND learner_id = :learnerId
          AND generation = :generation
          AND status = :activeStatus
          AND state_version = :expectedStateVersion
          AND next_turn_ordinal = :expectedPreviousTurnOrdinal
        """,
    )
    protected abstract suspend fun allocateTurnOrdinalCas(
        learnerId: String,
        conversationId: String,
        generation: Long,
        expectedStateVersion: Long,
        expectedPreviousTurnOrdinal: Int,
        nextTurnOrdinal: Int,
        nowEpochMillis: Long,
        activeStatus: String,
    ): Int

    @Query(
        """
        SELECT * FROM tutor_turn_receipt
        WHERE conversation_id = :conversationId
          AND conversation_generation = :generation
          AND client_turn_id = :clientTurnId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findTurnByClientKey(
        conversationId: String,
        generation: Long,
        clientTurnId: String,
    ): TutorTurnReceiptEntity?

    @Query("SELECT * FROM tutor_turn_receipt WHERE turn_receipt_id = :turnReceiptId LIMIT 1")
    protected abstract suspend fun findTurn(turnReceiptId: String): TutorTurnReceiptEntity?

    @Query(
        """
        SELECT * FROM tutor_turn_receipt
        WHERE learner_id = :learnerId
          AND turn_receipt_id = :turnReceiptId
        LIMIT 1
        """,
    )
    internal abstract suspend fun openTurn(
        learnerId: String,
        turnReceiptId: String,
    ): TutorTurnReceiptEntity?

    @Query(
        """
        UPDATE tutor_evidence_request
        SET status = :cancelledStatus,
            state_version = state_version + 1,
            terminal_idempotency_key = :idempotencyKey,
            terminal_payload_fingerprint = :payloadFingerprint,
            terminal_source_fact_id = NULL,
            resolved_at_epoch_millis = CASE
                WHEN created_at_epoch_millis > :nowEpochMillis THEN created_at_epoch_millis
                ELSE :nowEpochMillis
            END
        WHERE learner_id = :learnerId
          AND conversation_id = :conversationId
          AND conversation_generation = :generation
          AND status = :pendingStatus
        """,
    )
    protected abstract suspend fun cancelPendingEvidenceForArchive(
        learnerId: String,
        conversationId: String,
        generation: Long,
        idempotencyKey: String,
        payloadFingerprint: String,
        nowEpochMillis: Long,
        pendingStatus: String,
        cancelledStatus: String,
    ): Int

    @Query(
        """
        SELECT * FROM tutor_evidence_request
        WHERE conversation_id = :conversationId
          AND conversation_generation = :generation
          AND prepare_idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun findEvidenceByPrepareKey(
        conversationId: String,
        generation: Long,
        idempotencyKey: String,
    ): TutorEvidenceRequestEntity?

    @Query(
        "SELECT * FROM tutor_evidence_request WHERE evidence_request_id = :evidenceRequestId LIMIT 1",
    )
    protected abstract suspend fun findEvidenceRequest(
        evidenceRequestId: String,
    ): TutorEvidenceRequestEntity?

    @Query("SELECT * FROM learning_problem_anchor WHERE anchor_id = :anchorId LIMIT 1")
    protected abstract suspend fun findAnchor(anchorId: String): LearningProblemAnchorEntity?

    @Query(
        """
        SELECT * FROM learning_problem_anchor
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND question_fingerprint = :questionFingerprint
          AND revision_fingerprint = :revisionFingerprint
          AND fingerprint_version = :fingerprintVersion
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAnchorByFingerprint(
        learnerId: String,
        subject: String,
        questionFingerprint: String,
        revisionFingerprint: String,
        fingerprintVersion: String,
    ): LearningProblemAnchorEntity?

    @Query(
        "SELECT * FROM learning_observation_source_fact WHERE source_fact_id = :sourceFactId LIMIT 1",
    )
    protected abstract suspend fun findSourceFact(
        sourceFactId: String,
    ): LearningObservationSourceFactEntity?

    @Query(
        """
        SELECT * FROM learning_observation_source_fact
        WHERE evidence_request_id = :evidenceRequestId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceFactForEvidence(
        evidenceRequestId: String,
    ): LearningObservationSourceFactEntity?

    @Query("SELECT COUNT(*) FROM tutor_evidence_request WHERE status = :status")
    internal abstract suspend fun countEvidenceRequests(status: String): Int

    @Query("SELECT COUNT(*) FROM learning_problem_anchor")
    internal abstract suspend fun countAnchors(): Int

    @Query("SELECT COUNT(*) FROM learning_observation_source_fact")
    internal abstract suspend fun countSourceFacts(): Int

    @Query("SELECT COUNT(*) FROM error_book_entry")
    internal abstract suspend fun countErrorBookEntries(): Int

    @Query("SELECT COUNT(*) FROM review_queue_item")
    internal abstract suspend fun countReviewQueueItems(): Int

    @Query(
        """
        UPDATE tutor_evidence_request
        SET status = :terminalStatus,
            state_version = state_version + 1,
            terminal_idempotency_key = :idempotencyKey,
            terminal_payload_fingerprint = :payloadFingerprint,
            terminal_source_fact_id = :sourceFactId,
            resolved_at_epoch_millis = :nowEpochMillis
        WHERE evidence_request_id = :evidenceRequestId
          AND status = :pendingStatus
          AND state_version = :expectedStateVersion
        """,
    )
    protected abstract suspend fun finalizeEvidenceCas(
        evidenceRequestId: String,
        expectedStateVersion: Long,
        terminalStatus: String,
        idempotencyKey: String,
        payloadFingerprint: String,
        sourceFactId: String?,
        nowEpochMillis: Long,
        pendingStatus: String,
    ): Int

    @Transaction
    open suspend fun createConversation(
        command: CreateTutorConversationCommand,
        nowEpochMillis: Long,
    ): TutorConversationWriteResult {
        val candidate = TutorConversationEntity(
            conversationId = command.conversationId,
            learnerId = command.learnerId,
            generation = command.generation,
            status = TutorConversationStatus.ACTIVE.name,
            nextTurnOrdinal = 0,
            stateVersion = 0,
            createIdempotencyKey = command.idempotencyKey,
            createPayloadFingerprint = command.payloadFingerprint,
            archiveIdempotencyKey = null,
            archivePayloadFingerprint = null,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            archivedAtEpochMillis = null,
        )
        if (insertConversation(candidate) != INSERT_CONFLICT) {
            return TutorConversationWriteResult(created = true, conversation = candidate.toModel())
        }
        val existing = findConversation(command.conversationId)
            ?: findConversationByCreateKey(command.learnerId, command.idempotencyKey)
            ?: throw TutorConversationConflictException(command.conversationId)
        if (!existing.matchesCreate(command)) {
            throw TutorConversationConflictException(command.conversationId)
        }
        return TutorConversationWriteResult(created = false, conversation = existing.toModel())
    }

    @Transaction
    open suspend fun archiveConversation(
        command: ArchiveTutorConversationCommand,
        nowEpochMillis: Long,
    ): TutorConversationArchiveWriteResult {
        val before = findConversation(command.conversationId)
            ?: throw TutorMemoryScopeConflictException("Tutor conversation is unavailable")
        before.requireScope(command.learnerId, command.conversationGeneration)
        if (before.status == TutorConversationStatus.ARCHIVED.name) {
            if (
                before.archiveIdempotencyKey == command.idempotencyKey &&
                before.archivePayloadFingerprint == command.payloadFingerprint
            ) {
                cancelPendingEvidenceForArchive(
                    learnerId = command.learnerId,
                    conversationId = command.conversationId,
                    generation = command.conversationGeneration,
                    idempotencyKey = command.idempotencyKey,
                    payloadFingerprint = command.payloadFingerprint,
                    nowEpochMillis = nowEpochMillis,
                    pendingStatus = TutorEvidenceRequestStatus.PENDING.name,
                    cancelledStatus = TutorEvidenceRequestStatus.CANCELLED.name,
                )
                return TutorConversationArchiveWriteResult(
                    archived = false,
                    conversation = before.toModel(),
                )
            }
            throw TutorConversationConflictException(command.conversationId)
        }
        if (
            before.status != TutorConversationStatus.ACTIVE.name ||
            before.stateVersion != command.expectedStateVersion
        ) {
            throw TutorConversationConflictException(command.conversationId)
        }
        cancelPendingEvidenceForArchive(
            learnerId = command.learnerId,
            conversationId = command.conversationId,
            generation = command.conversationGeneration,
            idempotencyKey = command.idempotencyKey,
            payloadFingerprint = command.payloadFingerprint,
            nowEpochMillis = nowEpochMillis,
            pendingStatus = TutorEvidenceRequestStatus.PENDING.name,
            cancelledStatus = TutorEvidenceRequestStatus.CANCELLED.name,
        )
        if (
            archiveConversationCas(
                learnerId = command.learnerId,
                conversationId = command.conversationId,
                generation = command.conversationGeneration,
                expectedStateVersion = command.expectedStateVersion,
                idempotencyKey = command.idempotencyKey,
                payloadFingerprint = command.payloadFingerprint,
                nowEpochMillis = nowEpochMillis,
                activeStatus = TutorConversationStatus.ACTIVE.name,
                archivedStatus = TutorConversationStatus.ARCHIVED.name,
            ) != 1
        ) {
            throw TutorConversationConflictException(command.conversationId)
        }
        return TutorConversationArchiveWriteResult(
            archived = true,
            conversation = checkNotNull(findConversation(command.conversationId)).toModel(),
        )
    }

    @Transaction
    open suspend fun allocateTurn(
        command: AllocateTutorTurnCommand,
        nowEpochMillis: Long,
    ): TutorTurnAllocationResult {
        findTurnByClientKey(
            command.conversationId,
            command.conversationGeneration,
            command.clientTurnId,
        )?.let { existing ->
            if (!existing.matches(command)) throw TutorTurnConflictException(command.clientTurnId)
            val conversation = checkNotNull(findConversation(command.conversationId))
            conversation.requireScope(command.learnerId, command.conversationGeneration)
            return TutorTurnAllocationResult(
                created = false,
                conversation = conversation.toModel(),
                receipt = existing.toModel(),
            )
        }

        val conversation = findConversation(command.conversationId)
            ?: throw TutorMemoryScopeConflictException("Tutor conversation is unavailable")
        conversation.requireScope(command.learnerId, command.conversationGeneration)
        if (
            conversation.status != TutorConversationStatus.ACTIVE.name ||
            conversation.stateVersion != command.expectedConversationStateVersion ||
            conversation.nextTurnOrdinal + 1 != command.expectedTurnOrdinal ||
            command.occurredAtEpochMillis < conversation.createdAtEpochMillis ||
            command.occurredAtEpochMillis > nowEpochMillis
        ) {
            throw TutorTurnConflictException(command.clientTurnId)
        }
        if (
            allocateTurnOrdinalCas(
                learnerId = command.learnerId,
                conversationId = command.conversationId,
                generation = command.conversationGeneration,
                expectedStateVersion = command.expectedConversationStateVersion,
                expectedPreviousTurnOrdinal = command.expectedTurnOrdinal - 1,
                nextTurnOrdinal = command.expectedTurnOrdinal,
                nowEpochMillis = nowEpochMillis,
                activeStatus = TutorConversationStatus.ACTIVE.name,
            ) != 1
        ) {
            throw TutorTurnConflictException(command.clientTurnId)
        }
        val receipt = command.toEntity(
            conversationStateVersion = command.expectedConversationStateVersion + 1,
            turnOrdinal = command.expectedTurnOrdinal,
            allocatedAtEpochMillis = nowEpochMillis,
        )
        if (insertTurn(receipt) == INSERT_CONFLICT) {
            throw TutorTurnConflictException(command.clientTurnId)
        }
        return TutorTurnAllocationResult(
            created = true,
            conversation = checkNotNull(findConversation(command.conversationId)).toModel(),
            receipt = receipt.toModel(),
        )
    }

    @Transaction
    open suspend fun prepareEvidence(
        command: PrepareTutorEvidenceRequestCommand,
        nowEpochMillis: Long,
    ): TutorEvidencePreparationResult {
        val replay = findEvidenceRequest(command.evidenceRequestId)
            ?: findEvidenceByPrepareKey(
                command.conversationId,
                command.conversationGeneration,
                command.idempotencyKey,
            )
        if (replay != null) {
            if (!replay.matches(command)) {
                throw TutorEvidenceConflictException(command.evidenceRequestId)
            }
            return TutorEvidencePreparationResult(created = false, request = replay.toModel())
        }
        val conversation = findConversation(command.conversationId)
            ?: throw TutorMemoryScopeConflictException("Tutor conversation is unavailable")
        conversation.requireScope(command.learnerId, command.conversationGeneration)
        if (
            conversation.status != TutorConversationStatus.ACTIVE.name ||
            conversation.stateVersion != command.conversationStateVersion
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val turn = findTurn(command.turnReceiptId)
            ?: throw TutorMemoryScopeConflictException("Tutor turn is unavailable")
        if (!turn.matches(command)) {
            throw TutorMemoryScopeConflictException("Tutor evidence scope does not match its turn")
        }
        val candidate = command.toEntity(nowEpochMillis)
        if (insertEvidenceRequest(candidate) == INSERT_CONFLICT) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        return TutorEvidencePreparationResult(created = true, request = candidate.toModel())
    }

    @Transaction
    open suspend fun finalizeEvidence(
        command: FinalizeTutorEvidenceRequestCommand,
        nowEpochMillis: Long,
    ): TutorEvidenceFinalizationResult {
        val request = findEvidenceRequest(command.evidenceRequestId)
            ?: throw TutorMemoryScopeConflictException("Tutor evidence request is unavailable")
        if (!request.matches(command)) {
            throw TutorMemoryScopeConflictException("Tutor evidence scope does not match")
        }
        if (request.status != TutorEvidenceRequestStatus.PENDING.name) {
            return replayFinalization(request, command)
        }
        if (request.stateVersion != command.expectedEvidenceStateVersion) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val conversation = findConversation(command.conversationId)
            ?: throw TutorMemoryScopeConflictException("Tutor conversation is unavailable")
        conversation.requireScope(command.learnerId, command.conversationGeneration)
        if (
            conversation.status != TutorConversationStatus.ACTIVE.name ||
            conversation.stateVersion != command.conversationStateVersion
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val turn = findTurn(command.turnReceiptId)
            ?: throw TutorMemoryScopeConflictException("Tutor turn is unavailable")
        if (!turn.matches(request)) {
            throw TutorMemoryScopeConflictException("Tutor evidence request no longer matches its turn")
        }

        if (command.terminalStatus == TutorEvidenceRequestStatus.CANCELLED) {
            if (
                finalizeEvidenceCas(
                    evidenceRequestId = command.evidenceRequestId,
                    expectedStateVersion = command.expectedEvidenceStateVersion,
                    terminalStatus = TutorEvidenceRequestStatus.CANCELLED.name,
                    idempotencyKey = command.idempotencyKey,
                    payloadFingerprint = command.payloadFingerprint,
                    sourceFactId = null,
                    nowEpochMillis = nowEpochMillis,
                    pendingStatus = TutorEvidenceRequestStatus.PENDING.name,
                ) != 1
            ) {
                throw TutorEvidenceConflictException(command.evidenceRequestId)
            }
            return TutorEvidenceFinalizationResult(
                replayed = false,
                request = checkNotNull(findEvidenceRequest(command.evidenceRequestId)).toModel(),
                anchor = null,
                sourceFact = null,
            )
        }

        val submission = checkNotNull(command.submission)
        if (
            submission.occurredAtEpochMillis < request.createdAtEpochMillis ||
            submission.occurredAtEpochMillis < turn.allocatedAtEpochMillis ||
            submission.occurredAtEpochMillis > nowEpochMillis
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        requireSourceMatchesKind(submission.source, command.kind)
        val anchor = ensureAnchor(command.toAnchorEntity(submission, nowEpochMillis))
        val sourceFact = command.toSourceFactEntity(submission)
        ensureNoConflictingFact(sourceFact)
        if (insertSourceFact(sourceFact) == INSERT_CONFLICT) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        if (
            finalizeEvidenceCas(
                evidenceRequestId = command.evidenceRequestId,
                expectedStateVersion = command.expectedEvidenceStateVersion,
                terminalStatus = TutorEvidenceRequestStatus.SUBMITTED.name,
                idempotencyKey = command.idempotencyKey,
                payloadFingerprint = command.payloadFingerprint,
                sourceFactId = sourceFact.sourceFactId,
                nowEpochMillis = nowEpochMillis,
                pendingStatus = TutorEvidenceRequestStatus.PENDING.name,
            ) != 1
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        return TutorEvidenceFinalizationResult(
            replayed = false,
            request = checkNotNull(findEvidenceRequest(command.evidenceRequestId)).toModel(),
            anchor = anchor.toModel(),
            sourceFact = sourceFact.toModel(),
        )
    }

    private suspend fun ensureAnchor(
        candidate: LearningProblemAnchorEntity,
    ): LearningProblemAnchorEntity {
        val existing = findAnchor(candidate.anchorId)
            ?: findAnchorByFingerprint(
                candidate.learnerId,
                candidate.subject,
                candidate.questionFingerprint,
                candidate.revisionFingerprint,
                candidate.fingerprintVersion,
            )
        if (existing != null) {
            if (!existing.matchesIdentity(candidate)) {
                throw TutorEvidenceConflictException(candidate.anchorId)
            }
            return existing
        }
        if (insertAnchor(candidate) == INSERT_CONFLICT) {
            throw TutorEvidenceConflictException(candidate.anchorId)
        }
        return candidate
    }

    private suspend fun ensureNoConflictingFact(candidate: LearningObservationSourceFactEntity) {
        val evidenceRequestId = checkNotNull(candidate.evidenceRequestId)
        val existing = findSourceFact(candidate.sourceFactId)
            ?: findSourceFactForEvidence(evidenceRequestId)
        if (existing != null) {
            throw TutorEvidenceConflictException(evidenceRequestId)
        }
    }

    private suspend fun replayFinalization(
        request: TutorEvidenceRequestEntity,
        command: FinalizeTutorEvidenceRequestCommand,
    ): TutorEvidenceFinalizationResult {
        if (
            request.status != command.terminalStatus.name ||
            request.terminalIdempotencyKey != command.idempotencyKey ||
            request.terminalPayloadFingerprint != command.payloadFingerprint
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val fact = request.terminalSourceFactId?.let { sourceFactId ->
            findSourceFact(sourceFactId)
                ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        if (command.terminalStatus == TutorEvidenceRequestStatus.SUBMITTED) {
            val submission = checkNotNull(command.submission)
            val expectedFact = command.toSourceFactEntity(submission)
            if (fact != expectedFact) throw TutorEvidenceConflictException(command.evidenceRequestId)
            val anchor = findAnchor(command.problemAnchorId)
                ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
            val expectedAnchor = command.toAnchorEntity(
                submission,
                anchor.createdAtEpochMillis,
            )
            if (anchor != expectedAnchor) {
                throw TutorEvidenceConflictException(command.evidenceRequestId)
            }
            return TutorEvidenceFinalizationResult(
                replayed = true,
                request = request.toModel(),
                anchor = anchor.toModel(),
                sourceFact = fact.toModel(),
            )
        }
        if (fact != null || command.submission != null) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        return TutorEvidenceFinalizationResult(
            replayed = true,
            request = request.toModel(),
            anchor = null,
            sourceFact = null,
        )
    }
}

private fun TutorConversationEntity.matchesCreate(command: CreateTutorConversationCommand): Boolean =
    conversationId == command.conversationId &&
        learnerId == command.learnerId &&
        generation == command.generation &&
        createIdempotencyKey == command.idempotencyKey &&
        createPayloadFingerprint == command.payloadFingerprint

private fun LearningProblemAnchorEntity.matchesIdentity(
    candidate: LearningProblemAnchorEntity,
): Boolean =
    anchorId == candidate.anchorId &&
        learnerId == candidate.learnerId &&
        subject == candidate.subject &&
        questionFingerprint == candidate.questionFingerprint &&
        revisionFingerprint == candidate.revisionFingerprint &&
        fingerprintVersion == candidate.fingerprintVersion

private fun TutorConversationEntity.requireScope(learnerId: String, generation: Long) {
    if (this.learnerId != learnerId || this.generation != generation) {
        throw TutorMemoryScopeConflictException("Tutor conversation scope does not match")
    }
}

private fun TutorTurnReceiptEntity.matches(command: AllocateTutorTurnCommand): Boolean =
    turnReceiptId == command.turnReceiptId &&
        conversationId == command.conversationId &&
        learnerId == command.learnerId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.expectedConversationStateVersion + 1 &&
        turnOrdinal == command.expectedTurnOrdinal &&
        clientTurnId == command.clientTurnId &&
        payloadFingerprint == command.payloadFingerprint &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint &&
        studentMessageFingerprint == command.studentMessageFingerprint &&
        studentMessageSummary == command.studentMessageSummary &&
        occurredAtEpochMillis == command.occurredAtEpochMillis

private fun TutorTurnReceiptEntity.matches(
    command: PrepareTutorEvidenceRequestCommand,
): Boolean =
    conversationId == command.conversationId &&
        learnerId == command.learnerId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.conversationStateVersion &&
        turnReceiptId == command.turnReceiptId &&
        turnOrdinal == command.turnOrdinal &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint

private fun TutorTurnReceiptEntity.matches(request: TutorEvidenceRequestEntity): Boolean =
    conversationId == request.conversationId &&
        learnerId == request.learnerId &&
        conversationGeneration == request.conversationGeneration &&
        conversationStateVersion == request.conversationStateVersion &&
        turnReceiptId == request.turnReceiptId &&
        turnOrdinal == request.turnOrdinal &&
        subject == request.subject &&
        problemAnchorId == request.problemAnchorId &&
        requestVersion == request.requestVersion &&
        explanationMode == request.explanationMode &&
        modeVersion == request.modeVersion &&
        directiveFingerprint == request.directiveFingerprint

private fun TutorEvidenceRequestEntity.matches(
    command: PrepareTutorEvidenceRequestCommand,
): Boolean =
    evidenceRequestId == command.evidenceRequestId &&
        learnerId == command.learnerId &&
        conversationId == command.conversationId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.conversationStateVersion &&
        turnReceiptId == command.turnReceiptId &&
        turnOrdinal == command.turnOrdinal &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        kind == command.kind.name &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint &&
        prepareIdempotencyKey == command.idempotencyKey &&
        preparePayloadFingerprint == command.payloadFingerprint

private fun TutorEvidenceRequestEntity.matches(
    command: FinalizeTutorEvidenceRequestCommand,
): Boolean =
    evidenceRequestId == command.evidenceRequestId &&
        learnerId == command.learnerId &&
        conversationId == command.conversationId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.conversationStateVersion &&
        turnReceiptId == command.turnReceiptId &&
        turnOrdinal == command.turnOrdinal &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        kind == command.kind.name &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint

private fun AllocateTutorTurnCommand.toEntity(
    conversationStateVersion: Long,
    turnOrdinal: Int,
    allocatedAtEpochMillis: Long,
) = TutorTurnReceiptEntity(
    turnReceiptId = turnReceiptId,
    conversationId = conversationId,
    learnerId = learnerId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnOrdinal = turnOrdinal,
    clientTurnId = clientTurnId,
    payloadFingerprint = payloadFingerprint,
    subject = subject.name,
    problemAnchorId = problemAnchorId,
    requestVersion = requestVersion,
    explanationMode = explanationMode.name,
    modeVersion = modeVersion,
    directiveFingerprint = directiveFingerprint,
    studentMessageFingerprint = studentMessageFingerprint,
    studentMessageSummary = studentMessageSummary,
    occurredAtEpochMillis = occurredAtEpochMillis,
    allocatedAtEpochMillis = allocatedAtEpochMillis,
)

private fun PrepareTutorEvidenceRequestCommand.toEntity(
    createdAtEpochMillis: Long,
) = TutorEvidenceRequestEntity(
    evidenceRequestId = evidenceRequestId,
    learnerId = learnerId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnReceiptId = turnReceiptId,
    turnOrdinal = turnOrdinal,
    subject = subject.name,
    problemAnchorId = problemAnchorId,
    kind = kind.name,
    requestVersion = requestVersion,
    explanationMode = explanationMode.name,
    modeVersion = modeVersion,
    directiveFingerprint = directiveFingerprint,
    status = TutorEvidenceRequestStatus.PENDING.name,
    stateVersion = 0,
    prepareIdempotencyKey = idempotencyKey,
    preparePayloadFingerprint = payloadFingerprint,
    terminalIdempotencyKey = null,
    terminalPayloadFingerprint = null,
    terminalSourceFactId = null,
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = null,
)

private fun FinalizeTutorEvidenceRequestCommand.toAnchorEntity(
    submission: com.tingyun.smartmistakebook.core.database.TutorEvidenceSubmission,
    createdAtEpochMillis: Long,
) = LearningProblemAnchorEntity(
    anchorId = problemAnchorId,
    learnerId = learnerId,
    subject = subject.name,
    questionFingerprint = submission.questionFingerprint,
    revisionFingerprint = submission.revisionFingerprint,
    fingerprintVersion = submission.fingerprintVersion,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun FinalizeTutorEvidenceRequestCommand.toSourceFactEntity(
    submission: com.tingyun.smartmistakebook.core.database.TutorEvidenceSubmission,
) = LearningObservationSourceFactEntity(
    sourceFactId = submission.sourceFactId,
    learnerId = learnerId,
    source = submission.source.name,
    factKind = submission.factKind.name,
    anchorId = problemAnchorId,
    subject = subject.name,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    turnReceiptId = turnReceiptId,
    evidenceRequestId = evidenceRequestId,
    responseFingerprint = submission.responseFingerprint,
    responseSummary = submission.responseSummary,
    payloadFingerprint = payloadFingerprint,
    occurredAtEpochMillis = submission.occurredAtEpochMillis,
    sourceVersion = submission.sourceVersion,
)

private fun requireSourceMatchesKind(
    source: LearningObservationSource,
    kind: TutorEvidenceRequestKind,
) {
    val expected = when (kind) {
        TutorEvidenceRequestKind.CHOICE -> LearningObservationSource.TUTOR_CHOICE
        TutorEvidenceRequestKind.FREE_RESPONSE -> LearningObservationSource.TUTOR_FREE_RESPONSE
        TutorEvidenceRequestKind.VISUAL_TARGET -> LearningObservationSource.TUTOR_VISUAL_TARGET
        TutorEvidenceRequestKind.SPECIFIC_STUCK -> LearningObservationSource.TUTOR_SPECIFIC_STUCK
    }
    if (source != expected) {
        throw TutorMemoryScopeConflictException("Tutor source kind does not match the request")
    }
}

private fun TutorConversationEntity.toModel() = TutorConversation(
    conversationId = conversationId,
    learnerScopeId = learnerId,
    generation = generation,
    status = TutorConversationStatus.valueOf(status),
    createdAtEpochMillis = createdAtEpochMillis,
    archivedAtEpochMillis = archivedAtEpochMillis,
    stateVersion = stateVersion,
)

private fun TutorTurnReceiptEntity.toModel() = TutorTurnReceipt(
    turnReceiptId = turnReceiptId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnOrdinal = turnOrdinal,
    subject = SubjectKind.valueOf(subject),
    problemAnchorId = problemAnchorId,
    requestVersion = requestVersion,
    modeVersion = modeVersion,
    explanationMode = TutorExplanationMode.valueOf(explanationMode),
    directiveFingerprint = directiveFingerprint,
    studentMessageFingerprint = studentMessageFingerprint,
    studentMessageSummary = studentMessageSummary,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun TutorEvidenceRequestEntity.toModel() = TutorEvidenceRequest(
    evidenceRequestId = evidenceRequestId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnReceiptId = turnReceiptId,
    turnOrdinal = turnOrdinal,
    subject = SubjectKind.valueOf(subject),
    problemAnchorId = problemAnchorId,
    kind = TutorEvidenceRequestKind.valueOf(kind),
    requestVersion = requestVersion,
    modeVersion = modeVersion,
    explanationMode = TutorExplanationMode.valueOf(explanationMode),
    directiveFingerprint = directiveFingerprint,
    status = TutorEvidenceRequestStatus.valueOf(status),
    stateVersion = stateVersion,
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
    terminalSourceFactId = terminalSourceFactId,
)

private fun LearningProblemAnchorEntity.toModel() = LearningProblemAnchor(
    anchorId = anchorId,
    learnerScopeId = learnerId,
    subject = SubjectKind.valueOf(subject),
    questionFingerprint = questionFingerprint,
    revisionFingerprint = revisionFingerprint,
    fingerprintVersion = fingerprintVersion,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun LearningObservationSourceFactEntity.toModel() = LearningObservationSourceFact(
    sourceFactId = sourceFactId,
    learnerScopeId = learnerId,
    source = LearningObservationSource.valueOf(source),
    factKind = com.tingyun.smartmistakebook.core.model.LearningObservationFactKind.valueOf(factKind),
    anchorId = anchorId,
    subject = SubjectKind.valueOf(subject),
    conversationGeneration = conversationGeneration,
    conversationId = conversationId,
    turnReceiptId = turnReceiptId,
    evidenceRequestId = evidenceRequestId,
    responseFingerprint = responseFingerprint,
    responseSummary = responseSummary,
    occurredAtEpochMillis = occurredAtEpochMillis,
    sourceVersion = sourceVersion,
)
