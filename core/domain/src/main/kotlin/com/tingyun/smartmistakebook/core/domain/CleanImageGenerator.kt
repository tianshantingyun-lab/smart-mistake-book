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

/**
 * The narrow seam a local tool-loop executor uses to redraw one canonical source
 * asset into a clean sheet. Implementations resolve the asset's bytes (file
 * vault) and run the image-to-image engine; kept asset-id-keyed so the tool
 * runner depends only on this one operation and never on capture persistence.
 * A null result means the generator declined (no capable model / network /
 * asset missing), which the caller surfaces as a non-fatal tool outcome.
 */
fun interface CleanRedrawTool {
    suspend fun redrawCleanImage(sourceAssetId: String): CleanImageResult?
}

data class CleanImageResult(
    val bytes: ByteArray,
    val mimeType: String,
)
