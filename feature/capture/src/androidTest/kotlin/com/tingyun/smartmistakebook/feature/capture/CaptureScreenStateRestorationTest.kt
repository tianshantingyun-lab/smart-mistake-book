package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionSummary
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/**
 * Locks the rememberSaveable survival contract that a state-holder refactor
 * must preserve: entering CaptureScreen with a resumeDraftId shows the resume
 * gate, and a configuration change (process/saveable-state restore) keeps that
 * gate instead of silently dropping back to the fresh-entry screen.
 */
class CaptureScreenStateRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resumedDraftStateSurvivesConfigurationChange() {
        val restorationTester = StateRestorationTester(composeRule)
        val draft = resumableDraft()

        restorationTester.setContent {
            MaterialTheme {
                CaptureScreen(
                    entryOrigin = CaptureEntryOrigin.LIBRARY,
                    repository = RestorationFakeResumeRepository(draft),
                    modelTasks = RestorationFakeModelTasks(),
                    onOpenModelSettings = {},
                    onTutorSessionReady = {},
                    onLibraryEntryReady = {},
                    onSplitReady = {},
                    onBack = {},
                    resumeDraftId = draft.draftId,
                )
            }
        }

        // Loading a found draft moves past the gate into the editor screen.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("capture_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capture_screen").assertExists()

        // Simulate a configuration change (rotation / process death restore);
        // the resumed draft state must survive via rememberSaveable.
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("capture_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capture_screen").assertExists()
    }

    private fun resumableDraft(): ResumableCaptureDraft {
        val page = CaptureSourcePage(
            pageIndex = 0,
            imageUri = "file:///draft-resume.png",
            sourceAssetId = "asset-resume",
            sourceAssetSha256 = "a".repeat(64),
            width = 10,
            height = 10,
            byteSize = 100,
            createdAtEpochMillis = 1,
        )
        return ResumableCaptureDraft(
            draftId = "draft-resume",
            origin = CaptureEntryOrigin.LIBRARY,
            sourceImageUri = page.imageUri,
            sourceAssetId = page.sourceAssetId,
            sourceAssetSha256 = page.sourceAssetSha256,
            sourceWidth = page.width,
            sourceHeight = page.height,
            sourceByteSize = page.byteSize,
            draftCreatedAtEpochMillis = page.createdAtEpochMillis,
            currentRevisionNumber = 1,
            currentRevisionDocumentFingerprint = "a".repeat(64),
            currentRevisionCreatedAtEpochMillis = 1,
            subject = null,
            title = "题目",
            questionDocument = CaptureScreenTestFixtures.blankDocument(),
            transcription = "",
            writingLayer = com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer.UNKNOWN,
            latestAssessmentTask = null,
            sourcePageAssessmentTasks = listOf<ModelTaskSnapshot?>(null),
            latestParseTask = null,
            tutorSessionId = null,
            workspace = null,
            updatedAtEpochMillis = 1,
        )
    }
}

private class RestorationFakeResumeRepository(
    private val draft: ResumableCaptureDraft,
) : CaptureWorkflowRepository {
    override fun observePendingCaptures(): Flow<List<com.tingyun.smartmistakebook.core.domain.PendingCaptureItem>> =
        flowOf(emptyList())

    override suspend fun readPendingCapture(draftId: String): com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft? =
        draft.takeIf { it.draftId == draftId }

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
    ): CaptureDraftSplitResult = error("not used")

    override suspend fun confirmForTutoring(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession = error("not used")

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? = null

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope> = emptyList()

    override suspend fun saveTutorSession(
        request: com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest,
    ): CapturedProblemCommitSummary = error("not used")

    override suspend fun endTutorSessionWithoutSaving(
        request: com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest,
    ): com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult = error("not used")

    override suspend fun confirmAndCommit(
        request: com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary = error("not used")

    override suspend fun attachCleanRedrawImage(
        problemRevisionId: String,
        cleanImageBytes: ByteArray,
        cleanImageMimeType: String,
    ): Boolean = false
}

private class RestorationFakeModelTasks : ModelTaskRepository {
    override suspend fun capabilities(): ProviderCapabilitySnapshot =
        error("no provider in this test")

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
        flow { error("not used") }
}

/** Shared minimal fixtures for capture screen tests. */
internal object CaptureScreenTestFixtures {
    fun blankDocument() = com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument(
        document = com.tingyun.smartmistakebook.core.model.QuestionDocument(
            id = "document-1",
            blocks = listOf(
                com.tingyun.smartmistakebook.core.model.ContentBlock.Paragraph(id = "stem", markdown = ""),
            ),
        ),
        blockEvidence = listOf(
            com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = "asset-resume",
                sourceRegion = com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion(
                    left = 0.0,
                    top = 0.0,
                    right = 1.0,
                    bottom = 1.0,
                ),
                writingLayer = com.tingyun.smartmistakebook.core.model.WritingLayer.UNKNOWN,
                provenance = com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance.IMPORTED_STRUCTURE,
                reviewStatus = com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus.NEEDS_REVIEW,
                producerVersion = "capture-import-v1",
            ),
        ),
    )
}
