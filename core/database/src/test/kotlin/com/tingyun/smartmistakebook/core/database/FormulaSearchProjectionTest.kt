package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaSearchProjectionTest {

    @Test
    fun formulaBlockIsLinearizedThroughTheSharedMathPipeline() {
        val tokens = FormulaSearchProjection.tokensForSnapshot(
            encodeSnapshot(
                ContentBlock.Formula(
                    id = "f1",
                    latex = "\\frac{1}{2}",
                    alternativeText = "二分之一",
                ),
            ),
        )

        // MathRenderer linearizes \frac{1}{2} to (1)/(2); no CJK, so the
        // segmented column keeps the whole readable formula as one token.
        assertEquals("(1)/(2)", tokens)
    }

    @Test
    fun noFormulaBlocksAndMissingSnapshotsStayEmpty() {
        assertEquals("", FormulaSearchProjection.tokensForSnapshot(null))
        assertEquals("", FormulaSearchProjection.tokensForSnapshot("   "))
        assertEquals(
            "",
            FormulaSearchProjection.tokensForSnapshot(
                encodeSnapshot(ContentBlock.Paragraph("p1", "纯文本题干，没有公式。")),
            ),
        )
    }

    @Test
    fun undecodableSnapshotsFailClosedToEmpty() {
        assertEquals("", FormulaSearchProjection.tokensForSnapshot("{not-json"))
    }

    @Test
    fun blankLatexFallsBackToAlternativeText() {
        val tokens = FormulaSearchProjection.tokensForSnapshot(
            encodeSnapshot(
                ContentBlock.Formula(id = "f1", latex = "", alternativeText = "勾股定理"),
            ),
        )

        assertEquals("勾 股 定 理", tokens)
    }

    @Test
    fun chineseMixedFormulaSegmentsCjkCharactersLikeOtherColumns() {
        val tokens = FormulaSearchProjection.tokensForSnapshot(
            encodeSnapshot(
                ContentBlock.Formula(
                    id = "f1",
                    latex = "S=\\text{面积}",
                    alternativeText = "面积公式",
                ),
            ),
        )

        // Rendered formula "S=面积" goes through the same CjkTextTokenizer
        // used for every other indexed column: CJK characters split apart.
        assertEquals("S=面 积", tokens)
    }

    @Test
    fun inlineDollarFormulasInParagraphsAndChoicesAreExtracted() {
        val tokens = FormulaSearchProjection.tokensForSnapshot(
            encodeSnapshot(
                ContentBlock.Paragraph("p1", "已知 \$x+1=2\$，求 \$x\$ 的值。"),
                ContentBlock.ChoiceGroup(
                    id = "c1",
                    promptMarkdown = "下列满足 \$a^2+b^2=c^2\$ 的是",
                    choices = listOf(
                        StructuredChoice(id = "a", markdown = "\$3^2+4^2=5^2\$"),
                        StructuredChoice(id = "b", markdown = "无公式选项"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("x+1=2", "x", "a²+b²=c²", "3²+4²=5²"),
            FormulaSearchProjection.extractFormulaTexts(
                capturedDocument(
                    ContentBlock.Paragraph("p1", "已知 \$x+1=2\$，求 \$x\$ 的值。"),
                    ContentBlock.ChoiceGroup(
                        id = "c1",
                        promptMarkdown = "下列满足 \$a^2+b^2=c^2\$ 的是",
                        choices = listOf(
                            StructuredChoice(id = "a", markdown = "\$3^2+4^2=5^2\$"),
                            StructuredChoice(id = "b", markdown = "无公式选项"),
                        ),
                    ),
                ).document,
            ),
        )
        // Index side and query side share CjkTextTokenizer, so every emitted
        // token round-trips through the query transformation unchanged.
        assertEquals(tokens, CjkTextTokenizer.segment(tokens))
        assertTrue(tokens.isNotEmpty())
    }

    @Test
    fun queryTokensMatchIndexSideSegmentationForFormulaText() {
        val indexed = FormulaSearchProjection.tokensForSnapshot(
            encodeSnapshot(
                ContentBlock.Formula(
                    id = "f1",
                    latex = "x^{2}-4=0",
                    alternativeText = "一元二次方程",
                ),
            ),
        )

        assertEquals(indexed.split(' ').filter(String::isNotEmpty), CjkTextTokenizer.tokens(indexed))
    }

    private fun encodeSnapshot(vararg blocks: ContentBlock): String =
        CapturedQuestionDocumentCodec.encode(capturedDocument(*blocks))

    private fun capturedDocument(vararg blocks: ContentBlock) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "draft-document",
            title = "公式检索夹具",
            blocks = blocks.toList(),
        ),
        blockEvidence = blocks.map { block ->
            QuestionBlockEvidence(
                blockId = block.id,
                sourceAssetId = "asset-${block.id}",
                sourceRegion = null,
                writingLayer = WritingLayer.PRINTED,
                provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                confidence = null,
                reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                producerVersion = null,
            )
        },
    )
}
