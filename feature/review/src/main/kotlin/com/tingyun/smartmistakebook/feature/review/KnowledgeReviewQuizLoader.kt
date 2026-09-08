package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizOutput
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.toTutorAssessmentItem

/**
 * 为知识点复习计划中的一个队列项现场出题（spec dual-review-entry §3.3/§3.4）。
 *
 * 一次出题 = 解析该知识点讲解材料 → 构造 KNOWLEDGE_QUIZ 请求（带 egress least-disclosure
 * manifest）→ 跑模型任务到终态 → 成功时把输出映射成可判答的 [TutorAssessmentItem]。任何
 * 一步不可用（provider 不支持/无材料/任务失败）都返回 null——调用方把它视为"本次不可
 * 出题"，由会话 UI 决定重试或跳过。
 *
 * 消灭的失败：知识点复习的"取题"逻辑散在 Composable 无法测试、各节点材料解析标准不一。
 */
class KnowledgeReviewQuizLoader(
    private val modelTasks: ModelTaskRepository,
    private val references: TutorTeachingReferenceRepository,
) {
    suspend fun loadQuiz(entry: KnowledgeReviewQueueEntry): TutorAssessmentItem? {
        val provider = runCatching { modelTasks.capabilities() }.getOrNull() ?: return null
        if (!provider.supports(com.tingyun.smartmistakebook.core.model.ModelTaskKind.KNOWLEDGE_QUIZ)) {
            return null
        }
        val teachingReferences = references.referencesFor(
            subject = entry.subject,
            knowledgeNodeIds = setOf(entry.knowledgeNodeId),
            limit = TutorTeachingReferenceRepository.DEFAULT_LIMIT,
        )
        val material = teachingReferences.firstOrNull { reference ->
            entry.knowledgeNodeId in reference.knowledgeNodeIds
        } ?: return null
        if (provider.executionLocation == ModelExecutionLocation.UNAVAILABLE) return null

        val requestId = "knowledge-quiz:review:${entry.knowledgeNodeId}:${java.util.UUID.randomUUID()}"
        val occurredAt = System.currentTimeMillis()
        val request = buildKnowledgeQuizRequest(
            provider = provider,
            requestId = requestId,
            entry = entry,
            reference = material,
            occurredAtEpochMillis = occurredAt,
        ) ?: return null

        var succeededOutput: KnowledgeQuizOutput? = null
        modelTasks.execute(request).collect { snapshot ->
            if (snapshot.status == ModelTaskStatus.SUCCEEDED) {
                succeededOutput = snapshot.output as? KnowledgeQuizOutput
            }
        }
        val output = succeededOutput ?: return null
        return output.toTutorAssessmentItem(
            knowledgeNodeId = entry.knowledgeNodeId,
            itemId = "knowledge-quiz:item:${entry.knowledgeNodeId}",
        )
    }
}
