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
    /**
     * 原子认领早于 [createdBeforeEpochMillis] 且此刻仍无任何引用的规范资产行：
     * 判定与删除在同一写事务内完成，返回被删的行供调用方清理其磁盘文件。
     *
     * 两点都是必需的：同事务让"判定之后才提交的新引用"不会误删；宽限期让
     * "已落盘但引用尚未建立"的在途资产（大堂发送路径里登记资产行到写入消息引用之间）
     * 免于回收。
     */
    suspend fun claimUnreferencedCanonicalAssets(
        createdBeforeEpochMillis: Long,
    ): List<CanonicalSourceAssetRecord>

    suspend fun insertOrphanCanonicalAssetForTest(asset: CanonicalSourceAssetRecord)

    /**
     * 登记一个已落盘（vault 校验通过）的规范资产行，供消息附图等新引用形态使用；
     * 引用建立后由孤儿清理按引用判定保留。
     */
    suspend fun registerCanonicalSourceAsset(asset: CanonicalSourceAssetRecord) {
        throw UnsupportedOperationException("This database does not register canonical assets")
    }
}
