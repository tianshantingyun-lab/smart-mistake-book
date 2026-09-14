package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CommitTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.port.MasteryAggregateRecord
import com.tingyun.smartmistakebook.core.database.port.SubjectMasteryRecord
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
import com.tingyun.smartmistakebook.core.domain.tutorSessionObjectiveRecord
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRecency
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolOutcome
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Deterministic evidence id for a MASTERY_UPDATE write: the same
 * (namespace = model-task requestId, tool, knowledge node) always maps to the
 * same id, so a retried write is idempotent (Room IGNORE no-ops the second
 * insert). Null namespace (direct/test callers) falls back to a unique but
 * non-idempotent nanoTime id.
 */
internal fun masteryUpdateEvidenceId(
    namespace: String?,
    tool: TutorToolName,
    knowledgeNodeId: String,
): String = namespace
    ?.let { "chat-ev:$it:${tool.name}:$knowledgeNodeId" }
    ?: "chat-ev-${System.nanoTime()}"

/**
 * `tutor_message.role` value produced by `TutorConversationDao` for the
 * student's own turn. Only these rows count as "学生原话" when verifying
 * evidence anchors — assistant text is the model's own output and cannot
 * corroborate its own claims.
 */
private const val STUDENT_MESSAGE_ROLE = "STUDENT"

/**
 * How many knowledge nodes the keyword search may resolve in one focused
 * `MASTERY_READ`. This bounds *resolution*, not the answer: a wider net only
 * wastes budget on looser matches, since every resolved node has to fit the
 * result budget anyway.
 */
private const val MASTERY_FOCUS_RESOLUTION_LIMIT = 24

/**
 * Characters held back from the result budget so the truncation note itself
 * always fits. A note that got cut off would leave the model reading a partial
 * list as if it were complete — the failure the note exists to prevent.
 */
private const val TRUNCATION_NOTE_RESERVE_CHARS = 240

/**
 * Executes locally authorized read tools for the tutor tool loop
 * (spec model-intent-routing §2/§4). Every outcome is a capped markdown
 * digest — the model never sees raw rows, and failures become error
 * outcomes instead of exceptions so the loop can continue.
 */
internal class RoomTutorToolRunner(private val port: StudyDatabasePort) {
    /** 观测面：工具环协议测试断言执行器确实被调用。 */
    var executedCallCount: Int = 0
        private set

    /** Student context the tools need; lobby sessions have no subject. */
    data class Context(
        val subject: String?,
        val learnerId: String = "learner:local",
        val conversationId: String? = null,
        /**
         * The tutor session id (bare, not the prefixed conversation anchor).
         * NOTEBOOK_WRITE needs it to resolve the capture draft: a tutor session's
         * sessionId differs from its draftId, so the write path goes
         * sessionId -> readTutorSession -> draftId -> readProblemDraft(draftId).
         */
        val tutorSessionId: String? = null,
        /**
         * 当前教学轮次（`TutorRespondInput.cycleOrdinal`）。MASTERY_UPDATE 的
         * 客观交叉核对只数**本轮**的检查题作答：`restartCycle` 会在同一题上开新一轮
         * 重教，上一轮的答错正是重教的理由，永久计入会让门不可达。
         */
        val cycleOrdinal: Int = 1,
        /**
         * Namespace for deterministic evidence ids (the model-task requestId).
         * Null keeps the legacy nanoTime fallback for direct/test callers;
         * the tool loop always supplies it so a retried MASTERY_UPDATE is
         * idempotent instead of appending duplicate evidence.
         */
        val evidenceIdNamespace: String? = null,
        /**
         * Real-time attention factor for the current tutoring session
         * (research tutor-evidence-gate §2): [MasteryWriteGate] rejects a
         * write below its floor. Defaults to fully-attentive when the UI
         * collection channel is not wired.
         */
        val attentionFactor: Double = 1.0,
        /**
         * Whether this call may use the extended result budget.
         *
         * The round-level rule ("at most one extended result per round") lives in
         * the repository, because that is the only place that can see a round's
         * sibling calls; it passes the verdict down here. Defaults to allowed so
         * a direct caller asking for the larger budget gets it — the guard exists
         * to stop three oversized results stacking into one prompt, not to make
         * the request silently do nothing.
         */
        val allowsExtendedResult: Boolean = true,
    ) {
        init {
            require(attentionFactor in 0.0..1.0) { "Attention factor must be in 0..1" }
            require(cycleOrdinal > 0) { "Tutor cycle ordinal must be positive" }
        }
    }

