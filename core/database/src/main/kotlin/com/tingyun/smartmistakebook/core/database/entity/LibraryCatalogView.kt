package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.DatabaseView

@DatabaseView(
    viewName = "library_catalog",
    value = """
        SELECT
            entry.entry_id,
            entry.problem_id,
            revision.revision_id AS problem_revision_id,
            entry.practice_unit_id,
            problem.subject,
            unit.title,
            revision.problem_markdown,
            entry.accepted_at_epoch_millis AS created_at_epoch_millis,
            entry.updated_at_epoch_millis AS updated_at_epoch_millis,
            memory.next_review_at_epoch_millis,
            NULL AS retrievability,
            memory.learner_id AS memory_learner_id,
            (
                SELECT
                    CASE
                        WHEN COUNT(*) = 0 THEN 'unknown'
                        WHEN SUM(CASE WHEN mastery.status = 'CONFLICTED' THEN 1 ELSE 0 END) > 0
                            THEN 'conflicted'
                        WHEN SUM(CASE WHEN mastery.status = 'STALE' THEN 1 ELSE 0 END) > 0
                            THEN 'stale'
                        WHEN SUM(CASE WHEN mastery.status = 'LEARNING' THEN 1 ELSE 0 END) > 0
                            THEN 'learning'
                        WHEN SUM(CASE WHEN mastery.status = 'MASTERED' THEN 1 ELSE 0 END) =
                            COUNT(*) THEN 'mastered'
                        ELSE 'unknown'
                    END
                FROM learner_knowledge_mastery_state AS mastery
                INNER JOIN practice_unit_knowledge_binding AS binding
                    ON binding.knowledge_node_id = mastery.knowledge_node_id
                   AND binding.practice_unit_id = entry.practice_unit_id
                   AND binding.basis_revision_id = revision.revision_id
                WHERE mastery.projection_name = 'study-experience-v1'
                  AND mastery.learner_id = memory.learner_id
            ) AS mastery_id,
            (
                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))
                FROM problem_classification_binding AS classification
                WHERE classification.problem_id = entry.problem_id
                  AND classification.basis_revision_id = revision.revision_id
                  AND classification.dimension = 'CHAPTER'
            ) AS chapter_labels,
            (
                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))
                FROM problem_classification_binding AS classification
                WHERE classification.problem_id = entry.problem_id
                  AND classification.basis_revision_id = revision.revision_id
                  AND classification.dimension = 'KNOWLEDGE'
            ) AS knowledge_labels
        FROM error_book_entry AS entry
        JOIN practice_unit AS unit
            ON unit.practice_unit_id = entry.practice_unit_id
        JOIN problem AS problem
            ON problem.problem_id = entry.problem_id
        JOIN problem_revision AS revision
            ON revision.revision_id = entry.current_revision_id
           AND revision.problem_id = entry.problem_id
        LEFT JOIN learner_problem_memory_state AS memory
            ON memory.practice_unit_id = entry.practice_unit_id
           AND memory.projection_name = 'study-experience-v1'
        WHERE entry.status = 'ACTIVE'
    """
)
internal data class LibraryCatalogView(
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    val subject: String,
    val title: String,
    @ColumnInfo(name = "problem_markdown")
    val problemMarkdown: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "next_review_at_epoch_millis")
    val nextReviewAtEpochMillis: Long?,
    val retrievability: Double?,
    @ColumnInfo(name = "memory_learner_id")
    val memoryLearnerId: String?,
    @ColumnInfo(name = "mastery_id")
    val masteryId: String,
    @ColumnInfo(name = "chapter_labels")
    val chapterLabels: String?,
    @ColumnInfo(name = "knowledge_labels")
    val knowledgeLabels: String?,
)

/**
 * Knowledge-question lattice (three-store closed loop): the explicit
 * (knowledge node x practice unit x mastery x memory) join that makes
 * KC-to-question weight propagation a first-class query. Read-only,
 * always fresh - propagation reads this instead of denormalized copies.
 */
@DatabaseView(
    viewName = "knowledge_question_lattice",
    value = """
        SELECT
            binding.practice_unit_id AS practice_unit_id,
            binding.knowledge_node_id AS knowledge_node_id,
            binding.strength AS binding_strength,
            binding.basis_revision_id AS basis_revision_id,
            binding.taxonomy_version AS binding_taxonomy_version,
            entry.entry_id AS entry_id,
            entry.status AS entry_status,
            mastery.learner_id AS kc_learner_id,
            memory.learner_id AS memory_learner_id,
            mastery.lower_bound_independent_correct AS kc_conservative_mastery,
            mastery.status AS kc_status,
            mastery.last_evidence_direction AS kc_last_evidence_direction,
            mastery.last_evidence_at_epoch_millis AS kc_last_evidence_at,
            memory.stability_days AS question_stability_days,
            memory.difficulty AS question_difficulty,
            memory.next_review_at_epoch_millis AS question_next_review_at,
            memory.lapse_count AS question_lapse_count,
            memory.consecutive_cross_day_again AS question_cross_day_again
        FROM practice_unit_knowledge_binding AS binding
        LEFT JOIN learner_knowledge_mastery_state AS mastery
            ON mastery.knowledge_node_id = binding.knowledge_node_id
           AND mastery.projection_name = 'study-experience-v1'
        LEFT JOIN learner_problem_memory_state AS memory
            ON memory.practice_unit_id = binding.practice_unit_id
           AND memory.projection_name = 'study-experience-v1'
        LEFT JOIN error_book_entry AS entry
            ON entry.practice_unit_id = binding.practice_unit_id
           AND entry.status = 'ACTIVE'
    """,
)
internal data class KnowledgeQuestionLatticeView(
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "binding_strength")
    val bindingStrength: Double,
    @ColumnInfo(name = "basis_revision_id")
    val basisRevisionId: String,
    @ColumnInfo(name = "binding_taxonomy_version")
    val bindingTaxonomyVersion: String,
    @ColumnInfo(name = "entry_id")
    val entryId: String?,
    @ColumnInfo(name = "entry_status")
    val entryStatus: String?,
    @ColumnInfo(name = "kc_learner_id")
    val kcLearnerId: String?,
    @ColumnInfo(name = "memory_learner_id")
    val memoryLearnerId: String?,
    @ColumnInfo(name = "kc_conservative_mastery")
    val kcConservativeMastery: Double?,
    @ColumnInfo(name = "kc_status")
    val kcStatus: String?,
    @ColumnInfo(name = "kc_last_evidence_direction")
    val kcLastEvidenceDirection: String?,
    @ColumnInfo(name = "kc_last_evidence_at")
    val kcLastEvidenceAt: Long?,
    @ColumnInfo(name = "question_stability_days")
    val questionStabilityDays: Double?,
    @ColumnInfo(name = "question_difficulty")
    val questionDifficulty: Double?,
    @ColumnInfo(name = "question_next_review_at")
    val questionNextReviewAt: Long?,
    @ColumnInfo(name = "question_lapse_count")
    val questionLapseCount: Int?,
    @ColumnInfo(name = "question_cross_day_again")
    val questionCrossDayAgain: Int?,
)
