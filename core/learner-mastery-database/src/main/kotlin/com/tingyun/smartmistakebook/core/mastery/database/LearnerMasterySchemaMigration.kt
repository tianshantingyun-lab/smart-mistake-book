package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val LEARNER_MASTERY_MIGRATION_1_2 =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            dropLearnerMasteryImmutabilityTriggers(connection)
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN problem_family_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN presentation_fingerprint TEXT NOT NULL DEFAULT ''",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN response_form TEXT NOT NULL DEFAULT 'IMPORTED_RECORD'",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN independently_answered INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN hint_count INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN answer_revealed INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN elapsed_duration_millis INTEGER",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN verification_kind TEXT NOT NULL DEFAULT 'SELF_REPORTED'",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN evidence_context_kind TEXT NOT NULL DEFAULT 'SAVED_MISTAKE'",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN ephemeral_problem_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN tutor_turn_reference_id TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN submission_evidence_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN attribution_model_version TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN authorized_problem_bindings_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN authorized_knowledge_refs_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN knowledge_manifest_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact " +
                    "ADD COLUMN knowledge_activation_generation INTEGER",
            )
            connection.execSQL(
                """
                UPDATE mastery_source_fact
                SET presentation_fingerprint = presentation_id
                WHERE presentation_fingerprint = ''
                """.trimIndent(),
            )

            connection.execSQL(
                "ALTER TABLE mastery_learning_event " +
                    "ADD COLUMN problem_family_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_learning_event " +
                    "ADD COLUMN presentation_fingerprint TEXT NOT NULL DEFAULT ''",
            )
            connection.execSQL(
                "ALTER TABLE mastery_learning_event " +
                    "ADD COLUMN evidence_quality_micros INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "ALTER TABLE mastery_learning_event " +
                    "ADD COLUMN independently_answered INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                """
                UPDATE mastery_learning_event
                SET presentation_fingerprint = (
                    SELECT presentation_id
                    FROM mastery_source_fact
                    WHERE mastery_source_fact.source_fact_id =
                        mastery_learning_event.source_fact_id
                )
                WHERE presentation_fingerprint = ''
                """.trimIndent(),
            )

            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection " +
                    "ADD COLUMN evidence_quality_micros INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection " +
                    "ADD COLUMN independent_problem_family_count INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection " +
                    "ADD COLUMN distinct_presentation_count INTEGER NOT NULL DEFAULT 0",
            )

            connection.execSQL(
                "ALTER TABLE mastery_cross_store_outbox " +
                    "ADD COLUMN learner_id TEXT NOT NULL DEFAULT ''",
            )
            connection.execSQL(
                """
                UPDATE mastery_cross_store_outbox
                SET learner_id = (
                    SELECT learner_id
                    FROM mastery_learning_event
                    WHERE mastery_learning_event.event_id =
                        mastery_cross_store_outbox.aggregate_id
                )
                WHERE learner_id = ''
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_cross_store_outbox_learner_id_published_at_epoch_millis
                ON mastery_cross_store_outbox(learner_id, published_at_epoch_millis)
                """.trimIndent(),
            )

            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS mastery_problem_family_node_budget (
                    learner_id TEXT NOT NULL,
                    problem_family_fingerprint TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    knowledge_node_id TEXT NOT NULL,
                    taxonomy_version TEXT NOT NULL,
                    stable_node_identity_fingerprint TEXT NOT NULL,
                    observation_count INTEGER NOT NULL,
                    consumed_mass_micros INTEGER NOT NULL,
                    last_event_id TEXT NOT NULL,
                    updated_at_epoch_millis INTEGER NOT NULL,
                    PRIMARY KEY(
                        learner_id,
                        problem_family_fingerprint,
                        subject,
                        knowledge_node_id,
                        taxonomy_version
                    )
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_problem_family_node_budget_learner_id_subject_updated_at_epoch_millis
                ON mastery_problem_family_node_budget(
                    learner_id,
                    subject,
                    updated_at_epoch_millis
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_problem_family_node_budget_stable_node_identity_fingerprint
                ON mastery_problem_family_node_budget(stable_node_identity_fingerprint)
                """.trimIndent(),
            )

            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS mastery_legacy_fact_migration_checkpoint (
                    learner_id TEXT NOT NULL,
                    source_generation TEXT NOT NULL,
                    batch_sequence INTEGER NOT NULL,
                    batch_fingerprint TEXT NOT NULL,
                    observation_count INTEGER NOT NULL,
                    final_batch INTEGER NOT NULL,
                    source_policy_version TEXT NOT NULL,
                    projection_policy_version TEXT NOT NULL,
                    completed_at_epoch_millis INTEGER NOT NULL,
                    PRIMARY KEY(learner_id, source_generation, batch_sequence)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_legacy_fact_migration_checkpoint_learner_id_source_generation_batch_fingerprint
                ON mastery_legacy_fact_migration_checkpoint(
                    learner_id,
                    source_generation,
                    batch_fingerprint
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT OR REPLACE INTO mastery_store_metadata(metadata_key, metadata_value)
                VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(
                connection = connection,
                tableNames = LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V2,
                includeCutoverInsertGuards = false,
            )
        }
    }

