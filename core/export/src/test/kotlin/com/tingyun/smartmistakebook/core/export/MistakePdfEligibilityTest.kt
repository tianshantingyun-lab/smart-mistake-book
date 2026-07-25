package com.tingyun.smartmistakebook.core.export

import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureAxis
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakePdfEligibilityTest {
    @Test
    fun readyConfirmedSupportedDocumentMapsWithoutFallbackMarkdown() {
        val state = readyState(
            blocks = listOf(
                ContentBlock.Paragraph("stem", "已知 **函数**：\$f(x)\$"),
                ContentBlock.Formula("formula", "f(x)=x^2", "f x 等于 x 平方"),
                ContentBlock.ChoiceGroup(
                    id = "choices",
                    promptMarkdown = "请选择",
                    choices = listOf(
                        StructuredChoice("a", "单调递增"),
                        StructuredChoice("b", "先减后增"),
                    ),
                    selectedChoiceId = "b",
                ),
            ),
        )

        val result = MistakePdfEligibility.check(state) as MistakePdfEligibilityResult.Eligible

        assertEquals("二次函数", result.input.title)
        assertEquals(3, result.input.blocks.size)
        assertEquals(
            "已知 函数：\$f(x)\$",
            (result.input.blocks[0] as MistakePdfBlock.Paragraph).text,
        )
        val choice = (result.input.blocks[2] as MistakePdfBlock.ChoiceGroup).choices[1]
        assertTrue(choice.selected)
        assertFalse(result.input.title.contains("绝不能导出的 fallback"))
    }

    @Test
    fun legacyAndCorruptStatesFailClosed() {
        val legacy = MistakeDetailState.Legacy(detail())
        val corrupt = MistakeDetailState.CorruptSnapshot(detail().identity)

        assertEquals(
            setOf(MistakePdfIneligibility.LEGACY_CONTENT),
            (MistakePdfEligibility.check(legacy) as MistakePdfEligibilityResult.Ineligible).reasons,
        )
        assertEquals(
            setOf(MistakePdfIneligibility.CORRUPT_CONTENT),
            (MistakePdfEligibility.check(corrupt) as MistakePdfEligibilityResult.Ineligible).reasons,
        )
    }

    @Test
    fun unconfirmedBlockFailsClosed() {
        val state = readyState(
            blocks = listOf(ContentBlock.Paragraph("stem", "题目")),
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
        )

        val reasons = (MistakePdfEligibility.check(state) as MistakePdfEligibilityResult.Ineligible)
            .reasons

        assertTrue(MistakePdfIneligibility.UNCONFIRMED_OR_INVALID_DOCUMENT in reasons)
    }

    @Test
    fun confirmedFigureMapsToDeterministicPdfBlock() {
        val state = readyState(
            blocks = listOf(
                ContentBlock.Figure(
                    id = "figure",
                    alternativeText = "函数图像",
                    schema = FigureSchema.Cartesian(
                        xAxis = FigureAxis(-1.0, 1.0),
                        yAxis = FigureAxis(-1.0, 1.0),
                    ),
                ),
            ),
        )

        val result = MistakePdfEligibility.check(state) as MistakePdfEligibilityResult.Eligible
        val figure = result.input.blocks.single() as MistakePdfBlock.Figure

        assertEquals("函数图像", figure.alternativeText)
        assertTrue(figure.schema is FigureSchema.Cartesian)
    }

    @Test
    fun danglingSelectedChoiceFailsClosed() {
        val state = readyState(
            blocks = listOf(
                ContentBlock.ChoiceGroup(
                    id = "choices",
                    promptMarkdown = "请选择",
                    choices = listOf(StructuredChoice("a", "选项 A")),
                    selectedChoiceId = "missing",
                ),
            ),
        )

        val reasons = (MistakePdfEligibility.check(state) as MistakePdfEligibilityResult.Ineligible)
            .reasons

        assertTrue(MistakePdfIneligibility.INVALID_CHOICE_STATE in reasons)
    }

    private fun readyState(
        blocks: List<ContentBlock>,
        reviewStatus: QuestionBlockReviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
    ): MistakeDetailState.Ready = MistakeDetailState.Ready(
        detail = detail(),
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-1",
                title = "二次函数",
                blocks = blocks,
            ),
            blockEvidence = blocks.map { block ->
                QuestionBlockEvidence(
                    blockId = block.id,
                    sourceAssetId = "source-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = reviewStatus,
                )
            },
        ),
    )

    private fun detail() = MistakeDetail(
        identity = MistakeDetailIdentity(
            errorBookEntryId = "entry-1",
            problemId = "problem-1",
            problemRevisionId = "revision-1",
            revisionNumber = 1,
            title = "二次函数",
            subject = "数学",
        ),
        fallbackMarkdown = "绝不能导出的 fallback",
        source = MistakeSourceSet.Missing,
    )
}
