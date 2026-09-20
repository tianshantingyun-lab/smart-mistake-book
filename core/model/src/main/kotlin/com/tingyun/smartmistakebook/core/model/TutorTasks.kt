package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Coarse, privacy-preserving evidence disclosed for one tutor plan. */
@Serializable
enum class TutorEvidenceLevel {
    UNKNOWN,
    LEARNING,
    MASTERED,
    CONFLICTED,
    STALE,
}

/** Coarse time buckets avoid disclosing raw study timestamps to an external provider. */
@Serializable
enum class TutorEvidenceRecency {
    WITHIN_7_DAYS,
    WITHIN_30_DAYS,
    WITHIN_90_DAYS,
    OLDER,
    UNKNOWN,
    ;

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /**
         * Buckets one event timestamp against the moment of disclosure.
         *
         * Lives here rather than next to a caller because two producers render
         * this vocabulary into the same prompt: the tutor request builder (the
         * `relevantLearningEvidence` block) and the `MASTERY_READ` tool result.
         * Two copies of the bucket boundaries would let the same fact read
         * "WITHIN_7_DAYS" in one block and "WITHIN_30_DAYS" in the other, and
         * nothing would catch it.
         *
         * A missing timestamp, or one in the future (clock rollback), is
         * UNKNOWN rather than a guess.
         */
        fun of(eventAtEpochMillis: Long?, atEpochMillis: Long): TutorEvidenceRecency {
            val eventAt = eventAtEpochMillis ?: return UNKNOWN
            if (eventAt > atEpochMillis) return UNKNOWN
            val ageMillis = atEpochMillis - eventAt
            return when {
                ageMillis <= 7L * MILLIS_PER_DAY -> WITHIN_7_DAYS
                ageMillis <= 30L * MILLIS_PER_DAY -> WITHIN_30_DAYS
                ageMillis <= 90L * MILLIS_PER_DAY -> WITHIN_90_DAYS
                else -> OLDER
            }
        }
    }
}

@Serializable
enum class TutorQuestionReviewStatus {
    DUE,
    SCHEDULED,
    STALE,
}

/** Bounded projection of this exact question's local learning facts. */
@Serializable
data class TutorQuestionLearningEvidence(
    val independentRecallCount: Int,
    val assistedRecallCount: Int,
    val retrievalFailureCount: Int,
    val answerRevealCount: Int,
    val retentionEstimate: Double? = null,
    val reviewStatus: TutorQuestionReviewStatus,
) {
    init {
        require(
            independentRecallCount >= 0 && assistedRecallCount >= 0 &&
                retrievalFailureCount >= 0 && answerRevealCount >= 0,
        ) { "Tutor question evidence counts must not be negative" }
        require(retentionEstimate == null || retentionEstimate.isFinite() && retentionEstimate in 0.0..1.0) {
            "Tutor question retention must be between zero and one"
        }
        require((reviewStatus == TutorQuestionReviewStatus.STALE) == (retentionEstimate == null)) {
            "Only stale question evidence omits the retention estimate"
        }
    }
}

/** Deterministic summary of earlier cycles; raw chat history stays local. */
@Serializable
data class TutorConversationMemory(
    val completedCycleCount: Int,
    val answeredTurnCount: Int,
    val correctChoiceCount: Int,
    val lastFeedbackMarkdown: String? = null,
    val lastRequestedMove: TutorMoveType? = null,
    val solutionWasRevealed: Boolean = false,
) {
    init {
        require(completedCycleCount > 0)
        require(answeredTurnCount >= 0)
        require(correctChoiceCount in 0..answeredTurnCount)
        if (answeredTurnCount == 0) {
            require(lastFeedbackMarkdown == null) {
                "Action-only tutor memory cannot contain choice feedback"
            }
            require(lastRequestedMove != null || solutionWasRevealed) {
                "Action-only tutor memory must retain a direct teaching action"
            }
        } else {
            requireNotNull(lastFeedbackMarkdown).requireTutorMarkdown(
                "Tutor conversation memory feedback",
                TutorTurnPlan.MAX_FEEDBACK_CHARS,
            )
        }
    }
}

