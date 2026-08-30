package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Local read tools the model may request during a tool-loop round
 * (spec model-intent-routing §2). All execution is local and deterministic;
 * the model only ever supplies an intent-gated request with anchored terms.
 */
@Serializable
enum class TutorToolName {
    KNOWLEDGE_READ,
    NOTEBOOK_READ,
    MASTERY_READ,
    /** 写工具：需要学生明确命令，当前阶段仅声明不启用（spec §2 T4/T6）。 */
    NOTEBOOK_WRITE,
    MASTERY_UPDATE,
}

/** One model-issued tool request for the current round. */
@Serializable
data class TutorToolCall(
    val tool: TutorToolName,
    val rationale: String,
    val terms: List<String> = emptyList(),
) {
    init {
        require(rationale.isNotBlank() && rationale.length <= MAX_TOOL_RATIONALE_CHARS) {
            "A tool call must state an anchored reason of at most $MAX_TOOL_RATIONALE_CHARS chars"
        }
        require(
            terms.size <= TutorIntentDecision.MAX_LOOKUP_TERMS &&
                terms.all { term ->
                    term == term.trim() &&
                        term.length in 1..TutorIntentDecision.MAX_LOOKUP_TERM_CHARS &&
                        term.none(Char::isISOControl)
                } &&
                terms.distinctBy(String::lowercase).size == terms.size,
        ) {
            "Tool call terms must be short distinct student-derived words"
        }
        if (tool != TutorToolName.MASTERY_READ) {
            require(terms.isNotEmpty()) {
                "The $tool tool requires at least one lookup term"
            }
        }
    }

    companion object {
        const val MAX_TOOL_RATIONALE_CHARS = 200
    }
}

/** Locally computed outcome of one executed tool call — the model never sees raw rows. */
@Serializable
data class TutorToolOutcome(
    val tool: TutorToolName,
    val ok: Boolean,
    val summaryMarkdown: String,
    val errorKind: String? = null,
) {
    init {
        summaryMarkdown.requireSafeModelText(
            label = "Tutor tool outcome summary",
            maxChars = MAX_TOOL_RESULT_CHARS,
            allowLineBreaks = true,
        )
        require(ok || errorKind != null) { "A failed tool outcome must carry an error kind" }
    }

    companion object {
        const val MAX_TOOL_RESULT_CHARS = 2_000
    }
}

/** Everything the model gets back for one tool round, injected into the next prompt. */
@Serializable
data class TutorToolRoundResult(
    val roundOrdinal: Int,
    val outcomes: List<TutorToolOutcome>,
) {
    init {
        require(roundOrdinal in 1..MAX_TOOL_ROUNDS) {
            "Tool round ordinal $roundOrdinal is outside 1..$MAX_TOOL_ROUNDS"
        }
        require(outcomes.isNotEmpty()) { "A tool round must carry at least one outcome" }
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 2
    }
}

/**
 * The model's request for a tool round: it must restate its intent decision so the
 * local authorization matrix can gate every call against intent + confidence.
 * The looping repository supplies kind/subject context — this output is a
 * protocol artifact, never persisted as a terminal task result.
 */
@Serializable
@SerialName("tutor_tool_requests_output")
data class TutorToolRequestsOutput(
    val intentDecision: TutorIntentDecision,
    val calls: List<TutorToolCall>,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        require(calls.isNotEmpty() && calls.size <= MAX_TOOL_CALLS_PER_ROUND) {
            "A tool round must request 1..$MAX_TOOL_CALLS_PER_ROUND calls"
        }
        require(calls.distinctBy(TutorToolCall::tool).size == calls.size) {
            "A tool round must not request the same tool twice"
        }
        modelVersion.requireSafeModelText(
            label = "Tutor tool request model version",
            maxChars = MAX_TOOL_REQUEST_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
    }

    companion object {
        const val MAX_TOOL_CALLS_PER_ROUND = 3
        const val MAX_TOOL_REQUEST_MODEL_VERSION_CHARS = 64
    }
}

/** Result of the local authorization matrix applied to one tool round (spec §3.2). */
data class TutorToolAuthorization(
    val allowedTools: Set<TutorToolName>,
    val routeEligible: Boolean,
    val reason: String,
)

/**
 * Intent × confidence × declared-set gate for one tool round. Quantified per
 * spec §9.4: below [ROUTE_CONFIDENCE_THRESHOLD] the intent is untrusted and no
 * tool runs; per-intent tool sets follow the local boundary matrix; the
 * declared set the repository announced in the prompt is intersected last.
 */
fun tutorToolAuthorization(
    decision: TutorIntentDecision,
    declaredTools: Set<TutorToolName>,
): TutorToolAuthorization {
    if (decision.confidence < ROUTE_CONFIDENCE_THRESHOLD) {
        return TutorToolAuthorization(
            allowedTools = emptySet(),
            routeEligible = false,
            reason = "intent confidence ${decision.confidence} below $ROUTE_CONFIDENCE_THRESHOLD",
        )
    }
    if (decision.intent == TutorMessageIntent.AMBIGUOUS) {
        return TutorToolAuthorization(
            allowedTools = emptySet(),
            routeEligible = false,
            reason = "intent is ambiguous",
        )
    }
    val byIntent = when (decision.intent) {
        TutorMessageIntent.CURRENT_QUESTION_HELP ->
            setOf(TutorToolName.KNOWLEDGE_READ, TutorToolName.NOTEBOOK_READ, TutorToolName.MASTERY_READ, TutorToolName.MASTERY_UPDATE)
        TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP -> setOf(TutorToolName.NOTEBOOK_READ)
        TutorMessageIntent.LEARNING_PROGRESS_LOOKUP -> setOf(TutorToolName.MASTERY_READ)
        TutorMessageIntent.APP_HELP_OR_SETTINGS,
        TutorMessageIntent.CASUAL_CONVERSATION,
        TutorMessageIntent.END_OR_PAUSE,
        -> emptySet()
    }
    val allowed = byIntent.intersect(declaredTools)
    return TutorToolAuthorization(
        allowedTools = allowed,
        routeEligible = true,
        reason = if (allowed.isEmpty()) {
            "intent ${decision.intent} has no declared tools"
        } else {
            "allowed ${allowed.map(TutorToolName::name)}"
        },
    )
}

/** Thresholds are contract constants pending calibration (spec §9.4). */
const val TUTOR_TOOL_ROUTE_CONFIDENCE_THRESHOLD = 0.45
private const val ROUTE_CONFIDENCE_THRESHOLD = TUTOR_TOOL_ROUTE_CONFIDENCE_THRESHOLD

/** Upper bound on simultaneously declared tools for one dispatch (spec §2 core five). */
const val MAX_TOOL_DECLARATIONS = 5
