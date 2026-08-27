package com.tingyun.smartmistakebook.core.model

enum class NetworkMode {
    STRICT_OFFLINE,
    LOCAL_FIRST,
}

/** A point-in-time view of capabilities that the current build can honestly offer. */
data class AppCapabilitySnapshot(
    val networkMode: NetworkMode,
    val cameraCaptureAvailable: Boolean,
    val trustedOcrAvailable: Boolean,
    val tutorTeachingEnabled: Boolean,
    val remoteModelConfigured: Boolean,
    val remoteModelCapabilitiesTested: Boolean = false,
    val remoteModelImageInputVerified: Boolean = false,
    val remoteModelStructuredOutputVerified: Boolean = false,
    /** Global preference: run intelligent mistake organization automatically once a model is available. */
    val organizationAutoRun: Boolean = true,
) {
    val networkRequestsAllowed: Boolean
        get() = networkMode == NetworkMode.LOCAL_FIRST

    val remoteModelAvailable: Boolean
        get() = networkRequestsAllowed && remoteModelConfigured &&
            remoteModelStructuredOutputVerified
}
