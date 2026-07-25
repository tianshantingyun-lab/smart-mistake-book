package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode

object FlavorCapabilityFactory {
    fun create() = AppCapabilitySnapshot(
        networkMode = NetworkMode.STRICT_OFFLINE,
        cameraCaptureAvailable = true,
        trustedOcrAvailable = false,
        tutorTeachingEnabled = false,
        remoteModelConfigured = false,
    )
}
