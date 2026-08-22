package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Incremental maintenance and matching for the library FTS projection
 * (audit section 8.2 / PR-09). The drain path re-segments queued revisions,
 * upserts content rows, then issues the FTS4 'rebuild' command so the index
 * can never go stale after insert/update/delete.
 */
@Dao
interface LibraryFtsSearchDao {

    /** Projection row read for re-segmentation during outbox drain. */
    data class RevisionProjectionRow(
        @androidx.room3.ColumnInfo(name = "revision_id")
        val revisionId: String,
        val title: String,
        @androidx.room3.ColumnInfo(name = "problem_markdown")
        val problemMarkdown: String,
    )

    @Query(
        """
        SELECT revision_id FROM library_search_outbox
        ORDER BY outbox_id ASC
        LIMIT 500
        """,
    )
    suspend fun readOutboxBatch(): List<String>

    @Query("SELECT COUNT(*) FROM library_search_fts")
    suspend fun countIndexed(): Int

    @Query("SELECT COUNT(*) FROM problem_revision")
    suspend fun countRevisions(): Int

    @Query(
        """
        SELECT revision.revision_id, revision.title, revision.problem_markdown
        FROM problem_revision AS revision
        WHERE revision.revision_id = :revisionId
        """,
    )
    suspend fun readRevisionForProjection(revisionId: String): RevisionProjectionRow?

    @Query(
        """
        INSERT INTO library_search_content
            (problem_revision_id, stem_text, options_text, solution_text, subject,
             chapter, knowledge_points, tags, error_reason, formula_tokens)
        VALUES (:revisionId, :stemText, '', '', '', '', '', '', '', '')
        """,
    )
    suspend fun insertContent(
        revisionId: String,
        stemText: String,
    )

    @Query(
        """
        UPDATE library_search_content
        SET stem_text = :stemText
        WHERE problem_revision_id = :revisionId
        """,
    )
    suspend fun updateContentStem(revisionId: String, stemText: String)

    @Query("DELETE FROM library_search_content WHERE problem_revision_id = :revisionId")
    suspend fun deleteContent(revisionId: String)

    @Query("DELETE FROM library_search_outbox WHERE revision_id = :revisionId")
    suspend fun clearOutboxFor(revisionId: String)

    @Query(
        "SELECT COUNT(*) FROM library_search_content WHERE problem_revision_id = :revisionId",
    )
    suspend fun countContentFor(revisionId: String): Int

    /** Full projection backfill source: every current library revision. */
    @Query(
        """
        SELECT entry.current_revision_id AS revisionId
        FROM error_book_entry AS entry
        WHERE entry.status = 'ACTIVE'
        """,
    )
    suspend fun readActiveLibraryRevisionIds(): List<String>

    @Query("DELETE FROM library_search_content")
    suspend fun clearAllContent()

    @Query("DELETE FROM library_search_outbox")
    suspend fun clearOutbox()

    /** Rebuilds the external-content FTS index from library_search_content. */
    @Query("INSERT INTO library_search_fts(library_search_fts) VALUES('rebuild')")
    suspend fun rebuildIndex()

}
