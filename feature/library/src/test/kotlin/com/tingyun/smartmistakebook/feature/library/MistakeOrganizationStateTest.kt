package com.tingyun.smartmistakebook.feature.library

import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MistakeOrganizationStateTest {
    @Test
    fun providerStateOwnsTheSurfaceBeforeAnyOrganizationWork() {
        assertEquals(
            MistakeOrganizationSurfaceState.CapabilityLoading,
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    providerAvailability = OrganizationProviderAvailability.LOADING,
                    hasPreparation = true,
                ),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.CapabilityLoadFailed,
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    providerAvailability = OrganizationProviderAvailability.LOAD_FAILED,
                    hasPreparation = true,
                ),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.ProviderUnavailable,
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    providerAvailability = OrganizationProviderAvailability.UNAVAILABLE,
                    hasPreparation = true,
                ),
            ),
        )
    }

    @Test
    fun preparationMovesFromLoadingToConsentWithoutExtraUserSteps() {
        assertEquals(
            MistakeOrganizationSurfaceState.PreparationLoading,
            resolveMistakeOrganizationSurface(readyFacts()),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.Consent(paused = false, running = false),
            resolveMistakeOrganizationSurface(readyFacts().copy(hasPreparation = true)),
        )
    }

    @Test
    fun dismissedOrInconsistentPreparationOffersOneRecoveryAction() {
        assertEquals(
            MistakeOrganizationSurfaceState.PreparationRetry,
            resolveMistakeOrganizationSurface(
                readyFacts().copy(preparationDismissed = true),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.PreparationRetry,
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    hasRecoveredRequest = true,
                    preparationDismissed = true,
                ),
            ),
        )
    }

    @Test
    fun recoveredExternalRequestRequiresFreshConsentAndShowsProgress() {
        assertEquals(
            MistakeOrganizationSurfaceState.Consent(paused = true, running = false),
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    hasPreparation = true,
                    hasRecoveredRequest = true,
                ),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.Consent(paused = true, running = true),
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    hasPreparation = true,
                    hasRecoveredRequest = true,
                    isContinuingRecoveredRequest = true,
                ),
            ),
        )
    }

    @Test
    fun allActiveTaskStatusesShareTheRunningSurface() {
        val runningStatuses = listOf(
            ModelTaskStatus.WAITING_FOR_MODEL,
            ModelTaskStatus.QUEUED,
            ModelTaskStatus.RUNNING,
            ModelTaskStatus.STREAMING,
        )

        runningStatuses.forEach { status ->
            assertEquals(
                MistakeOrganizationSurfaceState.Running("正在读取题目"),
                resolveMistakeOrganizationSurface(
                    readyFacts().copy(
                        hasPreparation = true,
                        taskStatus = status,
                        taskMessage = "正在读取题目",
                    ),
                ),
            )
        }
    }

    @Test
    fun terminalExecutionFailureKeepsAStudentFriendlyFallbackMessage() {
        assertEquals(
            MistakeOrganizationSurfaceState.ExecutionFailed("这次整理没有完成"),
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    hasPreparation = true,
                    taskStatus = ModelTaskStatus.PERMANENT_FAILURE,
                    taskMessage = "",
                ),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.ExecutionFailed("网络不太稳定，请再试一次"),
            resolveMistakeOrganizationSurface(
                readyFacts().copy(
                    hasPreparation = true,
                    taskStatus = ModelTaskStatus.CANCELLED,
                    taskMessage = "网络不太稳定，请再试一次",
                ),
            ),
        )
    }

    @Test
    fun unusableSuccessfulResultCanNeverReachTheAppliedSurface() {
        assertEquals(
            MistakeOrganizationSurfaceState.UnusableResult,
            resolveMistakeOrganizationSurface(
                successfulFacts().copy(hasUsableOutput = false),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.UnusableResult,
            resolveMistakeOrganizationSurface(
                successfulFacts().copy(taskRequestId = null),
            ),
        )
    }

    @Test
    fun staleApplyResultCannotBeShownForANewerRequest() {
        assertEquals(
            MistakeOrganizationSurfaceState.Applying,
            resolveMistakeOrganizationSurface(
                successfulFacts().copy(
                    applyState = AutomaticOrganizationState.Applied("older-request"),
                ),
            ),
        )
    }

    @Test
    fun successfulResultMovesThroughEveryLocalApplyOutcome() {
        val requestId = "organization-request"
        val base = successfulFacts().copy(taskRequestId = requestId)

        assertEquals(
            MistakeOrganizationSurfaceState.Applying,
            resolveMistakeOrganizationSurface(base),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.Applying,
            resolveMistakeOrganizationSurface(
                base.copy(applyState = AutomaticOrganizationState.Applying(requestId)),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.Incomplete,
            resolveMistakeOrganizationSurface(
                base.copy(applyState = AutomaticOrganizationState.Incomplete(requestId)),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.ApplyFailed("暂时无法保存"),
            resolveMistakeOrganizationSurface(
                base.copy(
                    applyState = AutomaticOrganizationState.Failed(
                        requestId,
                        "暂时无法保存",
                    ),
                ),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.PreservedUserCorrection,
            resolveMistakeOrganizationSurface(
                base.copy(
                    applyState = AutomaticOrganizationState.PreservedUserCorrection(requestId),
                ),
            ),
        )
        assertEquals(
            MistakeOrganizationSurfaceState.Applied,
            resolveMistakeOrganizationSurface(
                base.copy(applyState = AutomaticOrganizationState.Applied(requestId)),
            ),
        )
    }

    private fun readyFacts() = MistakeOrganizationSurfaceFacts(
        providerAvailability = OrganizationProviderAvailability.READY,
    )

    private fun successfulFacts() = readyFacts().copy(
        hasPreparation = true,
        taskStatus = ModelTaskStatus.SUCCEEDED,
        taskRequestId = "organization-request",
        hasUsableOutput = true,
    )
}
