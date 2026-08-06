package com.tingyun.smartmistakebook.feature.tutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedTutorChoiceSubmissionGateTest {
    @Test
    fun exactActiveRequestIsAllowed() {
        val gate = CapturedTutorChoiceSubmissionGate()

        assertTrue(gate.allows("request-1", identityStillCurrent = true))
        assertFalse(gate.allows("request-1", identityStillCurrent = false))
    }

    @Test
    fun explicitCancellationInvalidatesBeforeSuspendableCleanup() {
        val gate = CapturedTutorChoiceSubmissionGate()

        gate.invalidate("request-1")

        assertFalse(gate.allows("request-1", identityStillCurrent = true))
        assertTrue(gate.allows("request-2", identityStillCurrent = true))
    }

    @Test
    fun disposalClosesEveryLateSubmission() {
        val gate = CapturedTutorChoiceSubmissionGate()

        gate.close()

        assertFalse(gate.allows("request-1", identityStillCurrent = true))
        assertFalse(gate.allows("request-2", identityStillCurrent = true))
    }

    @Test
    fun responseDrivenPresentationChangeDoesNotRevokeTheExactAnswer() {
        val gate = CapturedTutorChoiceSubmissionGate()

        assertTrue(gate.allows("request-1", identityStillCurrent = true))
    }

    @Test
    fun recoveryWaitsUntilPersistentCancellationHasBeenArbitrated() {
        assertEquals(
            CapturedTutorChoiceRecoveryDecision.WAIT_FOR_PERSISTED_CANCELLATION,
            decideCapturedTutorChoiceRecovery(
                requestId = "request-1",
                inspectedCancellationRequestId = "request-1",
                persistedCancellation = null,
                cancellationRequiredRequestId = null,
            ),
        )
        assertEquals(
            CapturedTutorChoiceRecoveryDecision.WAIT_FOR_PERSISTED_CANCELLATION,
            decideCapturedTutorChoiceRecovery(
                requestId = "request-1",
                inspectedCancellationRequestId = "request-1",
                persistedCancellation = false,
                cancellationRequiredRequestId = "request-1",
            ),
        )
    }

    @Test
    fun persistedCancellationAlwaysWinsRecovery() {
        assertEquals(
            CapturedTutorChoiceRecoveryDecision.SKIP_CANCELLED_REQUEST,
            decideCapturedTutorChoiceRecovery(
                requestId = "request-1",
                inspectedCancellationRequestId = "request-1",
                persistedCancellation = true,
                cancellationRequiredRequestId = null,
            ),
        )
    }

    @Test
    fun unrelatedCancellationDoesNotDelayRecovery() {
        assertEquals(
            CapturedTutorChoiceRecoveryDecision.RECOVER,
            decideCapturedTutorChoiceRecovery(
                requestId = "request-1",
                inspectedCancellationRequestId = "request-2",
                persistedCancellation = null,
                cancellationRequiredRequestId = "request-2",
            ),
        )
    }
}
