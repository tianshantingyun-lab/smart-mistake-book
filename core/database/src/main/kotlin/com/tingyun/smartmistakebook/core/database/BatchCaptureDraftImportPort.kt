package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

enum class CaptureDraftBatchImportDisposition {
    CREATED,
    REUSED_EXACT_CONTENT,
}

/**
 * Capture-owned import request.
 *
 * [batchJobId] is only an opaque deduplication namespace. It is never an authorization token and
 * intentionally does not require a coordinator-owned batch job or page row to exist.
 */
data class ImportOrReuseBatchCaptureDraftCommand(
    val batchJobId: String,
    val batchPageIndex: Int,
    val request: CreateProblemDraftCommand,
) {
    init {
        requireBatchCaptureOpaqueValue(batchJobId, "Batch job id")
        require(batchPageIndex >= 0) { "Batch page index must not be negative" }
    }
}

data class CaptureDraftBatchImportReceipt(
    val receiptReference: String,
    val batchJobId: String,
    val batchPageIndex: Int,
    val requestFingerprint: String,
    val originalDraftId: String,
    val sourceAssetId: String,
    val disposition: CaptureDraftBatchImportDisposition,
    val importedAtEpochMillis: Long,
    val receiptFingerprint: String,
) {
    init {
        requireBatchCaptureSha256(receiptReference, "Receipt reference")
        requireBatchCaptureOpaqueValue(batchJobId, "Batch job id")
        require(batchPageIndex >= 0) { "Batch page index must not be negative" }
        requireBatchCaptureSha256(requestFingerprint, "Request fingerprint")
        requireBatchCaptureOpaqueValue(originalDraftId, "Original draft id")
        requireBatchCaptureOpaqueValue(sourceAssetId, "Source asset id")
        require(importedAtEpochMillis >= 0) { "Batch capture import time must not be negative" }
        requireBatchCaptureSha256(receiptFingerprint, "Receipt fingerprint")
        require(
            receiptReference ==
                batchCaptureDraftReceiptReference(
                    batchJobId = batchJobId,
                    batchPageIndex = batchPageIndex,
                ),
        ) { "Batch capture receipt reference does not match its natural key" }
        require(
            receiptFingerprint ==
                batchCaptureDraftReceiptFingerprint(
                    receiptReference = receiptReference,
                    batchJobId = batchJobId,
                    batchPageIndex = batchPageIndex,
                    requestFingerprint = requestFingerprint,
                    originalDraftId = originalDraftId,
                    sourceAssetId = sourceAssetId,
                    disposition = disposition,
                    importedAtEpochMillis = importedAtEpochMillis,
                ),
        ) { "Batch capture receipt fingerprint does not match its payload" }
    }
}

data class CaptureDraftBatchImportReceiptPage(
    val receipts: List<CaptureDraftBatchImportReceipt>,
    val nextReceiptSequenceExclusive: Long?,
) {
    init {
        require(receipts.map { it.batchJobId }.distinct().size <= 1) {
            "A batch capture receipt page must belong to one job"
        }
        require(receipts.map { it.batchPageIndex }.distinct().size == receipts.size) {
            "A batch capture receipt page cannot repeat a source page"
        }
        require(
            nextReceiptSequenceExclusive == null ||
                nextReceiptSequenceExclusive > 0,
        ) { "Batch capture receipt cursor must be a positive persisted sequence" }
    }
}

/**
 * Narrow capture-owner capability.
 *
 * Authorization happens before this capability is obtained. None of its opaque batch identifiers
 * confer permissions or permit reads outside the exact namespace supplied by the caller.
 */
interface BatchCaptureDraftImportPort {
    suspend fun importOrReuseBatchDraft(
        command: ImportOrReuseBatchCaptureDraftCommand,
    ): CaptureDraftBatchImportReceipt

