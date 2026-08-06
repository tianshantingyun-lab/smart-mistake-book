package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CaptureMergeSessionReceiptReference

private const val MAX_CAPTURE_DRAFT_SOURCE_ASSETS = 8
private const val SESSION_VERSION_ASSET_BITS = 8

@JvmInline
internal value class CaptureDraftSessionId(
    val value: String,
) {
    init {
        requireCaptureSessionIdentifier(value, "Capture draft session id")
    }
}

internal data class ImportCaptureDraftSessionCommand(
    val request: CaptureDraftImportRequest,
)

internal data class ReadCaptureDraftCanonicalAssetsQuery(
    val draftSessionId: CaptureDraftSessionId,
    val maximumAssetCount: Int = MAX_CAPTURE_DRAFT_SOURCE_ASSETS,
) {
    init {
        require(maximumAssetCount in 1..MAX_CAPTURE_DRAFT_SOURCE_ASSETS) {
            "Capture draft asset read budget is invalid"
        }
    }
}

internal data class CaptureDraftCanonicalAssetPage(
    val pageIndex: Int,
    val asset: CaptureAssetDescriptor,
) {
    init {
        require(pageIndex >= 0) { "Capture draft source page index must not be negative" }
    }
}

/**
 * Immutable view of one temporary draft's canonical sources.
 *
 * [sessionVersion] changes when either the semantic draft revision or its ordered source bundle
 * changes. Consumers must echo both the version and [assetOrderFingerprint] when merging.
 */
internal data class CaptureDraftCanonicalAssetBundle(
    val draftSessionId: CaptureDraftSessionId,
    val revisionNumber: Int,
    val sessionVersion: Long,
    val assetOrderFingerprint: String,
    val pages: List<CaptureDraftCanonicalAssetPage>,
) {
    init {
        require(revisionNumber > 0) { "Capture draft revision must be positive" }
        require(sessionVersion == captureDraftSessionVersion(revisionNumber, pages.size)) {
            "Capture draft session version does not match its revision and asset count"
        }
        requireCanonicalFingerprint(assetOrderFingerprint, "Capture draft asset-order fingerprint")
        require(pages.size in 1..MAX_CAPTURE_DRAFT_SOURCE_ASSETS) {
            "Capture draft source bundle is outside the supported range"
        }
        require(pages.map(CaptureDraftCanonicalAssetPage::pageIndex) == pages.indices.toList()) {
            "Capture draft source pages must be strictly ordered and contiguous"
        }
        require(pages.map { page -> page.asset.assetId }.distinct().size == pages.size) {
            "Capture draft source assets must be unique"
        }
        require(
            assetOrderFingerprint ==
                captureAssetOrderFingerprint(
                    draftSessionId = draftSessionId,
                    pages = pages,
                ),
        ) {
            "Capture draft asset-order fingerprint does not match its pages"
        }
    }
}

/**
 * Compare-and-merge command for exactly two draft sessions that the batch coordinator already
 * proved are adjacent. Capture owns the temporary draft/asset mutation; batch must only bind the
 * returned receipt and remap the following page.
 */
internal data class MergeAdjacentCaptureDraftsCommand(
    val batchJobId: String,
    val batchPageIndex: Int,
    val primaryDraftSessionId: CaptureDraftSessionId,
    val followingDraftSessionId: CaptureDraftSessionId,
    val expectedPrimarySessionVersion: Long,
    val expectedFollowingSessionVersion: Long,
    val expectedPrimaryAssetOrderFingerprint: String,
    val expectedFollowingAssetOrderFingerprint: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        requireCaptureSessionIdentifier(batchJobId, "Capture draft merge batch job id")
        require(batchPageIndex >= 0) { "Capture draft merge batch page index must not be negative" }
        require(primaryDraftSessionId != followingDraftSessionId) {
            "Adjacent capture drafts must be distinct"
        }
        require(expectedPrimarySessionVersion > 0 && expectedFollowingSessionVersion > 0) {
            "Expected capture draft session versions must be positive"
        }
        requireCanonicalFingerprint(
            expectedPrimaryAssetOrderFingerprint,
            "Expected primary asset-order fingerprint",
        )
        requireCanonicalFingerprint(
            expectedFollowingAssetOrderFingerprint,
            "Expected following asset-order fingerprint",
        )
        require(occurredAtEpochMillis >= 0) { "Capture draft merge time must not be negative" }
    }

    val canonicalFingerprint: String
        get() =
            CanonicalSha256("capture-draft-merge-request-v1")
                .field("batchJobId", batchJobId)
                .field("batchPageIndex", batchPageIndex)
                .field("primaryDraftSessionId", primaryDraftSessionId.value)
                .field("followingDraftSessionId", followingDraftSessionId.value)
                .field("expectedPrimarySessionVersion", expectedPrimarySessionVersion)
                .field(
                    "expectedPrimaryAssetOrderFingerprint",
                    expectedPrimaryAssetOrderFingerprint,
                )
                .field("expectedFollowingSessionVersion", expectedFollowingSessionVersion)
                .field(
                    "expectedFollowingAssetOrderFingerprint",
                    expectedFollowingAssetOrderFingerprint,
                )
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .finish()
}

