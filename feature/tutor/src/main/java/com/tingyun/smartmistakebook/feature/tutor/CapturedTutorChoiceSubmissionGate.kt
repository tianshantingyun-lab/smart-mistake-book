package com.tingyun.smartmistakebook.feature.tutor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronous lifetime gate for a prepared tutor answer.
 *
 * Explicit cancellation and screen disposal close authority immediately, before any suspendable
 * persistence cleanup can race a late submission. An explanation produced by the submitted answer
 * does not touch this gate, so that exact answer may still finish its durable transaction.
 */
internal class CapturedTutorChoiceSubmissionGate {
    private val active = AtomicBoolean(true)
    private val invalidatedRequestIds = ConcurrentHashMap.newKeySet<String>()

    fun invalidate(requestId: String) {
        require(requestId.isNotBlank()) { "Captured-choice request id must not be blank" }
        invalidatedRequestIds += requestId
    }

    fun close() {
        active.set(false)
    }

    fun allows(requestId: String, identityStillCurrent: Boolean): Boolean =
        active.get() &&
            identityStillCurrent &&
            requestId !in invalidatedRequestIds
}

internal enum class CapturedTutorChoiceRecoveryDecision {
    WAIT_FOR_PERSISTED_CANCELLATION,
    SKIP_CANCELLED_REQUEST,
    RECOVER,
}

/**
 * Persistent cancellation is authoritative during process recovery. A recovery coroutine may not
 * race the cancellation lookup or a cancellation that the current guidance transition requires.
 */
internal fun decideCapturedTutorChoiceRecovery(
    requestId: String,
    inspectedCancellationRequestId: String?,
    persistedCancellation: Boolean?,
    cancellationRequiredRequestId: String?,
): CapturedTutorChoiceRecoveryDecision {
    val cancellationApplies =
        requestId == inspectedCancellationRequestId ||
            requestId == cancellationRequiredRequestId
    if (!cancellationApplies) return CapturedTutorChoiceRecoveryDecision.RECOVER
    if (persistedCancellation == true) {
        return CapturedTutorChoiceRecoveryDecision.SKIP_CANCELLED_REQUEST
    }
    if (persistedCancellation == null || requestId == cancellationRequiredRequestId) {
        return CapturedTutorChoiceRecoveryDecision.WAIT_FOR_PERSISTED_CANCELLATION
    }
    return CapturedTutorChoiceRecoveryDecision.RECOVER
}
