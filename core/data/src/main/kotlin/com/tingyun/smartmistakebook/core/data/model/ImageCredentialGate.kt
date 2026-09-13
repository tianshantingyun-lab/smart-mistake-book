package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult

/**
 * **图片出网（配图与去手写）的唯一闸门**：返回可用于图生图的凭据，或 null。
 * 两条图形通道（[AttachedImageGeneratorFactory] 的配图、`ConfiguredCleanImageGenerator`
 * 的去手写）都必须走这里，因此它们**不可能对同一件事做出不同判断**。
 *
 * 放行条件：本构建可出网 ＋ 有一份配置好的凭据 ＋ 能力测试确认支持图像输入。
 *
 * **关于审计 S-2 的现状（2026-09-14 随口径对齐订正）。** S-2 的形态是：这个函数原先收
 * `networkRequestsAllowed: Boolean`，而两个调用点传的是 `capabilities.networkRequestsAllowed`
 * ——那是**构建变体位**（`localFirst` / `strictOffline`），不是用户的同意开关；于是用户在设置里
 * 关掉「模型智能体同意」之后，配图与去手写**仍然把原图字节发出去**。
 * 当时的修法是把它改成收 `ModelAgentConsentStore?`，让"传错开关"在类型上不可表达。
 *
 * **该修法已随产品口径变更被取代**（`main` 的 `e462f1ea`：删除全局同意开关，「配置模型即同意」）。
 * 现在没有第二个同意状态可以传——**凭据本身就是同意**：没有配置就没有凭据，本函数返回 null，
 * 一个字节都发不出去；要撤回出网就删除配置。所以这里重新收能力位，而 S-2 要防的失效
 * （"撤销后仍上传"）在新口径下不再可表达：**可撤回的东西已经不是那个开关，而是凭据**。
 * 这条断言由 `ImageCredentialGateTest` 与 `ConfiguredCleanImageGeneratorTest` 的
 * "无凭据 / 能力未验证 / 不可出网 → null" 三格钉住。
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
