package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePageRelationTest {
    @Test
    fun adjacentPageAssessmentRequiresOneBoundedRelation() {
        val request = request(followingSourceAssets = listOf(followingSource()))
        val valid = output(CapturePageRelation.SAME_QUESTION)
        val missing = output(null)

        assertTrue(ModelTaskCompletionValidator.validate(request, valid).isEmpty())
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.PAGE_RELATION_MISMATCH),
            ModelTaskCompletionValidator.validate(request, missing).map { it.code },
        )
    }

    @Test
    fun ordinaryCaptureAssessmentCannotSmuggleAPageRelation() {
        val request = request(followingSourceAssets = emptyList())

        assertTrue(ModelTaskCompletionValidator.validate(request, output(null)).isEmpty())
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.PAGE_RELATION_MISMATCH),
            ModelTaskCompletionValidator.validate(
                request,
                output(CapturePageRelation.NEXT_QUESTION),
            ).map { it.code },
        )
    }

    @Test
    fun pageComparisonSurvivesDurableRequestAndOutputCodec() {
        val request = request(followingSourceAssets = listOf(followingSource()))
        val output = output(CapturePageRelation.UNSURE)

        assertEquals(request, ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request)))
        assertEquals(output, ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output)))
    }

    private fun request(followingSourceAssets: List<CaptureSourceAssetRef>) = ModelTaskRequest(
        requestId = "page-relation-request",
        input = CaptureAssessmentInput(
            draftId = "batch-boundary-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 900,
            imageHeight = 1_200,
            followingSourceAssets = followingSourceAssets,
        ),
        occurredAtEpochMillis = 1_000,
    )

    private fun followingSource() = CaptureSourceAssetRef(
        assetId = "asset-2",
        sha256 = "b".repeat(64),
        width = 900,
        height = 1_200,
        pageIndex = 1,
    )

    private fun output(relation: CapturePageRelation?) = CaptureAssessmentOutput(
        CaptureAssessment(
            decision = CaptureAssessmentDecision.PASS,
            issues = emptyList(),
            suggestedActions = emptyList(),
            modelVersion = "fixture/page-relation-v1",
            followingPageRelations = listOfNotNull(relation),
        ),
    )
}
