package com.tingyun.smartmistakebook.core.data.capture

/**
 * Capture can request only the owner-decided exact-asset-or-unresolved path.
 *
 * Trusted-source and reviewed-alias admission remain fail-closed until a dedicated local authority
 * can reconstruct them from durable trusted state. Capture cannot self-report an authority enum,
 * construct receipt contents, inject a verifier, or infer identity from recognition/model output/URI.
 */
internal sealed interface ProductionStudentProblemIdentityEvidenceRequest {
    data object ExactAssetSelectionOrUnresolved :
        ProductionStudentProblemIdentityEvidenceRequest
}
