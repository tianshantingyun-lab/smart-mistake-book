package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "review_plan",
    indices = [
        Index(
            value = [
                "learner_id",
                "local_day_epoch_day",
                "time_zone_id",
                "review_plan_id",
            ],
            unique = true,
        ),
        Index(value = ["plan_fingerprint"], unique = true),
        Index(value = ["status", "local_date"]),
    ],
)
internal data class ReviewPlanEntity(
    @PrimaryKey
    @ColumnInfo(name = "review_plan_id")
    val reviewPlanId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "local_day_epoch_day")
    val localDayEpochDay: Long,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
    @ColumnInfo(name = "time_budget_seconds")
    val timeBudgetSeconds: Int,
    @ColumnInfo(name = "planning_at_epoch_millis")
    val planningAtEpochMillis: Long,
    val status: String,
    @ColumnInfo(name = "planner_version")
    val plannerVersion: String,
    @ColumnInfo(name = "projection_checkpoint")
    val projectionCheckpoint: Long,
    @ColumnInfo(name = "input_fingerprint")
    val inputFingerprint: String,
    @ColumnInfo(name = "plan_fingerprint")
    val planFingerprint: String,
    @ColumnInfo(name = "plan_revision")
    val planRevision: Int,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "active_review_plan_slot",
    primaryKeys = ["learner_id", "local_day_epoch_day", "time_zone_id"],
    foreignKeys = [
        ForeignKey(
            entity = ReviewPlanEntity::class,
            parentColumns = [
                "learner_id",
                "local_day_epoch_day",
                "time_zone_id",
                "review_plan_id",
            ],
            childColumns = [
                "learner_id",
                "local_day_epoch_day",
                "time_zone_id",
                "current_review_plan_id",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["current_review_plan_id"], unique = true),
        Index(
            value = [
                "learner_id",
                "local_day_epoch_day",
                "time_zone_id",
                "current_review_plan_id",
            ],
        ),
    ],
)
internal data class ActiveReviewPlanSlotEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "local_day_epoch_day")
    val localDayEpochDay: Long,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
    @ColumnInfo(name = "current_review_plan_id")
    val currentReviewPlanId: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "review_queue_item",
    foreignKeys = [
        ForeignKey(
            entity = ReviewPlanEntity::class,
            parentColumns = ["review_plan_id"],
            childColumns = ["review_plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["review_plan_id"]),
        Index(value = ["practice_unit_id"]),
        Index(value = ["review_plan_id", "ordinal"], unique = true),
        Index(value = ["review_plan_id", "practice_unit_id"], unique = true),
    ],
)
internal data class ReviewQueueItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "review_queue_item_id")
    val reviewQueueItemId: String,
    @ColumnInfo(name = "review_plan_id")
    val reviewPlanId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "item_family_id")
    val itemFamilyId: String,
    @ColumnInfo(name = "source_bundle_id")
    val sourceBundleId: String?,
    val ordinal: Int,
    @ColumnInfo(name = "priority_score")
    val priorityScore: Double,
    @ColumnInfo(name = "due_at_epoch_millis")
    val dueAtEpochMillis: Long?,
    @ColumnInfo(name = "difficulty_band")
    val difficultyBand: String,
    @ColumnInfo(name = "estimated_seconds")
    val estimatedSeconds: Int,
    @ColumnInfo(name = "reason_snapshot")
    val reasonSnapshot: String,
    val status: String,
)

