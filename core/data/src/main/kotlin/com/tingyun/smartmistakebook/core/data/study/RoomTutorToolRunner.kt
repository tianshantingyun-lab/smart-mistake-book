package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.domain.CleanRedrawTool
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
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
internal class RoomTutorToolRunner(
    private val port: StudyDatabasePort,
    private val cleanRedrawTool: CleanRedrawTool? = null,
) {
    /** 观测面：工具环协议测试断言执行器确实被调用。 */
    var executedCallCount: Int = 0
        private set

    /** Student context the tools need; lobby sessions have no subject. */
    data class Context(
        val subject: String?,
        val learnerId: String = "learner:local",
        val conversationId: String? = null,
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
         * Current tutor session id — FIGURE_REDRAW resolves the session's source
         * asset through it. Null when the round has no captured session.
         */
        val sessionId: String? = null,
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
            TutorToolName.NOTEBOOK_WRITE -> TutorToolOutcome(
                tool = call.tool,
                ok = false,
                summaryMarkdown = "错题库写入需要学生确认。",
                errorKind = "confirmation_required",
            )
            TutorToolName.FIGURE_REDRAW -> figureRedraw(call, context)
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


    private suspend fun figureRedraw(call: TutorToolCall, context: Context): TutorToolOutcome {
        val tool = cleanRedrawTool
            ?: return TutorToolOutcome(
                tool = TutorToolName.FIGURE_REDRAW,
                ok = false,
                summaryMarkdown = "当前没有可用的题图重绘能力。",
                errorKind = "no_redraw_tool",
            )
        val requestedAssetId = call.sourceAssetId
        if (requestedAssetId.isNullOrBlank()) {
            return TutorToolOutcome(
                tool = TutorToolName.FIGURE_REDRAW,
                ok = false,
                summaryMarkdown = "重绘请求缺少要处理的题图。",
                errorKind = "no_source_asset",
            )
        }
        // 只允许重绘当前会话自己的源图：请求的 asset 必须命中会话的 source asset。
        val session = context.sessionId?.let { sessionId ->
            port.readTutorSession(sessionId)
        }
        val sessionAssetId = session?.sourceAsset?.sourceAssetId
        if (sessionAssetId == null || sessionAssetId != requestedAssetId) {
            return TutorToolOutcome(
                tool = TutorToolName.FIGURE_REDRAW,
                ok = false,
                summaryMarkdown = "这张题图不属于当前会话，已拒绝重绘。",
                errorKind = "asset_mismatch",
            )
        }
        val clean = tool.redrawCleanImage(requestedAssetId)
            ?: return TutorToolOutcome(
                tool = TutorToolName.FIGURE_REDRAW,
                ok = false,
                summaryMarkdown = "重绘没有完成（当前模型未启用图生图，或这张图无法处理）。",
                errorKind = "declined",
            )
        return TutorToolOutcome(
            tool = TutorToolName.FIGURE_REDRAW,
            ok = true,
            summaryMarkdown = "已生成干净题面（将随题目保存展示）。",
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

        // 会话证据（冷却/配额/行为佐证的读源）。
        val conversationId = context.conversationId
        val conversationEvidence = if (conversationId.isNullOrBlank()) {
            emptyList()
        } else {
            port.readChatEvidenceByConversation(conversationId)
        }
        // 行为佐证：客观作答信号由 repository 的 attempt 事件承载——runner
        // 拿不到时保守为 false，MASTERED 高置信档因此要求显式行为通道（见 gate）。
        // 冷却：同 KC 最近一次被接受写入距今。
        val acceptedEvidence = conversationEvidence.filter { !it.isRejected }
        val lastSameKcWrite = acceptedEvidence
            .filter { it.knowledge_node_id == knowledgeNodeId }
            .maxOfOrNull { it.created_at_epoch_millis }
        val sameKcLastWriteAgoMillis = lastSameKcWrite?.let { (now - it).coerceAtLeast(0) }

        // KC 锚定：terms[0] 必须命中真实知识节点（防模型臆测节点）。
        val anchored = knowledgeNodeId.isNotBlank() &&
            port.readKnowledgeNodesByIds(setOf(knowledgeNodeId)).isNotEmpty()

        val input = MasteryWriteGate.GateInput(
            intentConfidence = 0.9, // 意图门已在 repository 层由 tutorToolAuthorization 把关
            evidenceConfidence = call.confidence,
            direction = direction,
            understanding = understanding,
            knowledgeNodeIsAnchored = anchored,
            hasBehavioralSupport = false,
            sameKcLastWriteAgoMillis = sameKcLastWriteAgoMillis,
            writesThisConversation = acceptedEvidence.size,
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
