package com.tingyun.smartmistakebook.core.data.session

internal data class CaptureModelSourceSessionRef(
    val pageIndex: Int,
    val assetId: String,
    val contentSha256: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        require(pageIndex >= 0) { "Capture source page index must not be negative" }
        assetId.requireSessionIdentifier("Capture source asset id")
        contentSha256.requireSessionFingerprint("Capture source asset fingerprint")
        require(mimeType.isNotBlank()) { "Capture source MIME type must not be blank" }
        require(byteSize > 0) { "Capture source byte size must be positive" }
        require(width > 0 && height > 0) { "Capture source dimensions must be positive" }
        require(createdAtEpochMillis >= 0) { "Capture source time must not be negative" }
    }
}

/**
 * Privacy-minimal dependency head used before a model task starts.
 *
 * It deliberately excludes recognized text, title, answers, classifications, and every committed
 * student-mistake identity. A caller can only prove that its transient revision and assets are
 * still current.
 */
internal data class CaptureModelDependencySnapshot(
    val scope: SessionScope,
    val draftSessionId: String,
    val revisionNumber: Int,
    val revisionFingerprint: String,
    val version: SessionVersion,
    val status: String,
    val sourceAssets: List<CaptureModelSourceSessionRef>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        draftSessionId.requireSessionIdentifier("Capture draft session id")
        require(revisionNumber > 0) { "Capture draft revision must be positive" }
        revisionFingerprint.requireSessionFingerprint("Capture draft revision fingerprint")
        status.requireSessionIdentifier("Capture draft session status")
        require(sourceAssets.isNotEmpty()) { "Capture model dependency requires source assets" }
        require(sourceAssets.map { it.pageIndex } == sourceAssets.indices.toList()) {
            "Capture source assets must be contiguous and ordered"
        }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Capture model dependency times are invalid"
        }
    }
}

internal data class CaptureModelDependencyQuery(
    val scope: SessionScope,
    val draftSessionId: String,
) {
    init {
        draftSessionId.requireSessionIdentifier("Capture draft session id")
    }
}

internal fun interface CaptureModelDependencyReadPort {
    suspend fun read(
        query: CaptureModelDependencyQuery,
    ): CaptureModelDependencySnapshot?
}
