package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.LegacyModelAssetDocumentReadPort
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal class LegacyCaptureModelDependencyReadAdapter(
    private val boundScope: SessionScope,
    private val legacy: LegacyModelAssetDocumentReadPort,
) : CaptureModelDependencyReadPort {
    override suspend fun read(
        query: CaptureModelDependencyQuery,
    ): CaptureModelDependencySnapshot? {
        query.scope.requireBoundTo(boundScope)
        return legacy.readProblemDraft(query.draftSessionId)?.toModelDependency(boundScope)
    }
}

private fun ProblemDraftRecord.toModelDependency(
    scope: SessionScope,
): CaptureModelDependencySnapshot {
    val fingerprint =
        CanonicalSha256("capture-model-dependency-session-state-v1")
            .field("draftSessionId", draftId)
            .field("revisionNumber", currentRevision.revisionNumber)
            .field("revisionFingerprint", currentRevision.documentFingerprint)
            .field("status", status)
            .field("sourceCount", sourceAssets.size)
            .apply {
                sourceAssets.forEach { source ->
                    field("pageIndex", source.pageIndex)
                    field("assetId", source.sourceAsset.sourceAssetId)
                    field("assetFingerprint", source.sourceAsset.contentSha256)
                }
            }.field("updatedAtEpochMillis", updatedAtEpochMillis)
            .finish()
    return CaptureModelDependencySnapshot(
        scope = scope,
        draftSessionId = draftId,
        revisionNumber = currentRevision.revisionNumber,
        revisionFingerprint = currentRevision.documentFingerprint,
        version = SessionVersion(currentRevision.revisionNumber.toLong(), fingerprint),
        status = status,
        sourceAssets =
            sourceAssets.map { source ->
                val asset = source.sourceAsset
                CaptureModelSourceSessionRef(
                    pageIndex = source.pageIndex,
                    assetId = asset.sourceAssetId,
                    contentSha256 = asset.contentSha256,
                    mimeType = asset.mimeType,
                    byteSize = asset.byteSize,
                    width = asset.width,
                    height = asset.height,
                    createdAtEpochMillis = asset.createdAtEpochMillis,
                )
            },
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
