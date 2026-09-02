package com.tingyun.smartmistakebook.core.export

import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.FigureSchema
import com.tingyun.smartmistakebook.core.model.SafeInlineMarkdown
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val CLEAN_IMAGE_SOURCE_ROLE = "CLEAN_IMAGE"

enum class MistakePdfIneligibility {
    LOADING,
    LEGACY_CONTENT,
    CORRUPT_CONTENT,
    NOT_FOUND,
    UNCONFIRMED_OR_INVALID_DOCUMENT,
    UNSUPPORTED_BLOCK,
    EMPTY_SUPPORTED_BLOCK,
    INVALID_CHOICE_STATE,
}

sealed interface MistakePdfEligibilityResult {
    data class Eligible(val input: MistakePdfExportInput) : MistakePdfEligibilityResult

    data class Ineligible(
        val reasons: Set<MistakePdfIneligibility>,
    ) : MistakePdfEligibilityResult {
        init {
            require(reasons.isNotEmpty()) { "An ineligible export must have a reason" }
        }
    }
}

/**
 * Strict bridge from the trusted mistake-detail projection into an immutable export snapshot.
 * fallbackMarkdown and source images are deliberately absent from this contract.
 */
object MistakePdfEligibility {
    fun check(state: MistakeDetailState): MistakePdfEligibilityResult = when (state) {
        MistakeDetailState.Loading -> ineligible(MistakePdfIneligibility.LOADING)
        is MistakeDetailState.Legacy -> ineligible(MistakePdfIneligibility.LEGACY_CONTENT)
        is MistakeDetailState.CorruptSnapshot -> ineligible(MistakePdfIneligibility.CORRUPT_CONTENT)
        MistakeDetailState.NotFound -> ineligible(MistakePdfIneligibility.NOT_FOUND)
        is MistakeDetailState.Ready -> checkReady(state)
    }

    private fun checkReady(state: MistakeDetailState.Ready): MistakePdfEligibilityResult {
        val reasons = buildSet {
            if (
                CapturedQuestionDocumentValidator
                    .validateForCommit(state.questionDocument)
                    .isNotEmpty()
            ) {
                add(MistakePdfIneligibility.UNCONFIRMED_OR_INVALID_DOCUMENT)
            }
            state.questionDocument.document.blocks.forEach { block ->
                when (block) {
                    is ContentBlock.Paragraph -> {
                        if (block.markdown.isBlank()) {
                            add(MistakePdfIneligibility.EMPTY_SUPPORTED_BLOCK)
                        }
                    }
                    is ContentBlock.Formula -> {
                        if (block.latex.isBlank() && block.alternativeText.isBlank()) {
                            add(MistakePdfIneligibility.EMPTY_SUPPORTED_BLOCK)
                        }
                    }
                    is ContentBlock.ChoiceGroup -> {
                        if (block.choices.isEmpty() || block.choices.any { it.markdown.isBlank() }) {
                            add(MistakePdfIneligibility.EMPTY_SUPPORTED_BLOCK)
                        }
                        if (
                            block.selectedChoiceId != null &&
                            block.choices.none { it.id == block.selectedChoiceId }
                        ) {
                            add(MistakePdfIneligibility.INVALID_CHOICE_STATE)
                        }
                    }
                    is ContentBlock.Figure -> Unit
                    is ContentBlock.Unknown -> add(MistakePdfIneligibility.UNSUPPORTED_BLOCK)
                }
            }
        }
        if (reasons.isNotEmpty()) return MistakePdfEligibilityResult.Ineligible(reasons)

        val identity = state.detail.identity
        val document = state.questionDocument
        val blocks = document.document.blocks.map { block ->
            when (block) {
                is ContentBlock.Paragraph -> MistakePdfBlock.Paragraph(
                    id = block.id,
                    text = safeInlineText(block.markdown),
                )
                is ContentBlock.Formula -> MistakePdfBlock.Formula(
                    id = block.id,
                    latex = block.latex,
                    alternativeText = block.alternativeText,
                    display = block.display,
                )
                is ContentBlock.ChoiceGroup -> MistakePdfBlock.ChoiceGroup(
                    id = block.id,
                    prompt = safeInlineText(block.promptMarkdown),
                    choices = block.choices.map { choice ->
                        MistakePdfChoice(
                            id = choice.id,
                            text = safeInlineText(choice.markdown),
                            selected = choice.id == block.selectedChoiceId,
                            enabled = choice.enabled,
                        )
                    },
                    enabled = block.enabled,
                )
                is ContentBlock.Figure -> MistakePdfBlock.Figure(
                    id = block.id,
                    title = block.title,
                    alternativeText = block.alternativeText,
                    schema = block.schema,
                )
                is ContentBlock.Unknown -> error("Unsupported blocks were rejected before mapping")
            }
        }
        val documentFingerprint = CapturedQuestionDocumentFingerprint.of(document)
        return MistakePdfEligibilityResult.Eligible(
            MistakePdfExportInput(
                errorBookEntryId = identity.errorBookEntryId,
                problemId = identity.problemId,
                problemRevisionId = identity.problemRevisionId,
                revisionNumber = identity.revisionNumber,
                title = identity.title,
                subject = identity.subject,
                subtitle = "${identity.subject} · 第 ${identity.revisionNumber} 版",
                documentTitle = document.document.title,
                blocks = blocks,
                cleanImageLocalUri = cleanImageUri(state.detail.source),
                questionDocumentSha256 = documentFingerprint,
                inputSha256 = exportFingerprint(
                    identity.errorBookEntryId,
                    identity.problemId,
                    identity.problemRevisionId,
                    identity.revisionNumber.toString(),
                    identity.title,
                    identity.subject,
                    documentFingerprint,
                ),
            ),
        )
    }

