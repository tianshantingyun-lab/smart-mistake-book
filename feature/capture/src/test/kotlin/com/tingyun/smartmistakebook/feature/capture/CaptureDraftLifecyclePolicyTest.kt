package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureFailureCode
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowPhase
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDraftLifecyclePolicyTest {
    @Test
    fun resumeSkipsReloadWhenTheSameDraftImageIsAlreadyOnScreen() {
        assertTrue(captureResumeShouldSkipLoad("draft-1", "draft-1", "file:///a.jpg"))
        assertFalse(captureResumeShouldSkipLoad("draft-1", "draft-1", null))
        assertFalse(captureResumeShouldSkipLoad("draft-1", "draft-2", "file:///a.jpg"))
    }

    @Test
    fun resumeAfterLoadDistinguishesMissingFailureAndTutorRedirect() {
        assertEquals(
            CaptureResumeLoadedDecision.SourceUnavailable,
            captureResumeLoadedDecision(loadFailed = true, tutorSessionId = null, draftFound = false),
        )
        assertEquals(
            CaptureResumeLoadedDecision.Missing,
            captureResumeLoadedDecision(loadFailed = false, tutorSessionId = null, draftFound = false),
        )
        assertEquals(
            CaptureResumeLoadedDecision.RedirectTutor("session-1"),
            captureResumeLoadedDecision(loadFailed = false, tutorSessionId = "session-1", draftFound = true),
        )
        assertEquals(
            CaptureResumeLoadedDecision.Apply,
            captureResumeLoadedDecision(loadFailed = false, tutorSessionId = null, draftFound = true),
        )
    }

    @Test
    fun resumeRecognitionStateDependsOnWhetherTextAlreadyExists() {
        assertEquals(
            CaptureRecognitionState.NOT_ATTEMPTED.name,
            captureResumeRecognitionStateName("  "),
        )
        assertEquals(
            CaptureRecognitionState.CANDIDATE_AVAILABLE.name,
            captureResumeRecognitionStateName("已知题干"),
        )
    }

    @Test
    fun resumeSelectsTheFirstUnsuccessfulPageAndKeepsPersistedRequestIds() {
        val first = succeededAssessment("assess-a", "asset-a")
        val second = succeededAssessment("assess-b", "asset-b")
            .copy(status = ModelTaskStatus.RUNNING, output = null, stage = ModelTaskStage.WAITING)
        val parse = succeededAssessment("parse-keep", "asset-a")
        val pages = listOf(page(0, "asset-a"), page(1, "asset-b"))
        val selected = captureResumeModelTaskSelection(
            draftId = "draft-1",
            revisionNumber = 3,
            sourcePages = pages,
            pageTasks = listOf(first, second),
            latestParseTask = parse,
        )
        assertEquals(1, selected.pageIndex)
        assertEquals("assess-b", selected.assessmentRequestId)
        assertEquals("asset-b", selected.assessmentSourceAssetId)
        assertEquals("parse-keep", selected.parseRequestId)
    }

    @Test
    fun resumeBuildsFreshRequestIdsWhenNoTaskExists() {
        val pages = listOf(page(0, "asset-a"))
        val selected = captureResumeModelTaskSelection(
            draftId = "draft-1",
            revisionNumber = 2,
            sourcePages = pages,
            pageTasks = listOf(null),
            latestParseTask = null,
        )
        assertEquals(resumeAssessmentRequestId("draft-1", 0), selected.assessmentRequestId)
        assertEquals(resumeParseRequestId("draft-1", 2), selected.parseRequestId)
        assertEquals(pages[0].createdAtEpochMillis, selected.assessmentOccurredAtEpochMillis)
    }

    @Test
    fun importedNewAndAppendRequestsUseStableStudentFacingIds() {
        val created = captureImportedNewModelTasks("req-1", "asset-a", pageCount = 2)
        assertEquals(listOf(null, null), created.pageSnapshots)
        assertEquals("capture-assess:req-1", created.assessmentRequestId)
        assertEquals("capture-parse:req-1", created.parseRequestId)

        val existing = succeededAssessment("assess-a", "asset-a")
        val appended = captureImportedAppendModelTasks(
            requestId = "req-2",
            sourcePages = listOf(page(0, "asset-a"), page(1, "asset-b")),
            existingSnapshots = listOf(existing),
        )
        assertEquals(1, appended.selectedPageIndex)
        assertEquals(listOf(existing, null), appended.pageSnapshots)
        assertEquals("capture-assess:req-2:p1", appended.assessmentRequestId)
        assertEquals("asset-b", appended.assessmentSourceAssetId)
        assertEquals("capture-parse:req-2:pages2", appended.parseRequestId)
    }

    @Test
    fun savedWorkflowOnlyBumpsTheLibraryRevision() {
        assertNull(
            captureSavedWorkflowApplication(
                CaptureWorkflowPhase.COMMITTING,
                CaptureEntryOrigin.LIBRARY,
                1,
            ),
        )
        val library = captureSavedWorkflowApplication(
            CaptureWorkflowPhase.SAVED,
            CaptureEntryOrigin.LIBRARY,
            1,
        )
        assertEquals(true, library?.commitLibraryEntry)
        assertEquals(2, library?.nextRevisionNumber)
        val tutor = captureSavedWorkflowApplication(
            CaptureWorkflowPhase.SAVED,
            CaptureEntryOrigin.TUTOR,
            4,
        )
        assertEquals(false, tutor?.commitLibraryEntry)
        assertNull(tutor?.nextRevisionNumber)
    }

    @Test
    fun onlyRejectedCommitLeavesTheUnknownOutcomeFlag() {
        assertTrue(
            captureFailedWorkflowMarksUnknownOutcome(
                CaptureWorkflowPhase.FAILED,
                CaptureFailureCode.COMMIT_REJECTED,
            ),
        )
        assertFalse(
            captureFailedWorkflowMarksUnknownOutcome(
                CaptureWorkflowPhase.FAILED,
                CaptureFailureCode.IMPORT_REJECTED,
            ),
        )
        assertFalse(
            captureFailedWorkflowMarksUnknownOutcome(
                CaptureWorkflowPhase.SAVED,
                CaptureFailureCode.COMMIT_REJECTED,
            ),
        )
    }

    @Test
    fun parseTextAdoptionRespectsUserEditsAndSuggestsATitle() {
        assertNull(
            captureParseTextAdoption(
                transcriptionEditedByUser = true,
                titleEditedByUser = false,
                structuredProjection = "题干",
                documentTitle = "标题",
            ),
        )
        val adopted = captureParseTextAdoption(
            transcriptionEditedByUser = false,
            titleEditedByUser = false,
            structuredProjection = "这是整理后的题干",
            documentTitle = "  函数题  ",
        )
        assertEquals("这是整理后的题干", adopted?.transcription)
        assertEquals("函数题", adopted?.title)
        val keepTitle = captureParseTextAdoption(
            transcriptionEditedByUser = false,
            titleEditedByUser = true,
            structuredProjection = "题干",
            documentTitle = "旧标题",
        )
        assertEquals("题干", keepTitle?.transcription)
        assertNull(keepTitle?.title)
    }

    @Test
    fun tutorAutoStartRequiresAFreshMatchingExternalApproval() {
        assertNull(
            captureTutorAutoStartAuthorization(
                sessionId = "session-1",
                questionDocumentId = "document-1",
                revisionNumber = 1,
                provider = null,
                manifest = null,
                activeAuthorizationId = "auth-1",
                initialTutorPlanAuthorizationId = "auth-1",
                nowEpochMillis = 1_000,
            ),
        )
        val provider = ProviderCapabilitySnapshot(
            providerId = "external",
            providerDisplayName = "外部",
            modelId = "model-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.CAPTURE_ASSESS),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
            providerConfigurationVersion = "v1",
        )
        val manifest = buildCaptureEgressManifest(
            authorizationId = "auth-1",
            draftId = "draft-1",
            provider = provider,
            assetId = "asset-1",
            assetSha256 = "a".repeat(64),
            assetByteSize = 2_048,
            assetWidth = 10,
            assetHeight = 20,
            approvedAtEpochMillis = 500,
        )
        val granted = captureTutorAutoStartAuthorization(
            sessionId = "session-1",
            questionDocumentId = "document-1",
            revisionNumber = 1,
            provider = provider,
            manifest = manifest,
            activeAuthorizationId = "auth-1",
            initialTutorPlanAuthorizationId = "auth-1",
            nowEpochMillis = 600,
        )
        assertNotNull(granted)
        assertEquals("auth-1", granted?.authorizationId)
        assertEquals("session-1", granted?.sessionId)
        assertEquals(ModelPromptPolicyVersions.TUTOR_PLAN, granted?.promptPolicyVersion)
        assertNull(
            captureTutorAutoStartAuthorization(
                sessionId = "session-1",
                questionDocumentId = "document-1",
                revisionNumber = 1,
                provider = provider,
                manifest = manifest,
                activeAuthorizationId = "auth-other",
                initialTutorPlanAuthorizationId = "auth-1",
                nowEpochMillis = 600,
            ),
        )
    }

    private fun succeededAssessment(requestId: String, assetId: String): ModelTaskSnapshot {
        val request = captureAssessmentRequest(
            requestId = requestId,
            draftId = "draft-1",
            sourceAssetId = assetId,
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 10,
            imageHeight = 20,
            occurredAtEpochMillis = 10,
            egressManifest = null,
        )
        return ModelTaskSnapshot(
            taskId = "task-$requestId",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 3,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "已完成",
            attemptCount = 1,
            output = CaptureAssessmentOutput(
                CaptureAssessment(
                    decision = CaptureAssessmentDecision.PASS,
                    issues = emptyList(),
                    suggestedActions = emptyList(),
                    modelVersion = "fixture-v1",
                ),
            ),
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )
    }

    private fun page(index: Int, assetId: String) = CaptureSourcePage(
        pageIndex = index,
        imageUri = "file:///$assetId.png",
        sourceAssetId = assetId,
        sourceAssetSha256 = "a".repeat(64),
        width = 10,
        height = 20,
        byteSize = 100L,
        createdAtEpochMillis = 7,
    )

    @Test
    fun restoredMissingImageClearsTheStaleUri() {
        assertEquals(CaptureRestoredUriDecision.Keep, captureRestoredUriDecision(null, true, false))
        assertEquals(CaptureRestoredUriDecision.Keep, captureRestoredUriDecision("content://picker", false, false))
        assertEquals(CaptureRestoredUriDecision.Keep, captureRestoredUriDecision("content://ok", true, true))
        val cleared = captureRestoredUriDecision("content://gone", true, false)
        assertTrue(cleared is CaptureRestoredUriDecision.Clear)
        assertEquals(CAPTURE_RESTORED_IMAGE_MISSING, (cleared as CaptureRestoredUriDecision.Clear).error)
        assertFalse("URI" in CAPTURE_RESTORED_IMAGE_MISSING)
        assertFalse("FileProvider" in CAPTURE_RESTORED_IMAGE_MISSING)
        val recovery = captureOwnedUriRecovery(
            pendingCamera = CaptureRestoredUriDecision.Keep,
            received = CaptureRestoredUriDecision.Clear(CAPTURE_RESTORED_IMAGE_MISSING),
            replacement = CaptureRestoredUriDecision.Keep,
            pendingAppend = CaptureRestoredUriDecision.Keep,
        )
        assertTrue(recovery.clearReceived)
        assertFalse(recovery.clearPendingCamera)
        assertEquals(CAPTURE_RESTORED_IMAGE_MISSING, recovery.error)
    }
}
