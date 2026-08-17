package com.tingyun.smartmistakebook.core.model

enum class ModelTaskAssetPolicy {
    FORBIDDEN,
    REQUIRED,
    CONTEXTUAL,
}

data class ModelTaskContract(
    val kind: ModelTaskKind,
    val egressPurpose: ModelEgressPurpose,
    val promptPolicyVersion: String,
    val assetPolicy: ModelTaskAssetPolicy,
    val requiredDisclosures: Set<ModelEgressDataClass>,
    val prohibitedDisclosures: Set<ModelEgressDataClass>,
    val maxProviderDispatches: Int = ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES,
) {
    init {
        require(kind != ModelTaskKind.TUTOR_LOBBY || assetPolicy == ModelTaskAssetPolicy.FORBIDDEN) {
            "Tutor lobby is a text-only contract"
        }
        require(kind == ModelTaskKind.TUTOR_VISUAL_GENERATE ||
            kind == ModelTaskKind.TUTOR_VISUAL_REVIEW ||
            assetPolicy != ModelTaskAssetPolicy.CONTEXTUAL
        ) {
            "Only visual tutor tasks may conditionally authorize assets"
        }
        require(promptPolicyVersion == ModelPromptPolicyVersions.currentFor(kind)) {
            "Contract prompt policy is not the current policy for ${kind.name}"
        }
        require(maxProviderDispatches == ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES) {
            "Every implemented contract shares the dispatch budget"
        }
        require(requiredDisclosures.isNotEmpty()) { "Contract disclosure set must not be empty" }
        require(requiredDisclosures.intersect(prohibitedDisclosures).isEmpty()) {
            "Contract disclosures cannot be both required and prohibited"
        }
        require(
            ModelEgressDataClass.FULL_LEARNING_HISTORY !in requiredDisclosures &&
                ModelEgressDataClass.API_CREDENTIALS !in requiredDisclosures,
        ) { "No contract may require full history or API credentials" }
    }
}

object ModelTaskContractRegistry {
    private val contracts: Map<ModelTaskKind, ModelTaskContract> = listOf(
        ModelTaskContract(
            kind = ModelTaskKind.CAPTURE_ASSESS,
            egressPurpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
            promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
            assetPolicy = ModelTaskAssetPolicy.REQUIRED,
            requiredDisclosures = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
            prohibitedDisclosures = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
        ),
        ModelTaskContract(
            kind = ModelTaskKind.CAPTURE_PARSE,
            egressPurpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
            promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
            assetPolicy = ModelTaskAssetPolicy.REQUIRED,
            requiredDisclosures = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
            prohibitedDisclosures = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
        ),
        ModelTaskContract(
            kind = ModelTaskKind.PROBLEM_CLASSIFY,
            egressPurpose = ModelEgressPurpose.CLASSIFICATION,
            promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
            assetPolicy = ModelTaskAssetPolicy.FORBIDDEN,
            requiredDisclosures = ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
            prohibitedDisclosures = ModelEgressManifest.PROBLEM_ORGANIZATION_PROHIBITED_DATA,
        ),
        ModelTaskContract(
            kind = ModelTaskKind.TUTOR_LOBBY,
            egressPurpose = ModelEgressPurpose.TUTORING,
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
            assetPolicy = ModelTaskAssetPolicy.FORBIDDEN,
            requiredDisclosures = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
            prohibitedDisclosures = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
        ),
        ModelTaskContract(
            kind = ModelTaskKind.TUTOR_PLAN,
            egressPurpose = ModelEgressPurpose.TUTORING,
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
            assetPolicy = ModelTaskAssetPolicy.FORBIDDEN,
            requiredDisclosures = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
            prohibitedDisclosures = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA,
        ),
        ModelTaskContract(
            kind = ModelTaskKind.TUTOR_RESPOND,
            egressPurpose = ModelEgressPurpose.TUTORING,
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_RESPOND,
            assetPolicy = ModelTaskAssetPolicy.FORBIDDEN,
            requiredDisclosures = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
            prohibitedDisclosures = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
        ),
        ModelTaskContract(
            kind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            egressPurpose = ModelEgressPurpose.TUTORING,
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_VISUAL_GENERATE,
            assetPolicy = ModelTaskAssetPolicy.CONTEXTUAL,
            requiredDisclosures = ModelEgressManifest.tutorVisualGenerateDisclosure(false),
            prohibitedDisclosures =
                ModelEgressDataClass.entries.toSet() -
                    ModelEgressManifest.tutorVisualGenerateDisclosure(false),
        ),
        ModelTaskContract(
            kind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            egressPurpose = ModelEgressPurpose.TUTORING,
            promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_VISUAL_REVIEW,
            assetPolicy = ModelTaskAssetPolicy.CONTEXTUAL,
            requiredDisclosures = ModelEgressManifest.tutorVisualReviewDisclosure(false),
            prohibitedDisclosures =
                ModelEgressDataClass.entries.toSet() -
                    ModelEgressManifest.tutorVisualReviewDisclosure(false),
        ),
    ).associateBy(ModelTaskContract::kind)

    fun require(kind: ModelTaskKind): ModelTaskContract =
        contracts[kind] ?: error("No implemented model task contract for ${kind.name}")

    fun all(): List<ModelTaskContract> = contracts.values.sortedBy(ModelTaskContract::kind)
}
