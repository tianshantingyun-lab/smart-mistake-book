package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.WritingLayer
import okio.ByteString.Companion.toByteString
import java.util.Arrays

internal data class BlockMetadata(
    val sourceAssetId: String,
    val region: NormalizedSourceRegion,
    val writingLayer: WritingLayer,
    val confidence: Double?,
)

internal class ApprovedImage(
    val mimeType: String,
    private val bytes: ByteArray,
) : AutoCloseable {
    fun base64(): String = bytes.toByteString().base64()

    override fun close() = Arrays.fill(bytes, 0.toByte())
}

internal data class ApprovedImageReadPlan(
    val assetId: String,
    /**
     * Expected asset byte size. Null under ProviderConsented (unknown until the
     * asset is opened); the read resolves and enforces the budget from the real size.
     */
    val byteSize: Long?,
)
