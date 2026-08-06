package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Upsert
import androidx.room3.Transaction

/**
 * Learning-event, projection, subject digest and directional budget primitive DAO.
 */
@Dao
internal abstract class LearnerMasterySubjectDigestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLearningEvent(entity: MasteryLearningEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLearningEventAttributions(
        entities: List<MasteryLearningEventAttributionEntity>,
    )

    @Query(
        "SELECT * FROM mastery_learning_event WHERE source_fact_id = :sourceFactId LIMIT 1",
    )
    protected abstract suspend fun findLearningEventBySourceFact(
        sourceFactId: String,
    ): MasteryLearningEventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLearningEvidenceSupersession(
        entity: MasteryLearningEvidenceSupersessionEntity,
    ): Long

    @Query(
        """
        SELECT * FROM mastery_learning_evidence_supersession
        WHERE idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun findLearningEvidenceSupersessionByIdempotency(
        idempotencyKey: String,
    ): MasteryLearningEvidenceSupersessionEntity?

    @Query(
        """
        SELECT * FROM mastery_learning_evidence_supersession
        WHERE original_event_id = :eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findLearningEvidenceSupersessionByOriginalEvent(
        eventId: String,
    ): MasteryLearningEvidenceSupersessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAppliedEvent(entity: MasteryAppliedEventEntity)

    @Upsert
    protected abstract suspend fun upsertProjection(entity: MasteryKnowledgeProjectionEntity)

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND trend = 'WAVERING'
        ORDER BY last_evidence_at_epoch_millis DESC, knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readDigestWaveringFocus(
        learnerId: String,
        subject: String,
        limit: Int,
    ): List<MasteryKnowledgeProjectionEntity>

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND recall_reinforcement_at_epoch_millis IS NOT NULL
          AND recall_reinforcement_at_epoch_millis <= :nowEpochMillis
        ORDER BY recall_reinforcement_at_epoch_millis ASC, knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readDigestReinforcementDueFocus(
        learnerId: String,
        subject: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<MasteryKnowledgeProjectionEntity>

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND recall_familiarizing_at_epoch_millis IS NOT NULL
          AND recall_familiarizing_at_epoch_millis <= :nowEpochMillis
        ORDER BY recall_familiarizing_at_epoch_millis ASC, knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readDigestFamiliarizingDueFocus(
        learnerId: String,
        subject: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<MasteryKnowledgeProjectionEntity>

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND recall_due_at_epoch_millis <= :nowEpochMillis
        ORDER BY recall_due_at_epoch_millis ASC, knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readDigestRecallDueFocus(
        learnerId: String,
        subject: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<MasteryKnowledgeProjectionEntity>

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND last_negative_at_epoch_millis IS NOT NULL
        ORDER BY last_negative_at_epoch_millis DESC, knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readDigestRecentNegativeFocus(
        learnerId: String,
        subject: String,
        limit: Int,
    ): List<MasteryKnowledgeProjectionEntity>

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
        LIMIT 1
        """,
    )
    protected abstract suspend fun findProjection(
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): MasteryKnowledgeProjectionEntity?

    @Upsert
    protected abstract suspend fun upsertShadowProjection(entity: MasteryProjectionShadowEntity)

    @Upsert
    protected abstract suspend fun upsertShadowProjections(
        entities: List<MasteryProjectionShadowEntity>,
    )

    @Query(
        """
        SELECT * FROM mastery_projection_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND stable_node_identity_fingerprint IN (:stableNodeIdentityFingerprints)
        """,
    )
    protected abstract suspend fun readShadowProjectionsByStableIdentity(
        generationId: Long,
        learnerId: String,
        stableNodeIdentityFingerprints: List<String>,
    ): List<MasteryProjectionShadowEntity>

    @Query(
        """
        DELETE FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
        """,
    )
    protected abstract suspend fun deleteProjection(
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): Int

    @Query(
        """
        SELECT
            SUM(CASE WHEN mastery_state = 'NEEDS_REINFORCEMENT' THEN 1 ELSE 0 END)
                AS needs_reinforcement_count,
            SUM(CASE WHEN mastery_state = 'FAMILIARIZING' THEN 1 ELSE 0 END)
                AS familiarizing_count,
            SUM(CASE WHEN mastery_state = 'STEADY' THEN 1 ELSE 0 END)
                AS steady_count
        FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId AND subject = :subject
        """,
    )
    protected abstract suspend fun countMasteryBands(
        learnerId: String,
        subject: String,
    ): MasteryBandCountRow

    @Query(
        """
        SELECT
            SUM(CASE WHEN mastery_state = 'NEEDS_REINFORCEMENT' THEN 1 ELSE 0 END)
                AS needs_reinforcement_count,
            SUM(CASE WHEN mastery_state = 'FAMILIARIZING' THEN 1 ELSE 0 END)
                AS familiarizing_count,
            SUM(CASE WHEN mastery_state = 'STEADY' THEN 1 ELSE 0 END)
                AS steady_count
        FROM mastery_projection_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND subject = :subject
        """,
    )
    protected abstract suspend fun countShadowMasteryBands(
        generationId: Long,
        learnerId: String,
        subject: String,
    ): MasteryBandCountRow

    @Upsert
    protected abstract suspend fun upsertSubjectDigest(entity: MasterySubjectDigestEntity)

    @Upsert
    protected abstract suspend fun upsertShadowSubjectDigest(
        entity: MasterySubjectDigestShadowEntity,
    )

    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget
        WHERE learner_id = :learnerId
          AND presentation_id = :presentationId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction = :direction
        LIMIT 1
        """,
    )
    protected abstract suspend fun findPresentationNodeBudget(
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): MasteryPresentationNodeBudgetEntity?

    @Query(
        """
        DELETE FROM mastery_presentation_node_budget
        WHERE learner_id = :learnerId
          AND presentation_id = :presentationId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction = :direction
        """,
    )
    protected abstract suspend fun deletePresentationNodeBudget(
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): Int

    @Query(
        """
        SELECT
            e.learner_id AS learner_id,
            e.presentation_fingerprint AS presentation_fingerprint,
            a.subject AS subject,
            a.knowledge_node_id AS knowledge_node_id,
            a.taxonomy_version AS taxonomy_version,
            e.direction AS direction,
            SUM(a.evidence_mass_micros) AS consumed_mass_micros,
            MAX(e.admitted_at_epoch_millis) AS updated_at_epoch_millis
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.presentation_fingerprint = :presentationId
          AND a.subject = :subject
          AND a.knowledge_node_id = :knowledgeNodeId
          AND a.taxonomy_version = :taxonomyVersion
          AND e.direction = :direction
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        GROUP BY
            e.learner_id,
            e.presentation_fingerprint,
            a.subject,
            a.knowledge_node_id,
            a.taxonomy_version,
            e.direction
        LIMIT 1
        """,
    )
    protected abstract suspend fun readActivePresentationBudget(
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): MasteryPresentationBudgetRebuildRow?

    @Query(
        """
        SELECT e.event_id
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.presentation_fingerprint = :presentationId
          AND a.subject = :subject
          AND a.knowledge_node_id = :knowledgeNodeId
          AND a.taxonomy_version = :taxonomyVersion
          AND e.direction = :direction
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
            SELECT 1 FROM mastery_learning_evidence_supersession s
            WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
            SELECT 1 FROM mastery_open_response_legacy_quarantine q
            WHERE q.accepted_event_id = e.event_id
          )
        ORDER BY e.admitted_at_epoch_millis DESC, e.event_id DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLatestActivePresentationBudgetEventId(
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): String?

    @Upsert
    protected abstract suspend fun upsertPresentationNodeBudget(
        entity: MasteryPresentationNodeBudgetEntity,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertShadowPresentationNodeBudgetsIfAbsent(
        entities: List<MasteryPresentationNodeBudgetShadowEntity>,
    ): List<Long>

    @Query(
        """
        UPDATE mastery_presentation_node_budget_shadow
        SET consumed_mass_micros = consumed_mass_micros + :additionalMassMicros,
            last_event_id = CASE
                WHEN :updatedAtEpochMillis > updated_at_epoch_millis OR
                     (
                        :updatedAtEpochMillis = updated_at_epoch_millis AND
                        :lastEventId > last_event_id
                     )
                THEN :lastEventId
                ELSE last_event_id
            END,
            updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis)
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction = :direction
          AND consumed_mass_micros <= :maximumMassMicros - :additionalMassMicros
        """,
    )
    protected abstract suspend fun addToShadowPresentationNodeBudget(
        generationId: Long,
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
        additionalMassMicros: Long,
        maximumMassMicros: Long,
        lastEventId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget
        WHERE learner_id = :learnerId
          AND problem_family_fingerprint = :problemFamilyFingerprint
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction = :direction
        LIMIT 1
        """,
    )
    protected abstract suspend fun findProblemFamilyNodeBudget(
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): MasteryProblemFamilyNodeBudgetEntity?

    @Query(
        """
        DELETE FROM mastery_problem_family_node_budget
        WHERE learner_id = :learnerId
          AND problem_family_fingerprint = :problemFamilyFingerprint
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction = :direction
        """,
    )
    protected abstract suspend fun deleteProblemFamilyNodeBudget(
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): Int

    @Query(
        """
        SELECT
            e.learner_id AS learner_id,
            e.problem_family_fingerprint AS problem_family_fingerprint,
            a.subject AS subject,
            a.knowledge_node_id AS knowledge_node_id,
            a.taxonomy_version AS taxonomy_version,
            e.direction AS direction,
            COUNT(DISTINCT e.event_id) AS observation_count,
            SUM(a.evidence_mass_micros) AS consumed_mass_micros,
            MAX(e.admitted_at_epoch_millis) AS updated_at_epoch_millis
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.problem_family_fingerprint = :problemFamilyFingerprint
          AND a.subject = :subject
          AND a.knowledge_node_id = :knowledgeNodeId
          AND a.taxonomy_version = :taxonomyVersion
          AND e.direction = :direction
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        GROUP BY
            e.learner_id,
            e.problem_family_fingerprint,
            a.subject,
            a.knowledge_node_id,
            a.taxonomy_version,
            e.direction
        LIMIT 1
        """,
    )
    protected abstract suspend fun readActiveProblemFamilyBudget(
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): MasteryProblemFamilyBudgetRebuildRow?

    @Query(
        """
        SELECT e.event_id
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.problem_family_fingerprint = :problemFamilyFingerprint
          AND a.subject = :subject
          AND a.knowledge_node_id = :knowledgeNodeId
          AND a.taxonomy_version = :taxonomyVersion
          AND e.direction = :direction
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
            SELECT 1 FROM mastery_learning_evidence_supersession s
            WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
            SELECT 1 FROM mastery_open_response_legacy_quarantine q
            WHERE q.accepted_event_id = e.event_id
          )
        ORDER BY e.admitted_at_epoch_millis DESC, e.event_id DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLatestActiveProblemFamilyBudgetEventId(
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
    ): String?

    @Upsert
    protected abstract suspend fun upsertProblemFamilyNodeBudget(
        entity: MasteryProblemFamilyNodeBudgetEntity,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertShadowProblemFamilyNodeBudgetsIfAbsent(
        entities: List<MasteryProblemFamilyNodeBudgetShadowEntity>,
    ): List<Long>

    @Query(
        """
        UPDATE mastery_problem_family_node_budget_shadow
        SET observation_count = observation_count + :additionalObservationCount,
            consumed_mass_micros = consumed_mass_micros + :additionalMassMicros,
            last_event_id = CASE
                WHEN :updatedAtEpochMillis > updated_at_epoch_millis OR
                     (
                        :updatedAtEpochMillis = updated_at_epoch_millis AND
                        :lastEventId > last_event_id
                     )
                THEN :lastEventId
                ELSE last_event_id
            END,
            updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis)
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND problem_family_fingerprint = :problemFamilyFingerprint
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction = :direction
          AND observation_count <= :maximumObservationCount - :additionalObservationCount
          AND consumed_mass_micros <= :maximumMassMicros - :additionalMassMicros
        """,
    )
    protected abstract suspend fun addToShadowProblemFamilyNodeBudget(
        generationId: Long,
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
        additionalObservationCount: Long,
        maximumObservationCount: Long,
        additionalMassMicros: Long,
        maximumMassMicros: Long,
        lastEventId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM mastery_subject_digest
        WHERE learner_id = :learnerId AND subject = :subject
        LIMIT 1
        """,
    )
    protected abstract suspend fun readSubjectDigest(
        learnerId: String,
        subject: String,
    ): MasterySubjectDigestEntity?

    @Query(
        """
        SELECT * FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId AND subject = :subject
        ORDER BY
            CASE mastery_state
                WHEN 'NEEDS_REINFORCEMENT' THEN 0
                WHEN 'FAMILIARIZING' THEN 1
                ELSE 2
            END ASC,
            last_evidence_at_epoch_millis DESC,
            knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readDigestBaselineFocus(
        learnerId: String,
        subject: String,
        limit: Int,
    ): List<MasteryKnowledgeProjectionEntity>

    @Transaction
    internal open suspend fun readSubjectDigestSnapshot(
        learnerId: String,
        subject: String,
        limit: Int,
        nowEpochMillis: Long = Long.MAX_VALUE,
    ): MasterySubjectDigestSnapshot {
        require(limit > 0) { "Subject digest focus limit must be positive" }
        require(nowEpochMillis >= 0L) { "Subject digest clock must not be negative" }
        val focus = roundRobinDistinctFocus(
            limit = limit,
            lanes =
                listOf(
                    readDigestWaveringFocus(learnerId, subject, limit),
                    readDigestReinforcementDueFocus(
                        learnerId,
                        subject,
                        nowEpochMillis,
                        limit,
                    ),
                    readDigestFamiliarizingDueFocus(
                        learnerId,
                        subject,
                        nowEpochMillis,
                        limit,
                    ),
                    readDigestRecallDueFocus(learnerId, subject, nowEpochMillis, limit),
                    readDigestRecentNegativeFocus(learnerId, subject, limit),
                    readDigestBaselineFocus(learnerId, subject, limit),
                ),
        )
        return MasterySubjectDigestSnapshot(
            digest = readSubjectDigest(learnerId, subject),
            focus = focus,
        )
    }
}
