package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorMappingTest {
    @Test
    fun modelFailureCodesMapToStableUserFacingCodes() {
        assertEquals(
            AppErrorCode.PROVIDER_NOT_CONFIGURED,
            ModelFailureCode.MODEL_NOT_CONFIGURED.toAppErrorCode(),
        )
        assertEquals(
            AppErrorCode.EGRESS_CONSENT_REQUIRED,
            ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED.toAppErrorCode(),
        )
        assertEquals(
            AppErrorCode.EGRESS_LEASE_EXPIRED,
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID.toAppErrorCode(),
        )
        assertEquals(
            AppErrorCode.PROVIDER_CAPABILITY_MISMATCH,
            ModelFailureCode.PROVIDER_CAPABILITY_MISSING.toAppErrorCode(),
        )
        assertEquals(
            AppErrorCode.NETWORK_UNAVAILABLE,
            ModelFailureCode.TIMEOUT.toAppErrorCode(),
        )
    }

    @Test
    fun userRecoverableErrorNeverLeaksRawExceptions() {
        val error = userRecoverableError(
            code = AppErrorCode.NETWORK_UNAVAILABLE,
            title = "暂时连不上模型",
            message = "这次讲解没有发送成功，题图和问题已保留。",
            dataSafe = true,
            primaryAction = RecoveryAction.RETRY,
            secondaryAction = RecoveryAction.CONTINUE,
        )

        assertTrue(error.dataSafe)
        assertEquals(RecoveryAction.RETRY, error.primaryAction)
        assertFalse(error.message.contains("Exception"))
        assertFalse(error.message.contains(" at "))
    }

    @Test
    fun modelTaskFailureMapsRetryabilityToRecoveryAction() {
        val retryable = ModelTaskFailure(
            code = ModelFailureCode.NETWORK_UNAVAILABLE,
            message = "服务暂时不可用，题图已保留。",
            retryable = true,
        ).toUserRecoverableError()
        val permanent = ModelTaskFailure(
            code = ModelFailureCode.INVALID_RESPONSE,
            message = "模型返回的内容无法使用。",
            retryable = false,
        ).toUserRecoverableError()

        assertEquals(AppErrorCode.NETWORK_UNAVAILABLE, retryable.code)
        assertEquals(RecoveryAction.RETRY, retryable.primaryAction)
        assertEquals(AppErrorCode.MODEL_OUTPUT_INVALID, permanent.code)
        assertEquals(null, permanent.primaryAction)
        assertTrue(retryable.dataSafe)
    }
}
