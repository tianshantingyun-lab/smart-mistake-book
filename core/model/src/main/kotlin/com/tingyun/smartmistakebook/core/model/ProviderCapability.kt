package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/**
 * Captured probe snapshot for an AI model provider. Bound to a specific
 * base URL, model ID, and configuration version. Used to decide streaming
 * strategy, vision support, and structured output format at request time.
 *
 * Distinguished from the contract-side [com.tingyun.smartmistakebook.core.model
 * .ProviderCapabilitySnapshot] in `ModelTasks.kt` (which describes declared
 * capability at task-authorization time) by representing the actual probe
 * result observed at runtime.
 */
@Serializable
data class ProviderProbeSnapshot(
    val providerBaseUrl: String,
    val modelId: String,
    val configVersion: String,
    val supportsText: Boolean,
    val supportsVision: Boolean,
    val supportsStructuredOutput: Boolean,
    val supportsSseStreaming: Boolean,
    val maximumInputBytes: Long? = null,
    val maximumImageCount: Int? = null,
    val testedAtEpochMillis: Long,
    val protocolFingerprint: String,
    val errorMessage: String? = null,
) {
    init {
        require(providerBaseUrl.isNotBlank()) { "Provider base URL must not be blank" }
        require(modelId.isNotBlank()) { "Model id must not be blank" }
        require(configVersion.isNotBlank()) { "Config version must not be blank" }
        require(testedAtEpochMillis >= 0) { "Test time must not be negative" }
        require(protocolFingerprint.isNotBlank()) { "Protocol fingerprint must not be blank" }
    }

    /**
     * Whether this provider supports the full Tutor pipeline (text + vision + streaming).
     */
    val isFullTutorCapable: Boolean
        get() = supportsText && supportsSseStreaming

    /**
     * Whether this provider can handle image-attached problems.
     */
    val isVisionCapable: Boolean
        get() = supportsText && supportsVision

    /**
     * Returns a degraded copy that disables features known to be broken.
     */
    fun withSseDisabled(): ProviderProbeSnapshot = copy(
        supportsSseStreaming = false,
        errorMessage = "SSE streaming disabled after capability probe failure",
    )

    fun withVisionDisabled(): ProviderProbeSnapshot = copy(
        supportsVision = false,
        errorMessage = "Vision disabled after capability probe failure",
    )
}

/**
 * Strategy for falling back when a provider doesn't support the requested protocol.
 */
enum class ProviderFallbackStrategy {
    /** Use the provider as-is; failures surface as errors. */
    FAIL_FAST,
    /** Disable SSE and retry with standard JSON request/response. */
    FALLBACK_TO_NON_STREAMING,
    /** Disable vision and retry with text-only request. */
    FALLBACK_TO_TEXT_ONLY,
    /** Disable both SSE and vision. */
    FALLBACK_TO_TEXT_ONLY_NON_STREAMING,
    /** Skip this provider entirely and try the next configured provider. */
    SKIP_PROVIDER,
}

/**
 * Result of a capability probe against a provider endpoint.
 */
sealed interface CapabilityProbeResult {
    data class Success(
        val snapshot: ProviderProbeSnapshot,
    ) : CapabilityProbeResult

    data class Failure(
        val errorMessage: String,
        val isRetryable: Boolean,
    ) : CapabilityProbeResult

    data object Timeout : CapabilityProbeResult
}