internal data class ReadCaptureDraftMergeReceiptQuery(
    val batchJobId: String,
    val batchPageIndex: Int,
    val primaryDraftSessionId: CaptureDraftSessionId,
    val followingDraftSessionId: CaptureDraftSessionId,
) {
    init {
        requireCaptureSessionIdentifier(batchJobId, "Capture draft merge batch job id")
        require(batchPageIndex >= 0) { "Capture draft merge batch page index must not be negative" }
        require(primaryDraftSessionId != followingDraftSessionId) {
            "Capture draft merge receipt query requires distinct drafts"
        }
    }
}

/**
 * Append-only hand-off value for a completed temporary merge.
 *
 * This receipt remains readable after either draft is revised, finalized, or removed. It is the
 * only proof batch may use before it remaps the following page to [mergedDraftSessionId].
 */
internal data class CaptureDraftMergeReceipt(
    val receiptReference: String,
    val batchJobId: String,
    val batchPageIndex: Int,
    val primaryDraftSessionId: CaptureDraftSessionId,
    val followingDraftSessionId: CaptureDraftSessionId,
    val mergedDraftSessionId: CaptureDraftSessionId,
    val assetOrderFingerprint: String,
    val sessionVersion: Long,
    val sourceAssetCount: Int,
    val mergedAtEpochMillis: Long,
    val receiptFingerprint: String,
) {
    init {
        requireCanonicalFingerprint(receiptReference, "Capture draft merge receipt reference")
        requireCaptureSessionIdentifier(batchJobId, "Capture draft merge batch job id")
        require(batchPageIndex >= 0) { "Capture draft merge batch page index must not be negative" }
        require(primaryDraftSessionId != followingDraftSessionId) {
            "Merged capture drafts must be distinct"
        }
        require(mergedDraftSessionId == primaryDraftSessionId) {
            "The merged capture draft must retain the primary draft identity"
        }
        requireCanonicalFingerprint(assetOrderFingerprint, "Merged asset-order fingerprint")
        require(sessionVersion > 0) { "Merged capture draft session version must be positive" }
        require(sourceAssetCount in 2..MAX_CAPTURE_DRAFT_SOURCE_ASSETS) {
            "Merged capture draft source count is outside the supported range"
        }
        require(mergedAtEpochMillis >= 0) { "Capture draft merge time must not be negative" }
        require(
            receiptReference ==
                CaptureMergeSessionReceiptReference.forBatchBoundary(
                    jobId = batchJobId,
                    pageIndex = batchPageIndex,
                    primaryDraftSessionId = primaryDraftSessionId.value,
                    followingDraftSessionId = followingDraftSessionId.value,
                ),
        ) {
            "Capture draft merge receipt reference does not match its batch boundary"
        }
        requireCanonicalFingerprint(receiptFingerprint, "Capture draft merge receipt fingerprint")
        require(
            receiptFingerprint ==
                captureDraftMergeReceiptFingerprint(
                    receiptReference = receiptReference,
                    batchJobId = batchJobId,
                    batchPageIndex = batchPageIndex,
                    primaryDraftSessionId = primaryDraftSessionId,
                    followingDraftSessionId = followingDraftSessionId,
                    assetOrderFingerprint = assetOrderFingerprint,
                    sessionVersion = sessionVersion,
                    sourceAssetCount = sourceAssetCount,
                    mergedAtEpochMillis = mergedAtEpochMillis,
                ),
        ) {
            "Capture draft merge receipt fingerprint does not match its immutable payload"
        }
    }
}

