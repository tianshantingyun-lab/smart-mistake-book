package com.tingyun.smartmistakebook.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogItem
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogRepository
import com.tingyun.smartmistakebook.core.domain.LibraryFacetKind as DomainLibraryFacetKind
import com.tingyun.smartmistakebook.core.domain.LibraryQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var catalog = LibraryCatalog(emptyList())
    private var catalogRepository: LibraryCatalogRepository? = null
    private val repositoryFlow = MutableStateFlow<LibraryCatalogRepository?>(null)
    /** 分页源由装配点交进来（审计 R-02）：域接口不再提到 Paging。 */
    private var pagingSourceFor: ((LibraryQuery) -> PagingSource<Int, LibraryCatalogItem>)? = null

    private val initialActiveFacet = LibraryFacet.fromId(savedStateHandle[ACTIVE_FACET_KEY])
    private val initialSelections = LibrarySelections(
        subject = restoredSelection(LibraryFacet.SUBJECT),
        chapter = restoredSelection(LibraryFacet.CHAPTER),
        knowledge = restoredSelection(LibraryFacet.KNOWLEDGE),
        mastery = restoredSelection(LibraryFacet.MASTERY),
    )

    private val queryFlow = MutableStateFlow(
        LibraryQuery(
            searchText = savedStateHandle[QUERY_KEY] ?: "",
            subjectId = initialSelections.subject,
            sectionId = initialSelections.chapter,
            knowledgePointId = initialSelections.knowledge,
            masteryId = initialSelections.mastery,
        ),
    )

    val pagingData: Flow<PagingData<LibraryCatalogItem>> = combine(
        repositoryFlow,
        queryFlow,
    ) { repository, query -> repository to query }
        .flatMapLatest { (repository, query) ->
            val pagingSourceFor = pagingSourceFor
            if (repository == null || pagingSourceFor == null) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = 10,
                        initialLoadSize = 60,
                    ),
                    pagingSourceFactory = { pagingSourceFor(query) },
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    var uiState: LibraryUiState by mutableStateOf(
        deriveUiState(
            query = queryFlow.value.searchText,
            activeFacet = initialActiveFacet,
            selections = initialSelections,
        ),
    )
        private set

    fun updateCatalog(mistakes: List<LibraryMistake>) {
        if (catalogRepository != null) return
        catalog = LibraryCatalog(mistakes)
        val normalizedSelections = LibraryFacet.entries.fold(LibrarySelections()) { selections, facet ->
            selections.withSelection(
                facet,
                catalog.normalizeSelection(
                    facet,
                    uiState.selections.selectedOptionId(facet),
                    selections,
                ),
            )
        }
        persistSelections(normalizedSelections)
        uiState = deriveUiState(
            query = uiState.query,
            activeFacet = uiState.activeFacet,
            selections = normalizedSelections,
        )
    }

    fun bindRepository(
        repository: LibraryCatalogRepository,
        pagingSourceFor: ((LibraryQuery) -> PagingSource<Int, LibraryCatalogItem>)? = null,
    ) {
        if (catalogRepository === repository && this.pagingSourceFor === pagingSourceFor) return
        catalogRepository = repository
        this.pagingSourceFor = pagingSourceFor
        repositoryFlow.value = repository
        refreshFromRepository()
    }

    fun updateQuery(value: String) {
        if (value == uiState.query) return
        savedStateHandle[QUERY_KEY] = value
        if (catalogRepository != null) {
            uiState = uiState.copy(query = value, loaded = false)
            refreshFromRepository()
            return
        }
        uiState = deriveUiState(
            query = value,
            activeFacet = uiState.activeFacet,
            selections = uiState.selections,
        )
    }

    fun selectFacet(facet: LibraryFacet) {
        if (facet == uiState.activeFacet) return
        savedStateHandle[ACTIVE_FACET_KEY] = facet.id
        uiState = uiState.copy(
            activeFacet = facet,
            activeOptions = catalog.optionsFor(facet, uiState.selections),
        )
        if (catalogRepository != null) refreshFromRepository()
    }

    fun toggleFilter(facet: LibraryFacet, optionId: String?) {
        if (
            optionId != null &&
            catalog.optionsFor(facet, uiState.selections).none { it.id == optionId }
        ) return
        val currentSelection = uiState.selections.selectedOptionId(facet)
        val nextSelection = if (optionId != null && currentSelection == optionId) null else optionId
        if (nextSelection == currentSelection) return
        val nextSelections = when (facet) {
            LibraryFacet.SUBJECT -> uiState.selections.copy(
                subject = nextSelection,
                chapter = null,
                knowledge = null,
            )
            LibraryFacet.CHAPTER -> uiState.selections.copy(
                chapter = nextSelection,
                knowledge = null,
            )
            LibraryFacet.KNOWLEDGE -> uiState.selections.copy(knowledge = nextSelection)
            LibraryFacet.MASTERY -> uiState.selections.copy(mastery = nextSelection)
        }
        persistSelections(nextSelections)
        if (catalogRepository != null) {
            uiState = uiState.copy(selections = nextSelections, loaded = false)
            refreshFromRepository()
            return
        }
        uiState = deriveUiState(
            query = uiState.query,
            activeFacet = uiState.activeFacet,
            selections = nextSelections,
        )
    }

    fun clearAll() {
        savedStateHandle[QUERY_KEY] = ""
        LibraryFacet.entries.forEach { facet ->
            savedStateHandle[selectionKey(facet)] = null
        }
        if (catalogRepository != null) {
            uiState = uiState.copy(
                query = "",
                selections = LibrarySelections(),
                loaded = false,
            )
            refreshFromRepository()
            return
        }
        uiState = deriveUiState(
            query = "",
            activeFacet = uiState.activeFacet,
            selections = LibrarySelections(),
        )
    }

    private fun restoredSelection(facet: LibraryFacet): String? =
        savedStateHandle[selectionKey(facet)]

    private fun deriveUiState(
        query: String,
        activeFacet: LibraryFacet,
        selections: LibrarySelections,
    ): LibraryUiState = LibraryUiState(
        query = query,
        activeFacet = activeFacet,
        selections = selections,
        activeOptions = catalog.optionsFor(activeFacet, selections),
        visibleMistakes = catalog.filter(query, selections),
    )

    private fun persistSelections(selections: LibrarySelections) {
        LibraryFacet.entries.forEach { facet ->
            savedStateHandle[selectionKey(facet)] = selections.selectedOptionId(facet)
        }
    }

    private fun refreshFromRepository() {
        val repository = catalogRepository ?: return
        val domainQuery = LibraryQuery(
            searchText = uiState.query,
            subjectId = uiState.selections.subject,
            sectionId = uiState.selections.chapter,
            knowledgePointId = uiState.selections.knowledge,
            masteryId = uiState.selections.mastery,
        )
        queryFlow.value = domainQuery
        viewModelScope.launch {
            val count = repository.totalCount(domainQuery)
            val facetOptions = when (uiState.activeFacet) {
                LibraryFacet.SUBJECT -> repository.facets(
                    domainQuery,
                    DomainLibraryFacetKind.SUBJECT,
                )
                LibraryFacet.CHAPTER -> repository.facets(
                    domainQuery,
                    DomainLibraryFacetKind.SECTION,
                )
                LibraryFacet.KNOWLEDGE -> repository.facets(
                    domainQuery,
                    DomainLibraryFacetKind.KNOWLEDGE_POINT,
                )
                LibraryFacet.MASTERY -> repository.facets(
                    domainQuery,
                    DomainLibraryFacetKind.MASTERY,
                )
            }.map { facet -> LibraryFacetOption(facet.id, facet.label, facet.count) }
            uiState = uiState.copy(
                activeOptions = facetOptions,
                totalCount = count,
                loaded = true,
            )
        }
    }

    /**
     * 导出候选必须与筛选结果同一口径：按当前筛选拉全量 id（上限内），
     * 而不是 Paging 已加载的子集——否则按钮承诺的道数和导出文件不一致。
     * 超出 [maxCount] 时返回空列表，由导出页提示缩小范围。
     */
    suspend fun collectExportCandidateIds(maxCount: Int): List<String> {
        val repository = catalogRepository
            ?: return uiState.visibleMistakes
                .map { it.id }
                .take(maxCount)
        val domainQuery = LibraryQuery(
            searchText = uiState.query,
            subjectId = uiState.selections.subject,
            sectionId = uiState.selections.chapter,
            knowledgePointId = uiState.selections.knowledge,
            masteryId = uiState.selections.mastery,
        )
        val total = repository.totalCount(domainQuery)
        if (total == 0 || total > maxCount) return emptyList()
        val ids = ArrayList<String>(total)
        val pageSize = 200
        while (ids.size < total) {
            val page = repository.query(
                query = domainQuery,
                offset = ids.size,
                limit = minOf(pageSize, total - ids.size),
            )
            if (page.items.isEmpty()) break
            ids += page.items.map { it.entryId }
        }
        return ids
    }

    fun exportVisible(maxCount: Int, onResult: (List<String>) -> Unit) {
        viewModelScope.launch { onResult(collectExportCandidateIds(maxCount)) }
    }

    private companion object {
        const val QUERY_KEY = "library_query"
        const val ACTIVE_FACET_KEY = "library_active_facet"
        const val PAGE_SIZE = 30

        fun selectionKey(facet: LibraryFacet): String = "library_filter_${facet.id}"
    }
}