@Serializable
data class TutorKnowledgeEvidence(
    val knowledgeNodeId: String,
    val displayName: String,
    val level: TutorEvidenceLevel,
    val independentCorrectLowerBound: Double,
    val evidenceMass: Double = 0.0,
    val independentCorrectObservationCount: Int = 0,
    val latestEvidenceRecency: TutorEvidenceRecency = TutorEvidenceRecency.UNKNOWN,
    val latestIndependentErrorRecency: TutorEvidenceRecency = TutorEvidenceRecency.UNKNOWN,
) {
    init {
        knowledgeNodeId.requireSafeModelText(
            "Tutor evidence id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        displayName.requireSafeModelText("Tutor evidence label", MAX_LABEL_CHARS, false)
        require(independentCorrectLowerBound.isFinite() && independentCorrectLowerBound in 0.0..1.0) {
            "Tutor evidence lower bound must be between zero and one"
        }
        require(evidenceMass.isFinite() && evidenceMass in 0.0..MAX_DISCLOSED_EVIDENCE_MASS) {
            "Tutor evidence mass is outside the disclosure budget"
        }
        require(independentCorrectObservationCount in 0..MAX_DISCLOSED_OBSERVATIONS) {
            "Tutor evidence observation count is outside the disclosure budget"
        }
    }

    companion object {
        const val MAX_LABEL_CHARS = 96
        const val MAX_DISCLOSED_EVIDENCE_MASS = 100.0
        const val MAX_DISCLOSED_OBSERVATIONS = 100
    }
}

@Serializable
enum class TutorMoveType {
    DEEPEN_REASONING,
    TARGET_MISCONCEPTION,
    CHANGE_REPRESENTATION,
    CONNECT_KNOWLEDGE,
    REVEAL_SOLUTION,
}

/** One bounded, student-authored branch in the durable tutoring conversation. */
@Serializable
data class TutorTurnHistoryEntry(
    val turnOrdinal: Int,
    val diagnosticStemMarkdown: String,
    val selectedChoiceMarkdown: String,
    val selectionWasCorrect: Boolean,
    val feedbackMarkdown: String,
    val requestedMove: TutorMoveType,
) {
    init {
        require(turnOrdinal > 0) { "Tutor history ordinal must be positive" }
        diagnosticStemMarkdown.requireTutorMarkdown("Tutor history stem", TutorTurnPlan.MAX_STEM_CHARS)
        selectedChoiceMarkdown.requireTutorMarkdown("Tutor history choice", TutorTurnPlan.MAX_CHOICE_CHARS)
        feedbackMarkdown.requireTutorMarkdown("Tutor history feedback", TutorTurnPlan.MAX_FEEDBACK_CHARS)
        require(requestedMove != TutorMoveType.REVEAL_SOLUTION) {
            "Revealing the stored solution does not create another model turn"
        }
    }
}

@Serializable
data class TutorSuggestedMove(
    val id: String,
    val label: String,
    val type: TutorMoveType,
) {
    init {
        id.requireSafeModelText("Tutor move id", ModelTaskRequest.MAX_ID_CHARS, false)
        label.requireSafeModelText("Tutor move label", MAX_LABEL_CHARS, false)
        StudentFacingLanguagePolicy.requirePlainLanguage(label, "Tutor move label")
    }

    companion object {
        const val MAX_LABEL_CHARS = 32
    }
}

/**
 * A model receives only the confirmed question and a small relevance candidate set. It never
 * receives the full learning ledger and cannot mutate mastery state.
 */
@Serializable
@SerialName("tutor_plan")
data class TutorPlanInput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val subject: String,
    val questionDocument: QuestionDocument,
    val relevantLearningEvidence: List<TutorKnowledgeEvidence>,
    val projectionIsCurrent: Boolean,
    val reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    val questionLearningEvidence: TutorQuestionLearningEvidence? = null,
    val cycleOrdinal: Int = 1,
    val priorConversationMemory: TutorConversationMemory? = null,
    /** Exact, bounded student messages retained from earlier cycles of this same question. */
    val priorCycleStudentMessages: List<String> = emptyList(),
    val turnOrdinal: Int = 1,
    val priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
    /** Stored model advisories (teaching focus / misconception) for this question. */
    val priorTeachingAdvisories: List<String> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.TUTOR_PLAN

    override val isAgentConsentEligible: Boolean
        get() = true

    override val subjectId: String
        get() = sessionId

    init {
        sessionId.requireSafeModelText("Tutor session id", ModelTaskRequest.MAX_ID_CHARS, false)
        require(draftRevisionNumber > 0) { "Tutor draft revision must be positive" }
        subject.requireSafeModelText("Tutor subject", MAX_SUBJECT_CHARS, false)
        require(questionDocument.blocks.isNotEmpty()) { "Tutor planning requires a confirmed question" }
        require(relevantLearningEvidence.size <= MAX_RELEVANT_EVIDENCE) {
            "Tutor planning disclosed too many learning summaries"
        }
        require(
            relevantLearningEvidence.map(TutorKnowledgeEvidence::knowledgeNodeId).distinct().size ==
                relevantLearningEvidence.size,
        ) { "Tutor learning evidence ids must be unique" }
        reviewedTeachingReferences.requireValidTutorTeachingReferences(
            subject = subject,
            label = "Tutor planning",
        )
        require(priorTeachingAdvisories.size <= TutorDebriefInput.MAX_DEBRIEF_LABELS) {
            "Tutor planning disclosed too many stored advisories"
        }
        priorTeachingAdvisories.forEach { advisory ->
            advisory.requireSafeModelText("Stored teaching advisory", 1_000, true)
        }
        require(turnOrdinal in 1..MAX_TURNS) { "Tutor turn ordinal exceeds the conversation budget" }
        require(cycleOrdinal > 0) { "Tutor cycle ordinal must be positive" }
        require((cycleOrdinal == 1) == (priorConversationMemory == null)) {
            "Only later tutor cycles may include prior conversation memory"
        }
        require(cycleOrdinal > 1 || priorCycleStudentMessages.isEmpty()) {
            "The first tutor cycle cannot contain messages from an earlier cycle"
        }
        require(priorCycleStudentMessages.size <= MAX_PRIOR_CYCLE_STUDENT_MESSAGES) {
            "Tutor planning disclosed too many earlier student messages"
        }
        priorCycleStudentMessages.forEach { message ->
            message.requireSafeModelText(
                "Earlier tutor-cycle student message",
                TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS,
                true,
            )
        }
        require(
            priorCycleStudentMessages.sumOf { message -> message.length } <=
                MAX_PRIOR_CYCLE_STUDENT_MESSAGE_CHARS,
        ) { "Earlier tutor-cycle student messages exceed their total text budget" }
        require(priorTurns.size == turnOrdinal - 1) {
            "Tutor history must contain every preceding turn exactly once"
        }
        require(priorTurns.map(TutorTurnHistoryEntry::turnOrdinal) == (1 until turnOrdinal).toList()) {
            "Tutor history ordinals must be contiguous"
        }
    }

    companion object {
        const val MAX_RELEVANT_EVIDENCE = 12

        /**
         * 讲解材料的**唯一**约束：总字符预算。**条数不设上限**。
         *
         * 此前还有一条 `MAX_TEACHING_REFERENCES = 4` 的条数上限，于是真正卡住内容的始终是它、
         * 而不是预算：内置包 10356 条材料实测 `markdownChars` 中位数 195，4 条典型材料合计
         * 约 780 字符，20000 的预算连 4% 都用不到——预算写了却从未生效，而"每节点最多 4 条"
         * 却让 649 个超配节点的材料永远送不到模型。
         *
         * 去掉条数上限后预算才开始做真实工作。取值依据（同一份实测）：
         * min 64 / p50 195 / p90 295 / p99 463 / max 1029。20000 可容纳约 103 条典型材料；
         * 单节点材料字符合计最大 18885（"光合作用与细胞呼吸过程"节点 97 条），仍在预算内。
         * 典型情形一题绑定 5 个中位节点约 3800 字符，余量充足。
         */
        const val MAX_TEACHING_REFERENCE_MARKDOWN_CHARS = 20_000
        const val MAX_SUBJECT_CHARS = 32
        const val MAX_TURNS = 4
        const val MAX_PRIOR_CYCLE_STUDENT_MESSAGES = 8
        const val MAX_PRIOR_CYCLE_STUDENT_MESSAGE_CHARS = 6_000
    }
}

