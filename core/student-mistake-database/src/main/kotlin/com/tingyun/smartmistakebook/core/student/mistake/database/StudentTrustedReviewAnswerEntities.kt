package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

@Entity(
    tableName = "student_trusted_review_answer_rule",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["basis_revision_id"], unique = true),
        Index(value = ["problem_id"]),
        Index(value = ["learner_id", "basis_revision_id"], unique = true),
        Index(value = ["rule_canonical_fingerprint"], unique = true),
        Index(value = ["provenance_canonical_fingerprint"]),
    ],
)
internal data class StudentTrustedReviewAnswerRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "answer_rule_id")
    val answerRuleId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "question_generation")
    val questionGeneration: Long,
    @ColumnInfo(name = "question_version")
    val questionVersion: String,
    @ColumnInfo(name = "rule_kind")
    val ruleKind: String,
    @ColumnInfo(name = "accepted_values_wire")
    val acceptedValuesWire: String?,
    @ColumnInfo(name = "correct_values_wire")
    val correctValuesWire: String?,
    @ColumnInfo(name = "expected_numeric_value")
    val expectedNumericValue: String?,
    @ColumnInfo(name = "absolute_tolerance")
    val absoluteTolerance: String?,
    @ColumnInfo(name = "expected_unit")
    val expectedUnit: String?,
    @ColumnInfo(name = "answer_spec_version")
    val answerSpecVersion: String,
    @ColumnInfo(name = "provenance_kind")
    val provenanceKind: String,
    @ColumnInfo(name = "provenance_reference_id")
    val provenanceReferenceId: String,
    @ColumnInfo(name = "provenance_canonical_fingerprint")
    val provenanceCanonicalFingerprint: String,
    @ColumnInfo(name = "rule_canonical_fingerprint")
    val ruleCanonicalFingerprint: String,
    @ColumnInfo(name = "admitted_at_epoch_millis")
    val admittedAtEpochMillis: Long,
)

/**
 * Fail-closed ownership fence for one exact rendered review presentation.
 *
 * The review-session transaction creates this row before exposing the presentation. A trusted
 * answer lease may bind it once, but strong evidence remains disabled until the same live lease
 * records an unassisted attempt in the transaction that promotes this fence.
 */
@Entity(
    tableName = "student_trusted_review_presentation_fence",
    foreignKeys = [
        ForeignKey(
            entity = StudentReviewSessionEntity::class,
            parentColumns = ["session_id", "plan_id", "learner_id"],
            childColumns = ["session_id", "plan_id", "learner_id"],
        ),
        ForeignKey(
            entity = StudentReviewQueueItemEntity::class,
            parentColumns = ["queue_item_id", "plan_id", "learner_id"],
            childColumns = ["queue_item_id", "plan_id", "learner_id"],
        ),
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
        ),
        ForeignKey(
            entity = StudentPracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
        ),
    ],
    indices = [
        Index(value = ["fence_canonical_fingerprint"], unique = true),
        Index(value = ["session_id", "plan_id", "learner_id"]),
        Index(value = ["queue_item_id", "plan_id", "learner_id"]),
        Index(value = ["problem_id"]),
        Index(value = ["basis_revision_id"]),
        Index(value = ["practice_unit_id"]),
        Index(value = ["error_book_entry_id"]),
        Index(value = ["learner_id", "session_id", "presentation_id"], unique = true),
        Index(value = ["bound_lease_receipt_id"], unique = true),
        Index(value = ["bound_lease_canonical_fingerprint"], unique = true),
        Index(value = ["eligible_attempt_receipt_id"], unique = true),
    ],
)
internal data class StudentTrustedReviewPresentationFenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "fence_id")
    val fenceId: String,
    @ColumnInfo(name = "fence_canonical_fingerprint")
    val fenceCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String,
    val status: String,
    @ColumnInfo(name = "bound_lease_receipt_id")
    val boundLeaseReceiptId: String?,
    @ColumnInfo(name = "bound_lease_canonical_fingerprint")
    val boundLeaseCanonicalFingerprint: String?,
    @ColumnInfo(name = "eligible_attempt_receipt_id")
    val eligibleAttemptReceiptId: String?,
    @ColumnInfo(name = "eligible_at_epoch_millis")
    val eligibleAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "student_trusted_review_lease_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentTrustedReviewAnswerRuleEntity::class,
            parentColumns = ["answer_rule_id"],
            childColumns = ["answer_rule_id"],
        ),
        ForeignKey(
            entity = StudentReviewSessionEntity::class,
            parentColumns = ["session_id", "plan_id", "learner_id"],
            childColumns = ["session_id", "plan_id", "learner_id"],
        ),
        ForeignKey(
            entity = StudentReviewQueueItemEntity::class,
            parentColumns = ["queue_item_id", "plan_id", "learner_id"],
            childColumns = ["queue_item_id", "plan_id", "learner_id"],
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["basis_revision_id"],
        ),
    ],
    indices = [
        Index(value = ["lease_canonical_fingerprint"], unique = true),
        Index(value = ["answer_rule_id"]),
        Index(value = ["session_id", "plan_id", "learner_id"]),
        Index(value = ["queue_item_id", "plan_id", "learner_id"]),
        Index(value = ["basis_revision_id"]),
        Index(value = ["learner_id", "session_id", "presentation_id"]),
        Index(value = ["valid_through_epoch_millis"]),
    ],
)
internal data class StudentTrustedReviewLeaseReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "lease_receipt_id")
    val leaseReceiptId: String,
    @ColumnInfo(name = "lease_canonical_fingerprint")
    val leaseCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "expected_session_version")
    val expectedSessionVersion: Long,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "answer_rule_id")
    val answerRuleId: String,
    @ColumnInfo(name = "issued_at_epoch_millis")
    val issuedAtEpochMillis: Long,
    @ColumnInfo(name = "valid_through_epoch_millis")
    val validThroughEpochMillis: Long,
)

