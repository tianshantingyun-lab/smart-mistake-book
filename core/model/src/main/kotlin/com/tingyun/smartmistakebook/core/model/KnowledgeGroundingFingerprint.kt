package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Groups repeated ontology gaps without losing their per-question occurrences. */
object KnowledgeGroundingFingerprint {
    private val whitespace = Regex("\\s+")
    private val groundingKeyPattern = Regex("grounding:[a-f0-9]{64}")

    fun isValid(value: String): Boolean = value.matches(groundingKeyPattern)

    fun of(
        subject: SubjectKind,
        expectedParentKnowledgeDisplayName: String,
        query: String,
    ): String {
        val canonical = listOf(
            subject.name,
            expectedParentKnowledgeDisplayName.normalizedPart(),
            query.normalizedPart(),
        ).joinToString("|")
        return "grounding:${sha256(canonical)}"
    }

    fun occurrenceId(
        organizationRequestId: String,
        requestOrdinal: Int,
        groundingKey: String,
    ): String {
        require(organizationRequestId.isNotBlank()) { "Organization request id must not be blank" }
        require(requestOrdinal >= 0) { "Knowledge grounding ordinal must not be negative" }
        require(isValid(groundingKey)) {
            "Knowledge grounding key is invalid"
        }
        return "grounding-request:${sha256("$organizationRequestId|$requestOrdinal|$groundingKey").take(40)}"
    }

    fun resolutionId(
        groundingKey: String,
        knowledgeNodeId: String,
    ): String {
        require(isValid(groundingKey)) {
            "Knowledge grounding key is invalid"
        }
        require(knowledgeNodeId.isNotBlank()) { "Knowledge node id must not be blank" }
        return "grounding-resolution:${sha256("$groundingKey|$knowledgeNodeId").take(40)}"
    }

    fun resolvedBindingId(
        practiceUnitId: String,
        knowledgeNodeId: String,
        problemRevisionId: String,
        taxonomyVersion: String,
    ): String {
        require(
            listOf(practiceUnitId, knowledgeNodeId, problemRevisionId, taxonomyVersion)
                .all(String::isNotBlank),
        ) { "Resolved knowledge binding identity must not contain blank parts" }
        val canonical = listOf(
            practiceUnitId,
            knowledgeNodeId,
            problemRevisionId,
            taxonomyVersion,
        ).joinToString("|")
        return "knowledge-binding:${sha256(canonical).take(40)}"
    }

    private fun String.normalizedPart(): String =
        trim().lowercase(Locale.ROOT).replace(whitespace, " ")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
