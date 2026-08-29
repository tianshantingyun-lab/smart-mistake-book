package com.tingyun.smartmistakebook.core.database.dao

import androidx.paging.PagingSource
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import com.tingyun.smartmistakebook.core.database.entity.LibrarySearchContentEntity
import com.tingyun.smartmistakebook.core.database.entity.LibrarySearchOutboxEntity

/**
 * Incremental maintenance and matching for the library FTS projection
 * (audit section 8.2 / PR-09).
 *
 * The drain path re-segments queued revisions one row at a time; each content
 * upsert/delete goes through the room_fts_content_sync triggers, which issue
 * the per-row FTS delete + re-insert. No full 'rebuild' is issued on the hot
 * path: [rebuildIndex] stays available only as a repair / first-bootstrap
 * command.
 *
 * Ranking note: the index is FTS4, where bm25()/rank do not exist. Relevance
 * is computed as a weighted sum of per-column hits for the primary query token
 * (column-scoped MATCH expressions, one EXISTS per column), with an extra
 * boost per additional query token that matches anywhere in the row; no
 * auxiliary matchinfo() decoding is needed.
 */
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
@Dao
internal interface LibraryFtsSearchDao {

    /** Projection row read for re-segmentation during outbox drain. */
    data class RevisionProjectionRow(
        @ColumnInfo(name = "revision_id")
        val revisionId: String,
        val title: String,
        @ColumnInfo(name = "problem_markdown")
        val problemMarkdown: String,
        @ColumnInfo(name = "question_document_snapshot")
        val questionDocumentSnapshot: String?,
        @ColumnInfo(name = "answer_spec_snapshot")
        val answerSpecSnapshot: String?,
        val subject: String,
        val chapter: String,
        @ColumnInfo(name = "knowledge_points")
        val knowledgePoints: String,
        val tags: String,
        @ColumnInfo(name = "error_reason")
        val errorReason: String,
    )

    /** Catalog row plus an FTS snippet for search result rendering. */
    data class LibrarySearchHitRow(
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
        val snippet: String,
    )

    // ------------------------------------------------------------------
    // Outbox drain (incremental projection maintenance)
    // ------------------------------------------------------------------

    @Query(
        """
        SELECT revision_id FROM library_search_outbox
        ORDER BY outbox_id ASC
        LIMIT 500
        """,
    )
    suspend fun readOutboxBatch(): List<String>

    @Query(
        """
        SELECT revision.revision_id,
               revision.title,
               revision.problem_markdown,
               revision.question_document_snapshot,
               revision.answer_spec_snapshot,
               problem.subject,
               COALESCE(
                   (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                    FROM problem_classification_binding AS classification
                    WHERE classification.problem_id = entry.problem_id
                      AND classification.basis_revision_id = revision.revision_id
                      AND classification.dimension = 'CHAPTER'), '') AS chapter,
               COALESCE(
                   (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                    FROM problem_classification_binding AS classification
                    WHERE classification.problem_id = entry.problem_id
                      AND classification.basis_revision_id = revision.revision_id
                      AND classification.dimension = 'KNOWLEDGE'), '') AS knowledge_points,
               COALESCE(
                   (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                    FROM problem_classification_binding AS classification
                    WHERE classification.problem_id = entry.problem_id
                      AND classification.basis_revision_id = revision.revision_id
                      AND classification.dimension NOT IN ('CHAPTER', 'KNOWLEDGE')), '') AS tags,
               COALESCE(
                   (SELECT GROUP_CONCAT(classification.display_name, CHAR(10))
                    FROM problem_classification_binding AS classification
                    WHERE classification.problem_id = entry.problem_id
                      AND classification.basis_revision_id = revision.revision_id
                      AND classification.dimension = 'ERROR_CAUSE'), '') AS error_reason
        FROM error_book_entry AS entry
        JOIN problem_revision AS revision
            ON revision.revision_id = :revisionId
           AND revision.revision_id = entry.current_revision_id
           AND revision.problem_id = entry.problem_id
        JOIN problem AS problem
            ON problem.problem_id = entry.problem_id
        WHERE entry.status = 'ACTIVE'
        LIMIT 1
        """,
    )
    suspend fun readRevisionForProjection(revisionId: String): RevisionProjectionRow?

    @Query(
        """
        INSERT INTO library_search_content
            (problem_revision_id, stem_text, options_text, solution_text, subject,
             chapter, knowledge_points, tags, error_reason, formula_tokens)
        VALUES (:revisionId, :stemText, :optionsText, :solutionText, :subject,
                :chapter, :knowledgePoints, :tags, :errorReason, :formulaTokens)
        """,
    )
    suspend fun insertContent(
        revisionId: String,
        stemText: String,
        optionsText: String,
        solutionText: String,
        subject: String,
        chapter: String,
        knowledgePoints: String,
        tags: String,
        errorReason: String,
        formulaTokens: String,
    )

