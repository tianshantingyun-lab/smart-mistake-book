package com.tingyun.smartmistakebook.feature.library

import androidx.compose.runtime.Immutable
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.LibraryCatalogItem
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ReadableMathText
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.studentLabel

internal enum class LibraryFacet(
    val id: String,
    val label: String,
) {
    SUBJECT("subject", "科目"),
    CHAPTER("chapter", "板块"),
    KNOWLEDGE("knowledge", "知识点"),
    MASTERY("mastery", "掌握程度"),
    ;

    companion object {
        fun fromId(id: String?): LibraryFacet = entries.firstOrNull { it.id == id } ?: SUBJECT
    }
}

internal enum class MasteryState(
    val id: String,
    val label: String,
) {
    UNKNOWN("unknown", "暂无学习记录"),
    LEARNING("learning", "学习中"),
    MASTERED("mastered", "已掌握"),
    CONFLICTED("conflicted", "需巩固"),
    STALE("stale", "待复习"),
    ;

    companion object {
        fun normalizeStoredValue(value: String?): String? = entries
            .firstOrNull { it.id == value || it.label == value }
            ?.id

        fun fromId(value: String?): MasteryState =
            entries.firstOrNull { it.id == value } ?: UNKNOWN
    }
}

@Immutable
internal data class LibraryFacetOption(
    val id: String,
    val label: String,
)

@Immutable
internal data class LibraryMistake(
    val id: String,
    val title: String,
    val summary: String,
    val subject: SubjectKind,
    val chapterLabels: List<String> = emptyList(),
    val knowledgeLabels: List<String> = emptyList(),
    val mastery: MasteryState,
) {
    val contentPath: String = buildList {
        add(subject.studentLabel())
        addAll(chapterLabels)
        addAll(knowledgeLabels)
    }.distinct().joinToString(" › ")

    private val normalizedSearchText = buildString {
        append(title)
        append('\n')
        append(summary)
        append('\n')
        append(subject.studentLabel())
        append('\n')
        append(chapterLabels.joinToString("\n"))
        append('\n')
        append(knowledgeLabels.joinToString("\n"))
        append('\n')
        append(mastery.label)
    }.lowercase()

    fun facetOptions(facet: LibraryFacet): List<LibraryFacetOption> = when (facet) {
        LibraryFacet.SUBJECT -> listOf(LibraryFacetOption(subject.name, subject.studentLabel()))
        LibraryFacet.CHAPTER -> chapterLabels.map { LibraryFacetOption(it, it) }
        LibraryFacet.KNOWLEDGE -> knowledgeLabels.map { LibraryFacetOption(it, it) }
        LibraryFacet.MASTERY -> listOf(LibraryFacetOption(mastery.id, mastery.label))
    }

    fun hasFacetValue(facet: LibraryFacet, optionId: String): Boolean = when (facet) {
        LibraryFacet.SUBJECT -> subject.name == optionId
        LibraryFacet.CHAPTER -> optionId in chapterLabels
        LibraryFacet.KNOWLEDGE -> optionId in knowledgeLabels
        LibraryFacet.MASTERY -> mastery.id == optionId
    }

    fun matchesNormalizedQuery(normalizedQuery: String): Boolean =
        normalizedQuery.isEmpty() || normalizedSearchText.contains(normalizedQuery)
}

@Immutable
internal data class LibrarySelections(
    val subject: String? = null,
    val chapter: String? = null,
    val knowledge: String? = null,
    val mastery: String? = null,
) {
    val isEmpty: Boolean
        get() = subject == null && chapter == null && knowledge == null && mastery == null

    fun selectedOptionId(facet: LibraryFacet): String? = when (facet) {
        LibraryFacet.SUBJECT -> subject
        LibraryFacet.CHAPTER -> chapter
        LibraryFacet.KNOWLEDGE -> knowledge
        LibraryFacet.MASTERY -> mastery
    }

    fun withSelection(facet: LibraryFacet, optionId: String?): LibrarySelections = when (facet) {
        LibraryFacet.SUBJECT -> copy(subject = optionId)
        LibraryFacet.CHAPTER -> copy(chapter = optionId)
        LibraryFacet.KNOWLEDGE -> copy(knowledge = optionId)
        LibraryFacet.MASTERY -> copy(mastery = optionId)
    }
}

@Immutable
internal data class LibraryUiState(
    val query: String,
    val activeFacet: LibraryFacet,
    val selections: LibrarySelections,
    val activeOptions: List<LibraryFacetOption>,
    val visibleMistakes: List<LibraryMistake>,
    val totalCount: Int = visibleMistakes.size,
    val hasMore: Boolean = false,
    val loaded: Boolean = true,
) {
    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || !selections.isEmpty
}

internal enum class LibraryEmptyState(
    val title: String,
    val supportingText: String,
) {
    CATALOG_EMPTY(
        title = "还没有错题",
        supportingText = "拍照或上传第一道错题，之后会自动整理到这里。",
    ),
    FILTERED_EMPTY(
        title = "没有符合条件的错题",
        supportingText = "换一个关键词，或清除当前筛选。",
    ),
}

