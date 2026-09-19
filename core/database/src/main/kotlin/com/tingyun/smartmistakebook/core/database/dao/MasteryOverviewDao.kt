package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query

/**
 * Knowledge-node-grained mastery reads for the tutor's `MASTERY_READ` tool.
 *
 * Why this exists next to the `knowledge_question_lattice` view: the lattice is
 * **question-grained** (one row per binding), so counting its rows counts
 * bindings rather than knowledge nodes and the same node appears once per bound
 * question. The tool needs a node-grained view of the same facts plus the
 * aggregate history behind each node, and it needs them **scoped to one
 * subject** — the tool must never widen disclosure past the current subject.
 *
 * The projection name is the same literal the `library_catalog` and
 * `knowledge_question_lattice` views already pin, rather than a parameter:
 * there is exactly one production projection and inventing a second place to
 * carry the constant would just create a way for the two to disagree. Every
 * caller-supplied value (`learnerId`, `subject`, `knowledgeNodeIds`) is bound.
 */
@Dao
internal interface MasteryOverviewDao {

    /**
     * Every knowledge node of [subject] that has a projected mastery state,
     * weakest first. Nodes with no evidence have no row here at all — "no
     * evidence" is absent, not zero, and the caller reports the remainder as a
     * count instead of as rows.
     */
    @Query(
        """
        SELECT
            node.knowledge_node_id,
            node.display_name,
            node.granularity,
            node.node_kind,
            mastery.probability_independent_correct,
            mastery.lower_bound_independent_correct,
            mastery.evidence_mass,
            mastery.status,
            mastery.last_evidence_at_epoch_millis,
            mastery.last_evidence_direction,
            mastery.last_independent_error_at_epoch_millis,
            (
                SELECT COUNT(DISTINCT binding.practice_unit_id)
                FROM practice_unit_knowledge_binding AS binding
                INNER JOIN error_book_entry AS entry
                    ON entry.practice_unit_id = binding.practice_unit_id
                   AND entry.status = 'ACTIVE'
                WHERE binding.knowledge_node_id = node.knowledge_node_id
            ) AS bound_question_count
        FROM learner_knowledge_mastery_state AS mastery
        INNER JOIN knowledge_node AS node
            ON node.knowledge_node_id = mastery.knowledge_node_id
        WHERE mastery.projection_name = 'study-experience-v1'
          AND mastery.learner_id = :learnerId
          AND node.subject = :subject
        ORDER BY mastery.lower_bound_independent_correct ASC,
                 node.canonical_name ASC,
                 node.knowledge_node_id ASC
        """,
    )
    suspend fun readSubjectMastery(
        learnerId: String,
        subject: String,
    ): List<SubjectMasteryRow>

    /**
     * Independent-correct aggregates per knowledge node. The counts are what
     * the mastery gate's breadth requirement is about (distinct item families
     * and distinct study days), so they are reported separately from the raw
     * observation count rather than folded together.
     */
    @Query(
        """
        SELECT
            knowledge_node_id,
            COUNT(*) AS observation_count,
            COUNT(DISTINCT item_family_id) AS item_family_count,
            COUNT(DISTINCT study_day_epoch_day) AS study_day_count,
            MAX(occurred_at_epoch_millis) AS latest_at_epoch_millis
        FROM independent_correct_observation
        WHERE projection_name = 'study-experience-v1'
          AND learner_id = :learnerId
          AND knowledge_node_id IN (:knowledgeNodeIds)
        GROUP BY knowledge_node_id
        """,
    )
    suspend fun readIndependentCorrectAggregates(
        learnerId: String,
        knowledgeNodeIds: List<String>,
    ): List<IndependentCorrectAggregateRow>

    /**
     * Independent-error aggregates per knowledge node.
     *
     * `attempt_event` carries no knowledge node, so the node comes from the
     * evidence attribution the attempt was recorded against. The count is
     * `COUNT(DISTINCT attempt_id)`: one snapshot can hold more than one
     * attribution for the same node (bindings are unique per taxonomy version),
     * and counting join rows would multiply a single wrong answer.
     */
    @Query(
        """
        SELECT
            attribution.knowledge_node_id AS knowledge_node_id,
            COUNT(DISTINCT attempt.attempt_id) AS error_count,
            MAX(attempt.occurred_at_epoch_millis) AS latest_at_epoch_millis
        FROM attempt_event AS attempt
        INNER JOIN assessment_evidence_attribution AS attribution
            ON attribution.snapshot_id = attempt.assessment_snapshot_id
        WHERE attempt.learner_id = :learnerId
          AND attempt.evidence_direction = 'NEGATIVE'
          AND attribution.knowledge_node_id IN (:knowledgeNodeIds)
        GROUP BY attribution.knowledge_node_id
        """,
    )
    suspend fun readIndependentErrorAggregates(
        learnerId: String,
        knowledgeNodeIds: List<String>,
    ): List<IndependentErrorAggregateRow>

