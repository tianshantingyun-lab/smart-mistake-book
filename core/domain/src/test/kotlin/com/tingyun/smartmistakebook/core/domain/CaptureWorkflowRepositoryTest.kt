package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureWorkflowRepositoryTest {
    @Test
    fun `available recognition summary requires bounded candidate text and confidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureRecognitionSummary(
                state = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                candidateText = "",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureRecognitionSummary(
                state = CaptureRecognitionState.CANDIDATE_AVAILABLE,
                candidateText = "题干",
                confidence = 1.01,
            )
        }
    }

    @Test
    fun `confirmation request requires an exact workspace with persisted final identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmCapturedProblemRequest(
                draftId = "draft-1",
                workspaceIdentity = CaptureDraftWorkspaceIdentity(
                    draftId = "draft-1",
                    basisRevisionNumber = 2,
                    workspaceVersion = 1,
                    workspaceFingerprint = "a".repeat(64),
                    finalConfirmationRequest = null,
                ),
            )
        }
    }

    @Test
    fun `replacement and tutor end commands reject ambiguous identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplaceCaptureDraftRequest(
                requestId = "replace-1",
                replacedDraftId = "draft-1",
                expectedReplacedRevisionNumber = 0,
                localUri = "file:///new.png",
                source = CaptureInputSource.CAMERA,
                occurredAtEpochMillis = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndTutorSessionWithoutSaveRequest(
                sessionId = "",
                occurredAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun `tutor disposition cannot be both saved and ended without save`() {
        val session = tutorSession(isSaved = false, isEndedWithoutSave = true)
        assertEquals(TutorSessionDisposition.ENDED_WITHOUT_SAVE, session.disposition)
        assertThrows(IllegalArgumentException::class.java) {
            tutorSession(isSaved = true, isEndedWithoutSave = true)
        }
    }

    private fun tutorSession(
        isSaved: Boolean,
        isEndedWithoutSave: Boolean,
    ): ConfirmedTutorSession {
        val document = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-1",
                blocks = listOf(ContentBlock.Paragraph("stem", "求函数定义域。")),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        )
        return ConfirmedTutorSession(
            sessionId = "session-1",
            draftId = "draft-1",
            draftRevisionNumber = 2,
            subject = "MATH",
            title = "函数定义域",
            questionDocument = document,
            sourceImageUri = "file:///source.png",
            createdAtEpochMillis = 1,
            isSaved = isSaved,
            isEndedWithoutSave = isEndedWithoutSave,
            errorBookEntryId = "entry-1".takeIf { isSaved },
        )
    }
}
