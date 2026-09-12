package com.tingyun.smartmistakebook.core.database.dao

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import com.tingyun.smartmistakebook.core.database.entity.LibraryCatalogView

/**
 * Numeric mastery for a catalog row — the weakest conservative mastery among the
 * row's bound knowledge points. This is the copy for this file's two `@Query`
 * annotations; `LEAST_MASTERED_MASTERY_SQL` in `LibraryCatalogSorts.kt` holds the
 * full reasoning and the copy used by the FTS store's runtime-built query.
 *
 * The duplication is forced: Room accepts a same-file constant but rejects a
 * query assembled from a cross-file one (both positional and `value =`
 * concatenation fail KSP with "No property named value was found in annotation
 * Query"). The three sort sites are therefore kept honest by behavior —
 * `LibraryLeastMasteredSortInstrumentedTest` covers this query and the FTS path.
 */
private const val LEAST_MASTERED_MASTERY_SQL: String =
    "(" +
        "SELECT MIN(mastery.lower_bound_independent_correct) " +
        "FROM learner_knowledge_mastery_state AS mastery " +
        "INNER JOIN practice_unit_knowledge_binding AS binding " +
        "ON binding.knowledge_node_id = mastery.knowledge_node_id " +
        "AND binding.practice_unit_id = catalog.practice_unit_id " +
        "AND binding.basis_revision_id = catalog.problem_revision_id " +
        "WHERE mastery.projection_name = 'study-experience-v1' " +
        "AND mastery.learner_id = catalog.memory_learner_id" +
        ")"

@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
@Dao
internal interface LibraryQueryDao {
    @Query(
        """
        SELECT * FROM library_catalog AS catalog
        WHERE (:subjectId IS NULL OR catalog.subject = :subjectId)
          AND (
              :sectionId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'CHAPTER'
                    AND classification.label_id = :sectionId
              )
          )
          AND (
              :knowledgePointId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'KNOWLEDGE'
                    AND classification.label_id = :knowledgePointId
              )
          )
          AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId)
          AND (
              :searchText = '' OR instr(
                  lower(
                      catalog.title || CHAR(10) || catalog.problem_markdown || CHAR(10) ||
                      catalog.subject || CHAR(10) ||
                      COALESCE(catalog.chapter_labels, '') || CHAR(10) ||
                      COALESCE(catalog.knowledge_labels, '')
                  ),
                  lower(:searchText)
              ) > 0
          )
        ORDER BY
            CASE :sort WHEN 'RECENTLY_CREATED' THEN catalog.created_at_epoch_millis END DESC,
            CASE :sort WHEN 'NEXT_REVIEW' THEN catalog.next_review_at_epoch_millis END ASC,
            CASE :sort WHEN 'LEAST_MASTERED' THEN """ + LEAST_MASTERED_MASTERY_SQL + """ END ASC,
            catalog.updated_at_epoch_millis DESC,
            catalog.entry_id ASC
        """,
    )
    fun pagingSource(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
    ): PagingSource<Int, LibraryCatalogView>

