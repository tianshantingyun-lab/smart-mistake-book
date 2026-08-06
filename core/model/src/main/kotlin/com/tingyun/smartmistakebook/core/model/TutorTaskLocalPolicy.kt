package com.tingyun.smartmistakebook.core.model

/** Local, fail-closed answer authority. Open text and model declarations never grant permission. */
fun TutorRespondInput.studentAuthorizedSolutionRequest(): Boolean =
    requestedMove == TutorMoveType.REVEAL_SOLUTION

/** Single local authority shared by preview, completion, rendering, exposure, and history. */
fun TutorRespondInput.authorizesSolutionExposure(): Boolean =
    explanationMode == TutorExplanationMode.DIRECT || studentAuthorizedSolutionRequest()

/** Shared defense-in-depth boundary for validation, rendering, exposure recording, and history. */
fun TutorRespondOutput.canExposeSolutionFor(input: TutorRespondInput): Boolean =
    solutionRevealed &&
        input.authorizesSolutionExposure() &&
        sessionId == input.sessionId &&
        draftRevisionNumber == input.draftRevisionNumber &&
        questionDocumentId == input.questionDocument.id &&
        responseOrdinal == input.responseOrdinal &&
        cycleOrdinal == input.cycleOrdinal &&
        turnOrdinal == input.turnOrdinal

/**
 * Applies the deterministic part of the explanation boundary. Model-authored Markdown never proves
 * semantic non-disclosure: only a host-authored free-response prompt can remain unexposed.
 */
fun TutorRespondOutput.locallyConstrainedFor(input: TutorRespondInput): TutorRespondOutput? {
    val declaredResponseIntent = responseIntent ?: if (interactionDirective != null) {
        TutorResponseIntent.ASK
    } else {
        TutorResponseIntent.EXPLAIN
    }
    if (input.studentAuthorizedSolutionRequest()) {
        return takeIf {
            declaredResponseIntent == TutorResponseIntent.EXPLAIN &&
            solutionRevealed &&
                interactionDirective == null &&
                (
                    responseIntent == TutorResponseIntent.EXPLAIN ||
                        !messageMarkdown.containsTutorQuestionMark()
                    )
        }
    }
    if (intentDecision.intent != TutorMessageIntent.CURRENT_QUESTION_HELP) {
        return takeIf {
            declaredResponseIntent == TutorResponseIntent.EXPLAIN &&
            !solutionRevealed &&
                visualScene == null &&
                visualRequest == null &&
                suggestedMoves.isEmpty() &&
                interactionDirective == null &&
                !messageMarkdown.containsDeterministicSolutionClaim()
        }
    }
    if (input.authorizesSolutionExposure()) {
        return takeIf {
            declaredResponseIntent == TutorResponseIntent.EXPLAIN &&
            solutionRevealed &&
                interactionDirective == null &&
                (
                    responseIntent == TutorResponseIntent.EXPLAIN ||
                        !messageMarkdown.containsTutorQuestionMark()
                    )
        }
    }
    val localInteraction = interactionDirective?.locallyAuthoredGuidedFreeResponse()
    val hasSafeSingleAsk = responseIntent == TutorResponseIntent.ASK &&
        !solutionRevealed &&
        visualScene == null &&
        visualRequest == null &&
        suggestedMoves.isEmpty() &&
        localInteraction != null
    return if (hasSafeSingleAsk) {
        copy(
            messageMarkdown = GUIDED_INTERACTION_MESSAGE,
            responseIntent = TutorResponseIntent.ASK,
            solutionRevealed = false,
            interactionDirective = localInteraction,
        )
    } else {
        copy(
            responseIntent = TutorResponseIntent.EXPLAIN,
            solutionRevealed = true,
            interactionDirective = null,
        )
    }
}

internal fun TutorInteractionDirective.locallyAuthoredGuidedFreeResponse():
    TutorInteractionDirective.FreeResponse? =
    when (this) {
        is TutorInteractionDirective.FreeResponse ->
            TutorInteractionDirective.FreeResponse(GUIDED_FREE_RESPONSE_PROMPT)
        TutorInteractionDirective.Continue,
        is TutorInteractionDirective.Choices,
        is TutorInteractionDirective.VisualTarget,
        -> null
    }

internal fun String.containsTutorQuestionMark(): Boolean =
    any { character -> character == '？' || character == '?' }

internal fun String.containsDeterministicSolutionClaim(): Boolean {
    val normalized = normalizedTutorBoundaryText()
    return EXPLICIT_RESULT_CLAIM.containsMatchIn(normalized) ||
        FINAL_SELECTION_CLAIM.containsMatchIn(normalized) ||
        FINAL_EQUATION_CLAIM.containsMatchIn(normalized) ||
        COMPLETE_SOLUTION_CLAIM.containsMatchIn(normalized)
}

internal fun String.normalizedTutorContentForComparison(): String =
    normalizedTutorBoundaryText()
        .replace(TUTOR_CONTENT_WHITESPACE, " ")
        .lowercase()

private fun String.normalizedTutorBoundaryText(): String =
    replace(TUTOR_BOUNDARY_MARKDOWN_DECORATION, "")
        .replace('\u00a0', ' ')
        .trim()

