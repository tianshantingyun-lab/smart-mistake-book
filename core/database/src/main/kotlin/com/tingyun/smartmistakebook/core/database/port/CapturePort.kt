package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.PendingCaptureDraftRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for capture operations.
 */
interface CaptureReadPort {
    fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>>
    suspend fun readPendingCaptureDraft(draftId: String): PendingCaptureDraftRecord?
    suspend fun readCanonicalSourceAsset(sourceAssetId: String): CanonicalSourceAssetRecord?
    suspend fun readUnreferencedCanonicalAssets(): List<CanonicalSourceAssetRecord>
}

/**
 * Write port for capture operations.
 */
interface CaptureWritePort {
    suspend fun deleteUnreferencedCanonicalAssets(): Int
    suspend fun insertOrphanCanonicalAssetForTest(asset: CanonicalSourceAssetRecord)
}
