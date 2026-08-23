package com.tingyun.smartmistakebook.core.data.library

import androidx.paging.PagingSource
import com.tingyun.smartmistakebook.core.database.CjkTextTokenizer
import com.tingyun.smartmistakebook.core.database.LibraryCatalogRow
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogItem
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogPage
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogRepository
import com.tingyun.smartmistakebook.core.domain.LibraryFacetCount
import com.tingyun.smartmistakebook.core.domain.LibraryFacetKind
import com.tingyun.smartmistakebook.core.domain.LibraryQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomLibraryCatalogRepository(
    private val database: StudyDatabasePort,
) : LibraryCatalogRepository {
    override fun pagingSource(query: LibraryQuery): PagingSource<Int, LibraryCatalogItem> {
        val searchText = query.searchText.trim()
        val tokens = CjkTextTokenizer.tokens(searchText)
        if (tokens.isNotEmpty()) {
            // FTS path: query side applies the exact same CJK segmentation
            // used when indexing, then ranks by weighted column hits.
            return MappingPagingSource(
                delegate = database.librarySearchPagingSource(
                    matchQuery = CjkTextTokenizer.matchExpression(searchText),
                    subjectId = query.subjectId,
                    sectionId = query.sectionId,
                    knowledgePointId = query.knowledgePointId,
                    masteryId = query.masteryId,
                    sort = query.sort.name,
                    tokens = tokens,
                ),
                transform = LibraryCatalogRow::toCatalogItem,
            )
        }
        // Fallback for blank search text: the instr(lower()) catalog path.
        return MappingPagingSource(
            delegate = database.libraryPagingSource(
                searchText = searchText,
                subjectId = query.subjectId,
                sectionId = query.sectionId,
                knowledgePointId = query.knowledgePointId,
                masteryId = query.masteryId,
                sort = query.sort.name,
            ),
            transform = LibraryCatalogRow::toCatalogItem,
        )
    }

    override suspend fun totalCount(query: LibraryQuery): Int = withContext(Dispatchers.IO) {
        val searchText = query.searchText.trim()
        val tokens = CjkTextTokenizer.tokens(searchText)
        if (tokens.isNotEmpty()) {
            database.librarySearchCount(
                matchQuery = CjkTextTokenizer.matchExpression(searchText),
                subjectId = query.subjectId,
                sectionId = query.sectionId,
                knowledgePointId = query.knowledgePointId,
                masteryId = query.masteryId,
            )
        } else {
            database.libraryCatalogCount(
                searchText = searchText,
                subjectId = query.subjectId,
                sectionId = query.sectionId,
                knowledgePointId = query.knowledgePointId,
                masteryId = query.masteryId,
            )
        }
    }

    override suspend fun query(
        query: LibraryQuery,
        offset: Int,
        limit: Int,
    ): LibraryCatalogPage = withContext(Dispatchers.IO) {
        require(offset >= 0 && limit > 0) { "Library page window is invalid" }
        val page = database.libraryCatalogPage(
            searchText = query.searchText.trim(),
            subjectId = query.subjectId,
            sectionId = query.sectionId,
            knowledgePointId = query.knowledgePointId,
            masteryId = query.masteryId,
            sort = query.sort.name,
            offset = offset,
            limit = limit,
        )
        LibraryCatalogPage(
            items = page.map(LibraryCatalogRow::toCatalogItem),
            totalCount = totalCount(query),
            offset = offset,
            limit = limit,
        )
    }

    override suspend fun facets(
        query: LibraryQuery,
        facet: LibraryFacetKind,
    ): List<LibraryFacetCount> = withContext(Dispatchers.IO) {
        database.libraryCatalogFacets(
            searchText = query.searchText.trim(),
            subjectId = query.subjectId,
            sectionId = query.sectionId,
            knowledgePointId = query.knowledgePointId,
            masteryId = query.masteryId,
            facet = facet.name,
        ).map { row ->
            LibraryFacetCount(
                id = row.id,
                label = if (facet == LibraryFacetKind.MASTERY) {
                    masteryLabel(row.id)
                } else {
                    row.label
                },
                count = row.count,
            )
        }
    }
}

object LibraryCatalogRepositoryFactory {
    fun create(database: StudyDatabasePort): LibraryCatalogRepository =
        RoomLibraryCatalogRepository(database)
}

private fun LibraryCatalogRow.toCatalogItem() = LibraryCatalogItem(
    entryId = entryId,
    title = title,
    summary = com.tingyun.smartmistakebook.core.model.ReadableMathText.inlineMarkdown(
        problemMarkdown,
        lineBreakReplacement = ' ',
    ).lineSequence()
        .joinToString(separator = " ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .take(64),
    subjectId = subject,
    chapterLabels = chapterLabels,
    knowledgeLabels = knowledgeLabels,
    masteryId = masteryId,
    updatedAtEpochMillis = updatedAtEpochMillis,
    createdAtEpochMillis = createdAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
)

private fun masteryLabel(id: String): String = when (id) {
    "unknown" -> "暂无学习记录"
    "learning" -> "学习中"
    "mastered" -> "已掌握"
    "conflicted" -> "需巩固"
    "stale" -> "待复习"
    else -> id
}
