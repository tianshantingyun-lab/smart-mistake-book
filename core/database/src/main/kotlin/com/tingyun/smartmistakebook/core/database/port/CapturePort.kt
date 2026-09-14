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

    /**
     * 登记一个已落盘（vault 校验通过）的规范资产行，供消息附图等新引用形态使用；
     * 引用建立后由孤儿清理按引用判定保留。
     */
    suspend fun registerCanonicalSourceAsset(asset: CanonicalSourceAssetRecord) {
        throw UnsupportedOperationException("This database does not register canonical assets")
    }
}
