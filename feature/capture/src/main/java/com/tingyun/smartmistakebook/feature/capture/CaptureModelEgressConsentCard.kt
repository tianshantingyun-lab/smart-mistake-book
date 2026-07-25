package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.isModelEgressApprovalFresh
import com.tingyun.smartmistakebook.core.model.requiresEgressAuthorizationRenewal
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val CAPTURE_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.CAPTURE_DOCUMENT

internal fun ProviderCapabilitySnapshot.requiresCaptureEgressApproval(): Boolean =
    executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER && supportsImageInput

/**
 * A capture approval is intentionally process-local once execution can have reached a provider.
 * Persisted manifests remain useful evidence, but they never authorize a restored UI to dispatch
 * the same request again without an explicit student action.
 */
internal class CaptureExternalExecutionLaunchGuard {
    private val launchedAuthorizations = mutableSetOf<String>()

    fun claim(
        request: ModelTaskRequest,
        snapshot: ModelTaskSnapshot?,
        provider: ProviderCapabilitySnapshot,
        manifest: ModelEgressManifest?,
        activeAuthorizationId: String?,
        nowEpochMillis: Long,
    ): Boolean {
        if (!provider.requiresCaptureEgressApproval()) return true
        if (
            snapshot?.status in setOf(
                ModelTaskStatus.SUCCEEDED,
                ModelTaskStatus.PERMANENT_FAILURE,
                ModelTaskStatus.CANCELLED,
                ModelTaskStatus.RETRYABLE_FAILURE,
                ModelTaskStatus.RUNNING,
                ModelTaskStatus.STREAMING,
            ) || snapshot?.attemptCount?.let { it > 0 } == true
        ) {
            return false
        }
        val exactManifest = manifest ?: return false
        val activeAuthorization = activeAuthorizationId ?: return false
        if (
            exactManifest.authorizationId != activeAuthorization ||
            request.egressManifest != exactManifest ||
            !exactManifest.isModelEgressApprovalFresh(nowEpochMillis)
        ) {
            return false
        }
        val launchKey = "${request.requestId}:$activeAuthorization"
        return launchedAuthorizations.add(launchKey)
    }
}

internal fun captureEgressApprovalMustBeRenewed(
    assessmentSnapshot: ModelTaskSnapshot?,
    parseSnapshot: ModelTaskSnapshot?,
): Boolean = (parseSnapshot ?: assessmentSnapshot)
    ?.failure
    ?.code
    ?.requiresEgressAuthorizationRenewal() == true

internal fun ModelTaskSnapshot.matchesCaptureProvider(
    provider: ProviderCapabilitySnapshot,
): Boolean {
    val approvedProvider = request.egressManifest
    return if (approvedProvider != null) {
        approvedProvider.providerId == provider.providerId &&
            approvedProvider.modelId == provider.modelId &&
            approvedProvider.providerConfigurationVersion == provider.providerConfigurationVersion
    } else {
        this.provider?.let { executedBy ->
            executedBy.providerId == provider.providerId &&
                executedBy.modelId == provider.modelId &&
                executedBy.providerConfigurationVersion == provider.providerConfigurationVersion
        } == true
    }
}

/**
 * A settings failure needs another send-scope approval only after the provider configuration
 * actually changes. Egress failures always renew approval and never route through settings.
 */
internal fun ModelTaskSnapshot.requiresFreshCaptureApproval(
    provider: ProviderCapabilitySnapshot,
): Boolean {
    val failureCode = failure?.code ?: return false
    return !matchesCaptureProvider(provider) ||
        request.egressManifest?.promptPolicyVersion != CAPTURE_PROMPT_POLICY_VERSION ||
        failureCode.requiresEgressAuthorizationRenewal() ||
        (failureCode.requiresModelSettings() && !matchesCaptureProvider(provider))
}

internal fun captureRecoveryRequestId(
    failedRequest: ModelTaskRequest,
    provider: ProviderCapabilitySnapshot,
    authorizationId: String,
    approvedAtEpochMillis: Long,
): String {
    val taskName = when (failedRequest.input.kind) {
        ModelTaskKind.CAPTURE_ASSESS -> "assess"
        ModelTaskKind.CAPTURE_PARSE -> "parse"
        else -> error("Only capture tasks can be recovered here")
    }
    val fingerprint = sha256CaptureRecovery(
        buildString {
            append(ModelTaskFingerprint.of(failedRequest))
            append('\n').append(provider.providerId.length).append(':').append(provider.providerId)
            append('\n').append(provider.modelId.length).append(':').append(provider.modelId)
            append('\n')
                .append(provider.providerConfigurationVersion.length)
                .append(':')
                .append(provider.providerConfigurationVersion)
            append('\n').append(CAPTURE_PROMPT_POLICY_VERSION)
            append('\n').append(authorizationId.length).append(':').append(authorizationId)
            append('\n').append(approvedAtEpochMillis)
        },
    ).take(32)
    return "capture-$taskName:approved-recovery:$fingerprint"
}

