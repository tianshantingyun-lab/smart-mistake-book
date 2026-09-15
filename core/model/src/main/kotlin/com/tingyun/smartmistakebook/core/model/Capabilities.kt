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
    /** 上次能力测试是鉴权失败：文案要提示"API Key 可能失效"而不是"模型不兼容"。 */
    val remoteModelAuthenticationFailed: Boolean = false,
) {
    val networkRequestsAllowed: Boolean
        get() = networkMode == NetworkMode.LOCAL_FIRST

    val remoteModelAvailable: Boolean
        get() = networkRequestsAllowed && remoteModelConfigured &&
            remoteModelStructuredOutputVerified
}
