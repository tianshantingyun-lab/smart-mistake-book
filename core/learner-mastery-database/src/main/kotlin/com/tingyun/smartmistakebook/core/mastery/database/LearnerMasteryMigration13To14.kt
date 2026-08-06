package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds fail-closed storage foundations for a future calibrated projection.
 *
 * No legacy learning event is backfilled: DB13 does not contain enough structured behavior-order
 * and eligibility evidence to reconstruct these rows without guessing. Existing projection rows,
 * the active generation, and its snapshot fingerprint are left byte-for-byte unchanged.
 */
internal val LEARNER_MASTERY_MIGRATION_13_14 =
    object : Migration(13, 14) {
        override suspend fun migrate(connection: SQLiteConnection) {
            addProjectionGenerationReleaseBindingColumns(connection)
            createProjectionInputTables(connection)
            createCalibrationReleaseTables(connection)
            installLearnerMasteryNextCalibrationGuards(connection)
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

private fun addProjectionGenerationReleaseBindingColumns(connection: SQLiteConnection) {
    listOf(
        "calibration_release_id TEXT",
        "calibration_release_fingerprint TEXT",
        "projection_input_set_fingerprint TEXT",
        "projection_implementation_fingerprint TEXT",
        "generation_manifest_fingerprint TEXT",
    ).forEach { column ->
        connection.execSQL("ALTER TABLE mastery_projection_generation ADD COLUMN $column")
    }
}

private fun createProjectionInputTables(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE` (
            `event_id` TEXT NOT NULL,
            `attribution_ordinal` INTEGER NOT NULL,
            `event_canonical_fingerprint` TEXT NOT NULL,
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `taxonomy_version` TEXT NOT NULL,
            `event_sequence` INTEGER NOT NULL,
            `behavior_order` INTEGER NOT NULL,
            `eligibility_basis` TEXT NOT NULL,
            `projection_eligible` INTEGER NOT NULL,
            `outcome` TEXT NOT NULL,
            `assistance` TEXT NOT NULL,
            `retry_state` TEXT NOT NULL,
            `attempt_ordinal` INTEGER NOT NULL,
            `hint_count` INTEGER NOT NULL,
            `answer_was_revealed` INTEGER NOT NULL,
            `independently_answered` INTEGER NOT NULL,
            `source_proof_fingerprint` TEXT NOT NULL,
            `attribution_fingerprint` TEXT NOT NULL,
            `canonical_fingerprint` TEXT NOT NULL,
            `captured_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`event_id`, `attribution_ordinal`),
            FOREIGN KEY(`event_id`, `event_canonical_fingerprint`)
                REFERENCES `mastery_learning_event`(`event_id`, `canonical_fingerprint`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`event_id`, `attribution_ordinal`)
                REFERENCES `mastery_learning_event_attribution`(`event_id`, `ordinal`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_mastery_projection_input_fact_event_fingerprint`
        ON `$LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE`
            (`event_id`, `event_canonical_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        -- Per-node replay order is event_sequence, then behavior_order; the remaining columns
        -- make the keyset order total and deterministic.
        CREATE UNIQUE INDEX IF NOT EXISTS `$LEARNER_MASTERY_PROJECTION_INPUT_REPLAY_INDEX`
        ON `$LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE` (
            `learner_id`, `subject`, `knowledge_node_id`, `taxonomy_version`,
            `event_sequence`, `behavior_order`, `event_id`, `attribution_ordinal`
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_mastery_projection_input_fact_canonical_fingerprint`
        ON `$LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE` (`canonical_fingerprint`)
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE` (
            `gap_fingerprint` TEXT NOT NULL,
            `event_id` TEXT NOT NULL,
            `event_canonical_fingerprint` TEXT NOT NULL,
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `event_sequence` INTEGER NOT NULL,
            `missing_component` TEXT NOT NULL,
            `detector_version` TEXT NOT NULL,
            `detected_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`gap_fingerprint`),
            FOREIGN KEY(`event_id`, `event_canonical_fingerprint`)
                REFERENCES `mastery_learning_event`(`event_id`, `canonical_fingerprint`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_projection_input_coverage_gap_event_fingerprint`
        ON `$LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE`
            (`event_id`, `event_canonical_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_mastery_projection_input_coverage_gap_replay_keyset`
        ON `$LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE`
            (`learner_id`, `subject`, `event_sequence`, `event_id`, `missing_component`)
        """.trimIndent(),
    )
}

private fun createCalibrationReleaseTables(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE` (
            `release_id` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `release_schema_version` TEXT NOT NULL,
            `projection_policy_version` TEXT NOT NULL,
            `calibration_version` TEXT NOT NULL,
            `source_input_set_fingerprint` TEXT NOT NULL,
            `projection_implementation_fingerprint` TEXT NOT NULL,
            `profile_manifest_fingerprint` TEXT NOT NULL,
            `validation_manifest_fingerprint` TEXT NOT NULL,
            `release_fingerprint` TEXT NOT NULL,
            `created_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`release_id`)
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_mastery_calibration_release_release_fingerprint`
        ON `$LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE` (`release_fingerprint`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_mastery_calibration_release_state_created_at`
        ON `$LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE` (`state`, `created_at_epoch_millis`)
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE` (
            `release_id` TEXT NOT NULL,
            `profile_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `taxonomy_version` TEXT NOT NULL,
            `population_scope` TEXT NOT NULL,
            `parameter_schema_version` TEXT NOT NULL,
            `parameter_set_fingerprint` TEXT NOT NULL,
            `training_source_fingerprint` TEXT NOT NULL,
            `evaluation_plan_fingerprint` TEXT NOT NULL,
            `header_fingerprint` TEXT NOT NULL,
            PRIMARY KEY(`release_id`, `profile_id`),
            FOREIGN KEY(`release_id`)
                REFERENCES `$LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE`(`release_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_mastery_calibration_profile_header_release_id`
        ON `$LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE` (`release_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_mastery_calibration_profile_header_subject_taxonomy`
        ON `$LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE`
            (`subject`, `taxonomy_version`, `release_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_mastery_calibration_profile_header_fingerprint`
        ON `$LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE` (`header_fingerprint`)
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE` (
            `release_id` TEXT NOT NULL,
            `profile_id` TEXT NOT NULL,
            `metric_id` TEXT NOT NULL,
            `split_id` TEXT NOT NULL,
            `sample_count` INTEGER NOT NULL,
            `metric_value_micros` INTEGER NOT NULL,
            `cohort_fingerprint` TEXT NOT NULL,
            `metric_fingerprint` TEXT NOT NULL,
            `evaluated_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`release_id`, `profile_id`, `metric_id`, `split_id`),
            FOREIGN KEY(`release_id`, `profile_id`)
                REFERENCES `$LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE`
                    (`release_id`, `profile_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_mastery_calibration_validation_metric_profile`
        ON `$LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE`
            (`release_id`, `profile_id`)
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_mastery_calibration_validation_metric_fingerprint`
        ON `$LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE` (`metric_fingerprint`)
        """.trimIndent(),
    )
}
