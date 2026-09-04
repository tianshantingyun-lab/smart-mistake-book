package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveTutorDraftToLibraryUseCaseTest {
    @Test
    fun firstSaveCreatesOnceAndSecondTapReportsAlreadySaved() = runBlocking {
        val repository = FakeSaveRepository(session())
        val useCase = SaveTutorDraftToLibraryUseCase(repository)

        val first = useCase(
            SaveTutorDraftRequest(
                draftId = "draft-1",
                sessionId = "session-1",
                occurredAtEpochMillis = 1_000,
            ),
        )
        val second = useCase(
            SaveTutorDraftRequest(
                draftId = "draft-1",
                sessionId = "session-1",
                occurredAtEpochMillis = 2_000,
            ),
        )

        assertTrue(first.created)
        assertFalse(first.alreadySaved)
        assertEquals("entry-1", first.entryId)
        assertFalse(second.created)
        assertTrue(second.alreadySaved)
        assertEquals("entry-1", second.entryId)
        assertEquals(1, repository.saveCount)
    }

    @Test
    fun deterministicRequestIdKeepsIdempotencyAcrossCalls() = runBlocking {
        val repository = FakeSaveRepository(session())
        val useCase = SaveTutorDraftToLibraryUseCase(repository)

        useCase(
            SaveTutorDraftRequest(
                draftId = "draft-1",
                sessionId = "session-1",
                occurredAtEpochMillis = 1_000,
            ),
        )

        assertEquals("save-tutor-draft:draft-1:session-1", repository.lastRequestId)
    }

    private fun session() = ConfirmedTutorSession(
        sessionId = "session-1",
        draftId = "draft-1",
        draftRevisionNumber = 1,
        subject = "MATH",
        title = "函数单调性",
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "question-1",
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
        ),
        sourceImageUri = "file:///source.png",
        createdAtEpochMillis = 1_000,
        isSaved = false,
        errorBookEntryId = null,
    )
}

private class FakeSaveRepository(
    private var current: ConfirmedTutorSession,
) : CaptureWorkflowRepository {
    var saveCount = 0
    var lastRequestId: String? = null

    override fun observePendingCaptures(): Flow<List<PendingCaptureItem>> =
        flowOf(emptyList())

    override suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft? = null

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): CaptureDraftWorkspaceSnapshot? = null

    override suspend fun saveDraftWorkspace(
        request: SaveCaptureDraftWorkspaceRequest,
    ): CaptureDraftWorkspaceSnapshot = error("not used")

    override suspend fun consumeDraftWorkspace(
        request: ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean = true

    override suspend fun importDraft(request: CaptureDraftImportRequest): CaptureDraftSummary =
        error("not used")

    override suspend fun appendDraftPage(
        request: AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary = error("not used")

    override suspend fun replaceDraft(
        request: ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary = error("not used")

    override suspend fun splitDraft(
        request: SplitCaptureDraftRequest,
    ): CaptureDraftSplitResult = error("not used")

    override suspend fun confirmForTutoring(
        request: ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession = current

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? =
        current.takeIf { it.sessionId == sessionId }

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<TutorVisualSourceAssetScope> = emptyList()

    override suspend fun saveTutorSession(
        request: SaveTutorSessionRequest,
    ): CapturedProblemCommitSummary {
        saveCount += 1
        lastRequestId = request.requestId
        current = current.copy(
            isSaved = true,
            errorBookEntryId = "entry-1",
        )
        return CapturedProblemCommitSummary(
            draftId = current.draftId,
            problemId = "problem-1",
            problemRevisionId = "revision-1",
            practiceUnitId = "unit-1",
            errorBookEntryId = "entry-1",
            created = true,
        )
    }

    override suspend fun endTutorSessionWithoutSaving(
        request: EndTutorSessionWithoutSaveRequest,
    ): EndTutorSessionWithoutSaveResult = error("not used")

    override suspend fun confirmAndCommit(
        request: ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary = error("not used")

    override suspend fun attachCleanRedrawImage(
        problemRevisionId: String,
        cleanImageBytes: ByteArray,
        cleanImageMimeType: String,
    ): Boolean = error("not used")
}
