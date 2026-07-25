package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode

object FlavorCapabilityFactory {
    fun create() = AppCapabilitySnapshot(
        networkMode = NetworkMode.LOCAL_FIRST,
        cameraCaptureAvailable = true,
        trustedOcrAvailable = false,
        tutorTeachingEnabled = true,
        remoteModelConfigured = false,
    )
}
