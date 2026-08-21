package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Persisted student model prediction. One row per prediction produced by a
 * model version. Linked to the exact practice unit so a later real outcome
 * can be resolved back into this row for calibration.
 */
@Entity(
    tableName = "student_model_prediction",
    indices = [
        Index(value = ["practice_unit_id"]),
        Index(value = ["knowledge_node_id"]),
        Index(value = ["prediction_window_end_epoch_millis"]),
        Index(value = ["model_id", "model_version"]),
    ],
)
internal data class StudentModelPredictionEntity(
    @PrimaryKey
    @ColumnInfo(name = "prediction_id")
    val predictionId: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "algorithm_hash")
    val algorithmHash: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String?,
    @ColumnInfo(name = "feature_fingerprint")
    val featureFingerprint: String,
    @ColumnInfo(name = "predicted_score")
    val predictedScore: Double,
    @ColumnInfo(name = "conservative_score")
    val conservativeScore: Double,
    @ColumnInfo(name = "prediction_window_start_epoch_millis")
    val predictionWindowStartEpochMillis: Long,
    @ColumnInfo(name = "prediction_window_end_epoch_millis")
    val predictionWindowEndEpochMillis: Long,
    @ColumnInfo(name = "predicted_at_epoch_millis")
    val predictedAtEpochMillis: Long,
    @ColumnInfo(name = "resolved")
    val resolved: Boolean = false,
)

/**
 * The actual outcome that resolves a [StudentModelPredictionEntity].
 * Enables computing Brier/log-loss/ECE and per-bucket calibration.
 */
@Entity(
    tableName = "prediction_outcome",
    foreignKeys = [
        ForeignKey(
            entity = StudentModelPredictionEntity::class,
            parentColumns = ["prediction_id"],
            childColumns = ["prediction_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["prediction_id"], unique = true),
    ],
)
internal data class PredictionOutcomeEntity(
    @PrimaryKey
    @ColumnInfo(name = "prediction_id")
    val predictionId: String,
    @ColumnInfo(name = "observed_at_epoch_millis")
    val observedAtEpochMillis: Long,
    @ColumnInfo(name = "was_independent_correct")
    val wasIndependentCorrect: Boolean,
    @ColumnInfo(name = "response_latency_ms")
    val responseLatencyMs: Long?,
    @ColumnInfo(name = "hint_count")
    val hintCount: Int,
)
