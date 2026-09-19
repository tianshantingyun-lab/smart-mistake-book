package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun resendingIsOfferedExactlyWhereAFreshDispatchCanSucceed() {
        // 这些码重发有意义：授权会重新签发，网络/上游问题可能已经过去。
        val expected = setOf(
            ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED,
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
            ModelFailureCode.NETWORK_UNAVAILABLE,
            ModelFailureCode.SERVICE_UNAVAILABLE,
            ModelFailureCode.TIMEOUT,
            ModelFailureCode.RATE_LIMITED,
            ModelFailureCode.INVALID_RESPONSE,
            ModelFailureCode.UNKNOWN,
        )

        ModelFailureCode.entries.forEach { code ->
            assertEquals(code.name, code in expected, code.recoverableByResending())
        }
    }

    @Test
    fun capabilityAndPayloadFailuresNeverOfferAResend() {
        // 反例必须钉住：这些码给出"重新发送"按钮只会让学生再失败一次。
        val neverResendable = setOf(
            ModelFailureCode.MODEL_NOT_CONFIGURED,
            ModelFailureCode.AUTHENTICATION_FAILED,
            ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
            ModelFailureCode.PROVIDER_REJECTED_INPUT,
        )

        neverResendable.forEach { code ->
            assertFalse(code.name, code.recoverableByResending())
        }
    }
}
