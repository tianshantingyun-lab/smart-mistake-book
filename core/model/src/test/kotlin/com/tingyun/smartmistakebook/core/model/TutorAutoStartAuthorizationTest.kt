package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorAutoStartAuthorizationTest {
    private val provider = ProviderCapabilitySnapshot(
        providerId = "provider-a",
        providerDisplayName = "模型 A",
        modelId = "model-a",
        supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "config-a",
    )

    private val authorization = TutorAutoStartAuthorization.grant(
        authorizationId = "authorization-a",
        sessionId = "session-a",
        questionDocumentId = "document-a",
        revisionNumber = 3,
        provider = provider,
        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
        approvedAtEpochMillis = 10_000,
    )

    @Test
    fun exactFreshAuthorizationMatches() {
        assertTrue(authorization.matchesCurrent(provider = provider, nowEpochMillis = 10_001))
    }

    @Test
    fun providerOrConfigurationReplacementDoesNotMatch() {
        assertFalse(
            authorization.matchesCurrent(
                provider = provider.copy(
                    providerId = "provider-b",
                    providerConfigurationVersion = "config-b",
                ),
                nowEpochMillis = 10_001,
            ),
        )
    }

    @Test
    fun questionRevisionOrDocumentReplacementDoesNotMatch() {
        assertFalse(
            authorization.matchesCurrent(
                questionDocumentId = "document-b",
                revisionNumber = 4,
                provider = provider,
                nowEpochMillis = 10_001,
            ),
        )
    }

    @Test
    fun promptPolicyReplacementDoesNotMatch() {
        assertFalse(
            authorization.matchesCurrent(
                provider = provider,
                promptPolicyVersion = "tutor-plan-next",
                nowEpochMillis = 10_001,
            ),
        )
    }

    @Test
    fun expiredAuthorizationDoesNotMatch() {
        assertFalse(
            authorization.matchesCurrent(
                provider = provider,
                nowEpochMillis = 10_000 + MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1,
            ),
        )
    }

    private fun TutorAutoStartAuthorization.matchesCurrent(
        sessionId: String = "session-a",
        questionDocumentId: String = "document-a",
        revisionNumber: Int = 3,
        provider: ProviderCapabilitySnapshot,
        promptPolicyVersion: String = ModelPromptPolicyVersions.TUTOR_PLAN,
        nowEpochMillis: Long,
    ): Boolean = matches(
        sessionId = sessionId,
        questionDocumentId = questionDocumentId,
        revisionNumber = revisionNumber,
        provider = provider,
        promptPolicyVersion = promptPolicyVersion,
        nowEpochMillis = nowEpochMillis,
    )
}
