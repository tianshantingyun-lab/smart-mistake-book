package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy

/**
 * Projection generation, ledger sequence and migration primitive DAO.
 */
@Dao
internal abstract class LearnerMasteryProjectionGenerationDao : LearnerMasterySubjectDigestDao() {
    @Query("SELECT COUNT(*) FROM mastery_knowledge_projection")
    protected abstract suspend fun countActiveProjectionRows(): Long

    @Query("SELECT COUNT(*) FROM mastery_subject_digest")
    protected abstract suspend fun countActiveSubjectDigestRows(): Long

    @Query("SELECT COUNT(*) FROM mastery_presentation_node_budget")
    protected abstract suspend fun countActivePresentationBudgetRows(): Long

    @Query("SELECT COUNT(*) FROM mastery_problem_family_node_budget")
    protected abstract suspend fun countActiveProblemFamilyBudgetRows(): Long

    @Query(
        """
        INSERT INTO mastery_knowledge_projection(
            learner_id, subject, knowledge_node_id, taxonomy_version,
            latest_evidence_knowledge_pack_version, stable_node_identity_fingerprint,
            positive_evidence_micros, negative_evidence_micros, mastery_score_micros,
            mastery_state, trend, observation_count, memory_stability_millis,
            recall_due_at_epoch_millis, last_positive_at_epoch_millis,
            last_negative_at_epoch_millis, last_evidence_at_epoch_millis,
            last_event_sequence, last_ordered_event_id, projection_policy_version,
            evidence_quality_micros, independent_problem_family_count,
            distinct_presentation_count, historical_log_odds_micros,
            calibration_snapshot_fingerprint, calibration_profile_id, calibration_version,
            recall_familiarizing_at_epoch_millis, recall_reinforcement_at_epoch_millis
        )
        SELECT
            learner_id, subject, knowledge_node_id, taxonomy_version,
            latest_evidence_knowledge_pack_version, stable_node_identity_fingerprint,
            positive_evidence_micros, negative_evidence_micros, mastery_score_micros,
            mastery_state, trend, observation_count, memory_stability_millis,
            recall_due_at_epoch_millis, last_positive_at_epoch_millis,
            last_negative_at_epoch_millis, last_evidence_at_epoch_millis,
            last_event_sequence, last_ordered_event_id, projection_policy_version,
            evidence_quality_micros, independent_problem_family_count,
            distinct_presentation_count, historical_log_odds_micros,
            calibration_snapshot_fingerprint, calibration_profile_id, calibration_version,
            recall_familiarizing_at_epoch_millis, recall_reinforcement_at_epoch_millis
        FROM mastery_projection_shadow
        WHERE generation_id = :generationId
        """,
    )
    protected abstract suspend fun copyShadowProjectionsToActive(generationId: Long)

    @Query(
        """
        INSERT INTO mastery_subject_digest(
            learner_id, subject, needs_reinforcement_count, familiarizing_count,
            steady_count, last_event_sequence, updated_at_epoch_millis,
            projection_policy_version
        )
        SELECT
            learner_id, subject, needs_reinforcement_count, familiarizing_count,
            steady_count, last_event_sequence, updated_at_epoch_millis,
            projection_policy_version
        FROM mastery_subject_digest_shadow
        WHERE generation_id = :generationId
        """,
    )
    protected abstract suspend fun copyShadowSubjectDigestsToActive(generationId: Long)

    @Query(
        """
        INSERT INTO mastery_presentation_node_budget(
            learner_id, presentation_id, subject, knowledge_node_id, taxonomy_version,
            direction, stable_node_identity_fingerprint, consumed_mass_micros, last_event_id,
            updated_at_epoch_millis
        )
        SELECT
            learner_id, presentation_id, subject, knowledge_node_id, taxonomy_version,
            direction, stable_node_identity_fingerprint, consumed_mass_micros, last_event_id,
            updated_at_epoch_millis
        FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
        """,
    )
    protected abstract suspend fun copyShadowPresentationBudgetsToActive(generationId: Long)

