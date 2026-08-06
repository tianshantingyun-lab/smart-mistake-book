package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "student_review_session",
    foreignKeys = [
        ForeignKey(
            entity = StudentReviewPlanEntity::class,
            parentColumns = ["plan_id", "learner_id"],
            childColumns = ["plan_id", "learner_id"],
        ),
        ForeignKey(
            entity = StudentReviewQueueItemEntity::class,
            parentColumns = ["queue_item_id", "plan_id", "learner_id"],
            childColumns = ["current_queue_item_id", "plan_id", "learner_id"],
        ),
    ],
    indices = [
        Index(value = ["session_canonical_fingerprint"], unique = true),
        Index(value = ["plan_id"]),
        Index(value = ["plan_id", "learner_id"]),
        Index(value = ["learner_id", "state", "updated_at_epoch_millis"]),
        Index(value = ["learner_id", "plan_id", "updated_at_epoch_millis"]),
        Index(value = ["active_learner_id"], unique = true),
        Index(value = ["current_queue_item_id"]),
        Index(value = ["current_queue_item_id", "plan_id", "learner_id"]),
        Index(value = ["session_id", "plan_id", "learner_id"], unique = true),
    ],
)
internal data class StudentReviewSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "session_canonical_fingerprint")
    val sessionCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "active_learner_id")
    val activeLearnerId: String?,
    val state: String,
    @ColumnInfo(name = "session_version")
    val sessionVersion: Long,
    @ColumnInfo(name = "current_queue_item_id")
    val currentQueueItemId: String?,
    @ColumnInfo(name = "current_presentation_id")
    val currentPresentationId: String?,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
)

@Entity(
    tableName = "student_review_transition_receipt",
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
    ],
    indices = [
        Index(value = ["transition_canonical_fingerprint"], unique = true),
        Index(value = ["session_id", "resulting_session_version"], unique = true),
        Index(value = ["queue_item_id"], unique = true),
        Index(value = ["session_id", "plan_id", "learner_id"]),
        Index(value = ["queue_item_id", "plan_id", "learner_id"]),
        Index(value = ["learner_id", "occurred_at_epoch_millis"]),
        Index(value = ["outbox_event_id"], unique = true),
    ],
)
internal data class StudentReviewTransitionReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "transition_id")
    val transitionId: String,
    @ColumnInfo(name = "transition_canonical_fingerprint")
    val transitionCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "queue_item_id")
    val queueItemId: String,
    @ColumnInfo(name = "action_kind")
    val actionKind: String,
    @ColumnInfo(name = "expected_session_version")
    val expectedSessionVersion: Long,
    @ColumnInfo(name = "resulting_session_version")
    val resultingSessionVersion: Long,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "observation_id")
    val observationId: String?,
    @ColumnInfo(name = "submission_id")
    val submissionId: String?,
    @ColumnInfo(name = "response_form")
    val responseForm: String?,
    @ColumnInfo(name = "response_canonical_fingerprint")
    val responseCanonicalFingerprint: String?,
    @ColumnInfo(name = "verification_outcome")
    val verificationOutcome: String?,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int?,
    @ColumnInfo(name = "hint_count")
    val hintCount: Int?,
    @ColumnInfo(name = "answer_was_revealed")
    val answerWasRevealed: Boolean?,
    @ColumnInfo(name = "verification_policy_version")
    val verificationPolicyVersion: String?,
    @ColumnInfo(name = "elapsed_duration_millis")
    val elapsedDurationMillis: Long?,
    @ColumnInfo(name = "next_available_at_epoch_millis")
    val nextAvailableAtEpochMillis: Long,
    @ColumnInfo(name = "next_due_at_epoch_millis")
    val nextDueAtEpochMillis: Long?,
    @ColumnInfo(name = "scheduling_policy_version")
    val schedulingPolicyVersion: String,
    @ColumnInfo(name = "outbox_event_id")
    val outboxEventId: String?,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
)

@Entity(
    tableName = "student_review_reveal_receipt",
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
    ],
    indices = [
        Index(value = ["reveal_canonical_fingerprint"], unique = true),
        Index(value = ["session_id", "queue_item_id"], unique = true),
        Index(value = ["queue_item_id"]),
        Index(value = ["session_id", "plan_id", "learner_id"]),
        Index(value = ["queue_item_id", "plan_id", "learner_id"]),
        Index(value = ["learner_id", "revealed_at_epoch_millis"]),
    ],
)
internal data class StudentReviewRevealReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "reveal_id")
    val revealId: String,
    @ColumnInfo(name = "reveal_canonical_fingerprint")
    val revealCanonicalFingerprint: String,
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
    @ColumnInfo(name = "revealed_at_epoch_millis")
    val revealedAtEpochMillis: Long,
)

internal enum class StudentReviewSessionState {
    ACTIVE,
    COMPLETED,
}

internal enum class StudentReviewSessionActionKind {
    RESPONSE,
    DONE,
    STUCK,
    REVEAL,
}
