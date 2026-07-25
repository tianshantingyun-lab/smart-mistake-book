package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAsset
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.ModelExecutionPermit
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution

internal class AndroidRestrictedModelAssetSource(
    context: Context,
    private val database: StudyDatabasePort,
) : RestrictedModelAssetSource {
    private val vault = AndroidCanonicalAssetVault(context.applicationContext)

    override suspend fun open(
        execution: ModelGatewayExecution,
        assetId: String,
    ): RestrictedModelAsset {
        val manifest = (execution.permit as? ModelExecutionPermit.External)?.manifest
            ?: throw SecurityException("External model asset access requires an egress permit")
        check(execution.request.egressManifest == manifest) {
            "External model execution does not match its persisted egress manifest"
        }
        val grant = manifest.assets.singleOrNull { it.assetId == assetId }
            ?: throw SecurityException("Asset is outside the approved egress scope")
        require(grant.selectedRegion == null) {
            "Region-scoped image egress remains blocked until a trusted crop stream is available"
        }
        val record = database.readCanonicalSourceAsset(assetId)
            ?: throw SecurityException("Approved model asset is unavailable")
        check(
            record.contentSha256 == grant.sha256 &&
                record.byteSize == grant.byteSize &&
                record.width == grant.width &&
                record.height == grant.height,
        ) { "Approved model asset changed after authorization" }
        val file = vault.resolve(record)
        return RestrictedModelAsset(
            assetId = record.sourceAssetId,
            sha256 = record.contentSha256,
            mimeType = record.mimeType,
            byteSize = record.byteSize,
            width = record.width,
            height = record.height,
            stream = file.inputStream().buffered(),
        )
    }
}

object RestrictedModelAssetSourceFactory {
    fun create(
        context: Context,
        database: StudyDatabasePort,
    ): RestrictedModelAssetSource = AndroidRestrictedModelAssetSource(context, database)
}