private val TUTOR_BOUNDARY_MARKDOWN_DECORATION = Regex("""[*_~#>]""")
private val TUTOR_CONTENT_WHITESPACE = Regex("""\s+""")
private val EXPLICIT_RESULT_CLAIM = Regex(
    pattern =
        """(?ix)""" +
            """(?:最终\s*(?:答案|结果|结论)|正确\s*(?:答案|选项)|本题\s*(?:答案|结论)|答案|结论)""" +
            """\s*(?:是|为|[:：])\s*""" +
            """(?!什么|多少|哪(?:个|项)?|谁|如何|怎么|是否|能否|由\s*(?:哪个|什么)|[?？])\S""" +
            """|\b(?:final\s+answer|answer|conclusion)\s*(?:is|:)\s*""" +
            """(?!what|which|who|how)\S""",
)
private val FINAL_SELECTION_CLAIM = Regex(
    pattern =
        """(?ix)(?:所以|因此|故|从而|可见|可知|应当|应该)""" +
            """\s*(?:应当|应该|要|可)?\s*(?:选择|选)\s*(?:项\s*)?""" +
            """(?:[a-hＡ-Ｈ]\b|[甲乙丙丁①②③④⑤⑥⑦⑧])""" +
            """|\b(?:therefore|thus)\s+(?:choose|select)\s+[a-h]\b""",
)
private val FINAL_EQUATION_CLAIM = Regex(
    pattern =
        """(?ix)(?:最终|综上|解得|求得|算得|得到|推出|可得)\s*[,，:：]?\s*""" +
            """[^\r\n。；;]{0,64}[=＝]\s*\S+""" +
            """|\b(?:solving\s+gives|therefore)\s+[^.\r\n]{0,48}=\s*\S+""",
)
private val COMPLETE_SOLUTION_CLAIM = Regex(
    """(?ix)完整\s*(?:解法|解答|解析|过程)\s*(?:是|如下|[:：])""" +
        """|\bcomplete\s+solution\s*(?:is|follows|:)\b""",
)

const val GUIDED_INTERACTION_MESSAGE = "先完成下面这个小步骤。"
const val GUIDED_FREE_RESPONSE_PROMPT = "下一步应该怎么做？"

/**
 * Deterministic presentation reducer for initial tutor plans.
 *
 * DIRECT never waits for input. A provider that asks anyway is reduced to its already-returned
 * complete solution path without inspecting punctuation. GUIDED exposes only one host-authored
 * free-response interaction; every uncertified provider shape is replaced with that local step.
 */
fun TutorPlanOutput.locallyConstrainedFor(input: TutorPlanInput): TutorPlanOutput {
    val interactionCount = listOfNotNull(
        plan.diagnosticItem,
        plan.interactionDirective,
    ).size
    val declaredIntent = plan.responseIntent ?: if (interactionCount > 0) {
        TutorResponseIntent.ASK
    } else {
        TutorResponseIntent.EXPLAIN
    }
    val constrainedPlan = when (input.explanationMode) {
        TutorExplanationMode.DIRECT -> plan.copy(
            showOpening = plan.showOpening && declaredIntent != TutorResponseIntent.ASK,
            responseIntent = TutorResponseIntent.EXPLAIN,
            solutionRevealed = true,
            diagnosticItem = null,
            interactionDirective = null,
            guidedInteractionProposal = null,
            hintMarkdown = null,
        )

        TutorExplanationMode.GUIDED -> {
            val guidedProposal = plan.singleGuidedInteractionProposal()
            val localInteraction = plan.interactionDirective
                ?.locallyAuthoredGuidedFreeResponse()
            val hasSafeSingleAsk = plan.responseIntent == TutorResponseIntent.ASK &&
                interactionCount == 1 &&
                !plan.solutionRevealed &&
                plan.diagnosticItem == null &&
                localInteraction != null
            val interactionDirective = if (hasSafeSingleAsk) {
                localInteraction
            } else {
                TutorInteractionDirective.FreeResponse(GUIDED_FREE_RESPONSE_PROMPT)
            }
            plan.copy(
                openingMarkdown = GUIDED_INTERACTION_MESSAGE,
                showOpening = true,
                responseIntent = TutorResponseIntent.ASK,
                solutionRevealed = false,
                diagnosticItem = null,
                interactionDirective = interactionDirective,
                guidedInteractionProposal = guidedProposal,
                visualScene = null,
                visualRequest = null,
                suggestedMoves = emptyList(),
            )
        }
    }
    return copy(plan = constrainedPlan)
}

private fun TutorTurnPlan.singleGuidedInteractionProposal(): TutorGuidedInteractionProposal? {
    if (responseIntent != TutorResponseIntent.ASK || solutionRevealed || diagnosticItem != null) {
        return null
    }
    val retained = guidedInteractionProposal
    val current = when (val directive = interactionDirective) {
        is TutorInteractionDirective.Choices -> TutorGuidedInteractionProposal(directive)
        is TutorInteractionDirective.VisualTarget -> TutorGuidedInteractionProposal(
            directive = directive,
            visualScene = visualScene as? TutorVisualDocumentScene,
        )
        TutorInteractionDirective.Continue,
        is TutorInteractionDirective.FreeResponse,
        null,
        -> null
    }
    return listOfNotNull(retained, current).distinct().singleOrNull()
}