    private fun cleanImageUri(
        source: com.tingyun.smartmistakebook.core.domain.MistakeSourceSet,
    ): String? = when (source) {
        is com.tingyun.smartmistakebook.core.domain.MistakeSourceSet.Present ->
            source.assets.firstNotNullOfOrNull { asset ->
                (asset.location as? com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation.Available)
                    ?.takeIf { asset.role == CLEAN_IMAGE_SOURCE_ROLE }
                    ?.localUri
            }
        else -> null
    }

    private fun safeInlineText(value: String): String = buildString {
        SafeInlineMarkdown.parse(value).forEach { token ->
            when (token) {
                is com.tingyun.smartmistakebook.core.model.InlineToken.Text -> append(token.value)
                is com.tingyun.smartmistakebook.core.model.InlineToken.Strong -> append(token.value)
                is com.tingyun.smartmistakebook.core.model.InlineToken.Emphasis -> append(token.value)
                is com.tingyun.smartmistakebook.core.model.InlineToken.Code -> append(token.value)
                is com.tingyun.smartmistakebook.core.model.InlineToken.Formula -> {
                    append('$')
                    append(token.value)
                    append('$')
                }
                com.tingyun.smartmistakebook.core.model.InlineToken.LineBreak -> append('\n')
            }
        }
    }

    private fun ineligible(reason: MistakePdfIneligibility) =
        MistakePdfEligibilityResult.Ineligible(setOf(reason))
}

class MistakePdfExportInput internal constructor(
    val errorBookEntryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val revisionNumber: Int,
    val title: String,
    val subject: String,
    val subtitle: String,
    val documentTitle: String?,
    val blocks: List<MistakePdfBlock>,
    val questionDocumentSha256: String,
    val inputSha256: String,
    val maxPages: Int = MistakePdfExportLimits.MAX_SINGLE_PAGES,
    val maxRenderedLines: Int = MistakePdfExportLimits.MAX_SINGLE_RENDERED_LINES,
    val maxPdfBytes: Long = MistakePdfExportLimits.MAX_SINGLE_PDF_BYTES,
    val cleanImageLocalUri: String? = null,
)

sealed interface MistakePdfBlock {
    val id: String

    data class Paragraph(
        override val id: String,
        val text: String,
    ) : MistakePdfBlock

    data class SectionHeading(
        override val id: String,
        val text: String,
    ) : MistakePdfBlock

    data class Formula(
        override val id: String,
        val latex: String,
        val alternativeText: String,
        val display: Boolean,
    ) : MistakePdfBlock

    data class ChoiceGroup(
        override val id: String,
        val prompt: String,
        val choices: List<MistakePdfChoice>,
        val enabled: Boolean,
    ) : MistakePdfBlock

    data class Figure(
        override val id: String,
        val title: String?,
        val alternativeText: String,
        val schema: FigureSchema,
    ) : MistakePdfBlock
}

data class MistakePdfChoice(
    val id: String,
    val text: String,
    val selected: Boolean,
    val enabled: Boolean,
)

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
}

internal fun exportFingerprint(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    return digest.digest().toHex()
}
