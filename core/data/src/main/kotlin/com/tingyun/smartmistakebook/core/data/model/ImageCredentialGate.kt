package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelAgentConsentStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult

/**
 * **图片出网（配图与去手写）的唯一闸门**：返回可用于图生图的凭据，或 null。
 *
 * 三方都满足才放行：用户已给出全局「模型智能体」同意、有配置好的凭据、能力测试确认支持图像输入。
 * 两条图形通道（[AttachedImageGeneratorFactory] 的配图、`ConfiguredCleanImageGenerator` 的去手写）
 * 都必须走这里，因此它们**不可能对同一件事做出不同判断**。
 *
 * **为什么参数是 `ModelAgentConsentStore?` 而不是 `Boolean`（审计 S-2）。**
 * 原签名收一个 `networkRequestsAllowed: Boolean`，而两个调用点传的都是
 * `capabilities.networkRequestsAllowed`——那是**构建变体位**（`localFirst` / `strictOffline`），
 * **不是用户的同意开关**。后果：用户在设置里关掉「模型智能体同意」后，配图与去手写**仍然把原图
 * 字节发出去**（原 finding `egress-bypass-image-channel` 的精确形态：不是"没检查"，是"读错了开关"）。
 * 改成收同意存储之后，**同一类错误在类型上不可表达**——布尔值再也传不进来，
 * 而且 `Switch` 型漏检（拿"存储存在与否"当同意）有断言钉住。
 *
 * 离线变体不需要额外的变体位检查：`SmartMistakeBookApplication` 只在
 * `capabilities.networkRequestsAllowed` 为真时构造同意存储，因此 `null` 已经同时表示
 * 「这个变体不允许联网」与「没有同意渠道」。**传 null 一律拒绝。**
 */
internal suspend fun resolveImageCredential(
    configurationStore: ModelConfigurationStore,
    modelAgentConsentStore: ModelAgentConsentStore?,
): ModelCredentialReadResult.Available? {
    if (modelAgentConsentStore?.current() != true) return null
    val read = configurationStore.readCredential()
    val available = read as? ModelCredentialReadResult.Available ?: return null
    val verification = available.configuration.capabilityVerification
    if (verification == null || !verification.supportsImageInput) return null
    return available
}
