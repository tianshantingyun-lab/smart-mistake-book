package com.tingyun.smartmistakebook.core.domain

/**
 * The one seam through which a captured problem's original photo is redrawn into a
 * clean sheet at commit time. Implementations are wired at the app layer and may
 * call an image-to-image provider (e.g. the tools/image-mcp-server, or OpenAI
 * `/v1/images/edits`). A null generator at the repository means "never redraw",
 * so the mistake-book commit stays fast and offline-safe.
 */
fun interface CleanImageGenerator {
    /**
     * Produces the clean redraw, or null when the generator declines (e.g. no
     * capable model is configured). A null result leaves the revision with the
     * original photo only; it is never a failure of the commit.
     */
    suspend fun generateClean(originalBytes: ByteArray, mimeType: String): CleanImageResult?
}

data class CleanImageResult(
    val bytes: ByteArray,
    val mimeType: String,
)
