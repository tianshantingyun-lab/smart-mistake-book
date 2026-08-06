package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHint
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintStatus
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceState
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionChoice
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.WritingLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedTutorSessionUiPolicyTest {
    @Test
    fun temporarySessionNeverClaimsThatTeachingIsReady() {
        assertEquals(
            "临时题目 · 讲完后再决定是否存入",
            tutorSessionStatusLine(isSaved = false),
        )
        assertEquals(
            "已存入错题本",
            tutorSessionStatusLine(isSaved = true),
        )
        assertEquals(
            "本次讲题已结束 · 未存入错题本",
            tutorSessionStatusLine(TutorSessionDisposition.ENDED_WITHOUT_SAVE),
        )
    }

    @Test
    fun saveActionStaysOptionalAndReportsDurableStates() {
        assertEquals(
            "存入错题本",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = false, saveFailed = false),
        )
        assertEquals(
            "保存中",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = true, saveFailed = false),
        )
        assertEquals(
            "重试保存",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = false, saveFailed = true),
        )
        assertEquals(
            "已存入",
            tutorSessionSaveLabel(isSaved = true, saveInProgress = false, saveFailed = false),
        )
    }

    @Test
    fun directPreviewCreatesExposureOnlyAfterSafeMarkdownIsVisible() {
        val key = exposureKey()
        val preparing = activeMessage(snapshot = null)
        val visible = activeMessage(
            snapshot = TutorMarkdownSnapshot(
                stableMarkdown = "完整讲解",
                provisionalMarkdown = "",
            ),
        )

        assertNull(
            transientDirectPreviewExposureKey(
                exposureKey = key,
                explanationMode = TutorExplanationMode.DIRECT,
                activeMessage = preparing,
            ),
        )
        assertEquals(
            key,
            transientDirectPreviewExposureKey(
                exposureKey = key,
                explanationMode = TutorExplanationMode.DIRECT,
                activeMessage = visible,
            ),
        )
        assertEquals("完整讲解", visible.snapshot?.visibleMarkdown)
    }

    @Test
    fun guidedPreviewNeverCreatesUnauthorizedTransientExposure() {
        assertNull(
            transientDirectPreviewExposureKey(
                exposureKey = exposureKey(),
                explanationMode = TutorExplanationMode.GUIDED,
                activeMessage = activeMessage(
                    snapshot = TutorMarkdownSnapshot(
                        stableMarkdown = "引导提示",
                        provisionalMarkdown = "",
                    ),
                ),
            ),
        )
    }

    @Test
    fun invalidTerminalKeepsShownDirectContentAndExactlyOneRetry() {
        val failed = activeMessage(
            snapshot = TutorMarkdownSnapshot(
                stableMarkdown = "已显示的完整讲解",
                provisionalMarkdown = "",
            ),
            phase = TutorActiveStreamPhase.FAILED,
            retryable = true,
        )

        assertEquals("已显示的完整讲解", failed.snapshot?.visibleMarkdown)
        assertEquals(listOf(TutorActiveStreamRecovery.RETRY), failed.recoveryActions)
        assertEquals(
            exposureKey(),
            transientDirectPreviewExposureKey(
                exposureKey = exposureKey(),
                explanationMode = TutorExplanationMode.DIRECT,
                activeMessage = failed,
            ),
        )
    }

    @Test
    fun hintVisibleButNotRecordedBlocksAnswerSubmission() {
        val hint = hint(status = TutorCurrentSessionHintStatus.AVAILABLE)

        assertTrue(
            isHintSubmissionBlocked(
                hint = hint,
                locallyVisibleHintToken = hint.slotToken,
                hintCommitBusyToken = hint.slotToken,
                hintCommitFailedToken = null,
            ),
        )
    }

    @Test
    fun failedHintCommitKeepsAnswersBlockedUntilRetrySucceeds() {
        val hint = hint(status = TutorCurrentSessionHintStatus.AVAILABLE)

        assertTrue(
            isHintSubmissionBlocked(
                hint = hint,
                locallyVisibleHintToken = hint.slotToken,
                hintCommitBusyToken = null,
                hintCommitFailedToken = hint.slotToken,
            ),
        )
    }

    @Test
    fun durablyShownHintDoesNotBlockAnswerSubmission() {
        val hint = hint(status = TutorCurrentSessionHintStatus.SHOWN)

        assertFalse(
            isHintSubmissionBlocked(
                hint = hint,
                locallyVisibleHintToken = hint.slotToken,
                hintCommitBusyToken = null,
                hintCommitFailedToken = null,
            ),
        )
    }

    @Test
    fun hintStillAvailableBeforeRevealDoesNotBlockAnswerSubmission() {
        val hint = hint(status = TutorCurrentSessionHintStatus.AVAILABLE)

        assertFalse(
            isHintSubmissionBlocked(
                hint = hint,
                locallyVisibleHintToken = null,
                hintCommitBusyToken = null,
                hintCommitFailedToken = null,
            ),
        )
    }

    @Test
    fun disclosureFlagsFollowProviderAndPendingActionState() {
        assertTrue(
            tutorShowPlanRecoveryDisclosure(
                hasPlanFreshApproval = true,
                hasPendingPlanAction = false,
                executableProviderIsExternal = true,
            ),
        )
        assertFalse(
            tutorShowPlanRecoveryDisclosure(
                hasPlanFreshApproval = true,
                hasPendingPlanAction = false,
                executableProviderIsExternal = false,
            ),
        )
        assertTrue(
            tutorShowVisualRetryDisclosure(
                hasPendingVisualRetry = true,
                currentProviderIsExternal = true,
            ),
        )
        assertFalse(
            tutorShowVisualRetryDisclosure(
                hasPendingVisualRetry = true,
                currentProviderIsExternal = false,
            ),
        )
        assertTrue(
            tutorShowRespondDisclosure(
                respondSupported = true,
                hasCurrentPlan = true,
                respondAuthorized = false,
                hasPlanFreshApproval = false,
                hasPendingVisualRetry = false,
                pendingResponseRetryIsRebuildable = true,
            ),
        )
        assertFalse(
            tutorShowRespondDisclosure(
                respondSupported = true,
                hasCurrentPlan = true,
                respondAuthorized = true,
                hasPlanFreshApproval = false,
                hasPendingVisualRetry = false,
                pendingResponseRetryIsRebuildable = true,
            ),
        )
        assertTrue(
            tutorShowChatStartError(
                composerAvailable = false,
                chatStartError = "稍后重试",
            ),
        )
        assertFalse(
            tutorShowChatStartError(
                composerAvailable = true,
                chatStartError = "稍后重试",
            ),
        )
    }

    @Test
    fun activeReplyDisclosureFollowsStreamState() {
        val active = activeMessage(snapshot = null)

        assertTrue(tutorShowActiveReply(activeMessage = active, activeReplyExists = false))
        assertFalse(tutorShowActiveReply(activeMessage = active, activeReplyExists = true))
        assertFalse(tutorShowActiveReply(activeMessage = null, activeReplyExists = false))
    }

    @Test
    fun currentTurnComparesCycleAndTurnOrdinals() {
        assertTrue(
            tutorIsCurrentTurn(
                taskCycleOrdinal = 2,
                taskTurnOrdinal = 3,
                currentCycleOrdinal = 2,
                currentTurnOrdinal = 3,
            ),
        )
        assertFalse(
            tutorIsCurrentTurn(
                taskCycleOrdinal = 2,
                taskTurnOrdinal = 3,
                currentCycleOrdinal = 2,
                currentTurnOrdinal = 4,
            ),
        )
    }

    @Test
    fun missingVisualResolutionFallsBackToHidden() {
        val anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = 1,
            turnOrdinal = 1,
        )

        assertEquals(
            TutorVisualResolution.Hidden,
            tutorResolvedVisual(
                visualAnchor = anchor,
                resolvedVisualStates = emptyMap(),
            ),
        )
        assertEquals(
            TutorVisualResolution.Hidden,
            tutorResolvedVisual(
                visualAnchor = null,
                resolvedVisualStates = emptyMap(),
            ),
        )
    }

    @Test
    fun awaitingContinuationMatchesExactTaskRequest() {
        assertTrue(
            tutorAwaitingContinuation(
                awaitingRequestId = "request-7",
                taskRequestId = "request-7",
            ),
        )
        assertFalse(
            tutorAwaitingContinuation(
                awaitingRequestId = null,
                taskRequestId = "request-7",
            ),
        )
    }

    @Test
    fun planInteractionRequiresTailCurrentAndNoPendingGate() {
        assertTrue(
            tutorPlanInteractionEnabled(
                isTail = true,
                isCurrentTurn = true,
                hasFreshApproval = false,
                pendingInteractionBlocked = false,
                responseActionAwaitingAuthorization = false,
            ),
        )
        assertFalse(
            tutorPlanInteractionEnabled(
                isTail = true,
                isCurrentTurn = false,
                hasFreshApproval = false,
                pendingInteractionBlocked = false,
                responseActionAwaitingAuthorization = false,
            ),
        )
        assertFalse(
            tutorPlanInteractionEnabled(
                isTail = true,
                isCurrentTurn = true,
                hasFreshApproval = true,
                pendingInteractionBlocked = false,
                responseActionAwaitingAuthorization = false,
            ),
        )
    }

    @Test
    fun hintRequestRequiresGuidedAuthorizedAndIdle() {
        assertTrue(
            tutorHintRequestEnabled(
                guidedMode = true,
                hintsUsed = 2,
                maxHints = 3,
                respondSupported = true,
                respondAuthorized = true,
                chatSending = false,
                pendingInteractionBlocked = false,
                responseActionAwaitingAuthorization = false,
            ),
        )
        assertFalse(
            tutorHintRequestEnabled(
                guidedMode = false,
                hintsUsed = 2,
                maxHints = 3,
                respondSupported = true,
                respondAuthorized = true,
                chatSending = false,
                pendingInteractionBlocked = false,
                responseActionAwaitingAuthorization = false,
            ),
        )
        assertFalse(
            tutorHintRequestEnabled(
                guidedMode = true,
                hintsUsed = 3,
                maxHints = 3,
                respondSupported = true,
                respondAuthorized = true,
                chatSending = false,
                pendingInteractionBlocked = false,
                responseActionAwaitingAuthorization = false,
            ),
        )
    }

    @Test
    fun pendingResponseResolvesVisibleDirectiveChoice() {
        val timeline = listOf(
            TutorConversationTimelineItem.Plan(planTaskWithDirective()),
        )
        val pending = PendingTutorEgressAction.NewResponse(
            message = "先判断符号",
            requestedMove = null,
            clearDraftOnPersist = true,
            selectedChoiceId = "choice-1",
            choiceSourceRequestId = "request-plan-choice",
        )

        val response = tutorPendingTutorResponseMessage(timeline, pending)

        assertNotNull(response)
        assertEquals("先判断符号", response!!.messageMarkdown)
        assertEquals("choice-1", response.selectedChoiceId)
        assertEquals("request-plan-choice", response.choiceSourceRequestId)
    }

    @Test
    fun pendingFreeResponsePassesThroughWithoutChoiceIdentity() {
        val response = tutorPendingTutorResponseMessage(
            timeline = emptyList(),
            pending = PendingTutorEgressAction.NewResponse(
                message = "直接讲下一步",
                requestedMove = null,
                clearDraftOnPersist = false,
                selectedChoiceId = null,
                choiceSourceRequestId = null,
            ),
        )

        assertNotNull(response)
        assertEquals("直接讲下一步", response!!.messageMarkdown)
        assertNull(response.selectedChoiceId)
        assertNull(response.choiceSourceRequestId)
    }

    @Test
    fun pendingChoiceWithoutVisibleDirectiveIsRejected() {
        val pending = PendingTutorEgressAction.NewResponse(
            message = "先判断符号",
            requestedMove = null,
            clearDraftOnPersist = true,
            selectedChoiceId = "choice-1",
            choiceSourceRequestId = "request-missing",
        )

        assertNull(
            tutorPendingTutorResponseMessage(timeline = emptyList(), pending = pending),
        )
    }

    @Test
    fun pendingChoiceOutsideVisibleDirectiveIsRejected() {
        val timeline = listOf(
            TutorConversationTimelineItem.Plan(planTaskWithDirective()),
        )
        val pending = PendingTutorEgressAction.NewResponse(
            message = "不属于当前选项",
            requestedMove = null,
            clearDraftOnPersist = true,
            selectedChoiceId = "choice-99",
            choiceSourceRequestId = "request-plan-choice",
        )

        assertNull(
            tutorPendingTutorResponseMessage(timeline = timeline, pending = pending),
        )
    }

    @Test
    fun evidenceCancellationCarriesExactQuestionIdentityAndClock() {
        val command = tutorEvidenceCancellation(
            question = question(),
            requestId = "evidence-request-1",
            clock = { 1234L },
        )

        assertEquals("session-1", command.sessionId)
        assertEquals("document-1", command.questionDocumentId)
        assertEquals(2, command.revisionNumber)
        assertEquals("evidence-request-1", command.evidenceRequestId)
        assertEquals(1234L, command.occurredAtEpochMillis)
    }

    @Test
    fun cancellationIsConfirmedRequiresLocalOrReplayedConfirmation() {
        val replayedId = "replayed-request"
        assertTrue(
            tutorCancellationIsConfirmed(
                requestId = "local-1",
                locallyCancelledEvidenceRequestIds = setOf("local-1"),
                replayedCancellationRequestId = replayedId,
                replayedPendingIsCancelled = null,
            ),
        )
        assertTrue(
            tutorCancellationIsConfirmed(
                requestId = replayedId,
                locallyCancelledEvidenceRequestIds = emptySet(),
                replayedCancellationRequestId = replayedId,
                replayedPendingIsCancelled = true,
            ),
        )
        assertFalse(
            tutorCancellationIsConfirmed(
                requestId = replayedId,
                locallyCancelledEvidenceRequestIds = emptySet(),
                replayedCancellationRequestId = replayedId,
                replayedPendingIsCancelled = null,
            ),
        )
        assertFalse(
            tutorCancellationIsConfirmed(
                requestId = "other",
                locallyCancelledEvidenceRequestIds = emptySet(),
                replayedCancellationRequestId = replayedId,
                replayedPendingIsCancelled = true,
            ),
        )
    }

    @Test
    fun pendingInteractionBlockedFollowsResolutionAndPendingSet() {
        val resolution = TutorGuidanceModeResolution(
            state = TutorGuidanceState(
                problem = TutorProblemScope(
                    problemId = "document-1",
                    revisionNumber = 2,
                ),
                mode = TutorExplanationMode.GUIDED,
                pendingEvidenceRequestId = null,
            ),
            cancelEvidenceRequestId = "cancel-1",
            blockPendingInteraction = true,
        )
        val confirmed: (String) -> Boolean = { it != "cancel-1" }
        assertTrue(
            tutorPendingInteractionIsCurrentlyBlocked(
                guidanceResolution = resolution,
                cancellationPendingEvidenceRequestIds = emptySet(),
                replayedCancellationRequestId = null,
                cancellationIsConfirmed = confirmed,
            ),
        )
        assertFalse(
            tutorPendingInteractionIsCurrentlyBlocked(
                guidanceResolution = TutorGuidanceModeResolution(
                    state = resolution.state,
                    cancelEvidenceRequestId = null,
                    blockPendingInteraction = false,
                ),
                cancellationPendingEvidenceRequestIds = emptySet(),
                replayedCancellationRequestId = null,
                cancellationIsConfirmed = confirmed,
            ),
        )
    }

    private fun planTaskWithDirective() = ModelTaskSnapshot(
        taskId = "task-plan-choice",
        request = ModelTaskRequest(
            requestId = "request-plan-choice",
            input = TutorPlanInput(
                sessionId = "session-1",
                draftRevisionNumber = 2,
                subject = "MATH",
                questionDocument = QuestionDocument(
                    id = "document-1",
                    blocks = listOf(ContentBlock.Paragraph("stem", "求解当前题目")),
                ),
                cycleOrdinal = 1,
                turnOrdinal = 1,
            ),
            occurredAtEpochMillis = 1,
        ),
        requestFingerprint = ModelTaskFingerprint.of(
            ModelTaskRequest(
                requestId = "request-plan-choice",
                input = TutorPlanInput(
                    sessionId = "session-1",
                    draftRevisionNumber = 2,
                    subject = "MATH",
                    questionDocument = QuestionDocument(
                        id = "document-1",
                        blocks = listOf(ContentBlock.Paragraph("stem", "求解当前题目")),
                    ),
                    cycleOrdinal = 1,
                    turnOrdinal = 1,
                ),
                occurredAtEpochMillis = 1,
            ),
        ),
        status = ModelTaskStatus.SUCCEEDED,
        stateVersion = 1,
        stage = ModelTaskStage.COMPLETE,
        userMessage = "讲解已准备",
        attemptCount = 1,
        provider = provider(),
        output = TutorPlanOutput(
            sessionId = "session-1",
            draftRevisionNumber = 2,
            questionDocumentId = "document-1",
            plan = TutorTurnPlan(
                openingMarkdown = "先判断符号",
                diagnosticItem = null,
                solutionMarkdown = "解答",
                alternateMethodMarkdown = "另一种方法",
                difficultyReasonMarkdown = "根据当前题说明。",
                targetedEvidenceLabels = emptyList(),
                inferredKnowledgeLabels = listOf("函数与导数"),
                interactionDirective = TutorInteractionDirective.Choices(
                    promptMarkdown = "关键一步是什么？",
                    choices = listOf(
                        TutorInteractionChoice(id = "choice-1", labelMarkdown = "先判断符号"),
                        TutorInteractionChoice(id = "choice-2", labelMarkdown = "直接代入计算"),
                    ),
                ),
            ),
            modelVersion = "model-v1",
            cycleOrdinal = 1,
            turnOrdinal = 1,
        ),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "Compatible model",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN, ModelTaskKind.TUTOR_RESPOND),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "configuration-v1",
    )

    private fun question() = ConfirmedTutorSession(
        sessionId = "session-1",
        draftId = "draft-document-1",
        draftRevisionNumber = 2,
        subject = "MATH",
        title = "当前题目",
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-1",
                blocks = listOf(ContentBlock.Paragraph("stem", "求解当前题目")),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset-document-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        ),
        sourceImageUri = "file:///private/document-1.jpg",
        createdAtEpochMillis = 1,
        isSaved = false,
        errorBookEntryId = null,
    ).toTutorQuestionContext()

    private fun activeMessage(
        snapshot: TutorMarkdownSnapshot?,
        phase: TutorActiveStreamPhase = TutorActiveStreamPhase.STREAMING,
        retryable: Boolean = false,
    ) = TutorActiveStreamMessage(
        studentMessage = "请直接讲解",
        ownerVersion = 7,
        turnVersion = 1,
        modeVersion = 0,
        identity = TutorStreamIdentity(
            requestId = "request-direct",
            ownerVersion = 7,
            turnVersion = 1,
            modeVersion = 0,
        ),
        snapshot = snapshot,
        phase = phase,
        retryable = retryable,
    )

    private fun exposureKey() = TutorAnswerExposureKey(
        sessionId = "session-1",
        questionDocumentId = "document-1",
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
        modelTaskRequestId = "request-direct",
        responseOrdinal = 1,
    )

    private fun hint(status: TutorCurrentSessionHintStatus) = TutorCurrentSessionHint(
        markdown = "先比较两个时刻的磁通量。",
        slotToken = "a".repeat(64),
        status = status,
    )
}