internal fun resolveLibraryEmptyState(
    totalMistakeCount: Int,
    visibleMistakeCount: Int,
): LibraryEmptyState? = when {
    totalMistakeCount == 0 -> LibraryEmptyState.CATALOG_EMPTY
    visibleMistakeCount == 0 -> LibraryEmptyState.FILTERED_EMPTY
    else -> null
}

internal class LibraryCatalog(
    private val mistakes: List<LibraryMistake>,
) {
    private val optionsByFacet: Map<LibraryFacet, List<LibraryFacetOption>> =
        LibraryFacet.entries.associateWith { facet ->
            val uniqueOptions = linkedMapOf<String, LibraryFacetOption>()
            mistakes.forEach { mistake ->
                mistake.facetOptions(facet).forEach { option ->
                    if (option.id !in uniqueOptions) {
                        uniqueOptions[option.id] = option
                    }
                }
            }
            uniqueOptions.values.toList()
        }

    private val mistakesByFacetValue: Map<LibraryFacet, Map<String, List<LibraryMistake>>> =
        LibraryFacet.entries.associateWith { facet ->
            val mutableIndex = linkedMapOf<String, MutableList<LibraryMistake>>()
            mistakes.forEach { mistake ->
                mistake.facetOptions(facet).forEach { option ->
                    mutableIndex.getOrPut(option.id, ::mutableListOf).add(mistake)
                }
            }
            mutableIndex.mapValues { (_, indexedMistakes) -> indexedMistakes.toList() }
        }

    fun optionsFor(
        facet: LibraryFacet,
        selections: LibrarySelections = LibrarySelections(),
    ): List<LibraryFacetOption> = optionsByFacet.getValue(facet)
        .asSequence()
        .filter { option -> optionAvailableInHierarchy(facet, option.id, selections) }
        .toList()

    fun normalizeSelection(
        facet: LibraryFacet,
        storedValue: String?,
        selections: LibrarySelections,
    ): String? {
        val normalizedValue = if (facet == LibraryFacet.MASTERY) {
            MasteryState.normalizeStoredValue(storedValue)
        } else {
            storedValue
        }
        return normalizedValue?.takeIf { candidate ->
            optionsFor(facet, selections).any { option -> option.id == candidate }
        }
    }

    fun filter(
        query: String,
        selections: LibrarySelections,
    ): List<LibraryMistake> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty() && selections.isEmpty) return mistakes

        var candidates = mistakes
        LibraryFacet.entries.forEach { facet ->
            val selectedId = selections.selectedOptionId(facet) ?: return@forEach
            val indexedMistakes = mistakesByFacetValue.getValue(facet)[selectedId]
                ?: return emptyList()
            if (indexedMistakes.size < candidates.size) candidates = indexedMistakes
        }

        return candidates.filterTo(ArrayList()) { mistake ->
            mistake.matchesNormalizedQuery(normalizedQuery) &&
                LibraryFacet.entries.all { facet ->
                    val selectedId = selections.selectedOptionId(facet)
                    selectedId == null || mistake.hasFacetValue(facet, selectedId)
                }
        }
    }

    private fun optionAvailableInHierarchy(
        facet: LibraryFacet,
        optionId: String,
        selections: LibrarySelections,
    ): Boolean {
        if (facet == LibraryFacet.SUBJECT || facet == LibraryFacet.MASTERY) return true
        return mistakesByFacetValue.getValue(facet)[optionId].orEmpty().any { mistake ->
            (selections.subject == null || mistake.hasFacetValue(LibraryFacet.SUBJECT, selections.subject)) &&
                (
                    facet == LibraryFacet.CHAPTER ||
                        selections.chapter == null ||
                        mistake.hasFacetValue(LibraryFacet.CHAPTER, selections.chapter)
                    )
        }
    }
}

internal fun StudyCatalogEntry.toLibraryMistake(): LibraryMistake {
    val subjectKind = runCatching { SubjectKind.valueOf(subject) }.getOrDefault(SubjectKind.GENERAL)
    return LibraryMistake(
        id = entryId,
        title = title,
        summary = ReadableMathText.inlineMarkdown(problemMarkdown, lineBreakReplacement = ' ')
            .lineSequence()
            .joinToString(separator = " ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .take(64),
        subject = subjectKind,
        chapterLabels = chapterLabels,
        knowledgeLabels = knowledgeLabels,
        mastery = masteryStatus.toLibraryMastery(),
    )
}

internal fun LibraryCatalogItem.toLibraryMistake(): LibraryMistake {
    val subjectKind = runCatching { SubjectKind.valueOf(subjectId) }
        .getOrDefault(SubjectKind.GENERAL)
    return LibraryMistake(
        id = entryId,
        title = title,
        summary = summary,
        subject = subjectKind,
        chapterLabels = chapterLabels,
        knowledgeLabels = knowledgeLabels,
        mastery = MasteryState.fromId(masteryId),
    )
}

private fun MasteryStatus.toLibraryMastery(): MasteryState = when (this) {
    MasteryStatus.UNKNOWN -> MasteryState.UNKNOWN
    MasteryStatus.LEARNING -> MasteryState.LEARNING
    MasteryStatus.MASTERED -> MasteryState.MASTERED
    MasteryStatus.CONFLICTED -> MasteryState.CONFLICTED
    MasteryStatus.STALE -> MasteryState.STALE
}
