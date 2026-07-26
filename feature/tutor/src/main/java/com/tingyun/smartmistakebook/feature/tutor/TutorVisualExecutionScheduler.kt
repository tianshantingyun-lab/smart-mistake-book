package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class TutorVisualExecutionOutcome {
    COMPLETED,
    FAILED,
}

internal data class TutorVisualExecutionKey(
    val anchor: TutorVisualTurnAnchor,
    val taskKind: ModelTaskKind,
    val semanticRequestId: String,
)

internal suspend fun collectTutorVisualExecution(
    execution: Flow<ModelTaskSnapshot>,
): TutorVisualExecutionOutcome = try {
    execution.collect()
    TutorVisualExecutionOutcome.COMPLETED
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    TutorVisualExecutionOutcome.FAILED
}

internal fun Set<TutorVisualExecutionKey>.afterExecution(
    key: TutorVisualExecutionKey,
    outcome: TutorVisualExecutionOutcome,
): Set<TutorVisualExecutionKey> = when (outcome) {
    TutorVisualExecutionOutcome.COMPLETED -> this - key
    TutorVisualExecutionOutcome.FAILED -> this + key
}

internal fun Set<TutorVisualExecutionKey>.failureFor(
    anchor: TutorVisualTurnAnchor,
    generationRequestId: String?,
    reviewRequestId: String?,
): TutorVisualExecutionKey? = firstOrNull { failure ->
    failure.anchor == anchor &&
        when (failure.taskKind) {
            ModelTaskKind.TUTOR_VISUAL_GENERATE ->
                failure.semanticRequestId == generationRequestId
            ModelTaskKind.TUTOR_VISUAL_REVIEW ->
                failure.semanticRequestId == reviewRequestId
            else -> false
        }
}

internal class TutorVisualAnchorScheduler(
    private val scope: CoroutineScope,
) {
    private val activeJobs = ConcurrentHashMap<TutorVisualExecutionKey, Job>()

    fun launch(
        key: TutorVisualExecutionKey,
        block: suspend () -> Unit,
    ): Boolean {
        lateinit var launched: Job
        launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                activeJobs.remove(key, launched)
            }
        }
        if (activeJobs.putIfAbsent(key, launched) != null) {
            launched.cancel()
            return false
        }
        launched.start()
        return true
    }

    fun cancelExcept(keys: Set<TutorVisualExecutionKey>) {
        activeJobs.entries.forEach { (key, job) ->
            if (key !in keys && activeJobs.remove(key, job)) job.cancel()
        }
    }
}
