package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Rule
import org.junit.Test

class CaptureDocumentPreviewCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun structuredCandidateShowsBlocksAndWritingLayerBoundary() {
        composeRule.setContent {
            MaterialTheme {
                CaptureDocumentPreviewCard(candidate())
            }
        }

        composeRule.onNodeWithTag("capture_document_preview").assertExists()
        composeRule.onNodeWithText("题面已准备好").assertExists()
        composeRule.onNodeWithText("2 处题面内容 · 印刷 1 · 手写 1").assertExists()
        composeRule.onNodeWithText("已知函数 f(x)=x²。").assertExists()
        composeRule.onNodeWithText("保存时会同时保留原图和这份题面。").assertExists()
    }

    private fun candidate() = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-ui-test",
            title = "函数求导",
            blocks = listOf(
                ContentBlock.Paragraph("stem", "已知函数 f(x)=x²。"),
                ContentBlock.Formula("formula", "f'(x)=2x", "f 撇等于二 x"),
            ),
        ),
        blockEvidence = listOf(
            evidence("stem", WritingLayer.PRINTED, 0.1, 0.45),
            evidence("formula", WritingLayer.HANDWRITTEN, 0.5, 0.8),
        ),
    )

    private fun evidence(
        blockId: String,
        writingLayer: WritingLayer,
        top: Double,
        bottom: Double,
    ) = QuestionBlockEvidence(
        blockId = blockId,
        sourceAssetId = "asset-ui-test",
        sourceRegion = NormalizedSourceRegion(0.1, top, 0.9, bottom),
        writingLayer = writingLayer,
        provenance = QuestionBlockProvenance.MODEL_DOCUMENT_PARSE,
        confidence = 0.9,
        reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
        producerVersion = "fixture/parse-v1",
    )
}
