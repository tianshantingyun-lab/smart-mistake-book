package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionEventEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionHeadEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionScopeEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentHostWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentPolicyEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorFreeResponseOutboxEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface CurrentTutorInteractionSessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPolicy(entity: TutorCurrentPolicyEntity): Long

    @Update
    suspend fun updatePolicy(entity: TutorCurrentPolicyEntity): Int

    @Query(
        """
        SELECT * FROM tutor_current_policy
        WHERE learner_id = :learnerId AND session_id = :sessionId
        """,
    )
    suspend fun readPolicy(
        learnerId: String,
        sessionId: String,
    ): TutorCurrentPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHostWork(entity: TutorCurrentHostWorkEntity): Long

    @Update
    suspend fun updateHostWork(entity: TutorCurrentHostWorkEntity): Int

    @Query(
        """
        SELECT * FROM tutor_current_host_work
        WHERE learner_id = :learnerId AND session_id = :sessionId
        """,
    )
    suspend fun readHostWork(
        learnerId: String,
        sessionId: String,
    ): TutorCurrentHostWorkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFreeResponseOutbox(entity: TutorFreeResponseOutboxEntity): Long

    @Query(
        """
        SELECT * FROM tutor_free_response_outbox
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND action_token = :actionToken
        """,
    )
    suspend fun readFreeResponseOutbox(
        learnerId: String,
        sessionId: String,
        actionToken: String,
    ): TutorFreeResponseOutboxEntity?

    @Query(
        """
        SELECT MIN(
            CASE
                WHEN status = 'IN_FLIGHT' AND (
                    lease_owner_id IS NULL OR
                    lease_generation_id IS NULL OR
                    lease_token IS NULL OR
                    lease_expires_at_epoch_millis IS NULL
                ) THEN 0
                ELSE discard_after_epoch_millis
            END
        )
        FROM tutor_free_response_outbox
        WHERE status IN ('NEEDS_DISPATCH', 'IN_FLIGHT')
        """,
    )
    suspend fun readNextPendingFreeResponseOutboxCutoff(): Long?

    @Query(
        """
        UPDATE tutor_free_response_outbox
        SET status = 'FAILED_CLOSED',
            encrypted_answer = NULL,
            nonce = NULL,
            lease_owner_id = NULL,
            lease_generation_id = NULL,
            lease_token = NULL,
            lease_expires_at_epoch_millis = NULL,
            updated_at_epoch_millis = CASE
                WHEN updated_at_epoch_millis > :capturedCutoffEpochMillis
                    THEN updated_at_epoch_millis
                ELSE :capturedCutoffEpochMillis
            END
        WHERE status IN ('NEEDS_DISPATCH', 'IN_FLIGHT')
          AND (
              discard_after_epoch_millis <= :capturedCutoffEpochMillis OR
              (status = 'IN_FLIGHT' AND (
                  lease_owner_id IS NULL OR
                  lease_generation_id IS NULL OR
                  lease_token IS NULL OR
                  lease_expires_at_epoch_millis IS NULL
              ))
          )
        """,
    )
    suspend fun failClosedPendingFreeResponseOutboxesThrough(
        capturedCutoffEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM tutor_free_response_outbox
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND status IN ('NEEDS_DISPATCH', 'IN_FLIGHT')
          AND (
              discard_after_epoch_millis <= :nowEpochMillis OR
              dispatch_attempt_count >= :maxDispatchAttempts OR
              (status = 'NEEDS_DISPATCH' AND
                  next_dispatch_at_epoch_millis <= :nowEpochMillis) OR
              (status = 'IN_FLIGHT' AND (
                  lease_expires_at_epoch_millis <= :nowEpochMillis OR
                  lease_owner_id IS NULL OR
                  lease_generation_id IS NULL OR
                  lease_token IS NULL OR
                  lease_expires_at_epoch_millis IS NULL OR
                  lease_generation_id != :leaseGenerationId
              ))
          )
        ORDER BY claimed_at_epoch_millis ASC, action_token ASC
        LIMIT 1
        """,
    )
    suspend fun readRecoverableFreeResponseOutbox(
        learnerId: String,
        sessionId: String,
        nowEpochMillis: Long,
        leaseGenerationId: String,
        maxDispatchAttempts: Int,
    ): TutorFreeResponseOutboxEntity?

    @Query(
        """
        UPDATE tutor_free_response_outbox
        SET status = 'IN_FLIGHT',
            lease_owner_id = :leaseOwnerId,
            lease_generation_id = :leaseGenerationId,
            lease_token = :leaseToken,
            lease_expires_at_epoch_millis = :leaseExpiresAtEpochMillis,
            dispatch_attempt_count = dispatch_attempt_count + 1,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND action_token = :actionToken
          AND payload_fingerprint = :expectedPayloadFingerprint
          AND discard_after_epoch_millis > :updatedAtEpochMillis
          AND dispatch_attempt_count < :maxDispatchAttempts
          AND (
              (status = 'NEEDS_DISPATCH' AND
                  next_dispatch_at_epoch_millis <= :updatedAtEpochMillis) OR
              (status = 'IN_FLIGHT' AND (
                  lease_expires_at_epoch_millis <= :updatedAtEpochMillis OR
                  lease_owner_id IS NULL OR
                  lease_generation_id IS NULL OR
                  lease_token IS NULL OR
                  lease_expires_at_epoch_millis IS NULL OR
                  lease_generation_id != :leaseGenerationId
              ))
          )
        """,
    )
    suspend fun acquireFreeResponseOutbox(
        learnerId: String,
        sessionId: String,
        actionToken: String,
        expectedPayloadFingerprint: String,
        leaseOwnerId: String,
        leaseGenerationId: String,
        leaseToken: String,
        leaseExpiresAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
        maxDispatchAttempts: Int,
    ): Int

    @Query(
        """
        UPDATE tutor_free_response_outbox
        SET status = 'NEEDS_DISPATCH',
            next_dispatch_at_epoch_millis = :nextDispatchAtEpochMillis,
            lease_owner_id = NULL,
            lease_generation_id = NULL,
            lease_token = NULL,
            lease_expires_at_epoch_millis = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND action_token = :actionToken
          AND status = 'IN_FLIGHT'
          AND lease_token = :leaseToken
        """,
    )
    suspend fun releaseFreeResponseOutbox(
        learnerId: String,
        sessionId: String,
        actionToken: String,
        leaseToken: String,
        nextDispatchAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_free_response_outbox
        SET status = 'COMPLETED',
            encrypted_answer = NULL,
            nonce = NULL,
            lease_owner_id = NULL,
            lease_generation_id = NULL,
            lease_token = NULL,
            lease_expires_at_epoch_millis = NULL,
            candidate_idempotency_key = :candidateIdempotencyKey,
            candidate_receipt_fingerprint = :candidateReceiptFingerprint,
            completed_at_epoch_millis = :completedAtEpochMillis,
            updated_at_epoch_millis = :completedAtEpochMillis
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND action_token = :actionToken
          AND status = 'IN_FLIGHT'
          AND lease_token = :leaseToken
        """,
    )
    suspend fun completeFreeResponseOutbox(
        learnerId: String,
        sessionId: String,
        actionToken: String,
        leaseToken: String,
        candidateIdempotencyKey: String,
        candidateReceiptFingerprint: String,
        completedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_free_response_outbox
        SET status = 'FAILED_CLOSED',
            encrypted_answer = NULL,
            nonce = NULL,
            lease_owner_id = NULL,
            lease_generation_id = NULL,
            lease_token = NULL,
            lease_expires_at_epoch_millis = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND action_token = :actionToken
          AND status != 'COMPLETED'
        """,
    )
    suspend fun failClosedFreeResponseOutbox(
        learnerId: String,
        sessionId: String,
        actionToken: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_free_response_outbox
        SET status = 'FAILED_CLOSED',
            encrypted_answer = NULL,
            nonce = NULL,
            lease_owner_id = NULL,
            lease_generation_id = NULL,
            lease_token = NULL,
            lease_expires_at_epoch_millis = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND action_token = :actionToken
          AND status = 'IN_FLIGHT'
          AND lease_token = :leaseToken
        """,
    )
    suspend fun failClosedLeasedFreeResponseOutbox(
        learnerId: String,
        sessionId: String,
        actionToken: String,
        leaseToken: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_free_response_outbox
        SET status = 'FAILED_CLOSED',
            encrypted_answer = NULL,
            nonce = NULL,
            lease_owner_id = NULL,
            lease_generation_id = NULL,
            lease_token = NULL,
            lease_expires_at_epoch_millis = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND session_id = :sessionId
          AND status NOT IN ('COMPLETED', 'FAILED_CLOSED')
        """,
    )
    suspend fun failClosedFreeResponseOutboxesForConversation(
        learnerId: String,
        sessionId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM tutor_current_host_work
        WHERE learner_id = :learnerId AND session_id = :sessionId
        """,
    )
    fun observeHostWork(
        learnerId: String,
        sessionId: String,
    ): Flow<TutorCurrentHostWorkEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScope(entity: TutorCurrentInteractionScopeEntity): Long

    @Query("SELECT * FROM tutor_current_interaction_scope WHERE scope_id = :scopeId")
    suspend fun readScope(scopeId: String): TutorCurrentInteractionScopeEntity?

    @Query(
        """
        UPDATE tutor_current_interaction_scope
        SET attempt_ordinal = :newAttemptOrdinal,
            hint_count = :newHintCount
        WHERE scope_id = :scopeId
          AND attempt_ordinal = :expectedAttemptOrdinal
          AND hint_count = :expectedHintCount
        """,
    )
    suspend fun advanceStudentInteractionState(
        scopeId: String,
        expectedAttemptOrdinal: Int,
        expectedHintCount: Int,
        newAttemptOrdinal: Int,
        newHintCount: Int,
    ): Int

    @Query("SELECT * FROM tutor_current_interaction_scope WHERE scope_id IN (:scopeIds)")
    suspend fun readScopes(scopeIds: List<String>): List<TutorCurrentInteractionScopeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHead(entity: TutorCurrentInteractionHeadEntity): Long

    @Query(
        """
        SELECT * FROM tutor_current_interaction_head
        WHERE learner_id = :learnerId AND conversation_id = :conversationId
        """,
    )
    suspend fun readHead(
        learnerId: String,
        conversationId: String,
    ): TutorCurrentInteractionHeadEntity?

    @Query(
        """
        UPDATE tutor_current_interaction_head
        SET current_scope_id = :newScopeId,
            state_version = 0,
            state_fingerprint = :newStateFingerprint,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND conversation_id = :conversationId
          AND current_scope_id = :expectedScopeId
          AND state_version = :expectedStateVersion
          AND state_fingerprint = :expectedStateFingerprint
        """,
    )
    suspend fun replaceHead(
        learnerId: String,
        conversationId: String,
        expectedScopeId: String,
        expectedStateVersion: Long,
        expectedStateFingerprint: String,
        newScopeId: String,
        newStateFingerprint: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE tutor_current_interaction_head
        SET state_version = :newStateVersion,
            state_fingerprint = :newStateFingerprint,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE learner_id = :learnerId
          AND conversation_id = :conversationId
          AND current_scope_id = :scopeId
          AND state_version = :expectedStateVersion
          AND state_fingerprint = :expectedStateFingerprint
        """,
    )
    suspend fun advanceHead(
        learnerId: String,
        conversationId: String,
        scopeId: String,
        expectedStateVersion: Long,
        expectedStateFingerprint: String,
        newStateVersion: Long,
        newStateFingerprint: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT scope.*
        FROM tutor_current_interaction_scope AS scope
        INNER JOIN tutor_current_interaction_head AS head
          ON head.current_scope_id = scope.scope_id
        INNER JOIN tutor_conversation AS conversation
          ON conversation.conversation_id = scope.authority_conversation_id
         AND conversation.learner_id = scope.learner_id
         AND conversation.generation = scope.authority_conversation_generation
        INNER JOIN tutor_turn_receipt AS turn_receipt
          ON turn_receipt.turn_receipt_id = scope.authority_turn_receipt_id
        LEFT JOIN tutor_current_host_work AS host_work
          ON host_work.learner_id = scope.learner_id
         AND host_work.session_id = scope.conversation_id
        WHERE head.learner_id = :learnerId AND head.conversation_id = :conversationId
          AND conversation.status = 'ACTIVE'
          AND conversation.state_version = scope.authority_conversation_state_version
          AND turn_receipt.conversation_state_version = scope.authority_conversation_state_version
          AND (
              host_work.session_id IS NULL OR (
                  host_work.status = 'ACTIVE'
                  AND host_work.active_scope_id = scope.scope_id
                  AND host_work.target_activation_fingerprint = scope.activation_fingerprint
                  AND host_work.authority_directive_fingerprint = turn_receipt.directive_fingerprint
                  AND host_work.constrained_tutor_content_fingerprint = scope.presentation_fingerprint
              )
          )
        """,
    )
    fun observeCurrentScope(
        learnerId: String,
        conversationId: String,
    ): Flow<TutorCurrentInteractionScopeEntity?>

    @Query(
        """
        SELECT event.*
        FROM tutor_current_interaction_event AS event
        INNER JOIN tutor_current_interaction_head AS head
          ON head.current_scope_id = event.scope_id
        WHERE head.learner_id = :learnerId AND head.conversation_id = :conversationId
        ORDER BY event.event_sequence ASC
        """,
    )
    fun observeCurrentEvents(
        learnerId: String,
        conversationId: String,
    ): Flow<List<TutorCurrentInteractionEventEntity>>

    @Query(
        """
        SELECT * FROM tutor_current_interaction_event
        WHERE learner_id = :learnerId AND conversation_id = :conversationId
        ORDER BY recorded_at_epoch_millis ASC, event_sequence ASC, event_id ASC
        """,
    )
    fun observeHistoryEvents(
        learnerId: String,
        conversationId: String,
    ): Flow<List<TutorCurrentInteractionEventEntity>>

    @Query(
        """
        SELECT * FROM tutor_current_interaction_event
        WHERE learner_id = :learnerId
          AND event_kind = 'ANSWER_EXPOSURE'
          AND model_task_request_id IN (:modelTaskRequestIds)
        ORDER BY recorded_at_epoch_millis ASC, event_id ASC
        """,
    )
    suspend fun readAnswerExposureEvents(
        learnerId: String,
        modelTaskRequestIds: Set<String>,
    ): List<TutorCurrentInteractionEventEntity>

    @Query(
        """
        SELECT * FROM tutor_current_interaction_event
        WHERE scope_id = :scopeId
        ORDER BY event_sequence ASC
        """,
    )
    suspend fun readEvents(scopeId: String): List<TutorCurrentInteractionEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(entity: TutorCurrentInteractionEventEntity): Long

    @Query(
        """
        SELECT * FROM tutor_current_interaction_event
        WHERE learner_id = :learnerId AND idempotency_key = :idempotencyKey
        """,
    )
    suspend fun readEventByIdempotency(
        learnerId: String,
        idempotencyKey: String,
    ): TutorCurrentInteractionEventEntity?

    @Query(
        """
        SELECT * FROM tutor_current_interaction_event
        WHERE learner_id = :learnerId
          AND event_kind = 'OPEN_RESPONSE_CANDIDATE_CLAIM'
          AND payload_fingerprint = :candidateScopeFingerprint
        LIMIT 1
        """,
    )
    suspend fun readOpenResponseClaimByScopeFingerprint(
        learnerId: String,
        candidateScopeFingerprint: String,
    ): TutorCurrentInteractionEventEntity?

    @Query(
        """
        SELECT * FROM tutor_conversation
        WHERE conversation_id = :conversationId
          AND learner_id = :learnerId
          AND generation = :generation
        """,
    )
    suspend fun readAuthorityConversation(
        learnerId: String,
        conversationId: String,
        generation: Long,
    ): TutorConversationEntity?

    @Query(
        """
        SELECT * FROM tutor_turn_receipt
        WHERE turn_receipt_id = :turnReceiptId AND learner_id = :learnerId
        """,
    )
    suspend fun readAuthorityTurn(
        learnerId: String,
        turnReceiptId: String,
    ): TutorTurnReceiptEntity?

    @Query(
        """
        SELECT request.*
        FROM tutor_evidence_request AS request
        WHERE request.evidence_request_id = :evidenceRequestId
          AND request.learner_id = :learnerId
          AND request.status = 'PENDING'
          AND NOT EXISTS (
              SELECT 1
              FROM tutor_learning_evidence_finalization_receipt AS finalization
              WHERE finalization.evidence_request_id = request.evidence_request_id
          )
        """,
    )
    suspend fun readPendingAuthorityEvidenceRequest(
        learnerId: String,
        evidenceRequestId: String,
    ): TutorEvidenceRequestEntity?
}
