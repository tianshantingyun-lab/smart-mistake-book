package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns capture model-task execution. A request may be dispatched only once per composition
 * process; dispatch itself is guarded by the caller's fail-closed consent check so a restored
 * UI cannot silently re-enqueue egress work after recreation.
 */
internal class CaptureModelTaskCoordinator(
    private val modelTasks: ModelTaskRepository,
    private val scope: CoroutineScope,
) {
    private val activeRequestIds = mutableSetOf<String>()

    fun executeAssessment(
        request: ModelTaskRequest,
        onSnapshot: (ModelTaskSnapshot) -> Unit,
    ) {
        execute(request, onSnapshot)
    }

    fun executeParse(
        request: ModelTaskRequest,
        onSnapshot: (ModelTaskSnapshot) -> Unit,
    ) {
        execute(request, onSnapshot)
    }

    private fun execute(
        request: ModelTaskRequest,
        onSnapshot: (ModelTaskSnapshot) -> Unit,
    ) {
        if (!activeRequestIds.add(request.requestId)) return
        scope.launch {
            try {
                modelTasks.execute(request).collect(onSnapshot)
            } finally {
                activeRequestIds.remove(request.requestId)
            }
        }
    }
}
