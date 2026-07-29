package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemOrganizationAuthorizationTest {
    @Test
    fun persistedGrantRoundTripsAndBuildsOnlyTheBoundedV3Manifest() {
        val grant = grant()

        assertEquals(
            grant,
            ProblemOrganizationAuthorizationGrantCodec.decode(
                ProblemOrganizationAuthorizationGrantCodec.encode(grant),
            ),
        )
        val manifest = grant.toEgressManifest("revision-1")
        assertEquals(setOf(ModelTaskKind.PROBLEM_CLASSIFY), manifest.authorizedTaskKinds)
        assertEquals(ModelEgressPurpose.CLASSIFICATION, manifest.purpose)
        assertEquals(grant.assets, manifest.assets)
        assertEquals("revision-1", manifest.subjectId)
    }

    @Test
    fun malformedOrPolicyTamperedSnapshotFailsClosed() {
        val encoded = ProblemOrganizationAuthorizationGrantCodec.encode(grant())

        assertNull(ProblemOrganizationAuthorizationGrantCodec.decodeOrNull("{"))
        assertNull(
            ProblemOrganizationAuthorizationGrantCodec.decodeOrNull(
                encoded.replace(
                    ProblemOrganizationAuthorizationGrant.CURRENT_AUTHORIZATION_POLICY_VERSION,
                    "changed-policy",
                ),
            ),
        )
    }

    @Test
    fun currentMatchRejectsExpiryAndEveryProviderIdentityChange() {
        val grant = grant()
        val provider = provider()

        assertTrue(grant.matchesCurrent(provider, NOW))
        assertFalse(grant.matchesCurrent(provider.copy(providerId = "other"), NOW))
        assertFalse(grant.matchesCurrent(provider.copy(modelId = "other"), NOW))
        assertFalse(
            grant.matchesCurrent(
                provider.copy(providerConfigurationVersion = "other"),
                NOW,
            ),
        )
        assertFalse(grant.matchesCurrent(provider, grant.expiresAtEpochMillis))
    }

    private fun grant() = ProblemOrganizationAuthorizationGrant(
        authorizationId = "organization-authorization",
        sourceDraftId = "draft-1",
        providerId = provider().providerId,
        modelId = provider().modelId,
        providerConfigurationVersion = provider().providerConfigurationVersion,
        approvedAtEpochMillis = NOW - 1,
        expiresAtEpochMillis = NOW + 1_000,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = "asset-1",
                sha256 = "a".repeat(64),
                byteSize = 1_024,
                width = 1_200,
                height = 1_600,
            ),
        ),
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "Provider",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        providerConfigurationVersion = "configuration-1",
    )

    private companion object {
        const val NOW = 10_000L
    }
}
