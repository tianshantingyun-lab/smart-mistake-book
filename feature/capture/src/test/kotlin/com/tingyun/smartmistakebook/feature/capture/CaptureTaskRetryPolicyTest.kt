package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTaskRetryPolicyTest {
    @Test
    fun cancelledAssessmentGetsAFreshRequestInsteadOfANonceBump() {
        val retry = nextCaptureTaskRetry(ModelTaskStatus.CANCELLED)
        assertTrue(retry.replaceRequestId)
        assertTrue(retry.clearSnapshot)
        assertFalse(retry.incrementNonce)
    }

    @Test
    fun inFlightAssessmentOnlyBumpsTheNonce() {
        val retry = nextCaptureTaskRetry(ModelTaskStatus.RUNNING)
        assertFalse(retry.replaceRequestId)
        assertFalse(retry.clearSnapshot)
        assertTrue(retry.incrementNonce)
    }
}
