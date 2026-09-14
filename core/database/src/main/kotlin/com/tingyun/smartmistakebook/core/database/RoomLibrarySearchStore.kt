package com.tingyun.smartmistakebook.core.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room3.RoomRawQuery
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.LibraryFacetCountRow
import com.tingyun.smartmistakebook.core.database.dao.LibraryFtsSearchDao
import com.tingyun.smartmistakebook.core.database.entity.LibraryCatalogView
import kotlinx.coroutines.CancellationException

/** Library catalog reads and the incremental CJK FTS search projection. */
internal class RoomLibrarySearchStore(
    private val database: StudyDatabase,
) {
    fun pagingSource(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
    ): PagingSource<Int, LibraryCatalogRow> =
        MappingPagingSource(
            delegate = database.libraryQueryDao().pagingSource(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
                sort = sort,
            ),
            transform = LibraryCatalogView::toRow,
        )

    fun searchPagingSource(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
    ): PagingSource<Int, LibraryCatalogRow> {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        require(tokens.isNotEmpty()) { "FTS search needs at least one query token" }
        val primaryPhrase = CjkTextTokenizer.quotedPhrase(tokens.first())
        val neverMatchPhrase = CjkTextTokenizer.quotedPhrase("\uFFFD")
        val extras = tokens.drop(1).take(3).map(CjkTextTokenizer::quotedPhrase)
        return RefreshingPagingSource(
            beforeLoad = ::refreshProjection,
            delegate = MappingPagingSource(
                delegate = database.libraryFtsSearchDao().searchPagingSource(
                    buildLibrarySearchRawQuery(
                        matchQuery = matchQuery,
                        subjectId = subjectId,
                        sectionId = sectionId,
                        knowledgePointId = knowledgePointId,
                        masteryId = masteryId,
                        sort = sort,
                        primaryPhrase = primaryPhrase,
                        extraTokenPhrases = listOf(
                            extras.getOrElse(0) { neverMatchPhrase },
                            extras.getOrElse(1) { neverMatchPhrase },
                            extras.getOrElse(2) { neverMatchPhrase },
                        ),
                    ),
                ),
                transform = LibraryFtsSearchDao.LibrarySearchHitRow::toCatalogRow,
            ),
        )
    }

    /**
     * Builds the FTS4 search statement for [LibraryFtsSearchDao.searchPagingSource]
     * as a [RoomRawQuery].
     *
     * The weighted ranking sums per-column hit indicators expressed as
     * CASE WHEN EXISTS(...) constructs, which Room's @Query SQL parser
     * rejects; the statement therefore runs raw. Every dynamic value is
     * bound positionally through the binding function (never interpolated),
     * and the secondary sort term is chosen from a fixed whitelist, so no
     * caller-controlled text reaches the SQL.
     */
    private fun buildLibrarySearchRawQuery(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        primaryPhrase: String,
        extraTokenPhrases: List<String>,
        limit: Int? = null,
        offset: Int? = null,
    ): RoomRawQuery {
        val bindings = mutableListOf<Any>(matchQuery)
        val filters = StringBuilder()
        if (subjectId != null) {
            filters.append("\n  AND catalog.subject = ?")
            bindings += subjectId
        }
        if (sectionId != null) {
            filters.append(
                "\n  AND EXISTS (\n" +
                    "      SELECT 1 FROM problem_classification_binding AS classification\n" +
                    "      WHERE classification.problem_id = catalog.problem_id\n" +
                    "        AND classification.basis_revision_id = catalog.problem_revision_id\n" +
                    "        AND classification.dimension = 'CHAPTER'\n" +
                    "        AND classification.label_id = ?\n" +
                    "  )",
            )
            bindings += sectionId
        }
        if (knowledgePointId != null) {
            filters.append(
                "\n  AND EXISTS (\n" +
                    "      SELECT 1 FROM problem_classification_binding AS classification\n" +
                    "      WHERE classification.problem_id = catalog.problem_id\n" +
                    "        AND classification.basis_revision_id = catalog.problem_revision_id\n" +
                    "        AND classification.dimension = 'KNOWLEDGE'\n" +
                    "        AND classification.label_id = ?\n" +
                    "  )",
            )
            bindings += knowledgePointId
        }
        if (masteryId != null) {
            filters.append("\n  AND catalog.mastery_id = ?")
            bindings += masteryId
        }
        val ranking = StringBuilder()
        listOf(
            "stem_text" to 4,
            "solution_text" to 3,
            "knowledge_points" to 2,
            "subject" to 2,
            "options_text" to 1,
            "chapter" to 1,
            "tags" to 1,
            "error_reason" to 1,
            "formula_tokens" to 1,
        ).forEachIndexed { index, (column, weight) ->
            if (index > 0) ranking.append("\n  + ")
            ranking.append(
                "$weight * (CASE WHEN EXISTS (\n" +
                    "    SELECT 1 FROM library_search_fts AS ranked\n" +
                    "    WHERE ranked.docid = content.content_row_id\n" +
                    "      AND ranked.$column MATCH ?\n" +
                    ") THEN 1 ELSE 0 END)",
            )
            bindings += primaryPhrase
        }
        extraTokenPhrases.forEach { phrase ->
            ranking.append(
                "\n  + (CASE WHEN EXISTS (\n" +
                    "    SELECT 1 FROM library_search_fts\n" +
                    "    WHERE library_search_fts.docid = content.content_row_id\n" +
                    "      AND library_search_fts MATCH ?\n" +
                    ") THEN 1 ELSE 0 END)",
            )
            bindings += phrase
        }
        val sortClause = when (sort) {
            "RECENTLY_CREATED" -> "catalog.created_at_epoch_millis DESC,\n    "
            "NEXT_REVIEW" -> "catalog.next_review_at_epoch_millis ASC,\n    "
            "LEAST_MASTERED" -> "$LEAST_MASTERED_MASTERY_SQL ASC,\n    "
            else -> ""
        }
        if (limit != null) {
            bindings += limit.toLong()
            bindings += offset!!.toLong()
        }
        val sql = "SELECT catalog.*,\n" +
            "       snippet(library_search_fts, '【', '】', '…', -1, 12) AS snippet\n" +
            "FROM library_search_fts\n" +
            "JOIN library_search_content AS content\n" +
            "    ON content.content_row_id = library_search_fts.docid\n" +
            "JOIN library_catalog AS catalog\n" +
            "    ON catalog.problem_revision_id = content.problem_revision_id\n" +
            "WHERE library_search_fts MATCH ?$filters\n" +
            "ORDER BY (\n" +
            "    $ranking\n" +
            ") DESC,\n" +
            "    $sortClause" +
            "catalog.updated_at_epoch_millis DESC,\n" +
            "    catalog.entry_id ASC" +
            if (limit != null) "\nLIMIT ? OFFSET ?" else ""
        val orderedBindings = bindings.toList()
        return RoomRawQuery(sql) { statement ->
            orderedBindings.forEachIndexed { index, value ->
                when (value) {
                    is Int -> statement.bindLong(index + 1, value.toLong())
                    is Long -> statement.bindLong(index + 1, value)
                    else -> statement.bindText(index + 1, value as String)
                }
            }
        }
    }

    suspend fun searchCount(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        refreshProjection()
        return database.libraryFtsSearchDao().countSearch(
            matchQuery = matchQuery,
            subjectId = subjectId,
            sectionId = sectionId,
            knowledgePointId = knowledgePointId,
            masteryId = masteryId,
        )
    }

    suspend fun searchPage(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        require(tokens.isNotEmpty()) { "FTS search needs at least one query token" }
        refreshProjection()
        val primaryPhrase = CjkTextTokenizer.quotedPhrase(tokens.first())
        val neverMatchPhrase = CjkTextTokenizer.quotedPhrase("\uFFFD")
        val extras = tokens.drop(1).take(3).map(CjkTextTokenizer::quotedPhrase)
        return database.libraryFtsSearchDao().searchPage(
            buildLibrarySearchRawQuery(
                matchQuery = matchQuery,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
                sort = sort,
                primaryPhrase = primaryPhrase,
                extraTokenPhrases = listOf(
                    extras.getOrElse(0) { neverMatchPhrase },
                    extras.getOrElse(1) { neverMatchPhrase },
                    extras.getOrElse(2) { neverMatchPhrase },
                ),
                limit = limit,
                offset = offset,
            ),
        ).map { it.toCatalogRow() }
    }

    suspend fun searchFacets(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        refreshProjection()
        val dao = database.libraryFtsSearchDao()
        return when (facet) {
            "SUBJECT" -> dao.searchSubjectFacets(
                matchQuery = matchQuery,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            )
            "SECTION" -> dao.searchSectionFacets(
                matchQuery = matchQuery,
                subjectId = subjectId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            )
            "KNOWLEDGE_POINT" -> dao.searchKnowledgeFacets(
                matchQuery = matchQuery,
                subjectId = subjectId,
                sectionId = sectionId,
                masteryId = masteryId,
            )
            "MASTERY" -> dao.searchMasteryFacets(
                matchQuery = matchQuery,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
            )
            else -> error("Unsupported library facet kind: $facet")
        }.map(LibraryFacetCountRow::toRecord)
    }

    /**
     * Idempotently create FTS content-sync and outbox triggers on the raw
     * connection. Room rejects DDL in @Query, so this runs outside the DAO;
     * IF NOT EXISTS keeps repeat calls cheap.
     */
    private suspend fun ensureSearchTriggers() {
        database.withRawConnection(isReadOnly = false) { connection ->
        listOf(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `library_search_content` BEGIN " +
                "DELETE FROM `library_search_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `library_search_content` BEGIN " +
                "DELETE FROM `library_search_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `library_search_content` BEGIN " +
                "INSERT INTO `library_search_fts`(`docid`, `stem_text`, `options_text`, " +
                "`solution_text`, `subject`, `chapter`, `knowledge_points`, `tags`, " +
                "`error_reason`, `formula_tokens`) VALUES (NEW.`rowid`, NEW.`stem_text`, " +
                "NEW.`options_text`, NEW.`solution_text`, NEW.`subject`, NEW.`chapter`, " +
                "NEW.`knowledge_points`, NEW.`tags`, NEW.`error_reason`, " +
                "NEW.`formula_tokens`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_AFTER_INSERT " +
                "AFTER INSERT ON `library_search_content` BEGIN " +
                "INSERT INTO `library_search_fts`(`docid`, `stem_text`, `options_text`, " +
                "`solution_text`, `subject`, `chapter`, `knowledge_points`, `tags`, " +
                "`error_reason`, `formula_tokens`) VALUES (NEW.`rowid`, NEW.`stem_text`, " +
                "NEW.`options_text`, NEW.`solution_text`, NEW.`subject`, NEW.`chapter`, " +
                "NEW.`knowledge_points`, NEW.`tags`, NEW.`error_reason`, " +
                "NEW.`formula_tokens`); END",
            "CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_insert " +
                "AFTER INSERT ON `problem_revision` BEGIN " +
                "INSERT INTO `library_search_outbox` (`revision_id`, `queued_at_epoch_millis`) " +
                "VALUES (NEW.`revision_id`, NEW.`created_at_epoch_millis`); END",
            "CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_update " +
                "AFTER UPDATE ON `problem_revision` BEGIN " +
                "INSERT INTO `library_search_outbox` (`revision_id`, `queued_at_epoch_millis`) " +
                "VALUES (NEW.`revision_id`, NEW.`created_at_epoch_millis`); END",
        ).forEach { sql ->
            connection.usePrepared(sql) { statement -> statement.step() }
        }
        }
    }

    suspend fun refreshProjection() {
        database.withWriteTransaction {
            val dao = database.libraryFtsSearchDao()
            ensureSearchTriggers()
            if (dao.countIndexed() == 0) {
                // First bootstrap (or repair): re-segment everything we know
                // about - the active library plus every already-materialized
                // row; stale content rows drop out through the projection read.
                val targets = (dao.readActiveLibraryRevisionIds() +
                    dao.readIndexedRevisionIds()).distinct()
                for (revisionId in targets) {
                    drainSearchRevision(dao, revisionId)
                }
            } else {
                // Incremental path: drain the outbox revision by revision;
                // each upsert/delete is mirrored into the FTS index by the
                // room_fts_content_sync triggers.
                while (true) {
                    val batch = dao.readOutboxBatch()
                    if (batch.isEmpty()) break
                    for (revisionId in batch.distinct()) {
                        drainSearchRevision(dao, revisionId)
                    }
                }
            }
        }
    }

    private suspend fun drainSearchRevision(
        dao: LibraryFtsSearchDao,
        revisionId: String,
    ) {
        val row = dao.readRevisionForProjection(revisionId)
        if (row == null) {
            // Dropped from the library: the BEFORE_DELETE sync trigger
            // removes the FTS row alongside the content row.
            dao.deleteContent(revisionId)
        } else {
            val stem = CjkTextTokenizer.segment(row.title + "\n" + row.problemMarkdown)
            val options = CjkTextTokenizer.segment(row.questionDocumentSnapshot.orEmpty())
            val solution = CjkTextTokenizer.segment(row.answerSpecSnapshot.orEmpty())
            val subject = CjkTextTokenizer.segment(row.subject)
            val chapter = CjkTextTokenizer.segment(row.chapter)
            val knowledge = CjkTextTokenizer.segment(row.knowledgePoints)
            val tags = CjkTextTokenizer.segment(row.tags)
            val errorReason = CjkTextTokenizer.segment(row.errorReason)
            val formulaTokens = FormulaSearchProjection.tokensForSnapshot(
                row.questionDocumentSnapshot,
            )
            if (dao.countContentFor(revisionId) > 0) {
                dao.updateContent(
                    revisionId = revisionId,
                    stemText = stem,
                    optionsText = options,
                    solutionText = solution,
                    subject = subject,
                    chapter = chapter,
                    knowledgePoints = knowledge,
                    tags = tags,
                    errorReason = errorReason,
                    formulaTokens = formulaTokens,
                )
            } else {
                dao.insertContent(
                    revisionId = revisionId,
                    stemText = stem,
                    optionsText = options,
                    solutionText = solution,
                    subject = subject,
                    chapter = chapter,
                    knowledgePoints = knowledge,
                    tags = tags,
                    errorReason = errorReason,
                    formulaTokens = formulaTokens,
                )
            }
        }
        dao.clearOutboxFor(revisionId)
    }

    /** Rebuilds one revision's FTS row from its projection (used on restore). */
    internal suspend fun reindexRevision(revisionId: String) {
        drainSearchRevision(database.libraryFtsSearchDao(), revisionId)
    }

    suspend fun catalogPage(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> {
        return database.libraryQueryDao()
            .page(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
                sort = sort,
                offset = offset,
                limit = limit,
            )
            .map(LibraryCatalogView::toRow)
    }

    suspend fun catalogCount(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int {
        return database.libraryQueryDao().count(
            searchText = searchText,
            subjectId = subjectId,
            sectionId = sectionId,
            knowledgePointId = knowledgePointId,
            masteryId = masteryId,
        )
    }

    suspend fun catalogFacets(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> {
        return when (facet) {
            "SUBJECT" -> database.libraryQueryDao().subjectFacets(
                searchText = searchText,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            ).map(LibraryFacetCountRow::toRecord)

            "SECTION" -> database.libraryQueryDao().sectionFacets(
                searchText = searchText,
                subjectId = subjectId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            ).map(LibraryFacetCountRow::toRecord)

            "KNOWLEDGE_POINT" -> database.libraryQueryDao().knowledgeFacets(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                masteryId = masteryId,
            ).map(LibraryFacetCountRow::toRecord)

            "MASTERY" -> database.libraryQueryDao().masteryFacets(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
            ).map(LibraryFacetCountRow::toRecord)

            else -> error("Unsupported library facet kind: $facet")
        }
    }
}

/** Shared catalog-label decoding: unit-separated, trimmed, deduplicated, sorted. */
internal fun String?.toCatalogLabels(): List<String> = this
    ?.split("\u001F")
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    ?.sorted()
    .orEmpty()

private fun LibraryCatalogView.toRow() = LibraryCatalogRow(
    entryId = entryId,
    title = title,
    problemMarkdown = problemMarkdown,
    subject = subject,
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    masteryId = masteryId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
)

private fun LibraryFacetCountRow.toRecord() = LibraryFacetCountRecord(
    id = id,
    label = label,
    count = count,
)

private fun LibraryFtsSearchDao.LibrarySearchHitRow.toCatalogRow() = LibraryCatalogRow(
    entryId = entryId,
    title = title,
    problemMarkdown = problemMarkdown,
    subject = subject,
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    masteryId = masteryId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
)

private class RefreshingPagingSource<T : Any>(
    private val beforeLoad: suspend () -> Unit,
    private val delegate: PagingSource<Int, T>,
) : PagingSource<Int, T>() {
    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        return delegate.getRefreshKey(state)
    }
    override suspend fun load(params: PagingSource.LoadParams<Int>): PagingSource.LoadResult<Int, T> {
        try {
            beforeLoad()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Projection refresh is best-effort; delegate load still proceeds.
        }
        return delegate.load(params)
    }
}