internal val LEARNER_MASTERY_MIGRATION_2_3 =
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_knowledge_projection_learner_id_subject_stable_node_identity_fingerprint
                ON mastery_knowledge_projection(
                    learner_id,
                    subject,
                    stable_node_identity_fingerprint
                )
                """.trimIndent(),
            )
        }
    }

internal val LEARNER_MASTERY_MIGRATION_3_4 =
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            dropLearnerMasteryImmutabilityTriggers(connection)
            connection.execSQL(
                "ALTER TABLE mastery_admission_receipt " +
                    "ADD COLUMN admission_policy_version TEXT NOT NULL " +
                    "DEFAULT 'learner-mastery-admission-legacy'",
            )
            connection.execSQL(
                "ALTER TABLE mastery_observation_candidate " +
                    "ADD COLUMN candidate_origin TEXT NOT NULL DEFAULT 'TRUSTED_LOCAL'",
            )
            connection.execSQL(
                "ALTER TABLE mastery_admission_receipt " +
                    "ADD COLUMN calibration_version TEXT NOT NULL " +
                    "DEFAULT 'learner-mastery-calibration-legacy'",
            )
            connection.execSQL(
                "ALTER TABLE mastery_learning_event " +
                    "ADD COLUMN admission_policy_version TEXT NOT NULL " +
                    "DEFAULT 'learner-mastery-admission-legacy'",
            )
            connection.execSQL(
                "ALTER TABLE mastery_learning_event " +
                    "ADD COLUMN calibration_version TEXT NOT NULL " +
                    "DEFAULT 'learner-mastery-calibration-legacy'",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS mastery_evidence_review_case (
                    review_case_id TEXT NOT NULL,
                    candidate_id TEXT NOT NULL,
                    candidate_canonical_fingerprint TEXT NOT NULL,
                    source_fact_id TEXT NOT NULL,
                    source_proof_fingerprint TEXT NOT NULL,
                    learner_id TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    admission_policy_version TEXT NOT NULL,
                    calibration_version TEXT NOT NULL,
                    review_case_fingerprint TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL,
                    PRIMARY KEY(review_case_id),
                    FOREIGN KEY(candidate_id, candidate_canonical_fingerprint)
                        REFERENCES mastery_observation_candidate(
                            candidate_id,
                            canonical_fingerprint
                        )
                        ON UPDATE NO ACTION
                        ON DELETE RESTRICT,
                    FOREIGN KEY(source_fact_id, source_proof_fingerprint)
                        REFERENCES mastery_source_proof(
                            source_fact_id,
                            proof_fingerprint
                        )
                        ON UPDATE NO ACTION
                        ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_case_candidate_id_candidate_canonical_fingerprint
                ON mastery_evidence_review_case(
                    candidate_id,
                    candidate_canonical_fingerprint
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_case_source_fact_id_source_proof_fingerprint
                ON mastery_evidence_review_case(
                    source_fact_id,
                    source_proof_fingerprint
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_case_learner_id_subject_created_at_epoch_millis
                ON mastery_evidence_review_case(
                    learner_id,
                    subject,
                    created_at_epoch_millis
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_case_review_case_fingerprint
                ON mastery_evidence_review_case(review_case_fingerprint)
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(
                connection = connection,
                tableNames = LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V4,
                includeCutoverInsertGuards = false,
            )
        }
    }

/**
 * Adds only empty append-only authority tables and nullable provenance columns.
 *
 * Existing v1-v4 rows deliberately remain without a calibration snapshot, review resolution, or
 * locally-issued evidence identity. Their original projection policy continues to define replay;
 * this migration must not manufacture provenance for historical data.
 */
internal val LEARNER_MASTERY_MIGRATION_4_5 =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            dropLearnerMasteryImmutabilityTriggers(connection)

            connection.execSQL(
                "ALTER TABLE mastery_source_fact ADD COLUMN " +
                    "authority_attempt_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact ADD COLUMN " +
                    "authority_submission_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact ADD COLUMN " +
                    "authority_presentation_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact ADD COLUMN " +
                    "authority_problem_family_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_source_fact ADD COLUMN authority_identity_version TEXT",
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_source_fact_learner_id_subject_authority_attempt_fingerprint
                ON mastery_source_fact(
                    learner_id,
                    subject,
                    authority_attempt_fingerprint
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_source_fact_learner_id_subject_authority_submission_fingerprint
                ON mastery_source_fact(
                    learner_id,
                    subject,
                    authority_submission_fingerprint
                )
                """.trimIndent(),
            )

            connection.execSQL(
                "ALTER TABLE mastery_learning_event ADD COLUMN " +
                    "calibration_snapshot_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_learning_event ADD COLUMN calibration_profile_id TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_learning_event ADD COLUMN " +
                    "review_resolution_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection ADD COLUMN " +
                    "historical_log_odds_micros INTEGER",
            )
            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection ADD COLUMN " +
                    "calibration_snapshot_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection ADD COLUMN " +
                    "calibration_profile_id TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection ADD COLUMN " +
                    "recall_familiarizing_at_epoch_millis INTEGER",
            )
            connection.execSQL(
                "ALTER TABLE mastery_knowledge_projection ADD COLUMN " +
                    "recall_reinforcement_at_epoch_millis INTEGER",
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_knowledge_projection_learner_id_recall_familiarizing_at_epoch_millis
                ON mastery_knowledge_projection(
                    learner_id,
                    recall_familiarizing_at_epoch_millis
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_knowledge_projection_learner_id_recall_reinforcement_at_epoch_millis
                ON mastery_knowledge_projection(
                    learner_id,
                    recall_reinforcement_at_epoch_millis
                )
                """.trimIndent(),
            )

            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS mastery_calibration_snapshot (
                    subject TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    calibration_version TEXT NOT NULL,
                    projection_policy_version TEXT NOT NULL,
                    prior_log_odds_micros INTEGER NOT NULL,
                    positive_log_likelihood_micros INTEGER NOT NULL,
                    negative_log_likelihood_micros INTEGER NOT NULL,
                    steady_threshold_micros INTEGER NOT NULL,
                    reinforcement_threshold_micros INTEGER NOT NULL,
                    recall_half_life_scale_micros INTEGER NOT NULL,
                    snapshot_fingerprint TEXT NOT NULL,
                    PRIMARY KEY(subject, calibration_version)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_calibration_snapshot_profile_id
                ON mastery_calibration_snapshot(profile_id)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_calibration_snapshot_snapshot_fingerprint
                ON mastery_calibration_snapshot(snapshot_fingerprint)
                """.trimIndent(),
            )

            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS mastery_evidence_review_resolution (
                    resolution_id TEXT NOT NULL,
                    review_case_id TEXT NOT NULL,
                    learner_id TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    decision TEXT NOT NULL,
                    authority TEXT NOT NULL,
                    reviewer_version TEXT NOT NULL,
                    review_evidence_fingerprint TEXT NOT NULL,
                    calibration_snapshot_fingerprint TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    resolution_fingerprint TEXT NOT NULL,
                    decided_at_epoch_millis INTEGER NOT NULL,
                    PRIMARY KEY(resolution_id),
                    FOREIGN KEY(review_case_id)
                        REFERENCES mastery_evidence_review_case(review_case_id)
                        ON UPDATE NO ACTION
                        ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_resolution_review_case_id
                ON mastery_evidence_review_resolution(review_case_id)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_resolution_learner_id_subject_decided_at_epoch_millis
                ON mastery_evidence_review_resolution(
                    learner_id,
                    subject,
                    decided_at_epoch_millis
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_resolution_idempotency_key
                ON mastery_evidence_review_resolution(idempotency_key)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_evidence_review_resolution_resolution_fingerprint
                ON mastery_evidence_review_resolution(resolution_fingerprint)
                """.trimIndent(),
            )

            installLearnerMasteryImmutableLedgerGuards(
                connection = connection,
                tableNames = LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V5,
                includeCutoverInsertGuards = false,
            )
        }
    }

internal val LEARNER_MASTERY_MIGRATION_5_6 =
    object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS mastery_learning_evidence_supersession (
                    supersession_id TEXT NOT NULL,
                    learner_id TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    original_source_fact_id TEXT NOT NULL,
                    original_source_fact_canonical_fingerprint TEXT NOT NULL,
                    original_event_id TEXT NOT NULL,
                    original_event_canonical_fingerprint TEXT NOT NULL,
                    replacement_source_fact_id TEXT NOT NULL,
                    replacement_source_fact_canonical_fingerprint TEXT NOT NULL,
                    replacement_candidate_id TEXT NOT NULL,
                    replacement_candidate_canonical_fingerprint TEXT NOT NULL,
                    replacement_event_id TEXT NOT NULL,
                    authority TEXT NOT NULL,
                    authority_version TEXT NOT NULL,
                    correction_evidence_fingerprint TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    canonical_fingerprint TEXT NOT NULL,
                    superseded_at_epoch_millis INTEGER NOT NULL,
                    PRIMARY KEY(supersession_id),
                    FOREIGN KEY(original_source_fact_id)
                        REFERENCES mastery_source_fact(source_fact_id)
                        ON UPDATE NO ACTION
                        ON DELETE RESTRICT,
                    FOREIGN KEY(original_event_id)
                        REFERENCES mastery_learning_event(event_id)
                        ON UPDATE NO ACTION
                        ON DELETE RESTRICT,
                    FOREIGN KEY(replacement_source_fact_id)
                        REFERENCES mastery_source_fact(source_fact_id)
                        ON UPDATE NO ACTION
                        ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_original_source_fact_id
                ON mastery_learning_evidence_supersession(original_source_fact_id)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_original_source_fact_canonical_fingerprint
                ON mastery_learning_evidence_supersession(
                    original_source_fact_canonical_fingerprint
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_original_event_id
                ON mastery_learning_evidence_supersession(original_event_id)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_replacement_source_fact_id
                ON mastery_learning_evidence_supersession(replacement_source_fact_id)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_replacement_event_id
                ON mastery_learning_evidence_supersession(replacement_event_id)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_learner_id_subject_superseded_at_epoch_millis
                ON mastery_learning_evidence_supersession(
                    learner_id,
                    subject,
                    superseded_at_epoch_millis
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_idempotency_key
                ON mastery_learning_evidence_supersession(idempotency_key)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_mastery_learning_evidence_supersession_canonical_fingerprint
                ON mastery_learning_evidence_supersession(canonical_fingerprint)
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(
                connection = connection,
                tableNames = LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V6,
                includeCutoverInsertGuards = false,
            )
        }
    }

private val LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V2 =
    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES -
        setOf(
            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
            LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
            LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
            LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
            "mastery_evidence_review_case",
            "mastery_evidence_review_resolution",
            "mastery_calibration_snapshot",
            "mastery_learning_evidence_supersession",
            LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
            LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
            LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
        )

private val LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V4 =
    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES -
        setOf(
            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
            LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
            LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
            LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
            "mastery_evidence_review_resolution",
            "mastery_calibration_snapshot",
            "mastery_learning_evidence_supersession",
            LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
            LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
            LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
        )

private val LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V5 =
    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES -
        setOf(
            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
            LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
            LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
            LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
            "mastery_learning_evidence_supersession",
            LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
            LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
            LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
        )

private val LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES_V6 =
    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES -
        setOf(
            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
            LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
            LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
            LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
            LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
            LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
            LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE,
        )