    suspend fun run(call: TutorToolCall, context: Context): TutorToolOutcome {
        executedCallCount += 1
        return try {
        when (call.tool) {
            TutorToolName.KNOWLEDGE_READ -> {
                val subject = context.subject
                if (subject.isNullOrBlank()) {
                    TutorToolOutcome(
                        tool = call.tool,
                        ok = false,
                        summaryMarkdown = "当前会话没有科目上下文，无法查询知识库。",
                        errorKind = "no_subject",
                    )
                } else {
                    knowledgeRead(subject, call.terms)
                }
            }
            TutorToolName.NOTEBOOK_READ -> notebookRead(call.terms)
            TutorToolName.MASTERY_READ -> masteryRead(call, context, context.allowsExtendedResult)
            TutorToolName.MASTERY_UPDATE -> masteryUpdate(call, context)
            TutorToolName.NOTEBOOK_WRITE -> notebookWrite(context)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        TutorToolOutcome(
            tool = call.tool,
            ok = false,
            summaryMarkdown = "查询没有完成，可以换个说法再试。",
            errorKind = "failed",
        )
    }
    }

    private suspend fun knowledgeRead(subject: String, terms: List<String>): TutorToolOutcome {
        val features = KnowledgeSearchFeatureExtractor.fromQuestion(terms.joinToString(" "))
        val nodes = port.readSubjectKnowledgeRecallCandidates(
            subject = subject,
            searchFeatures = features,
            limit = 5,
        )
        if (nodes.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.KNOWLEDGE_READ,
                ok = true,
                summaryMarkdown = "知识库里没有匹配的知识点。",
            )
        }
        val lines = nodes.mapIndexed { index, node ->
            val boundary = node.boundaryMarkdown?.take(80)
            "${index + 1}. ${node.displayName}${boundary?.let { "：$it" } ?: ""}"
        }
        return TutorToolOutcome(
            tool = TutorToolName.KNOWLEDGE_READ,
            ok = true,
            summaryMarkdown = "知识点候选 ${nodes.size} 个：\n${lines.joinToString("\n")}",
        )
    }

