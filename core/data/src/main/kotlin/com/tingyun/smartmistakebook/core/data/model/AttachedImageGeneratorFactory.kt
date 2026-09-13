package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import android.net.Uri
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.domain.ModelAgentConsentStore
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
 * No global model-agent consent (or no credential / no image capability) →
 * returns a resolver that always yields null (so no figure shows), mirroring the
 * clean-redraw gate. Consent is read through [resolveImageCredential], which is
 * the shared gate for both figure paths (audit S-2: it used to read a build-flavor
 * capability bit, so revoking consent did not stop the upload).
 */
object AttachedImageGeneratorFactory {
    fun create(
        context: Context,
        configurationStore: ModelConfigurationStore?,
        modelAgentConsentStore: ModelAgentConsentStore?,
        resolveCurrentSheetBytes: suspend () -> ByteArray?,
    ): suspend (AttachedImage) -> String? {
        if (configurationStore == null) return { null }
        val vault = AndroidCanonicalAssetVault(context.applicationContext)
        val generator = AttachedImageGenerator(
            generate = { request ->
                val credential = resolveImageCredential(configurationStore, modelAgentConsentStore)
                    ?: throw ImageGenerationException("no usable model credential")
                credential.apiKey.use { apiKey ->
                    val keyChars = apiKey.copyChars()
                    try {
                        val endpoint = resolveGuardedEdits(credential.configuration.baseUrl)
                        val channel = OpenAiImageGenerationChannel(
                            baseUrl = endpoint.baseUrl,
                            client = endpoint.client,
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
