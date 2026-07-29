package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Audited, short-lived consent for organizing one exact captured problem.
 *
 * This is deliberately separate from capture parsing and tutor authorization. It contains no URI,
 * prompt, API credential, or learning history, and cannot authorize any task except v3 problem
 * organization for the exact image assets listed here.
 */
@Serializable
data class ProblemOrganizationAuthorizationGrant(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val authorizationPolicyVersion: String = CURRENT_AUTHORIZATION_POLICY_VERSION,
    val authorizationId: String,
    val sourceDraftId: String,
    val purpose: ModelEgressPurpose = ModelEgressPurpose.CLASSIFICATION,
    val authorizedTaskKind: ModelTaskKind = ModelTaskKind.PROBLEM_CLASSIFY,
    val requestSchemaVersion: Int = ModelEgressManifest.CURRENT_SCHEMA_VERSION,
    val promptPolicyVersion: String = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val approvedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val assets: List<ModelEgressAssetGrant>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported problem organization authorization schema"
        }
        require(authorizationPolicyVersion == CURRENT_AUTHORIZATION_POLICY_VERSION) {
            "Unsupported problem organization authorization policy"
        }
        authorizationId.requireSafeModelText(
            "Problem organization authorization id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        sourceDraftId.requireSafeModelText(
            "Problem organization authorization draft",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(purpose == ModelEgressPurpose.CLASSIFICATION) {
            "Problem organization authorization has an invalid purpose"
        }
        require(authorizedTaskKind == ModelTaskKind.PROBLEM_CLASSIFY) {
            "Problem organization authorization has an invalid task"
        }
        require(requestSchemaVersion == ModelEgressManifest.CURRENT_SCHEMA_VERSION) {
            "Problem organization authorization has an invalid request schema"
        }
        require(promptPolicyVersion == ModelPromptPolicyVersions.PROBLEM_ORGANIZATION) {
            "Problem organization authorization has an invalid prompt policy"
        }
        providerId.requireSafeModelText(
            "Problem organization provider id",
            MAX_PROVIDER_ID_CHARS,
            false,
        )
        modelId.requireSafeModelText(
            "Problem organization model id",
            MAX_MODEL_VERSION_CHARS,
            false,
        )
        providerConfigurationVersion.requireSafeModelText(
            "Problem organization provider configuration version",
            MAX_MODEL_VERSION_CHARS,
            false,
        )
        require(approvedAtEpochMillis >= 0) {
            "Problem organization approval time must not be negative"
        }
        require(expiresAtEpochMillis >= approvedAtEpochMillis) {
            "Problem organization authorization expires before approval"
        }
        require(
            expiresAtEpochMillis - approvedAtEpochMillis in
                1L..MODEL_EGRESS_APPROVAL_TTL_MILLIS,
        ) {
            "Problem organization authorization lifetime is outside policy"
        }
        require(assets.isNotEmpty() && assets.size <= MAX_CAPTURE_SOURCE_ASSETS) {
            "Problem organization authorization requires an exact image scope"
        }
        require(assets.map(ModelEgressAssetGrant::assetId).distinct().size == assets.size) {
            "Problem organization authorization asset ids must be unique"
        }
    }

    fun matchesCurrent(
        provider: ProviderCapabilitySnapshot,
        nowEpochMillis: Long,
    ): Boolean = nowEpochMillis in approvedAtEpochMillis until expiresAtEpochMillis &&
        provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        provider.supportsImageInput &&
        provider.supports(ModelTaskKind.PROBLEM_CLASSIFY) &&
        provider.providerId == providerId &&
        provider.modelId == modelId &&
        provider.providerConfigurationVersion == providerConfigurationVersion

    fun toEgressManifest(requestSubjectId: String): ModelEgressManifest {
        requestSubjectId.requireSafeModelText(
            "Problem organization request subject",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        val disclosure = ModelEgressManifest.problemOrganizationV3Disclosure(
            includesSelectedRegion = assets.any { it.selectedRegion != null },
        )
        return ModelEgressManifest(
            schemaVersion = requestSchemaVersion,
            authorizationId = authorizationId,
            subjectId = requestSubjectId,
            purpose = purpose,
            authorizedTaskKinds = setOf(authorizedTaskKind),
            providerId = providerId,
            modelId = modelId,
            providerConfigurationVersion = providerConfigurationVersion,
            promptPolicyVersion = promptPolicyVersion,
            approvedAtEpochMillis = approvedAtEpochMillis,
            assets = assets,
            disclosedData = disclosure,
            prohibitedData =
                ModelEgressManifest.dataClassUniverseForSchema(requestSchemaVersion) - disclosure,
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val CURRENT_AUTHORIZATION_POLICY_VERSION =
            "problem-organization-authorization-policy-v1"
    }
}

object ProblemOrganizationAuthorizationGrantCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(grant: ProblemOrganizationAuthorizationGrant): String = json.encodeToString(grant)

    fun decode(snapshot: String): ProblemOrganizationAuthorizationGrant =
        json.decodeFromString(snapshot)

    fun decodeOrNull(snapshot: String?): ProblemOrganizationAuthorizationGrant? =
        snapshot?.let { runCatching { decode(it) }.getOrNull() }
}
