package com.tingyun.smartmistakebook.feature.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryBatchExportEntryInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exportUsesCurrentFilteredOrderWithoutPerQuestionSelection() {
        var exportedIds = emptyList<String>()
        composeRule.setContent {
            SmartMistakeBookTheme {
                LibraryRoute(
                    entries = listOf(
                        entry("math-1", SubjectKind.MATH.name, "导数题"),
                        entry("chemistry-1", SubjectKind.CHEMISTRY.name, "平衡题"),
                        entry("math-2", SubjectKind.MATH.name, "函数题"),
                    ),
                    pendingCaptureCount = 0,
                    onCapture = {},
                    onBatchImport = {},
                    onOpenPendingCaptures = {},
                    onExportVisible = { exportedIds = it },
                    onOpenItem = {},
                )
            }
        }

        composeRule.onNodeWithTag(
            "library_filter_subject_${SubjectKind.MATH.name.hashCode().toUInt()}",
        ).performClick()
        composeRule.onNodeWithTag("library_more").performClick()
        composeRule.onNodeWithTag("library_export_visible").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("math-1", "math-2"), exportedIds)
        }
    }

    @Test
    fun emptyLibraryKeepsExportDisabledAndDoesNotInvokeIt() {
        var exportCalls = 0
        composeRule.setContent {
            SmartMistakeBookTheme {
                LibraryRoute(
                    entries = emptyList(),
                    pendingCaptureCount = 0,
                    onCapture = {},
                    onBatchImport = {},
                    onOpenPendingCaptures = {},
                    onExportVisible = { exportCalls += 1 },
                    onOpenItem = {},
                )
            }
        }

        composeRule.onNodeWithTag("library_more").performClick()
        composeRule.onNodeWithTag("library_export_visible")
            .assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(0, exportCalls)
        }
    }

    @Test
    fun activeFacetAndFilterOptionsExposeTheirSelectedState() {
        composeRule.setContent {
            SmartMistakeBookTheme {
                LibraryRoute(
                    entries = listOf(entry("math-1", SubjectKind.MATH.name, "导数题")),
                    pendingCaptureCount = 0,
                    onCapture = {},
                    onBatchImport = {},
                    onOpenPendingCaptures = {},
                    onExportVisible = {},
                    onOpenItem = {},
                )
            }
        }

        val mathOptionTag =
            "library_filter_subject_${SubjectKind.MATH.name.hashCode().toUInt()}"
        composeRule.onNodeWithTag("library_facet_subject").assertIsSelected()
        composeRule.onNodeWithTag("library_filter_all").assertIsSelected()
        composeRule.onNodeWithTag(mathOptionTag).assertIsNotSelected().performClick()
        composeRule.onNodeWithTag("library_facet_chapter").assertIsSelected()
        composeRule.onNodeWithTag("library_facet_subject").performClick()
        composeRule.onNodeWithTag(mathOptionTag).assertIsSelected()
        composeRule.onNodeWithTag("library_filter_all").assertIsNotSelected()
        assertNoC4StudentCopy()
    }

    @Test
    fun overflowKeepsAllSecondaryToolsAndOneCompactPendingStatus() {
        var batchCalls = 0
        var pendingCalls = 0
        composeRule.setContent {
            SmartMistakeBookTheme {
                LibraryRoute(
                    entries = listOf(entry("math-1", SubjectKind.MATH.name, "导数题")),
                    pendingCaptureCount = 2,
                    onCapture = {},
                    onBatchImport = { batchCalls += 1 },
                    onOpenPendingCaptures = { pendingCalls += 1 },
                    onExportVisible = {},
                    onOpenItem = {},
                )
            }
        }

        composeRule.onNodeWithTag("library_pending_review")
            .assertTextEquals("2 道待处理")
            .performClick()
        composeRule.onAllNodesWithText("2 道待处理").assertCountEquals(1)
        composeRule.onNodeWithTag("library_more").performClick()
        composeRule.onNodeWithTag("library_batch_import").performClick()
        composeRule.onNodeWithTag("library_more").performClick()
        composeRule.onNodeWithText("图片转文档").performClick()

        composeRule.runOnIdle {
            assertEquals(1, batchCalls)
            assertEquals(2, pendingCalls)
        }
        listOf("错因", "来源", "题型").forEach { forbidden ->
            composeRule.onAllNodesWithText(forbidden, substring = true).assertCountEquals(0)
        }
        assertNoC4StudentCopy()
    }

    private fun entry(
        id: String,
        subject: String,
        title: String,
    ) = StudyCatalogEntry(
        entryId = id,
        problemId = "problem-$id",
        problemRevisionId = "revision-$id",
        practiceUnitId = "practice-$id",
        subject = subject,
        title = title,
        problemMarkdown = "$title 的题面",
        sourceKey = null,
        isCuratedExample = false,
        chapterLabels = listOf("板块"),
        knowledgeLabels = listOf("知识点"),
        masteryStatus = MasteryStatus.LEARNING,
        nextReviewAtEpochMillis = null,
        retrievability = null,
    )

    private fun assertNoC4StudentCopy() {
        (C4_FORBIDDEN_TERMS + "%").forEach { term ->
            composeRule.onAllNodesWithText(
                term,
                substring = true,
                useUnmergedTree = true,
            ).assertCountEquals(0)
            composeRule.onAllNodes(
                hasContentDescription(term, substring = true),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        }
    }

    private companion object {
        val C4_FORBIDDEN_TERMS = listOf(
            "知识本体",
            "学习投影",
            "grounding",
            "taxonomy",
            "embedding",
            "置信度",
            "分类依据",
            "资料完整度",
            "待补齐",
            "检索召回",
            "证据不足",
            "根据多次独立作答估计",
        )
    }
}
