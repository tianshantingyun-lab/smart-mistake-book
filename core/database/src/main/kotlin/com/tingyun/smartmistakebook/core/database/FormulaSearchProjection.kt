package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.InlineToken
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.ReadableMathText
import com.tingyun.smartmistakebook.core.model.SafeInlineMarkdown

/**
 * Deterministic formula projection for the library FTS index
 * (audit section 8.2: "规范化公式 token").
 *
 * Formula text is extracted from the structured question snapshot:
 * explicit [ContentBlock.Formula] blocks plus bounded inline `$...$`
 * formulas inside paragraph / choice markdown. Each formula is linearized
 * with the production pipeline [ReadableMathText.formula]
 * (MathParser -> MathRenderer, pass-through on parse failure) and the
 * joined result is segmented with exactly the same [CjkTextTokenizer]
 * transformation applied to every other index column, so the query side
 * (CjkTextTokenizer.quotedPhrase) always agrees with the index side.
 */
object FormulaSearchProjection {

    /**
     * Builds the `formula_tokens` column value for one revision from its
     * captured question document snapshot JSON. Returns an empty string
     * when the snapshot is absent, undecodable, or carries no formula.
     */
    fun tokensForSnapshot(questionDocumentSnapshot: String?): String {
        if (questionDocumentSnapshot.isNullOrBlank()) return ""
        val captured = runCatching {
            CapturedQuestionDocumentCodec.decode(questionDocumentSnapshot)
        }.getOrNull() ?: return ""
        return tokensForDocument(captured.document)
    }

    /** Segmented formula tokens for one document; empty when no formula. */
    fun tokensForDocument(document: QuestionDocument): String {
        val formulas = extractFormulaTexts(document)
        if (formulas.isEmpty()) return ""
        return CjkTextTokenizer.segment(formulas.joinToString(separator = "\n"))
    }

    /** Ordered, de-duplicated readable formula texts found in a document. */
    fun extractFormulaTexts(document: QuestionDocument): List<String> = buildList {
        document.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Formula -> readableFormula(block)?.let(::add)
                is ContentBlock.Paragraph -> addAll(inlineFormulas(block.markdown))
                is ContentBlock.ChoiceGroup -> {
                    addAll(inlineFormulas(block.promptMarkdown))
                    block.choices.forEach { choice -> addAll(inlineFormulas(choice.markdown)) }
                }
                is ContentBlock.Figure,
                is ContentBlock.Unknown,
                -> Unit
            }
        }
    }.distinct()

    /**
     * Linearizes one formula block: rendered readable text first, falling
     * back to the accessibility alternative text when the latex is blank.
     */
    private fun readableFormula(block: ContentBlock.Formula): String? {
        val rendered = ReadableMathText.formula(block.latex)
        return rendered.ifBlank { block.alternativeText }.takeIf(String::isNotBlank)
    }

    /** Extracts bounded inline `$...$` formulas from markdown text. */
    private fun inlineFormulas(markdown: String): List<String> {
        if (markdown.isBlank()) return emptyList()
        return SafeInlineMarkdown.parse(markdown)
            .filterIsInstance<InlineToken.Formula>()
            .map { ReadableMathText.formula(it.value) }
            .filter(String::isNotBlank)
    }
}
