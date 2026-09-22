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
    /** 写工具 T6：模型提语义证据、本地门控落库（spec §5）；T4 需学生明确命令，当前阶段仅声明不启用。 */
    NOTEBOOK_WRITE,
    MASTERY_UPDATE,
}

/**
 * Model-judged evidence direction for a MASTERY_UPDATE call. The model
 * decides the semantic sign; the local gate and weight table decide
 * everything numeric (research tutor-evidence-gate §0.1).
 */
@Serializable
enum class TutorEvidenceDirection {
    POSITIVE,
    NEGATIVE,
}

/**
 * Model-judged tier of how well the student understands the current
 * knowledge point (research tutor-evidence-gate §1: a noisy predictor, never
 * a fact — self-report of understanding is systematically overconfident).
 * The mapping to an evidence weight is local and constant (FSRS-grade
 * analogy: STRUGGLING↔Again, UNCERTAIN↔Hard, CONFIDENT↔Good,
 * MASTERED-with-behavioral-support↔Easy).
 */
@Serializable
enum class TutorUnderstandingTier {
    STRUGGLING,
    UNCERTAIN,
    CONFIDENT,
    MASTERED,
}

/**
 * Model-judged difficulty tier of the current problem. Advisory only —
 * audited with the evidence, never enters the forgetting curve.
 */
@Serializable
enum class TutorDifficultyTier {
    EASY,
    MEDIUM,
    HARD,
}

