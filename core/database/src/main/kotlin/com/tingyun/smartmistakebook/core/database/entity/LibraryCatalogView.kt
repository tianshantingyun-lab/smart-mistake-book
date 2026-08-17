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
            memory.retrievability,
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
                  AND mastery.learner_id = 'learner:local'
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
        LEFT JOIN problem_memory_state AS memory
            ON memory.practice_unit_id = entry.practice_unit_id
        WHERE entry.status = 'ACTIVE'
    """,
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
    @ColumnInfo(name = "mastery_id")
    val masteryId: String,
    @ColumnInfo(name = "chapter_labels")
    val chapterLabels: String?,
    @ColumnInfo(name = "knowledge_labels")
    val knowledgeLabels: String?,
)
