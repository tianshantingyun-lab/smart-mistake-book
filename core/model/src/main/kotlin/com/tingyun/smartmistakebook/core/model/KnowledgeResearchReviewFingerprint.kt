package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class KnowledgeResearchSourceFingerprint(
    val canonicalSourceUri: String,
    val contentFingerprint: String,
)

object KnowledgeResearchReviewFingerprint {
    fun of(
        groundingKey: String,
        subject: String,
        sources: List<KnowledgeResearchSourceFingerprint>,
    ): String = sha256(
        buildString {
            append(groundingKey)
            append('|')
            append(subject)
            sources.sortedBy(KnowledgeResearchSourceFingerprint::canonicalSourceUri)
                .forEach { source ->
                    append('|')
                    append(source.canonicalSourceUri)
                    append('|')
                    append(source.contentFingerprint)
                }
        },
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
