package com.tingyun.smartmistakebook.core.domain

import kotlinx.coroutines.flow.Flow

/** A split-import ledger record mirrored into the domain layer. */
data class SplitImportJobSummary(
    val jobId: String,
    val sourceKind: String,
    val pageCount: Int,
    val questionCount: Int,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val sourceUri: String? = null,
    val questions: List<SplitImportQuestionSummary> = emptyList(),
) {
    val readyForReview: Boolean
        get() = status == SplitImportStatus.READY || status == SplitImportStatus.PREPARING
}

data class SplitImportQuestionSummary(
    val jobId: String,
    val questionOrdinal: Int,
    val pageIndex: Int,
    val region: SplitRegion,
    val selected: Boolean,
    val confirmState: String,
    val splitDraftId: String?,
)

/** Page-local normalized region (0..1) for a cut piece. */
data class SplitRegion(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

object SplitImportStatus {
    const val PREPARING = "PREPARING"
    const val READY = "READY"
    const val COMPLETED = "COMPLETED"
    const val ABANDONED = "ABANDONED"
}

object SplitImportConfirmState {
    const val PENDING = "PENDING"
    const val SAVED = "SAVED"
    const val TUTOR_SESSION = "TUTOR_SESSION"
    const val REJECTED = "REJECTED"
}

/**
 * Cut-one-import-into-questions ledger for whole-page / whole-PDF imports.
 * This interface intentionally depends only on the domain layer so the UI
 * never touches concrete database records.
 */
interface SplitImportRepository {
    fun observeActiveImports(): Flow<List<SplitImportJobSummary>>

    suspend fun readImport(jobId: String): SplitImportJobSummary?

    suspend fun updateSelection(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun markConfirmed(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
        occurredAtEpochMillis: Long,
    ): Boolean

    suspend fun complete(jobId: String, occurredAtEpochMillis: Long): Boolean

    suspend fun abandon(jobId: String, occurredAtEpochMillis: Long): Boolean
}