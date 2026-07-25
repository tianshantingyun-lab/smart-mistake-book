package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned question AST plus the evidence needed to decide whether each block is safe to trust.
 * Markdown is only a leaf payload inside [QuestionDocument], never the authoritative whole record.
 */
@Serializable
data class CapturedQuestionDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val document: QuestionDocument,
    val blockEvidence: List<QuestionBlockEvidence>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class QuestionBlockEvidence(
    val blockId: String,
    val sourceAssetId: String,
    val sourceRegion: NormalizedSourceRegion? = null,
    val writingLayer: WritingLayer = WritingLayer.UNKNOWN,
    val provenance: QuestionBlockProvenance,
    val confidence: Double? = null,
    val reviewStatus: QuestionBlockReviewStatus,
    val producerVersion: String? = null,
)

@Serializable
data class NormalizedSourceRegion(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

@Serializable
enum class WritingLayer {
    PRINTED,
    HANDWRITTEN,
    MIXED,
    DIAGRAM,
    UNKNOWN,
}

@Serializable
enum class QuestionBlockProvenance {
    USER_TRANSCRIPTION,
    USER_CORRECTION,
    LOCAL_OCR,
    OPTIONAL_REMOTE_OCR,
    MODEL_DOCUMENT_PARSE,
    DETERMINISTIC_RULE,
    IMPORTED_STRUCTURE,
}

@Serializable
enum class QuestionBlockReviewStatus {
    CANDIDATE,
    NEEDS_REVIEW,
    /** Accepted by deterministic local commit policy; this is not a student confirmation. */
    LOCAL_POLICY_ACCEPTED,
    USER_CONFIRMED,
}

enum class CapturedQuestionIssueCode {
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_STRUCTURED_CONTENT,
    EVIDENCE_DOES_NOT_MATCH_BLOCKS,
    BLANK_SOURCE_ASSET_ID,
    INVALID_SOURCE_REGION,
    MISSING_SOURCE_REGION,
    UNRESOLVED_WRITING_LAYER,
    INVALID_CONFIDENCE,
    MISSING_PRODUCER_VERSION,
    UNRESOLVED_BLOCK,
    EMPTY_QUESTION,
}

data class CapturedQuestionIssue(
    val code: CapturedQuestionIssueCode,
    val blockId: String? = null,
)

object CapturedQuestionDocumentValidator {
    fun validateDraft(value: CapturedQuestionDocument): List<CapturedQuestionIssue> = buildList {
        if (value.schemaVersion != CapturedQuestionDocument.CURRENT_SCHEMA_VERSION) {
            add(CapturedQuestionIssue(CapturedQuestionIssueCode.UNSUPPORTED_SCHEMA_VERSION))
        }
        val sanitized = StructuredContentSanitizer.sanitize(value.document)
        if (sanitized.issues.isNotEmpty() || sanitized.document != value.document) {
            add(CapturedQuestionIssue(CapturedQuestionIssueCode.INVALID_STRUCTURED_CONTENT))
        }

        val blockIds = value.document.blocks.map(ContentBlock::id)
        val evidenceIds = value.blockEvidence.map(QuestionBlockEvidence::blockId)
        if (blockIds.toSet().size != blockIds.size || evidenceIds != blockIds) {
            add(CapturedQuestionIssue(CapturedQuestionIssueCode.EVIDENCE_DOES_NOT_MATCH_BLOCKS))
        }

        value.blockEvidence.forEach { evidence ->
            if (evidence.sourceAssetId.isBlank()) {
                add(
                    CapturedQuestionIssue(
                        CapturedQuestionIssueCode.BLANK_SOURCE_ASSET_ID,
                        evidence.blockId,
                    ),
                )
            }
            evidence.sourceRegion?.let { region ->
                if (!region.isValid()) {
                    add(
                        CapturedQuestionIssue(
                            CapturedQuestionIssueCode.INVALID_SOURCE_REGION,
                            evidence.blockId,
                        ),
                    )
                }
            }
            evidence.confidence?.let { confidence ->
                if (!confidence.isFinite() || confidence !in 0.0..1.0) {
                    add(
                        CapturedQuestionIssue(
                            CapturedQuestionIssueCode.INVALID_CONFIDENCE,
                            evidence.blockId,
                        ),
                    )
                }
            }
            if (
                evidence.provenance !in USER_PROVENANCE &&
                evidence.producerVersion.isNullOrBlank()
            ) {
                add(
                    CapturedQuestionIssue(
                        CapturedQuestionIssueCode.MISSING_PRODUCER_VERSION,
                        evidence.blockId,
                    ),
                )
            }
        }
    }.distinct()

    fun validateForCommit(value: CapturedQuestionDocument): List<CapturedQuestionIssue> = buildList {
        addAll(validateDraft(value))
        value.blockEvidence
            .filter { it.reviewStatus !in COMMIT_ACCEPTED_STATUSES }
            .forEach {
                add(CapturedQuestionIssue(CapturedQuestionIssueCode.UNRESOLVED_BLOCK, it.blockId))
            }
        value.blockEvidence
            .filter { it.sourceRegion == null }
            .forEach {
                add(CapturedQuestionIssue(CapturedQuestionIssueCode.MISSING_SOURCE_REGION, it.blockId))
            }
        if (value.document.blocks.none { block -> block.hasMeaningfulQuestionContent() }) {
            add(CapturedQuestionIssue(CapturedQuestionIssueCode.EMPTY_QUESTION))
        }
    }.distinct()

    private fun NormalizedSourceRegion.isValid(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0 &&
            left < right && top < bottom

    private fun ContentBlock.hasMeaningfulQuestionContent(): Boolean = when (this) {
        is ContentBlock.Paragraph -> markdown.isNotBlank()
        is ContentBlock.Formula -> latex.isNotBlank() || alternativeText.isNotBlank()
        is ContentBlock.ChoiceGroup -> promptMarkdown.isNotBlank() || choices.any {
            it.markdown.isNotBlank()
        }
        is ContentBlock.Figure -> alternativeText.isNotBlank()
        is ContentBlock.Unknown -> false
    }

    private val USER_PROVENANCE = setOf(
        QuestionBlockProvenance.USER_TRANSCRIPTION,
        QuestionBlockProvenance.USER_CORRECTION,
    )

    private val COMMIT_ACCEPTED_STATUSES = setOf(
        QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED,
        QuestionBlockReviewStatus.USER_CONFIRMED,
    )
}

/** Strict bounded codec used for Room snapshots and adapter boundaries. */
object CapturedQuestionDocumentCodec {
    const val MAX_ENCODED_CHARS = 128_000

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(value: CapturedQuestionDocument): String {
        require(CapturedQuestionDocumentValidator.validateDraft(value).isEmpty()) {
            "Captured question document is invalid"
        }
        return json.encodeToString(CapturedQuestionDocument.serializer(), value).also {
            require(it.length <= MAX_ENCODED_CHARS) { "Captured question snapshot exceeds budget" }
        }
    }

    fun decode(value: String): CapturedQuestionDocument {
        require(value.length <= MAX_ENCODED_CHARS) { "Captured question snapshot exceeds budget" }
        return json.decodeFromString(CapturedQuestionDocument.serializer(), value).also {
            require(CapturedQuestionDocumentValidator.validateDraft(it).isEmpty()) {
                "Captured question snapshot is invalid"
            }
        }
    }
}

object CapturedQuestionDocumentFingerprint {
    fun of(value: CapturedQuestionDocument): String = sha256(
        CapturedQuestionDocumentCodec.encode(value),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** Deterministic legacy/search projection. The structured snapshot remains authoritative. */
object QuestionDocumentMarkdownProjection {
    fun project(document: QuestionDocument): String = buildList {
        document.title?.takeIf(String::isNotBlank)?.let { add("# $it") }
        document.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Paragraph -> add(block.markdown)
                is ContentBlock.Formula -> add(
                    if (block.display) "\$\$${block.latex}\$\$" else "\$${block.latex}\$",
                )
                is ContentBlock.ChoiceGroup -> {
                    if (block.promptMarkdown.isNotBlank()) add(block.promptMarkdown)
                    block.choices.forEachIndexed { index, choice ->
                        add("${('A'.code + index).toChar()}. ${choice.markdown}")
                    }
                }
                is ContentBlock.Figure -> add("[图形：${block.alternativeText}]")
                is ContentBlock.Unknown -> Unit
            }
        }
    }.filter(String::isNotBlank).joinToString("\n\n")
}
