package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.entity.BatchCaptureContentBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftBatchImportReceiptEntity

internal class RoomBatchCaptureDraftImportStore(
    private val database: StudyDatabase,
    private val afterBindingInserted: suspend () -> Unit = {},
    private val beforeReceiptInserted: suspend () -> Unit = {},
) {
    suspend fun importOrReuse(
        command: ImportOrReuseBatchCaptureDraftCommand,
    ): CaptureDraftBatchImportReceipt = database.withWriteTransaction {
        DatabaseContractValidator.validateCreateProblemDraft(command.request)
        val dao = database.batchCaptureDraftImportDao()
        val requestFingerprint = command.canonicalRequestFingerprint()
        dao.readReceipt(command.batchJobId, command.batchPageIndex)?.let { existing ->
            if (existing.requestFingerprint != requestFingerprint) {
                batchCaptureImportConflict(command)
            }
            return@withWriteTransaction existing.toRecord()
        }

        val request = command.request
        val requestedBinding =
            BatchCaptureContentBindingEntity(
                batchJobId = command.batchJobId,
                contentSha256 = request.sourceAsset.contentSha256,
                originalDraftId = request.draftId,
                sourceAssetId = request.sourceAsset.sourceAssetId,
                boundAtEpochMillis = request.initialRevision.createdAtEpochMillis,
            )
        val createdBinding = dao.insertContentBinding(requestedBinding) != -1L
        val binding =
            checkNotNull(
                dao.readContentBinding(
                    batchJobId = command.batchJobId,
                    contentSha256 = request.sourceAsset.contentSha256,
                ),
            ) { "Batch capture content binding disappeared inside its transaction" }

        val disposition =
            if (createdBinding) {
                check(binding == requestedBinding) {
                    "Created batch capture binding does not match its request"
                }
                afterBindingInserted()
                val createdDraft = database.problemDraftTransactionDao().create(request)
                if (!createdDraft.created || !createdDraft.draft.matchesBinding(binding)) {
                    batchCaptureImportConflict(command)
                }
                CaptureDraftBatchImportDisposition.CREATED
            } else {
                val winner = database.problemDraftTransactionDao().read(binding.originalDraftId)
                if (
                    binding.contentSha256 != request.sourceAsset.contentSha256 ||
                    winner?.matchesBinding(binding) != true
                ) {
                    batchCaptureImportConflict(command)
                }
                CaptureDraftBatchImportDisposition.REUSED_EXACT_CONTENT
            }

        beforeReceiptInserted()
        val receipt =
            command.toReceiptEntity(
                requestFingerprint = requestFingerprint,
                binding = binding,
                disposition = disposition,
            )
        dao.insertReceipt(receipt)
        val persisted =
            checkNotNull(dao.readReceipt(command.batchJobId, command.batchPageIndex)) {
                "Batch capture import receipt disappeared inside its transaction"
            }
        check(persisted == receipt) {
            "Persisted batch capture import receipt does not match its request"
        }
        persisted.toRecord()
    }

    suspend fun readExact(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftBatchImportReceipt? {
        requireBatchCaptureReadKey(batchJobId, batchPageIndex)
        return database.withReadTransaction {
            database.batchCaptureDraftImportDao()
                .readReceipt(batchJobId, batchPageIndex)
                ?.toRecord()
        }
    }

    suspend fun readPage(
        batchJobId: String,
        afterReceiptSequenceExclusive: Long,
        limit: Int,
    ): CaptureDraftBatchImportReceiptPage {
        requireBatchCaptureReadKey(batchJobId, pageIndex = 0)
        require(afterReceiptSequenceExclusive >= 0) {
            "Batch capture receipt cursor must not be negative"
        }
        require(limit in 1..MAX_BATCH_CAPTURE_RECEIPT_PAGE_SIZE) {
            "Batch capture receipt page size is outside the supported range"
        }
        return database.withReadTransaction {
            val rows =
                database.batchCaptureDraftImportDao().readReceipts(
                    batchJobId = batchJobId,
                    afterReceiptSequenceExclusive = afterReceiptSequenceExclusive,
                    limit = limit + 1,
                )
            val hasMore = rows.size > limit
            val pageRows = rows.take(limit)
            val receipts = pageRows.map { it.receipt.toRecord() }
            CaptureDraftBatchImportReceiptPage(
                receipts = receipts,
                nextReceiptSequenceExclusive =
                    if (hasMore) pageRows.last().receiptSequence else null,
            )
        }
    }
}

private fun ImportOrReuseBatchCaptureDraftCommand.toReceiptEntity(
    requestFingerprint: String,
    binding: BatchCaptureContentBindingEntity,
    disposition: CaptureDraftBatchImportDisposition,
): CaptureDraftBatchImportReceiptEntity {
    val receiptReference =
        batchCaptureDraftReceiptReference(
            batchJobId = batchJobId,
            batchPageIndex = batchPageIndex,
        )
    val importedAtEpochMillis = request.initialRevision.createdAtEpochMillis
    val receiptFingerprint =
        batchCaptureDraftReceiptFingerprint(
            receiptReference = receiptReference,
            batchJobId = batchJobId,
            batchPageIndex = batchPageIndex,
            requestFingerprint = requestFingerprint,
            originalDraftId = binding.originalDraftId,
            sourceAssetId = binding.sourceAssetId,
            disposition = disposition,
            importedAtEpochMillis = importedAtEpochMillis,
        )
    return CaptureDraftBatchImportReceiptEntity(
        receiptReference = receiptReference,
        batchJobId = batchJobId,
        batchPageIndex = batchPageIndex,
        requestFingerprint = requestFingerprint,
        originalDraftId = binding.originalDraftId,
        sourceAssetId = binding.sourceAssetId,
        disposition = disposition.name,
        importedAtEpochMillis = importedAtEpochMillis,
        receiptFingerprint = receiptFingerprint,
    )
}

private fun CaptureDraftBatchImportReceiptEntity.toRecord() =
    CaptureDraftBatchImportReceipt(
        receiptReference = receiptReference,
        batchJobId = batchJobId,
        batchPageIndex = batchPageIndex,
        requestFingerprint = requestFingerprint,
        originalDraftId = originalDraftId,
        sourceAssetId = sourceAssetId,
        disposition = CaptureDraftBatchImportDisposition.valueOf(disposition),
        importedAtEpochMillis = importedAtEpochMillis,
        receiptFingerprint = receiptFingerprint,
    )

private fun ProblemDraftRecord.matchesBinding(
    binding: BatchCaptureContentBindingEntity,
): Boolean =
    draftId == binding.originalDraftId &&
        sourceAsset.sourceAssetId == binding.sourceAssetId &&
        sourceAsset.contentSha256 == binding.contentSha256

private fun requireBatchCaptureReadKey(
    batchJobId: String,
    pageIndex: Int,
) {
    require(
        batchJobId.isNotBlank() &&
            batchJobId == batchJobId.trim() &&
            batchJobId.length <= 256 &&
            batchJobId.none(Char::isISOControl),
    ) { "Batch job id must be a bounded opaque value" }
    require(pageIndex >= 0) { "Batch page index must not be negative" }
}

private fun batchCaptureImportConflict(
    command: ImportOrReuseBatchCaptureDraftCommand,
): Nothing = throw ImmutablePayloadConflictException(
    "capture_draft_batch_import_receipt",
    "${command.batchJobId}:${command.batchPageIndex}",
)
