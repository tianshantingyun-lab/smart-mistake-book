package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * Immutable owner-issued identity for the question that is currently visible in Tutor.
 *
 * This is session coordination data. It is deliberately separate from the student mistake,
 * learner-mastery, and teaching-knowledge stores. The authority columns bind the visible scope to
 * the v44 conversation/turn ledger; feature code cannot create an unbacked current question.
 */
@Entity(
    tableName = "tutor_current_interaction_scope",
    primaryKeys = ["scope_id"],
    foreignKeys = [
        ForeignKey(
            entity = TutorConversationEntity::class,
            parentColumns = ["conversation_id", "learner_id", "generation"],
            childColumns = [
                "authority_conversation_id",
                "learner_id",
                "authority_conversation_generation",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorTurnReceiptEntity::class,
            parentColumns = ["turn_receipt_id"],
            childColumns = ["authority_turn_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "authority_conversation_id",
                "learner_id",
                "authority_conversation_generation",
            ],
        ),
        Index(value = ["authority_turn_receipt_id"]),
        Index(
            value = [
                "learner_id",
                "conversation_id",
                "conversation_generation",
                "question_document_id",
                "question_revision_number",
            ],
        ),
        Index(value = ["activation_fingerprint"], unique = true),
    ],
)
internal data class TutorCurrentInteractionScopeEntity(
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long,
    @ColumnInfo(name = "conversation_state_version")
    val conversationStateVersion: Long,
    @ColumnInfo(name = "authority_conversation_id")
    val authorityConversationId: String,
    @ColumnInfo(name = "authority_conversation_generation")
    val authorityConversationGeneration: Long,
    @ColumnInfo(name = "authority_conversation_state_version")
    val authorityConversationStateVersion: Long,
    @ColumnInfo(name = "authority_turn_receipt_id")
    val authorityTurnReceiptId: String,
    @ColumnInfo(name = "authority_turn_ordinal")
    val authorityTurnOrdinal: Int,
    @ColumnInfo(name = "authority_request_version")
    val authorityRequestVersion: Long,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "question_revision_number")
    val questionRevisionNumber: Int,
    @ColumnInfo(name = "question_document_snapshot")
    val questionDocumentSnapshot: String,
    @ColumnInfo(name = "question_fingerprint")
    val questionFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "problem_anchor_id")
    val problemAnchorId: String,
    @ColumnInfo(name = "explanation_mode")
    val explanationMode: String,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "turn_reference_id")
    val turnReferenceId: String,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "turn_generation")
    val turnGeneration: Long,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo(name = "hint_count")
    val hintCount: Int,
    @ColumnInfo(name = "answer_was_revealed")
    val answerWasRevealed: Boolean,
    @ColumnInfo(name = "request_version")
    val requestVersion: Long,
    @ColumnInfo(name = "learning_write_permission_version")
    val learningWritePermissionVersion: Long,
    @ColumnInfo(name = "presentation_fingerprint")
    val presentationFingerprint: String,
    @ColumnInfo(name = "problem_fingerprint")
    val problemFingerprint: String,
    @ColumnInfo(name = "problem_family_fingerprint")
    val problemFamilyFingerprint: String,
    @ColumnInfo(name = "attribution_policy_version")
    val attributionPolicyVersion: String,
    @ColumnInfo(name = "response_policy_version")
    val responsePolicyVersion: String,
    @ColumnInfo(name = "rubric_canonical_fingerprint")
    val rubricCanonicalFingerprint: String,
    @ColumnInfo(name = "knowledge_authority_fingerprint")
    val knowledgeAuthorityFingerprint: String,
    val evaluator: String,
    @ColumnInfo(name = "evaluator_policy_fingerprint")
    val evaluatorPolicyFingerprint: String,
    @ColumnInfo(name = "activation_fingerprint")
    val activationFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "tutor_current_interaction_head",
    primaryKeys = ["learner_id", "conversation_id"],
    foreignKeys = [
        ForeignKey(
            entity = TutorCurrentInteractionScopeEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["current_scope_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["current_scope_id"], unique = true)],
)
internal data class TutorCurrentInteractionHeadEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "current_scope_id")
    val currentScopeId: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "state_fingerprint")
    val stateFingerprint: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/** Durable UI-owned policy epoch. It intentionally exists before any Host activation work. */
@Entity(
    tableName = "tutor_current_policy",
    primaryKeys = ["learner_id", "session_id"],
    indices = [Index(value = ["updated_at_epoch_millis"])],
)
internal data class TutorCurrentPolicyEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "explanation_mode")
    val explanationMode: String,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "learning_writes_allowed")
    val learningWritesAllowed: Boolean,
    @ColumnInfo(name = "learning_write_permission_version")
    val learningWritePermissionVersion: Long,
    @ColumnInfo(name = "visual_intent")
    val visualIntent: String,
    @ColumnInfo(name = "visual_intent_version")
    val visualIntentVersion: Long,
    @ColumnInfo(name = "state_fingerprint")
    val stateFingerprint: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/**
 * One recoverable activation saga per learner and confirmed Tutor session.
 *
 * The row contains only authority references and canonical fingerprints. Knowledge proofs and
 * evaluator material remain process-local and must be independently reverified on every resume.
 */
