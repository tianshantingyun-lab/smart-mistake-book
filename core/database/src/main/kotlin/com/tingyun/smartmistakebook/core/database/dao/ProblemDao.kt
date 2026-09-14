package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import kotlinx.coroutines.flow.Flow

internal data class MistakeRow(
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String?,
    val subject: String,
    val title: String,
    @ColumnInfo(name = "problem_markdown")
    val problemMarkdown: String,
    val status: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "estimated_seconds")
    val estimatedSeconds: Int,
    @ColumnInfo(name = "next_review_at_epoch_millis")
    val nextReviewAtEpochMillis: Long?,
    val retrievability: Double?,
    @ColumnInfo(name = "knowledge_node_ids")
    val knowledgeNodeIds: String?,
    @ColumnInfo(name = "chapter_labels")
    val chapterLabels: String?,
    @ColumnInfo(name = "knowledge_labels")
    val knowledgeLabels: String?,
    @ColumnInfo(name = "capture_occurrence_count")
    val captureOccurrenceCount: Int,
)

/**
 * 错题目录的**唯一一份** SQL：目录列表与「只取一题的 KC 范围」两个入口共用它。
 *
 * 共用不是为了让代码短，而是为了**不可能分叉**：`knowledge_node_ids` 那段谓词
 * （绑定 × 修订 × 组织回执对齐）若复制一份，同一道题在目录与补救两处就会有两个范围，
 * 而**没有任何测试会红**（审计 N-19／N-20）。定向查询于是包成 `SELECT … FROM (<这份 SQL>)`
 * 的派生表、把「哪一个 practice_unit」放到外层——谓词本身逐字相同。
 */
internal const val MISTAKE_CATALOG_SQL = """
        SELECT
            entry.entry_id,
            problem.problem_id,
            revision.revision_id AS problem_revision_id,
            unit.practice_unit_id,
            entry.source_key,
            problem.subject,
            unit.title,
            revision.problem_markdown,
            entry.status,
            entry.accepted_at_epoch_millis AS created_at_epoch_millis,
            entry.updated_at_epoch_millis AS updated_at_epoch_millis,
            unit.estimated_seconds,
            memory.next_review_at_epoch_millis,
            NULL AS retrievability,
            (
                SELECT COUNT(*)
                FROM problem_draft_commit_receipt AS receipt
                WHERE receipt.practice_unit_id = unit.practice_unit_id
            ) AS capture_occurrence_count,
            (
                SELECT GROUP_CONCAT(binding.knowledge_node_id, CHAR(31))
                FROM practice_unit_knowledge_binding AS binding
                INNER JOIN knowledge_node AS node
                    ON node.knowledge_node_id = binding.knowledge_node_id
                WHERE binding.practice_unit_id = unit.practice_unit_id
                  AND binding.basis_revision_id = revision.revision_id
                  AND (
                      NOT EXISTS (
                          SELECT 1
                          FROM problem_organization_receipt AS receipt
                          WHERE receipt.problem_id = problem.problem_id
                            AND receipt.problem_revision_id = revision.revision_id
                      )
                      OR (
                          binding.accepted_at_epoch_millis = (
                              SELECT MAX(receipt.accepted_at_epoch_millis)
                              FROM problem_organization_receipt AS receipt
                              WHERE receipt.problem_id = problem.problem_id
                                AND receipt.problem_revision_id = revision.revision_id
                          )
                          AND EXISTS (
                              SELECT 1
                              FROM problem_classification_binding AS classification
                              WHERE classification.problem_id = problem.problem_id
                                AND classification.basis_revision_id = revision.revision_id
                                AND classification.dimension = 'KNOWLEDGE'
                                AND classification.taxonomy_version =
                                    binding.taxonomy_version
                                AND classification.accepted_at_epoch_millis =
                                    binding.accepted_at_epoch_millis
                          )
                      )
                  )
            ) AS knowledge_node_ids,
            (
                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))
                FROM problem_classification_binding AS classification
                WHERE classification.problem_id = problem.problem_id
                  AND classification.basis_revision_id = revision.revision_id
                  AND classification.dimension = 'CHAPTER'
            ) AS chapter_labels,
            (
                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))
                FROM problem_classification_binding AS classification
                WHERE classification.problem_id = problem.problem_id
                  AND classification.basis_revision_id = revision.revision_id
                  AND classification.dimension = 'KNOWLEDGE'
                  AND (
                      NOT EXISTS (
                          SELECT 1
                          FROM problem_organization_receipt AS receipt
                          WHERE receipt.problem_id = problem.problem_id
                            AND receipt.problem_revision_id = revision.revision_id
                      )
                      OR (
                          classification.accepted_at_epoch_millis = (
                              SELECT MAX(receipt.accepted_at_epoch_millis)
                              FROM problem_organization_receipt AS receipt
                              WHERE receipt.problem_id = problem.problem_id
                                AND receipt.problem_revision_id = revision.revision_id
                          )
                          AND EXISTS (
                              SELECT 1
                              FROM knowledge_node AS node
                              INNER JOIN practice_unit_knowledge_binding AS binding
                                  ON binding.knowledge_node_id = node.knowledge_node_id
                                 AND binding.practice_unit_id = unit.practice_unit_id
                                 AND binding.basis_revision_id = revision.revision_id
                              WHERE binding.taxonomy_version =
                                  classification.taxonomy_version
                                AND binding.accepted_at_epoch_millis =
                                    classification.accepted_at_epoch_millis
                          )
                      )
                  )
            ) AS knowledge_labels
        FROM error_book_entry AS entry
        JOIN practice_unit AS unit
            ON unit.practice_unit_id = entry.practice_unit_id
        JOIN problem AS problem
            ON problem.problem_id = unit.problem_id
        JOIN problem_revision AS revision
            ON revision.revision_id = entry.current_revision_id
        LEFT JOIN learner_problem_memory_state AS memory
            ON memory.practice_unit_id = unit.practice_unit_id
           AND memory.projection_name = 'study-experience-v1'
        WHERE entry.status = 'ACTIVE'
        ORDER BY entry.updated_at_epoch_millis DESC, entry.entry_id ASC
        """

