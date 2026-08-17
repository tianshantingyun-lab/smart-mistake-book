package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns capture model-task execution. A request may be dispatched only once per composition
 * process, and external providers still pass through [CaptureExternalExecutionLaunchGuard] so a
 * persisted manifest alone cannot silently re-enqueue work after recreation.
 */
internal class CaptureModelTaskCoordinator(
    private val modelTasks: ModelTaskRepository,
    private val launchGuard: CaptureExternalExecutionLaunchGuard =
        CaptureExternalExecutionLaunchGuard(),
    private val scope: CoroutineScope,
) {
    private val activeRequestIds = mutableSetOf<String>()

    fun executeAssessment(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
        manifest: ModelEgressManifest?,
        activeAuthorizationId: String?,
        onSnapshot: (ModelTaskSnapshot) -> Unit,
    ) {
        execute(
            request = request,
            provider = provider,
            manifest = manifest,
            activeAuthorizationId = activeAuthorizationId,
            onSnapshot = onSnapshot,
        )
    }

    fun executeParse(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
        manifest: ModelEgressManifest?,
        activeAuthorizationId: String?,
        onSnapshot: (ModelTaskSnapshot) -> Unit,
    ) {
        execute(
            request = request,
            provider = provider,
            manifest = manifest,
            activeAuthorizationId = activeAuthorizationId,
            onSnapshot = onSnapshot,
        )
    }

    private fun execute(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
        manifest: ModelEgressManifest?,
        activeAuthorizationId: String?,
        onSnapshot: (ModelTaskSnapshot) -> Unit,
    ) {
        if (!activeRequestIds.add(request.requestId)) return
        if (
            !launchGuard.claim(
                request = request,
                snapshot = null,
                provider = provider,
                manifest = manifest,
                activeAuthorizationId = activeAuthorizationId,
                nowEpochMillis = System.currentTimeMillis(),
            )
        ) {
            activeRequestIds.remove(request.requestId)
            return
        }
        scope.launch {
            try {
                modelTasks.execute(request).collect(onSnapshot)
            } finally {
                activeRequestIds.remove(request.requestId)
            }
        }
    }
}
