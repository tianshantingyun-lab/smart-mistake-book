package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

/**
 * 构造一次知识点复习出题的模型任务请求（spec dual-review-entry §3.3）。
 *
 * KNOWLEDGE_QUIZ 是"只围绕该知识点 + 其讲解材料 boundary 出选择题"的任务：输入由
 * 计划队列项（知识点/科目/掌握度）与该点的讲解材料（TutorTeachingReference，标题/正文/
 * 边界）拼装。egress manifest 只在外部 provider 时携带（least-disclosure：仅披露
 * SUBJECT_KNOWLEDGE_BASE + RELEVANT_LEARNING_EVIDENCE，禁止其余数据类）；本地执行不
 * 带 manifest。返回 null = 当前 provider 不支持该任务（调用方应把该点视为本次不可出题）。
 */
internal fun buildKnowledgeQuizRequest(
    provider: ProviderCapabilitySnapshot,
    requestId: String,
    entry: KnowledgeReviewQueueEntry,
    reference: TutorTeachingReference,
    occurredAtEpochMillis: Long,
): ModelTaskRequest? {
    require(requestId.isNotBlank()) { "Knowledge quiz request id must not be blank" }
    require(entry.knowledgeNodeId in reference.knowledgeNodeIds) {
        "Teaching reference must cover the reviewed knowledge node"
    }
    require(reference.subject == entry.subject) {
        "Teaching reference must stay within the reviewed node's subject"
    }
    if (!provider.supports(ModelTaskKind.KNOWLEDGE_QUIZ)) return null
    require(occurredAtEpochMillis >= 0) { "Knowledge quiz request time must not be negative" }

    val input = KnowledgeQuizInput(
        knowledgeNodeId = entry.knowledgeNodeId,
        subjectId = entry.subject,
        materialTitle = reference.title,
        materialContentMarkdown = reference.contentMarkdown,
        materialBoundaryMarkdown = reference.boundaryMarkdown,
        lastMasteryScore = entry.masteryScore,
        lastEvidenceAtEpochMillis = entry.lastEvidenceAtEpochMillis,
    )
    val manifest = when (provider.executionLocation) {
        ModelExecutionLocation.EXTERNAL_PROVIDER -> ModelEgressManifest(
            authorizationId = "authorization:$requestId",
            subjectId = input.subjectId,
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.KNOWLEDGE_QUIZ),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = ModelPromptPolicyVersions.KNOWLEDGE_QUIZ,
            approvedAtEpochMillis = occurredAtEpochMillis,
            assets = emptyList(),
            disclosedData = ModelEgressManifest.KNOWLEDGE_QUIZ_DISCLOSURE,
            prohibitedData = ModelEgressManifest.KNOWLEDGE_QUIZ_PROHIBITED_DATA,
        )
        ModelExecutionLocation.LOCAL_NO_EGRESS -> null
        ModelExecutionLocation.UNAVAILABLE -> error("Knowledge quiz provider is unavailable")
    }
    return ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = manifest,
    )
}
