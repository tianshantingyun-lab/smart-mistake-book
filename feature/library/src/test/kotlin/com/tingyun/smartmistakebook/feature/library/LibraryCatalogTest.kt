package com.tingyun.smartmistakebook.feature.library

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogTest {
    private val mistakes = listOf(
        mistake(
            id = "derivative",
            title = "导数与函数单调性",
            summary = "闭区间最值",
            mastery = MasteryState.LEARNING,
            chapter = "函数",
            knowledge = listOf("导数", "函数单调性"),
        ),
        mistake(
            id = "geometry",
            title = "导数的几何意义",
            summary = "连续答对 3 次",
            mastery = MasteryState.MASTERED,
            chapter = "函数",
            knowledge = listOf("导数几何意义"),
        ),
        mistake(
            id = "chemistry",
            title = "化学平衡移动判断",
            summary = "浓度商",
            mastery = MasteryState.MASTERED,
            subject = SubjectKind.CHEMISTRY,
            chapter = "化学平衡",
            knowledge = listOf("浓度商"),
        ),
    )
    private val catalog = LibraryCatalog(mistakes)

    @Test
    fun userVisibleFacetsOnlyContainContentHierarchyAndMastery() {
        assertEquals(
            listOf(
                LibraryFacet.SUBJECT,
                LibraryFacet.CHAPTER,
                LibraryFacet.KNOWLEDGE,
                LibraryFacet.MASTERY,
            ),
            LibraryFacet.entries,
        )
        assertEquals(listOf("科目", "板块", "知识点", "掌握程度"), LibraryFacet.entries.map { it.label })
    }

    @Test
    fun unfilteredCatalogReusesStableBackingList() {
        val result = catalog.filter(query = "", selections = LibrarySelections())

        assertSame(mistakes, result)
    }

    @Test
    fun emptyStateDistinguishesANewLibraryFromFilteredResults() {
        val newLibrary = resolveLibraryEmptyState(
            totalMistakeCount = 0,
            visibleMistakeCount = 0,
        )
        val filteredResults = resolveLibraryEmptyState(
            totalMistakeCount = mistakes.size,
            visibleMistakeCount = 0,
        )

        assertEquals(LibraryEmptyState.CATALOG_EMPTY, newLibrary)
        assertEquals("还没有错题", newLibrary?.title)
        assertEquals("拍照或上传第一道错题，之后会自动整理到这里。", newLibrary?.supportingText)
        assertEquals(LibraryEmptyState.FILTERED_EMPTY, filteredResults)
        assertEquals("没有符合条件的错题", filteredResults?.title)
        assertEquals("换一个关键词，或清除当前筛选。", filteredResults?.supportingText)
        assertNull(
            resolveLibraryEmptyState(
                totalMistakeCount = mistakes.size,
                visibleMistakeCount = mistakes.size,
            ),
        )
    }

    @Test
    fun searchAndTypedMasteryFilterKeepExistingBehavior() {
        val searchResult = catalog.filter(query = "3", selections = LibrarySelections())
        val masteredResult = catalog.filter(
            query = "",
            selections = LibrarySelections(mastery = MasteryState.MASTERED.id),
        )

        assertEquals(setOf("geometry"), searchResult.mapTo(mutableSetOf(), LibraryMistake::id))
        assertEquals(
            setOf("geometry", "chemistry"),
            masteredResult.mapTo(mutableSetOf(), LibraryMistake::id),
        )
        assertEquals("已掌握", MasteryState.MASTERED.label)
    }

    @Test
    fun facetSwitchDoesNotRecomputeVisibleResults() {
        val viewModel = LibraryViewModel(SavedStateHandle())
        viewModel.updateCatalog(mistakes)
        val initialResults = viewModel.uiState.visibleMistakes

        viewModel.selectFacet(LibraryFacet.MASTERY)

        assertSame(initialResults, viewModel.uiState.visibleMistakes)
        assertTrue(viewModel.uiState.activeOptions.isNotEmpty())
    }

    @Test
    fun legacyMasteryLabelRestoresAsStableId() {
        val viewModel = LibraryViewModel(
            SavedStateHandle(mapOf("library_filter_mastery" to "已掌握")),
        )
        viewModel.updateCatalog(mistakes)

        assertEquals(MasteryState.MASTERED.id, viewModel.uiState.selections.mastery)
        assertEquals(2, viewModel.uiState.visibleMistakes.size)
    }

    @Test
    fun subjectAndChapterNarrowTheNextHierarchyLevel() {
        val mathSelection = LibrarySelections(subject = SubjectKind.MATH.name)
        assertEquals(
            listOf("函数"),
            catalog.optionsFor(LibraryFacet.CHAPTER, mathSelection).map { it.id },
        )

        val functionSelection = mathSelection.copy(chapter = "函数")
        assertEquals(
            setOf("导数", "函数单调性", "导数几何意义"),
            catalog.optionsFor(LibraryFacet.KNOWLEDGE, functionSelection).mapTo(mutableSetOf()) { it.id },
        )
    }

    @Test
    fun changingSubjectClearsDownstreamHierarchySelections() {
        val viewModel = LibraryViewModel(SavedStateHandle())
        viewModel.updateCatalog(mistakes)
        viewModel.toggleFilter(LibraryFacet.SUBJECT, SubjectKind.MATH.name)
        viewModel.toggleFilter(LibraryFacet.CHAPTER, "函数")
        viewModel.toggleFilter(LibraryFacet.KNOWLEDGE, "导数")

        viewModel.toggleFilter(LibraryFacet.SUBJECT, SubjectKind.CHEMISTRY.name)

        assertEquals(SubjectKind.CHEMISTRY.name, viewModel.uiState.selections.subject)
        assertNull(viewModel.uiState.selections.chapter)
        assertNull(viewModel.uiState.selections.knowledge)
        assertEquals(listOf("chemistry"), viewModel.uiState.visibleMistakes.map { it.id })
    }

    @Test
    fun multiKnowledgeMistakeCanBeFilteredByEachIndividualLabel() {
        assertTrue(catalog.optionsFor(LibraryFacet.KNOWLEDGE).any { it.id == "函数单调性" })
        assertEquals(
            listOf("derivative"),
            catalog.filter("", LibrarySelections(knowledge = "函数单调性")).map { it.id },
        )
    }

    @Test
    fun missingClassificationDoesNotCreatePlaceholderFilters() {
        val entry = StudyCatalogEntry(
            entryId = "legacy",
            problemId = "problem-legacy",
            problemRevisionId = "revision-legacy",
            practiceUnitId = "practice-legacy",
            subject = SubjectKind.MATH.name,
            title = "函数题",
            problemMarkdown = "求函数值。",
            sourceKey = "capture:legacy",
            isCuratedExample = false,
            masteryStatus = MasteryStatus.UNKNOWN,
            nextReviewAtEpochMillis = null,
            retrievability = null,
        ).toLibraryMistake()
        val legacyCatalog = LibraryCatalog(listOf(entry))

        listOf("本机题库", "证据不足").forEach { placeholder ->
            assertTrue(legacyCatalog.filter(placeholder, LibrarySelections()).isEmpty())
        }
        assertEquals("数学", entry.contentPath)
        assertEquals("暂无学习记录", entry.mastery.label)
        assertTrue(legacyCatalog.optionsFor(LibraryFacet.CHAPTER).isEmpty())
        assertTrue(legacyCatalog.optionsFor(LibraryFacet.KNOWLEDGE).isEmpty())
    }

    @Test
    fun generalSubjectUsesTheSharedStudentLabel() {
        val entry = mistake(
            id = "general",
            title = "综合实践题",
            summary = "根据材料完成建模。",
            mastery = MasteryState.UNKNOWN,
            subject = SubjectKind.GENERAL,
            chapter = "综合实践",
            knowledge = listOf("建模"),
        )

        assertEquals("综合 › 综合实践 › 建模", entry.contentPath)
    }

    private fun mistake(
        id: String,
        title: String,
        summary: String,
        mastery: MasteryState,
        subject: SubjectKind = SubjectKind.MATH,
        chapter: String,
        knowledge: List<String>,
    ) = LibraryMistake(
        id = id,
        title = title,
        summary = summary,
        subject = subject,
        chapterLabels = listOf(chapter),
        knowledgeLabels = knowledge,
        mastery = mastery,
    )
}
