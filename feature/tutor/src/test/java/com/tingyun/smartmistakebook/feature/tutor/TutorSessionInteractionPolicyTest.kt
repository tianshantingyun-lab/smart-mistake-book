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
    fun respondCollectRequiresAnExecutableProviderAndGuardsLocalRecovery() {
        val external = provider()
        val local = provider(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS)

        assertFalse(
            tutorRespondCollectCanStart(
                provider = null,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = false,
            ),
        )
        assertTrue(
            tutorRespondCollectCanStart(
                provider = external,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = false,
            ),
        )
        assertFalse(
            tutorRespondCollectCanStart(
                provider = external,
                requestHasEgressManifest = false,
                allowExternalEnvelopeForLocalRecovery = false,
                chatSubmitPending = true,
            ),
        )
        assertTrue(
            tutorRespondCollectCanStart(
                provider = local,
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
    fun agentGateGovernsPlanDispatchAndContinueAndVisualProvider() {
        val external = provider()
        val local = provider(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS)

        assertFalse(
            tutorAgentChatEnabled(
                provider = null,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = external,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = local,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertFalse(
            tutorAgentChatEnabled(
                provider = provider(supportsPlan = false),
                kind = ModelTaskKind.TUTOR_PLAN,
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
    fun configuredProviderIsTheSingleLiveGateAcrossPlanRespondAndVisual() {
        val externalImage = provider()
        val externalStructuredOnly = provider(supportsImageInput = false)
        val local = provider(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS)

        listOf(
            ModelTaskKind.TUTOR_PLAN,
            ModelTaskKind.TUTOR_RESPOND,
            ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ModelTaskKind.TUTOR_VISUAL_REVIEW,
        ).forEach { kind ->
            assertFalse(tutorAgentChatEnabled(provider = null, kind = kind))
            // A provider the app cannot currently execute against fails closed even though
            // it is configured: unavailability outranks the configured-model admission.
            assertFalse(
                tutorAgentChatEnabled(
                    provider = provider(executionLocation = ModelExecutionLocation.UNAVAILABLE),
                    kind = kind,
                ),
            )
            // A local provider never egresses, so the single gate always admits it.
            assertTrue(tutorAgentChatEnabled(provider = local, kind = kind))
        }
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                kind = ModelTaskKind.TUTOR_RESPOND,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalImage,
                kind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
                kind = ModelTaskKind.TUTOR_PLAN,
            ),
        )
        assertTrue(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
                kind = ModelTaskKind.TUTOR_RESPOND,
            ),
        )
        assertFalse(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
                kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ),
        )
        assertFalse(
            tutorAgentChatEnabled(
                provider = externalStructuredOnly,
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
