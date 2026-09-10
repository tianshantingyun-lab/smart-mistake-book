package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.domain.KnowledgeQuizFeedbackResult
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
import com.tingyun.smartmistakebook.core.domain.knowledgeQuizMasteryVerdict

/**
 * Knowledge-quiz feedback writes: the objective verdict is gated by
 * [MasteryWriteGate] (node anchoring, per-conversation and per-learner
 * quotas) before it becomes chat evidence. Extracted from the study repository
 * so the anti-farming gate stays next to the write it guards.
 */
internal class KnowledgeQuizFeedbackWriter(
    private val database: StudyDatabasePort,
    private val learnerId: String,
) {
    suspend fun submit(
        requestId: String,
        knowledgeNodeId: String,
        correctChoiceId: String,
        selectedChoiceId: String,
        occurredAtEpochMillis: Long,
        conversationId: String,
    ): KnowledgeQuizFeedbackResult {
        val isCorrect = selectedChoiceId == correctChoiceId
        val verdict = knowledgeQuizMasteryVerdict(isCorrect)
        val now = occurredAtEpochMillis
        // 知识点锚定：节点必须真实存在于知识库，防止写入游离/臆造节点。
        val anchored = database.readKnowledgeNodesByIds(setOf(knowledgeNodeId)).isNotEmpty()
        val lastSameKcWrite = database.lastAcceptedChatEvidenceAtForKc(learnerId, knowledgeNodeId)
        val sameKcLastWriteAgoMillis = lastSameKcWrite?.let { (now - it).coerceAtLeast(0) }
        val acceptedInWindow = database.countAcceptedChatEvidenceSince(
            learnerId = learnerId,
            sinceEpochMillis = now - MasteryWriteGate.LEARNER_WINDOW_MILLIS,
        )
        // 知识点复习按"每次复习会话"计数防刷：调用方传入该次会话的 id（见
        // KnowledgeReviewSessionViewModel），否则固定 id 会把每会话配额变成终身配额，
        // 累计写满后永久拒写（审计 2026-09-09 P1）。
        require(conversationId.isNotBlank()) { "Knowledge quiz conversation id must not be blank" }
        val acceptedInConversation = database.countAcceptedChatEvidenceInConversation(conversationId)
        val input = MasteryWriteGate.GateInput(
            intentConfidence = 1.0, // 本地确定的客观作答，非模型意图路由
            evidenceConfidence = 1.0, // 客观对错，置信满
            direction = verdict.direction,
            understanding = verdict.understanding,
            knowledgeNodeIsAnchored = anchored,
            hasObjectiveSupport = verdict.hasBehavioralSupport,
            // 客观作答通道本来就有一份本地可核查的证据（答对），无需模型引用锚。
            evidenceAnchorCount = 0,
            sameKcLastWriteAgoMillis = sameKcLastWriteAgoMillis,
            writesThisConversation = acceptedInConversation,
            writesThisLearnerInWindow = acceptedInWindow,
            attentionFactor = 1.0,
        )
        return when (val result = MasteryWriteGate.evaluate(input)) {
            is MasteryWriteGate.GateResult.Accepted -> {
                database.recordChatEvidence(
                    listOf(
                        LearnerChatEvidenceEntity(
                            evidence_id = "knowledge-quiz:$requestId:$knowledgeNodeId",
                            learner_id = learnerId,
                            conversation_id = conversationId,
                            knowledge_node_id = knowledgeNodeId,
                            direction = verdict.direction.name,
                            weight = result.weight,
                            reason_markdown = "知识点复习作答（${if (isCorrect) "答对" else "答错"}）",
                            confidence = 1.0,
                            source_kind = "KNOWLEDGE_QUIZ",
                            created_at_epoch_millis = now,
                        ),
                    ),
                )
                KnowledgeQuizFeedbackResult(
                    isCorrect = isCorrect,
                    evidenceRecorded = true,
                )
            }
            is MasteryWriteGate.GateResult.Rejected -> KnowledgeQuizFeedbackResult(
                isCorrect = isCorrect,
                evidenceRecorded = false,
                rejectedReason = result.reason.name,
            )
        }
    }
}
