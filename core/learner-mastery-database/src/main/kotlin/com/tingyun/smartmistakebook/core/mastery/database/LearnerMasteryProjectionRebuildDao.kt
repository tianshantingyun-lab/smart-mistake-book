package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Projection rebuild and evidence-dimension read surface.
 *
 * Keeping directional replay, calibration binding history, shadow evidence dimension pages,
 * subject rebuild pages and destructive rebuild primitives in one DAO lets the write-heavy
 * [LearnerMasteryDao] retain only the transaction flow that mutates these inputs.
 */
@Dao
internal abstract class LearnerMasteryProjectionRebuildDao : LearnerMasteryOpenResponseDao() {
    @Query(
        """
        SELECT * FROM mastery_learning_event_attribution
        WHERE event_id = :eventId
        ORDER BY ordinal ASC
        """,
    )
    protected abstract suspend fun findLearningEventAttributions(
        eventId: String,
    ): List<MasteryLearningEventAttributionEntity>

    @Query(
        """
        SELECT
            e.event_id AS event_id,
            e.candidate_id AS candidate_id,
            e.source_fact_id AS source_fact_id,
            e.source_proof_fingerprint AS source_proof_fingerprint,
            e.learner_id AS learner_id,
            e.subject AS subject,
            e.direction AS direction,
            e.event_sequence AS event_sequence,
            e.occurred_at_epoch_millis AS occurred_at_epoch_millis,
            e.admitted_at_epoch_millis AS admitted_at_epoch_millis,
            e.projection_policy_version AS projection_policy_version,
            e.admission_policy_version AS admission_policy_version,
            e.calibration_version AS calibration_version,
            e.calibration_snapshot_fingerprint AS calibration_snapshot_fingerprint,
            e.calibration_profile_id AS calibration_profile_id,
            e.review_resolution_fingerprint AS review_resolution_fingerprint,
            e.problem_family_fingerprint AS problem_family_fingerprint,
            e.presentation_fingerprint AS presentation_fingerprint,
            e.evidence_quality_micros AS evidence_quality_micros,
            e.independently_answered AS independently_answered,
            e.canonical_fingerprint AS event_canonical_fingerprint,
            a.ordinal AS ordinal,
            a.knowledge_node_id AS knowledge_node_id,
            a.taxonomy_version AS taxonomy_version,
            a.knowledge_pack_version AS knowledge_pack_version,
            a.knowledge_node_ref_fingerprint AS knowledge_node_ref_fingerprint,
            a.evidence_mass_micros AS evidence_mass_micros
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND a.subject = :subject
          AND a.knowledge_node_id = :knowledgeNodeId
          AND a.taxonomy_version = :taxonomyVersion
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        ORDER BY e.occurred_at_epoch_millis ASC, e.event_id ASC, a.ordinal ASC,
            e.direction ASC
        """,
    )
    protected abstract suspend fun readOrderedNodeHistory(
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): List<MasteryEventAttributionReplayRow>

    @Query(
        """
        SELECT DISTINCT
            e.subject AS subject,
            e.projection_policy_version AS projection_policy_version,
            e.calibration_version AS calibration_version,
            e.calibration_profile_id AS calibration_profile_id,
            e.calibration_snapshot_fingerprint AS calibration_snapshot_fingerprint
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND a.subject = :subject
          AND a.knowledge_node_id = :knowledgeNodeId
          AND a.taxonomy_version = :taxonomyVersion
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        """,
    )
    protected abstract suspend fun readDistinctNodeHistoryCalibrationBindings(
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): List<MasteryCalibrationBindingRow>

    @Query(
        """
        SELECT * FROM mastery_learning_event e
            INDEXED BY index_mastery_learning_event_directional_budget_replay
        WHERE e.learner_id = :learnerId
          AND e.occurred_at_epoch_millis = :occurredAtEpochMillis
          AND e.event_id = :eventId
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
            SELECT 1 FROM mastery_learning_evidence_supersession s
            WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
            SELECT 1 FROM mastery_open_response_legacy_quarantine q
            WHERE q.accepted_event_id = e.event_id
          )
        LIMIT 1
        """,
    )
    protected abstract suspend fun readProjectionReplayEventAtCursor(
        learnerId: String,
        occurredAtEpochMillis: Long,
        eventId: String,
    ): MasteryLearningEventEntity?

