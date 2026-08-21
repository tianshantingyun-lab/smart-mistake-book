package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.PredictionOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.StudentModelPredictionEntity

/**
 * DAO for persisting student-model predictions and their outcomes.
 * This is the closed loop the audit requires: predictions are stored, later
 * real outcomes are written back, and calibration can be computed over
 * resolved prediction/outcome pairs.
 */
@Dao
internal interface PredictionAuditDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPredictions(predictions: List<StudentModelPredictionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutcome(outcome: PredictionOutcomeEntity)

    @Query("SELECT * FROM student_model_prediction WHERE prediction_id = :id")
    suspend fun findPrediction(id: String): StudentModelPredictionEntity?

    @Query(
        """
        SELECT * FROM student_model_prediction
        WHERE prediction_window_start_epoch_millis <= :atEpochMillis
          AND prediction_window_end_epoch_millis >= :atEpochMillis
          AND resolved = 0
        ORDER BY predicted_at_epoch_millis ASC
        """,
    )
    suspend fun findPendingAt(atEpochMillis: Long): List<StudentModelPredictionEntity>

    @Query(
        """
        SELECT p.*, o.was_independent_correct, o.observed_at_epoch_millis
        FROM student_model_prediction p
        JOIN prediction_outcome o ON o.prediction_id = p.prediction_id
        WHERE p.model_id = :modelId AND p.model_version = :modelVersion
        """,
    )
    suspend fun findResolvedForModel(
        modelId: String,
        modelVersion: String,
    ): List<ResolvedPredictionRow>

    @Query(
        """
        UPDATE student_model_prediction
        SET resolved = 1
        WHERE prediction_id = :predictionId
        """,
    )
    suspend fun markResolved(predictionId: String)

    @Query("SELECT COUNT(*) FROM student_model_prediction WHERE resolved = 1")
    suspend fun countResolved(): Int
}

/**
 * A resolved prediction joined with its outcome, used to compute calibration
 * metrics (Brier, log-loss, ECE) per model version and bucket.
 */
internal data class ResolvedPredictionRow(
    val predictionId: String,
    val modelId: String,
    val modelVersion: String,
    val algorithmHash: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String?,
    val featureFingerprint: String,
    val predictedScore: Double,
    val conservativeScore: Double,
    val predictionWindowStartEpochMillis: Long,
    val predictionWindowEndEpochMillis: Long,
    val predictedAtEpochMillis: Long,
    val resolved: Boolean,
    val wasIndependentCorrect: Boolean,
    val observedAtEpochMillis: Long,
)
