package com.tingyun.smartmistakebook.feature.library

import com.tingyun.smartmistakebook.core.model.ModelTaskStatus

internal enum class OrganizationProviderAvailability {
    LOADING,
    LOAD_FAILED,
    UNAVAILABLE,
    READY,
}

internal data class MistakeOrganizationSurfaceFacts(
    val providerAvailability: OrganizationProviderAvailability,
    val hasPreparation: Boolean = false,
    val isPreparing: Boolean = false,
    val preparationDismissed: Boolean = false,
    val hasRecoveredRequest: Boolean = false,
    val isContinuingRecoveredRequest: Boolean = false,
    val taskStatus: ModelTaskStatus? = null,
    val taskMessage: String? = null,
    val taskRequestId: String? = null,
    val hasUsableOutput: Boolean = false,
    val applyState: AutomaticOrganizationState = AutomaticOrganizationState.Idle,
)

internal sealed interface MistakeOrganizationSurfaceState {
    data object CapabilityLoading : MistakeOrganizationSurfaceState
    data object CapabilityLoadFailed : MistakeOrganizationSurfaceState
    data object ProviderUnavailable : MistakeOrganizationSurfaceState
    data object PreparationLoading : MistakeOrganizationSurfaceState
    data object PreparationRetry : MistakeOrganizationSurfaceState

    data class Consent(
        val paused: Boolean,
        val running: Boolean,
    ) : MistakeOrganizationSurfaceState

    data class ExecutionFailed(val message: String) : MistakeOrganizationSurfaceState
    data class Running(val message: String?) : MistakeOrganizationSurfaceState
    data object UnusableResult : MistakeOrganizationSurfaceState
    data object Applying : MistakeOrganizationSurfaceState
    data object Incomplete : MistakeOrganizationSurfaceState
    data class ApplyFailed(val message: String) : MistakeOrganizationSurfaceState
    data object PreservedUserCorrection : MistakeOrganizationSurfaceState
    data object Applied : MistakeOrganizationSurfaceState
}

internal fun resolveMistakeOrganizationSurface(
    facts: MistakeOrganizationSurfaceFacts,
): MistakeOrganizationSurfaceState = when {
    facts.providerAvailability == OrganizationProviderAvailability.LOAD_FAILED ->
        MistakeOrganizationSurfaceState.CapabilityLoadFailed

    facts.providerAvailability == OrganizationProviderAvailability.LOADING ->
        MistakeOrganizationSurfaceState.CapabilityLoading

    facts.providerAvailability == OrganizationProviderAvailability.UNAVAILABLE ->
        MistakeOrganizationSurfaceState.ProviderUnavailable

    facts.hasRecoveredRequest && facts.hasPreparation -> MistakeOrganizationSurfaceState.Consent(
        paused = true,
        running = facts.isContinuingRecoveredRequest,
    )

    facts.hasRecoveredRequest -> MistakeOrganizationSurfaceState.PreparationRetry

    !facts.hasPreparation && (facts.isPreparing || !facts.preparationDismissed) ->
        MistakeOrganizationSurfaceState.PreparationLoading

    !facts.hasPreparation -> MistakeOrganizationSurfaceState.PreparationRetry

    facts.taskStatus == ModelTaskStatus.PERMANENT_FAILURE ||
        facts.taskStatus == ModelTaskStatus.CANCELLED -> {
        MistakeOrganizationSurfaceState.ExecutionFailed(
            message = facts.taskMessage?.takeIf(String::isNotBlank) ?: "这次整理没有完成",
        )
    }

    facts.taskStatus in RUNNING_ORGANIZATION_STATUSES ->
        MistakeOrganizationSurfaceState.Running(facts.taskMessage)

    facts.taskStatus != ModelTaskStatus.SUCCEEDED -> MistakeOrganizationSurfaceState.Consent(
        paused = false,
        running = false,
    )

    !facts.hasUsableOutput || facts.taskRequestId == null ->
        MistakeOrganizationSurfaceState.UnusableResult

    facts.applyState.requestId != null &&
        facts.applyState.requestId != facts.taskRequestId ->
        MistakeOrganizationSurfaceState.Applying

    else -> when (val applyState = facts.applyState) {
        AutomaticOrganizationState.Idle,
        is AutomaticOrganizationState.Applying,
        -> MistakeOrganizationSurfaceState.Applying

        is AutomaticOrganizationState.Incomplete ->
            MistakeOrganizationSurfaceState.Incomplete

        is AutomaticOrganizationState.Failed ->
            MistakeOrganizationSurfaceState.ApplyFailed(applyState.message)

        is AutomaticOrganizationState.PreservedUserCorrection ->
            MistakeOrganizationSurfaceState.PreservedUserCorrection

        is AutomaticOrganizationState.Applied ->
            MistakeOrganizationSurfaceState.Applied
    }
}

private val RUNNING_ORGANIZATION_STATUSES = setOf(
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
)

internal sealed interface AutomaticOrganizationState {
    val requestId: String?

    data object Idle : AutomaticOrganizationState {
        override val requestId: String? = null
    }

    data class Applying(override val requestId: String) : AutomaticOrganizationState
    data class Applied(override val requestId: String) : AutomaticOrganizationState
    data class PreservedUserCorrection(
        override val requestId: String,
    ) : AutomaticOrganizationState
    data class Incomplete(override val requestId: String) : AutomaticOrganizationState
    data class Failed(
        override val requestId: String,
        val message: String,
    ) : AutomaticOrganizationState
}
