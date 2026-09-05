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
        val record = database.readCanonicalSourceAsset(assetId)
            ?: throw SecurityException("Approved model asset is unavailable")
        when (val permit = execution.permit) {
            is ModelExecutionPermit.External -> {
                val manifest = permit.manifest
                check(execution.request.egressManifest == manifest) {
                    "External model execution does not match its persisted egress manifest"
                }
                val grant = manifest.assets.singleOrNull { it.assetId == assetId }
                    ?: throw SecurityException("Asset is outside the approved egress scope")
                require(grant.selectedRegion == null) {
                    "Region-scoped image egress remains blocked until a trusted crop stream is available"
                }
                check(
                    record.contentSha256 == grant.sha256 &&
                        record.byteSize == grant.byteSize &&
                        record.width == grant.width &&
                        record.height == grant.height,
                ) { "Approved model asset changed after authorization" }
            }
            ModelExecutionPermit.ProviderConsented -> {
                // Global-consent read: the request carries consent and the capture
                // pipeline authorized it; the asset must be one this request references
                // and be a whole-image (no region) canonical asset. The record is the
                // source of truth (no manifest grant to compare).
                check(execution.request.captureEgressConsentGranted) {
                    "Consented model asset access requires the consent flag"
                }
                check(execution.request.input.isCapturePipelineKind) {
                    "Consented asset access is limited to the capture pipeline"
                }
            }
            ModelExecutionPermit.LocalOnly ->
                throw SecurityException("External model asset access requires an egress permit")
        }
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
