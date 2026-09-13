package com.tingyun.smartmistakebook.core.data.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片出网闸门（审计 S-2／原 finding `egress-bypass-image-channel`）。
 *
 * 消灭的失败：闸门的输入原先是 `capabilities.networkRequestsAllowed`——那是**构建变体位**
 * （`localFirst` / `strictOffline`），**不是用户的同意开关**。于是用户在设置里关掉
 * 「模型智能体同意」之后，配图与去手写**仍然把原图字节发出去**，
 * 而同一个应用里的聊天、捕获、批量导入三条路径都老老实实读用户开关。
 *
 * 修复的形式是**换掉输入的类型**（`Boolean` → `ModelAgentConsentStore?`），
 * 因此原缺陷在类型上不可表达；但这个文件仍然必要，因为**"读到了开关"与"读对了开关的值"是两件事**：
 * 变异 M1（只看存储是否存在、不看它的值）编译得过，只有下面第 1、2 条断言能抓住它。
 */
class ImageCredentialGateTest {

    @Test
    fun declinesWhenTheUserHasNotGrantedConsent() = runBlocking {
        val consent = FakeModelAgentConsentStore(granted = false)

        assertNull(
            "撤销同意后必须拒绝——这正是 S-2 的失败形状：开关关了，字节照样发出去",
            resolveImageCredential(FakeModelConfigurationStore(), consent),
        )
    }

    @Test
    fun declinesAfterConsentIsRevokedMidSession() = runBlocking {
        val consent = FakeModelAgentConsentStore(granted = true)
        val store = FakeModelConfigurationStore()
        assertTrue(
            "先决条件：同意时确实放行，否则后面那次「拒绝」无从谈起",
            resolveImageCredential(store, consent) != null,
        )

        // 用户在设置页关掉开关，同一进程内下一次出网必须立刻受影响（不能等重启）。
        consent.granted = false

        assertNull("同意是每次出网现读的，不是进程启动时快照的", resolveImageCredential(store, consent))
    }

    @Test
    fun declinesWhenThereIsNoConsentChannelAtAll() = runBlocking {
        // 离线变体（strictOffline）不构造同意存储，因此 null 同时表示"这个变体不许联网"。
        assertNull(resolveImageCredential(FakeModelConfigurationStore(), null))
    }

    @Test
    fun declinesWithoutACredentialOrAnImageCapableVerification() = runBlocking {
        val consent = FakeModelAgentConsentStore(granted = true)

        assertNull(
            "有同意但没配模型 → 拒绝",
            resolveImageCredential(FakeModelConfigurationStore(credentialAvailable = false), consent),
        )
        assertNull(
            "能力测试没跑过 → 拒绝",
            resolveImageCredential(FakeModelConfigurationStore(snapshot = FakeModelConfigurationStore.configuration(capability = null)), consent),
        )
        assertNull(
            "能力测试跑过但没确认图像输入 → 拒绝",
            resolveImageCredential(
                FakeModelConfigurationStore(
                    snapshot = FakeModelConfigurationStore.configuration(
                        capability = FakeModelConfigurationStore.configuredCapability(supportsImageInput = false),
                    ),
                ),
                consent,
            ),
        )
    }

    @Test
    fun grantsOnlyWhenAllThreeConditionsHold() = runBlocking {
        val available = resolveImageCredential(
            FakeModelConfigurationStore(),
            FakeModelAgentConsentStore(granted = true),
        )

        assertEquals(FakeModelConfigurationStore.BASE_URL, available?.configuration?.baseUrl)
        assertEquals(FakeModelConfigurationStore.MODEL_ID, available?.configuration?.modelId)
    }
}
