package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorSendPhase
import com.tingyun.smartmistakebook.core.domain.TutorSendState
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorSessionInteractionPolicyTest {
    @Test
    fun choiceAndMoveAreBlockedWhileBusyOrUnauthorized() {
        assertFalse(
            tutorChoiceSubmissionCanStart(
                hasPlanOutput = true,
                hasDiagnosticItem = true,
                hasEvaluation = true,
                interactionBusy = true,
            ),
        )
        assertTrue(
            tutorChoiceSubmissionCanStart(
                hasPlanOutput = true,
                hasDiagnosticItem = true,
                hasEvaluation = true,
                interactionBusy = false,
            ),
        )
        assertFalse(
            tutorMoveCanStart(
                interactionBusy = false,
                awaitingAuthorization = true,
                hasExecutableProvider = true,
            ),
        )
        assertTrue(
            tutorMoveCanStart(
                interactionBusy = false,
                awaitingAuthorization = false,
                hasExecutableProvider = true,
            ),
        )
        assertFalse(
            tutorRestartCanStart(
                awaitingAuthorization = false,
                hasExecutableProvider = true,
                hasConversationMemory = false,
            ),
        )
        assertEquals(2, tutorPlanAttemptCount(2))
        assertEquals(10L, tutorExternalPlanApprovedAt(10L, 20L))
        assertEquals(20L, tutorExternalPlanApprovedAt(null, 20L))
        assertEquals(null, tutorExternalPlanApprovedAt(null, null))
    }

    @Test
    fun interactionErrorsStayStudentFacing() {
        assertTrue(TUTOR_CHOICE_SAVE_ERROR.isNotBlank())
        assertTrue(TUTOR_MOVE_SAVE_ERROR.isNotBlank())
        assertFalse("Exception" in TUTOR_CHOICE_SAVE_ERROR + TUTOR_MOVE_SAVE_ERROR)
        assertFalse("null" in TUTOR_CHOICE_SAVE_ERROR + TUTOR_MOVE_SAVE_ERROR)
        assertFalse("WorkManager" in TUTOR_CHOICE_SAVE_ERROR + TUTOR_MOVE_SAVE_ERROR)
    }

    @Test
    fun respondCollectStaysClosedWithoutLeaseOrWhileBusy() {
        assertFalse(
            tutorRespondCollectCanStart(
                provider = null,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                respondApprovedAtEpochMillis = null,
                chatSubmitPending = false,
            ),
        )
        assertFalse(
            tutorRespondExecuteCanStart(
                pendingAllowed = true,
                hasPlanOutput = true,
                providerCanExecute = true,
                messageBlank = true,
                chatSending = false,
            ),
        )
        assertTrue(
            tutorRespondExecuteCanStart(
                pendingAllowed = true,
                hasPlanOutput = true,
                providerCanExecute = true,
                messageBlank = false,
                chatSending = false,
            ),
        )
        assertFalse(
            tutorRespondRetryPendingAllowed(
                pending = PendingTutorEgressAction.NewResponse(
                    message = "hi",
                    requestedMove = null,
                    clearDraftOnPersist = true,
                ),
                requestId = "req-1",
            ),
        )
        assertTrue(
            tutorRespondRetryPendingAllowed(
                pending = PendingTutorEgressAction.RetryResponse("req-1"),
                requestId = "req-1",
            ),
        )
        assertEquals(
            20L,
            tutorRespondExternalApprovedAt(ModelExecutionLocation.LOCAL_NO_EGRESS, null, 20L),
        )
        assertEquals(
            null,
            tutorRespondExternalApprovedAt(ModelExecutionLocation.EXTERNAL_PROVIDER, null, 20L),
        )
        assertEquals(
            10L,
            tutorRespondExternalApprovedAt(ModelExecutionLocation.EXTERNAL_PROVIDER, 10L, 20L),
        )
    }

    @Test
    fun respondCopyStaysStudentFacing() {
        val copy = listOf(
            TUTOR_RESPOND_IN_PROGRESS_TITLE,
            TUTOR_RESPOND_IN_PROGRESS_MESSAGE,
            TUTOR_RESPOND_LIMIT_TITLE,
            TUTOR_RESPOND_LIMIT_MESSAGE,
            TUTOR_RESPOND_VALIDATION_TITLE,
            TUTOR_RESPOND_VALIDATION_MESSAGE,
            TUTOR_RESPOND_NETWORK_TITLE,
            TUTOR_RESPOND_NETWORK_MESSAGE,
        ).joinToString()
        assertFalse("Exception" in copy)
        assertFalse("WorkManager" in copy)
        assertFalse("lease" in copy)
        assertFalse("dispatch" in copy)
        assertFalse("SSE" in copy)
    }

    @Test
    fun respondSendAdvanceUsesTheDispatchBudget() {
        val first = tutorRespondSendAdvance(
            sendState = TutorSendState(),
            logicalOperationId = "op-1",
            messageId = "op-1",
            isRetry = false,
        )
        assertTrue(first is TutorRespondSendAdvance.Ready)
        first as TutorRespondSendAdvance.Ready
        assertEquals(TutorSendPhase.DISPATCHING, first.nextState.phase)
        assertEquals(1, first.nextState.dispatchAttemptCount)
    }

    @Test
    fun planExecuteStaysClosedWhileResponseAuthorizationIsPending() {
        assertFalse(
            tutorPlanExecuteCanStart(
                awaitingResponseAuthorization = true,
                hasExecutableProvider = true,
            ),
        )
        assertFalse(
            tutorPlanExecuteCanStart(
                awaitingResponseAuthorization = false,
                hasExecutableProvider = false,
            ),
        )
        assertTrue(
            tutorPlanExecuteCanStart(
                awaitingResponseAuthorization = false,
                hasExecutableProvider = true,
            ),
        )
        assertTrue(tutorContinueAfterMove(hasChoicePayload = true, nextHistorySize = 1))
        assertFalse(tutorContinueAfterMove(hasChoicePayload = false, nextHistorySize = 1))
        assertFalse(tutorContinueAfterMove(hasChoicePayload = true, nextHistorySize = 8))
        assertFalse(
            tutorVisualProviderCanExecute(
                provider = null,
                taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
    }
}