    @Query(
        """
        UPDATE library_search_content
        SET stem_text = :stemText,
            options_text = :optionsText,
            solution_text = :solutionText,
            subject = :subject,
            chapter = :chapter,
            knowledge_points = :knowledgePoints,
            tags = :tags,
            error_reason = :errorReason,
            formula_tokens = :formulaTokens
        WHERE problem_revision_id = :revisionId
        """,
    )
    suspend fun updateContent(
        revisionId: String,
        stemText: String,
        optionsText: String,
        solutionText: String,
        subject: String,
        chapter: String,
        knowledgePoints: String,
        tags: String,
        errorReason: String,
        formulaTokens: String,
    )

    /** Deletes one content row; the BEFORE_DELETE sync trigger removes the FTS row. */
    @Query("DELETE FROM library_search_content WHERE problem_revision_id = :revisionId")
    suspend fun deleteContent(revisionId: String)

    @Query("DELETE FROM library_search_outbox WHERE revision_id = :revisionId")
    suspend fun clearOutboxFor(revisionId: String)

    @Query(
        "SELECT COUNT(*) FROM library_search_content WHERE problem_revision_id = :revisionId",
    )
    suspend fun countContentFor(revisionId: String): Int

    /** First-bootstrap source: every revision currently visible in the library. */
    @Query(
        """
        SELECT entry.current_revision_id AS revisionId
        FROM error_book_entry AS entry
        WHERE entry.status = 'ACTIVE'
        """,
    )
    suspend fun readActiveLibraryRevisionIds(): List<String>

    /** Revisions already materialized in the content table (bootstrap catch-up). */
    @Query("SELECT problem_revision_id FROM library_search_content")
    suspend fun readIndexedRevisionIds(): List<String>

    @Query("SELECT COUNT(*) FROM library_search_content")
    suspend fun countContent(): Int

    @Query("SELECT COUNT(*) FROM library_search_fts")
    suspend fun countIndexed(): Int

    @Query("SELECT COUNT(*) FROM library_search_outbox")
    suspend fun countOutbox(): Int

    /**
     * Repair / first-bootstrap command only. Rebuilds the external-content
     * FTS index from library_search_content; never part of the drain path.
     */
    @Query("INSERT INTO library_search_fts(library_search_fts) VALUES('rebuild')")
    suspend fun rebuildIndex()

    // ------------------------------------------------------------------
    // Sync / outbox triggers
    // ------------------------------------------------------------------
    //
    // Room classifies CREATE TRIGGER DDL as an UNKNOWN query type and
    // rejects it in @Query, so the room_fts_content_sync_* and
    // library_search_outbox_revision_* triggers are created lazily on the
    // raw connection by RoomStudyDatabase.ensureSearchTriggers (DDL
    // byte-identical to 32.json); they are deliberately not part of the DAO.

    // ------------------------------------------------------------------
    // Matching queries (FTS4; bm25() is FTS5-only and must never be used)
    // ------------------------------------------------------------------

    /**
     * Relevance-ranked paged search, executed as a raw query.
     *
     * The weighted ranking sums per-column hit indicators computed as
     * CASE WHEN EXISTS(...) constructs. Room's @Query SQL parser rejects
     * CASE WHEN, so this query is declared as [RawQuery] to bypass static
     * SQL validation and run verbatim; the runtime semantics stay FTS4-legal
     * (MATCH only appears as a WHERE constraint, snippet() takes the bare
     * table identifier). The caller constructs the [RoomRawQuery] in
     * RoomLibrarySearchStore.buildLibrarySearchRawQuery with positional bindings
     * only - no value is ever interpolated into the SQL text.
     */
    @RawQuery(
        observedEntities = [
            LibrarySearchContentEntity::class,
            LibrarySearchOutboxEntity::class,
        ],
    )
    fun searchPagingSource(query: RoomRawQuery): PagingSource<Int, LibrarySearchHitRow>

