package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy

/**
 * Calibration snapshot and learning-event calibration binding primitives.
 */
@Dao
internal abstract class LearnerMasteryCalibrationDao : LearnerMasteryCrossStoreDao() {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCalibrationSnapshot(
        entity: MasteryCalibrationSnapshotEntity,
    ): Long

    @Query(
        """
        SELECT * FROM mastery_calibration_snapshot
        WHERE subject = :subject
          AND calibration_version = :calibrationVersion
          AND profile_id = :profileId
          AND snapshot_fingerprint = :snapshotFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun findCalibrationSnapshot(
        subject: String,
        calibrationVersion: String,
        profileId: String,
        snapshotFingerprint: String,
    ): MasteryCalibrationSnapshotEntity?

    @Query(
        """
        SELECT
            event.subject,
            event.projection_policy_version,
            event.calibration_version,
            event.calibration_profile_id,
            event.calibration_snapshot_fingerprint
        FROM mastery_learning_event AS event
        LEFT JOIN mastery_calibration_snapshot AS snapshot
          ON snapshot.subject = event.subject
         AND snapshot.calibration_version = event.calibration_version
         AND snapshot.profile_id = event.calibration_profile_id
         AND snapshot.snapshot_fingerprint = event.calibration_snapshot_fingerprint
        WHERE
            (
                event.projection_policy_version = :currentProjectionPolicyVersion
                AND (
                    event.calibration_profile_id IS NULL
                    OR event.calibration_snapshot_fingerprint IS NULL
                    OR snapshot.subject IS NULL
                )
            )
            OR
            (
                event.projection_policy_version != :currentProjectionPolicyVersion
                AND (
                    event.calibration_version != :legacyCalibrationVersion
                    OR event.calibration_profile_id IS NOT NULL
                    OR event.calibration_snapshot_fingerprint IS NOT NULL
                )
            )
        LIMIT 1
        """,
    )
    protected abstract suspend fun findInvalidLearningEventCalibrationBinding(
        currentProjectionPolicyVersion: String,
        legacyCalibrationVersion: String,
    ): MasteryCalibrationBindingRow?

    @Query(
        """
        SELECT
            projection.subject,
            projection.projection_policy_version,
            projection.calibration_version,
            projection.calibration_profile_id,
            projection.calibration_snapshot_fingerprint
        FROM mastery_knowledge_projection AS projection
        LEFT JOIN mastery_calibration_snapshot AS snapshot
          ON snapshot.subject = projection.subject
         AND snapshot.calibration_version = projection.calibration_version
         AND snapshot.profile_id = projection.calibration_profile_id
         AND snapshot.snapshot_fingerprint = projection.calibration_snapshot_fingerprint
        WHERE
            (
                projection.projection_policy_version = :currentProjectionPolicyVersion
                AND (
                    projection.calibration_version IS NULL
                    OR projection.calibration_profile_id IS NULL
                    OR projection.calibration_snapshot_fingerprint IS NULL
                    OR snapshot.subject IS NULL
                )
            )
            OR
            (
                projection.projection_policy_version != :currentProjectionPolicyVersion
                AND (
                    projection.calibration_version IS NOT NULL
                    OR projection.calibration_profile_id IS NOT NULL
                    OR projection.calibration_snapshot_fingerprint IS NOT NULL
                )
            )
        LIMIT 1
        """,
    )
    protected abstract suspend fun findInvalidProjectionCalibrationBinding(
        currentProjectionPolicyVersion: String,
    ): MasteryCalibrationBindingRow?

    @Query("SELECT * FROM mastery_calibration_snapshot")
    protected abstract suspend fun readAllCalibrationSnapshots():
        List<MasteryCalibrationSnapshotEntity>
}