    /**
     * Model-issued evidence aggregates per knowledge node, split by whether the
     * write gate accepted it — a node whose evidence is mostly rejected is a
     * different story from one whose evidence landed.
     */
    @Query(
        """
        SELECT
            knowledge_node_id,
            SUM(CASE WHEN rejected_reason IS NULL THEN 1 ELSE 0 END) AS accepted_count,
            SUM(CASE WHEN rejected_reason IS NOT NULL THEN 1 ELSE 0 END) AS rejected_count,
            MAX(CASE WHEN rejected_reason IS NULL THEN created_at_epoch_millis END)
                AS latest_accepted_at_epoch_millis
        FROM learner_chat_evidence
        WHERE learner_id = :learnerId
          AND knowledge_node_id IN (:knowledgeNodeIds)
        GROUP BY knowledge_node_id
        """,
    )
    suspend fun readChatEvidenceAggregates(
        learnerId: String,
        knowledgeNodeIds: List<String>,
    ): List<ChatEvidenceAggregateRow>

    /**
     * How many knowledge nodes of [subject] are reviewable at all. The mastery
     * list only contains nodes that already have evidence, so without this the
     * caller cannot tell a subject with three weak nodes from a subject with
     * three weak nodes *and* five hundred never-tested ones — and the model
     * would read the short list as the whole subject.
     *
     * Mirrors the verification-status filter `countReviewedKnowledgeNodesBySubject`
     * uses, so the two counts describe the same population. It also mirrors that
     * query's `status != 'RETIRED'`: a retired knowledge point is not reviewable,
     * and counting it would overstate "另有 N 个尚无学习证据" to the model.
     */
    @Query(
        """
        SELECT COUNT(*) FROM knowledge_node
        WHERE subject = :subject
          AND verification_status IN ('CURATED', 'SOURCE_GROUNDED', 'USER_CONFIRMED')
          AND status != 'RETIRED'
        """,
    )
    suspend fun countReviewableKnowledgeNodes(subject: String): Int
}

internal data class SubjectMasteryRow(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val granularity: String,
    @ColumnInfo(name = "node_kind")
    val nodeKind: String,
    @ColumnInfo(name = "probability_independent_correct")
    val probabilityIndependentCorrect: Double,
    @ColumnInfo(name = "lower_bound_independent_correct")
    val lowerBoundIndependentCorrect: Double,
    @ColumnInfo(name = "evidence_mass")
    val evidenceMass: Double,
    val status: String,
    @ColumnInfo(name = "last_evidence_at_epoch_millis")
    val lastEvidenceAtEpochMillis: Long?,
    @ColumnInfo(name = "last_evidence_direction")
    val lastEvidenceDirection: String?,
    @ColumnInfo(name = "last_independent_error_at_epoch_millis")
    val lastIndependentErrorAtEpochMillis: Long?,
    @ColumnInfo(name = "bound_question_count")
    val boundQuestionCount: Int,
)

internal data class IndependentCorrectAggregateRow(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "observation_count")
    val observationCount: Int,
    @ColumnInfo(name = "item_family_count")
    val itemFamilyCount: Int,
    @ColumnInfo(name = "study_day_count")
    val studyDayCount: Int,
    @ColumnInfo(name = "latest_at_epoch_millis")
    val latestAtEpochMillis: Long?,
)

internal data class IndependentErrorAggregateRow(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "error_count")
    val errorCount: Int,
    @ColumnInfo(name = "latest_at_epoch_millis")
    val latestAtEpochMillis: Long?,
)

internal data class ChatEvidenceAggregateRow(
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "accepted_count")
    val acceptedCount: Int,
    @ColumnInfo(name = "rejected_count")
    val rejectedCount: Int,
    @ColumnInfo(name = "latest_accepted_at_epoch_millis")
    val latestAcceptedAtEpochMillis: Long?,
)
