package com.tingyun.smartmistakebook.core.export

import com.tingyun.smartmistakebook.core.domain.MistakeDetailState

enum class MistakePdfBatchIneligibility {
    EMPTY,
    TOO_MANY_QUESTIONS,
    NO_READY_QUESTION,
}

sealed interface MistakePdfBatchEligibilityResult {
    data class Eligible(
        val input: MistakePdfExportInput,
        val includedCount: Int,
        val omittedCount: Int,
    ) : MistakePdfBatchEligibilityResult

    data class Ineligible(
        val reason: MistakePdfBatchIneligibility,
    ) : MistakePdfBatchEligibilityResult
}

/**
 * Builds one immutable, ordered print snapshot from the current library result.
 *
 * Unavailable entries are omitted automatically. The user is never asked to repair model output
 * or manually re-enter a question just to print the rest of the list.
 */
object MistakePdfBatchEligibility {
    const val MAX_QUESTIONS = 100

    fun check(states: List<MistakeDetailState>): MistakePdfBatchEligibilityResult {
        if (states.isEmpty()) {
            return MistakePdfBatchEligibilityResult.Ineligible(
                MistakePdfBatchIneligibility.EMPTY,
            )
        }
        if (states.size > MAX_QUESTIONS) {
            return MistakePdfBatchEligibilityResult.Ineligible(
                MistakePdfBatchIneligibility.TOO_MANY_QUESTIONS,
            )
        }

        val eligibleInputs = states.mapNotNull { state ->
            (MistakePdfEligibility.check(state) as? MistakePdfEligibilityResult.Eligible)?.input
        }
        if (eligibleInputs.isEmpty()) {
            return MistakePdfBatchEligibilityResult.Ineligible(
                MistakePdfBatchIneligibility.NO_READY_QUESTION,
            )
        }

        val includedCount = eligibleInputs.size
        val blocks = eligibleInputs.flatMapIndexed { index, input ->
            buildList {
                add(
                    MistakePdfBlock.SectionHeading(
                        id = "question-$index-heading",
                        text = "${index + 1}. ${input.title}",
                    ),
                )
                add(
                    MistakePdfBlock.Paragraph(
                        id = "question-$index-meta",
                        text = input.subtitle,
                    ),
                )
                input.blocks.forEach { block ->
                    add(block.withNamespacedId("question-$index-${block.id}"))
                }
            }
        }
        val sourceFingerprints = eligibleInputs.map(MistakePdfExportInput::inputSha256)
        val combinedFingerprint = exportFingerprint(
            "mistake-batch-v1",
            includedCount.toString(),
            *sourceFingerprints.toTypedArray(),
        )
        return MistakePdfBatchEligibilityResult.Eligible(
            input = MistakePdfExportInput(
                errorBookEntryId = "batch",
                problemId = "batch",
                problemRevisionId = combinedFingerprint,
                revisionNumber = 1,
                title = "错题练习",
                subject = "当前筛选",
                subtitle = "按当前筛选整理 · 共 $includedCount 道",
                documentTitle = null,
                blocks = blocks,
                questionDocumentSha256 = combinedFingerprint,
                inputSha256 = combinedFingerprint,
                maxPages = MistakePdfExportLimits.MAX_BATCH_PAGES,
                maxRenderedLines = MistakePdfExportLimits.MAX_BATCH_RENDERED_LINES,
                maxPdfBytes = MistakePdfExportLimits.MAX_BATCH_PDF_BYTES,
            ),
            includedCount = includedCount,
            omittedCount = states.size - includedCount,
        )
    }
}

private fun MistakePdfBlock.withNamespacedId(namespacedId: String): MistakePdfBlock = when (this) {
    is MistakePdfBlock.Paragraph -> copy(id = namespacedId)
    is MistakePdfBlock.SectionHeading -> copy(id = namespacedId)
    is MistakePdfBlock.Formula -> copy(id = namespacedId)
    is MistakePdfBlock.ChoiceGroup -> copy(id = namespacedId)
    is MistakePdfBlock.Figure -> copy(id = namespacedId)
}