/** Builds a new authorized envelope while preserving the failed capture input byte-for-byte. */
internal fun rebuildCaptureRequestAfterApproval(
    failedTask: ModelTaskSnapshot,
    provider: ProviderCapabilitySnapshot,
    freshManifest: ModelEgressManifest,
): ModelTaskRequest {
    require(failedTask.request.input.kind in setOf(
        ModelTaskKind.CAPTURE_ASSESS,
        ModelTaskKind.CAPTURE_PARSE,
    )) { "Only capture tasks can be recovered here" }
    require(freshManifest.providerId == provider.providerId)
    require(freshManifest.modelId == provider.modelId)
    require(
        freshManifest.providerConfigurationVersion == provider.providerConfigurationVersion,
    )
    require(
        freshManifest.authorizationId != failedTask.request.egressManifest?.authorizationId,
    ) { "Capture recovery must not reuse the failed authorization" }
    return ModelTaskRequest(
        requestId = captureRecoveryRequestId(
            failedRequest = failedTask.request,
            provider = provider,
            authorizationId = freshManifest.authorizationId,
            approvedAtEpochMillis = freshManifest.approvedAtEpochMillis,
        ),
        input = failedTask.request.input,
        occurredAtEpochMillis = failedTask.request.occurredAtEpochMillis,
        egressManifest = freshManifest,
    )
}

private fun sha256CaptureRecovery(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun buildCaptureEgressManifest(
    authorizationId: String,
    draftId: String,
    provider: ProviderCapabilitySnapshot,
    assetId: String,
    assetSha256: String,
    assetByteSize: Long,
    assetWidth: Int,
    assetHeight: Int,
    approvedAtEpochMillis: Long,
): ModelEgressManifest {
    return buildCaptureEgressManifest(
        authorizationId = authorizationId,
        draftId = draftId,
        provider = provider,
        sourcePages = listOf(
            CaptureSourcePage(
                pageIndex = 0,
                imageUri = "content://authorized-source/$assetId",
                sourceAssetId = assetId,
                sourceAssetSha256 = assetSha256,
                width = assetWidth,
                height = assetHeight,
                byteSize = assetByteSize,
            ),
        ),
        approvedAtEpochMillis = approvedAtEpochMillis,
    )
}

internal fun buildCaptureEgressManifest(
    authorizationId: String,
    draftId: String,
    provider: ProviderCapabilitySnapshot,
    sourcePages: List<CaptureSourcePage>,
    approvedAtEpochMillis: Long,
): ModelEgressManifest {
    require(provider.requiresCaptureEgressApproval()) {
        "Only external image providers require a capture egress manifest"
    }
    require(sourcePages.isNotEmpty()) { "Capture egress requires at least one source page" }
    return ModelEgressManifest(
        authorizationId = authorizationId,
        subjectId = draftId,
        purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
        authorizedTaskKinds = setOf(
            ModelTaskKind.CAPTURE_ASSESS,
            ModelTaskKind.CAPTURE_PARSE,
        ),
        providerId = provider.providerId,
        modelId = provider.modelId,
        providerConfigurationVersion = provider.providerConfigurationVersion,
        promptPolicyVersion = CAPTURE_PROMPT_POLICY_VERSION,
        approvedAtEpochMillis = approvedAtEpochMillis,
        assets = sourcePages.map { page ->
            ModelEgressAssetGrant(
                assetId = page.sourceAssetId,
                sha256 = page.sourceAssetSha256,
                byteSize = page.byteSize,
                width = page.width,
                height = page.height,
            )
        },
        disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
        prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
    )
}

internal fun ModelEgressManifest.matchesCaptureApproval(
    draftId: String,
    provider: ProviderCapabilitySnapshot,
    assetId: String,
    assetSha256: String,
): Boolean = subjectId == draftId &&
    providerId == provider.providerId &&
    modelId == provider.modelId &&
    providerConfigurationVersion == provider.providerConfigurationVersion &&
    promptPolicyVersion == CAPTURE_PROMPT_POLICY_VERSION &&
    assets.singleOrNull()?.let { it.assetId == assetId && it.sha256 == assetSha256 } == true

internal fun ModelEgressManifest.matchesCaptureApproval(
    draftId: String,
    provider: ProviderCapabilitySnapshot,
    sourcePages: List<CaptureSourcePage>,
): Boolean = subjectId == draftId &&
    providerId == provider.providerId &&
    modelId == provider.modelId &&
    providerConfigurationVersion == provider.providerConfigurationVersion &&
    promptPolicyVersion == CAPTURE_PROMPT_POLICY_VERSION &&
    assets.size == sourcePages.size &&
    sourcePages.all { page ->
        assets.singleOrNull { it.assetId == page.sourceAssetId }?.let { grant ->
            grant.sha256 == page.sourceAssetSha256 &&
                grant.byteSize == page.byteSize &&
                grant.width == page.width &&
                grant.height == page.height
        } == true
    }

@Composable
internal fun CaptureModelEgressConsentCard(
    provider: ProviderCapabilitySnapshot,
    approved: Boolean,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    approveActionText: String = "允许这一次",
) {
    val accent = JadeActive
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag("capture_model_egress_consent_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (approved) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.CloudUpload
                },
                contentDescription = null,
                modifier = Modifier.size(23.dp),
                tint = accent,
            )
            Text(
                text = if (approved) {
                    "本次只发送当前题图"
                } else {
                    "把这张题图交给模型整理？"
                },
                modifier = Modifier.padding(start = 8.dp),
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "本次只把当前题图发给${provider.providerDisplayName}，用于读题和整理；不会发送其他题目或学习记录。",
            modifier = Modifier.padding(top = 8.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!approved) {
            PrimaryActionButton(
                text = approveActionText,
                onClick = onApprove,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("capture_model_egress_approve_button"),
            )
        }
    }
}
