package com.tingyun.smartmistakebook.core.domain


enum class LibrarySort {
    RECENTLY_UPDATED,
    RECENTLY_CREATED,
    NEXT_REVIEW,
    LEAST_MASTERED,
}

data class LibraryQuery(
    val searchText: String = "",
    val subjectId: String? = null,
    val sectionId: String? = null,
    val knowledgePointId: String? = null,
    val masteryId: String? = null,
    val sort: LibrarySort = LibrarySort.RECENTLY_UPDATED,
)

data class LibraryCatalogItem(
    val entryId: String,
    val title: String,
    val summary: String,
    val subjectId: String,
    val chapterLabels: List<String>,
    val knowledgeLabels: List<String>,
    val masteryId: String,
    val updatedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val nextReviewAtEpochMillis: Long?,
    val retrievability: Double?,
) {
    init {
        require(entryId.isNotBlank()) { "Library item id must not be blank" }
        require(title.isNotBlank()) { "Library item title must not be blank" }
        require(subjectId.isNotBlank()) { "Library item subject must not be blank" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Library item update time cannot precede creation"
        }
    }
}

data class LibraryCatalogPage(
    val items: List<LibraryCatalogItem>,
    val totalCount: Int,
    val offset: Int,
    val limit: Int,
) {
    init {
        require(offset >= 0 && limit > 0) { "Library page window is invalid" }
        require(totalCount >= items.size) { "Library page cannot exceed total count" }
    }

    val hasMore: Boolean
        get() = offset + items.size < totalCount
}

data class LibraryFacetCount(
    val id: String,
    val label: String,
    val count: Int,
) {
    init {
        require(id.isNotBlank() && label.isNotBlank()) {
            "Library facet option must have an id and label"
        }
        require(count >= 0) { "Library facet count must not be negative" }
    }
}

interface LibraryCatalogRepository {
    suspend fun totalCount(query: LibraryQuery): Int

    suspend fun query(
        query: LibraryQuery,
        offset: Int,
        limit: Int,
    ): LibraryCatalogPage

    suspend fun facets(
        query: LibraryQuery,
        facet: LibraryFacetKind,
    ): List<LibraryFacetCount>
}

enum class LibraryFacetKind {
    SUBJECT,
    SECTION,
    KNOWLEDGE_POINT,
    MASTERY,
}
