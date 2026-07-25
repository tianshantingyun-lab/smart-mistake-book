package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOcrQuestionDocumentMapperTest {
    @Test
    fun `maps bounded OCR blocks to untrusted evidence in source order`() {
        val region = NormalizedSourceRegion(0.1, 0.2, 0.9, 0.8)

        val document = LocalOcrQuestionDocumentMapper.map(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            recognition = LocalTextRecognition(
                blocks = listOf(
                    LocalRecognizedTextBlock(
                        text = "<script> **题干**",
                        sourceRegion = region,
                        confidence = 0.73,
                    ),
                    LocalRecognizedTextBlock(
                        text = "第二行",
                        sourceRegion = null,
                        confidence = null,
                    ),
                ),
                producerVersion = "fixture-ocr-v1",
            ),
        )

        assertTrue(CapturedQuestionDocumentValidator.validateDraft(document).isEmpty())
        assertEquals(listOf("ocr-000", "ocr-001"), document.document.blocks.map(ContentBlock::id))
        assertEquals(
            "＜script＞ ＊＊题干＊＊",
            (document.document.blocks.first() as ContentBlock.Paragraph).markdown,
        )
        val firstEvidence = document.blockEvidence.first()
        assertEquals(region, firstEvidence.sourceRegion)
        assertEquals(WritingLayer.UNKNOWN, firstEvidence.writingLayer)
        assertEquals(QuestionBlockProvenance.LOCAL_OCR, firstEvidence.provenance)
        assertEquals(QuestionBlockReviewStatus.CANDIDATE, firstEvidence.reviewStatus)
        assertEquals(0.73, checkNotNull(firstEvidence.confidence), 0.0001)
    }

    @Test
    fun `empty recognition remains a reviewable draft rather than trusted empty text`() {
        val document = LocalOcrQuestionDocumentMapper.map(
            draftId = "draft-empty",
            sourceAssetId = "asset-empty",
            recognition = LocalTextRecognition(emptyList(), "fixture-ocr-v1"),
        )

        val block = document.document.blocks.single() as ContentBlock.Paragraph
        val evidence = document.blockEvidence.single()
        assertTrue(block.markdown.isEmpty())
        assertNull(evidence.sourceRegion)
        assertEquals(QuestionBlockReviewStatus.NEEDS_REVIEW, evidence.reviewStatus)
        assertTrue(CapturedQuestionDocumentValidator.validateDraft(document).isEmpty())
        assertTrue(CapturedQuestionDocumentValidator.validateForCommit(document).isNotEmpty())
    }
}
