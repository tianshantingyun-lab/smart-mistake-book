package com.tingyun.smartmistakebook.feature.tutor

import androidx.lifecycle.SavedStateHandle
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class TutorSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadReadySessionCreatesAnchoredConversationOnce() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureWorkflowRepository(session = session())
            val conversations = FakeTutorConversationRepository()
            val viewModel = TutorSessionViewModel(
                savedStateHandle = SavedStateHandle(mapOf("sessionId" to "session-1")),
                repository = repository,
                conversations = conversations,
                ioDispatcher = dispatcher,
            )

            advanceUntilIdle()

            assertEquals(
                TutorSessionUiState.Ready(session()),
                viewModel.uiState.value,
            )
            assertEquals(1, conversations.createCount)
            assertEquals("tutor-conv:captured:session-1", conversations.lastConversationId)
            assertEquals("session-1", conversations.lastAnchorId)
        }

    @Test
    fun saveSessionPersistsOnceAndRefreshes() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureWorkflowRepository(session = session())
            val viewModel = TutorSessionViewModel(
                savedStateHandle = SavedStateHandle(mapOf("sessionId" to "session-1")),
                repository = repository,
                conversations = FakeTutorConversationRepository(),
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.save(session())
            advanceUntilIdle()

            assertEquals(1, repository.saveCount)
            assertFalse(viewModel.saveInProgress.value)
            assertTrue(viewModel.uiState.value is TutorSessionUiState.Ready)
        }

    @Test
    fun endWithoutSavingEmitsConsumableEndEvent() =
        runTest(dispatcher.scheduler) {
            val repository = FakeCaptureWorkflowRepository(session = session())
            val viewModel = TutorSessionViewModel(
                savedStateHandle = SavedStateHandle(mapOf("sessionId" to "session-1")),
                repository = repository,
                conversations = FakeTutorConversationRepository(),
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            viewModel.endWithoutSaving(session())
            advanceUntilIdle()

            assertEquals(1, repository.endCount)
            assertTrue(viewModel.pendingEnd.value)
            viewModel.onEndConsumed()
            assertFalse(viewModel.pendingEnd.value)
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

private class FakeCaptureWorkflowRepository(
    private val session: ConfirmedTutorSession,
) : CaptureWorkflowRepository {
    var saveCount = 0
    var endCount = 0

    override fun observePendingCaptures(): Flow<List<com.tingyun.smartmistakebook.core.domain.PendingCaptureItem>> =
        flowOf(emptyList())

    override suspend fun readPendingCapture(
        draftId: String,
    ): com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft? = null

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot? = null

    override suspend fun saveDraftWorkspace(
        request: com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot =
        error("not used")

    override suspend fun consumeDraftWorkspace(
        request: com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean = true

    override suspend fun importDraft(
        request: com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest,
    ): CaptureDraftSummary = error("not used")

    override suspend fun appendDraftPage(
        request: com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary = error("not used")

    override suspend fun replaceDraft(
        request: com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary = error("not used")

    override suspend fun splitDraft(
        request: com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest,
    ): com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult = error("not used")

    override suspend fun confirmForTutoring(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession = session

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? =
        session.takeIf { it.sessionId == sessionId }

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope> = emptyList()

    override suspend fun saveTutorSession(
        request: com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest,
    ): CapturedProblemCommitSummary {
        saveCount += 1
        return CapturedProblemCommitSummary(
            draftId = "draft-1",
            problemId = "problem-1",
            problemRevisionId = "revision-1",
            practiceUnitId = "unit-1",
            errorBookEntryId = "entry-1",
            created = true,
        )
    }

    override suspend fun endTutorSessionWithoutSaving(
        request: com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest,
    ): com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult {
        endCount += 1
        return com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult(
            sessionId = request.sessionId,
            draftId = "draft-1",
            endedAtEpochMillis = request.occurredAtEpochMillis,
            created = false,
        )
    }

    override suspend fun confirmAndCommit(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary = error("not used")

    override suspend fun attachCleanRedrawImage(
        problemRevisionId: String,
        cleanImageBytes: ByteArray,
        cleanImageMimeType: String,
    ): Boolean = error("not used")
}

private class FakeTutorConversationRepository : TutorConversationRepository {
    var createCount = 0
    var lastConversationId: String? = null
    var lastAnchorId: String? = null

    override fun observeRecent(limit: Int): Flow<List<TutorConversation>> =
        flowOf(emptyList())

    override fun observeConversation(
        conversationId: String,
    ): Flow<TutorConversationSnapshot?> = flowOf(null)

    override suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): TutorConversation {
        createCount += 1
        lastConversationId = command.conversationId
        lastAnchorId = command.anchorId
        return TutorConversation(
            conversationId = command.conversationId,
            anchorKind = TutorConversationAnchorKind.EPHEMERAL_DRAFT,
            anchorId = command.anchorId,
            anchorRevisionId = command.anchorRevisionId,
            status = TutorConversationStatus.ACTIVE,
            title = command.title,
            createdAtEpochMillis = command.createdAtEpochMillis,
            updatedAtEpochMillis = command.createdAtEpochMillis,
            lastTurnOrdinal = 0,
        )
    }

    override suspend fun appendStudentMessage(
        command: com.tingyun.smartmistakebook.core.domain.AppendTutorStudentMessageCommand,
    ): com.tingyun.smartmistakebook.core.domain.TutorMessage = error("not used")

    override suspend fun appendAssistantMessage(
        command: com.tingyun.smartmistakebook.core.domain.AppendTutorAssistantMessageCommand,
    ): com.tingyun.smartmistakebook.core.domain.TutorMessage = error("not used")

    override suspend fun updateMessageStatus(
        command: com.tingyun.smartmistakebook.core.domain.UpdateTutorMessageStatusCommand,
    ): com.tingyun.smartmistakebook.core.domain.TutorMessage = error("not used")

    override suspend fun pauseConversation(
        command: com.tingyun.smartmistakebook.core.domain.PauseTutorConversationCommand,
    ): TutorConversation = error("not used")

    override suspend fun archiveConversation(
        command: com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand,
    ): TutorConversation = error("not used")

    override suspend fun deleteConversation(
        command: com.tingyun.smartmistakebook.core.domain.DeleteTutorConversationCommand,
    ) = error("not used")

    override suspend fun saveDraft(
        command: com.tingyun.smartmistakebook.core.domain.SaveTutorConversationDraftCommand,
    ) = error("not used")

    override suspend fun clearDraft(
        command: com.tingyun.smartmistakebook.core.domain.ClearTutorConversationDraftCommand,
    ) = error("not used")
}