    @Query(
        """
        SELECT * FROM mastery_learning_event e
            INDEXED BY index_mastery_learning_event_directional_budget_replay
        WHERE e.learner_id = :learnerId
          AND e.occurred_at_epoch_millis = :occurredAtEpochMillis
          AND e.event_id > :eventId
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
            SELECT 1 FROM mastery_learning_evidence_supersession s
            WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
            SELECT 1 FROM mastery_open_response_legacy_quarantine q
            WHERE q.accepted_event_id = e.event_id
          )
        ORDER BY e.event_id
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readProjectionReplayEventsAtSameInstantAfterId(
        learnerId: String,
        occurredAtEpochMillis: Long,
        eventId: String,
        limit: Int,
    ): List<MasteryLearningEventEntity>

    @Query(
        """
        SELECT * FROM mastery_learning_event e
            INDEXED BY index_mastery_learning_event_directional_budget_replay
        WHERE e.learner_id = :learnerId
          AND e.occurred_at_epoch_millis > :occurredAtEpochMillis
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
            SELECT 1 FROM mastery_learning_evidence_supersession s
            WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
            SELECT 1 FROM mastery_open_response_legacy_quarantine q
            WHERE q.accepted_event_id = e.event_id
          )
        ORDER BY e.occurred_at_epoch_millis, e.event_id
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readProjectionReplayEventsForLearnerAfterInstant(
        learnerId: String,
        occurredAtEpochMillis: Long,
        limit: Int,
    ): List<MasteryLearningEventEntity>

