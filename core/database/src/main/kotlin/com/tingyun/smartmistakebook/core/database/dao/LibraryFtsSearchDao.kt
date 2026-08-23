package com.tingyun.smartmistakebook.core.database.dao

import androidx.paging.PagingSource
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter

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
interface LibraryFtsSearchDao {

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
    // Sync / outbox triggers (created lazily, byte-identical to 32.json)
    // ------------------------------------------------------------------

    @Query(
        "SELECT COUNT(*) FROM sqlite_master " +
            "WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_library_search_fts%'",
    )
    suspend fun countFtsSyncTriggers(): Int

    @Query(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `library_search_content` BEGIN " +
            "DELETE FROM `library_search_fts` WHERE `docid`=OLD.`rowid`; END",
    )
    suspend fun createFtsSyncBeforeUpdateTrigger()

    @Query(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `library_search_content` BEGIN " +
            "DELETE FROM `library_search_fts` WHERE `docid`=OLD.`rowid`; END",
    )
    suspend fun createFtsSyncBeforeDeleteTrigger()

    @Query(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `library_search_content` BEGIN " +
            "INSERT INTO `library_search_fts`(`docid`, `stem_text`, `options_text`, " +
            "`solution_text`, `subject`, `chapter`, `knowledge_points`, `tags`, " +
            "`error_reason`, `formula_tokens`) VALUES (NEW.`rowid`, NEW.`stem_text`, " +
            "NEW.`options_text`, NEW.`solution_text`, NEW.`subject`, NEW.`chapter`, " +
            "NEW.`knowledge_points`, NEW.`tags`, NEW.`error_reason`, " +
            "NEW.`formula_tokens`); END",
    )
    suspend fun createFtsSyncAfterUpdateTrigger()

    @Query(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_AFTER_INSERT " +
            "AFTER INSERT ON `library_search_content` BEGIN " +
            "INSERT INTO `library_search_fts`(`docid`, `stem_text`, `options_text`, " +
            "`solution_text`, `subject`, `chapter`, `knowledge_points`, `tags`, " +
            "`error_reason`, `formula_tokens`) VALUES (NEW.`rowid`, NEW.`stem_text`, " +
            "NEW.`options_text`, NEW.`solution_text`, NEW.`subject`, NEW.`chapter`, " +
            "NEW.`knowledge_points`, NEW.`tags`, NEW.`error_reason`, " +
            "NEW.`formula_tokens`); END",
    )
    suspend fun createFtsSyncAfterInsertTrigger()

    @Query(
        "SELECT COUNT(*) FROM sqlite_master " +
            "WHERE type = 'trigger' AND name LIKE 'library_search_outbox_revision%'",
    )
    suspend fun countOutboxTriggers(): Int

    @Query(
        "CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_insert " +
            "AFTER INSERT ON `problem_revision` BEGIN " +
            "INSERT INTO `library_search_outbox` (`revision_id`, `queued_at_epoch_millis`) " +
            "VALUES (NEW.`revision_id`, NEW.`created_at_epoch_millis`); END",
    )
    suspend fun createOutboxInsertTrigger()

    @Query(
        "CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_update " +
            "AFTER UPDATE ON `problem_revision` BEGIN " +
            "INSERT INTO `library_search_outbox` (`revision_id`, `queued_at_epoch_millis`) " +
            "VALUES (NEW.`revision_id`, NEW.`created_at_epoch_millis`); END",
    )
    suspend fun createOutboxUpdateTrigger()

    // ------------------------------------------------------------------
    // Matching queries (FTS4; bm25() is FTS5-only and must never be used)
    // ------------------------------------------------------------------

    /**
     * Relevance-ranked paged search. [matchQuery] is the implicit-AND MATCH
     * expression produced by CjkTextTokenizer on the query side (all query
     * tokens must appear); [primaryStemPhrase]..[primaryFormulaPhrase] are the
     * column-scoped phrase forms of the FIRST query token used to compute the
     * weighted column-hit score, and the extraTokenPhrase parameters are the
     * remaining query tokens (quoted phrases) used as tie-breaker boosts.
     * Facet filters mirror LibraryQueryDao exactly by joining the
     * library_catalog view.
     */
    @Query(
        """
        SELECT catalog.*,
               snippet('library_search_fts', '【', '】', '…', -1, 12) AS snippet
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
        ORDER BY (
            4 * (CASE WHEN library_search_fts.stem_text MATCH :primaryStemPhrase THEN 1 ELSE 0 END)
          + 3 * (CASE WHEN library_search_fts.solution_text MATCH :primarySolutionPhrase THEN 1 ELSE 0 END)
          + 2 * (CASE WHEN library_search_fts.knowledge_points MATCH :primaryKnowledgePhrase THEN 1 ELSE 0 END)
          + 2 * (CASE WHEN library_search_fts.subject MATCH :primarySubjectPhrase THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts.options_text MATCH :primaryOptionsPhrase THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts.chapter MATCH :primaryChapterPhrase THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts.tags MATCH :primaryTagsPhrase THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts.error_reason MATCH :primaryErrorReasonPhrase THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts.formula_tokens MATCH :primaryFormulaPhrase THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts MATCH :extraTokenPhrase1 THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts MATCH :extraTokenPhrase2 THEN 1 ELSE 0 END)
          + (CASE WHEN library_search_fts MATCH :extraTokenPhrase3 THEN 1 ELSE 0 END)
        ) DESC,
            CASE :sort WHEN 'RECENTLY_CREATED' THEN catalog.created_at_epoch_millis END DESC,
            CASE :sort WHEN 'NEXT_REVIEW' THEN catalog.next_review_at_epoch_millis END ASC,
            CASE :sort WHEN 'LEAST_MASTERED' THEN catalog.retrievability END ASC,
            catalog.updated_at_epoch_millis DESC,
            catalog.entry_id ASC
        """,
    )
    fun searchPagingSource(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        primaryStemPhrase: String,
        primaryOptionsPhrase: String,
        primarySolutionPhrase: String,
        primarySubjectPhrase: String,
        primaryChapterPhrase: String,
        primaryKnowledgePhrase: String,
        primaryTagsPhrase: String,
        primaryErrorReasonPhrase: String,
        primaryFormulaPhrase: String,
        extraTokenPhrase1: String,
        extraTokenPhrase2: String,
        extraTokenPhrase3: String,
    ): PagingSource<Int, LibrarySearchHitRow>

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
}