    /** Counting twin of [searchPagingSource] for totals and facets. */
    @Query(
        """
        SELECT COUNT(*)
        FROM library_search_fts
        JOIN library_search_content AS content
            ON content.content_row_id = library_search_fts.docid
        JOIN library_catalog AS catalog
            ON catalog.problem_revision_id = content.problem_revision_id
        WHERE library_search_fts MATCH :matchQuery
          AND (:subjectId IS NULL OR catalog.subject = :subjectId)
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
        """,
    )
    suspend fun countSearch(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int

    /** Offset-paged twin of [searchPagingSource] (same ranking), raw for the same reason. */
    @RawQuery(
        observedEntities = [
            LibrarySearchContentEntity::class,
            LibrarySearchOutboxEntity::class,
        ],
    )
    suspend fun searchPage(query: RoomRawQuery): List<LibrarySearchHitRow>

    @Query(
        "SELECT catalog.subject AS id, catalog.subject AS label, COUNT(*) AS count " +
            "FROM library_catalog AS catalog " +
            "JOIN library_search_content AS content " +
            "    ON content.problem_revision_id = catalog.problem_revision_id " +
            "JOIN library_search_fts ON library_search_fts.docid = content.content_row_id " +
            "WHERE library_search_fts MATCH :matchQuery " +
            "AND (:sectionId IS NULL OR EXISTS (SELECT 1 FROM problem_classification_binding AS c " +
            "WHERE c.problem_id = catalog.problem_id AND c.basis_revision_id = catalog.problem_revision_id " +
            "AND c.dimension = 'CHAPTER' AND c.label_id = :sectionId)) " +
            "AND (:knowledgePointId IS NULL OR EXISTS (SELECT 1 FROM problem_classification_binding AS k " +
            "WHERE k.problem_id = catalog.problem_id AND k.basis_revision_id = catalog.problem_revision_id " +
            "AND k.dimension = 'KNOWLEDGE' AND k.label_id = :knowledgePointId)) " +
            "AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId) " +
            "GROUP BY catalog.subject ORDER BY COUNT(*) DESC, id ASC",
    )
    suspend fun searchSubjectFacets(
        matchQuery: String,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): List<LibraryFacetCountRow>

    @Query(
        "SELECT classification.label_id AS id, classification.display_name AS label, COUNT(*) AS count " +
            "FROM library_catalog AS catalog " +
            "JOIN problem_classification_binding AS classification " +
            "    ON classification.problem_id = catalog.problem_id " +
            "   AND classification.basis_revision_id = catalog.problem_revision_id " +
            "   AND classification.dimension = 'CHAPTER' " +
            "JOIN library_search_content AS content " +
            "    ON content.problem_revision_id = catalog.problem_revision_id " +
            "JOIN library_search_fts ON library_search_fts.docid = content.content_row_id " +
            "WHERE library_search_fts MATCH :matchQuery " +
            "AND (:subjectId IS NULL OR catalog.subject = :subjectId) " +
            "AND (:knowledgePointId IS NULL OR EXISTS (SELECT 1 FROM problem_classification_binding AS k " +
            "WHERE k.problem_id = catalog.problem_id AND k.basis_revision_id = catalog.problem_revision_id " +
            "AND k.dimension = 'KNOWLEDGE' AND k.label_id = :knowledgePointId)) " +
            "AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId) " +
            "GROUP BY classification.label_id, classification.display_name " +
            "ORDER BY COUNT(*) DESC, id ASC",
    )
    suspend fun searchSectionFacets(
        matchQuery: String,
        subjectId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): List<LibraryFacetCountRow>

    @Query(
        "SELECT classification.label_id AS id, classification.display_name AS label, COUNT(*) AS count " +
            "FROM library_catalog AS catalog " +
            "JOIN problem_classification_binding AS classification " +
            "    ON classification.problem_id = catalog.problem_id " +
            "   AND classification.basis_revision_id = catalog.problem_revision_id " +
            "   AND classification.dimension = 'KNOWLEDGE' " +
            "JOIN library_search_content AS content " +
            "    ON content.problem_revision_id = catalog.problem_revision_id " +
            "JOIN library_search_fts ON library_search_fts.docid = content.content_row_id " +
            "WHERE library_search_fts MATCH :matchQuery " +
            "AND (:subjectId IS NULL OR catalog.subject = :subjectId) " +
            "AND (:sectionId IS NULL OR EXISTS (SELECT 1 FROM problem_classification_binding AS ch " +
            "WHERE ch.problem_id = catalog.problem_id AND ch.basis_revision_id = catalog.problem_revision_id " +
            "AND ch.dimension = 'CHAPTER' AND ch.label_id = :sectionId)) " +
            "AND (:masteryId IS NULL OR catalog.mastery_id = :masteryId) " +
            "GROUP BY classification.label_id, classification.display_name " +
            "ORDER BY COUNT(*) DESC, id ASC",
    )
    suspend fun searchKnowledgeFacets(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        masteryId: String?,
    ): List<LibraryFacetCountRow>

    @Query(
        "SELECT catalog.mastery_id AS id, catalog.mastery_id AS label, COUNT(*) AS count " +
            "FROM library_catalog AS catalog " +
            "JOIN library_search_content AS content " +
            "    ON content.problem_revision_id = catalog.problem_revision_id " +
            "JOIN library_search_fts ON library_search_fts.docid = content.content_row_id " +
            "WHERE library_search_fts MATCH :matchQuery " +
            "AND (:subjectId IS NULL OR catalog.subject = :subjectId) " +
            "AND (:sectionId IS NULL OR EXISTS (SELECT 1 FROM problem_classification_binding AS c " +
            "WHERE c.problem_id = catalog.problem_id AND c.basis_revision_id = catalog.problem_revision_id " +
            "AND c.dimension = 'CHAPTER' AND c.label_id = :sectionId)) " +
            "AND (:knowledgePointId IS NULL OR EXISTS (SELECT 1 FROM problem_classification_binding AS k " +
            "WHERE k.problem_id = catalog.problem_id AND k.basis_revision_id = catalog.problem_revision_id " +
            "AND k.dimension = 'KNOWLEDGE' AND k.label_id = :knowledgePointId)) " +
            "GROUP BY catalog.mastery_id ORDER BY COUNT(*) DESC, id ASC",
    )
    suspend fun searchMasteryFacets(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
    ): List<LibraryFacetCountRow>
}
