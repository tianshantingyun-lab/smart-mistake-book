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
    fun reviewExecutionFailureWithoutAPersistedTaskRemainsRetryable() {
        val retry = retry().copy(
            taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            failedRequestId = null,
        )

        assertTrue(retry.matchesFailedTask(null))
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