/** One already-persisted student/assistant exchange for this exact confirmed question. */
@Serializable
data class TutorChatHistoryEntry(
    val studentMessage: String,
    val assistantMarkdown: String,
    val studentImageAssetRefs: List<String> = emptyList(),  // 新增：学生消息中的图片
) {
    init {
        studentMessage.requireSafeModelText(
            "Tutor chat history student message",
            TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS,
            true,
        )
        assistantMarkdown.requireTutorRespondText(
            "Tutor chat history assistant message",
            TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS,
        )
        require(studentImageAssetRefs.size <= MAX_IMAGE_ASSETS_PER_MESSAGE) {
            "Too many images in history entry (max $MAX_IMAGE_ASSETS_PER_MESSAGE)"
        }
    }

    companion object {
        const val MAX_IMAGE_ASSETS_PER_MESSAGE = 5
    }
}

/**
 * A bounded text follow-up about one confirmed question. Conversation context is display-only
 * history and never becomes mastery evidence or authority to mutate the learning ledger.
 */
@Serializable
@SerialName("tutor_respond")
data class TutorRespondInput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val subject: String,
    val questionDocument: QuestionDocument,
    val relevantLearningEvidence: List<TutorKnowledgeEvidence>,
    val projectionIsCurrent: Boolean,
    val reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    val questionLearningEvidence: TutorQuestionLearningEvidence? = null,
    val responseOrdinal: Int,
    val cycleOrdinal: Int = 1,
    val turnOrdinal: Int = 1,
    val studentMessage: String,
    val visibleTutorContextMarkdown: String? = null,
    val priorMessages: List<TutorChatHistoryEntry> = emptyList(),
    /**
     * 更早轮次的确定性摘要（超出预算被挤出原样窗口的那些轮）：让模型知道前面聊过什么、
     * 已经给过什么结论，而不是让它们无声消失。为空表示没有轮次被挤出。
     */
    val priorDigest: String? = null,
    val requestedMove: TutorMoveType? = null,
    /**
     * 当前消息附带的规范资产 id（按选择顺序，最多
     * [TutorChatHistoryEntry.MAX_IMAGE_ASSETS_PER_MESSAGE] 张）。空表示纯文字。
     * 图片只随本条消息出网，历史轮次不带图。
     */
    val studentImageAssetRefs: List<String> = emptyList(),
    /** Non-empty enables the tool protocol for this dispatch (spec §3.1). */
    val toolDeclarations: List<TutorToolName> = emptyList(),
    /** Results of prior tool rounds; round 1 dispatch always leaves this empty. */
    val toolRoundResults: List<TutorToolRoundResult> = emptyList(),
    /**
     * 本轮派发前本地组好的候选菜单（显式添加的题 + 上一轮绑定的题 + 本地文本检索前 N 条），
     * 供模型判"这一轮在说哪一道（或不指任何一道）"。空表示本地没有任何候选——此时本轮
     * 必然是无题轮（[TutorRespondOutput.boundQuestion] 不可能通过本地校验）。
     */
    val boundQuestionCandidates: List<RelatedProblemCandidate> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.TUTOR_RESPOND

    override val isAgentConsentEligible: Boolean
        get() = true

    override val subjectId: String
        get() = sessionId

    /**
     * 消息带图时要求 provider 具备图片输入能力。Respond 属于 agent-eligible，
     * 所以这一位会同时驱动出网许可判定（`requiresImageInput`）与图片读取计划；
     * capture 题图走的正是同一条全局同意通道，因此不需要逐次披露清单。
     */
    override val requestsImageBytes: Boolean
        get() = studentImageAssetRefs.isNotEmpty()

    init {
        sessionId.requireSafeModelText("Tutor response session id", ModelTaskRequest.MAX_ID_CHARS, false)
        require(draftRevisionNumber > 0) { "Tutor response draft revision must be positive" }
        subject.requireSafeModelText("Tutor response subject", TutorPlanInput.MAX_SUBJECT_CHARS, false)
        require(questionDocument.blocks.isNotEmpty()) {
            "Tutor response requires the confirmed question"
        }
        require(relevantLearningEvidence.size <= TutorPlanInput.MAX_RELEVANT_EVIDENCE) {
            "Tutor response disclosed too many learning summaries"
        }
        require(
            relevantLearningEvidence.map(TutorKnowledgeEvidence::knowledgeNodeId).distinct().size ==
                relevantLearningEvidence.size,
        ) { "Tutor response learning evidence ids must be unique" }
        reviewedTeachingReferences.requireValidTutorTeachingReferences(
            subject = subject,
            label = "Tutor response",
        )
        require(responseOrdinal > 0) { "Tutor response ordinal must be positive" }
        require(cycleOrdinal > 0) { "Tutor response cycle ordinal must be positive" }
        require(turnOrdinal in 1..TutorPlanInput.MAX_TURNS) {
            "Tutor response turn ordinal exceeds the conversation budget"
        }
        studentMessage.requireSafeModelText(
            "Tutor response student message",
            MAX_STUDENT_MESSAGE_CHARS,
            true,
        )
        visibleTutorContextMarkdown?.requireTutorRespondText(
            "Tutor visible context",
            MAX_VISIBLE_CONTEXT_CHARS,
        )
        require(priorMessages.size <= MAX_PRIOR_MESSAGES) {
            "Tutor response contains too many prior chat messages"
        }
        require(
            priorMessages.sumOf { message ->
                message.studentMessage.length + message.assistantMarkdown.length
            } <= MAX_PRIOR_MESSAGE_CHARS,
        ) { "Tutor response prior chat exceeds its total text budget" }
        priorDigest?.requireSafeModelText(
            "Tutor response prior digest",
            MAX_PRIOR_DIGEST_CHARS,
            true,
        )
        require(studentImageAssetRefs.size <= TutorChatHistoryEntry.MAX_IMAGE_ASSETS_PER_MESSAGE) {
            "Too many images in current message (max ${TutorChatHistoryEntry.MAX_IMAGE_ASSETS_PER_MESSAGE})"
        }
        require(toolDeclarations.size <= MAX_TOOL_DECLARATIONS) {
            "Tutor response declares too many tools"
        }
        require(toolDeclarations.distinct().size == toolDeclarations.size) {
            "Tutor response tool declarations must be distinct"
        }
        require(toolRoundResults.size <= TutorToolRoundResult.MAX_TOOL_ROUNDS) {
            "Tutor response carries too many tool rounds"
        }
        require(toolRoundResults.isEmpty() || toolDeclarations.isNotEmpty()) {
            "Tutor response tool rounds require declared tools"
        }
        require(
            toolRoundResults.map(TutorToolRoundResult::roundOrdinal) ==
                (1..toolRoundResults.size).toList(),
        ) {
            "Tutor response tool round ordinals must be sequential from one"
        }
        require(boundQuestionCandidates.size <= MAX_BOUND_QUESTION_CANDIDATES) {
            "Tutor response carries too many bound-question candidates"
        }
        require(
            boundQuestionCandidates.map { it.problemId to it.problemRevisionId }.distinct().size ==
                boundQuestionCandidates.size,
        ) { "Tutor response bound-question candidates must be unique per revision" }
    }

    companion object {
        const val MAX_STUDENT_MESSAGE_CHARS = 1_200
        const val MAX_VISIBLE_CONTEXT_CHARS = 12_000
        const val MAX_PRIOR_MESSAGES = 8
        const val MAX_PRIOR_MESSAGE_CHARS = 24_000

        /** 候选菜单上限：与 `ProblemOrganizationInput.MAX_RELATION_CANDIDATES` 同一口径。 */
        const val MAX_BOUND_QUESTION_CANDIDATES = 8

        /**
         * 早期对话摘要的长度上限。摘要是"要点提示"，不该反过来挤占原样保留的轮次，
         * 所以它远小于原样窗口；生成端（`TutorChatDigest`）另有一条更紧的上限。
         */
        const val MAX_PRIOR_DIGEST_CHARS = 2_000
    }
}

