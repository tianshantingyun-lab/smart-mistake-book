package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorSendPhase
import com.tingyun.smartmistakebook.core.domain.TutorSendState
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorSessionInteractionPolicyTest {
    @Test
    fun choiceAndMoveAreBlockedWhileBusyOrWithoutAnExecutableProvider() {
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
                interactionBusy = true,
                hasExecutableProvider = true,
            ),
        )
        assertFalse(
            tutorMoveCanStart(
                interactionBusy = false,
                hasExecutableProvider = false,
            ),
        )
        assertTrue(
            tutorMoveCanStart(
                interactionBusy = false,
                hasExecutableProvider = true,
            ),
        )
        assertFalse(
            tutorRestartCanStart(
                hasExecutableProvider = true,
                hasConversationMemory = false,
            ),
        )
        assertTrue(
            tutorRestartCanStart(
                hasExecutableProvider = true,
                hasConversationMemory = true,
            ),
        )
        assertEquals(2, tutorPlanAttemptCount(2))
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
    fun respondCollectRequiresAnExecutableProviderAndConsentForExternalDispatch() {
        val external = provider()
        val local = provider(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS)

        assertFalse(
            tutorRespondCollectCanStart(
                provider = null,
                consentEnabled = true,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = false,
            ),
        )
        assertFalse(
            tutorRespondCollectCanStart(
                provider = external,
                consentEnabled = false,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = false,
            ),
        )
        assertTrue(
            tutorRespondCollectCanStart(
                provider = external,
                consentEnabled = true,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = false,
            ),
        )
        assertFalse(
            tutorRespondCollectCanStart(
                provider = external,
                consentEnabled = true,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = true,
            ),
        )
        assertTrue(
            tutorRespondCollectCanStart(
                provider = local,
                consentEnabled = false,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = false,
            ),
        )
    }

    @Test
    fun respondExecuteIsUnchangedFromTheTextComposerGate() {
        assertFalse(
            tutorRespondExecuteCanStart(
                hasPlanOutput = true,
                providerCanExecute = true,
                messageBlank = true,
                chatSending = false,
            ),
        )
        assertTrue(
            tutorRespondExecuteCanStart(
                hasPlanOutput = true,
                providerCanExecute = true,
                messageBlank = false,
                chatSending = false,
            ),
        )
        assertFalse(
            tutorRespondExecuteCanStart(
                hasPlanOutput = true,
                providerCanExecute = true,
                messageBlank = false,
                chatSending = true,
            ),
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
    fun planExecuteGatesOnProviderAndGlobalConsent() {
        val external = provider()
        val local = provider(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS)

        assertFalse(tutorPlanExecuteCanStart(provider = null, consentEnabled = true))
        assertFalse(
            tutorPlanExecuteCanStart(
                provider = external,
                consentEnabled = false,
            ),
        )
        assertTrue(
            tutorPlanExecuteCanStart(
                provider = external,
                consentEnabled = true,
            ),
        )
        assertTrue(
            tutorPlanExecuteCanStart(
                provider = local,
                consentEnabled = false,
            ),
        )
        assertFalse(
            tutorPlanExecuteCanStart(
                provider = provider(supportsPlan = false),
                consentEnabled = true,
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

    @Test
    fun agentChatIsTheSingleLiveGateAcrossPlanRespondAndVisual() {
        val externalImage = provider()
        val externalStructuredOnly = provider(supportsImageInput = false)
        val local = provider(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS)

        listOf(
            ModelTaskKind.TUTOR_PLAN,
            ModelTaskKind.TUTOR_RESPOND,
            ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ModelTaskKind.TUTOR_VISUAL_REVIEW,
        ).forEach { kind ->
            assertFalse(tutorAgentChatEnabled(provider = null, consentEnabled = true, kind = kind))
            assertFalse(
                tutorAgentChatEnabled(
                    provider = externalImage,
                    consentEnabled = false,
                    kind = kind,
                ),
            )
            assertFalse(
                tutorAgentChatEnabled(
                    provider = local,
                    consentEnabled = true,
                    kind = kind,
                ),
            )
        }
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_RESPOND,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_RESPOND,
            ),
        )
        assertFalse(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
        assertFalse(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
                consentEnabled = true,
                kind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
        )
    }

    private fun provider(
        executionLocation: ModelExecutionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        supportsImageInput: Boolean = true,
        supportsPlan: Boolean = true,
    ) = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "模型",
        modelId = "model",
        supportedTasks = buildSet {
            add(ModelTaskKind.TUTOR_PLAN)
            add(ModelTaskKind.TUTOR_RESPOND)
            add(ModelTaskKind.TUTOR_LOBBY)
            add(ModelTaskKind.TUTOR_VISUAL_GENERATE)
            add(ModelTaskKind.TUTOR_VISUAL_REVIEW)
            if (!supportsPlan) remove(ModelTaskKind.TUTOR_PLAN)
        },
        supportsImageInput = supportsImageInput,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = executionLocation,
        providerConfigurationVersion = "configuration-v1",
    )
}
