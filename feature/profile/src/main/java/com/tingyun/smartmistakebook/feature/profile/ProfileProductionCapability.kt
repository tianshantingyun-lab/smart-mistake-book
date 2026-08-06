package com.tingyun.smartmistakebook.feature.profile

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository

/**
 * The only production learning dependency that the profile feature may consume.
 *
 * Settings on the current profile page are navigation actions, so no settings storage is part of
 * this capability. Teaching references are tutor inputs and are not read by the profile UI.
 */
class ProfileProductionCapability(
    val learningMasteryDisplay: LearningMasteryDisplayRepository,
)

/**
 * Publication-owned source. It remains empty until the application publishes the complete graph.
 */
fun interface ProfileProductionCapabilitySource {
    fun currentCapability(): ProfileProductionCapability?
}

/**
 * Fail-closed production boundary for profile and learning-mastery presentation.
 */
class ProfileProductionCapabilityProvider(
    private val adapterAvailability: ProductionAdapterAvailabilityPort,
    private val capabilitySource: ProfileProductionCapabilitySource,
) {
    internal fun resolve(): ProfileProductionCapabilityDecision {
        val manifest =
            try {
                adapterAvailability.readManifest()
            } catch (_: Exception) {
                return ProfileProductionCapabilityDecision.Blocked(
                    ProfileProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
                )
            }

        val missingRequiredAdapters =
            REQUIRED_PROFILE_ADAPTERS - manifest.availableAdapters
        if (missingRequiredAdapters.isNotEmpty()) {
            return ProfileProductionCapabilityDecision.Blocked(
                reason =
                    ProfileProductionCapabilityBlockReason
                        .REQUIRED_ADAPTERS_UNAVAILABLE,
                missingRequiredAdapters = missingRequiredAdapters,
            )
        }
        if (!manifest.isComplete) {
            return ProfileProductionCapabilityDecision.Blocked(
                ProfileProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            )
        }

        val capability =
            try {
                capabilitySource.currentCapability()
            } catch (_: Exception) {
                null
            }
                ?: return ProfileProductionCapabilityDecision.Blocked(
                    ProfileProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
                )

        return ProfileProductionCapabilityDecision.Available(capability)
    }

    companion object {
        /**
         * ProductionAdapter currently has no separate learning-mastery-display entry. The bounded
         * display repository is published by the mastery-context authority runtime, so this is the
         * exact existing adapter rather than a feature-defined substitute.
         */
        val REQUIRED_PROFILE_ADAPTERS: Set<ProductionAdapter> =
            setOf(ProductionAdapter.TUTOR_MASTERY_CONTEXT)
    }
}

internal enum class ProfileProductionCapabilityBlockReason {
    MANIFEST_UNAVAILABLE,
    REQUIRED_ADAPTERS_UNAVAILABLE,
    PRODUCTION_MANIFEST_INCOMPLETE,
    CAPABILITY_UNPUBLISHED,
}

internal sealed interface ProfileProductionCapabilityDecision {
    data class Available(
        val capability: ProfileProductionCapability,
    ) : ProfileProductionCapabilityDecision

    data class Blocked(
        val reason: ProfileProductionCapabilityBlockReason,
        val missingRequiredAdapters: Set<ProductionAdapter> = emptySet(),
    ) : ProfileProductionCapabilityDecision
}
