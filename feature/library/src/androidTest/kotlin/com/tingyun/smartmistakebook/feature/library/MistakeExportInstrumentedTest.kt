package com.tingyun.smartmistakebook.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MistakeExportInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyDetailOffersTutorAndPassesTheVisibleExactRevision() {
        val state = readyState(
            listOf(ContentBlock.Paragraph("stem", "求函数 f(x)=x² 的单调区间。")),
        )
        var tutorKey: MistakeRevisionKey? = null
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = state,
                    onBack = {},
                    onExport = {},
                    onTutor = { tutorKey = it },
                )
            }
        }

        composeRule.onNodeWithTag("mistake_detail_start_tutor").performClick()

        composeRule.runOnIdle {
            assertEquals(EXACT_KEY, tutorKey)
        }
    }

    @Test
    fun readyDetailOffersExportAndPassesTheVisibleExactRevision() {
        val state = readyState(
            listOf(ContentBlock.Paragraph("stem", "求函数 f(x)=x² 的单调区间。")),
        )
        var exportedKey: MistakeRevisionKey? = null
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = state,
                    onBack = {},
                    onExport = { exportedKey = it },
                )
            }
        }

        composeRule.onNodeWithTag("mistake_detail_export_a4").performClick()

        composeRule.runOnIdle {
            assertEquals(EXACT_KEY, exportedKey)
        }
    }

    @Test
    fun exactReadyRevisionPreparesARealPreviewBeforeShowingActions() {
        val repository = FakeMistakeDetailRepository(
            readyState(listOf(ContentBlock.Paragraph("stem", "求函数 f(x)=x² 的单调区间。"))),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeExportRoute(
                    key = EXACT_KEY,
                    repository = repository,
                    onBack = {},
                )
            }
        }

        waitForTag("mistake_export_ready")

        composeRule.onNodeWithTag("mistake_export_preview").assertExists()
        composeRule.onNodeWithTag("mistake_export_save").assertExists()
        composeRule.onNodeWithTag("mistake_export_share").assertExists()
        composeRule.onNodeWithTag("mistake_export_print").assertExists()
        composeRule.runOnIdle {
            assertEquals(EXACT_KEY, repository.observedExactKey)
        }
    }

    @Test
    fun figureShowsPreviewAndDeliveryActions() {
        val figure = ContentBlock.Figure(
            id = "figure",
            title = "函数图像",
            alternativeText = "开口向上的抛物线",
            schema = FigureSchema.SymbolTable(
                headers = listOf("x", "y"),
                rows = listOf(listOf("0", "0"), listOf("1", "1")),
            ),
        )
        val repository = FakeMistakeDetailRepository(readyState(listOf(figure)))
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeExportRoute(
                    key = EXACT_KEY,
                    repository = repository,
                    onBack = {},
                )
            }
        }

        waitForTag("mistake_export_ready")

        composeRule.onNodeWithTag("mistake_export_preview").assertExists()
        composeRule.onNodeWithTag("mistake_export_save").assertExists()
        composeRule.onNodeWithTag("mistake_export_share").assertExists()
        composeRule.onNodeWithTag("mistake_export_print").assertExists()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun readyState(blocks: List<ContentBlock>): MistakeDetailState.Ready =
        MistakeDetailState.Ready(
            detail = MistakeDetail(
                identity = identity(),
                fallbackMarkdown = "备用题面",
                source = MistakeSourceSet.Missing,
            ),
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document-1",
                    title = "函数单调区间",
                    blocks = blocks,
                ),
                blockEvidence = blocks.map { block ->
                    QuestionBlockEvidence(
                        blockId = block.id,
                        sourceAssetId = "asset-1",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = if (block is ContentBlock.Figure) {
                            WritingLayer.DIAGRAM
                        } else {
                            WritingLayer.PRINTED
                        },
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    )
                },
            ),
        )

    private fun identity() = MistakeDetailIdentity(
        errorBookEntryId = EXACT_KEY.entryId,
        problemId = EXACT_KEY.problemId,
        problemRevisionId = EXACT_KEY.problemRevisionId,
        revisionNumber = 3,
        title = "函数单调区间",
        subject = "MATH",
    )

    private class FakeMistakeDetailRepository(
        private val state: MistakeDetailState,
    ) : MistakeDetailRepository {
        var observedExactKey: MistakeRevisionKey? = null

        override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> = flowOf(state)

        override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> {
            observedExactKey = key
            return flowOf(state)
        }

        override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState = state
    }

    private companion object {
        val EXACT_KEY = MistakeRevisionKey(
            entryId = "entry-1",
            problemId = "problem-1",
            problemRevisionId = "revision-3",
        )
    }
}