    @Query(
        """
        SELECT * FROM library_catalog AS catalog
        WHERE (:subjectId IS NULL OR catalog.subject = :subjectId)
          AND (
              :sectionId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'CHAPTER'
                    AND classification.label_id = :sectionId
              )
          )
          AND (
              :knowledgePointId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'KNOWLEDGE'
                    AND classification.label_id = :knowledgePointId
              )
          )
          AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId)
          AND (
              :searchText = '' OR instr(
                  lower(
                      catalog.title || CHAR(10) || catalog.problem_markdown || CHAR(10) ||
                      catalog.subject || CHAR(10) ||
                      COALESCE(catalog.chapter_labels, '') || CHAR(10) ||
                      COALESCE(catalog.knowledge_labels, '')
                  ),
                  lower(:searchText)
              ) > 0
          )
        ORDER BY
            CASE :sort WHEN 'RECENTLY_CREATED' THEN catalog.created_at_epoch_millis END DESC,
            CASE :sort WHEN 'NEXT_REVIEW' THEN catalog.next_review_at_epoch_millis END ASC,
            CASE :sort WHEN 'LEAST_MASTERED' THEN """ + LEAST_MASTERED_MASTERY_SQL + """ END ASC,
            catalog.updated_at_epoch_millis DESC,
            catalog.entry_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun page(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogView>

    @Query(
        """
        SELECT COUNT(*) FROM library_catalog AS catalog
        WHERE (:subjectId IS NULL OR catalog.subject = :subjectId)
          AND (
              :sectionId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'CHAPTER'
                    AND classification.label_id = :sectionId
              )
          )
          AND (
              :knowledgePointId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'KNOWLEDGE'
                    AND classification.label_id = :knowledgePointId
              )
          )
          AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId)
          AND (
              :searchText = '' OR instr(
                  lower(
                      catalog.title || CHAR(10) || catalog.problem_markdown || CHAR(10) ||
                      catalog.subject || CHAR(10) ||
                      COALESCE(catalog.chapter_labels, '') || CHAR(10) ||
                      COALESCE(catalog.knowledge_labels, '')
                  ),
                  lower(:searchText)
              ) > 0
          )
        """,
    )
    suspend fun count(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int

    @Query(
        """
        SELECT catalog.subject AS id, catalog.subject AS label, COUNT(*) AS count
        FROM library_catalog AS catalog
        WHERE (:sectionId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'CHAPTER'
                    AND classification.label_id = :sectionId
              ))
          AND (:knowledgePointId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'KNOWLEDGE'
                    AND classification.label_id = :knowledgePointId
              ))
          AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId)
          AND (
              :searchText = '' OR instr(
                  lower(
                      catalog.title || CHAR(10) || catalog.problem_markdown || CHAR(10) ||
                      catalog.subject || CHAR(10) ||
                      COALESCE(catalog.chapter_labels, '') || CHAR(10) ||
                      COALESCE(catalog.knowledge_labels, '')
                  ),
                  lower(:searchText)
              ) > 0
          )
        GROUP BY catalog.subject
        ORDER BY COUNT(*) DESC, id ASC
        """,
    )
    suspend fun subjectFacets(
        searchText: String,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): List<LibraryFacetCountRow>

    @Query(
        """
        SELECT classification.label_id AS id, classification.display_name AS label, COUNT(*) AS count
        FROM library_catalog AS catalog
        INNER JOIN problem_classification_binding AS classification
            ON classification.problem_id = catalog.problem_id
           AND classification.basis_revision_id = catalog.problem_revision_id
           AND classification.dimension = 'CHAPTER'
        WHERE (:subjectId IS NULL OR catalog.subject = :subjectId)
          AND (:knowledgePointId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS knowledge
                  WHERE knowledge.problem_id = catalog.problem_id
                    AND knowledge.basis_revision_id = catalog.problem_revision_id
                    AND knowledge.dimension = 'KNOWLEDGE'
                    AND knowledge.label_id = :knowledgePointId
              ))
          AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId)
          AND (
              :searchText = '' OR instr(
                  lower(
                      catalog.title || CHAR(10) || catalog.problem_markdown || CHAR(10) ||
                      catalog.subject || CHAR(10) ||
                      COALESCE(catalog.chapter_labels, '') || CHAR(10) ||
                      COALESCE(catalog.knowledge_labels, '')
                  ),
                  lower(:searchText)
              ) > 0
          )
        GROUP BY classification.label_id, classification.display_name
        ORDER BY COUNT(*) DESC, id ASC
        """,
    )
    suspend fun sectionFacets(
        searchText: String,
        subjectId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): List<LibraryFacetCountRow>

    @Query(
        """
        SELECT classification.label_id AS id, classification.display_name AS label, COUNT(*) AS count
        FROM library_catalog AS catalog
        INNER JOIN problem_classification_binding AS classification
            ON classification.problem_id = catalog.problem_id
           AND classification.basis_revision_id = catalog.problem_revision_id
           AND classification.dimension = 'KNOWLEDGE'
        WHERE (:subjectId IS NULL OR catalog.subject = :subjectId)
          AND (:sectionId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS chapter
                  WHERE chapter.problem_id = catalog.problem_id
                    AND chapter.basis_revision_id = catalog.problem_revision_id
                    AND chapter.dimension = 'CHAPTER'
                    AND chapter.label_id = :sectionId
              ))
          AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId)
          AND (
              :searchText = '' OR instr(
                  lower(
                      catalog.title || CHAR(10) || catalog.problem_markdown || CHAR(10) ||
                      catalog.subject || CHAR(10) ||
                      COALESCE(catalog.chapter_labels, '') || CHAR(10) ||
                      COALESCE(catalog.knowledge_labels, '')
                  ),
                  lower(:searchText)
              ) > 0
          )
        GROUP BY classification.label_id, classification.display_name
        ORDER BY COUNT(*) DESC, id ASC
        """,
    )
    suspend fun knowledgeFacets(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        masteryId: String?,
    ): List<LibraryFacetCountRow>

    @Query(
        """
        SELECT catalog.mastery_id AS id, catalog.mastery_id AS label, COUNT(*) AS count
        FROM library_catalog AS catalog
        WHERE (:subjectId IS NULL OR catalog.subject = :subjectId)
          AND (:sectionId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'CHAPTER'
                    AND classification.label_id = :sectionId
              ))
          AND (:knowledgePointId IS NULL OR EXISTS (
                  SELECT 1 FROM problem_classification_binding AS classification
                  WHERE classification.problem_id = catalog.problem_id
                    AND classification.basis_revision_id = catalog.problem_revision_id
                    AND classification.dimension = 'KNOWLEDGE'
                    AND classification.label_id = :knowledgePointId
              ))
          AND (
              :searchText = '' OR instr(
                  lower(
                      catalog.title || CHAR(10) || catalog.problem_markdown || CHAR(10) ||
                      catalog.subject || CHAR(10) ||
                      COALESCE(catalog.chapter_labels, '') || CHAR(10) ||
                      COALESCE(catalog.knowledge_labels, '')
                  ),
                  lower(:searchText)
              ) > 0
          )
        GROUP BY catalog.mastery_id
        ORDER BY COUNT(*) DESC, id ASC
        """,
    )
    suspend fun masteryFacets(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
    ): List<LibraryFacetCountRow>
}

internal data class LibraryFacetCountRow(
    val id: String,
    val label: String,
    val count: Int,
)