    private suspend fun notebookRead(terms: List<String>): TutorToolOutcome {
        val searchText = terms.joinToString(" ").take(120)
        val rows = port.libraryCatalogPage(
            searchText = searchText,
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "RECENTLY_CREATED",
            offset = 0,
            limit = 6,
        )
        if (rows.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_READ,
                ok = true,
                summaryMarkdown = "错题本里没有匹配的条目。",
            )
        }
        val lines = rows.mapIndexed { index, row ->
            "${index + 1}. ${row.title}（${row.subject}）"
        }
        return TutorToolOutcome(
            tool = TutorToolName.NOTEBOOK_READ,
            ok = true,
            summaryMarkdown = "错题本匹配 ${rows.size} 条：\n${lines.joinToString("\n")}",
        )
    }

    /**
     * 写错题本（T4）：把当前会话已识别、校验通过的真实题存入错题本。
     * 学生确认门已在授权层（explicitActionRequest）把关；此处只做"当前会话确有已识别题面
     * → 校验 → commit"。题面来源强锚定到 draft（学生手机上识别过的真实题），不是模型凭空
     * 生成——防臆造。commitTutorSession 内部幂等（已保存则只返回，不重复落库）。
     */
    private suspend fun notebookWrite(context: Context): TutorToolOutcome {
        val sessionId = context.tutorSessionId
        if (sessionId.isNullOrBlank()) {
            return TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_WRITE,
                ok = false,
                summaryMarkdown = "当前会话没有可保存的题目。",
                errorKind = "no_conversation",
            )
        }
        // 解析真实 draftId：tutor session 的 sessionId ≠ draftId，需先经
        // readTutorSession(sessionId) 拿记录里的 draftId，再用它读题面 draft。
        val tutorSession = port.readTutorSession(sessionId)
            ?: return TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_WRITE,
                ok = false,
                summaryMarkdown = "当前会话未建立完整讲题上下文，无法保存。",
                errorKind = "no_tutor_session",
            )
        val draftId = tutorSession.draftId
        val draft = port.readProblemDraft(draftId)
            ?: return TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_WRITE,
                ok = false,
                summaryMarkdown = "当前会话还没有识别出题目，无法保存。",
                errorKind = "reference_not_found",
            )
        val revision = draft.currentRevision
        val issues = CapturedQuestionDocumentValidator.validateForCommit(revision.questionDocument)
        if (issues.isNotEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_WRITE,
                ok = false,
                summaryMarkdown = "题目尚未准备就绪，无法保存。",
                errorKind = "not_ready",
            )
        }
        val now = System.currentTimeMillis()
        val problemId = revision.draftId
        val result = port.commitTutorSession(
            CommitTutorSessionCommand(
                sessionId = sessionId,
                commit = CommitProblemDraftCommand(
                    commandId = "commit-$draftId-$now",
                    draftId = draftId,
                    expectedRevisionNumber = revision.revisionNumber,
                    problemId = problemId,
                    problemRevisionId = "$draftId-rev-${revision.revisionNumber}",
                    practiceUnitId = draftId,
                    errorBookEntryId = "entry-$draftId-$now",
                    estimatedSeconds = 60,
                    committedAtEpochMillis = now,
                ),
            ),
        )
        return if (result.created) {
            TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_WRITE,
                ok = true,
                summaryMarkdown = "已保存到错题本。",
            )
        } else {
            TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_WRITE,
                ok = true,
                summaryMarkdown = "这道题已在错题本里。",
            )
        }
    }

    /**
     * Reads the learner's mastery of the current subject, knowledge-node grained.
     *
     * Two modes, chosen by whether the model named anything:
     * - **list** (`terms` empty): the whole subject's nodes that have evidence,
     *   weakest first — this is what closes the old `take(24)` blind slice,
     *   which was ordered by practice-unit id and therefore showed an arbitrary
     *   24 bindings regardless of how weak or relevant they were.
     * - **focus** (`terms` given): the nodes those words resolve to, each with
     *   its structured history aggregates.
     *
     * The subject is a **disclosure boundary**, not a filter of convenience:
     * the tool may only ever return the subject this session is already working
     * in, which is why the query is scoped by it rather than filtered after the
     * fact. That is also what keeps the result inside the already-disclosed
     * "bounded learning evidence" class (see the tool-loop wiring design §3.6).
     *
     * There is no row cap — the character budget is the real bound — so an
     * oversized result is truncated with a visible note telling the model how to
     * narrow (more specific terms) or to ask for the larger budget.
     */
    private suspend fun masteryRead(
        call: TutorToolCall,
        context: Context,
        allowsExtendedResult: Boolean,
    ): TutorToolOutcome {
        val subject = context.subject?.takeIf(String::isNotBlank)
        if (subject == null) {
            return TutorToolOutcome(
                tool = TutorToolName.MASTERY_READ,
                ok = false,
                summaryMarkdown = "当前会话没有科目上下文，无法读取掌握情况。",
                errorKind = "no_subject",
            )
        }
        val rows = port.readSubjectMastery(context.learnerId, subject)
        if (rows.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.MASTERY_READ,
                ok = true,
                summaryMarkdown = "还没有足够的学习记录来评估掌握情况。",
            )
        }
        val budget = if (call.extendedResult && allowsExtendedResult) {
            TutorToolOutcome.MAX_TOOL_RESULT_CHARS_EXTENDED
        } else {
            TutorToolOutcome.MAX_TOOL_RESULT_CHARS
        }
        val now = System.currentTimeMillis()
        val focusNodes = if (call.terms.isEmpty()) {
            null
        } else {
            resolveFocusNodes(subject, call.terms)
        }
        if (focusNodes != null && focusNodes.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.MASTERY_READ,
                ok = true,
                summaryMarkdown = "没有找到与${call.terms.joinToString("、")}匹配的知识点，" +
                    "可以换用材料或题面里的原词再试。",
            )
        }
        val selected = (if (focusNodes == null) rows else rows.filter { it.knowledgeNodeId in focusNodes })
            // Sorted here rather than relying on the query's ORDER BY: the query
            // has no LIMIT, so what matters is the order in force when the result
            // budget truncates — the rows dropped have to be the *least* urgent
            // ones, and a weaker node is always more urgent than a stronger one.
            .sortedWith(
                compareBy(
                    SubjectMasteryRecord::lowerBoundIndependentCorrect,
                    SubjectMasteryRecord::displayName,
                    SubjectMasteryRecord::knowledgeNodeId,
                ),
            )
        val aggregates = if (focusNodes == null || selected.isEmpty()) {
            emptyMap()
        } else {
            port.readMasteryAggregates(context.learnerId, selected.mapTo(linkedSetOf()) { it.knowledgeNodeId })
                .associateBy(MasteryAggregateRecord::knowledgeNodeId)
        }
        val unmeasured = focusNodes.orEmpty()
            .filterKeys { nodeId -> selected.none { it.knowledgeNodeId == nodeId } }
        return TutorToolOutcome(
            tool = TutorToolName.MASTERY_READ,
            ok = true,
            summaryMarkdown = renderMasteryRead(
                subject = subject,
                rows = selected,
                aggregates = aggregates,
                unmeasuredNodes = unmeasured,
                subjectNodeCount = port.countReviewableKnowledgeNodes(subject),
                focused = focusNodes != null,
                atEpochMillis = now,
                budgetChars = budget,
            ),
        )
    }

    /**
     * Resolves the model's words to knowledge nodes of the current subject via
     * the reviewed search index, keeping the display names so a node that has no
     * evidence yet can still be reported by name rather than as an opaque id.
     */
    private suspend fun resolveFocusNodes(subject: String, terms: List<String>): Map<String, String> {
        val features = KnowledgeSearchFeatureExtractor.fromQuestion(terms.joinToString(" "))
        if (features.isEmpty()) return emptyMap()
        return port.readSubjectKnowledgeRecallCandidates(
            subject = subject,
            searchFeatures = features,
            limit = MASTERY_FOCUS_RESOLUTION_LIMIT,
        ).associate { node -> node.knowledgeNodeId to node.displayName }
    }

    private fun renderMasteryRead(
        subject: String,
        rows: List<SubjectMasteryRecord>,
        aggregates: Map<String, MasteryAggregateRecord>,
        unmeasuredNodes: Map<String, String>,
        subjectNodeCount: Int,
        focused: Boolean,
        atEpochMillis: Long,
        budgetChars: Int,
    ): String {
        val body = BudgetedLines(budgetChars - TRUNCATION_NOTE_RESERVE_CHARS)
        val header = buildString {
            append("掌握情况（科目 $subject")
            if (focused) append("，聚焦查询") else append("，按最弱优先")
            append("）：")
            if (focused) {
                append("命中 ${rows.size} 个已有证据的知识点")
                if (unmeasuredNodes.isNotEmpty()) append("，另有 ${unmeasuredNodes.size} 个尚无学习证据")
            } else {
                append("${rows.size} 个知识点已有学习证据")
                val remaining = subjectNodeCount - rows.size
                if (remaining > 0) append("，该科另有 $remaining 个尚无学习证据")
            }
            append('。')
        }
        body.add(header)
        body.add("列：序号. 名称|粒度|保守掌握度|证据量|状态|最近证据|最近独立错误|绑定错题数")
        rows.forEachIndexed { index, row ->
            body.add(
                "${index + 1}. ${row.displayName}|${row.granularity}|" +
                    "${"%.2f".format(row.lowerBoundIndependentCorrect)}|" +
                    "${"%.2f".format(row.evidenceMass)}|${row.status}|" +
                    "${TutorEvidenceRecency.of(row.lastEvidenceAtEpochMillis, atEpochMillis)}|" +
                    "${TutorEvidenceRecency.of(row.lastIndependentErrorAtEpochMillis, atEpochMillis)}|" +
                    "${row.boundQuestionCount}",
            )
            aggregates[row.knowledgeNodeId]?.let { detail -> appendAggregateDetail(body, detail, atEpochMillis) }
        }
        unmeasuredNodes.entries.sortedBy { it.value }.forEach { (_, name) ->
            body.add("$name|尚无学习证据")
        }
        return body.render(
            truncationNote = "已截断：还有 ${body.droppedCount} 项未显示。" +
                "可用更具体的 terms 收窄查询，或对单次查询申请扩展预算（extendedResult=true）。",
        )
    }

    private fun appendAggregateDetail(
        body: BudgetedLines,
        detail: MasteryAggregateRecord,
        atEpochMillis: Long,
    ) {
        body.add(
            "   独立答对 ${detail.independentCorrectCount} 次（跨 " +
                "${detail.independentCorrectItemFamilyCount} 个题目族、" +
                "${detail.independentCorrectStudyDayCount} 个学习日），最近 " +
                "${TutorEvidenceRecency.of(detail.lastIndependentCorrectAtEpochMillis, atEpochMillis)}",
        )
        body.add(
            "   独立错误 ${detail.independentErrorCount} 次，最近 " +
                "${TutorEvidenceRecency.of(detail.lastIndependentErrorAtEpochMillis, atEpochMillis)}",
        )
        body.add(
            "   讲题/测验证据 接受 ${detail.acceptedModelEvidenceCount} 条、" +
                "被拒 ${detail.rejectedModelEvidenceCount} 条，最近接受 " +
                "${TutorEvidenceRecency.of(detail.lastAcceptedModelEvidenceAtEpochMillis, atEpochMillis)}",
        )
    }

    /**
     * Collects lines up to a character budget and counts what did not fit.
     *
     * The count is the point: a silently shortened list would read as the whole
     * subject, and the model would conclude there is nothing more to look at.
     */
    private class BudgetedLines(private val budgetChars: Int) {
        private val lines = mutableListOf<String>()
        private var usedChars = 0
        var droppedCount = 0
            private set

        fun add(line: String) {
            val cost = line.length + 1
            if (usedChars + cost > budgetChars) {
                droppedCount += 1
                return
            }
            lines += line
            usedChars += cost
        }

        fun render(truncationNote: String): String = buildString {
            append(lines.joinToString("\n"))
            if (droppedCount > 0) {
                append('\n')
                append(truncationNote)
            }
        }
    }


    private suspend fun masteryUpdate(call: TutorToolCall, context: Context): TutorToolOutcome {
        // 模型只给语义元素（direction/understanding/锚定 terms），weight 与
        // 一切门控由本地 MasteryWriteGate 决定——模型无数值权，无关键词猜测。
        // TutorToolCall.init 已强制 MASTERY_UPDATE 必须带 direction/understanding；
        // 此处仍按"宁漏记"防御：缺字段时拒写而非默认负向。
        val direction = call.direction
        val understanding = call.understanding
        val knowledgeNodeId = call.terms.firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        // 幂等 evidence_id：同 request 命名空间内同工具+知识点映射同 id，
        // 所以重发同一条证据既不重复落库、也不重复占用学习序列——判定在
        // ChatEvidenceDao.insertAsLedgerEvents 里、且发生在**分配序列号之前**
        // （见 masteryUpdateEvidenceId 与审计 S-1）。
        val evidenceId = masteryUpdateEvidenceId(
            namespace = context.evidenceIdNamespace,
            tool = call.tool,
            knowledgeNodeId = knowledgeNodeId,
        )
        if (direction == null || understanding == null) {
            return TutorToolOutcome(
                tool = TutorToolName.MASTERY_UPDATE,
                ok = false,
                summaryMarkdown = "这条学习证据缺少模型的方向/理解判断，未计入掌握度。",
                errorKind = "rejected:missing_semantics",
            )
        }

        // 门控数据源：三个索引支撑的精确查询（批量量级 O(log n)，不做全表拉取）。
        // - 同 KC 冷却按 learner 粒度（跨会话）：防"我懂了"开新会话绕过。
        // - 会话配额按本会话 accepted 数。
        // - learner 滚动窗配额按 learner 最近窗口内 accepted 总数（防多会话 farm）。
        val conversationId = context.conversationId
        val lastSameKcWrite = port.lastAcceptedChatEvidenceAtForKc(context.learnerId, knowledgeNodeId)
        val sameKcLastWriteAgoMillis = lastSameKcWrite?.let { (now - it).coerceAtLeast(0) }
        val acceptedInWindow = port.countAcceptedChatEvidenceSince(
            learnerId = context.learnerId,
            sinceEpochMillis = now - MasteryWriteGate.LEARNER_WINDOW_MILLIS,
        )
        val acceptedInConversation = context.conversationId
            ?.let { port.countAcceptedChatEvidenceInConversation(it) }
            ?: 0

        // KC 锚定：terms[0] 必须命中真实知识节点（防模型臆测节点），且——当存在当前题
        // 科目上下文时（Respond 派遣）——目标节点必须属于该科目。写工具只允许落到当前
        // 教学上下文相关的知识点；游离/跨科目的 KC 写入一律视为未锚定拒写，防止模型在
        // 一个科目会话里把证据写进无关科目。Lobby/无科目时不强加科目匹配（但 repository
        // 层已保证写工具不会在 Lobby 派遣里到达这里）。
        val knowledgeNode = if (knowledgeNodeId.isBlank()) {
            null
        } else {
            port.readKnowledgeNodesByIds(setOf(knowledgeNodeId)).firstOrNull()
        }
        val anchored = knowledgeNode != null &&
            (context.subject == null || knowledgeNode.subject == context.subject)

        val input = MasteryWriteGate.GateInput(
            intentConfidence = 0.9, // 意图门已在 repository 层由 tutorToolAuthorization 把关
            evidenceConfidence = call.confidence,
            direction = direction,
            understanding = understanding,
            knowledgeNodeIsAnchored = anchored,
            // 讲题通道本地拿不到"学生懂了"的客观佐证（研究 §1：本地无可靠语义
            // 信号），故 MASTERED 的可核查性改为数模型 rationale 里逐字引用的
            // 证据锚条数（档2，spec 2026-09-06 §1；档1 prompt 规范同源）。
            hasObjectiveSupport = false,
            // 只数**引文真出现在本会话文本里**的锚：档1 规范要求"逐字引用学生
            // 原话"，仅数引号会让 `"因为""所以"` 这类编造凑够门槛。
            // **正向各档都消费这个值**（MASTERED ≥2，其余正向 ≥1，2026-09-13 的正向底线），
            // 因此不能只在 MASTERED 时核对——否则 CONFIDENT 会拿"引号数"冒充"已核实锚"，
            // 编造的引文照样本进库。负向不消费，省掉这次回读。
            evidenceAnchorCount = if (direction == TutorEvidenceDirection.POSITIVE) {
                MasteryWriteGate.verifiedEvidenceAnchorCount(
                    rationale = call.rationale,
                    verifiableText = verifiableSessionText(context),
                )
            } else {
                0
            },
            // 反向的客观核对（研究 tutor-evidence-gate §3.2）：学生在本轮答错过
            // 模型自己出的检查题时，模型再判 POSITIVE 就是口头声明压过行为证据。
            // 这与"有没有佐证"是两个方向——此处查的是"有没有反驳"。门只对
            // POSITIVE 消费它，故只在正向判断时才付这次回读的成本。
            objectiveAnswersContradictPositive =
                direction == TutorEvidenceDirection.POSITIVE &&
                    objectiveAnswersContradictPositive(context),
            sameKcLastWriteAgoMillis = sameKcLastWriteAgoMillis,
            writesThisConversation = acceptedInConversation,
            writesThisLearnerInWindow = acceptedInWindow,
            attentionFactor = context.attentionFactor,
        )
        when (val result = MasteryWriteGate.evaluate(input)) {
            is MasteryWriteGate.GateResult.Accepted -> {
                val entry = LearnerChatEvidenceEntity(
                    evidence_id = evidenceId,
                    learner_id = context.learnerId,
                    conversation_id = conversationId.orEmpty(),
                    knowledge_node_id = knowledgeNodeId,
                    direction = direction.name,
                    weight = result.weight,
                    reason_markdown = call.rationale,
                    confidence = input.evidenceConfidence,
                    source_kind = "MODEL_CHAT",
                    created_at_epoch_millis = now,
                )
                port.recordChatEvidence(listOf(entry))
                return TutorToolOutcome(
                    tool = TutorToolName.MASTERY_UPDATE,
                    ok = true,
                    summaryMarkdown = "学习证据已记录：${direction.name} weight=${result.weight}",
                )
            }
            is MasteryWriteGate.GateResult.Rejected -> {
                // 被拒 ≠ 删除：落 rejected 审计行（不进投影），outcome 返回拒因。
                val entry = LearnerChatEvidenceEntity(
                    evidence_id = evidenceId,
                    learner_id = context.learnerId,
                    conversation_id = conversationId.orEmpty(),
                    knowledge_node_id = knowledgeNodeId,
                    direction = direction.name,
                    weight = 0.0,
                    reason_markdown = call.rationale,
                    confidence = input.evidenceConfidence,
                    source_kind = "MODEL_CHAT",
                    created_at_epoch_millis = now,
                    rejected_reason = result.reason.name,
                    rejected_at_epoch_millis = now,
                )
                port.recordChatEvidence(listOf(entry))
                return TutorToolOutcome(
                    tool = TutorToolName.MASTERY_UPDATE,
                    ok = false,
                    summaryMarkdown = "这条学习证据未通过校验，未计入掌握度（${result.reason.name}）。",
                    errorKind = "rejected:${result.reason.name}",
                )
            }
        }
    }

    /**
     * 本轮学生客观作答有没有推翻正向判断（研究 `tutor-evidence-gate-research.md` §3.2）。
     *
     * 只数**当前轮**：`restartCycle` 会在同一题上开新一轮重教，上一轮的答错正是
     * 重教的理由；把历史轮次的答错永久计入，学生重教后答对也洗不掉，门就成了
     * 不可达的死门（与档2 修的 0.18 死常数同类）。
     *
     * 无会话上下文（Lobby 派遣 / 测试直调）时不强加核对——没有会话就没有客观作答
     * 可言。读失败会冒泡到 [run] 的 catch 变成 failed outcome，即写不进去，
     * 不会因此误放行。
     */
    private suspend fun objectiveAnswersContradictPositive(context: Context): Boolean {
        val sessionId = context.tutorSessionId?.takeIf(String::isNotBlank) ?: return false
        val correctness = port.observeTutorTurnResponses(sessionId)
            .first()
            .filter { it.cycleOrdinal == context.cycleOrdinal }
            .mapNotNull(TutorTurnResponseRecord::selectionWasCorrect)
        return tutorSessionObjectiveRecord(correctness).contradictsPositiveClaim
    }

    /**
     * 本会话里学生**确实产出过**的文本，供证据锚核对（[MasteryWriteGate.verifiedEvidenceAnchorCount]）。
     *
     * 两个来源，都是本地事实而非模型自报：
     * - 学生消息原文（`tutor_message` 的 STUDENT 行）；
     * - 学生的客观作答（本轮检查题所选选项文本），属于档1 规范里的"可观察行为"。
     *
     * 读失败或没有会话上下文时返回空串：核对函数对空语料返回 0 锚，于是
     * MASTERED 判断被拒——写不进去，不会因读失败而误放行。
     */
    private suspend fun verifiableSessionText(context: Context): String {
        val sessionId = context.tutorSessionId?.takeIf(String::isNotBlank)
        val conversationId = context.conversationId?.takeIf(String::isNotBlank)
        val studentMessages = conversationId
            ?.let { id ->
                port.observeTutorMessages(id)
                    .first()
                    .filter { it.role == STUDENT_MESSAGE_ROLE }
                    .map(TutorMessageRecord::bodyMarkdown)
            }
            .orEmpty()
        val objectiveAnswers = sessionId
            ?.let { id ->
                port.observeTutorTurnResponses(id)
                    .first()
                    .filter { it.cycleOrdinal == context.cycleOrdinal }
                    .mapNotNull(TutorTurnResponseRecord::selectedChoiceMarkdown)
            }
            .orEmpty()
        return (studentMessages + objectiveAnswers).joinToString("\n")
    }
}
