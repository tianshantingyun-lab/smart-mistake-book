package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val LEARNER_MASTERY_MIGRATION_10_11 =
    object : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            projectionGenerationSchemaStatements().forEach(connection::execSQL)
        }
    }

private fun projectionGenerationSchemaStatements(): List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS `mastery_projection_generation` (
            `generation_id` INTEGER NOT NULL,
            `state` TEXT NOT NULL,
            `target_projection_policy_version` TEXT NOT NULL,
            `target_calibration_version` TEXT NOT NULL,
            `source_event_count` INTEGER NOT NULL,
            `source_supersession_count` INTEGER NOT NULL,
            `stage` TEXT NOT NULL,
            `cursor_learner_id` TEXT NOT NULL,
            `cursor_subject` TEXT NOT NULL,
            `cursor_event_sequence` INTEGER NOT NULL,
            `cursor_ordinal` INTEGER NOT NULL,
            `lease_owner_id` TEXT,
            `lease_expires_at_epoch_millis` INTEGER,
            `snapshot_fingerprint` TEXT,
            `projection_row_count` INTEGER,
            `subject_digest_row_count` INTEGER,
            `created_at_epoch_millis` INTEGER NOT NULL,
            `activated_at_epoch_millis` INTEGER,
            PRIMARY KEY(`generation_id`)
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_projection_generation_state_generation_id`
        ON `mastery_projection_generation` (`state`, `generation_id`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_projection_generation_lease_expires_at_epoch_millis`
        ON `mastery_projection_generation` (`lease_expires_at_epoch_millis`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `mastery_projection_shadow` (
            `generation_id` INTEGER NOT NULL,
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `taxonomy_version` TEXT NOT NULL,
            `latest_evidence_knowledge_pack_version` TEXT NOT NULL,
            `stable_node_identity_fingerprint` TEXT NOT NULL,
            `positive_evidence_micros` INTEGER NOT NULL,
            `negative_evidence_micros` INTEGER NOT NULL,
            `mastery_score_micros` INTEGER NOT NULL,
            `mastery_state` TEXT NOT NULL,
            `trend` TEXT NOT NULL,
            `observation_count` INTEGER NOT NULL,
            `memory_stability_millis` INTEGER NOT NULL,
            `recall_due_at_epoch_millis` INTEGER NOT NULL,
            `last_positive_at_epoch_millis` INTEGER,
            `last_negative_at_epoch_millis` INTEGER,
            `last_evidence_at_epoch_millis` INTEGER NOT NULL,
            `last_event_sequence` INTEGER NOT NULL,
            `last_ordered_event_id` TEXT NOT NULL,
            `projection_policy_version` TEXT NOT NULL,
            `evidence_quality_micros` INTEGER NOT NULL,
            `independent_problem_family_count` INTEGER NOT NULL,
            `distinct_presentation_count` INTEGER NOT NULL,
            `historical_log_odds_micros` INTEGER,
            `calibration_snapshot_fingerprint` TEXT,
            `calibration_profile_id` TEXT,
            `calibration_version` TEXT,
            `recall_familiarizing_at_epoch_millis` INTEGER,
            `recall_reinforcement_at_epoch_millis` INTEGER,
            PRIMARY KEY(
                `generation_id`, `learner_id`, `subject`,
                `knowledge_node_id`, `taxonomy_version`
            )
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_projection_shadow_generation_id_learner_id_subject`
        ON `mastery_projection_shadow` (`generation_id`, `learner_id`, `subject`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_projection_shadow_generation_id_stable_node_identity_fingerprint`
        ON `mastery_projection_shadow`
            (`generation_id`, `stable_node_identity_fingerprint`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `mastery_subject_digest_shadow` (
            `generation_id` INTEGER NOT NULL,
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `needs_reinforcement_count` INTEGER NOT NULL,
            `familiarizing_count` INTEGER NOT NULL,
            `steady_count` INTEGER NOT NULL,
            `last_event_sequence` INTEGER NOT NULL,
            `updated_at_epoch_millis` INTEGER NOT NULL,
            `projection_policy_version` TEXT NOT NULL,
            PRIMARY KEY(`generation_id`, `learner_id`, `subject`)
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_subject_digest_shadow_generation_id_learner_id`
        ON `mastery_subject_digest_shadow` (`generation_id`, `learner_id`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `mastery_presentation_node_budget_shadow` (
            `generation_id` INTEGER NOT NULL,
            `learner_id` TEXT NOT NULL,
            `presentation_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `taxonomy_version` TEXT NOT NULL,
            `stable_node_identity_fingerprint` TEXT NOT NULL,
            `consumed_mass_micros` INTEGER NOT NULL,
            `last_event_id` TEXT NOT NULL,
            `updated_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(
                `generation_id`, `learner_id`, `presentation_id`, `subject`,
                `knowledge_node_id`, `taxonomy_version`
            )
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_presentation_node_budget_shadow_generation_id_learner_id_subject`
        ON `mastery_presentation_node_budget_shadow`
            (`generation_id`, `learner_id`, `subject`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `mastery_problem_family_node_budget_shadow` (
            `generation_id` INTEGER NOT NULL,
            `learner_id` TEXT NOT NULL,
            `problem_family_fingerprint` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `knowledge_node_id` TEXT NOT NULL,
            `taxonomy_version` TEXT NOT NULL,
            `stable_node_identity_fingerprint` TEXT NOT NULL,
            `observation_count` INTEGER NOT NULL,
            `consumed_mass_micros` INTEGER NOT NULL,
            `last_event_id` TEXT NOT NULL,
            `updated_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(
                `generation_id`, `learner_id`, `problem_family_fingerprint`, `subject`,
                `knowledge_node_id`, `taxonomy_version`
            )
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_mastery_problem_family_node_budget_shadow_generation_id_learner_id_subject`
        ON `mastery_problem_family_node_budget_shadow`
            (`generation_id`, `learner_id`, `subject`)
        """.trimIndent(),
    )
