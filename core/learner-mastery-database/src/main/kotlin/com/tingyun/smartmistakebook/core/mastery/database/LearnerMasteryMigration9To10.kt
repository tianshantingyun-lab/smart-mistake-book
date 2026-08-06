package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

internal val LEARNER_MASTERY_MIGRATION_9_10 =
    object : Migration(9, 10) {
        override suspend fun migrate(connection: SQLiteConnection) {
            dropLearnerMasteryImmutabilityTriggers(connection)
            dropLearnerMasteryCalibrationBindingTriggers(connection)
            validateVersion9CalibrationRows(connection)
            migrateVersion9To10CalibrationLeaseSchema(connection)
            verifyVersion10CalibrationLeaseForeignKeys(connection)
            installLearnerMasteryCalibrationBindingGuards(connection)
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

internal fun persistLearnerMasteryCalibrationRegistry(
    connection: SQLiteConnection,
    tableName: String = "mastery_calibration_snapshot",
) {
    check(Regex("[a-z0-9_]+").matches(tableName)) {
        "Unsafe learner-mastery calibration table name"
    }
    connection.prepare(
        """
        INSERT OR IGNORE INTO $tableName (
            subject,
            profile_id,
            calibration_version,
            projection_policy_version,
            prior_log_odds_micros,
            positive_log_likelihood_micros,
            negative_log_likelihood_micros,
            steady_threshold_micros,
            reinforcement_threshold_micros,
            steady_minimum_observation_count,
            material_score_delta_micros,
            maximum_absolute_log_odds_micros,
            probability_transform_version,
            legacy_probability_bridge_version,
            initial_stability_millis,
            minimum_stability_millis,
            maximum_stability_millis,
            positive_stability_gain_millis,
            negative_stability_scale_micros,
            recall_half_life_scale_micros,
            presentation_mass_cap_micros,
            problem_family_mass_cap_micros,
            second_family_observation_scale_micros,
            repeated_family_observation_scale_micros,
            stable_conflict_floor_millis,
            local_verified_mass_micros,
            deterministic_rubric_mass_micros,
            model_reviewed_mass_micros,
            one_hint_scale_micros,
            multiple_hints_scale_micros,
            unknown_assistance_scale_micros,
            one_retry_scale_micros,
            multiple_retries_scale_micros,
            model_attribution_cap_micros,
            open_response_cap_micros,
            snapshot_fingerprint
        ) VALUES (
            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """.trimIndent(),
    ).use { statement ->
        LocalMasteryCalibrationRegistry.allSnapshots().forEach { snapshot ->
            statement.bindText(1, snapshot.subject)
            statement.bindText(2, snapshot.profileId)
            statement.bindText(3, snapshot.calibrationVersion)
            statement.bindText(4, snapshot.projectionPolicyVersion)
            statement.bindLong(5, snapshot.priorLogOddsMicros)
            statement.bindLong(6, snapshot.positiveLogLikelihoodMicros)
            statement.bindLong(7, snapshot.negativeLogLikelihoodMicros)
            statement.bindLong(8, snapshot.steadyThresholdMicros)
            statement.bindLong(9, snapshot.reinforcementThresholdMicros)
            statement.bindLong(10, snapshot.steadyMinimumObservationCount)
            statement.bindLong(11, snapshot.materialScoreDeltaMicros)
            statement.bindLong(12, snapshot.maximumAbsoluteLogOddsMicros)
            statement.bindLong(13, snapshot.probabilityTransformVersion)
            statement.bindLong(14, snapshot.legacyProbabilityBridgeVersion)
            statement.bindLong(15, snapshot.initialStabilityMillis)
            statement.bindLong(16, snapshot.minimumStabilityMillis)
            statement.bindLong(17, snapshot.maximumStabilityMillis)
            statement.bindLong(18, snapshot.positiveStabilityGainMillis)
            statement.bindLong(19, snapshot.negativeStabilityScaleMicros)
            statement.bindLong(20, snapshot.recallHalfLifeScaleMicros)
            statement.bindLong(21, snapshot.presentationMassCapMicros)
            statement.bindLong(22, snapshot.problemFamilyMassCapMicros)
            statement.bindLong(23, snapshot.secondFamilyObservationScaleMicros)
            statement.bindLong(24, snapshot.repeatedFamilyObservationScaleMicros)
            statement.bindLong(25, snapshot.stableConflictFloorMillis)
            statement.bindLong(26, snapshot.localVerifiedMassMicros)
            statement.bindLong(27, snapshot.deterministicRubricMassMicros)
            statement.bindLong(28, snapshot.modelReviewedMassMicros)
            statement.bindLong(29, snapshot.oneHintScaleMicros)
            statement.bindLong(30, snapshot.multipleHintsScaleMicros)
            statement.bindLong(31, snapshot.unknownAssistanceScaleMicros)
            statement.bindLong(32, snapshot.oneRetryScaleMicros)
            statement.bindLong(33, snapshot.multipleRetriesScaleMicros)
            statement.bindLong(34, snapshot.modelAttributionCapMicros)
            statement.bindLong(35, snapshot.openResponseCapMicros)
            statement.bindText(36, snapshot.snapshotFingerprint)
            statement.step()
            statement.reset()
            statement.clearBindings()
        }
    }
    auditCompleteLocalMasteryCalibrationRegistry(connection, tableName)
}

private fun validateVersion9CalibrationRows(connection: SQLiteConnection) {
    connection.prepare(
        """
        SELECT subject,
               profile_id,
               calibration_version,
               projection_policy_version,
               prior_log_odds_micros,
               positive_log_likelihood_micros,
               negative_log_likelihood_micros,
               steady_threshold_micros,
               reinforcement_threshold_micros,
               recall_half_life_scale_micros,
               snapshot_fingerprint
        FROM mastery_calibration_snapshot
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val expected =
                LocalMasteryCalibrationRegistry.resolve(
                    subject = statement.getText(0),
                    calibrationVersion = statement.getText(2),
                    profileId = statement.getText(1),
                    snapshotFingerprint = statement.getText(10),
                )
            check(statement.getText(1) == expected.profileId)
            check(statement.getText(3) == expected.projectionPolicyVersion)
            check(statement.getLong(4) == expected.priorLogOddsMicros)
            check(statement.getLong(5) == expected.positiveLogLikelihoodMicros)
            check(statement.getLong(6) == expected.negativeLogLikelihoodMicros)
            check(statement.getLong(7) == expected.steadyThresholdMicros)
            check(statement.getLong(8) == expected.reinforcementThresholdMicros)
            check(statement.getLong(9) == expected.recallHalfLifeScaleMicros)
        }
    }
}

internal fun auditCompleteLocalMasteryCalibrationRegistry(
    connection: SQLiteConnection,
    tableName: String,
) {
    check(Regex("[a-z0-9_]+").matches(tableName)) {
        "Unsafe learner-mastery calibration table name"
    }
    var rowCount = 0
    connection.prepare(
        """
        SELECT subject,
               profile_id,
               calibration_version,
               projection_policy_version,
               prior_log_odds_micros,
               positive_log_likelihood_micros,
               negative_log_likelihood_micros,
               steady_threshold_micros,
               reinforcement_threshold_micros,
               steady_minimum_observation_count,
               material_score_delta_micros,
               maximum_absolute_log_odds_micros,
               probability_transform_version,
               legacy_probability_bridge_version,
               initial_stability_millis,
               minimum_stability_millis,
               maximum_stability_millis,
               positive_stability_gain_millis,
               negative_stability_scale_micros,
               recall_half_life_scale_micros,
               presentation_mass_cap_micros,
               problem_family_mass_cap_micros,
               second_family_observation_scale_micros,
               repeated_family_observation_scale_micros,
               stable_conflict_floor_millis,
               local_verified_mass_micros,
               deterministic_rubric_mass_micros,
               model_reviewed_mass_micros,
               one_hint_scale_micros,
               multiple_hints_scale_micros,
               unknown_assistance_scale_micros,
               one_retry_scale_micros,
               multiple_retries_scale_micros,
               model_attribution_cap_micros,
               open_response_cap_micros,
               snapshot_fingerprint
        FROM $tableName
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            LocalMasteryCalibrationRegistry.verify(
                MasteryCalibrationSnapshotEntity(
                    subject = statement.getText(0),
                    profileId = statement.getText(1),
                    calibrationVersion = statement.getText(2),
                    projectionPolicyVersion = statement.getText(3),
                    priorLogOddsMicros = statement.getLong(4),
                    positiveLogLikelihoodMicros = statement.getLong(5),
                    negativeLogLikelihoodMicros = statement.getLong(6),
                    steadyThresholdMicros = statement.getLong(7),
                    reinforcementThresholdMicros = statement.getLong(8),
                    steadyMinimumObservationCount = statement.getLong(9),
                    materialScoreDeltaMicros = statement.getLong(10),
                    maximumAbsoluteLogOddsMicros = statement.getLong(11),
                    probabilityTransformVersion = statement.getLong(12),
                    legacyProbabilityBridgeVersion = statement.getLong(13),
                    initialStabilityMillis = statement.getLong(14),
                    minimumStabilityMillis = statement.getLong(15),
                    maximumStabilityMillis = statement.getLong(16),
                    positiveStabilityGainMillis = statement.getLong(17),
                    negativeStabilityScaleMicros = statement.getLong(18),
                    recallHalfLifeScaleMicros = statement.getLong(19),
                    presentationMassCapMicros = statement.getLong(20),
                    problemFamilyMassCapMicros = statement.getLong(21),
                    secondFamilyObservationScaleMicros = statement.getLong(22),
                    repeatedFamilyObservationScaleMicros = statement.getLong(23),
                    stableConflictFloorMillis = statement.getLong(24),
                    localVerifiedMassMicros = statement.getLong(25),
                    deterministicRubricMassMicros = statement.getLong(26),
                    modelReviewedMassMicros = statement.getLong(27),
                    oneHintScaleMicros = statement.getLong(28),
                    multipleHintsScaleMicros = statement.getLong(29),
                    unknownAssistanceScaleMicros = statement.getLong(30),
                    oneRetryScaleMicros = statement.getLong(31),
                    multipleRetriesScaleMicros = statement.getLong(32),
                    modelAttributionCapMicros = statement.getLong(33),
                    openResponseCapMicros = statement.getLong(34),
                    snapshotFingerprint = statement.getText(35),
                ),
            )
            rowCount += 1
        }
    }
    check(rowCount == LocalMasteryCalibrationRegistry.allSnapshots().size) {
        "Learner-mastery calibration registry is incomplete"
    }
}
