package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CommitTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
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
    ) {
        init {
            require(attentionFactor in 0.0..1.0) { "Attention factor must be in 0..1" }
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
            TutorToolName.MASTERY_READ -> masteryRead(context.learnerId)
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

    private suspend fun masteryRead(learnerId: String): TutorToolOutcome {
        val lattice = port.observeKnowledgeQuestionLattice(learnerId).first().take(24)
        if (lattice.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.MASTERY_READ,
                ok = true,
                summaryMarkdown = "还没有足够的学习记录来评估掌握情况。",
            )
        }
        val mastered = lattice.count { it.kcStatus == "MASTERED" }
        val learning = lattice.count { it.kcStatus == "LEARNING" }
        val conflicted = lattice.count { it.kcStatus == "CONFLICTED" }
        val weakest = lattice
            .filter { it.kcConservativeMastery != null }
            .sortedBy { it.kcConservativeMastery ?: 1.0 }
            .take(5)
            .mapIndexed { index, row ->
                "${index + 1}. 掌握度 ${"%.2f".format(row.kcConservativeMastery)}：${row.practiceUnitId}"
            }
        return TutorToolOutcome(
            tool = TutorToolName.MASTERY_READ,
            ok = true,
            summaryMarkdown = buildString {
                append("学习记录覆盖 ${lattice.size} 条，其中已掌握 $mastered、学习中 $learning、冲突 $conflicted。")
                if (weakest.isNotEmpty()) {
                    append("\n最薄弱：\n${weakest.joinToString("\n")}")
                }
            },
        )
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
        // 幂等 evidence_id：同 request 命名空间内同工具+知识点映射同 id——
        // 重试不重复落库（Room IGNORE 兜底，见 masteryUpdateEvidenceId）。
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
            evidenceAnchorCount = MasteryWriteGate.evidenceAnchorCount(call.rationale),
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
}
