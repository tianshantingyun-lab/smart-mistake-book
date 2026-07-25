package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_APPROVAL_TTL_MILLIS
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot

internal data class CaptureAcquisitionEgressIntent(
    val intentId: String,
    val purpose: CaptureAcquisitionPurpose,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val createdAtEpochMillis: Long,
    val authorizesInitialTutorPlan: Boolean,
) {
    init {
        require(intentId.isNotBlank())
        require(providerId.isNotBlank())
        require(modelId.isNotBlank())
        require(providerConfigurationVersion.isNotBlank())
        require(createdAtEpochMillis >= 0)
    }

    fun bindReturnedSource(sourceUri: String): CaptureSourceEgressIntent {
        require(sourceUri.isNotBlank())
        return CaptureSourceEgressIntent(
            intentId = intentId,
            purpose = purpose,
            providerId = providerId,
            modelId = modelId,
            providerConfigurationVersion = providerConfigurationVersion,
            createdAtEpochMillis = createdAtEpochMillis,
            authorizesInitialTutorPlan = authorizesInitialTutorPlan,
            sourceUri = sourceUri,
        )
    }
}

internal data class CaptureSourceEgressIntent(
    val intentId: String,
    val purpose: CaptureAcquisitionPurpose,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val createdAtEpochMillis: Long,
    val authorizesInitialTutorPlan: Boolean,
    val sourceUri: String,
) {
    fun matchesSource(
        purpose: CaptureAcquisitionPurpose,
        sourceUri: String,
    ): Boolean = this.purpose == purpose && this.sourceUri == sourceUri

    fun bindDraft(
        draftId: String,
        sourcePages: List<CaptureSourcePage>,
    ): CaptureDraftEgressIntent {
        require(draftId.isNotBlank())
        require(sourcePages.isNotEmpty())
        return CaptureDraftEgressIntent(
            intentId = intentId,
            providerId = providerId,
            modelId = modelId,
            providerConfigurationVersion = providerConfigurationVersion,
            createdAtEpochMillis = createdAtEpochMillis,
            authorizesInitialTutorPlan = authorizesInitialTutorPlan,
            draftId = draftId,
            assets = sourcePages.map(CaptureSourcePage::toEgressAssetIdentity),
        )
    }
}

internal data class CaptureDraftEgressIntent(
    val intentId: String,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val createdAtEpochMillis: Long,
    val authorizesInitialTutorPlan: Boolean,
    val draftId: String,
    val assets: List<CaptureEgressAssetIdentity>,
) {
    fun matches(
        provider: ProviderCapabilitySnapshot,
        draftId: String,
        sourcePages: List<CaptureSourcePage>,
        nowEpochMillis: Long,
    ): Boolean = provider.requiresCaptureEgressApproval() &&
        provider.providerId == providerId &&
        provider.modelId == modelId &&
        provider.providerConfigurationVersion == providerConfigurationVersion &&
        this.draftId == draftId &&
        assets == sourcePages.map(CaptureSourcePage::toEgressAssetIdentity) &&
        nowEpochMillis >= createdAtEpochMillis &&
        nowEpochMillis - createdAtEpochMillis <= MODEL_EGRESS_APPROVAL_TTL_MILLIS
}

internal data class CaptureEgressAssetIdentity(
    val assetId: String,
    val sha256: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
)

/**
 * Holds the informed acquisition intent only in this composition's process. A new instance after
 * recreation is deliberately empty, so restored work must use the visible one-tap continuation.
 */
internal class CaptureInformedEgressIntentSession {
    private var acquisitionIntent: CaptureAcquisitionEgressIntent? = null
    private var sourceIntent: CaptureSourceEgressIntent? = null

    fun begin(
        provider: ProviderCapabilitySnapshot?,
        purpose: CaptureAcquisitionPurpose,
        intentId: String,
        nowEpochMillis: Long,
        authorizesInitialTutorPlan: Boolean = false,
    ) {
        sourceIntent = null
        acquisitionIntent = provider
            ?.takeIf(ProviderCapabilitySnapshot::requiresCaptureEgressApproval)
            ?.let {
                CaptureAcquisitionEgressIntent(
                    intentId = intentId,
                    purpose = purpose,
                    providerId = it.providerId,
                    modelId = it.modelId,
                    providerConfigurationVersion = it.providerConfigurationVersion,
                    createdAtEpochMillis = nowEpochMillis,
                    authorizesInitialTutorPlan = authorizesInitialTutorPlan &&
                        it.supports(ModelTaskKind.TUTOR_PLAN),
                )
            }
    }

    fun bindReturnedSource(
        purpose: CaptureAcquisitionPurpose,
        sourceUri: String,
    ): CaptureSourceEgressIntent? {
        val exactIntent = acquisitionIntent
            ?.takeIf { it.purpose == purpose }
            ?.bindReturnedSource(sourceUri)
        acquisitionIntent = null
        sourceIntent = exactIntent
        return exactIntent
    }

    fun sourceFor(
        purpose: CaptureAcquisitionPurpose,
        sourceUri: String,
    ): CaptureSourceEgressIntent? = sourceIntent?.takeIf {
        it.matchesSource(purpose = purpose, sourceUri = sourceUri)
    }

    fun complete(intentId: String) {
        if (sourceIntent?.intentId == intentId) sourceIntent = null
    }

    fun cancelAcquisition() {
        acquisitionIntent = null
    }
}

internal fun captureInitialEgressDisclosure(
    provider: ProviderCapabilitySnapshot?,
    entryOrigin: CaptureEntryOrigin = CaptureEntryOrigin.LIBRARY,
): String = when {
    entryOrigin == CaptureEntryOrigin.TUTOR &&
        provider?.requiresCaptureEgressApproval() == true &&
        provider.supports(ModelTaskKind.TUTOR_PLAN) ->
        "本次题图、整理后的题目和与本题相关的学习记录会交给${provider.providerDisplayName}，用于开始讲解；不会发送其他题目。"
    entryOrigin == CaptureEntryOrigin.TUTOR && provider != null &&
        provider.supports(ModelTaskKind.TUTOR_PLAN) ->
        "本次题图和与本题相关的学习记录只在本机整理并开始讲解。"
    provider?.requiresCaptureEgressApproval() == true ->
        "仅把本次选中的题图交给${provider.providerDisplayName}整理；不会发送其他题目或学习记录。"
    provider != null -> "本次选中的题图只在本机整理。"
    else -> "仅把本次选中的题图交给当前配置的大模型整理；不会发送其他题目或学习记录。"
}

private fun CaptureSourcePage.toEgressAssetIdentity(): CaptureEgressAssetIdentity =
    CaptureEgressAssetIdentity(
        assetId = sourceAssetId,
        sha256 = sourceAssetSha256,
        byteSize = byteSize,
        width = width,
        height = height,
    )
