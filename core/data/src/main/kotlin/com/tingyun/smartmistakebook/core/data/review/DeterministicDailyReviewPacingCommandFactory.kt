package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * Deterministic, whole-problem pacing policy for DONE and STUCK.
 *
 * It reads only the currently presented saved item. The resulting command has no answer,
 * knowledge, observation, or mastery field, so it cannot be upgraded into learning evidence.
 */
class DeterministicDailyReviewPacingCommandFactory(
    private val nowEpochMillis: () -> Long,
) : DailyReviewPacingCommandFactory {
    override fun create(
        signal: DailyReviewPacingSignal,
        home: ReviewHomeState.Ready,
    ): DailyReviewPacingCommand {
        val session =
            requireNotNull(home.session) {
                "Daily review pacing requires an active session"
            }
        require(session.status == ReviewHomeSessionStatus.ACTIVE) {
            "Daily review pacing requires an active session"
        }
        val problem =
            requireNotNull(home.nextProblem) {
                "Daily review pacing requires a presented saved problem"
            }
        require(problem.queueItemId == session.currentQueueItemId) {
            "Daily review pacing item does not match the active presentation"
        }
        val presentationId =
            requireNotNull(session.currentPresentationId) {
                "Daily review pacing requires a presentation id"
            }
        val presentationStartedAt =
            requireNotNull(session.currentPresentationStartedAtEpochMillis) {
                "Daily review pacing requires a presentation start time"
            }
        val occurredAt = nowEpochMillis()
        require(occurredAt >= presentationStartedAt) {
            "Daily review pacing time precedes the active presentation"
        }
        val elapsedDurationMillis =
            credibleElapsedDurationMillis(
                wallElapsedMillis = occurredAt - presentationStartedAt,
                baselineSeconds = problem.estimatedDurationSeconds,
            )
        val intervalMillis =
            when (signal) {
                DailyReviewPacingSignal.DONE -> DONE_INTERVAL_MILLIS
                DailyReviewPacingSignal.STUCK -> STUCK_INTERVAL_MILLIS
            }
        val nextAvailableAt = occurredAt.saturatingAdd(intervalMillis)
        val transitionId =
            CanonicalSha256(TRANSITION_ID_DOMAIN)
                .field("signal", signal.name)
                .field("sessionId", session.sessionId)
                .field("queueItemId", problem.queueItemId)
                .field("sessionVersion", session.version)
                .field("presentationId", presentationId)
                .field("occurredAtEpochMillis", occurredAt)
                .finish()
        return DailyReviewPacingCommand(
            signal = signal,
            transitionId = transitionId,
            sessionId = session.sessionId,
            queueItemId = problem.queueItemId,
            expectedSessionVersion = session.version,
            presentationId = presentationId,
            nextAvailableAtEpochMillis = nextAvailableAt,
            nextDueAtEpochMillis = nextAvailableAt,
            schedulingPolicyVersion = SCHEDULING_POLICY_VERSION,
            elapsedDurationMillis = elapsedDurationMillis,
            occurredAtEpochMillis = occurredAt,
        )
    }

    private companion object {
        const val TRANSITION_ID_DOMAIN = "daily-review-pacing-transition-id-v1"
        const val SCHEDULING_POLICY_VERSION = "daily-review-whole-problem-v1"
        const val DONE_INTERVAL_MILLIS = 3L * 24L * 60L * 60L * 1_000L
        const val STUCK_INTERVAL_MILLIS = 12L * 60L * 60L * 1_000L
    }
}

private fun credibleElapsedDurationMillis(
    wallElapsedMillis: Long,
    baselineSeconds: Int,
): Long {
    require(wallElapsedMillis >= 0L) { "Review elapsed duration must not be negative" }
    require(baselineSeconds > 0) { "Review duration baseline must be positive" }
    val baselineMillis =
        (baselineSeconds.toLong() * 1_000L)
            .coerceAtMost(DailyReviewPacingCommand.MAX_PACING_ELAPSED_DURATION_MILLIS)
    val minimumCredibleMillis = maxOf(1_000L, baselineMillis / ELAPSED_OUTLIER_RATIO)
    val maximumCredibleMillis =
        (baselineMillis * ELAPSED_OUTLIER_RATIO)
            .coerceAtMost(DailyReviewPacingCommand.MAX_PACING_ELAPSED_DURATION_MILLIS)
    return if (wallElapsedMillis in minimumCredibleMillis..maximumCredibleMillis) {
        wallElapsedMillis
    } else {
        baselineMillis
    }
}

private fun Long.saturatingAdd(increment: Long): Long =
    if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

private const val ELAPSED_OUTLIER_RATIO = 4L