    @Query(
        """
        SELECT * FROM mastery_learning_event e
            INDEXED BY index_mastery_learning_event_directional_budget_replay
        WHERE e.learner_id > :learnerId
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
            SELECT 1 FROM mastery_learning_evidence_supersession s
            WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
            SELECT 1 FROM mastery_open_response_legacy_quarantine q
            WHERE q.accepted_event_id = e.event_id
          )
        ORDER BY e.learner_id, e.occurred_at_epoch_millis, e.event_id
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readProjectionReplayEventsAfterLearner(
        learnerId: String,
        limit: Int,
    ): List<MasteryLearningEventEntity>

    @Query(
        """
        SELECT * FROM mastery_learning_event_attribution
        WHERE event_id IN (:eventIds)
        """,
    )
    protected abstract suspend fun readProjectionReplayAttributionsForEvents(
        eventIds: List<String>,
    ): List<MasteryLearningEventAttributionEntity>

    protected open suspend fun readProjectionHistoryRebuildPage(
        cursorLearnerId: String,
        cursorOccurredAtEpochMillis: Long,
        cursorEventId: String,
        cursorOrdinal: Int,
        cursorDirection: String,
        limit: Int,
    ): List<MasteryEventAttributionReplayRow> {
        require(limit in 1..LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE) {
            "Projection rebuild page limit is outside its bounded contract"
        }
        val rows = ArrayList<MasteryEventAttributionReplayRow>(limit)
        if (cursorEventId.isNotEmpty()) {
            readProjectionReplayEventAtCursor(
                learnerId = cursorLearnerId,
                occurredAtEpochMillis = cursorOccurredAtEpochMillis,
                eventId = cursorEventId,
            )?.let { event ->
                check(cursorDirection.isEmpty() || event.direction == cursorDirection) {
                    "Projection rebuild cursor direction conflicts with its immutable event"
                }
                findLearningEventAttributions(event.eventId)
                    .asSequence()
                    .filter { it.ordinal > cursorOrdinal }
                    .take(limit)
                    .mapTo(rows) { attribution -> event.replayRow(attribution) }
            }
            if (rows.size == limit) return rows
        }

        var eventCursor =
            MasteryProjectionReplayEventCursor(
                learnerId = cursorLearnerId,
                occurredAtEpochMillis = cursorOccurredAtEpochMillis,
                eventId = cursorEventId,
            )
        while (rows.size < limit) {
            val eventQueryLimit =
                minOf(PROJECTION_REPLAY_EVENT_QUERY_BATCH_SIZE, limit - rows.size)
            val events =
                readProjectionReplayEventPageAfter(
                    cursor = eventCursor,
                    limit = eventQueryLimit,
                )
            if (events.isEmpty()) break
            check(events.size <= PROJECTION_REPLAY_EVENT_QUERY_BATCH_SIZE) {
                "Projection replay event query exceeded SQLite's bounded variable batch"
            }
            val attributionsByEvent =
                readProjectionReplayAttributionsForEvents(events.map { it.eventId })
                    .groupBy { it.eventId }
            events.forEach { event ->
                eventCursor = event.replayCursor()
                attributionsByEvent[event.eventId]
                    .orEmpty()
                    .sortedBy { it.ordinal }
                    .forEach { attribution ->
                        rows += event.replayRow(attribution)
                        if (rows.size == limit) return rows
                    }
            }
            if (events.size < eventQueryLimit) break
        }
        return rows
    }

    private suspend fun readProjectionReplayEventPageAfter(
        cursor: MasteryProjectionReplayEventCursor,
        limit: Int,
    ): List<MasteryLearningEventEntity> {
        val events = ArrayList<MasteryLearningEventEntity>(limit)
        fun append(page: List<MasteryLearningEventEntity>) {
            events.addAll(page.take(limit - events.size))
        }
        append(
            readProjectionReplayEventsAtSameInstantAfterId(
                learnerId = cursor.learnerId,
                occurredAtEpochMillis = cursor.occurredAtEpochMillis,
                eventId = cursor.eventId,
                limit = limit,
            ),
        )
        if (events.size < limit) {
            append(
                readProjectionReplayEventsForLearnerAfterInstant(
                    learnerId = cursor.learnerId,
                    occurredAtEpochMillis = cursor.occurredAtEpochMillis,
                    limit = limit - events.size,
                ),
            )
        }
        if (events.size < limit) {
            append(
                readProjectionReplayEventsAfterLearner(
                    learnerId = cursor.learnerId,
                    limit = limit - events.size,
                ),
            )
        }
        return events
    }

    @Query(
        """
        SELECT COUNT(*)
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        """,
    )
    protected abstract suspend fun countProjectionHistoryRebuildRows(): Long

    @Query(
        """
        SELECT
            SUM(e.evidence_quality_micros) AS evidence_quality_sum_micros,
            COUNT(DISTINCT e.event_id) AS evidence_event_count,
            COUNT(DISTINCT CASE
                WHEN e.independently_answered = 1
                THEN e.problem_family_fingerprint
                ELSE NULL
            END) AS independent_problem_family_count,
            COUNT(DISTINCT e.presentation_fingerprint) AS distinct_presentation_count
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND a.subject = :subject
          AND a.knowledge_node_id = :knowledgeNodeId
          AND a.taxonomy_version = :taxonomyVersion
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        """,
    )
    protected abstract suspend fun readProjectionEvidenceDimensions(
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): MasteryProjectionEvidenceDimensionsRow

    @Query(
        """
        SELECT
            a.knowledge_node_id AS knowledge_node_id,
            SUM(e.evidence_quality_micros) AS evidence_quality_sum_micros,
            COUNT(DISTINCT e.event_id) AS evidence_event_count,
            COUNT(DISTINCT CASE
                WHEN e.independently_answered = 1
                THEN e.problem_family_fingerprint
                ELSE NULL
            END) AS independent_problem_family_count,
            COUNT(DISTINCT e.presentation_fingerprint) AS distinct_presentation_count
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.learner_id = :learnerId
          AND e.direction IN ('POSITIVE', 'NEGATIVE')
          AND a.subject = :subject
          AND a.taxonomy_version = :taxonomyVersion
          AND a.knowledge_node_id IN (:knowledgeNodeIds)
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        GROUP BY a.knowledge_node_id
        """,
    )
    protected abstract suspend fun readProjectionEvidenceDimensionsForNodes(
        learnerId: String,
        subject: String,
        taxonomyVersion: String,
        knowledgeNodeIds: List<String>,
    ): List<MasteryProjectionNodeEvidenceDimensionsRow>

    @Query(
        """
        SELECT e.occurred_at_epoch_millis, e.event_id, a.ordinal, e.direction
        FROM mastery_learning_event e
        INNER JOIN mastery_learning_event_attribution a ON a.event_id = e.event_id
        WHERE e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
              SELECT 1
              FROM mastery_learning_evidence_supersession s
              WHERE s.original_event_id = e.event_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM mastery_open_response_legacy_quarantine q
              WHERE q.accepted_event_id = e.event_id
          )
        ORDER BY e.learner_id DESC, e.occurred_at_epoch_millis DESC,
                 e.event_id DESC, a.ordinal DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLastProjectionBudgetInputRow():
        MasteryProjectionBudgetInputTailRow?

