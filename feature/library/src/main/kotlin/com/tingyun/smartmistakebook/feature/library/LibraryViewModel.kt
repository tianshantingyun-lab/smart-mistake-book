package com.tingyun.smartmistakebook.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

internal class LibraryViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var catalog = LibraryCatalog(emptyList())

    private val initialActiveFacet = LibraryFacet.fromId(savedStateHandle[ACTIVE_FACET_KEY])
    private val initialSelections = LibrarySelections(
        subject = restoredSelection(LibraryFacet.SUBJECT),
        chapter = restoredSelection(LibraryFacet.CHAPTER),
        knowledge = restoredSelection(LibraryFacet.KNOWLEDGE),
        mastery = restoredSelection(LibraryFacet.MASTERY),
    )

    var uiState: LibraryUiState by mutableStateOf(
        deriveUiState(
            query = savedStateHandle[QUERY_KEY] ?: "",
            activeFacet = initialActiveFacet,
            selections = initialSelections,
        ),
    )
        private set

    fun updateCatalog(mistakes: List<LibraryMistake>) {
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

    fun updateQuery(value: String) {
        if (value == uiState.query) return
        savedStateHandle[QUERY_KEY] = value
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

    private companion object {
        const val QUERY_KEY = "library_query"
        const val ACTIVE_FACET_KEY = "library_active_facet"

        fun selectionKey(facet: LibraryFacet): String = "library_filter_${facet.id}"
    }
}
