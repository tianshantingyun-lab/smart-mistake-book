package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CaptureStructuredDocumentEditorInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editsLeavesWithoutFlatteningFormulaChoicesOrFigure() {
        var state by mutableStateOf(state())

        composeRule.setContent {
            MaterialTheme {
                CaptureStructuredDocumentEditor(
                    state = state,
                    enabled = true,
                    onBlockChange = { state = state.editBlock(it) },
                )
            }
        }

        composeRule.onNodeWithText("修改题面").assertExists()
        composeRule.onNodeWithText("只改有误的地方", substring = true).assertExists()
        composeRule.onNodeWithTag("capture_block_stem")
            .performTextReplacement("修正后的题干")
        composeRule.onNodeWithTag("capture_block_choices_choice_choice-b")
            .performTextReplacement("修正后的 B")
        composeRule.onNodeWithText("图形 4 已按原图保留", substring = true).assertExists()

        composeRule.runOnIdle {
            val blocks = state.workingDocument.document.blocks
            assertEquals("修正后的题干", (blocks[0] as ContentBlock.Paragraph).markdown)
            assertEquals("f(x)=x^2", (blocks[1] as ContentBlock.Formula).latex)
            assertEquals("修正后的 B", (blocks[2] as ContentBlock.ChoiceGroup).choices[1].markdown)
            assertTrue(blocks[3] is ContentBlock.Figure)
            assertEquals(4, state.workingDocument.blockEvidence.size)
        }
    }

    private fun state() = CaptureWorkspaceUiState(
        draftId = "draft-1",
        basisRevisionNumber = 1,
        baseCandidateFingerprint = "a".repeat(64),
        subject = "MATH",
        workingDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "question-1",
                title = "函数题",
                blocks = listOf(
                    ContentBlock.Paragraph("stem", "原题干"),
                    ContentBlock.Formula("formula", "f(x)=x^2", "函数公式"),
                    ContentBlock.ChoiceGroup(
                        id = "choices",
                        promptMarkdown = "请选择",
                        choices = listOf(
                            StructuredChoice("choice-a", "选项 A"),
                            StructuredChoice("choice-b", "选项 B"),
                        ),
                    ),
                    ContentBlock.Figure(
                        id = "figure",
                        alternativeText = "函数值表",
                        schema = FigureSchema.SymbolTable(
                            headers = listOf("x", "f(x)"),
                            rows = listOf(listOf("0", "0")),
                        ),
                    ),
                ),
            ),
            blockEvidence = listOf("stem", "formula", "choices", "figure").map { id ->
                QuestionBlockEvidence(
                    blockId = id,
                    sourceAssetId = "asset-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                    reviewStatus = QuestionBlockReviewStatus.CANDIDATE,
                )
            },
        ),
        editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
        userEditedFields = emptySet(),
        userEditedBlockIds = emptySet(),
        finalConfirmationRequest = null,
    )
}