    suspend fun readBatchCaptureDraftReceipt(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftBatchImportReceipt?

    suspend fun readBatchCaptureDraftReceipts(
        batchJobId: String,
        afterReceiptSequenceExclusive: Long = 0,
        limit: Int = DEFAULT_BATCH_CAPTURE_RECEIPT_PAGE_SIZE,
    ): CaptureDraftBatchImportReceiptPage
}

suspend fun StudyDatabasePort.importOrReuseBatchDraft(
    command: ImportOrReuseBatchCaptureDraftCommand,
): CaptureDraftBatchImportReceipt = batchCaptureDraftImportPort().importOrReuseBatchDraft(command)

suspend fun StudyDatabasePort.readBatchCaptureDraftReceipt(
    batchJobId: String,
    batchPageIndex: Int,
): CaptureDraftBatchImportReceipt? =
    batchCaptureDraftImportPort().readBatchCaptureDraftReceipt(batchJobId, batchPageIndex)

suspend fun StudyDatabasePort.readBatchCaptureDraftReceipts(
    batchJobId: String,
    afterReceiptSequenceExclusive: Long = 0,
    limit: Int = DEFAULT_BATCH_CAPTURE_RECEIPT_PAGE_SIZE,
): CaptureDraftBatchImportReceiptPage =
    batchCaptureDraftImportPort().readBatchCaptureDraftReceipts(
        batchJobId = batchJobId,
        afterReceiptSequenceExclusive = afterReceiptSequenceExclusive,
        limit = limit,
    )

internal fun ImportOrReuseBatchCaptureDraftCommand.canonicalRequestFingerprint(): String {
    val source = request.sourceAsset
    val revision = request.initialRevision
    return CanonicalSha256(BATCH_CAPTURE_REQUEST_FINGERPRINT_DOMAIN)
        .field("batchJobId", batchJobId)
        .field("batchPageIndex", batchPageIndex)
        .field("sourceAssetId", source.sourceAssetId)
        .field("contentSha256", source.contentSha256)
        .field("relativePath", source.relativePath)
        .field("mimeType", source.mimeType)
        .field("byteSize", source.byteSize)
        .field("width", source.width)
        .field("height", source.height)
        .field("sourceType", source.sourceType)
        .field("sourceCreatedAtEpochMillis", source.createdAtEpochMillis)
        .field("draftId", request.draftId)
        .field("origin", request.origin)
        .nullableField("callerRequestFingerprint", request.requestFingerprint)
        .field("revisionDraftId", revision.draftId)
        .field("revisionNumber", revision.revisionNumber)
        .nullableLongField("basisRevisionNumber", revision.basisRevisionNumber?.toLong())
        .nullableField("subject", revision.subject)
        .field("title", revision.title)
        .field("documentFingerprint", revision.documentFingerprint)
        .field("author", revision.author)
        .field("revisionCreatedAtEpochMillis", revision.createdAtEpochMillis)
        .finish()
}

internal fun batchCaptureDraftReceiptReference(
    batchJobId: String,
    batchPageIndex: Int,
): String = CanonicalSha256(BATCH_CAPTURE_RECEIPT_REFERENCE_DOMAIN)
    .field("batchJobId", batchJobId)
    .field("batchPageIndex", batchPageIndex)
    .finish()

internal fun batchCaptureDraftReceiptFingerprint(
    receiptReference: String,
    batchJobId: String,
    batchPageIndex: Int,
    requestFingerprint: String,
    originalDraftId: String,
    sourceAssetId: String,
    disposition: CaptureDraftBatchImportDisposition,
    importedAtEpochMillis: Long,
): String = CanonicalSha256(BATCH_CAPTURE_RECEIPT_FINGERPRINT_DOMAIN)
    .field("receiptReference", receiptReference)
    .field("batchJobId", batchJobId)
    .field("batchPageIndex", batchPageIndex)
    .field("requestFingerprint", requestFingerprint)
    .field("originalDraftId", originalDraftId)
    .field("sourceAssetId", sourceAssetId)
    .field("disposition", disposition.name)
    .field("importedAtEpochMillis", importedAtEpochMillis)
    .finish()

private fun StudyDatabasePort.batchCaptureDraftImportPort(): BatchCaptureDraftImportPort =
    this as? BatchCaptureDraftImportPort
        ?: throw UnsupportedOperationException(
            "Batch capture draft import requires the audited Room capture owner",
        )

private fun requireBatchCaptureOpaqueValue(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_BATCH_CAPTURE_OPAQUE_VALUE_CHARS &&
            value.none(Char::isISOControl),
    ) { "$label must be a bounded opaque value" }
}

private fun requireBatchCaptureSha256(
    value: String,
    label: String,
) {
    require(value.matches(BATCH_CAPTURE_SHA_256)) {
        "$label must be a lowercase SHA-256 value"
    }
}

const val DEFAULT_BATCH_CAPTURE_RECEIPT_PAGE_SIZE = 50
internal const val MAX_BATCH_CAPTURE_RECEIPT_PAGE_SIZE = 100
private const val MAX_BATCH_CAPTURE_OPAQUE_VALUE_CHARS = 256
private const val BATCH_CAPTURE_REQUEST_FINGERPRINT_DOMAIN =
    "batch-capture-draft-import-request-v1"
private const val BATCH_CAPTURE_RECEIPT_REFERENCE_DOMAIN =
    "batch-capture-draft-import-receipt-reference-v1"
private const val BATCH_CAPTURE_RECEIPT_FINGERPRINT_DOMAIN =
    "batch-capture-draft-import-receipt-v1"
private val BATCH_CAPTURE_SHA_256 = Regex("[0-9a-f]{64}")