@Entity(
    tableName = "tutor_current_host_work",
    primaryKeys = ["learner_id", "session_id"],
    foreignKeys = [
        ForeignKey(
            entity = TutorConversationEntity::class,
            parentColumns = ["conversation_id", "learner_id", "generation"],
            childColumns = [
                "authority_conversation_id",
                "learner_id",
                "authority_conversation_generation",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorTurnReceiptEntity::class,
            parentColumns = ["turn_receipt_id"],
            childColumns = ["authority_turn_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorCurrentInteractionScopeEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["active_scope_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["work_id"], unique = true),
        Index(value = ["learner_id", "model_task_request_id"], unique = true),
        Index(
            value = [
                "authority_conversation_id",
                "learner_id",
                "authority_conversation_generation",
            ],
        ),
        Index(value = ["authority_turn_receipt_id"]),
        Index(value = ["active_scope_id"]),
        Index(value = ["status", "updated_at_epoch_millis"]),
    ],
)
internal data class TutorCurrentHostWorkEntity(
    @ColumnInfo(name = "work_id")
    val workId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "question_revision_number")
    val questionRevisionNumber: Int,
    val subject: String,
    @ColumnInfo(name = "authority_conversation_id")
    val authorityConversationId: String,
    @ColumnInfo(name = "authority_conversation_generation")
    val authorityConversationGeneration: Long,
    @ColumnInfo(name = "authority_conversation_state_version")
    val authorityConversationStateVersion: Long,
    @ColumnInfo(name = "authority_turn_receipt_id")
    val authorityTurnReceiptId: String,
    @ColumnInfo(name = "authority_turn_ordinal")
    val authorityTurnOrdinal: Int,
    @ColumnInfo(name = "authority_request_version")
    val authorityRequestVersion: Long,
    @ColumnInfo(name = "authority_directive_fingerprint")
    val authorityDirectiveFingerprint: String,
    @ColumnInfo(name = "model_task_request_id")
    val modelTaskRequestId: String,
    @ColumnInfo(name = "model_task_request_fingerprint")
    val modelTaskRequestFingerprint: String,
    @ColumnInfo(name = "problem_anchor_id")
    val problemAnchorId: String,
    @ColumnInfo(name = "explanation_mode")
    val explanationMode: String,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "learning_writes_allowed")
    val learningWritesAllowed: Boolean,
    @ColumnInfo(name = "learning_write_permission_version")
    val learningWritePermissionVersion: Long,
    @ColumnInfo(name = "visual_intent")
    val visualIntent: String,
    @ColumnInfo(name = "visual_intent_version")
    val visualIntentVersion: Long,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo(name = "request_version")
    val requestVersion: Long,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    @ColumnInfo(name = "pending_interaction_kind")
    val pendingInteractionKind: String?,
    @ColumnInfo(name = "target_scope_id")
    val targetScopeId: String,
    @ColumnInfo(name = "target_activation_fingerprint")
    val targetActivationFingerprint: String,
    @ColumnInfo(name = "constrained_tutor_content_fingerprint")
    val constrainedTutorContentFingerprint: String,
    @ColumnInfo(name = "active_scope_id")
    val activeScopeId: String?,
    @ColumnInfo(name = "presentation_token")
    val presentationToken: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    val status: String,
    @ColumnInfo(name = "revocation_reason")
    val revocationReason: String?,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "state_fingerprint")
    val stateFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/**
 * Conversation-scoped encrypted outbox. Raw answers never enter Host work, interaction events,
 * model memory, or learner mastery.
 */