@Entity(
    tableName = "review_queue_knowledge_node",
    primaryKeys = ["review_queue_item_id", "knowledge_node_id"],
    foreignKeys = [
        ForeignKey(
            entity = ReviewQueueItemEntity::class,
            parentColumns = ["review_queue_item_id"],
            childColumns = ["review_queue_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["knowledge_node_id"])],
)
internal data class ReviewQueueKnowledgeNodeEntity(
    @ColumnInfo(name = "review_queue_item_id")
    val reviewQueueItemId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
)

@Entity(
    tableName = "review_queue_reason",
    primaryKeys = ["review_queue_item_id", "reason"],
    foreignKeys = [
        ForeignKey(
            entity = ReviewQueueItemEntity::class,
            parentColumns = ["review_queue_item_id"],
            childColumns = ["review_queue_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["review_queue_item_id"])],
)
internal data class ReviewQueueReasonEntity(
    @ColumnInfo(name = "review_queue_item_id")
    val reviewQueueItemId: String,
    val reason: String,
)

@Entity(
    tableName = "review_session",
    foreignKeys = [
        ForeignKey(
            entity = ReviewPlanEntity::class,
            parentColumns = ["review_plan_id"],
            childColumns = ["review_plan_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["review_plan_id"]),
        Index(value = ["status", "last_active_at_epoch_millis"]),
        Index(value = ["active_session_key"], unique = true),
    ],
)
internal data class ReviewSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "review_session_id")
    val reviewSessionId: String,
    @ColumnInfo(name = "review_plan_id")
    val reviewPlanId: String,
    val status: String,
    @ColumnInfo(name = "active_session_key")
    val activeSessionKey: String?,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "last_active_at_epoch_millis")
    val lastActiveAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "current_ordinal")
    val currentOrdinal: Int,
    @ColumnInfo(name = "time_budget_seconds")
    val timeBudgetSeconds: Int,
    @ColumnInfo(name = "projection_checkpoint")
    val projectionCheckpoint: Long,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
)

/** Immutable audit trail for every accepted review-session progress state. */
@Entity(
    tableName = "review_session_revision",
    primaryKeys = ["review_session_id", "state_version"],
    foreignKeys = [
        ForeignKey(
            entity = ReviewSessionEntity::class,
            parentColumns = ["review_session_id"],
            childColumns = ["review_session_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ReviewPlanEntity::class,
            parentColumns = ["review_plan_id"],
            childColumns = ["review_plan_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["review_plan_id"]),
        Index(value = ["review_session_id", "last_active_at_epoch_millis"]),
    ],
)
internal data class ReviewSessionRevisionEntity(
    @ColumnInfo(name = "review_session_id")
    val reviewSessionId: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "review_plan_id")
    val reviewPlanId: String,
    val status: String,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "last_active_at_epoch_millis")
    val lastActiveAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "current_ordinal")
    val currentOrdinal: Int,
    @ColumnInfo(name = "time_budget_seconds")
    val timeBudgetSeconds: Int,
    @ColumnInfo(name = "projection_checkpoint")
    val projectionCheckpoint: Long,
)

/** Immutable proof that one canonical attempt advanced exactly one review-session item. */
@Entity(
    tableName = "review_session_advance_receipt",
    foreignKeys = [
        ForeignKey(
            entity = ReviewSessionRevisionEntity::class,
            parentColumns = ["review_session_id", "state_version"],
            childColumns = ["review_session_id", "from_version"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ReviewSessionRevisionEntity::class,
            parentColumns = ["review_session_id", "state_version"],
            childColumns = ["review_session_id", "to_version"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ReviewQueueItemEntity::class,
            parentColumns = ["review_queue_item_id"],
            childColumns = ["review_queue_item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PracticeUnitEntity::class,
            parentColumns = ["practice_unit_id"],
            childColumns = ["practice_unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AttemptEventEntity::class,
            parentColumns = ["attempt_id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["review_session_id", "from_version"], unique = true),
        Index(value = ["review_session_id", "to_version"], unique = true),
        Index(value = ["review_queue_item_id"]),
        Index(value = ["practice_unit_id"]),
    ],
)
internal data class ReviewSessionAdvanceReceiptEntity(
    @ColumnInfo(name = "review_session_id")
    val reviewSessionId: String,
    @ColumnInfo(name = "from_version")
    val fromVersion: Long,
    @ColumnInfo(name = "to_version")
    val toVersion: Long,
    @ColumnInfo(name = "review_queue_item_id")
    val reviewQueueItemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @PrimaryKey
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "submission_id")
    val submissionId: String,
    @ColumnInfo(name = "presentation_id")
    val presentationId: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
)
