package com.tingyun.smartmistakebook.core.knowledge.database

/**
 * A build-time-only trust-registry contribution.
 *
 * Debug may contribute a deliberately tiny fixture for install and integrity tests, but that
 * fixture can never qualify for the production cutover or stand in for the formal corpus.
 */
internal enum class BuiltInKnowledgePackPurpose {
    DEBUG_BOUNDARY_FIXTURE,
    FORMAL_HIGH_SCHOOL_RELEASE,
}

internal data class BuildVariantTrustedKnowledgePackDefinition(
    val pack: ReviewedKnowledgePack,
    val generation: Long,
    val productionCutoverEligible: Boolean,
    val purpose: BuiltInKnowledgePackPurpose,
    val formalActivationProof: FormalKnowledgePackActivationProof?,
) {
    init {
        require(generation > 0L)
        when (purpose) {
            BuiltInKnowledgePackPurpose.DEBUG_BOUNDARY_FIXTURE -> {
                require(!productionCutoverEligible) {
                    "Debug boundary fixtures can never qualify for the production cutover"
                }
                require(pack.metadata.packId.startsWith("debug.")) {
                    "Debug boundary fixtures must use the debug namespace"
                }
                require(formalActivationProof == null) {
                    "Debug boundary fixtures must not masquerade as signed formal releases"
                }
            }
            BuiltInKnowledgePackPurpose.FORMAL_HIGH_SCHOOL_RELEASE -> {
                require(productionCutoverEligible) {
                    "A signed formal release must be eligible for the production cutover"
                }
                val proof = requireNotNull(formalActivationProof) {
                    "A formal release requires candidate, independent-review, and signature proof"
                }
                requireNotNull(proof.candidate.coverageProofV2) {
                    "A formal release requires reviewed coverage proof v2"
                }
            }
        }
    }

    fun requireGovernance(
        expectedContentFingerprint: String,
        trustedSigningKeys: Collection<FormalKnowledgePackSigningKey>,
    ) {
        when (purpose) {
            BuiltInKnowledgePackPurpose.DEBUG_BOUNDARY_FIXTURE -> Unit
            BuiltInKnowledgePackPurpose.FORMAL_HIGH_SCHOOL_RELEASE ->
                FormalKnowledgePackActivationPolicy.requireActivatable(
                    pack = pack,
                    expectedContentFingerprint = expectedContentFingerprint,
                    activationGeneration = generation,
                    proof = requireNotNull(formalActivationProof),
                    trustedSigningKeys = trustedSigningKeys,
                )
        }
    }
}
