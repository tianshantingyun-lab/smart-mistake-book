package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import android.net.Uri
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.model.AttachedImage

/**
 * Public app-layer entry point for the attached-figure resolver. Assembles a
 * production [AttachedImageGenerator] over the user's configured model
 * credential (SSRF-guarded, same as the chat gateway), resolving each model
 * [AttachedImage] intent to a persisted local `file://` URI.
 *
 * [resolveCurrentSheetBytes] must come from the caller — it supplies the current
 * question's problem-sheet bytes for a REDRAW_PROBLEM (never model-supplied).
 * Not network-capable / no credential / no image capability → returns a resolver
 * that always yields null (so no figure shows), mirroring the clean-redraw gate.
 * Both halves of this egress path are shared with that generator — the credential
 * gate [resolveImageCredential] and the channel construction [ImageChannelFactory]
 * — so the two figure paths cannot decide differently.
 *
 * 审计 S-2 的对齐：这道闸门曾经收「全局模型同意存储」而不是能力位，因为当时的口径里
 * 同意是可以单独撤回的开关；`main` 的 `e462f1ea` 删除该开关后，「配置模型即同意」，
 * **凭据本身就是同意**（没有配置 ⇒ 没有凭据 ⇒ 一个字节都发不出去）。详见
 * `ImageCredentialGate` 的类注释。
 */
object AttachedImageGeneratorFactory {
    fun create(
        context: Context,
        configurationStore: ModelConfigurationStore?,
        networkRequestsAllowed: Boolean,
        resolveCurrentSheetBytes: suspend () -> ByteArray?,
    ): suspend (AttachedImage) -> String? = create(
        context = context,
        configurationStore = configurationStore,
        networkRequestsAllowed = networkRequestsAllowed,
        resolveCurrentSheetBytes = resolveCurrentSheetBytes,
        channelFactory = GuardedEditsChannelFactory,
    )

    /**
     * The same assembly with the network seam opened, so a test can observe whether
     * the gate let this path reach the point where a channel — and with it an upload
     * — gets built. Audit N-28: this is the one image egress route with no such
     * evidence, and through [AttachedImageGenerator] "declined" is indistinguishable
     * from "tried and failed" (both degrade to a missing figure).
     */
    internal fun create(
        context: Context,
        configurationStore: ModelConfigurationStore?,
        networkRequestsAllowed: Boolean,
        resolveCurrentSheetBytes: suspend () -> ByteArray?,
        channelFactory: ImageChannelFactory,
    ): suspend (AttachedImage) -> String? {
        if (configurationStore == null) return { null }
        val vault = AndroidCanonicalAssetVault(context.applicationContext)
        val generator = AttachedImageGenerator(
            generate = { request ->
                val credential = resolveImageCredential(configurationStore, networkRequestsAllowed)
                    ?: throw ImageGenerationException("no usable model credential")
                credential.apiKey.use { apiKey ->
                    val keyChars = apiKey.copyChars()
                    try {
                        val channel = channelFactory.create(
                            baseUrl = credential.configuration.baseUrl,
                            authorization = "Bearer ${String(keyChars)}",
                        )
                        channel.generate(request)
                    } finally {
                        java.util.Arrays.fill(keyChars, '\u0000')
                    }
                }
            },
            persist = { bytes, mimeType, sourceType, createdAt ->
                vault.persistCleanImageBytes(
                    bytes = bytes,
                    mimeType = mimeType,
                    sourceType = sourceType,
                    createdAtEpochMillis = createdAt,
                )
            },
            resolveCurrentSheetBytes = resolveCurrentSheetBytes,
            uriFor = { record -> Uri.fromFile(vault.resolve(record)).toString() },
        )
        return { image -> generator.resolve(listOf(image), System.currentTimeMillis())[image.imageId] }
    }
}
