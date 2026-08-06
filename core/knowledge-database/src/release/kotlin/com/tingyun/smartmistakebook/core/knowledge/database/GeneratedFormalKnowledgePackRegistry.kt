package com.tingyun.smartmistakebook.core.knowledge.database

/**
 * Fail-closed release default.
 *
 * The offline signer replaces this source with a registry containing only a reviewed public key,
 * signed activation digests, and a link to the generated reviewed pack asset. No private key is
 * ever generated or stored in this source tree.
 */
internal object GeneratedFormalKnowledgePackRegistry {
    val formalSigningKeys: List<FormalKnowledgePackSigningKey> = emptyList()
    val entries: List<BuildVariantTrustedKnowledgePackDefinition> = emptyList()
}