/** One model-issued tool request for the current round. */
@Serializable
data class TutorToolCall(
    val tool: TutorToolName,
    val rationale: String,
    val terms: List<String> = emptyList(),
    /**
     * Model-judged semantic direction — MASTERY_UPDATE only. The model
     * decides the sign (it is the semantic element only it can judge from
     * the dialogue); the numeric weight stays local.
     */
    val direction: TutorEvidenceDirection? = null,
    /**
     * Model-judged understanding tier — MASTERY_UPDATE only. Semantic
     * judgment; the tier→weight mapping stays local and constant.
     */
    val understanding: TutorUnderstandingTier? = null,
    /** Model-judged difficulty tier (advisory, audited with the evidence). */
    val difficultyTier: TutorDifficultyTier? = null,
    /**
     * 这一**次调用**声明锚在哪一道题（逐次题锚，两种解析路由都能表达的落点）。
     *
     * 判据与轮次声明完全相同（候选必须在派发前的菜单内、锚词必须在学生消息里逐字出现且在该题
     * 自身文本里可核对），但落点不同：轮次声明要整轮的信封才能表达，而原生 tool_calls 路由的
     * 标准形态 content=null，轮次声明无处可放；逐次锚把复述放回**两种路由都能表达**的地方。
     *
     * 2026-09-21 裁定（D6）之后它**不再是写工具准入的来源**：无题轮不再结构性拒写，写不写
     * 由模型语义判定，MASTERY_UPDATE 的准入改为代号白名单（本会话已披露集合）。此字段仍被
     * 解析（模型可能照旧复述），保留供审计与轮次绑定的旁证；不消费它做放/拒判定。
     */
    val boundQuestion: TutorRoundQuestionDeclaration? = null,
    /**
     * Model's own confidence in its semantic judgment (0..1), MASTERY_UPDATE
     * only. The local gate thresholds it against
     * [com.tingyun.smartmistakebook.core.domain.MasteryWriteGate.EVIDENCE_CONFIDENCE_THRESHOLD].
     */
    val confidence: Double = 0.8,
    /**
     * Asks for the larger result budget on this one call — MASTERY_READ only.
     * A **semantic** request, not a number: the model says "this query may need
     * more room" and the ceiling stays a local constant
     * ([TutorToolOutcome.MAX_TOOL_RESULT_CHARS_EXTENDED]). Letting the model
     * name a size would hand it control over how much learning data leaves the
     * device — the same split every other numeric decision follows (the model
     * supplies semantics, the local layer supplies numbers).
     *
     * Only MASTERY_READ may set it: it is the one read tool whose result grows
     * with the learner's history. The round-level guard that at most one such
     * call is honoured per round lives in the executor, because that is where
     * the round's other calls are visible.
     */
    val extendedResult: Boolean = false,
) {
    init {
        require(rationale.isNotBlank() && rationale.length <= MAX_TOOL_RATIONALE_CHARS) {
            "A tool call must state an anchored reason of at most $MAX_TOOL_RATIONALE_CHARS chars"
        }
        require(confidence in 0.0..1.0) { "Tool call confidence must be in 0..1" }
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
        require(!extendedResult || tool == TutorToolName.MASTERY_READ) {
            "Only MASTERY_READ may request an extended result budget"
        }
        if (tool == TutorToolName.MASTERY_UPDATE) {
            require(direction != null) {
                "A MASTERY_UPDATE call must state a model-judged evidence direction"
            }
            require(understanding != null) {
                "A MASTERY_UPDATE call must state a model-judged understanding tier"
            }
        } else {
            require(direction == null && understanding == null) {
                "Only MASTERY_UPDATE carries a direction or understanding tier"
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
            maxChars = MAX_TOOL_RESULT_CHARS_EXTENDED,
            allowLineBreaks = true,
        )
        require(ok || errorKind != null) { "A failed tool outcome must carry an error kind" }
    }

    companion object {
        /**
         * Default result budget every tool targets. A tool that emits more must
         * have a reason recorded on it (today only `MASTERY_READ` does, and only
         * for a call that asked for the extended budget).
         */
        const val MAX_TOOL_RESULT_CHARS = 2_000

        /**
         * Absolute ceiling for one outcome, applied by [TutorToolOutcome]'s own
         * validation. It is the larger value because a model-requested extended
         * read is legitimate here; the pipeline still needs a hard bound so a
         * mis-sized result can never silently inflate the next prompt.
         *
         * Sized so a full subject list stays inside it with room to spare: a
         * subject carries on the order of a few hundred knowledge nodes *with*
         * mastery state, and one compact line each fits well under this.
         *
         * The total-across-a-round budget is a separate, implemented concern:
         * [TutorToolRoundResult.MAX_TOOL_ROUND_RESULT_CHARS] caps a round at
         * 4k — spec §3.1's ≤4k/round — plus
         * [TutorToolRoundResult.EXTENDED_ROUND_RESULT_INCREMENT_CHARS] when the
         * round spent its single extended call. [tutorToolRoundResult] applies
         * the cap; the executor honours at most one extended call per round.
         */
        const val MAX_TOOL_RESULT_CHARS_EXTENDED = 6_000
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
        const val MAX_TOOL_ROUNDS = 5

        /**
         * Total characters of tool results one round may add to the next prompt
         * (spec model-intent-routing §3.1).
         *
         * The spec's 4k was written for a round of at most two calls, and the
         * implementation allows three — so this is deliberately a cap on the
         * *sum*, not a restatement of the per-call budget: three calls at the
         * default 2k would otherwise add 6k with nothing bounding the total.
         */
        const val MAX_TOOL_ROUND_RESULT_CHARS = 4_000

        /**
         * Extra round budget granted when the round contains a call that asked
         * for the extended result budget. Without this the 4k round cap would
         * make a 6k single result unreachable, which would quietly cancel the
         * extended budget the model is allowed to request.
         */
        const val EXTENDED_ROUND_RESULT_INCREMENT_CHARS = 4_000
    }
}

/**
 * Builds the round result the next dispatch will carry, applying the round's
 * result budget.
 *
 * The budget decision lives here rather than in the repository that assembles
 * the round: which number applies, and how a round that asked for the extended
 * budget differs, is policy — and policy belongs where it can be tested without
 * a database. The repository keeps only the call.
 */
fun tutorToolRoundResult(
    roundOrdinal: Int,
    outcomes: List<TutorToolOutcome>,
    extendedResultUsed: Boolean,
): TutorToolRoundResult = TutorToolRoundResult(
    roundOrdinal = roundOrdinal,
    outcomes = budgetTutorToolOutcomes(
        outcomes = outcomes,
        budgetChars = TutorToolRoundResult.MAX_TOOL_ROUND_RESULT_CHARS +
            if (extendedResultUsed) {
                TutorToolRoundResult.EXTENDED_ROUND_RESULT_INCREMENT_CHARS
            } else {
                0
            },
    ),
)

/**
 * Fits a round's outcomes into [budgetChars], in order.
 *
 * In order, not proportionally: the model asked for these queries in a
 * sequence, so the earlier ones are the ones it wanted first. What does not fit
 * is still reported — a silently shortened result reads as a complete one.
 *
 * Each step first sets aside enough for every *later* outcome to at least carry
 * its "not sent" line. Without that reserve the round overshoots the very budget
 * it is enforcing: admitting that a result was dropped costs characters too.
 *
 * A squeezed outcome keeps its `ok` and `errorKind`: those describe whether the
 * tool ran, and shortening the text does not change that. Only the summary is
 * rewritten, and always to something non-blank so the outcome stays valid.
 */
fun budgetTutorToolOutcomes(
    outcomes: List<TutorToolOutcome>,
    budgetChars: Int,
): List<TutorToolOutcome> {
    val minimumRoundCost = outcomes.size * (DROPPED_OUTCOME_SUMMARY.length + 1)
    require(budgetChars >= minimumRoundCost) {
        "A tool round budget of $budgetChars chars cannot report ${outcomes.size} outcomes"
    }
    var remaining = budgetChars
    return outcomes.mapIndexed { index, outcome ->
        val summary = outcome.summaryMarkdown
        val laterReserve = (outcomes.size - index - 1) * (DROPPED_OUTCOME_SUMMARY.length + 1)
        val availableForThis = remaining - laterReserve - 1
        val truncatedFits = availableForThis >= TRUNCATED_OUTCOME_NOTE.length + MIN_TRUNCATED_PREFIX_CHARS
        val replacement = when {
            summary.length <= availableForThis -> null
            truncatedFits ->
                summary.take(availableForThis - TRUNCATED_OUTCOME_NOTE.length) + TRUNCATED_OUTCOME_NOTE
            else -> DROPPED_OUTCOME_SUMMARY
        }
        val budgetedSummary = replacement ?: summary
        remaining -= budgetedSummary.length + 1
        if (replacement == null) outcome else outcome.copy(summaryMarkdown = replacement)
    }
}

/**
 * How much of a truncated outcome's own text must survive for the truncation to
 * be worth doing at all: a two-character prefix followed by "已截断" tells the
 * model nothing it can use, so at that point the dropped-summary line is the
 * more honest answer.
 */
private const val MIN_TRUNCATED_PREFIX_CHARS = 12

/**
 * The model-visible marker appended to a result the round budget had to cut.
 * Public because it is part of the budget's observable contract — a consumer
 * that renders or asserts on tool results has to be able to recognise it
 * without re-spelling the text and drifting from it.
 */
const val TRUNCATED_OUTCOME_NOTE = "…[本轮结果预算已用尽，本条已截断]"

/**
 * The model-visible stand-in for a result the round budget could not carry at
 * all. Kept non-blank because a tool outcome's summary is required to be.
 */
const val DROPPED_OUTCOME_SUMMARY = "本轮结果预算已用尽，本条摘要未随请求发送。"

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
            setOf(TutorToolName.KNOWLEDGE_READ, TutorToolName.NOTEBOOK_READ, TutorToolName.MASTERY_READ, TutorToolName.MASTERY_UPDATE, TutorToolName.NOTEBOOK_WRITE)
        TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP -> setOf(TutorToolName.NOTEBOOK_READ)
        TutorMessageIntent.LEARNING_PROGRESS_LOOKUP -> setOf(TutorToolName.MASTERY_READ)
        TutorMessageIntent.APP_HELP_OR_SETTINGS,
        TutorMessageIntent.CASUAL_CONVERSATION,
        TutorMessageIntent.END_OR_PAUSE,
        -> emptySet()
    }
    // 写工具（MASTERY_UPDATE / NOTEBOOK_WRITE）的确认门：MASTERY_UPDATE 由本地 gate 全权
    // 决定（spec §5.2：模型提交证据+本地门控）；NOTEBOOK_WRITE 需要学生明确要求
    // （explicitActionRequest，spec §9.7），模型自报"学生说收"才放行——本地无法独立验证，
    // 靠授权矩阵 + 学生明确命令双重兜底。未明确确认的写工具申请一律不放行。
    val allowed = byIntent.intersect(declaredTools).filterTo(mutableSetOf()) { tool ->
        when (tool) {
            TutorToolName.NOTEBOOK_WRITE -> decision.explicitActionRequest
            // MASTERY_UPDATE 不需要 explicitActionRequest（本地 gate 做主）。
            else -> true
        }
    }
    return TutorToolAuthorization(
        allowedTools = allowed,
        routeEligible = true,
        reason = if (allowed.isEmpty()) {
            if (TutorToolName.NOTEBOOK_WRITE in byIntent && !decision.explicitActionRequest) {
                "NOTEBOOK_WRITE requires an explicit student request"
            } else {
                "intent ${decision.intent} has no declared tools"
            }
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