@Entity(
    tableName = "student_trusted_review_attempt_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentTrustedReviewLeaseReceiptEntity::class,
            parentColumns = ["lease_receipt_id"],
            childColumns = ["lease_receipt_id"],
        ),
        ForeignKey(
            entity = StudentReviewSessionEntity::class,
            parentColumns = ["session_id", "plan_id", "learner_id"],
            childColumns = ["session_id", "plan_id", "learner_id"],
        ),
        ForeignKey(
            entity = StudentReviewQueueItemEntity::class,
            parentColumns = ["queue_item_id", "plan_id", "learner_id"],
            childColumns = ["queue_item_id", "plan_id", "learner_id"],
        ),
    ],
    indices = [
        Index(value = ["lease_receipt_id"], unique = true),
        Index(
            value = ["session_id", "presentation_id", "attempt_ordinal"],
            unique = true,
        ),
        Index(value = ["session_id", "plan_id", "learner_id"]),
        Index(value = ["queue_item_id", "plan_id", "learner_id"]),
        Index(value = ["learner_id", "submitted_at_epoch_millis"]),
        Index(value = ["attempt_canonical_fingerprint"], unique = true),
        Index(value = ["submission_idempotency_key"], unique = true),
    ],
)
internal data class StudentTrustedReviewAttemptReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "attempt_receipt_id")
    val attemptReceiptId: String,
    @ColumnInfo(name = "attempt_canonical_fingerprint")
    val attemptCanonicalFingerprint: String,
    @ColumnInfo(name = "submission_idempotency_key")
    val submissionIdempotencyKey: String,
    @ColumnInfo(name = "lease_receipt_id")
    val leaseReceiptId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int,
    @ColumnInfo(name = "presentation_started_at_epoch_millis")
    val presentationStartedAtEpochMillis: Long,
    @ColumnInfo(name = "submitted_at_epoch_millis")
    val submittedAtEpochMillis: Long,
    @ColumnInfo(name = "elapsed_duration_millis")
    val elapsedDurationMillis: Long,
    @ColumnInfo(name = "hint_count")
    val hintCount: Int,
    @ColumnInfo(name = "first_hint_at_epoch_millis")
    val firstHintAtEpochMillis: Long?,
    @ColumnInfo(name = "last_hint_at_epoch_millis")
    val lastHintAtEpochMillis: Long?,
    @ColumnInfo(name = "answer_revealed_at_epoch_millis")
    val answerRevealedAtEpochMillis: Long?,
)

