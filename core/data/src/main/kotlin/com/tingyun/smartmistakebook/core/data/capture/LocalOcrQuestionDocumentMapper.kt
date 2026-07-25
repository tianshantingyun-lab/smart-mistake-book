package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SafeInlineMarkdown
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.model.WritingLayer

internal object LocalOcrQuestionDocumentMapper {
    fun map(
        draftId: String,
        sourceAssetId: String,
        recognition: LocalTextRecognition,
    ): CapturedQuestionDocument {
        var remainingTextChars = StructuredContentLimits.MAX_DOCUMENT_TEXT_CHARS
        val candidates = recognition.blocks
            .asSequence()
            .mapNotNull { block ->
                if (remainingTextChars == 0) return@mapNotNull null
                val safeText = SafeInlineMarkdown.literal(block.text)
                    .trim()
                    .take(remainingTextChars)
                if (safeText.isBlank()) return@mapNotNull null
                remainingTextChars -= safeText.length
                block.copy(text = safeText)
            }
            .take(StructuredContentLimits.MAX_BLOCKS)
            .toList()

        if (candidates.isEmpty()) {
            return CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document-$draftId",
                    blocks = listOf(ContentBlock.Paragraph(id = "stem", markdown = "")),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = "stem",
                        sourceAssetId = sourceAssetId,
                        provenance = QuestionBlockProvenance.LOCAL_OCR,
                        reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                        producerVersion = recognition.producerVersion,
                    ),
                ),
            )
        }

        val blockIds = candidates.indices.map { index -> "ocr-${index.toString().padStart(3, '0')}" }
        return CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-$draftId",
                blocks = candidates.mapIndexed { index, block ->
                    ContentBlock.Paragraph(id = blockIds[index], markdown = block.text)
                },
            ),
            blockEvidence = candidates.mapIndexed { index, block ->
                QuestionBlockEvidence(
                    blockId = blockIds[index],
                    sourceAssetId = sourceAssetId,
                    sourceRegion = block.sourceRegion,
                    writingLayer = WritingLayer.UNKNOWN,
                    provenance = QuestionBlockProvenance.LOCAL_OCR,
                    confidence = block.confidence,
                    reviewStatus = QuestionBlockReviewStatus.CANDIDATE,
                    producerVersion = recognition.producerVersion,
                )
            },
        )
    }
}
