package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "student_tutor_interaction_answer_certificate",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["problem_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["admission_idempotency_key"], unique = true),
        Index(value = ["owner_canonical_fingerprint"], unique = true),
        Index(value = ["provenance_receipt_canonical_fingerprint"], unique = true),
        Index(value = ["learner_id", "problem_revision_id"]),
        Index(value = ["session_id", "model_task_request_id"]),
        Index(value = ["certificate_family_canonical_fingerprint", "issued_at_epoch_millis"]),
        Index(value = ["presentation_canonical_fingerprint"]),
        Index(value = ["problem_id"]),
        Index(value = ["problem_revision_id"]),
    ],
)
internal data class TutorInteractionAnswerCertificateEntity(
    @PrimaryKey
    @ColumnInfo(name = "certificate_id")
    val certificateId: String,
    @ColumnInfo(name = "admission_idempotency_key")
    val admissionIdempotencyKey: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "problem_revision_number")
    val problemRevisionNumber: Int,
    @ColumnInfo(name = "question_document_fingerprint")
    val questionDocumentFingerprint: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "model_task_request_id")
    val modelTaskRequestId: String,
    @ColumnInfo(name = "cycle_ordinal")
    val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal")
    val turnOrdinal: Int,
    @ColumnInfo(name = "mode_version")
    val modeVersion: Long,
    @ColumnInfo(name = "learning_write_permission_version")
    val learningWritePermissionVersion: Long,
    @ColumnInfo(name = "interaction_kind")
    val interactionKind: String,
    @ColumnInfo(name = "presentation_canonical_fingerprint")
    val presentationCanonicalFingerprint: String,
    @ColumnInfo(name = "allowed_answer_ids_wire")
    val allowedAnswerIdsWire: String,
    /** Never selected by the answer-free reader. */
    @ColumnInfo(name = "correct_answer_ids_wire")
    val correctAnswerIdsWire: String,
    @ColumnInfo(name = "provenance_kind")
    val provenanceKind: String,
    /** Owner-only receipt material; never selected by the answer-free reader. */
    @ColumnInfo(name = "provenance_receipt_id")
    val provenanceReceiptId: String,
    @ColumnInfo(name = "provenance_receipt_canonical_fingerprint")
    val provenanceReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "provenance_receipt_schema_version")
    val provenanceReceiptSchemaVersion: String,
    @ColumnInfo(name = "certificate_schema_version")
    val certificateSchemaVersion: String,
    @ColumnInfo(name = "admission_policy_version")
    val admissionPolicyVersion: String,
    @ColumnInfo(name = "certificate_family_canonical_fingerprint")
    val certificateFamilyCanonicalFingerprint: String,
    @ColumnInfo(name = "presentation_answer_free_canonical_fingerprint")
    val presentationAnswerFreeCanonicalFingerprint: String,
    /** Owner commitment includes the correct rule and is never returned to readers. */
    @ColumnInfo(name = "owner_canonical_fingerprint")
    val ownerCanonicalFingerprint: String,
    /** Public receipt commitment excludes the correct rule and provenance receipt. */
    @ColumnInfo(name = "admission_receipt_canonical_fingerprint")
    val admissionReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "issued_at_epoch_millis")
    val issuedAtEpochMillis: Long,
    @ColumnInfo(name = "not_after_epoch_millis")
    val notAfterEpochMillis: Long,
)

@Entity(
    tableName = "student_tutor_interaction_answer_certificate_status_event",
    foreignKeys = [
        ForeignKey(
            entity = TutorInteractionAnswerCertificateEntity::class,
            parentColumns = ["certificate_id"],
            childColumns = ["certificate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["certificate_id", "status_generation"], unique = true),
        Index(value = ["status_canonical_fingerprint"], unique = true),
        Index(value = ["certificate_id", "status", "status_generation"]),
    ],
)
internal data class TutorInteractionAnswerCertificateStatusEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "status_event_id")
    val statusEventId: String,
    @ColumnInfo(name = "certificate_id")
    val certificateId: String,
    @ColumnInfo(name = "status_generation")
    val statusGeneration: Long,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "reason_canonical_fingerprint")
    val reasonCanonicalFingerprint: String,
    @ColumnInfo(name = "status_policy_version")
    val statusPolicyVersion: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "status_canonical_fingerprint")
    val statusCanonicalFingerprint: String,
)