@Entity(
    tableName = "student_trusted_review_assistance_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentTrustedReviewLeaseReceiptEntity::class,
            parentColumns = ["lease_receipt_id"],
            childColumns = ["lease_receipt_id"],
        ),
        ForeignKey(
            entity = StudentReviewSessionEntity::class,
            parentColumns = ["session_id", "plan_id", "learner_id"],
            childColumns = ["session_id", "plan_id", "learner_id"],
        ),
        ForeignKey(
            entity = StudentReviewQueueItemEntity::class,
            parentColumns = ["queue_item_id", "plan_id", "learner_id"],
            childColumns = ["queue_item_id", "plan_id", "learner_id"],
        ),
    ],
    indices = [
        Index(value = ["lease_receipt_id"]),
        Index(value = ["session_id", "plan_id", "learner_id"]),
        Index(value = ["queue_item_id", "plan_id", "learner_id"]),
        Index(
            value = ["session_id", "presentation_id", "kind", "occurred_at_epoch_millis"],
        ),
        Index(value = ["assistance_canonical_fingerprint"], unique = true),
    ],
)
internal data class StudentTrustedReviewAssistanceReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "assistance_event_id")
    val assistanceEventId: String,
    @ColumnInfo(name = "assistance_canonical_fingerprint")
    val assistanceCanonicalFingerprint: String,
    @ColumnInfo(name = "lease_receipt_id")
    val leaseReceiptId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    val kind: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
)

internal enum class StudentTrustedReviewAnswerRuleKind {
    CHOICE,
    NUMERIC,
    VISUAL_TARGET,
}

internal enum class StudentTrustedReviewPresentationFenceStatus {
    PENDING,
    STRONG_EVIDENCE_ELIGIBLE,
}

internal fun newStudentTrustedReviewPresentationFence(
    learnerId: String,
    planId: String,
    sessionId: String,
    queueItem: StudentReviewQueueItemEntity,
    presentationId: String,
    problem: StudentProblemDocumentEntity,
    createdAtEpochMillis: Long,
): StudentTrustedReviewPresentationFenceEntity {
    check(
            queueItem.learnerId == learnerId &&
            queueItem.planId == planId &&
            problem.learnerId == learnerId &&
            problem.primaryPracticeUnitId == queueItem.practiceUnitId &&
            problem.currentRevisionId == queueItem.basisRevisionId &&
            problem.lifecycleState == StudentProblemLifecycleState.ACTIVE.name,
    ) {
        "Trusted review fence scope is not an exact active student problem presentation"
    }
    val errorBookEntryId = checkNotNull(problem.errorBookEntryId) {
        "Trusted review fence requires an exact error-book entry"
    }
    check(createdAtEpochMillis >= 0L) {
        "Trusted review fence creation time must not be negative"
    }
    val fingerprint =
        CanonicalSha256("student-trusted-review-presentation-fence-v1")
            .field("learnerId", learnerId)
            .field("planId", planId)
            .field("sessionId", sessionId)
            .field("queueItemId", queueItem.queueItemId)
            .field("presentationId", presentationId)
            .field("problemId", problem.problemId)
            .field("basisRevisionId", queueItem.basisRevisionId)
            .field("practiceUnitId", queueItem.practiceUnitId)
            .field("errorBookEntryId", errorBookEntryId)
            .field("createdAtEpochMillis", createdAtEpochMillis)
            .finish()
    return StudentTrustedReviewPresentationFenceEntity(
        fenceId = "trusted-review-fence:$fingerprint",
        fenceCanonicalFingerprint = fingerprint,
        learnerId = learnerId,
        planId = planId,
        sessionId = sessionId,
        queueItemId = queueItem.queueItemId,
        presentationId = presentationId,
        problemId = problem.problemId,
        basisRevisionId = queueItem.basisRevisionId,
        practiceUnitId = queueItem.practiceUnitId,
        errorBookEntryId = errorBookEntryId,
        status = StudentTrustedReviewPresentationFenceStatus.PENDING.name,
        boundLeaseReceiptId = null,
        boundLeaseCanonicalFingerprint = null,
        eligibleAttemptReceiptId = null,
        eligibleAtEpochMillis = null,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}
