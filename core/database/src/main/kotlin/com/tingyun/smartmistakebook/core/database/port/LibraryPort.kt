package com.tingyun.smartmistakebook.core.database.port

import androidx.paging.PagingSource
import com.tingyun.smartmistakebook.core.database.LibraryCatalogRow
import com.tingyun.smartmistakebook.core.database.LibraryFacetCountRecord

/**
 * Read-only port for library catalog operations.
 * Provides paging, counting, and faceted search.
 */
interface LibraryReadPort {
    fun libraryPagingSource(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
    ): PagingSource<Int, LibraryCatalogRow>

    suspend fun libraryCatalogPage(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow>

    suspend fun libraryCatalogCount(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int

    suspend fun libraryCatalogFacets(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord>

    /**
     * FTS-backed paging source for non-blank search text. [matchQuery] is the
     * implicit-AND FTS4 MATCH expression and [tokens] are the index-aligned
     * query tokens used for relevance ranking; the implementation refreshes
     * the search projection incrementally before loading pages. Default is a
     * no-op hook so fakes without an FTS index keep compiling.
     */
    fun librarySearchPagingSource(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
    ): PagingSource<Int, LibraryCatalogRow> =
        error("FTS library search is not backed by this database port")

    /** Counting twin of [librarySearchPagingSource]. */
    suspend fun librarySearchCount(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int = error("FTS library search is not backed by this database port")

    /** Offset-paged twin of [librarySearchPagingSource] with identical ranking. */
    suspend fun librarySearchPage(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> = error("FTS library search is not backed by this database port")

    /** FTS-scoped facet counts ([facet] mirrors LibraryQueryDao facet kinds). */
    suspend fun librarySearchFacets(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> =
        error("FTS library search is not backed by this database port")

    /**
     * Drains the library search outbox and applies pending revisions to the
     * FTS projection row by row. Safe to call repeatedly; incremental by
     * design (a full rebuild happens only as a repair).
     */
    suspend fun refreshLibrarySearchProjection() {
        // No-op default for fakes without an FTS index.
    }
}
