package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Persisted visual-interaction attempt (audit PR-11 / section 12).
 * One row per judged student action in the dynamic teaching GUI. The
 * action payload is stored as its serialized core-model JSON so the
 * nine action contracts stay extensible without schema churn.
 */
@Entity(
    tableName = "visual_interaction_attempt",
    indices = [
        Index(value = ["problem_revision_id"]),
        Index(value = ["attempted_at_epoch_millis"]),
    ],
)
internal data class VisualInteractionAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "action_kind")
    val actionKind: String,
    @ColumnInfo(name = "action_payload")
    val actionPayload: String,
    @ColumnInfo(name = "feasible")
    val feasible: Boolean,
    @ColumnInfo(name = "feedback")
    val feedback: String,
    @ColumnInfo(name = "attempted_at_epoch_millis")
    val attemptedAtEpochMillis: Long,
)
