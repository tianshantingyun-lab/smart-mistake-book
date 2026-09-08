package com.tingyun.smartmistakebook.core.domain

/**
 * Pretest routing for batch-introduced mistakes (spec `batch-intake-spec.md`
 * §3): the first exposure of a never-attempted question is a PRETEST — a
 * redo of the original problem with immediate feedback — not a spaced
 * review and not a lecture. Which surface can carry that pretest depends on
 * what the item structurally supports:
 *
 * - CHOICE_FLOW: the item has machine-checkable options — the existing
 *   review-session choice path scores it exactly and the outcome lands as a
 *   real attempt (INDEPENDENT_RECALL / RETRIEVAL_FAILURE).
 * - TUTOR_JUDGED_FLOW: free-response / diagram items without options — the
 *   tutor session judges the student's submission (existing visual/text
 *   verdict chain) and the verdict lands as the attempt.
 * - SELF_REPORT_FALLBACK: neither is available (e.g. no answer spec yet) —
 *   a low-weight metacognitive self-report, never a fake score.
 *
 * Pure function over booleans; the persistence layer supplies whether the
 * item has options and whether an answer spec exists.
 */
object PretestRouting {

    enum class PretestSurface {
        /** Machine-scored choice path (existing review choice submission). */
        CHOICE_FLOW,
        /** Tutor-judged free-response path (existing tutor verdict chain). */
        TUTOR_JUDGED_FLOW,
        /** No reliable scoring surface — metacognitive self-report only. */
        SELF_REPORT_FALLBACK,
    }

    data class ItemCapabilities(
        /** Item carries machine-checkable options (optionsSnapshot non-blank). */
        val hasOptions: Boolean,
        /** Item has a verifiable answer spec (answerSpecSnapshot non-blank). */
        val hasAnswerSpec: Boolean,
        /** A tutor session is available to judge free-response submissions. */
        val tutorAvailable: Boolean = true,
    ) {
        companion object {
            /**
             * Assemble capabilities from the persistence layer's authoritative
             * signals (spec §6 P2): the assessment item's scoring mode plus
             * whether it carries options and an answer spec. The scoring mode
             * is the strongest signal — AUTO_VERIFIED items are machine
             * checkable, RUBRIC_ASSISTED items need a judge, USER_SELF_REPORT
             * items are metacognitive only.
             *
             * @param scoringMode one of the StudyDbValue.ScoringMode strings.
             */
            fun fromScoringMode(
                scoringMode: String?,
                hasOptions: Boolean,
                hasAnswerSpec: Boolean,
                tutorAvailable: Boolean = true,
            ): ItemCapabilities = when (scoringMode) {
                // Machine-checkable choice items route to the choice surface.
                // hasOptions stays strict — AUTO_VERIFIED without options (an
                // auto-graded fill-in) is still not a choice flow.
                "AUTO_VERIFIED" -> ItemCapabilities(
                    hasOptions = hasOptions,
                    hasAnswerSpec = hasAnswerSpec,
                    tutorAvailable = tutorAvailable,
                )
                // Free-response items need a human/LLM judge.
                "RUBRIC_ASSISTED" -> ItemCapabilities(
                    hasOptions = false,
                    hasAnswerSpec = hasAnswerSpec,
                    tutorAvailable = tutorAvailable,
                )
                // Self-report items are metacognitive only — never a scored
                // surface, regardless of any structural signals on the row.
                "USER_SELF_REPORT" -> ItemCapabilities(
                    hasOptions = false,
                    hasAnswerSpec = false,
                    tutorAvailable = false,
                )
                // Unknown / no assessment item yet — fall back to the
                // structural signals only.
                else -> ItemCapabilities(
                    hasOptions = hasOptions,
                    hasAnswerSpec = hasAnswerSpec,
                    tutorAvailable = tutorAvailable,
                )
            }
        }
    }

    /**
     * Pretest route for a never-attempted question.
     *
     * CHOICE wins when options exist — an exact machine score is the most
     * reliable first attempt (no tutor variance, instant feedback). A
     * free-response item routes to the tutor judge when one is available and
     * the answer spec allows judging. Everything else falls back to the
     * self-report surface (low weight, no fake score).
     */
    fun routeForNewItem(capabilities: ItemCapabilities): PretestSurface = when {
        capabilities.hasOptions -> PretestSurface.CHOICE_FLOW
        capabilities.tutorAvailable && capabilities.hasAnswerSpec ->
            PretestSurface.TUTOR_JUDGED_FLOW
        else -> PretestSurface.SELF_REPORT_FALLBACK
    }

    /**
     * Whether an outcome on [surface] counts as a REAL first attempt for the
     * mastery ledger (spec §3: the pretest answer is the first genuine
     * extraction). Self-reports do not — they are metacognitive observations
     * only and must never masquerade as an independent recall.
     */
    fun producesRealAttempt(surface: PretestSurface): Boolean =
        surface == PretestSurface.CHOICE_FLOW || surface == PretestSurface.TUTOR_JUDGED_FLOW
}
