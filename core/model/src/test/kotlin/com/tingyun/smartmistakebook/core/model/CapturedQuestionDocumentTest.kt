package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedQuestionDocumentTest {
    @Test
    fun codecRoundTripsEvidenceWithoutPromotingOcrToTruth() {
        val candidate = captured(
            provenance = QuestionBlockProvenance.LOCAL_OCR,
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
            confidence = 0.97,
            producerVersion = "ocr-fixture-v1",
        )

        val decoded = CapturedQuestionDocumentCodec.decode(
            CapturedQuestionDocumentCodec.encode(candidate),
        )

        assertEquals(candidate, decoded)
        assertEquals(
            listOf(CapturedQuestionIssue(CapturedQuestionIssueCode.UNRESOLVED_BLOCK, "stem")),
            CapturedQuestionDocumentValidator.validateForCommit(decoded),
        )
    }

    @Test
    fun commitRequiresMatchingConfirmedEvidenceAndMeaningfulContent() {
        val confirmed = captured(
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
        )
        assertTrue(CapturedQuestionDocumentValidator.validateForCommit(confirmed).isEmpty())
        assertEquals(
            CapturedQuestionDocumentFingerprint.of(confirmed),
            CapturedQuestionDocumentFingerprint.of(
                CapturedQuestionDocumentCodec.decode(CapturedQuestionDocumentCodec.encode(confirmed)),
            ),
        )
        assertTrue(QuestionDocumentMarkdownProjection.project(confirmed.document).contains("单调区间"))

        val unknownWriting = confirmed.copy(blockEvidence = confirmed.blockEvidence.map {
            it.copy(writingLayer = WritingLayer.UNKNOWN, sourceRegion = null)
        })
        val unresolvedCodes = CapturedQuestionDocumentValidator.validateForCommit(unknownWriting)
            .map(CapturedQuestionIssue::code)
            .toSet()
        assertTrue(CapturedQuestionIssueCode.MISSING_SOURCE_REGION in unresolvedCodes)
        assertFalse(CapturedQuestionIssueCode.UNRESOLVED_WRITING_LAYER in unresolvedCodes)

        val mismatched = confirmed.copy(blockEvidence = confirmed.blockEvidence.map {
            it.copy(blockId = "other")
        })
        assertTrue(
            CapturedQuestionDocumentValidator.validateDraft(mismatched).any {
                it.code == CapturedQuestionIssueCode.EVIDENCE_DOES_NOT_MATCH_BLOCKS
            },
        )
    }

    @Test
    fun localPolicyAcceptanceIsCommitReadyWithoutChangingOcrProvenanceOrConfidence() {
        val accepted = captured(
            provenance = QuestionBlockProvenance.LOCAL_OCR,
            reviewStatus = QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED,
            confidence = 0.93,
            producerVersion = "ocr-fixture-v1",
        )

        assertTrue(CapturedQuestionDocumentValidator.validateForCommit(accepted).isEmpty())
        val decoded = CapturedQuestionDocumentCodec.decode(
            CapturedQuestionDocumentCodec.encode(accepted),
        )
        assertEquals(QuestionBlockProvenance.LOCAL_OCR, decoded.blockEvidence.single().provenance)
        assertEquals(0.93, decoded.blockEvidence.single().confidence)
        assertEquals(
            QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED,
            decoded.blockEvidence.single().reviewStatus,
        )
    }

    @Test
    fun unknownWritingLayerIsNeutralCommitMetadata() {
        val accepted = captured(
            provenance = QuestionBlockProvenance.LOCAL_OCR,
            reviewStatus = QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED,
            producerVersion = "ocr-fixture-v1",
        ).let { document ->
            document.copy(
                blockEvidence = document.blockEvidence.map {
                    it.copy(writingLayer = WritingLayer.UNKNOWN)
                },
            )
        }

        assertEquals(WritingLayer.UNKNOWN, accepted.blockEvidence.single().writingLayer)
        assertTrue(CapturedQuestionDocumentValidator.validateForCommit(accepted).isEmpty())
    }

    @Test
    fun invalidRegionsConfidenceAndUnversionedModelOutputFailClosed() {
        val invalid = captured(
            provenance = QuestionBlockProvenance.OPTIONAL_REMOTE_OCR,
            reviewStatus = QuestionBlockReviewStatus.CANDIDATE,
            confidence = Double.NaN,
            producerVersion = null,
        ).let { document ->
            document.copy(
                blockEvidence = document.blockEvidence.map {
                    it.copy(sourceRegion = NormalizedSourceRegion(0.9, 0.1, 0.2, 1.1))
                },
            )
        }

        val codes = CapturedQuestionDocumentValidator.validateDraft(invalid).map { it.code }.toSet()
        assertTrue(CapturedQuestionIssueCode.INVALID_SOURCE_REGION in codes)
        assertTrue(CapturedQuestionIssueCode.INVALID_CONFIDENCE in codes)
        assertTrue(CapturedQuestionIssueCode.MISSING_PRODUCER_VERSION in codes)
    }

    private fun captured(
        provenance: QuestionBlockProvenance,
        reviewStatus: QuestionBlockReviewStatus,
        confidence: Double? = null,
        producerVersion: String? = null,
    ) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "draft-document",
            title = "函数单调性",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数 \$f(x)=x^2\$ 的单调区间。")),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = "asset-1",
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.PRINTED,
                provenance = provenance,
                confidence = confidence,
                reviewStatus = reviewStatus,
                producerVersion = producerVersion,
            ),
        ),
    )
}