/**
 * 模型对"这一轮在说哪一道题"的声明。**声明本身不授予任何权限**：本地还要核两条——
 * 候选必须在派发前的菜单内（按 problemId + problemRevisionId 精确匹配），并且
 * [anchorTerms] 每一条都要在学生这一轮的消息里逐字出现、且至少一条能在该题自身
 * （标题或题面）里找到。核不过就是无题轮。
 *
 * [anchorTerms] 沿用 `TutorIntentAuthority.actionIsBoundTo`/`lookupTerms` 的逐字锚纪律：
 * 模型给出词、本地逐字比对，模型不能只给一个"我觉得是这道"的判断。
 *
 * @property problemId 声明指向的题目 id；必须在本地候选菜单内。
 * @property problemRevisionId 声明指向的题面修订 id；同一道题的不同修订不算命中。
 * @property anchorTerms 学生消息里逐字出现、且能在该题自身文本里找到的词，至少一条。
 */
@Serializable
data class TutorRoundQuestionDeclaration(
    val problemId: String,
    val problemRevisionId: String,
    val anchorTerms: List<String> = emptyList(),
) {
    init {
        problemId.requireSafeModelText(
            "Tutor round question id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        problemRevisionId.requireSafeModelText(
            "Tutor round question revision",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(anchorTerms.size <= MAX_ANCHOR_TERMS) {
            "Tutor round question declaration carries too many anchor terms"
        }
        anchorTerms.forEach { term ->
            term.requireSafeModelText("Tutor round question anchor", MAX_ANCHOR_TERM_CHARS, false)
        }
    }

    companion object {
        const val MAX_ANCHOR_TERMS = 4
        const val MAX_ANCHOR_TERM_CHARS = 64
    }
}

/**
 * Local, fail-closed answer authority. A model declaration never grants this permission.
 *
 * Free text must contain an unambiguous request; ambiguous wording is left unanswered until the
 * student asks clearly or taps the dedicated reveal control.
 */
fun TutorRespondInput.studentAuthorizedSolutionRequest(): Boolean {
    if (requestedMove == TutorMoveType.REVEAL_SOLUTION) return true
    val normalized = studentMessage.lowercase()
    return EXPLICIT_SOLUTION_REQUEST_MARKERS.any(normalized::contains) &&
        SOLUTION_REQUEST_NEGATIONS.none(normalized::contains)
}

/**
 * Shared defense-in-depth boundary for validation, rendering, exposure recording, and history.
 *
 * 除五项身份精确匹配之外，还要求**本轮确实有绑定题**（[boundQuestion] 非 null，即那一轮的
 * 声明通过了本地两条校验）：无题轮永不产生答案暴露记录——暴露记录是"学生看过这道题的答案"
 * 的证据，锚不到题就没有主人，写进去之后任何按题查询都会错认。
 */
fun TutorRespondOutput.canExposeSolutionFor(input: TutorRespondInput): Boolean =
    boundQuestion != null &&
        solutionRevealed &&
        input.studentAuthorizedSolutionRequest() &&
        sessionId == input.sessionId &&
        draftRevisionNumber == input.draftRevisionNumber &&
        questionDocumentId == input.questionDocument.id &&
        responseOrdinal == input.responseOrdinal &&
        cycleOrdinal == input.cycleOrdinal &&
        turnOrdinal == input.turnOrdinal

private val EXPLICIT_SOLUTION_REQUEST_MARKERS = setOf(
    "给我答案",
    "请给答案",
    "直接给答案",
    "把答案给我",
    "告诉我答案",
    "答案告诉我",
    "说出答案",
    "公布答案",
    "我想看答案",
    "我要看答案",
    "让我看答案",
    "直接看答案",
    "答案是什么",
    "答案是多少",
    "答案是啥",
    "给我完整答案",
    "给我完整解法",
    "看完整解法",
    "给我完整过程",
    "写出完整过程",
    "完整过程写出来",
    "把解题过程完整写出来",
    "看完整讲解",
    "给我完整讲解",
    "请完整讲解",
    "完整讲解一下",
    "直接告诉我",
    "给出结果",
    "直接给结果",
    "直接说结果",
    "结果是什么",
    "结果是多少",
)
private val SOLUTION_REQUEST_NEGATIONS = setOf(
    "不要答案",
    "别给答案",
    "不用给答案",
    "不要直接给答案",
    "别直接给答案",
    "不用直接给答案",
    "不直接给答案",
    "不要告诉我答案",
    "别告诉我答案",
    "先别告诉我答案",
    "不用告诉我答案",
    "不要公布答案",
    "别公布答案",
    "别说答案",
    "不说答案",
    "先不说答案",
    "答案不用说",
    "不想看答案",
    "不需要答案",
    "不看答案",
    "先不看答案",
    "不要完整解法",
    "不用完整解法",
    "不要完整讲解",
    "不用完整讲解",
    "不要完整过程",
    "不用完整过程",
    "别写完整过程",
    "不要结果",
    "别给结果",
    "不用给结果",
    "先不要结果",
    "结果不用说",
    "不要直接告诉我",
)

/** Model-authored content. This is intentionally not a [VerifiedTeachingArtifact]. */
@Serializable
data class TutorTurnPlan(
    val openingMarkdown: String,
    /** Optional interaction about the confirmed question; explanation-only turns omit it. */
    val diagnosticItem: TutorAssessmentItem? = null,
    /** Optional single local-rendered scene; the complete Markdown solution remains the fallback. */
    val visualScene: TutorVisualScene? = null,
    /** Optional asynchronous v2 visual request; text remains immediately usable without it. */
    val visualRequest: TutorVisualGenerationRequest? = null,
    val solutionMarkdown: String,
    val alternateMethodMarkdown: String,
    val difficultyReasonMarkdown: String,
    val targetedEvidenceLabels: List<String>,
    val inferredKnowledgeLabels: List<String>,
    val suggestedMoves: List<TutorSuggestedMove> = emptyList(),
    /** Optional student-visible reasoning trace; folded by default, never re-fed to the model. */
    val thinkingMarkdown: String? = null,
) {
    init {
        require(visualScene == null || visualRequest == null) {
            "A tutor turn cannot return a legacy scene and an asynchronous visual request together"
        }
        openingMarkdown.requireTutorMarkdown("Tutor opening", MAX_OPENING_CHARS)
        solutionMarkdown.requireTutorMarkdown("Tutor solution", MAX_SOLUTION_CHARS)
        alternateMethodMarkdown.requireTutorMarkdown("Tutor alternate method", MAX_SOLUTION_CHARS)
        difficultyReasonMarkdown.requireTutorMarkdown("Tutor difficulty reason", MAX_REASON_CHARS)
        thinkingMarkdown.requireThinkingMarkdown("Tutor thinking")
        diagnosticItem?.let { item ->
            require(item.choices.size <= MAX_INTERACTION_CHOICES) {
                "A tutor interaction must stay within the bounded choice count"
            }
            item.stemMarkdown.requireTutorMarkdown("Tutor diagnostic stem", MAX_STEM_CHARS)
            item.promptMarkdown?.requireTutorMarkdown("Tutor diagnostic prompt", MAX_REASON_CHARS)
            item.choices.forEach { choice ->
                choice.markdown.requireTutorMarkdown("Tutor diagnostic choice", MAX_CHOICE_CHARS)
                require(!choice.feedbackMarkdown.isNullOrBlank()) {
                    "Every tutor diagnostic choice needs targeted feedback"
                }
                choice.feedbackMarkdown.requireTutorMarkdown("Tutor choice feedback", MAX_FEEDBACK_CHARS)
            }
        }
        require(targetedEvidenceLabels.size <= TutorPlanInput.MAX_RELEVANT_EVIDENCE) {
            "Tutor plan targeted too many evidence labels"
        }
        require(inferredKnowledgeLabels.size in 1..MAX_INFERRED_LABELS) {
            "Tutor plan needs a bounded knowledge classification"
        }
        (targetedEvidenceLabels + inferredKnowledgeLabels).forEach { label ->
            label.requireSafeModelText("Tutor knowledge label", TutorKnowledgeEvidence.MAX_LABEL_CHARS, false)
            StudentFacingLanguagePolicy.requirePlainLanguage(label, "Tutor knowledge label")
        }
        require(targetedEvidenceLabels.distinct().size == targetedEvidenceLabels.size)
        require(inferredKnowledgeLabels.distinct().size == inferredKnowledgeLabels.size)
        require(openingMarkdown != solutionMarkdown) {
            "Tutor opening must not reveal the complete solution"
        }
        require(alternateMethodMarkdown != solutionMarkdown) {
            "An alternate method must not merely duplicate the main solution"
        }
        require(suggestedMoves.size <= MAX_SUGGESTED_MOVES) {
            "A tutor turn may expose at most three contextual next moves"
        }
        require(suggestedMoves.map(TutorSuggestedMove::id).distinct().size == suggestedMoves.size) {
            "Tutor move ids must be unique"
        }
        require(suggestedMoves.map(TutorSuggestedMove::type).distinct().size == suggestedMoves.size) {
            "Tutor move types must be unique"
        }
        require(suggestedMoves.count { it.type == TutorMoveType.REVEAL_SOLUTION } <= 1) {
            "A tutor turn may expose at most one explicit solution reveal"
        }
    }

    companion object {
        const val MAX_OPENING_CHARS = 2_000
        const val MAX_STEM_CHARS = 2_000
        const val MAX_CHOICE_CHARS = 800
        const val MAX_FEEDBACK_CHARS = 2_000
        const val MAX_SOLUTION_CHARS = 12_000
        const val MAX_REASON_CHARS = 1_000
        const val MAX_INFERRED_LABELS = 8
        const val MAX_INTERACTION_CHOICES = 5
        const val MAX_SUGGESTED_MOVES = 3
        const val MAX_THINKING_CHARS = 4_000
    }
}

@Serializable
@SerialName("tutor_plan_output")
data class TutorPlanOutput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val questionDocumentId: String,
    val plan: TutorTurnPlan,
    val modelVersion: String,
    val cycleOrdinal: Int = 1,
    val turnOrdinal: Int = 1,
    val attachedImages: List<AttachedImage> = emptyList(),
) : ModelTaskOutput {
    init {
        sessionId.requireSafeModelText("Tutor output session id", ModelTaskRequest.MAX_ID_CHARS, false)
        require(draftRevisionNumber > 0) { "Tutor output draft revision must be positive" }
        questionDocumentId.requireSafeModelText(
            "Tutor output question document id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        modelVersion.requireSafeModelText("Tutor model version", MAX_MODEL_VERSION_CHARS, false)
        require(attachedImages.size <= AttachedImage.MAX_ATTACHED_IMAGES) {
            "A tutor plan may attach at most ${AttachedImage.MAX_ATTACHED_IMAGES} figures"
        }
        require(cycleOrdinal > 0) { "Tutor output cycle ordinal must be positive" }
        require(turnOrdinal in 1..TutorPlanInput.MAX_TURNS) {
            "Tutor output turn ordinal exceeds the conversation budget"
        }
    }
}

/** Persistable model-authored reply to one bounded current-question student message. */
@Serializable
@SerialName("tutor_respond_output")
data class TutorRespondOutput(
    val sessionId: String,
    val draftRevisionNumber: Int,
    val questionDocumentId: String,
    val responseOrdinal: Int,
    val cycleOrdinal: Int = 1,
    val turnOrdinal: Int = 1,
    val messageMarkdown: String,
    /** True only when this exact reply displays the current question's answer or full solution. */
    val solutionRevealed: Boolean = false,
    val visualScene: TutorVisualScene? = null,
    val visualRequest: TutorVisualGenerationRequest? = null,
    val suggestedMoves: List<TutorSuggestedMove> = emptyList(),
    val intentDecision: TutorIntentDecision = TutorIntentDecision.ambiguousDefault(),
    /** Optional student-visible reasoning trace; folded by default, never re-fed to the model. */
    val thinkingMarkdown: String? = null,
    /** Optional locally-rendered figures the model asked for; drawn after the body, never in markdown. */
    val attachedImages: List<AttachedImage> = emptyList(),
    /**
     * **本轮真正绑定的题**；null 表示无题轮。
     *
     * 只在解析层写入：模型声明先经 `TutorRoundQuestionBindingPolicy.resolve`（候选必须在派发前
     * 的菜单内、锚词必须逐字可核对），核过才落到这里，核不过就是 null。唯一的生产者是
     * `OpenAiModelResponseParsers.toTutorRespond`，所以下游（门控、暴露、落库、渲染）可以把它
     * 当作"已校验的本轮绑定"读，不必各自再判一次。
     */
    val boundQuestion: TutorRoundQuestionDeclaration? = null,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        require(visualScene == null || visualRequest == null) {
            "A tutor response cannot return a legacy scene and an asynchronous visual request together"
        }
        sessionId.requireSafeModelText("Tutor response output session id", ModelTaskRequest.MAX_ID_CHARS, false)
        require(draftRevisionNumber > 0) {
            "Tutor response output draft revision must be positive"
        }
        questionDocumentId.requireSafeModelText(
            "Tutor response output question document id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(responseOrdinal > 0) { "Tutor response output ordinal must be positive" }
        require(cycleOrdinal > 0) { "Tutor response output cycle ordinal must be positive" }
        require(turnOrdinal in 1..TutorPlanInput.MAX_TURNS) {
            "Tutor response output turn ordinal exceeds the conversation budget"
        }
        messageMarkdown.requireTutorRespondText(
            "Tutor response message",
            MAX_MESSAGE_MARKDOWN_CHARS,
        )
        thinkingMarkdown.requireThinkingMarkdown("Tutor thinking")
        require(attachedImages.size <= AttachedImage.MAX_ATTACHED_IMAGES) {
            "A tutor reply may attach at most ${AttachedImage.MAX_ATTACHED_IMAGES} figures"
        }
        require(suggestedMoves.size <= TutorTurnPlan.MAX_SUGGESTED_MOVES) {
            "A tutor response may expose at most three contextual next moves"
        }
        require(suggestedMoves.map(TutorSuggestedMove::id).distinct().size == suggestedMoves.size) {
            "Tutor response move ids must be unique"
        }
        require(suggestedMoves.map(TutorSuggestedMove::type).distinct().size == suggestedMoves.size) {
            "Tutor response move types must be unique"
        }
        modelVersion.requireSafeModelText("Tutor response model version", MAX_MODEL_VERSION_CHARS, false)
    }

    companion object {
        const val MAX_MESSAGE_MARKDOWN_CHARS = 12_000
    }
}

/** The locally-rendered figure kinds a tutor reply may ask for. */
@Serializable
enum class AttachedImageKind {
    /** Redraw the current question's problem sheet cleanly (handwriting removed); source is auto-resolved. */
    REDRAW_PROBLEM,
    /** Generate a worked-solution / process figure described by the model (text-to-image). */
    GENERATE_PROCESS,
}

/**
 * A model-authored request for one figure to attach to a tutor reply. This is a
 * *request* (the model describes what it wants), not a persisted reference: the
 * local image generator redraws/generates it, persists it as a canonical asset,
 * and the reply renders it via a local-only image component.
 *
 * The description is free prose describing the figure; it carries no URL, no
 * base64, no pixel/color directives. A REDRAW_PROBLEM seeds from the current
 * question's sheet (auto-resolved, never model-supplied); a GENERATE_PROCESS is
 * rendered from prose only. Rendering stays local — the model never emits a URI,
 * so no remote-fetch path is introduced.
 */
@Serializable
data class AttachedImage(
    val imageId: String,
    val kind: AttachedImageKind,
    val description: String,
    val accessibilityText: String = "",
) {
    init {
        imageId.requireSafeModelText("Attached image id", MAX_ATTACHED_IMAGE_ID_CHARS, false)
        require(description.isNotBlank() && description.length <= MAX_ATTACHED_DESC_CHARS) {
            "Attached image description must be present and bounded"
        }
        description.requireTutorMarkdown("Attached image description", MAX_ATTACHED_DESC_CHARS)
        accessibilityText
            .takeIf { it.isNotBlank() }
            ?.requireTutorMarkdown("Attached image accessibility", MAX_ACCESSIBILITY_TEXT_CHARS)
    }

    companion object {
        const val MAX_ATTACHED_DESC_CHARS = 2_000
        const val MAX_ACCESSIBILITY_TEXT_CHARS = 512
        const val MAX_ATTACHED_IMAGE_ID_CHARS = 128
        const val MAX_ATTACHED_IMAGES = 6
    }
}

internal fun String.requireTutorMarkdown(label: String, maxChars: Int) {
    requireSafeModelText(label, maxChars, true)
    StudentFacingLanguagePolicy.requirePlainLanguage(this, label)
    val normalized = lowercase()
    require("<script" !in normalized && "javascript:" !in normalized) {
        "$label contains active content"
    }
}

/** Optional student-visible reasoning trace on a tutor output; folded by default in the UI. */
internal fun String?.requireThinkingMarkdown(label: String) {
    if (this == null) return
    requireTutorMarkdown(label, TutorTurnPlan.MAX_THINKING_CHARS)
}

private fun String.requireTutorRespondText(label: String, maxChars: Int) {
    requireTutorSceneText(label, maxChars, true)
}

internal fun requireTutorSceneHeader(
    sceneId: String,
    title: String,
    schemaVersion: Int,
    expectedSchemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) {
    sceneId.requireTutorSceneId("Tutor visual scene id")
    title.requireTutorSceneText("Tutor visual scene title", TutorVisualScene.MAX_TITLE_CHARS, false)
    require(schemaVersion == expectedSchemaVersion) {
        "Unsupported tutor visual scene schema version"
    }
}

internal fun String.requireTutorSceneId(label: String) {
    requireSafeModelText(label, ModelTaskRequest.MAX_ID_CHARS, false)
}

internal fun String.requireTutorSceneFormula(label: String) {
    requireTutorSceneText(label, TutorVisualScene.MAX_FORMULA_CHARS, false)
    require(!RestrictedFormulaText.hasUnsupportedCommand(this)) {
        "$label contains an unsupported formula command"
    }
}

internal fun String.requireTutorSceneText(label: String, maxChars: Int, allowLineBreaks: Boolean) {
    requireSafeModelText(label, maxChars, allowLineBreaks)
    StudentFacingLanguagePolicy.requirePlainLanguage(this, label)
    require(!TUTOR_SCENE_HTML.containsMatchIn(this)) { "$label contains HTML" }
    require(!TUTOR_SCENE_CODE_MARKUP.containsMatchIn(this)) { "$label contains code markup" }
    require(!TUTOR_SCENE_MARKDOWN_LINK.containsMatchIn(this)) { "$label contains a Markdown link" }
    require(!TUTOR_SCENE_REFERENCE_LINK.containsMatchIn(this)) { "$label contains a reference link" }
    require(!TUTOR_SCENE_IMAGE_MARKER.containsMatchIn(this)) { "$label contains image markup" }
    require(!SafeInlineMarkdown.BARE_URL.containsMatchIn(this)) { "$label contains a URL" }
}

internal fun requireUniqueTutorSceneIds(sceneId: String, itemIds: List<String>) {
    val allIds = listOf(sceneId) + itemIds
    require(allIds.distinct().size == allIds.size) { "Tutor visual scene ids must be unique" }
}

internal fun requireTutorSceneTextBudget(parts: List<String>) {
    require(parts.sumOf(String::length) <= TutorVisualScene.MAX_TOTAL_TEXT_CHARS) {
        "Tutor visual scene exceeds its total text budget"
    }
}

private val TUTOR_SCENE_HTML = Regex("(?is)<!--|<\\s*/?\\s*[a-z][^>]*>")
private val TUTOR_SCENE_CODE_MARKUP = Regex("`|~~~")
private val TUTOR_SCENE_MARKDOWN_LINK = Regex(
    """!?\[[^\r\n]{0,256}]\s*\([^\r\n)]{0,2048}\)""",
)
private val TUTOR_SCENE_REFERENCE_LINK = Regex(
    """\[[^\r\n]{1,256}]\s*\[[^\r\n]{0,256}]""",
)
private val TUTOR_SCENE_IMAGE_MARKER = Regex("!\\s*\\[")

/**
 * Silent post-session debrief (three-store closed loop): after a saved
 * mistake tutoring visit the model summarizes the misconception and the
 * teaching focus it just covered. The output lands in the mastery
 * database's advisory layer (llm_teaching_advisory) - never in learning
 * evidence. Trigger is fully silent (user decision): no prompt, no badge.
 */
@Serializable
@SerialName("tutor_debrief_input")
data class TutorDebriefInput(
    val sessionId: String,
    val practiceUnitId: String,
    val subject: String,
    /** The confirmed question stem the session was about (bounded). */
    val questionStemMarkdown: String,
    /** Bounded transcript of this visit's tutor turns (student-safe text). */
    val transcriptMarkdown: String,
    /** Knowledge labels the session's plan already named. */
    val knowledgeLabels: List<String> = emptyList(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.LEARNING_SUMMARIZE

    override val subjectId: String
        get() = sessionId

    init {
        sessionId.requireSafeModelText("Tutor debrief session id", ModelTaskRequest.MAX_ID_CHARS, false)
        practiceUnitId.requireSafeModelText("Tutor debrief practice unit", ModelTaskRequest.MAX_ID_CHARS, false)
        subject.requireSafeModelText("Tutor debrief subject", MAX_DEBRIEF_SUBJECT_CHARS, false)
        questionStemMarkdown.requireSafeModelText(
            "Tutor debrief question stem",
            MAX_DEBRIEF_STEM_CHARS,
            true,
        )
        transcriptMarkdown.requireSafeModelText(
            "Tutor debrief transcript",
            MAX_DEBRIEF_TRANSCRIPT_CHARS,
            true,
        )
        require(knowledgeLabels.size <= MAX_DEBRIEF_LABELS) {
            "Tutor debrief disclosed too many knowledge labels"
        }
        knowledgeLabels.forEach { label ->
            label.requireSafeModelText("Tutor debrief knowledge label", MAX_DEBRIEF_LABEL_CHARS, false)
        }
    }

    companion object {
        const val MAX_DEBRIEF_SUBJECT_CHARS = 32
        const val MAX_DEBRIEF_STEM_CHARS = 4_000
        const val MAX_DEBRIEF_TRANSCRIPT_CHARS = 12_000
        const val MAX_DEBRIEF_LABELS = 8
        const val MAX_DEBRIEF_LABEL_CHARS = 64
    }
}

/** The debrief's advisory payload; both fields are advisory-layer text. */
@Serializable
@SerialName("tutor_debrief_output")
data class TutorDebriefOutput(
    val sessionId: String,
    val practiceUnitId: String,
    /** The misconception this visit exposed, or null when none surfaced. */
    val misconceptionMarkdown: String?,
    /** The teaching focus labels the visit actually covered (1..8). */
    val teachingFocusLabels: List<String>,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        require(misconceptionMarkdown == null || misconceptionMarkdown.isNotBlank()) {
            "A present misconception must not be blank"
        }
        require(misconceptionMarkdown == null || misconceptionMarkdown.length <= MAX_MISCONCEPTION_CHARS) {
            "Tutor debrief misconception exceeds the budget"
        }
        require(teachingFocusLabels.isNotEmpty() && teachingFocusLabels.size <= TutorDebriefInput.MAX_DEBRIEF_LABELS) {
            "Tutor debrief must name one to eight teaching focus labels"
        }
        teachingFocusLabels.forEach { label ->
            label.requireSafeModelText("Tutor debrief focus label", TutorDebriefInput.MAX_DEBRIEF_LABEL_CHARS, false)
        }
    }

    companion object {
        const val MAX_MISCONCEPTION_CHARS = 1_000
    }
}
