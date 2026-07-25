package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFailureRecoveryTest {
    @Test
    fun onlyConfigurationAndProviderFailuresOpenModelSettings() {
        val expected = setOf(
            ModelFailureCode.MODEL_NOT_CONFIGURED,
            ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
            ModelFailureCode.AUTHENTICATION_FAILED,
        )

        ModelFailureCode.entries.forEach { code ->
            assertEquals(code.name, code in expected, code.requiresModelSettings())
        }
    }

    @Test
    fun onlyMissingOrStaleEgressApprovalRequestsRenewal() {
        val expected = setOf(
            ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED,
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
        )

        ModelFailureCode.entries.forEach { code ->
            assertEquals(code.name, code in expected, code.requiresEgressAuthorizationRenewal())
        }
    }
}
