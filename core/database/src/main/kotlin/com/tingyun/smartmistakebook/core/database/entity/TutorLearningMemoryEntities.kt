package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "tutor_conversation",
    indices = [
        Index(value = ["learner_id", "status", "updated_at_epoch_millis"]),
        Index(value = ["learner_id", "create_idempotency_key"], unique = true),
        Index(value = ["conversation_id", "learner_id", "generation"], unique = true),
    ],
)
internal data class TutorConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val generation: Long,
    val status: String,
    @ColumnInfo(name = "next_turn_ordinal")
    val nextTurnOrdinal: Int,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "create_idempotency_key")
    val createIdempotencyKey: String,
    @ColumnInfo(name = "create_payload_fingerprint")
    val createPayloadFingerprint: String,
    @ColumnInfo(name = "archive_idempotency_key")
    val archiveIdempotencyKey: String?,
    @ColumnInfo(name = "archive_payload_fingerprint")
    val archivePayloadFingerprint: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "archived_at_epoch_millis")
    val archivedAtEpochMillis: Long?,
)

@Entity(
    tableName = "tutor_turn_receipt",
    foreignKeys = [
        ForeignKey(
            entity = TutorConversationEntity::class,
            parentColumns = ["conversation_id", "learner_id", "generation"],
            childColumns = ["conversation_id", "learner_id", "conversation_generation"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "learner_id", "conversation_generation"]),
        Index(
            value = ["conversation_id", "conversation_generation", "turn_ordinal"],
            unique = true,
        ),
        Index(
            value = ["conversation_id", "conversation_generation", "client_turn_id"],
            unique = true,
        ),
        Index(value = ["learner_id", "subject", "occurred_at_epoch_millis"]),
    ],
)
internal data class TutorTurnReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "turn_receipt_id")
    val turnReceiptId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long,
    @ColumnInfo(name = "conversation_state_version")
    val conversationStateVersion: Long,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "client_turn_id")
    val clientTurnId: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    val subject: String,
    @ColumnInfo(name = "problem_anchor_id")
    val problemAnchorId: String?,
    @ColumnInfo(name = "request_version")
    val requestVersion: Long,
    @ColumnInfo(name = "explanation_mode")
    val explanationMode: String,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "directive_fingerprint")
    val directiveFingerprint: String,
    @ColumnInfo(name = "student_message_fingerprint")
    val studentMessageFingerprint: String,
    @ColumnInfo(name = "student_message_summary")
    val studentMessageSummary: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "allocated_at_epoch_millis")
    val allocatedAtEpochMillis: Long,
)

@Entity(
    tableName = "tutor_evidence_request",
    foreignKeys = [
        ForeignKey(
            entity = TutorConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorTurnReceiptEntity::class,
            parentColumns = ["turn_receipt_id"],
            childColumns = ["turn_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["turn_receipt_id"]),
        Index(
            value = ["conversation_id", "conversation_generation", "prepare_idempotency_key"],
            unique = true,
        ),
        Index(value = ["learner_id", "status", "created_at_epoch_millis"]),
        Index(value = ["problem_anchor_id"]),
    ],
)
internal data class TutorEvidenceRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long,
    @ColumnInfo(name = "conversation_state_version")
    val conversationStateVersion: Long,
    @ColumnInfo(name = "turn_receipt_id")
    val turnReceiptId: String,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    val subject: String,
    @ColumnInfo(name = "problem_anchor_id")
    val problemAnchorId: String,
    val kind: String,
    @ColumnInfo(name = "request_version")
    val requestVersion: Long,
    @ColumnInfo(name = "explanation_mode")
    val explanationMode: String,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "directive_fingerprint")
    val directiveFingerprint: String,
    val status: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "prepare_idempotency_key")
    val prepareIdempotencyKey: String,
    @ColumnInfo(name = "prepare_payload_fingerprint")
    val preparePayloadFingerprint: String,
    @ColumnInfo(name = "terminal_idempotency_key")
    val terminalIdempotencyKey: String?,
    @ColumnInfo(name = "terminal_payload_fingerprint")
    val terminalPayloadFingerprint: String?,
    @ColumnInfo(name = "terminal_source_fact_id")
    val terminalSourceFactId: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "resolved_at_epoch_millis")
    val resolvedAtEpochMillis: Long?,
)

@Entity(
    tableName = "learning_problem_anchor",
    indices = [
        Index(
            value = [
                "learner_id",
                "subject",
                "question_fingerprint",
                "revision_fingerprint",
                "fingerprint_version",
            ],
            unique = true,
        ),
        Index(value = ["anchor_id", "learner_id", "subject"], unique = true),
    ],
)
internal data class LearningProblemAnchorEntity(
    @PrimaryKey
    @ColumnInfo(name = "anchor_id")
    val anchorId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "question_fingerprint")
    val questionFingerprint: String,
    @ColumnInfo(name = "revision_fingerprint")
    val revisionFingerprint: String,
    @ColumnInfo(name = "fingerprint_version")
    val fingerprintVersion: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "learning_observation_source_fact",
    foreignKeys = [
        ForeignKey(
            entity = LearningProblemAnchorEntity::class,
            parentColumns = ["anchor_id", "learner_id", "subject"],
            childColumns = ["anchor_id", "learner_id", "subject"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorTurnReceiptEntity::class,
            parentColumns = ["turn_receipt_id"],
            childColumns = ["turn_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TutorEvidenceRequestEntity::class,
            parentColumns = ["evidence_request_id"],
            childColumns = ["evidence_request_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["anchor_id", "learner_id", "subject"]),
        Index(value = ["conversation_id"]),
        Index(value = ["turn_receipt_id"]),
        Index(value = ["evidence_request_id"], unique = true),
        Index(value = ["learner_id", "subject", "occurred_at_epoch_millis"]),
        Index(value = ["payload_fingerprint"]),
    ],
)
internal data class LearningObservationSourceFactEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val source: String,
    @ColumnInfo(name = "fact_kind")
    val factKind: String,
    @ColumnInfo(name = "anchor_id")
    val anchorId: String,
    val subject: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String?,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long?,
    @ColumnInfo(name = "turn_receipt_id")
    val turnReceiptId: String?,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    @ColumnInfo(name = "response_fingerprint")
    val responseFingerprint: String,
    @ColumnInfo(name = "response_summary")
    val responseSummary: String,
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "source_version")
    val sourceVersion: String,
)