@Entity(
    tableName = "tutor_free_response_outbox",
    primaryKeys = ["learner_id", "action_token"],
    foreignKeys = [
        ForeignKey(
            entity = TutorConversationEntity::class,
            parentColumns = ["conversation_id", "learner_id", "generation"],
            childColumns = [
                "authority_conversation_id",
                "learner_id",
                "conversation_generation",
            ],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = [
                "authority_conversation_id",
                "learner_id",
                "conversation_generation",
            ],
        ),
        Index(
            value = [
                "learner_id",
                "session_id",
                "status",
                "next_dispatch_at_epoch_millis",
                "lease_expires_at_epoch_millis",
            ],
        ),
    ],
)
internal data class TutorFreeResponseOutboxEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "authority_conversation_id")
    val authorityConversationId: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long,
    @ColumnInfo(name = "action_token")
    val actionToken: String,
    @ColumnInfo(name = "action_expires_at_epoch_millis")
    val actionExpiresAtEpochMillis: Long,
    @ColumnInfo(name = "work_id")
    val workId: String,
    @ColumnInfo(name = "work_state_version")
    val workStateVersion: Long,
    @ColumnInfo(name = "work_state_fingerprint")
    val workStateFingerprint: String,
    @ColumnInfo(name = "presentation_token")
    val presentationToken: String,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String,
    @ColumnInfo(name = "answer_binding")
    val answerBinding: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    @ColumnInfo(name = "canonical_occurred_at_epoch_millis")
    val canonicalOccurredAtEpochMillis: Long,
    val status: String,
    @ColumnInfo(name = "encrypted_answer")
    val encryptedAnswer: ByteArray?,
    val nonce: ByteArray?,
    @ColumnInfo(name = "key_version")
    val keyVersion: Int,
    @ColumnInfo(name = "dispatch_attempt_count")
    val dispatchAttemptCount: Int,
    @ColumnInfo(name = "next_dispatch_at_epoch_millis")
    val nextDispatchAtEpochMillis: Long,
    @ColumnInfo(name = "discard_after_epoch_millis")
    val discardAfterEpochMillis: Long,
    @ColumnInfo(name = "lease_owner_id")
    val leaseOwnerId: String?,
    @ColumnInfo(name = "lease_generation_id")
    val leaseGenerationId: String?,
    @ColumnInfo(name = "lease_token")
    val leaseToken: String?,
    @ColumnInfo(name = "lease_expires_at_epoch_millis")
    val leaseExpiresAtEpochMillis: Long?,
    @ColumnInfo(name = "candidate_idempotency_key")
    val candidateIdempotencyKey: String?,
    @ColumnInfo(name = "candidate_receipt_fingerprint")
    val candidateReceiptFingerprint: String?,
    @ColumnInfo(name = "claimed_at_epoch_millis")
    val claimedAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

/** Append-only current-session event. Nullable columns are validated by event kind at the port. */
@Entity(
    tableName = "tutor_current_interaction_event",
    primaryKeys = ["learner_id", "event_id"],
    foreignKeys = [
        ForeignKey(
            entity = TutorCurrentInteractionScopeEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["scope_id", "event_sequence"], unique = true),
        Index(value = ["learner_id", "idempotency_key"], unique = true),
        Index(value = ["learner_id", "conversation_id", "recorded_at_epoch_millis"]),
        Index(value = ["authorization_request_id"]),
    ],
)
internal data class TutorCurrentInteractionEventEntity(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long,
    @ColumnInfo(name = "question_document_id")
    val questionDocumentId: String,
    @ColumnInfo(name = "question_revision_number")
    val questionRevisionNumber: Int,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "turn_generation")
    val turnGeneration: Long,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "committed_state_fingerprint")
    val committedStateFingerprint: String,
    @ColumnInfo(name = "event_kind")
    val eventKind: String,
    @ColumnInfo(name = "authorization_purpose")
    val authorizationPurpose: String,
    @ColumnInfo(name = "authorization_request_id")
    val authorizationRequestId: String?,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "request_version")
    val requestVersion: Long,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
    @ColumnInfo(name = "diagnostic_stem_markdown")
    val diagnosticStemMarkdown: String?,
    @ColumnInfo(name = "selected_choice_id")
    val selectedChoiceId: String?,
    @ColumnInfo(name = "selected_choice_markdown")
    val selectedChoiceMarkdown: String?,
    @ColumnInfo(name = "selection_was_correct")
    val selectionWasCorrect: Boolean?,
    @ColumnInfo(name = "feedback_markdown")
    val feedbackMarkdown: String?,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    @ColumnInfo(name = "requested_move")
    val requestedMove: String?,
    @ColumnInfo(name = "solution_revealed")
    val solutionRevealed: Boolean,
    @ColumnInfo(name = "surface_kind")
    val surfaceKind: String?,
    @ColumnInfo(name = "model_task_request_id")
    val modelTaskRequestId: String?,
    @ColumnInfo(name = "response_ordinal")
    val responseOrdinal: Int?,
    @ColumnInfo(name = "scene_source_kind")
    val sceneSourceKind: String?,
    @ColumnInfo(name = "scene_task_request_id")
    val sceneTaskRequestId: String?,
    @ColumnInfo(name = "scene_id")
    val sceneId: String?,
    @ColumnInfo(name = "scene_fingerprint")
    val sceneFingerprint: String?,
    @ColumnInfo(name = "hit_proof_id")
    val hitProofId: String?,
    @ColumnInfo(name = "panel_id")
    val panelId: String?,
    @ColumnInfo(name = "frame_fingerprint")
    val frameFingerprint: String?,
    @ColumnInfo(name = "step_index")
    val stepIndex: Int?,
    @ColumnInfo(name = "selected_target_id")
    val selectedTargetId: String?,
    @ColumnInfo(name = "target_revision_ref")
    val targetRevisionRef: String?,
    @ColumnInfo(name = "target_practice_ref")
    val targetPracticeRef: String?,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String?,
)
