package com.tingyun.smartmistakebook

import kotlinx.coroutines.CancellationException

/**
 * Loads one **optional** review-session card (spec §2.9 remediation / §2.16
 * re-teach) without letting a failure escape into the composition.
 *
 * Both of those lookups run inside `produceState`'s coroutine, which is a child
 * of the composition's scope and has no `CoroutineExceptionHandler` — so an
 * exception there does not degrade the screen, it takes the process down
 * (audit N-03). For a card that the session can proceed without, the right
 * answer is "no card", with the failure reported through [onFailure] rather
 * than swallowed (failure mode E is about defaults impersonating signals; a
 * silent catch that leaves no trace is the same mistake one level up).
 *
 * Cancellation is deliberately re-thrown: swallowing it would keep this
 * coroutine running after the screen is gone, which is why the catch is
 * written per-case rather than as a bare `catch (Throwable)`.
 */
internal suspend fun <T> loadOptionalSessionCard(
    onFailure: (Throwable) -> Unit = ::logOptionalSessionCardFailure,
    load: suspend () -> T?,
): T? = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    onFailure(failure)
    null
}

private fun logOptionalSessionCardFailure(failure: Throwable) {
    android.util.Log.w(
        "SmartMistakeBook",
        "Optional review-session card failed to load; the session continues without it",
        failure,
    )
}