    @Query(
        """
        SELECT * FROM mastery_projection_shadow
        WHERE generation_id = :generationId
        ORDER BY learner_id, subject, knowledge_node_id, taxonomy_version
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readFirstProjectionEvidenceDimensionPage(
        generationId: Long,
        limit: Int,
    ): List<MasteryProjectionShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_projection_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version > :taxonomyVersion
        ORDER BY taxonomy_version
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readProjectionEvidenceDimensionsAfterTaxonomy(
        generationId: Long,
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        limit: Int,
    ): List<MasteryProjectionShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_projection_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND subject = :subject
          AND knowledge_node_id > :knowledgeNodeId
        ORDER BY knowledge_node_id, taxonomy_version
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readProjectionEvidenceDimensionsAfterNode(
        generationId: Long,
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        limit: Int,
    ): List<MasteryProjectionShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_projection_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND subject > :subject
        ORDER BY subject, knowledge_node_id, taxonomy_version
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readProjectionEvidenceDimensionsAfterSubject(
        generationId: Long,
        learnerId: String,
        subject: String,
        limit: Int,
    ): List<MasteryProjectionShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_projection_shadow
        WHERE generation_id = :generationId
          AND learner_id > :learnerId
        ORDER BY learner_id, subject, knowledge_node_id, taxonomy_version
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readProjectionEvidenceDimensionsAfterLearner(
        generationId: Long,
        learnerId: String,
        limit: Int,
    ): List<MasteryProjectionShadowEntity>

    protected open suspend fun readProjectionEvidenceDimensionPage(
        generationId: Long,
        cursorLearnerId: String,
        cursorSubject: String,
        cursorKnowledgeNodeId: String,
        cursorTaxonomyVersion: String,
        limit: Int,
    ): List<MasteryProjectionShadowEntity> {
        require(limit in 1..PROJECTION_EVIDENCE_DIMENSION_PAGE_SIZE) {
            "Projection evidence-dimension page exceeds its bounded contract"
        }
        if (cursorLearnerId.isEmpty()) {
            return readFirstProjectionEvidenceDimensionPage(generationId, limit)
        }
        val rows = ArrayList<MasteryProjectionShadowEntity>(limit)
        fun append(page: List<MasteryProjectionShadowEntity>) {
            rows.addAll(page.take(limit - rows.size))
        }
        append(
            readProjectionEvidenceDimensionsAfterTaxonomy(
                generationId,
                cursorLearnerId,
                cursorSubject,
                cursorKnowledgeNodeId,
                cursorTaxonomyVersion,
                limit,
            ),
        )
        if (rows.size < limit) {
            append(
                readProjectionEvidenceDimensionsAfterNode(
                    generationId,
                    cursorLearnerId,
                    cursorSubject,
                    cursorKnowledgeNodeId,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readProjectionEvidenceDimensionsAfterSubject(
                    generationId,
                    cursorLearnerId,
                    cursorSubject,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readProjectionEvidenceDimensionsAfterLearner(
                    generationId,
                    cursorLearnerId,
                    limit - rows.size,
                ),
            )
        }
        return rows
    }

    @Query(
        """
        SELECT
            e.learner_id AS learner_id,
            e.subject AS subject,
            MAX(e.event_sequence) AS last_event_sequence,
            MAX(e.admitted_at_epoch_millis) AS updated_at_epoch_millis
        FROM mastery_learning_event e
        WHERE e.direction IN ('POSITIVE', 'NEGATIVE')
          AND NOT EXISTS (
            SELECT 1
            FROM mastery_learning_evidence_supersession s
            WHERE s.original_event_id = e.event_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM mastery_open_response_legacy_quarantine q
            WHERE q.accepted_event_id = e.event_id
          )
          AND (
            e.learner_id > :cursorLearnerId OR
            (e.learner_id = :cursorLearnerId AND e.subject > :cursorSubject)
          )
        GROUP BY e.learner_id, e.subject
        ORDER BY e.learner_id, e.subject
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readSubjectRebuildPage(
        cursorLearnerId: String,
        cursorSubject: String,
        limit: Int,
    ): List<MasterySubjectRebuildRow>

    @Query("DELETE FROM mastery_knowledge_projection")
    protected abstract suspend fun deleteAllKnowledgeProjections()

    @Query("DELETE FROM mastery_subject_digest")
    protected abstract suspend fun deleteAllSubjectDigests()

    @Query("DELETE FROM mastery_presentation_node_budget")
    protected abstract suspend fun deleteAllPresentationBudgets()

    @Query("DELETE FROM mastery_problem_family_node_budget")
    protected abstract suspend fun deleteAllProblemFamilyBudgets()

}
