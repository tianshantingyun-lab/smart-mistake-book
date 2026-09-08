package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult

/**
 * Shared credential gate for the image-to-image / generation channels: returns
 * the available credential (with a verified image-capable model) or null when
 * networking is not allowed / no credential is configured / the capability test
 * did not confirm image input. Mirrors the gating in the clean-redraw generator
 * so both figure paths decide identically.
 */
internal suspend fun resolveImageCredential(
    configurationStore: ModelConfigurationStore,
    networkRequestsAllowed: Boolean,
): ModelCredentialReadResult.Available? {
    if (!networkRequestsAllowed) return null
    val read = configurationStore.readCredential()
    val available = read as? ModelCredentialReadResult.Available ?: return null
    val verification = available.configuration.capabilityVerification
    if (verification == null || !verification.supportsImageInput) return null
    return available
}
