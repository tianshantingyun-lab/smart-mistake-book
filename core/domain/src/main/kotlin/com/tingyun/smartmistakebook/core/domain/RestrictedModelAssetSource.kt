package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import java.io.Closeable
import java.io.InputStream

/** One verified, bounded stream opened only for an externally authorized model execution. */
class RestrictedModelAsset(
    val assetId: String,
    val sha256: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val stream: InputStream,
) : Closeable {
    override fun close() = stream.close()
}

/**
 * The only boundary through which an external model adapter may read canonical image bytes.
 * Implementations must verify the execution permit and the immutable asset fingerprint again.
 */
interface RestrictedModelAssetSource {
    suspend fun open(
        execution: ModelGatewayExecution,
        assetId: String,
    ): RestrictedModelAsset
}