    @Query(
        """
        INSERT INTO mastery_problem_family_node_budget(
            learner_id, problem_family_fingerprint, subject, knowledge_node_id,
            taxonomy_version, direction, stable_node_identity_fingerprint, observation_count,
            consumed_mass_micros, last_event_id, updated_at_epoch_millis
        )
        SELECT
            learner_id, problem_family_fingerprint, subject, knowledge_node_id,
            taxonomy_version, direction, stable_node_identity_fingerprint, observation_count,
            consumed_mass_micros, last_event_id, updated_at_epoch_millis
        FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
        """,
    )
    protected abstract suspend fun copyShadowProblemFamilyBudgetsToActive(
        generationId: Long,
    )

    @Query(
        """
        UPDATE mastery_projection_generation
        SET state = 'RETIRED'
        WHERE state = 'ACTIVE' AND generation_id != :generationId
        """,
    )
    protected abstract suspend fun retireOtherProjectionGenerations(generationId: Long): Int

    @Query(
        """
        UPDATE mastery_projection_generation
        SET state = 'RETIRED',
            lease_owner_id = NULL,
            lease_expires_at_epoch_millis = NULL
        WHERE state = 'BUILDING'
          AND (
            target_projection_policy_version != :targetProjectionPolicyVersion OR
            target_calibration_version != :targetCalibrationVersion
          )
        """,
    )
    protected abstract suspend fun retireStaleBuildingProjectionGenerations(
        targetProjectionPolicyVersion: String,
        targetCalibrationVersion: String,
    ): Int

    @Query(
        """
        UPDATE mastery_projection_generation
        SET state = 'RETIRED',
            lease_owner_id = NULL,
            lease_expires_at_epoch_millis = NULL
        WHERE state = 'BUILDING'
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_store_metadata AS epoch_binding
              WHERE epoch_binding.metadata_key =
                  '$DIRECTIONAL_BUDGET_GENERATION_EPOCH_METADATA_PREFIX' ||
                      mastery_projection_generation.generation_id
                AND epoch_binding.metadata_value = :epoch
          )
        """,
    )
    protected abstract suspend fun retireBuildingProjectionGenerationsOutsideEpoch(
        epoch: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun initializeSequence(entity: MasteryLedgerSequenceEntity): Long

    @Query(
        """
        UPDATE mastery_ledger_sequence
        SET last_allocated_sequence = last_allocated_sequence + 1
        WHERE learner_id = :learnerId
        """,
    )
    protected abstract suspend fun incrementSequence(learnerId: String): Int

    @Query(
        """
        SELECT last_allocated_sequence FROM mastery_ledger_sequence
        WHERE learner_id = :learnerId
        """,
    )
    protected abstract suspend fun readSequence(learnerId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMigrationCheckpoint(
        entity: MasteryLegacyFactMigrationCheckpointEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMigrationDestinationRecord(
        entity: LearnerMasteryMigrationDestinationRecordEntity,
    ): Long

    @Query(
        """
        SELECT * FROM mastery_legacy_fact_migration_checkpoint
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
          AND batch_sequence = :batchSequence
        LIMIT 1
        """,
    )
    internal abstract suspend fun findMigrationCheckpoint(
        learnerId: String,
        sourceGeneration: String,
        batchSequence: Long,
    ): MasteryLegacyFactMigrationCheckpointEntity?

    @Query(
        """
        SELECT * FROM mastery_legacy_fact_migration_checkpoint
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
        ORDER BY batch_sequence DESC
        LIMIT 1
        """,
    )
    internal abstract suspend fun findLatestMigrationCheckpoint(
        learnerId: String,
        sourceGeneration: String,
    ): MasteryLegacyFactMigrationCheckpointEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM mastery_cutover_fence
        ) OR EXISTS(
            SELECT 1
            FROM mastery_legacy_fact_migration_checkpoint
            WHERE learner_id = :learnerId
              AND source_generation = :sourceGeneration
              AND final_batch = 1
        )
        """,
    )
    protected abstract suspend fun isMigrationLedgerSealed(
        learnerId: String,
        sourceGeneration: String,
    ): Boolean

}