/**
 * Capture-owned temporary session boundary.
 *
 * This capability never confirms a mistake, changes learner mastery, or reads the curriculum
 * knowledge base.
 */
internal interface CaptureDraftSessionPort {
    suspend fun importDraft(
        command: ImportCaptureDraftSessionCommand,
    ): CaptureDraftSessionId

    suspend fun readDraftSummary(
        draftSessionId: CaptureDraftSessionId,
    ): CaptureDraftSummary?

    suspend fun readCanonicalSourceAssets(
        query: ReadCaptureDraftCanonicalAssetsQuery,
    ): CaptureDraftCanonicalAssetBundle?

    suspend fun mergeAdjacentDrafts(
        command: MergeAdjacentCaptureDraftsCommand,
    ): CaptureDraftMergeReceipt

    suspend fun readMergeReceipt(
        query: ReadCaptureDraftMergeReceiptQuery,
    ): CaptureDraftMergeReceipt?
}

internal fun captureDraftSessionVersion(
    revisionNumber: Int,
    sourceAssetCount: Int,
): Long {
    require(revisionNumber > 0) { "Capture draft revision must be positive" }
    require(sourceAssetCount in 1..MAX_CAPTURE_DRAFT_SOURCE_ASSETS) {
        "Capture draft source count is outside the supported range"
    }
    return (revisionNumber.toLong() shl SESSION_VERSION_ASSET_BITS) or
        sourceAssetCount.toLong()
}

internal fun captureAssetOrderFingerprint(
    draftSessionId: CaptureDraftSessionId,
    pages: List<CaptureDraftCanonicalAssetPage>,
): String {
    require(pages.size in 1..MAX_CAPTURE_DRAFT_SOURCE_ASSETS)
    return CanonicalSha256("capture-draft-asset-order-v1")
        .field("draftSessionId", draftSessionId.value)
        .field("assetCount", pages.size)
        .apply {
            pages.forEach { page ->
                field("pageIndex", page.pageIndex)
                field("assetId", page.asset.assetId)
                field("contentSha256", page.asset.contentSha256)
            }
        }
        .finish()
}

internal fun captureDraftMergeReceiptFingerprint(
    receiptReference: String,
    batchJobId: String,
    batchPageIndex: Int,
    primaryDraftSessionId: CaptureDraftSessionId,
    followingDraftSessionId: CaptureDraftSessionId,
    assetOrderFingerprint: String,
    sessionVersion: Long,
    sourceAssetCount: Int,
    mergedAtEpochMillis: Long,
): String =
    CanonicalSha256("capture-draft-merge-receipt-v1")
        .field("receiptReference", receiptReference)
        .field("batchJobId", batchJobId)
        .field("batchPageIndex", batchPageIndex)
        .field("primaryDraftSessionId", primaryDraftSessionId.value)
        .field("followingDraftSessionId", followingDraftSessionId.value)
        .field("assetOrderFingerprint", assetOrderFingerprint)
        .field("sessionVersion", sessionVersion)
        .field("sourceAssetCount", sourceAssetCount)
        .field("mergedAtEpochMillis", mergedAtEpochMillis)
        .finish()

internal fun captureMergeSessionReceiptReference(
    command: MergeAdjacentCaptureDraftsCommand,
): String =
    CaptureMergeSessionReceiptReference.forBatchBoundary(
        jobId = command.batchJobId,
        pageIndex = command.batchPageIndex,
        primaryDraftSessionId = command.primaryDraftSessionId.value,
        followingDraftSessionId = command.followingDraftSessionId.value,
    )

private fun requireCaptureSessionIdentifier(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value of at most 256 characters"
    }
}

private fun requireCanonicalFingerprint(
    value: String,
    label: String,
) {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "$label must be a lowercase SHA-256" }
}
