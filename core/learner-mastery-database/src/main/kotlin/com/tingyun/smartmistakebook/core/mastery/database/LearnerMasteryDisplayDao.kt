package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Display-only read surface for learner-mastery projections.
 *
 * Keeping these reads separate from the write-heavy rebuild DAO gives the display path a stable,
 * smaller ownership boundary and prevents a single Room DAO from growing with every presentation
 * concern.
 */
@Dao
internal abstract class LearnerMasteryDisplayDao {
    @Query(
        """
        SELECT COALESCE(
            (
                SELECT last_allocated_sequence
                FROM mastery_ledger_sequence
                WHERE learner_id = :learnerId
            ),
            0
        )
        """,
    )
    internal abstract fun observeDisplayRevision(learnerId: String): Flow<Long>

    @Query(
        """
        WITH display_transition(transition_at_epoch_millis) AS (
            SELECT
                CASE
                    WHEN projection_policy_version = 'learner-mastery-projection-v3'
                        THEN recall_familiarizing_at_epoch_millis
                    ELSE recall_due_at_epoch_millis + 1
                END
            FROM mastery_knowledge_projection
            WHERE learner_id = :learnerId
              AND mastery_state = 'STEADY'
              AND (
                  (
                      projection_policy_version = 'learner-mastery-projection-v3'
                      AND recall_familiarizing_at_epoch_millis IS NOT NULL
                  )
                  OR (
                      projection_policy_version != 'learner-mastery-projection-v3'
                      AND recall_due_at_epoch_millis < 9223372036854775807
                  )
              )
            UNION ALL
            SELECT
                CASE
                    WHEN projection_policy_version = 'learner-mastery-projection-v3'
                        THEN recall_reinforcement_at_epoch_millis
                    ELSE recall_due_at_epoch_millis + memory_stability_millis + 1
                END
            FROM mastery_knowledge_projection
            WHERE learner_id = :learnerId
              AND mastery_state = 'STEADY'
              AND (
                  (
                      projection_policy_version = 'learner-mastery-projection-v3'
                      AND recall_reinforcement_at_epoch_millis IS NOT NULL
                  )
                  OR (
                      projection_policy_version != 'learner-mastery-projection-v3'
                      AND recall_due_at_epoch_millis < 9223372036854775807
                      AND memory_stability_millis <=
                          9223372036854775806 - recall_due_at_epoch_millis
                  )
              )
            UNION ALL
            SELECT
                CASE
                    WHEN projection_policy_version = 'learner-mastery-projection-v3'
                        THEN recall_reinforcement_at_epoch_millis
                    ELSE recall_due_at_epoch_millis + 1
                END
            FROM mastery_knowledge_projection
            WHERE learner_id = :learnerId
              AND mastery_state = 'FAMILIARIZING'
              AND (
                  (
                      projection_policy_version = 'learner-mastery-projection-v3'
                      AND recall_reinforcement_at_epoch_millis IS NOT NULL
                  )
                  OR (
                      projection_policy_version != 'learner-mastery-projection-v3'
                      AND recall_due_at_epoch_millis < 9223372036854775807
                  )
              )
        )
        SELECT
            MAX(
                CASE
                    WHEN transition_at_epoch_millis <= :nowEpochMillis
                        THEN transition_at_epoch_millis
                    ELSE NULL
                END
            ) AS latest_transition_at_epoch_millis,
            MIN(
                CASE
                    WHEN transition_at_epoch_millis > :nowEpochMillis
                        THEN transition_at_epoch_millis
                    ELSE NULL
                END
            ) AS next_transition_at_epoch_millis
        FROM display_transition
        """,
    )
    internal abstract suspend fun readDisplayTemporalBounds(
        learnerId: String,
        nowEpochMillis: Long,
    ): MasteryDisplayTemporalBoundsRow

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
        ORDER BY subject ASC, stable_node_identity_fingerprint ASC
        """,
    )
    protected abstract suspend fun readDisplayOverviewProjections(
        learnerId: String,
    ): List<MasteryKnowledgeProjectionEntity>

    @Query(
        """
        SELECT DISTINCT taxonomy_version
        FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
        ORDER BY taxonomy_version ASC
        """,
    )
    protected abstract suspend fun readDisplayTaxonomyVersions(
        learnerId: String,
    ): List<String>

    @Query(
        """
        SELECT last_allocated_sequence FROM mastery_ledger_sequence
        WHERE learner_id = :learnerId
        """,
    )
    protected abstract suspend fun readSequence(learnerId: String): Long?

    @Transaction
    internal open suspend fun readDisplayOverviewSnapshot(
        learnerId: String,
        expectedRevision: Long,
    ): MasteryDisplayOverviewDaoSnapshot {
        val currentRevision = readSequence(learnerId) ?: 0L
        if (currentRevision != expectedRevision) {
            return MasteryDisplayOverviewDaoSnapshot(
                revision = currentRevision,
                revisionChanged = true,
                projections = emptyList(),
                taxonomyVersions = emptySet(),
            )
        }
        return MasteryDisplayOverviewDaoSnapshot(
            revision = currentRevision,
            revisionChanged = false,
            projections = readDisplayOverviewProjections(learnerId),
            taxonomyVersions = readDisplayTaxonomyVersions(learnerId).toSet(),
        )
    }

    @Transaction
    internal open suspend fun readDisplayPageSnapshot(
        learnerId: String,
        subject: String,
        expectedRevision: Long,
        stableNodeIdentityFingerprints: List<String>,
    ): MasteryDisplayPageDaoSnapshot {
        val currentRevision = readSequence(learnerId) ?: 0L
        if (currentRevision != expectedRevision) {
            return MasteryDisplayPageDaoSnapshot(
                revision = currentRevision,
                revisionChanged = true,
                projections = emptyList(),
                taxonomyVersions = emptySet(),
            )
        }
        val projections =
            readExactKnowledgeProjections(
                learnerId = learnerId,
                subject = subject,
                stableNodeFingerprints = stableNodeIdentityFingerprints,
            )
        return MasteryDisplayPageDaoSnapshot(
            revision = currentRevision,
            revisionChanged = false,
            projections = projections,
            taxonomyVersions = readDisplayTaxonomyVersions(learnerId).toSet(),
        )
    }

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND stable_node_identity_fingerprint IN (:stableNodeFingerprints)
        ORDER BY stable_node_identity_fingerprint ASC
        """,
    )
    internal abstract suspend fun readExactKnowledgeProjections(
        learnerId: String,
        subject: String,
        stableNodeFingerprints: List<String>,
    ): List<MasteryKnowledgeProjectionEntity>
}