@Entity(
    tableName = "student_tutor_interaction_answer_certificate_lease_receipt",
    foreignKeys = [
        ForeignKey(
            entity = TutorInteractionAnswerCertificateEntity::class,
            parentColumns = ["certificate_id"],
            childColumns = ["certificate_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["lease_canonical_fingerprint"], unique = true),
        Index(value = ["certificate_id", "certificate_status_generation"]),
        Index(value = ["learner_id", "issued_at_epoch_millis"]),
    ],
)
internal data class TutorInteractionAnswerCertificateLeaseReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "lease_receipt_id")
    val leaseReceiptId: String,
    @ColumnInfo(name = "certificate_id")
    val certificateId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "certificate_status_generation")
    val certificateStatusGeneration: Long,
    @ColumnInfo(name = "presentation_answer_free_canonical_fingerprint")
    val presentationAnswerFreeCanonicalFingerprint: String,
    @ColumnInfo(name = "issued_at_epoch_millis")
    val issuedAtEpochMillis: Long,
    @ColumnInfo(name = "not_after_epoch_millis")
    val notAfterEpochMillis: Long,
    @ColumnInfo(name = "lease_canonical_fingerprint")
    val leaseCanonicalFingerprint: String,
)

@Entity(
    tableName = "student_tutor_interaction_answer_evaluation_receipt",
    foreignKeys = [
        ForeignKey(
            entity = TutorInteractionAnswerCertificateLeaseReceiptEntity::class,
            parentColumns = ["lease_receipt_id"],
            childColumns = ["lease_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["lease_receipt_id"], unique = true),
        Index(value = ["evaluation_canonical_fingerprint"], unique = true),
        Index(value = ["learner_id", "evaluated_at_epoch_millis"]),
    ],
)
internal data class TutorInteractionAnswerEvaluationReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "evaluation_receipt_id")
    val evaluationReceiptId: String,
    @ColumnInfo(name = "lease_receipt_id")
    val leaseReceiptId: String,
    @ColumnInfo(name = "certificate_id")
    val certificateId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "response_canonical_fingerprint")
    val responseCanonicalFingerprint: String,
    @ColumnInfo(name = "response_was_correct")
    val responseWasCorrect: Boolean,
    @ColumnInfo(name = "evaluated_at_epoch_millis")
    val evaluatedAtEpochMillis: Long,
    @ColumnInfo(name = "evaluation_canonical_fingerprint")
    val evaluationCanonicalFingerprint: String,
)

internal data class TutorInteractionAnswerCertificatePrivateRow(
    @ColumnInfo(name = "certificate_id") val certificateId: String,
    @ColumnInfo(name = "learner_id") val learnerId: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "problem_id") val problemId: String,
    @ColumnInfo(name = "practice_unit_id") val practiceUnitId: String,
    @ColumnInfo(name = "problem_revision_id") val problemRevisionId: String,
    @ColumnInfo(name = "problem_revision_number") val problemRevisionNumber: Int,
    @ColumnInfo(name = "question_document_fingerprint") val questionDocumentFingerprint: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "model_task_request_id") val modelTaskRequestId: String,
    @ColumnInfo(name = "cycle_ordinal") val cycleOrdinal: Int,
    @ColumnInfo(name = "turn_ordinal") val turnOrdinal: Int,
    @ColumnInfo(name = "mode_version") val modeVersion: Long,
    @ColumnInfo(name = "learning_write_permission_version") val learningWritePermissionVersion: Long,
    @ColumnInfo(name = "interaction_kind") val interactionKind: String,
    @ColumnInfo(name = "presentation_canonical_fingerprint") val presentationCanonicalFingerprint: String,
    @ColumnInfo(name = "allowed_answer_ids_wire") val allowedAnswerIdsWire: String,
    @ColumnInfo(name = "correct_answer_ids_wire") val correctAnswerIdsWire: String,
    @ColumnInfo(name = "certificate_schema_version") val certificateSchemaVersion: String,
    @ColumnInfo(name = "admission_policy_version") val admissionPolicyVersion: String,
    @ColumnInfo(name = "admission_receipt_canonical_fingerprint") val admissionReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "issued_at_epoch_millis") val issuedAtEpochMillis: Long,
    @ColumnInfo(name = "not_after_epoch_millis") val notAfterEpochMillis: Long,
    @ColumnInfo(name = "status_generation") val statusGeneration: Long,
)

internal data class TutorInteractionAnswerCertificateStatusHeadRow(
    @ColumnInfo(name = "certificate_id") val certificateId: String,
    @ColumnInfo(name = "status_generation") val statusGeneration: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "not_after_epoch_millis") val notAfterEpochMillis: Long,
)
