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
 * - UNAVAILABLE: neither is available (e.g. no model configured) — the item
 *   stays blocked with an honest notice; no self-report, no fake score.
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
        /**
         * 既没有机判选项、也没有可用的讲题判定（无模型 / 无 answer spec 且判定不可用）。
         * 2026-09-13 起不再有"低权重自评"兜底：这类题保持阻塞并如实提示，
         * 绝不把学生的自报当成对错证据（产品裁定 + `docs/research/model-judged-verdict-pricing.md`）。
         */
        UNAVAILABLE,
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
     * the answer spec allows judging. Without either surface the item stays
     * blocked ([UNAVAILABLE]) — there is no self-report fallback any more.
     */
    fun routeForNewItem(capabilities: ItemCapabilities): PretestSurface = when {
        capabilities.hasOptions -> PretestSurface.CHOICE_FLOW
        capabilities.tutorAvailable && capabilities.hasAnswerSpec ->
            PretestSurface.TUTOR_JUDGED_FLOW
        else -> PretestSurface.UNAVAILABLE
    }

    /**
     * Whether an outcome on [surface] counts as a REAL first attempt for the
     * mastery ledger (spec §3: the pretest answer is the first genuine
     * extraction). [UNAVAILABLE] produces nothing at all — the item stays
     * blocked instead of recording a metacognitive self-report.
     */
    fun producesRealAttempt(surface: PretestSurface): Boolean =
        surface == PretestSurface.CHOICE_FLOW || surface == PretestSurface.TUTOR_JUDGED_FLOW
}
