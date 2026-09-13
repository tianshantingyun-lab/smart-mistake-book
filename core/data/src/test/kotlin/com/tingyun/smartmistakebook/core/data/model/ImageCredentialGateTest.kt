package com.tingyun.smartmistakebook.core.data.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片出网闸门（审计 S-2／原 finding `egress-bypass-image-channel`）。
 *
 * **S-2 的失败形状**：闸门的输入原先是 `capabilities.networkRequestsAllowed`——那是**构建变体位**
 * （`localFirst` / `strictOffline`），**不是用户可以撤回的东西**。于是用户在设置里关掉
 * 「模型智能体同意」之后，配图与去手写**仍然把原图字节发出去**，
 * 而同一个应用里的聊天、捕获、批量导入三条路径都老老实实读用户开关。
 *
 * **2026-09-14 随口径对齐订正：判据换了，要防的失败没消失。** `main` 的 `e462f1ea` 删除了全局
 * 同意开关——「配置模型即同意」，于是**凭据本身就是同意**：
 *  - 没有配置 ⇒ `readCredential()` 返回 `Missing` ⇒ 本函数 null ⇒ **一个字节都发不出去**；
 *  - **撤回出网 = 删除配置**，不再是关一个开关。
 *
 * 所以下面要钉的不再是"开关的值"，而是**"凭据现在还在不在"**——两者只是同一件事换了个载体。
 * 这个文件仍然必要，理由与当年一致：**"读到了凭据"与"读对了它此刻还在不在"是两件事**；
 * 一个把"配置存储对象存在"当同意的实现，会在下面第二条上露馅（配置已删、存储对象仍在）。
 */
class ImageCredentialGateTest {

    @Test
    fun declinesWhenThisBuildCannotEgress() = runBlocking {
        assertNull(
            "本构建不可出网时一律拒绝——严格离线变体的形状",
            resolveImageCredential(FakeModelConfigurationStore(), networkRequestsAllowed = false),
        )
    }

    @Test
    fun declinesAfterTheConfigurationIsRemovedMidSession() = runBlocking {
        val store = FakeModelConfigurationStore()
        assertTrue(
            "先决条件：配置在时确实放行，否则后面那次「拒绝」无从谈起",
            resolveImageCredential(store, networkRequestsAllowed = true) != null,
        )

        // 撤回出网 = 删除配置。同一进程内下一次出网必须立刻受影响（不能等重启）。
        store.credentialAvailable = false

        assertNull(
            "凭据是每次出网现读的，不是进程启动时快照的；配置删了就必须立刻拒绝",
            resolveImageCredential(store, networkRequestsAllowed = true),
        )
    }

    @Test
    fun declinesWithoutACredentialOrAnImageCapableVerification() = runBlocking {
        assertNull(
            "没配模型 → 拒绝",
            resolveImageCredential(
                FakeModelConfigurationStore(credentialAvailable = false),
                networkRequestsAllowed = true,
            ),
        )
        assertNull(
            "能力测试没跑过 → 拒绝",
            resolveImageCredential(
                FakeModelConfigurationStore(
                    snapshot = FakeModelConfigurationStore.configuration(capability = null),
                ),
                networkRequestsAllowed = true,
            ),
        )
        assertNull(
            "能力测试跑过但没确认图像输入 → 拒绝",
            resolveImageCredential(
                FakeModelConfigurationStore(
                    snapshot = FakeModelConfigurationStore.configuration(
                        capability = FakeModelConfigurationStore.configuredCapability(
                            supportsImageInput = false,
                        ),
                    ),
                ),
                networkRequestsAllowed = true,
            ),
        )
    }

    @Test
    fun grantsOnlyWhenAllConditionsHold() = runBlocking {
        val available = resolveImageCredential(
            FakeModelConfigurationStore(),
            networkRequestsAllowed = true,
        )

        assertEquals(FakeModelConfigurationStore.BASE_URL, available?.configuration?.baseUrl)
        assertEquals(FakeModelConfigurationStore.MODEL_ID, available?.configuration?.modelId)
    }
}
