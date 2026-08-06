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
import com.tingyun.smartmistakebook.core.database.entity.ModelTaskEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceCancellationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnResponseEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedTutorProblemIdentity
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput

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

    @Query(
        """
        SELECT conversation_id, learner_id, generation, status, next_turn_ordinal,
               state_version, create_idempotency_key, create_payload_fingerprint,
               archive_idempotency_key, archive_payload_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis, archived_at_epoch_millis
        FROM tutor_conversation
        WHERE conversation_id = :conversationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findConversation(
        conversationId: String,
    ): TutorConversationEntity?

    @Query(
        """
        SELECT conversation_id, learner_id, generation, status, next_turn_ordinal,
               state_version, create_idempotency_key, create_payload_fingerprint,
               archive_idempotency_key, archive_payload_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis, archived_at_epoch_millis
        FROM tutor_conversation
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
        SELECT conversation_id, learner_id, generation, status, next_turn_ordinal,
               state_version, create_idempotency_key, create_payload_fingerprint,
               archive_idempotency_key, archive_payload_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis, archived_at_epoch_millis
        FROM tutor_conversation
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
        SELECT conversation_id, learner_id, generation, status, next_turn_ordinal,
               state_version, create_idempotency_key, create_payload_fingerprint,
               archive_idempotency_key, archive_payload_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis, archived_at_epoch_millis
        FROM tutor_conversation
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
        SELECT conversation_id, learner_id, generation, status, next_turn_ordinal,
               state_version, create_idempotency_key, create_payload_fingerprint,
               archive_idempotency_key, archive_payload_fingerprint,
               created_at_epoch_millis, updated_at_epoch_millis, archived_at_epoch_millis
        FROM tutor_conversation
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
        SELECT turn_receipt_id, conversation_id, learner_id, conversation_generation,
               conversation_state_version, turn_ordinal, client_turn_id,
               payload_fingerprint, subject, problem_anchor_id, request_version,
               explanation_mode, mode_version, directive_fingerprint,
               student_message_fingerprint, student_message_summary,
               occurred_at_epoch_millis, allocated_at_epoch_millis
        FROM tutor_turn_receipt
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

    @Query(
        """
        SELECT turn_receipt_id, conversation_id, learner_id, conversation_generation,
               conversation_state_version, turn_ordinal, client_turn_id,
               payload_fingerprint, subject, problem_anchor_id, request_version,
               explanation_mode, mode_version, directive_fingerprint,
               student_message_fingerprint, student_message_summary,
               occurred_at_epoch_millis, allocated_at_epoch_millis
        FROM tutor_turn_receipt
        WHERE turn_receipt_id = :turnReceiptId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findTurn(turnReceiptId: String): TutorTurnReceiptEntity?

    @Query(
        """
        SELECT turn_receipt_id, conversation_id, learner_id, conversation_generation,
               conversation_state_version, turn_ordinal, client_turn_id,
               payload_fingerprint, subject, problem_anchor_id, request_version,
               explanation_mode, mode_version, directive_fingerprint,
               student_message_fingerprint, student_message_summary,
               occurred_at_epoch_millis, allocated_at_epoch_millis
        FROM tutor_turn_receipt
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
          AND NOT EXISTS (
              SELECT 1
              FROM tutor_learning_evidence_finalization_receipt AS intent
              WHERE intent.evidence_request_id =
                    tutor_evidence_request.evidence_request_id
          )
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
        SELECT evidence_request_id, learner_id, conversation_id, conversation_generation,
               conversation_state_version, turn_receipt_id, turn_ordinal, subject,
               problem_anchor_id, kind, request_version, explanation_mode, mode_version,
               directive_fingerprint, status, state_version, prepare_idempotency_key,
               prepare_payload_fingerprint, terminal_idempotency_key,
               terminal_payload_fingerprint, terminal_source_fact_id,
               created_at_epoch_millis, resolved_at_epoch_millis
        FROM tutor_evidence_request
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
        """
        SELECT evidence_request_id, learner_id, conversation_id, conversation_generation,
               conversation_state_version, turn_receipt_id, turn_ordinal, subject,
               problem_anchor_id, kind, request_version, explanation_mode, mode_version,
               directive_fingerprint, status, state_version, prepare_idempotency_key,
               prepare_payload_fingerprint, terminal_idempotency_key,
               terminal_payload_fingerprint, terminal_source_fact_id,
               created_at_epoch_millis, resolved_at_epoch_millis
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
        SELECT evidence_request_id, learner_id, conversation_id, conversation_generation,
               conversation_state_version, turn_receipt_id, turn_ordinal, subject,
               problem_anchor_id, kind, request_version, explanation_mode, mode_version,
               directive_fingerprint, status, state_version, prepare_idempotency_key,
               prepare_payload_fingerprint, terminal_idempotency_key,
               terminal_payload_fingerprint, terminal_source_fact_id,
               created_at_epoch_millis, resolved_at_epoch_millis
        FROM tutor_evidence_request
        WHERE learner_id = :learnerId
          AND evidence_request_id = :evidenceRequestId
        LIMIT 1
        """,
    )
    internal abstract suspend fun openEvidenceRequest(
        learnerId: String,
        evidenceRequestId: String,
    ): TutorEvidenceRequestEntity?

    @Query(
        """
        SELECT anchor_id, learner_id, subject, question_fingerprint,
               revision_fingerprint, fingerprint_version, created_at_epoch_millis
        FROM learning_problem_anchor
        WHERE anchor_id = :anchorId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAnchor(anchorId: String): LearningProblemAnchorEntity?

    @Query(
        """
        SELECT anchor_id, learner_id, subject, question_fingerprint,
               revision_fingerprint, fingerprint_version, created_at_epoch_millis
        FROM learning_problem_anchor
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
        """
        SELECT source_fact_id, learner_id, source, fact_kind, anchor_id, subject,
               conversation_id, conversation_generation, turn_receipt_id,
               evidence_request_id, response_fingerprint, response_summary,
               payload_fingerprint, occurred_at_epoch_millis, source_version
        FROM learning_observation_source_fact
        WHERE source_fact_id = :sourceFactId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceFact(
        sourceFactId: String,
    ): LearningObservationSourceFactEntity?

    @Query(
        """
        SELECT source_fact_id, learner_id, source, fact_kind, anchor_id, subject,
               conversation_id, conversation_generation, turn_receipt_id,
               evidence_request_id, response_fingerprint, response_summary,
               payload_fingerprint, occurred_at_epoch_millis, source_version
        FROM learning_observation_source_fact
        WHERE evidence_request_id = :evidenceRequestId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceFactForEvidence(
        evidenceRequestId: String,
    ): LearningObservationSourceFactEntity?

    @Query(
        """
        SELECT learner_id, session_id, question_document_id, revision_number,
               evidence_request_id, cancelled_at_epoch_millis
        FROM tutor_evidence_cancellation
        WHERE learner_id = :learnerId
          AND evidence_request_id = :evidenceRequestId
        ORDER BY session_id, question_document_id, revision_number
        LIMIT 2
        """,
    )
    protected abstract suspend fun findPersistentEvidenceCancellations(
        learnerId: String,
        evidenceRequestId: String,
    ): List<TutorEvidenceCancellationEntity>

    @Query(
        """
        SELECT session_id, question_document_id, revision_number, cycle_ordinal,
               turn_ordinal, diagnostic_stem_markdown, selected_choice_id,
               selected_choice_markdown, selection_was_correct, feedback_markdown,
               evidence_request_id, requested_move, solution_revealed,
               choice_submitted_at_epoch_millis, submitted_at_epoch_millis,
               updated_at_epoch_millis
        FROM tutor_turn_response
        WHERE evidence_request_id = :evidenceRequestId
        ORDER BY session_id, cycle_ordinal, turn_ordinal
        LIMIT 2
        """,
    )
    protected abstract suspend fun findChoiceResponsesForEvidence(
        evidenceRequestId: String,
    ): List<TutorTurnResponseEntity>

    @Query(
        """
        SELECT task_id, request_id, request_fingerprint, operation_fingerprint,
               request_snapshot, task_kind, subject_id, tutor_response_ordinal,
               status, state_version, stage, user_message, attempt_count,
               provider_snapshot, output_snapshot, failure_code, failure_message,
               failure_retryable, created_at_epoch_millis, updated_at_epoch_millis
        FROM model_task
        WHERE request_id = :requestId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findChoiceModelTask(requestId: String): ModelTaskEntity?

    @Query(
        """
        SELECT session_id, draft_id, draft_revision_number, created_at_epoch_millis
        FROM tutor_session
        WHERE session_id = :sessionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findChoiceTutorSession(sessionId: String): TutorSessionEntity?

    @Query(
        """
        SELECT draft_id, revision_number, basis_revision_number, subject, title,
               question_document_snapshot, document_fingerprint, author,
               created_at_epoch_millis
        FROM problem_draft_revision
        WHERE draft_id = :draftId
          AND revision_number = :revisionNumber
        LIMIT 1
        """,
    )
    protected abstract suspend fun findChoiceProblemRevision(
        draftId: String,
        revisionNumber: Int,
    ): ProblemDraftRevisionEntity?

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
          AND NOT EXISTS (
              SELECT 1
              FROM tutor_learning_evidence_finalization_receipt AS intent
              WHERE intent.evidence_request_id = :evidenceRequestId
          )
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
        val persistentCancellation = uniquePersistentCancellation(command)
        if (persistentCancellation != null) {
            return enforcePersistentCancellation(
                request = request,
                command = command,
                cancellation = persistentCancellation,
                nowEpochMillis = nowEpochMillis,
            )
        }
        if (request.status != TutorEvidenceRequestStatus.PENDING.name) {
            return replayFinalization(request, command, nowEpochMillis)
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

        val submittedClaim = checkNotNull(command.submission)
        val submission = when (command.kind) {
            TutorEvidenceRequestKind.CHOICE -> deriveChoiceSubmission(
                command = command,
                claim = submittedClaim,
            )
            else -> submittedClaim.also { claim ->
                requireSourceMatchesKind(claim.source, command.kind)
            }
        }
        if (
            submission.occurredAtEpochMillis < request.createdAtEpochMillis ||
            submission.occurredAtEpochMillis < turn.allocatedAtEpochMillis ||
            submission.occurredAtEpochMillis > nowEpochMillis
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
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

    private suspend fun uniquePersistentCancellation(
        command: FinalizeTutorEvidenceRequestCommand,
    ): TutorEvidenceCancellationEntity? {
        val cancellations = findPersistentEvidenceCancellations(
            learnerId = command.learnerId,
            evidenceRequestId = command.evidenceRequestId,
        )
        if (cancellations.size > 1) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        return cancellations.singleOrNull()
    }

    private suspend fun enforcePersistentCancellation(
        request: TutorEvidenceRequestEntity,
        command: FinalizeTutorEvidenceRequestCommand,
        cancellation: TutorEvidenceCancellationEntity,
        nowEpochMillis: Long,
    ): TutorEvidenceFinalizationResult {
        if (request.status == TutorEvidenceRequestStatus.SUBMITTED.name) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val existingFact = findSourceFactForEvidence(command.evidenceRequestId)
        if (request.terminalSourceFactId != null || existingFact != null) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        if (request.status == TutorEvidenceRequestStatus.CANCELLED.name) {
            return TutorEvidenceFinalizationResult(
                replayed = true,
                request = request.toModel(),
                anchor = null,
                sourceFact = null,
            )
        }
        if (request.status != TutorEvidenceRequestStatus.PENDING.name) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val cancellationFingerprint = cancellation.canonicalFingerprint()
        if (
            finalizeEvidenceCas(
                evidenceRequestId = command.evidenceRequestId,
                expectedStateVersion = request.stateVersion,
                terminalStatus = TutorEvidenceRequestStatus.CANCELLED.name,
                idempotencyKey = "$PERSISTENT_CANCELLATION_ID_PREFIX:$cancellationFingerprint",
                payloadFingerprint = cancellationFingerprint,
                sourceFactId = null,
                nowEpochMillis = maxOf(
                    request.createdAtEpochMillis,
                    minOf(cancellation.cancelledAtEpochMillis, nowEpochMillis),
                ),
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

    private suspend fun deriveChoiceSubmission(
        command: FinalizeTutorEvidenceRequestCommand,
        claim: com.tingyun.smartmistakebook.core.database.TutorEvidenceSubmission,
    ): com.tingyun.smartmistakebook.core.database.TutorEvidenceSubmission {
        val responses = findChoiceResponsesForEvidence(command.evidenceRequestId)
        if (responses.size != 1) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val response = responses.single()
        val responseSummary = response.canonicalChoiceSummary()
        val trustedScope = resolveTrustedChoiceScope(command, response)
        val responseFingerprint = response.canonicalChoiceFingerprint()
        val occurredAtEpochMillis = response.choiceSubmittedAtEpochMillis
            ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        return claim.copy(
            sourceFactId = canonicalChoiceSourceFactId(
                learnerId = command.learnerId,
                evidenceRequestId = command.evidenceRequestId,
                responseFingerprint = responseFingerprint,
            ),
            source = LearningObservationSource.TUTOR_CHOICE,
            factKind = if (response.selectionWasCorrect == true) {
                LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE
            } else {
                LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE
            },
            questionFingerprint = trustedScope.questionFingerprint,
            revisionFingerprint = trustedScope.revisionFingerprint,
            fingerprintVersion = trustedScope.fingerprintVersion,
            responseFingerprint = responseFingerprint,
            responseSummary = responseSummary,
            occurredAtEpochMillis = occurredAtEpochMillis,
            sourceVersion = CAPTURED_CHOICE_SOURCE_VERSION,
        )
    }

    private suspend fun resolveTrustedChoiceScope(
        command: FinalizeTutorEvidenceRequestCommand,
        response: TutorTurnResponseEntity,
    ): TrustedChoiceScope {
        val task = findChoiceModelTask(command.evidenceRequestId)
            ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        val request = runCatching { ModelTaskCodec.decodeRequest(task.requestSnapshot) }
            .getOrElse { throw TutorEvidenceConflictException(command.evidenceRequestId) }
        val output = task.outputSnapshot
            ?.let { snapshot ->
                runCatching { ModelTaskCodec.decodeOutput(snapshot) }
                    .getOrElse { throw TutorEvidenceConflictException(command.evidenceRequestId) }
            }
        val input = request.input as? TutorPlanInput
            ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        val planOutput = output as? TutorPlanOutput
            ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        val item = planOutput.plan.diagnosticItem
            ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        val selectedChoice = item.choices.singleOrNull { choice ->
            choice.id == response.selectedChoiceId
        } ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        if (
            task.status != ModelTaskStatus.SUCCEEDED.name ||
            request.requestId != command.evidenceRequestId ||
            input.sessionId != response.sessionId ||
            input.questionDocument.id != response.questionDocumentId ||
            input.draftRevisionNumber != response.revisionNumber ||
            input.cycleOrdinal != response.cycleOrdinal ||
            input.turnOrdinal != response.turnOrdinal ||
            input.subject != command.subject.name ||
            planOutput.sessionId != response.sessionId ||
            planOutput.questionDocumentId != response.questionDocumentId ||
            planOutput.draftRevisionNumber != response.revisionNumber ||
            planOutput.cycleOrdinal != response.cycleOrdinal ||
            planOutput.turnOrdinal != response.turnOrdinal ||
            planOutput.plan.interactionDirective != null ||
            response.diagnosticStemMarkdown != item.stemMarkdown ||
            response.selectedChoiceMarkdown != selectedChoice.markdown ||
            response.selectionWasCorrect != (selectedChoice.id == item.correctChoiceId) ||
            response.feedbackMarkdown != selectedChoice.feedbackMarkdown ||
            checkNotNull(response.choiceSubmittedAtEpochMillis) < task.updatedAtEpochMillis
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val session = findChoiceTutorSession(response.sessionId)
            ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        if (session.draftRevisionNumber != response.revisionNumber) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val revision = findChoiceProblemRevision(
            draftId = session.draftId,
            revisionNumber = session.draftRevisionNumber,
        ) ?: throw TutorEvidenceConflictException(command.evidenceRequestId)
        val capturedDocument = runCatching {
            CapturedQuestionDocumentCodec.decode(revision.questionDocumentSnapshot)
        }.getOrElse { throw TutorEvidenceConflictException(command.evidenceRequestId) }
        if (
            revision.subject != command.subject.name ||
            capturedDocument.document != input.questionDocument ||
            capturedDocument.document.id != response.questionDocumentId ||
            CapturedQuestionDocumentFingerprint.of(capturedDocument) != revision.documentFingerprint
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        val questionFingerprint =
            CapturedTutorProblemIdentity.questionFingerprint(session.draftId)
        val fingerprintVersion = CapturedTutorProblemIdentity.fingerprintVersion
        val expectedDirectiveFingerprint = canonicalChoiceDirectiveFingerprint(
            evidenceRequestId = command.evidenceRequestId,
            session = session,
            questionDocumentId = response.questionDocumentId,
            revisionFingerprint = revision.documentFingerprint,
            input = input,
            output = planOutput,
            item = item,
        )
        val expectedConversationId = opaqueId(
            CAPTURED_CHOICE_CONVERSATION_ID_DOMAIN,
            command.learnerId,
            response.sessionId,
            session.draftId,
            response.revisionNumber.toString(),
            response.questionDocumentId,
        )
        val expectedTurnReceiptId = opaqueId(
            CAPTURED_CHOICE_TURN_RECEIPT_ID_DOMAIN,
            command.learnerId,
            response.sessionId,
            session.draftId,
            response.revisionNumber.toString(),
            response.questionDocumentId,
            command.evidenceRequestId,
            response.cycleOrdinal.toString(),
            response.turnOrdinal.toString(),
            expectedDirectiveFingerprint,
        )
        val expectedAnchorId = opaqueId(
            CAPTURED_CHOICE_PROBLEM_ANCHOR_ID_DOMAIN,
            command.learnerId,
            command.subject.name,
            questionFingerprint,
            revision.documentFingerprint,
            fingerprintVersion,
        )
        if (
            command.directiveFingerprint != expectedDirectiveFingerprint ||
            command.conversationId != expectedConversationId ||
            command.turnReceiptId != expectedTurnReceiptId ||
            command.problemAnchorId != expectedAnchorId
        ) {
            throw TutorEvidenceConflictException(command.evidenceRequestId)
        }
        return TrustedChoiceScope(
            questionFingerprint = questionFingerprint,
            revisionFingerprint = revision.documentFingerprint,
            fingerprintVersion = fingerprintVersion,
        )
    }

    private suspend fun replayFinalization(
        request: TutorEvidenceRequestEntity,
        command: FinalizeTutorEvidenceRequestCommand,
        nowEpochMillis: Long,
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
            val submittedClaim = checkNotNull(command.submission)
            val submission = when (command.kind) {
                TutorEvidenceRequestKind.CHOICE -> deriveChoiceSubmission(
                    command = command,
                    claim = submittedClaim,
                )
                else -> submittedClaim.also { claim ->
                    requireSourceMatchesKind(claim.source, command.kind)
                }
            }
            if (submission.occurredAtEpochMillis > nowEpochMillis) {
                throw TutorEvidenceConflictException(command.evidenceRequestId)
            }
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
