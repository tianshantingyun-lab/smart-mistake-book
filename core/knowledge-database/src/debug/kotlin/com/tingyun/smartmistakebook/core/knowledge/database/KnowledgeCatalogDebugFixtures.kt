package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof

/**
 * Debug-variant fixture for consumer-module tests.
 *
 * Production and release variants do not contain this type. It avoids teaching tests to bypass
 * capability constructors with reflection while still letting another database module exercise
 * its local admission checks in isolation.
 */
object KnowledgeCatalogDebugFixtures {
    private val proofAuthority = KnowledgeReferenceProofAuthority.create()

    fun verifiedReferenceProof(
        ref: KnowledgeNodeRef,
        manifestFingerprint: String,
        activationGeneration: Long,
    ): VerifiedKnowledgeReferenceProof =
        proofAuthority.issuer.issue(
            ref,
            manifestFingerprint,
            activationGeneration,
        )

    fun verifiedNodeHandle(
        node: KnowledgeCatalogNode,
        manifestFingerprint: String,
        activationGeneration: Long,
    ): VerifiedKnowledgeNodeHandle =
        VerifiedKnowledgeNodeHandle.create(
            node = node,
            manifestFingerprint = manifestFingerprint,
            activationGeneration = activationGeneration,
        )
}
