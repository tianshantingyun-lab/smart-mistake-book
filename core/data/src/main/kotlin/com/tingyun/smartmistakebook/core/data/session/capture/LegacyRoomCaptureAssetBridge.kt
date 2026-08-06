package com.tingyun.smartmistakebook.core.data.session.capture

import com.tingyun.smartmistakebook.core.data.capture.CaptureAssetDescriptor
import com.tingyun.smartmistakebook.core.data.capture.CaptureAssetSessionPort
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import java.io.File

/**
 * Converts temporary legacy session records at the session boundary.
 *
 * The production asset vault remains independent of the legacy database representation.
 */
internal class LegacyRoomCaptureAssetBridge(
    private val assets: CaptureAssetSessionPort,
) {
    fun import(
        localUri: String,
        sourceType: String,
        createdAtEpochMillis: Long,
    ): CanonicalSourceAssetRecord =
        assets.import(
            localUri = localUri,
            sourceType = sourceType,
            createdAtEpochMillis = createdAtEpochMillis,
        ).toLegacyRecord()

    fun crop(
        source: CanonicalSourceAssetRecord,
        region: NormalizedSourceRegion,
        createdAtEpochMillis: Long,
    ): CanonicalSourceAssetRecord =
        assets.crop(
            source = source.toDescriptor(),
            region = region,
            createdAtEpochMillis = createdAtEpochMillis,
        ).toLegacyRecord()

    fun delete(record: CanonicalSourceAssetRecord) {
        assets.delete(record.toDescriptor())
    }

    fun resolve(record: CanonicalSourceAssetRecord): File =
        assets.resolve(record.toDescriptor())

    fun descriptor(record: CanonicalSourceAssetRecord): CaptureAssetDescriptor =
        record.toDescriptor().also(assets::resolve)
}

private fun CaptureAssetDescriptor.toLegacyRecord(): CanonicalSourceAssetRecord =
    CanonicalSourceAssetRecord(
        sourceAssetId = assetId,
        contentSha256 = contentSha256,
        relativePath = relativePath,
        mimeType = mimeType,
        byteSize = byteSize,
        width = width,
        height = height,
        sourceType = sourceType,
        createdAtEpochMillis = createdAtEpochMillis,
    )

private fun CanonicalSourceAssetRecord.toDescriptor(): CaptureAssetDescriptor =
    CaptureAssetDescriptor(
        assetId = sourceAssetId,
        contentSha256 = contentSha256,
        relativePath = relativePath,
        mimeType = mimeType,
        byteSize = byteSize,
        width = width,
        height = height,
        sourceType = sourceType,
        createdAtEpochMillis = createdAtEpochMillis,
    )
