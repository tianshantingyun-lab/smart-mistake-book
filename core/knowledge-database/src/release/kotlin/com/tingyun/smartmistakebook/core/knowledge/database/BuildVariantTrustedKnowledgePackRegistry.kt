package com.tingyun.smartmistakebook.core.knowledge.database

internal object BuildVariantTrustedKnowledgePackRegistry {
    val formalSigningKeys: List<FormalKnowledgePackSigningKey> =
        GeneratedFormalKnowledgePackRegistry.formalSigningKeys
    val entries: List<BuildVariantTrustedKnowledgePackDefinition> =
        GeneratedFormalKnowledgePackRegistry.entries
}
