package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTutorEgressStateTest {
    @Test
    fun visualRetryLeaseRequiresTheExactSemanticAndProviderIdentity() {
        val provider = provider()
        val retry = retry()

        assertTrue(retry.matches(provider, "semantic-request"))
        assertFalse(retry.matches(provider.copy(providerId = "other-provider"), "semantic-request"))
        assertFalse(retry.matches(provider.copy(modelId = "other-model"), "semantic-request"))
        assertFalse(
            retry.matches(
                provider.copy(providerConfigurationVersion = "configuration-v2"),
                "semantic-request",
            ),
        )
        assertFalse(retry.matches(provider, "changed-request"))
    }

    @Test
    fun processRestorationNeverRestoresVisualRetryApprovalAuthority() {
        val restored = retry().copy(approvedAtEpochMillis = 123).restoredWithoutAuthorization()

        assertNull(restored.approvedAtEpochMillis)
        assertTrue(restored.matches(provider(), "semantic-request"))
    }

    @Test
    fun externalVisualRetryUsesOnlyItsExactInMemoryApproval() {
        val approved = retry().copy(approvedAtEpochMillis = 123)

        assertEquals(123L, approved.approvedAtFor(provider(), "semantic-request"))
        assertNull(approved.approvedAtFor(provider(), "changed-request"))
        assertNull(
            approved.approvedAtFor(
                provider().copy(providerConfigurationVersion = "configuration-v2"),
                "semantic-request",
            ),
        )
        assertNull(approved.restoredWithoutAuthorization().approvedAtFor(provider(), "semantic-request"))
    }

    @Test
    fun staleApprovedVisualRetryIsClearedBeforeAReplacementCanBeDisclosed() {
        val approved = retry().copy(approvedAtEpochMillis = 123)
        val pending = PendingTutorEgressState(approved)
        val changedIdentities = listOf(
            provider().copy(providerId = "other-provider") to "semantic-request",
            provider().copy(modelId = "other-model") to "semantic-request",
            provider().copy(providerConfigurationVersion = "configuration-v2") to
                "semantic-request",
            provider() to "semantic-request-for-changed-source",
        )

        changedIdentities.forEach { (currentProvider, currentSemanticRequestId) ->
            assertNull(
                pending.clearVisualRetryIfIdentityChanged(
                    expectedRetry = approved,
                    provider = currentProvider,
                    semanticRequestId = currentSemanticRequestId,
                ).action,
            )
        }
        assertEquals(
            pending,
            pending.clearVisualRetryIfIdentityChanged(
                expectedRetry = approved,
                provider = provider(),
                semanticRequestId = "semantic-request",
            ),
        )
        assertNull(
            retry().copy(
                semanticRequestId = "semantic-request-for-changed-source",
            ).approvedAtEpochMillis,
        )
    }

    @Test
    fun providerAuthorityFailureClearsOnlyPendingVisualRetry() {
        val visualPending = PendingTutorEgressState(
            retry().copy(approvedAtEpochMillis = 123),
        )
        val responsePending = PendingTutorEgressState(
            PendingTutorEgressAction.RetryResponse("response-request"),
        )

        assertNull(visualPending.withoutVisualRetry().action)
        assertEquals(responsePending, responsePending.withoutVisualRetry())
    }

    @Test
    fun unavailableVisualExecutionRevokesAnApprovedRetryBeforeEarlyReturn() {
        val approved = retry().copy(approvedAtEpochMillis = 123)
        val pending = PendingTutorEgressState(approved)

        assertNull(
            pending.clearVisualRetryIfExecutionBlocked(
                expectedRetry = approved,
                executionAvailable = false,
            ).action,
        )
        assertEquals(
            pending,
            pending.clearVisualRetryIfExecutionBlocked(
                expectedRetry = approved,
                executionAvailable = true,
            ),
        )
        assertEquals(
            pending,
            pending.clearVisualRetryIfExecutionBlocked(
                expectedRetry = approved.copy(semanticRequestId = "replacement"),
                executionAvailable = false,
            ),
        )
    }

    @Test
    fun reviewExecutionFailureWithoutAPersistedTaskRemainsRetryable() {
        val retry = retry().copy(
            taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            failedRequestId = null,
        )

        assertTrue(retry.matchesFailedTask(null))
    }

    @Test
    fun pendingChoiceResponseRoundTripsItsSourceAndChoiceIdentity() {
        val pending = PendingTutorEgressState(
            PendingTutorEgressAction.NewResponse(
                message = "继续",
                requestedMove = null,
                clearDraftOnPersist = false,
                selectedChoiceId = "choice-b",
                choiceSourceRequestId = "visible-reply-request",
            ),
        )
        val saved = PendingTutorEgressStateCodec.encode(pending)
        val restored = PendingTutorEgressStateCodec.decode(saved).action
            as PendingTutorEgressAction.NewResponse

        assertEquals("choice-b", restored.selectedChoiceId)
        assertEquals("visible-reply-request", restored.choiceSourceRequestId)
        assertEquals("继续", restored.message)
        val legacyPayload = saved
            .filterKeys { key ->
                key != "selected_choice_id" && key != "choice_source_request_id"
            }
        val legacyRestored = PendingTutorEgressStateCodec.decode(legacyPayload).action
            as PendingTutorEgressAction.NewResponse

        assertNull(legacyRestored.selectedChoiceId)
        assertNull(legacyRestored.choiceSourceRequestId)
        assertEquals("继续", legacyRestored.message)
    }

    private fun retry() = PendingTutorEgressAction.RetryVisual(
        anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = 1,
            turnOrdinal = 1,
        ),
        taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
        failedRequestId = "failed-request",
        semanticRequestId = "semantic-request",
        providerId = "provider",
        modelId = "model",
        providerConfigurationVersion = "configuration-v1",
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "Provider",
        modelId = "model",
        supportedTasks = setOf(
            ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ModelTaskKind.TUTOR_VISUAL_REVIEW,
        ),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        providerConfigurationVersion = "configuration-v1",
    )
}