@Dao
internal interface ProblemDao {

    @Query(
        "SELECT * FROM knowledge_question_lattice " +
            "WHERE memory_learner_id IS NULL OR memory_learner_id = :learnerId " +
            "ORDER BY practice_unit_id, knowledge_node_id",
    )
    fun observeKnowledgeQuestionLattice(learnerId: String): Flow<List<com.tingyun.smartmistakebook.core.database.entity.KnowledgeQuestionLatticeView>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProblems(problems: List<ProblemEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRevisions(revisions: List<ProblemRevisionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPracticeUnits(practiceUnits: List<PracticeUnitEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertErrorBookEntries(entries: List<ErrorBookEntryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnowledgeNodes(nodes: List<KnowledgeNodeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnowledgeBindings(
        bindings: List<PracticeUnitKnowledgeBindingEntity>,
    ): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRelations(relations: List<ProblemRelationEntity>): List<Long>

    @Query(MISTAKE_CATALOG_SQL)
    fun observeActiveMistakes(): Flow<List<MistakeRow>>

    /**
     * 一道题的 KC 范围。与本 DAO 的目录列表**同一份 SQL**（见 [MISTAKE_CATALOG_SQL]）——
     * 会话里这条路每张卡都要走一次，而目录读是「全表 × 每行 5 个相关子查询」（审计 N-19）。
     *
     * 返回 null 有两种来源（这道题不存在 ／ 它没有任何绑定），调用方对这两者应当是同一个意思：
     * **没有可言的 KC 范围**——不要拿一个猜的范围去查材料。
     */
    @Query(
        """
        SELECT catalog.knowledge_node_ids
        FROM ($MISTAKE_CATALOG_SQL) AS catalog
        WHERE catalog.practice_unit_id = :practiceUnitId
        """,
    )
    suspend fun knowledgeNodeIdsForPracticeUnit(practiceUnitId: String): String?

    @Query(
        """
        SELECT
            entry.entry_id,
            problem.problem_id,
            revision.revision_id AS problem_revision_id,
            unit.practice_unit_id,
            entry.source_key,
            problem.subject,
            unit.title,
            revision.problem_markdown,
            entry.status,
            entry.accepted_at_epoch_millis AS created_at_epoch_millis,
            entry.updated_at_epoch_millis AS updated_at_epoch_millis,
            unit.estimated_seconds,
            memory.next_review_at_epoch_millis,
            NULL AS retrievability,
            (
                SELECT COUNT(*)
                FROM problem_draft_commit_receipt AS receipt
                WHERE receipt.practice_unit_id = unit.practice_unit_id
            ) AS capture_occurrence_count,
            (
                SELECT GROUP_CONCAT(binding.knowledge_node_id, CHAR(31))
                FROM practice_unit_knowledge_binding AS binding
                INNER JOIN knowledge_node AS node
                    ON node.knowledge_node_id = binding.knowledge_node_id
                WHERE binding.practice_unit_id = unit.practice_unit_id
                  AND binding.basis_revision_id = revision.revision_id
                  AND (
                      NOT EXISTS (
                          SELECT 1
                          FROM problem_organization_receipt AS receipt
                          WHERE receipt.problem_id = problem.problem_id
                            AND receipt.problem_revision_id = revision.revision_id
                      )
                      OR (
                          binding.accepted_at_epoch_millis = (
                              SELECT MAX(receipt.accepted_at_epoch_millis)
                              FROM problem_organization_receipt AS receipt
                              WHERE receipt.problem_id = problem.problem_id
                                AND receipt.problem_revision_id = revision.revision_id
                          )
                          AND EXISTS (
                              SELECT 1
                              FROM problem_classification_binding AS classification
                              WHERE classification.problem_id = problem.problem_id
                                AND classification.basis_revision_id = revision.revision_id
                                AND classification.dimension = 'KNOWLEDGE'
                                AND classification.taxonomy_version =
                                    binding.taxonomy_version
                                AND classification.accepted_at_epoch_millis =
                                    binding.accepted_at_epoch_millis
                          )
                      )
                  )
            ) AS knowledge_node_ids,
            NULL AS chapter_labels,
            NULL AS knowledge_labels
        FROM error_book_entry AS entry
        JOIN practice_unit AS unit
            ON unit.practice_unit_id = entry.practice_unit_id
        JOIN problem AS problem
            ON problem.problem_id = unit.problem_id
        JOIN problem_revision AS revision
            ON revision.revision_id = entry.current_revision_id
        LEFT JOIN learner_problem_memory_state AS memory
            ON memory.practice_unit_id = unit.practice_unit_id
           AND memory.projection_name = 'study-experience-v1'
        WHERE entry.source_key = :sourceKey
        LIMIT 1
        """,
    )
    /** Same projection-backed memory source as [observeActiveMistakes]. */
    suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRow?

    @Query("SELECT COUNT(*) FROM error_book_entry WHERE status = 'ACTIVE'")
    suspend fun countActiveMistakes(): Int

    @Query(
        """
        UPDATE problem_relation
        SET status = :staleStatus,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE status != :staleStatus
          AND (
            source_basis_revision_id = :problemRevisionId
            OR target_basis_revision_id = :problemRevisionId
          )
        """,
    )
    suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        staleStatus: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("SELECT status FROM problem_relation WHERE relation_id = :relationId")
    suspend fun relationStatus(relationId: String): String?
}
