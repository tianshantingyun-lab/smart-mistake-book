package com.tingyun.smartmistakebook.feature.library

import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.export.MistakePdfIneligibility
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeExportUiPolicyTest {
    @Test
    fun detailExportsOnlyItsReadyExactRevision() {
        val state = readyState()
        val expected = MistakeRevisionKey(ENTRY_ID, PROBLEM_ID, REVISION_ID)

        assertEquals(expected, state.exportRevisionKeyOrNull())
        assertTrue(state.matchesExactKey(expected))
        assertFalse(
            state.matchesExactKey(
                MistakeRevisionKey(ENTRY_ID, PROBLEM_ID, "another-revision"),
            ),
        )
        assertNull(MistakeDetailState.Loading.exportRevisionKeyOrNull())
        assertNull(MistakeDetailState.NotFound.exportRevisionKeyOrNull())
        assertNull(
            MistakeDetailState.Legacy(
                MistakeDetail(identity(), "旧题面", MistakeSourceSet.Missing),
            ).exportRevisionKeyOrNull(),
        )
        assertNull(
            MistakeDetailState.CorruptSnapshot(identity()).exportRevisionKeyOrNull(),
        )
    }

    @Test
    fun everyBlockedReasonHasPlainStudentFacingCopyAndFigureTakesPriority() {
        MistakePdfIneligibility.entries.forEach { reason ->
            assertTrue(exportBlockedMessage(setOf(reason)).isNotBlank())
        }

        val figureMessage = exportBlockedMessage(
            setOf(
                MistakePdfIneligibility.UNSUPPORTED_BLOCK,
                MistakePdfIneligibility.UNCONFIRMED_OR_INVALID_DOCUMENT,
            ),
        )
        assertTrue(figureMessage.contains("图形"))
        assertTrue(figureMessage.contains("避免"))
    }

    @Test
    fun displayNameRemovesUnsafePathCharactersAndPinsRevision() {
        val displayName = exportDisplayName("  函数/A:B?  \n", 7)

        assertEquals("错题-函数 A B-第7版.pdf", displayName)
        assertFalse(displayName.contains('/'))
        assertTrue(displayName.endsWith(".pdf"))
    }

    private fun readyState(): MistakeDetailState.Ready {
        val block = ContentBlock.Paragraph("stem", "已知函数 f(x)=x²，求单调区间。")
        return MistakeDetailState.Ready(
            detail = MistakeDetail(
                identity = identity(),
                fallbackMarkdown = "备用题面",
                source = MistakeSourceSet.Missing,
            ),
            questionDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document-1",
                    title = "函数单调区间",
                    blocks = listOf(block),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = block.id,
                        sourceAssetId = "asset-1",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
            ),
        )
    }

    private fun identity() = MistakeDetailIdentity(
        errorBookEntryId = ENTRY_ID,
        problemId = PROBLEM_ID,
        problemRevisionId = REVISION_ID,
        revisionNumber = 3,
        title = "函数单调区间",
        subject = "MATH",
    )

    private companion object {
        const val ENTRY_ID = "entry-1"
        const val PROBLEM_ID = "problem-1"
        const val REVISION_ID = "revision-3"
    }
}
