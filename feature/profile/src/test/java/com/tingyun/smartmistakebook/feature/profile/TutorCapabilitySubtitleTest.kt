package com.tingyun.smartmistakebook.feature.profile

import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.NetworkMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TutorCapabilitySubtitleTest {
    @Test
    fun strictOfflineWithoutAModelShowsTutorAsUnavailable() {
        val capabilities = AppCapabilitySnapshot(
            networkMode = NetworkMode.STRICT_OFFLINE,
            cameraCaptureAvailable = true,
            trustedOcrAvailable = false,
            tutorTeachingEnabled = true,
            remoteModelConfigured = false,
        )

        assertEquals(
            "讲题功能暂不可用 · 查看当前能力",
            tutorCapabilitySubtitle(capabilities),
        )
    }

    @Test
    fun savedButUntestedModelAsksForTheExplicitCapabilityTest() {
        val capabilities = localFirstCapabilities(remoteModelConfigured = true)

        assertEquals(
            "模型配置已保存 · 请完成能力测试",
            tutorCapabilitySubtitle(capabilities),
        )
    }

    @Test
    fun profileOnlyAdvertisesTheCapabilitiesThatPassed() {
        val capabilities = localFirstCapabilities(
            remoteModelConfigured = true,
            remoteModelStructuredOutputVerified = true,
            remoteModelImageInputVerified = false,
        )

        assertEquals(
            "智能讲题已就绪 · 图片读取暂不可用",
            tutorCapabilitySubtitle(capabilities),
        )
    }

    private fun localFirstCapabilities(
        remoteModelConfigured: Boolean,
        remoteModelStructuredOutputVerified: Boolean = false,
        remoteModelImageInputVerified: Boolean = false,
    ) = AppCapabilitySnapshot(
        networkMode = NetworkMode.LOCAL_FIRST,
        cameraCaptureAvailable = true,
        trustedOcrAvailable = false,
        tutorTeachingEnabled = true,
        remoteModelConfigured = remoteModelConfigured,
        remoteModelStructuredOutputVerified = remoteModelStructuredOutputVerified,
        remoteModelImageInputVerified = remoteModelImageInputVerified,
    )
}
