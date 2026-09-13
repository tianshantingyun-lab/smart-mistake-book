package com.tingyun.smartmistakebook.core.data.model

/**
 * Builds the authenticated image channel over the SSRF-guarded edits endpoint.
 *
 * Shared by both image egress paths ([AttachedImageGeneratorFactory] and
 * `ConfiguredCleanImageGenerator`) for the same reason [resolveImageCredential]
 * is shared: they must not be able to decide the same question differently.
 *
 * It is also the seam that makes "the gate refused **before** anything was
 * constructed" assertable — building the channel is the first step that reaches
 * the network, so a call count on this factory is the difference between
 * "declined" and "tried and failed" (which look identical from the outside, since
 * one failed figure degrades to that figure's absence).
 */
internal fun interface ImageChannelFactory {
    suspend fun create(baseUrl: String, authorization: String): ImageGenerationChannel
}

/** Production factory: the authenticated channel over the guarded edits endpoint. */
internal object GuardedEditsChannelFactory : ImageChannelFactory {
    override suspend fun create(
        baseUrl: String,
        authorization: String,
    ): ImageGenerationChannel {
        val endpoint = resolveGuardedEdits(baseUrl)
        return OpenAiImageGenerationChannel(
            baseUrl = endpoint.baseUrl,
            client = endpoint.client,
            authorization = authorization,
        )
    }
}
