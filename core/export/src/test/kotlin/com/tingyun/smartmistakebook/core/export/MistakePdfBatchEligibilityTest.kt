package com.tingyun.smartmistakebook.core.export

import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakePdfBatchEligibilityTest {
    @Test
    fun preservesVisibleOrderAndAutomaticallyOmitsUnavailableEntries() {
        val result = MistakePdfBatchEligibility.check(
            listOf(
                readyState("entry-b", "第二题"),
                MistakeDetailState.NotFound,
                readyState("entry-a", "第一题"),
            ),
        ) as MistakePdfBatchEligibilityResult.Eligible

        assertEquals(2, result.includedCount)
        assertEquals(1, result.omittedCount)
        assertEquals(MistakePdfExportLimits.MAX_BATCH_PAGES, result.input.maxPages)
        assertEquals(
            listOf("1. 第二题", "2. 第一题"),
            result.input.blocks
                .filterIsInstance<MistakePdfBlock.SectionHeading>()
                .map(MistakePdfBlock.SectionHeading::text),
        )
        assertEquals(
            result.input.blocks.size,
            result.input.blocks.map(MistakePdfBlock::id).distinct().size,
        )
    }

    @Test
    fun fingerprintChangesWhenVisibleOrderChanges() {
        val first = readyState("entry-a", "第一题")
        val second = readyState("entry-b", "第二题")

        val forward = MistakePdfBatchEligibility.check(
            listOf(first, second),
        ) as MistakePdfBatchEligibilityResult.Eligible
        val reversed = MistakePdfBatchEligibility.check(
            listOf(second, first),
        ) as MistakePdfBatchEligibilityResult.Eligible

        assertNotEquals(forward.input.inputSha256, reversed.input.inputSha256)
    }

    @Test
    fun emptyOversizedAndEntirelyUnavailableListsFailBeforeRendering() {
        assertEquals(
            MistakePdfBatchIneligibility.EMPTY,
            (MistakePdfBatchEligibility.check(emptyList()) as
                MistakePdfBatchEligibilityResult.Ineligible).reason,
        )
        assertEquals(
            MistakePdfBatchIneligibility.TOO_MANY_QUESTIONS,
            (
                MistakePdfBatchEligibility.check(
                    List(MistakePdfBatchEligibility.MAX_QUESTIONS + 1) {
                        MistakeDetailState.NotFound
                    },
                ) as MistakePdfBatchEligibilityResult.Ineligible
                ).reason,
        )
        assertEquals(
            MistakePdfBatchIneligibility.NO_READY_QUESTION,
            (
                MistakePdfBatchEligibility.check(
                    listOf(
                        MistakeDetailState.NotFound,
                        MistakeDetailState.Legacy(detail("legacy", "旧题")),
                    ),
                ) as MistakePdfBatchEligibilityResult.Ineligible
                ).reason,
        )
    }

    @Test
    fun batchSnapshotNeverCopiesFallbackMarkdown() {
        val result = MistakePdfBatchEligibility.check(
            listOf(readyState("entry-a", "第一题")),
        ) as MistakePdfBatchEligibilityResult.Eligible

        val exportedText = result.input.blocks.joinToString { block ->
            when (block) {
                is MistakePdfBlock.Paragraph -> block.text
                is MistakePdfBlock.SectionHeading -> block.text
                is MistakePdfBlock.Formula -> block.alternativeText
                is MistakePdfBlock.ChoiceGroup -> block.prompt
                is MistakePdfBlock.Figure -> block.alternativeText
            }
        }
        assertTrue("绝不能导出的备用文字" !in exportedText)
    }

    private fun readyState(
        entryId: String,
        title: String,
    ): MistakeDetailState.Ready {
        val block = ContentBlock.Paragraph("stem", "$title 的正式题面")
        return MistakeDetailState.Ready(
            detail = detail(entryId, title),
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document-$entryId",
                    title = title,
                    blocks = listOf(block),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = block.id,
                        sourceAssetId = "source-$entryId",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
            ),
        )
    }

    private fun detail(
        entryId: String,
        title: String,
    ) = MistakeDetail(
        identity = MistakeDetailIdentity(
            errorBookEntryId = entryId,
            problemId = "problem-$entryId",
            problemRevisionId = "revision-$entryId",
            revisionNumber = 1,
            title = title,
            subject = "数学",
        ),
        fallbackMarkdown = "绝不能导出的备用文字",
        source = MistakeSourceSet.Missing,
    )
}
