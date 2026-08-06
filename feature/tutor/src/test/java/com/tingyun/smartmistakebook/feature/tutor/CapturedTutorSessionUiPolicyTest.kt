package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHint
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintStatus
import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
